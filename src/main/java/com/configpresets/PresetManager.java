package com.configpresets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Manages persistence and lifecycle of {@link Preset} objects.
 *
 * <p>Each preset is stored as an individual pretty-printed {@code .json} file in
 * the {@code .minecraft/config-presets/} directory. The file name is derived from
 * the preset name (sanitized).</p>
 */
public class PresetManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("configpresets/PresetManager");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path presetsDir;

    public PresetManager(Path gameDir) {
        this.presetsDir = gameDir.resolve("config-presets");
        ensureDirectory();
    }

    /** Convenience constructor resolving against the current working directory. */
    public PresetManager() {
        this(Paths.get("."));
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

    /** Persists a preset to disk, updating its lastModified timestamp. */
    public synchronized boolean save(Preset preset) {
        if (preset == null || preset.name == null || preset.name.isBlank()) {
            LOGGER.warn("Refusing to save preset with no name");
            return false;
        }
        ensureDirectory();
        preset.touch();
        Path target = fileFor(preset.name);
        try {
            Files.writeString(target, GSON.toJson(preset), StandardCharsets.UTF_8);
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
        try {
            String json = Files.readString(target, StandardCharsets.UTF_8);
            return Preset.fromJson(json);
        } catch (Exception e) {
            LOGGER.error("Failed to load preset '{}'", name, e);
            return null;
        }
    }

    /** Deletes a preset by name. Returns true if a file was removed. */
    public synchronized boolean delete(String name) {
        Path target = fileFor(name);
        try {
            boolean removed = Files.deleteIfExists(target);
            if (removed) {
                LOGGER.info("Deleted preset '{}'", name);
            }
            return removed;
        } catch (IOException e) {
            LOGGER.error("Failed to delete preset '{}'", name, e);
            return false;
        }
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
        copy.isDefault = false; // duplicates are never default
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

    /** Returns all presets found on disk. */
    public synchronized List<Preset> getAll() {
        List<Preset> result = new ArrayList<>();
        if (!Files.isDirectory(presetsDir)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(presetsDir)) {
            stream.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            Preset preset = Preset.fromJson(
                                    Files.readString(p, StandardCharsets.UTF_8));
                            result.add(preset);
                        } catch (Exception e) {
                            LOGGER.error("Skipping unreadable preset file {}", p.getFileName(), e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Failed to list presets directory {}", presetsDir, e);
        }
        return result;
    }

    /** Returns presets in the given folder ({@code null} = root). */
    public synchronized List<Preset> getByFolder(String folder) {
        List<Preset> result = new ArrayList<>();
        for (Preset p : getAll()) {
            if (Objects.equals(p.folder, folder)
                    || (folder != null && folder.equals(p.folder))) {
                result.add(p);
            }
        }
        return result;
    }

    /** Returns presets carrying a tag with the given (case-insensitive) name. */
    public synchronized List<Preset> getByTag(String tagName) {
        List<Preset> result = new ArrayList<>();
        for (Preset p : getAll()) {
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
     * others and persisting the changes.
     */
    public synchronized void setDefault(Preset target) {
        if (target == null) {
            return;
        }
        for (Preset p : getAll()) {
            boolean shouldBeDefault = sanitize(p.name).equals(sanitize(target.name));
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
