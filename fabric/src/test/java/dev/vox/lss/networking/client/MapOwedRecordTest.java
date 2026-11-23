package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map's debt on disk (issue #380): {@code <dimension>.mapowed} beside the cache file. The
 * cache file itself keeps its format, so 0.23 still reads it. The record says which columns
 * it knows about; a column the cache holds and the record does not know has an unknown
 * history, which is never read as "the map has it".
 */
class MapOwedRecordTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ResourceKey<Level> dimension(String name) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("lss_test:" + name));
    }

    private static long p(int x, int z) {
        return PositionUtil.packPosition(x, z);
    }

    /** A session that read the record, asked for no fill, and found none asked for. */
    private static final ColumnStateMap.MapDebt PLAIN = new ColumnStateMap.MapDebt(false, false);

    /** A session's state at save time: {@code held} columns, of which {@code owed} are owed
     *  and the rest were given to a map. */
    private static ColumnStateMap.DetachedState session(long[] held, long[] owed, ColumnStateMap.MapDebt debt) {
        var map = new ColumnStateMap();
        long stamp = 1000;
        var owes = new LongOpenHashSet(owed);
        for (long position : held) {
            map.onReceived(position, stamp++);
            map.setMapOwed(position, owes.contains(position));
        }
        var state = map.detachForSave();
        state.mapDebt = debt;
        return state;
    }

    private static ColumnStateMap adopt(ColumnStateMap.LoadedState loaded) {
        var map = new ColumnStateMap();
        map.adoptLoaded(loaded);
        return map;
    }

    private static ColumnStateMap reload(String server, ResourceKey<Level> dim) {
        return adopt(ColumnCacheStore.loadState(server, dim, false));
    }

    @Test
    void theDebtAndTheRequestSurviveASaveAndTheCacheFileIsReadAsBefore() {
        var dim = dimension("mapowed_roundtrip");
        final String server = "test-mapowed-roundtrip";
        try {
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1), p(2, 2), p(50, 50)}, new long[]{p(1, 1), p(50, 50)},
                            new ColumnStateMap.MapDebt(true, false)), 0, 0, false);

            var loaded = ColumnCacheStore.loadState(server, dim, false);
            assertTrue(loaded.mapFillRequested, "a fill that was asked for goes on next session");
            var map = adopt(loaded);
            assertTrue(map.isMapOwed(p(1, 1)));
            assertFalse(map.isMapOwed(p(2, 2)));
            assertTrue(map.isMapOwed(p(50, 50)));
            assertEquals(2, map.mapOwedCount());
            assertEquals(0, map.mapUnknownCount(), "every column of this save is known");

            var stamps = ColumnCacheStore.load(server, dim);
            assertEquals(3, stamps.size(), "the cache file holds what it always held");
            assertEquals(1000L, stamps.get(p(1, 1)));
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    /** A cache saved before the debt was recorded: nothing is known, so nothing is assumed. */
    @Test
    void aCacheWithoutARecordHasAnUnknownHistoryAndAnEmptyCacheHasNone() {
        var dim = dimension("mapowed_legacy");
        final String server = "test-mapowed-legacy";
        try {
            assertEquals(0, reload(server, dim).mapUnknownCount(), "nothing is held: nothing is unknown");

            var stamps = new Long2LongOpenHashMap();
            stamps.put(p(1, 1), 100L);
            stamps.put(p(2, 2), 200L);
            ColumnCacheStore.save(server, dim, stamps); // as 0.23 wrote it: no record
            assertFalse(Files.exists(ColumnCacheStore.mapOwedFile(server, dim)));

            var loaded = ColumnCacheStore.loadState(server, dim, false);
            assertFalse(loaded.mapFillRequested);
            var map = adopt(loaded);
            assertEquals(2, map.mapUnknownCount());
            assertEquals(0, map.mapOwedCount(), "unknown is not owed");
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    /** The movement prune bounds what a session holds; the record keeps what it does not. */
    @Test
    void theSavingSessionDecidesForWhatItHoldsAndTheRecordStandsForTheRest() {
        var dim = dimension("mapowed_merge");
        final String server = "test-mapowed-merge";
        try {
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1), p(2, 2), p(3, 3)}, new long[]{p(1, 1), p(2, 2), p(3, 3)}, PLAIN),
                    0, 0, false);
            // Next session: (1,1) arrived again while a map was written, (2,2) is held and
            // still owed, (3,3) was never in memory, (4,4) is new and owed.
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1), p(2, 2), p(4, 4)}, new long[]{p(2, 2), p(4, 4)}, PLAIN),
                    0, 0, false);

            var map = reload(server, dim);
            assertFalse(map.isMapOwed(p(1, 1)), "the session held it and said it is paid");
            assertTrue(map.isMapOwed(p(2, 2)));
            assertTrue(map.isMapOwed(p(3, 3)), "not held by the session: the record stands");
            assertTrue(map.isMapOwed(p(4, 4)));
            assertEquals(3, map.mapOwedCount());
            assertEquals(0, map.mapUnknownCount());
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    /** A fill pays a debt without changing the stamp. The player then walks away, the column
     *  leaves memory, and the save must still tell the record: otherwise the column is owed
     *  again next session, announced again and sent again, for ever. */
    @Test
    void aDebtPaidForAColumnThePlayerWalkedAwayFromStaysPaid() {
        var dim = dimension("mapowed_paid_pruned");
        final String server = "test-mapowed-paid-pruned";
        try {
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1), p(100, 0)}, new long[]{p(1, 1), p(100, 0)}, PLAIN), 0, 0, false);

            var map = reload(server, dim);
            assertTrue(map.isMapOwed(p(100, 0)), "premise");
            map.setMapOwed(p(100, 0), false); // the fill delivered it to the map
            map.pruneOutOfRange(0, 0, 10);    // and the player is somewhere else at the save
            assertEquals(-1L, map.timestampFor(p(100, 0)), "premise: no longer in memory");
            var state = map.detachForSave();
            state.mapDebt = PLAIN;
            ColumnCacheStore.mergeSaveDetached(server, dim, state, 0, 0, false);

            var after = reload(server, dim);
            assertEquals(1001L, after.timestampFor(p(100, 0)), "the cache still holds the column");
            assertFalse(after.isMapOwed(p(100, 0)), "and its debt stays paid");
            assertFalse(after.isMapUnknown(p(100, 0)));
            assertTrue(after.isMapOwed(p(1, 1)));
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    @Test
    void aRemovedColumnAndAnEvictedColumnAreNotInTheRecord() {
        var dim = dimension("mapowed_removed");
        final String server = "test-mapowed-removed";
        int oldCap = ColumnCacheStore.mergeEvictionCap;
        try {
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1), p(2, 2), p(90, 90)}, new long[]{p(1, 1), p(2, 2), p(90, 90)}, PLAIN),
                    0, 0, false);
            // A session that unstamps (2,2) (its consumer never got it) and holds the rest.
            var map = reload(server, dim);
            map.onIngestFailed(p(2, 2));
            var state = map.detachForSave();
            state.mapDebt = PLAIN;
            ColumnCacheStore.mergeEvictionCap = 1; // and the cache keeps only the nearest column
            ColumnCacheStore.mergeSaveDetached(server, dim, state, 0, 0, false);

            var stamps = ColumnCacheStore.load(server, dim);
            assertEquals(1, stamps.size(), "premise: (2,2) was removed and (90,90) evicted");
            var record = ColumnCacheStore.loadMapOwed(server, dim);
            assertEquals(1, record.known().size(), "the record knows only columns the file holds");
            assertEquals(1, record.owed().size());
            var after = reload(server, dim);
            assertTrue(after.isMapOwed(p(1, 1)));
            assertEquals(1, after.mapOwedCount());
        } finally {
            ColumnCacheStore.mergeEvictionCap = oldCap;
            ColumnCacheStore.clearForServer(server);
        }
    }

    /** Fill Map on a cache from before the record: every column of unknown history is owed,
     *  also the ones the saving session did not have in memory. Without the request the
     *  rest of the file stays unknown. */
    @Test
    void aFillAskedForMakesTheUnknownRestOfTheFileOwed() {
        var dim = dimension("mapowed_unknown_fill");
        final String server = "test-mapowed-unknown-fill";
        try {
            var stamps = new Long2LongOpenHashMap();
            stamps.put(p(1, 1), 100L);
            stamps.put(p(2, 2), 200L);
            stamps.put(p(500, 500), 300L);
            stamps.put(p(600, 600), 0L); // a legacy "nothing here" stamp: not a held column
            ColumnCacheStore.save(server, dim, stamps);

            // A session that holds (1,1) and (2,2) and asked for no fill.
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1), p(2, 2)}, new long[]{p(2, 2)}, PLAIN), 0, 0, false);
            var plain = reload(server, dim);
            assertTrue(plain.isMapUnknown(p(500, 500)), "held by the file only, and nobody asked: still unknown");
            assertTrue(plain.isMapOwed(p(2, 2)));
            assertFalse(plain.isMapUnknown(p(1, 1)));

            // The same, and the player asked for a fill; the fill already put (1,1) on the map.
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1), p(2, 2)}, new long[]{p(2, 2)},
                            new ColumnStateMap.MapDebt(true, true)), 0, 0, false);
            var loaded = ColumnCacheStore.loadState(server, dim, false);
            assertTrue(loaded.mapFillRequested);
            var map = adopt(loaded);
            assertFalse(map.isMapOwed(p(1, 1)));
            assertTrue(map.isMapOwed(p(2, 2)));
            assertTrue(map.isMapOwed(p(500, 500)), "owed since the player asked");
            assertEquals(2, map.mapOwedCount());
            assertEquals(0, map.mapUnknownCount());
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    /** A column the session holds and knows nothing about (loaded from a cache without a
     *  record, never sent again, no fill asked for) is still unknown after the save: holding
     *  a column is not knowing what the map has. */
    @Test
    void anUnknownColumnTheSessionHoldsStaysUnknownAcrossASave() {
        var dim = dimension("mapowed_unknown_kept");
        final String server = "test-mapowed-unknown-kept";
        try {
            var stamps = new Long2LongOpenHashMap();
            stamps.put(p(1, 1), 100L);
            stamps.put(p(2, 2), 200L);
            ColumnCacheStore.save(server, dim, stamps);

            var map = reload(server, dim);
            assertEquals(2, map.mapUnknownCount(), "premise");
            map.onReceived(p(2, 2), 300L); // one of them arrives again, and a map writes it
            map.setMapOwed(p(2, 2), false);
            var state = map.detachForSave();
            state.mapDebt = PLAIN;
            ColumnCacheStore.mergeSaveDetached(server, dim, state, 0, 0, false);

            var after = reload(server, dim);
            assertTrue(after.isMapUnknown(p(1, 1)));
            assertFalse(after.isMapUnknown(p(2, 2)));
            assertFalse(after.isMapOwed(p(2, 2)));
            assertEquals(1, after.mapUnknownCount());
            assertEquals(0, after.mapOwedCount());
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    /** Columns that reach the cache file without the record being told (an older version
     *  run in between, a save that never read the record) are unknown, never "not owed". */
    @Test
    void columnsTheRecordWasNotToldAboutAreUnknown() throws IOException {
        var dim = dimension("mapowed_untold");
        final String server = "test-mapowed-untold";
        try {
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1), p(2, 2)}, new long[]{p(1, 1)}, PLAIN), 0, 0, false);
            byte[] before = Files.readAllBytes(ColumnCacheStore.mapOwedFile(server, dim));

            // A save without a debt: the record is left exactly as it is.
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(4, 4)}, new long[]{}, null), 0, 0, false);
            assertEquals(java.util.HexFormat.of().formatHex(before),
                    java.util.HexFormat.of().formatHex(Files.readAllBytes(ColumnCacheStore.mapOwedFile(server, dim))));
            // And 0.23, which knows no record, merging a column into the file.
            var columns = new Long2LongOpenHashMap();
            columns.defaultReturnValue(-1L);
            columns.put(p(5, 5), 700L);
            ColumnCacheStore.mergeSave(server, dim, columns, new LongOpenHashSet(), 0, 0);

            var map = reload(server, dim);
            assertTrue(map.isMapOwed(p(1, 1)), "what the record knows still holds");
            assertFalse(map.isMapUnknown(p(2, 2)));
            assertTrue(map.isMapUnknown(p(4, 4)), "added without telling the record");
            assertTrue(map.isMapUnknown(p(5, 5)));
            assertEquals(2, map.mapUnknownCount());
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    /** "The player was told" is kept with the record, stands when a session never learned it,
     *  and a debt that returns late (for a dimension already saved) takes it back. */
    @Test
    void thePlayerWasToldIsKeptWithTheRecord() {
        var dim = dimension("mapowed_hinted");
        final String server = "test-mapowed-hinted";
        try {
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1), p(2, 2)}, new long[]{p(1, 1)},
                            new ColumnStateMap.MapDebt(false, false, true)), 0, 0, false);
            assertTrue(ColumnCacheStore.loadState(server, dim, false).mapHinted);
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(3, 3)}, new long[]{}, PLAIN), 0, 0, false);
            assertTrue(ColumnCacheStore.loadState(server, dim, false).mapHinted, "a session that never learned it");
            ColumnCacheStore.applyMapOwed(server, dim, new LongOpenHashSet(new long[]{p(2, 2)}));
            assertFalse(ColumnCacheStore.loadState(server, dim, false).mapHinted, "new debt: told again");
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(3, 3)}, new long[]{}, new ColumnStateMap.MapDebt(false, false, true)),
                    0, 0, false);
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(3, 3)}, new long[]{}, new ColumnStateMap.MapDebt(false, false, false)),
                    0, 0, false);
            assertFalse(ColumnCacheStore.loadState(server, dim, false).mapHinted);
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    /** A fill that was asked for stands, across sessions, until the cache owes the map
     *  nothing: then the save ends it. */
    @Test
    void theRequestEndsWhenTheRecordHoldsNoDebt() {
        var dim = dimension("mapowed_request_ends");
        final String server = "test-mapowed-request-ends";
        try {
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1), p(2, 2)}, new long[]{p(2, 2)},
                            new ColumnStateMap.MapDebt(true, false)), 0, 0, false);
            assertTrue(ColumnCacheStore.loadState(server, dim, false).mapFillRequested, "debt is left");
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(2, 2)}, new long[]{}, new ColumnStateMap.MapDebt(true, false)),
                    0, 0, false);
            assertFalse(ColumnCacheStore.loadState(server, dim, false).mapFillRequested, "none is left");
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    /** What the record says about a fill stands when the saving session never learned it. */
    @Test
    void theRequestStandsWhenTheSessionNeverLearnedIt() {
        var dim = dimension("mapowed_flag");
        final String server = "test-mapowed-flag";
        try {
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1)}, new long[]{p(1, 1)}, new ColumnStateMap.MapDebt(true, false)),
                    0, 0, false);
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(2, 2)}, new long[]{}, new ColumnStateMap.MapDebt(null, false)),
                    0, 0, false);
            assertTrue(ColumnCacheStore.loadState(server, dim, false).mapFillRequested);
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(2, 2)}, new long[]{}, PLAIN), 0, 0, false);
            assertFalse(ColumnCacheStore.loadState(server, dim, false).mapFillRequested);
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    /** A map lets go of a column after its dimension was saved (the player went through a
     *  portal with the map's queue full): the debt reaches that dimension's record. */
    @Test
    void aDebtThatReturnsForASavedDimensionReachesItsRecord() {
        var dim = dimension("mapowed_late");
        final String server = "test-mapowed-late";
        try {
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1), p(2, 2), p(3, 3)}, new long[]{p(3, 3)},
                            new ColumnStateMap.MapDebt(true, false)), 0, 0, false);
            ColumnCacheStore.applyMapOwed(server, dim, new LongOpenHashSet(new long[]{p(2, 2), p(77, 77)}));

            var loaded = ColumnCacheStore.loadState(server, dim, false);
            assertTrue(loaded.mapFillRequested, "the request is kept");
            var map = adopt(loaded);
            assertTrue(map.isMapOwed(p(2, 2)));
            assertFalse(map.isMapOwed(p(1, 1)));
            assertEquals(2, map.mapOwedCount(), "a column the file does not hold owes nothing");
            assertFalse(ColumnCacheStore.loadMapOwed(server, dim).known()
                    .containsKey(ColumnStateMap.leafKeyFor(p(77, 77))), "and is not in the record");

            // A cache from before the record learns about just that column.
            var legacyDim = dimension("mapowed_late_legacy");
            var stamps = new Long2LongOpenHashMap();
            stamps.put(p(1, 1), 100L);
            stamps.put(p(2, 2), 200L);
            ColumnCacheStore.save(server, legacyDim, stamps);
            ColumnCacheStore.applyMapOwed(server, legacyDim, new LongOpenHashSet(new long[]{p(2, 2)}));
            var legacy = reload(server, legacyDim);
            assertTrue(legacy.isMapOwed(p(2, 2)));
            assertTrue(legacy.isMapUnknown(p(1, 1)));
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    @Test
    void anUnreadableRecordIsAnUnknownHistoryAndNeverCostsTheCache() throws IOException {
        var dim = dimension("mapowed_corrupt");
        final String server = "test-mapowed-corrupt";
        try {
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1)}, new long[]{p(1, 1)}, PLAIN), 0, 0, false);
            var file = ColumnCacheStore.mapOwedFile(server, dim);

            Files.write(file, new byte[]{1, 2, 3});
            assertNull(ColumnCacheStore.loadMapOwed(server, dim));
            var map = reload(server, dim);
            assertEquals(1000L, map.timestampFor(p(1, 1)), "the cache itself loads");
            assertEquals(1, map.mapUnknownCount());
            assertEquals(0, map.mapOwedCount());

            // A header that promises more leaves than the file can hold: nothing is sized
            // from it (the largest count would not even fit a table).
            Files.write(file, java.nio.ByteBuffer.allocate(16).putInt(0x4C534D4F).putInt(1).putInt(0)
                    .putInt(1_000_000).array());
            assertNull(ColumnCacheStore.loadMapOwed(server, dim));
            Files.write(file, java.nio.ByteBuffer.allocate(16).putInt(0x4C534D4F).putInt(1).putInt(0)
                    .putInt(Integer.MAX_VALUE).array());
            assertNull(ColumnCacheStore.loadMapOwed(server, dim));
            Files.write(file, java.nio.ByteBuffer.allocate(16).putInt(0x4C534D4F).putInt(1).putInt(0)
                    .putInt(-5).array());
            assertNull(ColumnCacheStore.loadMapOwed(server, dim));
            // Another version's record is not guessed at.
            Files.write(file, java.nio.ByteBuffer.allocate(16).putInt(0x4C534D4F).putInt(2).putInt(0)
                    .putInt(0).array());
            assertNull(ColumnCacheStore.loadMapOwed(server, dim));
            // An owed bit for a column the record does not claim to know is not believed.
            long key = ColumnStateMap.leafKeyFor(p(1, 1));
            Files.write(file, java.nio.ByteBuffer.allocate(40).putInt(0x4C534D4F).putInt(1).putInt(0).putInt(1)
                    .putLong(key).putLong(0L).putLong(-1L).array());
            assertEquals(0, reload(server, dim).mapOwedCount());
            assertEquals(1, reload(server, dim).mapUnknownCount());
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    @Test
    void theRecordGoesWithTheBucketAndWithAnEmptiedCache() {
        var dim = dimension("mapowed_cleared");
        final String server = "test-mapowed-cleared";
        try {
            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1)}, new long[]{p(1, 1)}, PLAIN), 0, 0, false);
            assertTrue(Files.exists(ColumnCacheStore.mapOwedFile(server, dim)));

            // The only column is unstamped: the cache file is deleted, and the record with it.
            var map = reload(server, dim);
            map.onIngestFailed(p(1, 1));
            var state = map.detachForSave();
            state.mapDebt = PLAIN;
            ColumnCacheStore.mergeSaveDetached(server, dim, state, 0, 0, false);
            assertTrue(ColumnCacheStore.load(server, dim).isEmpty(), "premise: the cache is empty");
            assertFalse(Files.exists(ColumnCacheStore.mapOwedFile(server, dim)));

            ColumnCacheStore.mergeSaveDetached(server, dim,
                    session(new long[]{p(1, 1)}, new long[]{p(1, 1)}, PLAIN), 0, 0, false);
            assertTrue(Files.exists(ColumnCacheStore.mapOwedFile(server, dim)));
            ColumnCacheStore.clearForServer(server);
            assertFalse(Files.exists(ColumnCacheStore.mapOwedFile(server, dim)), "a clear takes the record with the bucket");
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }

    /** The plain cache save forms (tests of older behaviour, and 0.23's whole save) write no record. */
    @Test
    void thePlainMergeFormsDoNotWriteARecord() {
        var dim = dimension("mapowed_plain");
        final String server = "test-mapowed-plain";
        try {
            var columns = new Long2LongOpenHashMap();
            columns.defaultReturnValue(-1L);
            columns.put(p(1, 1), 100L);
            ColumnCacheStore.mergeSave(server, dim, columns, new LongOpenHashSet(), 0, 0);
            assertFalse(Files.exists(ColumnCacheStore.mapOwedFile(server, dim)));
        } finally {
            ColumnCacheStore.clearForServer(server);
        }
    }
}
