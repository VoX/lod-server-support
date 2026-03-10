package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.TickSnapshot;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages chunk generation requests for the Paper plugin.
 * Uses Paper's async chunk API ({@code World.getChunkAtAsync}) which guarantees
 * {@code ChunkStatus.FULL} before the callback fires and manages tickets automatically.
 */
public class PaperChunkGenerationService {

    record GenerationCallback(UUID playerUuid, int requestId, long submissionOrder) {}

    private record PendingGenerationKey(ResourceKey<Level> dimension, int cx, int cz) {}

    /**
     * Tracks an active async chunk load. The async future fires {@link #onChunkReady}
     * when Paper finishes loading/generating the chunk to FULL status.
     */
    static class ActiveGeneration {
        final List<GenerationCallback> callbacks = new ArrayList<>();
        int ticksWaiting = 0;
    }

    private final LinkedHashMap<PendingGenerationKey, ActiveGeneration> active = new LinkedHashMap<>();
    private final Map<UUID, Integer> perPlayerActiveCount = new HashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<PaperChunkDiskReader.SimpleReadResult>> playerResults = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TickSnapshot.GenerationReadyData> generationReadyQueue = new ConcurrentLinkedQueue<>();

    private final Plugin plugin;
    private final int maxConcurrent;
    private final int maxPerPlayerActive;
    private final int timeoutTicks;

    // Volatile is sufficient — only written from the main tick thread, read by /stats commands.
    private volatile long totalSubmitted = 0;
    private volatile long totalCompleted = 0;
    private volatile long totalTimeouts = 0;

    public PaperChunkGenerationService(PaperConfig config, Plugin plugin) {
        this.plugin = plugin;
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

        // Already active — piggyback on existing async load
        var existingActive = this.active.get(key);
        if (existingActive != null) {
            existingActive.callbacks.add(new GenerationCallback(playerUuid, requestId, submissionOrder));
            incrementCount(this.perPlayerActiveCount, playerUuid);
            return true;
        }

        // Try to add directly to active and launch async load
        int playerActive = this.perPlayerActiveCount.getOrDefault(playerUuid, 0);
        if (this.active.size() < this.maxConcurrent && playerActive < this.maxPerPlayerActive) {
            var gen = new ActiveGeneration();
            gen.callbacks.add(new GenerationCallback(playerUuid, requestId, submissionOrder));
            this.active.put(key, gen);
            incrementCount(this.perPlayerActiveCount, playerUuid);
            this.totalSubmitted++;

            launchAsyncLoad(key, level, cx, cz);
            return true;
        }

        // At capacity — reject. Client's retry loop will re-request later.
        return false;
    }

    /**
     * Launches Paper's async chunk load. The callback fires on the main thread
     * when the chunk reaches FULL status. Paper manages tickets automatically.
     */
    private void launchAsyncLoad(PendingGenerationKey key, ServerLevel level, int cx, int cz) {
        level.getWorld().getChunkAtAsync(cx, cz, true, false).whenComplete((chunk, ex) -> {
            if (ex != null) {
                LSSLogger.error("Async chunk load failed at " + cx + "," + cz, ex);
            }
            var readyChunk = ex == null ? chunk : null;
            // Ensure callback runs on the main thread — whenComplete does not guarantee thread
            try {
                Bukkit.getScheduler().runTask(this.plugin, () ->
                        onChunkReady(key, level, readyChunk, cx, cz));
            } catch (Exception scheduleEx) {
                // Plugin disabled during shutdown — do not call onChunkReady inline
                // because we're on an async thread and active map is not thread-safe.
                // shutdown() already clears the active map.
                LSSLogger.warn("Could not schedule generation callback (plugin shutting down) at " + cx + "," + cz);
            }
        });
    }

