package dev.vox.lss.paper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * GSON-based JSON config for the Paper plugin. Same format and defaults as the Fabric
 * server config, stored in plugins/LodServerSupport/lss-server-config.json.
 */
public class PaperConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "lss-server-config.json";

    public boolean enabled = true;
    public int lodDistanceChunks = 256;
    public int bytesPerSecondLimitPerPlayer = 20_971_520;
    public int diskReaderThreads = 5;
    public int sendQueueLimitPerPlayer = 4000;
    public int bytesPerSecondLimitGlobal = 104_857_600;
    public boolean enableChunkGeneration = true;
    public int generationConcurrencyLimitGlobal = 32;

    public int generationTimeoutSeconds = 60;
    public int syncOnLoadRateLimitPerPlayer = 800;
    public int syncOnLoadConcurrencyLimitPerPlayer = 200;
    public int generationRateLimitPerPlayer = 80;
    public int generationConcurrencyLimitPerPlayer = 16;
    public int perDimensionTimestampCacheSizeMB = 32;
    public int dirtyBroadcastIntervalSeconds = 10;
    public List<String> updateEvents = List.of(
            "org.bukkit.event.block.BlockPlaceEvent",
            "org.bukkit.event.block.BlockBreakEvent",
            "org.bukkit.event.block.BlockExplodeEvent",
            "org.bukkit.event.block.BlockPistonExtendEvent",
            "org.bukkit.event.block.BlockPistonRetractEvent",
            "org.bukkit.event.world.StructureGrowEvent",
            "org.bukkit.event.world.ChunkPopulateEvent"
    );

    public void validate() {
        lodDistanceChunks = Math.clamp(lodDistanceChunks, LSSConstants.MIN_LOD_DISTANCE, LSSConstants.MAX_LOD_DISTANCE);
        bytesPerSecondLimitPerPlayer = Math.clamp(bytesPerSecondLimitPerPlayer, LSSConstants.MIN_BYTES_PER_SECOND, LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER);
        diskReaderThreads = Math.clamp(diskReaderThreads, LSSConstants.MIN_DISK_READER_THREADS, LSSConstants.MAX_DISK_READER_THREADS);
        sendQueueLimitPerPlayer = Math.clamp(sendQueueLimitPerPlayer, LSSConstants.MIN_SEND_QUEUE_SIZE, LSSConstants.MAX_SEND_QUEUE_SIZE);
        bytesPerSecondLimitGlobal = (int) Math.clamp((long) bytesPerSecondLimitGlobal, LSSConstants.MIN_BYTES_PER_SECOND, LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT);
        generationConcurrencyLimitGlobal = Math.clamp(generationConcurrencyLimitGlobal, LSSConstants.MIN_CONCURRENT_GENERATIONS, LSSConstants.MAX_CONCURRENT_GENERATIONS);

        generationTimeoutSeconds = Math.clamp(generationTimeoutSeconds, LSSConstants.MIN_GENERATION_TIMEOUT, LSSConstants.MAX_GENERATION_TIMEOUT);
        syncOnLoadRateLimitPerPlayer = Math.clamp(syncOnLoadRateLimitPerPlayer, LSSConstants.MIN_RATE_LIMIT, LSSConstants.MAX_RATE_LIMIT);
        syncOnLoadConcurrencyLimitPerPlayer = Math.clamp(syncOnLoadConcurrencyLimitPerPlayer, LSSConstants.MIN_CONCURRENCY_LIMIT, LSSConstants.MAX_CONCURRENCY_LIMIT);
        generationRateLimitPerPlayer = Math.clamp(generationRateLimitPerPlayer, LSSConstants.MIN_RATE_LIMIT, LSSConstants.MAX_RATE_LIMIT);
        generationConcurrencyLimitPerPlayer = Math.clamp(generationConcurrencyLimitPerPlayer, LSSConstants.MIN_CONCURRENCY_LIMIT, LSSConstants.MAX_CONCURRENCY_LIMIT);
        perDimensionTimestampCacheSizeMB = Math.clamp(perDimensionTimestampCacheSizeMB, LSSConstants.MIN_TIMESTAMP_CACHE_SIZE_MB, LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB);
        dirtyBroadcastIntervalSeconds = Math.clamp(dirtyBroadcastIntervalSeconds, LSSConstants.MIN_DIRTY_BROADCAST_INTERVAL, LSSConstants.MAX_DIRTY_BROADCAST_INTERVAL);
        if (updateEvents == null) updateEvents = List.of();
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
        config.validate();
        if (!fileExists) {
            config.save(dataFolder);
        }
        return config;
    }

}
