package dev.vox.lss.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Generic JSON config loader. Subclasses declare public fields as config entries;
 * this base class handles persistence via GSON. The config directory is supplied
 * at load time (platforms resolve it differently).
 */
public abstract class JsonConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Set by load(); transient so GSON neither serializes nor overwrites it.
    private transient Path configDir;
    // The filename load() actually resolved — read AND written here, so a config adopted from the
    // OTHER brand's file (an LSS<->VSS jar swap) keeps being written back to that same file rather
    // than silently forking a second file. Null for a directly-constructed instance (tests), which
    // then falls back to getFileName().
    private transient String activeFileName;

    /** The brand-PRIMARY config filename: the file created when none exists, and the save target
     *  for an instance built directly rather than via {@link #load} (tests). */
    protected abstract String getFileName();

    private String saveFileName() {
        return this.activeFileName != null ? this.activeFileName : getFileName();
    }

    /** Override to clamp or correct field values after deserialization. */
    public void validate() {}

    public void save() {
        String name = saveFileName();
        try {
            Path path = this.configDir.resolve(name);
            Files.createDirectories(path.getParent());
            // Write to a temp file then atomically move over the target. load() re-saves on
            // every startup, so a plain truncate-then-write that crashes mid-write would leave
            // a corrupt file and every subsequent boot would silently fall back to all defaults.
            Path tmp = path.resolveSibling(name + ".tmp");
            Files.writeString(tmp, GSON.toJson(this));
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LSSLogger.error("Failed to save config " + name, e);
        }
    }

    /** Single-file load (no cross-brand fallback) — the original behavior. */
    protected static <T extends JsonConfig> T load(Class<T> type, String fileName, Path configDir) {
        return load(type, new String[]{fileName}, configDir);
    }

    /**
     * Load from the first EXISTING candidate filename (brand-primary first, other brand's file as a
     * fallback — see {@link #brandedConfigCandidates}). The chosen file is adopted as the save
     * target, so read and write stay on the same file (an LSS&lt;-&gt;VSS jar swap keeps its config
     * instead of forking a fresh one). If NO candidate exists, the brand-primary (candidates[0]) is
     * created with defaults. A candidate that exists but is empty/corrupt is left untouched — the
     * defaults are used in memory but never written over it.
     */
    protected static <T extends JsonConfig> T load(Class<T> type, String[] candidates, Path configDir) {
        if (candidates.length == 0) {
            // Unreachable via current callers (single-file wraps to length 1; brandedConfigCandidates
            // always returns 2), but this overload is protected — fail loudly rather than let
            // candidates[0] below throw a bare AIOOBE that escapes the ReflectiveOperationException catch.
            throw new IllegalArgumentException("config candidate list must be non-empty");
        }
        String chosen = null;
        for (String name : candidates) {
            if (Files.isRegularFile(configDir.resolve(name))) { chosen = name; break; }
        }
        if (chosen != null) {
            try {
                String json = Files.readString(configDir.resolve(chosen));
                T config = GSON.fromJson(json, type);
                if (config != null) {
                    JsonConfig jc = (JsonConfig) config;
                    jc.configDir = configDir;
                    jc.activeFileName = chosen; // adopt: read + write the SAME file
                    config.validate();
                    config.save();
                    return config;
                }
                LSSLogger.warn("Config " + chosen + " was empty or invalid, using defaults");
            } catch (Exception e) {
                LSSLogger.error("Failed to read config " + chosen + ", using defaults", e);
            }
        }
        try {
            T config = type.getDeclaredConstructor().newInstance();
            JsonConfig jc = (JsonConfig) config;
            jc.configDir = configDir;
            jc.activeFileName = candidates[0]; // the brand-primary
            config.validate();
            if (chosen == null) { // only create a fresh file when NONE existed (don't clobber a corrupt one)
                config.save();
            }
            return config;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot instantiate config " + type.getName(), e);
        }
    }

    /**
     * Ordered config-filename candidates for the running brand: the brand's OWN file first, the
     * other brand's file as a fallback. This is what lets a "Voxy Server Side" install prefer
     * {@code vss-<kind>-config.json} yet still adopt an existing {@code lss-<kind>-config.json}
     * (and vice-versa) — so swapping the LSS and VSS jars keeps the config, and only a genuinely
     * fresh install creates the brand's own file. {@code kind} is e.g. "client".
     */
    protected static String[] brandedConfigCandidates(String kind) {
        String lss = "lss-" + kind + "-config.json";
        String vss = "vss-" + kind + "-config.json";
        return "VSS".equalsIgnoreCase(Brand.shortName())
                ? new String[]{vss, lss}
                : new String[]{lss, vss};
    }
}
