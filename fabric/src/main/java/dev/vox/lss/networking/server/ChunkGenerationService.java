package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.TickSnapshot;
import dev.vox.lss.config.LSSServerConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ChunkGenerationService {
    // flags=2 (LOADING) makes the chunk load/generate; timeout=0 means we manage lifetime ourselves
    private static final TicketType LSS_GEN_TICKET = new TicketType(0, 2);

    record GenerationCallback(UUID playerUuid, int requestId, long submissionOrder) {}

    private record PendingGenerationKey(ResourceKey<Level> dimension, int cx, int cz) {}

    static class PendingGeneration {
        final ChunkPos pos;
        final ServerLevel level;
        final List<GenerationCallback> callbacks = new ArrayList<>();
        int ticksWaiting = 0;

        PendingGeneration(ChunkPos pos, ServerLevel level) {
            this.pos = pos;
            this.level = level;
        }
    }

    private final LinkedHashMap<PendingGenerationKey, PendingGeneration> active = new LinkedHashMap<>();
    private final Map<UUID, Integer> perPlayerActiveCount = new HashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<ChunkDiskReader.ReadResult>> playerResults = new ConcurrentHashMap<>();

    private final int maxConcurrent;
    private final int maxPerPlayerActive;
    private final int timeoutTicks;

    // Volatile is sufficient — only written from the main tick thread, read by /stats commands.
    private volatile long totalSubmitted = 0;
    private volatile long totalCompleted = 0;
    private volatile long totalTimeouts = 0;

    public ChunkGenerationService(LSSServerConfig config) {
        this.maxConcurrent = config.generationConcurrencyLimitGlobal;
        this.maxPerPlayerActive = config.generationConcurrencyLimitPerPlayer;
        this.timeoutTicks = config.generationTimeoutSeconds * LSSConstants.TICKS_PER_SECOND;
    }

    /**
     * Submit a generation request. Returns true if accepted (piggyback or new active slot),
     * false if at capacity (caller should feed back a rejection result).
     */
    public boolean submitGeneration(UUID playerUuid, int requestId, ServerLevel level, int cx, int cz, long submissionOrder) {
        var key = new PendingGenerationKey(level.dimension(), cx, cz);

        // Already active — piggyback on existing entry
        var existing = this.active.get(key);
        if (existing != null) {
            existing.callbacks.add(new GenerationCallback(playerUuid, requestId, submissionOrder));
            incrementCount(this.perPlayerActiveCount, playerUuid);
            return true;
        }

        // Try to add directly to active
        int playerActive = this.perPlayerActiveCount.getOrDefault(playerUuid, 0);
        if (this.active.size() < this.maxConcurrent && playerActive < this.maxPerPlayerActive) {
            var pos = new ChunkPos(cx, cz);
            level.getChunkSource().addTicketWithRadius(LSS_GEN_TICKET, pos, 0);

            var gen = new PendingGeneration(pos, level);
            gen.callbacks.add(new GenerationCallback(playerUuid, requestId, submissionOrder));
            this.active.put(key, gen);
            incrementCount(this.perPlayerActiveCount, playerUuid);
            this.totalSubmitted++;
            return true;
        }

        // At capacity — reject. Client's retry loop will re-request later.
        return false;
    }

    /**
     * Tick the generation service. Extracts primitives for completed chunks (main thread safe),
     * returns GenerationReadyData for the processing thread to voxelize off-thread.
     * Timeouts produce empty results directly in the player result queue.
     */
    public List<TickSnapshot.GenerationReadyData> tick() {
        if (this.active.isEmpty()) return List.of();
        List<TickSnapshot.GenerationReadyData> ready = null;
        var iter = this.active.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            var gen = entry.getValue();
            gen.ticksWaiting++;

            if (gen.ticksWaiting > this.timeoutTicks) {
                LSSLogger.debug("Generation timeout for chunk " + gen.pos.x + "," + gen.pos.z
                        + " after " + gen.ticksWaiting + " ticks (" + gen.callbacks.size() + " callbacks)");
                for (var cb : gen.callbacks) {
                    this.addResult(cb.playerUuid, ChunkDiskReader.emptyResult(
                            cb.playerUuid, cb.requestId, gen.pos.x, gen.pos.z, cb.submissionOrder));
                    decrementCount(this.perPlayerActiveCount, cb.playerUuid);
                }
                gen.level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, gen.pos, 0);
                iter.remove();
                this.totalTimeouts++;
                continue;
            }

            LevelChunk chunk = gen.level.getChunkSource().getChunkNow(gen.pos.x, gen.pos.z);
            if (chunk != null) {
                try {
                    long columnTimestamp = LSSConstants.epochSeconds();
                    LoadedColumnData columnData = SectionSerializer.serializeColumn(
                            gen.level, chunk, gen.pos.x, gen.pos.z);

                    // One GenerationReadyData per callback — processing thread will voxelize
                    for (var cb : gen.callbacks) {
                        if (ready == null) ready = new ArrayList<>();
                        ready.add(new TickSnapshot.GenerationReadyData(
                                cb.playerUuid, cb.requestId, columnData, columnTimestamp,
                                cb.submissionOrder));
                        decrementCount(this.perPlayerActiveCount, cb.playerUuid);
                    }
                    this.totalCompleted++;
                } catch (Exception e) {
                    LSSLogger.error("Failed to extract primitives for generated chunk at " + gen.pos.x + ", " + gen.pos.z, e);
                    for (var cb : gen.callbacks) {
                        this.addResult(cb.playerUuid, ChunkDiskReader.emptyResult(
                                cb.playerUuid, cb.requestId, gen.pos.x, gen.pos.z, cb.submissionOrder));
                        decrementCount(this.perPlayerActiveCount, cb.playerUuid);
                    }
                }
                gen.level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, gen.pos, 0);
                iter.remove();
            }
        }
        return ready != null ? ready : List.of();
    }

    void registerPlayer(UUID playerUuid) {
        this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>());
    }

    void addResult(UUID playerUuid, ChunkDiskReader.ReadResult result) {
        var queue = this.playerResults.get(playerUuid);
        if (queue != null) {
            queue.add(result);
        }
    }

    public ConcurrentLinkedQueue<ChunkDiskReader.ReadResult> getPlayerQueue(UUID playerUuid) {
        return this.playerResults.get(playerUuid);
    }

    public void removePlayerResults(UUID playerUuid) {
        this.playerResults.remove(playerUuid);
    }

    public void removePlayer(UUID playerUuid) {
        this.removePlayerResults(playerUuid);
        this.perPlayerActiveCount.remove(playerUuid);

        // Clean up active entries
        var iter = this.active.entrySet().iterator();
        while (iter.hasNext()) {
            var gen = iter.next().getValue();
            gen.callbacks.removeIf(cb -> cb.playerUuid.equals(playerUuid));
            if (gen.callbacks.isEmpty()) {
                gen.level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, gen.pos, 0);
                iter.remove();
            }
        }
    }

    public void shutdown() {
        for (var gen : this.active.values()) {
            gen.level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, gen.pos, 0);
        }
        this.active.clear();
        this.perPlayerActiveCount.clear();
        this.playerResults.clear();
    }

    public String getDiagnostics() {
        return String.format("submitted=%d, completed=%d, active=%d, timeouts=%d",
                totalSubmitted, totalCompleted, active.size(), totalTimeouts);
    }

    public long getTotalSubmitted() { return totalSubmitted; }
    public long getTotalCompleted() { return totalCompleted; }
    public long getTotalTimeouts() { return totalTimeouts; }

    private static void incrementCount(Map<UUID, Integer> map, UUID uuid) {
        map.merge(uuid, 1, Integer::sum);
    }

    private static void decrementCount(Map<UUID, Integer> map, UUID uuid) {
        var count = map.get(uuid);
        if (count != null) {
            if (count <= 1) map.remove(uuid);
            else map.put(uuid, count - 1);
        }
    }
}
