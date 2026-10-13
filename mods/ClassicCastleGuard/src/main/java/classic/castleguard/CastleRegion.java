/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 * Modified 2026-10-11 for the private Minecraft 26.3 / NeoForge source port.
 */
package classic.castleguard;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.LoggerFactory;
/**
 * Server spawn footprint: 96 x 96 blocks, all heights, in the overworld. Deliberately does not intercept ordinary
 * world writes. 1.2.0: the footprint's position is read once from config/classic_castle_guard.properties
 * (centerX, centerZ, floorY, protectBlocks); the defaults are the original behaviour (0, 0, 70, false).
 */
public final class CastleRegion {
  private CastleRegion() {}
  private static final int CENTER_X, CENTER_Z, FLOOR_Y, DISTRICT_HALF;
  private static final boolean PROTECT_BLOCKS;
  static {
    Properties values = new Properties();
    values.setProperty("centerX", "0"); values.setProperty("centerZ", "0"); values.setProperty("floorY", "70");
    values.setProperty("protectBlocks", "false"); values.setProperty("districtHalfSize", "48");
    Path file = FMLPaths.CONFIGDIR.get().resolve("classic_castle_guard.properties");
    try {
      if (Files.isRegularFile(file)) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
      } else {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
          values.store(writer, "Classic Castle Guard: the protected spawn is x/z [center-48, center+47]; /spawn arrives at center + (0.5, floorY+1, 5.5)");
        }
      }
    } catch (IOException e) {
      LoggerFactory.getLogger("classic_castle_guard").error("Could not read or create {}; using the default spawn position", file, e);
    }
    CENTER_X = parse(values, "centerX", 0); CENTER_Z = parse(values, "centerZ", 0); FLOOR_Y = parse(values, "floorY", 70);
    PROTECT_BLOCKS = "true".equalsIgnoreCase(values.getProperty("protectBlocks", "false").trim());
    DISTRICT_HALF = Math.max(48, parse(values, "districtHalfSize", 48));
  }
  private static int parse(Properties values, String key, int fallback) {
    try { return Integer.parseInt(values.getProperty(key).trim()); }
    catch (RuntimeException e) {
      LoggerFactory.getLogger("classic_castle_guard").error("classic_castle_guard.properties: {} is not a whole number; using {}", key, fallback);
      return fallback;
    }
  }
  public static int minX() { return CENTER_X - 48; }
  public static int maxX() { return CENTER_X + 47; }
  public static int minZ() { return CENTER_Z - 48; }
  public static int maxZ() { return CENTER_Z + 47; }
  /** 1.3.0: when true, blocks inside the footprint cannot be changed by non-operators, except in the stall plot chunks. */
  public static boolean protectsBlocks() { return PROTECT_BLOCKS; }
  /**
   * The twelve player stall plots of the market layout, as chunk offsets from the centre chunk (three per corner block).
   * Those chunks are left to the players and the claims mod.
   */
  private static final long[] STALL_CHUNKS = {key(-2,-3), key(-3,-2), key(-2,-2), key(1,-3), key(2,-2), key(1,-2),
                                              key(-3,1), key(-2,1), key(-2,2), key(2,1), key(1,1), key(1,2)};
  private static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }
  public static boolean isStallPlot(BlockPos pos) {
    long k = key(Math.floorDiv(pos.getX() - CENTER_X, 16), Math.floorDiv(pos.getZ() - CENTER_Z, 16));
    for (long stall : STALL_CHUNKS) if (stall == k) return true;
    return false;
  }
  /**
   * 1.4.0: the whole spawn district (the walled market plus the plot rings and the roads between them), a square of
   * districtHalfSize blocks around the centre; 48 = just the walled market, as before. Inside it players cannot be
   * hurt, hostile mobs cannot exist and, with protectBlocks, nothing can be built or broken.
   */
  public static boolean inDistrict(Level level, BlockPos pos) {
    return level instanceof ServerLevel && Level.OVERWORLD.equals(level.dimension()) && pos != null
      && pos.getX() >= CENTER_X - DISTRICT_HALF && pos.getX() < CENTER_X + DISTRICT_HALF
      && pos.getZ() >= CENTER_Z - DISTRICT_HALF && pos.getZ() < CENTER_Z + DISTRICT_HALF;
  }
  public static int districtHalfSize() { return DISTRICT_HALF; }
  /** Block the arriving player stands in (feet). */
  public static BlockPos arrival() { return new BlockPos(CENTER_X, FLOOR_Y + 1, CENTER_Z + 5); }
  public static boolean contains(Level level, BlockPos pos) {
    return level instanceof ServerLevel && Level.OVERWORLD.equals(level.dimension()) && pos != null
      && pos.getX() >= minX() && pos.getX() <= maxX() && pos.getZ() >= minZ() && pos.getZ() <= maxZ();
  }
}
