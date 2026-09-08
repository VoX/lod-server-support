package dev.vox.lss.common.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Interrupted policy invalidation must remain incomplete across a normal reopen. */
class SqliteLodStoreMaskShutdownTest {
    @TempDir Path tmp;
    private static final String DIM = "minecraft:overworld";
    private SqliteLodStore open(String mask, Function<String, Path> resolver) {
        var env = new SqliteLodStore.Environment(tmp.resolve("store"), "store-mask-test", 20,
                resolver, d -> mask, 0);
        var store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env, new LodStoreDiagnostics());
        assertNotNull(store);
        return store;
    }
    @Test void interruptedMaskDropMustNotCommitNewFingerprintOverSurvivingRows() throws Exception {
        Path region = Files.createDirectories(tmp.resolve("region"));
        var header = ByteBuffer.allocate(8192);
        header.putInt(0, 513);
        header.putInt(4096, (int)(System.currentTimeMillis()/1000L-1000));
        Files.write(region.resolve("r.0.0.mca"), header.array());
        var original = open("off", d -> region);
        try {
            assertTrue(original.awaitSweep(10_000));
            assertTrue(original.deposit(DIM, 0L, new byte[]{1,2,3,4}, 100L));
            long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (original.get(DIM, 0L) == null && System.nanoTime()<end) Thread.sleep(10);
            assertNotNull(original.get(DIM, 0L));
        } finally { original.shutdown(); }
        var enteredResolver = new CountDownLatch(1);
        var holdUntilShutdown = new CountDownLatch(1);
        var interrupted = open("antixray:new-hidden-block-list", d -> {
            enteredResolver.countDown();
            try {
                holdUntilShutdown.await();
            } catch (InterruptedException expectedShutdown) {
                // Gate the actual interleaving: shutdown starts after runSweep's
                // entry check and before dropDimensionRows' first shutdown check.
            }
            return region;
        });
        try {
            assertTrue(enteredResolver.await(10, TimeUnit.SECONDS));
            interrupted.shutdown();
        } finally {
            holdUntilShutdown.countDown();
            interrupted.shutdown();
        }
        var reopened = open("antixray:new-hidden-block-list", d -> region);
        try {
            assertTrue(reopened.awaitSweep(10_000));
            assertNull(reopened.get(DIM, 0L),
                    "Old unmasked bytes must not survive an interrupted policy-change drop");
        } finally { reopened.shutdown(); }
    }

    @Test void interruptedBetweenCommittedBatchesKeepsOldFingerprint() throws Exception {
        Path region = Files.createDirectories(tmp.resolve("region"));
        var original = open("old-policy", d -> region);
        try {
            assertTrue(original.awaitSweep(10_000));
            assertTrue(original.deposit(DIM, 0L, new byte[]{1, 2, 3}, 100L));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (original.get(DIM, 0L) == null && System.nanoTime() < deadline) Thread.sleep(10);
            assertNotNull(original.get(DIM, 0L));
        } finally { original.shutdown(); }
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("store/store.db"));
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            c.setAutoCommit(false);
            // More than DROP_BATCH_ROWS; the first batch commits before the listener.
            for (int n = 1; n <= 4096; n++) {
                st.executeUpdate("INSERT INTO lods_1 SELECT " + n
                        + ",ts,chash,usize,src_stamp,fhash,wirefmt,blob FROM lods_1 WHERE pos=0");
            }
            c.commit();
        }
        var resolverEntered = new CountDownLatch(1);
        var allowDrop = new CountDownLatch(1);
        var batchCommitted = new CountDownLatch(1);
        var holdUntilShutdown = new CountDownLatch(1);
        var interrupted = open("new-policy", d -> {
            resolverEntered.countDown();
            try { allowDrop.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return region;
        });
        try {
            assertTrue(resolverEntered.await(10, TimeUnit.SECONDS));
            interrupted.setSweepDropListener((d, positions) -> {
                batchCommitted.countDown();
                try { holdUntilShutdown.await(); } catch (InterruptedException expected) { /* shutdown */ }
            });
            allowDrop.countDown();
            assertTrue(batchCommitted.await(10, TimeUnit.SECONDS));
            interrupted.shutdown();
        } finally {
            allowDrop.countDown();
            holdUntilShutdown.countDown();
            interrupted.shutdown();
        }
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            try (var rs = st.executeQuery("SELECT mask_fingerprint FROM dims WHERE id=1")) {
                assertTrue(rs.next());
                assertEquals("old-policy", rs.getString(1), "partial cleanup cannot certify the new policy");
            }
            try (var rs = st.executeQuery("SELECT COUNT(*) FROM lods_1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "one committed batch, one surviving row");
            }
        }
        var resumed = open("new-policy", d -> region);
        try { assertTrue(resumed.awaitSweep(10_000)); } finally { resumed.shutdown(); }
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            try (var rs = st.executeQuery("SELECT mask_fingerprint FROM dims WHERE id=1")) {
                assertTrue(rs.next()); assertEquals("new-policy", rs.getString(1));
            }
            try (var rs = st.executeQuery("SELECT COUNT(*) FROM lods_1")) {
                assertTrue(rs.next()); assertEquals(0, rs.getInt(1));
            }
        }
    }
}
