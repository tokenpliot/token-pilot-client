package io.tokenpilot.client.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.tokenpilot.client.internal.Json;

/**
 * In-process stand-in for {@code POST /api/ingestion/events/batch}. Each request is answered by a
 * {@link Responder}; {@link #deduplicating()} behaves like the real server's idempotency (same eventId and
 * payload: DUPLICATE, changed payload: REJECTED INGESTION-409).
 */
public final class FakeControlPlane implements AutoCloseable {

    /**
     * A Control Plane that is down: nothing listens on port 1. A port taken from {@code new ServerSocket(0)} and
     * released would be an ephemeral port another process could bind before the test connects.
     */
    public static final URI UNREACHABLE = URI.create("http://127.0.0.1:1");

    public record Request(Map<String, String> headers, String body, Map<String, Object> json) {

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> items() {
            return (List<Map<String, Object>>) json.get("items");
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    public record Reply(int status, String body) {
    }

    @FunctionalInterface
    public interface Responder {
        Reply reply(Request request, int requestNumber) throws Exception;
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile Responder responder;

    private FakeControlPlane(Responder responder) throws IOException {
        this.responder = responder;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public static FakeControlPlane start(Responder responder) throws IOException {
        return new FakeControlPlane(responder);
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public void respondWith(Responder responder) {
        this.responder = responder;
    }

    /** For responders that hang: blocks until {@link #close()}. */
    public void awaitRelease() throws InterruptedException {
        released.await(30, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0)));
            @SuppressWarnings("unchecked")
            Map<String, Object> json = (Map<String, Object>) Json.parse(body);
            Request request = new Request(Map.copyOf(headers), body, json);
            requests.add(request);

            Reply reply;
            try {
                reply = responder.reply(request, counter.incrementAndGet());
            } catch (Exception exception) {
                reply = new Reply(500, "{\"code\":\"COMMON-500\"}");
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        }
    }

    // --- canned replies ---

    /** Item results in request order, e.g. {@code itemResult("CREATED", null, false)}. */
    public static Reply batchReply(List<String> itemResults) {
        return new Reply(200, "{\"success\":true,\"code\":\"SUCCESS\",\"data\":{\"items\":["
            + String.join(",", itemResults) + "]}}");
    }

    public static String itemResult(int index, String status, String code, boolean retryable) {
        return "{\"index\":" + index + ",\"status\":\"" + status + "\",\"code\":"
            + (code == null ? "null" : "\"" + code + "\"") + ",\"retryable\":" + retryable + "}";
    }

    public static Reply error(int status, String code) {
        return new Reply(status, "{\"success\":false,\"code\":\"" + code + "\",\"message\":\"x\"}");
    }

    /** Stores items by eventId like the real server and answers CREATED, DUPLICATE, or a 409 rejection. */
    public static Responder deduplicating() {
        Map<String, Map<String, Object>> stored = new ConcurrentHashMap<>();
        return (request, number) -> deduplicate(stored, request);
    }

    public static Reply deduplicate(Map<String, Map<String, Object>> stored, Request request) {
        List<String> results = new ArrayList<>();
        List<Map<String, Object>> items = request.items();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            Map<String, Object> previous = stored.putIfAbsent((String) item.get("eventId"), item);
            if (previous == null) {
                results.add(itemResult(i, "CREATED", null, false));
            } else if (previous.equals(item)) {
                results.add(itemResult(i, "DUPLICATE", null, false));
            } else {
                results.add(itemResult(i, "REJECTED", "INGESTION-409", false));
            }
        }
        return batchReply(results);
    }
}
