package classiccrates.cosmetic;

import classiccrates.ClassicCrates;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A one-use token carrying a {@link Cosmetic}. A trail token is worn by right-clicking; an item cosmetic is applied
 * to the item in the off hand, or by combining item and token in an anvil ({@link CosmeticEvents}).
 */
public class CosmeticTokenItem extends Item {

    public CosmeticTokenItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        Cosmetic cosmetic = stack.get(ClassicCrates.COSMETIC.get());
        if (cosmetic == null) {
            return super.getName(stack);
        }
        return Component.literal(cosmetic.name()).withStyle(cosmetic.color());
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack token = player.getItemInHand(hand);
        Cosmetic cosmetic = token.get(ClassicCrates.COSMETIC.get());
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (cosmetic == null) {
            player.sendOverlayMessage(Component.translatable("classiccrates.token.blank"));
            return InteractionResult.FAIL;
        }

        if (cosmetic.isTrail()) {
            player.setData(ClassicCrates.TRAIL.get(), new Trail(cosmetic.trail(), cosmetic.name()));
            player.sendSystemMessage(Component.translatable("classiccrates.token.trail_on", cosmetic.name()).withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            // The item to change is in the other hand
            InteractionHand otherHand = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            ItemStack target = player.getItemInHand(otherHand);
            if (target.isEmpty()) {
                player.sendOverlayMessage(Component.translatable("classiccrates.token.need_offhand"));
                return InteractionResult.FAIL;
            }
            if (!cosmetic.fits(target)) {
                player.sendOverlayMessage(Component.translatable("classiccrates.token.wrong_item", target.getHoverName()));
                return InteractionResult.FAIL;
            }
            Component targetName = target.getHoverName();
            cosmetic.applyTo(target);
            player.sendSystemMessage(Component.translatable("classiccrates.token.applied", cosmetic.name(), targetName).withStyle(ChatFormatting.LIGHT_PURPLE));
        }

        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.2F);
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.isCreative()) {
            token.shrink(1);
        }
        return InteractionResult.SUCCESS_SERVER;
    }
}
