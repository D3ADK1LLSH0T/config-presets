package com.configpresets.config;

import com.configpresets.config.ModConfig.ButtonPosition;
import com.configpresets.screen.PresetScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Hand-built settings screen replacing the Cloth Config screen from the Fabric
 * build. Uses vanilla widgets (checkboxes and cycling buttons) so the mod has no
 * dependency on Cloth Config. Registered through NeoForge's
 * {@code IConfigScreenFactory} and reachable from the in-game shortcuts.
 */
public class ConfigScreen extends Screen {

    private final Screen parent;

    private int presetIndex = 0;
    private List<String> presetNames;
    private Button presetButton;
    private Button positionButton;

    public ConfigScreen(Screen parent) {
        super(Component.literal("Config Presets"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ModConfig cfg = ModConfig.get();

        int cx = this.width / 2;
        int left = cx - 155;
        int right = cx + 5;
        int y = 40;
        int rowH = 24;

        // ---- General ----
        addRenderableWidget(Checkbox.builder(Component.literal("Auto-save active preset on exit"), this.font)
                .pos(left, y).selected(cfg.autoSaveOnExit)
                .tooltip(Tooltip.create(Component.literal(
                        "When enabled, the currently active preset is updated\nwith your settings as you leave the game.")))
                .onValueChange((c, v) -> cfg.autoSaveOnExit = v).build());
        addRenderableWidget(Checkbox.builder(Component.literal("Create backup before applying preset"), this.font)
                .pos(right, y).selected(cfg.backupBeforeApply)
                .tooltip(Tooltip.create(Component.literal(
                        "Snapshot your current options before a preset overwrites them.")))
                .onValueChange((c, v) -> cfg.backupBeforeApply = v).build());
        y += rowH;

        addRenderableWidget(Checkbox.builder(Component.literal("Show toast notifications"), this.font)
                .pos(left, y).selected(cfg.showToasts)
                .tooltip(Tooltip.create(Component.literal("Show a pop-up toast when a preset is applied.")))
                .onValueChange((c, v) -> cfg.showToasts = v).build());
        addRenderableWidget(Checkbox.builder(Component.literal("Warn when a restart may be needed"), this.font)
                .pos(right, y).selected(cfg.showRestartWarning)
                .tooltip(Tooltip.create(Component.literal(
                        "After applying a preset that changes mod configs, warn that\nsome mods may need a game restart to fully apply them.\nNot every mod can reload its config at runtime.")))
                .onValueChange((c, v) -> cfg.showRestartWarning = v).build());
        y += rowH;

        // ---- UI ----
        addRenderableWidget(Checkbox.builder(Component.literal("Show button on Title Screen"), this.font)
                .pos(left, y).selected(cfg.showButtonOnTitleScreen)
                .onValueChange((c, v) -> cfg.showButtonOnTitleScreen = v).build());
        addRenderableWidget(Checkbox.builder(Component.literal("Show button on Pause Screen"), this.font)
                .pos(right, y).selected(cfg.showButtonOnPauseScreen)
                .onValueChange((c, v) -> cfg.showButtonOnPauseScreen = v).build());
        y += rowH + 4;

        // Button position cycle.
        positionButton = Button.builder(positionLabel(cfg.buttonPosition), b -> {
            ButtonPosition[] values = ButtonPosition.values();
            ModConfig c = ModConfig.get();
            c.buttonPosition = values[(c.buttonPosition.ordinal() + 1) % values.length];
            b.setMessage(positionLabel(c.buttonPosition));
        }).bounds(left, y, 150, 20)
                .tooltip(Tooltip.create(Component.literal("Where the preset button appears on the title / pause screens.")))
                .build();
        addRenderableWidget(positionButton);

        // Default preset cycle.
        presetNames = ModConfig.collectPresetNames();
        presetIndex = Math.max(0, presetNames.indexOf(cfg.defaultPresetOnLaunch));
        presetButton = Button.builder(presetLabel(), b -> {
            presetIndex = (presetIndex + 1) % presetNames.size();
            ModConfig.applyDefaultPresetSelection(presetNames.get(presetIndex));
            b.setMessage(presetLabel());
        }).bounds(right, y, 150, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "This preset is applied automatically when the game launches.\nOnly one preset can be the launch default.")))
                .build();
        addRenderableWidget(presetButton);
        y += rowH + 8;

        // ---- Navigation ----
        addRenderableWidget(Button.builder(Component.literal("Open Presets Manager"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new PresetScreen(this));
            }
        }).bounds(cx - 100, y, 200, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(cx - 100, this.height - 28, 200, 20).build());
    }

    private static Component positionLabel(ButtonPosition position) {
        return Component.literal("Button position: " + ModConfig.prettyEnum(position));
    }

    private Component presetLabel() {
        return Component.literal("Default on launch: " + presetNames.get(presetIndex));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        ModConfig.save();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
