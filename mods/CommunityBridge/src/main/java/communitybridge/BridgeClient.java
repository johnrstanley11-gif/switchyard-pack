package communitybridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** All HTTP, outbox IO and delivery journal IO run on the single worker. Only command execution uses server.execute. */
final class BridgeClient implements AutoCloseable {
    private final BridgeConfig config;
    private final MinecraftServer server;
    private final Path outboxPath, journalPath;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "CommunityBridge-IO");
        thread.setDaemon(true);
        return thread;
    });
    private final ArrayList<JsonObject> outbox;
    private final Map<String, Delivery> journal;
    private final ArrayBlockingQueue<JsonObject> pendingEvents;
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final Semaphore linkSlots = new Semaphore(32);
    private final Semaphore chatSlots = new Semaphore(64);
    private final AtomicLong lastWarn = new AtomicLong();
    private volatile List<String> online = List.of();
    private volatile String status = "starting";
    private long nextPoll, nextRetry, nextChat;
    private long chatCursor = -1;          // last website chat id shown in game; -1 = not in step yet
    private boolean chatFromSite = true;
    private boolean outboxDirty;
    private int failures;
    private volatile boolean accepting = true;

    BridgeClient(BridgeConfig config, MinecraftServer server, Path directory) throws IOException {
        this.config = config;
        this.server = server;
        pendingEvents = new ArrayBlockingQueue<>(config.maxOutbox());
        outboxPath = directory.resolve("outbox.json");
        journalPath = directory.resolve("deliveries.json");
        Files.createDirectories(directory);
        try {
            outbox = Files.exists(outboxPath)
                    ? AtomicJson.GSON.fromJson(Files.readString(outboxPath), new TypeToken<ArrayList<JsonObject>>() {}.getType())
                    : new ArrayList<>();
            journal = Files.exists(journalPath)
                    ? AtomicJson.GSON.fromJson(Files.readString(journalPath), new TypeToken<LinkedHashMap<String, Delivery>>() {}.getType())
                    : new LinkedHashMap<>();
            if (outbox == null || journal == null || outbox.size() > config.maxOutbox() || journal.size() > config.maxJournal())
                throw new IOException("Bridge state is invalid or above configured limits; inspect saved files before restarting");
            // Never continue with unreadable deduplication history; it could duplicate paid rewards.
            if (journal.values().stream().anyMatch(d -> d == null || d.state == null || d.uuid == null || d.command == null))
                throw new IOException("Delivery journal is invalid; restore a backup before restarting");
        } catch (RuntimeException error) { throw new IOException("Could not parse persisted bridge state; restore a backup", error); }
        updateStatus();
        worker.scheduleWithFixedDelay(this::cycle, 1, 2, TimeUnit.SECONDS);
    }

    String serverId() { return config.serverId(); }
    String status() { return status; }
    void online(List<String> players) { online = List.copyOf(players); }

    void enqueue(JsonObject payload) {
        if (!accepting) return;
        if (!pendingEvents.offer(payload)) {
            pendingEvents.poll();
            pendingEvents.offer(payload);
            warn("Pending telemetry full: oldest event dropped; playtime can undercount during overload");
        }
        // Coalesce events so a slow disk never creates an unbounded executor task backlog.
        scheduleFlush();
    }

    /** 1.1.0: a durable event for an endpoint other than the heartbeat (votes). The path travels with the event. */
    void enqueueTo(String path, JsonObject payload) {
        payload.addProperty("_path", path);
        enqueue(payload);
    }

    /** 1.1.0: one best-effort request (chat lines). Dropped while the website is failing or the queue is full. */
    void send(String path, JsonObject payload) {
        if (!accepting || worker.isShutdown() || !chatSlots.tryAcquire()) return;
        worker.execute(() -> {
            try { if (failures == 0) request(path, payload); }
            catch (Exception error) { warn("Website did not take a chat line (" + error.getClass().getSimpleName() + "); it is not retried"); }
            finally { chatSlots.release(); }
        });
    }

    private void scheduleFlush() {
        if (!worker.isShutdown() && flushScheduled.compareAndSet(false, true)) worker.execute(() -> {
            flushScheduled.set(false);
            try { persistPending(); }
            catch (IOException error) { warn("Could not persist playtime events; check free disk space and permissions"); }
        });
    }

    private void persistPending() throws IOException {
        ArrayList<JsonObject> batch = new ArrayList<>();
        pendingEvents.drainTo(batch);
        for (JsonObject payload : batch) {
            if (outbox.size() >= config.maxOutbox()) {
                outbox.removeFirst();
                warn("Outbox full: oldest event dropped; playtime will undercount during this prolonged outage");
            }
            outbox.add(payload);
            outboxDirty = true;
        }
        if (outboxDirty) { AtomicJson.write(outboxPath, outbox); outboxDirty = false; }
        updateStatus();
    }

    void link(String code, UUID uuid, String username) {
        if (!accepting) return;
        if (!linkSlots.tryAcquire()) {
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null) player.sendSystemMessage(Component.literal("Account linking is busy. Try again shortly."));
            });
            return;
        }
        worker.execute(() -> {
            String message;
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("code", code);
                payload.addProperty("uuid", uuid.toString());
                payload.addProperty("username", username);
                JsonObject result = request("/api/server/link", payload);
                message = result.has("linked") && result.get("linked").getAsBoolean()
                        ? "Minecraft account linked! Refresh your website profile."
                        : "Account link was not accepted. Generate a fresh code on the website.";
            } catch (ApiException error) {
                message = error.status >= 400 && error.status < 500
                        ? "That code is invalid, expired, already used, or your account is already linked. Generate a fresh code on the website."
                        : "The website is unavailable. Try /link again shortly.";
            } catch (Exception error) { message = "The website is unavailable. Try /link again shortly."; }
            String response = message;
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null) player.sendSystemMessage(Component.literal(response));
            });
            linkSlots.release();
        });
    }

    private void cycle() {
        if (!accepting) return;
        if (System.nanoTime() < nextRetry) return;
        try {
            persistPending();
            drain(20);
            if (System.nanoTime() >= nextPoll) {
                nextPoll = System.nanoTime() + config.pollSeconds() * 1_000_000_000L;
                poll();
            }
            if (chatFromSite && System.nanoTime() >= nextChat) {
                nextChat = System.nanoTime() + 3_000_000_000L;
                pollChat();
            }
            failures = 0;
            updateStatus();
        } catch (Exception error) {
            failures = Math.min(failures + 1, 6);
            nextRetry = System.nanoTime() + Math.min(60, 1 << failures) * 1_000_000_000L;
            warn("Website transport failed (" + error.getClass().getSimpleName() + "); durable outbox retained, retrying");
        }
    }

    private void drain(int limit) throws Exception {
        for (int sent = 0; sent < limit && !outbox.isEmpty(); sent++) {
            JsonObject event = outbox.getFirst();
            String path = event.has("_path") ? event.get("_path").getAsString() : "/api/server/heartbeat";
            JsonObject body = event;
            if (event.has("_path")) { body = event.deepCopy(); body.remove("_path"); }
            try { request(path, body); }
            catch (ApiException error) {
                // A malformed individual event must not block the whole outbox. Auth/rate failures retain it.
                if (!List.of(400, 404, 409, 422).contains(error.status)) throw error;
                warn("Website rejected one event for " + path + " (HTTP " + error.status + "); removing it");
            }
            outbox.removeFirst();
            outboxDirty = true;
            AtomicJson.write(outboxPath, outbox);
            outboxDirty = false;
        }
    }

    private void poll() throws Exception {
        if (online.isEmpty()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("server_id", config.serverId());
        JsonArray players = new JsonArray();
        online.forEach(players::add);
        payload.add("online_uuids", players);
        JsonObject result = request("/api/server/commands/claim", payload);
        if (!result.has("commands") || !result.get("commands").isJsonArray()) throw new IOException("Malformed claim response");
        JsonArray commands = result.getAsJsonArray("commands");
        if (commands.size() > 20) throw new IOException("Too many commands in one claim response");
        for (JsonElement element : commands) deliver(element.getAsJsonObject());
    }

    /** 1.2.0: lines typed on the website or in Discord are shown in game chat. Nothing is replayed after a quiet spell. */
    private void pollChat() throws Exception {
        if (online.isEmpty()) { chatCursor = -1; return; }
        JsonObject payload = new JsonObject();
        payload.addProperty("server_id", config.serverId());
        payload.addProperty("after", chatCursor);
        JsonObject result;
        try { result = request("/api/server/chat/poll", payload); }
        catch (ApiException error) {
            if (error.status != 404) throw error;
            chatFromSite = false;              // an older website without this endpoint
            warn("The website has no chat feed for the game; website and Discord chat will not be shown in game");
            return;
        }
        boolean inStep = chatCursor >= 0;
        if (result.has("last")) chatCursor = Math.max(0, result.get("last").getAsLong());
        if (!inStep || !result.has("messages") || !result.get("messages").isJsonArray()) return;
        List<Component> lines = new ArrayList<>();
        for (JsonElement element : result.getAsJsonArray("messages")) {
            if (lines.size() >= 20 || !element.isJsonObject()) break;
            JsonObject message = element.getAsJsonObject();
            String name = clean(message.has("username") ? message.get("username").getAsString() : "", 40);
            String body = clean(message.has("body") ? message.get("body").getAsString() : "", 256);
            if (name.isEmpty() || body.isEmpty()) continue;
            boolean discord = message.has("source") && "discord".equals(message.get("source").getAsString());
            lines.add(Component.literal(discord ? "[Discord] " : "[Web] ").withStyle(discord ? ChatFormatting.BLUE : ChatFormatting.DARK_AQUA)
                    .append(Component.literal(name + ": " + body).withStyle(ChatFormatting.WHITE)));
        }
        if (!lines.isEmpty()) server.execute(() -> lines.forEach(line -> server.getPlayerList().broadcastSystemMessage(line, false)));
    }

    /** One line of plain text: control characters removed, length capped. */
    private static String clean(String text, int max) {
        String plain = text.replaceAll("[\\p{Cntrl}\u00a7]", " ").strip();
        return plain.length() > max ? plain.substring(0, max) : plain;
    }

    private void deliver(JsonObject payload) throws Exception {
        if (!accepting) return;
        String id = payload.get("id").getAsString();
        String uuid = UUID.fromString(payload.get("uuid").getAsString()).toString();
        String command = payload.get("command").getAsString();
        String lease = payload.get("lease_token").getAsString();
        if (!id.matches("[1-9][0-9]{0,18}") || lease.length() > 256 || command.length() > 512
                || command.isBlank() || command.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IOException("Invalid command claim");
        }
        String root = command.stripLeading().split("\\s+", 2)[0];
        if (!config.commandRoots().contains(root)) {
            ack(id, lease, false, "Command root is not allowed by bridge configuration");
            return;
        }
        Delivery saved = journal.get(id);
        if (saved != null && (!saved.uuid.equals(uuid) || !saved.command.equals(command))) {
            warn("Command " + id + " differs from its saved journal entry; manual reconciliation required");
            return;
        }
        if (saved != null && "DONE".equals(saved.state)) { ack(id, lease, true, ""); return; }
        if (saved != null && "IN_PROGRESS".equals(saved.state)) {
            warn("Command " + id + " is held after uncertain execution; console: communitybridge resolve " + id + " done|retry");
            return;
        }
        if (saved == null && journal.size() >= config.maxJournal()) {
            warn("Delivery journal reached its limit; increase maxJournalEntries before issuing new rewards");
            return;
        }
        Delivery delivery = new Delivery(uuid, command);
        journal.put(id, delivery);
        AtomicJson.write(journalPath, journal); // IN_PROGRESS must be durable BEFORE any gameplay mutation.
        updateStatus();
        CompletableFuture<Outcome> execution = new CompletableFuture<>();
        server.execute(() -> {
            try {
                if (!accepting) { execution.complete(new Outcome(false, "Bridge stopped before execution")); return; }
                ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(uuid));
                if (player == null) { execution.complete(new Outcome(false, "Player disconnected before execution")); return; }
                AtomicBoolean success = new AtomicBoolean();
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput()
                        .withCallback((ok, value) -> { if (ok) success.set(true); }), command);
                execution.complete(new Outcome(success.get(), success.get() ? "" : "Command reported failure; check server command configuration"));
            } catch (Exception error) { execution.complete(new Outcome(false, "Server command failed: " + error.getClass().getSimpleName())); }
        });
        // A timeout leaves IN_PROGRESS. Never guess whether a delayed main-thread command ran.
        Outcome outcome = execution.get(10, TimeUnit.SECONDS);
        delivery.state = outcome.success ? "DONE" : "FAILED";
        delivery.error = outcome.error;
        delivery.updatedAt = Instant.now().toString();
        AtomicJson.write(journalPath, journal); // persist success before ACK so an HTTP retry cannot replay it
        updateStatus();
        ack(id, lease, outcome.success, outcome.error);
    }

    private void ack(String id, String lease, boolean success, String error) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", Long.parseLong(id));
        payload.addProperty("lease_token", lease);
        payload.addProperty("success", success);
        payload.addProperty("error", error);
        request("/api/server/commands/ack", payload);
    }

    void resolve(String id, String action, Consumer<String> response) {
        worker.execute(() -> {
            String message;
            try {
                Delivery delivery = journal.get(id);
                if (delivery == null || !"IN_PROGRESS".equals(delivery.state)) {
                    message = "No held IN_PROGRESS command with ID " + id + ".";
                } else {
                    if ("done".equals(action)) { delivery.state = "DONE"; delivery.updatedAt = Instant.now().toString(); }
                    else journal.remove(id);
                    AtomicJson.write(journalPath, journal);
                    updateStatus();
                    message = "Command " + id + " resolved as " + action + "; next claim will "
                            + ("done".equals(action) ? "acknowledge it without execution." : "allow execution again.");
                }
            } catch (IOException error) { message = "Could not persist journal resolution. Check disk and permissions."; }
            String output = message;
            server.execute(() -> response.accept(output));
        });
    }

    private JsonObject request(String path, JsonObject payload) throws Exception {
        URI endpoint = URI.create(config.websiteUrl().toString() + path);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + config.token()).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(AtomicJson.GSON.toJson(payload))).build();
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, ignored -> new LimitedBody());
        try {
            // Covers the entire body, including a peer that sends headers then stalls.
            HttpResponse<byte[]> response = pending.get(8, TimeUnit.SECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ApiException(response.statusCode());
            JsonObject result = AtomicJson.GSON.fromJson(new String(response.body(), StandardCharsets.UTF_8), JsonObject.class);
            if (result == null) throw new IOException("Empty API response");
            return result;
        } catch (Exception error) { pending.cancel(true); throw error; }
    }

    private void updateStatus() {
        long held = journal.values().stream().filter(d -> "IN_PROGRESS".equals(d.state)).count();
        status = "outbox=" + outbox.size() + ", pending=" + pendingEvents.size() + ", journal=" + journal.size()
                + ", held=" + held + ", online=" + online.size();
    }

    private void warn(String message) {
        long now = System.nanoTime();
        long previous = lastWarn.get();
        if (now - previous >= 60_000_000_000L && lastWarn.compareAndSet(previous, now))
            CommunityBridge.LOGGER.warn("Community Bridge: {}", message);
    }

    @Override public void close() {
        accepting = false;
        worker.execute(() -> {
            try { persistPending(); }
            catch (IOException error) { warn("Could not persist final telemetry on shutdown"); }
        });
        // Previously submitted leave events are persisted before shutdown; no network must block the tick thread.
        worker.shutdown();
        http.shutdownNow();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                worker.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); worker.shutdownNow(); }
        // Shutdown-only disk flush: after worker termination there is no concurrent state mutation.
        // This preserves queued logout events even if a command wait had to be interrupted.
        if (worker.isTerminated()) {
            try { persistPending(); }
            catch (IOException error) { warn("Could not persist final telemetry; check disk and permissions"); }
        }
    }

    private static final class ApiException extends IOException {
        final int status;
        ApiException(int status) { super("HTTP " + status); this.status = status; }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> completion = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return completion; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > 1_048_576) {
                    subscription.cancel();
                    completion.completeExceptionally(new IOException("Oversized API response"));
                    return;
                }
                byte[] block = new byte[buffer.remaining()];
                buffer.get(block);
                bytes.writeBytes(block);
            }
        }
        @Override public void onError(Throwable error) { completion.completeExceptionally(error); }
        @Override public void onComplete() { completion.complete(bytes.toByteArray()); }
    }
    private record Outcome(boolean success, String error) {}
    private static final class Delivery {
        String state = "IN_PROGRESS";
        String uuid, command, error = "", updatedAt = Instant.now().toString();
        Delivery(String uuid, String command) { this.uuid = uuid; this.command = command; }
    }
}
