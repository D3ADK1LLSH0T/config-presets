package com.configpresets;

import com.configpresets.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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

    private final Text title;
    private final Text description;

    private Toast.Visibility visibility = Toast.Visibility.SHOW;
    private long firstUpdate = -1L;

    public PresetToast(Text title, Text description) {
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getToastManager() == null) {
            return;
        }
        Text title = Text.literal("Config Presets").formatted(Formatting.GREEN, Formatting.BOLD);
        Text desc = Text.literal("Applied: " + presetName).formatted(Formatting.WHITE);
        client.getToastManager().add(new PresetToast(title, desc));
    }

    /** Generic informational toast (e.g. saved / imported / exported). */
    public static void showInfo(String line) {
        if (!ModConfig.get().showToasts) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getToastManager() == null) {
            return;
        }
        client.getToastManager().add(new PresetToast(
                Text.literal("Config Presets").formatted(Formatting.AQUA, Formatting.BOLD),
                Text.literal(line).formatted(Formatting.WHITE)));
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
    public Toast.Visibility getVisibility() {
        return visibility;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public void draw(DrawContext context, TextRenderer textRenderer, long startTime) {
        int w = getWidth();
        int h = getHeight();

        // Panel background: outer border + inner fill, mimicking the toast frame.
        context.fill(0, 0, w, h, 0xF0100010);            // dark translucent body
        context.fill(0, 0, w, 1, 0xFF4A4A6A);            // top edge
        context.fill(0, h - 1, w, h, 0xFF1A1A2A);        // bottom edge
        context.fill(0, 0, 1, h, 0xFF4A4A6A);            // left edge
        context.fill(w - 1, 0, w, h, 0xFF1A1A2A);        // right edge

        // A small accent swatch on the left, like an icon slot.
        context.fill(4, 8, 16, 24, 0xFF55FF55);
        context.fill(5, 9, 15, 23, 0xFF2E8B2E);

        int textX = 22;
        context.drawText(textRenderer, title, textX, 7, 0xFFFFFFFF, false);
        context.drawText(textRenderer, description, textX, 18, 0xFFCCCCCC, false);
    }

    @Override
    public Object getType() {
        // Distinct type so multiple preset toasts can stack rather than replace.
        return this;
    }
}
