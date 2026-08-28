package dev.vox.lss.common.store;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The SQLite LOD store tier (plan §1/§2): serves previously serialized wire-format
 * section bytes across restarts. DERIVED data — corruption, schema/wire/mask drift, and
 * codec changes are all "drop and rebuild", never migrate; deleting the DB (with -wal/
 * -shm) is always safe.
 *
 * <p><b>Schema (§2, Phase 0 decisions baked in):</b> WAL + synchronous=NORMAL (power
 * loss may lose recent commits, can never corrupt — fine for derived data; do not
 * "harden"), page_size 16384 (zero overflow chains at zstd-1 blob sizes), one ROWID
 * table per dimension ({@code lods_<dimId>}: {@code pos INTEGER PRIMARY KEY} IS the
 * rowid — true clustering, no secondary index), {@code dims}/{@code regions}/{@code
 * meta} side tables, ~64-row write transactions, mmap OFF (no measured win; SIGBUS on
 * IO error is uncatchable).
 *
 * <p><b>Threading:</b> {@code get()} runs on reader-pool threads over THREAD-CONFINED
 * read-only connections ({@code SQLITE_OPEN_READONLY} via query_only, {@code
 * wal_autocheckpoint=0} so readers never checkpoint); ALL writes (deposits, deletes,
 * sweep, meta) happen on the single batcher thread — WAL readers don't block the writer
 * and vice versa. The stale-hit window between a processing-thread invalidation and the
 * batcher's async row delete is closed by the same tombstone map the memory tier proved:
 * {@code invalidate()}/{@code delete()} stamp tombstones synchronously and {@code get()}
 * consults them first, so an invalidated position reads as a miss the moment the
 * invalidation returns ("effective before any subsequent get()"), with the DB delete
 * following on the batcher.
 *
 * <p><b>Freshness (§1 v2, per-column):</b> every row carries {@code src_stamp} — the
 * deposit-time epoch second, a CONSERVATIVE stand-in for the chunk's save time (any
 * region write AFTER the deposit has a header timestamp ≥ it). The startup sweep stats
 * every region file against {@code regions.seen_mtime} with {@code !=} (backup restores
 * move mtime BACKWARD), reads the changed files' 4 KiB header timestamp tables, and
 * DELETES rows whose header stamp ≥ their {@code src_stamp}; a VANISHED region file
 * drops all its rows (or the store would intercept the miss that regenerates
 * deliberately-deleted chunks). An unresolvable region directory drops the whole
 * dimension's rows (fail-safe: more NBT reads, never stale serves). The store serves
 * NOTHING until the sweep completes (misses during the boot window fall to the NBT
 * ladder). Known accepted cost: vanilla's metadata-only re-saves advance header stamps
 * without changing LOD content, so rows for chunks LOADED near shutdown are
 * conservatively dropped at the next startup — those are the probe-served positions the
 * store never serves anyway; the far disc keeps its rows.
 *
 * <p><b>Containment:</b> every entry point catches {@link Throwable}. Read-side failures
 * count {@code store.errors} and read as misses. Repeated writer-side failures latch the
 * store OFF one-way ({@code store=unavailable} in diag) with one warning. {@code
 * org.sqlite.tmpdir} is pointed at the store directory before the first connection
 * (noexec /tmp ships {@code UnsatisfiedLinkError} otherwise). A WAL watchdog issues
 * {@code wal_checkpoint(TRUNCATE)} above a size threshold (PASSIVE checkpoints cannot
 * reset the WAL under continuous readers) and feeds {@code store.wal_bytes}/{@code
 * db_bytes}/{@code checkpoint_ms_max}.
 */
public final class SqliteLodStore implements LodStoreService {

    // v2: auto_vacuum=INCREMENTAL (must be set before table creation; the bump
    // rebuilds every v1 store once — the legal migration for derived data).
    // 3: the fhash column (frame-level integrity for protocol-19 verbatim frame
    // serving, compressed-columns plan §0.1/§3). Derived data: the bump
    // drops-and-rebuilds; the warm store re-warms from serves/backfill.
    // 4: chash/fhash switch FNV-1a 64 -> CRC32C zero-extended (perf round Phase 2/R4 —
    // the byte loop was ~28% of the batcher thread; see LodStoreService.contentHash).
    // Old rows would fail every validation under the new function, so the bump
    // drops-and-rebuilds; rollback is symmetric (evaluateMeta's core gate is an
    // equality compare, an old jar against a v4 store also rebuilds).
    // Interim (mega plan R-1): C4 re-specifies schema 4 (wirefmt column + per-row hash
    // dispatch against legacyContentHashFnv); a Phase-2-era store drops there via
    // evaluateMeta (meta wire 19 != 20, a core key) — no shipped v0.9.x store ever
    // sees this shape.
    static final int SCHEMA_VERSION = 4;
    /** The table-structure generation evaluateMeta pins (C4): "wirefmt" = every lods_*
     *  table carries the wirefmt column. */
    static final String STORE_LAYOUT = "wirefmt";
    /** The row body format constants (C4, XVER §5): 19 = native-layout (pre-migration
     *  v0.9.x rows), 20 = the canonical v20 dictionary layout. */
    static final int WIREFMT_NATIVE_19 = LodStoreService.WIREFMT_NATIVE_19;
    static final int WIREFMT_V20 = LodStoreService.WIREFMT_V20;
    /** Rows per background-migration batch (C4 §5.4). One batch runs per IDLE batcher
     *  iteration (the 200 ms queue-poll timeout) plus a busy FLOOR of one batch per 32
     *  applied ops (review #3 — strict idle-gating starved the walk to zero under any
     *  steady deposit traffic), so the walk yields hard to live store traffic
     *  — deposits, deletes, sweeps — and on an idle store tops out around
     *  ~320 rows/s on a quiet server (a multi-GB store migrates in under an hour;
     *  restraint-first, the spec's pacing intent, with idle-gating standing in for the
     *  backfill's MSPT gate — the batcher is an off-main MIN_PRIORITY+1 thread, so its
     *  tick impact is IO contention, which idle-gating bounds). */
    private static final int MIGRATE_ROWS_PER_BATCH = 64;

    // ---- C4 background migration walk state (batcher thread; status reads volatile) ----
    private volatile boolean migratePending;
    // Dev-only soak hold (see maybeMigrateBatch): 0 = off. Batcher-thread mutated
    // after init; nonzero only when -Dlss.soak.migrationHoldSeconds is set.
    private long migrationHoldUntilNanos =
            Integer.getInteger("lss.soak.migrationHoldSeconds", 0) <= 0 ? 0
                    : System.nanoTime()
                            + Integer.getInteger("lss.soak.migrationHoldSeconds", 0) * 1_000_000_000L;
    private boolean migrationHoldLogged;
    private volatile long migrateTotal;
    private final java.util.concurrent.atomic.AtomicLong migrateRemaining =
            new java.util.concurrent.atomic.AtomicLong();
    /** Dims not yet exhausted by the walk; rebuilt at boot from dimIds when pending. */
    private final java.util.ArrayDeque<Integer> migrateDims = new java.util.ArrayDeque<>();
    /** Applied ops since the last walk batch — the busy floor's counter (batcher only). */
    private int opsSinceMigrateBatch;
    /** Armed by {@link #failNextMigrationBatchesForTest} (review #8's fault seam). */
    private volatile int failNextMigrationBatches;
    /** The native→v20 body translator for the walk (platform-wired, same function the
     *  serve rung uses). Null = walk waits (serves still translate via the reader). */
    private volatile java.util.function.UnaryOperator<byte[]> legacyMigrationTranslator;

    private static final String DB_FILE = "store.db";
    private static final int PAGE_SIZE = 16384;
    private static final int WRITE_TXN_ROWS = 64;
    private static final int QUEUE_CAPACITY = 1024;
    private static final long WAL_CHECKPOINT_BYTES = 64L << 20; // TRUNCATE above 64 MB
    private static final long GAUGE_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** Firm-cap runaway stop (review B12): max eviction batches per gauge tick. */
    private static final int MAX_EVICTION_PASSES_PER_TICK = 8;
    // Per-pass, per-dimension row ceiling. The pass normally stops far short of this,
    // as soon as it has covered the byte deficit; this only bounds how long one pass
    // can hold the batcher when the store is enormously over cap.
    private static final int EVICTION_MAX_ROWS_PER_DIM = 512;
    // Whole-dimension drops materialize at most this many positions at a time.
    private static final int DROP_BATCH_ROWS = 4096;
    // Evict down to this fraction of the cap rather than to exactly the cap, so a
    // store sitting at its limit does not re-enter eviction on every 5 s gauge tick.
    private static final double EVICTION_TARGET_FRACTION = 0.95;
    // One PRAGMA execute reclaims ONE page (see drainIncrementalVacuum), so this is
    // both a page budget and a statement budget: 2048 * 16 KB = 32 MB returned to the
    // filesystem per 5 s tick, comfortably ahead of the ~2.5 MB/s a max-pace backfill
    // deposits, while bounding how long the batcher sits in the drain (a long batcher
    // stall is what lets tombstones expire under their own queued deletes).
    private static final int MAX_VACUUM_PAGES_PER_TICK = 2048;
    /** Writer-side failures within one session before the one-way off latch. */
    private static final int WRITE_FAILURE_LATCH = 20;
    /** Sanity ceiling on a row's self-declared uncompressed size (real columns are
     *  tens of KB; 16 MB is far above any legal wire column) — bounds the decompress
     *  allocation before the integrity hash can vet the bytes. */
    private static final int MAX_ROW_USIZE = 16 << 20;
    private static final byte[] EMPTY = new byte[0];

    /** Everything the platform must provide (kept as one bundle so tests can fake it). */
    // NOTE: no knownDimensions list — the sweep iterates the DIMS TABLE, so every
    // dimension the store holds rows for gets a freshness pass; a dim the resolver
    // cannot place fail-safe drops. A caller-frozen list exempted late-created worlds.
    public record Environment(Path storeDir, String mcVersion, int wireVersion,
                              Function<String, Path> regionDirResolver,
                              Function<String, String> maskFingerprintResolver,
                              int resweepSeconds, long maxDbBytes,
                              String registryFingerprint,
                              String registryContentFingerprint) {

        /** Null fingerprints normalize to "" — the record's convention for "no
         *  evidence" (a null would NPE inside evaluateMeta/writeMeta, and the
         *  catch-all would rebuild a healthy store). */
        public Environment {
            registryFingerprint = registryFingerprint == null ? "" : registryFingerprint;
            registryContentFingerprint =
                    registryContentFingerprint == null ? "" : registryContentFingerprint;
        }

        /** Pre-content-fingerprint shape (v0.13.1 permutation plan §3.2): an empty
         *  content fingerprint makes a permutation UNPROVABLE — the ladder drops,
         *  never vacuously matches ("" == "" was the plan review's MAJOR-1). */
        public Environment(Path storeDir, String mcVersion, int wireVersion,
                           Function<String, Path> regionDirResolver,
                           Function<String, String> maskFingerprintResolver,
                           int resweepSeconds, long maxDbBytes,
                           String registryFingerprint) {
            this(storeDir, mcVersion, wireVersion, regionDirResolver,
                    maskFingerprintResolver, resweepSeconds, maxDbBytes,
                    registryFingerprint, "");
        }

        /** Pre-registry-fingerprint shape (tests without a platform registry). */
        public Environment(Path storeDir, String mcVersion, int wireVersion,
                           Function<String, Path> regionDirResolver,
                           Function<String, String> maskFingerprintResolver,
                           int resweepSeconds, long maxDbBytes) {
            this(storeDir, mcVersion, wireVersion, regionDirResolver,
                    maskFingerprintResolver, resweepSeconds, maxDbBytes, "");
        }

        /** Pre-Phase-5 shape (tests): no size cap. */
        public Environment(Path storeDir, String mcVersion, int wireVersion,
                           Function<String, Path> regionDirResolver,
                           Function<String, String> maskFingerprintResolver,
                           int resweepSeconds) {
            this(storeDir, mcVersion, wireVersion, regionDirResolver,
                    maskFingerprintResolver, resweepSeconds, Long.MAX_VALUE, "");
        }
    }

    private sealed interface Op {
        /** {@code preFramed}: {@code bytes} IS the zstd frame (usize/chash/fhash
         *  caller-computed on the processing thread — the compress-once reuse, plan §3);
         *  otherwise {@code bytes} are raw and the batcher compresses + hashes. */
        record Deposit(String dim, long packed, byte[] bytes, long ts, long srcStampSeconds,
                       long enqueuedNanos, boolean preFramed, int usize, long chash,
                       long fhash) implements Op {
            Deposit(String dim, long packed, byte[] bytes, long ts, long srcStampSeconds,
                    long enqueuedNanos) {
                this(dim, packed, bytes, ts, srcStampSeconds, enqueuedNanos, false, 0, 0L, 0L);
            }
        }
        record DeleteRows(String dim, long[] positions) implements Op {}
        record Resweep() implements Op {}
        record BackfillMark(String dim, int rx, int rz) implements Op {}
        record DropAll() implements Op {}
    }

    private final LodStoreMode mode;
    private final LodStoreDiagnostics diag;
    private final StoreCodec codec;
    private final Environment env;
    private final Path dbPath;

    private final ArrayBlockingQueue<Op> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    // Deletes and resweeps ride a separate UNBOUNDED queue drained before deposits: a row
    // delete must never be shed (its tombstone expires after TOMBSTONE_TTL_NANOS — losing
    // the delete would resurrect a stale row), and mixing them into the bounded deposit
    // queue forced shed-ordering contortions (and a livelock when the queue was all
    // deletes). Unbounded is safe here: volume is edit-rate-bounded, and a mass
    // invalidation arrives as ONE DeleteRows op carrying the whole position array.
    private final java.util.concurrent.ConcurrentLinkedQueue<Op> controlQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Thread batcher;
    private final AtomicBoolean shutdown = new AtomicBoolean();
    // One-way containment latch: after repeated writer failures the store stops serving
    // and stops accepting work (diag renders store=unavailable via the null-store path).
    private volatile boolean latchedOff;
    private final AtomicBoolean latchWarned = new AtomicBoolean();
    private final AtomicBoolean readErrorWarned = new AtomicBoolean();
    /** One cap-eviction INFO per session (store-cap-behavior-plan §2); the volatile
     *  emission count is the latch pin's proxy (batcher-written, read after quiesce). */
    private final AtomicBoolean capLogLatch = new AtomicBoolean();
    private volatile int capLogEmissions;
    /** Regions whose seen_mtime a deposit cleared since the last sweep pass (review
     *  B13 memo — batcher-thread only, reset by runSweep). */
    private final Map<Integer, java.util.HashSet<Long>> sweepReopened = new HashMap<>();
    /** {dimId, rpos} memo entries added during the OPEN txn (2026-08-05 review F5,
     *  batcher-thread only): a rollback undoes the txn's regions-DELETEs while the
     *  sweepReopened memo survived, so a later same-region deposit skipped the clear and
     *  the stale seen_mtime row ==-skipped every future sweep. rollbackTxn prunes exactly
     *  these entries; commitTxn clears the list once the deletes are durable. */
    private final java.util.ArrayDeque<long[]> txnReopened = new java.util.ArrayDeque<>();
    /** Bumped by every applied DropAll (review B9): the backfill snapshots it per
     *  region and skips the done-mark when it changed — a region judged before the
     *  drop must not be marked done after it (permanent warm hole otherwise). */
    private volatile long dropGeneration;
    private int writerFailures;

