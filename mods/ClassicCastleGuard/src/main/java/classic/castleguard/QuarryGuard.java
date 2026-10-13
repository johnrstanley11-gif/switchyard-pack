/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 */
package classic.castleguard;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 1.8.0: rules for QuarryPlus machines.
 * <ul>
 * <li>The Chunk Destroyer (quarryplus:adv_quarry) cannot be placed at all (banChunkDestroyer, default true).</li>
 * <li>A quarry or one of its markers cannot be placed within quarryClaimDistance blocks (default 64, the largest
 *     quarry the server's quarryplus.toml allows) of a chunk that somebody else has claimed, the spawn district
 *     included, so a quarry cannot be set up to dig into a neighbour's land. Your own claims do not count.</li>
 * </ul>
 * Settings are in config/classic_castle_guard.properties. Operators are not exempt: the rules are about lag and
 * neighbours, not about rank. A block placed by a machine rather than a player is refused near any claim.
 */
public final class QuarryGuard {
  private QuarryGuard() {}
  private static final Logger LOG = LoggerFactory.getLogger("classic_castle_guard");
  private static final Set<String> AREA_BLOCKS = Set.of("quarry", "marker", "flexible_marker", "chunk_marker");
  private static final boolean BAN_DESTROYER;
  private static final int DISTANCE;
  static {
    Properties values = new Properties();
    Path file = FMLPaths.CONFIGDIR.get().resolve("classic_castle_guard.properties");
    try {
      if (Files.isRegularFile(file)) { try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); } }
    } catch (Exception e) {
      LOG.error("Could not read {}; using the default quarry rules", file, e);
    }
    BAN_DESTROYER = !"false".equalsIgnoreCase(values.getProperty("banChunkDestroyer", "true").trim());
    int distance = 64;
    try { distance = Math.max(0, Math.min(512, Integer.parseInt(values.getProperty("quarryClaimDistance", "64").trim()))); }
    catch (RuntimeException e) { LOG.error("classic_castle_guard.properties: quarryClaimDistance is not a whole number; using 64"); }
    DISTANCE = distance;
    LOG.info("Quarry rules: Chunk Destroyer {}; no quarry or marker within {} blocks of another player's claim", BAN_DESTROYER ? "banned" : "allowed", DISTANCE);
  }

  @SubscribeEvent
  public static void onPlace(BlockEvent.EntityPlaceEvent event) {
    if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
    Identifier id = BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock());
    if (id == null || !"quarryplus".equals(id.getNamespace())) return;
    ServerPlayer player = event.getEntity() instanceof ServerPlayer placer ? placer : null;
    if (BAN_DESTROYER && "adv_quarry".equals(id.getPath())) {
      event.setCanceled(true);
      if (player != null) player.sendSystemMessage(Component.literal("The Chunk Destroyer is not allowed on this server. Use a quarry instead."));
      return;
    }
    if (DISTANCE <= 0 || !AREA_BLOCKS.contains(id.getPath()) || !ModList.get().isLoaded("openpartiesandclaims")) return;
    String neighbour;
    try { neighbour = LandClaims.foreignClaimNear(level.getServer(), level.dimension().identifier(), event.getPos(), DISTANCE, player == null ? null : player.getUUID()); }
    catch (RuntimeException e) { LOG.error("Quarry rules: could not check the claims near {}", event.getPos().toShortString(), e); return; }
    if (neighbour == null) return;
    event.setCanceled(true);
    if (player != null) player.sendSystemMessage(Component.literal("Too close to land claimed by " + neighbour + ": quarries and their markers must be at least " + DISTANCE + " blocks from other players' claims."));
  }
}
