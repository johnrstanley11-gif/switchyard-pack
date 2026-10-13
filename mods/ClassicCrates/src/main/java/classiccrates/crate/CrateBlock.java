package classiccrates.crate;

import classiccrates.ClassicCrates;
import classiccrates.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Prediction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The vote crate. Right-clicking it with a crate key uses up the key and gives the player one roll of the crate
 * loot table. It holds nothing itself, so one crate at spawn serves everybody.
 */
public class CrateBlock extends HorizontalDirectionalBlock {

    public CrateBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(ClassicCrates.CRATE_KEY.get())) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            if (roll(serverLevel, pos, serverPlayer) && !serverPlayer.isCreative()) {
                stack.shrink(1);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) {
            player.sendOverlayMessage(Component.translatable("classiccrates.crate.need_key").withStyle(ChatFormatting.YELLOW));
            level.playSound(null, pos, SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Gives the player one roll of the crate loot table, with the sounds, particles and messages of a crate at pos.
     *
     * @return False when nothing was given (bad or empty loot table): the caller must not take a key then
     */
    public static boolean roll(ServerLevel level, BlockPos pos, ServerPlayer player) {
        Identifier tableId = Identifier.tryParse(Config.CRATE_LOOT_TABLE.get());
        if (tableId == null) {
            ClassicCrates.LOGGER.error("crate.lootTable '{}' in classiccrates.toml is not a valid id", Config.CRATE_LOOT_TABLE.get());
            return false;
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, tableId));
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .create(LootContextParamSets.CHEST);
        List<ItemStack> rewards = table.getRandomItems(params);
        if (rewards.isEmpty()) {
            // Keep the key: an empty or missing loot table is an admin mistake, not the player's loss
            player.sendSystemMessage(Component.translatable("classiccrates.crate.empty").withStyle(ChatFormatting.RED));
            ClassicCrates.LOGGER.warn("Crate loot table {} gave nothing to {}", tableId, player.getScoreboardName());
            return false;
        }

        MutableComponent list = Component.empty();
        boolean first = true;
        for (ItemStack reward : rewards) {
            if (!first) list.append(", ");
            first = false;
            list.append(reward.getCount() > 1 ? Component.literal(reward.getCount() + "x ").append(reward.getHoverName()) : reward.getHoverName().copy());

            ItemStack given = reward.copy();
            if (!player.getInventory().add(given) || !given.isEmpty()) {
                player.drop(given, false, Prediction.SERVER_ONLY);
            }
        }

        level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.8F, 1.3F);
        level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.8F, 1.0F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 14, 0.4, 0.3, 0.4, 0.0);
        level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 10, 0.2, 0.3, 0.2, 0.03);

        player.sendSystemMessage(Component.translatable("classiccrates.crate.won", list).withStyle(ChatFormatting.GOLD));
        if (Config.BROADCAST_WINS.get()) {
            Component message = Component.translatable("classiccrates.crate.broadcast", player.getDisplayName(), list).withStyle(ChatFormatting.GRAY);
            for (ServerPlayer other : level.getServer().getPlayerList().getPlayers()) {
                if (other != player) other.sendSystemMessage(message);
            }
        }
        return true;
    }
}
