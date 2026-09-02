package dev.vox.lss.common.config;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.XrayMaskPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server config shared verbatim by Fabric and Paper: same fields, same defaults,
 * same clamps, same file name. Platform subclasses add only platform-specific options
 * (Paper's updateEvents) and the config-directory resolution.
 */
public abstract class ServerConfigBase extends JsonConfig {
    protected static final String FILE_NAME = "lss-server-config.json";

    /** Brand-preferred server-config filenames ({@code vss-server-config.json} first on a
     *  VSS jar) with the other brand's file as the adoption fallback — the migration story
     *  the old brand-INVARIANT rule demanded: {@code JsonConfig.load} adopts whichever file
     *  EXISTS as the save target, so an LSS<->VSS jar swap keeps its config, and only a
     *  genuinely fresh install creates the brand's own file. A METHOD, not a static field:
     *  the candidate order must read the brand AFTER the platform's Brand.load, and a
     *  class-init-time field would freeze whatever the classloading order happened to see.
     *  (Folded from XANTHA's v0.10.0 VSS release patch, 2026-08-13.) */
    protected static String[] serverConfigCandidates() {
        return brandedConfigCandidates("server");
    }

    public boolean enabled = true;
    /**
     * Per-player service gate (service-permission-gate-plan.md; evolved from PR #244):
     * when true, a handshaking player who does not hold BOTH {@code lss.use} and
     * {@code vss.use} is told LOD is unavailable — the reply carries
     * {@code enabled=false} in the client's own dialect and no session is registered —
     * instead of being served, and registered CURRENT-dialect sessions are re-checked
     * live (~10 s cadence; denied players are re-offered when granted or when the gate
     * is disarmed).
     *
     * <p><b>The nodes default to TRUE everywhere</b> (user decision 2026-08-25 on the
     * PR), so turning this key on denies NOBODY by itself — denial is always an
     * explicit negative grant in the permission backend (LuckPerms:
     * {@code group default permission set lss.use false}), and one negative grant on
     * EITHER spelling denies (enforcement is the AND of both — the deny model's
     * De Morgan mirror of the far-player privacy nodes).
     *
     * <p><b>Default false, and that default is load-bearing:</b> off, the handshake
     * path is byte-for-byte what it was before the gate existed (no permission
     * backend is even consulted). Per-platform enforcement: Paper/Folia = Bukkit
     * permissions; Fabric = the fabric-permissions-api bridge (absent = everyone
     * served, with a once-warn within one recheck interval of arming); NeoForge = the native PermissionAPI
     * nodes. This is a fail-open ROLLOUT lever, not a security boundary — a dead
     * permission backend serves everyone.
     */
    public boolean requireServicePermission = false;
    /** LOD radius in chunks. Back to 512 by user decision 2026-08-13 (reverting the
     *  2026-08-12 stage-A cut to 300 — history: 256 → 512 (2026-08-08 rework) → 300 →
     *  512). Note what scales with it — the timestamp cache (see
     *  effectiveTimestampCacheMB, AUTO follows this automatically) and, with the
     *  store on (a fresh install's default), the warmed disk footprint. */
    public int lodDistanceChunks = 512;
    /**
     * Per-world LOD-distance overrides. {@link #lodDistanceChunks} remains the default
     * for any world that is not named here. Keys are matched in the order callers
     * pass them to {@link #lodDistanceForWorld}:
     * <ul>
     *   <li>Paper: Bukkit world name first ({@code world_nether}, a Multiverse world
     *       like {@code creative}), then the dimension id
     *       ({@code minecraft:the_nether}) as fallback so a vanilla-named nether
     *       still matches when the admin keyed the dimension.</li>
     *   <li>Fabric / NeoForge: the dimension resource location
     *       ({@code minecraft:overworld}, {@code minecraft:the_nether},
     *       {@code minecraft:the_end}, or a datapack/mod dimension).</li>
     * </ul>
     * Empty (the default) = every world uses {@link #lodDistanceChunks}. Unknown
     * keys are ignored at lookup, never an error. Each value is clamped through
     * {@link #clampLodDistance} on validate, the same band as the default.
     *
     * <p>This is a LOCAL config surface, not a wire change: SessionConfig still
     * carries one distance, resolved for the player's current world at handshake
     * and re-pushed on dimension change.
     *
     * <p><b>{@code volatile} + replace-never-mutate:</b> this is read on the handshake
     * thread (a region thread on Folia), the processing thread, and the broadcaster
     * thread, but reassigned by {@code /lsslod set}. Every writer builds a fresh
     * {@link LinkedHashMap} and assigns it whole (never mutates the live map), so the
     * volatile reference safely publishes a fully-built map with no torn reads — the
     * same discipline as the volatile {@code genSlotCap} the processing thread reads
     * cross-thread. (Scalar config fields need no volatile: an {@code int} write can't
     * tear, and the tick-poll consumers re-read them live.)
     */
    public volatile Map<String, Integer> lodDistanceChunksByWorld = new LinkedHashMap<>();
    /**
     * Per-player bandwidth cap. Went 20 -> 50 -> 25 MiB on 2026-08-02, 25 -> 15 MiB on
     * 2026-08-05 (v0.9.1), then 15 -> 25 MiB on 2026-08-08 (all user decisions; the
     * last in the config rework's bandwidth re-bump alongside global 60 -> 75).
     *
     * <p><b>This charges RAW bytes, not wire bytes</b> ({@code estimatedBytes} = raw sections +
     * envelope; wire compression is deliberately invisible to it), because what it really
     * bounds is CLIENT DECODE AND INGEST WORK — and the elytra chunk-wall investigation
     * confirmed the client, not the link, is the binding constraint. So the ~6.25:1 the
     * compressed-columns work bought did NOT loosen the thing this cap exists to limit: at
     * 25 MiB counted the wire cost is roughly 4 MB/s, but the client still decodes 25 MiB/s.
     *
     * <p>Context for anyone retuning it: the elytra chunk wall reproduced at ~25 MB/s
     * counted, so the 25 default sits AT the historic incident rate (the 2026-08-05
     * 15 MiB era sat below it). That is a deliberate return: the thing that actually
     * caused the wall (the client's scan-cadence gate) is fixed, and the #71 ingest
     * taper and the decode-queue halt sit underneath as client-side guards. The
     * falsifiable check is the cap sweep in the investigation's section 11.7 — sweep
     * upward until {@code runway} collapses in the client trace.
     *
     * <p><b>Key spelling since the 2026-08-08 config rework:</b> {@code
     * mbPerSecondLimitPerPlayer}, a decimal in MiB/s (12.5 works). −1 = "not in the
     * file" sentinel; {@link #validate} resolves it — the new key wins when both are
     * present, else the legacy {@code bytesPerSecondLimitPerPlayer} converts, else the
     * 25.0 default. Consumers never read these fields directly: {@link
     * #bytesPerSecondPerPlayer()} is the resolved byte value.
     */
    public double mbPerSecondLimitPerPlayer = -1;
    /** RETIRED key spelling — honored on read, never written (see the rework note
     *  above; −1 = absent). */
    @HiddenFromFile
    public int bytesPerSecondLimitPerPlayer = -1;
    /**
     * LSS disk-read pool size. <b>0 = AUTO (the default)</b>, derived from the resolved read
     * path — see {@link #effectiveDiskReaderThreads(boolean)}.
     *
     * <p><b>This is not disk parallelism</b> (read-scheduler-design.md section 0), and the old
     * fixed default of 5 encouraged exactly the wrong instinct. On vanilla's single-threaded
     * IOWorker it is the number of LSS reads that can sit in the shared IO queue AHEAD of a
     * vanilla read: more threads do not speed up cold reads (the worker serializes them
     * regardless) but linearly increase how long a vanilla chunk load waits behind LSS. Where a
     * real priority mechanism exists instead — Moonrise's {@code Priority.LOW}, which is every
     * Paper/Folia server and any Fabric server with Moonrise — that tradeoff does not apply and
     * more threads are simply more parse/serialize CPU. One fixed number cannot be right for
     * both, which is why the default derives.
     */
    public int diskReaderThreads = 0;
    /**
     * Disk-read concurrency gate (disk-read-concurrency-gate-plan.md): at most this many
     * reader-pool threads may run the EXPENSIVE serve phase (region read → zlib inflate →
     * NBT parse → transcode → zstd compress — milliseconds of CPU per column) at any
     * instant. Store-hit serves (~44 µs SQLite blob + frame reuse) never consume a
     * permit. A read refused by the gate is a silent drop healed by the client's ≤1 s
     * re-declaration (the standard transient-drop path, counted {@code disk.gated}).
     *
     * <p><b>The CPU-vs-bandwidth separation:</b> the bandwidth caps bound the CLIENT
     * (raw bytes ≈ decode work); this bounds the SERVER (concurrent expensive reads ≈
     * CPU). Before this key the bandwidth caps were the only throughput governor on the
     * serve path, so raising them for fast warm store serves also uncapped the disk
     * path's CPU bill on cold regions.
     *
     * <p><b>0 = AUTO (the default), store-conditional:</b> with a store attached, K =
     * half the resolved reader pool (rounded up — pool 8 → 4, pool 3 → 2, pool 1 → 1),
     * reserving the rest for store lookups; with NO store attached, K = the pool — a
     * structural no-op, because there is no cheap path to protect and every upgrading
     * (store-off) server would otherwise pay pure downside on fresh worlds. See
     * {@link #effectiveMaxConcurrentDiskReads(int, boolean)}.
     *
     * <p><b>Disable idiom: set it ≥ the reader pool size</b> (e.g. 64) — a K at or above
     * the pool cannot bind (the {@code lodColumnsPerSecondLimit} large-value-inert
     * precedent). 0 is NOT off here — it is AUTO, exactly like the adjacent
     * {@code diskReaderThreads}; nonzero clamps 1..64 and additionally to the resolved
     * pool at derivation.
     */
    public int maxConcurrentDiskReads = 0;
    /**
     * Per-player send-queue cap. The default is the wire batch cap: under v17 replace
     * semantics a player's backlog is at most ONE wire batch, and a payload only enqueues
     * for an admitted backlog position, so enqueued payloads per player structurally
     * cannot exceed MAX_BATCH_CHUNK_REQUESTS — at this default the router's queue gate is
     * unreachable for any legal client, and the queue bounds worst-case buffered-payload
     * RAM at ~one batch per player.
     *
     * <p>The 2026-08-02 config review proposed retiring this to a constant on exactly that
     * "unreachable at its default" argument, and <b>implementation reversed the call</b>:
     * lowering it is the ONLY lever that exercises {@code service.queue_full}, the send-queue
     * breaker — which is a real production loss signal, unlike the transient-drop counters
     * beside it. The {@code bandwidth-throttle} soak scenario sets 64 here and gates on
     * queue_full firing; retiring the key would have left that production path with no
     * end-to-end coverage. A knob with no production use but a genuine harness role is test
     * infrastructure, not dead weight.
     */
    public int sendQueueLimitPerPlayer = LSSConstants.MAX_BATCH_CHUNK_REQUESTS;
    // outboundBufferCeilingKB DELETED 2026-08-13 (deletion review #2, user decision):
    // the AUTO mode was live-falsified three times (adaptive-transfer-rate-plan.md), and
    // the surviving operator-FIXED ceiling was structurally shadowed — the netty gauge
    // caps at the high-water mark while writable and the ceiling floored at 64 KB, so it
    // could only fire while NOT_WRITABLE, exactly when the default-ON transport yield
    // already skips the flush with better semantics (starvation floor + relevance
    // prune). Slow-link pacing is owned by the client transfer governor + the server
    // ping backstop; an old config file's key is ignored and dropped on the next save.
    /**
     * The vanilla-ping backstop (adaptive-transfer-rate-plan.md, Mechanism B): when a
     * player's keepalive ping rises >750 ms over its session baseline while LSS was
     * actually sending to them, their LOD bandwidth allocation is cut (first cut lands
     * below the observed send rate) and recovers slowly once ping normalizes. Coarse
     * and universal — it protects ANY client on a congested link, including old ones
     * without the client-side transfer governor. Runtime-mutable via
     * {@code /lsslod set enablePingBackstop} (the live A/B lever).
     */
    public boolean enablePingBackstop = true;
    /**
     * Send pacing (send-pacing-plan.md v3 — the refill-floored, burst-clamped drain):
     * spreads the bandwidth bank's one-tick burst into a ~5-tick slope so vanilla
     * packets interleave during LOD resolution waves (join/rejoin/teleport). The
     * budget floors at the allocation's own per-tick refill share, so sustained
     * throughput is never paced below the configured cap — this is a burst SHAPER,
     * never a rate governor (rate ownership is the client's, via want-set sizing).
     * Runtime-mutable via {@code /lsslod set enableSendPacing} (the live A/B lever).
     */
    public boolean enableSendPacing = true;
    /**
     * Region summaries (region-summary-sync-plan.md §5/§9): answer client
     * {@code lss:region_summary_req} frames with per-region freshness stamp windows so
     * an upgraded client validates the clean bulk of its cached disc in one exchange
     * instead of re-declaring ~1M positions over minutes. Checked in the HANDLER (not
     * just channel advertisement), though boot-set in practice — the key is not in the
     * {@code /lsslod set} registry, so a flip needs a restart. Off = requests are
     * silently dropped and clients fall back to per-column revalidation (exactly the
     * pre-summary behavior).
     */
    public boolean enableRegionSummaries = true;

    /**
     * Transport yield (vanilla-first-lod-yield-plan.md v2.1, v0.10.0 stage A2): while the
     * player's netty channel reports NOT WRITABLE, the per-tick column flush is skipped
     * and the queue retained — LSS never piles more LOD bytes into a channel that is
     * already backed up ahead of vanilla's chunk packets. A starvation floor sends one
     * payload per 5 s so a hard-yielding player is distinguishable from a dead one, and a
     * once-a-minute relevance prune drops queue entries the player has long left behind.
     * <b>Default TRUE since v0.11.0</b> (user decision 2026-08-13, superseding the
     * v0.10.0 ships-unarmed stance and its planned live-A/B precondition — the flip
     * rides the v0.11.0 Modrinth manual-testing pause as its live observation window).
     * Behind a buffering proxy (Velocity/Bungee) the gate sees the server→proxy hop
     * and is best-effort: it can under-yield, never over-yield. While armed, expect
     * flying players to ride the floor during sustained vanilla chunk bursts — that
     * IS "vanilla first". Loopback channels never go unwritable, so soaks/gametests
     * are provably unaffected (the CI-inertness pin in TransportYieldFlushTest).
     * No clamp (boolean).
     */
    public boolean lodYieldsToVanillaTransport = true;
    /** Fleet-wide bandwidth ceiling. Raised 100 -> 256 MiB 2026-08-02 (config review
     *  section 3.2 — at 20 MiB/player the old value bound at FIVE concurrent LOD players),
     *  lowered 256 -> 60 MiB on 2026-08-05 (user decision, v0.9.1, alongside the 15 MiB
     *  per-player cut), then 60 -> 75 on 2026-08-08 with the per-player 15 -> 25 re-bump:
     *  a deliberate total-egress bound sized for typical hosts. At the 25 MiB per-player
     *  default it binds at THREE concurrent full-rate LOD players and manifests as
     *  everyone slowing together — operators with more simultaneous LOD traffic should
     *  raise this first. Key spelling since the 2026-08-08 rework:
     *  {@code mbPerSecondLimitGlobal}, decimal MiB/s; same sentinel/priority scheme as
     *  the per-player pair; consumers read {@link #bytesPerSecondGlobal()}. */
    public double mbPerSecondLimitGlobal = -1;
    /** RETIRED key spelling — honored on read, never written (−1 = absent). */
    @HiddenFromFile
    public int bytesPerSecondLimitGlobal = -1;
    public boolean enableChunkGeneration = true;
    /** 32 -> 40 by user decision 2026-08-08 (config rework), matching the per-player cap. */
    public int generationConcurrencyLimitGlobal = 40;
    public int generationTimeoutSeconds = 60;
    /**
     * Seconds between dirty-column pushes to clients. <b>0 = dirty pushes DISABLED</b>
     * (v0.11.0, dirty-broadcast-interval-zero-plan.md): no {@code DirtyColumnsS2CPayload}
     * ever leaves the server, but the drain and the whole invalidation fan-out — LOD-store
     * rows, the timestamp cache, in-flight taints, per-player done-bit/probe-stamp clears —
     * keep running every {@link LSSConstants#DIRTY_DRAIN_ONLY_INTERVAL_SECONDS} seconds, so
     * a rejoin or any mid-session re-ask re-resolves honestly. Consequences an operator
     * accepts with 0: connected clients keep stale LOD until they re-ask (rejoin always
     * heals), and {@code NOT_GENERATED}-parked positions lose their one mid-session revival
     * path (the dirty broadcast) — they heal only on reconnect. Flipping back to nonzero is
     * live (the broadcasters re-read per tick) but never retroactive: edits drained during
     * an off window already left the tracker and surface via re-ask only. Negative values
     * normalize to 0; 1 stays the nonzero floor, 300 the ceiling.
     */
    public int dirtyBroadcastIntervalSeconds = 10;
    // The per-player SYNC (disk-read) slot cap is NOT config anymore — see
    // LSSConstants.SYNC_ON_LOAD_SLOT_CAP (shadowed by the disk-pool headroom gate at the
    // default pool size; a fixed fairness ceiling above it). The generation caps stay
    // config: they are the real worldgen limiters, but they are server-internal (off the
    // wire since the server-owned-generation fold into v17).
    /** 16 -> 40 by user decision 2026-08-08 (config rework). validate() still clamps
     *  this to the global cap. */
    public int generationConcurrencyLimitPerPlayer = 40;
    /**
     * Per-dimension up-to-date timestamp cache size (live heap, not disk). <b>0 = AUTO (the
     * default)</b> — derived from {@link #lodDistanceChunks}, see
     * {@link #effectiveTimestampCacheMB()}. A fixed value silently under-provisions the moment
     * an admin raises the LOD distance, which is precisely when the cache matters most; the
     * derived value tracks it. Multiply by dimension count for the real heap budget.
     */
    public int perDimensionTimestampCacheSizeMB = 0;
    /**
     * Miss-memo TTL (docs/planning/miss-memo-design.md): an authoritative disk miss is
     * remembered for this many seconds, so a position waiting for a generation slot skips
     * the redundant not-found re-reads (arriving at the client's scan cadence — 1 Hz, up
     * to 4 Hz under its adaptive fast re-scan) and falls through to the generation
     * decision directly. 0 disables the memo (the kill switch — restores the pre-memo
     * re-read churn, which remains fully correct behavior).
     */
    public int missMemoTtlSeconds = 30;
    /**
     * When true (default), LSS disk reads yield to vanilla/gameplay chunk loading: Fabric
     * schedules them at IOWorker BACKGROUND priority (or Moonrise Priority.LOW when Moonrise is
     * present); Paper/Folia route through Moonrise at Priority.LOW. Set false to restore
     * FOREGROUND reads (the pre-0.7 behavior) as a rollback. No clamp: a boolean has no
     * out-of-range value.
     *
     * <p>The 2026-08-02 config review proposed retiring this (its real failure modes are
     * handled by automatic one-way latches, not by config), and <b>implementation reversed the
     * call</b> for the same reason as {@code sendQueueLimitPerPlayer}: it is the arm selector
     * for {@code benchmark_compare.sh}'s {@code v17-fg} foreground-vs-background CPU
     * comparison. Retiring it would not have broken that harness — it would have made its two
     * arms silently identical, which is worse than breaking.
     */
    @HiddenFromFile // expert rollback switch — honored from files, never written (2026-08-08 rework)
    public boolean useBackgroundReadPriority = true;
    /**
     * When true (default), the Fabric vanilla-IOWorker background read is SPLIT (perf
     * round Phase 3 / R1): the single-threaded IOWorker executor only fetches the
     * chunk's raw compressed record; zlib inflate + NBT parse run on the LSS reader
     * pool (or the backfill thread for backfill reads). The IOWorker was the busiest
     * serving thread by ~7x per-thread — pread + inflate + full parse for every LOD
     * read — and is the mechanism of the documented A7 read-timeout storms. Set false
     * to restore the pre-split full-read-on-executor closure as a rollback NARROWER
     * than {@code useBackgroundReadPriority=false} (which also drops the Moonrise rung
     * and all read protection). Fabric-only in effect: Paper/Folia reads route through
     * Moonrise, which never had the executor-parse problem; the Moonrise rung on
     * Fabric is likewise untouched. No clamp: a boolean has no out-of-range value.
     */
    @HiddenFromFile // expert rollback switch — honored from files, never written (2026-08-08 rework)
    public boolean useBackgroundReadSplit = true;
    /**
     * When true (default), the Phase 3 split's pool-side NBT parse is SELECTIVE (perf
     * round Phase 4 / R2): only the root keys the serializer actually reads ({@code
     * Status}, {@code sections} — the whitelist is build-pinned against the serializer
     * source) are materialized; every other root subtree is skipped without building
     * Strings/HashMaps/tags. The win is allocation churn, not inflate (skipped bytes
     * still move through the inflater). One documented leniency divergence: a corrupt
     * NON-section root subtree that full parse would throw on can now skip cleanly —
     * more lenient, and any selective-parse throw falls back to a full parse of the
     * same buffer. Fabric-only in effect (it lives at the split's parse site; the
     * {@code lodStoreBackfillColumnsPerSecond} shared-key idiom). Set false to
     * restore the full root parse as a rollback. No clamp: a boolean has no
     * out-of-range value.
     */
    @HiddenFromFile // expert rollback switch — honored from files, never written (2026-08-08 rework)
    public boolean useSelectiveNbtParse = true;
    /**
     * When true (default), disk-read column serving transcodes region NBT straight into
     * wire bytes — palette ids and bit-storage longs copied verbatim off the NBT — instead
     * of decoding every section into PalettedContainer objects and re-serializing them
     * (docs/planning/nbt-transcode-design.md). Byte-identical output (golden corpus +
     * live/disk parity gates); exotic shapes (>256-entry block palettes, >8-entry biome
     * palettes, malformed data, x-ray-mask-needing sections) fall back per section to the
     * object path automatically. Set false to force EVERY section through the object path
     * (the pre-round-2 behavior) as a rollback. No clamp: a boolean has no out-of-range
     * value.
     */
    @HiddenFromFile // expert rollback switch — honored from files, never written (2026-08-08 rework)
    public boolean useNbtTranscode = true;
    /**
     * When true (default), columns for capability-declaring protocol-19 clients ship as
     * zstd-1 frames end-to-end (docs/planning/compressed-columns-design.md): store hits
     * ship their stored frame verbatim, live/disk/generation serves compress once on the
     * processing thread — removing the netty deflate over raw bytes (~520 us/col on store
     * hits, Phase 0) at the cost of ~+12% wire bytes. Requires the server-side zstd
     * native (probed at service start; unavailable => raw for everyone, one warning).
     * Set false as the rollback lever: codec 0 for everyone, capability ignored. No
     * clamp: a boolean has no out-of-range value.
     */
    @HiddenFromFile // expert rollback switch — honored from files, never written (2026-08-08 rework)
    public boolean useCompressedColumns = true;
    /**
     * When true (default), clients running the legacy protocol-16 mod (v0.6.x) get a
     * translated LOD session through the v16 compat shim (docs/planning/v16-compat-design.md)
     * instead of the silent version-mismatch no-session. Inert for current-protocol clients;
     * set false as the kill switch to restore the strict version gate. No clamp: boolean.
     */
    public boolean enableV16Compat = true;
    /**
     * When true (default), clients running the protocol-18 mod (v0.7.x–v0.8.x) get a native
     * LOD session through the v18 compat rung (docs/planning/v18-compat-design.md): the
     * current session shape with the SessionConfig echoing 18, columns forced codec-RAW, and
     * the codec byte stripped at egress. Without the rung those clients degrade to the v16
     * fallback session after their 5 s discovery timeout. Inert for current-protocol
     * clients; set false as the kill switch to restore the strict version gate for 18.
     * No clamp: boolean.
     */
    public boolean enableV18Compat = true;
    /**
     * When true (default), clients running the protocol-19 mod (v0.9.x–v0.10.x-pre) get a
     * native LOD session through the v19 compat rung
     * (docs/planning/cross-version-identity-encoding-plan.md §4.2): the current session
     * shape with the SessionConfig echoing 19 and the column BODY translated per serve
     * from the v20 identity-dictionary layout back to native global-id palettes. Without
     * the rung those clients degrade to the v16 fallback session after their 5 s
     * discovery timeout. Inert for current-protocol clients; set false as the kill
     * switch to restore the strict version gate for 19. No clamp: boolean.
     */
    public boolean enableV19Compat = true;
    /**
     * When true (default), a legacy-protocol handshake (19/18/16) from a client that
     * ViaVersion/ViaFabric POSITIVELY reports as running a different MC version is
     * answered with silence instead of a compat session
     * (docs/planning/cross-version-identity-encoding-plan.md §7): legacy column bytes
     * are native-id formats for THIS MC version's registries, so a cross-MC legacy
     * client would receive garbage it cannot decode. One INFO line names the versions.
     * Fail-open — without Via, or when Via has no signal for the player, behavior is
     * unchanged. This is the operator override: the guard denies registration off a
     * third-party reflective API, and a Via API drift that misreported protocols would
     * otherwise lock out legitimate same-MC legacy clients with no recourse — set
     * false to disarm. Current-protocol (v20) clients are never affected (their
     * handshake carries the MC data version). No clamp: boolean.
     */
    public boolean enableViaMismatchGuard = true;
    /**
     * The LOD store switch (docs/planning/lod-store-implementation-plan.md):
     * "off" (no store — the kill switch every store gate A/Bs against) and
     * <b>"full" (the SQLite disk store — the DEFAULT since 2026-08-02)</b>, alone since the
     * Phase 2 delete-the-tier verdict.
     *
     * <p><b>Default-on costs disk, and that is a deliberate trade.</b> Live measurement:
     * 98.8% of column serves are store hits (280,273 hits / 2,808 real disk reads), and a hit
     * serves in ~100 us against ~2.4 ms for the NBT path — plus, since compressed columns, it
     * ships its stored zstd frame verbatim with no compression at all. The price is that a
     * fully warmed store roughly DOUBLES the world folder (~5.3-7.6 KB/col against
     * ~10.6 KB/chunk of region data). It is derived data: deleting the {@code lss-lod/} folder
     * is always safe, and the service logs the expected growth once at startup.
     *
     * <p>SPLIT DEFAULT (user decision, 2026-08-08, second round — refines the same-day
     * on-by-default decision, which superseded the 2026-08-03 opt-in): the COMPILED
     * default here is "off", so a key ABSENT from an existing config file keeps the
     * store off — an upgrade must never silently double a world folder (the 2026-08-03
     * concern, back as the governing rule for upgrades). A BRAND-NEW install — no
     * config file at all — generates "on" via {@link #onFreshCreate}, so new servers
     * get the feature from day one with the disk cost stated in README/release notes.
     * "on" ≡ "full" (the old spelling stays a read alias; the file writes back "on").
     * {@code lodStoreBackfill} deliberately stays ON so an armed store gets the
     * background warm-up — one switch.
     *
     * <p>Unknown values still normalize to "off" (a typo silently DISABLES the feature
     * instead of enabling a storage engine — predictable either way, and the config
     * echo names the effective mode). "memory" remains one of those unknowns — the
     * in-memory tier itself is DELETED (2026-08-13); a failed SQLite init runs
     * store-less (see LodStores).
     *
     * <p><b>Harness note:</b> the soak/benchmark stagings and gametest run dirs pin
     * this OFF explicitly (store scenarios excepted) — their law baselines and source
     * pins were calibrated store-off, and re-baselining them buys nothing.
     */
    public String lodStore = "off";
    // NOTE: lodStoreMemoryMB is RETIRED (2026-08-02) along with the "memory" mode, and
    // the in-memory tier itself was DELETED 2026-08-13 (a failed SQLite init runs
    // store-less). GSON ignores the key on load and validate()'s next save drops it
    // from the file, same as the retired syncOnLoadConcurrencyLimitPerPlayer.
    /**
     * Periodic LOD-store freshness re-sweep (seconds; 0 = off). This is PAPER's stale
     * bound: its dirty detection is event-driven with documented unfired-event gaps
     * (e.g. walk-in generation without ChunkPopulateEvent opted in), so the store
     * re-checks region mtimes/header stamps on this cadence — staleness is bounded by
     * ≈ one autosave + one sweep. PaperConfig overrides the default to 300; the shared
     * default stays 0 for Fabric, whose content-hash dirty pipeline invalidates store
     * rows at runtime (a periodic resweep there would only churn-drop rows on
     * metadata-only re-saves) and whose startup sweep covers offline edits.
     */
    public int lodStoreResweepSeconds = 0;
    /**
     * Opt-in LOD-store background backfill (Phase 4, docs/planning/
     * lod-store-implementation-plan.md): when true AND lodStore=full, a MIN_PRIORITY
     * thread walks every region file nearest-spawn-first and warms the store for
     * terrain no client has asked for yet, yielding to player reads and tick health.
     * Also controllable at runtime via /lsslod store backfill start|stop.
     *
     * <p><b>Default TRUE since 2026-08-02.</b> Organic warming only ever covers terrain a
     * client already asked for — which is terrain that was already served once. The store's
     * value proposition is that the SECOND visitor, and the same player after a restart,
     * arrive warm; that requires walking terrain nobody has requested yet. Safe as a default
     * because the restraint architecture is not tunable: MIN_PRIORITY thread, one read at a
     * time, the reader-headroom gate, the MSPT ceiling, 500 ms pause polling. The walk is
     * resumable across restarts via per-region done-marks and logs a size estimate up front.
     *
     * <p>Stays TRUE even though {@code lodStore} now defaults to "off" (2026-08-03): the key
     * is INERT while the store is off, so the only thing this default decides is what an
     * operator gets when they DO opt in — and they should get the whole feature from one
     * switch rather than discovering a second one later. No clamp: boolean.
     */
    public boolean lodStoreBackfill = true;
    /**
     * Backfill pace: visited columns per second (docs/planning/store-backfill-tuning-plan.md).
     * Every visited column counts against the window — deposits, hasRow skips, and errors
     * alike — so the value bounds the walk's total footprint, not just its read rate.
     * The restraint gates (reader headroom, tick health, MIN_PRIORITY, one read at a
     * time) are deliberately NOT tunable; this knob only trades walk duration against
     * idle-server IO pressure. Inert on Paper until it grows a backfill (same recorded
     * stance as lodStoreBackfill).
     *
     * <p><b>Raised 100 -> 500 on 2026-08-02</b>, which is what makes the backfill tolerable as
     * a default: at 100 col/s a 700k-column world is ~2 hours of continuous background walking;
     * at 500 it is ~23 minutes. For a task with a definite end, finishing sooner is strictly
     * better — the restraint gates are unchanged, so the walk is no more intrusive per unit of
     * work, it simply stops being present sooner. 500 is live-proven on the Modrinth server and
     * sits inside the honest band (a single-threaded synchronous read is ~1-2 ms healthy, so
     * ~500-1000/s is the natural ceiling). The pace is a CEILING, never a floor: a constrained
     * box is simply gated down (measured 32 col/s under load at the old 100 cap).
     */
    public int lodStoreBackfillColumnsPerSecond = 500;
    // NOTE: lodStoreBackfillTickCeilingMillis is RETIRED (2026-08-02, config review section 6)
    // — now LSSConstants.LOD_STORE_BACKFILL_TICK_CEILING_MS. Its clamp band was 20..50 and both
    // ends were degenerate by its own documentation (>= 50 never pauses, <= 20 never runs on a
    // busy server), which is not a tuning range. GSON drops the key on the next save.
    /**
     * On-disk size cap for the SQLite LOD store (main DB, MB). <b>0 = UNCAPPED — the
     * default</b> (user decision, docs/planning/store-cap-behavior-plan.md: admins
     * should simply know the store roughly DOUBLES the world folder when fully warmed
     * — ~7.6 KB/col vs ~10.6 KB/chunk of region data; a silent partial-warmth cap
     * surprises more than disk growth does, and at the old 2048 default a
     * pregenerated world entered a backfill<->eviction treadmill forever). A nonzero
     * value (clamped 64..1048576) opts into the bound for quota-limited hosts: above it
     * the batcher evicts oldest-timestamp rows in batches and returns pages via
     * incremental_vacuum. NOTE the eviction order is oldest FIRST-DEPOSITED (a row's
     * ts is set when it enters and is not refreshed by hits), so a capped store sheds
     * its longest-resident terrain, not its least-recently-served. Evicted columns
     * re-warm on their next serve, and the affected backfill regions are un-marked so
     * an enabled backfill revisits them; the backfill also hard-stops near an active
     * cap rather than churn it.
     */
    public int lodStoreMaxMB = 0;

    /**
     * Resolved disk-reader pool size, honouring the 0 = AUTO default.
     *
     * @param prioritizedReadPath true when the resolved read path carries a REAL priority
     *        mechanism that defers to gameplay independently of concurrency — Moonrise's
     *        {@code Priority.LOW}, i.e. every Paper/Folia server and any Fabric server with
     *        Moonrise resolved. False for vanilla's single-threaded IOWorker, where LSS
     *        concurrency IS the vanilla-delay tradeoff, and for the chunk-IO-overhaul
     *        fallback, where the adaptive throttle owns the real limit and this is a ceiling.
     */
    public int effectiveDiskReaderThreads(boolean prioritizedReadPath) {
        if (diskReaderThreads > 0) return diskReaderThreads;
        if (!prioritizedReadPath) return LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER;
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2,
                LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER,
                LSSConstants.AUTO_DISK_READER_THREADS_PRIORITIZED_MAX);
    }

    /**
     * Resolved disk-read gate capacity K, honouring the 0 = AUTO default
     * (disk-read-concurrency-gate-plan.md — the {@code effectiveDiskReaderThreads}
     * three-part pattern: field + runtime-parameter resolver + clamps-nonzero-only).
     *
     * @param resolvedReaderThreads the RESOLVED pool size (0=AUTO already applied via
     *        {@link #effectiveDiskReaderThreads(boolean)}) — deriving from it inherits
     *        the read-path-aware sizing, and an explicit override clamps to it (a K
     *        above the pool cannot bind)
     * @param storeAttached the POST-DEGRADE store state ({@code store != null} at the
     *        service, never {@code lodStore != "off"}): a store that failed to init has
     *        no cheap rung to protect, and half-pooling a store-less server is the
     *        convergent-MAJOR regression both gate reviews carved out
     */
    public int effectiveMaxConcurrentDiskReads(int resolvedReaderThreads, boolean storeAttached) {
        if (maxConcurrentDiskReads > 0) {
            return Math.clamp(maxConcurrentDiskReads, LSSConstants.MIN_MAX_CONCURRENT_DISK_READS,
                    resolvedReaderThreads);
        }
        if (!storeAttached) return resolvedReaderThreads; // AUTO, no store: no-op gate
        return Math.clamp(
                (resolvedReaderThreads + LSSConstants.AUTO_DISK_READ_GATE_DIVISOR - 1)
                        / LSSConstants.AUTO_DISK_READ_GATE_DIVISOR, // ceil(pool/2)
                LSSConstants.MIN_MAX_CONCURRENT_DISK_READS, resolvedReaderThreads);
    }

    /**
     * Resolved per-dimension timestamp-cache size in MB, honouring the 0 = AUTO default.
     *
     * <p>The scan is a Chebyshev (square) ring walk, so the tracked region is
     * {@code (2*(lodDistanceChunks + LOD_DISTANCE_BUFFER) + 1)^2} positions; AUTO provisions
     * TIMESTAMP_CACHE_AUTO_COVERAGE_FACTOR (8x) that area at the tile cache's ~5 B/column
     * (D0 — part of the ~13x tile win is spent on coverage, so roaming and multi-player
     * spread stop thrashing eviction; net at the default distance: ~2.5x less RAM (~30 MB
     * -> ~12 MB) tracking ~5.3x more columns than the pre-tile 1.5x/64 B model). Clamped
     * into the same band an
     * explicit value gets — the 512 MB ceiling now clears distance 1024 at full coverage.
     */
    public int effectiveTimestampCacheMB() {
        if (perDimensionTimestampCacheSizeMB > 0) return perDimensionTimestampCacheSizeMB;
        long side = 2L * (maxConfiguredLodDistanceChunks() + LSSConstants.LOD_DISTANCE_BUFFER) + 1L;
        long columns = (long) (side * side
                * LSSConstants.TIMESTAMP_CACHE_AUTO_COVERAGE_FACTOR);
        long mb = columns * LSSConstants.TIMESTAMP_CACHE_HEAP_BYTES_PER_COLUMN
                / (1024L * 1024L);
        return (int) Math.clamp(mb, LSSConstants.MIN_TIMESTAMP_CACHE_SIZE_MB,
                LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB);
    }

    /** The store's byte cap for {@code Environment.maxDbBytes}: 0 (or a validated-away
     *  negative) means uncapped = Long.MAX_VALUE. Both platforms wire through this so
     *  the 0-semantics cannot drift. */
    public long lodStoreMaxBytes() {
        return lodStoreMaxMB <= 0 ? Long.MAX_VALUE : lodStoreMaxMB * 1024L * 1024L;
    }

    /**
     * One-line effective-config echo, INFO-logged once at service start on both platforms.
     *
     * <p>The format is a SCRIPT-CONSUMED CONTRACT (PERF round Phase 0 item 1): the
     * measurement harnesses ({@code profile_disk_read.sh}, {@code compress_gate.sh},
     * {@code backfill_profile.sh}) grep {@code "Effective config: "} out of server.log
     * into each run's meta.json and FAIL the arm when a staged knob does not appear
     * here — an ignored config key must invalidate the arm, not silently compare two
     * identical arms (the exact failure mode the 2026-08-06 findings erratum recorded).
     * Comma-separated {@code key=value} pairs in this fixed order; new perf-sensitive
     * knobs APPEND (scripts match per-key substrings, so appending is compatible).
     * Every echoed value is the RESOLVED one — echoing a raw key would hide exactly
     * the resolution the scripts need to see. Pinned by ConfigValidationTest + the
     * Paper twin; the call-site wiring (resolved arguments, once per service start)
     * is source-regex-pinned in ChannelAccessorContractTest.
     *
     * @param effectiveReaderThreads the RESOLVED pool size — 0=AUTO already applied via
     *        {@link #effectiveDiskReaderThreads(boolean)}
     * @param effectiveCompressedColumns the LIVE wire-compression state AFTER the zstd
     *        native probe, not the config request — a probe-failed server ships raw for
     *        every session, and echoing the request would let compress_gate.sh compare
     *        two identical raw arms with both marked valid (B0 review M1)
     * @param effectiveMaxConcurrentDiskReads the RESOLVED gate capacity K — 0=AUTO and
     *        the store-conditional already applied via
     *        {@link #effectiveMaxConcurrentDiskReads(int, boolean)}. Because K depends
     *        on POST-DEGRADE store attachment, the echo call site sits AFTER store
     *        attachment on both platforms (an earlier echo would report K computed
     *        store-less on every store-armed server — the same resolved-not-requested
     *        rule as the zstd argument; ordering source-pinned in
     *        ChannelAccessorContractTest)
     */
    public String effectiveConfigEcho(int effectiveReaderThreads,
                                      boolean effectiveCompressedColumns,
                                      int effectiveMaxConcurrentDiskReads) {
        return "Effective config: useNbtTranscode=" + useNbtTranscode
                + ", diskReaderThreads=" + effectiveReaderThreads
                + ", useCompressedColumns=" + effectiveCompressedColumns
                + ", useBackgroundReadSplit=" + useBackgroundReadSplit
                + ", useSelectiveNbtParse=" + useSelectiveNbtParse
                + ", maxConcurrentDiskReads=" + effectiveMaxConcurrentDiskReads;
    }
    /**
     * LOD x-ray masking (docs/planning/antixray-compat-design.md §3). "auto" (default)
     * masks iff an anti-xray engine is detected — Paper's built-in anti-xray config, or the
     * AntiXray mod on Fabric — adopting its per-world hidden list + max-block-height
     * ("mask exactly what the packet engine masks"). "on" forces masking everywhere; "off"
     * is the explicit kill switch (re-opens the LOD ore leak knowingly — the AntiXray crash
     * shim stays active regardless). Unknown values normalize to "auto".
     */
    public String xrayObfuscation = "auto";
    /**
     * FALLBACK hidden-block list — used when no engine values are adoptable (mode "on"
     * without a detected engine, or a detection/reflection failure). Verbatim copy of
     * Paper's default engine-mode-1 {@code hidden-blocks}. All states of each block are
     * hidden; unknown ids warn and are skipped at resolve time. An explicit empty list
     * means "hide nothing"; a malformed null restores this default.
     */
    public List<String> xrayHiddenBlocks = defaultXrayHiddenBlocks();

    // ---- Far players (v0.11.0 — far-player-proxies-plan.md §3.4; ARMED since E2:
    // ---- compiled default "on", clients send CAPABILITY_FAR_PLAYERS; serving is
    // ---- always additionally gated on that bit, so vanilla/legacy clients cost 0).

    /**
     * Far-player proxies: {@code "off"} / {@code "opt-in"} / {@code "on"}. Server-
     * authoritative privacy mode (the ESP-oracle fix over SeeU): {@code opt-in} serves
     * only targets whose own client sent shareSelf=true; {@code on} serves everyone
     * minus the exclude list / permission node / shareSelf opt-outs. COMPILED DEFAULT
     * {@code "on"} since E2 for fresh AND upgrading installs (user decision
     * 2026-08-12; E1 shipped it {@code "off"}/inert). Unknown values still fail SAFE
     * to {@code off} — for a position-sharing feature the safe direction is private.
     */
    public String farPlayers = "on";
    /** Broadcast cadence in ticks (default 10 = 2 Hz full-rate tier; far tiers halve). */
    public int farPlayersUpdateIntervalTicks = 10;
    /** Server cap on the visibility ring, blocks (client prefs intersect it). Default
     *  2048 — deliberately NOT SeeU's 8192; admins raise it consciously (privacy). */
    public int farPlayersMaxDistanceBlocks = 2048;
    /** Inner ring in blocks (0 = none): players nearer than this are vanilla's job. */
    public int farPlayersMinDistanceBlocks = 0;
    /** Serve spectators as far players (default false — SeeU's proven default). */
    public boolean farPlayersSendSpectators = false;
    /** Names/UUIDs never served as far players regardless of mode (restart-only for
     *  v0.11.0 — R-9 registers only the mode + max distance as runtime keys). */
    public List<String> farPlayersExclude = List.of();
    /**
     * FALLBACK mask cutoff: only blocks below this world Y are masked (Paper's default 64).
     * At/above it the data already ships unobfuscated in vanilla chunk packets, so masking
     * there would over-hide while protecting nothing.
     */
    public int xrayMaxBlockHeight = 64;

    /** Paper's default engine-mode-1 hidden-blocks, copied verbatim (2026-07-23 build). */
    public static List<String> defaultXrayHiddenBlocks() {
        return List.of(
                "copper_ore", "deepslate_copper_ore", "raw_copper_block",
                "gold_ore", "deepslate_gold_ore",
                "iron_ore", "deepslate_iron_ore", "raw_iron_block",
                "coal_ore", "deepslate_coal_ore",
                "lapis_ore", "deepslate_lapis_ore",
                "mossy_cobblestone", "obsidian", "chest",
                "diamond_ore", "deepslate_diamond_ore",
                "redstone_ore", "deepslate_redstone_ore",
                "clay",
                "emerald_ore", "deepslate_emerald_ore",
                "ender_chest");
    }

    @Override
    protected String getFileName() {
        // Brand-preferred since 2026-08-13 (XANTHA's release patch): the candidates
        // mechanism IS the migration story the old brand-invariant comment demanded —
        // an existing lss-server-config.json is adopted (read AND written) by a VSS jar,
        // so a jar swap keeps its config; only a fresh VSS install creates vss-*.
        return serverConfigCandidates()[0];
    }

    /** MiB — the mb* bandwidth keys' unit. */
    private static final double MB = 1024.0 * 1024.0;
    // The shipped bandwidth defaults, in MiB/s (2026-08-05 user decision on the byte
    // values; re-denominated by the 2026-08-08 key rename).
    private static final double DEFAULT_MB_PER_PLAYER = 25.0;
    private static final double DEFAULT_MB_GLOBAL = 75.0;

    /** The key-rename resolution ladder (2026-08-08 rework), pure: the NEW decimal-MiB
     *  key wins when present (its sentinel −1 means "not in the file"), else the legacy
     *  byte key converts, else the default. Shared by validate() and the accessors so a
     *  config validate() never touched still resolves sanely (the lodStoreMaxBytes()
     *  robustness convention) instead of leaking a −1 sentinel to consumers. */
    private static double resolveMb(double mbValue, int legacyBytes, double defaultMb) {
        if (mbValue >= 0) return mbValue;
        return legacyBytes >= 0 ? legacyBytes / MB : defaultMb;
    }

    /** Resolved per-player bandwidth in bytes/s — the ONLY reader-facing form (the
     *  mb/legacy field pair is a file-format concern; validate() canonicalizes it). */
    public int bytesPerSecondPerPlayer() {
        return (int) Math.round(
                resolveMb(mbPerSecondLimitPerPlayer, bytesPerSecondLimitPerPlayer, DEFAULT_MB_PER_PLAYER) * MB);
    }

    /** Resolved global bandwidth ceiling in bytes/s — see {@link #bytesPerSecondPerPlayer()}. */
    public int bytesPerSecondGlobal() {
        return (int) Math.round(
                resolveMb(mbPerSecondLimitGlobal, bytesPerSecondLimitGlobal, DEFAULT_MB_GLOBAL) * MB);
    }

    /** validate()'s half of the rename: fold the resolution into the mb field (what
     *  save() writes and the clamps bound) and re-sentinel the legacy field so it can
     *  never leak stale state into a later validate pass. */
    private void resolveBandwidthKeys() {
        mbPerSecondLimitPerPlayer =
                resolveMb(mbPerSecondLimitPerPlayer, bytesPerSecondLimitPerPlayer, DEFAULT_MB_PER_PLAYER);
        mbPerSecondLimitGlobal =
                resolveMb(mbPerSecondLimitGlobal, bytesPerSecondLimitGlobal, DEFAULT_MB_GLOBAL);
        bytesPerSecondLimitPerPlayer = -1;
        bytesPerSecondLimitGlobal = -1;
    }

    /** Brand-new install (no config file existed): generate the file with the store ON
     *  — the {@code lodStore} split default. See the field javadoc; the compiled "off"
     *  governs keys absent from existing files, this "on" governs fresh generation. */
    @Override
    protected void onFreshCreate() {
        lodStore = "on";
    }

    // ---- Per-key clamp helpers (v0.11.0 stage C, runtime-settings-commands-plan.md +
    // the mega plan's R-2 registry clamp rule): validate() and the /lsslod set registry
    // share these EXACT functions, so a registry row can never clamp differently from
    // boot validation (the review MAJOR: a bare (min,max) row would turn
    // `set dirtyBroadcastIntervalSeconds 0` into 1 s — re-breaking DIRTY0 through the
    // command surface one stage after it was fixed — and `set maxConcurrentDiskReads 0`
    // into K=1 instead of AUTO). ----

    public static int clampLodDistance(int v) {
        return Math.clamp(v, LSSConstants.MIN_LOD_DISTANCE, LSSConstants.MAX_LOD_DISTANCE);
    }

    /**
     * Rebuilds the per-world override map: drops blank/null and over-long keys, coerces
     * any {@link Number} value (a defensive {@code intValue()} — Gson honors the field's
     * {@code Map<String,Integer>} generic type and yields Integers, but a hand-edited file
     * or a schema drift could present a Double), clamps each value through
     * {@link #clampLodDistance}, restores an empty map from null. Insertion order is kept
     * so a re-save does not reshuffle an admin's file. The over-long key drop mirrors the
     * {@code /lsslod set} path's rejection (which throws) — a file just drops, never fails
     * the whole load.
     */
    public static Map<String, Integer> clampLodDistanceByWorld(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) return new LinkedHashMap<>();
        Map<String, Integer> cleaned = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            Object keyObj = entry.getKey();
            if (!(keyObj instanceof String key)) continue;
            key = key.trim();
            if (key.isEmpty() || key.length() > LSSConstants.MAX_DIMENSION_STRING_LENGTH) continue;
            Object value = entry.getValue();
            if (!(value instanceof Number n)) continue;
            cleaned.put(key, clampLodDistance(n.intValue()));
        }
        return cleaned;
    }

    /**
     * The LOD distance that applies to {@code worldKeys} — first matching override
     * in {@link #lodDistanceChunksByWorld} wins; otherwise {@link #lodDistanceChunks}.
     * Null, blank, and unknown keys are skipped (fail toward the default). Callers
     * pass Paper's Bukkit world name then the dimension id, or Fabric's dimension
     * id alone; see the field javadoc for the key convention.
     */
    public int lodDistanceForWorld(String... worldKeys) {
        // Snapshot the volatile once: it is only ever REPLACED whole (never mutated), so
        // one read gives a stable, fully-built map for the whole lookup.
        Map<String, Integer> byWorld = this.lodDistanceChunksByWorld;
        if (byWorld != null) {
            for (String key : worldKeys) {
                if (key == null || key.isEmpty()) continue;
                Integer override = byWorld.get(key);
                if (override != null) return override;
            }
        }
        return lodDistanceChunks;
    }

    /**
     * The largest configured LOD distance (default plus every override). Used for
     * GLOBAL sizing that cannot be per-player — the AUTO timestamp-cache budget,
     * the diskReadDone sweep radius, the yield prune, the region-summary admission
     * window. An under-sized global bound is the corrupting direction (a high
     * override would get its still-declarable done-bits swept); over-size is only
     * memory already bounded elsewhere.
     */
    public int maxConfiguredLodDistanceChunks() {
        int max = lodDistanceChunks;
        Map<String, Integer> byWorld = this.lodDistanceChunksByWorld; // snapshot the volatile once
        if (byWorld != null) {
            for (Integer v : byWorld.values()) {
                if (v != null) max = Math.max(max, v);
            }
        }
        return max;
    }

    /** Negative = the file-absent sentinel → the compiled default; else the byte band. */
    public static double clampMbPerPlayer(double v) {
        if (v < 0) return DEFAULT_MB_PER_PLAYER;
        return Math.clamp(v, LSSConstants.MIN_BYTES_PER_SECOND / MB,
                LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER / MB);
    }

    public static double clampMbGlobal(double v) {
        if (v < 0) return DEFAULT_MB_GLOBAL;
        return Math.clamp(v, LSSConstants.MIN_BYTES_PER_SECOND / MB,
                LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT / MB);
    }

    public static int clampGenGlobal(int v) {
        return Math.clamp(v, LSSConstants.MIN_CONCURRENT_GENERATIONS, LSSConstants.MAX_CONCURRENT_GENERATIONS);
    }

    /** Cross-field: the per-player cap clamps against the CONFIGURED global (§9.1). */
    public static int clampGenPerPlayer(int v, int configuredGlobal) {
        return Math.clamp(v, LSSConstants.MIN_CONCURRENCY_LIMIT, configuredGlobal);
    }

    /** 0 (and negatives) = dirty pushes disabled — a first-class value (DIRTY0). */
    public static int clampDirtyBroadcastInterval(int v) {
        return v <= 0 ? 0 : Math.clamp(v,
                LSSConstants.MIN_DIRTY_BROADCAST_INTERVAL, LSSConstants.MAX_DIRTY_BROADCAST_INTERVAL);
    }

    /** 0 (and negatives) = AUTO, store-conditional — never a tight gate. */
    public static int clampMaxConcurrentDiskReads(int v) {
        return v <= 0 ? 0 : Math.clamp(v,
                LSSConstants.MIN_MAX_CONCURRENT_DISK_READS, LSSConstants.MAX_DISK_READER_THREADS);
    }

    /** Far-player mode normalization (E1): "off" / "opt-in" / "on"; unknown → the
     *  compiled default "off" (E1 ships INERT — E2 flips the default to "on"). Static
     *  per the R-2 registry clamp rule — the /lsslod set row (R-9) uses this exact
     *  helper. */
    public static String clampFarPlayersMode(String v) {
        if (v == null) return "off";
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "on" -> "on";
            case "opt-in", "optin", "opt_in" -> "opt-in";
            default -> "off";
        };
    }

    public static int clampFarPlayersUpdateInterval(int v) {
        return Math.clamp(v, 2, 100);
    }

    public static int clampFarPlayersMaxDistance(int v) {
        return Math.clamp(v, 128, 16384);
    }

    /** Min distance: 0 = no inner ring; clamps into [0, 16384] (validate() additionally
     *  drags it under the max — an inverted ring would hide everyone). */
    public static int clampFarPlayersMinDistance(int v) {
        return Math.clamp(v, 0, 16384);
    }

    // R-5 log-on-change (stage C): validate() re-runs on every /lsslod set, so its
    // advisory INFO lines must fire on TRANSITION, not per call — a runtime `set` would
    // otherwise re-print the dirty-0 mode line (and Paper's Folia store warn) on every
    // unrelated key change. Transient: never serialized, per-process state only.
    private transient int lastAdvisedDirtyInterval = Integer.MIN_VALUE;

    @Override
    public void validate() {
        lodDistanceChunks = clampLodDistance(lodDistanceChunks);
        lodDistanceChunksByWorld = clampLodDistanceByWorld(lodDistanceChunksByWorld);
        resolveBandwidthKeys();
        mbPerSecondLimitPerPlayer = clampMbPerPlayer(mbPerSecondLimitPerPlayer);
        // 0 = AUTO is a first-class value (the default); only a nonzero explicit override
        // clamps into the supported band — the same shape as lodStoreMaxMB.
        diskReaderThreads = diskReaderThreads <= 0 ? 0 : Math.clamp(diskReaderThreads,
                LSSConstants.MIN_DISK_READER_THREADS, LSSConstants.MAX_DISK_READER_THREADS);
        // Same 0 = AUTO shape (negative normalizes to AUTO, mirroring diskReaderThreads —
        // never to 1, which would be the TIGHTEST gate); the pool clamp applies at
        // derivation, where the resolved pool size is known.
        maxConcurrentDiskReads = clampMaxConcurrentDiskReads(maxConcurrentDiskReads);
        sendQueueLimitPerPlayer = Math.clamp(sendQueueLimitPerPlayer,
                LSSConstants.MIN_SEND_QUEUE_SIZE, LSSConstants.MAX_SEND_QUEUE_SIZE);
        // 0 = disabled is a first-class value (the default); any nonzero opt-in clamps into
        // the supported band — same shape as lodStoreMaxMB.
        mbPerSecondLimitGlobal = clampMbGlobal(mbPerSecondLimitGlobal);
        // Far players (E1): mode normalizes through the shared helper (R-2/R-9); the
        // ring stays well-formed (min dragged under max — an inverted ring hides
        // everyone); a malformed null exclude list restores the empty default.
        farPlayers = clampFarPlayersMode(farPlayers);
        farPlayersUpdateIntervalTicks = clampFarPlayersUpdateInterval(farPlayersUpdateIntervalTicks);
        farPlayersMaxDistanceBlocks = clampFarPlayersMaxDistance(farPlayersMaxDistanceBlocks);
        farPlayersMinDistanceBlocks = Math.min(
                clampFarPlayersMinDistance(farPlayersMinDistanceBlocks), farPlayersMaxDistanceBlocks);
        if (farPlayersExclude == null) farPlayersExclude = List.of();
        generationConcurrencyLimitGlobal = clampGenGlobal(generationConcurrencyLimitGlobal);
        generationTimeoutSeconds = Math.clamp(generationTimeoutSeconds, LSSConstants.MIN_GENERATION_TIMEOUT, LSSConstants.MAX_GENERATION_TIMEOUT);
        // 0 (and negative nonsense) = dirty pushes disabled — the lodStoreMaxMB idiom; only a
        // nonzero value clamps into the sending band. See the field javadoc for the semantics.
        dirtyBroadcastIntervalSeconds = clampDirtyBroadcastInterval(dirtyBroadcastIntervalSeconds);
        if (dirtyBroadcastIntervalSeconds == 0
                && lastAdvisedDirtyInterval != dirtyBroadcastIntervalSeconds) {
            // Precedent: PaperConfig's Folia store warn — the mode must be visible in the
            // log. Log-on-CHANGE (R-5, stage C): validate() re-runs on every /lsslod set.
            // (Still fires during the config suites' extreme-value clamp sweeps on the
            // first transition; harmless.)
            dev.vox.lss.common.LSSLogger.info("dirtyBroadcastIntervalSeconds is 0: dirty pushes to"
                    + " clients are DISABLED. The invalidation drain still runs every "
                    + LSSConstants.DIRTY_DRAIN_ONLY_INTERVAL_SECONDS + " s; connected clients pick"
                    + " up terrain edits only on rejoin or their own re-asks.");
        }
        lastAdvisedDirtyInterval = dirtyBroadcastIntervalSeconds;
        // Config review section 9.1: the per-player ceiling used to be MAX_CONCURRENCY_LIMIT
        // (1000) while the GLOBAL ceiling is MAX_CONCURRENT_GENERATIONS — a per-player value
        // above the fleet-wide one is unreachable by construction (a player cannot hold more
        // generation slots than exist), so it validated to silent nonsense. Clamp against the
        // configured global, which is itself already clamped on the line above.
        generationConcurrencyLimitPerPlayer = clampGenPerPlayer(
                generationConcurrencyLimitPerPlayer, generationConcurrencyLimitGlobal);
        // 0 = AUTO (derived from lodDistanceChunks); only an explicit value clamps.
        perDimensionTimestampCacheSizeMB = perDimensionTimestampCacheSizeMB <= 0 ? 0
                : Math.clamp(perDimensionTimestampCacheSizeMB,
                        LSSConstants.MIN_TIMESTAMP_CACHE_SIZE_MB, LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB);
        missMemoTtlSeconds = Math.clamp(missMemoTtlSeconds, LSSConstants.MIN_MISS_MEMO_TTL_SECONDS, LSSConstants.MAX_MISS_MEMO_TTL_SECONDS);
        lodStore = dev.vox.lss.common.store.LodStoreMode.normalize(lodStore).configValue();
        lodStoreResweepSeconds = Math.clamp(lodStoreResweepSeconds,
                LSSConstants.MIN_LOD_STORE_RESWEEP_SECONDS, LSSConstants.MAX_LOD_STORE_RESWEEP_SECONDS);
        // 0 (and negative nonsense) = uncapped — the resweepSeconds 0-means-off
        // pattern; only a nonzero opt-in cap gets the 64..1048576 floor/ceiling.
        lodStoreMaxMB = lodStoreMaxMB <= 0 ? 0 : Math.clamp(lodStoreMaxMB,
                LSSConstants.MIN_LOD_STORE_MAX_MB, LSSConstants.MAX_LOD_STORE_MAX_MB);
        lodStoreBackfillColumnsPerSecond = Math.clamp(lodStoreBackfillColumnsPerSecond,
                LSSConstants.MIN_LOD_STORE_BACKFILL_CPS, LSSConstants.MAX_LOD_STORE_BACKFILL_CPS);
        xrayObfuscation = XrayMaskPolicy.normalizeMode(xrayObfuscation);
        if (xrayHiddenBlocks == null) xrayHiddenBlocks = defaultXrayHiddenBlocks();
        xrayMaxBlockHeight = Math.clamp(xrayMaxBlockHeight, LSSConstants.MIN_XRAY_MAX_BLOCK_HEIGHT, LSSConstants.MAX_XRAY_MAX_BLOCK_HEIGHT);

        // Global Constraint #28 is GONE: no client budget derives from any server cap under
        // server-owned generation, so there is nothing to cross-clamp against the wire batch.
        // Its successor is a static inequality between constants, pinned by
        // WantSetBudgetInvariantTest: SYNC_ON_LOAD_SLOT_CAP + MAX_CONCURRENT_GENERATIONS
        //   + WANT_SET_FRONTIER_RESERVE <= WANT_SET_BUDGET <= MAX_BATCH_CHUNK_REQUESTS.
    }
}
