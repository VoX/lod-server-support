package dev.vox.lss.common;

/**
 * Protocol constants shared between Fabric and Paper implementations.
 */
public final class LSSConstants {
    private LSSConstants() {}

    public static final String MOD_ID = "lss";

    // 18: VoxelColumn carries a one-byte serve-source tag (in_memory/disk/generation) —
    // diagnostic attribution for the client trace. Still the "v17 want-set" design line;
    // the bump makes a mismatched pair fail safe (silent no-session) instead of
    // misaligning the column decode by one byte.
    // 19: VoxelColumn carries a one-byte codec tag after the source tag
    // (COLUMN_CODEC_RAW/ZSTD — docs/planning/compressed-columns-design.md): capability
    // sessions ship the section bytes as a zstd-1 frame end-to-end instead of raw bytes
    // under netty deflate. Same fail-safe rationale as 17->18: the layout must be
    // version-agreed, the capability bit only carries ABILITY.
    // 20: cross-version identity encoding (docs/planning/cross-version-identity-encoding-plan.md):
    // the section-array body replaces global-registry-id palettes with a per-column
    // identity DICTIONARY (canonical name+properties strings, first-seen indices) and
    // has NO DIRECT mode — the bytes stop depending on the emitting server's registry
    // ids/sizes, so stored/served columns survive MC versions, mods, and datapacks.
    // Column header, framing, codec byte, and every C2S shape are UNCHANGED.
    public static final int PROTOCOL_VERSION = 20;

    // VoxelColumn serve-source tag values (one wire byte; unknown values are kept verbatim
    // client-side — same forward-safety stance as the retired response byte 0)
    public static final byte COLUMN_SOURCE_IN_MEMORY = 0;
    public static final byte COLUMN_SOURCE_DISK = 1;
    public static final byte COLUMN_SOURCE_GENERATION = 2;
    // LOD-store hit (docs/planning/lod-store-implementation-plan.md): served from the
    // store instead of a region read. Diagnostic attribution only (client /lss trace
    // src:3); pre-store clients keep the byte verbatim by the unknown-values rule.
    public static final byte COLUMN_SOURCE_STORE = 3;

    // Channel identifiers (used as Minecraft resource location strings)
    public static final String CHANNEL_HANDSHAKE = "lss:handshake_c2s";
    public static final String CHANNEL_CHUNK_REQUEST = "lss:batch_chunk_req";
    public static final String CHANNEL_SESSION_CONFIG = "lss:session_config";
    public static final String CHANNEL_DIRTY_COLUMNS = "lss:dirty_columns";
    public static final String CHANNEL_VOXEL_COLUMN = "lss:voxel_column";
    public static final String CHANNEL_BATCH_RESPONSE = "lss:batch_response";
    /** C2S sidecar carrying the client's MC data version (protocol 20, XVER §2.2): the
     *  handshake byte shape is FROZEN at two VarInts forever (a trailing byte hard-kicks
     *  the v20 client from every shipped Fabric server), so new client facts ship on
     *  their own channel — legacy servers silently discard unregistered channels, and
     *  the v20 server treats absence as "legacy client". Diagnostics + the C5 Via-guard
     *  input; never needed to decode v20 data. */
    public static final String CHANNEL_CLIENT_INFO = "lss:client_info";

    // Far players (v0.11.0 stage E1, far-player-proxies-plan.md §3.1 as amended by the
    // mega plan's R-7/R-10): capability-gated additive payloads — the server sends
    // far-player frames only to sessions that declared CAPABILITY_FAR_PLAYERS, so no
    // compat rung and no protocol bump. C2S prefs ride the CHANNEL_CLIENT_INFO sidecar
    // doctrine (legacy servers silently discard unregistered channels; the send is
    // containment-guarded client-side). Wire is MC-VERSION-NEUTRAL by construction:
    // equipment/vehicle types cross as identity strings via a per-payload dictionary
    // (the v20 pattern), never numeric registry ids.
    public static final String CHANNEL_FAR_PLAYER_PREFS = "lss:far_player_prefs";
    public static final String CHANNEL_FAR_PLAYER_ROSTER = "lss:far_player_roster";
    public static final String CHANNEL_FAR_PLAYER_UPDATES = "lss:far_player_updates";

