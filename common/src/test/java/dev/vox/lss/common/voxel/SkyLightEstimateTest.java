package dev.vox.lss.common.voxel;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static dev.vox.lss.common.voxel.SkyLightEstimate.AIR;
import static dev.vox.lss.common.voxel.SkyLightEstimate.DOWN;
import static dev.vox.lss.common.voxel.SkyLightEstimate.EAST;
import static dev.vox.lss.common.voxel.SkyLightEstimate.NORTH;
import static dev.vox.lss.common.voxel.SkyLightEstimate.SOUTH;
import static dev.vox.lss.common.voxel.SkyLightEstimate.UP;
import static dev.vox.lss.common.voxel.SkyLightEstimate.WEST;
import static dev.vox.lss.common.voxel.SkyLightEstimate.descriptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #382: a chunk saved without light (vanilla's {@code ChunkDeleteLightFix} strips it on
 * a world upgrade) reached consumers with sky 0 in every terrain section and rendered black.
 * The client now estimates sky light for such a column with vanilla's own rule, restricted to
 * the column. These pin that rule on hand-built columns: the expected values are worked out
 * by hand from vanilla's source and increase rules, and every case is also compared, cell
 * for cell, with a plain fixpoint relaxation written independently of the routine.
 *
 * <p>Heights below are in blocks from the bottom of the range. Test blocks: stone
 * (dampening 15), leaves and water (1), a "dense" block (3), and bottom/top slabs
 * (dampening 0; one full face, four half side faces). Putting {@code AIR} carves a cell out
 * of a section that stays a content section.
 */
class SkyLightEstimateTest {
    private static final int SIDES = 1 << NORTH | 1 << SOUTH | 1 << WEST | 1 << EAST;
    private static final int STONE = descriptor(15, 0, 0);
    private static final int LEAVES = descriptor(1, 0, 0);
    private static final int WATER = descriptor(1, 0, 0);
    private static final int DENSE = descriptor(3, 0, 0);
    private static final int BOTTOM_SLAB = descriptor(0, 1 << DOWN, SIDES);
    private static final int TOP_SLAB = descriptor(0, 1 << UP, SIDES);

    /** A column of descriptors; a section nothing was put into is all air. */
    private static final class Grid implements SkyLightEstimate.Column {
        final int sections;
        final int[][] cells;
        int lookups, partialQueries;

        Grid(int sections) {
            this.sections = sections;
            this.cells = new int[sections][];
        }

        int height() { return sections * 16; }

        Grid put(int x, int y, int z, int descriptor) {
            int section = sections - 1 - (y >> 4);
            if (cells[section] == null) cells[section] = new int[4096];
            cells[section][SkyLightEstimate.cell(x, y & 15, z)] = descriptor;
            return this;
        }

        Grid box(int x0, int x1, int y0, int y1, int z0, int z1, int descriptor) {
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) put(x, y, z, descriptor);
            return this;
        }

        Grid layers(int y0, int y1, int descriptor) { return box(0, 15, y0, y1, 0, 15, descriptor); }

        @Override public boolean allAir(int section) { return cells[section] == null; }

        @Override public int descriptor(int section, int cell) {
            lookups++;
            return cells[section][cell];
        }

        /** Two half faces close the gap only when they are the two different halves. */
        @Override public boolean partialFacesClose(int sectionA, int cellA, int sectionB, int cellB, int direction) {
            partialQueries++;
            return cells[sectionA][cellA] != cells[sectionB][cellB];
        }

