package classiccrates.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * A grid of large tiles, sized for a touch screen: every tile is one tap, nothing needs typing. Used for the Switchyard
 * menu and its sub-pages (land, players, shortcuts, crates, cosmetics, rules, admin). The tiles are built by a supplier
 * so a page can be refreshed when its content changes (rule states arriving from the server, players joining).
 */
public class SwitchyardScreen extends Screen {

    static final int COPPER = 0xFFCB9060;
    static final int CHARCOAL = 0xD9201A16;
    static final int CHARCOAL_LIT = 0xF23A2C22;
    static final int TEXT = 0xFFF2E6D8;
    private static final int GAP = 6;

    /** One tile: what it says, the item drawn on it and what a tap does. */
    public record Tile(Component label, ItemStack icon, Runnable action) {
    }

    private final @Nullable Screen parent;
    private final Supplier<List<Tile>> tiles;
    private final Supplier<@Nullable Component> note;
    private int noteY;
    private List<FormattedCharSequence> noteLines = List.of();

    public SwitchyardScreen(Component title, @Nullable Screen parent, List<Tile> tiles, @Nullable Component note) {
        this(title, parent, () -> tiles, () -> note);
    }

    public SwitchyardScreen(Component title, @Nullable Screen parent, Supplier<List<Tile>> tiles, Supplier<@Nullable Component> note) {
        super(title);
        this.parent = parent;
        this.tiles = tiles;
        this.note = note;
    }

    /** Rebuilds the tiles from the supplier (same screen, same parent). */
    public void refresh() {
        this.clearWidgets();
        this.init();
    }

    @Override
    protected void init() {
        List<Tile> tiles = this.tiles.get();
        Component note = this.note.get();
        this.noteLines = note == null ? List.of() : this.font.split(note, this.width - 40);
        if (this.noteLines.size() > 4) {
            this.noteLines = this.noteLines.subList(0, 4);
        }
        int noteLines = this.noteLines.size();
        int top = 26;
        int bottom = this.height - 30 - noteLines * 10 - (noteLines > 0 ? 4 : 0);
        int columns = Math.max(1, Math.min(tiles.size(), this.width >= this.height ? (tiles.size() > 8 ? 5 : 4) : 2));
        int rows = Math.max(1, (tiles.size() + columns - 1) / columns);
        int tileWidth = Math.min(120, (this.width - 24 - (columns - 1) * GAP) / columns);
        int tileHeight = Math.max(24, Math.min(76, (bottom - top - (rows - 1) * GAP) / rows));
        int left = (this.width - columns * tileWidth - (columns - 1) * GAP) / 2;
        int gridTop = top + Math.max(0, (bottom - top - rows * tileHeight - (rows - 1) * GAP) / 2);
        for (int index = 0; index < tiles.size(); index++) {
            Tile tile = tiles.get(index);
            this.addRenderableWidget(new TileButton(left + (index % columns) * (tileWidth + GAP), gridTop + (index / columns) * (tileHeight + GAP),
                    tileWidth, tileHeight, tile));
        }
        this.noteY = gridTop + rows * tileHeight + (rows - 1) * GAP + 6;
        this.addRenderableWidget(Button.builder(this.parent == null ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, button -> this.onClose())
                .bounds(this.width / 2 - 60, this.height - 26, 120, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 10, COPPER);
        int y = this.noteY;
        for (FormattedCharSequence line : this.noteLines) {
            graphics.centeredText(this.font, line, this.width / 2, y, TEXT);
            y += 10;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static class TileButton extends AbstractButton {

        private final Tile tile;

        TileButton(int x, int y, int width, int height, Tile tile) {
            super(x, y, width, height, tile.label());
            this.tile = tile;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            this.tile.action().run();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            boolean lit = this.isHoveredOrFocused();
            graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), lit ? CHARCOAL_LIT : CHARCOAL);
            graphics.outline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), COPPER);
            if (lit) {
                graphics.outline(this.getX() + 1, this.getY() + 1, this.getWidth() - 2, this.getHeight() - 2, COPPER);
            }
            var font = Minecraft.getInstance().font;
            int centre = this.getX() + this.getWidth() / 2;
            if (this.getHeight() >= 52) {
                // Room for a double-size icon above the label.
                graphics.pose().pushMatrix();
                graphics.pose().translate(centre - 16, this.getY() + (this.getHeight() - 12 - 32) / 2);
                graphics.pose().scale(2.0F, 2.0F);
                graphics.fakeItem(this.tile.icon(), 0, 0);
                graphics.pose().popMatrix();
                graphics.centeredText(font, this.getMessage(), centre, this.getY() + this.getHeight() - 13, TEXT);
            } else {
                int width = 20 + font.width(this.getMessage());
                graphics.fakeItem(this.tile.icon(), centre - width / 2, this.getY() + (this.getHeight() - 16) / 2);
                graphics.text(font, this.getMessage(), centre - width / 2 + 20, this.getY() + (this.getHeight() - 8) / 2, TEXT);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }
}
