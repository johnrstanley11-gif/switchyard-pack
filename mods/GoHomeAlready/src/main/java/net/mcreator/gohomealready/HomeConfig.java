/*
 * Go Home Already! by OmniPraxis; original official binary declares MIT License.
 * Source reconstructed with Vineflower 1.12.0 from the verified 1.2.0 NeoForge binary.
 * Modified 2026-10-11 for the private Minecraft 26.3 / NeoForge source port.
 * This is reconstructed source, not an author-published source distribution.
 */
package net.mcreator.gohomealready;

import net.neoforged.fml.ModContainer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;

public class HomeConfig {
   private static final Builder BUILDER = new Builder();
   public static final BooleanValue ENABLE_SETHOME = BUILDER.comment("Enable /sethome").define("enableSetHome", true);
   public static final BooleanValue ENABLE_HOME = BUILDER.comment("Enable /home teleport").define("enableHome", true);
   public static final BooleanValue ENABLE_BED = BUILDER.comment("Enable /bed and /tobed").define("enableBed", true);
   public static final BooleanValue ENABLE_DELHOME = BUILDER.comment("Enable /delhome").define("enableDelHome", true);
   public static final BooleanValue ENABLE_BACK = BUILDER.comment("Enable /back").define("enableBack", true);
   public static final BooleanValue BACK_ON_DEATH = BUILDER.comment("Record the death spot so /back returns to it").define("backOnDeath", true);
   public static final IntValue DELHOME_CONFIRM_SECONDS = BUILDER.comment("Seconds a player has to repeat /delhome to confirm the delete (0 = no confirmation)")
      .defineInRange("delHomeConfirmSeconds", 0, 0, 300);
   public static final IntValue COOLDOWN_SECONDS = BUILDER.comment("Cooldown in seconds for teleport commands (0 = none)")
      .defineInRange("cooldownSeconds", 5, 0, 86400);
   public static final IntValue BYPASS_LEVEL = BUILDER.comment("Permission level that skips the teleport cooldown and warmup (0 = nobody waits)")
      .defineInRange("bypassLevel", 2, 0, 4);
   public static final IntValue WARMUP_SECONDS = BUILDER.comment("Seconds to stand still before a teleport completes (0 = instant)")
      .defineInRange("warmupSeconds", 0, 0, 60);
   public static final BooleanValue WARMUP_CANCEL_ON_MOVE = BUILDER.comment("Cancel a warming up teleport when the player moves")
      .define("warmupCancelOnMove", true);
   public static final BooleanValue WARMUP_CANCEL_ON_DAMAGE = BUILDER.comment("Cancel a warming up teleport when the player takes damage")
      .define("warmupCancelOnDamage", true);
   public static final BooleanValue GM_ENABLED;
   public static final IntValue GM_PERMISSION_LEVEL;
   public static final BooleanValue GM_ADMIN_ENABLED;
   public static final ModConfigSpec SPEC;

   // Restore the exact <clinit> order from the official bytecode. Vineflower
   // moved the push/pop calls below the field definitions in its pristine output.
   static {
      BUILDER.push("gameMaster");
      GM_ENABLED = BUILDER.comment("Master switch for /gohome gamemaster (off by default)").define("enabled", false);
      GM_PERMISSION_LEVEL = BUILDER.comment("Permission level required for gamemaster commands")
         .defineInRange("permissionLevel", 2, 0, 4);
      BUILDER.push("administration");
      GM_ADMIN_ENABLED = BUILDER.comment("Enable sethome/clearhome/clearbed admin commands").define("enabled", true);
      BUILDER.pop();
      BUILDER.pop();
      SPEC = BUILDER.build();
   }

   public HomeConfig() {
   }

   static void register(ModContainer container) {
      // LOCAL replaces the former unsynchronized COMMON config. Keep its old
      // filename so existing server settings continue to load.
      container.registerConfig(Type.LOCAL, SPEC, "go_home_already-common.toml");
   }

   static boolean hasPermission(PermissionSet permissions, int level) {
      // Legacy hasPermissions(0) accepted ordinary sources, including no-op users.
      return level == 0 || permissions.hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
   }
}
