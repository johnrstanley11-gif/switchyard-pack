/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 * Same hook VanillaBackport's LeavesBlockMixin used (LeavesBlock#animateTick).
 */
package classic.spruceneedles.mixin;

import classic.spruceneedles.SpruceNeedlesClient;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {
  @Inject(method = "animateTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V", at = @At("HEAD"))
  private void classicSpruceNeedles$animateTick(BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo ci) {
    SpruceNeedlesClient.animateTick(state, level, pos, random);
  }
}
