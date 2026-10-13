/*
 * Go Home Already! by OmniPraxis; original official binary declares MIT License.
 * Source reconstructed with Vineflower 1.12.0 from the verified 1.2.0 NeoForge binary.
 * Modified 2026-10-11 for the private Minecraft 26.3 / NeoForge source port.
 * This is reconstructed source, not an author-published source distribution.
 */
package net.mcreator.gohomealready;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = GoHomeAlreadyMod.MODID)
public class ToBed {
   public ToBed() {
   }

   @SubscribeEvent
   public static void registerCommand(RegisterCommandsEvent event) {
      event.getDispatcher().register((LiteralArgumentBuilder)Commands.literal("bed").executes(ToBed::toBed));
      event.getDispatcher().register((LiteralArgumentBuilder)Commands.literal("tobed").executes(ToBed::toBed));
   }

   private static int toBed(CommandContext<CommandSourceStack> context) {
      CommandSourceStack source = (CommandSourceStack)context.getSource();
      if (!(Boolean)HomeConfig.ENABLE_BED.get()) {
         source.sendFailure(Component.literal("This command is disabled."));
         return 0;
      } else if (!(source.getEntity() instanceof ServerPlayer player)) {
         source.sendFailure(Component.literal("Only players can use this command."));
         return 0;
      } else {
         ServerPlayer.RespawnConfig respawn = player.getRespawnConfig();
         ResourceKey<Level> var6 = ServerPlayer.RespawnConfig.getDimensionOrDefault(respawn);
         ServerLevel target = player.level().getServer().getLevel(var6);
         BlockPos bed = respawn == null ? null : respawn.respawnData().pos();
         if (target == null || bed == null) {
            target = player.level().getServer().overworld();
            bed = target.getRespawnData().pos();
         }

         return HomeTeleport.request(source, player, target, bed.getX() + 0.5, bed.getY() + 0.5, bed.getZ() + 0.5, "Teleported to bed!");
      }
   }
}
