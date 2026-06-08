package com.configpresets;

import com.configpresets.config.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;

/**
 * Lightweight toast shown when a preset is applied. Drawn in the style of an
 * advancement toast: a dark rounded panel with a title line and a description
 * line. Implemented directly against the 1.21.11 {@link Toast} interface
 * (which drives visibility via {@link #update} rather than the draw return).
 */
public class PresetToast implements Toast {

    private static final long DURATION_MS = 4000L;

    private static final int WIDTH = 160;
    private static final int HEIGHT = 32;

    private final Component title;
    private final Component description;

    private Toast.Visibility visibility = Toast.Visibility.SHOW;
    private long firstUpdate = -1L;

    public PresetToast(Component title, Component description) {
        this.title = title;
        this.description = description;
    }

    /**
     * Convenience helper to queue a "preset applied" toast, honoring the
     * {@code showToasts} config option. Safe to call from the client thread.
     */
    public static void showApplied(String presetName) {
        if (!ModConfig.get().showToasts) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getToastManager() == null) {
            return;
        }
        Component title = Component.literal("Config Presets").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
        Component desc = Component.literal("Applied: " + presetName).withStyle(ChatFormatting.WHITE);
        client.getToastManager().addToast(new PresetToast(title, desc));
    }

    /** Generic informational toast (e.g. saved / imported / exported). */
    public static void showInfo(String line) {
        if (!ModConfig.get().showToasts) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getToastManager() == null) {
            return;
        }
        client.getToastManager().addToast(new PresetToast(
                Component.literal("Config Presets").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                Component.literal(line).withStyle(ChatFormatting.WHITE)));
    }

    @Override
    public void update(ToastManager manager, long time) {
        if (firstUpdate < 0L) {
            firstUpdate = time;
        }
        this.visibility = (time - firstUpdate >= DURATION_MS)
                ? Toast.Visibility.HIDE
                : Toast.Visibility.SHOW;
    }

    @Override
    public Toast.Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return HEIGHT;
    }

    @Override
    public void render(GuiGraphics graphics, Font font, long startTime) {
        int w = width();
        int h = height();

        // Panel background: outer border + inner fill, mimicking the toast frame.
        graphics.fill(0, 0, w, h, 0xF0100010);            // dark translucent body
        graphics.fill(0, 0, w, 1, 0xFF4A4A6A);            // top edge
        graphics.fill(0, h - 1, w, h, 0xFF1A1A2A);        // bottom edge
        graphics.fill(0, 0, 1, h, 0xFF4A4A6A);            // left edge
        graphics.fill(w - 1, 0, w, h, 0xFF1A1A2A);        // right edge

        // A small accent swatch on the left, like an icon slot.
        graphics.fill(4, 8, 16, 24, 0xFF55FF55);
        graphics.fill(5, 9, 15, 23, 0xFF2E8B2E);

        int textX = 22;
        graphics.drawString(font, title, textX, 7, 0xFFFFFFFF, false);
        graphics.drawString(font, description, textX, 18, 0xFFCCCCCC, false);
    }

    @Override
    public Object getToken() {
        // Distinct token so multiple preset toasts can stack rather than replace.
        return this;
    }
}
