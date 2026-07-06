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

public class ChunkGenerationService {
    // timeout=0: we manage lifetime ourselves; persist=false; LOADING makes the chunk load/generate
    private static final TicketType LSS_GEN_TICKET = new TicketType(0L, false, TicketType.TicketUse.LOADING);

    record GenerationCallback(UUID playerUuid, long submissionOrder) {}

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

    /** Serializes a completed chunk into wire-format column data. Test seam (D9): injectable
     *  so a Throwable mid-serialization can be exercised against the release-in-finally
     *  contract without corrupting a real chunk; production always wires
     *  {@link SectionSerializer#serializeColumn}. */
    @FunctionalInterface
    public interface ColumnSerializer {
        LoadedColumnData serialize(ServerLevel level, LevelChunk chunk, int cx, int cz);
    }

    private final LinkedHashMap<PendingGenerationKey, PendingGeneration> active = new LinkedHashMap<>();
    private final Map<UUID, Integer> perPlayerActiveCount = new HashMap<>();

    private final int maxConcurrent;
    private final int maxPerPlayerActive;
    private final int timeoutTicks;
    private final ColumnSerializer columnSerializer;
    private DirtyContentFilter dirtyContentFilter;

    // Volatile is sufficient — only written from the main tick thread, read by /stats commands.
    private volatile long totalSubmitted = 0;
    private volatile long totalCompleted = 0;
    private volatile long totalTimeouts = 0;
    private volatile long totalRemovedInFlight = 0;

    public ChunkGenerationService(LSSServerConfig config) {
        this(config, SectionSerializer::serializeColumn);
    }

    /** Test seam constructor (see {@link ColumnSerializer}); zero behavior change when
     *  default-wired through the production constructor above. */
    public ChunkGenerationService(LSSServerConfig config, ColumnSerializer columnSerializer) {
        this.maxConcurrent = config.generationConcurrencyLimitGlobal;
        this.maxPerPlayerActive = config.generationConcurrencyLimitPerPlayer;
        this.timeoutTicks = config.generationTimeoutSeconds * LSSConstants.TICKS_PER_SECOND;
        this.columnSerializer = columnSerializer;
    }

    /** Wired by RequestProcessingService after construction (it owns the filter). */
    public void setDirtyContentFilter(DirtyContentFilter filter) {
        this.dirtyContentFilter = filter;
    }

    /**
     * Submit a generation request. Returns true if accepted (piggyback or new active slot),
     * false if at capacity (caller should feed back a rejection result).
     */
    public boolean submitGeneration(UUID playerUuid, ServerLevel level, int cx, int cz, long submissionOrder) {
        var key = new PendingGenerationKey(level.dimension(), cx, cz);

        // Already active — piggyback on existing entry
        var existing = this.active.get(key);
        if (existing != null) {
            existing.callbacks.add(new GenerationCallback(playerUuid, submissionOrder));
            incrementCount(this.perPlayerActiveCount, playerUuid);
            return true;
        }

        // Try to add directly to active
        int playerActive = this.perPlayerActiveCount.getOrDefault(playerUuid, 0);
        if (this.active.size() < this.maxConcurrent && playerActive < this.maxPerPlayerActive) {
            var pos = new ChunkPos(cx, cz);
            level.getChunkSource().addTicketWithRadius(LSS_GEN_TICKET, pos, 0);

            var gen = new PendingGeneration(pos, level);
            gen.callbacks.add(new GenerationCallback(playerUuid, submissionOrder));
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
     * returns one GenerationReadyData per callback for the processing thread — successes carry
     * column data; timeouts and extraction errors carry {@code columnData == null}.
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
                if (ready == null) ready = new ArrayList<>();
                addFailures(ready, gen);
                gen.level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, gen.pos, 0);
                iter.remove();
                this.totalTimeouts++;
                continue;
            }

