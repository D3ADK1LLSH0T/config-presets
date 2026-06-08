package com.configpresets.screen;

import net.minecraft.client.font.DrawnTextConsumer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.util.Identifier;

/**
 * A {@link ButtonWidget} that renders the standard Minecraft button background with
 * the mod's custom wrench icon centered on top of it, instead of a text label. Used
 * for the compact preset buttons on the title / pause screens and the "Config"
 * shortcut on the preset manager screen.
 */
public class IconButton extends ButtonWidget {

    /** 16x16 icon texture shipped in the mod's assets. */
    private static final Identifier ICON =
            Identifier.of("configpresets", "textures/gui/preset_button.png");
    private static final int ICON_SIZE = 16;

    public IconButton(int x, int y, int width, int height, net.minecraft.text.Text narrationLabel, PressAction onPress) {
        super(x, y, width, height, narrationLabel, onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER);
    }

    @Override
    protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
        // Draw the standard button background first, then the wrench icon on top.
        this.drawButton(context);
        int ix = this.getX() + (this.width - ICON_SIZE) / 2;
        int iy = this.getY() + (this.height - ICON_SIZE) / 2;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, ICON, ix, iy, 0.0F, 0.0F,
                ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
    }

    @Override
    protected void drawLabel(DrawnTextConsumer consumer) {
        // No text label: the icon is drawn in drawIcon(...) instead.
    }
}