        int at(int index) {
            int section = sections - 1 - (index >>> 12);
            return cells[section] == null ? AIR : cells[section][index & 4095];
        }
    }

    private static int light(SkyLightEstimate estimate, Grid grid, int x, int y, int z) {
        return estimate.lightAt(grid.sections - 1 - (y >> 4), SkyLightEstimate.cell(x, y & 15, z));
    }

    // ---- the independent reference: vanilla's two rules as a plain fixpoint ----

    private static boolean facesClose(Grid grid, int from, int direction, int to) {
        int a = grid.at(from), b = grid.at(to), opposite = direction ^ 1;
        boolean aFull = (a >>> 4 + direction & 1) != 0, bFull = (b >>> 4 + opposite & 1) != 0;
        boolean aPartial = (a >>> 10 + direction & 1) != 0, bPartial = (b >>> 10 + opposite & 1) != 0;
        if (aFull || bFull) return true;
        return aPartial && bPartial && a != b;
    }

    private static final int[] STEP = {-256, 256, -16, 16, -1, 1};

    private static int neighbour(Grid grid, int index, int direction) {
        int x = index & 15, z = index >> 4 & 15, y = index >> 8;
        switch (direction) {
            case DOWN: if (y == 0) return -1; break;
            case UP: if (y == grid.height() - 1) return -1; break;
            case NORTH: if (z == 0) return -1; break;
            case SOUTH: if (z == 15) return -1; break;
            case WEST: if (x == 0) return -1; break;
            default: if (x == 15) return -1;
        }
        return index + STEP[direction];
    }

    private static byte[] reference(Grid grid) {
        byte[] light = new byte[grid.height() << 8];
        for (int c = 0; c < 256; c++) {
            int y = grid.height() - 1;
            boolean open = true;
            int above = -1;   // index of the cell above, -1 = air (the top, or below an all-air section)
            while (open && y >= 0) {
                int section = grid.sections - 1 - (y >> 4);
                if (grid.allAir(section)) {
                    for (int i = 0; i < 16; i++, y--) light[y << 8 | c] = 15;
                    above = -1;
                    continue;
                }
                int index = y << 8 | c;
                boolean closed = above >= 0 ? facesClose(grid, above, DOWN, index)
                        : (grid.at(index) >>> 4 + UP & 1) != 0;
                if ((grid.at(index) & 15) != 0 || closed) {
                    open = false;
                    // the lowest source's step down (from air when nothing is above)
                    if (!closed) light[index] = (byte) Math.max(0, 15 - (grid.at(index) & 15));
                } else {
                    light[index] = 15;
                    above = index;
                    y--;
                }
            }
        }
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int index = 0; index < light.length; index++) {
                int level = light[index];
                if (level <= 1) continue;
                for (int direction = 0; direction < 6; direction++) {
                    int to = neighbour(grid, index, direction);
                    if (to < 0) continue;
                    int next = level - Math.max(1, grid.at(to) & 15);
                    if (next > light[to] && !facesClose(grid, index, direction, to)) {
                        light[to] = (byte) next;
                        changed = true;
                    }
                }
            }
        }
        return light;
    }

    private static SkyLightEstimate estimateAndCompare(Grid grid) {
        var estimate = new SkyLightEstimate();
        assertMatchesReference(grid, estimate);
        return estimate;
    }

    /** Runs the routine and compares every cell, and every packed layer, with the reference. */
    private static void assertMatchesReference(Grid grid, SkyLightEstimate estimate) {
        byte[] expected = reference(grid);
        boolean any = estimate.estimate(grid, grid.sections);
        boolean expectedAny = false;
        for (int section = 0; section < grid.sections; section++) {
            byte[] layer = estimate.layer(section);
            boolean lit = false;
            for (int cell = 0; cell < 4096; cell++) {
                int want = expected[(grid.sections - 1 - section) << 12 | cell];
                lit |= want != 0;
                int x = cell & 15, y = cell >> 8, z = cell >> 4 & 15;
                assertEquals(want, estimate.lightAt(section, cell),
                        "section " + section + " x=" + x + " y=" + y + " z=" + z);
                if (layer != null) assertEquals(want, layer[cell >> 1] >> 4 * (cell & 1) & 15,
                        "packed layer, section " + section + " cell " + cell);
            }
            assertEquals(lit, layer != null, "a layer exists exactly for a section that received light (" + section + ")");
            if (layer != null) assertEquals(SkyLightEstimate.LAYER_BYTES, layer.length);
            expectedAny |= lit;
        }
        assertEquals(expectedAny, any);
        assertFalse(estimate.exhausted());
    }

    // ---- sources ----

    @Test void openFlatGroundIsLitAboveTheSurfaceOnlyAndCostsOneLookupRunPerColumn() {
        var grid = new Grid(3).layers(0, 23, STONE);
        var estimate = estimateAndCompare(grid);
        for (int y = 24; y < 48; y++) assertEquals(15, light(estimate, grid, 5, y, 9));
        for (int y = 0; y < 24; y++) assertEquals(0, light(estimate, grid, 5, y, 9));
        byte[] top = estimate.layer(0);
        for (byte b : top) assertEquals((byte) 0xFF, b, "the air section above the surface is uniform 15");
        byte[] surface = estimate.layer(1);
        for (int i = 0; i < 2048; i++) assertEquals(i < 1024 ? 0 : (byte) 0xFF, surface[i]);
        assertNull(estimate.layer(2), "a section that received nothing has no layer");
        assertEquals(0, estimate.steps(), "nothing to spread: every source cell has lit or solid neighbours");
        // Each column reads its 8 air cells and the stone that stops it, in the surface section only.
        assertEquals(256 * 9, grid.lookups, "an ordinary surface column must not read below its surface");
    }

    @Test void aColumnFilledToTheTopGetsNoLightAndNoLayer() {
        var grid = new Grid(2).layers(0, 31, STONE);
        var estimate = new SkyLightEstimate();
        assertFalse(estimate.estimate(grid, 2));
        assertNull(estimate.layer(0));
        assertNull(estimate.layer(1));
        assertEquals(256, grid.lookups, "only the top cell of each column is read");

        // A solid cap over a hollow: still dark, the cap is the top of the range.
        var hollow = new Grid(2).layers(31, 31, STONE).layers(0, 3, STONE);
        assertMatchesReference(hollow, estimate);
        assertNull(estimate.layer(0));
        assertNull(estimate.layer(1));
    }

    @Test void blocksThatStartSeveralSectionsBelowTheTopLeaveFullBrightAirAbove() {
        var grid = new Grid(5).layers(0, 23, STONE);
        var estimate = estimateAndCompare(grid);
        for (int section = 0; section < 3; section++) {
            byte[] layer = estimate.layer(section);
            assertNotNull(layer);
            for (byte b : layer) assertEquals((byte) 0xFF, b);
        }
        assertEquals(15, light(estimate, grid, 0, 24, 0));
        assertEquals(0, light(estimate, grid, 0, 23, 0));
        assertNull(estimate.layer(4));
    }

    @Test void aDampeningBlockAtTheVeryTopOfTheRangeTakesLightFromTheOpenSkyAboveIt() {
        var grid = new Grid(1).layers(0, 12, STONE).layers(15, 15, LEAVES);
        var estimate = estimateAndCompare(grid);
        assertEquals(14, light(estimate, grid, 3, 15, 3), "15 from above the range, minus the leaf's 1");
        assertEquals(13, light(estimate, grid, 3, 14, 3));
        assertEquals(12, light(estimate, grid, 3, 13, 3));
        assertEquals(0, light(estimate, grid, 3, 12, 3));
    }

    // ---- dampening ----

    @Test void aLeafCanopyFadesByOnePerBlockInsideAndUnderIt() {
        var grid = new Grid(2).layers(0, 3, STONE).layers(20, 22, LEAVES);
        var estimate = estimateAndCompare(grid);
        assertEquals(15, light(estimate, grid, 8, 23, 8));
        assertEquals(14, light(estimate, grid, 8, 22, 8), "the top leaf is not a source: 15 - 1");
        assertEquals(13, light(estimate, grid, 8, 21, 8));
        assertEquals(12, light(estimate, grid, 8, 20, 8));
        assertEquals(11, light(estimate, grid, 8, 19, 8), "air under the canopy keeps fading, one per block");
        assertEquals(8, light(estimate, grid, 8, 16, 8));
        assertEquals(7, light(estimate, grid, 8, 15, 8), "across the section boundary");
        assertEquals(1, light(estimate, grid, 8, 9, 8));
        assertEquals(0, light(estimate, grid, 8, 8, 8));
    }

    @Test void aCanopyOverHalfTheColumnIsAlsoLitFromItsOpenSide() {
        var grid = new Grid(2).layers(0, 3, STONE).box(0, 7, 20, 22, 0, 15, LEAVES);
        var estimate = estimateAndCompare(grid);
        assertEquals(15, light(estimate, grid, 8, 4, 0), "the open half is lit down to the ground");
        assertEquals(14, light(estimate, grid, 7, 19, 5), "one step in from the open side");
        assertEquals(14, light(estimate, grid, 7, 20, 5), "a leaf beside open air: 15 - 1 from the side beats 12 from above");
        assertEquals(12, light(estimate, grid, 0, 20, 5), "deep in the canopy the light from above wins");
        assertEquals(11, light(estimate, grid, 0, 19, 5));
        assertEquals(11, light(estimate, grid, 3, 19, 5), "from above (11) and from the side (15 - 5 = 10)");
        assertEquals(7, light(estimate, grid, 0, 4, 5), "at the ground only the side light is left: 15 - 8");
    }

    @Test void waterFadesWithDepthAcrossASectionBoundary() {
        var grid = new Grid(2).layers(0, 11, STONE).layers(12, 19, WATER);
        var estimate = estimateAndCompare(grid);
        assertEquals(15, light(estimate, grid, 2, 20, 2));
        for (int depth = 1; depth <= 8; depth++) {
            assertEquals(15 - depth, light(estimate, grid, 2, 20 - depth, 2), "water " + depth + " deep");
        }
        assertEquals(0, light(estimate, grid, 2, 11, 2));
    }

    @Test void aDenserBlockCostsItsOwnDampeningPerStep() {
        var grid = new Grid(1).layers(0, 3, STONE).layers(10, 11, DENSE);
        var estimate = estimateAndCompare(grid);
        assertEquals(12, light(estimate, grid, 0, 11, 0));
        assertEquals(9, light(estimate, grid, 0, 10, 0));
        assertEquals(8, light(estimate, grid, 0, 9, 0), "air costs 1");
    }

    // ---- face shapes ----

    @Test void aBottomSlabRoofIsLitItselfAndDarkStraightBelow() {
        var grid = new Grid(2).layers(0, 3, STONE).layers(20, 20, BOTTOM_SLAB);
        var estimate = estimateAndCompare(grid);
        assertEquals(15, light(estimate, grid, 4, 21, 4));
        assertEquals(15, light(estimate, grid, 4, 20, 4), "light enters the slab cell from above");
        for (int y = 4; y < 20; y++) assertEquals(0, light(estimate, grid, 4, y, 4), "its full bottom face stops it");
    }

    @Test void aTopSlabRoofIsDarkItselfAndStraightBelow() {
        var grid = new Grid(2).layers(0, 3, STONE).layers(20, 20, TOP_SLAB);
        var estimate = estimateAndCompare(grid);
        assertEquals(15, light(estimate, grid, 4, 21, 4));
        for (int y = 4; y <= 20; y++) assertEquals(0, light(estimate, grid, 4, y, 4), "its full top face stops the light");
    }

    @Test void aHoleInATopSlabRoofLightsTheSlabsBesideItAndTheRoomBelow() {
        var grid = new Grid(2).layers(0, 3, STONE).layers(20, 20, TOP_SLAB).put(8, 20, 8, AIR);
        var estimate = estimateAndCompare(grid);
        assertEquals(15, light(estimate, grid, 8, 4, 8), "the hole is an open shaft");
        assertEquals(14, light(estimate, grid, 7, 20, 8), "sideways into the slab cell through its half side face");
        assertEquals(13, light(estimate, grid, 6, 20, 8), "slab to slab: two equal half faces leave the other half open");
        assertEquals(14, light(estimate, grid, 7, 19, 8), "beside the shaft under the roof");
        assertEquals(13, light(estimate, grid, 7, 19, 7));
        assertTrue(grid.partialQueries > 0, "two touching partial faces are decided by the caller");
    }

    @Test void twoHalfFacesThatTogetherCoverTheFaceCloseTheGap() {
        // A top slab beside a bottom slab: bottom half + top half = a full face (vanilla blocks it).
        var grid = new Grid(1).layers(0, 3, STONE).layers(10, 10, TOP_SLAB).box(8, 15, 10, 10, 0, 15, BOTTOM_SLAB);
        var estimate = estimateAndCompare(grid);
        assertEquals(15, light(estimate, grid, 8, 10, 4), "the bottom slab is open to the sky");
        assertEquals(0, light(estimate, grid, 7, 10, 4), "and cannot pass it sideways into the top slab");
        assertEquals(0, light(estimate, grid, 7, 9, 4));
    }

    @Test void undecidedPartialFacesAreTreatedAsClosed() {
        // One open cell in a top-slab layer that lies directly on stone: the only way into
        // the slabs is sideways, slab to slab.
        var open = new Grid(1).layers(0, 9, STONE).layers(10, 10, TOP_SLAB).put(8, 10, 8, AIR);
        // The same column through a lookup that keeps the interface default.
        SkyLightEstimate.Column conservative = new SkyLightEstimate.Column() {
            @Override public boolean allAir(int section) { return open.allAir(section); }
            @Override public int descriptor(int section, int cell) { return open.descriptor(section, cell); }
        };
        var estimate = new SkyLightEstimate();
        assertTrue(estimate.estimate(conservative, 1));
        assertEquals(14, light(estimate, open, 7, 10, 8), "air to slab is not a partial pair");
        assertEquals(0, light(estimate, open, 6, 10, 8), "slab to slab is, and the default closes it");
        byte[] exact = reference(open);
        assertEquals(13, exact[10 << 8 | 8 << 4 | 6], "the exact answer for these two equal halves is open");
        for (int cell = 0; cell < 4096; cell++) {
            assertTrue(estimate.lightAt(0, cell) <= exact[cell], "the conservative answer is never brighter");
        }
    }

    @Test void aSlabOnASectionFloorOverAnAllAirSectionPassesLightAsVanillaDoes() {
        // Vanilla's source scan skips the face check when it enters an all-air section
        // (ChunkSkyLightSources.findLowestSourceY), so this roof leaks; the same roof one
        // block higher, or over a section that is not all air, does not.
        var onTheBoundary = new Grid(3).layers(0, 3, STONE).layers(32, 32, BOTTOM_SLAB);
        var estimate = estimateAndCompare(onTheBoundary);
        assertEquals(15, light(estimate, onTheBoundary, 4, 31, 4));
        assertEquals(15, light(estimate, onTheBoundary, 4, 4, 4));

        var oneHigher = new Grid(3).layers(0, 3, STONE).layers(33, 33, BOTTOM_SLAB);
        assertMatchesReference(oneHigher, estimate);
        assertEquals(0, light(estimate, oneHigher, 4, 32, 4));
        assertEquals(0, light(estimate, oneHigher, 4, 4, 4));

        var overANonAirSection = new Grid(3).layers(0, 3, STONE).layers(32, 32, BOTTOM_SLAB).put(0, 20, 0, STONE);
        assertMatchesReference(overANonAirSection, estimate);
        assertEquals(0, light(estimate, overANonAirSection, 4, 31, 4));
    }

    // ---- sideways spread ----

    @Test void anOverhangIsLitFromItsOpenSideWithOnePerBlockFalloff() {
        var grid = new Grid(2).layers(0, 3, STONE).box(0, 7, 20, 20, 0, 15, STONE);
        var estimate = estimateAndCompare(grid);
        for (int x = 0; x <= 7; x++) {
            for (int y : new int[]{4, 15, 16, 19}) {
                assertEquals(15 - (8 - x), light(estimate, grid, x, y, 6), "under the overhang, x=" + x + " y=" + y);
            }
            assertEquals(0, light(estimate, grid, x, 20, 6), "the overhang itself");
            assertEquals(15, light(estimate, grid, x, 21, 6));
        }
        assertEquals(15, light(estimate, grid, 8, 4, 6));
        assertTrue(estimate.steps() > 0);
    }

    @Test void aCaveMouthLightsTheTunnelByDistanceFromTheShaft() {
        var grid = new Grid(3).layers(0, 39, STONE)
                .box(8, 8, 20, 39, 8, 8, AIR)      // a 1x1 shaft from the surface
                .box(4, 12, 16, 19, 8, 8, AIR);    // a tunnel under it
        var estimate = estimateAndCompare(grid);
        for (int y = 16; y < 48; y++) assertEquals(15, light(estimate, grid, 8, y, 8), "the shaft is open sky, y=" + y);
        for (int x = 4; x <= 12; x++) {
            for (int y = 16; y <= 19; y++) {
                assertEquals(15 - Math.abs(x - 8), light(estimate, grid, x, y, 8), "tunnel x=" + x + " y=" + y);
            }
        }
        assertEquals(0, light(estimate, grid, 3, 17, 8), "stone at the tunnel's end");
        assertEquals(0, light(estimate, grid, 8, 17, 7), "stone beside the tunnel");
        assertEquals(0, light(estimate, grid, 8, 15, 8), "stone under it");
    }

    @Test void lightNeverLeavesOrEntersThroughTheColumnBorder() {
        // An overhang open only towards x = 16, which is outside the column: nothing arrives.
        var grid = new Grid(1).layers(0, 3, STONE).layers(12, 12, STONE);
        var estimate = estimateAndCompare(grid);
        for (int y = 4; y < 12; y++) assertEquals(0, light(estimate, grid, 15, y, 15));
    }

    // ---- bounds and reuse ----

    @Test void theSpreadBudgetStopsTheSpreadAndLeavesTheResultDarkerNeverBrighter() {
        var grid = new Grid(4).layers(0, 3, STONE).box(0, 7, 60, 60, 0, 15, STONE);
        byte[] exact = reference(grid);
        var capped = new SkyLightEstimate(40);
        assertTrue(capped.estimate(grid, 4));
        assertTrue(capped.exhausted());
        assertEquals(41, capped.steps());
        boolean darker = false;
        for (int section = 0; section < 4; section++) {
            for (int cell = 0; cell < 4096; cell++) {
                int got = capped.lightAt(section, cell), want = exact[(3 - section) << 12 | cell];
                assertTrue(got <= want, "never brighter than the full result");
                darker |= got < want;
            }
        }
        assertTrue(darker, "the cut must be visible in this column");
        assertEquals(15, light(capped, grid, 12, 30, 3), "the source pass is not part of the budget");

        var full = new SkyLightEstimate();
        assertMatchesReference(grid, full);
        assertTrue(full.steps() < SkyLightEstimate.DEFAULT_SPREAD_BUDGET);
    }

    @Test void oneInstanceGivesEachColumnItsOwnResult() {
        var estimate = new SkyLightEstimate();
        assertMatchesReference(new Grid(4).layers(0, 3, STONE).box(0, 7, 60, 60, 0, 15, STONE), estimate);
        var solid = new Grid(2).layers(0, 31, STONE);
        assertFalse(estimate.estimate(solid, 2));
        for (int cell = 0; cell < 4096; cell++) {
            assertEquals(0, estimate.lightAt(0, cell), "nothing may be left from the column before");
            assertEquals(0, estimate.lightAt(1, cell));
        }
        assertMatchesReference(new Grid(3).layers(0, 39, STONE).box(8, 8, 20, 39, 8, 8, AIR), estimate);
        assertMatchesReference(new Grid(1).layers(0, 3, STONE).layers(12, 12, STONE), estimate);
        assertMatchesReference(new Grid(5).layers(0, 23, STONE), estimate);
    }

    @Test void theLayerUsesVanillasNibbleOrder() {
        // One lit cell layout that is not symmetric: a shaft at x=1, z=0 only.
        var grid = new Grid(1).layers(0, 15, STONE).box(1, 1, 8, 15, 0, 0, AIR);
        var estimate = estimateAndCompare(grid);
        byte[] layer = estimate.layer(0);
        // index = y << 8 | z << 4 | x; byte = index >> 1; the odd index is the high nibble.
        assertEquals((byte) 0xF0, layer[(8 << 8 | 1) >> 1]);
        assertEquals(0, layer[(7 << 8 | 1) >> 1]);
        assertEquals(0, layer[(8 << 8 | 2) >> 1]);
    }

    @Test void descriptorsKeepTheirPartsApart() {
        assertEquals(0, AIR);
        assertEquals(15, SkyLightEstimate.OPAQUE & 15);
        assertEquals(0x3F, SkyLightEstimate.OPAQUE >>> 4 & 0x3F);
        assertEquals(15, descriptor(99, 0, 0), "dampening clamps");
        assertEquals(0, descriptor(-3, 0, 0));
        int both = descriptor(0, 1 << UP, 1 << UP | 1 << WEST);
        assertEquals(1 << UP, both >>> 4 & 0x3F);
        assertEquals(1 << WEST, both >>> 10 & 0x3F, "a full face is not also partial");
        assertEquals(0, DOWN); assertEquals(1, UP); assertEquals(2, NORTH);
        assertEquals(3, SOUTH); assertEquals(4, WEST); assertEquals(5, EAST);
    }

    // ---- random terrain against the reference ----

    @Test void randomColumnsMatchTheReferenceCellForCell() {
        var random = new Random(382);
        var estimate = new SkyLightEstimate();
        int[] palette = {STONE, STONE, STONE, LEAVES, WATER, DENSE, BOTTOM_SLAB, TOP_SLAB, AIR};
        for (int round = 0; round < 60; round++) {
            int sections = 1 + random.nextInt(4);
            var grid = new Grid(sections);
            int base = random.nextInt(grid.height());
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int surface = Math.max(0, Math.min(grid.height() - 1, base + random.nextInt(9) - 4));
                for (int y = 0; y <= surface; y++) grid.put(x, y, z, STONE);
            }
            // caves, overhangs, canopies, pools and odd blocks
            for (int feature = 0; feature < 12; feature++) {
                int x0 = random.nextInt(16), z0 = random.nextInt(16), y0 = random.nextInt(grid.height());
                int x1 = Math.min(15, x0 + random.nextInt(8)), z1 = Math.min(15, z0 + random.nextInt(8));
                int y1 = Math.min(grid.height() - 1, y0 + random.nextInt(6));
                grid.box(x0, x1, y0, y1, z0, z1, palette[random.nextInt(palette.length)]);
            }
            assertMatchesReference(grid, estimate);
        }
    }
}
