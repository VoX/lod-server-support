package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Issue #437: which vanilla chunks that left the client Voxy saved with Vertigo's air in them,
 * read from the chunks themselves (docs/planning/2026-11-27-vertigo-hollowed-columns-plan.md §10).
 */
class VertigoHollowDetectorTest {
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    // ---- the rule on a client chunk: Y -64..319, 24 sections ----

    /** A chunk whose column (x, z) has its top block at heights(x, z), and whose sections listed
     *  in `airSections` (by index) the client holds as all air. */
    static LevelChunk chunk(IntBinaryOperator heights, int... airSections) {
        var chunk = mock(LevelChunk.class);
        when(chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)).thenReturn(true);
        when(chunk.getMinY()).thenReturn(-64);
        var sections = new LevelChunkSection[24];
        for (int i = 0; i < 24; i++) {
            var section = mock(LevelChunkSection.class);
            boolean air = false;
            for (int a : airSections) air |= a == i;
            when(section.hasOnlyAir()).thenReturn(air);
            sections[i] = section;
        }
        when(chunk.getSections()).thenReturn(sections);
        when(chunk.getSectionIndex(anyInt())).thenAnswer(call -> Math.floorDiv((int) call.getArgument(0) + 64, 16));
        when(chunk.getHeight(eq(Heightmap.Types.WORLD_SURFACE), anyInt(), anyInt()))
                .thenAnswer(call -> heights.applyAsInt(call.getArgument(1), call.getArgument(2)));
        return chunk;
    }

    /** Index of the section holding Y. */
    static int at(int y) { return Math.floorDiv(y + 64, 16); }

    @Test void theSurfaceSectionsHeldAsAirAreHollow() {
        // Flying high: Vertigo sent the surface sections (Y 64..95) as air.
        var hills = (IntBinaryOperator) (x, z) -> 70 + x + z; // tops 70..100
        assertTrue(VertigoCompat.topSectionEmpty(chunk(hills, at(70), at(80), at(96))));
        assertTrue(VertigoCompat.topSectionEmpty(chunk(hills, at(100))), "one column's top section is enough");
        assertFalse(VertigoCompat.topSectionEmpty(chunk(hills)), "everything sent");
    }

    @Test void skyCavesAndDeepTerrainNeverCount() {
        var flat = (IntBinaryOperator) (x, z) -> 70;
        int[] skyAndDeep = {at(-64), at(-40), at(0), at(100), at(200), at(319)};
        assertFalse(VertigoCompat.topSectionEmpty(chunk(flat, skyAndDeep)),
                "air above the surface is sky; air below it is out of sight from above");
    }

    @Test void airUnderAFloatingBuildOrBesideAnEndIslandIsNotHollow() {
        // A sky platform at Y 200 over ground at Y 70: the sections between are air on the server too.
        var platform = (IntBinaryOperator) (x, z) -> x < 4 ? 200 : 70;
        assertFalse(VertigoCompat.topSectionEmpty(chunk(platform, at(100), at(130), at(160), at(190))));
        // An End island edge: void columns (no block, top below the world) next to the island at Y 60.
        var island = (IntBinaryOperator) (x, z) -> x < 8 ? -65 : 60;
        assertFalse(VertigoCompat.topSectionEmpty(chunk(island, at(-64), at(-30), at(0), at(30))));
        assertTrue(VertigoCompat.topSectionEmpty(chunk(island, at(60))), "the island's own surface hidden");
    }

    @Test void aChunkWithoutItsHeightmapIsNeverHollow() {
        var chunk = chunk((x, z) -> 70, at(70));
        when(chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)).thenReturn(false);
        assertFalse(VertigoCompat.topSectionEmpty(chunk));
    }

    // ---- when: neighbours at the moment Voxy saves them ----

    record View(boolean topSectionEmpty) implements VertigoHollowDetector.ChunkView {}
    static final View HOLLOW = new View(true), FULL = new View(false);

    static final class World implements VertigoHollowDetector.Chunks {
        final Map<Long, View> chunks = new HashMap<>();
        World put(int x, int z, View v) { chunks.put(VertigoHollowDetector.pack(x, z), v); return this; }
        World disc(int r, View v) { for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) put(x, z, v); return this; }
        View drop(int x, int z) { return chunks.remove(VertigoHollowDetector.pack(x, z)); }
        public VertigoHollowDetector.ChunkView chunk(int x, int z) { return chunks.get(VertigoHollowDetector.pack(x, z)); }
    }

    @Test void aFullChunkFlownOverIsNotReported() {
        var detector = new VertigoHollowDetector();
        var world = new World().disc(3, FULL);
        assertFalse(detector.left(3, 0, world.drop(3, 0), world));
        assertEquals(0, detector.suspectCount());
    }

    @Test void aNeighbourHollowWhenItLeavesReadyIsReportedWhenItLeavesEvenIfFullByThen() {
        var detector = new VertigoHollowDetector();
        // Flying high: everything hollow. The edge chunk (3,0) leaves first; (2,0) loses "ready"
        // with it, and Voxy saves (2,0) hollow at that moment.
        var world = new World().disc(3, HOLLOW);
        assertTrue(detector.left(3, 0, world.drop(3, 0), world), "the edge itself was hollow");
        assertTrue(detector.suspectCount() > 0);
        // The player dives: Vertigo sends (2,0)'s surface before it leaves. Voxy does not save
        // a section that turns visible, so (2,0) is still hollow in Voxy.
        world.put(2, 0, FULL);
        assertTrue(detector.left(2, 0, world.drop(2, 0), world), "suspect from the neighbour leaving");
    }

    @Test void aChunkFullWhenItLeftReadyIsNotReported() {
        var detector = new VertigoHollowDetector();
        var world = new World().disc(3, FULL);
        detector.left(3, 0, world.drop(3, 0), world);
        assertFalse(detector.left(2, 0, world.drop(2, 0), world));
    }

    @Test void aChunkThatLeftWithAllItsNeighboursIsJudgedAsItIs() {
        // A teleport drops the whole disc: Sodium never removed it, so Voxy holds what it saved
        // at the add: hollow, as when it arrived.
        var detector = new VertigoHollowDetector();
        assertTrue(detector.left(0, 0, HOLLOW, new World()));
        assertFalse(detector.left(1, 0, FULL, new World()));
        assertEquals(0, detector.suspectCount(), "nothing else is loaded");
    }

    @Test void suspectsLeaveWithTheirChunkAndClearResetsThem() {
        var detector = new VertigoHollowDetector();
        var world = new World().disc(1, HOLLOW);
        detector.left(1, 1, world.drop(1, 1), world);
        assertEquals(3, detector.suspectCount(), "(0,0), (1,0) and (0,1) are the loaded neighbours of (1,1)");
        assertTrue(detector.left(1, 0, world.drop(1, 0), world));
        assertEquals(4, detector.suspectCount(), "(1,0) left; (0,-1) and (1,-1) joined");
        detector.clear();
        assertEquals(0, detector.suspectCount());
    }
}
