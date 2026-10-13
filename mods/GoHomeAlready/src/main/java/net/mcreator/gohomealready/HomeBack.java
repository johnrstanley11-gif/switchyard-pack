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
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

@EventBusSubscriber(modid = GoHomeAlreadyMod.MODID)
public class HomeBack {
   public static final String KEY_X = "backX";
   public static final String KEY_Y = "backY";
   public static final String KEY_Z = "backZ";
   public static final String KEY_DIM = "backDim";

   public HomeBack() {
   }

   @SubscribeEvent
   public static void registerCommand(RegisterCommandsEvent event) {
      event.getDispatcher().register((LiteralArgumentBuilder)Commands.literal("back").executes(HomeBack::back));
   }

   private static int back(CommandContext<CommandSourceStack> context) {
      CommandSourceStack source = (CommandSourceStack)context.getSource();
      if (!(Boolean)HomeConfig.ENABLE_BACK.get()) {
         source.sendFailure(Component.literal("This command is disabled."));
         return 0;
      }

      if (source.getEntity() instanceof ServerPlayer player) {
         CompoundTag data = SetHome.getPersistedData(player);
         if (!data.contains("backX")) {
            source.sendFailure(Component.literal("Nowhere to go back to."));
            return 0;
         } else {
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(data.getStringOr("backDim", "")));
            ServerLevel target = player.level().getServer().getLevel(dimKey);
            if (target == null) {
               source.sendFailure(Component.literal("That dimension no longer exists."));
               return 0;
            } else {
               return HomeTeleport.request(
                  source, player, target, data.getDoubleOr("backX", 0.0), data.getDoubleOr("backY", 0.0), data.getDoubleOr("backZ", 0.0), "Teleported back!"
               );
            }
         }
      } else {
         source.sendFailure(Component.literal("Only players can use this command."));
         return 0;
      }
   }

   @SubscribeEvent
   public static void onDeath(LivingDeathEvent event) {
      if ((Boolean)HomeConfig.BACK_ON_DEATH.get()) {
         if (event.getEntity() instanceof ServerPlayer player) {
            record(player, player.getX(), player.getY(), player.getZ(), player.level().dimension().identifier().toString());
         }
      }
   }

   public static void record(ServerPlayer player, double x, double y, double z, String dim) {
      if ((Boolean)HomeConfig.ENABLE_BACK.get()) {
         CompoundTag data = SetHome.getPersistedData(player);
         data.putDouble("backX", x);
         data.putDouble("backY", y);
         data.putDouble("backZ", z);
         data.putString("backDim", dim);
      }
   }
}