    // Region summaries (region-summary-sync-plan.md §4): two OPTIONAL channels with
    // version-neutral RegionSummaryWire byte[] bodies — the far-player pattern. NO
    // capability bit and NO canSend discovery: the REQUEST is the capability
    // declaration, sent fire-and-forget on a CURRENT-dialect session (legacy servers
    // silently discard the unregistered channel; legacy clients never send). The
    // explicit dimension echoed both ways + drop-on-mismatch is the entire anti-stale
    // binding — stamps are only ever COMPARED client-side, never ratcheted, so a
    // same-dimension stale frame is harmless.
    public static final String CHANNEL_REGION_SUMMARY_REQ = "lss:region_summary_req";
    public static final String CHANNEL_REGION_SUMMARY = "lss:region_summary";
    // Stamped up_to_date (stamped-up-to-date-plan.md): S2C verification-stamp frames,
    // sent ONLY to sessions that sent a region-summary request this session (the
    // request is the capability declaration — released clients never request, never
    // receive). Raw byte[] bodies (ColumnStampsWire), same family as the two above.
    public static final String CHANNEL_COL_STAMPS = "lss:col_stamps";

    // Time conversion constants
    public static final long NANOS_PER_SECOND = 1_000_000_000L;
    public static final long NANOS_PER_MS = 1_000_000L;

    // Minecraft server tick rate (ticks per second)
    public static final int TICKS_PER_SECOND = 20;

    // Timeout for async disk read operations (region file reads)
    public static final int DISK_READ_TIMEOUT_SECONDS = 10;

    // Distance buffer added to lodDistanceChunks for pruning and request gating
    public static final int LOD_DISTANCE_BUFFER = 32;

    // Wire format limits
    public static final int MAX_DIRTY_COLUMN_POSITIONS = 10240;
    /** Estimated per-column wire overhead bytes (coords + dimension string + timestamp + framing). */
    public static final int ESTIMATED_COLUMN_OVERHEAD_BYTES = 45;
    /** Max serialized section bytes the CLIENT decoder accepts (hostile-frame guard; the
     *  decoder rejects — and disconnects on — anything larger). Kept above the send threshold
     *  so frames from older servers stay readable. */
    public static final int MAX_SECTIONS_SIZE = 2_097_152; // 2 MB
    /** Max serialized section bytes the SERVER will put on the wire. Strictly below the client
     *  cap AND below Minecraft's netty frame limit: Varint21LengthFieldPrepender throws (and
     *  the connection dies) for any frame over 2_097_151 bytes, and with compression disabled
     *  the frame is sections + ~60 bytes of envelope — a column admitted at the old 2 MiB cap
     *  could kill the connection in a rejoin/re-serve kick loop. 2_000_000 leaves ~97 KB of
     *  margin for envelope growth and deflate expansion of incompressible data. */
    public static final int MAX_SEND_SECTIONS_SIZE = 2_000_000;
    /** Max chars for the VoxelColumn dimension resource-location string (caps both writers and the reader). */
    public static final int MAX_DIMENSION_STRING_LENGTH = 256;

