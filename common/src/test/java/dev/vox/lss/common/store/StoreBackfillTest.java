package dev.vox.lss.common.store;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 4 backfill driver against a REAL SqliteLodStore in a temp dir: traversal
 * order (nearest-origin region first), skip-if-row-already-present, per-region
 * resumability across a store restart, stop semantics, and the restraint pause gates.
 * Region files are synthesized as bare location headers (the driver reads only the
 * location table; the fake ColumnReader supplies the "NBT" bytes).
 */
class StoreBackfillTest {

    private static final String OW = "minecraft:overworld";

    @TempDir
    Path tmp;

    private Path regionDir() {
        return this.tmp.resolve("region");
    }

    /** Region with the given chunk indices present (bare 4 KiB location header). */
    private void writeRegion(int rx, int rz, int... presentIdx) throws Exception {
        Files.createDirectories(regionDir());
        ByteBuffer buf = ByteBuffer.allocate(4096);
        for (int idx : presentIdx) buf.putInt(idx * 4, (2 << 8) | 1);
        Files.write(regionDir().resolve("r." + rx + "." + rz + ".mca"), buf.array());
    }

    private SqliteLodStore openStore() throws Exception {
        var env = new SqliteLodStore.Environment(this.tmp.resolve("store"), "26.2-test", 18,
                d -> regionDir(), d -> "", 0);
        SqliteLodStore store = SqliteLodStore.createOrNull(LodStoreMode.FULL, env,
                new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        return store;
    }

    private StoreBackfill backfill(SqliteLodStore store, StoreBackfill.ColumnReader reader,
                                   AtomicBoolean headroom, AtomicBoolean tickOk) {
        return new StoreBackfill(store, d -> regionDir(), d -> new long[]{0, 0},
                List.of(OW), reader, headroom::get, tickOk::get);
    }

    private static void awaitDone(StoreBackfill bf) throws Exception {
        // 20 s budget (review T7): the pacing test's worst case (~3 s of rate-window
        // sleeps + per-region drain awaits) sat at 1.25x inside the old 10 s.
        for (int i = 0; i < 800 && bf.isRunning(); i++) Thread.sleep(25);
        assertTrue(!bf.isRunning(), "backfill must finish; status: " + bf.statusLine());
    }

    @Test
    void walksNearestRegionFirstAndDepositsPresentChunks() throws Exception {
        writeRegion(3, 0, 0, 1);   // far region: chunks (96,0), (97,0)
        writeRegion(0, 0, 5);      // near region: chunk (5,0)
        var order = new ConcurrentLinkedQueue<Long>();
        SqliteLodStore store = openStore();
        var bf = backfill(store, (dim, cx, cz) -> {
            order.add(PositionUtil.packPosition(cx, cz));
            return new byte[]{(byte) cx, (byte) cz, 9};
        }, new AtomicBoolean(true), new AtomicBoolean(true));
        assertTrue(bf.start());
        awaitDone(bf);

        assertEquals(PositionUtil.packPosition(5, 0), order.peek(),
                "the origin-nearest region must be walked first");
        assertEquals(3, order.size(), "every present chunk read exactly once");
        for (int i = 0; i < 400; i++) {
            if (store.get(OW, PositionUtil.packPosition(96, 0)) != null) break;
            Thread.sleep(25);
        }
        assertNotNull(store.get(OW, PositionUtil.packPosition(5, 0)),
                "backfilled column must be servable");
        assertEquals(3, store.diagnostics().getBackfillDeposits());
        store.shutdown();
    }

    /** The terminal statusLine is a SCRIPT-CONSUMED CONTRACT (PERF Phase 0 item 3):
     *  backfill_profile.sh parses "complete|stopped: R regions, D deposited, S skipped,
     *  E errors, P pauses" out of the "Store backfill " INFO line into meta.json — walk
     *  seconds + these counters define the Phase 2 deposits/s gate. A rewording fails
     *  SILENT on the harness side (its sed yields nothing and the run reds as
     *  parse_ok=false), so it must red HERE first (B0 review N6). */
    @Test
    void terminalStatusLineIsAScriptConsumedContract() throws Exception {
        writeRegion(0, 0, 5, 6);
        SqliteLodStore store = openStore();
        var bf = backfill(store, (dim, cx, cz) -> new byte[]{1},
                new AtomicBoolean(true), new AtomicBoolean(true));
        assertTrue(bf.start());
        awaitDone(bf);
        String status = bf.statusLine();
        assertTrue(status.matches(
                "complete: \\d+ regions, \\d+ deposited, \\d+ skipped, \\d+ errors, \\d+ pauses"),
                "backfill_profile.sh parses this exact shape, got: " + status);
        assertTrue(status.startsWith("complete: 1 regions, 2 deposited, 0 skipped, 0 errors"),
                status);
        store.shutdown();
    }

    @Test
    void skipsColumnsTheStoreAlreadyHas() throws Exception {
        writeRegion(0, 0, 5, 6);
        SqliteLodStore store = openStore();
        long present = PositionUtil.packPosition(5, 0);
        store.deposit(OW, present, new byte[]{1}, 100);
        for (int i = 0; i < 400 && store.get(OW, present) == null; i++) Thread.sleep(25);
        var reads = new ConcurrentLinkedQueue<Long>();
        var bf = backfill(store, (dim, cx, cz) -> {
            reads.add(PositionUtil.packPosition(cx, cz));
            return new byte[]{2};
        }, new AtomicBoolean(true), new AtomicBoolean(true));
        bf.start();
        awaitDone(bf);
        assertEquals(1, reads.size(), "the already-present column must not be re-read");
        assertEquals(PositionUtil.packPosition(6, 0), reads.peek());
        assertEquals(1, store.diagnostics().getBackfillSkips());
        store.shutdown();
    }

    @Test
    void finishedRegionsResumeAsDoneAcrossAStoreRestart() throws Exception {
        writeRegion(0, 0, 5);
        SqliteLodStore store = openStore();
        var bf = backfill(store, (dim, cx, cz) -> new byte[]{1},
                new AtomicBoolean(true), new AtomicBoolean(true));
        bf.start();
        awaitDone(bf);
        for (int i = 0; i < 400 && !store.isBackfillRegionDone(OW, 0, 0); i++) Thread.sleep(25);
        assertTrue(store.isBackfillRegionDone(OW, 0, 0), "region must be marked done");
        store.shutdown();

        SqliteLodStore reopened = openStore();
        assertTrue(reopened.isBackfillRegionDone(OW, 0, 0),
                "the progress table must survive a restart (resumability)");
        var reads = new ConcurrentLinkedQueue<Long>();
        var bf2 = backfill(reopened, (dim, cx, cz) -> {
            reads.add(1L);
            return new byte[]{1};
        }, new AtomicBoolean(true), new AtomicBoolean(true));
        bf2.start();
        awaitDone(bf2);
        assertEquals(0, reads.size(), "a done region must be skipped on resume");
        reopened.shutdown();
    }

    @Test
    void pauseGateHoldsWhileRestrainedAndStopExits() throws Exception {
        writeRegion(0, 0, 1, 2, 3);
        SqliteLodStore store = openStore();
        var headroom = new AtomicBoolean(false); // restrained from the start
        var bf = backfill(store, (dim, cx, cz) -> new byte[]{1},
                headroom, new AtomicBoolean(true));
        bf.start();
        for (int i = 0; i < 200 && !bf.statusLine().startsWith("paused"); i++) Thread.sleep(25);
        assertTrue(bf.statusLine().startsWith("paused"),
                "no-headroom must pause, not read: " + bf.statusLine());
        assertEquals(0, store.diagnostics().getBackfillReads(),
                "no reads while restrained");
        assertTrue(bf.stop(), "stop while paused must be accepted");
        awaitDone(bf);
        assertTrue(bf.statusLine().startsWith("stopped"), bf.statusLine());
        store.shutdown();
    }

    /** Review MAJOR: a region with read errors must NOT be done-marked — transient IO
     *  trouble would otherwise become a permanent warm-hole resumability never
     *  revisits. The unmarked region re-walks (cheaply, via hasRow) next run. */
    @Test
    void regionWithReadErrorsIsNotMarkedDone() throws Exception {
        writeRegion(0, 0, 1, 2);
        SqliteLodStore store = openStore();
        var bf = backfill(store, (dim, cx, cz) -> {
            if (cx == 1) throw new IllegalStateException("io storm");
            return new byte[]{1};
        }, new AtomicBoolean(true), new AtomicBoolean(true));
        bf.start();
        awaitDone(bf);
        // Await the QUEUE, not wall-clock (review T6): a fixed sleep let a wrongly
        // enqueued mark drain after the assert on a loaded box — a silent false PASS
        // for a confirmed review MAJOR.
        assertTrue(store.awaitDepositQueueEmpty(5000), "batcher must drain");
        assertTrue(!store.isBackfillRegionDone(OW, 0, 0),
                "an errored region must stay unmarked for the next walk");
        assertTrue(store.diagnostics().getErrors() >= 1,
                "backfill read failures must count store.errors (operator visibility)");
        store.shutdown();
    }

    /** R3-M1: the documented remediation pairing is "invalidate all -> re-backfill";
     *  done-marks surviving the drop made the re-backfill enumerate 0 regions and the
     *  store only ever re-warmed where players walked. */
    @Test
    void invalidateAllResetsBackfillProgressSoTheWalkCanRewarm() throws Exception {
        writeRegion(0, 0, 5);
        SqliteLodStore store = openStore();
        var bf = backfill(store, (dim, cx, cz) -> new byte[]{1},
                new AtomicBoolean(true), new AtomicBoolean(true));
        bf.start();
        awaitDone(bf);
        for (int i = 0; i < 400 && !store.isBackfillRegionDone(OW, 0, 0); i++) Thread.sleep(25);
        assertTrue(store.isBackfillRegionDone(OW, 0, 0), "region must be done-marked first");

        store.requestDropAllRows();
        for (int i = 0; i < 400 && store.isBackfillRegionDone(OW, 0, 0); i++) Thread.sleep(25);
        assertTrue(!store.isBackfillRegionDone(OW, 0, 0),
                "invalidate-all must reset the done-marks with the rows they describe");

        var reads = new ConcurrentLinkedQueue<Long>();
        var bf2 = backfill(store, (dim, cx, cz) -> {
            reads.add(1L);
            return new byte[]{1};
        }, new AtomicBoolean(true), new AtomicBoolean(true));
        bf2.start();
        awaitDone(bf2);
        assertEquals(1, reads.size(), "the dropped column must be re-read and re-warmed");
        store.shutdown();
    }

    /** Wiring pin (store-backfill-tuning-plan.md §3): the ctor's columnsPerSecond value
     *  actually paces the walk — cps=2 over a 6-chunk region must complete >= 2 rate
     *  windows. Asserts on the driver's window COUNTER, never wall-clock (timing
     *  asserts flake on loaded boxes; the counter increments deterministically every
     *  cps visited columns regardless of how long the sleeps really took). */
    @Test
    void columnsPerSecondCtorValuePacesTheWalkInRateWindows() throws Exception {
        writeRegion(0, 0, 1, 2, 3, 4, 5, 6);
        SqliteLodStore store = openStore();
        var bf = new StoreBackfill(store, d -> regionDir(), d -> new long[]{0, 0},
                List.of(OW), (dim, cx, cz) -> new byte[]{1},
                () -> true, () -> true, 2);
        assertTrue(bf.start());
        awaitDone(bf);
        assertTrue(bf.rateWindowCount() >= 2,
                "6 visited columns at 2 col/s must complete >= 2 rate windows, got "
                        + bf.rateWindowCount());
        assertEquals(6, store.diagnostics().getBackfillDeposits(),
                "pacing must slow the walk, never drop columns");
        store.shutdown();
    }

    /** Cap-behavior §3 estimate arithmetic (the log prose is deliberately unpinned):
     *  planned region-file bytes x the measured 0.72 LOD/region ratio. */
    @Test
    void estimateLodBytesAppliesTheMeasuredRatio() {
        assertEquals(0, StoreBackfill.estimateLodBytes(0));
        assertEquals(720_000L, StoreBackfill.estimateLodBytes(1_000_000L));
    }

    /** Cap-behavior §3's other estimate half (review MINOR: the ratio alone let a
     *  refactor drop the estimate from the walk-start line unnoticed): describePlan
     *  must sum the PLANNED region files into the line, carry the cap|uncapped token,
     *  and append the stop consequence exactly when the estimate exceeds an active
     *  cap. Asserts computed numbers via the same package-visible helpers, not prose. */
    @Test
    void describePlanSumsPlannedRegionFilesAndFlagsACapExceedingEstimate() throws Exception {
        writeRegion(0, 0, 1);   // two 4096-byte header files
        writeRegion(1, 0, 1);
        SqliteLodStore store = openStore();
        var bf = backfill(store, (dim, cx, cz) -> null,
                new AtomicBoolean(true), new AtomicBoolean(true));
        var plan = List.of(
                new StoreBackfill.RegionRef(OW, 0, 0, regionDir().resolve("r.0.0.mca"), 0),
                new StoreBackfill.RegionRef(OW, 1, 0, regionDir().resolve("r.1.0.mca"), 1));
        String expectedSize = StoreBackfill.formatSize(StoreBackfill.estimateLodBytes(8192));

        String uncapped = bf.describePlan(plan, Long.MAX_VALUE);
        // "region(s) to process" is script-consumed: backfill_profile.sh greps this
        // wording for the walk-START timestamp (B0 review N6 — the terminal-line pin's
        // start-side sibling).
        assertTrue(uncapped.contains("2 region(s) to process"), uncapped);
        assertTrue(uncapped.contains("~" + expectedSize), uncapped);
        assertTrue(uncapped.contains("(uncapped)"), uncapped);
        assertTrue(!uncapped.contains("STOP"), "no stop warning without a cap: " + uncapped);

        String capped = bf.describePlan(plan, 1024L); // estimate (5898) > cap
        assertTrue(capped.contains("cap: "), capped);
        assertTrue(capped.contains("STOP at the cap"),
                "estimate above an active cap must warn up front: " + capped);
        store.shutdown();
    }

    /** Cap-behavior §3: a walk against a store at >= 95% of an ACTIVE cap hard-stops
     *  BEFORE reading anything (each deposit into a capped store evicts an OLDER,
     *  nearer-spawn row — provably wasted work), leaves the region unmarked, and a
     *  re-run after raising the cap resumes exactly there. */
    @Test
    void walkHardStopsAtAnActiveCapAndResumesAfterRaisingIt() throws Exception {
        writeRegion(0, 0, 5);
        // 1 KB active cap — the fresh store DB alone exceeds 95% of it after the first
        // gauge refresh (production caps floor at 64 MB via config; the raw
        // Environment is the test seam for "store already at its cap").
        var cappedEnv = new SqliteLodStore.Environment(this.tmp.resolve("store"),
                "26.2-test", 18, d -> regionDir(), d -> "", 0, 1024L);
        SqliteLodStore store = SqliteLodStore.createOrNull(LodStoreMode.FULL, cappedEnv,
                new LodStoreDiagnostics());
        assertNotNull(store);
        assertTrue(store.awaitSweep(10_000));
        for (int i = 0; i < 400 && store.diagnostics().getDbBytes() == 0; i++) Thread.sleep(25);
        assertTrue(store.diagnostics().getDbBytes() > 0, "gauge must refresh before the walk");

        var reads = new ConcurrentLinkedQueue<Long>();
        var bf = new StoreBackfill(store, d -> regionDir(), d -> new long[]{0, 0},
                List.of(OW), (dim, cx, cz) -> {
                    reads.add(1L);
                    return new byte[]{1};
                }, () -> true, () -> true);
        assertTrue(bf.start());
        awaitDone(bf);
        assertTrue(bf.statusLine().startsWith("capped:"),
                "walk must hard-stop at the cap: " + bf.statusLine());
        assertEquals(0, reads.size(), "no reads may happen against a store at its cap");
        assertTrue(!store.isBackfillRegionDone(OW, 0, 0),
                "the unwalked region must stay unmarked (the resume point)");
        store.shutdown();

        SqliteLodStore raised = openStore(); // uncapped env, SAME store dir
        var bf2 = backfill(raised, (dim, cx, cz) -> {
            reads.add(1L);
            return new byte[]{1};
        }, new AtomicBoolean(true), new AtomicBoolean(true));
        bf2.start();
        awaitDone(bf2);
        assertEquals(1, reads.size(), "raising the cap must resume the walk exactly there");
        for (int i = 0; i < 400 && !raised.isBackfillRegionDone(OW, 0, 0); i++) Thread.sleep(25);
        assertTrue(raised.isBackfillRegionDone(OW, 0, 0));
        raised.shutdown();
    }

    /** C2 (review-fixes round): backfill deposits carry an ACQUISITION-time src_stamp,
     *  stamped BEFORE the read (R1-M2) — deposit-time stamping would make a save
     *  landing mid-read permanently sweep-invisible. The slow reader makes the two
     *  stampings ~2.5 s apart, far beyond the 1 s stamp granularity. */
    @Test
    void backfillDepositSrcStampPredatesTheRead() throws Exception {
        writeRegion(0, 0, 5);
        SqliteLodStore store = openStore();
        var readStartSecond = new java.util.concurrent.atomic.AtomicLong();
        var bf = backfill(store, (dim, cx, cz) -> {
            readStartSecond.set(System.currentTimeMillis() / 1000L);
            Thread.sleep(2500); // a region save could land here
            return new byte[]{1};
        }, new AtomicBoolean(true), new AtomicBoolean(true));
        bf.start();
        awaitDone(bf);
        assertTrue(store.awaitDepositQueueEmpty(5000));
        // Poll: the shared-txn COMMIT lands on the batcher's next idle tick (~200 ms
        // after the queue empties), and raw SQL sees only committed data.
        Long srcStamp = null;
        for (int i = 0; i < 400 && srcStamp == null; i++) {
            srcStamp = sqlSrcStampOrNull(PositionUtil.packPosition(5, 0));
            if (srcStamp == null) Thread.sleep(25);
        }
        assertNotNull(srcStamp, "deposited row must commit");
        assertTrue(srcStamp <= readStartSecond.get(),
                "src_stamp must predate the read (deposit-time stamping is ~2.5 s later): "
                        + srcStamp + " vs read-start " + readStartSecond.get());
        store.shutdown();
    }

    /** Raw src_stamp for one overworld row (bypasses get() so nothing masks it);
     *  null while the row is missing or not yet committed. */
    private Long sqlSrcStampOrNull(long pos) throws Exception {
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + this.tmp.resolve("store").resolve("store.db"));
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=3000");
            int dimId;
            try (var rs = st.executeQuery("SELECT id FROM dims WHERE name='" + OW + "'")) {
                if (!rs.next()) return null;
                dimId = rs.getInt(1);
            }
            try (var rs = st.executeQuery(
                    "SELECT src_stamp FROM lods_" + dimId + " WHERE pos=" + pos)) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    @Test
    void startIsIdempotentWhileRunning() throws Exception {
        writeRegion(0, 0, 1);
        SqliteLodStore store = openStore();
        var gate = new AtomicBoolean(false); // hold it paused so it stays running
        var bf = backfill(store, (dim, cx, cz) -> new byte[]{1},
                gate, new AtomicBoolean(true));
        assertTrue(bf.start());
        assertTrue(!bf.start(), "second start while running must be rejected");
        gate.set(true);
        awaitDone(bf);
        store.shutdown();
    }

    /** v0.11.0 stage C (SET plan Part 2): the remaining-estimate arithmetic table over
     *  the package-visible static — every branch pinned directly (review F5: the
     *  original trio only ever asserted degenerate zeros). The `running:` line itself
     *  is UNPINNED by scripts (only the terminal lines are contracts). */
    @Test
    void remainingEstimateArithmeticCoversBothBranchesAndTheClamps() {
        // Measured branch: 3 planned, 1 walked with 2 present chunks seen -> avg 2,
        // 2 regions x 2 = 4 columns.
        assertEquals("~2 regions / ~4 columns left",
                StoreBackfill.remainingEstimateFor(3, 1, 2));
        // Integer-division floor of the average, not a float render.
        assertEquals("~1 regions / ~2 columns left",
                StoreBackfill.remainingEstimateFor(3, 2, 5));
        // Pre-first-region fallback: no measured average yet -> <= worst case at
        // 1024 columns/region, and no divide-by-zero.
        assertEquals("~2 regions / <=2048 columns left",
                StoreBackfill.remainingEstimateFor(2, 0, 0));
        assertEquals("~0 regions / <=0 columns left",
                StoreBackfill.remainingEstimateFor(0, 0, 0));
        // walked can pass total when a plan shrinks mid-walk: remaining clamps at 0.
        assertEquals("~0 regions / ~0 columns left",
                StoreBackfill.remainingEstimateFor(1, 2, 5));
    }

    /** The walk actually maintains the volatile counters the estimate reads (the
     *  static table above pins arithmetic, this pins the plumbing): a reader latch
     *  parks the walker mid-region-1 of a two-region plan, and the concurrent estimate
     *  must read walked=1/total=2 with region 1's measured average. */
    @Test
    void remainingEstimateReflectsMidWalkProgressUnderALatchParkedReader() throws Exception {
        writeRegion(0, 0, 5, 6);       // near, walks first: 2 present chunks
        writeRegion(3, 0, 0, 1, 2, 3); // far: 4 present chunks
        SqliteLodStore store = openStore();
        var parked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new AtomicBoolean(true);
        var bf = backfill(store, (dim, cx, cz) -> {
            if (first.compareAndSet(true, false)) {
                parked.countDown();
                try {
                    release.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new byte[]{1};
        }, new AtomicBoolean(true), new AtomicBoolean(true));
        assertTrue(bf.start());
        assertTrue(parked.await(20, java.util.concurrent.TimeUnit.SECONDS),
                "walker must reach the first region-1 read");
        // Parked inside region 1's chunk loop: walked=1 (bumped at region visit),
        // seen=2 (region 1's present count), so remaining=1 at avg 2.
        assertEquals("~1 regions / ~2 columns left", bf.remainingEstimate(),
                "mid-walk the estimate must show the un-walked far region");
        release.countDown();
        awaitDone(bf);
        assertEquals("~0 regions / ~0 columns left", bf.remainingEstimate(),
                "at walk end the estimate must show nothing left");
        store.shutdown();
    }

    /** A SECOND start() must never show the prior run's progress (SET review): the
     *  counters reset at walk start. Deterministic (review T1/F6 — the old version
     *  raced the async done-mark commit and asserted a shape both outcomes produced):
     *  run 1's done-marks are POLLED committed before run 2 plans, two fresh regions
     *  make run 2's plan size 2, and the latch-parked mid-walk estimate distinguishes
     *  reset (walked=1, seen=1 -> "~1 / ~1") from carried-over counters (walked=2,
     *  seen=3 -> "~0 / ..."). */
    @Test
    void progressResetsOnASecondStart() throws Exception {
        writeRegion(0, 0, 5, 6); // run 1: one region, 2 present chunks
        SqliteLodStore store = openStore();
        var parkRun2 = new AtomicBoolean(false);
        var parked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var bf = backfill(store, (dim, cx, cz) -> {
            if (parkRun2.get() && parked.getCount() > 0) {
                parked.countDown();
                try {
                    release.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new byte[]{1};
        }, new AtomicBoolean(true), new AtomicBoolean(true));
        assertTrue(bf.start());
        awaitDone(bf);
        // Run 1 leaves walked=1/total=1. Wait for its done-mark to COMMIT so run 2's
        // plan deterministically excludes region (0,0) (mirrors the resume test).
        for (int i = 0; i < 400 && !store.isBackfillRegionDone(OW, 0, 0); i++) Thread.sleep(25);
        assertTrue(store.isBackfillRegionDone(OW, 0, 0), "run 1's region must be done-marked");

        writeRegion(5, 0, 7);       // run 2 nearest: 1 present chunk
        writeRegion(6, 0, 0, 1, 2); // run 2 far: 3 present chunks
        parkRun2.set(true);
        assertTrue(bf.start());
        assertTrue(parked.await(20, java.util.concurrent.TimeUnit.SECONDS),
                "run 2's walker must reach its first read");
        // Reset counters: walked=1, seen=1, total=2 -> "~1 / ~1". Carried-over run-1
        // counters would read walked=2, seen=3 -> "~0 regions / ..." instead.
        assertEquals("~1 regions / ~1 columns left", bf.remainingEstimate(),
                "a second start must re-derive progress from THIS run's counters");
        release.countDown();
        awaitDone(bf);
        store.shutdown();
    }
}
