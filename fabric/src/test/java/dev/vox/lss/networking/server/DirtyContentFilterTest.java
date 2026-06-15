package dev.vox.lss.networking.server;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hash-store behavior of {@link DirtyContentFilter}: all-air null-byte tolerance (a server-tick
 * crash found by the dimension-trip soak — the End void serializes columns to NULL section bytes),
 * the overflow eviction that bounds the per-dimension map, the fail-open contract when
 * serialization throws (via the injected {@code ColumnSerializer} seam), and the 0→1 hash remap
 * that keeps real hashes off the fastutil absent sentinel.
 */
class DirtyContentFilterTest {

    @Test
    void seedToleratesAllAirNullBytes() {
        var filter = new DirtyContentFilter();
        assertDoesNotThrow(() -> {
            filter.seed("minecraft:the_end", 10, 0, null);
            filter.seed("minecraft:the_end", 10, 0, null);
            filter.seed("minecraft:the_end", 10, 0, new byte[0]);
            filter.seed("minecraft:the_end", 11, 0, new byte[]{1, 2, 3});
        });
    }

    /**
     * Overflow eviction self-heals: clearing the map forgets baselines, so the next save of
     * unchanged content re-marks dirty exactly once (one spurious re-send) and then filtering
     * resumes — rather than the map leaking forever or post-clear saves staying silent.
     */
    @Test
    void overflowEvictionTreatsNextSaveAsFirstObservation() {
        var filter = new DirtyContentFilter();
        var dim = "minecraft:overworld";
        var otherDim = "minecraft:the_end";
        long pos = PositionUtil.packPosition(7, -3);
        long hash = 0x1234_5678_9ABC_DEF0L;

        assertTrue(filter.storeHash(dim, pos, hash), "first observation of a position is a change");
        assertFalse(filter.storeHash(dim, pos, hash), "identical re-save is filtered");
        assertTrue(filter.storeHash(otherDim, pos, hash), "same position in another dimension is independent");

        // Fill the dimension to one below the cap (pos already occupies one entry); z=1_000_000
        // keeps fillers distinct from pos.
        for (int i = 0; i < DirtyContentFilter.MAX_ENTRIES_PER_DIMENSION - 2; i++) {
            filter.storeHash(dim, PositionUtil.packPosition(i, 1_000_000), i + 1L);
        }
        assertFalse(filter.storeHash(dim, pos, hash), "still filtering just below the cap (no early eviction)");

        // Reach the cap; the next store clears the dimension wholesale before storing.
        filter.storeHash(dim, PositionUtil.packPosition(-1, 1_000_000), 99L);
        assertTrue(filter.storeHash(dim, pos, hash),
                "post-eviction save of identical content is treated as a first observation");
        assertFalse(filter.storeHash(dim, pos, hash), "filtering resumes after the single self-heal re-mark");

        assertFalse(filter.storeHash(otherDim, pos, hash), "eviction is per-dimension; other baselines survive");
    }

    /**
     * Fail-open contract: hashing is best-effort, so a serialization exception inside
     * contentChanged must count the save as changed — a spurious re-send is harmless, a
     * silently missed update is a permanently stale client. Also pins that the seam is fed
     * the same coordinates the filter keys on.
     */
    @Test
    void serializationExceptionFailsOpenAsChanged() {
        var seen = new int[2];
        var filter = new DirtyContentFilter((level, chunk, cx, cz) -> {
            seen[0] = cx;
            seen[1] = cz;
            throw new IllegalStateException("section read exploded mid-save");
        });

        assertTrue(filter.contentChanged(null, null, 4, -7, "minecraft:overworld"),
                "a serialization exception must fail open as changed");
        assertEquals(4, seen[0], "serializer seam receives the chunk X the filter keys on");
        assertEquals(-7, seen[1], "serializer seam receives the chunk Z the filter keys on");
    }

    /**
     * A transient serialization failure must not corrupt the stored baseline — neither
     * clearing it (the next identical save would re-mark and re-send for nothing) nor
     * recording a poison hash (the next identical save would read as changed).
     */
    @Test
    void serializationExceptionLeavesStoredBaselineUntouched() {
        var dim = "minecraft:overworld";
        byte[] content = {10, 20, 30, 40};
        var boom = new AtomicBoolean(false);
        var filter = new DirtyContentFilter((level, chunk, cx, cz) -> {
            if (boom.get()) {
                throw new RuntimeException("transient hash failure");
            }
            return content;
        });

        assertTrue(filter.contentChanged(null, null, 3, 5, dim), "first observation marks");
        assertFalse(filter.contentChanged(null, null, 3, 5, dim), "identical re-save is quiet");

        boom.set(true);
        assertTrue(filter.contentChanged(null, null, 3, 5, dim), "exception fails open as changed");

        boom.set(false);
        assertFalse(filter.contentChanged(null, null, 3, 5, dim),
                "baseline survives the exception: identical content is still recognized as unchanged");
    }

