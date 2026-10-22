package io.tokenpilot.client.enforce;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.tokenpilot.client.internal.Json;

/**
 * In-process stand-in for the Control Plane decision and settlement endpoints of {@code control-plane-v1.yaml}.
 * Records every request (path, headers, raw body) and answers with per-endpoint {@link Responder}s.
 */
final class FakeDecisionServer implements AutoCloseable {

    /** Nothing listens on port 1, so connections are refused at once. */
    static final URI UNREACHABLE = URI.create("http://127.0.0.1:1");

    static final String DECISIONS = "/api/control/v1/decisions";

    record Request(String path, Map<String, String> headers, String body, Map<String, Object> json) {

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    record Reply(int status, String body, Map<String, String> headers) {

        Reply(int status, String body) {
            this(status, body, Map.of());
        }
    }

    @FunctionalInterface
    interface Responder {
        Reply reply(Request request, int requestNumber) throws Exception;
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Request> decisionRequests = new CopyOnWriteArrayList<>();
    private final List<Request> settlementRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger decisionCounter = new AtomicInteger();
    private final AtomicInteger settlementCounter = new AtomicInteger();
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile Responder decisions = decides("ALLOW");
    private volatile Responder settlements = settling();

    private FakeDecisionServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    static FakeDecisionServer start() throws IOException {
        return new FakeDecisionServer();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    FakeDecisionServer decisions(Responder responder) {
        this.decisions = responder;
        return this;
    }

    FakeDecisionServer settlements(Responder responder) {
        this.settlements = responder;
        return this;
    }

    List<Request> decisionRequests() {
        return List.copyOf(decisionRequests);
    }

    List<Request> settlementRequests() {
        return List.copyOf(settlementRequests);
    }

    /** For responders that hang: blocks until {@link #close()}. */
    void awaitRelease() throws InterruptedException {
        released.await(30, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
            @SuppressWarnings("unchecked")
            Map<String, Object> json = (Map<String, Object>) Json.parse(body);
            String path = exchange.getRequestURI().getRawPath();
            Request request = new Request(path, Map.copyOf(headers), body, json);

            Reply reply;
            try {
                if (DECISIONS.equals(path)) {
                    decisionRequests.add(request);
                    reply = decisions.reply(request, decisionCounter.incrementAndGet());
                } else if (path.startsWith("/api/control/v1/reservations/") && path.endsWith("/settlements")) {
                    settlementRequests.add(request);
                    reply = settlements.reply(request, settlementCounter.incrementAndGet());
                } else {
                    reply = error(404, "NOT_FOUND", "INDETERMINATE");
                }
            } catch (InterruptedException exception) {
                return;
            } catch (Exception exception) {
                reply = new Reply(500, "{\"code\":\"INTERNAL\"}");
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        }
    }

    // --- canned replies ---

    /**
     * A 200 judgment for the request's requestId. {@code extraFields} are JSON members appended to {@code data},
     * e.g. {@link #reservation}, {@link #reasons}, {@link #approval}.
     */
    static Responder decides(String outcome, String... extraFields) {
        return (request, number) -> decision(request, number, outcome, extraFields);
    }

    static Reply decision(Request request, int number, String outcome, String... extraFields) {
        StringBuilder data = new StringBuilder("{\"schemaVersion\":\"2026-10-01\",\"decisionId\":\"dec-" + number
            + "\",\"requestId\":\"" + request.json().get("requestId") + "\",\"outcome\":\"" + outcome
            + "\",\"effectiveMode\":\"" + request.json().get("clientMode")
            + "\",\"policyVersion\":\"pol-v3\",\"evaluatedAt\":\"2026-10-02T12:00:00Z\"");
        boolean hasReasons = false;
        for (String field : extraFields) {
            data.append(',').append(field);
            hasReasons |= field.startsWith("\"reasons\"");
        }
        if (!hasReasons) {
            data.append(",\"reasons\":[]");
        }
        data.append('}');
        return ok(data.toString());
    }

    static String reservation(String reservationId) {
        return "\"reservation\":{\"reservationId\":\"" + reservationId + "\",\"state\":\"RESERVED\","
            + "\"reservedCostUsd\":\"0.017500\",\"expiresAt\":\"2026-10-02T12:05:01Z\"}";
    }

    static String reasons(String... codes) {
        StringBuilder out = new StringBuilder("\"reasons\":[");
        for (int i = 0; i < codes.length; i++) {
            out.append(i == 0 ? "" : ",").append("{\"code\":\"").append(codes[i])
                .append("\",\"message\":\"reason text\"}");
        }
        return out.append(']').toString();
    }

    static String approval(String approvalId) {
        return "\"approvalId\":\"" + approvalId + "\"";
    }

    /** Applies every settlement like a fresh reservation would: COMMITTED, RELEASED or RECONCILIATION_REQUIRED. */
    static Responder settling() {
        return (request, number) -> settled(stateFor(request), false);
    }

    static String stateFor(Request request) {
        return switch ((String) request.json().get("result")) {
            case "COMMIT" -> "COMMITTED";
            case "RELEASE" -> "RELEASED";
            default -> "RECONCILIATION_REQUIRED";
        };
    }

    static Reply settled(String state, boolean duplicate) {
        return ok("{\"schemaVersion\":\"2026-10-01\",\"reservationId\":\"rsv\",\"requestId\":\"req\","
            + "\"previousState\":\"IN_FLIGHT\",\"state\":\"" + state + "\",\"duplicate\":" + duplicate + "}");
    }

    static Reply ok(String data) {
        return new Reply(200, "{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"ok\",\"data\":" + data
            + ",\"errors\":[],\"timestamp\":\"2026-10-02T21:00:00\"}");
    }

    /** ErrorEnvelope; {@code dataOutcome} is what the body claims, which the client must not trust. */
    static Reply error(int status, String code, String dataOutcome) {
        return new Reply(status, "{\"success\":false,\"code\":\"" + code + "\",\"message\":\"x\",\"data\":"
            + "{\"outcome\":\"" + dataOutcome + "\"},\"errors\":[],\"timestamp\":\"2026-10-02T21:00:10\"}");
    }
}
