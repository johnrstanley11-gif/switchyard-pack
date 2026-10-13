package classiccrates.cosmetic;

import classiccrates.ClassicCrates;
import classiccrates.Config;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The particle trail a player wears (a player attachment). Particles are sent by the server, so the effect needs no
 * client code and everyone nearby sees it.
 */
public record Trail(Optional<ParticleOptions> particle, String name) {

    public static final Trail NONE = new Trail(Optional.empty(), "");

    public static final MapCodec<Trail> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ParticleTypes.CODEC.optionalFieldOf("particle").forGetter(Trail::particle),
            Codec.STRING.optionalFieldOf("name", "").forGetter(Trail::name)
    ).apply(i, Trail::new));

    public static class Events {

        private static final Map<UUID, Vec3> LAST_POSITION = new HashMap<>();

        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            LAST_POSITION.remove(event.getEntity().getUUID());
        }

        @SubscribeEvent
        public static void onPlayerTick(PlayerTickEvent.Post event) {
            if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) return;
            if (player.tickCount % Config.TRAIL_INTERVAL.get() != 0 || player.isSpectator() || player.isInvisible()) return;

            Trail trail = player.getExistingDataOrNull(ClassicCrates.TRAIL.get());
            if (trail == null || trail.particle().isEmpty()) return;

            // Only while moving, so a player standing still is not buried in particles. The position is compared with
            // the one at the previous puff (a server player's xOld/zOld already equal its position by now).
            Vec3 now = player.position();
            Vec3 before = LAST_POSITION.put(player.getUUID(), now);
            if (before == null || before.distanceToSqr(now) < 0.01) return;

            level.sendParticles(trail.particle().get(), player.getX(), player.getY() + 0.15, player.getZ(), 2, 0.18, 0.05, 0.18, 0.0);
        }
    }
}
