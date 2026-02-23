package dev.vox.lss.paper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.vox.lss.common.LSSLogger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GSON-based JSON config for the Paper plugin. Same format and defaults as the Fabric
 * server config, stored in plugins/LodServerSupport/lss-server-config.json.
 */
public class PaperConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "lss-server-config.json";

    public boolean enabled = true;
    public int lodDistanceChunks = 128;
    public int maxSectionsPerTickPerPlayer = 200;
    public int maxBytesPerSecondPerPlayer = 2_097_152;
    public boolean sendLightData = true;
    public boolean enableDiskReading = true;
    public int maxConcurrentDiskReads = 64;
    public int diskReaderThreads = 2;
    public int maxSendQueueSize = 4800;
    public int maxBytesPerSecondGlobal = 10_485_760;
    public int maxRequestsPerBatch = 256;
    public int maxPendingRequestsPerPlayer = 512;
    public boolean enableChunkGeneration = true;
    public int generationDistanceChunks = 64;
    public int maxConcurrentGenerations = 16;
    public int maxConcurrentGenerationsPerPlayer = 8;
    public int generationTimeoutSeconds = 60;

    public void validate() {
        lodDistanceChunks = clamp(lodDistanceChunks, 1, 512);
        maxSectionsPerTickPerPlayer = clamp(maxSectionsPerTickPerPlayer, 1, 10000);
        maxBytesPerSecondPerPlayer = clamp(maxBytesPerSecondPerPlayer, 1024, 104_857_600);
        maxConcurrentDiskReads = clamp(maxConcurrentDiskReads, 1, 512);
        diskReaderThreads = clamp(diskReaderThreads, 1, 64);
        maxSendQueueSize = clamp(maxSendQueueSize, 1, 100_000);
        maxBytesPerSecondGlobal = clamp(maxBytesPerSecondGlobal, 1024, 1_073_741_824);
        maxRequestsPerBatch = clamp(maxRequestsPerBatch, 1, 1024);
        maxPendingRequestsPerPlayer = clamp(maxPendingRequestsPerPlayer, 1, 4096);
        generationDistanceChunks = clamp(generationDistanceChunks, 1, 512);
        maxConcurrentGenerations = clamp(maxConcurrentGenerations, 1, 256);
        maxConcurrentGenerationsPerPlayer = clamp(maxConcurrentGenerationsPerPlayer, 1, 64);
        generationTimeoutSeconds = clamp(generationTimeoutSeconds, 1, 600);
    }

    public void save(Path dataFolder) {
        try {
            Files.createDirectories(dataFolder);
            Files.writeString(dataFolder.resolve(FILE_NAME), GSON.toJson(this));
        } catch (Exception e) {
            LSSLogger.error("Failed to save config " + FILE_NAME, e);
        }
    }

    public static PaperConfig load(Path dataFolder) {
        Path path = dataFolder.resolve(FILE_NAME);
        boolean fileExists = Files.isRegularFile(path);
        if (fileExists) {
            try {
                String json = Files.readString(path);
                PaperConfig config = GSON.fromJson(json, PaperConfig.class);
                if (config != null) {
                    config.validate();
                    config.save(dataFolder);
                    return config;
                }
                LSSLogger.warn("Config " + FILE_NAME + " was empty or invalid, using defaults");
            } catch (Exception e) {
                LSSLogger.error("Failed to read config " + FILE_NAME + ", using defaults", e);
            }
        }
        PaperConfig config = new PaperConfig();
        if (!fileExists) {
            config.save(dataFolder);
        }
        return config;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
