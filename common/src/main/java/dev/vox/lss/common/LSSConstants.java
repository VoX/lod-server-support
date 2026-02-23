package dev.vox.lss.common;

/**
 * Protocol constants shared between Fabric and Paper implementations.
 */
public final class LSSConstants {
    private LSSConstants() {}

    public static final int PROTOCOL_VERSION = 5;

    // Channel identifiers (used as Minecraft resource location strings)
    public static final String CHANNEL_HANDSHAKE = "lss:handshake_c2s";
    public static final String CHANNEL_CHUNK_REQUEST = "lss:chunk_request";
    public static final String CHANNEL_CANCEL_REQUEST = "lss:cancel_request";
    public static final String CHANNEL_SESSION_CONFIG = "lss:session_config";
    public static final String CHANNEL_CHUNK_SECTION = "lss:chunk_section";
    public static final String CHANNEL_REQUEST_COMPLETE = "lss:request_complete";
    public static final String CHANNEL_COLUMN_UP_TO_DATE = "lss:column_up_to_date";
    public static final String CHANNEL_DIRTY_COLUMNS = "lss:dirty_columns";

    // Wire format limits
    public static final int MAX_CHUNK_REQUEST_POSITIONS = 1024;
    public static final int MAX_CANCEL_BATCH_IDS = 256;
    public static final int MAX_DIRTY_COLUMN_POSITIONS = 4096;
    public static final int MAX_SECTION_DATA_SIZE = 1_048_576;

    // Status codes for RequestComplete payload
    public static final int STATUS_DONE = 0;
    public static final int STATUS_CANCELLED = 1;
    public static final int STATUS_REJECTED = 2;
}
