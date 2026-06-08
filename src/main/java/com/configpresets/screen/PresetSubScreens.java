package com.configpresets.screen;

import com.configpresets.ConfigPresetsMod;
import com.configpresets.Preset;
import com.configpresets.PresetManager;
import com.configpresets.PresetToast;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

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
    private final Component message;
    private final Runnable onConfirm;

    ConfirmScreen(Screen parent, Component message, Runnable onConfirm) {
        super(Component.literal("Confirm"));
        this.parent = parent;
        this.message = message;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int by = this.height / 2 + 12;
        this.addRenderableWidget(Button.builder(Component.literal("Confirm").withStyle(ChatFormatting.RED), b -> {
            try {
                onConfirm.run();
            } catch (Throwable t) {
                ConfigPresetsMod.LOGGER.error("Confirm action failed", t);
            }
            onClose();
        }).bounds(cx - 154, by, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(cx + 4, by, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, message,
                this.width / 2, this.height / 2 - 24, 0xFFFFFFFF);
    }

    /** Escape returns directly to the PresetScreen, never stacking screens. */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
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
        super(Component.literal("Export Preset"));
        this.parent = parent;
        this.preset = preset;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4 + 24;

        Checkbox modListBox = Checkbox.builder(
                        Component.literal("Also export mod list (.txt)"), this.font)
                .pos(cx - 120, y)
                .selected(includeModList)
                .onValueChange((checkbox, checked) -> includeModList = checked)
                .build();
        this.addRenderableWidget(modListBox);

        this.addRenderableWidget(Button.builder(Component.literal("Export"), b -> doExport())
                .bounds(cx - 154, this.height - 52, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx + 4, this.height - 52, 150, 20).build());
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
        List<IModInfo> mods = new ArrayList<>(ModList.get().getMods());
        mods.sort((a, b) -> a.getModId().compareToIgnoreCase(b.getModId()));
        for (IModInfo mc : mods) {
            sb.append(mc.getModId())
                    .append(" = ")
                    .append(mc.getVersion().toString())
                    .append('\n');
        }
        return sb.toString();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 40, 0xFFFFFFFF);
        graphics.centeredText(this.font,
                Component.literal("Preset: " + preset.name).withStyle(ChatFormatting.GRAY),
                this.width / 2, this.height / 4, 0xFFAAAAAA);
        if (!status.isEmpty()) {
            graphics.centeredText(this.font,
                    Component.literal(status).withStyle(ChatFormatting.GREEN),
                    this.width / 2, this.height - 76, 0xFF55FF55);
        }
    }

    /** Escape returns directly to the PresetScreen, never stacking screens. */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
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
        super(Component.literal("Import Preset"));
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

        this.addRenderableWidget(Button.builder(Component.literal("Import"), b -> doImport())
                .bounds(this.width / 2 - 154, this.height - 40, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(this.width / 2 + 4, this.height - 40, 150, 20).build());
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
            onClose();
        } catch (Exception e) {
            ConfigPresetsMod.LOGGER.error("Failed to import {}", file.getFileName(), e);
            PresetToast.showInfo("Import failed (see log)");
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 32, 0xFFFFFFFF);

        graphics.fill(listX, listY, listX + listW, listY + listH, 0x66000000);

        if (files.isEmpty()) {
            graphics.centeredText(this.font,
                    Component.literal("No files in config-presets/exports/").withStyle(ChatFormatting.GRAY),
                    listX + listW / 2, listY + listH / 2 - 4, 0xFFAAAAAA);
            return;
        }

        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        int y = listY - scroll;
        for (int i = 0; i < files.size(); i++) {
            int rowTop = y + i * ROW_H;
            if (rowTop + ROW_H >= listY && rowTop <= listY + listH) {
                boolean sel = i == selectedIndex;
                boolean hovered = mouseX >= listX && mouseX <= listX + listW
                        && mouseY >= rowTop && mouseY < rowTop + ROW_H
                        && mouseY >= listY && mouseY <= listY + listH;
                if (sel || hovered) {
                    graphics.fill(listX, rowTop, listX + listW, rowTop + ROW_H,
                            sel ? 0x803A6EA5 : 0x40FFFFFF);
                }
                graphics.text(this.font,
                        Component.literal(files.get(i).getFileName().toString()),
                        listX + 6, rowTop + 5, 0xFFFFFFFF, false);
            }
        }
        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();
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
        return super.mouseClicked(event, doubled);
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
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}



/**
 * Shown after applying a preset that changed one or more mod config files. Those
 * mods only read their config at launch, so this offers to restart the game.
 */
class RestartPromptScreen extends Screen {

    private static final Component LINE_1 = Component.literal("Mod configurations have changed.");
    private static final Component LINE_2 =
            Component.literal("Would you like to restart the game to apply changes?");

    private final Screen parent;

    RestartPromptScreen(Screen parent) {
        super(Component.literal("Restart Required"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int by = this.height / 2 + 16;
        this.addRenderableWidget(Button.builder(
                Component.literal("Restart Now").withStyle(ChatFormatting.RED), b -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                // Gracefully ends the game loop so the launcher can be used to relaunch.
                mc.stop();
            }
        }).bounds(cx - 154, by, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Later"), b -> onClose())
                .bounds(cx + 4, by, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, LINE_1, this.width / 2, this.height / 2 - 28, 0xFFFFFFFF);
        graphics.centeredText(this.font, LINE_2, this.width / 2, this.height / 2 - 14, 0xFFFFFFFF);
    }

    /** Escape returns directly to the PresetScreen, never stacking screens. */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
