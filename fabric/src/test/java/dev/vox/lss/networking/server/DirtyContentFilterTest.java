package dev.vox.lss.networking.server;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hash-store behavior of {@link DirtyContentFilter}: all-air null-byte tolerance (a server-tick
 * crash found by the dimension-trip soak — the End void serializes columns to NULL section bytes),
 * the overflow eviction that bounds the per-dimension map, the fail-open contract when
 * serialization throws (via the injected {@code ColumnSerializer} seam), and the 0→1 hash remap
 * that keeps real hashes off the fastutil absent sentinel.
 */
class DirtyContentFilterTest {

    // ---- Phase 3 save-hook deposits: the observation contract ----

    /** observeSave must agree with contentChanged AND hand out the exact serialized
     *  bytes on a change — the save-hook deposit's payload. */
    @Test
    void observeSaveHandsOutBytesOnChangeAndSuppressesUnchanged() {
        byte[] first = {1, 2, 3};
        byte[][] next = {first};
        var filter = new DirtyContentFilter((level, chunk, cx, cz) -> next[0]);
        var obs1 = filter.observeSave(null, null, 4, 5, "minecraft:overworld");
        assertTrue(obs1.changed(), "first observation is a change");
        assertTrue(obs1.depositable());
        assertArrayEquals(first, obs1.sectionBytes(), "the deposit payload is the hashed bytes");

        var obs2 = filter.observeSave(null, null, 4, 5, "minecraft:overworld");
        assertFalse(obs2.changed(), "identical re-save must be suppressed");
        assertNull(obs2.sectionBytes(), "no bytes handed out for a suppressed save");

        next[0] = new byte[]{9, 9};
        var obs3 = filter.observeSave(null, null, 4, 5, "minecraft:overworld");
        assertTrue(obs3.changed());
        assertArrayEquals(new byte[]{9, 9}, obs3.sectionBytes());
    }

    /** All-air columns observe as changed+depositable with NULL bytes (the store maps
     *  that to byte[0]); a serializer exception fails open as changed but NOT
     *  depositable — the caller must delete, never deposit stale/unknown bytes. */
    @Test
    void observeSaveAllAirIsDepositableAndFailOpenIsNot() {
        var allAir = new DirtyContentFilter((level, chunk, cx, cz) -> null);
        var obs = allAir.observeSave(null, null, 1, 1, "minecraft:the_end");
        assertTrue(obs.changed());
        assertTrue(obs.depositable(), "all-air is valid content, depositable as byte[0]");
        assertNull(obs.sectionBytes());

        var throwing = new DirtyContentFilter((level, chunk, cx, cz) -> {
            throw new IllegalStateException("boom");
        });
        var failOpen = throwing.observeSave(null, null, 1, 1, "minecraft:overworld");
        assertTrue(failOpen.changed(), "fail-open must still mark dirty");
        assertFalse(failOpen.depositable(), "unknown bytes must never be deposited");
    }

