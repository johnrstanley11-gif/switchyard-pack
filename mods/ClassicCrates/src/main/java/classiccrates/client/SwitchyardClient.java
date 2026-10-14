package classiccrates.client;

import classiccrates.ClassicCrates;
import classiccrates.admin.AdminPayloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.gamerules.GameRule;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Switchyard menu: pages of large tiles for the things players otherwise type as commands (/spawn, /home, /sethome,
 * /land, /tpa, /kit, /vote, /oclaims, /cosmetic ...), shortcuts to screens that need a key on a PC (chat, map, voice,
 * recipes) and, for operators, an admin page (time, weather, game rule toggles, kick/ban/teleport per player). Opened
 * with a key (Home by default; "Yard" on the phone layout) or from the pause menu. Client only: every tile sends an
 * ordinary command, so the server decides what is allowed. Only the admin page's rule states come over a payload.
 */
@Mod(value = ClassicCrates.MOD_ID, dist = Dist.CLIENT)
public class SwitchyardClient {

    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(ClassicCrates.id("switchyard"));
    private static final KeyMapping OPEN_MENU = new KeyMapping("key.classiccrates.switchyard_menu", InputConstants.KEY_HOME, CATEGORY);
    private static final URI VOTE_PAGE = URI.create("https://mc.jorry.live/vote");
    private static final URI RULES_PAGE = URI.create("https://mc.jorry.live/page/rules");
    private static final URI SITE = URI.create("https://mc.jorry.live/");
    private static final URI SERVER_INFO = URI.create("https://mc.jorry.live/page/server-info");
    private static final int MAX_PLAYER_TILES = 24;

    /** True after the touch layout was written into an Android launcher this start; the hint screen clears it. */
    private boolean showPhoneHint;