    // Serving gate: false until the startup sweep completes — a not-yet-swept stale row
    // must never hit (misses during boot fall to the NBT ladder, fail-safe).
    private volatile boolean serving;
    private final CountDownLatch sweepDone = new CountDownLatch(1);

    // Sweep-drop fan-out: a test/observability seam (fed alongside the store.sweep_drops
    // counter). Its production consumer was the deleted memory front tier; kept because
    // any future front cache MUST register here or resweep culls won't reach it.
    private volatile java.util.function.BiConsumer<String, long[]> sweepDropListener;

    // The tombstone map (the memory tier's proven protocol, §threading above).
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>> tombstones =
            new ConcurrentHashMap<>();
    private static final long TOMBSTONE_TTL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private long lastTombstoneSweepNanos = System.nanoTime();

    // Batcher-thread state
    private Connection writer;
    private final Map<String, Integer> dimIds = new HashMap<>();
    private final Map<String, PreparedStatement> insertByDim = new HashMap<>();
    private int txnRows;
    private long lastGaugeRefreshNanos;
    private long nextResweepNanos;
    /** Test-only batcher step gate (see the top of {@code batcherLoop}); production
     *  leaves it null. Volatile: set from the test thread, read by the batcher. */
    private volatile java.util.concurrent.Semaphore batcherStepsForTest;
    /** Per-dimension whole-drop barrier (nanoTime): deposits enqueued at or before it
     *  are refused, replacing the per-position tombstones a full drop used to stamp.
     *  See {@link #dropDimensionRows}. */
    private final Map<String, Long> dropBarrierNanos = new ConcurrentHashMap<>();
    /** Dimensions whose rows are mid-drop — readers are suppressed wholesale, so a
     *  half-dropped dimension never serves its surviving rows. */
    private final Set<String> droppingDims = ConcurrentHashMap.newKeySet();
    /** Resolved from the DB on first use rather than assumed to be {@link #PAGE_SIZE}:
     *  an existing store carries whatever page size it was created with, and the size
     *  cap multiplies by this. Batcher-confined, like the writer it is read through. */
    private long pageSizeBytes;

    // Reader connections, thread-confined (reader-pool threads via ThreadLocal). Tracked
    // for shutdown closing. Readers only ever see dimensions that exist at their first
    // use; a dim table created later is picked up by the per-call dim-id lookup.
    private final ThreadLocal<Connection> readerConn = new ThreadLocal<>();
    private final List<Connection> allReaderConns = new ArrayList<>();
    private final ConcurrentHashMap<String, Integer> dimIdsShared = new ConcurrentHashMap<>();

    /** Null when the codec native or the SQLite native/DB cannot serve this platform —
     *  the caller warns once and runs without the disk tier (degrade, never crash). */
    public static SqliteLodStore createOrNull(LodStoreMode mode, Environment env,
                                              LodStoreDiagnostics diag) {
        try {
            StoreCodec codec = StoreCodec.zstdOrNull();
            if (codec == null) return null;
            return new SqliteLodStore(mode, codec, env, diag);
        } catch (Throwable t) {
            LSSLogger.warn("LOD store: SQLite engine unavailable — running without the"
                    + " disk store", t);
            return null;
        }
    }

    private SqliteLodStore(LodStoreMode mode, StoreCodec codec, Environment env,
                           LodStoreDiagnostics diag) throws Exception {
        this.mode = mode;
        this.codec = codec;
        this.env = env;
        this.diag = diag;
        Files.createDirectories(env.storeDir());
        // noexec /tmp: sqlite-jdbc extracts its native lib to org.sqlite.tmpdir; the
        // world folder is always writable+executable for the server.
        if (System.getProperty("org.sqlite.tmpdir") == null) {
            System.setProperty("org.sqlite.tmpdir", env.storeDir().toString());
        }
        this.dbPath = env.storeDir().resolve(DB_FILE);
        // Open + validate meta on the CALLER thread (service construction): a mismatch
        // or corruption drops the DB and recreates it fresh — before the batcher exists.
        openOrRecreateWriter();
        initMigrationState();
        this.batcher = new Thread(this::batcherLoop, Brand.shortName() + " LOD Store SQLite");
        this.batcher.setDaemon(true);
        this.batcher.setPriority(Thread.MIN_PRIORITY + 1);
        this.batcher.start();
    }

    // ---- lifecycle / meta ----

    /** Test seam (permutation-plan fold): the last open's verdict, so the ladder
     *  tests assert WHICH rung fired instead of inferring it from {@code get()}. */
    private volatile MetaVerdict lastMetaVerdict;

    MetaVerdict lastMetaVerdictForTest() {
        return this.lastMetaVerdict;
    }

    private void openOrRecreateWriter() throws Exception {
        try {
            openWriter();
            maybeLazyUpgradeFromV19();
            MetaVerdict verdict = evaluateMeta();
            this.lastMetaVerdict = verdict;
            switch (verdict.kind()) {
                case OPEN -> { }
                case REFRESH ->
                    // Same registry (ordered compare passed) — the meta is merely
                    // missing/behind on the order-insensitive key (a pre-0.13.1
                    // store): top it up in place, no drop. INSERT OR REPLACE leaves
                    // the migrate_* bookkeeping untouched.
                    writeMeta();
                case KEEP_PERMUTED -> {
                    // Refresh BOTH registry keys to this boot's values, committed
                    // before the batcher exists / any serve. A crash before the
                    // commit re-decides identically next boot — the content compare
                    // is order-insensitive.
                    writeMeta();
                    // The log line is decoration and must never destroy the store the
                    // ladder just decided to keep (fix-review fold: a keptRowsSummary
                    // throw used to fall into the catch-all rebuild below) — a count
                    // failure degrades to a bare line, and the INFO lands only after
                    // the KEEP is durable.
                    String summary;
                    try {
                        summary = keptRowsSummary();
                    } catch (Exception e) {
                        summary = "row count unavailable";
                    }
                    LSSLogger.info("LOD store: registry ids permuted (same content) —"
                            + " store kept (v20 rows are identity-addressed): " + summary);
                }
                case DROP -> {
                    LSSLogger.info("LOD store: " + verdict.detail() + " — dropping and"
                            + " rebuilding the store (derived data, never migrated)");
                    closeWriter();
                    deleteDbFiles();
                    openWriter();
                    writeMeta();
                }
            }
        } catch (Exception first) {
            // Any failure here (corrupt DB, bad page) → drop and rebuild once.
            LSSLogger.warn("LOD store: could not open the existing store — dropping and"
                    + " rebuilding (derived data)", first);
            closeWriter();
            deleteDbFiles();
            openWriter();
            writeMeta();
        }
    }