    // Config validation bounds (shared between Fabric and Paper)
    public static final int MIN_LOD_DISTANCE = 1;
    public static final int MAX_LOD_DISTANCE = 2048;
    public static final int MIN_BYTES_PER_SECOND = 1024;
    /** Per-player bandwidth ceiling. Raised 100 MB -> 1 GiB 2026-08-02 (config review
     *  section 5): the live server hit the old ceiling exactly, and this bounds only what an
     *  admin deliberately types. The DEFAULT is 25 MiB (25 -> 15 on 2026-08-05, re-raised
     *  to 25 on 2026-08-08 — ServerConfigBase.DEFAULT_MB_PER_PLAYER is the authority) —
     *  the cap charges RAW bytes because it bounds client decode work (the confirmed
     *  receiver-limited bottleneck), so wire compression did not loosen the constraint it
     *  exists to enforce. */
    public static final int MAX_BYTES_PER_SECOND_PER_PLAYER = 1_073_741_824;
    /** Disk-reader pool bounds. 0 is a first-class value ABOVE this floor — it means
     *  AUTO (see ServerConfigBase.effectiveDiskReaderThreads), so validate() only clamps
     *  nonzero values, the same 0-means-derive shape as lodStoreMaxMB. */
    public static final int MIN_DISK_READER_THREADS = 1;
    public static final int MIN_SEND_QUEUE_SIZE = 1;
    public static final int MAX_SEND_QUEUE_SIZE = 100_000;
    /** AUTO disk-reader pool size where no real read priority exists — vanilla's
     *  single-threaded IOWorker (LSS concurrency IS the vanilla-delay tradeoff there) and the
     *  chunk-IO-overhaul fallback (the AdaptiveReadThrottle owns the real limit; this is a
     *  ceiling). Also the FLOOR of the prioritized tier, so a 2-core box never drops below it. */
    public static final int AUTO_DISK_READER_THREADS_SHARED_WORKER = 3;
    /** AUTO ceiling where Moonrise Priority.LOW defers to gameplay independently of
     *  concurrency (all Paper/Folia, Fabric+Moonrise): the limit is parse/serialize CPU, so
     *  scale with cores but stop well short of starving the rest of the server. */
    public static final int AUTO_DISK_READER_THREADS_PRIORITIZED_MAX = 8;
    public static final int MAX_DISK_READER_THREADS = 64;
    /** Disk-read concurrency gate floor (disk-read-concurrency-gate-plan.md). 0 is a
     *  first-class value above it — AUTO, see
     *  ServerConfigBase.effectiveMaxConcurrentDiskReads — so validate() clamps only
     *  nonzero values; the ceiling rides MAX_DISK_READER_THREADS (a K above the pool is
     *  structurally a no-op, the documented OFF idiom). */
    public static final int MIN_MAX_CONCURRENT_DISK_READS = 1;
    /** AUTO K on a STORE-ARMED server = ceil(pool / this): ≤ half the reader pool runs
     *  the expensive NBT phase (region read → inflate → parse → transcode → compress) at
     *  any instant, reserving the other half for ~44 µs store-hit serves — the structural
     *  fix for "cold-region reads starve the cheap store rung on the shared pool". With
     *  no store attached AUTO = pool (a no-op gate): no cheap path exists to protect and
     *  half-pooling would hand store-off servers pure downside on exactly the workloads
     *  where disk reads dominate (both gate reviews' convergent MAJOR). */
    public static final int AUTO_DISK_READ_GATE_DIVISOR = 2;
    /** Global bandwidth ceiling bound — same 1 GiB rationale as the per-player bound
     *  above (bounds only what an admin deliberately types). */
    public static final long MAX_BYTES_PER_SECOND_GLOBAL_LIMIT = 1_073_741_824;
    public static final int MIN_CONCURRENT_GENERATIONS = 1;
    /** Global generation ceiling. Raised 256 -> 512 2026-08-02: WantSetBudgetInvariantTest
     *  requires SYNC_ON_LOAD_SLOT_CAP(200) + this + WANT_SET_FRONTIER_RESERVE(64) <=
     *  WANT_SET_BUDGET(800), so the true ceiling is 536 and the headroom was unused. This
     *  is the CEILING only — the defaults are unchanged (no measurement justifies raising
     *  them, and on Fabric there is no priority hand-off or MSPT gate under worldgen). */
    public static final int MAX_CONCURRENT_GENERATIONS = 512;
    /** Generation order-spread gate: a NEW generation ticket may enter at most this many
     *  Chebyshev rings (measured from the player's declared want-set center) beyond that
     *  player's OLDEST outstanding ticket. The platform scheduler owns completion order and
     *  chunk-system rewrites (C2ME) do not complete equal-priority tickets FIFO — without
     *  this bound the per-slot refill keeps feeding newer/farther tickets on top of a
     *  starving near one, and the world fills in far-before-near (2026-07-16 live incident).
     *  A gated miss takes the standard transient drop (superseded + miss_dropped) and heals
     *  by re-declaration once the head resolves. 2 rings ≈ the per-player concurrency cap's
     *  natural span near the player, so a FIFO-completing platform (vanilla) never hits it. */
    public static final int MAX_GENERATION_RING_SPREAD = 2;
    public static final int MIN_GENERATION_TIMEOUT = 1;
    public static final int MAX_GENERATION_TIMEOUT = 600;
    public static final int MIN_DIRTY_BROADCAST_INTERVAL = 1;
    public static final int MAX_DIRTY_BROADCAST_INTERVAL = 300;
    /** Drain cadence when dirty SENDS are disabled (dirtyBroadcastIntervalSeconds = 0):
     *  the broadcasters still drain the tracker and run the invalidation fan-out (store
     *  rows, timestamp cache, in-flight taints, per-player done-bit/probe-stamp clears)
     *  on this interval — only the DirtyColumnsS2CPayload send is gated off. Matches the
     *  field's default so "off" costs the same server-side as the default cadence. */
    public static final int DIRTY_DRAIN_ONLY_INTERVAL_SECONDS = 10;
    public static final int MIN_CONCURRENCY_LIMIT = 1;
    // MAX_CONCURRENCY_LIMIT (1000) DELETED 2026-08-13 (deletion review D-6): the 9.1
    // config-review fix replaced its clamp role with clampGenPerPlayer(v, configuredGlobal)
    // — the constant enforced nothing and its two remaining test assertions pinned a
    // number no mechanism used.
    /** Per-DIMENSION timestamp-cache bounds (multiply by dimension count for the real heap
     *  budget). Ceiling raised 256 -> 512 2026-08-02: reachable on a large-distance server.
     *  0 means AUTO — derived from lodDistanceChunks, see
     *  ServerConfigBase.effectiveTimestampCacheMB — so validate() clamps only nonzero. */
    public static final int MIN_TIMESTAMP_CACHE_SIZE_MB = 1;
    public static final int MAX_TIMESTAMP_CACHE_SIZE_MB = 512;
    /** The tile cache's per-COLUMN heap budget for AUTO sizing (D0,
     *  timestamp-cache-tile-redesign.md §5: a full tile costs ~4.25 B/column, so 5 is
     *  honest for fills of ~85% and above; at 75% fill the true figure is ~5.67 B),
     *  used by ServerConfigBase.effectiveTimestampCacheMB. Replaced the per-entry 64 B
     *  model when the two parallel hash maps became flat tiles; the honest-fill bound
     *  is pinned by autoSizingPerColumnConstantCoversTheRealTileCost. */
    public static final int TIMESTAMP_CACHE_HEAP_BYTES_PER_COLUMN = 5;
    /** AUTO provisions this multiple of the lodDistance disc's area (D0, user decision
     *  2026-08-08): part of the tile win is spent on COVERAGE (~5.3x the old tracked
     *  columns) so roaming players and multi-player spread stop thrashing eviction,
     *  rather than returning all of it as RAM. */
    public static final double TIMESTAMP_CACHE_AUTO_COVERAGE_FACTOR = 8.0;

