package dev.vox.lss.common.store;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The C4 lazy schema 3→4 migration (XVER §5): the from-state matrix (exact v0.9.x
 * state upgrades IN PLACE and keeps every row; dev-era metas and foreign fingerprints
 * drop-and-rebuild — incl. the structural {@code store_layout} guard for the
 * C1..C3-era {@code 4∧20}-without-{@code wirefmt} shape that would otherwise latch
 * the store dead), pre-migration serving (19-rows validate under the LEGACY FNV hash
 * and surface {@code wirefmt=19}), and the background walk (translate + retag with
 * CRC hashes, resumable across restarts via meta — never the version keys — anomaly
 * rows deleted, DropAll resets, completion clears the bookkeeping and the status
 * token). The walk's translator here is a FAKE reversible function — the store treats
 * bodies as opaque; byte-fidelity of the REAL translator is the corpus chain in
 * {@code LegacyColumnEgressTest}, and the serve-rung translation is
 * {@code StoreFrameServingRungTest}'s C4 cases.
 */
class SqliteLodStoreMigrationTest {

    private static final String OW = "minecraft:overworld";
    private static final String FP = "fp-test";
    private static final int WIRE_20 = 20;

    /** The fake walk translator: prefix a marker byte (opaque to the store). */
    private static final UnaryOperator<byte[]> FAKE_TRANSLATOR = raw -> {
        byte[] out = new byte[raw.length + 1];
        out[0] = 0x77;
        System.arraycopy(raw, 0, out, 1, raw.length);
        return out;
    };

    @TempDir
    Path tmp;

    private Path storeDir() {
        return this.tmp.resolve("store");
    }

    private Path regionDir(String dim) {
        return this.tmp.resolve(dim.replace(':', '_')).resolve("region");
    }

    private SqliteLodStore.Environment env20() {
        return new SqliteLodStore.Environment(storeDir(), "26.2-test", WIRE_20,
                this::regionDir, d -> "", 0, Long.MAX_VALUE, FP);
    }

    private SqliteLodStore.Environment env20Fp(String ordered, String content) {
        return new SqliteLodStore.Environment(storeDir(), "26.2-test", WIRE_20,
                this::regionDir, d -> "", 0, Long.MAX_VALUE, ordered, content);
    }

    // ---- the schema-3 (released v0.9.x) fixture, built by hand ----

    private record FixtureRow(long pos, byte[] raw, boolean corruptBlob) {}

