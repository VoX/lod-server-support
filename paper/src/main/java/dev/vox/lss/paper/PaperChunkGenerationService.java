package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.TickSnapshot;
import ca.spottedleaf.concurrentutil.util.Priority;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages chunk generation requests for the Paper plugin.
 * Loads/generates via Moonrise's priority-aware scheduler
 * ({@code PlatformHooks.get().scheduleChunkLoad(..., Priority.LOW, ...)}) so LOD generation
 * DEFERS to player-driven NORMAL generation — the generation analogue of the Moonrise
 * {@code Priority.LOW} disk-read path. {@code addTicket=true} lets Moonrise own the load
 * ticket, and {@code ChunkStatus.FULL} is guaranteed before the completion fires (on the
 * chunk's owning thread — the region thread on Folia). Failure surfaces as a NULL chunk:
 * there is no throwable channel.
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
    // Pump-thread-owned (onChunkReady is scheduled to the pump via the GlobalRegionScheduler;
    // tick() swaps it out on the same thread)
    private List<TickSnapshot.GenerationReadyData> mainReady = new ArrayList<>();

    private final int maxConcurrent;
    private final int maxPerPlayerActive;
    private final int timeoutTicks;

    /** Test seam: hands the async-load completion back to the pump thread. Production
     *  default is the GlobalRegionScheduler (main thread on Paper, the pump's global-region
     *  thread on Folia), which throws {@code IllegalPluginAccessException} once the plugin is
     *  disabled — the identical type the legacy CraftScheduler threw, so containment is exact
     *  parity; it must keep catching broad {@code Exception}, never a named type. Tests
     *  inject throwing schedulers to pin that rejection containment. */
    @FunctionalInterface
    interface MainThreadScheduler {
        void schedule(Runnable task) throws Exception;
    }

    // Wired in the constructor (the default captures the constructor's plugin parameter).
    private MainThreadScheduler mainThreadScheduler;

    void setMainThreadScheduler(MainThreadScheduler scheduler) {
        this.mainThreadScheduler = scheduler;
    }

    // Volatile is sufficient — only written from the main tick thread, read by /stats commands.
    private volatile long totalSubmitted = 0;
    private volatile long totalCompleted = 0;
    private volatile long totalTimeouts = 0;
    private volatile long totalRemovedInFlight = 0;
    // Null-chunk completions (Moonrise's permanent-failure outcome). Warn-once + counted:
    // completion runs on region threads on Folia, volatile increments suffice for a
    // diagnostics-only counter (single writer per completion, monotonic-ish under race).
    private volatile boolean nullChunkWarned = false;
    private volatile long nullChunkFailures = 0;

    public PaperChunkGenerationService(PaperConfig config, Plugin plugin) {
        this.maxConcurrent = config.generationConcurrencyLimitGlobal;
        this.maxPerPlayerActive = config.generationConcurrencyLimitPerPlayer;
        this.timeoutTicks = config.generationTimeoutSeconds * LSSConstants.TICKS_PER_SECOND;
        this.mainThreadScheduler = task ->
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
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

            // Priority.LOW: LOD generation yields to player-driven (NORMAL) generation. Safe
            // against starvation-shaped timeouts because a timeout is a TRANSIENT outcome —
            // the client re-declares and the load is retried, never blanked NOT_GENERATED.
            launchAsyncLoad(key, level, cx, cz, gen.token, Priority.LOW);
            return true;
        }

        // At capacity — reject. Client's retry loop will re-request later.
        return false;
    }

    /**
     * Launches the Moonrise priority-aware chunk load. The completion fires
     * {@link #completeAsyncLoad} on the load's completion thread (the chunk's owning region
     * thread on Folia) with an NMS {@code ChunkAccess} — null is the failure outcome, there
     * is no throwable channel. Package-visible seam: tests override this to capture the
     * launch (and the priority, pinned to {@code Priority.LOW}).
     */
    void launchAsyncLoad(PendingGenerationKey key, ServerLevel level, int cx, int cz, long token,
                         Priority priority) {
        ca.spottedleaf.moonrise.common.PlatformHooks.get().scheduleChunkLoad(
                level, cx, cz, /*gen=*/true, ChunkStatus.FULL, /*addTicket=*/true, priority,
                chunk -> completeAsyncLoad(key, level, chunk, cx, cz, token));
    }

    /**
     * Completion-thread half of a generation: serialize the freshly loaded chunk HERE, then
     * hand only the immutable result to the pump. On Folia this thread is the chunk's owning
     * REGION thread — the only place the chunk is guaranteed still loaded: the load ticket
     * dies with this callback, and an isolated LOD chunk (no players nearby) can unload again
     * before a hop to the global tick runs. Deferring extraction to the pump lost ~93% of
     * generations that way (observed live: 6.9k "was null after async load" failures in one
     * fresh-backfill). On Paper this is the main thread — where the old post-hop extraction
     * ran anyway. The reads are region-legal and tear-free off-pump (getChunkNow is a
     * concurrent-map lookup; light reads clone SWMR state; PalettedContainer.write is
     * synchronized). Package-visible so tests can drive the completion exactly as the
     * Moonrise consumer would. A null {@code chunk} is the failure outcome (permanent:
     * NOT_GENERATED downstream — a failed load/vanished chunk must not be hammered).
     */
    void completeAsyncLoad(PendingGenerationKey key, ServerLevel level, ChunkAccess chunk,
                           int cx, int cz, long token) {
        if (chunk == null) {
            // Moonrise's failure outcome — no throwable channel exists. Warn-once: each
            // null is a per-column permanent failure, and a regression that reintroduces
            // them at scale (the 6.9k-in-one-soak history) must not also be a WARN storm.
            if (!this.nullChunkWarned) {
                this.nullChunkWarned = true;
                LSSLogger.warn("Async chunk load failed (null chunk) at " + cx + "," + cz
                        + " — further null-chunk failures are logged silently (counted)");
            }
            this.nullChunkFailures++;
        }
        LoadedColumnData extracted = null;
        Error rethrow = null;
        if (chunk != null) {
            try {
                extracted = extractColumnData(level, cx, cz);
            } catch (Error e) {
                // Books first (mirrors the Fabric twin): the failure hop below must still be
                // scheduled or every callback's generation slot leaks until disconnect.
                LSSLogger.error("Failed to extract primitives for generated chunk at " + cx + ", " + cz, e);
                rethrow = e;
            }
        }
        var columnData = extracted;
        try {
            this.mainThreadScheduler.schedule(() -> onChunkReady(key, columnData, cx, cz, token));
        } catch (Exception scheduleEx) {
            // Plugin disabled during shutdown — do not call onChunkReady inline
            // because we're not on the pump and the active map is not thread-safe.
            // shutdown() already clears the active map.
            LSSLogger.warn("Could not schedule generation callback (plugin shutting down) at " + cx + "," + cz);
        }
        if (rethrow != null) {
            // A plain rethrow would vanish into Moonrise's completion plumbing. Hand it to
            // the thread's uncaught handler instead, so an Error (OOME, linkage) stays as
            // loud as the old scheduled-task rethrow was.
            Thread.currentThread().getUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), rethrow);
        }
    }

    /**
     * Serializes the just-loaded chunk on the completion thread; null on any failure —
     * the failure books happen on the pump when {@link #onChunkReady} sees the null.
     * Errors propagate to {@link #completeAsyncLoad}, which schedules the failure outcome
     * before rethrowing.
     */
    LoadedColumnData extractColumnData(ServerLevel level, int cx, int cz) {
        try {
            LevelChunk nmsChunk = level.getChunkSource().getChunkNow(cx, cz);
            if (nmsChunk == null) {
                LSSLogger.warn("Chunk at " + cx + "," + cz + " was null after async load completed");
                return null;
            }
            return PaperSectionSerializer.serializeColumn(level, nmsChunk, cx, cz);
        } catch (Exception e) {
            LSSLogger.error("Failed to extract primitives for generated chunk at " + cx + ", " + cz, e);
            return null;
        }
    }

    /**
     * Called on the pump thread with the completion-thread's extraction result (null = the
     * load failed, the chunk vanished, or extraction threw). Books only — no chunk access.
     * Package-visible so tests can fire the completion that completeAsyncLoad would schedule.
     */
    void onChunkReady(PendingGenerationKey key, LoadedColumnData columnData,
                      int cx, int cz, long token) {
        var gen = this.active.get(key);
        // Reject a stale completion: the entry that launched this load already timed out and a
        // new entry was resubmitted under the same key (different token). Leaving the current
        // entry untouched lets ITS own completion serve it — a stale (often failed) result must
        // not consume the fresh entry. null = the entry was cleaned up by removePlayer/timeout.
        if (gen == null || gen.token != token) return;
        this.active.remove(key);

        if (columnData != null) {
            long columnTimestamp = LSSConstants.epochSeconds();
            String dimension = key.dimension().identifier().toString();
            for (var cb : gen.callbacks) {
                this.mainReady.add(new TickSnapshot.GenerationReadyData(
                        cb.playerUuid, cx, cz, dimension,
                        columnData, columnTimestamp, cb.submissionOrder));
                decrementCount(this.perPlayerActiveCount, cb.playerUuid);
            }
            this.totalCompleted++;
        } else {
            // PERMANENT: the load failed, the chunk vanished, or extraction threw — a
            // corrupt chunk must not be hammered. NOT_GENERATED; dirty broadcast revives.
            addFailures(gen.callbacks, key, cx, cz, false);
            this.totalRemovedInFlight++;
        }
    }

    /**
     * Add a failure outcome (columnData == null) for every callback. Main thread only.
     * Callers that removed the active entry must also count it exactly once — totalTimeouts
     * on the timeout path, totalRemovedInFlight on the failure paths (mirrors the Fabric
     * twin) — so the generation books (submitted == completed + timeouts + removed) balance
     * (soak law A4). {@code transientFailure} picks the wire disposition downstream: true =
     * silent drop + superseded (timeout), false = ColumnNotGenerated (permanent — failed
     * load / extraction). The books are identical either way.
     */
    private void addFailures(List<GenerationCallback> callbacks, PendingGenerationKey key,
                             int cx, int cz, boolean transientFailure) {
        String dimension = key.dimension().identifier().toString();
        for (var cb : callbacks) {
            this.mainReady.add(new TickSnapshot.GenerationReadyData(
                    cb.playerUuid, cx, cz, dimension, null, 0L, cb.submissionOrder, transientFailure));
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
                // Timeout is TRANSIENT (under Priority.LOW a starved load is routine on a
                // busy server): silent drop downstream, the client's re-declaration retries.
                addFailures(gen.callbacks, entry.getKey(), entry.getKey().cx, entry.getKey().cz, true);
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
        // No manual ticket cleanup needed — Moonrise owns the load ticket (addTicket=true).
        // Active async loads will complete but onChunkReady will find an empty active map.
        this.active.clear();
        this.perPlayerActiveCount.clear();
        this.mainReady.clear();
    }

    public String getDiagnostics() {
        return String.format("submitted=%d, completed=%d, active=%d, timeouts=%d, removed=%d",
                totalSubmitted, totalCompleted, active.size(), totalTimeouts, totalRemovedInFlight);
    }

    public long getTotalSubmitted() { return this.totalSubmitted; }

    /** Null-chunk (permanent-failure) completions — warn-once logged, counted here. */
    public long getNullChunkFailures() { return this.nullChunkFailures; }

    public long getTotalCompleted() { return this.totalCompleted; }

    public long getTotalTimeouts() { return this.totalTimeouts; }

    public long getTotalRemovedInFlight() { return this.totalRemovedInFlight; }

    /** Pump thread for exact values; command threads read racily (stale-tolerable admin
     *  diagnostics — never iterate {@code active} off-pump, size() is a plain field read). */
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
