/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 * Modified 2026-10-11 for the private Minecraft 26.3 / NeoForge source port.
 */
package classic.castleguard.mixin;
import classic.castleguard.CastleRegion;
import com.yogpc.qp.machine.MachineStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="com.yogpc.qp.machine.FluidDrain", remap=false)
public abstract class FluidDrainMixin {
  // Covers both direct liquid replacement and BucketPickup before either mutation.
  @Inject(method="drainSourceInto(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/block/state/BlockState;Lcom/yogpc/qp/machine/MachineStorage;Z)V", at=@At("HEAD"), cancellable=true, remap=false, require=1, expect=1, allow=1)
  private static void castleGuard$denyDrain(Level level, BlockPos pos, ServerPlayer player, BlockState newState, MachineStorage storage, boolean discard, CallbackInfo ci) {
    if (CastleRegion.contains(level, pos)) ci.cancel();
  }
}