    private void buildSchema3Store(String fingerprint, List<FixtureRow> rows) throws Exception {
        Files.createDirectories(storeDir());
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir().resolve("store.db"));
        StoreCodec codec = StoreCodec.zstdOrNull();
        assertNotNull(codec, "zstd natives required on the test classpath");
        long now = System.currentTimeMillis() / 1000L;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            // Match the released store's file shape (review m18): the lazy upgrade
            // KEEPS this file, so the fixture must carry v0.9.x's page size and
            // vacuum mode, not sqlite-jdbc defaults.
            st.execute("PRAGMA page_size=16384");
            st.execute("PRAGMA auto_vacuum=INCREMENTAL");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            st.execute("CREATE TABLE dims (id INTEGER PRIMARY KEY, name TEXT UNIQUE,"
                    + " mask_fingerprint TEXT)");
            st.execute("CREATE TABLE regions (dim INTEGER, rpos INTEGER, seen_mtime INTEGER,"
                    + " PRIMARY KEY (dim, rpos)) WITHOUT ROWID");
            st.execute("CREATE TABLE backfill (dim TEXT, rx INTEGER, rz INTEGER, done INTEGER,"
                    + " PRIMARY KEY (dim, rx, rz)) WITHOUT ROWID");
            // The RELEASED v0.9.x lods shape: no wirefmt column, FNV hashes.
            st.execute("CREATE TABLE lods_1 (pos INTEGER PRIMARY KEY, ts INTEGER NOT NULL,"
                    + " chash INTEGER NOT NULL, usize INTEGER NOT NULL,"
                    + " src_stamp INTEGER NOT NULL, fhash INTEGER NOT NULL,"
                    + " blob BLOB NOT NULL)");
            st.execute("CREATE INDEX lods_1_ts ON lods_1 (ts)");
            st.execute("INSERT INTO dims (id, name, mask_fingerprint) VALUES (1, '"
                    + OW + "', '')");
            for (var e : Map.of(
                    "schema_version", "3",
                    "wire_format_version", "19",
                    "mc_version", "26.2-test",
                    "codec", StoreCodec.NAME,
                    "registry_fingerprint", fingerprint).entrySet()) {
                st.execute("INSERT INTO meta (k, v) VALUES ('" + e.getKey() + "', '"
                        + e.getValue() + "')");
            }
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO lods_1 (pos, ts, chash, usize, src_stamp, fhash, blob)"
                             + " VALUES (?,?,?,?,?,?,?)")) {
            for (FixtureRow row : rows) {
                byte[] blob = row.corruptBlob()
                        ? new byte[] {1, 2, 3, 4} // not a zstd frame — the anomaly shape
                        : codec.compress(row.raw());
                ps.setLong(1, row.pos());
                ps.setLong(2, now);
                ps.setLong(3, LodStoreService.legacyContentHashFnv(row.raw()));
                ps.setInt(4, row.raw().length);
                ps.setLong(5, now);
                ps.setLong(6, LodStoreService.legacyContentHashFnv(blob));
                ps.setBytes(7, blob);
                ps.executeUpdate();
            }
        }
        // Region files + recorded seen_mtime so the startup sweep judges "unchanged"
        // and keeps every fixture row (a vanished region would drop them).
        writeRegionsFor(rows, now);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO regions (dim, rpos, seen_mtime) VALUES (1,?,?)")) {
            for (long rpos : rows.stream().map(r -> regionOf(r.pos())).distinct().toList()) {
                int rx = (int) (rpos >> 32);
                int rz = (int) rpos;
                long mtime = Files.getLastModifiedTime(
                        regionDir(OW).resolve("r." + rx + "." + rz + ".mca")).toMillis();
                ps.setLong(1, rpos);
                ps.setLong(2, mtime);
                ps.executeUpdate();
            }
        }
    }

    private static long regionOf(long packedPos) {
        int cx = PositionUtil.unpackX(packedPos);
        int cz = PositionUtil.unpackZ(packedPos);
        return ((long) (cx >> 5) << 32) | ((cz >> 5) & 0xFFFFFFFFL);
    }

    private void writeRegionsFor(List<FixtureRow> rows, long stampSec) throws Exception {
        Path dir = regionDir(OW);
        Files.createDirectories(dir);
        var byRegion = new java.util.HashMap<Long, ByteBuffer>();
        for (FixtureRow row : rows) {
            long rpos = regionOf(row.pos());
            ByteBuffer buf = byRegion.computeIfAbsent(rpos, k -> ByteBuffer.allocate(8192));
            int cx = PositionUtil.unpackX(row.pos()) & 31;
            int cz = PositionUtil.unpackZ(row.pos()) & 31;
            int idx = cx + cz * 32;
            buf.putInt(idx * 4, (2 << 8) | 1);
            buf.putInt(4096 + idx * 4, (int) stampSec);
        }
        for (var e : byRegion.entrySet()) {
            int rx = (int) (e.getKey() >> 32);
            int rz = (int) (long) e.getKey();
            Files.write(dir.resolve("r." + rx + "." + rz + ".mca"), e.getValue().array());
        }
    }

    private SqliteLodStore open() throws Exception {
        return openWith(env20());
    }

    private SqliteLodStore openWith(SqliteLodStore.Environment env) throws Exception {
        SqliteLodStore store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env,
                new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000), "startup sweep must complete");
        return store;
    }

    private String metaValue(String key) throws Exception {
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir().resolve("store.db"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=3000");
            try (var rs = st.executeQuery("SELECT v FROM meta WHERE k='" + key + "'")) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static byte[] raw(int seed, int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = (byte) (seed + i);
        return b;
    }

    // ---- the from-state matrix ----

    @Test
    void exactFromStateUpgradesInPlaceAndServesEveryRowPreMigration() throws Exception {
        long p1 = PositionUtil.packPosition(1, 2);
        long p2 = PositionUtil.packPosition(3, 4);
        buildSchema3Store(FP, List.of(new FixtureRow(p1, raw(10, 900), false),
                new FixtureRow(p2, raw(40, 700), false)));
        var store = open();
        try {
            var hit = store.get(OW, p1);
            assertNotNull(hit, "the lazy upgrade must KEEP the v0.9.x rows — a drop here"
                    + " is the 4 GB store self-destructing");
            assertArrayEquals(raw(10, 900), hit.sectionBytes(),
                    "pre-migration serve: FNV-validated original native bytes");
            assertEquals(19, hit.wirefmt(), "the row surfaces its pre-migration format");
            assertTrue(store.migrationStatusToken().contains("migrating="),
                    "status must show the pending walk, got '"
                            + store.migrationStatusToken() + "'");
        } finally {
            store.shutdown();
        }
    }

    @Test
    void foreignFingerprintSchema3DropsAndRebuilds() throws Exception {
        long p1 = PositionUtil.packPosition(1, 2);
        buildSchema3Store("other-registry", List.of(new FixtureRow(p1, raw(10, 900), false)));
        var store = open();
        try {
            assertNull(store.get(OW, p1),
                    "a fingerprint-drifted schema-3 store must drop (its ids are not"
                            + " decodable against this registry), never upgrade");
            assertEquals("", store.migrationStatusToken());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void devEraFourTwentyMetaWithoutWirefmtColumnDropsViaTheLayoutGuard() throws Exception {
        // The C1..C3-era shape (mega-plan structural guard): meta 4∧20 passes the old
        // equality compare while every wirefmt SELECT/INSERT would throw — without the
        // store_layout key this store opened "valid" and latched dead at
        // WRITE_FAILURE_LATCH, recoverable only by hand-deleting store.db.
        long p1 = PositionUtil.packPosition(1, 2);
        buildSchema3Store(FP, List.of(new FixtureRow(p1, raw(10, 900), false)));
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir().resolve("store.db"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE meta SET v='4' WHERE k='schema_version'");
            st.execute("UPDATE meta SET v='20' WHERE k='wire_format_version'");
        }
        var store = open();
        try {
            assertNull(store.get(OW, p1),
                    "the store_layout structural guard must drop the no-wirefmt store");
        } finally {
            store.shutdown();
        }
    }

    // ---- the background walk ----

    private static void awaitWalkDone(SqliteLodStore store) throws Exception {
        for (int i = 0; i < 600; i++) {
            if (store.migrationStatusToken().isEmpty()) return;
            Thread.sleep(50);
        }
        fail("walk did not complete; status=" + store.migrationStatusToken());
    }

    @Test
    void walkRetagsRowsWithCrcHashesAndResumesAcrossARestart() throws Exception {
        var rows = new java.util.ArrayList<FixtureRow>();
        for (int i = 0; i < 100; i++) {
            rows.add(new FixtureRow(PositionUtil.packPosition(i, i + 1), raw(i, 600 + i), false));
        }
        buildSchema3Store(FP, rows);

        // Boot 1: translator NOT wired — the walk must WAIT (serves still work), and
        // the pending state must survive the restart via meta, not the version keys.
        var store = open();
        try {
            Thread.sleep(600);
            assertTrue(store.migrationStatusToken().contains("/100"),
                    "unwired translator: the walk waits, pending survives");
            assertNotNull(store.get(OW, rows.get(0).pos()));
        } finally {
            store.shutdown();
        }

        // Boot 2: translator wired — the walk completes, rows re-serve TRANSLATED
        // under CRC validation with wirefmt=20, bookkeeping clears.
        store = open();
        try {
            store.setLegacyMigrationTranslator(FAKE_TRANSLATOR);
            awaitWalkDone(store);
            var hit = store.get(OW, rows.get(7).pos());
            assertNotNull(hit);
            assertEquals(20, hit.wirefmt(), "migrated rows carry the v20 tag");
            assertArrayEquals(FAKE_TRANSLATOR.apply(raw(7, 607)), hit.sectionBytes(),
                    "the migrated row serves the TRANSLATED body under CRC validation");
        } finally {
            store.shutdown();
        }

        // Boot 3: nothing pending — no walk, no status token.
        store = open();
        try {
            assertEquals("", store.migrationStatusToken(),
                    "completed bookkeeping must not resurrect a walk");
        } finally {
            store.shutdown();
        }
    }

    @Test
    void walkDeletesAnomalyRowsAndFinishes() throws Exception {
        long good = PositionUtil.packPosition(1, 2);
        long bad = PositionUtil.packPosition(3, 4);
        long poison = PositionUtil.packPosition(5, 6);
        buildSchema3Store(FP, List.of(new FixtureRow(good, raw(10, 500), false),
                new FixtureRow(bad, raw(20, 500), true),
                new FixtureRow(poison, raw(30, 500), false)));
        // The CRITICAL-1 shape: a bit-rotted usize would size a multi-GB decompress
        // allocation on the batcher — the walk must bound-check and resolve the row,
        // never attempt it (the old shape OOM'd into an endless batch-retry → latch).
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir().resolve("store.db"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE lods_1 SET usize=" + (1 << 30) + " WHERE pos=" + poison);
        }
        var store = open();
        try {
            store.setLegacyMigrationTranslator(FAKE_TRANSLATOR);
            awaitWalkDone(store);
            assertNotNull(store.get(OW, good), "the good row migrates");
            assertNull(store.get(OW, bad),
                    "an unparseable row is DELETED (derived data), never retried forever");
            assertNull(store.get(OW, poison),
                    "an out-of-bounds usize is resolved WITHOUT attempting the allocation");
            assertEquals(2, store.diagnostics().getMigrateAnomalies(),
                    "both anomaly flavors counted (review #4: deletion must be visible)");
        } finally {
            store.shutdown();
        }
    }

    @Test
    void depositOverANineteenRowFlipsItToV20AtTheUpsert() throws Exception {
        // The §3.1 pin (review #7, shipped untested): the ON CONFLICT clause carries
        // wirefmt=excluded.wirefmt, so a fresh deposit over a pre-migration row retags
        // it in ONE upsert — without that clause the row keeps wirefmt=19 while its
        // hashes go CRC, and every subsequent serve FNV-validates a CRC row → the
        // purge ladder deletes good rows.
        long p1 = PositionUtil.packPosition(1, 2);
        buildSchema3Store(FP, List.of(new FixtureRow(p1, raw(10, 800), false)));
        var store = open(); // translator NOT wired — the walk waits, only the deposit acts
        try {
            byte[] fresh = raw(90, 640);
            long newer = System.currentTimeMillis() / 1000L + 100;
            assertTrue(store.deposit(OW, p1, fresh, newer, newer));
            LodStoreService.StoreHit hit = null;
            for (int i = 0; i < 400; i++) {
                hit = store.get(OW, p1);
                if (hit != null && hit.wirefmt() == WIRE_20) break;
                Thread.sleep(25);
            }
            assertNotNull(hit);
            assertEquals(WIRE_20, hit.wirefmt(),
                    "the upsert's wirefmt=excluded.wirefmt must retag the row");
            assertArrayEquals(fresh, hit.sectionBytes(),
                    "the deposited body serves under CRC validation");
        } finally {
            store.shutdown();
        }
    }

    @Test
    void tsLoserDepositOverANineteenRowLeavesItNineteen() throws Exception {
        // Latest-wins by STORED ts: a losing deposit must change NOTHING — including
        // the wirefmt tag (a half-applied retag would CRC-validate FNV hashes).
        long p1 = PositionUtil.packPosition(1, 2);
        long p2 = PositionUtil.packPosition(5, 6);
        buildSchema3Store(FP, List.of(new FixtureRow(p1, raw(10, 800), false)));
        var store = open();
        try {
            long older = System.currentTimeMillis() / 1000L - 3600;
            assertTrue(store.deposit(OW, p1, raw(90, 640), older, older));
            // Sync barrier: a WINNING deposit at another pos proves the batch drained.
            long now = System.currentTimeMillis() / 1000L + 100;
            assertTrue(store.deposit(OW, p2, raw(50, 320), now, now));
            for (int i = 0; i < 400 && store.get(OW, p2) == null; i++) {
                Thread.sleep(25);
            }
            assertNotNull(store.get(OW, p2), "barrier deposit must land");
            var hit = store.get(OW, p1);
            assertNotNull(hit);
            assertEquals(19, hit.wirefmt(), "a ts-loser deposit leaves the 19-row intact");
            assertArrayEquals(raw(10, 800), hit.sectionBytes());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void aFaultedWalkBatchRetractsRowsAndWatermarkTogetherAndRetries() throws Exception {
        // Review #8: the watermark and its batch's row UPDATEs commit as ONE txn. A
        // fault between the UPDATEs and the commit must retract BOTH — a surviving
        // watermark over rolled-back rows would skip 64 rows forever (still 19, still
        // FNV, walk "complete"); surviving rows under a rolled-back watermark would
        // re-translate already-translated bodies.
        var rows = new java.util.ArrayList<FixtureRow>();
        for (int i = 0; i < 100; i++) {
            rows.add(new FixtureRow(PositionUtil.packPosition(i, i + 1), raw(i, 600), false));
        }
        buildSchema3Store(FP, rows);
        var store = open();
        try {
            store.failNextMigrationBatchesForTest(1);
            store.setLegacyMigrationTranslator(FAKE_TRANSLATOR);
            awaitWalkDone(store);
            assertEquals(0, store.pendingInjectedMigrationFaultsForTest(),
                    "the injected fault must actually have fired");
            for (int i = 0; i < 100; i++) {
                var hit = store.get(OW, rows.get(i).pos());
                assertNotNull(hit, "row " + i + " lost to the faulted batch");
                assertEquals(WIRE_20, hit.wirefmt(), "row " + i + " skipped by a"
                        + " watermark that outlived its rolled-back batch");
                assertArrayEquals(FAKE_TRANSLATOR.apply(raw(i, 600)), hit.sectionBytes(),
                        "row " + i + " must translate EXACTLY once");
            }
            assertEquals(100, store.diagnostics().getMigratedRows(),
                    "the rolled-back batch must not double-count");
            assertEquals(0, store.diagnostics().getMigrateAnomalies());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void walkRetagsAllAirRowsInsteadOfDeletingThem() throws Exception {
        // C6 review M-1: usize 0 is the LEGITIMATE all-air shape — the C4 poison
        // bound (usize <= 0 -> anomaly delete) swallowed it, so a real v0.9.x
        // upgrade deleted every End/void column and reported them as corruption.
        long allAir = PositionUtil.packPosition(1, 2);
        long content = PositionUtil.packPosition(3, 4);
        buildSchema3Store(FP, List.of(new FixtureRow(allAir, new byte[0], false),
                new FixtureRow(content, raw(10, 500), false)));
        var store = open();
        try {
            store.setLegacyMigrationTranslator(FAKE_TRANSLATOR);
            awaitWalkDone(store);
            var hit = store.get(OW, allAir);
            assertNotNull(hit, "the all-air row must SURVIVE the walk");
            assertEquals(0, hit.sectionBytes().length, "still all-air");
            assertEquals(WIRE_20, hit.wirefmt(), "retagged, no body work");
            assertEquals(0, store.diagnostics().getMigrateAnomalies(),
                    "an all-air row is never an anomaly");
            assertNotNull(store.get(OW, content), "the content row migrates beside it");
        } finally {
            store.shutdown();
        }
    }

    @Test
    void dropAllResetsTheWalkBookkeeping() throws Exception {
        var rows = new java.util.ArrayList<FixtureRow>();
        for (int i = 0; i < 50; i++) {
            rows.add(new FixtureRow(PositionUtil.packPosition(i, i + 1), raw(i, 600), false));
        }
        buildSchema3Store(FP, rows);
        var store = open();
        try {
            assertTrue(store.migrationStatusToken().contains("migrating="));
            store.requestDropAllRows();
            for (int i = 0; i < 400 && !store.migrationStatusToken().isEmpty(); i++) {
                Thread.sleep(25);
            }
            assertEquals("", store.migrationStatusToken(),
                    "DropAll drops the walk's subject rows — its bookkeeping resets with them");
        } finally {
            store.shutdown();
        }
    }

    /**
     * v0.13.1 fix-review fold (the residual escape): {@code finishMigration} must
     * VERIFY no 19-row remains before retiring the walk marker. The walk's documented
     * residual — a row whose translate anomaly'd AND whose fallback DELETE also
     * failed stays tagged 19 BEHIND the watermark, never revisited — used to outlive
     * {@code migrate_pending}, and under the permutation ladder a flagless 19-row is
     * a wrong-data KEEP (legacy bytes mistranslated through the permuted registry).
     * The verifying finish writes the permanent {@code migrate_residual} marker
     * instead, and the ladder's legacy rung reads BOTH markers.
     */
    @Test
    void residualNineteenRowBehindTheWatermarkBlocksThePermutationKeep() throws Exception {
        long pos = PositionUtil.packPosition(1, 1);
        buildSchema3Store(FP, List.of(new FixtureRow(pos, raw(1, 300), false)));
        // Boot 1 upgrades in place (row tagged 19, migrate_pending=1); no translator,
        // so the walk waits and the 19-row survives the boot.
        open().shutdown();
        // Hand-advance the watermark PAST the row: byte-for-byte the end-state of the
        // swallowed delete-failure (the walk never looks behind its watermark).
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + storeDir().resolve("store.db"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=3000");
            st.executeUpdate("INSERT OR REPLACE INTO meta (k, v) VALUES"
                    + " ('migrate_progress_1','" + (pos + 1) + "')");
        }

        // Boot 2 with the translator wired AND a content-fingerprinted env: the walk
        // finds nothing past the watermark, retires — and the verifying finish probes
        // the table, finds the stuck 19-row, and writes migrate_residual.
        SqliteLodStore resumed = openWith(env20Fp(FP, "bsc:cc/bioc:aa"));
        try {
            resumed.setLegacyMigrationTranslator(FAKE_TRANSLATOR);
            awaitWalkDone(resumed);
        } finally {
            resumed.shutdown();
        }
        assertEquals("1", metaValue("migrate_residual"),
                "the verifying finish must mark the surviving 19-row");
        assertNull(metaValue("migrate_pending"),
                "the walk bookkeeping still retires — re-arming cannot delete the row");

        // A later pure permutation must DROP: the residual row would mistranslate.
        SqliteLodStore permuted = openWith(env20Fp("fp-permuted", "bsc:cc/bioc:aa"));
        try {
            assertEquals(SqliteLodStore.MetaVerdict.Kind.DROP,
                    permuted.lastMetaVerdictForTest().kind(),
                    "a flagless KEEP here would serve the 19-row as wrong blocks");
            assertTrue(permuted.lastMetaVerdictForTest().detail().contains("legacy"),
                    "the drop must name the legacy condition, got: "
                            + permuted.lastMetaVerdictForTest().detail());
            assertNull(permuted.get(OW, pos));
        } finally {
            permuted.shutdown();
        }
    }
}
