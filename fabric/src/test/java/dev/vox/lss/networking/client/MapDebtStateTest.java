package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map's debt in the column state (issue #380): a bit per held column that no map has
 * been given. It follows the stamp, it is not a classification input, and the request side
 * can pick owed columns nearest first.
 */
class MapDebtStateTest {

    private static long p(int x, int z) {
        return PositionUtil.packPosition(x, z);
    }

    private static ColumnStateMap holding(long... positions) {
        var map = new ColumnStateMap();
        long stamp = 100;
        for (long position : positions) map.onReceived(position, stamp++);
        return map;
    }

    @Test
    void onlyAHeldColumnCanBeOwedAndTheDebtGoesWithTheStamp() {
        var map = new ColumnStateMap();
        map.setMapOwed(p(1, 1), true);
        assertEquals(0, map.mapOwedCount(), "nothing is held there: nothing can be owed");

        map.onReceived(p(1, 1), 50L);
        map.setMapOwed(p(1, 1), true);
        map.setMapOwed(p(1, 1), true);
        assertTrue(map.isMapOwed(p(1, 1)));
        assertEquals(1, map.mapOwedCount(), "setting it twice counts once");

        map.onIngestFailed(p(1, 1)); // the column never reached a consumer: unstamped
        assertEquals(-1L, map.timestampFor(p(1, 1)), "premise: the stamp is gone");
        assertFalse(map.isMapOwed(p(1, 1)), "a column that is no longer held owes the map nothing");
        assertEquals(0, map.mapOwedCount());

        map.onReceived(p(1, 1), 60L);
        map.setMapOwed(p(1, 1), true);
        map.setMapOwed(p(1, 1), false);
        assertEquals(0, map.mapOwedCount());
        map.setMapOwed(p(1, 1), false);
        assertEquals(0, map.mapOwedCount(), "clearing it twice counts once");
    }

    /** The scanners must not see the debt: as terrain an owed column is complete. */
    @Test
    void theDebtIsNotAClassificationInput() {
        var owed = holding(p(3, 4), p(3, 5), p(20, 20));
        var plain = holding(p(3, 4), p(3, 5), p(20, 20));
        owed.setMapOwed(p(3, 4), true);
        owed.setMapOwed(p(20, 20), true);
        for (long position : new long[]{p(3, 4), p(3, 5), p(20, 20), p(3, 6), p(99, 99)}) {
            assertEquals(plain.classify(position), owed.classify(position));
        }
        assertEquals(ColumnStateMap.SATISFIED, owed.classify(p(3, 4)), "an owed column is still satisfied");
        assertEquals(plain.receivedCount(), owed.receivedCount());
    }

    @Test
    void pruningDropsTheDebtWithTheColumn() {
        var map = holding(p(0, 0), p(9, 0), p(100, 0));
        map.setMapOwed(p(0, 0), true);
        map.setMapOwed(p(9, 0), true);
        map.setMapOwed(p(100, 0), true);
        assertEquals(3, map.mapOwedCount());

        map.pruneOutOfRange(0, 0, 10); // (100,0)'s leaf is wholly out of range
        assertEquals(2, map.mapOwedCount());
        assertFalse(map.isMapOwed(p(100, 0)));

        map.pruneOutOfRange(0, 0, 8); // (9,0) falls out of a leaf that straddles the range
        assertEquals(1, map.mapOwedCount());
        assertFalse(map.isMapOwed(p(9, 0)));
        assertTrue(map.isMapOwed(p(0, 0)));

        map.clear();
        assertEquals(0, map.mapOwedCount());
    }

