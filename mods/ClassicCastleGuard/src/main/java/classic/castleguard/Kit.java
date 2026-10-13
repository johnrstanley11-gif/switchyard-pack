/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 */
package classic.castleguard;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 1.7.0: /kit gives the starter kit, once every starterKitDays days (30) per player. Contents come from starterKit in
 * config/classic_castle_guard.properties as "item count" pairs separated by commas; the default is an Energy Collector
 * MK1 and an Energy Condenser (ProjectE), a full set of leather armour, the five stone tools and 32 melon slices.
 * Items are looked up by name when the kit is given, so this mod does not depend on ProjectE; a name that does not
 * exist is skipped and logged. When each player last took the kit is kept in
 * &lt;world&gt;/data/classic_castle_guard_kit_state.json.
 */
public final class Kit {
  private Kit() {}
  private static final Logger LOG = LoggerFactory.getLogger("classic_castle_guard");
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final String DEFAULT = "projecte:collector_mk1 1, projecte:condenser_mk1 1, minecraft:leather_helmet 1, minecraft:leather_chestplate 1,"
    + " minecraft:leather_leggings 1, minecraft:leather_boots 1, minecraft:stone_sword 1, minecraft:stone_pickaxe 1, minecraft:stone_axe 1,"
    + " minecraft:stone_shovel 1, minecraft:stone_hoe 1, minecraft:melon_slice 32";
  private static final String CONTENTS;
  private static final long WAIT;
  static {
    Properties values = new Properties();
    Path file = FMLPaths.CONFIGDIR.get().resolve("classic_castle_guard.properties");
    try {
      if (Files.isRegularFile(file)) { try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); } }
    } catch (Exception e) {
      LOG.error("Could not read {}; using the default starter kit", file, e);
    }
    CONTENTS = values.getProperty("starterKit", DEFAULT);
    int days = 30;
    try { days = Math.max(0, Math.min(3650, Integer.parseInt(values.getProperty("starterKitDays", "30").trim()))); }
    catch (RuntimeException e) { LOG.error("classic_castle_guard.properties: starterKitDays is not a whole number; using 30"); }
    WAIT = days * 24L * 60 * 60 * 1000;
  }
  private static Map<String, Long> taken = new LinkedHashMap<>();
  private static Path stateFile;

  @SubscribeEvent
  public static void onStarted(ServerStartedEvent event) {
    MinecraftServer server = event.getServer();
    stateFile = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("classic_castle_guard_kit_state.json");
    try {
      taken = Files.isRegularFile(stateFile) ? GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), new TypeToken<LinkedHashMap<String, Long>>() {}.getType()) : new LinkedHashMap<>();
    } catch (Exception e) {
      LOG.error("Could not load the starter kit record; starting with none", e); taken = null;
    }
    if (taken == null) taken = new LinkedHashMap<>();
    List<ItemStack> kit = contents();
    LOG.info("Starter kit: {} item types every {} days; {} players have taken it", kit.size(), WAIT / 86_400_000L, taken.size());
  }
  @SubscribeEvent
  public static void onStopping(ServerStoppingEvent event) { save(); }
  private static void save() {
    if (stateFile == null) return;
    try {
      Files.createDirectories(stateFile.getParent());
      Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
      Files.writeString(temp, GSON.toJson(taken), StandardCharsets.UTF_8);
      Files.move(temp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      LOG.error("Could not save the starter kit record", e);
    }
  }

  /** The kit as fresh stacks; names that are not items in this pack are left out. */
  private static List<ItemStack> contents() {
    List<ItemStack> stacks = new ArrayList<>();
    for (String entry : CONTENTS.split(",")) {
      String[] parts = entry.trim().split("\\s+");
      if (parts[0].isEmpty()) continue;
      try {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(parts[0]));
        int count = parts.length > 1 ? Math.max(1, Math.min(640, Integer.parseInt(parts[1]))) : 1;
        if (item == null || item == Items.AIR) { LOG.warn("Starter kit: there is no item called {}; skipped", parts[0]); continue; }
        while (count > 0) { ItemStack stack = new ItemStack(item, Math.min(count, item.getDefaultMaxStackSize())); count -= stack.getCount(); stacks.add(stack); }
      } catch (RuntimeException e) {
        LOG.warn("Starter kit: cannot read the entry \"{}\"; skipped", entry.trim());
      }
    }
    return stacks;
  }

  /** @return a message for the player. */
  static String claim(ServerPlayer player) {
    long now = System.currentTimeMillis();
    String id = player.getUUID().toString();
    Long last = taken.get(id);
    if (last != null && now - last < WAIT) return "You have had your starter kit. You can take it again in " + Plots.span(WAIT - (now - last)) + ".";
    List<ItemStack> kit = contents();
    if (kit.isEmpty()) return "The starter kit is empty on this server.";
    taken.put(id, now); save();          // recorded first: a full inventory or a crash must not allow a second kit
    for (ItemStack stack : kit) {
      if (!player.getInventory().add(stack) && !stack.isEmpty()) Containers.dropItemStack(player.level(), player.getX(), player.getY(), player.getZ(), stack);
    }
    LOG.info("Starter kit given to {}", player.getName().getString());
    return "Starter kit delivered: an Energy Collector and an Energy Condenser, leather armour, stone tools and melon. Anything that did not fit is at your feet."
      + (WAIT > 0 ? " You can take it again in " + Plots.span(WAIT) + "." : "");
  }

  /** Everyone: /kit. Operators: /kit reset &lt;player&gt; (lets an online player take it again now). */
  public static void register(RegisterCommandsEvent event) {
    event.getDispatcher().register(Commands.literal("kit")
      .executes(ctx -> { String answer = claim(ctx.getSource().getPlayerOrException()); ctx.getSource().sendSuccess(() -> Component.literal(answer), false); return 1; })
      .then(Commands.literal("reset").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        .then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
          ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"));
          if (target == null) { ctx.getSource().sendFailure(Component.literal("That player is not online.")); return 0; }
          taken.remove(target.getUUID().toString()); save();
          ctx.getSource().sendSuccess(() -> Component.literal(target.getName().getString() + " can take the starter kit again."), false);
          return 1;
        }))));
  }
}
