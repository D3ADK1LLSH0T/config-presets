package com.configpresets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data model representing a single configuration preset.
 *
 * <p>A preset captures a selectable subset of Minecraft client settings, mod
 * configuration files, and the enabled resource pack list. It is serialized to a
 * single {@code .json} file managed by {@link PresetManager}.</p>
 */
public class Preset {

    /** Shared Gson instance with pretty printing for human-readable preset files. */
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    // ---------------------------------------------------------------------
    // Identity / metadata
    // ---------------------------------------------------------------------

    /** Unique, user-facing name. Also used to derive the file name. */
    public String name = "";

    /** Free-form description shown in the GUI. */
    public String description = "";

    /** Single-level folder used for organization. {@code null} means "root". */
    public String folder = null;

    /** Color-coded tags for filtering/searching. */
    public List<Tag> tags = new ArrayList<>();

    // ---------------------------------------------------------------------
    // Capture toggles - which categories this preset saves/restores
    // ---------------------------------------------------------------------

    public boolean saveVideo = true;
    public boolean saveSound = true;
    public boolean saveControls = true;
    public boolean saveLanguage = true;
    public boolean saveAccessibility = true;
    public boolean saveChat = true;
    public boolean saveResourcePacks = true;
    public boolean saveModConfigs = false;

    /** Per-mod toggles: mod id -> whether that mod's config is included. */
    public Map<String, Boolean> perModConfigToggles = new LinkedHashMap<>();

    // ---------------------------------------------------------------------
    // Captured data
    // ---------------------------------------------------------------------

    /**
     * The actual captured settings, structured as named sections. Typical keys:
     * {@code "video"}, {@code "sound"}, {@code "controls"}, {@code "language"},
     * {@code "accessibility"}, {@code "chat"}, {@code "skin"},
     * {@code "resourcePacks"}, {@code "modConfigs"}.
     */
    public Map<String, Object> capturedSettings = new LinkedHashMap<>();

    // ---------------------------------------------------------------------
    // Behavior flags
    // ---------------------------------------------------------------------

    /** Whether this preset is applied automatically on game launch. */
    public boolean isDefault = false;

    /** Whether the current settings are written into this preset on game exit. */
    public boolean autoSaveOnExit = false;

    /**
     * Enforces the constraints that apply to the permanent default preset:
     * all category toggles are forced on and auto-save is always enabled.
     * Called by the manager whenever the default preset is loaded or saved.
     */
    public void enforceDefaultConstraints() {
        this.saveVideo = true;
        this.saveSound = true;
        this.saveControls = true;
        this.saveLanguage = true;
        this.saveAccessibility = true;
        this.saveChat = true;
        this.saveResourcePacks = true;
        this.saveModConfigs = true;
        this.autoSaveOnExit = true;
        this.isDefault = true;
    }

    // ---------------------------------------------------------------------
    // Timestamps (epoch milliseconds)
    // ---------------------------------------------------------------------

    public long createdAt = System.currentTimeMillis();
    public long lastModified = System.currentTimeMillis();

    public Preset() {
    }

    public Preset(String name) {
        this.name = name;
    }

    /**
     * A color-coded tag attached to a preset.
     */
    public static class Tag {
        /** Tag label. */
        public String name = "";
        /** Hex color string, e.g. {@code "#55FF55"}. */
        public String hexColor = "#FFFFFF";

        public Tag() {
        }

        public Tag(String name, String hexColor) {
            this.name = name;
            this.hexColor = hexColor;
        }
    }

    // ---------------------------------------------------------------------
    // Convenience helpers
    // ---------------------------------------------------------------------

    /** Marks the preset as modified now. */
    public void touch() {
        this.lastModified = System.currentTimeMillis();
    }

    /** Returns whether the preset has a tag with the given (case-insensitive) name. */
    public boolean hasTag(String tagName) {
        if (tagName == null) {
            return false;
        }
        for (Tag tag : tags) {
            if (tag.name != null && tag.name.equalsIgnoreCase(tagName)) {
                return true;
            }
        }
        return false;
    }

    /** Adds a tag if one with the same name does not already exist. */
    public void addTag(String tagName, String hexColor) {
        if (!hasTag(tagName)) {
            tags.add(new Tag(tagName, hexColor));
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getSection(String key) {
        Object value = capturedSettings.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    public void putSection(String key, Map<String, Object> section) {
        capturedSettings.put(key, section);
    }

    // ---------------------------------------------------------------------
    // Serialization
    // ---------------------------------------------------------------------

    /** Serializes this preset to pretty-printed JSON. */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Deserializes a preset from JSON.
     *
     * @throws JsonSyntaxException if the JSON is malformed
     */
    public static Preset fromJson(String json) {
        Preset preset = GSON.fromJson(json, Preset.class);
        if (preset == null) {
            preset = new Preset();
        }
        // Guard against null collections from partial / legacy files.
        if (preset.tags == null) {
            preset.tags = new ArrayList<>();
        }
        if (preset.perModConfigToggles == null) {
            preset.perModConfigToggles = new LinkedHashMap<>();
        }
        if (preset.capturedSettings == null) {
            preset.capturedSettings = new LinkedHashMap<>();
        }
        return preset;
    }
}
