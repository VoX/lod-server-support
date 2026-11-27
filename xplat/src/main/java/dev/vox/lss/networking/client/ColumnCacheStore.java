package dev.vox.lss.networking.client;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class ColumnCacheStore {
    private static final Pattern SANITIZE_PATTERN = Pattern.compile("[^a-zA-Z0-9._-]");
    static final int FORMAT_VERSION = 4; // package-visible: corruption tests write real headers
    private static final int MAX_CACHE_ENTRIES = 2_000_000;
    // Merge-save eviction threshold. Defaults to the load cap (the loader discards oversized
    // files WHOLESALE, so a merged file must never exceed it); package-visible so tests can
    // exercise eviction without writing 2M entries. The load-side validity guard stays on the
    // constant — a crafted file must not get a bigger allowance from a test override.
    static volatile int mergeEvictionCap = MAX_CACHE_ENTRIES;
    // Test observability: save() attempts (the coalesced-removal pin counts rewrites).
    static final AtomicInteger SAVE_WRITES_FOR_TEST = new AtomicInteger();
    /** save()/load() stream buffer — see the buffering note in save(). */
    private static final int IO_BUFFER_BYTES = 1 << 16;
    /**
     * Cache root, resolved LAZILY through {@link #resolveCacheRoot} (v0.11.0 stage D —
     * client-reset-command-and-cache-relocation-plan.md Part 2): fresh installs use
     * {@code <gameDir>/.lss/cache} (the {@code .voxy} convention); an install with an
     * existing {@code config/lss/cache} directory keeps it forever (adoption, no
     * migration — user decision). Deliberately UNBRANDED either way (jar-swap
     * continuity, ci-dual-publish.md). Lazy so tests can drive both branches of the
     * pure function without a loaded FabricLoader path baked in at class-init.
     * Accepted corner (review n7): if a backup restore recreates config/lss/cache
     * after .lss/cache was populated, the .lss bytes are orphaned (adoption flips
     * back; load and clear both use the resolved root, so no dishonest stamps —
     * just dead disk until hand-deleted).
     */
    private static volatile Path cacheDir;

    private static Path cacheDir() {
        Path dir = cacheDir;
        if (dir == null) {
            dir = resolveCacheRoot(dev.vox.lss.platform.LoaderServices.get().configDir(),
                    dev.vox.lss.platform.LoaderServices.get().gameDir());
            cacheDir = dir;
        }
        return dir;
    }

    /**
     * The adoption rule as a pure function: an existing {@code configDir/lss/cache}
     * DIRECTORY wins (existing installs keep their cache and their path); otherwise
     * {@code gameDir/.lss/cache}. A directory-exists check is deliberately the whole
     * rule — an empty old dir still adopts (harmless, deterministic).
     */
    static Path resolveCacheRoot(Path configDir, Path gameDir) {
        Path legacy = configDir.resolve("lss").resolve("cache");
        if (Files.isDirectory(legacy)) return legacy;
        // Brand-preferred dot-dir (.vss on a VSS jar) with cross-brand adoption — a jar
        // swap keeps the populated cache instead of orphaning it (the same adoption rule
        // as the branded config candidates).
        boolean vss = "VSS".equalsIgnoreCase(Brand.shortName());
        Path preferred = gameDir.resolve(vss ? ".vss" : ".lss").resolve("cache");
        Path other = gameDir.resolve(vss ? ".lss" : ".vss").resolve("cache");
        if (!Files.isDirectory(preferred) && Files.isDirectory(other)) return other;
        return preferred;
    }

    /** The resolved root — package-visible for the tests that used to rebuild it by hand. */
    static Path cacheRoot() {
        return cacheDir();
    }

    /**
     * Whether map backends are this session's ONLY column consumers (no Voxy, no other LSSApi
     * reader). Read on the CALLER thread when a load or save is queued. A session like that
     * marks the bucket it saves ({@code <dimension>.maponly} beside the file): its stamps cover
     * terrain only a map received. A session WITH another reader ignores a marked file — its
     * stamps would answer up_to_date for terrain Voxy never got — and its next save replaces
     * the file and clears the mark. Unmarked files (every cache from before the mark) load as
     * always. A seam for tests.
     */
    static volatile java.util.function.BooleanSupplier mapOnlyProbe =
            dev.vox.lss.compat.ModCompat::isMapOnlyIngestion;

    // Daemon thread — saves use atomic rename so JVM shutdown mid-write won't corrupt,
    // but the save may be lost. Acceptable for a rebuildable client cache.
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, Brand.shortName() + "-CacheIO");
        t.setDaemon(true);
        return t;
    });

    public static Long2LongOpenHashMap load(String serverAddress, ResourceKey<Level> dimension) {
        return load(serverAddress, dimension, false);
    }

    /** @param ignoreMapOnly a session with a non-map reader: a map-only file loads empty */
    static Long2LongOpenHashMap load(String serverAddress, ResourceKey<Level> dimension, boolean ignoreMapOnly) {
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        var file = getCacheFile(serverAddress, dimension);
        if (!Files.exists(file)) return map;
        if (ignoreMapOnly && Files.exists(mapOnlyMarker(serverAddress, dimension))) {
            LSSLogger.info("Column cache for " + dimensionKey(dimension) + " was filled by map ingestion alone"
                    + " (no Voxy) — ignoring it, so terrain the map received is downloaded again for Voxy");
            return map;
        }

        try (var in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(file), IO_BUFFER_BYTES))) {
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
            // Bound the pre-allocation by what the file can actually hold (8-byte header +
            // 16 bytes/entry), matching ColumnTimestampCache.load: a corrupt/crafted count
            // field must not force a ~64 MB ensureCapacity for entries that aren't there. A
            // short read past this hits EOF and returns the partial map, same as truncation.
            long maxPlausible = Math.max(0, (Files.size(file) - 8) / 16);
            map.ensureCapacity((int) Math.min(count, maxPlausible));
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

    /**
     * Load AND build the section-leaf state on the IO thread in one task (the A1 fix,
     * quadtree-client-state-plan.md §3.4): the old flow returned the raw map and the
     * render thread paid the whole per-entry apply — up to 2M un-presized hash inserts
     * in ONE tick (est. 100-300 ms) at every join and dimension re-entry. The main
     * thread now adopts pre-built leaves in O(leaves).
     */
    static CompletableFuture<ColumnStateMap.LoadedState> loadStateAsync(
            String serverAddress, ResourceKey<Level> dimension) {
        boolean ignoreMapOnly = !mapOnlyProbe.getAsBoolean();
        return CompletableFuture.supplyAsync(() -> loadState(serverAddress, dimension, ignoreMapOnly), IO_EXECUTOR);
    }

    /** IO-thread body of {@link #loadStateAsync}; package-visible so tests can run it inline. */
    static ColumnStateMap.LoadedState loadState(String serverAddress, ResourceKey<Level> dimension,
                                                boolean ignoreMapOnly) {
        var stamps = load(serverAddress, dimension, ignoreMapOnly);
        var state = ColumnStateMap.buildLoaded(stamps);
        if (stamps.isEmpty()) return state; // nothing held: nothing owed and nothing unknown
        // Never let the record cost the stamps: whatever goes wrong reading it, the cache
        // loads, and its columns have an unknown history.
        MapOwedFile record = null;
        try {
            record = loadMapOwed(serverAddress, dimension);
        } catch (RuntimeException e) {
            LSSLogger.warn("The map record for " + dimensionKey(dimension) + " could not be read; ignoring it", e);
        }
        if (record == null) {
            ColumnStateMap.applyMapRecord(state, null, null);
            return state;
        }
        state.mapFillRequested = record.fillRequested();
        state.mapHinted = record.hinted();
        ColumnStateMap.applyMapRecord(state, record.known(), record.owed());
        return state;
    }

    // ---- The map's debt (issue #380): <dimension>.mapowed beside the cache file ----
    //
    // Per 8x8 leaf of the column map, two 64-bit masks: the columns this record KNOWS
    // about, and which of those no map has been given. A column the cache file holds and
    // the record does not know (the whole file, for a cache from before the record; the
    // columns another version added; the columns of a save that was cut short) has an
    // unknown history, which is never read as "the map has it". Half a byte per column.
    // A file of its own so the cache file's format does not change and older versions
    // keep reading it; they ignore this one. It sits flat in the bucket, so a bucket clear
    // deletes it and an adoption moves it.

    private static final int MAP_OWED_MAGIC = 0x4C534D4F; // "LSMO"
    private static final int MAP_OWED_VERSION = 1;
    private static final int MAP_OWED_FILL_REQUESTED = 1;
    private static final int MAP_OWED_HINTED = 2;
    /** Header: magic, version, flags, leaf count. A leaf: key, known mask, owed mask. */
    private static final int MAP_OWED_HEADER_BYTES = 16;
    private static final int MAP_OWED_LEAF_BYTES = 24;

    /** The record on disk, by leaf key. {@code owed} is a subset of {@code known}. */
    record MapOwedFile(boolean fillRequested, boolean hinted, Long2LongOpenHashMap known,
                       Long2LongOpenHashMap owed) {}

    /**
     * What the saving session knows, by leaf key: its flags, which columns it holds at all
     * ({@code present}), which of those the map is owed and which have an unknown history,
     * and the debts it settled (also of columns it no longer holds). For the columns it
     * holds it is the authority; for the rest of the file the record stands.
     */
    record MapDebtSave(ColumnStateMap.MapDebt flags, Long2LongOpenHashMap present, Long2LongOpenHashMap owed,
                       Long2LongOpenHashMap unknown, Long2LongOpenHashMap settled) {}

    static Path mapOwedFile(String serverAddress, ResourceKey<Level> dimension) {
        return getServerDir(serverAddress).resolve(dimensionKey(dimension) + ".mapowed");
    }

    /** The record, or null when there is none or it cannot be read. */
    static MapOwedFile loadMapOwed(String serverAddress, ResourceKey<Level> dimension) {
        var file = mapOwedFile(serverAddress, dimension);
        if (!Files.exists(file)) return null;
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), IO_BUFFER_BYTES))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAP_OWED_MAGIC || version != MAP_OWED_VERSION) {
                LSSLogger.info("Map record " + file + " is not one this version reads; ignoring it");
                return null;
            }
            int flags = in.readInt();
            int count = in.readInt();
            // Bounded by what the file can hold AND by what a cache can: a corrupt count
            // must not size the maps (the cache file's own bound, one leaf per column).
            long plausible = Math.max(0, (Files.size(file) - MAP_OWED_HEADER_BYTES) / MAP_OWED_LEAF_BYTES);
            if (count < 0 || count > plausible || count > MAX_CACHE_ENTRIES) {
                LSSLogger.warn("Map record " + file + " has an invalid leaf count " + count + ", ignoring it");
                return null;
            }
            var known = new Long2LongOpenHashMap(Math.max(16, count));
            var owed = new Long2LongOpenHashMap();
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                long knownMask = in.readLong();
                long owedMask = in.readLong() & knownMask;
                if (knownMask != 0) known.put(key, knownMask);
                if (owedMask != 0) owed.put(key, owedMask);
            }
            return new MapOwedFile((flags & MAP_OWED_FILL_REQUESTED) != 0, (flags & MAP_OWED_HINTED) != 0,
                    known, owed);
        } catch (IOException e) {
            LSSLogger.warn("Failed to read the map record " + file, e);
            return null;
        }
    }

    private static void writeMapOwed(String serverAddress, ResourceKey<Level> dimension, boolean fillRequested,
                                     boolean hinted, Long2LongOpenHashMap known, Long2LongOpenHashMap owed) {
        var file = mapOwedFile(serverAddress, dimension);
        var tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (var out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(tmpFile), IO_BUFFER_BYTES))) {
                out.writeInt(MAP_OWED_MAGIC);
                out.writeInt(MAP_OWED_VERSION);
                out.writeInt((fillRequested ? MAP_OWED_FILL_REQUESTED : 0) | (hinted ? MAP_OWED_HINTED : 0));
                out.writeInt(known.size());
                for (var entry : known.long2LongEntrySet()) {
                    out.writeLong(entry.getLongKey());
                    out.writeLong(entry.getLongValue());
                    out.writeLong(owed.get(entry.getLongKey()) & entry.getLongValue());
                }
            }
            try {
                Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LSSLogger.warn("Failed to save the map record " + file, e);
            try { Files.deleteIfExists(tmpFile); } catch (IOException e2) {
                LSSLogger.warn("Failed to clean up temporary map record " + tmpFile, e2);
            }
        }
    }

    private static void setBit(Long2LongOpenHashMap masks, long packed) {
        long key = ColumnStateMap.leafKeyFor(packed);
        masks.put(key, masks.get(key) | (1L << ColumnStateMap.bitIndexFor(packed)));
    }

    private static void clearBit(Long2LongOpenHashMap masks, long packed) {
        long key = ColumnStateMap.leafKeyFor(packed);
        long mask = masks.get(key);
        if (mask == 0) return;
        mask &= ~(1L << ColumnStateMap.bitIndexFor(packed));
        if (mask == 0) masks.remove(key); else masks.put(key, mask);
    }

    /** Keeps only the bits of columns {@code stamps} holds (a positive stamp). */
    private static void retainHeld(Long2LongOpenHashMap masks, Long2LongOpenHashMap stamps) {
        var iter = masks.long2LongEntrySet().fastIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            long mask = entry.getLongValue();
            for (long bits = mask; bits != 0; bits &= bits - 1) {
                int bit = Long.numberOfTrailingZeros(bits);
                if (stamps.get(ColumnStateMap.positionFor(entry.getLongKey(), bit)) <= 0) mask &= ~(1L << bit);
            }
            if (mask == 0) iter.remove(); else entry.setValue(mask);
        }
    }

    // A debt that returns for a dimension the client has left (a map let go of a column
    // after the dimension's state was saved): set in that dimension's record on the IO
    // thread, coalesced like the removals below.
    private static final HashMap<String, LongOpenHashSet> PENDING_OWED = new HashMap<>();

    /** Marks a held column of a saved dimension as owed to the map. Any thread. */
    public static void markMapOwedAsync(String serverAddress, ResourceKey<Level> dimension, long packed) {
        String key = sanitizeForFilePath(serverAddress) + "/" + dimensionKey(dimension);
        synchronized (REMOVAL_LOCK) {
            var pending = PENDING_OWED.get(key);
            if (pending != null) {
                pending.add(packed);
                return;
            }
            var fresh = new LongOpenHashSet();
            fresh.add(packed);
            PENDING_OWED.put(key, fresh);
        }
        IO_EXECUTOR.execute(() -> {
            LongOpenHashSet batch;
            synchronized (REMOVAL_LOCK) {
                batch = PENDING_OWED.remove(key);
            }
            if (batch == null || batch.isEmpty()) return;
            try {
                applyMapOwed(serverAddress, dimension, batch);
            } catch (RuntimeException e) {
                LSSLogger.warn("Failed to update the map record for " + dimensionKey(dimension), e);
            }
        });
    }

    /** IO-thread body of {@link #markMapOwedAsync}; package-visible for inline tests. */
    static void applyMapOwed(String serverAddress, ResourceKey<Level> dimension, LongOpenHashSet positions) {
        var stamps = load(serverAddress, dimension);
        if (stamps.isEmpty()) return;
        var record = loadMapOwed(serverAddress, dimension);
        var known = record == null ? new Long2LongOpenHashMap() : record.known();
        var owed = record == null ? new Long2LongOpenHashMap() : record.owed();
        boolean changed = false;
        var iter = positions.iterator();
        while (iter.hasNext()) {
            long packed = iter.nextLong();
            if (stamps.get(packed) <= 0) continue; // not held any more: nothing is owed
            setBit(known, packed);
            setBit(owed, packed);
            changed = true;
        }
        // New debt: the flags stand, but the player is told again (see MapFill.hinted).
        if (changed) writeMapOwed(serverAddress, dimension, record != null && record.fillRequested(), false, known, owed);
    }

    /** Test-only queue-a-plain-save helper (FIFO/flush ordering tests). NOT for production:
     *  a plain overwrite discards every file entry the movement prune dropped from memory —
     *  the sliding-disc truncation F2 removed. Production saves go through
     *  {@link #mergeSaveAsync}; keep this package-private so a caller can't drift back. */
    static void saveAsync(String serverAddress, ResourceKey<Level> dimension, Long2LongOpenHashMap columns) {
        if (columns.isEmpty()) return;
        // Defensive copy so the caller can mutate the original freely
        var copy = new Long2LongOpenHashMap(columns);
        copy.defaultReturnValue(-1L);
        IO_EXECUTOR.execute(() -> save(serverAddress, dimension, copy));
    }

    public static void save(String serverAddress, ResourceKey<Level> dimension, Long2LongOpenHashMap columns) {
        write(serverAddress, dimension, columns);
    }

    /** The {@link #save} body; true once the file is in place. */
    private static boolean write(String serverAddress, ResourceKey<Level> dimension, Long2LongOpenHashMap columns) {
        if (columns.isEmpty()) return false;
        SAVE_WRITES_FOR_TEST.incrementAndGet();

        var file = getCacheFile(serverAddress, dimension);
        var tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            // Buffered: unbuffered per-writeLong 8-byte syscalls made big-cache disconnect
            // flushes take seconds (same pattern as the server-side half of issue #62).
            try (var out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(tmpFile), IO_BUFFER_BYTES))) {
                out.writeInt(FORMAT_VERSION);
                out.writeInt(columns.size());
                for (var entry : columns.long2LongEntrySet()) {
                    out.writeLong(entry.getLongKey());
                    out.writeLong(entry.getLongValue()); // raw timestamp
                }
            }
            try {
                Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Filesystems without atomic rename (some network mounts — same fallback as
                // settings persistence): a torn file on crash is tolerable, the loader discards it.
                Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
            LSSLogger.info("Saved " + columns.size() + " cached column entries for " + dimensionKey(dimension));
            return true;
        } catch (IOException e) {
            LSSLogger.warn("Failed to save column cache to " + file, e);
            try { Files.deleteIfExists(tmpFile); } catch (IOException e2) {
                LSSLogger.warn("Failed to clean up temporary cache file " + tmpFile, e2);
            }
            return false;
        }
    }

    /**
     * Merge-save on the IO thread: overlays the in-memory map onto the FILE's current
     * contents instead of overwriting it. The movement prune bounds the in-memory map to a
     * disc around the player, so a plain overwrite silently truncated the persisted cache
     * to "near where you last stood" — every previously-visited region re-declared ts=-1 on
     * revisit, re-downloading in full AND treating deliveries as first serves (no air-fill),
     * which re-opened the ghost-terrain hole the delivery-honesty work closed. Order:
     * existing file (a corrupt/oversized/old-version file degrades to empty — merge becomes
     * a plain overwrite, never a failed save) → delete {@code removals} (the session's
     * deliberate unstamps; applied to the file BEFORE the overlay so a re-received position
     * survives) → overlay memory (memory wins) → evict farthest-first past the cap (the
     * loader discards oversized files wholesale). Both inputs are snapshotted on the caller
     * thread. Safe against the coalesced removal drain ONLY because both run on the single
     * FIFO IO thread — parallelizing this executor would corrupt the load-modify-write.
     */
    public static void mergeSaveAsync(String serverAddress, ResourceKey<Level> dimension,
                                      Long2LongOpenHashMap columns, LongOpenHashSet removals,
                                      int centerCx, int centerCz) {
        if (columns.isEmpty() && (removals == null || removals.isEmpty())) return;
        // Defensive copies so the caller can mutate the originals freely
        var mapCopy = new Long2LongOpenHashMap(columns);
        mapCopy.defaultReturnValue(-1L);
        var removalsCopy = removals == null ? new LongOpenHashSet() : new LongOpenHashSet(removals);
        boolean mapOnly = mapOnlyProbe.getAsBoolean();
        IO_EXECUTOR.execute(() -> mergeSave(serverAddress, dimension, mapCopy, removalsCopy,
                centerCx, centerCz, mapOnly));
    }

    /**
     * The ownership-transfer save (the A2 fix, quadtree-client-state-plan.md §3.4):
     * consumes a {@link ColumnStateMap#detachForSave() detached} state — no PER-ENTRY
     * caller-thread copy (the detach's O(leaves) map-shell copy is ~16-31k reference
     * puts, sub-ms), where {@link #mergeSaveAsync} pays a 1-2M-entry map copy on the
     * render thread (est. 50-150 ms at every dimension change). The overlay map is synthesized
     * from the leaves ON THE IO THREAD and then flows through the unchanged
     * {@link #mergeSave} body (file merge → removals → overlay → evict → write).
     */
    public static void mergeSaveDetachedAsync(String serverAddress, ResourceKey<Level> dimension,
                                              ColumnStateMap.DetachedState state,
                                              int centerCx, int centerCz) {
        if (state.isEmpty()) return;
        boolean mapOnly = mapOnlyProbe.getAsBoolean();
        IO_EXECUTOR.execute(() -> mergeSaveDetached(serverAddress, dimension, state, centerCx, centerCz, mapOnly));
    }

    /** IO-thread body of {@link #mergeSaveDetachedAsync}; package-visible for inline tests. */
    static void mergeSaveDetached(String serverAddress, ResourceKey<Level> dimension,
                                  ColumnStateMap.DetachedState state,
                                  int centerCx, int centerCz) {
        mergeSaveDetached(serverAddress, dimension, state, centerCx, centerCz, false);
    }

    static void mergeSaveDetached(String serverAddress, ResourceKey<Level> dimension,
                                  ColumnStateMap.DetachedState state,
                                  int centerCx, int centerCz, boolean mapOnly) {
        var overlay = new Long2LongOpenHashMap(Math.max(16, state.entries));
        overlay.defaultReturnValue(-1L);
        state.forEachPresent(overlay::put);
        MapDebtSave debt = null;
        if (state.mapDebt != null) {
            var present = new Long2LongOpenHashMap();
            var owed = new Long2LongOpenHashMap();
            var unknown = new Long2LongOpenHashMap();
            state.forEachLeafMasks((leafKey, presentMask, owedMask, unknownMask) -> {
                present.put(leafKey, presentMask);
                if (owedMask != 0) owed.put(leafKey, owedMask);
                if (unknownMask != 0) unknown.put(leafKey, unknownMask);
            });
            debt = new MapDebtSave(state.mapDebt, present, owed, unknown, state.mapSettled);
        }
        mergeSave(serverAddress, dimension, overlay, state.removals, centerCx, centerCz, mapOnly, debt);
    }

    /** IO-thread body of {@link #mergeSaveAsync}; package-visible so tests can run it inline. */
    static void mergeSave(String serverAddress, ResourceKey<Level> dimension,
                          Long2LongOpenHashMap columns, LongOpenHashSet removals,
                          int centerCx, int centerCz) {
        mergeSave(serverAddress, dimension, columns, removals, centerCx, centerCz, false);
    }

    /** @param mapOnly the saving session's only readers were map backends (see {@link #mapOnlyProbe}) */
    static void mergeSave(String serverAddress, ResourceKey<Level> dimension,
                          Long2LongOpenHashMap columns, LongOpenHashSet removals,
                          int centerCx, int centerCz, boolean mapOnly) {
        mergeSave(serverAddress, dimension, columns, removals, centerCx, centerCz, mapOnly, null);
    }

    /** @param debt the saving session's record of the map's debt; null leaves the record on
     *              disk untouched (the forms above: tests and the plain cache paths) */
    static void mergeSave(String serverAddress, ResourceKey<Level> dimension,
                          Long2LongOpenHashMap columns, LongOpenHashSet removals,
                          int centerCx, int centerCz, boolean mapOnly, MapDebtSave debt) {
        Path marker = mapOnlyMarker(serverAddress, dimension);
        boolean marked = Files.exists(marker);
        if (mapOnly && !marked) {
            // Marked BEFORE the stamps land: a crash in between must never leave map-only
            // stamps unmarked on disk.
            try {
                Files.createDirectories(marker.getParent());
                Files.write(marker, new byte[0]);
            } catch (IOException e) {
                LSSLogger.warn("Failed to mark the column cache for " + dimensionKey(dimension) + " as map-only", e);
            }
        }
        // A session with another reader never inherits a map-only file's stamps: they would
        // answer up_to_date for terrain only the map received (its own load ignored them too).
        var merged = !mapOnly && marked ? emptyStamps() : load(serverAddress, dimension);
        // The map's debt for the columns of the FILE, before the overlay: the record on
        // disk. An ignored map-only file brings no record, like its stamps. A column the
        // record does not know stays unknown, unless a fill was asked for: then it is owed.
        Long2LongOpenHashMap knownMasks = null;
        Long2LongOpenHashMap owedMasks = null;
        boolean fillRequested = false;
        boolean hinted = false;
        if (debt != null) {
            knownMasks = new Long2LongOpenHashMap();
            owedMasks = new Long2LongOpenHashMap();
            if (mapOnly || !marked) {
                MapOwedFile record = null;
                try {
                    record = loadMapOwed(serverAddress, dimension);
                } catch (RuntimeException e) {
                    LSSLogger.warn("The map record for " + dimensionKey(dimension) + " could not be read; replacing it", e);
                }
                if (record != null) {
                    knownMasks.putAll(record.known());
                    owedMasks.putAll(record.owed());
                    fillRequested = record.fillRequested();
                    hinted = record.hinted();
                }
                if (debt.flags().fileUnknownBecomeOwed()) {
                    for (var entry : merged.long2LongEntrySet()) {
                        long packed = entry.getLongKey();
                        if (entry.getLongValue() <= 0) continue;
                        long leafKey = ColumnStateMap.leafKeyFor(packed);
                        long bit = 1L << ColumnStateMap.bitIndexFor(packed);
                        if ((knownMasks.get(leafKey) & bit) == 0) {
                            knownMasks.put(leafKey, knownMasks.get(leafKey) | bit);
                            owedMasks.put(leafKey, owedMasks.get(leafKey) | bit);
                        }
                    }
                }
            }
            if (debt.flags().fillRequested() != null) fillRequested = debt.flags().fillRequested();
            if (debt.flags().hinted() != null) hinted = debt.flags().hinted();
        }
        var removalIter = removals.iterator();
        while (removalIter.hasNext()) {
            long removed = removalIter.nextLong();
            merged.remove(removed);
            if (owedMasks != null) {
                clearBit(owedMasks, removed);
                clearBit(knownMasks, removed);
            }
        }
        merged.putAll(columns);
        if (owedMasks != null) {
            // Debts the session settled, also of columns it no longer holds (the movement
            // prune): without this a debt paid by a map fill, whose stamp did not change,
            // would come back from the record for every column the player walked away from.
            for (var entry : debt.settled().long2LongEntrySet()) {
                long key = entry.getLongKey();
                long settled = entry.getLongValue();
                if (settled == 0) continue;
                long owed = owedMasks.get(key) & ~settled;
                if (owed == 0) owedMasks.remove(key); else owedMasks.put(key, owed);
                knownMasks.put(key, knownMasks.get(key) | settled);
            }
            // For every column the saving session holds: what it owes is owed (what it
            // settled was cleared just above), and it is known unless the session itself
            // holds it as unknown.
            for (var entry : debt.present().long2LongEntrySet()) {
                long key = entry.getLongKey();
                long present = entry.getLongValue();
                long owed = owedMasks.get(key) | debt.owed().get(key);
                long known = (knownMasks.get(key) & ~present) | (present & ~debt.unknown().get(key));
                if (owed == 0) owedMasks.remove(key); else owedMasks.put(key, owed);
                if (known == 0) knownMasks.remove(key); else knownMasks.put(key, known);
            }
        }
        if (merged.size() > mergeEvictionCap) {
            merged = evictFarthest(merged, centerCx, centerCz, mergeEvictionCap);
        }
        if (owedMasks != null) {
            // Evicted and removed columns are not in the record: they owe nothing, and
            // if they come back they are told apart from columns the record knows.
            retainHeld(knownMasks, merged);
            retainHeld(owedMasks, merged);
            // A fill that was asked for stands until this cache owes the map nothing.
            if (owedMasks.isEmpty()) fillRequested = false;
        }
        boolean written;
        if (merged.isEmpty()) {
            // save() skips empty maps; a removals-only merge that emptied the file must
            // actually delete it, or the dishonest stamps survive.
            written = deleteCacheFile(serverAddress, dimension);
        } else {
            written = write(serverAddress, dimension, merged);
        }
        if (!mapOnly && marked && written) {
            try {
                Files.deleteIfExists(marker);
            } catch (IOException e) {
                LSSLogger.warn("Failed to clear the map-only mark of the column cache for " + dimensionKey(dimension), e);
            }
        }
        if (debt != null && written) {
            // After the stamps, so the record never describes columns the file does not
            // hold. A crash between the two leaves the older record: what this save added
            // is then unknown to it (never "on the map"), and a debt it paid may be asked
            // for the map once more.
            if (merged.isEmpty()) {
                try {
                    Files.deleteIfExists(mapOwedFile(serverAddress, dimension));
                } catch (IOException e) {
                    LSSLogger.warn("Failed to delete the map record for " + dimensionKey(dimension), e);
                }
            } else {
                writeMapOwed(serverAddress, dimension, fillRequested, hinted, knownMasks, owedMasks);
            }
        }
    }

    private static Long2LongOpenHashMap emptyStamps() {
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        return map;
    }

    /**
     * Keep the {@code cap} entries nearest (Chebyshev) to the save-time player position —
     * the in-memory disc is within prune radius of it, so the hot working set always
     * survives; what falls off is the farthest history, which a revisit re-downloads
     * honestly. Primitive sort (no boxing); ~2M keys is a few hundred ms on the FIFO IO
     * thread, and only when the cap is actually hit.
     */
    private static Long2LongOpenHashMap evictFarthest(Long2LongOpenHashMap merged,
                                                      int centerCx, int centerCz, int cap) {
        long[] keys = merged.keySet().toLongArray();
        LongArrays.quickSort(keys, (a, b) -> Integer.compare(
                PositionUtil.chebyshevDistance(PositionUtil.unpackX(a), PositionUtil.unpackZ(a), centerCx, centerCz),
                PositionUtil.chebyshevDistance(PositionUtil.unpackX(b), PositionUtil.unpackZ(b), centerCx, centerCz)));
        var kept = new Long2LongOpenHashMap(cap);
        kept.defaultReturnValue(-1L);
        for (int i = 0; i < cap; i++) {
            kept.put(keys[i], merged.get(keys[i]));
        }
        return kept;
    }

    private static boolean deleteCacheFile(String serverAddress, ResourceKey<Level> dimension) {
        try {
            Files.deleteIfExists(getCacheFile(serverAddress, dimension));
            return true;
        } catch (IOException e) {
            LSSLogger.warn("Failed to delete emptied column cache for " + dimensionKey(dimension), e);
            return false;
        }
    }

    // Coalesced cross-dimension removals: (sanitized server dir, sanitized dimension) → queued
    // positions. Keyed on the SANITIZED strings so key equality matches file identity. Guarded
    // by REMOVAL_LOCK; drained on the FIFO IO thread (one load-modify-write per burst).
    private static final Object REMOVAL_LOCK = new Object();
    private static final HashMap<String, LongOpenHashSet> PENDING_REMOVALS = new HashMap<>();

    /**
     * Remove positions from a dimension's persisted cache on the IO thread. Used for a
     * cross-dimension ingest-failure report: the failing column's stamp is already saved in the
     * OTHER dimension's cache (the report arrived after the player changed dimension, so its
     * in-memory state map is gone), and leaving it would answer a false up_to_date next time
     * that dimension is visited. Unconditional (like the same-dimension unstamp): a legitimate
     * re-serve since then just costs one harmless re-request on the next visit.
     *
     * <p>Coalesced: a burst of reports (a consumer whose storage hiccups across a portal
     * transition can emit dozens in one tick) queues into one pending set per dimension and
     * pays ONE whole-file load+rewrite when the drain runs — each call used to rewrite a
     * potentially multi-MB file by itself, serialized ahead of the new dimension's cache
     * load on the single IO thread (which gates scanning).
     */
    public static void removeAsync(String serverAddress, ResourceKey<Level> dimension, long packed) {
        String key = sanitizeForFilePath(serverAddress) + "/" + dimensionKey(dimension);
        synchronized (REMOVAL_LOCK) {
            var pending = PENDING_REMOVALS.get(key);
            if (pending != null) {
                pending.add(packed); // an already-queued drain picks this up
                return;
            }
            var fresh = new LongOpenHashSet();
            fresh.add(packed);
            PENDING_REMOVALS.put(key, fresh);
        }
        IO_EXECUTOR.execute(() -> {
            LongOpenHashSet batch;
            synchronized (REMOVAL_LOCK) {
                batch = PENDING_REMOVALS.remove(key);
            }
            if (batch == null || batch.isEmpty()) return;
            var map = load(serverAddress, dimension); // reuses the FIFO IO thread's file access
            boolean changed = false;
            var iter = batch.iterator();
            while (iter.hasNext()) {
                changed |= map.remove(iter.nextLong()) != map.defaultReturnValue();
            }
            if (!changed) return; // none were present
            if (map.isEmpty()) {
                // save() skips empty maps, so delete the now-empty file directly.
                deleteCacheFile(serverAddress, dimension);
            } else {
                save(serverAddress, dimension, map);
            }
        });
    }

    /**
     * Another store keeping files FLAT inside the buckets (the Xaero band-fill column store,
     * {@code <dim>.xfill.<n>}) — it rides this cache's keying and is deleted with every bucket
     * clear. It closes its files before a clear (Windows cannot delete an open file) and may
     * reopen after it.
     */
    public interface BucketTenant {
        void beforeClear();

        void afterClear();
    }

    private static final java.util.concurrent.CopyOnWriteArrayList<BucketTenant> TENANTS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addBucketTenant(BucketTenant tenant) {
        TENANTS.add(tenant);
    }

    public static void removeBucketTenant(BucketTenant tenant) {
        TENANTS.remove(tenant);
    }

    private static void tenantsBeforeClear() {
        for (var tenant : TENANTS) {
            try {
                tenant.beforeClear();
            } catch (RuntimeException e) {
                LSSLogger.warn("A column-cache tenant failed to close before a clear", e);
            }
        }
    }

    private static void tenantsAfterClear() {
        for (var tenant : TENANTS) {
            try {
                tenant.afterClear();
            } catch (RuntimeException e) {
                LSSLogger.warn("A column-cache tenant failed to resume after a clear", e);
            }
        }
    }

    /** A bucket's directory (tenants place their flat files there). */
    public static Path bucketDir(String bucket) {
        return getServerDir(bucket);
    }

    /** The cache root (tenants' cross-bucket accounting). */
    public static Path cacheRootPath() {
        return cacheDir();
    }

    /** A dimension's file stem inside a bucket. */
    public static String dimensionFileKey(ResourceKey<Level> dimension) {
        return dimensionKey(dimension);
    }

    public static void clearForServer(String serverAddress) {
        tenantsBeforeClear();
        try {
            clearForServerNow(serverAddress);
        } finally {
            tenantsAfterClear();
        }
    }

    private static void clearForServerNow(String serverAddress) {
        // Run on the IO thread (serialized FIFO with in-flight saves/loads so a queued save can't
        // re-create the files we delete) and wait, to preserve the synchronous clear contract.
        runIoAndWait(() -> {
            var dir = getServerDir(serverAddress);
            if (!Files.exists(dir)) return;
            try {
                deleteBucketDir(dir);
                LSSLogger.info("Cleared column cache for server " + serverAddress);
            } catch (IOException e) {
                LSSLogger.warn("Failed to clear column cache for " + serverAddress, e);
            }
        });
    }

    /**
     * The two-axis reset sweep (cache-alias-keying-and-reset-override-plan.md §2.4):
     * for every ADDRESS COMPONENT given (already sanitized/escaped — see
     * {@code CacheKeyAliases.addressComponent}; sanitization is idempotent on its own
     * output so a raw spelling degrades safely), delete the bare bucket AND every
     * sibling whose entry NAME matches {@code <component>.world-<16 lowercase hex>}
     * anchored ({@link WorldSubKey#SIBLING_SUFFIX}). Member matching is
     * CASE-INSENSITIVE against the normalized spellings so historically capitalized
     * buckets are swept too. ONE IO task, one bounded wait — not N 30 s waits — with a
     * per-entry try/catch so one undeletable bucket cannot strand the rest. The store
     * learns the suffix CONVENTION here, never alias semantics: which components to
     * sweep is entirely the caller's decision. Entry deletion is
     * NON-FOLLOWING (files then dir, no recursion — a symlinked entry is deleted as a
     * link, its foreign target tree untouched; {@link #deleteBucketDir}, shared with
     * {@link #clearForServer} and {@link #clearAll}).
     */
    public static void clearForServers(java.util.Collection<String> addressComponents) {
        clearForServers(addressComponents, false);
    }

    /** DH cannot hand off terrain while old native receipts survive a failed sweep. */
    public static void clearForServersStrict(java.util.Collection<String> addressComponents) {
        clearForServers(addressComponents, true);
    }

    private static void clearForServers(java.util.Collection<String> addressComponents, boolean strict) {
        if (addressComponents.isEmpty()) return;
        tenantsBeforeClear();
        try {
            clearForServersNow(addressComponents, strict);
        } finally {
            tenantsAfterClear();
        }
    }

    private static void clearForServersNow(java.util.Collection<String> addressComponents, boolean strict) {
        var targets = new java.util.HashSet<String>();
        for (String component : addressComponents) {
            if (component != null && !component.isBlank()) {
                targets.add(sanitizeForFilePath(component).toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (targets.isEmpty()) return;
        runIoAndWait(() -> {
            if (strict ? Files.notExists(cacheDir(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    : !Files.isDirectory(cacheDir())) return;
            int cleared = 0;
            IOException failure = null;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(cacheDir())) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    var tail = dev.vox.lss.seed.WorldSubKey.SIBLING_SUFFIX.matcher(lower);
                    String stem = tail.find() ? lower.substring(0, tail.start()) : lower;
                    if (!targets.contains(lower) && !targets.contains(stem)) continue;
                    try {
                        deleteBucketDir(entry);
                        cleared++;
                    } catch (IOException e) {
                        failure = e;
                        LSSLogger.warn("Failed to clear column cache bucket " + name, e);
                    }
                }
            } catch (IOException e) {
                failure = e;
                LSSLogger.warn("Failed to enumerate the column cache for the reset sweep", e);
            }
            LSSLogger.info("Cleared " + cleared + " column cache bucket(s) for "
                    + addressComponents.size() + " server spelling(s)");
            if (strict && failure != null) throw new java.io.UncheckedIOException(failure);
        }, strict);
    }

    /** Delete one bucket directory: files, then the dir. Non-recursive on purpose —
     *  buckets hold flat per-dimension files, and a planted subdirectory (which this
     *  store never creates) fails the final delete instead of being walked. NON-FOLLOWING
     *  (panel fix): a symlink planted in the cache root is deleted as a LINK — its
     *  foreign target tree survives untouched (the {@code wipeVoxyStore} discipline;
     *  {@code Files.isDirectory} follows links by default, which would have emptied the
     *  target's top level before unlinking). */
    private static void deleteBucketDir(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir)
                || !Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(dir);
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                // Iteration-order independent (2026-09-05: the GitHub runner image started
                // listing a planted subdirectory FIRST, and the DirectoryNotEmptyException it
                // threw here left the bucket's own files behind): a subdirectory is skipped
                // here and refused by the final delete below, so the flat files always go and
                // the subtree is still never walked. NOFOLLOW — a symlinked entry is a link.
                if (Files.isDirectory(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(dir);
    }

    /**
     * World-bucket preparation (plan §2.3), queued by the manager BEFORE the session's
     * first load so the single-FIFO IO thread orders it ahead of every read — and
     * behind any PRIOR session's queued saves, which therefore land in the bare bucket
     * and are carried by the move.
     *
     * <p>Two jobs, both no-ops when the seeded bucket already exists:
     * <ul>
     *   <li><b>Legacy adoption, once EVER per address component</b>: a bare bucket with
     *       NO {@code .world-*} sibling of any seed is MOVED into this seed's bucket —
     *       the warm upgrade path. The once-ever condition (any sibling present blocks
     *       it) is what keeps a reseed from swallowing lobby/seedless residue into each
     *       new world's bucket (§9 M-A2). A failed move logs once and the session runs
     *       on the empty seeded bucket — never the bare one — retrying next session.</li>
     *   <li><b>The sibling cap</b>: creating a sibling beyond {@value #MAX_WORLD_SIBLINGS}
     *       per component deletes the oldest (by last-modified) with one WARN naming the
     *       server seed-unstable — the AntiSeedCracker-family per-login randomizers
     *       would otherwise mint one bucket per join, unbounded (§9 fold).</li>
     * </ul>
     */
    public static void prepareWorldBucketAsync(String addressComponent, String subKey,
                                               boolean allowAdoption) {
        IO_EXECUTOR.execute(() -> {
            try {
                prepareWorldBucket(addressComponent, subKey, allowAdoption);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) throw vme;
                LSSLogger.warn("World-bucket preparation failed for " + addressComponent, t);
            }
        });
    }

    /** Cap on {@code .world-*} sibling buckets per address component (plan §2.3). */
    static final int MAX_WORLD_SIBLINGS = 8;

    /** Test observability: the last seed-unstable cap WARN (null = none since reset). */
    static volatile String lastCapWarnForTest;

    /** IO-thread body of {@link #prepareWorldBucketAsync}; package-visible for inline
     *  tests. {@code allowAdoption} is the SAME-SESSION residue guard (panel fix): a
     *  session that already used the bare bucket (a seedless lobby leg before this
     *  world) must not adopt its own residue into the new world's bucket — that would
     *  re-open exactly the stale-stamps hole the world axis closes. */
    static void prepareWorldBucket(String addressComponent, String subKey,
                                   boolean allowAdoption) {
        String bareName = sanitizeForFilePath(addressComponent);
        Path seeded = cacheDir().resolve(bareName + "." + sanitizeForFilePath(subKey));
        if (Files.isDirectory(seeded)) return;
        Path bare = cacheDir().resolve(bareName);
        var siblings = worldSiblingsOf(bareName);
        if (allowAdoption && siblings.isEmpty() && Files.isDirectory(bare)) {
            try {
                Files.move(bare, seeded);
                LSSLogger.info("Adopted the existing column cache for " + addressComponent
                        + " into its world bucket " + seeded.getFileName()
                        + " (one-time upgrade — stamps stay warm)");
            } catch (IOException e) {
                LSSLogger.info("Could not adopt the existing column cache for "
                        + addressComponent + " into " + seeded.getFileName()
                        + " — starting the world bucket empty; adoption retries next session ("
                        + e + ")");
            }
            return;
        }
        if (siblings.size() >= MAX_WORLD_SIBLINGS) {
            siblings.sort(java.util.Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return Long.MIN_VALUE; // unreadable mtime = oldest candidate
                }
            }));
            int excess = siblings.size() - (MAX_WORLD_SIBLINGS - 1);
            for (int i = 0; i < excess; i++) {
                try {
                    deleteBucketDir(siblings.get(i));
                } catch (IOException e) {
                    LSSLogger.warn("Failed to evict old world bucket "
                            + siblings.get(i).getFileName(), e);
                }
            }
            String warn = "Server " + addressComponent + " has minted more than "
                    + MAX_WORLD_SIBLINGS + " world cache buckets — its seed looks UNSTABLE "
                    + "(per-login seed randomization?); oldest bucket(s) evicted. The warm "
                    + "cache cannot work against a rotating seed; consider "
                    + "useWorldSubBuckets=false for this install";
            lastCapWarnForTest = warn;
            LSSLogger.warn(warn);
        }
    }

    /** The {@code <component>.world-<hex>} siblings currently on disk. CASE-INSENSITIVE
     *  on the component (panel fix, aligned with the sweep's member matching): bucket
     *  names preserve the raw address's case, and on the case-insensitive filesystems
     *  most clients run (Windows, default macOS) two case spellings share one
     *  namespace — a case-split sibling count would let the cap grow unbounded and the
     *  once-ever adoption re-fire. */
    private static java.util.List<Path> worldSiblingsOf(String bareName) {
        var out = new java.util.ArrayList<Path>();
        if (!Files.isDirectory(cacheDir())) return out;
        String prefix = bareName.toLowerCase(java.util.Locale.ROOT) + ".";
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(cacheDir())) {
            for (Path entry : entries) {
                String lower = entry.getFileName().toString()
                        .toLowerCase(java.util.Locale.ROOT);
                if (!lower.startsWith(prefix)) continue;
                String rest = lower.substring(prefix.length() - 1);
                if (dev.vox.lss.seed.WorldSubKey.SIBLING_SUFFIX.matcher(rest).matches()) {
                    out.add(entry);
                }
            }
        } catch (IOException e) {
            LSSLogger.warn("Failed to enumerate world buckets for " + bareName, e);
        }
        return out;
    }

    public static void clearAll() {
        tenantsBeforeClear();
        try {
            clearAllNow();
        } finally {
            tenantsAfterClear();
        }
    }

    private static void clearAllNow() {
        runIoAndWait(() -> {
            if (!Files.exists(cacheDir(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            IOException failure=null;
            try {dev.vox.lss.common.dh.DhReceiptStore.clear(cacheDir(), null);}
            catch(IOException e) {failure=e;}
            try (DirectoryStream<Path> servers = Files.newDirectoryStream(cacheDir())) {
                for (Path serverDir : servers) {
                    // DH receipts own this namespace and preserve foreign children.
                    if(serverDir.getFileName().toString().equals(dev.vox.lss.common.dh.DhReceiptStore.DIRECTORY))continue;
                    if (!Files.isDirectory(serverDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(serverDir)) continue;
                    try {deleteBucketDir(serverDir);}catch(IOException e) {failure=e;}
                }
            } catch(IOException e) {failure=e;}
            if(failure!=null) LSSLogger.warn("Failed to clear some column caches",failure);
            else LSSLogger.info("Cleared all column caches");
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

    /** Explicit DH reset only: drop this server's persisted DH receipts. */
    static void clearDhReceipts(String address) {
        runIoAndWait(() -> {
            try { dev.vox.lss.common.dh.DhReceiptStore.clear(cacheDir(), address); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }, true);
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
        runIoAndWait(task, false);
    }

    private static void runIoAndWait(Runnable task, boolean strict) {
        var future = IO_EXECUTOR.submit(task);
        try {
            future.get(ioWaitTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (strict) throw new IllegalStateException("Native cache sweep interrupted", e);
        } catch (ExecutionException e) {
            if (strict) throw new IllegalStateException("Native cache sweep failed", e.getCause());
            LSSLogger.warn("Column cache IO task failed", e.getCause());
        } catch (TimeoutException e) {
            if (strict) throw new IllegalStateException("Native cache sweep timed out", e);
            LSSLogger.warn("Column cache IO wait exceeded " + ioWaitTimeoutMs
                    + "ms — abandoning the wait (the task stays queued on the IO thread)");
        }
    }

    private static Path getServerDir(String serverAddress) {
        return cacheDir().resolve(sanitizeForFilePath(serverAddress));
    }

    private static Path getCacheFile(String serverAddress, ResourceKey<Level> dimension) {
        return getServerDir(serverAddress).resolve(dimensionKey(dimension) + ".bin");
    }

    static Path mapOnlyMarker(String serverAddress, ResourceKey<Level> dimension) {
        return getServerDir(serverAddress).resolve(dimensionKey(dimension) + ".maponly");
    }

    private static String dimensionKey(ResourceKey<Level> dimension) {
        return sanitizeForFilePath(dimension.identifier().toString());
    }

    static String sanitizeForFilePath(String name) {
        String sanitized = SANITIZE_PATTERN.matcher(name).replaceAll("_");
        // The pattern allows dots, so a name that is ENTIRELY dots ("." / "..") survives as a
        // path-walking segment — the embedded case is neutralized by the separator
        // substitution, but a bare ".." would resolve one level above the cache dir (and a
        // bare "." / empty result into it). Collapse those to a plain segment.
        if (sanitized.isEmpty() || sanitized.chars().allMatch(c -> c == '.')) return "_";
        return sanitized;
    }
}
