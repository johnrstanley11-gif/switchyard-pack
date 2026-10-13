package communitybridge;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod("communitybridge")
public final class CommunityBridge {
    static final Logger LOGGER = LogUtils.getLogger();
    private final Map<UUID, Session> sessions = new HashMap<>(); // logical server thread only
    private final Map<UUID, Long> lastLink = new HashMap<>();
    private BridgeClient bridge;
    private long nextHeartbeat;

    private static volatile CommunityBridge instance;

    public CommunityBridge() { instance = this; NeoForge.EVENT_BUS.register(this); }

    /** 1.1.0: public game chat is shown in the website shoutbox. Best effort: nothing is queued while the site is down. */
    @SubscribeEvent
    public void chat(ServerChatEvent event) {
        if (bridge == null || event.isCanceled()) return;
        ServerPlayer player = event.getPlayer();
        String text = event.getRawText().strip();
        if (text.isEmpty()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("server_id", bridge.serverId());
        payload.addProperty("event_id", UUID.randomUUID().toString());
        payload.addProperty("uuid", player.getUUID().toString());
        payload.addProperty("username", player.getScoreboardName());
        payload.addProperty("body", text.length() > 256 ? text.substring(0, 256) : text);
        bridge.send("/api/server/chat", payload);
    }

    /**
     * 1.1.0: a vote reported by a server list site is passed on to the website's vote leaderboard. Classic Crates calls
     * this by reflection, so neither mod needs the other. Call on the server thread. Queued in the durable outbox.
     */
    public static void reportVote(MinecraftServer server, String username, String service) {
        CommunityBridge self = instance;
        if (self == null || self.bridge == null || username == null || service == null || !username.matches("[A-Za-z0-9_]{1,16}")) return;
        ServerPlayer online = server.getPlayerList().getPlayerByName(username);
        String site = service.strip();
        JsonObject payload = new JsonObject();
        payload.addProperty("server_id", self.bridge.serverId());
        payload.addProperty("username", online != null ? online.getScoreboardName() : username);
        if (online != null) payload.addProperty("uuid", online.getUUID().toString());
        payload.addProperty("service", site.isEmpty() ? "unknown" : site.length() > 80 ? site.substring(0, 80) : site);
        payload.addProperty("event_id", username.toLowerCase(java.util.Locale.ROOT) + ":" + Instant.now().getEpochSecond() + ":" + Integer.toHexString(site.hashCode()));
        self.bridge.enqueueTo("/api/server/vote", payload);
    }

    @SubscribeEvent
    public void start(ServerStartedEvent event) {
        try {
            BridgeConfig config = BridgeConfig.load(FMLPaths.CONFIGDIR.get().resolve("communitybridge.properties"));
            if (!config.enabled()) { LOGGER.info("Community Bridge disabled; configure config/communitybridge.properties"); return; }
            bridge = new BridgeClient(config, event.getServer(), event.getServer().getWorldPath(LevelResource.ROOT).resolve("communitybridge"));
            nextHeartbeat = System.nanoTime() + 60_000_000_000L;
            LOGGER.info("Community Bridge enabled for server id {}", config.serverId());
        } catch (Exception error) {
            LOGGER.error("Community Bridge could not start: {}", error.getMessage());
            bridge = null;
        }
    }

    @SubscribeEvent
    public void join(PlayerEvent.PlayerLoggedInEvent event) {
        if (bridge == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        Session session = new Session(UUID.randomUUID().toString(), player.getUUID().toString(), player.getScoreboardName());
        sessions.put(player.getUUID(), session);
        bridge.enqueue(session.event("join", bridge.serverId()));
        refreshOnline();
    }

    @SubscribeEvent
    public void leave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (bridge == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        Session session = sessions.remove(player.getUUID());
        lastLink.remove(player.getUUID());
        if (session != null) bridge.enqueue(session.event("leave", bridge.serverId()));
        refreshOnline();
    }

    @SubscribeEvent
    public void tick(ServerTickEvent.Post event) {
        if (bridge == null || System.nanoTime() < nextHeartbeat) return;
        nextHeartbeat = System.nanoTime() + 60_000_000_000L;
        sessions.values().forEach(session -> bridge.enqueue(session.event("heartbeat", bridge.serverId())));
        refreshOnline();
    }

    private void refreshOnline() {
        if (bridge != null) bridge.online(sessions.values().stream().map(s -> s.uuid).toList());
    }

    @SubscribeEvent
    public void stop(ServerStoppingEvent event) {
        if (bridge != null) {
            sessions.values().forEach(session -> bridge.enqueue(session.event("leave", bridge.serverId())));
            bridge.close();
            bridge = null;
        }
        sessions.clear();
        lastLink.clear();
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("link")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.argument("code", StringArgumentType.word()).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (bridge == null) { player.sendSystemMessage(Component.literal("Account linking is not configured.")); return 0; }
                    String code = StringArgumentType.getString(context, "code");
                    if (!code.matches("[A-Za-z0-9-]{6,64}")) {
                        player.sendSystemMessage(Component.literal("Use the one-time code shown on your website account.")); return 0;
                    }
                    long now = System.nanoTime();
                    if (now - lastLink.getOrDefault(player.getUUID(), 0L) < 5_000_000_000L) {
                        player.sendSystemMessage(Component.literal("Wait five seconds before trying another link code.")); return 0;
                    }
                    lastLink.put(player.getUUID(), now);
                    bridge.link(code, player.getUUID(), player.getScoreboardName());
                    player.sendSystemMessage(Component.literal("Checking your account link..."));
                    return 1;
                })));
        event.getDispatcher().register(Commands.literal("communitybridge")
                .requires(source -> source.getEntity() == null)
                .then(Commands.literal("status").executes(context -> {
                    String status = bridge == null ? "disabled" : bridge.status();
                    context.getSource().sendSuccess(() -> Component.literal("Community Bridge: " + status), false);
                    return 1;
                }))
                .then(Commands.literal("resolve")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("action", StringArgumentType.word()).executes(context -> {
                                    if (bridge == null) { context.getSource().sendFailure(Component.literal("Bridge disabled.")); return 0; }
                                    String id = StringArgumentType.getString(context, "id");
                                    String action = StringArgumentType.getString(context, "action");
                                    if (!id.matches("[1-9][0-9]{0,18}") || !List.of("done", "retry").contains(action)) {
                                        context.getSource().sendFailure(Component.literal("Usage: communitybridge resolve <id> done|retry")); return 0;
                                    }
                                    bridge.resolve(id, action, message -> context.getSource().sendSuccess(() -> Component.literal(message), false));
                                    return 1;
                                })))));
    }

    private static final class Session {
        final String id, uuid, username;
        long sequence;
        Session(String id, String uuid, String username) { this.id = id; this.uuid = uuid; this.username = username; }
        JsonObject event(String type, String serverId) {
            JsonObject payload = new JsonObject();
            payload.addProperty("server_id", serverId);
            payload.addProperty("session_id", id);
            payload.addProperty("uuid", uuid);
            payload.addProperty("username", username);
            payload.addProperty("event", type);
            payload.addProperty("sequence", ++sequence);
            payload.addProperty("occurred_at", Instant.now().toString());
            return payload;
        }
    }
}
