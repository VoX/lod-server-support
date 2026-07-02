package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Gates dirty-column marking on actual LOD-visible content change.
 *
 * <p>Vanilla re-saves loaded chunks on a ~10s cooldown for metadata-only changes
 * (inhabitedTime ticks every tick a player is in range), so a save-hook-driven dirty
 * tracker re-marks every loaded chunk near a player forever. Each mark triggers a
 * broadcast, a client re-request, and a full column re-send of identical bytes —
 * a measured ~2-5 columns/s/player of pure waste. This filter hashes exactly what LSS
 * serves (the serialized sections + light wire bytes) and lets a save mark dirty only
 * when that content actually changed.
 *
 * <p>Fail-open: serialization errors fall back to "changed" — a spurious re-send is
 * harmless, a missed update is not. Paper needs no twin: its dirty detection is
 * Bukkit-event-driven (block changes), not save-driven.
 *
 * <p>All callers run on the main server thread (ChunkMap.save call sites are main-thread-only
 * in 26.1.2 — verified through saveChunkIfNeeded/scheduleUnload/saveAllChunks roots); the
 * synchronization is cheap insurance against future call sites, not a present need.
 */
public class DirtyContentFilter {
    /** Per-dimension cap; on overflow the map is cleared (chunks re-mark dirty once — self-heals).
     *  Package-visible so the eviction test can fill exactly to the cap. */
    static final int MAX_ENTRIES_PER_DIMENSION = 512 * 1024;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    /** All-air columns serialize to null bytes — valid content, hashed as this sentinel
     *  so air-to-air saves stay quiet and air-to-built transitions mark dirty. */
    private static final long ALL_AIR_HASH = 0x9E3779B97F4A7C15L;

    private final Map<String, Long2LongOpenHashMap> hashesByDimension = new HashMap<>();
    private final ColumnSerializer serializer;

    // Saves whose LOD-visible content matched the stored hash — the metadata-only re-saves this
    // filter exists to suppress (vanilla's ~10s inhabitedTime re-saves). Closes the dirty
    // conservation view: saves-observed == dirty.marked_total + dirty.suppressed_total.
    private long totalSuppressed;

    /** Serializes a column to exactly the section bytes LSS serves (the hash input).
     *  Injectable for tests only — lets the exception fail-open path run without MC
     *  level/chunk objects; production always wires {@link SectionSerializer#serializeColumn}. */
    @FunctionalInterface
    interface ColumnSerializer {
        byte[] serializeSections(ServerLevel level, LevelChunk chunk, int cx, int cz);
    }

    public DirtyContentFilter() {
        this((level, chunk, cx, cz) -> SectionSerializer.serializeColumn(level, chunk, cx, cz).serializedSections());
    }

    /** Test seam (see {@link ColumnSerializer}); zero behavior change when default-wired. */
    DirtyContentFilter(ColumnSerializer serializer) {
        this.serializer = serializer;
    }

    /**
     * Record served content as the change baseline. A serve IS a content observation:
     * seeding here means a generated chunk's first unload-save (identical bytes, since
     * serves and the save hook share the same serializer) no longer re-marks it dirty —
     * which otherwise re-sends every generated column a second time for nothing.
     */
    public synchronized void seed(String dimension, int cx, int cz, byte[] serializedSections) {
        storeHash(dimension, PositionUtil.packPosition(cx, cz), hashContent(serializedSections));
    }

    /**
     * Returns true if the chunk's LOD-visible content differs from the last save we saw
     * (always true for the first observed save of a position), updating the stored hash.
     */
    public synchronized boolean contentChanged(ServerLevel level, LevelChunk chunk, String dimension) {
        return contentChanged(level, chunk, chunk.getPos().x, chunk.getPos().z, dimension);
    }

    /** Position-explicit body; package-visible so tests can drive the injected serializer
     *  without constructing MC level/chunk objects. Synchronized (reentrant from the public
     *  overload) so the seam keeps the class's entry-points-hold-the-lock insurance. */
    synchronized boolean contentChanged(ServerLevel level, LevelChunk chunk, int cx, int cz, String dimension) {
        long hash;
        int lastLen;
        try {
            byte[] sections = this.serializer.serializeSections(level, chunk, cx, cz);
            hash = hashContent(sections);
            lastLen = sections == null ? 0 : sections.length;
        } catch (Exception e) {
            LSSLogger.debug("Dirty-content hash failed for chunk " + cx + "," + cz + ": " + e);
            return true;
        }

        long packed = PositionUtil.packPosition(cx, cz);
        boolean changed = storeHash(dimension, packed, hash);
        if (!changed) this.totalSuppressed++; // metadata-only re-save the filter suppressed
        if (changed && Boolean.getBoolean("lss.soak.dirtydebug")) {
            LSSLogger.info("[DirtyDebug] re-marked " + cx + "," + cz
                    + " hash=" + Long.toHexString(hash) + " len=" + lastLen);
        }
        return changed;
    }

    /** Stores the hash; returns true if it differs from the previous value (or none existed).
     *  Package-visible for testing the overflow-eviction path; synchronized (reentrant from the
     *  public methods) so the seam keeps the class's entry-points-hold-the-lock insurance. */
    synchronized boolean storeHash(String dimension, long packed, long hash) {
        var hashes = this.hashesByDimension.computeIfAbsent(dimension, k -> {
            var map = new Long2LongOpenHashMap();
            map.defaultReturnValue(0L);
            return map;
        });
        if (hashes.size() >= MAX_ENTRIES_PER_DIMENSION) {
            hashes.clear();
        }
        long previous = hashes.put(packed, hash);
        return previous != hash;
    }

    /** Cumulative count of suppressed metadata-only re-saves (see {@link #totalSuppressed}). */
    public synchronized long getTotalSuppressed() {
        return this.totalSuppressed;
    }

    private static long hashContent(byte[] bytes) {
        return bytes == null || bytes.length == 0 ? ALL_AIR_HASH : fnv1a64(bytes);
    }

    private static long fnv1a64(byte[] bytes) {
        long h = FNV_OFFSET;
        for (byte b : bytes) {
            h ^= (b & 0xFF);
            h *= FNV_PRIME;
        }
        return remapAbsentSentinel(h);
    }

    /** 0 is the map's absent sentinel — remap so a real hash never collides with "absent"
     *  (a stored 0 would make the first observation read as unchanged). Package-visible:
     *  no constructible input has a raw FNV-1a of 0, so the remap is only pinnable in isolation. */
    static long remapAbsentSentinel(long h) {
        return h == 0L ? 1L : h;
    }
}
