package io.tokenpilot.client;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import io.tokenpilot.client.internal.BatchEncoder;
import io.tokenpilot.client.internal.BatchSender;
import io.tokenpilot.client.internal.BatchSender.ItemResult;
import io.tokenpilot.client.internal.BatchSender.Outcome;

/**
 * Sends usage events to the Control Plane in the background (Observe mode).
 *
 * <p>{@link #record} only puts the event in a bounded queue: it never blocks on the network and never throws
 * because of delivery, so a slow or unavailable Control Plane cannot fail or slow down the LLM call being
 * recorded. A single sender thread drains the queue in batches; retryable failures are resent with exponential
 * backoff up to {@link TokenPilotClientConfig#maxAttempts()}, and every event ends up delivered or counted as
 * dropped ({@link #stats()}, {@link DeliveryListener}).
 *
 * <p>Retries resend the same immutable events, so the server deduplicates them by {@code eventId}.
 */
public final class TokenPilotClient implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(TokenPilotClient.class.getName());

    private final TokenPilotClientConfig config;
    private final BatchEncoder encoder;
    private final BatchSender sender;
    private final DeliveryListener listener;
    private final BlockingQueue<UsageEvent> queue;
    private final ScheduledExecutorService executor;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private volatile long shutdownDeadlineNanos = Long.MAX_VALUE;

    /** Recorded events not yet delivered or dropped, including the batch in flight. */
    private final AtomicLong pending = new AtomicLong();
    private final Object pendingMonitor = new Object();

    private final LongAdder recorded = new LongAdder();
    private final LongAdder created = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder retried = new LongAdder();
    private final Map<DropReason, LongAdder> dropped = new EnumMap<>(DropReason.class);

    private TokenPilotClient(TokenPilotClientConfig config) {
        this.config = config;
        this.encoder = new BatchEncoder(config.projectKey(), config.environment());
        this.sender = new BatchSender(config.endpoint(), config.apiKey(), config.connectTimeout(),
            config.requestTimeout());
        this.listener = config.deliveryListener();
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
        for (DropReason reason : DropReason.values()) {
            dropped.put(reason, new LongAdder());
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "token-pilot-client-sender");
            thread.setDaemon(true);
            return thread;
        });
        long interval = config.flushInterval().toNanos();
        executor.scheduleWithFixedDelay(this::drain, interval, interval, TimeUnit.NANOSECONDS);
    }

    public static TokenPilotClient create(TokenPilotClientConfig config) {
        return new TokenPilotClient(Objects.requireNonNull(config, "config must not be null"));
    }

    /**
     * Queues the event for delivery. Returns {@code false}, and reports the drop, when the queue is full or the
     * client is closed. Never blocks.
     */
    public boolean record(UsageEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (closed.get()) {
            drop(event, DropReason.CLOSED, "client is closed");
            return false;
        }
        pending.incrementAndGet();
        if (!queue.offer(event)) {
            settle(1);
            drop(event, DropReason.QUEUE_FULL, "queue capacity " + config.queueCapacity() + " reached");
            return false;
        }
        recorded.increment();
        if (queue.size() >= config.maxBatchSize()) {
            scheduleDrain();
        }
        return true;
    }

    /**
     * Sends what is queued now and waits until every recorded event is delivered or dropped, or the timeout
     * passes. Returns whether everything settled in time.
     */
    public boolean flush(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        scheduleDrain();
        synchronized (pendingMonitor) {
            while (pending.get() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(pendingMonitor, remaining);
            }
        }
        return true;
    }

    public ClientStats stats() {
        Map<DropReason, Long> drops = new EnumMap<>(DropReason.class);
        dropped.forEach((reason, count) -> drops.put(reason, count.sum()));
        return new ClientStats(recorded.sum(), created.sum(), duplicates.sum(), retried.sum(), drops,
            pending.get());
    }

    /**
     * Stops accepting events and keeps delivering what is queued for up to
     * {@link TokenPilotClientConfig#shutdownTimeout()}; whatever is left after that is dropped as
     * {@link DropReason#SHUTDOWN_TIMEOUT}.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        long timeout = config.shutdownTimeout().toNanos();
        shutdownDeadlineNanos = System.nanoTime() + timeout;
        executor.execute(this::drain);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout + TimeUnit.MILLISECONDS.toNanos(200), TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        List<UsageEvent> left = new ArrayList<>();
        queue.drainTo(left);
        dropAll(left, DropReason.SHUTDOWN_TIMEOUT, "client closed before delivery");
    }

    private void scheduleDrain() {
        if (!executor.isShutdown() && drainScheduled.compareAndSet(false, true)) {
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException exception) {
                drainScheduled.set(false);
            }
        }
    }

    /** Runs on the sender thread only. */
    private void drain() {
        drainScheduled.set(false);
        try {
            List<UsageEvent> batch = new ArrayList<>(config.maxBatchSize());
            while (queue.drainTo(batch, config.maxBatchSize()) > 0) {
                deliver(batch);
                batch = new ArrayList<>(config.maxBatchSize());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            // Keep the scheduled task alive; an unexpected bug must not stop delivery for good.
            LOG.log(Level.ERROR, "Token Pilot client sender failed unexpectedly", exception);
        }
    }

    private void deliver(List<UsageEvent> batch) throws InterruptedException {
        byte[] body = encoder.encode(batch);
        if (body.length > config.maxBatchBytes()) {
            if (batch.size() == 1) {
                dropAll(batch, DropReason.PAYLOAD_TOO_LARGE, body.length + " bytes");
                return;
            }
            deliverHalves(batch);
            return;
        }
        sendWithRetry(batch, body);
    }

    private void deliverHalves(List<UsageEvent> batch) throws InterruptedException {
        int middle = batch.size() / 2;
        List<UsageEvent> second = new ArrayList<>(batch.subList(middle, batch.size()));
        try {
            deliver(new ArrayList<>(batch.subList(0, middle)));
        } catch (InterruptedException | RuntimeException exception) {
            abandon(second, exception);
            throw exception;
        }
        deliver(second);
    }

    /** Settles events that will never be sent because the sender was interrupted (close) or hit a bug. */
    private void abandon(List<UsageEvent> events, Exception cause) {
        if (cause instanceof InterruptedException) {
            dropAll(events, DropReason.SHUTDOWN_TIMEOUT, "client closed while sending");
        } else {
            dropAll(events, DropReason.REQUEST_REFUSED, "client error: " + cause.getClass().getSimpleName());
        }
    }

    private void sendWithRetry(List<UsageEvent> batch, byte[] body) throws InterruptedException {
        // Events of this batch not yet delivered, dropped, or handed to deliverHalves (which settles its own).
        List<UsageEvent> remaining = batch;
        try {
            byte[] payload = body;
            for (int attempt = 1; ; attempt++) {
                Outcome outcome = sender.send(payload, remaining.size());
                String retryReason;

                if (outcome instanceof Outcome.Items items) {
                    List<UsageEvent> again = settleItems(remaining, items.items());
                    remaining = again;
                    if (again.isEmpty()) {
                        return;
                    }
                    retryReason = "server asked to retry " + again.size() + " item(s)";
                } else if (outcome instanceof Outcome.TooLarge) {
                    List<UsageEvent> tooLarge = remaining;
                    remaining = List.of();
                    if (tooLarge.size() == 1) {
                        dropAll(tooLarge, DropReason.PAYLOAD_TOO_LARGE, "HTTP 413");
                    } else {
                        deliverHalves(tooLarge);
                    }
                    return;
                } else if (outcome instanceof Outcome.Refused refused) {
                    String detail = "HTTP " + refused.status()
                        + (refused.code() == null ? "" : " " + refused.code());
                    LOG.log(Level.WARNING, "Control Plane refused a usage batch ({0}); check the API key, project"
                        + " and environment. {1} event(s) dropped.", detail, remaining.size());
                    List<UsageEvent> refusedEvents = remaining;
                    remaining = List.of();
                    dropAll(refusedEvents, DropReason.REQUEST_REFUSED, detail);
                    return;
                } else {
                    retryReason = ((Outcome.Retry) outcome).reason();
                }

                if (attempt >= config.maxAttempts()) {
                    LOG.log(Level.WARNING, "Giving up on {0} usage event(s) after {1} attempts ({2})",
                        remaining.size(), attempt, retryReason);
                    List<UsageEvent> exhausted = remaining;
                    remaining = List.of();
                    dropAll(exhausted, DropReason.RETRIES_EXHAUSTED, retryReason);
                    return;
                }
                retried.add(remaining.size());
                notifyRetry(remaining, attempt, retryReason);
                if (!sleepBackoff(attempt)) {
                    List<UsageEvent> unsent = remaining;
                    remaining = List.of();
                    dropAll(unsent, DropReason.SHUTDOWN_TIMEOUT, "client closed while retrying");
                    return;
                }
                if (remaining != batch) {
                    payload = encoder.encode(remaining);
                }
            }
        } catch (InterruptedException | RuntimeException exception) {
            abandon(remaining, exception);
            throw exception;
        }
    }

    /** Settles delivered and permanently rejected items; returns the ones to send again. */
    private List<UsageEvent> settleItems(List<UsageEvent> events, List<ItemResult> results) {
        List<UsageEvent> again = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            UsageEvent event = events.get(i);
            ItemResult result = results.get(i);
            switch (result.status()) {
                case CREATED -> delivered(event, false);
                case DUPLICATE -> delivered(event, true);
                case REJECTED -> {
                    if (result.retryable()) {
                        again.add(event);
                    } else {
                        dropAll(List.of(event), DropReason.REJECTED, result.code());
                    }
                }
            }
        }
        return again;
    }

    /** Full-jitter exponential backoff, cut short by close(). Returns false when the shutdown deadline passed. */
    private boolean sleepBackoff(int attempt) throws InterruptedException {
        long base = config.initialBackoff().toNanos() << Math.min(attempt - 1, 20);
        long capped = Math.min(Math.max(base, 0), config.maxBackoff().toNanos());
        long delay = capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        long remaining = shutdownDeadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return false;
        }
        TimeUnit.NANOSECONDS.sleep(Math.min(delay, remaining));
        return shutdownDeadlineNanos - System.nanoTime() > 0;
    }

    private void delivered(UsageEvent event, boolean duplicate) {
        (duplicate ? duplicates : created).increment();
        settle(1);
        try {
            listener.onDelivered(event, duplicate);
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "DeliveryListener.onDelivered failed", exception);
        }
    }

    private void dropAll(List<UsageEvent> events, DropReason reason, String detail) {
        if (events.isEmpty()) {
            return;
        }
        settle(events.size());
        for (UsageEvent event : events) {
            drop(event, reason, detail);
        }
    }

    private void drop(UsageEvent event, DropReason reason, String detail) {
        dropped.get(reason).increment();
        try {
            listener.onDropped(event, reason, detail);
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "DeliveryListener.onDropped failed", exception);
        }
    }

    private void notifyRetry(List<UsageEvent> events, int attempt, String reason) {
        try {
            listener.onRetry(List.copyOf(events), attempt, reason);
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "DeliveryListener.onRetry failed", exception);
        }
    }

    private void settle(int count) {
        if (pending.addAndGet(-count) <= 0) {
            synchronized (pendingMonitor) {
                pendingMonitor.notifyAll();
            }
        }
    }
}
