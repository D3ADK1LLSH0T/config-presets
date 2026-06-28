package com.configpresets;

import com.configpresets.config.ModConfig;
import com.configpresets.screen.PresetScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Main mod initializer for Config Presets.
 *
 * <p>Sets up the singleton {@link PresetManager} and {@link CaptureHandler}, applies
 * the default preset (if any) on launch, and registers the keybind (default
 * {@code P}) used to open the management screen.</p>
 */
public class ConfigPresetsMod implements ClientModInitializer {

    public static final String MOD_ID = "configpresets";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Keybind category (1.21.11 uses an Identifier-based category). */
    private static final KeyBinding.Category KEY_CATEGORY =
            KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

    private static PresetManager presetManager;
    private static CaptureHandler captureHandler;

    /** Keybind that opens the Config Presets screen (default: P). */
    public static KeyBinding openScreenKey;

    /** Set once the default preset has been applied on launch. */
    private static boolean defaultApplied = false;

    // --- Real-time auto-save: detect options.txt changes every second ---
    private static final int AUTO_SAVE_CHECK_INTERVAL = 20; // ticks (1 second)
    private static int autoSaveTickCounter = 0;
    private static long lastOptionsModified = 0;
    private static Path optionsFilePath;

    @Override
    public void onInitializeClient() {
        Path gameDir = FabricLoader.getInstance().getGameDir();

        presetManager = new PresetManager(gameDir);
        captureHandler = new CaptureHandler(gameDir);

        // Create / validate the built-in "Default" preset before anything else.
        presetManager.ensureDefaultPreset();

        // Track options.txt for real-time auto-save.
        optionsFilePath = gameDir.resolve("options.txt");
        lastOptionsModified = fileModifiedTime(optionsFilePath);

        LOGGER.info("Config Presets initialized. Presets directory: {}", presetManager.getPresetsDir());

        // Ensure config is loaded early.
        ModConfig.get();

        registerKeybinds();
        registerTickHandler();
        registerLifecycleHandlers();
    }

    /** Registers the auto-save-on-exit hook. */
    private void registerLifecycleHandlers() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> autoSaveOnExit());
    }

    /**
     * On exit, if enabled, updates the configured default / auto-save preset(s)
     * with the current settings so they persist between sessions.
     */
    private void autoSaveOnExit() {
        try {
            boolean globalAutoSave = ModConfig.get().autoSaveOnExit;
            for (Preset p : presetManager.getAll()) {
                if (globalAutoSave ? p.isDefault : p.autoSaveOnExit) {
                    LOGGER.info("Auto-saving preset on exit: {}", p.name);
                    captureHandler.capture(p);
                    presetManager.save(p);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Auto-save on exit failed", t);
        }
    }

    private void registerKeybinds() {
        openScreenKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.configpresets.open_screen",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                KEY_CATEGORY
        ));
    }

    private void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Apply the default preset once, after the client has finished loading.
            if (!defaultApplied && client != null) {
                defaultApplied = true;
                applyDefaultPreset();
            }

            if (openScreenKey != null) {
                while (openScreenKey.wasPressed()) {
                    onOpenScreenPressed(client);
                }
            }

            // Real-time auto-save: periodically check if options.txt was
            // modified and, if so, capture the current state into every
            // preset that has auto-save enabled.
            if (defaultApplied && ++autoSaveTickCounter >= AUTO_SAVE_CHECK_INTERVAL) {
                autoSaveTickCounter = 0;
                checkAndAutoSave();
            }
        });
    }

    /**
     * Checks whether {@code options.txt} has been modified since the last
     * snapshot and, if so, auto-saves every eligible preset.
     */
    private void checkAndAutoSave() {
        try {
            long mod = fileModifiedTime(optionsFilePath);
            if (mod != lastOptionsModified) {
                lastOptionsModified = mod;
                boolean globalAutoSave = ModConfig.get().autoSaveOnExit;
                for (Preset p : presetManager.getAll()) {
                    if (globalAutoSave ? p.isDefault : p.autoSaveOnExit) {
                        captureHandler.capture(p);
                        presetManager.save(p);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Real-time auto-save check failed", t);
        }
    }

    /** Returns the last-modified time of a file, or 0 if unreadable. */
    private static long fileModifiedTime(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Applies the default preset on launch. The default preset always auto-saves
     * the current settings into itself before restoring, so it stays current.
     */
    private void applyDefaultPreset() {
        try {
            Preset def = presetManager.getDefault();
            if (def != null) {
                LOGGER.info("Auto-saving and applying default preset on launch: {}", def.name);
                // Auto-save: capture current settings into the default preset.
                captureHandler.capture(def);
                presetManager.save(def);
                // Restore (the default preset itself — all toggles on, no fallback needed).
                CaptureHandler.RestoreResult result = captureHandler.restore(def);
                if (result.resourcePacksChanged) {
                    CaptureHandler.triggerResourceReload();
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to apply default preset on launch", t);
        }
    }

    /** Invoked when the keybind is pressed: opens the main preset screen. */
    private void onOpenScreenPressed(MinecraftClient client) {
        if (client == null) {
            return;
        }
        try {
            client.setScreen(new PresetScreen(client.currentScreen));
        } catch (Throwable t) {
            LOGGER.error("Failed to open Config Presets screen", t);
        }
    }

    // ---------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------

    public static PresetManager getPresetManager() {
        return presetManager;
    }

    public static CaptureHandler getCaptureHandler() {
        return captureHandler;
    }
}
