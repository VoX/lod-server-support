package dev.vox.lss.common.processing;

/**
 * Snapshot of a loaded chunk column's pre-serialized section data.
 * Built on the server thread (platform-specific serialization), consumed on the processing thread.
 *
 * @param cx                 chunk X coordinate
 * @param cz                 chunk Z coordinate
 * @param serializedSections section bytes in MC-native wire format
 * @param estimatedBytes     estimated wire size
 * @param probeCapture       pre-serialization authority; null only for ordinary disk/generation data
 *                           (unbound data is never an eligible loaded-probe hit)
 */
public record LoadedColumnData(
        int cx, int cz,
        byte[] serializedSections,
        int estimatedBytes,
        LoadedProbeGuard.Capture probeCapture
) {
    /** Ordinary disk/generation data has no loaded-probe authority. */
    public LoadedColumnData(int cx,int cz,byte[] serializedSections,int estimatedBytes) {
        this(cx,cz,serializedSections,estimatedBytes,null);
    }
}
