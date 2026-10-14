package classiccrates.client;

import classiccrates.ClassicCrates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Three small buttons beside every container screen (chests, Iron Chests, machines ...): Sort (asks Sort It Out to
 * sort the container), Dump (moves the player's main inventory into the container, hotbar untouched) and Match (moves
 * only the stacks whose item the container already holds). Dump and Match are ordinary shift-clicks sent one slot at a
 * time, so the server applies its usual rules and whatever does not fit stays where it was. Made for phones, where
 * shift-clicking 27 slots one by one is slow; works the same with a mouse.
 */
final class ContainerButtons {

    private static final int WIDTH = 38;
    private static final int HEIGHT = 16;
    private static final int GAP = 2;
    private static final String SORT_PACKET = "io.github.jamalam360.sort_it_out.network.C2SRequestSortPacket";
    private static final String JAMLIB_NETWORK = "io.github.jamalam360.jamlib.api.network.Network";
    private static final String JAMLIB_PAYLOAD = "io.github.jamalam360.jamlib.api.network.PacketPayload";
    /** Inventory slots 0-8 are the hotbar, 9-35 the main inventory; armour and offhand come after. */
    private static final int MAIN_START = 9;
    private static final int MAIN_END = 35;

    private ContainerButtons() {
    }

    static void init(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen) || screen instanceof InventoryScreen
                || screen instanceof CreativeModeInventoryScreen || screen.getMenu() instanceof InventoryMenu) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        Inventory inventory = minecraft.player.getInventory();
        if (containerSlots(menu, inventory).isEmpty() || mainInventorySlots(menu, inventory).isEmpty()) {
            return;
        }
        int x = screen.getLeftPos() - WIDTH - 4;
        if (x < 2) {
            x = Math.min(screen.getLeftPos() + screen.getImageWidth() + 4, screen.width - WIDTH - 2);
        }
        // Bottom-aligned with the screen: on phones the launcher's own buttons sit in the top-left corner (test18 put
        // these at the top and they were covered).
        int y = Math.max(2, screen.getTopPos() + screen.getImageHeight() - (3 * HEIGHT + 2 * GAP));
        event.addListener(button(x, y, "sort", () -> sort(screen)));
        event.addListener(button(x, y + HEIGHT + GAP, "dump", () -> move(screen, false)));
        event.addListener(button(x, y + 2 * (HEIGHT + GAP), "match", () -> move(screen, true)));
    }

    private static Button button(int x, int y, String name, Runnable action) {
        return Button.builder(Component.translatable("classiccrates.chest." + name), b -> action.run())
                .bounds(x, y, WIDTH, HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("classiccrates.chest." + name + ".tip")))
                .build();
    }

    /** Slots that belong to the block (or whatever the screen is for), not to the player. */
    private static List<Slot> containerSlots(AbstractContainerMenu menu, Inventory inventory) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container != inventory) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private static List<Slot> mainInventorySlots(AbstractContainerMenu menu, Inventory inventory) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() >= MAIN_START && slot.getContainerSlot() <= MAIN_END) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** Shift-clicks every main-inventory stack into the container; with {@code onlyMatching}, only kinds already there. */
    private static void move(AbstractContainerScreen<?> screen, boolean onlyMatching) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        Inventory inventory = minecraft.player.getInventory();
        List<ItemStack> present = new ArrayList<>();
        if (onlyMatching) {
            for (Slot slot : containerSlots(menu, inventory)) {
                if (slot.hasItem()) {
                    present.add(slot.getItem().copy());
                }
            }
        }
        int moved = 0;
        for (Slot slot : mainInventorySlots(menu, inventory)) {
            if (!slot.hasItem() || !slot.mayPickup(minecraft.player)) {
                continue;
            }
            if (onlyMatching && present.stream().noneMatch(stack -> ItemStack.isSameItemSameComponents(stack, slot.getItem()))) {
                continue;
            }
            minecraft.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, minecraft.player);
            moved++;
        }
        if (moved == 0) {
            minecraft.player.sendSystemMessage(Component.translatable(onlyMatching ? "classiccrates.chest.match.none" : "classiccrates.chest.dump.none"));
        }
    }

    /** Sort It Out's own request packet, sent through JamLib; both mods are in the pack but not compile dependencies. */
    private static void sort(AbstractContainerScreen<?> screen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        List<Slot> container = containerSlots(menu, minecraft.player.getInventory());
        try {
            Object packet = Class.forName(SORT_PACKET).getConstructor(int.class, int.class).newInstance(menu.containerId, container.get(0).index);
            Class.forName(JAMLIB_NETWORK).getMethod("sendToServer", Class.forName(JAMLIB_PAYLOAD)).invoke(null, packet);
        } catch (ReflectiveOperationException | RuntimeException e) {
            ClassicCrates.LOGGER.warn("Could not ask Sort It Out to sort the container", e);
            minecraft.player.sendSystemMessage(Component.translatable("classiccrates.chest.sort.missing"));
        }
    }
}
