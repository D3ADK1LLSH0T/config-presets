package com.configpresets.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A {@link Button} that renders the standard Minecraft button background with the
 * mod's custom wrench icon centered on top of it, instead of a text label. Used for
 * the compact preset buttons on the title / pause screens and the "Config" shortcut
 * on the preset manager screen.
 */
public class IconButton extends Button {

    /** 16x16 icon texture shipped in the mod's assets. */
    private static final Identifier ICON =
            Identifier.fromNamespaceAndPath("configpresets", "textures/gui/preset_button.png");
    private static final int ICON_SIZE = 16;

    public IconButton(int x, int y, int width, int height, Component narrationLabel, OnPress onPress) {
        super(x, y, width, height, narrationLabel, onPress, Button.DEFAULT_NARRATION);
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Draw the standard button background/sprite first, then the wrench icon on top.
        this.renderDefaultSprite(graphics);
        int ix = this.getX() + (this.width - ICON_SIZE) / 2;
        int iy = this.getY() + (this.height - ICON_SIZE) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, ix, iy, 0.0F, 0.0F,
                ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
    }
}
