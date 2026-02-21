package dev.vox.lss.networking.server;

import dev.vox.lss.LSSLogger;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.ChunkSectionS2CPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
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

public class ChunkGenerationService {
    // flags=2 (LOADING) makes the chunk load/generate; timeout=0 means we manage lifetime ourselves
    private static final TicketType LSS_GEN_TICKET = new TicketType(0, 2);

    record Callback(UUID playerUuid, int batchId) {}

    private record PendingKey(ResourceKey<Level> dimension, int cx, int cz) {}

    static class PendingGeneration {
        final ChunkPos pos;
        final ServerLevel level;
        final List<Callback> callbacks = new ArrayList<>();
        int ticksWaiting = 0;

        PendingGeneration(ChunkPos pos, ServerLevel level) {
            this.pos = pos;
            this.level = level;
        }
    }

    private final LinkedHashMap<PendingKey, PendingGeneration> active = new LinkedHashMap<>();
    private final LinkedHashMap<PendingKey, PendingGeneration> waiting = new LinkedHashMap<>();
    private final Map<UUID, Integer> perPlayerActiveCount = new HashMap<>();
    private final Map<UUID, Integer> perPlayerWaitingCount = new HashMap<>();
    private final ArrayDeque<ChunkDiskReader.ReadResult> results = new ArrayDeque<>();

    private final int maxConcurrent;
    private final int maxPerPlayerActive;
    private final int maxWaiting;
    private final int maxPerPlayerWaiting;
    private final int timeoutTicks;

    private long totalSubmitted = 0;
    private long totalCompleted = 0;
    private long totalTimeouts = 0;
    private long totalSkipped = 0;
    private long totalEvicted = 0;

    public ChunkGenerationService(LSSServerConfig config) {
        this.maxConcurrent = config.maxConcurrentGenerations;
        this.maxPerPlayerActive = config.maxConcurrentGenerationsPerPlayer;
        this.maxWaiting = config.maxWaitingGenerations;
        this.maxPerPlayerWaiting = config.maxWaitingGenerationsPerPlayer;
        this.timeoutTicks = config.generationTimeoutSeconds * 20;
    }

    public boolean submitGeneration(UUID playerUuid, int batchId, ServerLevel level, int cx, int cz) {
        var key = new PendingKey(level.dimension(), cx, cz);

        // Already active — piggyback on existing entry
        var existing = this.active.get(key);
        if (existing != null) {
            existing.callbacks.add(new Callback(playerUuid, batchId));
            incrementCount(this.perPlayerActiveCount, playerUuid);
            return true;
        }

        // Already waiting — piggyback on existing entry
        existing = this.waiting.get(key);
        if (existing != null) {
            existing.callbacks.add(new Callback(playerUuid, batchId));
            incrementCount(this.perPlayerWaitingCount, playerUuid);
            return true;
        }

        // Try to add directly to active
        int playerActive = this.perPlayerActiveCount.getOrDefault(playerUuid, 0);
        if (this.active.size() < this.maxConcurrent && playerActive < this.maxPerPlayerActive) {
            var pos = new ChunkPos(cx, cz);
            level.getChunkSource().addTicketWithRadius(LSS_GEN_TICKET, pos, 0);

            var gen = new PendingGeneration(pos, level);
            gen.callbacks.add(new Callback(playerUuid, batchId));
            this.active.put(key, gen);
            incrementCount(this.perPlayerActiveCount, playerUuid);
            this.totalSubmitted++;
            return true;
        }

        // Try to add to waiting queue
        int playerWaiting = this.perPlayerWaitingCount.getOrDefault(playerUuid, 0);
        if (this.waiting.size() < this.maxWaiting && playerWaiting < this.maxPerPlayerWaiting) {
            var pos = new ChunkPos(cx, cz);
            var gen = new PendingGeneration(pos, level);
            gen.callbacks.add(new Callback(playerUuid, batchId));
            this.waiting.put(key, gen);
            incrementCount(this.perPlayerWaitingCount, playerUuid);
            this.totalSubmitted++;
            return true;
        }

        this.totalSkipped++;
        return false;
    }

    public void tick(Map<UUID, PlayerRequestState> players) {
        this.tickActiveEntries();
        this.tickWaitingEntries();
        this.promoteWaitingEntries(players);
    }

    private void tickWaitingEntries() {
        int waitingTimeout = this.timeoutTicks * 2;
        var iter = this.waiting.entrySet().iterator();
        while (iter.hasNext()) {
            var gen = iter.next().getValue();
            gen.ticksWaiting++;

            if (gen.ticksWaiting > waitingTimeout) {
                for (var cb : gen.callbacks) {
                    this.results.add(new ChunkDiskReader.ReadResult(
                            cb.playerUuid, cb.batchId, gen.pos.x, gen.pos.z,
                            new ChunkSectionS2CPayload[0], 0L, false, false));
                    decrementCount(this.perPlayerWaitingCount, cb.playerUuid);
                }
                iter.remove();
                this.totalTimeouts++;
            }
        }
    }

