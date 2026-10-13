package classiccrates.client;

import classiccrates.ClassicCrates;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.mojang.blaze3d.platform.InputConstants;

import java.net.URI;
import java.util.List;

/**
 * The Switchyard menu: one screen of large tiles for the things players otherwise type as commands (/spawn, /home,
 * /sethome, /bed, /oclaims, /cosmetic ...). Opened with a key (Home by default) or from the pause menu. Client only; every
 * tile sends an ordinary command, so the server decides what is allowed.
 */
@Mod(value = ClassicCrates.MOD_ID, dist = Dist.CLIENT)
public class SwitchyardClient {

    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(ClassicCrates.id("switchyard"));
    private static final KeyMapping OPEN_MENU = new KeyMapping("key.classiccrates.switchyard_menu", InputConstants.KEY_HOME, CATEGORY);
    private static final URI VOTE_PAGE = URI.create("https://jorry.live/switchyard/vote");
    private static final URI RULES_PAGE = URI.create("https://jorry.live/switchyard/page/rules");

    public SwitchyardClient(IEventBus modBus) {
        boolean layoutInstalled = PhoneLayout.install();
        boolean[] told = {!layoutInstalled};
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn.class, event -> {
            if (!told[0]) {
                told[0] = true;
                event.getPlayer().sendSystemMessage(Component.translatable("classiccrates.phone.layout_installed"));
            }
        });
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.registerCategory(CATEGORY);
            event.register(OPEN_MENU);
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            Minecraft minecraft = Minecraft.getInstance();
            while (OPEN_MENU.consumeClick()) {
                if (minecraft.player != null && minecraft.gui.screen() == null) {
                    minecraft.gui.setScreen(menu(null));
                }
            }
        });
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, event -> {
            if (event.getScreen() instanceof PauseScreen pause && Minecraft.getInstance().player != null) {
                event.addListener(Button.builder(Component.translatable("classiccrates.menu.title"), button -> Minecraft.getInstance().gui.setScreen(menu(pause)))
                        .bounds(6, 6, 100, 24).build());
            }
        });
    }

    private static SwitchyardScreen.Tile tile(String name, ItemStack icon, Runnable action) {
        return new SwitchyardScreen.Tile(Component.translatable("classiccrates.menu." + name), icon, action);
    }

    /** Sends a command as the player and returns to the game, where the answer shows in chat. */
    private static Runnable command(String command) {
        return () -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.connection.sendCommand(command);
            }
            minecraft.gui.setScreen(null);
        };
    }

    /** Asks first; "yes" sends the command, "no" goes back to the page it came from. */
    private static void confirm(Screen back, String question, String command) {
        Minecraft.getInstance().gui.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                command(command).run();
            } else {
                Minecraft.getInstance().gui.setScreen(back);
            }
        }, Component.translatable("classiccrates.menu.title"), Component.translatable("classiccrates.menu.confirm." + question)));
    }

    static SwitchyardScreen menu(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(Component.translatable("classiccrates.menu.title"), parent, List.of(
                tile("spawn", new ItemStack(Items.COMPASS), command("spawn")),
                tile("home", new ItemStack(Items.OAK_DOOR), command("home")),
                tile("sethome", new ItemStack(Items.CAMPFIRE), () -> confirm(self[0], "sethome", "sethome")),
                tile("claims", new ItemStack(Items.FILLED_MAP), () -> Minecraft.getInstance().gui.setScreen(claims(self[0]))),
                tile("bed", new ItemStack(Items.CLOCK), command("bed")),
                tile("crates", new ItemStack(ClassicCrates.CRATE_KEY.get()), () -> Minecraft.getInstance().gui.setScreen(crates(self[0]))),
                tile("cosmetics", new ItemStack(Items.LEATHER_CHESTPLATE), () -> Minecraft.getInstance().gui.setScreen(cosmetics(self[0]))),
                tile("rules", new ItemStack(Items.WRITABLE_BOOK), () -> Minecraft.getInstance().gui.setScreen(rules(self[0])))), null);
        return self[0];
    }

    private static SwitchyardScreen crates(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        int keys = minecraft.player == null ? 0 : minecraft.player.getInventory().countItem(ClassicCrates.CRATE_KEY.get());
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(Component.translatable("classiccrates.menu.crates"), parent, List.of(
                tile("vote", new ItemStack(Items.PAPER), () -> ConfirmLinkScreen.confirmLinkNow(self[0], VOTE_PAGE)),
                tile("to_crate", new ItemStack(ClassicCrates.CRATE_ITEM.get()), command("spawn"))),
                Component.translatable("classiccrates.menu.crates.note", keys));
        return self[0];
    }

    /** Open Parties and Claims commands for the chunk the player stands in. */
    private static SwitchyardScreen claims(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(Component.translatable("classiccrates.menu.claims"), parent, List.of(
                tile("claim", new ItemStack(Items.OAK_FENCE), command("oclaims claim")),
                tile("unclaim", new ItemStack(Items.SHEARS), () -> confirm(self[0], "unclaim", "oclaims unclaim"))),
                Component.translatable("classiccrates.menu.claims.note"));
        return self[0];
    }

    private static SwitchyardScreen cosmetics(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(Component.translatable("classiccrates.menu.cosmetics"), parent, List.of(
                tile("trail_off", new ItemStack(Items.BLAZE_POWDER), command("cosmetic trail off")),
                tile("unskin", new ItemStack(Items.SHEARS), () -> confirm(self[0], "unskin", "cosmetic remove"))),
                Component.translatable("classiccrates.menu.cosmetics.note"));
        return self[0];
    }

    private static SwitchyardScreen rules(Screen parent) {
        SwitchyardScreen[] self = new SwitchyardScreen[1];
        self[0] = new SwitchyardScreen(Component.translatable("classiccrates.menu.rules"), parent, List.of(
                tile("rules_page", new ItemStack(Items.WRITABLE_BOOK), () -> ConfirmLinkScreen.confirmLinkNow(self[0], RULES_PAGE))),
                Component.translatable("classiccrates.menu.rules.note"));
        return self[0];
    }
}
