/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 * Modified 2026-10-11 for the private Minecraft 26.3 / NeoForge source port.
 */
package classic.castleguard;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.mcreator.gohomealready.HomeTeleport;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
/** Normal-player command. Reuses the installed home mod's warmup and cooldown. */
public final class SpawnCommand {
  private SpawnCommand() {}
  public static void register(RegisterCommandsEvent event) {
    event.getDispatcher().register(Commands.literal("spawn").executes(context -> spawn(context.getSource())));
    BlockPos arrival=CastleRegion.arrival();
    org.slf4j.LoggerFactory.getLogger("classic_castle_guard").info("Registered /spawn: arrival ({}.5,{},{}.5), shared home warmup and cooldown.", arrival.getX(), arrival.getY(), arrival.getZ());
  }
  private static int spawn(CommandSourceStack source) throws CommandSyntaxException {
    ServerPlayer player=source.getPlayerOrException();
    if (!player.isAlive() || player.isRemoved()) {
      source.sendFailure(Component.literal("You cannot use /spawn while dead."));
      return 0;
    }
    ServerLevel target=source.getServer().overworld();
    BlockPos arrival=CastleRegion.arrival();
    if (!target.isEmptyBlock(arrival) || !target.isEmptyBlock(arrival.above())) {
      source.sendFailure(Component.literal("Spawn is obstructed. Tell the server owner."));
      return 0;
    }
    return HomeTeleport.request(source,player,target,arrival.getX()+0.5,arrival.getY(),arrival.getZ()+0.5,"Returned to spawn.");
  }
}
