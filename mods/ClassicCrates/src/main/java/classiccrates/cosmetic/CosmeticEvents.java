package classiccrates.cosmetic;

import classiccrates.ClassicCrates;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Anvil application of cosmetic tokens and the tooltip lines that explain them.
 */
public final class CosmeticEvents {

    private CosmeticEvents() {
    }

    /**
     * Item in the left slot, a cosmetic token that fits it in the right slot: the result is the same item with the
     * cosmetic's look. Costs one level and uses up the token. Everything else about the item is kept.
     */
    @SubscribeEvent
    public static void onAnvilUpdate(AnvilUpdateEvent event) {
        ItemStack target = event.getLeft();
        ItemStack token = event.getRight();
        if (target.isEmpty() || !token.is(ClassicCrates.COSMETIC_TOKEN.get())) {
            return;
        }
        Cosmetic cosmetic = token.get(ClassicCrates.COSMETIC.get());
        if (cosmetic == null || cosmetic.isTrail() || !cosmetic.fits(target)) {
            return;
        }
        if (cosmetic.name().equals(target.get(ClassicCrates.APPLIED_COSMETIC.get()))) {
            return; // already has this look
        }
        ItemStack result = target.copy();
        Cosmetic.removeFrom(result); // a new look replaces the old one
        cosmetic.applyTo(result);
        event.setOutput(result);
        event.setXpCost(1);
        event.setMaterialCost(1);
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        Cosmetic cosmetic = stack.get(ClassicCrates.COSMETIC.get());
        if (cosmetic != null && stack.is(ClassicCrates.COSMETIC_TOKEN.get())) {
            cosmetic.rarity().ifPresent(rarity -> event.getToolTip().add(
                    Component.translatable("classiccrates.rarity." + rarity.toLowerCase(java.util.Locale.ROOT)).withStyle(cosmetic.color())));
            event.getToolTip().add(Component.translatable(cosmetic.isTrail() ? "classiccrates.token.hint_trail" : "classiccrates.token.hint_anvil")
                    .withStyle(ChatFormatting.GRAY));
        }
        String applied = stack.get(ClassicCrates.APPLIED_COSMETIC.get());
        if (applied != null) {
            event.getToolTip().add(Component.translatable("classiccrates.item.skin", applied).withStyle(ChatFormatting.GRAY));
        }
    }
}
