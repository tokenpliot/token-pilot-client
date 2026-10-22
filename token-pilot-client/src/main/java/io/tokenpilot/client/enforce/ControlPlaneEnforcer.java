package io.tokenpilot.client.enforce;

import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.tokenpilot.client.enforce.ControlPlaneApi.SettlementReply;

/**
 * Guards LLM provider calls with a Control Plane decision (ADR 0001).
 *
 * <p>{@link #execute} asks for a decision before every call, in every mode (OBSERVE decisions are recorded for
 * audit and shadow comparison), turns it into an action with the call site's {@link EnforcementSettings}, and
 * only then calls the provider. Only an ENFORCE setting can stop a call: a policy denial in the enforced scope, a
 * required approval, or, when the customer chose FAIL_CLOSED, an undecidable request or an unavailable Control
 * Plane. No HTTP error or transport failure is ever treated as a policy denial, and the decision call is bounded
 * by {@link EnforcerConfig#decisionTimeout()}.
 *
 * <p>A reservation issued with the decision is always settled, whatever the mode, so budget is never held
 * forever by this client: COMMIT with priced usage, RELEASE when the provider was never reached, or
 * RECONCILIATION_REQUIRED when the call may have cost money but its cost is unknown. Settlement runs on a
 * background thread and never fails or delays the returned call.
 */
