package dev.vox.lss.common;

/**
 * Protocol constants shared between Fabric and Paper implementations.
 */
public final class LSSConstants {
    private LSSConstants() {}

    public static final String MOD_ID = "lss";

    public static final int PROTOCOL_VERSION = 15;

    // Channel identifiers (used as Minecraft resource location strings)
    public static final String CHANNEL_HANDSHAKE = "lss:handshake_c2s";
    public static final String CHANNEL_CHUNK_REQUEST = "lss:batch_chunk_req";
    public static final String CHANNEL_SESSION_CONFIG = "lss:session_config";
    public static final String CHANNEL_DIRTY_COLUMNS = "lss:dirty_columns";
    public static final String CHANNEL_VOXEL_COLUMN = "lss:voxel_column";
    public static final String CHANNEL_BATCH_RESPONSE = "lss:batch_response";
    public static final String CHANNEL_CANCEL_REQUEST = "lss:cancel_request";
    public static final String CHANNEL_BANDWIDTH_UPDATE = "lss:bandwidth_update";

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
    /** Estimated per-column wire overhead bytes (requestId + coord + timestamp + framing). */
    public static final int ESTIMATED_COLUMN_OVERHEAD_BYTES = 25;

    // Config validation bounds (shared between Fabric and Paper)
    public static final int MIN_LOD_DISTANCE = 1;
    public static final int MAX_LOD_DISTANCE = 512;
    public static final int MIN_BYTES_PER_SECOND = 1024;
    public static final int MAX_BYTES_PER_SECOND_PER_PLAYER = 104_857_600;
    public static final int MIN_DISK_READER_THREADS = 1;
    public static final int MAX_DISK_READER_THREADS = 64;
    public static final int MIN_SEND_QUEUE_SIZE = 1;
    public static final int MAX_SEND_QUEUE_SIZE = 100_000;
    public static final long MAX_BYTES_PER_SECOND_GLOBAL_LIMIT = 1_073_741_824;
    public static final int MIN_CONCURRENT_GENERATIONS = 1;
    public static final int MAX_CONCURRENT_GENERATIONS = 256;
    public static final int MIN_GENERATION_TIMEOUT = 1;
    public static final int MAX_GENERATION_TIMEOUT = 600;
    public static final int MIN_DIRTY_BROADCAST_INTERVAL = 1;
    public static final int MAX_DIRTY_BROADCAST_INTERVAL = 300;
    public static final int MIN_RATE_LIMIT = 1;
    public static final int MAX_RATE_LIMIT = 1000;
    public static final int MIN_CONCURRENCY_LIMIT = 1;
    public static final int MAX_CONCURRENCY_LIMIT = 1000;
    public static final int MIN_TIMESTAMP_CACHE_SIZE_MB = 1;
    public static final int MAX_TIMESTAMP_CACHE_SIZE_MB = 256;
    public static final int MAX_BATCH_CHUNK_REQUESTS = 1024;
    public static final int MAX_BATCH_RESPONSES = 4096;

    // Batch response type tags
    public static final byte RESPONSE_RATE_LIMITED = 0;
    public static final byte RESPONSE_UP_TO_DATE = 1;
    public static final byte RESPONSE_NOT_GENERATED = 2;

    // Capabilities bitmask
    public static final int CAPABILITY_VOXEL_COLUMNS = 1;

    // Dimension ordinals for compact wire encoding (v8+)
    public static final int DIM_OVERWORLD = 0;
    public static final int DIM_THE_NETHER = 1;
    public static final int DIM_THE_END = 2;
    public static final int DIM_CUSTOM = -1;

    // Dimension resource location strings (common/ has no MC deps, so plain strings)
    public static final String DIM_STR_OVERWORLD = "minecraft:overworld";
    public static final String DIM_STR_THE_NETHER = "minecraft:the_nether";
    public static final String DIM_STR_THE_END = "minecraft:the_end";

    /** Current time as epoch seconds (protocol timestamp granularity). */
    public static long epochSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    /** Map a dimension resource location string to a wire ordinal. */
    public static int dimensionStringToOrdinal(String dimStr) {
        return switch (dimStr) {
            case DIM_STR_OVERWORLD -> DIM_OVERWORLD;
            case DIM_STR_THE_NETHER -> DIM_THE_NETHER;
            case DIM_STR_THE_END -> DIM_THE_END;
            default -> DIM_CUSTOM;
        };
    }

}