    /** Miss-memo TTL clamp (docs/planning/miss-memo-design.md): 0 disables the memo
     *  wholesale (the config kill switch). The ceiling bounds the staleness window — the
     *  memo's falsifying hooks are known incomplete, so a stale entry may delay service by
     *  up to the TTL. */
    public static final int MIN_MISS_MEMO_TTL_SECONDS = 0;
    public static final int MAX_MISS_MEMO_TTL_SECONDS = 60;

    // LOD-store on-disk size cap (Phase 5 eviction): oldest-ts rows are batch-evicted
    // and pages returned via incremental_vacuum once db+wal exceed the cap. 0 =
    // UNCAPPED (the default since store-cap-behavior-plan.md); the 64-floor applies
    // only to a nonzero opt-in cap — a tiny accidental cap would evict constantly.
    // Ceiling raised 32 GB -> 1 TB 2026-08-02: this bounds the ADMIN'S OWN disk, opted
    // into deliberately, and a Chunky-pregenerated world's store can exceed 32 GB —
    // where an artificial ceiling silently turns an intentional cap into a treadmill.
    public static final int MIN_LOD_STORE_MAX_MB = 64;
    public static final int MAX_LOD_STORE_MAX_MB = 1_048_576;

    public static final int MIN_LOD_STORE_RESWEEP_SECONDS = 0;
    public static final int MAX_LOD_STORE_RESWEEP_SECONDS = 3600;

    /** Backfill rate-cap clamp (docs/planning/store-backfill-tuning-plan.md §1): below
     *  ~10 col/s the walk cannot finish a large world in useful time (worse than off —
     *  it holds the thread and the hasRow probes for nothing); above ~1000 the
     *  single-threaded synchronous read (~1-2 ms healthy) is the limiter anyway and the
     *  value would only mislead. */
    public static final int MIN_LOD_STORE_BACKFILL_CPS = 10;
    public static final int MAX_LOD_STORE_BACKFILL_CPS = 1000;
    /** Backfill tick-health ceiling (smoothed MSPT): the walk pauses above this. RETIRED
     *  as config 2026-08-02 (config review section 6) — its clamp band was 20..50 and its own
     *  comment conceded that >= 50 never pauses and <= 20 never runs on a busy server, so a
     *  30-unit window with two degenerate ends is not a tuning range. Now a constant. */
    public static final int LOD_STORE_BACKFILL_TICK_CEILING_MS = 45;

