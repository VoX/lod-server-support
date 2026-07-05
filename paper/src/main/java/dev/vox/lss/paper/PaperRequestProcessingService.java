package dev.vox.lss.paper;

import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.IncomingRequest;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.TickDiagnostics;
import dev.vox.lss.common.processing.TickSnapshot;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Core orchestrator for per-player LOD request processing on Paper.
 * Adapted from Fabric's RequestProcessingService with Plugin Messaging send
 * calls.
 */
public class PaperRequestProcessingService {
    private final Map<UUID, PaperPlayerRequestState> players;
    private final MinecraftServer server;
    private final PaperChunkDiskReader diskReader;
    private final PaperChunkGenerationService generationService;
    private final SharedBandwidthLimiter bandwidthLimiter;
    private final PaperConfig config;
    private final PaperOffThreadProcessor offThreadProcessor;
    private final DirtyColumnTracker dirtyTracker;
    private final PaperDirtyColumnBroadcaster dirtyBroadcaster;

    private final long startTimeNanos = System.nanoTime();
    // Keyed by the lightweight ResourceKey (not ServerLevel): a ServerLevel key strongly
    // retains every world an LSS player ever visited — including unloaded ones on
    // world-cycling Paper servers (Multiverse/minigames). The dimension string is derivable
    // from the key.
    private final Map<ResourceKey<Level>, String> dimensionStringCache = new HashMap<>();

    private int diagLogCounter = 0;

    private volatile boolean shuttingDown = false;

    private final TickDiagnostics diag = new TickDiagnostics();



    private static final int DIAG_LOG_INTERVAL_TICKS = 100;
    private static final int MAX_PROBES_PER_TICK_PER_PLAYER = 512;

    /** Test seam: puts one encoded voxel-column frame on the wire. Production default is the
     *  raw NMS payload send; tests inject recording/throwing senders. */
    @FunctionalInterface
    interface ColumnPayloadSender {
        void send(PaperPlayerRequestState state, byte[] data) throws Exception;
    }

    /** Test seam: resolves a loaded chunk into pre-serialized column data, or null when the
     *  chunk is not loaded. Production default probes the chunk source on the main thread. */
    @FunctionalInterface
    interface LoadedColumnProbe {
        LoadedColumnData probe(ServerLevel level, int cx, int cz);
    }

    private ColumnPayloadSender columnPayloadSender = (state, data) ->
            PaperPayloadHandler.sendRawNmsPayload(state.getPlayer().getBukkitEntity(),
                    PaperPayloadHandler.ID_VOXEL_COLUMN, data);

    private LoadedColumnProbe loadedColumnProbe = (level, cx, cz) -> {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        return chunk != null ? PaperSectionSerializer.serializeColumn(level, chunk, cx, cz) : null;
    };

    void setColumnPayloadSender(ColumnPayloadSender sender) {
        this.columnPayloadSender = sender;
    }

    void setLoadedColumnProbe(LoadedColumnProbe probe) {
        this.loadedColumnProbe = probe;
    }

    // ---- Regionized loaded-chunk probing (Folia) ----
    //
    // On Folia the pump runs on the global region thread, which owns no chunks — yet
    // getChunkNow still RETURNS loaded chunks there (Moonrise's full-chunk map has no
    // ownership check; Folia soak baselines show ~150 in-memory serves per fresh-backfill).
    // Every one of those serves serialized a chunk the owning region thread may have been
    // mutating concurrently: a torn palette read shipped to the client as "up to date".
    // Regionized probing removes that race: the pump dispatches a probe task to each
    // player's owning region via the EntityScheduler; the task serializes only chunks that
    // region owns (isOwnedByCurrentRegion) and publishes an immutable-after-publish batch
    // the pump consumes into a later snapshot.
    //
    // Probe results are useless if they trail their requests: the router drains the whole
    // incoming queue every cycle, so a probe published between ticks T and T+1 describes
    // requests that were already routed at cycle T (a soak run measured exactly 0 in-memory
    // serves with that shape). The pump therefore HOLDS each tick's fresh arrivals for one
    // tick: at tick T it drains them, schedules their probe task, and parks them; at T+1 it
    // consumes the published batch and re-injects the parked requests, so routing cycle T+1
    // sees request and probe result in the same snapshot. Costs ~50 ms added latency per
    // request on Folia only — noise against the client's 1 s scan cadence. A probe task
    // that runs late simply misses: the release is unconditional, the requests route to
    // disk/generation as before, and the orphan batch is discarded on its next consume.

