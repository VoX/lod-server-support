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

/** External review regression probe; expected to RED on merged 1b544494. */
class ReviewMaskShutdownTest {
    @TempDir Path tmp;
    private static final String DIM = "minecraft:overworld";
    private SqliteLodStore open(String mask, Function<String, Path> resolver) {
        var env = new SqliteLodStore.Environment(tmp.resolve("store"), "1.21.1-review", 20,
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
}
