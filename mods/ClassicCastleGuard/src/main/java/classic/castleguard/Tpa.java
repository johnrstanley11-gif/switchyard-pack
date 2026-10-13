/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 */
package classic.castleguard;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.mcreator.gohomealready.HomeTeleport;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
/**
 * 1.9.0: teleport requests between players.
 * <ul>
 * <li>/tpa &lt;player&gt; asks to go to them; /tpahere &lt;player&gt; asks them to come to you.</li>
 * <li>The other player answers with /tpaccept or /tpdeny (or taps the words in chat); the sender can /tpcancel.
 *     A request lapses after 60 seconds, and a player has one outgoing request at a time.</li>
 * <li>The journey itself is the home mod's: the traveller stands still for its warm-up and shares the /home and
 *     /spawn cooldown. They arrive where the other player stood when the request was accepted.</li>
 * </ul>
 * An operator may send a request to themself, which is how the feature is tested with one player online.
 */
public final class Tpa {
  private Tpa() {}
  private static final long LIFETIME = 60_000L;
  /** A request waiting for an answer, kept under the id of the player who has to answer. */
  private record Request(UUID from, String fromName, boolean here, long expires) {}
  private static final Map<UUID, Request> WAITING = new HashMap<>();

  public static void register(RegisterCommandsEvent event) {
    event.getDispatcher().register(Commands.literal("tpa").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> ask(ctx, false))));
    event.getDispatcher().register(Commands.literal("tpahere").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> ask(ctx, true))));
    event.getDispatcher().register(Commands.literal("tpaccept").executes(ctx -> answer(ctx, true)));
    event.getDispatcher().register(Commands.literal("tpdeny").executes(ctx -> answer(ctx, false)));
    event.getDispatcher().register(Commands.literal("tpcancel").executes(Tpa::cancel));
  }

  private static void forget() {
    long now = System.currentTimeMillis();
    for (Iterator<Request> it = WAITING.values().iterator(); it.hasNext();) if (it.next().expires <= now) it.remove();
  }
  private static MutableComponent button(String label, String command, ChatFormatting colour) {
    return Component.literal(label).withStyle(style -> style.withColor(colour).withBold(true).withClickEvent(new ClickEvent.RunCommand(command)));
  }
  private static int say(CommandContext<CommandSourceStack> ctx, String text) {
    ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    return 1;
  }
  private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
    ctx.getSource().sendFailure(Component.literal(text));
    return 0;
  }

  private static int ask(CommandContext<CommandSourceStack> ctx, boolean here) throws CommandSyntaxException {
    ServerPlayer sender = ctx.getSource().getPlayerOrException();
    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
    forget();
    if (target == sender && !CitadelProtection.exempt(sender)) return fail(ctx, "You are already with yourself.");
    for (Request waiting : WAITING.values()) if (waiting.from.equals(sender.getUUID())) return fail(ctx, "You already have a request waiting. Use /tpcancel to withdraw it.");
    if (WAITING.containsKey(target.getUUID())) return fail(ctx, target.getName().getString() + " is still answering another request. Try again in a minute.");
    String name = sender.getName().getString();
    WAITING.put(target.getUUID(), new Request(sender.getUUID(), name, here, System.currentTimeMillis() + LIFETIME));
    target.sendSystemMessage(Component.literal(name + (here ? " asks you to teleport to them. " : " asks to teleport to you. ")).withStyle(ChatFormatting.GOLD)
      .append(button("[Accept]", "/tpaccept", ChatFormatting.GREEN)).append(Component.literal(" ")).append(button("[Deny]", "/tpdeny", ChatFormatting.RED))
      .append(Component.literal("  (or type /tpaccept or /tpdeny; lapses in 60 seconds)").withStyle(ChatFormatting.GRAY)));
    return say(ctx, "Request sent to " + target.getName().getString() + ". They have 60 seconds to answer; /tpcancel withdraws it.");
  }

  private static int answer(CommandContext<CommandSourceStack> ctx, boolean accept) throws CommandSyntaxException {
    ServerPlayer target = ctx.getSource().getPlayerOrException();
    forget();
    Request request = WAITING.remove(target.getUUID());
    if (request == null) return fail(ctx, "Nobody is waiting for your answer.");
    MinecraftServer server = ctx.getSource().getServer();
    ServerPlayer sender = server.getPlayerList().getPlayer(request.from);
    if (sender == null) return fail(ctx, request.fromName + " is no longer online.");
    String targetName = target.getName().getString();
    if (!accept) {
      sender.sendSystemMessage(Component.literal(targetName + " declined your teleport request.").withStyle(ChatFormatting.RED));
      return say(ctx, "Request from " + request.fromName + " declined.");
    }
    ServerPlayer traveller = request.here ? target : sender, host = request.here ? sender : target;
    if (!traveller.isAlive() || !host.isAlive()) return fail(ctx, "Nobody can teleport while one of you is dead.");
    host.sendSystemMessage(Component.literal(traveller.getName().getString() + " is on the way to you.").withStyle(ChatFormatting.GREEN));
    // The home mod tells the traveller about the warm-up, a cooldown or moving too early.
    int started = HomeTeleport.request(traveller.createCommandSourceStack(), traveller, host.level(), host.getX(), host.getY(), host.getZ(), "Teleported to " + host.getName().getString() + ".");
    if (started == 0) host.sendSystemMessage(Component.literal(traveller.getName().getString() + " cannot teleport right now (cooldown or already teleporting).").withStyle(ChatFormatting.RED));
    return started;
  }

  private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    ServerPlayer sender = ctx.getSource().getPlayerOrException();
    forget();
    for (Iterator<Map.Entry<UUID, Request>> it = WAITING.entrySet().iterator(); it.hasNext();) {
      Map.Entry<UUID, Request> entry = it.next();
      if (!entry.getValue().from.equals(sender.getUUID())) continue;
      it.remove();
      ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayer(entry.getKey());
      if (target != null) target.sendSystemMessage(Component.literal(sender.getName().getString() + " withdrew their teleport request.").withStyle(ChatFormatting.GRAY));
      return say(ctx, "Request withdrawn.");
    }
    return fail(ctx, "You have no request waiting.");
  }
}
