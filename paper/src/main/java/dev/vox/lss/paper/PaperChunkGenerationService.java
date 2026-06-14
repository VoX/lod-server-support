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

/**
 * Manages chunk generation requests for the Paper plugin.
 * Uses Paper's async chunk API ({@code World.getChunkAtAsync}) which guarantees
 * {@code ChunkStatus.FULL} before the callback fires and manages tickets automatically.
 */
public class PaperChunkGenerationService {

    record GenerationCallback(UUID playerUuid, long submissionOrder) {}

    // Package-visible (with launchAsyncLoad/onChunkReady) so T1 tests can drive the async
    // boundary directly instead of going through Bukkit's scheduler.
    record PendingGenerationKey(ResourceKey<Level> dimension, int cx, int cz) {}

    /**
     * Tracks an active async chunk load. The async future fires {@link #onChunkReady}
     * when Paper finishes loading/generating the chunk to FULL status.
     */
    static class ActiveGeneration {
        // Monotonic id of this launch. A stale async completion (from an entry that already
        // timed out and was resubmitted under the same key) carries the OLD token, so
        // onChunkReady can reject it and leave the current entry for its own completion —
        // otherwise the stale (possibly failed) result consumes the fresh entry by key match.
        final long token;
        final List<GenerationCallback> callbacks = new ArrayList<>();
        int ticksWaiting = 0;

        ActiveGeneration(long token) { this.token = token; }
    }

    // Main-thread-owned: assigns a unique token to each ActiveGeneration launch.
    private long nextGenerationToken = 0;

    private final LinkedHashMap<PendingGenerationKey, ActiveGeneration> active = new LinkedHashMap<>();
    private final Map<UUID, Integer> perPlayerActiveCount = new HashMap<>();
    // Main-thread-owned (onChunkReady is scheduled to the main thread via runTask; tick() swaps it out)
    private List<TickSnapshot.GenerationReadyData> mainReady = new ArrayList<>();

    private final Plugin plugin;
    private final int maxConcurrent;
    private final int maxPerPlayerActive;
    private final int timeoutTicks;

    /** Test seam: hands the async-load completion back to the main thread. Production
     *  default is Bukkit's scheduler, which throws once the plugin is disabled —
     *  tests inject throwing schedulers to pin that rejection containment. */
    @FunctionalInterface
    interface MainThreadScheduler {
        void schedule(Runnable task) throws Exception;
    }

    // Wired in the constructor (default references the blank-final plugin field, which is
    // not definitely assigned until the constructor body runs).
    private MainThreadScheduler mainThreadScheduler;

    void setMainThreadScheduler(MainThreadScheduler scheduler) {
        this.mainThreadScheduler = scheduler;
    }

    // Volatile is sufficient — only written from the main tick thread, read by /stats commands.
    private volatile long totalSubmitted = 0;
    private volatile long totalCompleted = 0;
    private volatile long totalTimeouts = 0;
    private volatile long totalRemovedInFlight = 0;

    public PaperChunkGenerationService(PaperConfig config, Plugin plugin) {
        this.plugin = plugin;
        this.maxConcurrent = config.generationConcurrencyLimitGlobal;
        this.maxPerPlayerActive = config.generationConcurrencyLimitPerPlayer;
        this.timeoutTicks = config.generationTimeoutSeconds * LSSConstants.TICKS_PER_SECOND;
        this.mainThreadScheduler = task -> Bukkit.getScheduler().runTask(this.plugin, task);
    }

    /**
     * Submit a generation request. Returns true if accepted (piggyback or new active slot),
     * false if at capacity (caller should feed back a rejection result).
     */
    public boolean submitGeneration(UUID playerUuid, ServerLevel level, int cx, int cz, long submissionOrder) {
        var key = new PendingGenerationKey(level.dimension(), cx, cz);

        // Already active — piggyback on existing async load
        var existingActive = this.active.get(key);
        if (existingActive != null) {
            existingActive.callbacks.add(new GenerationCallback(playerUuid, submissionOrder));
            incrementCount(this.perPlayerActiveCount, playerUuid);
            return true;
        }

        // Try to add directly to active and launch async load
        int playerActive = this.perPlayerActiveCount.getOrDefault(playerUuid, 0);
        if (this.active.size() < this.maxConcurrent && playerActive < this.maxPerPlayerActive) {
            var gen = new ActiveGeneration(++this.nextGenerationToken);
            gen.callbacks.add(new GenerationCallback(playerUuid, submissionOrder));
            this.active.put(key, gen);
            incrementCount(this.perPlayerActiveCount, playerUuid);
            this.totalSubmitted++;

            launchAsyncLoad(key, level, cx, cz, gen.token);
            return true;
        }

        // At capacity — reject. Client's retry loop will re-request later.
        return false;
    }

