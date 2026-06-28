package com.configpresets;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Consolidated handler that captures and restores Minecraft client settings,
 * resource pack load order, and mod configuration files.
 *
 * <p>Options are captured by parsing {@code options.txt} directly (the file is a
 * simple {@code key:value} list). This keeps the implementation resilient across
 * Minecraft versions and avoids reaching into many private mapping fields. After a
 * restore, {@link Minecraft#options} is reloaded so the in-memory state
 * matches the file, and resource packs are reloaded when changed.</p>
 */
public class CaptureHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("configpresets/CaptureHandler");

    /** Single compact Gson for single-line values like the resourcePacks list in options.txt. */
    private static final Gson COMPACT_GSON = new Gson();

    // Section keys used inside Preset#capturedSettings.
    public static final String SECTION_VIDEO = "video";
    public static final String SECTION_SOUND = "sound";
    public static final String SECTION_CONTROLS = "controls";
    public static final String SECTION_LANGUAGE = "language";
    public static final String SECTION_ACCESSIBILITY = "accessibility";
    public static final String SECTION_CHAT = "chat";
    public static final String SECTION_SKIN = "skin";
    public static final String SECTION_RESOURCE_PACKS = "resourcePacks";
    public static final String SECTION_MOD_CONFIGS = "modConfigs";
    /** Video-related mod configs (Sodium family, culling mods, Voxy, Nvidium). */
    public static final String SECTION_VIDEO_MOD_CONFIGS = "videoModConfigs";

    /**
     * Lower-case markers identifying config files that belong to video/performance
     * mods. When a preset's "Video Settings" toggle is enabled, any config file
     * under {@code config/} whose relative path contains one of these markers is
     * captured and restored together with the vanilla video options. Covers Sodium,
     * Sodium Extra, Reese's Sodium Options, Cull Leaves, More Culling, Voxy and
     * Nvidium.
     */
    private static final List<String> VIDEO_MOD_CONFIG_MARKERS = Arrays.asList(
            "sodium", "cullleaves", "cull-leaves", "cull_leaves",
            "moreculling", "more-culling", "voxy", "nvidium");

    /** options.txt keys grouped by category. */
    private static final List<String> VIDEO_KEYS = Arrays.asList(
            "renderDistance", "graphicsMode", "ao", "fov", "guiScale", "gamma",
            "maxFps", "particles", "fullscreen", "enableVsync", "mipmapLevels",
            "biomeBlendRadius", "entityShadows", "entityDistanceScaling",
            "prioritizeChunkUpdates", "renderClouds", "simulationDistance");

    private static final List<String> SOUND_PREFIXES = Arrays.asList("soundCategory_");

    private static final List<String> LANGUAGE_KEYS = Arrays.asList("lang");

    private static final List<String> ACCESSIBILITY_KEYS = Arrays.asList(
            "narrator", "textBackground", "textBackgroundOpacity", "highContrast",
            "autoJump", "darkMojangStudiosBackground", "panoramaScrollSpeed",
            "hideLightningFlashes", "damageTiltStrength", "glintSpeed", "glintStrength");

    private static final List<String> CHAT_KEYS = Arrays.asList(
            "chatVisibility", "chatColors", "chatLinks", "chatLinksPrompt",
            "chatScale", "chatOpacity", "chatWidth", "chatHeightFocused",
            "chatHeightUnfocused", "chatDelay", "backgroundForChatOnly",
            "autoSuggestions", "reducedDebugInfo");

    private static final List<String> SKIN_KEYS = Arrays.asList(
            "modelPart_cape", "modelPart_jacket", "modelPart_left_sleeve",
            "modelPart_right_sleeve", "modelPart_left_pants_leg",
            "modelPart_right_pants_leg", "modelPart_hat", "mainHand");

    /** options.txt key holding the JSON list of enabled resource packs. */
    private static final String RESOURCE_PACKS_KEY = "resourcePacks";
    private static final String INCOMPATIBLE_RESOURCE_PACKS_KEY = "incompatibleResourcePacks";

    private static final Set<String> CONFIG_EXTENSIONS = Set.of(
            ".json", ".toml", ".json5", ".properties", ".cfg", ".conf", ".yaml", ".yml");

    private final Path gameDir;
    private final Path optionsFile;
    private final Path configDir;

    public CaptureHandler(Path gameDir) {
        this.gameDir = gameDir;
        this.optionsFile = gameDir.resolve("options.txt");
        this.configDir = gameDir.resolve("config");
    }

    // =====================================================================
    // options.txt parsing helpers
    // =====================================================================

    /** Reads options.txt into an ordered key->value map. */
    public Map<String, String> readOptions() {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(optionsFile)) {
            return map;
        }
        try (BufferedReader reader = Files.newBufferedReader(optionsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                map.put(line.substring(0, idx), line.substring(idx + 1));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read options.txt", e);
        }
        return map;
    }

    /** Writes the given key->value map back to options.txt, preserving order. */
    public void writeOptions(Map<String, String> options) {
        try (BufferedWriter writer = Files.newBufferedWriter(optionsFile, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : options.entrySet()) {
                writer.write(e.getKey());
                writer.write(':');
                writer.write(e.getValue());
                writer.write('\n');
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write options.txt", e);
        }
    }

    private static Map<String, String> extractKeys(Map<String, String> all, List<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keys) {
            if (all.containsKey(k)) {
                out.put(k, all.get(k));
            }
        }
        return out;
    }

    private static Map<String, String> extractByPrefix(Map<String, String> all, List<String> prefixes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            for (String prefix : prefixes) {
                if (e.getKey().startsWith(prefix)) {
                    out.put(e.getKey(), e.getValue());
                    break;
                }
            }
        }
        return out;
    }

    private static Map<String, String> extractKeyBindings(Map<String, String> all) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (e.getKey().startsWith("key_")) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    // =====================================================================
    // CAPTURE
    // =====================================================================

    /**
     * Captures the enabled categories from the current game state into the preset,
     * according to the preset's {@code save*} toggles.
     */
    public void capture(Preset preset) {
        Map<String, String> options = readOptions();

        if (preset.saveVideo) {
            preset.putSection(SECTION_VIDEO, toObjectMap(extractKeys(options, VIDEO_KEYS)));
            // Video/performance mod configs (Sodium, culling mods, Voxy, Nvidium, ...)
            // are captured together with the vanilla video options.
            preset.putSection(SECTION_VIDEO_MOD_CONFIGS, captureVideoModConfigs());
        }
        if (preset.saveSound) {
            preset.putSection(SECTION_SOUND, toObjectMap(extractByPrefix(options, SOUND_PREFIXES)));
        }
        if (preset.saveControls) {
            preset.putSection(SECTION_CONTROLS, toObjectMap(extractKeyBindings(options)));
        }
        if (preset.saveLanguage) {
            preset.putSection(SECTION_LANGUAGE, toObjectMap(extractKeys(options, LANGUAGE_KEYS)));
        }
        if (preset.saveAccessibility) {
            preset.putSection(SECTION_ACCESSIBILITY, toObjectMap(extractKeys(options, ACCESSIBILITY_KEYS)));
        }
        if (preset.saveChat) {
            preset.putSection(SECTION_CHAT, toObjectMap(extractKeys(options, CHAT_KEYS)));
        }
        // Skin / model parts always captured alongside the player appearance.
        preset.putSection(SECTION_SKIN, toObjectMap(extractKeys(options, SKIN_KEYS)));

        if (preset.saveResourcePacks) {
            preset.putSection(SECTION_RESOURCE_PACKS, captureResourcePacks(options));
        }
        if (preset.saveModConfigs) {
            preset.putSection(SECTION_MOD_CONFIGS, captureModConfigs(preset));
        }
        preset.touch();
    }

    private static Map<String, Object> toObjectMap(Map<String, String> src) {
        return new LinkedHashMap<>(src);
    }

    /** Captures the ordered enabled resource pack list. */
    private Map<String, Object> captureResourcePacks(Map<String, String> options) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("enabled", parseJsonStringList(options.get(RESOURCE_PACKS_KEY)));
        section.put("incompatible", parseJsonStringList(options.get(INCOMPATIBLE_RESOURCE_PACKS_KEY)));
        return section;
    }

    /**
     * Captures raw contents of mod config files. Respects per-mod toggles: a mod is
     * included unless explicitly disabled in {@link Preset#perModConfigToggles}.
     */
    private Map<String, Object> captureModConfigs(Preset preset) {
        Map<String, Object> files = new LinkedHashMap<>();
        for (ModConfigFile cfg : getAvailableModConfigs()) {
            Boolean toggle = preset.perModConfigToggles.get(cfg.modId);
            boolean included = (toggle == null) || toggle;
            if (!included) {
                continue;
            }
            try {
                String content = Files.readString(cfg.path, StandardCharsets.UTF_8);
                files.put(cfg.relativePath, content);
            } catch (IOException e) {
                LOGGER.error("Could not read mod config {}", cfg.relativePath, e);
            }
        }
        return files;
    }

    /**
     * Captures the raw contents of every video/performance mod config file found
     * under {@code config/} (Sodium, Sodium Extra, Reese's Sodium Options, Cull
     * Leaves, More Culling, Voxy, Nvidium). These are stored alongside the vanilla
     * video options and restored when "Video Settings" is enabled.
     */
    private Map<String, Object> captureVideoModConfigs() {
        Map<String, Object> files = new LinkedHashMap<>();
        if (!Files.isDirectory(configDir)) {
            return files;
        }
        try (Stream<Path> stream = Files.walk(configDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(CaptureHandler::hasConfigExtension)
                    .sorted()
                    .forEach(p -> {
                        String relative = configDir.relativize(p).toString().replace('\\', '/');
                        if (!isVideoModConfig(relative)) {
                            return;
                        }
                        try {
                            files.put(relative, Files.readString(p, StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            LOGGER.error("Could not read video mod config {}", relative, e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Failed to scan config directory for video mod configs", e);
        }
        return files;
    }

    /** True if the relative config path belongs to a known video/performance mod. */
    private static boolean isVideoModConfig(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        for (String marker : VIDEO_MOD_CONFIG_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // RESTORE
    // =====================================================================

    /**
     * Aggregated outcome of {@link #restore(Preset)}, used by callers to decide
     * whether to reload resource packs and what notification to show.
     */
    public static class RestoreResult {
        /** True if the enabled resource pack list changed (reload recommended). */
        public boolean resourcePacksChanged = false;
        /**
         * Number of NON-video mod config files whose on-disk contents actually
         * changed. Video/graphics mod configs (Sodium, Cull Leaves, ...) are applied
         * immediately and intentionally excluded here, so they never trigger the
         * restart prompt.
         */
        public int modConfigsChanged = 0;
        /** Number of video/graphics mod config files whose contents changed. */
        public int videoModConfigsChanged = 0;

        /**
         * True if any NON-video mod config file contents changed and a game restart
         * is recommended for those mods to pick up the new values.
         */
        public boolean modConfigsRestored() {
            return modConfigsChanged > 0;
        }
    }

    /**
     * Restores the captured sections back into the game.
     *
     * <p>Writes {@code options.txt} and restores any captured mod config files to
     * disk. Minecraft options, video settings (including graphics mod configs such as
     * Sodium and Cull Leaves) and resource packs are all applied immediately. The
     * returned {@link RestoreResult} reports whether a resource pack reload is
     * recommended and whether any <em>non-video</em> mod config files changed — only
     * those warrant the restart prompt.</p>
     */
    public RestoreResult restore(Preset preset) {
        return restore(preset, null);
    }

    /**
     * Restores a preset with optional fallback to the default preset for any
     * category the user has disabled. When a non-default preset has a save toggle
     * unchecked, the corresponding section from {@code fallback} (the Default
     * preset) is used instead, so no settings are ever left un-applied.
     *
     * @param preset   the preset to apply
     * @param fallback the Default preset used for unchecked categories, may be null
     */
    public RestoreResult restore(Preset preset, Preset fallback) {
        RestoreResult result = new RestoreResult();
        Map<String, String> options = readOptions();
        int modConfigsChanged = 0;

        // For each category, use the preset's section if enabled, otherwise
        // fall back to the default preset's section.
        if (preset.saveVideo) {
            applySection(options, preset.getSection(SECTION_VIDEO));
            result.videoModConfigsChanged += restoreModConfigs(preset.getSection(SECTION_VIDEO_MOD_CONFIGS));
        } else if (fallback != null && fallback.saveVideo) {
            applySection(options, fallback.getSection(SECTION_VIDEO));
            result.videoModConfigsChanged += restoreModConfigs(fallback.getSection(SECTION_VIDEO_MOD_CONFIGS));
        }

        if (preset.saveSound) {
            applySection(options, preset.getSection(SECTION_SOUND));
        } else if (fallback != null && fallback.saveSound) {
            applySection(options, fallback.getSection(SECTION_SOUND));
        }

        if (preset.saveControls) {
            applySection(options, preset.getSection(SECTION_CONTROLS));
        } else if (fallback != null && fallback.saveControls) {
            applySection(options, fallback.getSection(SECTION_CONTROLS));
        }

        if (preset.saveLanguage) {
            applySection(options, preset.getSection(SECTION_LANGUAGE));
        } else if (fallback != null && fallback.saveLanguage) {
            applySection(options, fallback.getSection(SECTION_LANGUAGE));
        }

        if (preset.saveAccessibility) {
            applySection(options, preset.getSection(SECTION_ACCESSIBILITY));
        } else if (fallback != null && fallback.saveAccessibility) {
            applySection(options, fallback.getSection(SECTION_ACCESSIBILITY));
        }

        if (preset.saveChat) {
            applySection(options, preset.getSection(SECTION_CHAT));
        } else if (fallback != null && fallback.saveChat) {
            applySection(options, fallback.getSection(SECTION_CHAT));
        }

        // Skin is always captured alongside the preset.
        applySection(options, preset.getSection(SECTION_SKIN));

        if (preset.saveResourcePacks) {
            result.resourcePacksChanged = restoreResourcePacks(options, preset.getSection(SECTION_RESOURCE_PACKS));
        } else if (fallback != null && fallback.saveResourcePacks) {
            result.resourcePacksChanged = restoreResourcePacks(options, fallback.getSection(SECTION_RESOURCE_PACKS));
        }

        writeOptions(options);

        if (preset.saveModConfigs) {
            modConfigsChanged += restoreModConfigs(preset.getSection(SECTION_MOD_CONFIGS));
        } else if (fallback != null && fallback.saveModConfigs) {
            modConfigsChanged += restoreModConfigs(fallback.getSection(SECTION_MOD_CONFIGS));
        }
        result.modConfigsChanged = modConfigsChanged;

        reloadOptionsInMemory();
        return result;
    }

    private void applySection(Map<String, String> options, Map<String, Object> section) {
        if (section == null) {
            return;
        }
        for (Map.Entry<String, Object> e : section.entrySet()) {
            if (e.getValue() != null) {
                options.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
    }

    /** Restores resource pack list/order. Returns true if the list changed. */
    @SuppressWarnings("unchecked")
    private boolean restoreResourcePacks(Map<String, String> options, Map<String, Object> section) {
        if (section == null) {
            return false;
        }
        boolean changed = false;
        Object enabled = section.get("enabled");
        if (enabled instanceof List) {
            String newValue = toJsonStringList((List<String>) enabled);
            if (!newValue.equals(options.get(RESOURCE_PACKS_KEY))) {
                options.put(RESOURCE_PACKS_KEY, newValue);
                changed = true;
            }
        }
        Object incompatible = section.get("incompatible");
        if (incompatible instanceof List) {
            options.put(INCOMPATIBLE_RESOURCE_PACKS_KEY, toJsonStringList((List<String>) incompatible));
        }
        return changed;
    }

    /**
     * Writes raw mod config contents back to disk, only touching files whose
     * contents differ from what is already there.
     *
     * @return the number of config files whose on-disk contents actually changed
     */
    private int restoreModConfigs(Map<String, Object> section) {
        if (section == null) {
            return 0;
        }
        int changed = 0;
        for (Map.Entry<String, Object> e : section.entrySet()) {
            String relativePath = e.getKey();
            Object content = e.getValue();
            if (!(content instanceof String)) {
                continue;
            }
            Path target = configDir.resolve(relativePath).normalize();
            // Guard against path traversal outside config dir.
            if (!target.startsWith(configDir)) {
                continue;
            }
            try {
                String newContent = (String) content;
                String existing = Files.isRegularFile(target)
                        ? Files.readString(target, StandardCharsets.UTF_8)
                        : null;
                if (newContent.equals(existing)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, newContent, StandardCharsets.UTF_8);
                changed++;
            } catch (IOException ex) {
                LOGGER.error("Failed to restore mod config {}", relativePath, ex);
            }
        }
        return changed;
    }

    /**
     * Reloads the in-memory Minecraft options from disk and re-applies the settings
     * that need an explicit refresh to become visible (GUI scale, render distance,
     * graphics mode, etc.). Should be called on the render thread.
     */
    private void reloadOptionsInMemory() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.options != null) {
                // Reparse options.txt into the live Options object so every
                // OptionInstance reflects the restored values.
                client.options.load();
                // Re-apply GUI scale and other resolution-derived layout values.
                client.resizeDisplay();
                // Rebuild the world render so render distance / graphics changes show
                // up immediately when a level is loaded.
                if (client.level != null && client.levelRenderer != null) {
                    client.levelRenderer.allChanged();
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Could not reload in-memory options", t);
        }
    }

    /**
     * Triggers a resource pack reload on the Minecraft client. Should be called on
     * the render thread.
     *
     * <p>The restored {@code resourcePacks} list lives in {@code options.txt}; after
     * reloading the options we push that selection <em>into</em> the pack repository
     * via {@link net.minecraft.client.Options#loadSelectedResourcePacks(PackRepository)}
     * (which calls {@code PackRepository.setSelected(...)}) and then reload resources
     * so the new packs actually take effect.</p>
     */
    public static void triggerResourceReload() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                // Read the restored resourcePacks list back into the Options object.
                client.options.load();
                PackRepository repository = client.getResourcePackRepository();
                // Re-scan available packs (handles newly added/removed packs).
                repository.reload();
                // Apply the options' selected packs to the repository.
                client.options.loadSelectedResourcePacks(repository);
                // Reload resources so the new pack set is applied.
                client.reloadResourcePacks();
            }
        } catch (Throwable t) {
            LOGGER.error("Could not trigger resource pack reload", t);
        }
    }

    // =====================================================================
    // Mod config discovery
    // =====================================================================

    /** A discovered mod configuration file. */
    public static class ModConfigFile {
        public final String modId;
        public final String relativePath;
        public final Path path;

        public ModConfigFile(String modId, String relativePath, Path path) {
            this.modId = modId;
            this.relativePath = relativePath;
            this.path = path;
        }
    }

    /**
     * Scans {@code .minecraft/config/} (recursively) for known config file types and
     * returns them grouped with an inferred mod id (derived from the file/dir name).
     */
    public List<ModConfigFile> getAvailableModConfigs() {
        List<ModConfigFile> result = new ArrayList<>();
        if (!Files.isDirectory(configDir)) {
            return result;
        }
        try (Stream<Path> stream = Files.walk(configDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(CaptureHandler::hasConfigExtension)
                    .sorted()
                    .forEach(p -> {
                        String relative = configDir.relativize(p).toString().replace('\\', '/');
                        // Video/performance mod configs are handled as part of the
                        // "Video Settings" category, not as standalone mod configs.
                        if (isVideoModConfig(relative)) {
                            return;
                        }
                        result.add(new ModConfigFile(inferModId(relative), relative, p));
                    });
        } catch (IOException e) {
            LOGGER.error("Failed to scan config directory", e);
        }
        return result;
    }

    /** Returns distinct mod ids detected in the config directory, sorted. */
    public List<String> getAvailableModIds() {
        return getAvailableModConfigs().stream()
                .map(c -> c.modId)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private static boolean hasConfigExtension(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : CONFIG_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Infers a mod id from a config file's relative path. If the file lives in a
     * subdirectory, the top-level directory name is used; otherwise the file name
     * (without extension and common suffixes) is used.
     */
    /** Pre-compiled pattern for stripping common config-file suffixes. */
    private static final Pattern MOD_SUFFIX_PATTERN =
            Pattern.compile("[-_.](client|common|server|config|settings)$");

    private static String inferModId(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        int slash = normalized.indexOf('/');
        if (slash > 0) {
            return normalized.substring(0, slash);
        }
        String fileName = normalized;
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            fileName = fileName.substring(0, dot);
        }
        // Strip common config suffixes like "-client", "-common", ".client".
        fileName = MOD_SUFFIX_PATTERN.matcher(fileName).replaceAll("");
        return fileName.isBlank() ? normalized : fileName;
    }

    // =====================================================================
    // JSON string-list helpers (resource pack lists are JSON arrays in options.txt)
    // =====================================================================

    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    private static List<String> parseJsonStringList(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = COMPACT_GSON.fromJson(raw, STRING_LIST_TYPE);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String toJsonStringList(List<String> list) {
        return COMPACT_GSON.toJson(list == null ? new ArrayList<String>() : list, STRING_LIST_TYPE);
    }
}
