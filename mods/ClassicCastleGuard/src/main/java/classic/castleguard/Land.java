/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 */
package classic.castleguard;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 1.6.0: land that grows with time played, with weekly upkeep. The claiming itself (commands, map, protection) is Open
 * Parties and Claims; this class only decides how many chunks each player may hold and collects the upkeep.
 * <ul>
 * <li>Allowance: a square of landStartSide blocks (100) for a new player, growing evenly with hours online (AFK
 *     included; the vanilla play-time statistic) to landMaxSide blocks (5000) after landHoursToMax hours. It is an
 *     area, counted in chunks; the claimed chunks can have any shape.</li>
 * <li>Upkeep: one diamond a week for every landChunksPerDiamond claimed chunks (rounded up), taken from the player's
 *     land balance. /land pay moves diamonds (or diamond blocks) from the inventory into the balance; they go into the
 *     plot office's shop account chest. The first week after the first claim is free.</li>
 * <li>Unpaid: the player cannot claim more (their limit is what they hold) and their claims are open to everyone
 *     through the claims mod's access-override hook; nothing is removed.
 *     Paying (or unclaiming until the balance covers the week) brings the limit back within a minute.</li>
 * </ul>
 * State: &lt;world&gt;/data/classic_castle_guard_land_state.json. Settings: config/classic_castle_guard.properties.
 */