    /** Test seam: runs a task on the player's owning region. Production default is the
     *  EntityScheduler (the main thread on Paper); a task whose entity is removed before
     *  it runs is silently retired. */
    @FunctionalInterface
    interface RegionTaskScheduler {
        void schedule(ServerPlayer player, Runnable task);
    }

    /** Test seam: whether the current thread owns the chunk. getChunkNow + section reads
     *  are only race-free for chunks the executing region owns — a player's request disc
     *  can overlap a foreign region (another player's loaded area). */
    @FunctionalInterface
    interface RegionOwnershipCheck {
        boolean ownsChunk(ServerLevel level, int cx, int cz);
    }

    private RegionTaskScheduler regionTaskScheduler = (player, task) ->
            player.getBukkitEntity().getScheduler().run(this.plugin, t -> task.run(), null);

    private RegionOwnershipCheck regionOwnershipCheck = (level, cx, cz) ->
            Bukkit.isOwnedByCurrentRegion(level.getWorld(), cx, cz);

    /** Probing mode. Defaults to regionized on Folia only: on Paper the sync probe is both
     *  correct and a tick fresher. Package-visible for tests (IS_FOLIA is false in JUnit). */
    private boolean regionizedProbing = FoliaSupport.IS_FOLIA;

    void setRegionizedProbing(boolean regionized) {
        this.regionizedProbing = regionized;
    }

    void setRegionTaskScheduler(RegionTaskScheduler scheduler) {
        this.regionTaskScheduler = scheduler;
    }

    void setRegionOwnershipCheck(RegionOwnershipCheck check) {
        this.regionOwnershipCheck = check;
    }

    /** Region-thread → pump hand-off. The dimension is captured on the region thread so a
     *  dimension change between publish and consume discards the batch instead of serving
     *  old-dimension bytes under the new dimension. The probes map is mutated only inside
     *  {@code regionProbeResults.compute} (merge) and owned by the pump after {@code remove}. */
    record RegionProbeBatch(String dimension, Long2ObjectOpenHashMap<LoadedColumnData> probes) {}

    private final ConcurrentHashMap<UUID, RegionProbeBatch> regionProbeResults = new ConcurrentHashMap<>();

    /** Pump-only. Requests drained at tick T, released into the routing queue at T+1 once
     *  their probe task has had a region tick to publish. */
    private final Map<UUID, List<IncomingRequest>> heldForProbe = new HashMap<>();

    /** Collaborator set for the package-private constructor. Tests build it over recording
     *  collaborators; production wiring lives in {@link #productionWiring} only. */
    record Wiring(Map<UUID, PaperPlayerRequestState> players,
                  PaperChunkDiskReader diskReader,
                  PaperChunkGenerationService generationService,
                  PaperOffThreadProcessor offThreadProcessor,
                  DirtyColumnTracker dirtyTracker,
                  PaperDirtyColumnBroadcaster dirtyBroadcaster) {}

    // Null in test wiring — only the production-default regionTaskScheduler dereferences it,
    // and probe tests always inject a recording scheduler.
    private Plugin plugin;

    public PaperRequestProcessingService(MinecraftServer server, Plugin plugin, PaperConfig config) {
        this(server, config, productionWiring(server, plugin, config));
        this.plugin = plugin;
    }