    /** X-ray mask height clamp (docs/planning/antixray-compat-design.md §3): comfortably
     *  outside any MC build height so any real world Y is expressible, while bounding
     *  config nonsense. */
    public static final int MIN_XRAY_MAX_BLOCK_HEIGHT = -2048;
    public static final int MAX_XRAY_MAX_BLOCK_HEIGHT = 2048;

    /** Generation cohort span for memo-path pacing ("generation never overtakes nearer
     *  in-flight work"): a memo escalation may enter at most this many rings beyond the
     *  NEAREST outstanding generation ticket. Restores the ring-by-ring completion wave
     *  the pre-memo read-pipeline pacing produced implicitly — without it, instant memo
     *  refill pins the full generation cap in flight across the whole frontier+spread
     *  window and completions scramble across 3 rings (the inversion regression). */
    public static final int MAX_GENERATION_COHORT_SPAN = 1;

    /** Frontier outward damping (movement inversion fix, 2026-07-19 admission-trace
     *  diagnosis): the spread-gate's live-frontier reference follows INWARD observations
     *  instantly but may only advance OUTWARD at one ring per this many milliseconds
     *  (~3 rings/s). Under movement the honest frontier is non-monotonic — it jumps to
     *  the far edge at instants when the near field is momentarily all-satisfied, then
     *  collapses back as movement mints new near work; far waves admitted at those
     *  instants complete AFTER the newly-minted near work and pop as far-then-near
     *  "line inversions" when the player stops. Damping keeps the reference from
     *  reaching the far edge between mints; a stationary frontier advances ring-by-ring
     *  within the +2 spread window and never waits on it. 0 disables (test rigs). */
    public static final long FRONTIER_OUTWARD_DAMP_MILLIS_PER_RING = 333;

    /** Duplicate-serve grace (docs/planning/duplicate-serve-grace.md): a ts&le;0
     *  re-declaration arriving within this window of its column payload leaving the send
     *  queue (send success only) is silently skipped instead of honestly re-resolved —
     *  during cold backfill ~7-8% of asks cross their own payload's delivery in flight
     *  (crossings &asymp; departure_rate &times; client&rarr;server latency per scan — a
     *  RATE, so the client's adaptive cadence (up to 4 Hz) scales the absorbed skips
     *  with it) and each such re-resolve costs a redundant disk read + send. The skip
     *  keeps the done-bit and answers NOTHING (never up_to_date — the client claims no
     *  data), so a genuinely lost payload heals at most one scan past grace expiry —
     *  &le;750 ms at the client's 250 ms fast floor, &le;~1.5 s at the 1 Hz fallback —
     *  when the clear-and-re-resolve ladder applies unchanged. Tuning is asymmetric:
     *  shorter than the RTT+tick window buys nothing; longer only stretches that bounded
     *  heal delay. 0 disables (no stamps are written). */
    public static final long SEND_DEPARTURE_GRACE_MILLIS = 500;
    public static final int MAX_BATCH_CHUNK_REQUESTS = 1024;
    public static final int MAX_BATCH_RESPONSES = 4096;

    // Want-set frontier headroom (the Global Constraint #28 successor). The client
    // re-declares every unanswered position each scan, so the worst-case per-player
    // in-flight set (SYNC_ON_LOAD_SLOT_CAP disk slots + at most MAX_CONCURRENT_GENERATIONS
    // escalated generations — per-player gen can never exceed the global ceiling) competes
    // for the same WANT_SET_BUDGET as newly discovered frontier positions. The reserve is
    // the guaranteed frontier share; WantSetBudgetInvariantTest pins the inequality
    // (slot cap + gen ceiling + reserve <= budget <= one wire batch) so any constant drift
    // re-derives the boundary at build time instead of starving discovery at runtime.
    public static final int WANT_SET_FRONTIER_RESERVE = 64;

    // The client's single want-set budget: max positions the scanner declares per scan.
    // A fixed constant — under server-owned generation NO client budget derives from any
    // server cap (the concurrency caps left the wire). 800 preserves the historic effective
    // volume (old syncCap 200 x the old scan-budget multiplier).
    public static final int WANT_SET_BUDGET = 800;

