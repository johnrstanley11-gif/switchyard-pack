package classiccrates.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown once after the pack put its touch layout into an Android launcher: the layout is installed but not switched
 * on, and the steps to do that are in the launcher, not in the game. A chat line scrolls away under the other start-up
 * messages, so this is a screen with a button.
 */
final class PhoneHintScreen extends Screen {

    private MultiLineTextWidget body;

    PhoneHintScreen() {
        super(Component.translatable("classiccrates.phone.hint.title"));
    }

    @Override
    protected void init() {
        int width = Math.min(this.width - 40, 320);
        this.body = this.addRenderableWidget(new MultiLineTextWidget(0, 0, Component.translatable("classiccrates.phone.hint.body"), this.font)
                .setMaxWidth(width).setCentered(true));
        this.body.setX(this.width / 2 - this.body.getWidth() / 2);
        int top = Math.max(26, (this.height - this.body.getHeight() - 36) / 2);
        this.body.setY(top);
        this.addRenderableWidget(Button.builder(Component.translatable("classiccrates.phone.hint.ok"), button -> this.onClose())
                .bounds(this.width / 2 - 60, Math.min(this.height - 26, top + this.body.getHeight() + 12), 120, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 10, SwitchyardScreen.COPPER);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
