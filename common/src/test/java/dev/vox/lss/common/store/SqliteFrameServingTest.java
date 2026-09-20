package dev.vox.lss.common.store;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.store.LodStoreService.FrameHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Statement;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Frame-form serving on the SQLite store (compressed-columns plan §3): {@code getFrame}
 * hands back the STORED zstd frame with frame-level integrity (fhash + declared content
 * size — detection parity with {@code get()}'s raw-hash-after-decompress, review A
 * finding 1), {@code depositFrame} stores the wire frame verbatim (the store-frame ==
 * wire-frame identity pin, compress-once-use-twice), and bit-rot takes the SAME
 * row-poison purge ladder so a corrupt row dies server-side instead of looping through
 * client decode failures.
 */
class SqliteFrameServingTest {

    private static final String OW = "minecraft:overworld";
    private static final int WIRE = 19;

    @TempDir
    Path tmp;

    private SqliteLodStore open() throws Exception {
        var env = new SqliteLodStore.Environment(this.tmp.resolve("store"), "26.2-test", WIRE,
                d -> this.tmp.resolve("region"), d -> "", 0);
        SqliteLodStore store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env,
                new LodStoreDiagnostics());
        assertNotNull(store, "SQLite engine must be available on the test JVM");
        assertTrue(store.awaitSweep(10_000), "startup sweep must complete");
        return store;
    }

    private static FrameHit awaitFrame(SqliteLodStore store, String dim, long pos)
            throws Exception {
        for (int i = 0; i < 400; i++) {
            FrameHit hit = store.getFrame(dim, pos);
            if (hit != null) return hit;
            Thread.sleep(25);
        }
        return null;
    }

    private static byte[] realisticRaw() {
        byte[] raw = new byte[4096];
        new Random(7).nextBytes(raw);
        // half zeros so zstd actually compresses (pure random would not shrink)
        java.util.Arrays.fill(raw, 2048, 4096, (byte) 0);
        return raw;
    }

    @Test
    void rawDepositServesAValidatedFrame() throws Exception {
        var codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null);
        var store = open();
        try {
            long p = PositionUtil.packPosition(3, 4);
            byte[] raw = realisticRaw();
            store.deposit(OW, p, raw, 111L, 100L);
            FrameHit hit = awaitFrame(store, OW, p);
            assertNotNull(hit);
            assertEquals(raw.length, hit.usize());
            assertEquals(111L, hit.columnTimestamp());
            assertArrayEquals(raw, codec.decompress(hit.frame(), hit.usize()),
                    "the served frame decodes to exactly the deposited raw bytes");
        } finally {
            store.shutdown();
        }
    }

    @Test
    void depositFrameStoresTheWireFrameVerbatim() throws Exception {
        var codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null);
        var store = open();
        try {
            long p = PositionUtil.packPosition(-2, 9);
            byte[] raw = realisticRaw();
            byte[] wireFrame = codec.compress(raw);
            assertTrue(store.depositFrame(OW, p, wireFrame, raw.length,
                    LodStoreService.contentHash(raw), LodStoreService.contentHash(wireFrame),
                    222L, 200L));
            FrameHit hit = awaitFrame(store, OW, p);
            assertNotNull(hit);
            assertArrayEquals(wireFrame, hit.frame(),
                    "store frame == wire frame — the §3 identity pin (no recompress)");
            // The raw path still validates end-to-end off the caller-computed chash.
            var rawHit = store.get(OW, p);
            assertNotNull(rawHit);
            assertArrayEquals(raw, rawHit.sectionBytes());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void allAirRowsServeTheEmptyFrameShape() throws Exception {
        assumeTrue(StoreCodec.zstdOrNull() != null);
        var store = open();
        try {
            long p = PositionUtil.packPosition(0, 0);
            store.deposit(OW, p, null, 333L, 300L); // all-air normalizes to byte[0]
            FrameHit hit = awaitFrame(store, OW, p);
            assertNotNull(hit);
            assertEquals(0, hit.usize());
            assertEquals(0, hit.frame().length);
            assertEquals(333L, hit.columnTimestamp());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void depositFrameRefusesAllAirAndNonsense() throws Exception {
        assumeTrue(StoreCodec.zstdOrNull() != null);
        var store = open();
        try {
            assertFalse(store.depositFrame(OW, 1L, new byte[0], 0, 0L, 0L, 1L, 1L),
                    "all-air stays the raw path's byte[0] normalization");
            assertFalse(store.depositFrame(OW, 1L, null, 5, 0L, 0L, 1L, 1L));
            assertFalse(store.depositFrame(OW, 1L, new byte[]{1}, 0, 0L, 0L, 1L, 1L));
        } finally {
            store.shutdown();
        }
    }

    @Test
    void bitRotFailsFhashPurgesTheRowAndFallsBackToMiss() throws Exception {
        var codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null);
        var store = open();
        try {
            long p = PositionUtil.packPosition(5, 6);
            byte[] raw = realisticRaw();
            store.deposit(OW, p, raw, 444L, 400L);
            assertNotNull(awaitFrame(store, OW, p));

            // Flip one blob byte directly in the DB — the fhash check must catch it
            // WITHOUT a decompress and purge the row (the get() parity requirement).
            var ds = new org.sqlite.SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + this.tmp.resolve("store").resolve("store.db"));
            try (var conn = ds.getConnection(); Statement st = conn.createStatement()) {
                String table;
                try (var rs = st.executeQuery("SELECT name FROM sqlite_master"
                        + " WHERE type='table' AND name LIKE 'lods%'")) {
                    assertTrue(rs.next(), "one lods table exists");
                    table = rs.getString(1);
                }
                st.execute("UPDATE " + table + " SET blob = X'DEADBEEF' WHERE pos=" + p);
            }
            long errorsBefore = store.diagnostics().getErrors();
            assertNull(store.getFrame(OW, p), "a corrupt frame reads as a miss");
            assertTrue(store.diagnostics().getErrors() > errorsBefore);
            // The purge lands via the control queue; the row must die (no resurrection
            // through either read form).
            for (int i = 0; i < 400 && store.get(OW, p) != null; i++) Thread.sleep(25);
            assertNull(store.get(OW, p), "the poisoned row is purged, not re-served");
        } finally {
            store.shutdown();
        }
    }
}