public final class Land {
  private Land() {}
  private static final Logger LOG = LoggerFactory.getLogger("classic_castle_guard");
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final long WEEK = Plots.WEEK, DAY = WEEK / 7;
  private static final boolean ENABLED;
  private static final int START_SIDE, MAX_SIDE, HOURS_TO_MAX, CHUNKS_PER_DIAMOND;
  static {
    Properties values = new Properties();
    Path file = FMLPaths.CONFIGDIR.get().resolve("classic_castle_guard.properties");
    try {
      if (Files.isRegularFile(file)) { try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); } }
    } catch (Exception e) {
      LOG.error("Could not read {}; using the default land settings", file, e);
    }
    ENABLED = !"false".equalsIgnoreCase(values.getProperty("landClaims", "true").trim());
    START_SIDE = number(values, "landStartSide", 100, 16, 100000);
    MAX_SIDE = number(values, "landMaxSide", 5000, START_SIDE, 100000);
    HOURS_TO_MAX = number(values, "landHoursToMax", 500, 1, 1000000);
    CHUNKS_PER_DIAMOND = number(values, "landChunksPerDiamond", 16, 1, 1000000);
  }
  private static int number(Properties values, String key, int fallback, int min, int max) {
    try { return Math.max(min, Math.min(max, Integer.parseInt(values.getProperty(key, Integer.toString(fallback)).trim()))); }
    catch (RuntimeException e) { LOG.error("classic_castle_guard.properties: {} is not a whole number; using {}", key, fallback); return fallback; }
  }

  /** One player's land account. Plain fields so Gson can read and write it. */
  static final class Account {
    String name; long playTicks; int balance; long dueAt; boolean lapsed;
    String chestDimension; int[] chest;
  }
  static final class State { Map<String, Account> accounts = new LinkedHashMap<>(); }
  private static State state = new State();
  private static Path stateFile;
  private static MinecraftServer server;
  private static boolean active, dirty;
  private static String lastProblem;

  // ------------------------------------------------------------------ loading and saving
  @SubscribeEvent
  public static void onStarted(ServerStartedEvent event) {
    server = event.getServer();
    active = ENABLED && ModList.get().isLoaded("openpartiesandclaims");
    stateFile = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("classic_castle_guard_land_state.json");
    try {
      state = Files.isRegularFile(stateFile) ? GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), State.class) : new State();
    } catch (Exception e) {
      LOG.error("Could not load the land accounts; starting with none", e); state = null;
    }
    if (state == null) state = new State();
    if (state.accounts == null) state.accounts = new LinkedHashMap<>();
    if (active) {
      try { LandClaims.registerUnpaidAccess(server); }
      catch (RuntimeException e) { LOG.error("Land: could not register with the claims mod; unpaid land will stay protected", e); }
    }
    LOG.info("Land claims: {}; {} x {} blocks growing to {} x {} over {} hours played; upkeep 1 diamond a week per {} chunks; {} accounts",
      active ? "active" : ENABLED ? "OFF (Open Parties and Claims is not installed)" : "OFF (landClaims=false)",
      START_SIDE, START_SIDE, MAX_SIDE, MAX_SIDE, HOURS_TO_MAX, CHUNKS_PER_DIAMOND, state.accounts.size());
  }
  @SubscribeEvent
  public static void onStopping(ServerStoppingEvent event) { save(); server = null; active = false; }
  private static void save() {
    if (stateFile == null) return;
    try {
      Files.createDirectories(stateFile.getParent());
      Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
      Files.writeString(temp, GSON.toJson(state), StandardCharsets.UTF_8);
      Files.move(temp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      dirty = false;
    } catch (Exception e) {
      LOG.error("Could not save the land accounts", e);
    }
  }

  // ------------------------------------------------------------------ the rules
  /** True when this player's land upkeep is unpaid, which opens their claims to everyone (see {@link LandClaims}). */
  static boolean unpaid(UUID owner) {
    Account account = active ? state.accounts.get(owner.toString()) : null;
    return account != null && account.lapsed;
  }
  /** Side of the allowed square, in blocks, after this much time online. */
  static int side(long playTicks) {
    double hours = playTicks / 72000.0;
    return (int) Math.min(MAX_SIDE, START_SIDE + (MAX_SIDE - START_SIDE) * hours / HOURS_TO_MAX);
  }
  static int allowedChunks(long playTicks) { long side = side(playTicks); return (int) ((side * side + 255) / 256); }
  static int weeklyCost(int claimedChunks) { return (claimedChunks + CHUNKS_PER_DIAMOND - 1) / CHUNKS_PER_DIAMOND; }

  /** Brings one account up to date: play time, the weekly charge, and the limit the claims mod applies. */
  private static void update(UUID id, Account account, ServerPlayer online) {
    long now = System.currentTimeMillis();
    if (online != null) {
      long ticks = online.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
      if (ticks != account.playTicks) { account.playTicks = ticks; dirty = true; }
      account.name = online.getName().getString();
    }
    int claimed = LandClaims.claimed(server, id), cost = weeklyCost(claimed);
    boolean wasLapsed = account.lapsed;
    if (claimed == 0) {
      if (account.dueAt != 0 || account.lapsed) { account.dueAt = 0; account.lapsed = false; dirty = true; }
    } else if (account.dueAt == 0) {
      account.dueAt = now + WEEK; dirty = true;                       // first week is free
    } else if (now >= account.dueAt) {
      // an owner who is already unpaid is only retried from a chest that happens to be loaded (no chunk loading every minute)
      if (account.balance < cost) drawFromChest(id, account, cost - account.balance, !account.lapsed);
      if (account.balance >= cost) {
        account.balance -= cost;
        account.dueAt = account.lapsed ? now + WEEK : account.dueAt + WEEK;
        account.lapsed = false; dirty = true;
        LOG.info("Land: {} charged {} diamonds for {} chunks, balance {}", account.name, cost, claimed, account.balance);
        if (online != null) online.sendSystemMessage(Component.literal("Land office: " + cost + " diamonds upkeep paid for " + claimed + " chunks. Balance " + account.balance + "."));
      } else if (!account.lapsed) {
        account.lapsed = true; dirty = true;
        LOG.info("Land: {} cannot pay {} diamonds for {} chunks (balance {}); claims unprotected", account.name, cost, claimed, account.balance);
      }
    }
    if (online != null && account.lapsed && !wasLapsed)
      online.sendSystemMessage(Component.literal("Land office: upkeep of " + cost + " diamonds is unpaid, so your land is NOT protected. Use /land pay."));
    // Never below what they hold: the claims mod locks a player who is over the limit out of every claim, their own
    // included, so they could not even restock their upkeep chest. Unpaid = no new claims, nothing more.
    String problem = LandClaims.setLimit(server, id, account.lapsed ? claimed : Math.max(allowedChunks(account.playTicks), claimed));
    if (problem != null && !problem.equals(lastProblem) && (online != null || claimed > 0)) {
      lastProblem = problem; LOG.warn("Land: could not set the claim limit of {}: {}", account.name, problem);
    }
  }
  private static Account account(ServerPlayer player) {
    return state.accounts.computeIfAbsent(player.getUUID().toString(), k -> { dirty = true; return new Account(); });
  }
  private static void updateAll() {
    for (ServerPlayer player : server.getPlayerList().getPlayers()) account(player);
    for (Map.Entry<String, Account> entry : state.accounts.entrySet()) {
      UUID id = UUID.fromString(entry.getKey());
      ServerPlayer online = server.getPlayerList().getPlayer(id);
      if (online == null && entry.getValue().dueAt == 0) continue;      // offline and nothing claimed: nothing can change
      try { update(id, entry.getValue(), online); }
      catch (RuntimeException e) { LOG.error("Land: could not update the account of {}", entry.getValue().name, e); }
    }
    if (dirty) save();
  }

  // ------------------------------------------------------------------ events
  private static int ticks;
  @SubscribeEvent
  public static void onTick(ServerTickEvent.Post event) {
    if (active && ++ticks % 1200 == 0) updateAll();                   // once a minute
  }
  @SubscribeEvent
  public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
    if (!active || !(event.getEntity() instanceof ServerPlayer player)) return;
    Account account = account(player);
    try { update(player.getUUID(), account, player); } catch (RuntimeException e) { LOG.error("Land: could not update {} at login", account.name, e); }
    if (dirty) save();
    long left = account.dueAt - System.currentTimeMillis();
    int cost = weeklyCost(LandClaims.claimed(server, player.getUUID()));
    if (account.lapsed) player.sendSystemMessage(Component.literal("Land office: upkeep of " + cost + " diamonds is unpaid, so your land is NOT protected. Use /land pay."));
    else if (account.dueAt > 0 && left < 2 * DAY && account.balance < cost)
      player.sendSystemMessage(Component.literal("Land office: " + cost + " diamonds upkeep is due in " + Plots.span(left) + " and your balance is " + account.balance + ". Use /land pay."));
  }

  // ------------------------------------------------------------------ upkeep chests (1.6.1)
  /**
   * Sneak-click with a diamond on a chest that stands on the player's own claimed land.
   * @return a message for the player, or null when the chest is not on land they have claimed.
   */
  static String markChest(ServerPlayer player, ServerLevel level, BlockPos pos) {
    if (!active || !player.getUUID().equals(LandClaims.owner(server, level.dimension().identifier(), pos))) return null;
    Account account = account(player);
    String dimension = level.dimension().identifier().toString();
    account.chestDimension = dimension; account.chest = new int[] {pos.getX(), pos.getY(), pos.getZ()};
    dirty = true;
    update(player.getUUID(), account, player);
    save();
    return "Land office: your weekly land upkeep (now " + weeklyCost(LandClaims.claimed(server, player.getUUID())) + " diamonds) is taken from this chest when it is due. Keep diamonds or diamond blocks in it. To stop, break the chest.";
  }
  /** Moves this many diamonds' worth from the account's upkeep chest into its balance (and the shop account), if it holds that much. */
  private static void drawFromChest(UUID id, Account account, int amount, boolean loadChunk) {
    if (account.chest == null || account.chestDimension == null || amount <= 0) return;
    ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(account.chestDimension)));
    BlockPos pos = new BlockPos(account.chest[0], account.chest[1], account.chest[2]);
    if (level == null) return;
    if (!level.isLoaded(pos)) { if (!loadChunk) return; level.getChunk(pos.getX() >> 4, pos.getZ() >> 4); }
    Container chest = Plots.container(level, pos);
    if (chest == null || !id.equals(LandClaims.owner(server, level.dimension().identifier(), pos))) {       // chest gone, or the land is no longer theirs
      account.chest = null; account.chestDimension = null; dirty = true; return;
    }
    if (Plots.worth(chest) < amount) return;
    int change = Plots.draw(chest, amount);
    Plots.deposit(amount); Plots.save();
    account.balance += amount + change; dirty = true;                  // change that did not fit back stays on the balance
    LOG.info("Land: {} diamonds taken from {}'s upkeep chest", amount, account.name);
  }

  // ------------------------------------------------------------------ paying
  private static int value(ServerPlayer player) {
    int value = 0;
    for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
      ItemStack stack = player.getInventory().getItem(slot);
      if (stack.is(Items.DIAMOND)) value += stack.getCount(); else if (stack.is(Items.DIAMOND_BLOCK)) value += 9 * stack.getCount();
    }
    return value;
  }
  /** Takes this many diamonds' worth: loose diamonds first, then diamond blocks, with change given in diamonds. */
  private static void take(ServerPlayer player, int amount) {
    int loose = Math.min(amount, Plots.diamonds(player));
    Plots.take(player, loose); amount -= loose;
    for (int slot = 0; slot < player.getInventory().getContainerSize() && amount > 0; slot++) {
      ItemStack stack = player.getInventory().getItem(slot);
      if (!stack.is(Items.DIAMOND_BLOCK)) continue;
      int blocks = Math.min(stack.getCount(), (amount + 8) / 9);
      stack.shrink(blocks); amount -= 9 * blocks;
    }
    player.getInventory().setChanged();
    if (amount < 0) Plots.give(player, -amount);
  }
  /** @param amount diamonds to pay in; 0 = what the next weekly charge still needs. */
  static String pay(ServerPlayer player, int amount) {
    Account account = account(player);
    int claimed = LandClaims.claimed(server, player.getUUID()), cost = weeklyCost(claimed);
    if (amount == 0) amount = cost - account.balance;
    if (amount <= 0) return "Your land balance of " + account.balance + " diamonds already covers the next upkeep of " + cost + ". To pay ahead: /land pay <diamonds>.";
    int have = value(player);
    if (have < amount) return "You need " + amount + " diamonds (diamond blocks count as 9); you carry " + have + ".";
    take(player, amount); Plots.deposit(amount); Plots.save();
    account.balance += amount; dirty = true;
    LOG.info("Land: {} paid in {} diamonds, balance {}", player.getName().getString(), amount, account.balance);
    update(player.getUUID(), account, player);
    save();
    return amount + " diamonds paid in. " + describe(player.getUUID(), account);
  }
  static String describe(UUID id, Account account) {
    int claimed = LandClaims.claimed(server, id), allowed = allowedChunks(account.playTicks), side = side(account.playTicks), cost = weeklyCost(claimed);
    String text = "Land: " + (account.playTicks / 72000) + " hours played = " + side + " x " + side + " blocks (" + allowed + " chunks); " + claimed + " chunks claimed. Upkeep "
      + cost + " diamonds a week, balance " + account.balance + ". ";
    if (account.chest != null) text += "Upkeep chest at " + account.chest[0] + " " + account.chest[1] + " " + account.chest[2] + ". ";
    if (account.lapsed) return text + "UNPAID: your land is not protected until you /land pay.";
    if (claimed == 0) return text + "Claim with /oclaims claim or the claims map.";
    return text + "Next charge in " + Plots.span(account.dueAt - System.currentTimeMillis()) + ".";
  }

  // ------------------------------------------------------------------ commands
  /** Everyone: /land, /land pay [diamonds]. Operators: /land info &lt;player&gt;, /land credit &lt;player&gt; &lt;diamonds&gt;,
   *  /land charge &lt;player&gt; (take the weekly upkeep now instead of waiting for its date). */
  public static void register(RegisterCommandsEvent event) {
    event.getDispatcher().register(Commands.literal("land")
      .executes(ctx -> { if (off(ctx)) return 0; ServerPlayer player = ctx.getSource().getPlayerOrException(); Account account = account(player); update(player.getUUID(), account, player); return reply(ctx, describe(player.getUUID(), account)); })
      .then(Commands.literal("pay")
        .executes(ctx -> off(ctx) ? 0 : reply(ctx, pay(ctx.getSource().getPlayerOrException(), 0)))
        .then(Commands.argument("diamonds", IntegerArgumentType.integer(1, 1000000)).executes(ctx -> off(ctx) ? 0 : reply(ctx, pay(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "diamonds"))))))
      .then(Commands.literal("info").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        .then(Commands.argument("player", StringArgumentType.word()).executes(ctx -> {
          if (off(ctx)) return 0;
          Map.Entry<String, Account> found = named(ctx);
          return found == null ? 0 : reply(ctx, found.getValue().name + ". " + describe(UUID.fromString(found.getKey()), found.getValue()) + " Limit applied by the claims mod: " + LandClaims.limit(server, UUID.fromString(found.getKey())) + " chunks.");
        })))
      .then(Commands.literal("charge").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        .then(Commands.argument("player", StringArgumentType.word()).executes(ctx -> {
          if (off(ctx)) return 0;
          Map.Entry<String, Account> found = named(ctx);
          if (found == null) return 0;
          Account account = found.getValue(); UUID id = UUID.fromString(found.getKey());
          if (account.dueAt == 0) return reply(ctx, account.name + " has no claimed land, so there is nothing to charge.");
          account.dueAt = System.currentTimeMillis();
          update(id, account, server.getPlayerList().getPlayer(id)); save();
          return reply(ctx, account.name + ": weekly upkeep charged now. " + describe(id, account));
        })))
      .then(Commands.literal("credit").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        .then(Commands.argument("player", StringArgumentType.word()).then(Commands.argument("diamonds", IntegerArgumentType.integer(-1000000, 1000000)).executes(ctx -> {
          if (off(ctx)) return 0;
          Map.Entry<String, Account> found = named(ctx);
          if (found == null) return 0;
          Account account = found.getValue(); UUID id = UUID.fromString(found.getKey());
          account.balance = Math.max(0, account.balance + IntegerArgumentType.getInteger(ctx, "diamonds"));
          update(id, account, server.getPlayerList().getPlayer(id)); save();
          return reply(ctx, account.name + ": land balance is now " + account.balance + " diamonds.");
        })))));
  }
  private static boolean off(CommandContext<CommandSourceStack> ctx) {
    if (active) return false;
    ctx.getSource().sendFailure(Component.literal("Land claims are switched off on this server."));
    return true;
  }
  private static Map.Entry<String, Account> named(CommandContext<CommandSourceStack> ctx) {
    String name = StringArgumentType.getString(ctx, "player");
    for (Map.Entry<String, Account> entry : state.accounts.entrySet()) if (name.equalsIgnoreCase(entry.getValue().name)) return entry;
    ctx.getSource().sendFailure(Component.literal("No land account for " + name + " (they have not joined since land claims were added)."));
    return null;
  }
  private static int reply(CommandContext<CommandSourceStack> ctx, String text) {
    ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    return 1;
  }
}