    private void tickActiveEntries() {
        var iter = this.active.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            var gen = entry.getValue();
            gen.ticksWaiting++;

            if (gen.ticksWaiting > this.timeoutTicks) {
                for (var cb : gen.callbacks) {
                    this.results.add(new ChunkDiskReader.ReadResult(
                            cb.playerUuid, cb.batchId, gen.pos.x, gen.pos.z,
                            new ChunkSectionS2CPayload[0], 0L, false, false));
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
                    long columnTimestamp = gen.level.getGameTime();
                    var payloads = serializeChunkColumn(gen.level, chunk, gen.pos.x, gen.pos.z, columnTimestamp);

                    for (var cb : gen.callbacks) {
                        this.results.add(new ChunkDiskReader.ReadResult(
                                cb.playerUuid, cb.batchId, gen.pos.x, gen.pos.z,
                                payloads, columnTimestamp, false, false));
                        decrementCount(this.perPlayerActiveCount, cb.playerUuid);
                    }
                    this.totalCompleted++;
                } catch (Exception e) {
                    LSSLogger.error("Failed to serialize generated chunk at " + gen.pos.x + ", " + gen.pos.z, e);
                    for (var cb : gen.callbacks) {
                        this.results.add(new ChunkDiskReader.ReadResult(
                                cb.playerUuid, cb.batchId, gen.pos.x, gen.pos.z,
                                new ChunkSectionS2CPayload[0], 0L, false, false));
                        decrementCount(this.perPlayerActiveCount, cb.playerUuid);
                    }
                    this.totalCompleted++;
                }
                gen.level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, gen.pos, 0);
                iter.remove();
            }
        }
    }

    private void promoteWaitingEntries(Map<UUID, PlayerRequestState> players) {
        if (this.waiting.isEmpty()) return;

        var iter = this.waiting.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            var gen = entry.getValue();

            // Evict orphans — no live player among callbacks
            boolean hasLivePlayer = false;
            for (var cb : gen.callbacks) {
                if (players.containsKey(cb.playerUuid)) {
                    hasLivePlayer = true;
                    break;
                }
            }
            if (!hasLivePlayer) {
                for (var cb : gen.callbacks) {
                    this.results.add(new ChunkDiskReader.ReadResult(
                            cb.playerUuid, cb.batchId, gen.pos.x, gen.pos.z,
                            new ChunkSectionS2CPayload[0], 0L, false, false));
                    decrementCount(this.perPlayerWaitingCount, cb.playerUuid);
                }
                iter.remove();
                this.totalEvicted++;
                continue;
            }

            if (this.active.size() >= this.maxConcurrent) break;

            // Promote FIFO — backpressure ensures entries are already close-to-far
            iter.remove();
            for (var cb : gen.callbacks) {
                decrementCount(this.perPlayerWaitingCount, cb.playerUuid);
                incrementCount(this.perPlayerActiveCount, cb.playerUuid);
            }
            gen.ticksWaiting = 0;
            gen.level.getChunkSource().addTicketWithRadius(LSS_GEN_TICKET, gen.pos, 0);
            this.active.put(entry.getKey(), gen);
        }
    }

    private static ChunkSectionS2CPayload[] serializeChunkColumn(
            ServerLevel level, LevelChunk chunk, int cx, int cz, long columnTimestamp) {
        int minSectionY = level.getMinSectionY();
        var sections = chunk.getSections();

        var payloads = new ArrayList<ChunkSectionS2CPayload>();
        for (int i = 0; i < sections.length; i++) {
            int sectionY = minSectionY + i;
            var section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;

            var payload = RequestProcessingService.serializeLoadedSection(
                    level, section, cx, sectionY, cz, columnTimestamp);
            if (payload != null) {
                payloads.add(payload);
            }
        }
        return payloads.toArray(new ChunkSectionS2CPayload[0]);
    }

    public ChunkDiskReader.ReadResult pollResult() {
        return this.results.poll();
    }

    public void removePlayer(UUID playerUuid) {
        this.perPlayerActiveCount.remove(playerUuid);
        this.perPlayerWaitingCount.remove(playerUuid);

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

        // Clean up waiting entries
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
        for (var gen : this.active.values()) {
            gen.level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, gen.pos, 0);
        }
        this.active.clear();
        this.waiting.clear();
        this.perPlayerActiveCount.clear();
        this.perPlayerWaitingCount.clear();
        this.results.clear();
    }

    public String getDiagnostics() {
        return String.format("submitted=%d, completed=%d, active=%d, waiting=%d, timeouts=%d, skipped=%d, evicted=%d",
                totalSubmitted, totalCompleted, active.size(), waiting.size(), totalTimeouts, totalSkipped, totalEvicted);
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
