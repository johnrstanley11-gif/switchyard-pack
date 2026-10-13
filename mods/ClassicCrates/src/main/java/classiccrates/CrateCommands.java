package classiccrates;

import classiccrates.cosmetic.Cosmetic;
import classiccrates.cosmetic.Trail;
import classiccrates.vote.VoteHandler;
import classiccrates.vote.VoteListener;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Admin: /crates givekey &lt;player&gt; [count], /crates fakevote &lt;player&gt; (runs the whole vote path without a website),
 * /crates roll &lt;player&gt; (a crate roll without a key or a crate), /crates status. Everyone: /cosmetic trail off, /cosmetic remove (takes the cosmetic off the held item).
 */
public class CrateCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("crates")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("givekey")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> giveKey(ctx, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 640))
                                        .executes(ctx -> giveKey(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("fakevote")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "player");
                                    VoteHandler.onVote(ctx.getSource().getServer(), new VoteListener.Vote("a test vote", name, "", ""));
                                    return 1;
                                })))
                .then(Commands.literal("roll")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(ctx, "player"));
                                    if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                                        ctx.getSource().sendFailure(Component.literal("That player is not online."));
                                        return 0;
                                    }
                                    return classiccrates.crate.CrateBlock.roll(level, player.blockPosition(), player) ? 1 : 0;
                                })))
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.translatable("classiccrates.command.listener", VoteHandler.listenerStatus()), false);
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("cosmetic")
                .then(Commands.literal("trail")
                        .then(Commands.literal("off").executes(CrateCommands::trailOff)))
                .then(Commands.literal("remove").executes(CrateCommands::removeCosmetic)));
    }

    private static int giveKey(CommandContext<CommandSourceStack> ctx, int count) {
        String name = StringArgumentType.getString(ctx, "player");
        ServerPlayer player = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (player != null) {
            VoteHandler.giveKeys(player, count);
            ctx.getSource().sendSuccess(() -> Component.translatable("classiccrates.command.gave_keys", count, player.getDisplayName()), true);
        } else {
            VoteHandler.PendingKeys.get(ctx.getSource().getServer()).add(name, count);
            ctx.getSource().sendSuccess(() -> Component.translatable("classiccrates.command.queued_keys", name, count), true);
        }
        return count;
    }

    private static int trailOff(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Trail trail = player.getExistingDataOrNull(ClassicCrates.TRAIL.get());
        if (trail == null || trail.particle().isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("classiccrates.command.no_trail"));
            return 0;
        }
        player.removeData(ClassicCrates.TRAIL.get());
        ctx.getSource().sendSuccess(() -> Component.translatable("classiccrates.command.trail_off"), false);
        return 1;
    }

    private static int removeCosmetic(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !Cosmetic.removeFrom(held)) {
            ctx.getSource().sendFailure(Component.translatable("classiccrates.command.nothing_to_unskin"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("classiccrates.command.unskinned", held.getHoverName()), false);
        return 1;
    }
}
