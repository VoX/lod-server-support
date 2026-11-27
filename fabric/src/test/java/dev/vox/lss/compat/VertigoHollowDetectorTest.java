package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Issue #437: which unloading vanilla chunks Voxy saved with Vertigo's air in them, read from
 * the chunk itself (docs/planning/2026-11-27-vertigo-hollowed-columns-plan.md §3.1). An
 * overworld of sections -4..19 (Y -64..319).
 */
class VertigoHollowDetectorTest {
    static final int MIN = -4, MAX = 19;

    /** A chunk with surface heights low..high whose sections in `empty` are all air. */
    record View(int minTopY, int maxTopY, Set<Integer> empty) implements VertigoHollowDetector.ChunkView {
        public boolean sectionEmpty(int sectionY) { return empty.contains(sectionY); }
    }

    /** Terrain from Y -64 to 90, sections 6..19 air (sky), plus what Vertigo hid. */
    static View terrain(Integer... hidden) {
        var empty = new java.util.HashSet<>(Set.of(6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19));
        empty.addAll(Set.of(hidden));
        return new View(70, 90, empty);
    }

    static final class World implements VertigoHollowDetector.Chunks {
        final Map<Long, View> chunks = new HashMap<>();
        World put(int x, int z, View v) { chunks.put(VertigoHollowDetector.pack(x, z), v); return this; }
        World disc(int r, View v) { for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) put(x, z, v); return this; }
        void drop(int x, int z) { chunks.remove(VertigoHollowDetector.pack(x, z)); }
        public VertigoHollowDetector.ChunkView chunk(int x, int z) { return chunks.get(VertigoHollowDetector.pack(x, z)); }
    }

    @Test void skyAboveTheSurfaceAndCavesBelowItAreNotHollow() {
        assertFalse(VertigoHollowDetector.hollow(terrain(), MIN, MAX), "air above Y 90 is sky");
        assertFalse(VertigoHollowDetector.hollow(terrain(-4, -3, 0), MIN, MAX),
                "sections empty below the lowest surface block are out of sight from above");
    }

    @Test void anEmptySectionInTheSurfaceBandIsHollow() {
        // Flying high: Vertigo hid sections 4 and 5, which hold the surface (Y 64..95).
        assertTrue(VertigoHollowDetector.hollow(terrain(4, 5), MIN, MAX));
        assertTrue(VertigoHollowDetector.hollow(terrain(5), MIN, MAX), "the highest top block's section");
        assertTrue(VertigoHollowDetector.hollow(terrain(4), MIN, MAX), "the lowest top block's section");
    }

    @Test void theBandIsClampedAndAnEmptyChunkNeverCounts() {
        assertFalse(VertigoHollowDetector.hollow(new View(-65, -65, Set.of(-4, -3)), MIN, MAX), "no block at all");
        assertFalse(VertigoHollowDetector.hollow(new View(400, 400, Set.of()), MIN, MAX), "no section above the world");
        assertFalse(VertigoHollowDetector.hollow(null, MIN, MAX));
    }

    @Test void aFullChunkFlownOverIsNotReported() {
        var detector = new VertigoHollowDetector();
        var world = new World().disc(3, terrain());
        assertFalse(detector.unloading(3, 0, MIN, MAX, world));
        assertEquals(0, detector.suspectCount());
    }

    @Test void aNeighbourHollowWhenItLeavesReadyIsReportedAtItsOwnUnloadEvenIfFullByThen() {
        var detector = new VertigoHollowDetector();
        // Flying high: everything hollow. The edge chunk (3,0) unloads first; (2,0) loses
        // "ready" with it, and Voxy saves (2,0) hollow at that moment.
        var world = new World().disc(3, terrain(4, 5));
        assertTrue(detector.unloading(3, 0, MIN, MAX, world), "the edge itself was hollow");
        world.drop(3, 0);
        assertTrue(detector.suspectCount() > 0);
        // The player dives: Vertigo sends (2,0)'s surface before it unloads. Voxy does not save
        // a section that turns visible, so (2,0) is still hollow in Voxy.
        world.put(2, 0, terrain());
        assertTrue(detector.unloading(2, 0, MIN, MAX, world), "suspect from the neighbour unload");
    }

    @Test void aChunkFullWhenItLeftReadyIsNotReported() {
        var detector = new VertigoHollowDetector();
        var world = new World().disc(3, terrain());
        detector.unloading(3, 0, MIN, MAX, world);
        world.drop(3, 0);
        assertFalse(detector.unloading(2, 0, MIN, MAX, world));
    }

    @Test void aChunkDroppedWithAllItsNeighboursIsJudgedAsItIsNow() {
        // A teleport drops the whole disc: the slots are empty when Sodium's removals run, so
        // what Voxy holds is what it saved at the add: hollow now, as when it arrived.
        var detector = new VertigoHollowDetector();
        var world = new World().put(0, 0, terrain(4, 5));
        assertTrue(detector.unloading(0, 0, MIN, MAX, world));
        assertEquals(0, detector.suspectCount(), "nothing else is loaded");
    }

    @Test void suspectsLeaveAtTheirOwnUnloadAndClearResetsThem() {
        var detector = new VertigoHollowDetector();
        var world = new World().disc(1, terrain(5));
        detector.unloading(1, 1, MIN, MAX, world);
        assertEquals(3, detector.suspectCount(), "(0,0), (1,0) and (0,1) are the loaded neighbours of (1,1)");
        world.drop(1, 1);
        assertTrue(detector.unloading(1, 0, MIN, MAX, world));
        assertEquals(4, detector.suspectCount(), "(1,0) left; (0,-1), (1,-1) joined from its unload");
        detector.clear();
        assertEquals(0, detector.suspectCount());
    }

    @Test void aSilentlyEvictedSuspectIsStillOwed() {
        // A teleport evicts chunks without unload events: the sweep asks whether a neighbour's
        // unload had marked the chunk, and judges it by its content otherwise.
        var detector = new VertigoHollowDetector();
        var world = new World().disc(1, terrain(5));
        detector.unloading(1, 1, MIN, MAX, world);
        assertTrue(detector.forget(VertigoHollowDetector.pack(0, 0)), "marked by the neighbour unload");
        assertFalse(detector.forget(VertigoHollowDetector.pack(0, 0)), "and only once");
        assertFalse(detector.forget(VertigoHollowDetector.pack(-1, -1)), "never marked");
    }
}
