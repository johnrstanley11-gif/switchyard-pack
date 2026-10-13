/*
 * Go Home Already! by OmniPraxis; original official binary declares MIT License.
 * Source reconstructed with Vineflower 1.12.0 from the verified 1.2.0 NeoForge binary.
 * Modified 2026-10-11 for the private Minecraft 26.3 / NeoForge source port.
 * This is reconstructed source, not an author-published source distribution.
 */
package net.mcreator.gohomealready;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = GoHomeAlreadyMod.MODID)
public class HomeGameMaster {
   public HomeGameMaster() {
   }

   @SubscribeEvent
   public static void registerCommand(RegisterCommandsEvent event) {
      event.getDispatcher().register(Commands.literal("gohome")
         .then(Commands.literal("gamemaster")
            .requires(src -> HomeConfig.hasPermission(src.permissions(), gmPermission()) && gmEnabled())
            .then(Commands.literal("sethome")
               .then(Commands.argument("targets", EntityArgument.players())
                  .executes(HomeGameMaster::setHomeHere)
                  .then(Commands.argument("position", Vec3Argument.vec3()).executes(HomeGameMaster::setHomeAt))))
            .then(Commands.literal("clearhome")
               .then(Commands.argument("targets", EntityArgument.players()).executes(HomeGameMaster::clearHome)))
            .then(Commands.literal("clearbed")
               .then(Commands.argument("targets", EntityArgument.players()).executes(HomeGameMaster::clearBed)))));
   }

   private static boolean gmEnabled() {
      try {
         return (Boolean)HomeConfig.GM_ENABLED.get() && (Boolean)HomeConfig.GM_ADMIN_ENABLED.get();
      } catch (IllegalStateException e) {
         return false;
      }
   }

   private static int gmPermission() {
      try {
         return (Integer)HomeConfig.GM_PERMISSION_LEVEL.get();
      } catch (IllegalStateException e) {
         return 2;
      }
   }

   private static int setHomeHere(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)context.getSource();
      Vec3 pos = source.getPosition();
      String dim = source.getLevel().dimension().identifier().toString();
      return applyHome(context, pos, dim);
   }

   private static int setHomeAt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)context.getSource();
      Vec3 pos = Vec3Argument.getVec3(context, "position");
      String dim = source.getLevel().dimension().identifier().toString();
      return applyHome(context, pos, dim);
   }

   private static int applyHome(CommandContext<CommandSourceStack> context, Vec3 pos, String dim) throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)context.getSource();
      Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");

      for (ServerPlayer p : targets) {
         SetHome.writeHome(p, pos.x, pos.y, pos.z, dim);
      }

      int count = targets.size();
      source.sendSuccess(() -> Component.literal("Set home for " + count + " player(s)."), true);
      return count;
   }

   private static int clearHome(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)context.getSource();
      Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");

      for (ServerPlayer p : targets) {
         SetHome.clearHome(p);
      }

      int count = targets.size();
      source.sendSuccess(() -> Component.literal("Cleared home for " + count + " player(s)."), true);
      return count;
   }

   private static int clearBed(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)context.getSource();
      Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");

      for (ServerPlayer p : targets) {
         p.setRespawnPosition(null, false);
      }

      int count = targets.size();
      source.sendSuccess(() -> Component.literal("Cleared respawn point for " + count + " player(s)."), true);
      return count;
   }
}
