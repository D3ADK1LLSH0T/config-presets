package com.configpresets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages persistence and lifecycle of {@link Preset} objects.
 *
 * <p>Each preset is stored as an individual pretty-printed {@code .json} file in
 * the {@code .minecraft/config-presets/} directory. The file name is derived from
 * the preset name (sanitized).</p>
 *
 * <p><b>Performance.</b> Parsed presets are cached in memory and keyed by file
 * name. On every {@link #getAll()} the directory listing is compared against the
 * cache using each file's last-modified timestamp and size, so unchanged files
 * are never re-read or re-parsed. The single shared {@link Preset#GSON} instance
 * is reused for all (de)serialization, and {@link #save(Preset)} skips the disk
 * write entirely when the preset's serialized form is unchanged (a built-in
 * dirty check).</p>
 */
public class PresetManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("configpresets/PresetManager");

    /** Reuse the single shared Gson instance instead of allocating another. */
    private static final com.google.gson.Gson GSON = Preset.GSON;

    private final Path presetsDir;

    /**
     * In-memory cache of parsed presets keyed by file name. Invalidated per-file
     * via last-modified timestamp + size so external edits are still picked up.
     */
    private final Map<String, CacheEntry> cache = new HashMap<>();

    /** Cached parsed preset plus the file fingerprint used to detect changes. */
    private static final class CacheEntry {
        final Preset preset;
        final FileTime mtime;
        final long size;
        /** Last (de)serialized JSON, used for the no-op-save dirty check. */
        final String json;

        CacheEntry(Preset preset, FileTime mtime, long size, String json) {
            this.preset = preset;
            this.mtime = mtime;
            this.size = size;
            this.json = json;
        }
    }

    /** The canonical name for the built-in default preset. */
    public static final String DEFAULT_PRESET_NAME = "Default";

    public PresetManager(Path gameDir) {
        this.presetsDir = gameDir.resolve("config-presets");
        ensureDirectory();
    }

    /** Convenience constructor resolving against the current working directory. */
    public PresetManager() {
        this(Paths.get("."));
    }

    /**
     * Ensures a "Default" preset exists on disk. If none is found, one is created
     * with all capture toggles enabled, marked as the launch default, and saved.
     * Should be called once during mod initialization after the manager is created.
     */
    public synchronized void ensureDefaultPreset() {
        Preset def = load(DEFAULT_PRESET_NAME);
        if (def == null) {
            def = new Preset(DEFAULT_PRESET_NAME);
            def.description = "The default configuration preset. All settings are always saved.";
            def.enforceDefaultConstraints();
            save(def);
            LOGGER.info("Created built-in Default preset");
        } else {
            // Always re-enforce constraints in case the file was hand-edited.
            def.enforceDefaultConstraints();
            save(def);
        }
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(presetsDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create presets directory at {}", presetsDir, e);
        }
    }

    public Path getPresetsDir() {
        return presetsDir;
    }

    // ---------------------------------------------------------------------
    // File naming
    // ---------------------------------------------------------------------

    /** Sanitizes a preset name into a safe file name (without extension). */
    public static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        String cleaned = name.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");
        return cleaned.isBlank() ? "unnamed" : cleaned;
    }

    private Path fileFor(String name) {
        return presetsDir.resolve(sanitize(name) + ".json");
    }

    // ---------------------------------------------------------------------
    // CRUD operations
    // ---------------------------------------------------------------------

    /**
     * Persists a preset to disk, updating its lastModified timestamp.
     *
     * <p>If the preset's serialized form is identical to what was last written
     * (tracked in the cache), the disk write is skipped to avoid unnecessary I/O.</p>
     */
    public synchronized boolean save(Preset preset) {
        if (preset == null || preset.name == null || preset.name.isBlank()) {
            LOGGER.warn("Refusing to save preset with no name");
            return false;
        }
        ensureDirectory();
        Path target = fileFor(preset.name);
        String key = target.getFileName().toString();

        // Dirty check: if the current serialized state matches the last write and
        // the file still exists, there is nothing to persist.
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.preset == preset) {
            String currentJson = GSON.toJson(preset);
            if (currentJson.equals(cached.json) && Files.isRegularFile(target)) {
                return true;
            }
        }

        preset.touch();
        String json = GSON.toJson(preset);
        try {
            Files.writeString(target, json, StandardCharsets.UTF_8);
            FileTime mtime = Files.getLastModifiedTime(target);
            cache.put(key, new CacheEntry(preset, mtime, json.length(), json));
            LOGGER.info("Saved preset '{}' -> {}", preset.name, target.getFileName());
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save preset '{}'", preset.name, e);
            return false;
        }
    }

    /** Loads a preset by name, or {@code null} if missing/unreadable. */
    public synchronized Preset load(String name) {
        Path target = fileFor(name);
        if (!Files.isRegularFile(target)) {
            return null;
        }
        String key = target.getFileName().toString();
        try {
            // Fast path: return the cached instance when the file is unchanged.
            FileTime mtime = Files.getLastModifiedTime(target);
            long size = Files.size(target);
            CacheEntry cached = cache.get(key);
            if (cached != null && cached.mtime.equals(mtime) && cached.size == size) {
                return cached.preset;
            }
            String json = Files.readString(target, StandardCharsets.UTF_8);
            Preset preset = Preset.fromJson(json);
            cache.put(key, new CacheEntry(preset, mtime, size, json));
            return preset;
        } catch (Exception e) {
            LOGGER.error("Failed to load preset '{}'", name, e);
            return null;
        }
    }

    /**
     * Deletes a preset by name. The built-in default preset cannot be deleted.
     *
     * @return true if a file was removed
     */
    public synchronized boolean delete(String name) {
        if (isDefaultPreset(name)) {
            LOGGER.warn("Refusing to delete the Default preset");
            return false;
        }
        Path target = fileFor(name);
        try {
            boolean removed = Files.deleteIfExists(target);
            if (removed) {
                cache.remove(target.getFileName().toString());
                LOGGER.info("Deleted preset '{}'", name);
            }
            return removed;
        } catch (IOException e) {
            LOGGER.error("Failed to delete preset '{}'", name, e);
            return false;
        }
    }

    /**
     * Returns true if the given name resolves to the built-in default preset
     * (comparison is based on the sanitized file name so renames are tracked).
     */
    public boolean isDefaultPreset(String name) {
        if (name == null) return false;
        Preset def = getDefault();
        if (def != null) {
            return sanitize(def.name).equals(sanitize(name));
        }
        return sanitize(name).equals(sanitize(DEFAULT_PRESET_NAME));
    }

    /**
     * Returns true if the given preset object is the built-in default preset.
     */
    public boolean isDefaultPreset(Preset preset) {
        return preset != null && preset.isDefault;
    }

    /**
     * Renames a preset. Loads the old preset, writes it under the new name, and
     * removes the old file. Returns true on success.
     */
    public synchronized boolean rename(String oldName, String newName) {
        if (newName == null || newName.isBlank()) {
            return false;
        }
        Preset preset = load(oldName);
        if (preset == null) {
            LOGGER.warn("Cannot rename: preset '{}' not found", oldName);
            return false;
        }
        Path newTarget = fileFor(newName);
        if (Files.exists(newTarget) && !sanitize(oldName).equals(sanitize(newName))) {
            LOGGER.warn("Cannot rename: target '{}' already exists", newName);
            return false;
        }
        preset.name = newName;
        if (!save(preset)) {
            return false;
        }
        if (!sanitize(oldName).equals(sanitize(newName))) {
            delete(oldName);
        }
        return true;
    }

    /**
     * Duplicates a preset, producing a copy with a unique "(copy)" name.
     * Returns the newly created preset, or {@code null} on failure.
     */
    public synchronized Preset duplicate(Preset source) {
        if (source == null) {
            return null;
        }
        // Round-trip through JSON for a deep copy.
        Preset copy = Preset.fromJson(source.toJson());
        copy.isDefault = false;      // duplicates are never default
        copy.autoSaveOnExit = false;  // duplicates don't inherit auto-save
        String base = (source.name == null ? "preset" : source.name) + " (copy)";
        String candidate = base;
        int i = 2;
        while (Files.exists(fileFor(candidate))) {
            candidate = base + " " + i++;
        }
        copy.name = candidate;
        copy.createdAt = System.currentTimeMillis();
        copy.lastModified = copy.createdAt;
        return save(copy) ? copy : null;
    }

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    /**
     * Returns all presets found on disk. Unchanged files are served from the
     * in-memory cache; only new or modified files are re-read and parsed.
     */
    public synchronized List<Preset> getAll() {
        List<Preset> result = new ArrayList<>();
        if (!Files.isDirectory(presetsDir)) {
            cache.clear();
            return result;
        }
        Set<String> seen = new HashSet<>();
        try (Stream<Path> stream = Files.list(presetsDir)) {
            List<Path> files = stream
                    .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .collect(Collectors.toList());
            for (Path p : files) {
                String key = p.getFileName().toString();
                seen.add(key);
                try {
                    FileTime mtime = Files.getLastModifiedTime(p);
                    long size = Files.size(p);
                    CacheEntry cached = cache.get(key);
                    if (cached != null && cached.mtime.equals(mtime) && cached.size == size) {
                        result.add(cached.preset);
                        continue;
                    }
                    String json = Files.readString(p, StandardCharsets.UTF_8);
                    Preset preset = Preset.fromJson(json);
                    cache.put(key, new CacheEntry(preset, mtime, size, json));
                    result.add(preset);
                } catch (Exception e) {
                    LOGGER.error("Skipping unreadable preset file {}", p.getFileName(), e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to list presets directory {}", presetsDir, e);
        }
        // Evict cache entries for files that no longer exist.
        cache.keySet().retainAll(seen);
        return result;
    }

    /** Returns presets in the given folder ({@code null} = root). */
    public synchronized List<Preset> getByFolder(String folder) {
        List<Preset> all = getAll();
        List<Preset> result = new ArrayList<>();
        for (Preset p : all) {
            if (Objects.equals(p.folder, folder)) {
                result.add(p);
            }
        }
        return result;
    }

    /** Returns presets carrying a tag with the given (case-insensitive) name. */
    public synchronized List<Preset> getByTag(String tagName) {
        List<Preset> all = getAll();
        List<Preset> result = new ArrayList<>();
        for (Preset p : all) {
            if (p.hasTag(tagName)) {
                result.add(p);
            }
        }
        return result;
    }

    /** Returns a sorted list of distinct folder names (excluding root/null). */
    public synchronized List<String> getFolders() {
        List<String> folders = new ArrayList<>();
        for (Preset p : getAll()) {
            if (p.folder != null && !p.folder.isBlank() && !folders.contains(p.folder)) {
                folders.add(p.folder);
            }
        }
        folders.sort(String.CASE_INSENSITIVE_ORDER);
        return folders;
    }

    // ---------------------------------------------------------------------
    // Default preset management
    // ---------------------------------------------------------------------

    /**
     * Marks the given preset as the single default, clearing the flag on all
     * others and persisting the changes. Only presets whose flag actually changes
     * are written back to disk.
     */
    public synchronized void setDefault(Preset target) {
        if (target == null) {
            return;
        }
        String targetFile = sanitize(target.name);
        for (Preset p : getAll()) {
            boolean shouldBeDefault = sanitize(p.name).equals(targetFile);
            if (p.isDefault != shouldBeDefault) {
                p.isDefault = shouldBeDefault;
                save(p);
            }
        }
        target.isDefault = true;
    }

    /** Clears the default flag from whatever preset currently holds it. */
    public synchronized void clearDefault() {
        for (Preset p : getAll()) {
            if (p.isDefault) {
                p.isDefault = false;
                save(p);
            }
        }
    }

    /** Returns the default preset, or {@code null} if none is set. */
    public synchronized Preset getDefault() {
        for (Preset p : getAll()) {
            if (p.isDefault) {
                return p;
            }
        }
        return null;
    }

    /** Returns true if a preset with the given name exists on disk. */
    public synchronized boolean exists(String name) {
        return Files.exists(fileFor(name));
    }
}