    public SwitchyardClient(IEventBus modBus) {
        this.showPhoneHint = PhoneLayout.install();
        AdminPayloads.clientReceiver = AdminClient::receive;
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, event -> {
            AdminClient.clear();
            if (this.showPhoneHint) {
                event.getPlayer().sendSystemMessage(Component.translatable("classiccrates.phone.layout_installed"));
            }
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> AdminClient.clear());
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.registerCategory(CATEGORY);
            event.register(OPEN_MENU);
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            Minecraft minecraft = Minecraft.getInstance();
            AdminClient.tick();
            while (OPEN_MENU.consumeClick()) {
                if (minecraft.player != null && minecraft.gui.screen() == null) {
                    minecraft.gui.setScreen(menu(null));
                }
            }
            // The layout hint waits until the player is in the world and nothing else is open (joining shows other screens).
            if (this.showPhoneHint && minecraft.player != null && minecraft.gui.screen() == null && minecraft.player.tickCount > 100) {
                this.showPhoneHint = false;
                minecraft.gui.setScreen(new PhoneHintScreen());
            }
        });
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, ContainerButtons::init);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, event -> {
            if (event.getScreen() instanceof PauseScreen pause && Minecraft.getInstance().player != null) {
                // Centred above "Back to Game": the top-left corner is under the launcher's own buttons on phones.
                int x = 6, y = 6, width = 100;
                String returnToGame = Component.translatable("menu.returnToGame").getString();
                for (GuiEventListener listener : event.getListenersList()) {
                    if (listener instanceof Button button && button.getMessage().getString().equals(returnToGame)) {
                        x = button.getX() + (button.getWidth() - width) / 2;
                        y = Math.max(2, button.getY() - 24);
                    }
                }
                event.addListener(Button.builder(Component.translatable("classiccrates.menu.title"), button -> Minecraft.getInstance().gui.setScreen(menu(pause)))
                        .bounds(x, y, width, 20).build());
            }
        });
    }

    private static SwitchyardScreen.Tile tile(String name, ItemStack icon, Runnable action) {
        return new SwitchyardScreen.Tile(Component.translatable("classiccrates.menu." + name), icon, action);
    }

    private static SwitchyardScreen.Tile tile(Component label, ItemStack icon, Runnable action) {
        return new SwitchyardScreen.Tile(label, icon, action);
    }

    private static SwitchyardScreen.Tile tile(String name, ItemStack icon, String command) {
        return tile(name, icon, command(command));
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("classiccrates.menu." + key, args);
    }

    private static void sendCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.connection.sendCommand(command);
        }
    }

    /** Sends a command as the player and returns to the game, where the answer shows in chat. */
    private static Runnable command(String command) {
        return () -> {
            sendCommand(command);
            Minecraft.getInstance().gui.setScreen(null);
        };
    }

    /** Opens a page from a tile. */
    private static Runnable open(java.util.function.Function<Screen, SwitchyardScreen> page, SwitchyardScreen[] self) {
        return () -> Minecraft.getInstance().gui.setScreen(page.apply(self[0]));
    }

    private static void tell(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(message);
        }
    }

    /** Asks first; "yes" sends the command, "no" goes back to the page it came from. */
    private static void confirm(Screen back, String question, String command, Object... args) {
        Minecraft.getInstance().gui.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                command(command).run();
            } else {
                Minecraft.getInstance().gui.setScreen(back);
            }
        }, Component.translatable("classiccrates.menu.title"), Component.translatable("classiccrates.menu.confirm." + question, args)));
    }

    /**
     * Opens another mod's screen through reflection (the mods are not compile-time dependencies of this one, and a
     * simulated key press is unreliable: Xaero checks that the key is physically held, and the voice chat mute key
     * shares M with the map). Xaero's world map: {@code new GuiMap(null, null, WorldMapSession.getCurrentSession()
     * .getMapProcessor(), minecraft.getCameraEntity())}, exactly what its own key handler does.
     */
    private static Runnable worldMap() {
        return () -> {
            Minecraft minecraft = Minecraft.getInstance();
            try {
                Class<?> session = Class.forName("xaero.map.WorldMapSession");
                Object current = session.getMethod("getCurrentSession").invoke(null);
                if (current == null) {
                    tell(text("key_unbound", text("map")));
                    return;
                }
                Object processor = session.getMethod("getMapProcessor").invoke(current);
                Class<?> guiMap = Class.forName("xaero.map.gui.GuiMap");
                Object screen = guiMap.getConstructor(Screen.class, Screen.class, Class.forName("xaero.map.MapProcessor"), net.minecraft.world.entity.Entity.class)
                        .newInstance(null, null, processor, minecraft.getCameraEntity());
                minecraft.gui.setScreen((Screen) screen);
            } catch (ReflectiveOperationException | RuntimeException e) {
                ClassicCrates.LOGGER.warn("Could not open Xaero's world map", e);
                tell(text("key_missing", text("map")));
            }
        };
    }

    /** Simple Voice Chat: its setup guide while that is unfinished, otherwise the voice chat menu. */
    private static Runnable voiceChat() {
        return () -> {
            Minecraft minecraft = Minecraft.getInstance();
            try {
                Class<?> onboarding = Class.forName("de.maxhenkel.voicechat.gui.onboarding.OnboardingManager");
                if ((Boolean) onboarding.getMethod("isOnboarding").invoke(null)) {
                    onboarding.getMethod("startOnboarding", Screen.class).invoke(null, (Screen) null);
                    return;
                }
                Object screen = Class.forName("de.maxhenkel.voicechat.gui.VoiceChatScreen").getConstructor().newInstance();
                minecraft.gui.setScreen((Screen) screen);
            } catch (ReflectiveOperationException | RuntimeException e) {
                ClassicCrates.LOGGER.warn("Could not open the voice chat menu", e);
                tell(text("key_missing", text("voice")));
            }
        };
    }

    private static ItemStack head(PlayerInfo info) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(info.getProfile()));
        return head;
    }

    /** Everyone else on the server, by name. */
    private static List<PlayerInfo> others() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        List<PlayerInfo> players = new ArrayList<>();
        if (connection != null && minecraft.player != null) {
            for (PlayerInfo info : connection.getListedOnlinePlayers()) {
                if (!info.getProfile().id().equals(minecraft.player.getUUID())) {
                    players.add(info);
                }
            }
        }
        players.sort(Comparator.comparing(info -> info.getProfile().name().toLowerCase(java.util.Locale.ROOT)));
        return players.size() > MAX_PLAYER_TILES ? players.subList(0, MAX_PLAYER_TILES) : players;
    }

    private static boolean operator() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    static SwitchyardScreen menu(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(Component.translatable("classiccrates.menu.title"), parent, () -> {
            List<SwitchyardScreen.Tile> tiles = new ArrayList<>(List.of(
                    tile("spawn", new ItemStack(Items.COMPASS), "spawn"),
                    tile("home", new ItemStack(Items.OAK_DOOR), "home"),
                    tile("sethome", new ItemStack(Items.CAMPFIRE), () -> confirm(self[0], "sethome", "sethome")),
                    tile("bed", new ItemStack(Items.CLOCK), "bed"),
                    tile("land", new ItemStack(Items.FILLED_MAP), open(SwitchyardClient::land, self)),
                    tile("players", new ItemStack(Items.ENDER_PEARL), open(SwitchyardClient::players, self)),
                    tile("kit", new ItemStack(Items.CHEST), "kit"),
                    tile("vote", new ItemStack(Items.PAPER), "vote"),
                    tile("crates", new ItemStack(ClassicCrates.CRATE_KEY.get()), open(SwitchyardClient::crates, self)),
                    tile("cosmetics", new ItemStack(Items.LEATHER_CHESTPLATE), open(SwitchyardClient::cosmetics, self)),
                    tile("shortcuts", new ItemStack(Items.LEVER), open(SwitchyardClient::shortcuts, self)),
                    tile("rules", new ItemStack(Items.WRITABLE_BOOK), open(SwitchyardClient::rules, self))));
            if (operator()) {
                tiles.add(tile("admin", new ItemStack(Items.COMMAND_BLOCK), open(SwitchyardClient::admin, self)));
            }
            return tiles;
        }, () -> null);
        return self[0];
    }

    /** Open Parties and Claims chunk commands plus the Castle Guard land account. */
    private static SwitchyardScreen land(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(text("land"), parent, List.of(
                tile("claim", new ItemStack(Items.OAK_FENCE), "oclaims claim"),
                tile("unclaim", new ItemStack(Items.SHEARS), () -> confirm(self[0], "unclaim", "oclaims unclaim")),
                tile("land_info", new ItemStack(Items.FILLED_MAP), "land"),
                tile("land_pay", new ItemStack(Items.DIAMOND), () -> confirm(self[0], "land_pay", "land pay"))),
                text("land.note"));
        return self[0];
    }

    /** Everyone online as a tile; tapping one offers /tpa and /tpahere to that player. */
    private static SwitchyardScreen players(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(text("players"), parent, () -> {
            List<SwitchyardScreen.Tile> tiles = new ArrayList<>();
            tiles.add(tile("tpaccept", new ItemStack(Items.EMERALD), "tpaccept"));
            tiles.add(tile("tpdeny", new ItemStack(Items.REDSTONE), "tpdeny"));
            for (PlayerInfo info : others()) {
                String name = info.getProfile().name();
                tiles.add(tile(Component.literal(name), head(info), () -> Minecraft.getInstance().gui.setScreen(player(self[0], name))));
            }
            return tiles;
        }, () -> others().isEmpty() ? text("no_players") : text("players.note"));
        return self[0];
    }

    private static SwitchyardScreen player(Screen parent, String name) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(Component.literal(name), parent, List.of(
                tile(text("tpa", name), new ItemStack(Items.ENDER_PEARL), command("tpa " + name)),
                tile(text("tpahere", name), new ItemStack(Items.LEAD), command("tpahere " + name))),
                text("player.note"));
        return self[0];
    }

    /** Screens that need a key on a PC, for players on a phone or with the controls hidden. */
    private static SwitchyardScreen shortcuts(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(text("shortcuts"), parent, List.of(
                tile("chat", new ItemStack(Items.OAK_SIGN), () -> Minecraft.getInstance().gui.setScreen(new ChatScreen("", false))),
                tile("map", new ItemStack(Items.MAP), worldMap()),
                tile("recipes", new ItemStack(Items.CRAFTING_TABLE), () -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.player != null) {
                        minecraft.gui.setScreen(new InventoryScreen(minecraft.player));
                    }
                }),
                tile("voice", new ItemStack(Items.NOTE_BLOCK), voiceChat()),
                tile("website", new ItemStack(Items.BOOK), () -> ConfirmLinkScreen.confirmLinkNow(self[0], SITE)),
                tile("server_info", new ItemStack(Items.KNOWLEDGE_BOOK), () -> ConfirmLinkScreen.confirmLinkNow(self[0], SERVER_INFO))),
                text("shortcuts.note"));
        return self[0];
    }

    private static SwitchyardScreen crates(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        int keys = minecraft.player == null ? 0 : minecraft.player.getInventory().countItem(ClassicCrates.CRATE_KEY.get());
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(text("crates"), parent, List.of(
                tile("vote_page", new ItemStack(Items.PAPER), () -> ConfirmLinkScreen.confirmLinkNow(self[0], VOTE_PAGE)),
                tile("to_crate", new ItemStack(ClassicCrates.CRATE_ITEM.get()), "spawn")),
                text("crates.note", keys));
        return self[0];
    }

    private static SwitchyardScreen cosmetics(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(text("cosmetics"), parent, List.of(
                tile("trail_off", new ItemStack(Items.BLAZE_POWDER), "cosmetic trail off"),
                tile("unskin", new ItemStack(Items.SHEARS), () -> confirm(self[0], "unskin", "cosmetic remove"))),
                text("cosmetics.note"));
        return self[0];
    }

    private static SwitchyardScreen rules(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(text("rules"), parent, List.of(
                tile("rules_page", new ItemStack(Items.WRITABLE_BOOK), () -> ConfirmLinkScreen.confirmLinkNow(self[0], RULES_PAGE))),
                text("rules.note"));
        return self[0];
    }

    // ---- admin (operators; every tile is a normal command, the server checks the permission again) ----

    private static SwitchyardScreen admin(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(text("admin"), parent, List.of(
                tile("admin.world", new ItemStack(Items.GRASS_BLOCK), open(SwitchyardClient::adminWorld, self)),
                tile("admin.players", new ItemStack(Items.PLAYER_HEAD), open(SwitchyardClient::adminPlayers, self)),
                tile("creative", new ItemStack(Items.DIAMOND_BLOCK), "gamemode creative"),
                tile("survival", new ItemStack(Items.IRON_SWORD), "gamemode survival"),
                tile("spectator", new ItemStack(Items.ENDER_EYE), "gamemode spectator"),
                tile("save", new ItemStack(Items.BARREL), "save-all"),
                tile("banlist", new ItemStack(Items.IRON_BARS), "banlist")),
                text("admin.note"));
        return self[0];
    }

    /** Time, weather and game rule toggles. Rule states come from the server (AdminPayloads) when it has 1.4.0+. */
    private static SwitchyardScreen adminWorld(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        AdminClient.request();
        self[0] = new SwitchyardScreen(text("admin.world"), parent, () -> {
            List<SwitchyardScreen.Tile> tiles = new ArrayList<>(List.of(
                    tile("day", new ItemStack(Items.SUNFLOWER), "time set day"),
                    tile("night", new ItemStack(Items.LANTERN), "time set night"),
                    tile("clear", new ItemStack(Items.FEATHER), "weather clear"),
                    tile("rain", new ItemStack(Items.WATER_BUCKET), "weather rain")));
            for (GameRule<Boolean> rule : AdminPayloads.RULES) {
                String id = rule.id();
                Boolean state = AdminClient.rules.get(id);
                Component name = text("rule." + id);
                Component label = state == null ? text("rule.unknown", name) : text(state ? "rule.on" : "rule.off", name);
                tiles.add(tile(label, new ItemStack((net.minecraft.world.level.ItemLike) (state == null ? Items.COAL : state ? Items.EMERALD : Items.REDSTONE)), () -> {
                    if (state == null) {
                        command("gamerule " + id).run();
                    } else {
                        sendCommand("gamerule " + id + " " + !state);
                        AdminClient.rules.put(id, !state);
                        self[0].refresh();
                        AdminClient.requestLater(10);
                    }
                }));
            }
            return tiles;
        }, () -> AdminClient.supported() ? text("admin.world.note") : text("admin.world.old"));
        return self[0];
    }

    private static SwitchyardScreen adminPlayers(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(text("admin.players"), parent, () -> {
            List<SwitchyardScreen.Tile> tiles = new ArrayList<>();
            for (PlayerInfo info : others()) {
                String name = info.getProfile().name();
                tiles.add(tile(Component.literal(name), head(info), () -> Minecraft.getInstance().gui.setScreen(adminPlayer(self[0], name))));
            }
            return tiles;
        }, () -> others().isEmpty() ? text("no_players") : text("admin.players.note"));
        return self[0];
    }

    private static SwitchyardScreen adminPlayer(Screen parent, String name) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(text("admin.player", name), parent, List.of(
                tile("tp_to", new ItemStack(Items.ENDER_PEARL), "tp " + name),
                tile("bring", new ItemStack(Items.LEAD), "tp " + name + " @s"),
                tile("kick", new ItemStack(Items.LEATHER_BOOTS), () -> confirm(self[0], "kick", "kick " + name, name)),
                tile("ban", new ItemStack(Items.IRON_BARS), () -> confirm(self[0], "ban", "ban " + name, name, name)),
                tile("survival", new ItemStack(Items.IRON_SWORD), "gamemode survival " + name),
                tile("creative", new ItemStack(Items.DIAMOND_BLOCK), "gamemode creative " + name),
                tile("spectator", new ItemStack(Items.ENDER_EYE), "gamemode spectator " + name),
                tile("kit_reset", new ItemStack(Items.CHEST), "kit reset " + name),
                tile("land_info_player", new ItemStack(Items.FILLED_MAP), "land info " + name)),
                null);
        return self[0];
    }
}