    /** The two observation APIs share one baseline: a save observed via observeSave
     *  suppresses the same content via contentChanged and vice versa. */
    @Test
    void observeSaveAndContentChangedShareTheBaseline() {
        byte[] bytes = {5, 5, 5};
        var filter = new DirtyContentFilter((level, chunk, cx, cz) -> bytes);
        assertTrue(filter.observeSave(null, null, 2, 2, "minecraft:overworld").changed());
        assertFalse(filter.contentChanged(null, null, 2, 2, "minecraft:overworld"),
                "contentChanged must see observeSave's baseline");
    }

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
     * Overflow eviction is LRU and self-heals (xaero-scatter-remediation-plan.md WI-1b,
     * panel fold): past the per-dimension cap the LEAST-RECENTLY-touched baseline goes —
     * never the whole table (the old wholesale clear re-marked every loaded chunk once) and
     * never a hot one (a plain insertion-ordered put would evict the most re-saved chunks
     * first). The evicted chunk's next save reads as a first observation exactly once, then
     * filtering resumes; other baselines and other dimensions are untouched.
     */
    @Test
    void overflowEvictsTheColdestBaselineOnly() {
        var filter = new DirtyContentFilter();
        var dim = "minecraft:overworld";
        var otherDim = "minecraft:the_end";
        long pos = PositionUtil.packPosition(7, -3);
        long hash = 0x1234_5678_9ABC_DEF0L;

        assertTrue(filter.storeHash(dim, pos, hash), "first observation of a position is a change");
        assertFalse(filter.storeHash(dim, pos, hash), "identical re-save is filtered");
        assertTrue(filter.storeHash(otherDim, pos, hash), "same position in another dimension is independent");

        // Fill the dimension exactly to the cap (pos already occupies one entry); z=1_000_000
        // keeps fillers distinct from pos. Filler 0 is the OLDEST insertion after pos.
        for (int i = 0; i < DirtyContentFilter.MAX_ENTRIES_PER_DIMENSION - 1; i++) {
            filter.storeHash(dim, PositionUtil.packPosition(i, 1_000_000), i + 1L);
        }
        assertEquals(DirtyContentFilter.MAX_ENTRIES_PER_DIMENSION + 1, filter.entryCount(),
                "at the cap (plus the other dimension's one entry) nothing is evicted yet");
        // Touch pos: a re-save moves it to the recently-used end, so it must SURVIVE the
        // eviction the next new insertion triggers — filler 0, the coldest, goes instead.
        assertFalse(filter.storeHash(dim, pos, hash), "still filtering at the cap (no early eviction)");
        filter.storeHash(dim, PositionUtil.packPosition(-1, 1_000_000), 99L);
        assertEquals(DirtyContentFilter.MAX_ENTRIES_PER_DIMENSION + 1, filter.entryCount(),
                "one in, one out: the table stays at the cap");
        assertFalse(filter.storeHash(dim, pos, hash),
                "the recently-touched baseline survives eviction (LRU, not clear-all)");
        assertFalse(filter.storeHash(dim, PositionUtil.packPosition(1, 1_000_000), 2L),
                "filler 1 was not evicted: filtering continues for everything else");
        assertTrue(filter.storeHash(dim, PositionUtil.packPosition(0, 1_000_000), 1L),
                "the coldest baseline (filler 0) was the one evicted: its next save is a first observation");
        assertFalse(filter.storeHash(otherDim, pos, hash), "eviction is per-dimension; other baselines survive");
    }

    /**
     * The chunk-load baseline (xaero-scatter-remediation-plan.md WI-1b Option L): a chunk
     * seeded at LOAD has its first metadata-only save suppressed — the first-observed-save
     * storm's fix — while a genuine content change after the load still marks. Also pins
     * the observability pair the diag line and exporter read (seeded_load / entries).
     */
    @Test
    void seedLoadedSuppressesTheFirstSaveUnlessContentChanged() {
        var live = new java.util.concurrent.atomic.AtomicReference<byte[]>(new byte[]{1, 2, 3});
        var filter = new DirtyContentFilter((level, chunk, cx, cz) -> live.get());
        var dim = "minecraft:overworld";
        filter.seedLoaded(null, null, 3, 4, dim);
        assertEquals(1L, filter.getTotalSeededLoads(), "the load seam counts its baselines");
        assertEquals(1, filter.entryCount(), "entries counts live baselines");
        assertFalse(filter.contentChanged(null, null, 3, 4, dim),
                "a loaded chunk's first save with unchanged content is suppressed");
        assertEquals(1L, filter.getTotalSuppressed());
        live.set(new byte[]{9, 9, 9});
        assertTrue(filter.contentChanged(null, null, 3, 4, dim),
                "a real change after the load still marks");
        assertEquals(1, filter.entryCount(), "an update replaces the baseline, no new entry");
    }

    /** A throwing serializer seeds NOTHING (fail-open by omission): no entry, no count, and
     *  the chunk's first save reads absent → changed — exactly the pre-fix behavior. */
    @Test
    void seedLoadedFailsOpenByOmission() {
        var filter = new DirtyContentFilter((level, chunk, cx, cz) -> {
            throw new IllegalStateException("light engine not ready");
        });
        assertDoesNotThrow(() -> filter.seedLoaded(null, null, 1, 1, "minecraft:overworld"));
        assertEquals(0, filter.entryCount());
        assertEquals(0L, filter.getTotalSeededLoads());
        assertTrue(filter.contentChanged(null, null, 1, 1, "minecraft:overworld"),
                "without a seed the first save is a first observation");
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
     * Regression for the continuous-reload loop the buffered-Euclidean scanner exclusion now relies on
     * being suppressed (SpiralScannerTest#renderSquareCornersBeyondVanillasRoundedViewAreRequested): the LOD
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
        int cx = 7, cz = -7; // a square-corner column the buffered-Euclidean exclusion now serves

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