    /**
     * Called on the main thread when Paper finishes loading/generating a chunk to FULL.
     */
    private void onChunkReady(PendingGenerationKey key, ServerLevel level,
                               Chunk chunk, int cx, int cz) {
        var gen = this.active.remove(key);
        if (gen == null) return; // cleaned up by removePlayer or timeout

        if (chunk != null) {
            try {
                LevelChunk nmsChunk = level.getChunkSource().getChunkNow(cx, cz);
                if (nmsChunk != null) {
                    long columnTimestamp = LSSConstants.epochSeconds();
                    LoadedColumnData columnData = PaperSectionSerializer.serializeColumn(
                            level, nmsChunk, cx, cz);

                    for (var cb : gen.callbacks) {
                        this.generationReadyQueue.add(new TickSnapshot.GenerationReadyData(
                                cb.playerUuid, cb.requestId, columnData, columnTimestamp,
                                cb.submissionOrder));
                        decrementCount(this.perPlayerActiveCount, cb.playerUuid);
                    }
                    this.totalCompleted++;
                } else {
                    LSSLogger.warn("Chunk at " + cx + "," + cz + " was null after async load completed");
                    emptyResultForCallbacks(gen.callbacks, cx, cz, this.perPlayerActiveCount);
                }
            } catch (Exception e) {
                LSSLogger.error("Failed to extract primitives for generated chunk at " + cx + ", " + cz, e);
                emptyResultForCallbacks(gen.callbacks, cx, cz, this.perPlayerActiveCount);
            }
        } else {
            emptyResultForCallbacks(gen.callbacks, cx, cz, this.perPlayerActiveCount);
        }
    }

    private void emptyResultForCallbacks(List<GenerationCallback> callbacks, int cx, int cz,
                                          Map<UUID, Integer> countMap) {
        for (var cb : callbacks) {
            this.addResult(cb.playerUuid, PaperChunkDiskReader.emptyResult(
                    cb.playerUuid, cb.requestId, cx, cz, cb.submissionOrder));
            decrementCount(countMap, cb.playerUuid);
        }
    }

    /**
     * Tick the generation service. Returns generation-ready data for the processing thread to voxelize.
     */
    public List<TickSnapshot.GenerationReadyData> tick() {
        this.tickActiveTimeouts();

        // Drain generation-ready data from async callbacks
        List<TickSnapshot.GenerationReadyData> ready = null;
        TickSnapshot.GenerationReadyData grd;
        while ((grd = this.generationReadyQueue.poll()) != null) {
            if (ready == null) ready = new ArrayList<>();
            ready.add(grd);
        }
        return ready != null ? ready : List.of();
    }

    /**
     * Safety net: if an async load never completes, expire it after the timeout.
     * Paper's async API should always complete, but this prevents leaked active slots.
     */
    private void tickActiveTimeouts() {
        if (this.active.isEmpty()) return;
        var iter = this.active.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            var gen = entry.getValue();
            gen.ticksWaiting++;

            if (gen.ticksWaiting > this.timeoutTicks) {
                emptyResultForCallbacks(gen.callbacks, entry.getKey().cx, entry.getKey().cz,
                        this.perPlayerActiveCount);
                iter.remove();
                this.totalTimeouts++;
            }
        }
    }

    void registerPlayer(UUID playerUuid) {
        this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>());
    }

    void addResult(UUID playerUuid, PaperChunkDiskReader.SimpleReadResult result) {
        var queue = this.playerResults.get(playerUuid);
        if (queue != null) {
            queue.add(result);
        }
    }

    public ConcurrentLinkedQueue<PaperChunkDiskReader.SimpleReadResult> getPlayerQueue(UUID playerUuid) {
        return this.playerResults.get(playerUuid);
    }

    public void removePlayerResults(UUID playerUuid) {
        this.playerResults.remove(playerUuid);
    }

    public void removePlayer(UUID playerUuid) {
        this.removePlayerResults(playerUuid);

        // Clean active callbacks first — if onChunkReady fires between these steps,
        // decrementCount needs perPlayerActiveCount to still exist
        var activeIter = this.active.entrySet().iterator();
        while (activeIter.hasNext()) {
            var gen = activeIter.next().getValue();
            gen.callbacks.removeIf(cb -> cb.playerUuid.equals(playerUuid));
            if (gen.callbacks.isEmpty()) {
                activeIter.remove();
            }
        }

        this.perPlayerActiveCount.remove(playerUuid);
    }

    public void shutdown() {
        // No manual ticket cleanup needed — Paper manages tickets via getChunkAtAsync.
        // Active async futures will complete but onChunkReady will find empty active map.
        this.active.clear();
        this.perPlayerActiveCount.clear();
        this.playerResults.clear();
    }

    public String getDiagnostics() {
        return String.format("submitted=%d, completed=%d, active=%d, timeouts=%d",
                totalSubmitted, totalCompleted, active.size(), totalTimeouts);
    }

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
