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
    public static final int PROTOCOL_VERSION = 18;

    // VoxelColumn serve-source tag values (one wire byte; unknown values are kept verbatim
    // client-side — same forward-safety stance as the retired response byte 0)
    public static final byte COLUMN_SOURCE_IN_MEMORY = 0;
    public static final byte COLUMN_SOURCE_DISK = 1;
    public static final byte COLUMN_SOURCE_GENERATION = 2;

    // Channel identifiers (used as Minecraft resource location strings)
    public static final String CHANNEL_HANDSHAKE = "lss:handshake_c2s";
    public static final String CHANNEL_CHUNK_REQUEST = "lss:batch_chunk_req";
    public static final String CHANNEL_SESSION_CONFIG = "lss:session_config";
    public static final String CHANNEL_DIRTY_COLUMNS = "lss:dirty_columns";
    public static final String CHANNEL_VOXEL_COLUMN = "lss:voxel_column";
    public static final String CHANNEL_BATCH_RESPONSE = "lss:batch_response";

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
    public static final int MAX_BYTES_PER_SECOND_PER_PLAYER = 104_857_600;
    public static final int MIN_DISK_READER_THREADS = 1;
    public static final int MAX_DISK_READER_THREADS = 64;
    public static final int MIN_SEND_QUEUE_SIZE = 1;
    public static final int MAX_SEND_QUEUE_SIZE = 100_000;
    public static final long MAX_BYTES_PER_SECOND_GLOBAL_LIMIT = 1_073_741_824;
    public static final int MIN_CONCURRENT_GENERATIONS = 1;
    public static final int MAX_CONCURRENT_GENERATIONS = 256;
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
    public static final int MIN_CONCURRENCY_LIMIT = 1;
    public static final int MAX_CONCURRENCY_LIMIT = 1000;
    public static final int MIN_TIMESTAMP_CACHE_SIZE_MB = 1;
    public static final int MAX_TIMESTAMP_CACHE_SIZE_MB = 256;
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

    // Capabilities bitmask
    public static final int CAPABILITY_VOXEL_COLUMNS = 1;

    // Dimension resource location strings (common/ has no MC deps, so plain strings)
    public static final String DIM_STR_OVERWORLD = "minecraft:overworld";
    public static final String DIM_STR_THE_NETHER = "minecraft:the_nether";
    public static final String DIM_STR_THE_END = "minecraft:the_end";

    /** Current time as epoch seconds (protocol timestamp granularity). */
    public static long epochSeconds() {
        return System.currentTimeMillis() / 1000;
    }

}
