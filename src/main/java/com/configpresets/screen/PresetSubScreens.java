package com.configpresets.screen;

import com.configpresets.ConfigPresetsMod;
import com.configpresets.Preset;
import com.configpresets.PresetManager;
import com.configpresets.PresetToast;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

// =====================================================================
// This file groups the three small auxiliary screens used by
// PresetScreen. They are intentionally package-private top-level classes
// so the GUI layer stays compact.
// =====================================================================

/**
 * Generic yes/no confirmation dialog with a message and Confirm/Cancel buttons.
 */
class ConfirmScreen extends Screen {

    private final Screen parent;
    private final Text message;
    private final Runnable onConfirm;

    ConfirmScreen(Screen parent, Text message, Runnable onConfirm) {
        super(Text.literal("Confirm"));
        this.parent = parent;
        this.message = message;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int by = this.height / 2 + 12;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Confirm").formatted(Formatting.RED), b -> {
            try {
                onConfirm.run();
            } catch (Throwable t) {
                ConfigPresetsMod.LOGGER.error("Confirm action failed", t);
            }
            close();
        }).dimensions(cx - 154, by, 150, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close())
                .dimensions(cx + 4, by, 150, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // NOTE (1.21.11): do NOT call this.renderBackground(...) here.
        // Screen#render already renders the blurred background, and the
        // 1.21.11 render backend throws "Can only blur once per frame" if
        // the blur is applied twice. super.render(...) handles it.
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, message,
                this.width / 2, this.height / 2 - 24, 0xFFFFFFFF);
    }

    /** Escape returns directly to the PresetScreen, never stacking screens. */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}

/**
 * Export a single preset to {@code config-presets/exports/}, optionally writing
 * an accompanying human-readable mod list.
 */
class ExportScreen extends Screen {

    private final PresetScreen parent;
    private final Preset preset;
    private final PresetManager pm = ConfigPresetsMod.getPresetManager();

    private boolean includeModList = false;
    private String status = "";

    ExportScreen(PresetScreen parent, Preset preset) {
        super(Text.literal("Export Preset"));
        this.parent = parent;
        this.preset = preset;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4 + 24;

        CheckboxWidget modListBox = CheckboxWidget.builder(
                        Text.literal("Also export mod list (.txt)"), this.textRenderer)
                .pos(cx - 120, y)
                .checked(includeModList)
                .callback((checkbox, checked) -> includeModList = checked)
                .build();
        this.addDrawableChild(modListBox);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Export"), b -> doExport())
                .dimensions(cx - 154, this.height - 52, 150, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(cx + 4, this.height - 52, 150, 20).build());
    }

    private void doExport() {
        try {
            Path exportsDir = pm.getPresetsDir().resolve("exports");
            Files.createDirectories(exportsDir);

            String base = PresetManager.sanitize(preset.name);
            Path target = exportsDir.resolve(base + ".json");
            Files.writeString(target, preset.toJson(), StandardCharsets.UTF_8);

            if (includeModList) {
                Path txt = exportsDir.resolve(base + "_mods.txt");
                Files.writeString(txt, buildModList(), StandardCharsets.UTF_8);
            }

            status = "Exported to config-presets/exports/" + target.getFileName();
            PresetToast.showInfo("Exported: " + preset.name);
        } catch (IOException e) {
            ConfigPresetsMod.LOGGER.error("Failed to export preset {}", preset.name, e);
            status = "Export failed (see log)";
        }
    }

    private String buildModList() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Mod list captured for preset: ").append(preset.name).append('\n');
        List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        mods.sort((a, b) -> a.getMetadata().getId().compareToIgnoreCase(b.getMetadata().getId()));
        for (ModContainer mc : mods) {
            sb.append(mc.getMetadata().getId())
                    .append(" = ")
                    .append(mc.getMetadata().getVersion().getFriendlyString())
                    .append('\n');
        }
        return sb.toString();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // NOTE (1.21.11): do NOT call this.renderBackground(...) here.
        // Screen#render already renders the blurred background, and the
        // 1.21.11 render backend throws "Can only blur once per frame" if
        // the blur is applied twice. super.render(...) handles it.
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 40, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Preset: " + preset.name).formatted(Formatting.GRAY),
                this.width / 2, this.height / 4, 0xFFAAAAAA);
        if (!status.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(status).formatted(Formatting.GREEN),
                    this.width / 2, this.height - 76, 0xFF55FF55);
        }
    }

    /** Escape returns directly to the PresetScreen, never stacking screens. */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}

/**
 * Import a preset from a {@code .json} file in {@code config-presets/exports/}.
 */
class ImportScreen extends Screen {

    private final PresetScreen parent;
    private final PresetManager pm = ConfigPresetsMod.getPresetManager();

    private final List<Path> files = new ArrayList<>();
    private int selectedIndex = -1;

    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private static final int ROW_H = 18;
    private int scroll = 0;

