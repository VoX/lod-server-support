package dev.vox.lss.networking.client;

import dev.vox.lss.LSSLogger;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class ColumnCacheStore {
    private static final int FORMAT_VERSION = 1;
    private static final Path CACHE_DIR = FabricLoader.getInstance().getConfigDir().resolve("lss").resolve("cache");

    public static Long2LongOpenHashMap load(String serverAddress, ResourceKey<Level> dimension) {
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        var file = getCacheFile(serverAddress, dimension);
        if (!Files.exists(file)) return map;

        try (var in = new DataInputStream(Files.newInputStream(file))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                LSSLogger.warn("Column cache " + file + " has unsupported version " + version + ", discarding");
                return map;
            }
            int count = in.readInt();
            map.ensureCapacity(count);
            for (int i = 0; i < count; i++) {
                long pos = in.readLong();
                long timestamp = in.readLong();
                map.put(pos, timestamp);
            }
            LSSLogger.info("Loaded " + count + " cached column entries for " + dimensionKey(dimension));
        } catch (IOException e) {
            LSSLogger.warn("Failed to load column cache from " + file + ": " + e.getMessage());
        }
        return map;
    }

    public static void save(String serverAddress, ResourceKey<Level> dimension, Long2LongOpenHashMap columns) {
        if (columns.isEmpty()) return;

        var file = getCacheFile(serverAddress, dimension);
        var tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (var out = new DataOutputStream(Files.newOutputStream(tmpFile))) {
                out.writeInt(FORMAT_VERSION);
                out.writeInt(columns.size());
                for (var entry : columns.long2LongEntrySet()) {
                    out.writeLong(entry.getLongKey());
                    out.writeLong(entry.getLongValue());
                }
            }
            Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LSSLogger.info("Saved " + columns.size() + " cached column entries for " + dimensionKey(dimension));
        } catch (IOException e) {
            LSSLogger.warn("Failed to save column cache to " + file + ": " + e.getMessage());
            try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
        }
    }

    public static void clearForServer(String serverAddress) {
        var dir = getServerDir(serverAddress);
        if (!Files.exists(dir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
            LSSLogger.info("Cleared column cache for server " + serverAddress);
        } catch (IOException e) {
            LSSLogger.warn("Failed to clear column cache for " + serverAddress + ": " + e.getMessage());
        }
    }

    public static void clearAll() {
        if (!Files.exists(CACHE_DIR)) return;

        try (DirectoryStream<Path> servers = Files.newDirectoryStream(CACHE_DIR)) {
            for (Path serverDir : servers) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(serverDir)) {
                    for (Path file : files) {
                        Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(serverDir);
            }
            LSSLogger.info("Cleared all column caches");
        } catch (IOException e) {
            LSSLogger.warn("Failed to clear all column caches: " + e.getMessage());
        }
    }

    private static Path getServerDir(String serverAddress) {
        return CACHE_DIR.resolve(sanitize(serverAddress));
    }

    private static Path getCacheFile(String serverAddress, ResourceKey<Level> dimension) {
        return getServerDir(serverAddress).resolve(dimensionKey(dimension) + ".bin");
    }

    private static String dimensionKey(ResourceKey<Level> dimension) {
        return sanitize(dimension.identifier().toString());
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
