package dev.vox.lss.paper;

import dev.vox.lss.common.LSSLogger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
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

    record Callback(UUID playerUuid, int batchId, long submissionOrder) {}

    private record PendingKey(ResourceKey<Level> dimension, int cx, int cz) {}

    /**
     * Tracks an active async chunk load. The async future fires {@link #onChunkReady}
     * when Paper finishes loading/generating the chunk to FULL status.
     */
    static class ActiveGeneration {
        final List<Callback> callbacks = new ArrayList<>();
        int ticksWaiting = 0;
    }

    /**
     * Tracks a chunk waiting for an active slot to open before launching async load.
     */
    static class WaitingGeneration {
        final ChunkPos pos;
        final ServerLevel level;
        final List<Callback> callbacks = new ArrayList<>();

        WaitingGeneration(ChunkPos pos, ServerLevel level) {
            this.pos = pos;
            this.level = level;
        }
    }

    private final HashMap<PendingKey, ActiveGeneration> active = new HashMap<>();
    private final LinkedHashMap<PendingKey, WaitingGeneration> waiting = new LinkedHashMap<>();
    private final Map<UUID, Integer> perPlayerActiveCount = new HashMap<>();
    private final HashMap<UUID, ArrayDeque<PaperChunkDiskReader.SimpleReadResult>> playerResults = new HashMap<>();

    private final int maxConcurrent;
    private final int maxPerPlayerActive;
    private final int timeoutTicks;
    private final boolean sendLightData;

    private long totalSubmitted = 0;
    private long totalCompleted = 0;
    private long totalTimeouts = 0;
    private long totalEvicted = 0;

    public PaperChunkGenerationService(PaperConfig config) {
        this.maxConcurrent = config.maxConcurrentGenerations;
        this.maxPerPlayerActive = config.maxConcurrentGenerationsPerPlayer;
        this.timeoutTicks = config.generationTimeoutSeconds * 20;
        this.sendLightData = config.sendLightData;
    }

    public void submitGeneration(UUID playerUuid, int batchId, ServerLevel level, int cx, int cz, long submissionOrder) {
        var key = new PendingKey(level.dimension(), cx, cz);

        // Already active — piggyback on existing async load
        var existingActive = this.active.get(key);
        if (existingActive != null) {
            existingActive.callbacks.add(new Callback(playerUuid, batchId, submissionOrder));
            incrementCount(this.perPlayerActiveCount, playerUuid);
            return;
        }

        // Already waiting — piggyback
        var existingWaiting = this.waiting.get(key);
        if (existingWaiting != null) {
            existingWaiting.callbacks.add(new Callback(playerUuid, batchId, submissionOrder));
            return;
        }

        // Try to add directly to active and launch async load
        int playerActive = this.perPlayerActiveCount.getOrDefault(playerUuid, 0);
        if (this.active.size() < this.maxConcurrent && playerActive < this.maxPerPlayerActive) {
            var gen = new ActiveGeneration();
            gen.callbacks.add(new Callback(playerUuid, batchId, submissionOrder));
            this.active.put(key, gen);
            incrementCount(this.perPlayerActiveCount, playerUuid);
            this.totalSubmitted++;

            launchAsyncLoad(key, level, cx, cz);
            return;
        }

        // Add to unbounded waiting queue — backpressure is in the batch processor
        // (pendingGenerationCount gate). Orphans are evicted in promoteWaitingEntries.
        var gen = new WaitingGeneration(new ChunkPos(cx, cz), level);
        gen.callbacks.add(new Callback(playerUuid, batchId, submissionOrder));
        this.waiting.put(key, gen);
        this.totalSubmitted++;
    }

    /**
     * Launches Paper's async chunk load. The callback fires on the main thread
     * when the chunk reaches FULL status. Paper manages tickets automatically.
     */
    private void launchAsyncLoad(PendingKey key, ServerLevel level, int cx, int cz) {
        level.getWorld().getChunkAtAsync(cx, cz, true, false).thenAccept(chunk -> {
            onChunkReady(key, level, chunk, cx, cz);
        });
    }

    /**
     * Called on the main thread when Paper finishes loading/generating a chunk to FULL.
     */
    private void onChunkReady(PendingKey key, ServerLevel level,
                               org.bukkit.Chunk chunk, int cx, int cz) {
        var gen = this.active.remove(key);
        if (gen == null) return; // cleaned up by removePlayer or timeout

        if (chunk != null) {
            try {
                // The chunk is guaranteed FULL by Paper's async API.
                // Use getChunkNow() to get the NMS LevelChunk — the chunk is still
                // ticketed during this callback so it must be loaded.
                LevelChunk nmsChunk = level.getChunkSource().getChunkNow(cx, cz);
                if (nmsChunk != null) {
                    long columnTimestamp = System.currentTimeMillis() / 1000;
                    var sections = serializeChunkColumn(level, nmsChunk, cx, cz, columnTimestamp);

                    for (var cb : gen.callbacks) {
                        this.addResult(cb.playerUuid, new PaperChunkDiskReader.SimpleReadResult(
                                cb.playerUuid, cb.batchId, cx, cz,
                                sections, columnTimestamp, false, false, cb.submissionOrder));
                        decrementCount(this.perPlayerActiveCount, cb.playerUuid);
                    }
                    this.totalCompleted++;
                } else {
                    LSSLogger.warn("Chunk at " + cx + "," + cz + " was null after async load completed");
                    emptyResultForCallbacks(gen.callbacks, cx, cz, this.perPlayerActiveCount);
                    this.totalCompleted++;
                }
            } catch (Exception e) {
                LSSLogger.error("Failed to serialize generated chunk at " + cx + ", " + cz, e);
                emptyResultForCallbacks(gen.callbacks, cx, cz, this.perPlayerActiveCount);
                this.totalCompleted++;
            }
        } else {
            // null chunk — chunk couldn't be generated (gen=true but failed)
            emptyResultForCallbacks(gen.callbacks, cx, cz, this.perPlayerActiveCount);
            this.totalCompleted++;
        }
    }

    private void emptyResultForCallbacks(List<Callback> callbacks, int cx, int cz,
                                          Map<UUID, Integer> countMap) {
        for (var cb : callbacks) {
            this.addResult(cb.playerUuid, new PaperChunkDiskReader.SimpleReadResult(
                    cb.playerUuid, cb.batchId, cx, cz,
                    new PaperChunkDiskReader.EncodedSection[0], 0L, false, false, cb.submissionOrder));
            decrementCount(countMap, cb.playerUuid);
        }
    }

    public void tick(Map<UUID, PaperPlayerRequestState> players) {
        this.tickActiveTimeouts();
        this.promoteWaitingEntries(players);
    }

    /**
     * Safety net: if an async load never completes, expire it after the timeout.
     * Paper's async API should always complete, but this prevents leaked active slots.
     */
    private void tickActiveTimeouts() {
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

    private void promoteWaitingEntries(Map<UUID, PaperPlayerRequestState> players) {
        if (this.waiting.isEmpty()) return;

        // Pass 1: evict orphans — entries with no live player among callbacks
        var iter = this.waiting.entrySet().iterator();
        while (iter.hasNext()) {
            var gen = iter.next().getValue();
            boolean hasLivePlayer = false;
            for (var cb : gen.callbacks) {
                if (players.containsKey(cb.playerUuid)) {
                    hasLivePlayer = true;
                    break;
                }
            }
            if (!hasLivePlayer) {
                iter.remove();
                this.totalEvicted++;
            }
        }

        // Pass 2: promote closest entries to active
        while (this.active.size() < this.maxConcurrent && !this.waiting.isEmpty()) {
            PendingKey bestKey = null;
            int bestDist = Integer.MAX_VALUE;

            for (var entry : this.waiting.entrySet()) {
                int dist = minPlayerDistance(entry.getValue(), players);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestKey = entry.getKey();
                }
            }

            if (bestKey == null) break;

            var gen = this.waiting.remove(bestKey);
            var activeGen = new ActiveGeneration();
            for (var cb : gen.callbacks) {
                incrementCount(this.perPlayerActiveCount, cb.playerUuid);
                activeGen.callbacks.add(cb);
            }

            this.active.put(bestKey, activeGen);
            launchAsyncLoad(bestKey, gen.level, gen.pos.x, gen.pos.z);
        }
    }

    private static int minPlayerDistance(WaitingGeneration gen, Map<UUID, PaperPlayerRequestState> players) {
        int minDist = Integer.MAX_VALUE;
        for (var cb : gen.callbacks) {
            var state = players.get(cb.playerUuid);
            if (state == null) continue;
            var player = state.getPlayer();
            int playerCx = player.blockPosition().getX() >> 4;
            int playerCz = player.blockPosition().getZ() >> 4;
            int dist = Math.max(Math.abs(gen.pos.x - playerCx), Math.abs(gen.pos.z - playerCz));
            if (dist < minDist) minDist = dist;
        }
        return minDist;
    }

    private PaperChunkDiskReader.EncodedSection[] serializeChunkColumn(
            ServerLevel level, LevelChunk chunk, int cx, int cz, long columnTimestamp) {
        int minSectionY = level.getMinSectionY();
        var sections = chunk.getSections();
        var dimension = level.dimension();

        var encodedSections = new ArrayList<PaperChunkDiskReader.EncodedSection>();
        for (int i = 0; i < sections.length; i++) {
            int sectionY = minSectionY + i;
            var section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;

            var encoded = PaperRequestProcessingService.serializeLoadedSection(
                    level, section, cx, sectionY, cz, dimension, columnTimestamp, this.sendLightData);
            if (encoded != null) {
                encodedSections.add(encoded);
            }
        }
        return encodedSections.toArray(new PaperChunkDiskReader.EncodedSection[0]);
    }

    private void addResult(UUID playerUuid, PaperChunkDiskReader.SimpleReadResult result) {
        this.playerResults.computeIfAbsent(playerUuid, k -> new ArrayDeque<>()).add(result);
    }

    public ArrayDeque<PaperChunkDiskReader.SimpleReadResult> getPlayerQueue(UUID playerUuid) {
        return this.playerResults.get(playerUuid);
    }

    public void removePlayerResults(UUID playerUuid) {
        this.playerResults.remove(playerUuid);
    }

    public void removePlayer(UUID playerUuid) {
        this.removePlayerResults(playerUuid);
        this.perPlayerActiveCount.remove(playerUuid);

        // Clean active — remove callbacks. If empty, remove the entry.
        // The async future may still complete, but onChunkReady will find
        // no entry in the active map and do nothing.
        var activeIter = this.active.entrySet().iterator();
        while (activeIter.hasNext()) {
            var gen = activeIter.next().getValue();
            gen.callbacks.removeIf(cb -> cb.playerUuid.equals(playerUuid));
            if (gen.callbacks.isEmpty()) {
                activeIter.remove();
            }
        }

        var waitIter = this.waiting.entrySet().iterator();
        while (waitIter.hasNext()) {
            var gen = waitIter.next().getValue();
            gen.callbacks.removeIf(cb -> cb.playerUuid.equals(playerUuid));
            if (gen.callbacks.isEmpty()) {
                waitIter.remove();
            }
        }
    }

    public void shutdown() {
        // No manual ticket cleanup needed — Paper manages tickets via getChunkAtAsync.
        // Active async futures will complete but onChunkReady will find empty active map.
        this.active.clear();
        this.waiting.clear();
        this.perPlayerActiveCount.clear();
        this.playerResults.clear();
    }

    public String getDiagnostics() {
        return String.format("submitted=%d, completed=%d, active=%d, waiting=%d, timeouts=%d, evicted=%d",
                totalSubmitted, totalCompleted, active.size(), waiting.size(), totalTimeouts, totalEvicted);
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
