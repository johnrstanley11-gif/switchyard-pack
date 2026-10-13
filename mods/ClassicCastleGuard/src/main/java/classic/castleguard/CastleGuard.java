/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 * Modified 2026-10-11 for the private Minecraft 26.3 / NeoForge source port.
 */
package classic.castleguard;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.minecraft.world.entity.player.Player;
import org.slf4j.LoggerFactory;
@Mod("classic_castle_guard")
public final class CastleGuard {
  public CastleGuard() {
    NeoForge.EVENT_BUS.addListener(CastleGuard::denySpawnDamage);
    NeoForge.EVENT_BUS.addListener(CastleGuard::denyHostileJoin);
    NeoForge.EVENT_BUS.addListener(CastleGuard::removeHostileVisitors);
    NeoForge.EVENT_BUS.addListener(SpawnCommand::register);
    NeoForge.EVENT_BUS.register(CitadelProtection.class);
    NeoForge.EVENT_BUS.register(Plots.class);
    NeoForge.EVENT_BUS.addListener(PlotCommands::register);
    NeoForge.EVENT_BUS.register(Land.class);
    NeoForge.EVENT_BUS.addListener(Land::register);
    NeoForge.EVENT_BUS.register(Kit.class);
    NeoForge.EVENT_BUS.addListener(Kit::register);
    NeoForge.EVENT_BUS.register(QuarryGuard.class);
    NeoForge.EVENT_BUS.addListener(Tpa::register);
    NeoForge.EVENT_BUS.addListener(Vote::register);
    LoggerFactory.getLogger("classic_castle_guard").info("Castle guard active: safe spawn district (no player damage, no hostile mobs) of half size {} and QuarryPlus private 26.3 port compatibility; walled market overworld X[{},{}] Z[{},{}], all heights. /spawn enabled.", CastleRegion.districtHalfSize(), CastleRegion.minX(), CastleRegion.maxX(), CastleRegion.minZ(), CastleRegion.maxZ());
  }
  /**
   * 1.4.0: a player standing in the spawn district cannot be hurt by anything (mobs, explosions, falls, fire, other
   * players), and a player standing in it cannot hurt a player outside. Only damage that ignores invulnerability
   * (the void, /kill) still applies.
   */
  private static void denySpawnDamage(LivingIncomingDamageEvent event) {
    if (!(event.getEntity() instanceof Player victim) || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
    // DamageSource#getEntity is the responsible player for melee and owned arrows.
    if (CastleRegion.inDistrict(victim.level(), victim.blockPosition())
        || (event.getSource().getEntity() instanceof Player attacker && CastleRegion.inDistrict(attacker.level(), attacker.blockPosition()))) {
      event.setCanceled(true);
    }
  }
  /** Hostile mobs cannot spawn, be spawned or be loaded inside the district. */
  private static void denyHostileJoin(EntityJoinLevelEvent event) {
    Entity entity = event.getEntity();
    if (entity instanceof Enemy && CastleRegion.inDistrict(event.getLevel(), entity.blockPosition())) event.setCanceled(true);
  }
  /** A hostile mob that walks, falls or is pushed into the district disappears (checked twice a second). */
  private static void removeHostileVisitors(EntityTickEvent.Pre event) {
    Entity entity = event.getEntity();
    if (entity instanceof Enemy && entity.tickCount % 10 == 0 && CastleRegion.inDistrict(entity.level(), entity.blockPosition())) entity.discard();
  }
}