    // Server per-player SYNC (disk-read) slot cap. Formerly the config field
    // syncOnLoadConcurrencyLimitPerPlayer; now a constant. At the DEFAULT pool size its
    // admission role is shadowed by the shared disk-pool hasHeadroom() gate (5 threads ->
    // ~165 depth < 200); a larger diskReaderThreads config makes this constant the binding
    // per-player limiter — deliberate: one fixed fairness ceiling instead of a second
    // coupled knob (the retired key is release-noted). Router retain semantics unchanged
    // (SLOT_FULL retain-keep-scanning vs NO_DISK_HEADROOM retain-stop).
    public static final int SYNC_ON_LOAD_SLOT_CAP = 200;

    // Adaptive read-throttle (Approach B) target latency in ms: the EWMA submit->result setpoint
    // the AIMD controller steers toward. At or below it the read-concurrency limit grows; above it
    // (a proxy for the shared IO being busy) it shrinks. Engaged ONLY as the Fabric fallback when a
    // chunk-IO-overhaul mod (C2ME et al.) makes the IOWorker background-priority path unavailable —
    // see AbstractChunkDiskReader.enableAdaptiveThrottleFallback + ChunkDiskReader. A constant, not
    // a config field: the fallback is automatic and needs no user knob.
    public static final int ADAPTIVE_READ_TARGET_LATENCY_MS = 20;

    // Batch response type tags. Byte 0 was RESPONSE_RATE_LIMITED through v16 — retired by
    // the v17 want-set model (a full slot retains the want in the server's backlog instead of
    // bouncing it) and RESERVED: never reuse 0, and keep 1/2 stable (wire-parity fixtures pin
    // the bytes). The client skips unknown types inert, so a reserved byte is forward-safe.
    public static final byte RESPONSE_UP_TO_DATE = 1;
    public static final byte RESPONSE_NOT_GENERATED = 2;
    /** v16-COMPAT ONLY (see docs/planning/v16-compat-design.md): the retired byte 0, spoken
     *  exclusively to legacy protocol-16 sessions as the shim's overflow bounce — the old
     *  client backs off ~1 s and retries. Never sent to a v17+ client (byte 0 stays reserved
     *  there); never any other v16-session pressure signal (retention + re-declare cover those). */
    public static final byte RESPONSE_RATE_LIMITED_V16 = 0;

    /** The legacy protocol version the v16 compat shim serves. A client handshaking with this
     *  version (and {@code enableV16Compat}) gets a translated session: 6-field SessionConfig
     *  echoing 16, source-less VoxelColumn frames, and the synthetic want-set recordkeeping. */
    public static final int V16_COMPAT_PROTOCOL_VERSION = 16;

    /** The protocol-18 compat rung (docs/planning/v18-compat-design.md): a v0.7.x–v0.8.x
     *  client handshaking 18 (and {@code enableV18Compat}) gets a NATIVE session — the wire
     *  is the current one minus the codec byte: SessionConfig echoes 18 (the old client's
     *  gate hard-requires it), columns are forced codec-RAW and ship WITHOUT the codec tag
     *  (the v18 decode would consume it as the section-array length VarInt — hard kick),
     *  and C2S is byte-identical, so the want-set pipeline never learns the session is
     *  legacy. Membership only ({@code WireDialectTracker}) — no v16-style synthetic
     *  want-set. */
    public static final int V18_COMPAT_PROTOCOL_VERSION = 18;

    /** The protocol-19 compat rung (v0.9.x–v0.10.x-pre clients, {@code enableV19Compat}):
     *  a NATIVE session at the current header/framing — SessionConfig echoes 19, C2S is
     *  byte-identical — but the section-array BODY must be the legacy global-id layout,
     *  which is a per-serve TRANSLATION (v20 dictionary body → native palettes), not a
     *  header splice like v18/v16. Membership rides {@code WireDialectTracker}. */
    public static final int V19_COMPAT_PROTOCOL_VERSION = 19;

