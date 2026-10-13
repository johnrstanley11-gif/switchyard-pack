/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 */
package classic.castleguard;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.api.IServerClaimsManagerAPI;
import xaero.pac.common.server.claims.protection.override.api.ChunkAccessOverride;
import xaero.pac.common.server.claims.protection.override.api.ChunkAccessOverrideType;
import xaero.pac.common.server.claims.protection.override.api.IChunkAccessOverriderAPI;
import xaero.pac.common.server.player.config.api.v2.IPlayerConfigAPI;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;
/** 1.6.0: the only class that touches Open Parties and Claims; loaded only when that mod is installed (see {@link Land}). */
final class LandClaims {
  private LandClaims() {}
  /** Chunks this player has claimed, in every dimension. */
  static int claimed(MinecraftServer server, UUID player) {
    IServerClaimsManagerAPI claims = OpenPACServerAPI.get(server).getServerClaimsManager();
    return claims.hasPlayerInfo(player) ? claims.getPlayerInfo(player).getClaimCount() : 0;
  }
  /**
   * Makes the claims mod's limit for this player equal to {@code limit} chunks by setting their "bonus chunk claims"
   * (limit minus the mod's base limit, which may be negative).
   * @return null when the limit is in place, otherwise why it is not.
   */
  static String setLimit(MinecraftServer server, UUID player, int limit) {
    OpenPACServerAPI api = OpenPACServerAPI.get(server);
    IPlayerConfigAPI config = api.getPlayerConfigManager().getLoadedConfig(player);
    if (config == null) return "no claims config for that player yet";
    int bonus = limit - api.getServerClaimsManager().getPlayerBaseClaimLimit(player);
    Integer current = config.getRaw(PlayerConfigOptions.BONUS_CHUNK_CLAIMS);
    if (current != null && current == bonus) return null;
    IPlayerConfigAPI.SetResult result = config.tryToSet(PlayerConfigOptions.BONUS_CHUNK_CLAIMS, bonus);
    return result == IPlayerConfigAPI.SetResult.SUCCESS || result == IPlayerConfigAPI.SetResult.DEFAULTED ? null
      : "the claims mod answered " + result + " to a bonus of " + bonus + " chunks";
  }
  /**
   * Unpaid land is open to everyone: the claims mod asks this before refusing access to a claimed chunk, and for a
   * chunk whose owner has not paid the upkeep the answer is "allow". (A claim limit of zero alone does not do that:
   * the claims mod keeps protecting claims that are over the limit.)
   */
  static void registerUnpaidAccess(MinecraftServer server) {
    OpenPACServerAPI.get(server).getServerClaimsManager().getChunkAccessOverriderManager().register(new IChunkAccessOverriderAPI() {
      @Override public String getName() { return "classic_castle_guard_unpaid_land"; }
      @Override public ChunkAccessOverride overrideChunkAccess(Identifier dimension, int x, int z, IPlayerConfigAPI claimConfig, Entity accessor, UUID accessorId, MinecraftServer server, ChunkAccessOverride current) {
        UUID owner = claimConfig == null ? null : claimConfig.getPlayerId();
        return owner != null && Land.unpaid(owner) ? new ChunkAccessOverride(ChunkAccessOverrideType.ALLOW) : current;
      }
    });
  }
  /** Who has claimed the chunk at this position; null when it is unclaimed. */
  static UUID owner(MinecraftServer server, Identifier dimension, BlockPos pos) {
    IPlayerChunkClaimAPI claim = OpenPACServerAPI.get(server).getServerClaimsManager().get(dimension, pos);
    return claim == null ? null : claim.getPlayerId();
  }
  /**
   * 1.8.0: whose claim lies within this many blocks of a position, other than the player's own.
   * @return the owner's name ("the server" for a server claim), or null when there is none.
   */
  static String foreignClaimNear(MinecraftServer server, Identifier dimension, BlockPos pos, int blocks, UUID self) {
    IServerClaimsManagerAPI claims = OpenPACServerAPI.get(server).getServerClaimsManager();
    for (int chunkX = (pos.getX() - blocks) >> 4; chunkX <= (pos.getX() + blocks) >> 4; chunkX++) {
      for (int chunkZ = (pos.getZ() - blocks) >> 4; chunkZ <= (pos.getZ() + blocks) >> 4; chunkZ++) {
        IPlayerChunkClaimAPI claim = claims.get(dimension, chunkX, chunkZ);
        if (claim == null || claim.getPlayerId() == null || claim.getPlayerId().equals(self)) continue;
        UUID owner = claim.getPlayerId();
        if (owner.getMostSignificantBits() == 0L) return "the server";      // server and expired claims use fixed low ids
        String name = claims.hasPlayerInfo(owner) ? claims.getPlayerInfo(owner).getPlayerUsername() : null;
        return name == null || name.isBlank() ? "another player" : name;
      }
    }
    return null;
  }
  /** The limit the claims mod currently applies to this player. */
  static int limit(MinecraftServer server, UUID player) {
    return OpenPACServerAPI.get(server).getServerClaimsManager().getPlayerFullClaimLimit(player);
  }
}
