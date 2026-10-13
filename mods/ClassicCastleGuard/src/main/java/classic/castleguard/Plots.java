/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 */
package classic.castleguard;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 1.5.0: rented spawn plots. Plot shapes, signs and weekly prices come from config/classic_castle_guard_plots.json
 * (written by the spawn builder); who rents what is kept in &lt;world&gt;/data/classic_castle_guard_plots_state.json.
 * <ul>
 * <li>Rent: right-click the plot sign. The weekly price in diamonds is taken from the player and put into the shop
 *     account chest; each payment adds 7 days, at most 28 days ahead. One plot per player.</li>
 * <li>Only the renter, while paid up, may build in the plot. Unpaid: the plot is locked, the build stays, the renter can
 *     pay to get it back, and an operator can put it back up for rent (/plot release).</li>
 * <li>Auction: an operator opens one on a free plot; players bid diamonds (held by the server, the outbid player is
 *     refunded); the winner gets the plot with the first week paid by the bid.</li>
 * </ul>
 */
public final class Plots {
  private Plots() {}
  private static final Logger LOG = LoggerFactory.getLogger("classic_castle_guard");
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  static final long WEEK = 7L * 24 * 60 * 60 * 1000, MAX_AHEAD = 4 * WEEK, SNIPE = 5L * 60 * 1000;
  static final int BID_STEP = 8;

  /** A plot as laid out in the world. */
  record Plot(String name, String size, int xMin, int zMin, int xMax, int zMax, BlockPos sign, int price) {
    boolean contains(BlockPos pos) { return pos.getX() >= xMin && pos.getX() <= xMax && pos.getZ() >= zMin && pos.getZ() <= zMax; }
  }
  /** Who holds a plot. Plain fields so Gson can read and write it. */
  static final class Tenure {
    String owner, ownerName; long paidUntil;
    int[] payChest;
    long auctionEnds; int minBid, bid; String bidder, bidderName;
    boolean auction() { return auctionEnds > 0; }
  }
  static final class State {
    int[] shopChest;
    Map<String, Tenure> plots = new LinkedHashMap<>();
    Map<String, Integer> refunds = new LinkedHashMap<>();
    int undeposited;
  }
  private static final Map<String, Plot> PLOTS = new LinkedHashMap<>();
  private static State state = new State();
  private static Path stateFile;
  private static MinecraftServer server;

