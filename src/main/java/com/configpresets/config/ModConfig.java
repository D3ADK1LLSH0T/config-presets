package com.configpresets.config;

import com.configpresets.ConfigPresetsMod;
import com.configpresets.Preset;
import com.configpresets.screen.PresetScreen;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent mod settings, backed by {@code config/configpresets.json}, plus a
 * Cloth Config screen builder used by the Mod Menu integration.
 *
 * <p>This is intentionally a small POJO with public fields so Gson can read/write
 * it directly. A single shared instance is exposed via {@link #get()}.</p>
 */
public class ModConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("configpresets/ModConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Where the preset button is rendered on the title / pause screens. */
    public enum ButtonPosition {
        NEXT_TO_BUTTONS,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    // ----- General -----
    public boolean autoSaveOnExit = false;
    public boolean backupBeforeApply = true;
    public boolean showToasts = true;
    /**
     * When enabled, a notification warns that some mods may need a game restart
     * to fully apply restored config changes (some config systems cannot reload
     * at runtime). Disable to always show the plain "applied" toast.
     */
    public boolean showRestartWarning = true;

    // ----- UI -----
    public boolean showButtonOnTitleScreen = true;
    public boolean showButtonOnPauseScreen = true;
    public ButtonPosition buttonPosition = ButtonPosition.NEXT_TO_BUTTONS;

    // ----- Presets -----
    /** Name of the preset to apply on launch, or {@code "None"}. */
    public String defaultPresetOnLaunch = NONE;

    public static final String NONE = "None";

    // ---------------------------------------------------------------------
    // Singleton + persistence
    // ---------------------------------------------------------------------

    private static ModConfig instance;
    private static Path configFile;

    public static ModConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    private static Path file() {
        if (configFile == null) {
            configFile = FabricLoader.getInstance().getConfigDir().resolve("configpresets.json");
        }
        return configFile;
    }

    public static synchronized void load() {
        Path path = file();
        if (Files.isRegularFile(path)) {
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                ModConfig loaded = GSON.fromJson(json, ModConfig.class);
                instance = (loaded != null) ? loaded : new ModConfig();
            } catch (Exception e) {
                LOGGER.error("Failed to read {}, using defaults", path.getFileName(), e);
                instance = new ModConfig();
            }
        } else {
            instance = new ModConfig();
            save();
        }
        // Guard against nulls from partial files.
        if (instance.buttonPosition == null) {
            instance.buttonPosition = ButtonPosition.NEXT_TO_BUTTONS;
        }
        if (instance.defaultPresetOnLaunch == null) {
            instance.defaultPresetOnLaunch = NONE;
        }
    }

    public static synchronized void save() {
        if (instance == null) {
            return;
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    // ---------------------------------------------------------------------
    // Cloth Config screen
    // ---------------------------------------------------------------------

    /**
     * Builds the Cloth Config settings screen for this mod.
     *
     * @param parent the screen to return to when closing
     */
    public static Screen createConfigScreen(Screen parent) {
        ModConfig cfg = get();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("Config Presets"));

        ConfigEntryBuilder entry = builder.entryBuilder();

        // ---- General ----
        ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));
        general.addEntry(entry.startBooleanToggle(Text.literal("Auto-save active preset on exit"), cfg.autoSaveOnExit)
                .setDefaultValue(false)
                .setTooltip(Text.literal("When enabled, the currently active preset is updated\nwith your settings as you leave the game."))
                .setSaveConsumer(v -> cfg.autoSaveOnExit = v)
                .build());
        general.addEntry(entry.startBooleanToggle(Text.literal("Create backup before applying preset"), cfg.backupBeforeApply)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Snapshot your current options before a preset overwrites them."))
                .setSaveConsumer(v -> cfg.backupBeforeApply = v)
                .build());
        general.addEntry(entry.startBooleanToggle(Text.literal("Show toast notifications"), cfg.showToasts)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show a pop-up toast when a preset is applied."))
                .setSaveConsumer(v -> cfg.showToasts = v)
                .build());
        general.addEntry(entry.startBooleanToggle(Text.literal("Warn when a restart may be needed"), cfg.showRestartWarning)
                .setDefaultValue(true)
                .setTooltip(Text.literal("After applying a preset that changes mod configs, warn that\nsome mods may need a game restart to fully apply them.\nNot every mod can reload its config at runtime."))
                .setSaveConsumer(v -> cfg.showRestartWarning = v)
                .build());

        // ---- UI ----
        ConfigCategory ui = builder.getOrCreateCategory(Text.literal("UI"));
        ui.addEntry(entry.startBooleanToggle(Text.literal("Show button on Title Screen"), cfg.showButtonOnTitleScreen)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.showButtonOnTitleScreen = v)
                .build());
        ui.addEntry(entry.startBooleanToggle(Text.literal("Show button on Pause Screen"), cfg.showButtonOnPauseScreen)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.showButtonOnPauseScreen = v)
                .build());
        ui.addEntry(entry.startEnumSelector(Text.literal("Button position"), ButtonPosition.class, cfg.buttonPosition)
                .setDefaultValue(ButtonPosition.NEXT_TO_BUTTONS)
                .setEnumNameProvider(e -> Text.literal(prettyEnum((ButtonPosition) e)))
                .setSaveConsumer(v -> cfg.buttonPosition = v)
                .build());

        // ---- Presets ----
        ConfigCategory presets = builder.getOrCreateCategory(Text.literal("Presets"));
        List<String> presetNames = collectPresetNames();
        String current = presetNames.contains(cfg.defaultPresetOnLaunch) ? cfg.defaultPresetOnLaunch : NONE;
        presets.addEntry(entry.startStringDropdownMenu(Text.literal("Default preset on launch"), current)
                .setDefaultValue(NONE)
                .setSelections(presetNames)
                .setTooltip(Text.literal("This preset is applied automatically when the game launches.\nOnly one preset can be the launch default."))
                .setSaveConsumer(ModConfig::applyDefaultPresetSelection)
                .build());

        builder.setSavingRunnable(ModConfig::save);
        // Add a "Presets" shortcut button into the Cloth screen so the user can hop
        // straight to the preset manager (mirrors the "Config" button on PresetScreen).
        builder.setAfterInitConsumer(ModConfig::addPresetsNavButton);
        return builder.build();
    }

    /**
     * Adds a small "Presets" button to the bottom-right of the Cloth config screen.
     * The screen's {@code addDrawableChild} is protected, so we invoke it
     * reflectively; any failure is swallowed so the config screen still works.
     */
    private static void addPresetsNavButton(Screen screen) {
        try {
            ButtonWidget button = ButtonWidget.builder(Text.literal("Presets"), b -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        mc.setScreen(new PresetScreen(screen));
                    })
                    .dimensions(screen.width - 12 - 90, screen.height - 26, 90, 20)
                    .tooltip(Tooltip.of(Text.literal("Open the Config Presets manager")))
                    .build();
            Method add = findWidgetAdder(screen.getClass());
            if (add != null) {
                add.setAccessible(true);
                add.invoke(screen, button);
            }
        } catch (Throwable ignored) {
            // Non-fatal: the shortcut button just won't appear.
        }
    }

    /** Finds {@code addDrawableChild}/{@code addRenderableWidget} (one arg) up the hierarchy. */
    private static Method findWidgetAdder(Class<?> screenClass) {
        for (String name : new String[]{"addDrawableChild", "addRenderableWidget", "method_37063"}) {
            Class<?> c = screenClass;
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 1) {
                        return m;
                    }
                }
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static String prettyEnum(ButtonPosition p) {
        return switch (p) {
            case NEXT_TO_BUTTONS -> "Next to buttons";
            case TOP_LEFT -> "Top left";
            case TOP_RIGHT -> "Top right";
            case BOTTOM_LEFT -> "Bottom left";
            case BOTTOM_RIGHT -> "Bottom right";
        };
    }

    /** Returns "None" followed by every saved preset name. */
    private static List<String> collectPresetNames() {
        List<String> names = new ArrayList<>();
        names.add(NONE);
        try {
            if (ConfigPresetsMod.getPresetManager() != null) {
                for (Preset p : ConfigPresetsMod.getPresetManager().getAll()) {
                    if (p.name != null && !p.name.isBlank()) {
                        names.add(p.name);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not list presets for config dropdown", t);
        }
        return names;
    }

    /**
     * Records the chosen launch default and keeps the {@link Preset#isDefault}
     * flags in sync (only one preset may be the default at a time).
     */
    private static void applyDefaultPresetSelection(String name) {
        get().defaultPresetOnLaunch = (name == null) ? NONE : name;
        try {
            if (ConfigPresetsMod.getPresetManager() != null) {
                if (NONE.equals(name) || name == null) {
                    ConfigPresetsMod.getPresetManager().clearDefault();
                } else {
                    Preset target = ConfigPresetsMod.getPresetManager().load(name);
                    if (target != null) {
                        ConfigPresetsMod.getPresetManager().setDefault(target);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not sync default preset flag", t);
        }
    }
}