    // Capabilities bitmask
    public static final int CAPABILITY_VOXEL_COLUMNS = 1;
    /** Client can decode zstd-framed column payloads (declared only when its zstd native
     *  probe succeeds — see the client-side StoreCodec.zstdOrNull holder). The bit carries
     *  ABILITY only; the v19 layout (codec byte present) is version-agreed regardless. */
    public static final int CAPABILITY_ZSTD_COLUMNS = 2;
    /** Far players (E1): the client wants far-player roster/update frames. The handshake
     *  gate MASKS unknown bits ({@code HandshakeGate} — the in-repo precedent the FARP
     *  review verified), so this bit at an older server is silently ignored and the
     *  session registers normally: no compat rung, no version bump. INERT at E1 — the
     *  client-side composition compiles the bit OFF until E2 flips the defaults. */
    public static final int CAPABILITY_FAR_PLAYERS = 4;
    /** Map policy (0.19.0, map-policy plan A2): "I honor the server's map-ingestion
     *  policy" — the client obeys {@link #SESSION_FLAG_DENY_MAP_INGESTION}. Declared
     *  unconditionally by 0.19+ clients (with no map mod, honoring is trivial) at every
     *  discovery rung. Every server back to v0.2.1 only tests specific bits, so an old
     *  server ignores it; a server with {@code privacy.request_no_map_ingestion} armed
     *  serves only sessions that are CURRENT-dialect and carry it. */
    public static final int CAPABILITY_MAP_POLICY = 8;

    /** The v20 SessionConfig's trailing {@code sessionFlags} VarInt (map-policy plan A2):
     *  appended after {@code serverDataVersion} ONLY for a version-20, non-v16 frame to a
     *  session that declared {@link #CAPABILITY_MAP_POLICY}. Unknown bits are ignored. */
    public static final int SESSION_FLAG_DENY_MAP_INGESTION = 0x1;
    /** Why an answer says {@code enabled=false} (0.23.0 plan §3). At most one of the three is
     *  set, only together with {@code enabled=false}, and none means "not said" (an older
     *  server, or an operator who keeps the permission reason private). Clients from 0.19.0
     *  to 0.22.0 test bit 0 only, so these are invisible to them.
     *  This one: the service is switched off on this server. */
    public static final int SESSION_FLAG_OFF_SERVER = 0x2;
    /** This world's distance is 0 (the session is registered; other worlds may serve). */
    public static final int SESSION_FLAG_OFF_WORLD = 0x4;
    /** The server requires a permission this player does not hold, and its operator lets the
     *  player be told ({@code service.show_permission_refusal}). */
    public static final int SESSION_FLAG_OFF_PERMISSION = 0x8;
    /** The three reason bits. */
    public static final int SESSION_FLAG_OFF_REASONS =
            SESSION_FLAG_OFF_SERVER | SESSION_FLAG_OFF_WORLD | SESSION_FLAG_OFF_PERMISSION;
    /** {@code sessionFlags} sentinel: the field is absent from the frame (the encoder omits
     *  it; the client decodes absence as "unchanged" and keeps the connection's value). */
    public static final int SESSION_FLAGS_ABSENT = -1;

    // VoxelColumn codec tag values (one wire byte, protocol 19+, after the source tag).
    // Unlike the source tag, unknown values are NOT passed through verbatim client-side:
    // the codec byte changes how the section bytes must be read, so the decode drain
    // treats anything outside {0,1} as a decode failure (ingest-failure re-serve).
    public static final byte COLUMN_CODEC_RAW = 0;
    public static final byte COLUMN_CODEC_ZSTD = 1;

    /** Columns whose raw section bytes are below this never compress (codec 0): the frame
     *  header wins nothing on tiny bodies and the 0-section clear must stay raw (the
     *  client's clear detection reads the leading varint without a decompress). A safety
     *  floor, not corpus-tuned — the Phase 0 corpus (compressed-columns-design.md §10)
     *  contains no real column under 4 KiB; ColumnBytes' non-shrinking fallback covers
     *  everything else. */
    public static final int COLUMN_COMPRESS_MIN_BYTES = 512;

    // Dimension resource location strings (common/ has no MC deps, so plain strings)
    // Test-convenience literals only (deletion review D-13) — nothing on the wire or in
    // production reads these; they live here for the shared test corpus, not the protocol.
    public static final String DIM_STR_OVERWORLD = "minecraft:overworld";
    public static final String DIM_STR_THE_NETHER = "minecraft:the_nether";
    public static final String DIM_STR_THE_END = "minecraft:the_end";

    /** Current time as epoch seconds (protocol timestamp granularity). */
    public static long epochSeconds() {
        return System.currentTimeMillis() / 1000;
    }

}
