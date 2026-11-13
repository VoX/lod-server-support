package dev.vox.lss.common.store;

import dev.vox.lss.common.FaultCounters;
import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Opt-in background store backfill (plan §Population 3, Phase 4 — ships DEFAULT OFF):
 * walks every region file of every store-known dimension, Chebyshev-nearest-to-spawn
 * first, reads each present chunk through the PLATFORM's normal NBT serve path (same
 * serializers as live serves, so deposited bytes match serve bytes) and deposits it —
 * warming the store for terrain no client has asked for yet.
 *
 * <p><b>Self-restraint</b> (the plan's explicit anti-goal is v1's "pause while any
 * player backlog is nonempty", which never releases under 1 Hz re-declaration):
 * <ul>
 *   <li>one MIN_PRIORITY thread, ONE synchronous read at a time;</li>
 *   <li>{@code readerHeadroom} — pauses while the player-serving reader pool has no
 *       spare capacity (the same self-restraint gate the want-set router obeys);</li>
 *   <li>{@code tickHealthy} — pauses while the server tick is over the ceiling (the
 *       plan's MSPT gate; the platform wires its own tick-time source);</li>
 *   <li>a column-rate cap ({@code lodStoreBackfillColumnsPerSecond}, default
 *       {@value #DEFAULT_COLUMNS_PER_SECOND}/s) so an idle server still trickles
 *       rather than bursts.</li>
 * </ul>
 *
 * <p><b>Resumability:</b> finished regions are marked in the store's {@code backfill}
 * progress table (batcher-written, dropped with the DB like all derived data); a
 * restart re-enumerates and skips done regions. Columns whose store row already exists
 * are skipped without a read ({@code get()} — the row's freshness is the sweep's job).
 *
 * <p>All failure shapes are contained per column/region (counted {@code
 * store.errors} via the store's own paths where applicable); the driver never throws
 * out of its thread.
 */
public final class StoreBackfill {

    /** Default rate cap (docs/planning/store-backfill-tuning-plan.md: the config knob's
     *  default and the package-visible ctor's value — existing tests stay untouched). */
    static final int DEFAULT_COLUMNS_PER_SECOND = 100;
    private static final long PAUSE_POLL_MILLIS = 500;
    /** Measured LOD-bytes / region-file-bytes ratio for the walk-size estimate
     *  (store-cap-behavior-plan §3 — no extra IO, ±30% is plenty for a warning). */
    static final double LOD_BYTES_PER_REGION_BYTE = 0.72;
    /** Hard-stop threshold against an ACTIVE cap: depositing into a capped store is
     *  provably wasted work (each deposit evicts an OLDER, nearer-spawn row), and the
     *  5% margin absorbs the ~5 s gauge staleness. */
    static final double CAP_STOP_FRACTION = 0.95;
    /** Stop the walk with at least this much free on the store's volume. Sized to
     *  leave a server room to keep saving its own region files after LSS stops. */
    static final long MIN_FREE_SPACE_BYTES = 2L << 30; // 2 GiB

    /** Free bytes on the store's volume, or -1 if it cannot be determined — in which
     *  case the walk proceeds, since a failed stat must not be treated as "full". */
    private long usableSpaceBytes() {
        try {
            Path dir = this.store.storeDir();
            return dir == null ? -1L : Files.getFileStore(dir).getUsableSpace();
        } catch (Exception e) {
            // The walk goes on without its free-space guard: count it so that is visible.
            FaultCounters.global().record(FaultCounters.Site.BACKFILL_FREE_SPACE, e);
            return -1L;
        }
    }

    /** Platform seam: synchronously read + serialize one column's wire bytes from
     *  region NBT (the SAME path serves use); null = not servable (absent/all-air is
     *  byte[0] where servable-empty). Throws = contained, counted, skipped. */
    @FunctionalInterface
    public interface ColumnReader {
        byte[] read(String dimension, int cx, int cz) throws Exception;
    }

    private final SqliteLodStore store;
    private final Function<String, Path> regionDirResolver;
    private final Function<String, long[]> spawnChunkResolver; // dim -> {cx, cz}
    private final List<String> dimensions;
    private final ColumnReader columnReader;
    private final BooleanSupplier readerHeadroom;
    private final BooleanSupplier tickHealthy;
    /** Worker-owned window. A policy edit never replaces this charged-work ledger. */
    static final class RateWindow {
        private long started;
        private int charged;
        private long completed;
        RateWindow(long now) { started = now; }
        boolean tryAcquire(long now, int ceiling) {
            if (now - started >= 1_000_000_000L) {
                if (charged > 0) completed++;
                started = now;
                charged = 0;
            }
            if (charged >= ceiling) return false;
            charged++;
            return true;
        }
        long remainingNanos(long now) { return Math.max(0, 1_000_000_000L - (now - started)); }
        long completedWindows() { return completed; }
    }

    private record Policy(boolean enabled, int columnsPerSecond, long revision) {}
    private volatile Policy policy;
    private volatile Policy desiredPolicy;
    private java.util.concurrent.CompletableFuture<Void> policyReceipt;
    private boolean manualPause;
    private boolean successorRequested;
    private boolean closed;

    /** Control intent is serialized without joining or interrupting an active read. */
    public java.util.concurrent.CompletableFuture<Void> updatePolicy(boolean enabled, int rate, long revision) {
        return updatePolicy(enabled, rate, revision, false);
    }

    /** capIncreased is set only after the SQLite owner adopts the raised/removed cap. */
    public synchronized java.util.concurrent.CompletableFuture<Void> updatePolicy(
            boolean enabled, int rate, long revision, boolean capIncreased) {
        if (closed) return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("Backfill owner stopped"));
        if (revision < desiredPolicy.revision()) return java.util.concurrent.CompletableFuture.failedFuture(
                new java.util.concurrent.CancellationException("Superseded backfill policy"));
        boolean enabling = !desiredPolicy.enabled() && enabled;
        desiredPolicy = new Policy(enabled, Math.max(1, rate), revision);
        if (policyReceipt != null) policyReceipt.completeExceptionally(
                new java.util.concurrent.CancellationException("Superseded backfill policy"));
        var receipt = policyReceipt = new java.util.concurrent.CompletableFuture<Void>();
        if (!enabled) { successorRequested = false; stopRequested.set(true); }
        if (enabling) { manualPause = false; successorRequested = true; }
        if (capIncreased && enabled && !manualPause && statusLine.startsWith("capped:"))
            successorRequested = true;
        if (!running.get()) {
            startRequestedSuccessor();
            adoptPolicyAtBoundary();
        }
        return receipt;
    }

    /** Keep failed start intent for an explicit retry, without an automatic restart loop. */
    private void startRequestedSuccessor() {
        if (!successorRequested || closed || manualPause || !desiredPolicy.enabled()) return;
        try {
            if (!startWhenReady()) throw new IllegalStateException("Backfill store is not healthy");
        } catch (RuntimeException | Error failure) {
            if (policyReceipt != null) {
                policyReceipt.completeExceptionally(failure);
                policyReceipt = null;
            }
        }
    }

    private synchronized void adoptPolicyAtBoundary() {
        policy = desiredPolicy;
        // An enabled successor is not adopted until the previous run exits and
        // that successor actually starts. In particular, startup failure cannot
        // turn an already acknowledged off/on transition into silent inactivity.
        if (policyReceipt != null && !successorRequested) {
            policyReceipt.complete(null);
            policyReceipt = null;
        }
    }

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private volatile Thread worker;
    /** Dimensions with LOD service (distance > 0). An off dimension is neither planned nor
     *  walked; its regions stay unmarked, so the next run after it is turned back on (a restart
     *  or {@code /lsslod store backfill start}) walks them — a reload alone starts no run, and
     *  the store warms from serves meanwhile. Read live, so a reload applies at the next region. */
    private volatile java.util.function.Predicate<String> dimensionServed = dim -> true;
    private volatile String statusLine = "idle";
    // Remaining-estimate progress (v0.11.0 stage C, SET plan Part 2): written by the
    // worker, read by /lsslod store backfill status. `walked` is the VISITED count —
    // deliberately different from the terminal lines' regionsDone (done-MARKED only;
    // error/shed/undrained regions are excluded); the status line shows visited for
    // progress and leaves regionsDone semantics to the terminal lines. Estimate bias
    // caveat: the walk is nearest-spawn-first, so the observed avg over the walked
    // (denser, near-spawn) prefix OVERestimates columns-left for the sparse frontier —
    // acceptable for a `~` estimate.
    private volatile int planRegionsTotal;
    private volatile int planRegionsWalked;
    private volatile long presentChunksSeen;
    /** Rate-cap windows completed (worker-written, read after join — the wiring pin's
     *  counter: pacing asserts on this, never wall-clock). */
    private volatile long rateWindows;

    /** Package-visible default-rate ctor (tests). */
    StoreBackfill(SqliteLodStore store,
                  Function<String, Path> regionDirResolver,
                  Function<String, long[]> spawnChunkResolver,
                  List<String> dimensions,
                  ColumnReader columnReader,
                  BooleanSupplier readerHeadroom,
                  BooleanSupplier tickHealthy) {
        this(store, regionDirResolver, spawnChunkResolver, dimensions, columnReader,
                readerHeadroom, tickHealthy, DEFAULT_COLUMNS_PER_SECOND);
    }

    public StoreBackfill(SqliteLodStore store,
                         Function<String, Path> regionDirResolver,
                         Function<String, long[]> spawnChunkResolver,
                         List<String> dimensions,
                         ColumnReader columnReader,
                         BooleanSupplier readerHeadroom,
                         BooleanSupplier tickHealthy,
                         int columnsPerSecond) {
        this.store = store;
        this.regionDirResolver = regionDirResolver;
        this.spawnChunkResolver = spawnChunkResolver;
        this.dimensions = List.copyOf(dimensions);
        this.columnReader = columnReader;
        this.readerHeadroom = readerHeadroom;
        this.tickHealthy = tickHealthy;
        this.policy = this.desiredPolicy = new Policy(true, Math.max(1, columnsPerSecond), 0);
    }

    /** Returns false if already running, disabled, closed, or the store is not healthy. */
    public synchronized boolean start() { return startRun(false); }

    /** Startup may wait for the existing store's startup sweep; operator start requires health. */
    public synchronized boolean startWhenReady() { return startRun(true); }

    private boolean startRun(boolean awaitStartup) {
        if (closed || !desiredPolicy.enabled() || running.get()
                || ((!awaitStartup || !store.isStartupSweepPending()) && !store.isHealthy())) return false;
        manualPause = false;
        stopRequested.set(false);
        running.set(true);
        this.rateWindows = 0;
        this.statusLine = "starting";
        var t = new Thread(this::run, Brand.shortName() + " Store Backfill");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        this.worker = t;
        try {
            t.start();
            // An explicit operator start also fulfills a retained failed-start intent.
            successorRequested = false;
        }
        catch (RuntimeException | Error failure) {
            running.set(false);
            statusLine = "failed: worker could not start";
            throw failure;
        }
        return true;
    }

    /** Operational pause survives unchanged and rate-only reloads. */
    public synchronized boolean stop() {
        manualPause = true;
        successorRequested = false;
        stopRequested.set(true);
        return running.get();
    }

    public synchronized boolean isRunning() {
        return this.running.get();
    }

    public String statusLine() {
        return this.statusLine;
    }

    /** Rate-cap windows completed so far (package-visible: the wiring pin asserts
     *  pacing on this counter, not wall-clock — timing asserts flake on loaded boxes). */
    long rateWindowCount() {
        return this.rateWindows;
    }

    public void shutdown() {
        synchronized (this) {
            closed = true;
            stop();
            if (policyReceipt != null) policyReceipt.completeExceptionally(
                    new java.util.concurrent.CancellationException("Backfill owner stopped"));
            policyReceipt = null;
        }
        var t = this.worker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Package-visible: the describePlan estimate pin builds fake plans from these. */
    record RegionRef(String dim, int rx, int rz, Path mca, int chebFromSpawn) {}

    private void run() {
        long deposited = 0, skipped = 0, errors = 0, regionsDone = 0, pauses = 0;
        try {
            // The store serves nothing until its startup sweep completes, and get()/
            // hasRow() read as misses in that window — starting the walk early would
            // re-read and re-deposit everything already present (review MAJOR: the
            // config auto-start races the sweep).
            this.statusLine = "starting: awaiting store sweep"; // review B10 — not "idle"
            if (!this.store.awaitSweep(TimeUnit.MINUTES.toMillis(5))) {
                this.statusLine = "failed: store sweep never completed";
                return;
            }
            if (!this.store.isHealthy()) {
                // A latched store answers isBackfillRegionDone()=true for everything, so
                // enumerate() would return an empty plan and the status would read
                // "complete: 0 regions" — success-shaped output for a dead store (R3).
                this.statusLine = "aborted: store unhealthy (latched off?)";
                LSSLogger.warn("Store backfill aborted — store not healthy at start");
                return;
            }
            List<RegionRef> plan = enumerate();
            // Progress reset HERE, at walk start — a second start() must never show the
            // prior run's counts (SET review).
            this.planRegionsTotal = plan.size();
            this.planRegionsWalked = 0;
            this.presentChunksSeen = 0;
            LSSLogger.info(describePlan(plan, this.store.sizeCapBytes()));
            var rateWindow = new RateWindow(System.nanoTime());
            for (int ri = 0; ri < plan.size(); ri++) {
                RegionRef region = plan.get(ri);
                adoptPolicyAtBoundary();
                long capBytes = this.store.sizeCapBytes();
                if (this.stopRequested.get()) break;
                // Turned off since planning (a reload): skip, unmarked, so a later run walks it.
                if (!served(region.dim())) {
                    this.planRegionsWalked++; // visited (progress stays coherent), not marked done
                    continue;
                }
                // Hard stop at an active cap (store-cap-behavior-plan §3) — never a
                // pause: a full store does not un-fill itself, and each further
                // deposit evicts an OLDER (nearer-spawn) row, inverting the walk's
                // own value order. Unwalked regions stay unmarked, so an admin who
                // raises the cap and re-runs resumes exactly here.
                if (capBytes != Long.MAX_VALUE && this.store.approxSizeBytes()
                        >= (long) (capBytes * CAP_STOP_FRACTION)) {
                    this.statusLine = "capped: store at size cap, " + regionsDone
                            + " regions done, " + (plan.size() - ri) + " unwalked";
                    return;
                }
                if (!this.store.isHealthy()) {
                    // A latched store no-ops every write while counters would keep
                    // claiming progress — burning a world's worth of IO for nothing.
                    this.statusLine = "aborted: store unhealthy (latched off?)";
                    LSSLogger.warn("Store backfill aborted — store no longer healthy");
                    return;
                }
                // Free-space floor. The cap gate above is opt-in and OFF by default
                // (lodStoreMaxMB=0), so on the shipped configuration nothing else
                // bounds this walk: v0.9.0 turns the store and its backfill on by
                // default, and the walk writes roughly the size of the region files it
                // reads. On a quota-limited host (a panel-provisioned server sized just
                // above its world) that fills the volume in minutes — and the first
                // casualty is not LSS, which degrades correctly, but Minecraft's own
                // region saves. Stopping is always safe: unwalked regions stay
                // unmarked, so freeing space and restarting resumes here. (v0.9.0
                // review — nothing in the codebase consulted free space at all.)
                long usable = usableSpaceBytes();
                if (usable >= 0 && usable < MIN_FREE_SPACE_BYTES) {
                    this.statusLine = "stopped: low disk (" + (usable >> 20) + " MB free), "
                            + regionsDone + " regions done, " + (plan.size() - ri) + " unwalked";
                    LSSLogger.warn("Store backfill stopped — only " + (usable >> 20)
                            + " MB free on the store volume (floor "
                            + (MIN_FREE_SPACE_BYTES >> 20) + " MB). " + (plan.size() - ri)
                            + " regions left unwalked; they resume once space is freed. "
                            + "Set storage.lod_store.max_size_mib and reload to bound the store, or disable storage.lod_store.enabled and restart.");
                    return;
                }
                int[] present = presentChunks(region.mca());
                if (present == null) continue; // unreadable header: skip, sweep owns it
                // Bump AFTER the null-check (SET review): an unreadable region counted
                // as walked-with-zero would bias the columns/region average low.
                this.planRegionsWalked++;
                int presentCount = 0;
                for (int slot : present) {
                    if (slot != 0) presentCount++;
                }
                this.presentChunksSeen += presentCount;
                long regionErrors = 0;
                // B9: fence against a concurrent `invalidate all` — a region judged
                // before the drop must not be done-marked after it.
                long dropGenAtStart = this.store.dropGeneration();
                // B8: only THIS walk's sheds veto the done-mark (the old global
                // deposit-drop snapshot let any serve-path shed anywhere deny every
                // region durable progress — a busy server re-walked the world each
                // restart).
                boolean regionShed = false;
                for (int idx = 0; idx < 1024; idx++) {
                    adoptPolicyAtBoundary();
                    if (this.stopRequested.get()) break;
                    long columnCap = this.store.sizeCapBytes();
                    if (columnCap != Long.MAX_VALUE && this.store.approxSizeBytes()
                            >= (long) (columnCap * CAP_STOP_FRACTION)) {
                        this.statusLine = "capped: store at size cap";
                        return;
                    }
                    if (present[idx] == 0) continue;
                    // Restraint + rate cap cover EVERY visited column — the skip rung
                    // included (review: a warm region walk was 1024 unpaced full-row
                    // reads; hasRow is the cheap existence probe, but even it is paced).
                    pauses += pauseWhileRestrained();
                    if (this.stopRequested.get()) break;
                    if (!this.store.isHealthy()) {
                        // Mid-region latch (R3): deposit()/hasRow() silently no-op under
                        // the latch — without this check the rest of the region is up to
                        // 1024 real disk reads whose results all evaporate.
                        this.statusLine = "aborted: store unhealthy (latched off?)";
                        LSSLogger.warn("Store backfill aborted — store no longer healthy");
                        return;
                    }
                    // Charge before the read, including skips. A lowered ceiling retains
                    // all work charged in this window; an increase only releases the
                    // additional allowance. Polling lets a rate edit release a wait.
                    while (!stopRequested.get()) {
                        adoptPolicyAtBoundary();
                        long now = System.nanoTime();
                        if (rateWindow.tryAcquire(now, policy.columnsPerSecond())) break;
                        Thread.sleep(Math.min(50, rateWindow.remainingNanos(now) / 1_000_000L + 1));
                    }
                    this.rateWindows = rateWindow.completedWindows();
                    if (stopRequested.get()) break;
                    // Cap adoption may have happened during the restraint/rate wait.
                    columnCap = this.store.sizeCapBytes();
                    if (columnCap != Long.MAX_VALUE && this.store.approxSizeBytes()
                            >= (long) (columnCap * CAP_STOP_FRACTION)) {
                        this.statusLine = "capped: store at size cap";
                        return;
                    }
                    int cx = (region.rx() << 5) + (idx & 31);
                    int cz = (region.rz() << 5) + (idx >> 5);
                    long packed = PositionUtil.packPosition(cx, cz);
                    if (this.store.hasRow(region.dim(), packed)) {
                        skipped++;
                        this.store.diagnostics().recordBackfillSkip();
                    } else {
                        try {
                            // Acquisition stamp BEFORE the read (R1-M2): a save landing
                            // while the read runs must not be sweep-invisible.
                            long acquiredSeconds = System.currentTimeMillis() / 1000L;
                            byte[] bytes = this.columnReader.read(region.dim(), cx, cz);
                            this.store.diagnostics().recordBackfillRead();
                            if (bytes != null) {
                                if (!this.store.deposit(region.dim(), packed, bytes,
                                        System.currentTimeMillis() / 1000L, acquiredSeconds)) {
                                    regionShed = true;
                                }
                                deposited++;
                                this.store.diagnostics().recordBackfillDeposit();
                            } else {
                                skipped++;
                                this.store.diagnostics().recordBackfillSkip();
                            }
                        } catch (InterruptedException ie) {
                            // Shutdown interrupt mid-read (review B14): restore the
                            // flag and stop — not an error, no phantom store.errors,
                            // and the interrupt must not be swallowed.
                            Thread.currentThread().interrupt();
                            this.stopRequested.set(true);
                            break;
                        } catch (Throwable e) {
                            // Throwable, not Exception (round-3 review). The walk's
                            // serialization runs SYNCHRONOUSLY on this thread
                            // (readColumnBytesSyncForBackfill), unlike the pooled read
                            // path where future.get wraps pool-side throwables in an
                            // ExecutionException. So an Error from one hostile or corrupt
                            // chunk — the allocation-class failure the disk reader
                            // broadened its OWN containment to Throwable for, twice —
                            // escaped this per-column belt to the run-level handler and
                            // aborted the entire walk. The poison region never gets
                            // done-marked and lodStoreBackfill defaults on, so every
                            // subsequent boot re-enumerates, re-hits the same column and
                            // re-aborts: every region after it in the plan, other
                            // dimensions included, stays cold permanently.
                            errors++;
                            regionErrors++;
                            // Visible to operators: backfill failures count store.errors
                            // (review: the exact A7 failure mode this feature re-enters
                            // was invisible to every exporter).
                            this.store.diagnostics().recordError();
                            if (errors <= 3) {
                                LSSLogger.warn("Store backfill: read failed at " + region.dim()
                                        + " [" + cx + "," + cz + "] — skipping", e);
                            }
                        }
                    }
                    this.statusLine = "running: " + this.planRegionsWalked + "/"
                            + this.planRegionsTotal + " regions, "
                            + deposited + " deposited, " + skipped + " skipped, "
                            + errors + " errors, " + pauses + " pauses, "
                            + remainingEstimate();
                }
                // Done-mark ONLY a cleanly processed region: errors or shed deposits
                // would otherwise turn transient IO trouble into permanent warm-holes
                // that resumability never revisits (review MAJOR). An unmarked region
                // re-walks cheaply (hasRow skips). The mark waits for the deposit queue
                // to DRAIN first (4-agent round R3): the mark rides the priority control
                // queue, so an undrained mark could commit AHEAD of this region's still-
                // queued deposits — a crash in that window left a done-marked region
                // whose columns were never written; and sampling drops before the drain
                // let a post-sample shed slip under the depositsShed guard. A drain
                // timeout just skips the mark — the region re-walks next run.
                boolean drained = this.store.awaitDepositQueueEmpty(5000);
                if (!this.stopRequested.get() && regionErrors == 0 && drained
                        && !regionShed
                        && this.store.dropGeneration() == dropGenAtStart) {
                    this.store.markBackfillRegionDone(region.dim(), region.rx(), region.rz());
                    regionsDone++;
                }
            }
            this.statusLine = (this.stopRequested.get() ? "stopped: " : "complete: ")
                    + regionsDone + " regions, " + deposited + " deposited, "
                    + skipped + " skipped, " + errors + " errors, " + pauses + " pauses";
        } catch (InterruptedException e) {
            this.statusLine = "stopped (shutdown): " + regionsDone + " regions, "
                    + deposited + " deposited, " + skipped + " skipped, "
                    + errors + " errors";
        } catch (Throwable t) {
            this.statusLine = "failed: " + t;
            LSSLogger.warn("Store backfill aborted", t);
        } finally {
            // Close THIS worker thread's reader connection (review B6): each start()
            // thread otherwise leaks its ThreadLocal conn until store shutdown.
            this.store.closeReaderConnForCurrentThread();
            // The summary must print on EVERY exit path (the interrupt path used to
            // swallow it, so the one line carrying the error count never appeared).
            LSSLogger.info("Store backfill " + this.statusLine);
            synchronized (this) {
                this.running.set(false);
                startRequestedSuccessor();
                adoptPolicyAtBoundary();
            }
        }
    }

    /** Installs the served-dimension check (see {@link #dimensionServed}). */
    public void setDimensionFilter(java.util.function.Predicate<String> served) {
        this.dimensionServed = served == null ? dim -> true : served;
    }

    private boolean served(String dim) {
        try {
            return this.dimensionServed.test(dim);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            return true; // a broken check never silently skips a dimension
        }
    }

    /** All not-yet-done regions of all dims, Chebyshev-nearest-to-spawn first
     *  (players cluster spawn-side — the plan's traversal order). */
    private List<RegionRef> enumerate() {
        List<RegionRef> plan = new ArrayList<>();
        for (String dim : this.dimensions) {
            if (!served(dim)) continue;
            Path dir;
            try {
                dir = this.regionDirResolver.apply(dim);
            } catch (Throwable t) {
                continue;
            }
            if (dir == null || !Files.isDirectory(dir)) continue;
            long[] spawn;
            try {
                spawn = this.spawnChunkResolver.apply(dim);
            } catch (Throwable t) {
                spawn = null;
            }
            if (spawn == null || spawn.length < 2) spawn = new long[]{0, 0};
            int spawnRx = (int) spawn[0] >> 5;
            int spawnRz = (int) spawn[1] >> 5;
            try (var stream = Files.list(dir)) {
                for (Path mca : (Iterable<Path>) stream::iterator) {
                    String name = mca.getFileName().toString();
                    if (!name.startsWith("r.") || !name.endsWith(".mca")) continue;
                    String[] parts = name.split("\\.");
                    if (parts.length != 4) continue;
                    int rx, rz;
                    try {
                        rx = Integer.parseInt(parts[1]);
                        rz = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (this.store.isBackfillRegionDone(dim, rx, rz)) continue;
                    int cheb = Math.max(Math.abs(rx - spawnRx), Math.abs(rz - spawnRz));
                    plan.add(new RegionRef(dim, rx, rz, mca, cheb));
                }
            } catch (Exception e) {
                LSSLogger.warn("Store backfill: cannot list region dir for " + dim, e);
            }
        }
        plan.sort(Comparator.comparingInt(RegionRef::chebFromSpawn));
        return plan;
    }

    /** The `~N regions / ~M columns left` tail of the running: status line — a trivial
     *  reader of the three volatile progress counters; the arithmetic lives in the
     *  static below so the test can pin every branch directly. */
    String remainingEstimate() {
        return remainingEstimateFor(this.planRegionsTotal, this.planRegionsWalked,
                this.presentChunksSeen);
    }

    /** Before the first region completes there is no measured average, so the columns
     *  term is the `<=` worst case (1024/region); after, remaining x the measured
     *  present-chunks/region. Package-visible for the test's arithmetic-table pin. */
    static String remainingEstimateFor(int total, int walked, long seen) {
        int remaining = Math.max(0, total - walked);
        long columnsLeft = walked > 0
                ? remaining * (seen / Math.max(1, walked))
                : remaining * 1024L;
        return "~" + remaining + " regions / " + (walked > 0 ? "~" : "<=") + columnsLeft
                + " columns left";
    }

    /** The §3 start-of-walk line: region count + size estimate + cap consequence.
     *  The admin learns the disk outcome in second one, not from eviction spam an
     *  hour later. "New deposits", not "total" — a resumed walk plans only the
     *  not-yet-done regions. Package-visible: the estimate pin asserts the computed
     *  size lands in this line (a refactor dropping it must red, review MINOR). */
    String describePlan(List<RegionRef> plan, long capBytes) {
        long regionFileBytes = 0;
        for (RegionRef r : plan) {
            try {
                regionFileBytes += Files.size(r.mca());
            } catch (Exception ignored) {
                // unreadable file: the walk itself skips it via presentChunks
            }
        }
        long estimate = estimateLodBytes(regionFileBytes);
        String line = "Store backfill: " + plan.size() + " region(s) to process, estimated ~"
                + formatSize(estimate) + " of new deposits ("
                + (capBytes == Long.MAX_VALUE ? "uncapped" : "cap: " + formatSize(capBytes)) + ")";
        if (capBytes != Long.MAX_VALUE && estimate > capBytes) {
            line += " — the walk will STOP at the cap; nearest-spawn terrain is warmed "
                    + "first, raise storage.lod_store.max_size_mib (0 = uncapped) and reload for full coverage";
        }
        return line;
    }

    /** Walk-size estimate: planned region file bytes x the measured LOD/region ratio
     *  (package-visible: the test pins the arithmetic, not the prose). */
    static long estimateLodBytes(long regionFileBytes) {
        return (long) (regionFileBytes * LOD_BYTES_PER_REGION_BYTE);
    }

    /** Locale-pinned (comma-decimal locales would log "1,5 GB"), with an MB rung so
     *  sub-GB values never print "0.0 GB". */
    static String formatSize(long bytes) {
        if (bytes < (1L << 30)) return (bytes >> 20) + " MB";
        return String.format(java.util.Locale.ROOT, "%.1f GB",
                bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** The region header's location table: nonzero = chunk present. Null = unreadable. */
    private int[] presentChunks(Path mca) {
        try (FileChannel ch = FileChannel.open(mca)) {
            ByteBuffer buf = ByteBuffer.allocate(4096);
            int read = 0;
            while (read < 4096) {
                int n = ch.read(buf, read);
                if (n < 0) return null;
                read += n;
            }
            buf.flip();
            int[] loc = new int[1024];
            for (int i = 0; i < 1024; i++) loc[i] = buf.getInt(i * 4);
            return loc;
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the number of pause intervals slept (observability: the status line and
     *  gate evidence need to show restraint actually FIRING, not just existing). */
    private long pauseWhileRestrained() throws InterruptedException {
        long pauses = 0;
        while (!this.stopRequested.get()
                && (!this.readerHeadroom.getAsBoolean() || !this.tickHealthy.getAsBoolean())) {
            adoptPolicyAtBoundary();
            this.statusLine = "paused (yielding to players/tick)";
            Thread.sleep(PAUSE_POLL_MILLIS);
            pauses++;
        }
        return pauses;
    }
}
