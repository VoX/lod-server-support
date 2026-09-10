package dev.vox.lss.common.diagnostics;

import java.util.List;

/** Server-local owner capture of published gauges, without region traversal or identities.
 * Gauges are independently sampled; capturedAtMillis is collection time, not a joint tick. */
public record ServerStatusSnapshot(int schemaVersion, long capturedAtMillis, boolean serviceAvailable,
        boolean enabled, boolean generationEnabled, boolean generationConfiguredForRestart, int lodDistanceChunks,
        long uptimeSeconds, long sentSections, long rawBytes, long wireBytes,
        long bandwidthWindowBytesPerSecond, DiagnosticVersions versions) {
    public List<String> lines() {
        return List.of("Service: " + (serviceAvailable ? "available" : "inactive"),
                "Enabled: " + enabled + "; running generation: " + generationEnabled + "; distance: " + lodDistanceChunks,
                "Generation configured for restart: " + generationConfiguredForRestart
                        + (generationEnabled != generationConfiguredForRestart ? " (restart pending)" : ""),
                "Progress: " + sentSections + " sections, " + rawBytes + " raw bytes, " + wireBytes + " wire bytes",
                "Bandwidth window: " + bandwidthWindowBytesPerSecond + " bytes/s",
                "Gauges are independently sampled; no remote client cause is inferred.");
    }
}