    ImportScreen(PresetScreen parent) {
        super(Text.literal("Import Preset"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        files.clear();
        Path exportsDir = pm.getPresetsDir().resolve("exports");
        if (Files.isDirectory(exportsDir)) {
            try (Stream<Path> stream = Files.list(exportsDir)) {
                stream.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                        .sorted()
                        .forEach(files::add);
            } catch (IOException e) {
                ConfigPresetsMod.LOGGER.error("Failed to list exports directory", e);
            }
        }

        listX = this.width / 2 - 150;
        listY = 56;
        listW = 300;
        listH = this.height - listY - 56;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Import"), b -> doImport())
                .dimensions(this.width / 2 - 154, this.height - 40, 150, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close())
                .dimensions(this.width / 2 + 4, this.height - 40, 150, 20).build());
    }

    private void doImport() {
        if (selectedIndex < 0 || selectedIndex >= files.size()) {
            return;
        }
        Path file = files.get(selectedIndex);
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Preset preset = Preset.fromJson(json);
            if (preset.name == null || preset.name.isBlank()) {
                preset.name = file.getFileName().toString().replaceFirst("\\.json$", "");
            }
            // Avoid clobbering an existing preset by giving imports a unique name.
            String baseName = preset.name;
            String candidate = baseName;
            int i = 2;
            while (pm.exists(candidate)) {
                candidate = baseName + " (imported " + i++ + ")";
            }
            preset.name = candidate;
            preset.isDefault = false;
            if (pm.save(preset)) {
                PresetToast.showInfo("Imported: " + preset.name);
                parent.onEditorSaved();
            }
            close();
        } catch (Exception e) {
            ConfigPresetsMod.LOGGER.error("Failed to import {}", file.getFileName(), e);
            PresetToast.showInfo("Import failed (see log)");
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // NOTE (1.21.11): do NOT call this.renderBackground(...) here.
        // Screen#render already renders the blurred background, and the
        // 1.21.11 render backend throws "Can only blur once per frame" if
        // the blur is applied twice. super.render(...) handles it.
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 32, 0xFFFFFFFF);

        context.fill(listX, listY, listX + listW, listY + listH, 0x66000000);

        if (files.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("No files in config-presets/exports/").formatted(Formatting.GRAY),
                    listX + listW / 2, listY + listH / 2 - 4, 0xFFAAAAAA);
            return;
        }

        context.enableScissor(listX, listY, listX + listW, listY + listH);
        int y = listY - scroll;
        for (int i = 0; i < files.size(); i++) {
            int rowTop = y + i * ROW_H;
            if (rowTop + ROW_H >= listY && rowTop <= listY + listH) {
                boolean sel = i == selectedIndex;
                boolean hovered = mouseX >= listX && mouseX <= listX + listW
                        && mouseY >= rowTop && mouseY < rowTop + ROW_H
                        && mouseY >= listY && mouseY <= listY + listH;
                if (sel || hovered) {
                    context.fill(listX, rowTop, listX + listW, rowTop + ROW_H,
                            sel ? 0x803A6EA5 : 0x40FFFFFF);
                }
                context.drawText(this.textRenderer,
                        Text.literal(files.get(i).getFileName().toString()),
                        listX + 6, rowTop + 5, 0xFFFFFFFF, false);
            }
        }
        context.disableScissor();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (mouseX >= listX && mouseX <= listX + listW
                && mouseY >= listY && mouseY <= listY + listH) {
            int rel = (int) (mouseY - listY + scroll);
            int idx = rel / ROW_H;
            if (idx >= 0 && idx < files.size()) {
                selectedIndex = idx;
                if (doubled) {
                    doImport();
                }
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int contentH = files.size() * ROW_H;
        int maxScroll = Math.max(0, contentH - listH);
        scroll = Math.max(0, Math.min((int) (scroll - verticalAmount * 12), maxScroll));
        return true;
    }

    /** Escape returns directly to the PresetScreen, never stacking screens. */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}

// =====================================================================
// Restart prompt shown after applying a preset that changed mod configs.
// Mods only re-read their config files at launch, so we offer a restart.
// =====================================================================
class RestartPromptScreen extends Screen {

    private static final Text LINE_1 = Text.literal("Mod configurations have changed.");
    private static final Text LINE_2 =
            Text.literal("Would you like to restart the game to apply changes?");

    private final Screen parent;

    RestartPromptScreen(Screen parent) {
        super(Text.literal("Restart Required"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int by = this.height / 2 + 16;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Restart Now").formatted(Formatting.RED), b -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) {
                // Gracefully ends the game loop so the launcher can be used to relaunch.
                mc.scheduleStop();
            }
        }).dimensions(cx - 154, by, 150, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Later"), b -> close())
                .dimensions(cx + 4, by, 150, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // NOTE (1.21.11): do NOT call this.renderBackground(...) here.
        // Screen#render already renders the blurred background, and the
        // 1.21.11 render backend throws "Can only blur once per frame" if
        // the blur is applied twice. super.render(...) handles it.
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, LINE_1,
                this.width / 2, this.height / 2 - 28, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, LINE_2,
                this.width / 2, this.height / 2 - 14, 0xFFFFFFFF);
    }

    /** Escape returns directly to the PresetScreen, never stacking screens. */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
