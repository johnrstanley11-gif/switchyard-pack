/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 */
package classic.castleguard;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 1.10.0: /vote lists the server list sites as clickable links. The sites are read from
 * config/classic_castle_guard_vote_sites.txt every time the command is used, so a site can be added or removed
 * without restarting the server. One site per line: a title, a vertical bar, the https address of its vote page.
 * The vote itself, and the Crate Key it gives, are handled by Classic Crates' vote listener.
 */
public final class Vote {
  private Vote() {}
  private static final Logger LOG = LoggerFactory.getLogger("classic_castle_guard");
  private record Site(String title, URI address) {}

  private static Path file() { return FMLPaths.CONFIGDIR.get().resolve("classic_castle_guard_vote_sites.txt"); }

  private static List<Site> sites() {
    List<Site> sites = new ArrayList<>();
    Path file = file();
    try {
      if (!Files.isRegularFile(file)) {
        Files.writeString(file, "# Vote sites shown by /vote, one per line:  Title | https://address-of-the-vote-page\n# Read each time /vote is used; no restart needed.\n", StandardCharsets.UTF_8);
        return sites;
      }
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String text = line.strip();
        int bar = text.indexOf('|');
        if (text.isEmpty() || text.startsWith("#") || bar < 1) continue;
        String title = text.substring(0, bar).strip(), address = text.substring(bar + 1).strip();
        if (title.isEmpty() || title.length() > 60 || !address.startsWith("https://") || address.length() > 300 || address.chars().anyMatch(c -> c <= ' ')) {
          LOG.warn("Vote sites: line skipped (need \"Title | https://...\"): {}", text);
          continue;
        }
        try { sites.add(new Site(title, URI.create(address))); }
        catch (IllegalArgumentException e) { LOG.warn("Vote sites: not a valid address: {}", address); }
      }
    } catch (Exception e) {
      LOG.error("Could not read {}", file, e);
    }
    return sites;
  }

  public static void register(RegisterCommandsEvent event) {
    event.getDispatcher().register(Commands.literal("vote").executes(ctx -> show(ctx.getSource())));
  }

  private static int show(CommandSourceStack source) {
    List<Site> sites = sites();
    if (sites.isEmpty()) {
      source.sendSuccess(() -> Component.literal("There are no vote sites yet. Check back soon.").withStyle(ChatFormatting.GRAY), false);
      return 0;
    }
    String name = source.getEntity() instanceof ServerPlayer player ? player.getName().getString() : "your Minecraft name";
    source.sendSuccess(() -> Component.literal("Vote for Switchyard").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
      .append(Component.literal(": every vote gives you a Crate Key. Vote as ").withStyle(style -> style.withColor(ChatFormatting.GRAY).withBold(false)))
      .append(Component.literal(name).withStyle(style -> style.withColor(ChatFormatting.WHITE).withBold(false)))
      .append(Component.literal(".").withStyle(style -> style.withColor(ChatFormatting.GRAY).withBold(false))), false);
    for (Site site : sites) {
      source.sendSuccess(() -> Component.literal(" ✦ ").withStyle(ChatFormatting.GOLD)
        .append(Component.literal(site.title() + "  ").withStyle(ChatFormatting.WHITE))
        .append(Component.literal("[Vote]").withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true).withUnderlined(true)
          .withClickEvent(new ClickEvent.OpenUrl(site.address()))
          .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open " + site.address().getHost() + " in your browser"))))), false);
    }
    return sites.size();
  }
}