            LevelChunk chunk = gen.level.getChunkSource().getChunkNow(gen.pos.x, gen.pos.z);
            if (chunk != null) {
                if (ready == null) ready = new ArrayList<>();
                try {
                    long columnTimestamp = LSSConstants.epochSeconds();
                    LoadedColumnData columnData = this.columnSerializer.serialize(
                            gen.level, chunk, gen.pos.x, gen.pos.z);
                    String dimension = gen.level.dimension().location().toString();

                    // Seed the dirty filter with the served bytes: the chunk's imminent
                    // unload-save would otherwise count as "first observed save" and
                    // trigger a pointless second send of the identical column.
                    if (this.dirtyContentFilter != null) {
                        this.dirtyContentFilter.seed(dimension, gen.pos.x, gen.pos.z,
                                columnData.serializedSections());
                    }

                    // One GenerationReadyData per callback — processing thread will voxelize
                    for (var cb : gen.callbacks) {
                        ready.add(new TickSnapshot.GenerationReadyData(
                                cb.playerUuid, gen.pos.x, gen.pos.z, dimension,
                                columnData, columnTimestamp, cb.submissionOrder));
                        decrementCount(this.perPlayerActiveCount, cb.playerUuid);
                    }
                    this.totalCompleted++;
                } catch (Throwable t) {
                    LSSLogger.error("Failed to extract primitives for generated chunk at " + gen.pos.x + ", " + gen.pos.z, t);
                    addFailures(ready, gen);
                    // Failed extraction is a terminal removal that is neither completed nor a
                    // timeout — count it as removed-in-flight so the generation books
                    // (submitted == completed + timeouts + removed) still balance (soak law A4).
                    this.totalRemovedInFlight++;
                } finally {
                    // Always release the force-load ticket and drop the active entry — even on an
                    // Error during serialization — or the chunk stays force-loaded forever and the
                    // entry is retried (and re-throws) every server tick.
                    gen.level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, gen.pos, 0);
                    iter.remove();
                }
            }
        }
        return ready != null ? ready : List.of();
    }

    /** Add a failure outcome (columnData == null) for every callback of the entry. */
    private void addFailures(List<TickSnapshot.GenerationReadyData> ready, PendingGeneration gen) {
        String dimension = gen.level.dimension().location().toString();
        for (var cb : gen.callbacks) {
            ready.add(new TickSnapshot.GenerationReadyData(
                    cb.playerUuid, gen.pos.x, gen.pos.z, dimension,
                    null, 0L, cb.submissionOrder));
            decrementCount(this.perPlayerActiveCount, cb.playerUuid);
        }
    }

    public void removePlayer(UUID playerUuid) {
        this.perPlayerActiveCount.remove(playerUuid);

        // Clean up active entries
        var iter = this.active.entrySet().iterator();
        while (iter.hasNext()) {
            var gen = iter.next().getValue();
            gen.callbacks.removeIf(cb -> cb.playerUuid.equals(playerUuid));
            if (gen.callbacks.isEmpty()) {
                gen.level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, gen.pos, 0);
                iter.remove();
                // Submitted but neither completed nor timed out — without this counter the
                // submitted/completed books can never re-balance after a kick or dimension change
                this.totalRemovedInFlight++;
            }
        }
    }

    public void shutdown() {
        for (var gen : this.active.values()) {
            gen.level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, gen.pos, 0);
        }
        this.active.clear();
        this.perPlayerActiveCount.clear();
    }

    public String getDiagnostics() {
        return String.format("submitted=%d, completed=%d, active=%d, timeouts=%d, removed=%d",
                totalSubmitted, totalCompleted, active.size(), totalTimeouts, totalRemovedInFlight);
    }

    public long getTotalSubmitted() { return totalSubmitted; }
    public long getTotalCompleted() { return totalCompleted; }
    public long getTotalTimeouts() { return totalTimeouts; }
    public long getTotalRemovedInFlight() { return totalRemovedInFlight; }
    public int getActiveCount() { return active.size(); }

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