    @Test
    void owedColumnsArePickedNearestFirstInRangeAndNotWhileBusy() {
        long near = p(1, 1), mid = p(20, 0), far = p(60, 0), beyond = p(200, 0);
        var map = holding(near, mid, far, beyond);
        for (long position : new long[]{near, mid, far, beyond}) map.setMapOwed(position, true);

        long[] out = new long[8];
        assertEquals(3, map.collectMapOwed(0, 0, 100, out, 0, 8, null), "the fourth is out of range");
        assertArrayEquals(new long[]{near, mid, far}, Arrays.copyOf(out, 3), "nearest leaves first");

        assertEquals(1, map.collectMapOwed(0, 0, 100, out, 0, 1, null), "never more than asked for");
        assertEquals(near, out[0]);

        long[] behind = new long[4];
        assertEquals(2, map.collectMapOwed(0, 0, 100, behind, 2, 2, null));
        assertArrayEquals(new long[]{0, 0, near, mid}, behind, "written behind what the batch already holds");

        assertEquals(2, map.collectMapOwed(0, 0, 100, out, 0, 8, position -> position == near));
        assertArrayEquals(new long[]{mid, far}, Arrays.copyOf(out, 2), "a busy column is passed over");

        // Paid debts are not picked again; the walk then starts where debt may still be.
        map.setMapOwed(near, false);
        map.setMapOwed(mid, false);
        assertEquals(1, map.collectMapOwed(0, 0, 100, out, 0, 8, null));
        assertEquals(far, out[0]);
        assertEquals(1, map.collectMapOwed(0, 0, 100, out, 0, 8, null), "and again, from the moved start");
        assertEquals(far, out[0]);

        // A new debt next to the player is found although the start had moved outward.
        map.setMapOwed(near, true);
        assertEquals(2, map.collectMapOwed(0, 0, 100, out, 0, 8, null));
        assertArrayEquals(new long[]{near, far}, Arrays.copyOf(out, 2));

        // And so is everything after the player moved.
        assertEquals(1, map.collectMapOwed(200, 0, 20, out, 0, 8, null));
        assertEquals(beyond, out[0]);
    }