    /** Test seam: same field wiring as production, collaborators injected. */
    PaperRequestProcessingService(MinecraftServer server, PaperConfig config, Wiring wiring) {
        this.server = server;
        this.config = config;
        this.players = wiring.players();
        this.diskReader = wiring.diskReader();
        this.generationService = wiring.generationService();
        this.bandwidthLimiter = new SharedBandwidthLimiter(config.bytesPerSecondLimitGlobal);
        this.offThreadProcessor = wiring.offThreadProcessor();
        this.dirtyTracker = wiring.dirtyTracker();
        this.dirtyBroadcaster = wiring.dirtyBroadcaster();
    }

    private static Wiring productionWiring(MinecraftServer server, Plugin plugin, PaperConfig config) {
        Map<UUID, PaperPlayerRequestState> players = new ConcurrentHashMap<>();
        var diskReader = new PaperChunkDiskReader(config.diskReaderThreads);
        PaperChunkGenerationService generationService = config.enableChunkGeneration
                ? new PaperChunkGenerationService(config, plugin) : null;

        var dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
        var offThreadProcessor = new PaperOffThreadProcessor(
                players, diskReader, generationService != null, dataDir,
                config.perDimensionTimestampCacheSizeMB);
        offThreadProcessor.start();

        var dirtyTracker = new DirtyColumnTracker();
        var dirtyBroadcaster = new PaperDirtyColumnBroadcaster(
                server, players, dirtyTracker, offThreadProcessor);
        return new Wiring(players, diskReader, generationService, offThreadProcessor,
                dirtyTracker, dirtyBroadcaster);
    }

    public DirtyColumnTracker getDirtyTracker() {
        return this.dirtyTracker;
    }

    /** Cross-thread lifecycle ingress. On Folia, handshakes and PlayerQuit arrive on region
     *  threads; registerPlayer/removePlayer mutate pump-owned state (including the generation
     *  service's non-concurrent maps), so region-thread callers enqueue here and tick() drains
     *  first — one queue preserves arrival order across a kick→rejoin of the same UUID. */
    private sealed interface LifecycleEvent {
        record Register(ServerPlayer player, int capabilities) implements LifecycleEvent {}
        record Remove(UUID uuid) implements LifecycleEvent {}
    }

    private final ConcurrentLinkedQueue<LifecycleEvent> lifecycleMailbox = new ConcurrentLinkedQueue<>();

    /** Any thread. Applied at the top of the next tick(). */
    public void enqueueRegister(ServerPlayer player, int capabilities) {
        this.lifecycleMailbox.add(new LifecycleEvent.Register(player, capabilities));
    }

    /** Any thread. Applied at the top of the next tick(). */
    public void enqueueRemove(UUID uuid) {
        this.lifecycleMailbox.add(new LifecycleEvent.Remove(uuid));
    }

    private void drainLifecycleMailbox() {
        LifecycleEvent ev;
        while ((ev = this.lifecycleMailbox.poll()) != null) {
            switch (ev) {
                case LifecycleEvent.Register r -> registerPlayer(r.player(), r.capabilities());
                case LifecycleEvent.Remove r -> removePlayer(r.uuid());
            }
        }
    }

    public PaperPlayerRequestState registerPlayer(ServerPlayer player, int capabilities) {
        var state = this.players.computeIfAbsent(player.getUUID(), uuid -> {
            var s = new PaperPlayerRequestState(player,
                    this.config.syncOnLoadConcurrencyLimitPerPlayer,
                    this.config.generationConcurrencyLimitPerPlayer);
            // Session identity for the router's stale-snapshot guard (set before the map
            // publish so the processing thread never sees it null on a live state).
            s.setRegisteredDimension(player.level().dimension().identifier().toString());
            return s;
        });
        this.diskReader.registerPlayer(player.getUUID());
        state.setCapabilities(capabilities);
        state.markHandshakeComplete();
        return state;
    }

    public void removePlayer(UUID uuid) {
        this.players.remove(uuid);
        this.regionProbeResults.remove(uuid);
        this.heldForProbe.remove(uuid);
        this.offThreadProcessor.notifyPlayerRemoved(uuid);
        cleanupPlayerServices(uuid);
    }

