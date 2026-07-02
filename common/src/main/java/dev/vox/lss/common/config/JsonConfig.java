package dev.vox.lss.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

    protected abstract String getFileName();

    /** Override to clamp or correct field values after deserialization. */
    public void validate() {}

    public void save() {
        try {
            Path path = this.configDir.resolve(getFileName());
            Files.createDirectories(path.getParent());
            // Write to a temp file then atomically move over the target. load() re-saves on
            // every startup, so a plain truncate-then-write that crashes mid-write would leave
            // a corrupt file and every subsequent boot would silently fall back to all defaults.
            Path tmp = path.resolveSibling(getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this));
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LSSLogger.error("Failed to save config " + getFileName(), e);
        }
    }

    protected static <T extends JsonConfig> T load(Class<T> type, String fileName, Path configDir) {
        Path path = configDir.resolve(fileName);
        boolean fileExists = Files.isRegularFile(path);
        if (fileExists) {
            try {
                String json = Files.readString(path);
                T config = GSON.fromJson(json, type);
                if (config != null) {
                    ((JsonConfig) config).configDir = configDir;
                    config.validate();
                    config.save();
                    return config;
                }
                LSSLogger.warn("Config " + fileName + " was empty or invalid, using defaults");
            } catch (Exception e) {
                LSSLogger.error("Failed to read config " + fileName + ", using defaults", e);
            }
        }
        try {
            T config = type.getDeclaredConstructor().newInstance();
            ((JsonConfig) config).configDir = configDir;
            config.validate();
            if (!fileExists) {
                config.save();
            }
            return config;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot instantiate config " + type.getName(), e);
        }
    }
}
