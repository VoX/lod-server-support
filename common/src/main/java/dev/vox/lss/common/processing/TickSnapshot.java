package dev.vox.lss.common.processing;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tick state snapshot built on the main thread, consumed by the processing thread.
 * The maps are allocated fresh each tick and ownership transfers at
 * {@link OffThreadProcessor#postSnapshot}: the main thread never touches them again,
 * so the processing thread can iterate without synchronization.
 *
 * <p>Snapshot state is latest-wins (an unconsumed snapshot is simply replaced by the
 * next tick's); lossless events (generation outcomes, removals, invalidations,
 * dirty-clears) travel through the {@link OffThreadProcessor} mailbox instead.
 */
public record TickSnapshot(
        Map<UUID, String> playerDimensions,
        Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes,
        int maxSendQueueSize,
        boolean shutdown
) {
    /**
     * A generation outcome for one player. {@code columnData == null} means the chunk was
     * not generated; {@code transientFailure} then picks the disposition: {@code false} is
     * PERMANENT unservability (extraction error, failed load) and answers ColumnNotGenerated —
     * a session-permanent "stop asking" on the client; {@code true} is transient pressure
     * (timeout, capacity rejection) and is dropped silently, counted superseded — the client's
     * next want-set declaration retries it. {@code missDropped} marks the capacity-reject
     * flavor of a transient outcome: the disk miss behind it produced NEITHER a generation
     * submission NOR a wire answer, so it must count into the {@code miss_dropped} term of
     * soak law A5 (a timeout outcome must NOT — its submission already balanced the miss).
     * Both flags are meaningless when {@code columnData != null}.
     */
    public record GenerationReadyData(
            UUID playerUuid,
            int cx,
            int cz,
            String dimension,
            LoadedColumnData columnData,
            long columnTimestamp,
            long submissionOrder,
            boolean transientFailure,
            boolean missDropped
    ) {
        /** Success outcomes and permanent failures — both flags false. */
        public GenerationReadyData(UUID playerUuid, int cx, int cz, String dimension,
                                   LoadedColumnData columnData, long columnTimestamp,
                                   long submissionOrder) {
            this(playerUuid, cx, cz, dimension, columnData, columnTimestamp, submissionOrder,
                    false, false);
        }

        /** Timeout/extraction outcomes — the generation WAS submitted, so never a miss-drop. */
        public GenerationReadyData(UUID playerUuid, int cx, int cz, String dimension,
                                   LoadedColumnData columnData, long columnTimestamp,
                                   long submissionOrder, boolean transientFailure) {
            this(playerUuid, cx, cz, dimension, columnData, columnTimestamp, submissionOrder,
                    transientFailure, false);
        }
    }

    /** Shutdown sentinel — tells the processing thread to exit. */
    public static TickSnapshot shutdownSentinel() {
        return new TickSnapshot(Map.of(), Map.of(), 0, true);
    }

    /**
     * Group generation outcomes' packed positions by player, for skipping those positions
     * in the loaded-chunk probe pass. Returns null when there is nothing to group.
     */
    public static Map<UUID, LongOpenHashSet> groupPositionsByPlayer(List<GenerationReadyData> generationReady) {
        if (generationReady.isEmpty()) return null;
        Map<UUID, LongOpenHashSet> grouped = new HashMap<>();
        for (var genData : generationReady) {
            grouped.computeIfAbsent(genData.playerUuid(), k -> new LongOpenHashSet())
                    .add(PositionUtil.packPosition(genData.cx(), genData.cz()));
        }
        return grouped;
    }
}
