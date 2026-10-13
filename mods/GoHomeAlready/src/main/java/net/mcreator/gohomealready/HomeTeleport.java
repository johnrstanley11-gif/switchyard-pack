/*
 * Go Home Already! by OmniPraxis; original official binary declares MIT License.
 * Source reconstructed with Vineflower 1.12.0 from the verified 1.2.0 NeoForge binary.
 * Modified 2026-10-11 for the private Minecraft 26.3 / NeoForge source port.
 * This is reconstructed source, not an author-published source distribution.
 */
package net.mcreator.gohomealready;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;

@EventBusSubscriber(modid = GoHomeAlreadyMod.MODID)
public final class HomeTeleport {
   private static final double MOVE_TOLERANCE_SQR = 0.25;
   private static final Map<UUID, HomeTeleport.Pending> PENDING = new HashMap<>();

   private HomeTeleport() {
   }

   public static int request(CommandSourceStack source, ServerPlayer player, ServerLevel target, double x, double y, double z, String successMessage) {
      if (HomeCooldown.blocked(source, player)) {
         return 0;
      } else if (PENDING.containsKey(player.getUUID())) {
         source.sendFailure(Component.literal("You are already teleporting."));
         return 0;
      } else {
         int warmup = (Integer)HomeConfig.WARMUP_SECONDS.get();
         if (warmup > 0 && !HomeConfig.hasPermission(player.permissions(), HomeConfig.BYPASS_LEVEL.get())) {
            PENDING.put(player.getUUID(), new HomeTeleport.Pending(source, player, target, x, y, z, successMessage, warmup * 20));
            source.sendSuccess(() -> Component.literal("Teleporting in " + warmup + "s."), false);
            return 1;
         } else {
            execute(source, player, target, x, y, z, successMessage);
            return 1;
         }
      }
   }

   private static void execute(CommandSourceStack source, ServerPlayer player, ServerLevel target, double x, double y, double z, String successMessage) {
      HomeBack.record(player, player.getX(), player.getY(), player.getZ(), player.level().dimension().identifier().toString());
      player.teleportTo(target, x, y, z, Set.of(), player.getYRot(), player.getXRot(), true);
      HomeCooldown.record(player);
      source.sendSuccess(() -> Component.literal(successMessage), false);
   }

   @SubscribeEvent
   public static void onServerTick(Post event) {
      if (!PENDING.isEmpty()) {
         Iterator<Entry<UUID, HomeTeleport.Pending>> it = PENDING.entrySet().iterator();

         while (it.hasNext()) {
            Entry<UUID, HomeTeleport.Pending> entry = it.next();
            HomeTeleport.Pending pending = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
               it.remove();
            } else if ((Boolean)HomeConfig.WARMUP_CANCEL_ON_MOVE.get() && moved(player, pending)) {
               it.remove();
               player.sendSystemMessage(Component.literal("Teleport cancelled, you moved."));
            } else if ((Boolean)HomeConfig.WARMUP_CANCEL_ON_DAMAGE.get() && player.getHealth() < pending.health) {
               it.remove();
               player.sendSystemMessage(Component.literal("Teleport cancelled, you took damage."));
            } else {
               pending.health = Math.max(pending.health, player.getHealth());
               if (--pending.ticksLeft <= 0) {
                  it.remove();
                  execute(pending.source, player, pending.level, pending.x, pending.y, pending.z, pending.successMessage);
               }
            }
         }
      }
   }

   private static boolean moved(ServerPlayer player, HomeTeleport.Pending pending) {
      double dx = player.getX() - pending.fromX;
      double dy = player.getY() - pending.fromY;
      double dz = player.getZ() - pending.fromZ;
      return dx * dx + dy * dy + dz * dz > 0.25;
   }

   @SubscribeEvent
   public static void onLogout(PlayerLoggedOutEvent event) {
      PENDING.remove(event.getEntity().getUUID());
   }

   private static final class Pending {
      final CommandSourceStack source;
      final ServerLevel level;
      final double x;
      final double y;
      final double z;
      final String successMessage;
      final double fromX;
      final double fromY;
      final double fromZ;
      float health;
      int ticksLeft;

      Pending(CommandSourceStack source, ServerPlayer player, ServerLevel level, double x, double y, double z, String successMessage, int ticksLeft) {
         this.source = source;
         this.level = level;
         this.x = x;
         this.y = y;
         this.z = z;
         this.successMessage = successMessage;
         this.fromX = player.getX();
         this.fromY = player.getY();
         this.fromZ = player.getZ();
         this.health = player.getHealth();
         this.ticksLeft = ticksLeft;
      }
   }
}
