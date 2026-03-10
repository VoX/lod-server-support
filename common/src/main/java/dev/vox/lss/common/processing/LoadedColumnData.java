package dev.vox.lss.common.processing;

/**
 * Snapshot of a loaded chunk column's pre-serialized section data.
 * Built on the server thread (platform-specific serialization), consumed on the processing thread.
 *
 * @param cx                 chunk X coordinate
 * @param cz                 chunk Z coordinate
 * @param serializedSections section bytes in MC-native wire format
 * @param estimatedBytes     estimated wire size
 */
public record LoadedColumnData(
        int cx, int cz,
        byte[] serializedSections,
        int estimatedBytes
) {}
