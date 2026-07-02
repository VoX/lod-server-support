package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSLogger;
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
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ColumnCacheStore {
    private static final Pattern SANITIZE_PATTERN = Pattern.compile("[^a-zA-Z0-9._-]");
    static final int FORMAT_VERSION = 4; // package-visible: corruption tests write real headers
    private static final int MAX_CACHE_ENTRIES = 2_000_000;
    private static final Path CACHE_DIR = FabricLoader.getInstance().getConfigDir().resolve("lss").resolve("cache");
    // Daemon thread — saves use atomic rename so JVM shutdown mid-write won't corrupt,
    // but the save may be lost. Acceptable for a rebuildable client cache.
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LSS-CacheIO");
        t.setDaemon(true);
        return t;
    });

    public static Long2LongOpenHashMap load(String serverAddress, ResourceKey<Level> dimension) {
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        var file = getCacheFile(serverAddress, dimension);
        if (!Files.exists(file)) return map;

        try (var in = new DataInputStream(Files.newInputStream(file))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                // Pre-v4 caches may hold fabricated client-clock stamps that are
                // indistinguishable from real server timestamps, so they are discarded rather
                // than migrated (the cache is rebuildable; one cold resync after upgrade).
                LSSLogger.info("Column cache " + file + " is v" + version + " (< v" + FORMAT_VERSION
                        + "); discarding — timestamps rebuild on resync");
                return map;
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_CACHE_ENTRIES) {
                LSSLogger.warn("Column cache " + file + " has invalid entry count " + count + ", discarding");
                return map;
            }
            // (no pre-size: Long2LongOpenHashMap.ensureCapacity is private in fastutil 8.5.9,
            // which 1.20.1 ships; growth-on-put is fine at these entry counts)
            for (int i = 0; i < count; i++) {
                long pos = in.readLong();
                map.put(pos, in.readLong()); // raw server timestamp
            }
            LSSLogger.info("Loaded " + count + " cached column entries for " + dimensionKey(dimension));
        } catch (IOException e) {
            LSSLogger.warn("Failed to load column cache from " + file, e);
        }
        return map;
    }

    public static CompletableFuture<Long2LongOpenHashMap> loadAsync(String serverAddress, ResourceKey<Level> dimension) {
        return CompletableFuture.supplyAsync(() -> load(serverAddress, dimension), IO_EXECUTOR);
    }

    public static void saveAsync(String serverAddress, ResourceKey<Level> dimension, Long2LongOpenHashMap columns) {
        if (columns.isEmpty()) return;
        // Defensive copy so the caller can mutate the original freely
        var copy = new Long2LongOpenHashMap(columns);
        copy.defaultReturnValue(-1L);
        IO_EXECUTOR.execute(() -> save(serverAddress, dimension, copy));
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
                    out.writeLong(entry.getLongValue()); // raw timestamp
                }
            }
            Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LSSLogger.info("Saved " + columns.size() + " cached column entries for " + dimensionKey(dimension));
        } catch (IOException e) {
            LSSLogger.warn("Failed to save column cache to " + file, e);
            try { Files.deleteIfExists(tmpFile); } catch (IOException e2) {
                LSSLogger.warn("Failed to clean up temporary cache file " + tmpFile, e2);
            }
        }
    }

    /**
     * Remove one position from a dimension's persisted cache on the IO thread. Used for a
     * cross-dimension ingest-failure report: the failing column's stamp is already saved in the
     * OTHER dimension's cache (the report arrived after the player changed dimension, so its
     * in-memory state map is gone), and leaving it would answer a false up_to_date next time
     * that dimension is visited. Unconditional (like the same-dimension unstamp): a legitimate
     * re-serve since then just costs one harmless re-request on the next visit.
     */
    public static void removeAsync(String serverAddress, ResourceKey<Level> dimension, long packed) {
        IO_EXECUTOR.execute(() -> {
            var map = load(serverAddress, dimension); // reuses the FIFO IO thread's file access
            if (map.remove(packed) == map.defaultReturnValue()) return; // wasn't present
            if (map.isEmpty()) {
                // save() skips empty maps, so delete the now-empty file directly.
                try {
                    Files.deleteIfExists(getCacheFile(serverAddress, dimension));
                } catch (IOException e) {
                    LSSLogger.warn("Failed to delete emptied column cache for " + dimensionKey(dimension), e);
                }
            } else {
                save(serverAddress, dimension, map);
            }
        });
    }

    public static void clearForServer(String serverAddress) {
        // Run on the IO thread (serialized FIFO with in-flight saves/loads so a queued save can't
        // re-create the files we delete) and wait, to preserve the synchronous clear contract.
        runIoAndWait(() -> {
            var dir = getServerDir(serverAddress);
            if (!Files.exists(dir)) return;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path file : stream) {
                    Files.deleteIfExists(file);
                }
                Files.deleteIfExists(dir);
                LSSLogger.info("Cleared column cache for server " + serverAddress);
            } catch (IOException e) {
                LSSLogger.warn("Failed to clear column cache for " + serverAddress, e);
            }
        });
    }

    public static void clearAll() {
        // Run on the IO thread (serialized FIFO with in-flight saves/loads) and wait, to preserve
        // the synchronous clear contract.
        runIoAndWait(() -> {
            if (!Files.exists(CACHE_DIR)) return;

            try (DirectoryStream<Path> servers = Files.newDirectoryStream(CACHE_DIR)) {
                for (Path serverDir : servers) {
                    if (!Files.isDirectory(serverDir)) continue;
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(serverDir)) {
                        for (Path file : files) {
                            Files.deleteIfExists(file);
                        }
                    }
                    Files.deleteIfExists(serverDir);
                }
                LSSLogger.info("Cleared all column caches");
            } catch (IOException e) {
                LSSLogger.warn("Failed to clear all column caches", e);
            }
        });
    }

    /**
     * Block until all currently queued cache IO (saves/loads/clears) has completed.
     * The IO executor is a single FIFO thread, so an empty task draining through it
     * proves everything submitted before this call has finished. Needed by harnesses
     * that halt the JVM right after disconnect — the async disconnect save would
     * otherwise be lost with the daemon IO thread.
     */
    public static void flushPendingIo() {
        runIoAndWait(() -> {});
    }

    /**
     * Upper bound on how long a synchronous cache call ({@link #clearForServer},
     * {@link #clearAll}, {@link #flushPendingIo}) blocks its caller — these run on the main
     * client (render) thread via {@code /lss clearcache}. The clear contract only needs
     * submit-side FIFO ordering (the task is queued behind in-flight saves on the single IO
     * thread, so a queued save can never resurrect cleared files); an unbounded wait adds
     * nothing but a client hard-freeze when the cache dir sits on a hung network/removable
     * mount. Package-visible so tests can shrink it.
     */
    static volatile long ioWaitTimeoutMs = 30_000;

    /** Test seam: occupy the single IO thread with an arbitrary task (e.g. a gate latch). */
    static void runIoAsyncForTest(Runnable task) {
        IO_EXECUTOR.execute(task);
    }

    /** Run a cache IO task on the single IO thread and wait for it (serializes with saves/loads). */
    private static void runIoAndWait(Runnable task) {
        var future = IO_EXECUTOR.submit(task);
        try {
            future.get(ioWaitTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LSSLogger.warn("Column cache IO task failed", e.getCause());
        } catch (TimeoutException e) {
            LSSLogger.warn("Column cache IO wait exceeded " + ioWaitTimeoutMs
                    + "ms — abandoning the wait (the task stays queued on the IO thread)");
        }
    }

    private static Path getServerDir(String serverAddress) {
        return CACHE_DIR.resolve(sanitizeForFilePath(serverAddress));
    }

    private static Path getCacheFile(String serverAddress, ResourceKey<Level> dimension) {
        return getServerDir(serverAddress).resolve(dimensionKey(dimension) + ".bin");
    }

    private static String dimensionKey(ResourceKey<Level> dimension) {
        return sanitizeForFilePath(dimension.location().toString());
    }

    static String sanitizeForFilePath(String name) {
        return SANITIZE_PATTERN.matcher(name).replaceAll("_");
    }
}
