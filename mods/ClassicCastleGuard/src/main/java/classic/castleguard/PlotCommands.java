/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 */
package classic.castleguard;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
/**
 * Everyone: /plot info [name], /plot list, /plot bid &lt;diamonds&gt; (for the plot you stand in).
 * Operators: /plot release &lt;name&gt; (back up for rent, also cancels an auction), /plot auction &lt;name&gt; &lt;hours&gt; [minimum bid],
 * /plot give &lt;name&gt; &lt;player&gt; &lt;days&gt;, /plot shopchest &lt;x&gt; &lt;y&gt; &lt;z&gt;, /plot account (undeposited diamonds).
 */
public final class PlotCommands {
  private PlotCommands() {}
  private static final SuggestionProvider<CommandSourceStack> NAMES = (context, builder) ->
    SharedSuggestionProvider.suggest(Plots.all().stream().map(Plots.Plot::name), builder);
  public static void register(RegisterCommandsEvent event) {
    event.getDispatcher().register(Commands.literal("plot")
      .then(Commands.literal("info")
        .executes(ctx -> { Plots.Plot plot = here(ctx); return plot == null ? 0 : reply(ctx, Plots.describe(plot)); })
        .then(name().executes(ctx -> { Plots.Plot plot = named(ctx); return plot == null ? 0 : reply(ctx, Plots.describe(plot)); })))
      .then(Commands.literal("list").executes(ctx -> { for (String line : Plots.listing()) reply(ctx, line); return 1; }))
      .then(Commands.literal("bid").then(Commands.argument("diamonds", IntegerArgumentType.integer(1, 100000)).executes(ctx -> {
        Plots.Plot plot = here(ctx);
        return plot == null ? 0 : reply(ctx, Plots.bid(ctx.getSource().getPlayerOrException(), plot, IntegerArgumentType.getInteger(ctx, "diamonds")));
      })))
      .then(Commands.literal("release").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        .then(name().executes(ctx -> { Plots.Plot plot = named(ctx); return plot == null ? 0 : reply(ctx, Plots.release(plot)); })))
      .then(Commands.literal("auction").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        .then(name().then(Commands.argument("hours", IntegerArgumentType.integer(1, 336))
          .executes(ctx -> auction(ctx, 0))
          .then(Commands.argument("minimum", IntegerArgumentType.integer(1, 100000)).executes(ctx -> auction(ctx, IntegerArgumentType.getInteger(ctx, "minimum")))))))
      .then(Commands.literal("give").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        .then(name().then(Commands.argument("player", StringArgumentType.word()).then(Commands.argument("days", IntegerArgumentType.integer(1, 3650)).executes(ctx -> {
          Plots.Plot plot = named(ctx);
          ServerPlayer player = ctx.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(ctx, "player"));
          if (plot == null) return 0;
          if (player == null) { ctx.getSource().sendFailure(Component.literal("That player is not online.")); return 0; }
          return reply(ctx, Plots.grant(plot, player, IntegerArgumentType.getInteger(ctx, "days")));
        })))))
      .then(Commands.literal("shopchest").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes(ctx -> {
          BlockPos pos = new BlockPos(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"));
          if (!(ctx.getSource().getServer().overworld().getBlockEntity(pos) instanceof net.minecraft.world.Container)) {
            ctx.getSource().sendFailure(Component.literal("There is no chest (or other container) at " + pos.toShortString() + " in the overworld.")); return 0;
          }
          Plots.setShopChest(pos);
          return reply(ctx, "Rent and winning bids now go into the container at " + pos.toShortString() + ". Only operators can open it.");
        })))))
      .then(Commands.literal("account").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
        int before = Plots.undeposited();
        int moved = before > 0 ? Plots.flushUndeposited() : 0;
        return reply(ctx, before == 0 ? "Every payment is in the shop account chest." : moved + " of " + before + " held diamonds moved into the shop account chest.");
      })));
  }
  private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> name() {
    return Commands.argument("name", StringArgumentType.word()).suggests(NAMES);
  }
  private static int reply(CommandContext<CommandSourceStack> ctx, String text) {
    ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    return 1;
  }
  private static Plots.Plot named(CommandContext<CommandSourceStack> ctx) {
    Plots.Plot plot = Plots.byName(StringArgumentType.getString(ctx, "name"));
    if (plot == null) ctx.getSource().sendFailure(Component.literal("There is no plot with that name. See /plot list."));
    return plot;
  }
  private static Plots.Plot here(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    Plots.Plot plot = Plots.at(ctx.getSource().getPlayerOrException().blockPosition());
    if (plot == null) ctx.getSource().sendFailure(Component.literal("Stand inside a plot first."));
    return plot;
  }
  private static int auction(CommandContext<CommandSourceStack> ctx, int minimum) {
    Plots.Plot plot = named(ctx);
    if (plot == null) return 0;
    return reply(ctx, Plots.startAuction(plot, IntegerArgumentType.getInteger(ctx, "hours"), minimum > 0 ? minimum : plot.price()));
  }
}
