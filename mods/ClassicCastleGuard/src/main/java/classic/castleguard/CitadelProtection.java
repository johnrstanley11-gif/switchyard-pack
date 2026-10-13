/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 */
package classic.castleguard;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
/**
 * 1.3.0, optional (protectBlocks=true): the spawn's buildings, roads and walls cannot be broken, built on, burned,
 * flooded, tilled or blown up. Operators (permission level 2+) are exempt. 1.4.0: this covers the whole district
 * (CastleRegion#inDistrict), including every plot, and crops may be harvested and replanted so the community farm stays usable.
 * Machines of other mods that edit blocks without a player are NOT covered, apart from the QuarryPlus mixins.
 */
public final class CitadelProtection {
  private CitadelProtection() {}
  private static boolean guarded(Level level, BlockPos pos) {
    // 1.4.0: the whole district, plots included. 1.5.0: a plot opens to its paid-up renter (see exempt).
    return CastleRegion.protectsBlocks() && CastleRegion.inDistrict(level, pos);
  }
  /** Operators, and (1.5.0) the paid-up renter of the plot the position is in, may change blocks. */
  private static boolean exempt(Entity entity, BlockPos pos) {
    return exempt(entity) || (entity instanceof Player renter && Plots.mayBuild(renter, pos));
  }
  static boolean exempt(Entity entity) {
    return entity instanceof Player player && player.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(2)));
  }
  private static void tell(Entity entity) {
    if (entity instanceof Player player) player.sendOverlayMessage(Component.literal("Spawn is protected."));
  }
  @SubscribeEvent
  public static void onBreak(BreakBlockEvent event) {
    if (event.getLevel() instanceof Level level && guarded(level, event.getPos()) && !exempt(event.getPlayer(), event.getPos())
        && !event.getState().is(BlockTags.CROPS)) {
      event.setCanceled(true); tell(event.getPlayer());
    }
  }
  @SubscribeEvent
  public static void onPlace(BlockEvent.EntityPlaceEvent event) {
    if (event.getLevel() instanceof Level level && guarded(level, event.getPos()) && !exempt(event.getEntity(), event.getPos())
        && !event.getPlacedBlock().is(BlockTags.CROPS)) {
      event.setCanceled(true); tell(event.getEntity());
    }
  }
  @SubscribeEvent
  public static void onToolUse(BlockEvent.BlockToolModificationEvent event) {
    if (!event.isSimulated() && event.getLevel() instanceof Level level && guarded(level, event.getPos()) && !exempt(event.getPlayer(), event.getPos())) {
      event.setCanceled(true);
    }
  }
  @SubscribeEvent
  public static void onTrample(BlockEvent.FarmlandTrampleEvent event) {
    if (event.getLevel() instanceof Level level && guarded(level, event.getPos())) event.setCanceled(true);
  }
  @SubscribeEvent
  public static void onExplosion(ExplosionEvent.Detonate event) {
    if (CastleRegion.protectsBlocks()) event.getAffectedBlocks().removeIf(pos -> guarded(event.getLevel(), pos));
  }
  /** Buckets and fire starters do not go through the place event. */
  @SubscribeEvent
  public static void onUse(PlayerInteractEvent.RightClickBlock event) {
    ItemStack held = event.getItemStack();
    boolean risky = held.getItem() instanceof BucketItem || held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE);
    BlockPos beside = event.getPos().relative(event.getFace() == null ? net.minecraft.core.Direction.UP : event.getFace());
    // A renter may use buckets and fire only where both the clicked block and the block beside it are in their plot.
    if (risky && !(exempt(event.getEntity(), event.getPos()) && exempt(event.getEntity(), beside))
        && (guarded(event.getLevel(), event.getPos()) || guarded(event.getLevel(), beside))) {
      event.setCanceled(true); tell(event.getEntity());
    }
  }
}
