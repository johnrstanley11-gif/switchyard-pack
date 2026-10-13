package classiccrates.vote;

import classiccrates.ClassicCrates;
import classiccrates.Config;
import com.mojang.serialization.Codec;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns votes into crate keys. A player who is offline when the vote arrives gets the keys at the next login.
 */
public class VoteHandler {

    private static @Nullable VoteListener listener;
    private static String listenerStatus = "not started";

    public static String listenerStatus() {
        return listenerStatus;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Config.LISTENER_ENABLED.get()) {
            listenerStatus = "disabled in classiccrates.toml";
            return;
        }
        MinecraftServer server = event.getServer();
        try {
            listener = new VoteListener(FMLPaths.CONFIGDIR.get().resolve("classiccrates").resolve("votifier"),
                    Config.LISTENER_HOST.get(), Config.LISTENER_PORT.get(),
                    // Network thread -> server thread
                    vote -> server.execute(() -> onVote(server, vote)));
            listenerStatus = "listening on " + Config.LISTENER_HOST.get() + ":" + listener.port();
            ClassicCrates.LOGGER.info("Vote listener {} (keys: config/classiccrates/votifier)", listenerStatus);
        } catch (Exception e) {
            listenerStatus = "FAILED to start: " + e;
            ClassicCrates.LOGGER.error("Could not start the vote listener on port {}. Votes will not be received.", Config.LISTENER_PORT.get(), e);
        }
    }

    /**
     * 1.2.3: when the Community Bridge mod is installed, the vote also goes to the website's vote leaderboard. Looked up
     * by name so that neither mod depends on the other.
     */
    private static void reportToWebsite(MinecraftServer server, VoteListener.Vote vote) {
        try {
            Class.forName("communitybridge.CommunityBridge").getMethod("reportVote", MinecraftServer.class, String.class, String.class)
                    .invoke(null, server, vote.username(), vote.service());
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            // No bridge (or an older one): votes still give keys.
        } catch (ReflectiveOperationException | RuntimeException error) {
            ClassicCrates.LOGGER.warn("Could not pass a vote on to the website bridge", error);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (listener != null) {
            listener.close();
            listener = null;
        }
        listenerStatus = "stopped";
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.level().getServer();
        int pending = PendingKeys.get(server).take(player.getScoreboardName());
        if (pending > 0) {
            giveKeys(player, pending);
            player.sendSystemMessage(Component.translatable("classiccrates.vote.pending", pending).withStyle(ChatFormatting.GREEN));
        }
    }

    public static void onVote(MinecraftServer server, VoteListener.Vote vote) {
        reportToWebsite(server, vote);
        int keys = Config.KEYS_PER_VOTE.get();
        ServerPlayer player = server.getPlayerList().getPlayerByName(vote.username());
        if (player != null) {
            if (keys > 0) giveKeys(player, keys);
            player.sendSystemMessage(Component.translatable("classiccrates.vote.thanks", vote.service(), keys).withStyle(ChatFormatting.GREEN));
            player.level().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8F, 1.0F);
        } else if (keys > 0) {
            PendingKeys.get(server).add(vote.username(), keys);
        }

        if (Config.BROADCAST_VOTES.get()) {
            Component message = Component.translatable("classiccrates.vote.broadcast", vote.username(), vote.service()).withStyle(ChatFormatting.AQUA);
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other != player) other.sendSystemMessage(message);
            }
        }
    }

    public static void giveKeys(ServerPlayer player, int count) {
        while (count > 0) {
            int n = Math.min(count, 64);
            count -= n;
            ItemStack keys = new ItemStack(ClassicCrates.CRATE_KEY.get(), n);
            if (!player.getInventory().add(keys) || !keys.isEmpty()) {
                player.drop(keys, false, Prediction.SERVER_ONLY);
            }
        }
    }

    /**
     * Keys owed to players who were offline, by lower-case player name (vote sites only know names).
     */
    public static class PendingKeys extends SavedData {

        private static final Codec<PendingKeys> CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT)
                .fieldOf("pending").codec().xmap(PendingKeys::new, p -> p.pending);
        private static final SavedDataType<PendingKeys> TYPE = new SavedDataType<>(ClassicCrates.id("pending_keys"), PendingKeys::new, CODEC);

        private final Map<String, Integer> pending = new HashMap<>();

        public PendingKeys() {
        }

        private PendingKeys(Map<String, Integer> loaded) {
            pending.putAll(loaded);
        }

        public static PendingKeys get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(TYPE);
        }

        public void add(String playerName, int keys) {
            pending.merge(playerName.toLowerCase(Locale.ROOT), keys, Integer::sum);
            setDirty();
        }

        public int take(String playerName) {
            Integer keys = pending.remove(playerName.toLowerCase(Locale.ROOT));
            if (keys != null) setDirty();
            return keys == null ? 0 : keys;
        }

        public int peek(String playerName) {
            return pending.getOrDefault(playerName.toLowerCase(Locale.ROOT), 0);
        }
    }
}
