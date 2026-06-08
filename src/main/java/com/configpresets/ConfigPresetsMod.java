package com.configpresets;

import com.configpresets.config.ModConfig;
import com.configpresets.screen.PresetScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** Keybind category (26.X uses an Identifier-based category). */
    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

    private static PresetManager presetManager;
    private static CaptureHandler captureHandler;

    /** Keybind that opens the Config Presets screen (default: P). */
    public static KeyMapping openScreenKey;

    /** Set once the default preset has been applied on launch. */
    private static boolean defaultApplied = false;

    @Override
    public void onInitializeClient() {
        Path gameDir = FabricLoader.getInstance().getGameDir();

        presetManager = new PresetManager(gameDir);
        captureHandler = new CaptureHandler(gameDir);

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
        openScreenKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.configpresets.open_screen",
                InputConstants.Type.KEYSYM,
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
                while (openScreenKey.consumeClick()) {
                    onOpenScreenPressed(client);
                }
            }
        });
    }

    /** Applies the default preset on launch, if one is configured. */
    private void applyDefaultPreset() {
        try {
            Preset def = presetManager.getDefault();
            if (def != null) {
                LOGGER.info("Applying default preset on launch: {}", def.name);
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
    private void onOpenScreenPressed(Minecraft client) {
        if (client == null) {
            return;
        }
        try {
            client.setScreen(new PresetScreen(client.screen));
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