    /** Many debts in one leaf and a small budget: every one is reached over successive picks. */
    @Test
    void aBudgetSmallerThanALeafStillReachesEveryColumn() {
        var map = new ColumnStateMap();
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                map.onReceived(p(x, z), 500L);
                map.setMapOwed(p(x, z), true);
            }
        }
        long[] out = new long[5];
        int paid = 0;
        for (int round = 0; round < 40 && map.mapOwedCount() > 0; round++) {
            int n = map.collectMapOwed(0, 0, 64, out, 0, 5, null);
            assertTrue(n > 0, "debt remains, so a pick must find some");
            for (int i = 0; i < n; i++) {
                assertTrue(map.isMapOwed(out[i]));
                map.setMapOwed(out[i], false);
                paid++;
            }
        }
        assertEquals(64, paid);
        assertEquals(0, map.collectMapOwed(0, 0, 64, out, 0, 5, null));
    }

    /** A column the scanners are not done with is asked by the scan itself, and its answer
     *  says what the server holds now: a fill leaves it alone until then. */
    @Test
    void aColumnTheScannersAreNotDoneWithIsNotPicked() {
        long changed = p(2, 2), quiet = p(3, 3);
        var map = holding(changed, quiet);
        map.setMapOwed(changed, true);
        map.setMapOwed(quiet, true);
        assertTrue(map.markDirtyIfKnown(changed), "the server announced a change");
        long[] out = new long[4];
        assertEquals(1, map.collectMapOwed(0, 0, 32, out, 0, 4, null));
        assertEquals(quiet, out[0]);
        assertTrue(map.isMapOwed(changed), "its debt stands");

        map.onUpToDate(changed); // the scan asked, and the column is confirmed
        assertEquals(2, map.collectMapOwed(0, 0, 32, out, 0, 4, null));
    }

    @Test
    void theCountFollowsItsPredicate() {
        var map = holding(p(1, 1), p(2, 2), p(30, 30), p(300, 0));
        for (long position : new long[]{p(1, 1), p(2, 2), p(30, 30), p(300, 0)}) map.setMapOwed(position, true);
        assertEquals(3, map.countMapOwedInRange(0, 0, 100, null));
        assertEquals(2, map.countMapOwedInRange(0, 0, 100, position -> position == p(1, 1)));
        assertEquals(4, map.countMapOwedInRange(0, 0, 400, null));
    }

    private static ColumnStateMap loadedWithoutARecord(long... positions) {
        var file = new Long2LongOpenHashMap();
        long stamp = 100;
        for (long position : positions) file.put(position, stamp++);
        var loaded = ColumnStateMap.buildLoaded(file);
        ColumnStateMap.applyMapRecord(loaded, null, null);
        var map = new ColumnStateMap();
        map.adoptLoaded(loaded);
        return map;
    }

    /** A cache from before the record: nothing is known, so nothing is owed, and nothing is
     *  picked, until the player asks for a fill. */
    @Test
    void anUnknownHistoryIsNotOwedUntilThePlayerAsks() {
        var map = loadedWithoutARecord(p(1, 1), p(2, 2));
        map.onUpToDate(p(5, 5)); // answered without data: nothing is held there
        assertEquals(2, map.mapUnknownCount());
        assertEquals(0, map.mapOwedCount());
        assertTrue(map.isMapUnknown(p(1, 1)));
        assertEquals(0, map.collectMapOwed(0, 0, 32, new long[4], 0, 4, null));

        // A column that arrives is known from then on, whatever it is.
        map.onReceived(p(1, 1), 500L);
        map.setMapOwed(p(1, 1), false);
        assertFalse(map.isMapUnknown(p(1, 1)));
        assertEquals(1, map.mapUnknownCount());

        assertEquals(1, map.markUnknownMapOwed());
        assertTrue(map.isMapOwed(p(2, 2)));
        assertFalse(map.isMapOwed(p(1, 1)));
        assertFalse(map.isMapOwed(p(5, 5)));
        assertEquals(0, map.mapUnknownCount());
        assertEquals(1, map.mapOwedCount());
        assertEquals(0, map.markUnknownMapOwed(), "nothing is unknown any more");
    }

    /** A fill that was asked for stands for debt the cache file holds and this map no longer
     *  sees. The map says when owed columns, or columns of unknown history, left it with the
     *  movement prune: as whole leaves, and one by one in a leaf the boundary cuts. */
    @Test
    void debtThatLeavesMemoryWithThePruneIsRemembered() {
        var paid = holding(p(0, 0), p(100, 0));
        paid.setMapOwed(p(100, 0), false);
        paid.pruneOutOfRange(0, 0, 10);
        assertFalse(paid.prunedMapOwed(), "a column that owed nothing left");
        assertFalse(paid.prunedMapUnknown());

        var far = holding(p(0, 0), p(100, 0));
        far.setMapOwed(p(100, 0), true);
        far.pruneOutOfRange(0, 0, 10); // the whole leaf goes
        assertTrue(far.prunedMapOwed());
        assertFalse(far.prunedMapUnknown());

        // One leaf (x 0..7), cut by the boundary: 0..3 are out of range from (10, 0), 4..7 stay.
        var cut = holding(p(1, 0), p(6, 0));
        cut.setMapOwed(p(1, 0), true);
        cut.setMapOwed(p(6, 0), true);
        cut.pruneOutOfRange(10, 0, 6);
        assertEquals(1, cut.mapOwedCount(), "premise: one of the two left");
        assertTrue(cut.prunedMapOwed());
        var kept = holding(p(1, 0), p(6, 0));
        kept.setMapOwed(p(6, 0), true);
        kept.pruneOutOfRange(10, 0, 6);
        assertFalse(kept.prunedMapOwed(), "the one that left owed nothing");

        var unknown = loadedWithoutARecord(p(1, 0), p(6, 0), p(100, 0));
        unknown.pruneOutOfRange(10, 0, 6);
        assertTrue(unknown.prunedMapUnknown());
        assertFalse(unknown.prunedMapOwed());
        var unknownCut = loadedWithoutARecord(p(1, 0), p(6, 0));
        unknownCut.pruneOutOfRange(10, 0, 6);
        assertTrue(unknownCut.prunedMapUnknown(), "in a leaf the boundary cuts too");

        // It is this stay's knowledge: gone with the state.
        far.detachForSave();
        assertFalse(far.prunedMapOwed());
        unknown.detachForSave();
        assertFalse(unknown.prunedMapUnknown());
        cut.clear();
        assertFalse(cut.prunedMapOwed());
        unknownCut.clear();
        assertFalse(unknownCut.prunedMapUnknown());
    }

    @Test
    void unstampingAndPruningDropAnUnknownHistoryToo() {
        var map = loadedWithoutARecord(p(1, 1), p(100, 0));
        map.onIngestFailed(p(1, 1));
        assertEquals(1, map.mapUnknownCount());
        map.pruneOutOfRange(0, 0, 10);
        assertEquals(0, map.mapUnknownCount());
    }

    @Test
    void aLoadedCacheBringsItsRecordAndTheFileWinsWhereItsStampDoes() {
        var file = new Long2LongOpenHashMap();
        file.put(p(1, 1), 100L);
        file.put(p(2, 2), 200L);
        file.put(p(4, 4), 250L);
        file.put(p(40, 40), 300L);
        var loaded = ColumnStateMap.buildLoaded(file);
        var known = new Long2LongOpenHashMap();
        var owed = new Long2LongOpenHashMap();
        long first = ColumnStateMap.leafKeyFor(p(1, 1));
        // the record knows (1,1) and (2,2), and a column the file does not hold; not (4,4)
        known.put(first, bit(p(1, 1)) | bit(p(2, 2)) | bit(p(7, 7)));
        owed.put(first, bit(p(1, 1)) | bit(p(7, 7)) | bit(p(4, 4))); // (4,4) owed but not known: not believed
        known.put(ColumnStateMap.leafKeyFor(p(40, 40)), bit(p(40, 40)));
        owed.put(ColumnStateMap.leafKeyFor(p(40, 40)), bit(p(40, 40)));
        owed.put(ColumnStateMap.leafKeyFor(p(800, 800)), -1L); // a leaf the file does not hold at all
        ColumnStateMap.applyMapRecord(loaded, known, owed);

        var map = new ColumnStateMap();
        map.onReceived(p(2, 2), 999L);
        map.setMapOwed(p(2, 2), true);
        map.onReceived(p(1, 1), 998L);
        map.onReceived(p(3, 3), 997L);
        map.setMapOwed(p(3, 3), true);

        map.adoptLoaded(loaded);
        assertEquals(100L, map.timestampFor(p(1, 1)), "premise: the file's stamp wins");
        assertTrue(map.isMapOwed(p(1, 1)), "and its word on the map with it");
        assertFalse(map.isMapOwed(p(2, 2)), "the record knows (2,2) and says nothing is owed");
        assertFalse(map.isMapUnknown(p(2, 2)));
        assertTrue(map.isMapOwed(p(3, 3)), "a column only the live map holds keeps its own");
        assertTrue(map.isMapUnknown(p(4, 4)), "held by the file, not known to the record");
        assertFalse(map.isMapOwed(p(4, 4)));
        assertTrue(map.isMapOwed(p(40, 40)), "a leaf with no live twin transfers whole");
        assertFalse(map.isMapOwed(p(7, 7)), "a bit for a column the file does not hold is dropped");
        assertEquals(3, map.mapOwedCount());
        assertEquals(1, map.mapUnknownCount());
    }

    private static long bit(long packed) {
        return 1L << ColumnStateMap.bitIndexFor(packed);
    }

    @Test
    void aDetachedStateHandsItsMasksToTheSave() {
        var map = holding(p(1, 1), p(2, 2), p(9, 9));
        map.setMapOwed(p(1, 1), true);
        map.setMapOwed(p(9, 9), true);
        map.setMapOwed(p(2, 2), false);
        var state = map.detachForSave();
        assertEquals(0, map.mapOwedCount(), "the live map starts fresh");

        var present = new Long2LongOpenHashMap();
        var owed = new Long2LongOpenHashMap();
        var unknown = new Long2LongOpenHashMap();
        state.forEachLeafMasks((leafKey, presentMask, owedMask, unknownMask) -> {
            present.put(leafKey, presentMask);
            owed.put(leafKey, owedMask);
            unknown.put(leafKey, unknownMask);
        });
        long first = ColumnStateMap.leafKeyFor(p(1, 1));
        long second = ColumnStateMap.leafKeyFor(p(9, 9));
        assertEquals(bit(p(1, 1)) | bit(p(2, 2)), present.get(first));
        assertEquals(bit(p(1, 1)), owed.get(first));
        assertEquals(bit(p(9, 9)), owed.get(second));
        assertEquals(0L, unknown.get(first));
        assertEquals(bit(p(2, 2)), state.mapSettled.get(first), "the settled debt is handed over");
        assertEquals(p(9, 9), ColumnStateMap.positionFor(second, ColumnStateMap.bitIndexFor(p(9, 9))),
                "the record's addressing turns back into the position");
    }

    /** A debt that was paid without the stamp changing (a map fill) must reach the record
     *  even when the player has walked away from the column before the save. */
    @Test
    void aSettledDebtOutlivesThePruneAndAReturningDebtTakesItBack() {
        long far = p(100, 0), near = p(1, 1);
        var map = holding(far, near);
        map.setMapOwed(far, true);
        map.setMapOwed(near, true);
        map.setMapOwed(far, false);
        map.setMapOwed(near, false);
        map.setMapOwed(near, true); // the map let it go after all
        map.pruneOutOfRange(0, 0, 10);
        assertEquals(-1L, map.timestampFor(far), "premise: pruned");
        var state = map.detachForSave();
        assertEquals(bit(far), state.mapSettled.get(ColumnStateMap.leafKeyFor(far)));
        assertEquals(0L, state.mapSettled.get(ColumnStateMap.leafKeyFor(near)));
        assertTrue(map.detachForSave().mapSettled.isEmpty(), "handed over once");
    }

    /** A column on its way to a map is not owed and not settled: if nothing more is heard of
     *  it, the save claims nothing. A map that wrote a column this map no longer holds
     *  settles the record all the same. */
    @Test
    void whatIsOnItsWayToAMapIsNotSettled() {
        long pending = p(1, 1), lost = p(100, 0), wrote = p(101, 0);
        var map = holding(pending, lost, wrote);
        for (long position : new long[]{pending, lost, wrote}) map.setMapOwed(position, true);
        for (long position : new long[]{pending, lost, wrote}) map.setMapPending(position);
        assertEquals(0, map.mapOwedCount(), "not asked for a second time meanwhile");
        assertEquals(ColumnStateMap.MAP_SETTLED, map.mapState(pending));

        map.pruneOutOfRange(0, 0, 10); // the player moved on
        map.setMapOwed(lost, true);    // the map let it go: the column is gone, nothing to mark
        map.noteMapSettled(wrote);     // the map wrote it
        var state = map.detachForSave();
        assertEquals(0L, state.mapSettled.get(ColumnStateMap.leafKeyFor(pending)), "pending is no claim");
        assertEquals(bit(wrote), state.mapSettled.get(ColumnStateMap.leafKeyFor(lost)),
                "only the one a map wrote");
    }

    /** A delivery that is cancelled before any consumer had the column puts back what was
     *  known before it, an unknown history included. */
    @Test
    void theStateBeforeADeliveryCanBePutBack() {
        var map = loadedWithoutARecord(p(1, 1), p(2, 2), p(3, 3));
        map.setMapOwed(p(2, 2), true);
        map.setMapOwed(p(3, 3), false);
        assertEquals(ColumnStateMap.MAP_UNKNOWN, map.mapState(p(1, 1)));
        assertEquals(ColumnStateMap.MAP_OWED, map.mapState(p(2, 2)));
        assertEquals(ColumnStateMap.MAP_SETTLED, map.mapState(p(3, 3)));
        assertEquals(ColumnStateMap.MAP_SETTLED, map.mapState(p(50, 50)), "nothing held: nothing to say");

        for (long position : new long[]{p(1, 1), p(2, 2), p(3, 3)}) map.setMapOwed(position, false);
        map.restoreMapState(p(1, 1), ColumnStateMap.MAP_UNKNOWN);
        map.restoreMapState(p(2, 2), ColumnStateMap.MAP_OWED);
        map.restoreMapState(p(3, 3), ColumnStateMap.MAP_SETTLED);
        assertTrue(map.isMapUnknown(p(1, 1)));
        assertTrue(map.isMapOwed(p(2, 2)));
        assertEquals(ColumnStateMap.MAP_SETTLED, map.mapState(p(3, 3)));
        assertEquals(1, map.mapUnknownCount());
        assertEquals(1, map.mapOwedCount());
        var state = map.detachForSave();
        assertEquals(bit(p(3, 3)), state.mapSettled.get(ColumnStateMap.leafKeyFor(p(1, 1))),
                "only the one that is settled is recorded as settled");
    }
}
