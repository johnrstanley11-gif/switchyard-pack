/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 * Modified 2026-10-11 for the private Minecraft 26.3 / NeoForge source port.
 */
package classic.castleguard.mixin;
import classic.castleguard.CastleRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="com.yogpc.qp.machine.quarry.QuarryEntity", remap=false)
public abstract class QuarryFrameMixin {
  @Shadow(remap=false) BlockPos targetPos;
  // Leave the upstream checks and all outside behavior intact. A denied frame
  // stalls this target, as the existing canceled solid-block break path does.
  @Inject(method="makeFrame()V", at=@At(value="INVOKE", target="Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", remap=false), cancellable=true, remap=false, require=1, expect=1, allow=1)
  private void castleGuard$denyFrame(CallbackInfo ci) {
    if (CastleRegion.contains(((BlockEntity)(Object)this).getLevel(), targetPos)) ci.cancel();
  }
}