    /**
     * Launches Paper's async chunk load. The callback fires on the main thread
     * when the chunk reaches FULL status. Paper manages tickets automatically.
     * Package-visible seam: tests override this to capture the launch.
     */
    void launchAsyncLoad(PendingGenerationKey key, ServerLevel level, int cx, int cz, long token) {
        level.getWorld().getChunkAtAsync(cx, cz, true, false).whenComplete((chunk, ex) -> {
            if (ex != null) {
                LSSLogger.error("Async chunk load failed at " + cx + "," + cz, ex);
            }
            var readyChunk = ex == null ? chunk : null;
            // Ensure callback runs on the main thread — whenComplete does not guarantee thread
            try {
                this.mainThreadScheduler.schedule(() ->
                        onChunkReady(key, level, readyChunk, cx, cz, token));
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
     * Package-visible so tests can fire the completion that launchAsyncLoad would schedule.
     */
    void onChunkReady(PendingGenerationKey key, ServerLevel level,
                       Chunk chunk, int cx, int cz, long token) {
        var gen = this.active.get(key);
        // Reject a stale completion: the entry that launched this load already timed out and a
        // new entry was resubmitted under the same key (different token). Leaving the current
        // entry untouched lets ITS own completion serve it — a stale (often failed) result must
        // not consume the fresh entry. null = the entry was cleaned up by removePlayer/timeout.
        if (gen == null || gen.token != token) return;
        this.active.remove(key);

        if (chunk != null) {
            try {
                LevelChunk nmsChunk = level.getChunkSource().getChunkNow(cx, cz);
                if (nmsChunk != null) {
                    long columnTimestamp = LSSConstants.epochSeconds();
                    LoadedColumnData columnData = PaperSectionSerializer.serializeColumn(
                            level, nmsChunk, cx, cz);
                    String dimension = key.dimension().identifier().toString();

                    for (var cb : gen.callbacks) {
                        this.mainReady.add(new TickSnapshot.GenerationReadyData(
                                cb.playerUuid, cx, cz, dimension,
                                columnData, columnTimestamp, cb.submissionOrder));
                        decrementCount(this.perPlayerActiveCount, cb.playerUuid);
                    }
                    this.totalCompleted++;
                } else {
                    LSSLogger.warn("Chunk at " + cx + "," + cz + " was null after async load completed");
                    addFailures(gen.callbacks, key, cx, cz);
                    this.totalRemovedInFlight++;
                }
            } catch (Throwable t) {
                // Throwable, not Exception (mirrors the Fabric twin): an Error here would
                // otherwise skip addFailures and leak every callback's generation slot and
                // per-player count until disconnect.
                LSSLogger.error("Failed to extract primitives for generated chunk at " + cx + ", " + cz, t);
                addFailures(gen.callbacks, key, cx, cz);
                this.totalRemovedInFlight++;
                if (t instanceof Error err) throw err;
            }
        } else {
            addFailures(gen.callbacks, key, cx, cz);
            this.totalRemovedInFlight++;
        }
    }

    /**
     * Add a failure outcome (columnData == null) for every callback. Main thread only.
     * Callers that removed the active entry must also count it exactly once — totalTimeouts
     * on the timeout path, totalRemovedInFlight on the failure paths (mirrors the Fabric
     * twin) — so the generation books (submitted == completed + timeouts + removed) balance
     * (soak law A4).
     */
    private void addFailures(List<GenerationCallback> callbacks, PendingGenerationKey key, int cx, int cz) {
        String dimension = key.dimension().identifier().toString();
        for (var cb : callbacks) {
            this.mainReady.add(new TickSnapshot.GenerationReadyData(
                    cb.playerUuid, cx, cz, dimension, null, 0L, cb.submissionOrder));
            decrementCount(this.perPlayerActiveCount, cb.playerUuid);
        }
    }

    /**
     * Tick the generation service. Returns generation-ready data for the processing thread to voxelize.
     */
    public List<TickSnapshot.GenerationReadyData> tick() {
        this.tickActiveTimeouts();

        if (this.mainReady.isEmpty()) return List.of();
        var ready = this.mainReady;
        this.mainReady = new ArrayList<>();
        return ready;
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
                addFailures(gen.callbacks, entry.getKey(), entry.getKey().cx, entry.getKey().cz);
                iter.remove();
                this.totalTimeouts++;
            }
        }
    }

    public void removePlayer(UUID playerUuid) {
        // Clean active callbacks first — if onChunkReady fires between these steps,
        // decrementCount needs perPlayerActiveCount to still exist
        var activeIter = this.active.entrySet().iterator();
        while (activeIter.hasNext()) {
            var gen = activeIter.next().getValue();
            gen.callbacks.removeIf(cb -> cb.playerUuid.equals(playerUuid));
            if (gen.callbacks.isEmpty()) {
                activeIter.remove();
                // Submitted but neither completed nor timed out — without this counter the
                // submitted/completed books can never re-balance after a kick or dimension change
                this.totalRemovedInFlight++;
            }
        }

        this.perPlayerActiveCount.remove(playerUuid);
    }

    public void shutdown() {
        // No manual ticket cleanup needed — Paper manages tickets via getChunkAtAsync.
        // Active async futures will complete but onChunkReady will find empty active map.
        this.active.clear();
        this.perPlayerActiveCount.clear();
        this.mainReady.clear();
    }

    public String getDiagnostics() {
        return String.format("submitted=%d, completed=%d, active=%d, timeouts=%d, removed=%d",
                totalSubmitted, totalCompleted, active.size(), totalTimeouts, totalRemovedInFlight);
    }

    public long getTotalSubmitted() { return this.totalSubmitted; }

    public long getTotalCompleted() { return this.totalCompleted; }

    public long getTotalTimeouts() { return this.totalTimeouts; }

    public long getTotalRemovedInFlight() { return this.totalRemovedInFlight; }

    /** Main thread only (like every other read of {@code active}). */
    public int getActiveCount() { return this.active.size(); }

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