  // ------------------------------------------------------------------ loading and saving
  @SubscribeEvent
  public static void onStarted(ServerStartedEvent event) {
    server = event.getServer();
    PLOTS.clear();
    Path definitions = FMLPaths.CONFIGDIR.get().resolve("classic_castle_guard_plots.json");
    try {
      if (Files.isRegularFile(definitions)) {
        JsonArray list = JsonParser.parseString(Files.readString(definitions, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("plots");
        for (JsonElement element : list) {
          JsonObject o = element.getAsJsonObject(); JsonArray s = o.getAsJsonArray("sign");
          PLOTS.put(o.get("name").getAsString(), new Plot(o.get("name").getAsString(), o.get("size").getAsString(), o.get("xMin").getAsInt(), o.get("zMin").getAsInt(),
            o.get("xMax").getAsInt(), o.get("zMax").getAsInt(), new BlockPos(s.get(0).getAsInt(), s.get(1).getAsInt(), s.get(2).getAsInt()), o.get("price").getAsInt()));
        }
      }
      stateFile = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("classic_castle_guard_plots_state.json");
      state = Files.isRegularFile(stateFile) ? GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), State.class) : new State();
      if (state == null) state = new State();
      if (state.plots == null) state.plots = new LinkedHashMap<>();
      if (state.refunds == null) state.refunds = new LinkedHashMap<>();
    } catch (Exception e) {
      LOG.error("Could not load the spawn plots; every plot stays locked", e);
      PLOTS.clear(); state = new State();
    }
    LOG.info("Spawn plots: {} defined, {} held, shop account chest {}", PLOTS.size(), state.plots.values().stream().filter(t -> t.owner != null).count(),
      state.shopChest == null ? "NOT SET (/plot shopchest x y z)" : state.shopChest[0] + " " + state.shopChest[1] + " " + state.shopChest[2]);
    PLOTS.values().forEach(Plots::refreshSign);
  }
  @SubscribeEvent
  public static void onStopping(ServerStoppingEvent event) { save(); server = null; }
  static void save() {
    if (stateFile == null) return;
    try {
      Files.createDirectories(stateFile.getParent());
      Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
      Files.writeString(temp, GSON.toJson(state), StandardCharsets.UTF_8);
      Files.move(temp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      LOG.error("Could not save the spawn plot state", e);
    }
  }

  // ------------------------------------------------------------------ lookups
  static Plot byName(String name) { return PLOTS.get(name); }
  static java.util.Collection<Plot> all() { return PLOTS.values(); }
  static Plot at(BlockPos pos) {
    for (Plot plot : PLOTS.values()) if (plot.contains(pos)) return plot;
    return null;
  }
  static Tenure tenure(Plot plot) { return state.plots.computeIfAbsent(plot.name(), k -> new Tenure()); }
  static Plot heldBy(UUID player) {
    String id = player.toString();
    for (Plot plot : PLOTS.values()) {
      Tenure t = state.plots.get(plot.name());
      if (t != null && (id.equals(t.owner) || (t.auction() && id.equals(t.bidder)))) return plot;
    }
    return null;
  }
  /** True when this player may build at this position: it is inside a plot they rent and the rent is paid. */
  public static boolean mayBuild(Player player, BlockPos pos) {
    if (player == null) return false;
    Plot plot = at(pos);
    if (plot == null) return false;
    Tenure t = state.plots.get(plot.name());
    return t != null && player.getUUID().toString().equals(t.owner) && t.paidUntil > System.currentTimeMillis();
  }
  public static boolean isShopChest(BlockPos pos) {
    return state.shopChest != null && pos.getX() == state.shopChest[0] && pos.getY() == state.shopChest[1] && pos.getZ() == state.shopChest[2];
  }
  /** The chest's chunk is kept loaded so rent can be paid from anywhere in the district. */
  static void setShopChest(BlockPos pos) {
    state.shopChest = new int[] {pos.getX(), pos.getY(), pos.getZ()}; save();
    if (server != null) server.overworld().setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
  }

  // ------------------------------------------------------------------ diamonds
  static int diamonds(Player player) {
    int count = 0;
    for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
      ItemStack stack = player.getInventory().getItem(slot);
      if (stack.is(Items.DIAMOND)) count += stack.getCount();
    }
    return count;
  }
  static void take(Player player, int amount) {
    for (int slot = 0; slot < player.getInventory().getContainerSize() && amount > 0; slot++) {
      ItemStack stack = player.getInventory().getItem(slot);
      if (stack.is(Items.DIAMOND)) { int n = Math.min(amount, stack.getCount()); stack.shrink(n); amount -= n; }
    }
    player.getInventory().setChanged();
  }
  static void give(ServerPlayer player, int amount) {
    while (amount > 0) {
      ItemStack stack = new ItemStack(Items.DIAMOND, Math.min(64, amount)); amount -= stack.getCount();
      if (!player.getInventory().add(stack) && !stack.isEmpty()) net.minecraft.world.Containers.dropItemStack(player.level(), player.getX(), player.getY(), player.getZ(), stack);
    }
  }
  /** Pays diamonds back: straight away when the player is online, otherwise at their next login. */
  static void refund(String uuid, int amount) {
    if (uuid == null || amount <= 0) return;
    ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(UUID.fromString(uuid));
    if (online != null) { give(online, amount); online.sendSystemMessage(Component.literal("Plot office: " + amount + " diamonds returned to you.")); }
    else state.refunds.merge(uuid, amount, Integer::sum);
  }
  private static Container shopChest() {
    if (state.shopChest == null || server == null) return null;
    ServerLevel level = server.overworld();
    BlockPos pos = new BlockPos(state.shopChest[0], state.shopChest[1], state.shopChest[2]);
    return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof Container container ? container : null;
  }
  private static int room(Container chest) {
    int room = 0;
    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
      ItemStack stack = chest.getItem(slot);
      if (stack.isEmpty()) room += 64; else if (stack.is(Items.DIAMOND)) room += Math.max(0, 64 - stack.getCount());
    }
    return room;
  }
  /** Puts diamonds into the shop account chest; what does not fit (or has no chest) is remembered as undeposited. */
  static void deposit(int amount) {
    Container chest = shopChest();
    for (int slot = 0; chest != null && slot < chest.getContainerSize() && amount > 0; slot++) {
      ItemStack stack = chest.getItem(slot);
      if (stack.isEmpty()) { int n = Math.min(64, amount); chest.setItem(slot, new ItemStack(Items.DIAMOND, n)); amount -= n; }
      else if (stack.is(Items.DIAMOND) && stack.getCount() < 64) { int n = Math.min(64 - stack.getCount(), amount); stack.grow(n); amount -= n; }
    }
    if (chest != null) chest.setChanged();
    if (amount > 0) { state.undeposited += amount; LOG.warn("Shop account chest missing or full: {} diamonds recorded as undeposited (total {})", amount, state.undeposited); }
  }
  static int undeposited() { return state.undeposited; }
  /** Operator: move remembered diamonds into the chest once there is room again. */
  static int flushUndeposited() {
    int owed = state.undeposited; state.undeposited = 0; deposit(owed); save();
    return owed - state.undeposited;
  }

  // ------------------------------------------------------------------ pay chests (1.6.1)
  /** The container at a position; both halves of a double chest. Null when there is none or the place is not loaded. */
  static Container container(ServerLevel level, BlockPos pos) {
    if (!level.isLoaded(pos)) return null;
    BlockState blockState = level.getBlockState(pos);
    if (blockState.getBlock() instanceof ChestBlock chest) return ChestBlock.getContainer(chest, blockState, level, pos, true);
    return level.getBlockEntity(pos) instanceof Container container ? container : null;
  }
  /** Diamonds' worth in a container: diamonds, plus 9 for every diamond block. */
  static int worth(Container chest) {
    int worth = 0;
    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
      ItemStack stack = chest.getItem(slot);
      if (stack.is(Items.DIAMOND)) worth += stack.getCount(); else if (stack.is(Items.DIAMOND_BLOCK)) worth += 9 * stack.getCount();
    }
    return worth;
  }
  /**
   * Takes this many diamonds' worth out of a container that holds at least that much: diamonds first, then diamond
   * blocks, with the change put back as diamonds.
   * @return change that did not fit back into the container (the caller owes it to the owner).
   */
  static int draw(Container chest, int amount) {
    for (int pass = 0; pass < 2 && amount > 0; pass++) {
      for (int slot = 0; slot < chest.getContainerSize() && amount > 0; slot++) {
        ItemStack stack = chest.getItem(slot);
        if (pass == 0 && stack.is(Items.DIAMOND)) { int n = Math.min(amount, stack.getCount()); stack.shrink(n); amount -= n; }
        else if (pass == 1 && stack.is(Items.DIAMOND_BLOCK)) { int n = Math.min(stack.getCount(), (amount + 8) / 9); stack.shrink(n); amount -= 9 * n; }
      }
    }
    int change = -amount;
    for (int slot = 0; slot < chest.getContainerSize() && change > 0; slot++) {
      ItemStack stack = chest.getItem(slot);
      if (stack.isEmpty()) { int n = Math.min(64, change); chest.setItem(slot, new ItemStack(Items.DIAMOND, n)); change -= n; }
      else if (stack.is(Items.DIAMOND) && stack.getCount() < 64) { int n = Math.min(64 - stack.getCount(), change); stack.grow(n); change -= n; }
    }
    chest.setChanged();
    return Math.max(0, change);
  }
  private static final Map<UUID, Long> LAST_MARK = new java.util.HashMap<>();
  /** A held mouse button sends the click several times; only the first within two seconds counts. */
  private static boolean firstClick(ServerPlayer player) {
    long now = System.currentTimeMillis();
    Long last = LAST_MARK.put(player.getUUID(), now);
    return last == null || now - last > 2000;
  }
  /** Sneak-click with a diamond on a chest. @return a message for the player. */
  private static String markChest(ServerPlayer player, ServerLevel level, BlockPos pos) {
    Plot plot = at(pos);
    Tenure t = plot == null ? null : state.plots.get(plot.name());
    if (t != null && player.getUUID().toString().equals(t.owner)) {
      t.payChest = new int[] {pos.getX(), pos.getY(), pos.getZ()}; save();
      return "Plot office: rent on " + plot.name() + " (" + plot.price() + " diamonds a week) is taken from this chest when it is due. Keep diamonds or diamond blocks in it. To stop, break the chest.";
    }
    String land = Land.markChest(player, level, pos);
    if (land != null) return land;
    if (CitadelProtection.exempt(player)) {
      setShopChest(pos);
      return "Plot office: rent, winning bids and land upkeep now go into this chest (" + pos.toShortString() + "). Only operators can open it.";
    }
    return "A pay chest has to stand on a plot you rent or on land you have claimed.";
  }
  /** Once a minute: rent that is due within a day (or overdue) is taken from the plot's pay chest, if it holds enough. */
  private static void payFromChests() {
    long now = System.currentTimeMillis();
    for (Plot plot : PLOTS.values()) {
      Tenure t = state.plots.get(plot.name());
      if (t == null || t.owner == null || t.payChest == null || t.auction() || t.paidUntil - now > WEEK / 7) continue;
      BlockPos pos = new BlockPos(t.payChest[0], t.payChest[1], t.payChest[2]);
      ServerLevel level = server.overworld();
      if (!level.isLoaded(pos)) continue;
      Container chest = container(level, pos);
      if (chest == null || !plot.contains(pos)) { t.payChest = null; save(); continue; }
      if (worth(chest) < plot.price()) continue;
      int change = draw(chest, plot.price());
      if (change > 0) refund(t.owner, change);
      deposit(plot.price());
      t.paidUntil = Math.max(now, t.paidUntil) + WEEK;
      save(); refreshSign(plot);
      LOG.info("Plot {}: {} diamonds taken from {}'s pay chest, paid until {}", plot.name(), plot.price(), t.ownerName, new java.util.Date(t.paidUntil));
      ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(t.owner));
      if (online != null) online.sendSystemMessage(Component.literal("Plot office: " + plot.price() + " diamonds rent for " + plot.name() + " taken from your pay chest; " + span(t.paidUntil - now) + " left."));
    }
  }

  // ------------------------------------------------------------------ renting
  /** @return a message for the player. */
  static String rent(ServerPlayer player, Plot plot) {
    long now = System.currentTimeMillis();
    Tenure t = tenure(plot);
    String id = player.getUUID().toString();
    if (t.auction()) return plot.name() + " is being auctioned: " + (t.bid > 0 ? t.bid + " diamonds by " + t.bidderName : "no bids, minimum " + t.minBid) + ", ends in " + span(t.auctionEnds - now) + ". Bid with /plot bid <diamonds>.";
    if (t.owner != null && !id.equals(t.owner)) return plot.name() + " is rented by " + t.ownerName + (t.paidUntil > now ? " (" + span(t.paidUntil - now) + " left)." : " (rent unpaid; ask an admin if you want it).");
    if (t.owner == null) { Plot other = heldBy(player.getUUID()); if (other != null) return "One plot per player: you already hold " + other.name() + "."; }
    if (t.owner != null && t.paidUntil - now > MAX_AHEAD - WEEK) return plot.name() + " is paid for " + span(t.paidUntil - now) + "; you can pay at most 4 weeks ahead.";
    if (diamonds(player) < plot.price()) return "A week on " + plot.name() + " costs " + plot.price() + " diamonds; you have " + diamonds(player) + ".";
    Container chest = shopChest();
    if (chest == null || room(chest) < plot.price()) return "The plot office cannot take payment right now (shop account chest missing or full). Tell an admin.";
    take(player, plot.price()); deposit(plot.price());
    boolean fresh = t.owner == null;
    t.owner = id; t.ownerName = player.getName().getString(); t.paidUntil = Math.max(now, t.paidUntil) + WEEK;
    save(); refreshSign(plot);
    LOG.info("Plot {}: {} paid {} diamonds, paid until {}", plot.name(), t.ownerName, plot.price(), new java.util.Date(t.paidUntil));
    return (fresh ? "You now rent " : "Rent paid for ") + plot.name() + ": " + plot.price() + " diamonds, " + span(t.paidUntil - now) + " left. Only you can build here.";
  }
  /** Operator: back up for rent. Blocks in the plot are left as they are. */
  static String release(Plot plot) {
    Tenure t = tenure(plot);
    String was = t.ownerName;
    if (t.auction()) refund(t.bidder, t.bid);
    state.plots.put(plot.name(), new Tenure());
    save(); refreshSign(plot);
    return plot.name() + " is for rent again" + (was == null ? "." : " (was " + was + "). Its blocks were not touched.");
  }
  static String grant(Plot plot, ServerPlayer player, int days) {
    Tenure t = tenure(plot);
    if (t.auction()) return "Cancel the auction first (/plot release " + plot.name() + ").";
    t.owner = player.getUUID().toString(); t.ownerName = player.getName().getString(); t.paidUntil = System.currentTimeMillis() + days * 24L * 60 * 60 * 1000;
    save(); refreshSign(plot);
    return plot.name() + " given to " + t.ownerName + " for " + days + " days.";
  }

  // ------------------------------------------------------------------ auctions
  static String startAuction(Plot plot, int hours, int minBid) {
    Tenure t = tenure(plot);
    if (t.owner != null) return plot.name() + " is rented by " + t.ownerName + "; release it first.";
    if (t.auction()) return plot.name() + " is already being auctioned.";
    t.auctionEnds = System.currentTimeMillis() + hours * 60L * 60 * 1000; t.minBid = minBid; t.bid = 0; t.bidder = null; t.bidderName = null;
    save(); refreshSign(plot);
    say("Plot auction: " + plot.name() + " (" + plot.size() + ") is up for auction for " + hours + " hours, minimum bid " + minBid + " diamonds. Stand in it and use /plot bid <diamonds>.");
    return "Auction started for " + plot.name() + ".";
  }
  static String bid(ServerPlayer player, Plot plot, int amount) {
    long now = System.currentTimeMillis();
    Tenure t = tenure(plot);
    String id = player.getUUID().toString();
    if (!t.auction()) return plot.name() + " is not being auctioned.";
    Plot other = heldBy(player.getUUID());
    if (other != null && other != plot) return "One plot per player: you already hold or lead " + other.name() + ".";
    int needed = t.bid > 0 ? t.bid + BID_STEP : t.minBid;
    if (amount < needed) return "The next bid on " + plot.name() + " must be at least " + needed + " diamonds.";
    int extra = id.equals(t.bidder) ? amount - t.bid : amount;      // raising your own bid only costs the difference
    if (diamonds(player) < extra) return "You need " + extra + " diamonds for that bid; you have " + diamonds(player) + ".";
    take(player, extra);
    if (!id.equals(t.bidder)) refund(t.bidder, t.bid);
    t.bid = amount; t.bidder = id; t.bidderName = player.getName().getString();
    if (t.auctionEnds - now < SNIPE) t.auctionEnds = now + SNIPE;
    save(); refreshSign(plot);
    say("Plot auction: " + t.bidderName + " bids " + amount + " diamonds for " + plot.name() + " (ends in " + span(t.auctionEnds - now) + ").");
    return "Your bid of " + amount + " diamonds is the highest. The diamonds are held until you are outbid or win.";
  }
  private static void finishAuction(Plot plot, Tenure t) {
    if (t.bidder == null) say("Plot auction: nobody bid on " + plot.name() + "; it is for rent at " + plot.price() + " diamonds a week.");
    else {
      deposit(t.bid);
      t.owner = t.bidder; t.ownerName = t.bidderName; t.paidUntil = System.currentTimeMillis() + WEEK;
      say("Plot auction: " + t.ownerName + " wins " + plot.name() + " for " + t.bid + " diamonds. The first week is paid; after that rent is " + plot.price() + " a week.");
    }
    t.auctionEnds = 0; t.minBid = 0; t.bid = 0; t.bidder = null; t.bidderName = null;
    save(); refreshSign(plot);
  }
  private static void say(String text) {
    if (server != null) server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
    LOG.info(text);
  }

  // ------------------------------------------------------------------ events
  private static int ticks;
  @SubscribeEvent
  public static void onTick(ServerTickEvent.Post event) {
    if (++ticks % 20 != 0 || PLOTS.isEmpty()) return;
    long now = System.currentTimeMillis();
    for (Plot plot : PLOTS.values()) {
      Tenure t = state.plots.get(plot.name());
      if (t != null && t.auction() && t.auctionEnds <= now) finishAuction(plot, t);
    }
    if (ticks % 1200 == 0) { payFromChests(); PLOTS.values().forEach(Plots::refreshSign); }     // pay chests and time-left lines, once a minute
  }
  @SubscribeEvent
  public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return;
    Integer owed = state.refunds.remove(player.getUUID().toString());
    if (owed != null && owed > 0) { give(player, owed); player.sendSystemMessage(Component.literal("Plot office: " + owed + " diamonds returned to you (you were outbid or an auction was cancelled).")); save(); }
    Plot plot = heldBy(player.getUUID());
    if (plot != null) {
      Tenure t = tenure(plot); long left = t.paidUntil - System.currentTimeMillis();
      if (player.getUUID().toString().equals(t.owner) && left < 2L * 24 * 60 * 60 * 1000)
        player.sendSystemMessage(Component.literal(left > 0 ? "Plot office: rent on " + plot.name() + " runs out in " + span(left) + ". Right-click its sign to pay."
          : "Plot office: rent on " + plot.name() + " is unpaid and the plot is locked. Right-click its sign to pay " + plot.price() + " diamonds."));
    }
  }
  /** Right-click on a plot sign = rent or pay; also keeps players out of the shop account chest. */
  @SubscribeEvent
  public static void onUse(PlayerInteractEvent.RightClickBlock event) {
    if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
    if (level != level.getServer().overworld()) {
      // land can be claimed in any dimension, so its upkeep chest can be marked there too
      if (player.isShiftKeyDown() && player.getMainHandItem().is(Items.DIAMOND) && container(level, event.getPos()) != null) {
        String answer = Land.markChest(player, level, event.getPos().immutable());
        if (answer != null) { event.setCanceled(true); if (event.getHand() == InteractionHand.MAIN_HAND && firstClick(player)) player.sendSystemMessage(Component.literal(answer)); }
      }
      return;
    }
    // 1.6.1: sneaking with a diamond in hand marks the clicked chest: the pay chest of your own plot, the upkeep chest
    // of your own land, or (operators, anywhere else) the shop account chest.
    if (player.isShiftKeyDown() && player.getMainHandItem().is(Items.DIAMOND) && container(level, event.getPos()) != null) {
      event.setCanceled(true);
      if (event.getHand() == InteractionHand.MAIN_HAND && firstClick(player)) player.sendSystemMessage(Component.literal(markChest(player, level, event.getPos().immutable())));
      return;
    }
    if (isShopChest(event.getPos()) && !CitadelProtection.exempt(player)) {
      event.setCanceled(true); player.sendOverlayMessage(Component.literal("That is the plot office's account.")); return;
    }
    for (Plot plot : PLOTS.values()) {
      if (plot.sign().equals(event.getPos())) {
        event.setCanceled(true);
        if (event.getHand() == InteractionHand.MAIN_HAND) player.sendSystemMessage(Component.literal(rent(player, plot)));
        return;
      }
    }
  }

  // ------------------------------------------------------------------ signs and text
  static String span(long millis) {
    long minutes = Math.max(0, millis) / 60000;
    // rounded to the nearest unit, so a week just paid reads "7 days", not "6 days"
    if (minutes >= 2160) return ((minutes + 720) / 1440) + " days";
    if (minutes >= 90) return ((minutes + 30) / 60) + " hours";
    return Math.max(1, minutes) + " min";
  }
  static String describe(Plot plot) {
    Tenure t = state.plots.get(plot.name()); long now = System.currentTimeMillis();
    String head = plot.name() + " (" + plot.size() + ", " + plot.price() + " diamonds a week): ";
    if (t == null || (t.owner == null && !t.auction())) return head + "for rent.";
    if (t.auction()) return head + "auction, " + (t.bid > 0 ? t.bid + " by " + t.bidderName : "no bids, minimum " + t.minBid) + ", ends in " + span(t.auctionEnds - now) + ".";
    return head + "rented by " + t.ownerName + (t.paidUntil > now ? ", " + span(t.paidUntil - now) + " left." : ", UNPAID (locked).");
  }
  static void refreshSign(Plot plot) {
    if (server == null) return;
    ServerLevel level = server.overworld();
    if (!level.isLoaded(plot.sign()) || !(level.getBlockEntity(plot.sign()) instanceof SignBlockEntity sign)) return;
    Tenure t = state.plots.get(plot.name()); long now = System.currentTimeMillis();
    String size = Character.toUpperCase(plot.size().charAt(0)) + plot.size().substring(1);
    String[] lines = {plot.name(), size + " " + plot.price() + "/week", "For rent", "Right-click"};
    if (t != null && t.auction()) { lines[2] = t.bid > 0 ? "Bid " + t.bid + " " + t.bidderName : "Auction min " + t.minBid; lines[3] = "ends " + span(t.auctionEnds - now); }
    else if (t != null && t.owner != null) { lines[2] = t.ownerName; lines[3] = t.paidUntil > now ? span(t.paidUntil - now) + " left" : "RENT UNPAID"; }
    SignText text = sign.getText(SignTextSlot.FRONT);
    SignText.Mutable changed = text.asMutable();
    for (int i = 0; i < 4; i++) changed.setLine(i, Component.literal(lines[i]));
    SignText result = changed.asImmutable();
    if (!result.equals(text)) {
      sign.setText(result, SignTextSlot.FRONT); sign.setChanged();
      BlockState blockState = level.getBlockState(plot.sign());
      level.sendBlockUpdated(plot.sign(), blockState, blockState, 3);
    }
  }
  static List<String> listing() {
    List<String> lines = new ArrayList<>();
    for (Plot plot : PLOTS.values()) lines.add(describe(plot));
    return lines;
  }
}
