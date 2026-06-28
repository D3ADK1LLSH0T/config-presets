package com.configpresets;

import com.configpresets.config.ModConfig;
import com.configpresets.screen.IconButton;
import com.configpresets.screen.PresetScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Main mod entry point for Config Presets (NeoForge).
 *
 * <p>Sets up the singleton {@link PresetManager} and {@link CaptureHandler}, applies
 * the default preset (if any) on launch, registers the keybind (default {@code P})
 * used to open the management screen, and injects the custom icon button onto the
 * title / pause screens via NeoForge's {@link ScreenEvent} (no mixins required).</p>
 */
@Mod(value = ConfigPresetsMod.MOD_ID, dist = Dist.CLIENT)
public class ConfigPresetsMod {

    public static final String MOD_ID = "configpresets";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Keybind category (1.21.11 uses an Identifier-based category). */
    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

    private static PresetManager presetManager;
    private static CaptureHandler captureHandler;

    /** Keybind that opens the Config Presets screen (default: P). */
    public static KeyMapping openScreenKey;

    /** Set once the default preset has been applied on launch. */
    private static boolean defaultApplied = false;

    // --- Real-time auto-save: detect options.txt changes every second ---
    private static final int AUTO_SAVE_CHECK_INTERVAL = 20; // ticks (1 second)
    private static int autoSaveTickCounter = 0;
    private static long lastOptionsModified = 0;
    private static Path optionsFilePath;

    public ConfigPresetsMod(IEventBus modBus, ModContainer container) {
        Path gameDir = FMLPaths.GAMEDIR.get();

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

        // Mod-bus listeners (registration phase).
        modBus.addListener(this::onRegisterKeyMappings);

        // Register the NeoForge settings screen (replaces the Cloth/Mod Menu screen).
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (mc, parent) -> ModConfig.createConfigScreen(parent));

        // Game-bus listeners (runtime phase).
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onScreenInit);
        NeoForge.EVENT_BUS.addListener(this::onClientStopping);
    }

    // ---------------------------------------------------------------------
    // Keybind registration
    // ---------------------------------------------------------------------

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        openScreenKey = new KeyMapping(
                "key.configpresets.open_screen",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                KEY_CATEGORY);
        event.register(openScreenKey);
    }

    // ---------------------------------------------------------------------
    // Tick handling: default preset on launch + keybind polling
    // ---------------------------------------------------------------------

    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();

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

        // Real-time auto-save: periodically check if options.txt was
        // modified and, if so, capture the current state into every
        // preset that has auto-save enabled.
        if (defaultApplied && ++autoSaveTickCounter >= AUTO_SAVE_CHECK_INTERVAL) {
            autoSaveTickCounter = 0;
            checkAndAutoSave();
        }
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
    // Button injection on title / pause screens (NeoForge event, no mixins)
    // ---------------------------------------------------------------------

    private void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof TitleScreen) {
            if (ModConfig.get().showButtonOnTitleScreen) {
                addPresetButton(event, screen, false);
            }
        } else if (screen instanceof PauseScreen) {
            if (ModConfig.get().showButtonOnPauseScreen) {
                addPresetButton(event, screen, true);
            }
        }
    }

    private void addPresetButton(ScreenEvent.Init.Post event, Screen screen, boolean pauseScreen) {
        int[] pos = PresetScreen.computeButtonPosition(screen.width, screen.height, pauseScreen);
        IconButton button = new IconButton(
                pos[0], pos[1], 20, 20,
                Component.literal("Config Presets"),
                b -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) {
                        mc.setScreen(new PresetScreen(screen));
                    }
                });
        button.setTooltip(Tooltip.create(Component.literal("Config Presets")));
        event.addListener(button);
    }

    // ---------------------------------------------------------------------
    // Auto-save on exit
    // ---------------------------------------------------------------------

    private void onClientStopping(ClientStoppingEvent event) {
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
