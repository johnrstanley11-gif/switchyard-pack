/*
 * Go Home Already! by OmniPraxis; original official binary declares MIT License.
 * Source reconstructed with Vineflower 1.12.0 from the verified 1.2.0 NeoForge binary.
 * Modified 2026-10-11 for the private Minecraft 26.3 / NeoForge source port.
 * This is reconstructed source, not an author-published source distribution.
 */
package net.mcreator.gohomealready;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;

@EventBusSubscriber(modid = GoHomeAlreadyMod.MODID)
public final class HomeCooldown {
   private static final Map<UUID, Long> LAST_USE = new HashMap<>();

   private HomeCooldown() {
   }

   public static boolean blocked(CommandSourceStack source, ServerPlayer player) {
      int cd = (Integer)HomeConfig.COOLDOWN_SECONDS.get();
      if (cd > 0 && !HomeConfig.hasPermission(player.permissions(), HomeConfig.BYPASS_LEVEL.get())) {
         Long last = LAST_USE.get(player.getUUID());
         if (last == null) {
            return false;
         }

         long remaining = last + cd * 1000000000L - System.nanoTime();
         if (remaining <= 0L) {
            return false;
         }

         long secs = (remaining + 999999999L) / 1000000000L;
         source.sendFailure(Component.literal("Wait " + secs + "s before using that again."));
         return true;
      } else {
         return false;
      }
   }

   public static void record(ServerPlayer player) {
      LAST_USE.put(player.getUUID(), System.nanoTime());
   }

   @SubscribeEvent
   public static void onLogout(PlayerLoggedOutEvent event) {
      LAST_USE.remove(event.getEntity().getUUID());
   }
}
