/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 * Needle textures and behaviour carried over from VanillaBackport 1.1.7.10 by BlackGear (MIT).
 */
package classic.spruceneedles;

import net.minecraft.client.particle.FallingLeavesParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.util.ParticleUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

/** Client only: never referenced on a dedicated server. */
public final class SpruceNeedlesClient {
  private SpruceNeedlesClient() {}

  static void registerParticleProviders(RegisterParticleProvidersEvent event) {
    // The native tinted-leaves provider has exactly the parameters VanillaBackport's needle provider used.
    event.registerSpriteSet(SpruceNeedles.TINTED_NEEDLES.get(), FallingLeavesParticle.TintedLeavesProvider::new);
  }

  /** VanillaBackport FallingLeavesModule#makeFallingLeavesParticles, for the needle tag only. */
  public static void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
    if (!level.isClientSide() || !state.is(SpruceNeedles.SPAWN_FALLING_NEEDLES)) return;
    if (random.nextFloat() < SpruceNeedles.FALLING_LEAVES_FREQUENCY.get()) {
      BlockPos below = pos.below();
      if (!Block.isFaceFull(level.getBlockState(below).getCollisionShape(level, below), Direction.UP)
          && SpruceNeedles.FALLING_LEAVES.get()) {
        ParticleUtils.spawnParticleBelow(level, pos, random,
          ColorParticleOption.create(SpruceNeedles.TINTED_NEEDLES.get(), level.getClientLeafTintColor(pos)));
      }
    }
  }
}
