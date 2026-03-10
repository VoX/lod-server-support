package dev.vox.lss.common.processing;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable snapshot built on the main thread each tick, consumed by the processing thread.
 * Contains all server-thread-only data needed for off-thread request processing.
 */
public record TickSnapshot(
        Map<UUID, PlayerTickData> players,
        Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes,
        List<GenerationReadyData> generationReady,
        List<UUID> removedPlayers,
        int maxSendQueueSize,
        boolean shutdown
) {
    /** Per-player snapshot of server-thread-only data. */
    public record PlayerTickData(
            String dimension,
            boolean dimensionChanged
    ) {}

    /** Generation service: extracted data for chunks that completed generation. */
    public record GenerationReadyData(
            UUID playerUuid,
            int requestId,
            LoadedColumnData columnData,
            long columnTimestamp,
            long submissionOrder
    ) {}

    /** Shutdown sentinel — tells the processing thread to exit. */
    public static TickSnapshot shutdownSentinel() {
        return new TickSnapshot(
                Map.of(), Map.of(), List.of(), List.of(),
                0, true
        );
    }
}