    /**
     * Regression for the continuous-reload loop the coverage-aware scanner exclusion now relies on
     * being suppressed (SpiralScannerTest#uncoveredCornerInsideExclusionRadiusIsRequested): the LOD
     * corners the client serves are loaded and ticking on the server, so vanilla re-saves them every
     * ~10s for inhabitedTime alone. inhabitedTime is chunk metadata, NOT in the serialized section
     * bytes, so the served LOD content is byte-identical across those re-saves — the filter must
     * report them unchanged so the dirty broadcast never re-requests the corner. A genuine edit
     * still marks.
     */
    @Test
    void metadataOnlyResaveOfAServedCornerIsSuppressed_noReloadLoop() {
        var dim = "minecraft:overworld";
        var liveContent = new java.util.concurrent.atomic.AtomicReference<byte[]>(new byte[]{1, 2, 3, 4});
        var filter = new DirtyContentFilter((level, chunk, cx, cz) -> liveContent.get());
        int cx = 7, cz = -7; // a square-corner column the coverage-aware exclusion now serves

        assertTrue(filter.contentChanged(null, null, cx, cz, dim), "first serve records the baseline");
        for (int i = 0; i < 5; i++) {
            assertFalse(filter.contentChanged(null, null, cx, cz, dim),
                    "metadata-only re-save #" + i + " must stay quiet — re-marking would revive the reload loop");
        }
        liveContent.set(new byte[]{9, 9, 9, 9});
        assertTrue(filter.contentChanged(null, null, cx, cz, dim),
                "a real content change at the same corner still re-marks dirty");
    }

    /**
     * Absent-sentinel coupling: the per-dimension fastutil map uses defaultReturnValue(0), so
     * a stored hash of exactly 0 is indistinguishable from "never observed" — the FIRST save
     * of such content would read as unchanged and the column would never mark dirty. fnv1a64
     * therefore remaps a raw 0 result to 1 (no constructible input has a raw FNV-1a of 0,
     * which is why the remap is pinned in isolation). If any assertion here fails, sentinel
     * and remap have drifted apart — change them together.
     */
    @Test
    void fnvZeroResultStoresAsOneOffTheAbsentSentinel() {
        assertEquals(1L, DirtyContentFilter.remapAbsentSentinel(0L),
                "a raw FNV result of 0 must store as 1, never as the absent sentinel");
        assertEquals(1L, DirtyContentFilter.remapAbsentSentinel(1L),
                "remap target: raw 0 and raw 1 intentionally collide on 1");
        assertEquals(-1L, DirtyContentFilter.remapAbsentSentinel(-1L),
                "non-zero hashes pass through untouched");
        assertEquals(0x9E3779B97F4A7C15L, DirtyContentFilter.remapAbsentSentinel(0x9E3779B97F4A7C15L),
                "non-zero hashes pass through untouched (incl. the all-air sentinel value)");

        var filter = new DirtyContentFilter();
        assertFalse(filter.storeHash("minecraft:overworld", PositionUtil.packPosition(1, 2), 0L),
                "an unremapped 0 hash IS swallowed by the absent sentinel on first observation — "
                        + "the hazard the remap exists for");
    }

    /**
     * Zero-hashing content (stored as the remapped 1) keeps full filter semantics: first
     * observation marks, identical re-save stays quiet, edits in both directions still mark.
     * Fails if the absent sentinel ever moves onto the remap target.
     */
    @Test
    void remappedZeroHashKeepsFirstObservationAndResaveSemantics() {
        var filter = new DirtyContentFilter();
        var dim = "minecraft:the_end";
        long pos = PositionUtil.packPosition(-12, 34);

        assertTrue(filter.storeHash(dim, pos, 1L), "first observation of zero-hashing content marks");
        assertFalse(filter.storeHash(dim, pos, 1L), "identical re-save stays quiet");
        assertTrue(filter.storeHash(dim, pos, 0x1234L), "edit away from zero-hashing content marks");
        assertTrue(filter.storeHash(dim, pos, 1L), "edit back to zero-hashing content marks");
        assertFalse(filter.storeHash(dim, pos, 1L), "filtering resumes on the remapped value");
    }
}
