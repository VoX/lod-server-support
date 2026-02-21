package dev.vox.lss.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.vox.lss.LSSLogger;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generic JSON config loader. Subclasses declare public fields as config entries;
 * this base class handles persistence via GSON.
 */
public abstract class JsonConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    protected abstract String getFileName();

    /** Override to clamp or correct field values after deserialization. */
    protected void validate() {}

    public void save() {
        try {
            Path path = resolvePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (Exception e) {
            LSSLogger.error("Failed to save config " + getFileName(), e);
        }
    }

    private Path resolvePath() {
        return FabricLoader.getInstance().getConfigDir().resolve(getFileName());
    }

    protected static <T extends JsonConfig> T load(Class<T> type, String fileName) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(fileName);
        if (Files.isRegularFile(path)) {
            try {
                String json = Files.readString(path);
                T config = GSON.fromJson(json, type);
                if (config != null) {
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
            config.save();
            return config;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot instantiate config " + type.getName(), e);
        }
    }
}
