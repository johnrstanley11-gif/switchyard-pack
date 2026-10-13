/*
 * Go Home Already! by OmniPraxis; original official binary declares MIT License.
 * Source reconstructed with Vineflower 1.12.0 from the verified 1.2.0 NeoForge binary.
 * Modified 2026-10-11 for the private Minecraft 26.3 / NeoForge source port.
 * This is reconstructed source, not an author-published source distribution.
 */
package net.mcreator.gohomealready;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;

@EventBusSubscriber(modid = GoHomeAlreadyMod.MODID)
public class SetHome {
   public static final String KEY_X = "homeX";
   public static final String KEY_Y = "homeY";
   public static final String KEY_Z = "homeZ";
   public static final String KEY_DIM = "homeDim";
   private static final Map<UUID, Long> PENDING_DELETE = new HashMap<>();

   public SetHome() {
   }

   @SubscribeEvent
   public static void registerCommand(RegisterCommandsEvent event) {
      event.getDispatcher().register((LiteralArgumentBuilder)Commands.literal("sethome").executes(SetHome::setHome));
      event.getDispatcher().register((LiteralArgumentBuilder)Commands.literal("home").executes(SetHome::goHome));
      event.getDispatcher().register((LiteralArgumentBuilder)Commands.literal("delhome").executes(SetHome::delHome));
   }

   private static int setHome(CommandContext<CommandSourceStack> context) {
      CommandSourceStack source = (CommandSourceStack)context.getSource();
      if (!(Boolean)HomeConfig.ENABLE_SETHOME.get()) {
         source.sendFailure(Component.literal("This command is disabled."));
         return 0;
      } else if (source.getEntity() instanceof ServerPlayer player) {
         writeHome(player, player.getX(), player.getY(), player.getZ(), player.level().dimension().identifier().toString());
         PENDING_DELETE.remove(player.getUUID());
         source.sendSuccess(() -> Component.literal("Home set!"), false);
         return 1;
      } else {
         source.sendFailure(Component.literal("Only players can use this command."));
         return 0;
      }
   }

   private static int goHome(CommandContext<CommandSourceStack> context) {
      CommandSourceStack source = (CommandSourceStack)context.getSource();
      if (!(Boolean)HomeConfig.ENABLE_HOME.get()) {
         source.sendFailure(Component.literal("This command is disabled."));
         return 0;
      }

      if (source.getEntity() instanceof ServerPlayer player) {
         CompoundTag data = getPersistedData(player);
         if (!data.contains("homeX")) {
            source.sendFailure(Component.literal("No home set. Use /sethome first."));
            return 0;
         } else {
            double x = data.getDoubleOr("homeX", 0.0);
            double y = data.getDoubleOr("homeY", 0.0);
            double z = data.getDoubleOr("homeZ", 0.0);
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(data.getStringOr("homeDim", "")));
            ServerLevel target = player.level().getServer().getLevel(dimKey);
            if (target == null) {
               source.sendFailure(Component.literal("Your home dimension no longer exists."));
               return 0;
            } else {
               return HomeTeleport.request(source, player, target, x, y, z, "Teleported home!");
            }
         }
      } else {
         source.sendFailure(Component.literal("Only players can use this command."));
         return 0;
      }
   }

   private static int delHome(CommandContext<CommandSourceStack> context) {
      CommandSourceStack source = (CommandSourceStack)context.getSource();
      if (!(Boolean)HomeConfig.ENABLE_DELHOME.get()) {
         source.sendFailure(Component.literal("This command is disabled."));
         return 0;
      }

      if (source.getEntity() instanceof ServerPlayer player) {
         if (!hasHome(player)) {
            source.sendFailure(Component.literal("You have no home to delete."));
            return 0;
         } else {
            int confirmSeconds = (Integer)HomeConfig.DELHOME_CONFIRM_SECONDS.get();
            if (confirmSeconds > 0 && !consumePendingDelete(player, confirmSeconds)) {
               PENDING_DELETE.put(player.getUUID(), System.nanoTime());
               source.sendFailure(Component.literal("Run /delhome again within " + confirmSeconds + "s to delete your home."));
               return 0;
            } else {
               clearHome(player);
               source.sendSuccess(() -> Component.literal("Home deleted."), false);
               return 1;
            }
         }
      } else {
         source.sendFailure(Component.literal("Only players can use this command."));
         return 0;
      }
   }

   private static boolean consumePendingDelete(ServerPlayer player, int confirmSeconds) {
      Long asked = PENDING_DELETE.remove(player.getUUID());
      return asked == null ? false : System.nanoTime() - asked <= confirmSeconds * 1000000000L;
   }

   @SubscribeEvent
   public static void onLogout(PlayerLoggedOutEvent event) {
      PENDING_DELETE.remove(event.getEntity().getUUID());
   }

   public static void writeHome(ServerPlayer player, double x, double y, double z, String dim) {
      CompoundTag data = getPersistedData(player);
      data.putDouble("homeX", x);
      data.putDouble("homeY", y);
      data.putDouble("homeZ", z);
      data.putString("homeDim", dim);
   }

   public static boolean hasHome(ServerPlayer player) {
      return getPersistedData(player).contains("homeX");
   }

   public static void clearHome(ServerPlayer player) {
      CompoundTag data = getPersistedData(player);
      data.remove("homeX");
      data.remove("homeY");
      data.remove("homeZ");
      data.remove("homeDim");
   }

   public static CompoundTag getPersistedData(ServerPlayer player) {
      CompoundTag root = player.getPersistentData();
      if (!root.contains("PlayerPersisted")) {
         root.put("PlayerPersisted", new CompoundTag());
      }

      return root.getCompoundOrEmpty("PlayerPersisted");
   }
}
