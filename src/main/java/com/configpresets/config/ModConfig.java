package com.configpresets.config;

import com.configpresets.ConfigPresetsMod;
import com.configpresets.Preset;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent mod settings, backed by {@code config/configpresets.json}.
 *
 * <p>This is intentionally a small POJO with public fields so Gson can read/write
 * it directly. A single shared instance is exposed via {@link #get()}. The settings
 * screen is built by {@link ConfigScreen} (registered through NeoForge's
 * {@code IConfigScreenFactory}) instead of Cloth Config.</p>
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
            configFile = FMLPaths.CONFIGDIR.get().resolve("configpresets.json");
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
    // Settings screen
    // ---------------------------------------------------------------------

    /**
     * Builds the settings screen for this mod, returned to NeoForge's config screen
     * factory and the in-game shortcuts.
     *
     * @param parent the screen to return to when closing
     */
    public static Screen createConfigScreen(Screen parent) {
        return new ConfigScreen(parent);
    }

    static String prettyEnum(ButtonPosition p) {
        return switch (p) {
            case NEXT_TO_BUTTONS -> "Next to buttons";
            case TOP_LEFT -> "Top left";
            case TOP_RIGHT -> "Top right";
            case BOTTOM_LEFT -> "Bottom left";
            case BOTTOM_RIGHT -> "Bottom right";
        };
    }

    /** Returns "None" followed by every saved preset name. */
    static List<String> collectPresetNames() {
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
    static void applyDefaultPresetSelection(String name) {
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