    private void cleanupPlayerServices(UUID uuid) {
        this.diskReader.removePlayerResults(uuid);
        if (this.generationService != null)
            this.generationService.removePlayer(uuid);
    }

    public void handleBatchRequest(ServerPlayer player, PaperPayloadHandler.DecodedBatchChunkRequest batch) {
        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDist = this.config.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;

        for (int i = 0; i < batch.count(); i++) {
            long packedPosition = batch.packedPositions()[i];
            int cx = PositionUtil.unpackX(packedPosition);
            int cz = PositionUtil.unpackZ(packedPosition);
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > maxDist) continue;
            state.addRequest(cx, cz, batch.clientTimestamps()[i]);
        }
    }

    public void tick() {
        // shuttingDown FIRST: an overlapped tick during a runtime plugin-manager disable must
        // not apply lifecycle events into mid-teardown collaborators (registerPlayer racing
        // players.clear() / a shut-down disk reader). Post-shutdown mailbox growth is bounded:
        // onDisable nulls the service field, so producers stop within a tick.
        if (this.shuttingDown)
            return;
        // ...but BEFORE the enabled guard: a disabled server still receives quits (onPlayerQuit
        // enqueues unconditionally) and the queue must not grow unbounded. Draining while
        // disabled is safe by construction: HandshakeGate never invokes the registrar when
        // disabled, and removePlayer of an unregistered UUID is a no-op.
        drainLifecycleMailbox();
        if (!this.config.enabled)
            return;

        this.diag.reset(this.offThreadProcessor.getDiagnostics());

        var generationReady = tickGenerationService();
        var lifecycle = processPlayerLifecycle(generationReady);

        if (lifecycle.toRemove != null) {
            for (UUID uuid : lifecycle.toRemove)
                this.removePlayer(uuid);
        }

        if (this.regionizedProbing && !this.regionProbeResults.isEmpty()) {
            // A region task can publish after its player was removed (late publish); without
            // this sweep the batch would pin its column data until the same UUID rejoined.
            this.regionProbeResults.keySet().removeIf(uuid -> !this.players.containsKey(uuid));
        }

        postSnapshot(lifecycle, generationReady);
        this.drainSendActions();
        this.drainGenerationTicketRequests();
        flushSendQueues(lifecycle.activeCount);
        this.dirtyBroadcaster.tick(this.config);
        tickDiagnosticsLog();
    }

    private List<TickSnapshot.GenerationReadyData> tickGenerationService() {
        if (this.generationService == null)
            return List.of();
        return this.generationService.tick();
    }

    private record LifecycleResult(
            Map<UUID, String> playerDimensions,
            Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes,
            int activeCount,
            List<UUID> toRemove) {
    }

    private LifecycleResult processPlayerLifecycle(
            List<TickSnapshot.GenerationReadyData> generationReady) {
        // Allocated fresh each tick: the snapshot owns these maps after postSnapshot, so the
        // processing thread can iterate them without racing the next tick's lifecycle pass.
        Map<UUID, String> playerDimensions = new HashMap<>();
        Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes = new HashMap<>();

        // Per-player set of generation-outcome positions to skip in probeLoadedChunks
        Map<UUID, LongOpenHashSet> genReadyPositions = TickSnapshot.groupPositionsByPlayer(generationReady);

        int activeCount = 0;
        List<UUID> toRemove = null;
        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake())
                continue;
            activeCount++;
            this.diag.updateQueuePeak(state.getSendQueueSize());

            boolean removed = false;

            if (state.getPlayer().isRemoved()) {
                var current = this.server.getPlayerList().getPlayer(state.getPlayer().getUUID());
                if (current == null) {
                    if (toRemove == null)
                        toRemove = new ArrayList<>();
                    toRemove.add(state.getPlayer().getUUID());
                    removed = true;
                } else {
                    state.updatePlayer(current);
                }
            }

            if (removed)
                continue;

            if (state.checkDimensionChange()) {
                // A dimension change abandons all in-flight work. Reuse the (well-tested)
                // disconnect teardown + a fresh registration instead of a second, hand-rolled
                // partial-reset protocol: the processing thread unwinds the old state's dedup
                // groups via the removal event, and the fresh state starts clean next tick.
                var changed = state.getPlayer();
                int capabilities = state.getCapabilities();
                removePlayer(changed.getUUID());
                registerPlayer(changed, capabilities);
                continue;
            }

            var player = state.getPlayer();
            var level = player.level();
            String dimension = this.dimensionStringCache.computeIfAbsent(level.dimension(),
                    k -> k.identifier().toString());

            this.offThreadProcessor.updateDimensionContext(dimension, level);

            playerDimensions.put(player.getUUID(), dimension);

            var skipPositions = genReadyPositions != null
                    ? genReadyPositions.get(player.getUUID()) : null;
            Long2ObjectMap<LoadedColumnData> probes;
            if (this.regionizedProbing) {
                // Consume last tick's region-published batch, then advance the hold-release
                // pipeline (release last tick's arrivals, park + probe this tick's). The
                // sync probe is skipped entirely: the pump owns no chunks on Folia.
                probes = consumeRegionProbes(player.getUUID(), dimension, skipPositions);
                holdAndScheduleRegionProbe(state, player, level, skipPositions);
            } else {
                probes = this.probeLoadedChunks(state, level, skipPositions);
            }
            if (probes != null && !probes.isEmpty()) {
                loadedChunkProbes.put(player.getUUID(), probes);
            }
        }

        return new LifecycleResult(playerDimensions, loadedChunkProbes, activeCount, toRemove);
    }

    private void postSnapshot(LifecycleResult lifecycle,
            List<TickSnapshot.GenerationReadyData> generationReady) {
        var snapshot = new TickSnapshot(
                lifecycle.playerDimensions, lifecycle.loadedChunkProbes,
                this.config.sendQueueLimitPerPlayer, false);
        this.offThreadProcessor.postSnapshot(snapshot, generationReady);
    }

    private void flushSendQueues(int activeCount) {
        long perPlayerAllocation = this.bandwidthLimiter.getPerPlayerAllocation(activeCount);
        long perPlayerCap = Math.min(perPlayerAllocation, this.config.bytesPerSecondLimitPerPlayer);

        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake())
                continue;
            long[] dropped = state.flushSendQueue(perPlayerCap, this.bandwidthLimiter, this.diag,
                    data -> this.columnPayloadSender.send(state, data));
            if (dropped.length > 0) {
                // A send failure discarded resolved-but-undelivered columns: clear their
                // done-bits so the client's re-requests re-resolve instead of being
                // answered up-to-date for data that never arrived.
                this.offThreadProcessor.clearDiskReadDone(state.getPlayerUUID(), dropped);
            }
        }
    }

    private void tickDiagnosticsLog() {
        if (++this.diagLogCounter >= DIAG_LOG_INTERVAL_TICKS) {
            this.diagLogCounter = 0;
            DiagnosticsFormatter.logDebugSummary(this.diag, this.getUptimeSeconds(),
                    this.config.bytesPerSecondLimitGlobal, this.bandwidthLimiter, this.players.values());
        }
    }

    private Long2ObjectMap<LoadedColumnData> probeLoadedChunks(
            PaperPlayerRequestState state, ServerLevel level,
            LongOpenHashSet skipPositions) {
        var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
        int probed = 0;

        for (var req : state.getIncomingRequests()) {
            if (probed >= MAX_PROBES_PER_TICK_PER_PLAYER)
                break;
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (probes.containsKey(packed))
                continue;
            if (skipPositions != null && skipPositions.contains(packed))
                continue;

            var column = this.loadedColumnProbe.probe(level, req.cx(), req.cz());
            if (column != null) {
                probes.put(packed, column);
            }
            probed++;
        }

        return probes;
    }

    /** Pump only. Takes ownership of the player's published batch (if any) and applies the
     *  same skip contract the sync probe honors: a position with a generation outcome in
     *  this snapshot must not also appear as a probe. Returns null when nothing usable. */
    private Long2ObjectMap<LoadedColumnData> consumeRegionProbes(UUID uuid, String dimension,
                                                                 LongOpenHashSet skipPositions) {
        var batch = this.regionProbeResults.remove(uuid);
        if (batch == null) return null;
        // Serialized under the dimension the player was in when the task ran; a dimension
        // change in between must not serve old-dimension bytes under the new dimension.
        if (!batch.dimension().equals(dimension)) return null;
        if (skipPositions != null) {
            for (long packed : skipPositions) {
                batch.probes().remove(packed);
            }
        }
        return batch.probes();
    }

    /** Pump only. Advances the one-tick hold-release pipeline: drains this tick's fresh
     *  arrivals, releases the previous tick's held batch back into the routing queue (its
     *  probe results were consumed just above), then parks the fresh batch and hands its
     *  positions to the player's owning region. The processing thread may concurrently
     *  poll the same queue for an in-flight routing cycle — either consumer owning a given
     *  request is correct (a stolen request routes without probe results, exactly as a
     *  non-regionized request would). */
    private void holdAndScheduleRegionProbe(PaperPlayerRequestState state, ServerPlayer player,
                                            ServerLevel level, LongOpenHashSet skipPositions) {
        List<IncomingRequest> fresh = null;
        IncomingRequest req;
        while ((req = state.pollIncomingRequest()) != null) {
            if (fresh == null) fresh = new ArrayList<>();
            fresh.add(req);
        }

        var released = this.heldForProbe.remove(player.getUUID());
        if (released != null) {
            for (var r : released) {
                state.reinjectIncomingRequest(r);
            }
        }

        if (fresh == null) return;
        this.heldForProbe.put(player.getUUID(), fresh);

        long[] positions = snapshotProbePositions(fresh, skipPositions);
        if (positions.length == 0) return;
        UUID uuid = player.getUUID();
        this.regionTaskScheduler.schedule(player, () -> runRegionProbe(uuid, level, positions));
    }

    private static final long[] NO_POSITIONS = new long[0];

    /** Up to {@link #MAX_PROBES_PER_TICK_PER_PLAYER} distinct positions from the held batch. */
    private long[] snapshotProbePositions(List<IncomingRequest> held,
                                          LongOpenHashSet skipPositions) {
        LongOpenHashSet positions = null;
        for (var req : held) {
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (skipPositions != null && skipPositions.contains(packed)) continue;
            if (positions == null) positions = new LongOpenHashSet();
            if (!positions.add(packed)) continue;
            if (positions.size() >= MAX_PROBES_PER_TICK_PER_PLAYER) break;
        }
        return positions == null ? NO_POSITIONS : positions.toLongArray();
    }

    /** Region-thread task body. Touches no pump state: reads the level behind the ownership
     *  guard, serializes matches through the shared probe seam, and publishes one batch via
     *  compute (merge under the bin lock; the pump takes ownership atomically via remove). */
    private void runRegionProbe(UUID uuid, ServerLevel level, long[] positions) {
        Long2ObjectOpenHashMap<LoadedColumnData> found = null;
        for (long packed : positions) {
            int cx = PositionUtil.unpackX(packed);
            int cz = PositionUtil.unpackZ(packed);
            if (!this.regionOwnershipCheck.ownsChunk(level, cx, cz)) continue;
            var column = this.loadedColumnProbe.probe(level, cx, cz);
            if (column != null) {
                if (found == null) found = new Long2ObjectOpenHashMap<>();
                found.put(packed, column);
            }
        }
        if (found == null) return;
        var batch = new RegionProbeBatch(level.dimension().identifier().toString(), found);
        this.regionProbeResults.compute(uuid, (k, prev) -> {
            if (prev == null || !prev.dimension().equals(batch.dimension())) return batch;
            prev.probes().putAll(batch.probes());
            return prev;
        });
    }

    private void drainSendActions() {
        this.offThreadProcessor.drainSendActions((state, types, positions, count) ->
                PaperPayloadHandler.sendBatchResponse(state.getPlayer().getBukkitEntity(),
                        types, positions, count));
    }

    private void drainGenerationTicketRequests() {
        if (this.generationService == null)
            return;

        OffThreadProcessor.GenerationTicketRequest req;
        while ((req = this.offThreadProcessor.pollGenerationTicketRequest()) != null) {
            var state = this.players.get(req.playerUuid());
            if (state == null || !state.hasCompletedHandshake())
                continue;

            var player = state.getPlayer();
            var level = player.level();
            String dimension = this.dimensionStringCache.computeIfAbsent(level.dimension(),
                    k -> k.identifier().toString());
            // Ticket queued before a dimension change targets the old dimension's coordinates.
            // Dropping it leaks nothing: the admitting state was discarded by
            // removePlayer+registerPlayer (its slot dies with it), AND that same removePlayer
            // enqueues the removal event that sweeps the processing thread's UUID-keyed
            // generation in-flight tracking (removeGenerationTracking) — without that sweep the
            // dropped ticket's tracking would leak (do not add a drop path that skips it).
            if (!dimension.equals(req.dimension())) continue;
            boolean accepted = !player.isRemoved() && this.generationService.submitGeneration(
                    req.playerUuid(), level, req.cx(), req.cz(),
                    req.submissionOrder());
            if (!accepted) {
                // Capacity rejection or removed player: feed a failure outcome so the
                // processing thread frees the pending slot and tells the client ColumnNotGenerated.
                this.offThreadProcessor.feedGenerationFailure(
                        req.playerUuid(), req.cx(), req.cz(), dimension, req.submissionOrder());
            }
        }
    }

    public Map<UUID, PaperPlayerRequestState> getPlayers() {
        return Collections.unmodifiableMap(this.players);
    }

    public PaperChunkDiskReader getDiskReader() {
        return this.diskReader;
    }

    public PaperChunkGenerationService getGenerationService() {
        return this.generationService;
    }

    public SharedBandwidthLimiter getBandwidthLimiter() {
        return this.bandwidthLimiter;
    }

    public String getTickDiagnostics() {
        return this.diag.format(this.config.sendQueueLimitPerPlayer);
    }

    public TickDiagnostics getTickDiag() {
        return this.diag;
    }

    public long getWindowBandwidthRate() {
        return this.diag.getWindowBytesPerSecond();
    }

    public long getUptimeSeconds() {
        return (System.nanoTime() - this.startTimeNanos) / LSSConstants.NANOS_PER_SECOND;
    }

    public OffThreadProcessor<?> getOffThreadProcessor() {
        return this.offThreadProcessor;
    }

    public PaperConfig getConfig() {
        return this.config;
    }

    public void shutdown() {
        // Normal stop and /reload are serialized with the pump (region shutdown thread /
        // global tick thread), but a runtime plugin manager can disable us from a player
        // region thread — this flag shrinks the tick-vs-shutdown overlap to at most the one
        // in-flight tick (runtime disables are documented best-effort on Folia).
        this.shuttingDown = true;
        try {
            // Marks accumulated since the last broadcast interval must still invalidate the
            // timestamp cache BEFORE its final save (the invalidations ride the shutdown
            // sentinel take) — otherwise the persisted stamps answer false up_to_date for
            // edited columns across the restart.
            for (var entry : this.dirtyTracker.drainAll().entrySet()) {
                this.offThreadProcessor.invalidateTimestamps(entry.getKey(), entry.getValue());
            }
            this.offThreadProcessor.shutdown();
        } catch (Exception e) {
            LSSLogger.error("Error shutting down off-thread processor", e);
        }
        this.players.clear();
        try {
            this.diskReader.shutdown();
        } catch (Exception e) {
            LSSLogger.error("Error shutting down disk reader", e);
        }
        try {
            if (this.generationService != null) {
                this.generationService.shutdown();
            }
        } catch (Exception e) {
            LSSLogger.error("Error shutting down generation service", e);
        }
    }
}
