package communitybridge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

/** Offline checks. No game server, payment endpoint or configured website is contacted. */
public final class BridgeChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("communitybridge-checks-");
        try {
            Path config = directory.resolve("config/communitybridge.properties");
            verify(!BridgeConfig.load(config).enabled(), "first start must be disabled");
            verify(Files.readString(config).contains("apiToken=\n"), "first start must contain no secret");
            String values = "enabled=true\nserverId=main\napiToken=" + "a".repeat(64) + "\n";
            Files.writeString(config, values + "websiteUrl=http://example.com\n");
            rejects(() -> BridgeConfig.load(config), "remote HTTP must be rejected by default");
            Files.writeString(config, values + "websiteUrl=https://user:password@example.com\n");
            rejects(() -> BridgeConfig.load(config), "URL credentials must be rejected");
            Files.writeString(config, values + "websiteUrl=https://example.com?token=x\n");
            rejects(() -> BridgeConfig.load(config), "query strings must be rejected");
            Files.writeString(config, values + "websiteUrl=http://127.0.0.1:8080\n");
            verify(BridgeConfig.load(config).enabled(), "localhost HTTP should work in development");
            Files.writeString(config, "enabled=true\nwebsiteUrl=https://example.com\napiToken=short\n");
            rejects(() -> BridgeConfig.load(config), "short token must be rejected");

            Path journal = directory.resolve("world/communitybridge/deliveries.json");
            AtomicJson.write(journal, Map.of("123", Map.of("state", "IN_PROGRESS")));
            AtomicJson.write(journal, Map.of("123", Map.of("state", "DONE")));
            verify(Files.readString(journal).contains("DONE"), "atomic journal replacement should persist latest state");
            verify(!Files.exists(journal.resolveSibling("deliveries.json.tmp")), "successful replacement should leave no temporary file");

            HttpResponse.BodySubscriber<byte[]> ordinary = subscriber();
            TestSubscription ordinarySubscription = new TestSubscription();
            ordinary.onSubscribe(ordinarySubscription);
            ordinary.onNext(List.of(ByteBuffer.wrap("ok".getBytes())));
            ordinary.onComplete();
            verify(new String(ordinary.getBody().toCompletableFuture().join()).equals("ok"), "normal body should complete");

            HttpResponse.BodySubscriber<byte[]> oversized = subscriber();
            TestSubscription oversizedSubscription = new TestSubscription();
            oversized.onSubscribe(oversizedSubscription);
            oversized.onNext(List.of(ByteBuffer.allocate(1_048_577)));
            verify(oversizedSubscription.cancelled, "oversized response must cancel upstream");
            verify(oversized.getBody().toCompletableFuture().isCompletedExceptionally(), "oversized response must fail without unbounded allocation");
            transportRetry(directory);
            System.out.println("Community Bridge: " + checks + " offline security checks passed.");
        } finally {
            // Temporary test directory created above, never the game world or repository.
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void transportRetry(Path directory) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer fakeWebsite = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeWebsite.createContext("/api/server/heartbeat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int attempt = requests.incrementAndGet();
            byte[] response = "{\"accepted\":true,\"seconds\":0}".getBytes();
            exchange.sendResponseHeaders(attempt == 1 ? 503 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        fakeWebsite.start();
        Path state = directory.resolve("transport-world/communitybridge");
        BridgeConfig config = new BridgeConfig(true, URI.create("http://127.0.0.1:" + fakeWebsite.getAddress().getPort()),
                "test", "a".repeat(64), 10, 100, 100, Set.of("crates"));
        BridgeClient client = new BridgeClient(config, null, state); // no players/commands or Minecraft server
        try {
            JsonObject event = new JsonObject();
            event.addProperty("event", "join");
            event.addProperty("sequence", 1);
            client.enqueue(event);
            await(() -> requests.get() >= 1, "initial telemetry attempt should reach local fake endpoint");
            verify(Files.readString(state.resolve("outbox.json")).contains("\"sequence\": 1"), "HTTP 503 must retain durable telemetry");
            await(() -> requests.get() >= 2, "failed telemetry should retry automatically");
            await(() -> {
                try { return Files.readString(state.resolve("outbox.json")).trim().equals("[]"); }
                catch (IOException ignored) { return false; }
            }, "successful retry should remove the persisted event");
        } finally { client.close(); fakeWebsite.stop(0); }
    }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        verify(condition.getAsBoolean(), description);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse.BodySubscriber<byte[]> subscriber() throws Exception {
        var constructor = Class.forName("communitybridge.BridgeClient$LimitedBody").getDeclaredConstructor();
        constructor.setAccessible(true);
        return (HttpResponse.BodySubscriber<byte[]>) constructor.newInstance();
    }
    private static void verify(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
    private static void rejects(Throwing action, String description) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError(description);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static final class TestSubscription implements Flow.Subscription {
        boolean cancelled;
        @Override public void request(long count) {}
        @Override public void cancel() { cancelled = true; }
    }
}