public final class ControlPlaneEnforcer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ControlPlaneEnforcer.class.getName());

    private static final String CLOSED_BEFORE_SETTLEMENT = "closed before settlement";
    /** Error code of the client-side INDETERMINATE decision that refuses streaming in an ENFORCE scope. */
    public static final String STREAMING_NOT_ENFORCEABLE = "STREAMING_NOT_ENFORCEABLE";

    private final EnforcerConfig config;
    private final ControlPlaneApi api;
    private final EnforcementListener listener;
    private final ThreadPoolExecutor settlementExecutor;

    private final AtomicBoolean closed = new AtomicBoolean();
    /** Set once close() has started; only then is {@link #shutdownDeadlineNanos} meaningful. */
    private volatile boolean shuttingDown;
    private volatile long shutdownDeadlineNanos;

    private ControlPlaneEnforcer(EnforcerConfig config) {
        this.config = config;
        this.api = new ControlPlaneApi(config);
        this.listener = config.listener();
        // One thread keeps settlements of a reservation in order; the bounded queue keeps memory bounded when
        // the Control Plane is down for long.
        this.settlementExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(config.settlementQueueCapacity()), runnable -> {
                Thread thread = new Thread(runnable, "token-pilot-enforcer-settlement");
                thread.setDaemon(true);
                return thread;
            });
    }

    public static ControlPlaneEnforcer create(EnforcerConfig config) {
        return new ControlPlaneEnforcer(Objects.requireNonNull(config, "config must not be null"));
    }

    /**
     * Decides, enforces and calls the provider.
     *
     * @return the provider response with the decision and the action taken
     * @throws EnforcementException       when the action is BLOCK or HOLD_FOR_APPROVAL; the provider was not called
     * @throws CallNotDispatchedException rethrown from the provider call
     * @throws Exception                  whatever the provider call threw, unchanged
     * @throws IllegalStateException      after {@link #close()}
     */
    public <T> EnforcedResult<T> execute(CallRequest request, ProviderCall<T> call, UsageReader<T> reader)
        throws Exception {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(reader, "reader must not be null");
        if (closed.get()) {
            throw new IllegalStateException("enforcer is closed");
        }

        EnforcementSettings settings = config.policy().settingsFor(request.endpoint());
        Decision decision = api.decide(request, settings.mode());
        EnforcementAction action = settings.actionFor(decision.outcome());
        if (!decision.evaluated()) {
            LOG.log(Level.WARNING, "No Control Plane judgment (HTTP {0}, {1}); treated as {2}, action {3}",
                decision.httpStatus(), decision.errorCode(), decision.outcome(), action);
        }
        notifyDecision(request, decision, action);

        Reservation reservation = decision.reservation();
        if (action.stopsProviderCall()) {
            if (reservation != null) {
                // Not expected (reservations come with ALLOW), but a held reservation would block budget.
                schedule(request, reservation, SettlementType.RELEASE, null);
            }
            throw new EnforcementException(decision, action);
        }

        T response;
        try {
            response = call.call();
        } catch (CallNotDispatchedException notDispatched) {
            if (reservation != null) {
                schedule(request, reservation, SettlementType.RELEASE, null);
            }
            throw notDispatched;
        } catch (Throwable failure) {
            if (reservation != null) {
                // The provider may have processed and billed the call; keep the reserved liability until the
                // server reconciles it rather than zeroing it.
                schedule(request, reservation, SettlementType.RECONCILIATION_REQUIRED, null);
            }
            throw failure;
        }

        if (reservation != null) {
            ActualUsage usage = readUsage(reader, response);
            if (usage != null && usage.priced()) {
                schedule(request, reservation, SettlementType.COMMIT, usage);
            } else {
                schedule(request, reservation, SettlementType.RECONCILIATION_REQUIRED, usage);
            }
        }
        return new EnforcedResult<>(response, decision, action);
    }

    /**
     * Gate for streaming calls, which this client cannot settle (a stream can end anywhere, with or without
     * usage). In an ENFORCE scope the call is refused with {@link EnforcementException}: an opted-in customer
     * must never be left unprotected without noticing. The refusal is an {@code INDETERMINATE} decision with
     * error code {@link #STREAMING_NOT_ENFORCEABLE}, never a policy denial, and no request is sent. OBSERVE and
     * ADVISORY scopes pass without a decision, since neither would stop the call.
     *
     * @throws EnforcementException  in an ENFORCE scope
     * @throws IllegalStateException after {@link #close()}
     */
    public void checkStreaming(CallRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (closed.get()) {
            throw new IllegalStateException("enforcer is closed");
        }
        if (config.policy().settingsFor(request.endpoint()).mode() != EnforcementMode.ENFORCE) {
            return;
        }
        Decision decision = Decision.failure(request.requestId(), DecisionOutcome.INDETERMINATE, 0,
            STREAMING_NOT_ENFORCEABLE, null);
        notifyDecision(request, decision, EnforcementAction.BLOCK);
        throw new EnforcementException(decision, EnforcementAction.BLOCK);
    }

    /**
     * Stops accepting calls and keeps delivering queued settlements for up to
     * {@link EnforcerConfig#shutdownTimeout()}; whatever is left after that is reported through
     * {@link EnforcementListener#onSettlementFailed} as {@code closed before settlement}.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        long timeout = config.shutdownTimeout().toNanos();
        shutdownDeadlineNanos = System.nanoTime() + timeout;
        shuttingDown = true;
        settlementExecutor.shutdown();
        try {
            if (!settlementExecutor.awaitTermination(timeout + TimeUnit.MILLISECONDS.toNanos(200),
                TimeUnit.NANOSECONDS)) {
                abandon(settlementExecutor.shutdownNow());
                settlementExecutor.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            abandon(settlementExecutor.shutdownNow());
            Thread.currentThread().interrupt();
        }
    }

    private static <T> ActualUsage readUsage(UsageReader<T> reader, T response) {
        try {
            return reader.read(response);
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "UsageReader failed ({0}); settling for reconciliation",
                exception.getClass().getSimpleName());
            return null;
        }
    }

    private void schedule(CallRequest request, Reservation reservation, SettlementType type, ActualUsage usage) {
        Instant occurredAt = config.clock().instant();
        Settlement settlement = new Settlement(reservation.reservationId(), type,
            ControlPlaneApi.settlementKey(reservation.reservationId(), type),
            ControlPlaneApi.settlementBody(request.requestId(), type, usage, occurredAt));
        try {
            settlementExecutor.execute(settlement);
        } catch (RejectedExecutionException exception) {
            notifyFailed(settlement, settlementExecutor.isShutdown() ? CLOSED_BEFORE_SETTLEMENT : "queue full");
        }
    }

    private void abandon(List<Runnable> unstarted) {
        for (Runnable runnable : unstarted) {
            if (runnable instanceof Settlement settlement) {
                notifyFailed(settlement, CLOSED_BEFORE_SETTLEMENT);
            }
        }
    }

    /** One reservation settlement; runs on the settlement thread only. */
    private final class Settlement implements Runnable {

        private final String reservationId;
        private final SettlementType type;
        private final String idempotencyKey;
        private final byte[] body;

        Settlement(String reservationId, SettlementType type, String idempotencyKey, byte[] body) {
            this.reservationId = reservationId;
            this.type = type;
            this.idempotencyKey = idempotencyKey;
            this.body = body;
        }

        @Override
        public void run() {
            try {
                deliver();
            } catch (InterruptedException exception) {
                notifyFailed(this, CLOSED_BEFORE_SETTLEMENT);
                Thread.currentThread().interrupt();
            } catch (RuntimeException exception) {
                // Keep the settlement thread alive; one bug must not stop every later settlement.
                LOG.log(Level.ERROR, "Token Pilot settlement failed unexpectedly", exception);
                notifyFailed(this, "client error: " + exception.getClass().getSimpleName());
            }
        }

        private void deliver() throws InterruptedException {
            for (int attempt = 1; ; attempt++) {
                SettlementReply reply = api.settle(reservationId, idempotencyKey, body);
                if (reply instanceof SettlementReply.Settled settled) {
                    notifySettled(this, settled.state(), settled.duplicate());
                    return;
                }
                if (reply instanceof SettlementReply.Refused refused) {
                    String detail = "HTTP " + refused.status() + (refused.code() == null ? "" : " " + refused.code());
                    LOG.log(Level.WARNING, "Control Plane refused the {0} settlement ({1})", type, detail);
                    notifyFailed(this, detail);
                    return;
                }
                String reason = ((SettlementReply.Retry) reply).reason();
                if (attempt >= config.settlementMaxAttempts()) {
                    LOG.log(Level.WARNING, "Giving up on a {0} settlement after {1} attempts ({2})",
                        type, attempt, reason);
                    notifyFailed(this, "retries exhausted: " + reason);
                    return;
                }
                notifyRetry(this, attempt, reason);
                if (!sleepBackoff(attempt)) {
                    notifyFailed(this, CLOSED_BEFORE_SETTLEMENT);
                    return;
                }
            }
        }
    }

    /** Full-jitter exponential backoff, cut short by close(). Returns false when the shutdown deadline passed. */
    private boolean sleepBackoff(int attempt) throws InterruptedException {
        long base = config.initialBackoff().toNanos() << Math.min(attempt - 1, 20);
        long capped = Math.min(Math.max(base, 0), config.maxBackoff().toNanos());
        long delay = capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        if (!shuttingDown) {
            TimeUnit.NANOSECONDS.sleep(delay);
            return true;
        }
        // nanoTime values may be negative, so compare differences, never against a sentinel.
        long remaining = shutdownDeadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return false;
        }
        TimeUnit.NANOSECONDS.sleep(Math.min(delay, remaining));
        return shutdownDeadlineNanos - System.nanoTime() > 0;
    }

    // Listener calls are defensive: a failing listener must not break the guarded call or the settlement thread.

    private void notifyDecision(CallRequest request, Decision decision, EnforcementAction action) {
        try {
            listener.onDecision(request, decision, action);
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "EnforcementListener.onDecision failed", exception);
        }
    }

    private void notifySettled(Settlement settlement, String state, boolean duplicate) {
        try {
            listener.onSettled(settlement.reservationId, settlement.type, state, duplicate);
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "EnforcementListener.onSettled failed", exception);
        }
    }

    private void notifyRetry(Settlement settlement, int attempt, String reason) {
        try {
            listener.onSettlementRetry(settlement.reservationId, settlement.type, attempt, reason);
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "EnforcementListener.onSettlementRetry failed", exception);
        }
    }

    private void notifyFailed(Settlement settlement, String detail) {
        try {
            listener.onSettlementFailed(settlement.reservationId, settlement.type, detail);
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "EnforcementListener.onSettlementFailed failed", exception);
        }
    }
}