    private void openWriter() throws SQLException {
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + this.dbPath);
        this.writer = ds.getConnection();
        try (Statement st = this.writer.createStatement()) {
            // Brief lock contention (a reader mid-schema-read, an external inspection
            // tool) must wait, not throw: with timeout 0 a single SQLITE_BUSY costs a
            // writer-failure strike / a purged row (R1 review).
            st.execute("PRAGMA busy_timeout=3000");
            st.execute("PRAGMA page_size=" + PAGE_SIZE);
            // Before any table exists (fresh DB) this arms incremental_vacuum for the
            // Phase 5 eviction; on an existing DB it is a no-op (v1 stores rebuild via
            // the schema bump instead).
            st.execute("PRAGMA auto_vacuum=INCREMENTAL");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS dims (id INTEGER PRIMARY KEY,"
                    + " name TEXT UNIQUE, mask_fingerprint TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS regions (dim INTEGER, rpos INTEGER,"
                    + " seen_mtime INTEGER, PRIMARY KEY (dim, rpos)) WITHOUT ROWID");
            st.execute("CREATE TABLE IF NOT EXISTS backfill (dim TEXT, rx INTEGER,"
                    + " rz INTEGER, done INTEGER, PRIMARY KEY (dim, rx, rz)) WITHOUT ROWID");
        }
        this.writer.setAutoCommit(false);
        this.writer.commit();
        loadDims();
        boolean hasMeta;
        try (Statement st = this.writer.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM meta")) {
            rs.next();
            hasMeta = rs.getLong(1) > 0;
        }
        if (!hasMeta) writeMeta();
        this.writer.commit();
    }

    private Map<String, String> readMetaMap() throws SQLException {
        Map<String, String> meta = new HashMap<>();
        try (Statement st = this.writer.createStatement();
             ResultSet rs = st.executeQuery("SELECT k, v FROM meta")) {
            while (rs.next()) meta.put(rs.getString(1), rs.getString(2));
        }
        return meta;
    }

    /**
     * The C4 lazy schema 3→4 upgrade (XVER §5.1): fires ONLY from the exact released
     * v0.9.x state — {@code schema_version=3 ∧ wire_format_version=19} with matching
     * mc/codec/fingerprint — and upgrades IN PLACE: ALTER each {@code lods_*} table
     * {@code ADD COLUMN wirefmt INTEGER NOT NULL DEFAULT 19} (existing rows ARE
     * native-layout), then stamp meta {@code 4 ∧ 20 ∧ store_layout} in the SAME
     * writer transaction (autocommit is off; {@code writeMeta}'s commit lands the
     * ALTERs and the stamp together). Stamping is IMMEDIATE (the §5.1 review MAJOR:
     * {@code evaluateMeta}'s core gate is an equality compare, so a stamp-on-completion
     * scheme drops the multi-GB store on the first post-upgrade restart) — migration
     * COMPLETION is tracked by the rows' own {@code wirefmt} values, never the
     * version keys. Any OTHER from-state (dev-era metas, ≤18, foreign fingerprint)
     * returns untouched and falls through to {@code evaluateMeta} → drop-and-rebuild;
     * any THROW here reaches {@code openOrRecreateWriter}'s catch → drop-and-rebuild
     * (the {@code pragma table_info} probe makes a half-applied ALTER re-entrant,
     * but the drop is the simpler contract and the fallback is always legal on
     * derived data).
     */
    private void maybeLazyUpgradeFromV19() throws SQLException {
        Map<String, String> meta = readMetaMap();
        if (!"3".equals(meta.get("schema_version"))
                || !"19".equals(meta.get("wire_format_version"))
                || !this.env.mcVersion().equals(meta.get("mc_version"))
                || !StoreCodec.NAME.equals(meta.get("codec"))
                || !this.env.registryFingerprint().equals(meta.get("registry_fingerprint"))) {
            return;
        }
        LSSLogger.info("LOD store: lazy-upgrading the v0.9.x store in place (schema 3 → 4)"
                + " — existing rows tagged wirefmt=19, translated on serve, migrated in"
                + " the background (never a blocking rewrite)");
        long total = 0;
        try (Statement st = this.writer.createStatement()) {
            for (int dimId : this.dimIds.values()) {
                if (!tableHasColumn("lods_" + dimId, "wirefmt")) {
                    st.execute("ALTER TABLE lods_" + dimId
                            + " ADD COLUMN wirefmt INTEGER NOT NULL DEFAULT "
                            + WIREFMT_NATIVE_19);
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM lods_" + dimId)) {
                    rs.next();
                    total += rs.getLong(1);
                }
            }
            // Walk bookkeeping (meta so it survives restarts; the version keys never
            // track completion — §5.1): pending flag + totals; per-dim watermarks are
            // written by the walk itself, riding each batch's transaction.
            st.executeUpdate("INSERT OR REPLACE INTO meta (k, v) VALUES"
                    + " ('migrate_pending','1'), ('migrate_total','" + total
                    + "'), ('migrate_done','0')");
        }
        writeMeta();
    }

    /** {@code pragma table_info} presence probe (SQLite has no ADD COLUMN IF NOT
     *  EXISTS). */
    private boolean tableHasColumn(String table, String column) throws SQLException {
        try (Statement st = this.writer.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    /** The open-time meta verdict (store-registry-permutation-plan.md §3.3). */
    record MetaVerdict(Kind kind, String detail) {
        enum Kind { OPEN, REFRESH, KEEP_PERMUTED, DROP }

        static MetaVerdict drop(String detail) {
            return new MetaVerdict(Kind.DROP, detail);
        }
    }

    private MetaVerdict evaluateMeta() throws SQLException {
        Map<String, String> meta = readMetaMap();
        // Core identity keys — never relaxed, and named in the drop line (the old
        // one-size message sent every report down the wrong path). store_layout is
        // the C4 structural guard: a dev-window store with this schema+wire but no
        // wirefmt column carries no store_layout key → drop-and-rebuild.
        var core = new java.util.LinkedHashMap<String, String>();
        core.put("schema_version", String.valueOf(SCHEMA_VERSION));
        core.put("store_layout", STORE_LAYOUT);
        core.put("wire_format_version", String.valueOf(this.env.wireVersion()));
        core.put("mc_version", this.env.mcVersion());
        core.put("codec", StoreCodec.NAME);
        var drifted = new java.util.ArrayList<String>();
        for (var e : core.entrySet()) {
            if (!e.getValue().equals(meta.get(e.getKey()))) drifted.add(e.getKey());
        }
        if (!drifted.isEmpty()) {
            // Neutral wording — a version bump is not registry drift.
            return MetaVerdict.drop("store metadata drift " + drifted);
        }
        // Registry drift (4-agent round R2-M3): stored wire-v19 bytes embed GLOBAL
        // block-state/biome registry ids, which are assignment-order dependent — a
        // mod or datapack change shifts them while region files stay untouched, so
        // no freshness rule fires and every warm column would decode as wrong
        // blocks/biomes on the (registry-synced) client.
        String storedOrdered = meta.get("registry_fingerprint");
        if (storedOrdered == null) {
            return MetaVerdict.drop("pre-fingerprint store");
        }
        String storedContent = meta.get("registry_content_fingerprint");
        String envContent = this.env.registryContentFingerprint();
        if (this.env.registryFingerprint().equals(storedOrdered)) {
            // Registry unchanged. Top up / advance the order-insensitive key when
            // the platform supplied one; an EMPTY env value is an old-shape caller —
            // leave the meta alone rather than write a junk key.
            boolean topUp = !envContent.isEmpty() && !envContent.equals(storedContent);
            return new MetaVerdict(topUp ? MetaVerdict.Kind.REFRESH
                    : MetaVerdict.Kind.OPEN, "");
        }
        // Ordered mismatch. A pure permutation (identical identity SET, shuffled
        // global ids — VisualWorkbench-class per-boot dynamic registration) is
        // harmless to wire-v20 rows (identity-dictionary addressed, no global ids)
        // but only PROVABLY so: both sides must carry a real content fingerprint
        // ("" == "" is a vacuous match — the plan review's MAJOR-1 guard), the two
        // must agree, and no wirefmt=19 row may exist (legacy bytes embed the
        // permuted ids and translate via the CURRENT boot's registries).
        if (envContent.isEmpty() || storedContent == null || storedContent.isEmpty()) {
            return MetaVerdict.drop(
                    "registry drift (permutation unprovable — no content fingerprint)");
        }
        if (!envContent.equals(storedContent)) {
            return MetaVerdict.drop("registry content drift");
        }
        if (legacyRowsPossible(meta)) {
            return MetaVerdict.drop(
                    "registry ids permuted with legacy (wirefmt=19) rows present");
        }
        return new MetaVerdict(MetaVerdict.Kind.KEEP_PERMUTED, "");
    }

    /** O(1) proxy for "any wirefmt=19 row exists" (plan §3.3 — the naive per-dim
     *  {@code WHERE wirefmt=19 LIMIT 1} walks blob-leaf b-trees on the server thread,
     *  every boot, on exactly the permuting-registry servers this path serves):
     *  19-rows are produced ONLY by the lazy 3→4 upgrade, which sets
     *  {@code migrate_pending=1} in the same transaction; deposits always stamp 20;
     *  the pending key is deleted by the VERIFYING {@code finishMigration} (which
     *  probes every dim once at completion and writes the permanent
     *  {@code migrate_residual} marker if any 19-row survived — the fix-review
     *  MAJOR: the walk's swallowed delete-failure residual used to outlive the
     *  flag) or by a COMPLETED admin drop-all (a shutdown-interrupted drop keeps
     *  the keys so the walk re-arms). Pending-but-actually-complete answers true →
     *  drop, the safe direction for derived data. Accepted residual (fold record,
     *  plan §8): a store that reached the flagless-19-row state under a PRE-0.13.1
     *  jar and then ADOPTed carries no marker — bounded to rare double-fault /
     *  interrupted-drop histories, self-healing via any content change or a manual
     *  invalidate. */
    private static boolean legacyRowsPossible(Map<String, String> meta) {
        return "1".equals(meta.get("migrate_pending"))
                || "1".equals(meta.get("migrate_residual"));
    }

    /** Row/dim counts for the permutation-KEEP line (the §6 live gate greps them).
     *  {@code count(*)} rides the smallest index ({@code lods_<id>_ts}) — it never
     *  touches the blob leaves. */
    private String keptRowsSummary() throws SQLException {
        long rows = 0;
        try (Statement st = this.writer.createStatement()) {
            for (int dimId : this.dimIds.values()) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM lods_" + dimId)) {
                    rs.next();
                    rows += rs.getLong(1);
                }
            }
        }
        return rows + " row(s) across " + this.dimIds.size() + " dimension(s)";
    }

    private void writeMeta() throws SQLException {
        try (PreparedStatement ps = this.writer.prepareStatement(
                "INSERT OR REPLACE INTO meta (k, v) VALUES (?,?)")) {
            for (var e : Map.of(
                    "schema_version", String.valueOf(SCHEMA_VERSION),
                    "wire_format_version", String.valueOf(this.env.wireVersion()),
                    "mc_version", this.env.mcVersion(),
                    "codec", StoreCodec.NAME,
                    // Structural layout key (C4 mega-plan): evaluateMeta compares meta
                    // only, never table structure — a C1..C3-era dev store (meta 4∧20,
                    // NO wirefmt column) would otherwise open "valid" and latch dead at
                    // WRITE_FAILURE_LATCH on the first wirefmt INSERT.
                    "store_layout", STORE_LAYOUT,
                    "registry_fingerprint", this.env.registryFingerprint(),
                    // Order-insensitive twin (v0.13.1 permutation plan §3.1): the
                    // proof that an ordered mismatch is a pure permutation. Written
                    // even when the caller supplied "" (old-shape ctor) — the ladder
                    // treats empty on EITHER side as unprovable, never as a match.
                    "registry_content_fingerprint",
                    this.env.registryContentFingerprint()).entrySet()) {
                ps.setString(1, e.getKey());
                ps.setString(2, e.getValue());
                ps.executeUpdate();
            }
        }
        this.writer.commit();
    }

    private void loadDims() throws SQLException {
        this.dimIds.clear();
        // Clear the reader-side map too: after a drop-and-rebuild a stale entry would
        // point get() at a dropped lods_<id> table (a spurious store.errors per read).
        this.dimIdsShared.clear();
        try (Statement st = this.writer.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM dims")) {
            while (rs.next()) {
                this.dimIds.put(rs.getString(2), rs.getInt(1));
                this.dimIdsShared.put(rs.getString(2), rs.getInt(1));
            }
        }
        // Migrate pre-index stores in place (review A2): IF NOT EXISTS is additive and
        // idempotent — DELIBERATELY not a SCHEMA_VERSION bump, which would drop every
        // warm row for the sake of an index a one-time build provides. On a large
        // legacy store the build is a single pass at first boot.
        try (Statement st = this.writer.createStatement()) {
            for (int id : this.dimIds.values()) {
                st.execute("CREATE INDEX IF NOT EXISTS lods_" + id + "_ts ON lods_"
                        + id + " (ts)");
            }
        }
        this.writer.commit();
    }

    private void deleteDbFiles() throws java.io.IOException {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            try {
                Files.deleteIfExists(this.dbPath.resolveSibling(DB_FILE + suffix));
            } catch (Exception ignored) {
            }
        }
        // Verify the drop actually happened: re-opening over a SURVIVING file (perms,
        // a held handle) would stamp the CURRENT meta over rows written under the old
        // schema/wire/mask — the next wire bump would then serve old-format blobs that
        // round-trip the integrity hash perfectly (R1 review). Throwing here degrades
        // to store-off via createOrNull, the fail-safe direction.
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            if (Files.exists(this.dbPath.resolveSibling(DB_FILE + suffix))) {
                throw new java.io.IOException("stale store file survived drop-and-rebuild: "
                        + DB_FILE + suffix);
            }
        }
    }

    private void closeWriter() {
        for (var ps : this.insertByDim.values()) {
            try { ps.close(); } catch (Exception ignored) { }
        }
        this.insertByDim.clear();
        if (this.writer != null) {
            try { this.writer.close(); } catch (Exception ignored) { }
            this.writer = null;
        }
    }

    // ---- LodStoreService ----

    @Override
    public LodStoreMode mode() {
        return this.mode;
    }

    @Override
    public StoreHit get(String dimension, long packed) {
        if (!this.serving || this.latchedOff) return null;
        var tombs = this.tombstones.get(dimension);
        if (tombs != null && tombs.containsKey(packed)) return null;
        // A dimension mid-drop must not serve the rows it has not reached yet.
        if (this.droppingDims.contains(dimension)) return null;
        Integer dimId = this.dimIdsShared.get(dimension);
        if (dimId == null) return null;
        try {
            Connection c = readerConnection();
            if (c == null) return null;
            PreparedStatement ps = readerStatement(c, dimId, READER_STMT_GET,
                    "SELECT ts, chash, usize, wirefmt, blob FROM lods_" + dimId + " WHERE pos=?");
            ps.setLong(1, packed);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long ts = rs.getLong(1);
                long chash = rs.getLong(2);
                int usize = rs.getInt(3);
                int wirefmt = rs.getInt(4);
                if (usize == 0) return new StoreHit(EMPTY, ts, wirefmt);
                if (usize < 0 || usize > MAX_ROW_USIZE) {
                    // Bound the alloc BEFORE trusting the row's own size field — a
                    // bit-rotted usize otherwise allocates whatever it says (R1).
                    throw new IllegalStateException("row integrity failure at " + packed
                            + " (usize " + usize + " out of bounds)");
                }
                byte[] blob = rs.getBytes(5);
                byte[] raw = this.codec.decompress(blob, usize);
                // Per-row hash dispatch (C4): pre-migration 19-rows were written under
                // FNV-1a 64 (schema 3); everything else validates under CRC32C.
                long expect = wirefmt == WIREFMT_NATIVE_19
                        ? LodStoreService.legacyContentHashFnv(raw) : contentHash(raw);
                if (raw.length != usize || expect != chash) {
                    throw new IllegalStateException("row integrity failure at " + packed
                            + " (usize/chash mismatch)");
                }
                return new StoreHit(raw, ts, wirefmt);
            }
        } catch (Throwable t) {
            invalidateReaderStatement(dimId, READER_STMT_GET);
            this.diag.recordError();
            // Purge ONLY row-poison failures (our integrity throws, a decompress
            // failure) — a transient SQLException (SQLITE_BUSY under the WAL watchdog,
            // an IO hiccup) must not destroy a good row per attempt (R1 review); it
            // reads as a miss and the next re-declaration retries.
            if (!(t instanceof SQLException)) {
                enqueueControl(new Op.DeleteRows(dimension, new long[]{packed}));
            }
            if (this.readErrorWarned.compareAndSet(false, true)) {
                LSSLogger.warn("LOD store read failed — served from disk instead (counted"
                        + " store.errors; further failures are silent)", t);
            }
            return null;
        }
    }

    /**
     * Frame-form lookup (protocol-19 verbatim serving, plan §3): the stored zstd frame
     * WITHOUT a decompress. Integrity parity with {@link #get} comes from frame-level
     * checks — usize bounds, the frame's declared content size, and {@code fhash} over
     * the blob (~2-3 µs vs the ~24 µs decompress + raw hash) — feeding the SAME
     * row-poison purge ladder, so bit-rot dies here exactly as it does on the raw path
     * instead of looping through client decode failures (review A finding 1 of the
     * plan round).
     */
    @Override
    public FrameHit getFrame(String dimension, long packed) {
        if (!this.serving || this.latchedOff) return null;
        var tombs = this.tombstones.get(dimension);
        if (tombs != null && tombs.containsKey(packed)) return null;
        // A dimension mid-drop must not serve the rows it has not reached yet.
        if (this.droppingDims.contains(dimension)) return null;
        Integer dimId = this.dimIdsShared.get(dimension);
        if (dimId == null) return null;
        try {
            Connection c = readerConnection();
            if (c == null) return null;
            PreparedStatement ps = readerStatement(c, dimId, READER_STMT_GET_FRAME,
                    "SELECT ts, fhash, usize, wirefmt, blob FROM lods_" + dimId + " WHERE pos=?");
            ps.setLong(1, packed);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long ts = rs.getLong(1);
                long fhash = rs.getLong(2);
                int usize = rs.getInt(3);
                int wirefmt = rs.getInt(4);
                if (usize == 0) return new FrameHit(EMPTY, 0, ts, wirefmt);
                if (usize < 0 || usize > MAX_ROW_USIZE) {
                    throw new IllegalStateException("row integrity failure at " + packed
                            + " (usize " + usize + " out of bounds)");
                }
                byte[] blob = rs.getBytes(5);
                // Per-row hash dispatch (C4) — see get().
                long expect = wirefmt == WIREFMT_NATIVE_19
                        ? LodStoreService.legacyContentHashFnv(blob) : contentHash(blob);
                if (expect != fhash
                        || this.codec.declaredContentSize(blob) != usize) {
                    throw new IllegalStateException("row integrity failure at " + packed
                            + " (fhash/declared-size mismatch)");
                }
                return new FrameHit(blob, usize, ts, wirefmt);
            }
        } catch (Throwable t) {
            invalidateReaderStatement(dimId, READER_STMT_GET_FRAME);
            this.diag.recordError();
            // Same purge triage as get(): row-poison throws purge; transient
            // SQLExceptions read as a miss and retry on the next re-declaration.
            if (!(t instanceof SQLException)) {
                enqueueControl(new Op.DeleteRows(dimension, new long[]{packed}));
            }
            if (this.readErrorWarned.compareAndSet(false, true)) {
                LSSLogger.warn("LOD store read failed — served from disk instead (counted"
                        + " store.errors; further failures are silent)", t);
            }
            return null;
        }
    }

    /** Thread-confined read-only connection (created lazily per reader-pool thread). */
    private Connection readerConnection() {
        Connection c = this.readerConn.get();
        if (c != null) return c;
        if (this.shutdown.get()) return null; // closing conns; a new one would leak
        Connection created = null;
        try {
            var ds = new org.sqlite.SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + this.dbPath);
            created = ds.getConnection();
            if (this.failNextReaderSetupForTest) {
                // TEST-ONLY fault seam (three-lens review): the F3 pragma-throw path —
                // a throw after getConnection() succeeded must close the handle, and the
                // same thread must recover with a fresh connection on its next read.
                this.failNextReaderSetupForTest = false;
                this.lastReaderSetupFailureConnForTest = created;
                throw new SQLException("test-injected reader setup failure");
            }
            try (Statement st = created.createStatement()) {
                st.execute("PRAGMA busy_timeout=3000"); // see openWriter
                st.execute("PRAGMA query_only=1");
                st.execute("PRAGMA wal_autocheckpoint=0");
            }
            synchronized (this.allReaderConns) {
                // Re-check under the SAME lock shutdown's close loop holds: a shutdown that
                // ran completely between the entry check and here has already cleared the
                // list, and registering now would leak a native handle past close — on
                // Windows a held handle can fail a later same-JVM drop-and-rebuild (see
                // dropDimensionRows). Any interleaving either lands here (self-close) or
                // registers before shutdown's loop takes the lock (closed by the loop).
                if (this.shutdown.get()) {
                    closeQuietly(created);
                    return null;
                }
                this.allReaderConns.add(created);
            }
            // Publish to the thread-local only AFTER registration — a self-closed
            // connection must never be handed to later reads on this thread.
            this.readerConn.set(created);
            return created;
        } catch (Throwable t) {
            // A throw from the pragmas (or registration) after getConnection() succeeded
            // used to leak the unregistered handle — and because readerConn was never set,
            // EVERY subsequent read on the thread re-opened and re-leaked one (2026-08-05
            // review F3), unbounded on exactly the box already in trouble.
            closeQuietly(created);
            this.diag.recordError();
            return null;
        }
    }

    private static void closeQuietly(Connection c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) { }
    }

    private static final int READER_STMT_GET = 0;
    private static final int READER_STMT_GET_FRAME = 1;

    /**
     * Per-thread per-dimension cached reader SELECTs (2026-08-05 review P4): every disk
     * submit while the store serves pays a {@code get}/{@code getFrame}, and re-preparing
     * the SELECT cost ~5-20 µs against a ~100 µs hit. Readers are thread-confined
     * ({@link #readerConnection()}), so the cache is race-free by construction; statements
     * die with their connection (sqlite-jdbc closes statements on {@code Connection.close()}),
     * and the map is per-store-instance so a drop-and-rebuild (a fresh instance) can never
     * serve stale handles. No DDL touches the lods tables while serving (schema/mask/registry
     * drift rebuilds the whole store; {@code dropDimensionRows} DELETEs), so a cached
     * statement cannot silently go stale — a broken one throws and is invalidated below.
     */
    private final ThreadLocal<java.util.HashMap<Long, PreparedStatement>> readerStatements =
            ThreadLocal.withInitial(java.util.HashMap::new);

    private PreparedStatement readerStatement(Connection c, int dimId, int kind, String sql)
            throws SQLException {
        long key = ((long) dimId << 1) | kind;
        var cache = this.readerStatements.get();
        PreparedStatement ps = cache.get(key);
        if (ps == null || ps.isClosed()) {
            ps = c.prepareStatement(sql);
            cache.put(key, ps);
        }
        return ps;
    }

    /** Drop (and close) the calling thread's cached statement after a read failure — a
     *  broken statement must not wedge the thread's future reads on that dimension. */
    private void invalidateReaderStatement(int dimId, int kind) {
        PreparedStatement ps = this.readerStatements.get().remove(((long) dimId << 1) | kind);
        if (ps != null) {
            try { ps.close(); } catch (Exception ignored) { }
        }
    }

    @Override
    public boolean deposit(String dimension, long packed, byte[] sectionBytes, long columnTimestamp,
                           long acquiredEpochSeconds) {
        if (this.shutdown.get() || this.latchedOff) return false;
        byte[] normalized = sectionBytes == null || sectionBytes.length == 0 ? EMPTY : sectionBytes;
        // src_stamp: the byte-ACQUISITION wall second (read start / gen serialization),
        // never the column timestamp — the column ts of an untouched row can EQUAL its
        // region header stamp, and the sweep's `>=` would then drop every untouched row
        // in any region whose mtime moved. Callers without a stamp (<=0) fall back to
        // the deposit-call second — later than acquisition, so a save landing in the
        // acquisition→deposit gap is sweep-invisible on that path (R1-M2: all
        // production serve paths now pass the real acquisition stamp). Clamped to now:
        // a future stamp would blind the sweep to every subsequent save.
        long nowSeconds = System.currentTimeMillis() / 1000L;
        long srcStampSeconds = acquiredEpochSeconds > 0
                ? Math.min(acquiredEpochSeconds, nowSeconds) : nowSeconds;
        // Shed policy: deposits are droppable (the NBT ladder re-deposits on the next
        // serve); the oldest DEPOSIT in the queue is shed to admit the newest.
        Op.Deposit op = new Op.Deposit(dimension, packed, normalized, columnTimestamp,
                srcStampSeconds, System.nanoTime());
        boolean shed = false;
        while (!this.queue.offer(op)) {
            if (this.shutdown.get() || this.latchedOff) return false;
            if (this.queue.poll() != null) {
                this.diag.recordDepositDrop();
                shed = true;
            }
        }
        // Deliberately NO gauge write here: store.queue is a DRAIN-SIDE gauge (the
        // documented SERVER_DRAINS contract) — producer-side writes made sub-200 ms
        // transients visible to the soak quiescence predicate, and the Phase 3 save
        // hook's idle-save trickle red-ded converged runs on a 1-deep flicker. The
        // batcher stamps the depth after every drain pass; sustained backlogs still
        // show.
        return !shed;
    }

    @Override
    public boolean depositFrame(String dimension, long packed, byte[] frame, int usize,
                                long chash, long fhash, long columnTimestamp,
                                long srcStampSeconds) {
        // The frame path never carries all-air (that stays the raw path's byte[0]
        // normalization) — refuse nonsense so the caller's raw fallback handles it.
        if (frame == null || frame.length == 0 || usize <= 0) return false;
        if (this.shutdown.get() || this.latchedOff) return false;
        long nowSeconds = System.currentTimeMillis() / 1000L;
        long stamp = srcStampSeconds > 0 ? Math.min(srcStampSeconds, nowSeconds) : nowSeconds;
        Op.Deposit op = new Op.Deposit(dimension, packed, frame, columnTimestamp,
                stamp, System.nanoTime(), true, usize, chash, fhash);
        while (!this.queue.offer(op)) {
            if (this.shutdown.get() || this.latchedOff) return false;
            if (this.queue.poll() != null) {
                this.diag.recordDepositDrop();
                // Shed still returns TRUE (the frame WAS enqueued): the caller's false
                // fallback is a raw deposit, and shed-as-false would double-deposit.
            }
        }
        return true;
    }

    @Override
    public void invalidate(String dimension, long[] positions) {
        // Guard BEFORE the tombstone stamp (review B2): a latched store's batcher has
        // exited, so nothing ever sweeps tombstones again — the dirty fan-out would
        // grow the map for the rest of the session while the store serves nothing the
        // stamps could protect.
        if (this.shutdown.get() || this.latchedOff) return;
        long now = System.nanoTime();
        var tombs = this.tombstones.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        for (long packed : positions) {
            tombs.put(packed, now);
        }
        enqueueControl(new Op.DeleteRows(dimension, positions.clone()));
    }

    @Override
    public void delete(String dimension, long packed) {
        if (this.shutdown.get() || this.latchedOff) return; // see invalidate()
        this.tombstones.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                .put(packed, System.nanoTime());
        enqueueControl(new Op.DeleteRows(dimension, new long[]{packed}));
    }

    /** Deletes/resweeps: unbounded, never shed (see {@link #controlQueue}).
     *  No gauge write — drain-side gauge (see deposit()). */
    private void enqueueControl(Op op) {
        if (this.shutdown.get() || this.latchedOff) return;
        this.controlQueue.add(op);
    }

    private int queueDepth() {
        return this.queue.size() + this.controlQueue.size();
    }

    @Override
    public LodStoreDiagnostics diagnostics() {
        return this.diag;
    }

    /** Store health for long-running background users (the backfill driver): serving
     *  (startup sweep done) and not latched off. A latched store silently no-ops every
     *  write — a driver that keeps walking would burn IO for nothing and claim
     *  progress (review MAJOR). */
    public boolean isHealthy() {
        return this.serving && !this.latchedOff;
    }

    /** Row-existence check WITHOUT the blob fetch + decompress + integrity hash a full
     *  get() pays — the backfill's skip rung (review finding: a warm region walk was
     *  1024 back-to-back full-row reads). Tombstones honored like get(). */
    public boolean hasRow(String dimension, long packed) {
        if (!this.serving || this.latchedOff) return false;
        var tombs = this.tombstones.get(dimension);
        if (tombs != null && tombs.containsKey(packed)) return false;
        // A dimension mid-drop must not report rows it has not reached yet — the
        // backfill's hasRow skip would otherwise treat them as already warm.
        if (this.droppingDims.contains(dimension)) return false;
        Integer dimId = this.dimIdsShared.get(dimension);
        if (dimId == null) return false;
        try {
            Connection c = readerConnection();
            if (c == null) return false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM lods_" + dimId + " WHERE pos=?")) {
                ps.setLong(1, packed);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (Throwable t) {
            this.diag.recordError();
            return false;
        }
    }

    /** The /lsslod store invalidate-all ops lever (Phase 5): drops every row of every
     *  dimension on the batcher (tombstoned; counted sweep_drops). Re-warms from
     *  serves/backfill. Rows read in the sub-second queue window serve what they would
     *  have a moment earlier — acceptable for a remediation lever. */
    public void requestDropAllRows() {
        enqueueControl(new Op.DropAll());
    }

    /** Backfill progress (Phase 4): mark a region fully processed (batcher-written,
     *  dropped with the DB — derived data). */
    public void markBackfillRegionDone(String dimension, int rx, int rz) {
        enqueueControl(new Op.BackfillMark(dimension, rx, rz));
    }

    /** Backfill support (4-agent round R3): wait until the bounded deposit queue has
     *  drained — every deposit enqueued so far has been TAKEN by the batcher, and the
     *  single-threaded apply then orders any later control op (the region done-mark)
     *  strictly after them in the txn stream, so a crash can no longer persist a mark
     *  whose deposits were lost. False on timeout, shutdown, or a latched store. */
    public boolean awaitDepositQueueEmpty(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            if (this.latchedOff || this.shutdown.get()) return false;
            if (this.queue.isEmpty()) return true;
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Whether a region was already backfilled (backfill-thread read connection). */
    public boolean isBackfillRegionDone(String dimension, int rx, int rz) {
        if (this.latchedOff) return true; // latched store: do no work
        try {
            Connection c = readerConnection();
            if (c == null) return false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT done FROM backfill WHERE dim=? AND rx=? AND rz=?")) {
                ps.setString(1, dimension);
                ps.setInt(2, rx);
                ps.setInt(3, rz);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) != 0;
                }
            }
        } catch (Throwable t) {
            this.diag.recordError();
            return false;
        }
    }

    /** Blocks until the startup sweep finishes (test/harness seam). */
    public boolean awaitSweep(long timeoutMs) throws InterruptedException {
        return this.sweepDone.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Registers the sweep-drop fan-out (called on the batcher thread with each swept
     *  dimension's dropped positions; the tiered composition points this at the memory
     *  tier's invalidate). Set before serving starts. */
    public void setSweepDropListener(java.util.function.BiConsumer<String, long[]> listener) {
        this.sweepDropListener = listener;
    }

    private void notifySweepDrops(String dimension, List<Long> positions) {
        if (positions.isEmpty()) return;
        this.diag.recordSweepDrops(positions.size());
        var listener = this.sweepDropListener;
        if (listener == null) return;
        long[] packed = new long[positions.size()];
        for (int i = 0; i < packed.length; i++) packed[i] = positions.get(i);
        try {
            listener.accept(dimension, packed);
        } catch (Throwable t) {
            this.diag.recordError();
        }
    }

    @Override
    public void shutdown() {
        if (!this.shutdown.compareAndSet(false, true)) return;
        this.serving = false; // a post-shutdown get() must miss, not race closing conns
        this.batcher.interrupt();
        try {
            this.batcher.join(5000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (this.allReaderConns) {
            for (var c : this.allReaderConns) {
                try { c.close(); } catch (Exception ignored) { }
            }
            this.allReaderConns.clear();
        }
        // The batcher exited (or is wedged — then skip: single-writer discipline).
        if (!this.batcher.isAlive()) {
            try {
                if (this.writer != null) {
                    // Deliberately NO mtime snapshot here: seen_mtime is only ever
                    // written next to an actual header examination (see sweepDimension)
                    // — a shutdown-time stat would mark unexamined regions as seen and
                    // let offline edits skip every future sweep.
                    this.writer.commit();
                    checkpointTruncate();
                }
            } catch (Throwable ignored) {
            }
            closeWriter();
        }
        this.diag.setQueueDepth(0);
    }

    // ---- batcher thread ----

    /** Arm the C4 walk from meta (caller thread, before the batcher starts). */
    private void initMigrationState() throws SQLException {
        Map<String, String> meta = readMetaMap();
        if (!"1".equals(meta.get("migrate_pending"))) return;
        long total = parseLongOr(meta.get("migrate_total"), 0);
        long done = parseLongOr(meta.get("migrate_done"), 0);
        this.migrateTotal = total;
        this.migrateRemaining.set(Math.max(0, total - done));
        this.migrateDims.addAll(this.dimIds.values());
        this.migratePending = true;
        LSSLogger.info("LOD store: background migration pending — "
                + this.migrateRemaining.get() + "/" + total + " rows to rewrite to the"
                + " v20 body format (idle-paced; serves translate meanwhile)");
    }

    private static long parseLongOr(String s, long dflt) {
        try {
            return s == null ? dflt : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /**
     * One background-migration batch (C4, XVER §5.4; batcher thread, IDLE iterations
     * only): up to {@link #MIGRATE_ROWS_PER_BATCH} {@code wirefmt=19} rows of the
     * current dim — decompress → translate native→v20 → recompress → UPDATE row
     * (CRC32C hashes, {@code wirefmt=20}, {@code ts}/{@code src_stamp} untouched so
     * age order and sweep semantics survive) — with the per-dim watermark riding the
     * SAME transaction as its batch (a rolled-back batch retries because the
     * watermark rolled back with it; a watermark committed apart from its rows would
     * silently skip them). A per-row parse/translate anomaly DELETES the row (derived
     * data). The {@code AND wirefmt=19} guard on the UPDATE yields to a concurrent
     * re-deposit (latest-wins already retagged the row). SQL throws propagate to the
     * batcher's shared failure handling (rollback + WRITE_FAILURE_LATCH).
     */
    /** @return true when a batch did real committed work (the caller resets the
     *  writer-failure streak on it — a committed migration batch IS successful writer
     *  work, unlike a vacuous idle iteration). */
    private boolean maybeMigrateBatch() throws Exception {
        if (!this.migratePending) return false;
        // Dev-only walk hold (-Dlss.soak.migrationHoldSeconds, default 0 = off): the
        // store-migration soak gate needs the client's join to PROVABLY overlap the
        // 19-row serving window — without a hold the ~2.4k-row scenario walk finishes
        // seconds after boot, long before the join, and the gate's "served warm from
        // legacy rows" premise is timing-vacuous (pre-D3 review L3-1). One nanoTime
        // compare per idle iteration in production, where the property is unset.
        if (this.migrationHoldUntilNanos != 0) {
            if (System.nanoTime() - this.migrationHoldUntilNanos < 0) {
                if (!this.migrationHoldLogged) {
                    this.migrationHoldLogged = true;
                    LSSLogger.info("Store migration walk HELD (lss.soak.migrationHoldSeconds)");
                }
                return false;
            }
            this.migrationHoldUntilNanos = 0;
            LSSLogger.info("Store migration walk RESUMING after soak hold");
        }
        var translator = this.legacyMigrationTranslator;
        if (translator == null) return false;
        Integer dimId = this.migrateDims.peekFirst();
        if (dimId == null) {
            finishMigration();
            return false;
        }
        long watermark = parseLongOr(readMetaMap().get("migrate_progress_" + dimId),
                Long.MIN_VALUE);
        record Row(long pos, int usize, byte[] blob) {}
        var rows = new java.util.ArrayList<Row>(MIGRATE_ROWS_PER_BATCH);
        try (PreparedStatement ps = this.writer.prepareStatement(
                "SELECT pos, usize, blob FROM lods_" + dimId
                        + " WHERE wirefmt=" + WIREFMT_NATIVE_19 + " AND pos>?"
                        + " ORDER BY pos LIMIT " + MIGRATE_ROWS_PER_BATCH)) {
            ps.setLong(1, watermark);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong(1), rs.getInt(2), rs.getBytes(3)));
                }
            }
        }
        if (rows.isEmpty()) {
            // Dim exhausted: retire it (and its watermark) inside the shared txn.
            this.migrateDims.pollFirst();
            try (PreparedStatement ps = this.writer.prepareStatement(
                    "DELETE FROM meta WHERE k=?")) {
                ps.setString(1, "migrate_progress_" + dimId);
                ps.executeUpdate();
            }
            bumpTxn(1);
            return false;
        }
        int migrated = 0;
        int anomalies = 0;
        try (PreparedStatement up = this.writer.prepareStatement(
                "UPDATE lods_" + dimId + " SET chash=?, usize=?, fhash=?, wirefmt="
                        + WIREFMT_V20 + ", blob=? WHERE pos=? AND wirefmt="
                        + WIREFMT_NATIVE_19);
             PreparedStatement del = this.writer.prepareStatement(
                     "DELETE FROM lods_" + dimId + " WHERE pos=?")) {
            for (Row row : rows) {
                if (row.usize() == 0) {
                    // All-air row (C6 review M-1): usize 0 is the LEGITIMATE all-air
                    // shape, not corruption — the serve path short-circuits it before
                    // any hash/body work on both rungs, and the walk must do the same.
                    // Bare retag; hashes are never consulted at usize 0. The C4 bound
                    // below deliberately rejects usize <= 0 as poison — all-air must
                    // be peeled off BEFORE it, or every End/void column of a real
                    // v0.9.x upgrade is deleted and reported as corruption.
                    try (PreparedStatement retag = this.writer.prepareStatement(
                            "UPDATE lods_" + dimId + " SET wirefmt=" + WIREFMT_V20
                                    + " WHERE pos=? AND wirefmt=" + WIREFMT_NATIVE_19)) {
                        retag.setLong(1, row.pos());
                        retag.executeUpdate();
                        migrated++;
                    } catch (Throwable rowFailure) {
                        anomalies++;
                    }
                    continue;
                }
                try {
                    // The R1 bound, HERE too (C4 review CRITICAL-1): decompress
                    // allocates byte[usize] from the row's own size field — a
                    // bit-rotted usize otherwise attempts a multi-GB allocation on the
                    // batcher, and the resulting Error escaped the old catch(Exception)
                    // into a rollback → same-batch retry every 200 ms → the failure
                    // latch → a restart-surviving dead store.
                    if (row.usize() <= 0 || row.usize() > MAX_ROW_USIZE) {
                        throw new IllegalStateException("usize " + row.usize()
                                + " out of bounds");
                    }
                    byte[] raw = this.codec.decompress(row.blob(), row.usize());
                    byte[] v20 = translator.apply(raw);
                    byte[] frame = this.codec.compress(v20);
                    up.setLong(1, contentHash(v20));
                    up.setInt(2, v20.length);
                    up.setLong(3, contentHash(frame));
                    up.setBytes(4, frame);
                    up.setLong(5, row.pos());
                    up.executeUpdate();
                    migrated++;
                } catch (Throwable rowFailure) {
                    // Throwable, not Exception (review CRITICAL-1): an OOM from a
                    // hostile row or an Error out of the translator must resolve THIS
                    // row, never escape into the batch-retry loop. Derived data: the
                    // row is deleted; the next serve re-warms it from region truth.
                    anomalies++;
                    try {
                        del.setLong(1, row.pos());
                        del.executeUpdate();
                    } catch (Throwable deleteFailure) {
                        // Forward progress is absolute: a row whose DELETE also fails
                        // stays tagged 19 BEHIND the watermark — served translated
                        // forever, never revisited by the walk. Bounded and correct,
                        // strictly better than a batch that can never advance.
                    }
                }
            }
        }
        long newWatermark = rows.get(rows.size() - 1).pos();
        try (PreparedStatement ps = this.writer.prepareStatement(
                "INSERT OR REPLACE INTO meta (k, v) VALUES (?,?)")) {
            ps.setString(1, "migrate_progress_" + dimId);
            ps.setString(2, String.valueOf(newWatermark));
            ps.executeUpdate();
            ps.setString(1, "migrate_done");
            ps.setString(2, String.valueOf(
                    this.migrateTotal - Math.max(0, this.migrateRemaining.get() - rows.size())));
            ps.executeUpdate();
        }
        // Rows + watermark + done-count commit as ONE transaction.
        this.txnRows += rows.size() + 2;
        if (this.failNextMigrationBatches > 0) {
            // Test seam (review #8): fault the batch AFTER its UPDATEs, BEFORE the
            // commit — the rollback must retract rows AND watermark together.
            this.failNextMigrationBatches--;
            throw new SQLException("injected migration-batch failure (test seam)");
        }
        commitTxn();
        this.migrateRemaining.addAndGet(-rows.size());
        this.diag.recordMigrated(migrated, anomalies);
        return true;
    }

    /** Test seam (review #8): fail the next N migration batches between their row
     *  UPDATEs and the commit, driving the watermark-rides-the-batch-txn invariant. */
    void failNextMigrationBatchesForTest(int n) {
        this.failNextMigrationBatches = n;
    }

    /** 0 once every armed fault has fired (the test's proof the fault path ran). */
    int pendingInjectedMigrationFaultsForTest() {
        return this.failNextMigrationBatches;
    }

    private void finishMigration() throws SQLException {
        // VERIFY before clearing the marker (v0.13.1 fix-review fold): the walk's
        // documented residual — a row whose translate anomaly'd AND whose fallback
        // DELETE also failed stays tagged 19 BEHIND the watermark — used to outlive
        // migrate_pending, and under the permutation ladder a flagless 19-row is a
        // wrong-data KEEP (mistranslated legacy bytes). One probe per dim, ONCE per
        // store lifetime, on the batcher thread; any hit writes the permanent
        // migrate_residual marker (legacyRowsPossible's second term) so a permuted
        // boot still drops. The walk bookkeeping clears either way — re-arming a
        // walk that can never delete its stuck row would just re-fail each boot.
        boolean residual = false;
        try (Statement st = this.writer.createStatement()) {
            for (int dimId : this.dimIds.values()) {
                try (ResultSet rs = st.executeQuery("SELECT 1 FROM lods_" + dimId
                        + " WHERE wirefmt=" + WIREFMT_NATIVE_19 + " LIMIT 1")) {
                    if (rs.next()) {
                        residual = true;
                        break;
                    }
                }
            }
            if (residual) {
                st.executeUpdate("INSERT OR REPLACE INTO meta (k, v)"
                        + " VALUES ('migrate_residual','1')");
            }
            st.executeUpdate("DELETE FROM meta WHERE k IN ('migrate_pending',"
                    + " 'migrate_total', 'migrate_done')"
                    + " OR k LIKE 'migrate_progress_%'");
        }
        // commitTxn, not a raw commit (review m15): keep the txn bookkeeping
        // (txnRows / sweepReopened pruning) consistent with every other commit site.
        this.txnRows++;
        commitTxn();
        this.migratePending = false;
        this.migrateRemaining.set(0);
        long anomalies = this.diag.getMigrateAnomalies();
        LSSLogger.info("LOD store: background migration complete — "
                + this.diag.getMigratedRows() + " rows rewritten to v20"
                + (residual
                        ? " (RESIDUAL legacy rows remain — marked; registry"
                                + " permutations will rebuild this store)"
                        : "")
                + (anomalies > 0
                        ? ", " + anomalies + " unreadable rows DELETED (re-warm from"
                                + " serves/backfill — investigate if large)"
                        : "") + " of " + this.migrateTotal + " walked");
    }

    @Override
    public void setLegacyMigrationTranslator(java.util.function.UnaryOperator<byte[]> t) {
        this.legacyMigrationTranslator = t;
    }

    @Override
    public String migrationStatusToken() {
        // A latched store must LOOK dead (review B1) — no healthy-looking progress.
        if (!this.migratePending || this.latchedOff) return "";
        long anomalies = this.diag.getMigrateAnomalies();
        return " migrating=" + Math.max(0, this.migrateRemaining.get())
                + "/" + this.migrateTotal
                // Row DELETION is irreversible data loss on a production store — it
                // must be visible, not write-only (review #4).
                + (anomalies > 0 ? " migrate_anomalies=" + anomalies : "");
    }

    private void batcherLoop() {
        try {
            startupSweep();
        } catch (InterruptedException e) {
            // Shutdown mid-sweep: serving simply never turns on — not a failure.
        } catch (Throwable t) {
            // The one latch path that never counted an error (review B1): without this
            // a boot-dead store shows err=0 in every status surface.
            this.diag.recordError();
            latchOff("startup sweep failed", t);
        } finally {
            this.sweepDone.countDown();
        }
        this.nextResweepNanos = this.env.resweepSeconds() > 0
                ? System.nanoTime() + TimeUnit.SECONDS.toNanos(this.env.resweepSeconds())
                : Long.MAX_VALUE;
        while (!this.shutdown.get() && !this.latchedOff) {
            // Test seam: lets a test single-step the batcher, so the window between
            // "one op applied" and "the rest still queued" — which real stalls open
            // (runSweep on a large store, dropDimensionRows, a WAL TRUNCATE, the
            // vacuum drain) but which drains far too fast to observe from another
            // thread — becomes deterministic. Null in production: one reference read
            // per iteration. See pauseBatcherForTest.
            var steps = this.batcherStepsForTest;
            if (steps != null) {
                try {
                    steps.acquire();
                } catch (InterruptedException e) {
                    break;
                }
            }
            Op op = this.controlQueue.poll(); // deletes first: they must never wait behind deposits
            if (op == null) {
                try {
                    op = this.queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
            }
            long now = System.nanoTime();
            try {
                boolean migrationWorked = false;
                if (op == null) {
                    commitTxn(); // idle flush: never hold a sub-batch across the snapshot cadence
                    migrationWorked = maybeMigrateBatch();
                    this.opsSinceMigrateBatch = 0;
                } else {
                    apply(op);
                    // The busy floor (review #3): strict idle-gating starved the walk
                    // to ZERO under any steady deposit traffic (a 500 cps backfill
                    // never leaves a 200 ms idle window), inverting §5.5's
                    // migration-before-backfill intent. One batch per 32 applied ops
                    // keeps the walk at ~deposit pace while still yielding hard.
                    if (this.migratePending && ++this.opsSinceMigrateBatch >= 32) {
                        this.opsSinceMigrateBatch = 0;
                        migrationWorked = maybeMigrateBatch();
                    }
                }
                // Every iteration, not idle-only (R1 review: a continuously-busy batcher
                // — sustained backfill, edit storms — never saw an idle iteration and the
                // tombstone map grew for the duration). Queued-deposit safety is the
                // expiry floor inside sweepTombstones, no longer the idle gate.
                sweepTombstones(now);
                if (now >= this.nextResweepNanos) {
                    this.nextResweepNanos = now + TimeUnit.SECONDS.toNanos(this.env.resweepSeconds());
                    commitTxn();
                    runSweep(false);
                }
                maybeRefreshGauges(now);
                // Reset the failure streak only when an op actually applied: idle
                // iterations are vacuous no-ops and must not launder a broken writer
                // below the latch under sparse traffic (review finding).
                // A committed migration batch is real writer work too (review #2:
                // without it the latch became a LIFETIME budget of 20 for the walk —
                // it only ran on idle iterations, which never reset the streak).
                if (op != null || migrationWorked) this.writerFailures = 0;
            } catch (Throwable t) {
                // Shutdown-abort of a periodic resweep is a clean exit, not a writer
                // failure (review B5): runSweep signals it with InterruptedException,
                // which used to land here as a store.error + a latch strike — a latent
                // soak false-positive (store.errors == 0 checks) on shutdown timing.
                if (t instanceof InterruptedException && this.shutdown.get()) break;
                this.diag.recordError();
                rollbackTxn();
                // A DELETE must never be lost to a transient failure: its tombstone
                // expires after TOMBSTONE_TTL_NANOS and the row would resurrect.
                // Re-queue it — retries are bounded by the failure latch below, and
                // latchOff() stops serving, so a permanently broken writer can not
                // serve the undeleted row either.
                if (op instanceof Op.DeleteRows) this.controlQueue.add(op);
                if (++this.writerFailures >= WRITE_FAILURE_LATCH) {
                    latchOff("repeated write failures", t);
                }
            }
            this.diag.setQueueDepth(queueDepth());
        }
        // Graceful exit: flush queued deletes (never shed — see controlQueue), then the
        // txn. Containment is PER OP: one failing delete must not abandon the rest
        // (the boot sweep is the cross-restart backstop for whatever still fails —
        // an invalidated column's later region save advances its header stamp).
        Op op;
        while ((op = this.controlQueue.poll()) != null) {
            if (op instanceof Op.DeleteRows) {
                try {
                    apply(op);
                } catch (Throwable ignored) {
                    rollbackTxn();
                }
            }
        }
        try { commitTxn(); } catch (Throwable ignored) { }
    }

    private void latchOff(String why, Throwable t) {
        this.latchedOff = true;
        this.serving = false;
        if (this.latchWarned.compareAndSet(false, true)) {
            LSSLogger.warn("LOD store disabled for this session (" + why + ") — serving"
                    + " continues via the normal disk path; the store rebuilds next start"
                    + " (derived data)", t);
        }
        this.queue.clear();
        this.diag.setQueueDepth(0);
    }

    /** TEST-ONLY fault seam (review C3): the next N applied ops throw before any of
     *  their writes, exercising the rollback + re-queue + failure-latch paths that had
     *  zero coverage (the invariants whose comments explain why they exist). */
    private volatile int failOpsForTest;

    void failNextOpsForTest(int n) {
        this.failOpsForTest = n;
    }

    /** TEST-ONLY: registered reader connections (review F3 — must be 0 after shutdown;
     *  a nonzero count would be a native-handle leak past the close loop). */
    int readerConnCountForTest() {
        synchronized (this.allReaderConns) {
            return this.allReaderConns.size();
        }
    }

    /** TEST-ONLY fault seam for the F3 pragma-throw path — see readerConnection. */
    private volatile boolean failNextReaderSetupForTest;
    private volatile Connection lastReaderSetupFailureConnForTest;

    void failNextReaderSetupForTest() {
        this.failNextReaderSetupForTest = true;
    }

    Connection lastReaderSetupFailureConnForTest() {
        return this.lastReaderSetupFailureConnForTest;
    }

    /** TEST-ONLY: the calling thread's cached reader-statement count (review P4 pin —
     *  bounded at dims × 2 kinds; growth here is a statement leak). */
    int readerStatementCacheSizeForTest() {
        return this.readerStatements.get().size();
    }

    private void apply(Op op) throws Exception {
        if (this.failOpsForTest > 0) {
            this.failOpsForTest--;
            throw new SQLException("test-injected writer failure");
        }
        switch (op) {
            case Op.Deposit dep -> applyDeposit(dep);
            case Op.DeleteRows del -> {
                Integer dimId = this.dimIds.get(del.dim());
                if (dimId != null) {
                    try (PreparedStatement ps = this.writer.prepareStatement(
                            "DELETE FROM lods_" + dimId + " WHERE pos=?")) {
                        for (long p : del.positions()) {
                            ps.setLong(1, p);
                            ps.executeUpdate();
                        }
                    }
                    // Commit IMMEDIATELY, never inside the shared 64-row txn: a LATER
                    // op's failure rolls the txn back, and the failure handler re-queues
                    // only the op that THREW — an applied-but-uncommitted delete would be
                    // silently undone, its tombstone expires, and the stale row
                    // resurrects (4-agent round R1-M1). Deletes are edit-rate-rare; the
                    // lost batching is noise.
                    this.txnRows += del.positions().length;
                    commitTxn();
                }
            }
            case Op.Resweep ignored -> {
                commitTxn();
                runSweep(false);
            }
            case Op.DropAll ignored -> {
                // The /lsslod store invalidate all ops lever: every dimension's rows
                // tombstoned + dropped (counted sweep_drops — maintenance culls share
                // that counter); the store re-warms from serves/backfill.
                commitTxn();
                // Fence FIRST, drop second. Review B9 fences in-flight backfill
                // done-marks so a region judged before this drop cannot be marked done
                // after it — but the bump used to happen at the END of the arm, leaving
                // the whole drop unfenced. That window was a single `DELETE FROM` when
                // it was written; batching the drop (v0.9.0, to bound its heap) stretched
                // it to tens of seconds on a large store, which is long enough for a
                // concurrent walk of an already-warm region to see drained=true, read the
                // pre-bump generation, and enqueue a BackfillMark that lands after the
                // backfill table is cleared — a done-marked region with zero rows, never
                // re-walked. Bumping up front makes the whole drop fenced.
                this.dropGeneration++;
                // The backfill progress table must reset with the rows it describes
                // (4-agent round R3-M1): done-marks surviving the drop made the
                // documented "invalidate all -> re-backfill" remediation enumerate 0
                // regions — the store then only re-warmed where players walked. Cleared
                // up front too, so a mark racing the drop cannot survive in it.
                try (Statement st = this.writer.createStatement()) {
                    st.executeUpdate("DELETE FROM backfill");
                }
                this.writer.commit();
                for (var e : List.copyOf(this.dimIds.entrySet())) {
                    if (this.shutdown.get()) break; // same reason as inside the drop loop
                    // dropDimensionRows publishes each batch to the sweep-drop
                    // listener and installs the O(1) drop barrier itself; it no
                    // longer needs a tombstone per position.
                    dropDimensionRows(e.getKey(), e.getValue());
                }
                // C4 (review m16, hardened by the v0.13.1 fix-review fold): the walk
                // bookkeeping resets AFTER the drop loop AND ONLY when the drop ran to
                // completion — the clear used to run unconditionally, so a shutdown
                // mid-drop left surviving 19-rows with the walk state DELETED (exactly
                // what this comment claimed could not happen), and under the
                // permutation ladder a flagless 19-row is a wrong-data KEEP. With the
                // guard, an interrupted drop keeps the meta, the walk re-arms next
                // boot, and it simply finds fewer rows. The completed clear also
                // retires any migrate_residual marker — zero rows means zero 19-rows.
                if (!this.shutdown.get()) {
                    try (Statement st = this.writer.createStatement()) {
                        st.executeUpdate("DELETE FROM meta WHERE k IN ('migrate_pending',"
                                + " 'migrate_total', 'migrate_done', 'migrate_residual')"
                                + " OR k LIKE 'migrate_progress_%'");
                    }
                    this.writer.commit();
                    this.migratePending = false;
                    this.migrateRemaining.set(0);
                    this.migrateDims.clear();
                    LSSLogger.info("LOD store: dropped all rows + backfill progress"
                            + " (admin invalidate)");
                }
            }
            case Op.BackfillMark mark -> {
                try (PreparedStatement ps = this.writer.prepareStatement(
                        "INSERT OR REPLACE INTO backfill (dim, rx, rz, done) VALUES (?,?,?,1)")) {
                    ps.setString(1, mark.dim());
                    ps.setInt(2, mark.rx());
                    ps.setInt(3, mark.rz());
                    ps.executeUpdate();
                }
                bumpTxn(1);
            }
        }
    }

    private void applyDeposit(Op.Deposit dep) throws Exception {
        var tombs = this.tombstones.get(dep.dim());
        if (tombs != null) {
            Long t = tombs.get(dep.packed());
            if (t != null && t >= dep.enqueuedNanos()) {
                this.diag.recordDepositSkip();
                return;
            }
        }
        // Whole-dimension drop barrier — the O(1) stand-in for the per-position
        // tombstones a full drop used to stamp (see dropDimensionRows). Same rule: a
        // deposit enqueued at or before the drop must not resurrect a dropped row.
        Long barrier = this.dropBarrierNanos.get(dep.dim());
        if (barrier != null && dep.enqueuedNanos() - barrier <= 0) {
            this.diag.recordDepositSkip();
            return;
        }
        int dimId = dimIdFor(dep.dim());
        // Pre-framed deposits (compressed sessions) carry the wire frame + caller-computed
        // hashes — the batcher skips its compress entirely (plan §3); raw deposits keep
        // the compress-on-batcher shape.
        byte[] blob;
        int usize;
        long chash;
        long fhash;
        if (dep.preFramed()) {
            blob = dep.bytes();
            usize = dep.usize();
            chash = dep.chash();
            fhash = dep.fhash();
        } else {
            blob = dep.bytes().length == 0 ? EMPTY : this.codec.compress(dep.bytes());
            usize = dep.bytes().length;
            chash = contentHash(dep.bytes());
            fhash = contentHash(blob);
        }
        // Latest-wins by STORED ts in ONE statement: the conditional upsert replaces the
        // old SELECT-then-INSERT pair (the cold-path gate measured per-deposit cost —
        // statement overhead matters at backfill rates). 0 rows changed = the WHERE
        // rejected an older deposit; compressing the rare loser first is cheaper than a
        // SELECT on every winner.
        PreparedStatement insert = this.insertByDim.get(dep.dim());
        if (insert == null) {
            insert = this.writer.prepareStatement("INSERT INTO lods_" + dimId
                    + " (pos, ts, chash, usize, src_stamp, fhash, wirefmt, blob)"
                    + " VALUES (?,?,?,?,?,?,20,?)"
                    + " ON CONFLICT(pos) DO UPDATE SET ts=excluded.ts,"
                    + " chash=excluded.chash, usize=excluded.usize,"
                    + " src_stamp=excluded.src_stamp, fhash=excluded.fhash,"
                    // wirefmt flips to 20 WITH the row (C4 mega-plan pin §3.1): a
                    // re-deposit over an unmigrated 19-row that left the tag would
                    // carry CRC hashes under an FNV dispatch — purged as corrupt on
                    // its next hit.
                    + " wirefmt=excluded.wirefmt, blob=excluded.blob"
                    + " WHERE excluded.ts >= ts");
            this.insertByDim.put(dep.dim(), insert);
        }
        insert.setLong(1, dep.packed());
        insert.setLong(2, dep.ts());
        insert.setLong(3, chash);
        insert.setInt(4, usize);
        // src_stamp: the deposit-CALL wall second (never the column ts — see deposit()).
        insert.setLong(5, dep.srcStampSeconds());
        insert.setLong(6, fhash);
        insert.setBytes(7, blob);
        if (insert.executeUpdate() > 0) {
            this.diag.recordDeposit();
        } else {
            this.diag.recordDepositSkip(); // lost latest-wins to a newer-stamped row
        }
        bumpTxn(1);
        // A deposit re-opens its region's sweep judgement (review B13): a deposit
        // QUEUED while the sweep judged its region applies after seen_mtime was
        // recorded and would never be re-examined (`==` skip) — clear the record so
        // the next sweep re-judges. Memoized per region until the next sweep pass so
        // steady deposit traffic costs one indexed delete per region, not per column.
        long rpos = regionOf(dep.packed());
        if (this.sweepReopened.computeIfAbsent(dimId, k -> new java.util.HashSet<>())
                .add(rpos)) {
            // Record for rollback pruning BEFORE the DELETE executes — if executeUpdate
            // itself throws, the failure handler must already hold the entry to un-poison
            // the memo (review F5).
            this.txnReopened.add(new long[]{dimId, rpos});
            try (PreparedStatement ps = this.writer.prepareStatement(
                    "DELETE FROM regions WHERE dim=? AND rpos=?")) {
                ps.setInt(1, dimId);
                ps.setLong(2, rpos);
                ps.executeUpdate();
            }
            bumpTxn(1);
        }
        // Tombstone re-check after the write (the memory tier's proven interleaving
        // guard): an invalidate between the first check and the write must win.
        tombs = this.tombstones.get(dep.dim());
        if (tombs != null) {
            Long t = tombs.get(dep.packed());
            if (t != null && t >= dep.enqueuedNanos()) {
                try (PreparedStatement ps = this.writer.prepareStatement(
                        "DELETE FROM lods_" + dimId + " WHERE pos=?")) {
                    ps.setLong(1, dep.packed());
                    ps.executeUpdate();
                }
                // Commit IMMEDIATELY (review B3, the R1-M1 rule's last uncovered
                // path): for tombstones stamped by DropAll/eviction this re-check is
                // the ONLY delete — riding the shared txn lets a later op's rollback
                // resurrect a row the admin explicitly dropped.
                this.txnRows++;
                commitTxn();
            }
        }
    }

    private int dimIdFor(String dimension) throws SQLException {
        Integer id = this.dimIds.get(dimension);
        if (id != null) return id;
        int next = this.dimIds.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        try (PreparedStatement ps = this.writer.prepareStatement(
                "INSERT INTO dims (id, name, mask_fingerprint) VALUES (?,?,?)")) {
            ps.setInt(1, next);
            ps.setString(2, dimension);
            ps.setString(3, currentMaskFingerprint(dimension));
            ps.executeUpdate();
        }
        try (Statement st = this.writer.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS lods_" + next + " ("
                    + "pos INTEGER PRIMARY KEY, ts INTEGER NOT NULL, chash INTEGER NOT NULL,"
                    + " usize INTEGER NOT NULL, src_stamp INTEGER NOT NULL,"
                    // wirefmt (C4, XVER §5.1): the row's body format — every fresh
                    // deposit writes 20; DEFAULT 19 exists only on the lazy-upgrade
                    // ALTER (existing v0.9.x rows ARE native-layout).
                    + " fhash INTEGER NOT NULL, wirefmt INTEGER NOT NULL DEFAULT 20,"
                    + " blob BLOB NOT NULL)");
            // ts index (review A2): eviction's ORDER BY ts and the sweep's index-only
            // key scan both need it — without it each is a full scan of the blob pages.
            st.execute("CREATE INDEX IF NOT EXISTS lods_" + next + "_ts ON lods_"
                    + next + " (ts)");
        }
        this.writer.commit();
        this.dimIds.put(dimension, next);
        this.dimIdsShared.put(dimension, next);
        return next;
    }

    private String currentMaskFingerprint(String dimension) {
        try {
            String fp = this.env.maskFingerprintResolver().apply(dimension);
            return fp == null ? "" : fp;
        } catch (Throwable t) {
            return "";
        }
    }

    private void bumpTxn(int rows) throws SQLException {
        this.txnRows += rows;
        if (this.txnRows >= WRITE_TXN_ROWS) {
            commitTxn();
        }
    }

    private void commitTxn() throws SQLException {
        if (this.txnRows > 0) {
            this.writer.commit();
            this.txnRows = 0;
        }
        // The open txn's memo clears are durable now (a memo DELETE always bumps txnRows,
        // so it cannot be pending when the branch above skipped) — review F5.
        this.txnReopened.clear();
    }

    /**
     * Roll back the open txn and un-poison the B13 memo (2026-08-05 review F5): the
     * rollback undoes any {@code DELETE FROM regions} that rode the txn while its
     * {@code sweepReopened} entry survived — a later same-region deposit would then skip
     * the clear, and the surviving stale seen_mtime row would {@code ==}-skip every future
     * sweep of the region until its next real save (unbounded cross-restart staleness, the
     * exact shape seen_mtime exists to prevent). Pruning this txn's entries lets the next
     * deposit re-clear. Entries whose DELETE an intervening direct commit already flushed
     * are pruned too — the next deposit then re-executes an idempotent DELETE; one extra
     * statement, conservative direction.
     */
    private void rollbackTxn() {
        try { this.writer.rollback(); this.txnRows = 0; } catch (Throwable ignored) { }
        long[] entry;
        while ((entry = this.txnReopened.pollFirst()) != null) {
            var set = this.sweepReopened.get((int) entry[0]);
            if (set != null) set.remove(entry[1]);
        }
    }

    private void sweepTombstones(long nowNanos) {
        if (nowNanos - this.lastTombstoneSweepNanos < TOMBSTONE_TTL_NANOS) return;
        this.lastTombstoneSweepNanos = nowNanos;
        // A tombstone has TWO jobs and expiry must respect both:
        //   1. it gates queued DEPOSITS with enqueuedNanos <= stamp, so it may only
        //      expire once older than the oldest still-queued deposit (the age floor);
        //   2. it suppresses READERS (get/getFrame/hasRow) for a position whose
        //      invalidate/delete the batcher has not applied yet, so it must survive
        //      for as long as its own delete is queued.
        // Only (1) used to be enforced, justified by "control ops never consult
        // tombstones" — true, but it answers the wrong question. With the control queue
        // ignored, a batcher that stalled longer than the TTL inside one op (runSweep
        // on a large store, dropDimensionRows, a WAL TRUNCATE checkpoint, the vacuum
        // drain) resumed to find its first iteration apply delete #1 and then expire
        // the tombstones of deletes #2..#N still queued behind it — so get() served the
        // PRE-EDIT row for each until its delete drained. Silent, and it does not
        // self-heal: the client ingests it, the position leaves the want-set, and the
        // stale LOD survives until another edit there or a rejoin. (v0.9.0 review.)
        //
        // (2) is an IDENTITY question, not an age one: a tombstone is stamped just
        // BEFORE its delete is enqueued, so any age-based floor derived from the
        // control queue still expires the very tombstone it was meant to protect.
        // Hence the explicit position check below. The control queue is drained one op
        // per loop iteration and is normally empty; this runs at most once per TTL, on
        // the batcher thread — the only thread that removes from it, so the weakly
        // consistent iteration can only miss a just-added delete, whose tombstone is
        // far too young to be expiring anyway.
        Op head = this.queue.peek();
        final long ageFloor = head instanceof Op.Deposit dep ? dep.enqueuedNanos() : nowNanos;
        for (var dimEntry : this.tombstones.entrySet()) {
            Set<Long> undeleted = queuedDeletePositions(dimEntry.getKey());
            dimEntry.getValue().entrySet().removeIf(e ->
                    nowNanos - e.getValue() > TOMBSTONE_TTL_NANOS
                            && e.getValue() - ageFloor < 0
                            && !undeleted.contains(e.getKey()));
        }
    }

    /** Publishes accumulated sweep drops once they reach a batch, returning the list to
     *  keep accumulating into (the same one when it is still small, a fresh one after a
     *  flush). Keeps the sweep's transient heap bounded regardless of how many rows one
     *  dimension drops. */
    private List<Long> flushSweepDrops(String dimension, List<Long> accumulated) {
        if (accumulated.size() < DROP_BATCH_ROWS) return accumulated;
        notifySweepDrops(dimension, accumulated);
        return new ArrayList<>();
    }

    /** Positions in {@code dimension} whose DeleteRows is still queued, and whose
     *  tombstones must therefore not expire however old they are. */
    private Set<Long> queuedDeletePositions(String dimension) {
        Set<Long> pending = null;
        for (Op op : this.controlQueue) {
            if (op instanceof Op.DeleteRows del && del.dim().equals(dimension)) {
                if (pending == null) pending = new HashSet<>();
                for (long p : del.positions()) pending.add(p);
            }
        }
        return pending == null ? Set.of() : pending;
    }

    private void maybeRefreshGauges(long nowNanos) {
        if (nowNanos - this.lastGaugeRefreshNanos < GAUGE_REFRESH_NANOS) return;
        this.lastGaugeRefreshNanos = nowNanos;
        try {
            this.diag.setDbBytes(Files.exists(this.dbPath) ? Files.size(this.dbPath) : 0);
            Path wal = this.dbPath.resolveSibling(DB_FILE + "-wal");
            long walBytes = Files.exists(wal) ? Files.size(wal) : 0;
            this.diag.setWalBytes(walBytes);
            if (walBytes > WAL_CHECKPOINT_BYTES) {
                commitTxn();
                checkpointTruncate();
                walBytes = Files.exists(wal) ? Files.size(wal) : 0;
                this.diag.setWalBytes(walBytes);
            }
            // Phase 5 size cap: above it, evict the oldest-ts rows in batches and
            // return the pages (auto_vacuum=INCREMENTAL, armed at DB creation).
            // Evicted columns re-warm on their next serve.
            //
            // The cap is measured against the LOGICAL size — (page_count -
            // freelist_count) * page_size — never Files.size(). Two defects lived in
            // the file-size version and they compounded (v0.9.0 review, both measured
            // against the real engine):
            //   * WAL mode does not touch the main DB file until a checkpoint, so
            //     Files.size() is frozen while deposits accumulate. The old code
            //     compensated with a bounded WAL term, but that term only ever GREW
            //     inside a tick (eviction writes its own deletes into the WAL), so the
            //     re-measurement at the bottom of the loop could never fall and all 8
            //     passes always ran once entered — up to 4096 rows/dim. An 8 MB cap
            //     25% over was observed evicting its ENTIRE contents. That term also
            //     contradicted both checkpointTruncate's own comment below and the R1
            //     disposition in lod-store-progress.md, which record the cap as
            //     db-only. page_count is read through the writer's snapshot, so it
            //     already accounts for committed WAL frames and needs no WAL term.
            //   * Deleted rows return pages to the freelist, but the file shrinks only
            //     when the vacuum reclaims them — which it did not, one page per tick
            //     (see drainIncrementalVacuum). File size therefore never fell back
            //     under the cap and the store treadmilled permanently: measured 448
            //     rows / ~9 MB alive against a 64 MB cap (14% utilisation), evicting
            //     every deposit within 5 s, forever; one run ended at 0 rows with the
            //     file stuck 34 MB above its cap for the life of the DB.
            // Logical size falls the instant rows are deleted, so the loop converges
            // whether or not the vacuum keeps up; the vacuum is now purely about
            // returning space to the filesystem.
            long liveBytes = logicalDbBytes();
            if (liveBytes > this.env.maxDbBytes()) {
                commitTxn();
                // Firm cap (review B12): ONE 512-row/dim batch per 5 s tick
                // (~780 KB/s) is out-runnable by deposits — the backfill default alone
                // approaches it — so the cap silently did not bind. Loop batches
                // within this tick until under cap; the pass bound is a runaway stop,
                // and with the ts index each pass is an index walk, not a table scan.
                // Evict to a little UNDER the cap: landing exactly on it puts the
                // store back into this branch on the very next gauge tick.
                long target = (long) (this.env.maxDbBytes() * EVICTION_TARGET_FRACTION);
                for (int pass = 0; pass < MAX_EVICTION_PASSES_PER_TICK
                        && liveBytes > this.env.maxDbBytes(); pass++) {
                    int evicted = evictOldestBatch(liveBytes - target);
                    if (evicted == 0) break;
                    // Count + (once) log BEFORE the vacuum: evictOldestBatch has
                    // already committed its deletions, and a vacuum/commit throw is
                    // swallowed by this method's containment — the counter and the one
                    // cap line must not vanish with it (review: a store that evicts
                    // fine but cannot vacuum would treadmill with evicted=0 and no
                    // line, the exact observability hole §2 closes).
                    this.diag.recordSqlEvictions(evicted);
                    // Exactly ONE cap line per server session (store-cap-behavior-plan
                    // §2, user decision): once a store is at its cap, eviction is a
                    // steady-state fact, not news — the ~5 s gauge cadence turned this
                    // line into permanent spam on a treadmilling store. The single
                    // emission carries the durable context + where the ongoing state
                    // stays observable.
                    if (this.capLogLatch.compareAndSet(false, true)) {
                        this.capLogEmissions++;
                        LSSLogger.info("LOD store size cap: evicted " + evicted
                                + " oldest rows (live " + (liveBytes >> 20) + " MB > cap "
                                + (this.env.maxDbBytes() >> 20) + " MB) — the store is at "
                                + "its size cap and will keep evicting silently; running "
                                + "totals in '/" + Brand.serverCommand() + " store status' (evicted=), raise or "
                                + "zero lodStoreMaxMB (0 = uncapped) for full retention");
                    }
                    liveBytes = logicalDbBytes();
                }
                // Return the freed pages to the filesystem ONCE per tick, after the
                // eviction passes rather than inside each one: the cap has already
                // converged on logical bytes above, so this is disk hygiene and its
                // budget bounds the batcher's time here.
                drainIncrementalVacuum(MAX_VACUUM_PAGES_PER_TICK);
                this.writer.commit();
                this.diag.setDbBytes(Files.exists(this.dbPath) ? Files.size(this.dbPath) : 0);
            }
        } catch (Throwable ignored) {
            // gauge refresh is best-effort; a failed checkpoint retries next interval
        }
    }

    /** The directory holding the store, for the backfill's free-space floor. */
    Path storeDir() {
        return this.dbPath.getParent();
    }

    /** The active size cap in bytes (Long.MAX_VALUE = uncapped); the backfill's
     *  cap-stop gate reads these two rather than re-deriving gauge accounting. */
    long sizeCapBytes() {
        return this.env.maxDbBytes();
    }

    /** DropAll fence for the backfill's done-marks (review B9). */
    long dropGeneration() {
        return this.dropGeneration;
    }

    /** Closes and unregisters the CURRENT thread's reader connection — the backfill
     *  worker's exit hook (review B6: each start() thread otherwise leaks its
     *  ThreadLocal connection until store shutdown). */
    void closeReaderConnForCurrentThread() {
        Connection c = this.readerConn.get();
        if (c == null) return;
        this.readerConn.remove();
        // Keep the statement cache's "dies with its connection" invariant STRUCTURAL
        // (three-lens review): the cache keys on dimId, not the connection, so dropping
        // the connection without the cache would leave closed-statement handles that only
        // self-heal through the invalidate-on-throw path.
        this.readerStatements.remove();
        synchronized (this.allReaderConns) {
            this.allReaderConns.remove(c);
        }
        try { c.close(); } catch (Exception ignored) { }
    }

    /** One-word health state for status surfaces (review B1): a latched store must
     *  LOOK dead in the triage tool, not render a healthy token with frozen counters. */
    @Override
    public String stateToken() {
        if (this.latchedOff) return "latched";
        if (!this.serving) return "sweeping";
        return "ok";
    }

    /** Reads a single-value PRAGMA off the writer connection. Batcher-thread only. */
    private long pragmaLong(String pragma) throws SQLException {
        try (Statement st = this.writer.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA " + pragma)) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    /** Live data size: {@code (page_count - freelist_count) * page_size}. This — not
     *  {@link Files#size} — is what the size cap compares against; see the rationale
     *  at the cap check in {@link #maybeRefreshGauges}. Read through the writer's
     *  snapshot, so committed WAL frames are already counted and no WAL term is
     *  needed. Batcher-thread only. On any pragma failure it falls back to the file
     *  size, which over-reports (freed pages included) and therefore errs toward
     *  evicting rather than toward an unbounded store. */
    private long logicalDbBytes() {
        try {
            if (this.pageSizeBytes <= 0) this.pageSizeBytes = pragmaLong("page_size");
            long pageSize = this.pageSizeBytes > 0 ? this.pageSizeBytes : PAGE_SIZE;
            long pages = pragmaLong("page_count");
            long free = pragmaLong("freelist_count");
            if (pages <= 0 || free < 0 || free > pages) return fileSizeOrZero();
            return (pages - free) * pageSize;
        } catch (Exception e) {
            return fileSizeOrZero();
        }
    }

    private long fileSizeOrZero() {
        try {
            return Files.exists(this.dbPath) ? Files.size(this.dbPath) : 0;
        } catch (IOException e) {
            return this.diag.getDbBytes();
        }
    }

    /** Returns freed pages to the filesystem, bounded at {@code maxPages}.
     *
     *  <p>{@code PRAGMA incremental_vacuum} moves ONE page per {@code sqlite3_step},
     *  and a JDBC {@code execute()} steps the statement exactly once before
     *  finalizing it — so the single call this used to make reclaimed one page
     *  (16 KB) per 5 s tick no matter how much had just been evicted. Measured on the
     *  real engine during the v0.9.0 review: {@code freelist_count} 1874 → 1873 →
     *  1872 across three production-shaped calls, while {@code page_count} fell by ~1
     *  per tick under a sustained deposit stream. The file consequently never shrank.
     *  Loop instead, stopping when the freelist empties, the budget is spent, or a
     *  pass makes no progress (never spin on a vacuum that cannot advance — e.g. an
     *  open read snapshot pinning the pages). */
    private void drainIncrementalVacuum(int maxPages) throws SQLException {
        int budget = maxPages;
        while (budget > 0) {
            long before = pragmaLong("freelist_count");
            if (before <= 0) return;
            int batch = (int) Math.min(budget, before);
            try (Statement st = this.writer.createStatement()) {
                for (int i = 0; i < batch; i++) st.execute("PRAGMA incremental_vacuum");
            }
            budget -= batch;
            if (pragmaLong("freelist_count") >= before) return;
        }
    }

    /** Cap-accounted current size for the BACKFILL's stop gate. Deliberately the file
     *  size (plus a bounded WAL term), not {@link #logicalDbBytes}: this is called
     *  from the backfill worker thread, and the writer connection the pragmas need is
     *  batcher-confined. The two therefore differ by the freelist, and only in the
     *  safe direction — the walk stops slightly EARLIER than the evictor starts,
     *  which is the conservative error for a gate whose job is "do not fill the
     *  disk". With the vacuum now actually draining, the gap stays small.
     *  Statted LIVE (once per backfill region — negligible) rather than read from the
     *  ~5 s gauge: a fast walk deposits up to ~40 MB per gauge interval at max
     *  pace, which ate the stop gate's 5% margin at small caps (review MINOR).
     *  Falls back to the gauges if the stat fails. */
    long approxSizeBytes() {
        try {
            long db = Files.exists(this.dbPath) ? Files.size(this.dbPath) : 0;
            Path wal = this.dbPath.resolveSibling(DB_FILE + "-wal");
            long walBytes = Files.exists(wal) ? Files.size(wal) : 0;
            return db + Math.min(walBytes, WAL_CHECKPOINT_BYTES);
        } catch (Exception e) {
            return this.diag.getDbBytes()
                    + Math.min(this.diag.getWalBytes(), WAL_CHECKPOINT_BYTES);
        }
    }

    /** Pauses the batcher at the top of its loop and returns the permit source that
     *  releases it — one permit, one op. Test-only: tombstone expiry is only
     *  observable in the window between applying one delete and the next, and real
     *  stalls (large sweeps, whole-dimension drops, WAL checkpoints) neither happen on
     *  demand nor hold still long enough to sample. */
    java.util.concurrent.Semaphore pauseBatcherForTest() {
        var permits = new java.util.concurrent.Semaphore(0);
        this.batcherStepsForTest = permits;
        return permits;
    }

    /** Package-visible for the tombstone-floor pin: how many positions are currently
     *  suppressed for readers in {@code dimension}. */
    int tombstoneCountForTest(String dimension) {
        var tombs = this.tombstones.get(dimension);
        return tombs == null ? 0 : tombs.size();
    }

    /** Cap-log one-shot emissions (package-visible: the §2 latch pin counts CALLS —
     *  two eviction batches must produce exactly one log line). */
    int capLogEmissionCount() {
        return this.capLogEmissions;
    }

    /** Oldest-ts eviction ACROSS all dims (a real cross-dimension merge since the
     *  2026-08-05 review's F4 — see below), sized to the DEFICIT rather than to a fixed
     *  batch, and bounded at {@link #EVICTION_MAX_ROWS_PER_DIM} candidate rows/dim per
     *  pass so one pass cannot monopolise the batcher.
     *
     *  <p>This used to delete a flat 512 rows/dim however far over the cap the store
     *  actually was, and {@link #MAX_EVICTION_PASSES_PER_TICK} compounded that to 4096
     *  rows/dim per tick — so a store a few MB over its cap was emptied in a single
     *  gauge tick (measured: 87% of the store gone in one tick, and a small cap taken
     *  to zero rows). Correct accounting alone does not fix that: the loop re-checks
     *  between passes, but the FIRST pass already overshoots. Reading each candidate's
     *  {@code length(blob)} lets the pass stop the moment enough bytes are covered.
     *
     *  <p><b>Cross-dim fairness (review F4):</b> the pre-merge shape iterated dims in
     *  HashMap order against one shared budget, so the first dimension absorbed the
     *  entire deficit per pass and across passes — under sustained cap pressure one
     *  dim's genuinely-warm rows were hollowed out while the others kept everything.
     *  Candidates (≤ {@link #EVICTION_MAX_ROWS_PER_DIM} per dim, today's worst-case pass
     *  work) are now merged by ascending ts — ties broken by dimId then pos for
     *  determinism — and consumed in GLOBAL age order until the deficit is covered
     *  ({@code remaining > 0} checked before each victim, so the last one may overshoot,
     *  exactly like the per-dim loop did). Single-dim stores behave bit-identically
     *  modulo ts TIES, which the sort now breaks deterministically by pos where the old
     *  path took SQLite's scan order. Per-dim candidate collection stops early once a
     *  dim's own bytes cover the whole deficit (no later row of it can be a victim), so
     *  the typical steady-state pass steps a handful of rows, not 512×dims.
     *
     *  <p>Freed pages are ≥ freed blob bytes (page granularity), so covering the
     *  deficit in blob bytes always clears it in page bytes; the caller re-measures
     *  and runs another pass if not. Evicted positions go through the tombstone
     *  protocol like any delete so readers cannot race a half-removed row. */
    private int evictOldestBatch(long bytesToFree) throws SQLException {
        record Candidate(String dim, int dimId, long pos, long len, long ts) { }
        var candidates = new ArrayList<Candidate>();
        for (var entry : List.copyOf(this.dimIds.entrySet())) {
            long dimBytes = 0;
            try (Statement st = this.writer.createStatement();
                 ResultSet rs = st.executeQuery("SELECT pos, length(\"blob\"), ts FROM lods_"
                         + entry.getValue() + " ORDER BY ts ASC LIMIT "
                         + EVICTION_MAX_ROWS_PER_DIM)) {
                while (rs.next()) {
                    long len = Math.max(1L, rs.getLong(2));
                    candidates.add(new Candidate(entry.getKey(), entry.getValue(),
                            rs.getLong(1), len, rs.getLong(3)));
                    // Per-dim early stop (three-lens review): the victim set is the
                    // globally-oldest prefix covering the deficit, so no single dim can
                    // ever contribute more than the rows covering the WHOLE deficit —
                    // once this dim's own cumulative bytes reach it, later (younger) rows
                    // of this dim cannot be victims. Restores the pre-merge shape's
                    // typical-case cost (a handful of row-steps, not 512×dims per pass).
                    dimBytes += len;
                    if (dimBytes >= bytesToFree) break;
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingLong(Candidate::ts)
                .thenComparingInt(Candidate::dimId).thenComparingLong(Candidate::pos));

        // Victims in global age order, grouped per dim for the delete ladder below.
        var victimsByDim = new java.util.LinkedHashMap<String, List<Long>>();
        var dimIdByKey = new HashMap<String, Integer>();
        long remaining = bytesToFree;
        for (var cand : candidates) {
            if (remaining <= 0) break;
            victimsByDim.computeIfAbsent(cand.dim(), k -> new ArrayList<>()).add(cand.pos());
            dimIdByKey.put(cand.dim(), cand.dimId());
            remaining -= cand.len(); // already floored at 1 during collection
        }

        int evicted = 0;
        for (var entry : victimsByDim.entrySet()) {
            List<Long> oldest = entry.getValue();
            var tombs = this.tombstones.computeIfAbsent(entry.getKey(),
                    k -> new ConcurrentHashMap<>());
            long now = System.nanoTime();
            for (long pos : oldest) tombs.put(pos, now);
            // Un-mark the affected backfill regions BEFORE deleting their rows
            // (R3-M1's eviction half + review B4 crash ordering): a done-marked
            // region is never re-walked, so a crash between row-deletes and a LATER
            // un-mark left permanent warm-holes; un-mark-first makes the crash window
            // harmless — an unmarked region with surviving rows re-walks cheaply
            // (hasRow skips).
            var regions = new java.util.HashSet<Long>();
            for (long pos : oldest) regions.add(regionOf(pos));
            try (PreparedStatement ps = this.writer.prepareStatement(
                    "DELETE FROM backfill WHERE dim=? AND rx=? AND rz=?")) {
                for (long rpos : regions) {
                    ps.setString(1, entry.getKey());
                    ps.setInt(2, (int) (rpos >> 32));
                    ps.setInt(3, (int) rpos);
                    ps.executeUpdate();
                }
            }
            this.writer.commit();
            evicted += deleteRows(dimIdByKey.get(entry.getKey()), oldest);
        }
        return evicted;
    }

    private void checkpointTruncate() throws SQLException {
        long t0 = System.nanoTime();
        boolean busy = false;
        // Read the (busy, log, checkpointed) result row: a busy checkpoint is a silent
        // no-op, and ignoring it recorded a fast checkpoint_ms while the WAL kept
        // growing (R1 review). Best-effort here — the next interval retries. (This used
        // to add that the size cap "deliberately excludes WAL bytes"; it did not — it
        // added a bounded WAL term. The cap now measures logical bytes, for which the
        // question does not arise: page_count already accounts for committed WAL
        // frames. See maybeRefreshGauges.)
        try (Statement st = this.writer.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            busy = rs.next() && rs.getInt(1) != 0;
        }
        this.writer.commit();
        this.diag.recordCheckpointMs((System.nanoTime() - t0) / 1_000_000);
        if (busy) {
            LSSLogger.debug("LOD store: WAL checkpoint busy (readers active) — retrying"
                    + " next interval");
        }
    }

    // ---- freshness sweep ----

    private void startupSweep() throws Exception {
        runSweep(true);
        this.serving = true;
    }

    /**
     * The mtime/header freshness pass (startup, and Paper's periodic re-sweep). For each
     * known dimension: resolve the region dir (unresolvable → drop the whole dimension's
     * rows, fail-safe); stat each region file and compare {@code !=} against
     * {@code regions.seen_mtime}; a changed region gets one header read and per-column
     * {@code src_stamp} comparison (header stamp ≥ src_stamp → row deleted); a region
     * with rows but NO file on disk drops all its rows.
     */
    private void runSweep(boolean startup) throws Exception {
        // Iterate the DIMS TABLE, not a caller-provided list: any dimension the store
        // has rows for MUST get a freshness pass — a dim deposited at runtime (created
        // world) but absent from the resolver map resolves null below and fail-safe
        // drops, never "served but unswept" (review finding: a knownDimensions-frozen
        // loop silently exempted such dims from every freshness rule).
        for (var dimEntry : List.copyOf(this.dimIds.entrySet())) {
            if (this.shutdown.get()) {
                // Shutdown-abort: leave the sweep INCOMPLETE rather than let the
                // interrupt turn header reads into ClosedByInterruptException nulls
                // (which read as unreadable regions and silently drop warm rows).
                throw new InterruptedException("shutdown during sweep");
            }
            String dimension = dimEntry.getKey();
            int dimId = dimEntry.getValue();
            Path regionDir;
            try {
                regionDir = this.env.regionDirResolver().apply(dimension);
            } catch (Throwable t) {
                regionDir = null;
            }
            if (regionDir == null || !Files.isDirectory(regionDir)) {
                int dropped = dropDimensionRows(dimension, dimId);
                if (dropped > 0) {
                    LSSLogger.warn("LOD store: region directory for " + dimension
                            + " is unresolvable — dropped its " + dropped
                            + " stored rows (fail-safe)");
                }
                continue;
            }
            // Mask fingerprint (per-dimension, §1): drift → drop the dimension's rows.
            String fp = currentMaskFingerprint(dimension);
            String storedFp = storedMaskFingerprint(dimId);
            if (!fp.equals(storedFp)) {
                int dropped = dropDimensionRows(dimension, dimId);
                try (PreparedStatement ps = this.writer.prepareStatement(
                        "UPDATE dims SET mask_fingerprint=? WHERE id=?")) {
                    ps.setString(1, fp);
                    ps.setInt(2, dimId);
                    ps.executeUpdate();
                }
                this.writer.commit();
                if (dropped > 0) {
                    LSSLogger.info("LOD store: x-ray mask changed for " + dimension
                            + " — dropped " + dropped + " rows (rebuilds from serves)");
                }
                continue; // fresh slate; mtimes recorded below next pass
            }
            sweepDimension(dimension, dimId, regionDir);
        }
        // Reset the B13 memo: seen_mtime records written by THIS pass are current, so
        // the next deposit per region must clear them again.
        this.sweepReopened.clear();
        this.writer.commit();
    }

    private String storedMaskFingerprint(int dimId) throws SQLException {
        try (PreparedStatement ps = this.writer.prepareStatement(
                "SELECT mask_fingerprint FROM dims WHERE id=?")) {
            ps.setInt(1, dimId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getString(1) != null ? rs.getString(1) : "";
            }
        }
    }

    private void sweepDimension(String dimension, int dimId, Path regionDir) throws Exception {
        // Stat-driven (review A1): candidate regions come from an index-only key scan
        // + the filesystem — NEVER a full row scan. The old shape opened with
        // `SELECT pos, src_stamp FROM lods_<dim>` which, with ~5 KB blobs inline at
        // 16 KiB pages, read every page of the DB on every boot (gating serving) and
        // every Paper resweep, on the batcher with no IO restraint. Now an UNCHANGED
        // region costs one stat; only changed regions read their own rows back via
        // indexed pos-range seeks (regionRows).
        Map<Long, Long> seenMtimes = new HashMap<>();
        try (PreparedStatement ps = this.writer.prepareStatement(
                "SELECT rpos, seen_mtime FROM regions WHERE dim=?")) {
            ps.setInt(1, dimId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) seenMtimes.put(rs.getLong(1), rs.getLong(2));
            }
        }
        // Regions that HOLD rows: scan only the ts-index pages (~9 B/row, not the
        // blob pages — INDEXED BY pins the covering index; a bare pos scan would walk
        // the table b-tree, whose leaves ARE the blobs). Region keys are few, so the
        // transient boxing is irrelevant; the old per-region ArrayList<Long> of every
        // position is gone.
        java.util.HashSet<Long> rowRegions = new java.util.HashSet<>();
        try (Statement st = this.writer.createStatement();
             ResultSet rs = st.executeQuery("SELECT pos FROM lods_" + dimId
                     + " INDEXED BY lods_" + dimId + "_ts")) {
            while (rs.next()) rowRegions.add(regionOf(rs.getLong(1)));
        }
        // Published to the sweep-drop listener in bounded batches rather than accumulated
        // whole. Same shape the v0.9.0 review fixed for dropDimensionRows, through a door
        // it left open: when the region DIRECTORY survives but its .mca files do not — a
        // world-trim or regen tool run against a kept lss-lod/ — every region takes the
        // vanished branch below and this list would otherwise hold the dimension's entire
        // row set (boxed Longs, plus the long[] copy notifySweepDrops builds) on the
        // batcher, during the STARTUP sweep that gates serving.
        List<Long> droppedPositions = new ArrayList<>();
        int droppedVanished = 0, droppedStale = 0, checkedRegions = 0;
        for (long rpos : rowRegions) {
            if (this.shutdown.get()) throw new InterruptedException("shutdown during sweep");
            int rx = (int) (rpos >> 32);
            int rz = (int) rpos;
            Path mca = regionDir.resolve("r." + rx + "." + rz + ".mca");
            if (!Files.exists(mca)) {
                List<Long> rows = new ArrayList<>(regionRows(dimId, rpos).keySet());
                droppedVanished += deleteRows(dimId, rows);
                droppedPositions.addAll(rows);
                droppedPositions = flushSweepDrops(dimension, droppedPositions);
                continue;
            }
            // Capture the mtime BEFORE the header read, and record THAT value as
            // seen_mtime only after this region's rows were actually judged against
            // its header. Recording a fresh stat (the old shutdown-time bulk
            // recordRegionMtimes) marked regions "seen" whose headers were NEVER
            // examined — a save landing after the last examination was then skipped
            // by the != compare forever, turning Paper's "save + one sweep" staleness
            // bound into unbounded cross-restart staleness (review MAJOR).
            long mtime = Files.getLastModifiedTime(mca).toMillis();
            Long seen = seenMtimes.get(rpos);
            if (seen != null && seen == mtime) continue; // unchanged: zero row IO
            checkedRegions++;
            int[] headerStamps = readHeaderTimestamps(mca);
            Map<Long, Long> rows = regionRows(dimId, rpos);
            if (headerStamps == null) {
                if (this.shutdown.get()) throw new InterruptedException("shutdown during sweep");
                // Unreadable header: fail-safe, drop the region's rows.
                List<Long> all = new ArrayList<>(rows.keySet());
                droppedVanished += deleteRows(dimId, all);
                droppedPositions.addAll(all);
                droppedPositions = flushSweepDrops(dimension, droppedPositions);
                continue;
            }
            List<Long> stale = new ArrayList<>();
            for (var row : rows.entrySet()) {
                long pos = row.getKey();
                int cx = PositionUtil.unpackX(pos);
                int cz = PositionUtil.unpackZ(pos);
                int idx = (cx & 31) + ((cz & 31) << 5);
                // >= : a save in the SAME second as the deposit may postdate it
                // (1 s granularity) — conservative drop, never a stale serve.
                if (headerStamps[idx] >= row.getValue()) {
                    stale.add(pos);
                }
            }
            droppedStale += deleteRows(dimId, stale);
            droppedPositions.addAll(stale);
            droppedPositions = flushSweepDrops(dimension, droppedPositions);
            // This region's rows are now judged against the header we read — record
            // the PRE-read mtime, and ONLY when provably not raced: the stamp must be
            // unchanged by a post-judgement re-stat AND strictly older than the current
            // filesystem second. On a 1 s-granularity filesystem (exFAT, some NFS) a
            // save landing in the same second as the stat produces an EQUAL mtime the
            // != compare would then skip forever (R1 review); withholding the record
            // just re-examines the region next sweep — the conservative direction.
            long afterMtime = Files.getLastModifiedTime(mca).toMillis();
            if (afterMtime == mtime
                    && mtime / 1000L < System.currentTimeMillis() / 1000L) {
                try (PreparedStatement ps = this.writer.prepareStatement(
                        "INSERT OR REPLACE INTO regions (dim, rpos, seen_mtime) VALUES (?,?,?)")) {
                    ps.setInt(1, dimId);
                    ps.setLong(2, rpos);
                    ps.setLong(3, mtime);
                    ps.executeUpdate();
                }
            }
        }
        notifySweepDrops(dimension, droppedPositions);
        if (droppedVanished + droppedStale > 0 || checkedRegions > 0) {
            LSSLogger.info("LOD store sweep [" + dimension + "]: " + checkedRegions
                    + " changed region(s), dropped " + droppedStale + " stale + "
                    + droppedVanished + " vanished-region row(s)");
        }
        this.writer.commit();
    }

    private int[] readHeaderTimestamps(Path mca) {
        try (var ch = java.nio.channels.FileChannel.open(mca)) {
            var buf = java.nio.ByteBuffer.allocate(8192);
            int read = 0;
            while (read < 8192) {
                int n = ch.read(buf, read);
                if (n < 0) return null;
                read += n;
            }
            buf.flip();
            int[] stamps = new int[1024];
            for (int i = 0; i < 1024; i++) {
                int loc = buf.getInt(i * 4);
                // loc == 0: the chunk is ABSENT from a still-present region file — it was
                // deleted (region tools) or never saved. Fail-safe like the vanished-region
                // rule, at chunk granularity: MAX_VALUE makes the >= compare drop the row,
                // so the store never intercepts the miss that regenerates a deleted chunk.
                stamps[i] = loc == 0 ? Integer.MAX_VALUE : buf.getInt(4096 + i * 4);
            }
            return stamps;
        } catch (Exception e) {
            return null;
        }
    }

    private int deleteRows(int dimId, List<Long> positions) throws SQLException {
        if (positions.isEmpty()) return 0;
        try (PreparedStatement ps = this.writer.prepareStatement(
                "DELETE FROM lods_" + dimId + " WHERE pos=?")) {
            for (long pos : positions) {
                ps.setLong(1, pos);
                ps.executeUpdate();
            }
        }
        this.writer.commit();
        return positions.size();
    }

    /** Drops every row of a dimension, returning the dropped positions (the sweep-drop
     *  fan-out needs them — the memory tier must evict its copies too). */
    /** Drops every row of one dimension in bounded batches, publishing each batch to
     *  the sweep-drop listener. Returns the number of rows removed.
     *
     *  <p>This used to SELECT every position into one unbounded {@code ArrayList}, and
     *  the DropAll caller stamped a tombstone per position on top of that — roughly
     *  240 MB of list plus 800 MB of concurrent-map nodes on a 50 GB store, all live
     *  at once on the batcher thread, with {@code lodStoreMaxMB} defaulting to
     *  uncapped so nothing bounded the row count. Nor is it admin-only: the sweep
     *  drops a whole dimension for an unresolvable region directory and for
     *  mask-fingerprint drift, and Fabric's {@code transient:} nonce makes that drift
     *  routine on some AntiXray boots. (v0.9.0 review.)
     *
     *  <p>The per-position tombstones are replaced by two O(1) guards: readers are
     *  suppressed for the whole dimension while the drop runs (a half-dropped
     *  dimension must not serve its survivors), and the drop barrier refuses deposits
     *  that were enqueued before it — which is exactly what those tombstones did. */
    private int dropDimensionRows(String dimension, int dimId) throws SQLException {
        this.dropBarrierNanos.put(dimension, System.nanoTime());
        this.droppingDims.add(dimension);
        int total = 0;
        try {
            while (true) {
                // Observe shutdown between batches, as runSweep does per-region. Batching
                // this drop (v0.9.0, to bound its heap) turned it from one DELETE FROM into
                // tens of seconds of work, which is longer than shutdown()'s join: the
                // daemon batcher then outlived the store with its writer connection open,
                // so a same-JVM restart (Fabric singleplayer re-entry, Paper /reload) could
                // open a SECOND writer against it — breaking the single-writer discipline,
                // and on Windows leaving held handles that fail a later drop-and-rebuild.
                // (Round-3 review.)
                if (this.shutdown.get()) break;
                List<Long> batch = new ArrayList<>();
                try (Statement st = this.writer.createStatement();
                     ResultSet rs = st.executeQuery("SELECT pos FROM lods_" + dimId
                             + " LIMIT " + DROP_BATCH_ROWS)) {
                    while (rs.next()) batch.add(rs.getLong(1));
                }
                if (batch.isEmpty()) break;
                total += deleteRows(dimId, batch); // commits immediately, per the delete rule
                notifySweepDrops(dimension, batch);
            }
        } finally {
            this.droppingDims.remove(dimension);
        }
        return total;
    }

    /** One region's rows via 32 indexed range seeks on the pos PRIMARY KEY (review A1):
     *  each cx column of the region is one contiguous packed interval — cz within a
     *  region never crosses the sign flip, so the low word is contiguous and the
     *  signed-long BETWEEN is exact. Returns pos -> src_stamp. */
    private Map<Long, Long> regionRows(int dimId, long rpos) throws SQLException {
        int rx = (int) (rpos >> 32);
        int rz = (int) rpos;
        Map<Long, Long> rows = new HashMap<>();
        try (PreparedStatement ps = this.writer.prepareStatement(
                "SELECT pos, src_stamp FROM lods_" + dimId + " WHERE pos BETWEEN ? AND ?")) {
            for (int cx = rx << 5; cx <= (rx << 5) + 31; cx++) {
                ps.setLong(1, PositionUtil.packPosition(cx, rz << 5));
                ps.setLong(2, PositionUtil.packPosition(cx, (rz << 5) + 31));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.put(rs.getLong(1), rs.getLong(2));
                }
            }
        }
        return rows;
    }

    private static long regionOf(long packedPos) {
        int cx = PositionUtil.unpackX(packedPos);
        int cz = PositionUtil.unpackZ(packedPos);
        return ((long) (cx >> 5) << 32) | ((cz >> 5) & 0xFFFFFFFFL);
    }

    /** Delegates to the ONE canonical hash (LodStoreService.contentHash) — the
     *  frame-reuse deposit computes chash/fhash on the processing thread against it. */
    private static long contentHash(byte[] data) {
        return LodStoreService.contentHash(data);
    }
}
