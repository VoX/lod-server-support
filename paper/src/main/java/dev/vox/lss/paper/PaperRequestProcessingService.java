package dev.vox.lss.paper;

import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.compat.V16CompatManager;
import dev.vox.lss.common.processing.IncomingBatch;
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
    // The v16 compat shim's per-player sessions (legacy protocol-16 clients). The pipeline
    // never consults it: a v16 player is an ordinary registered player whose want-set is
    // declared by the shim at 1 Hz. See docs/planning/v16-compat-design.md.
    private final V16CompatManager v16Compat = new V16CompatManager();

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
    // Global ceiling on in-memory column SERIALIZATIONS across ALL players in one pump tick — the
    // per-player cap bounds one player, but N backfilling players would otherwise cost up to
    // 512*N serializations on the pump. Counts serializations (the expensive work), not
    // examinations. Applies to the non-Folia pump probe below; the Folia region-probe path runs
    // off-pump on owning region threads, so the per-player cap suffices there — with one honest
    // caveat: "distributed" assumes players in DIFFERENT regions. N players clustered in one
    // region all probe on that region's single thread (up to 512*N there, uncapped globally);
    // acceptable while Folia support is experimental, revisit if clustered-players soak shows
    // region-tick pressure. Once spent, later players fall through to the disk-read path and
    // the 1 Hz re-declaration heals it.
    // Gen-disabled corner (accepted): with enableChunkGeneration=false, a LOADED but
    // never-saved chunk whose probe this cap deferred falls through to a disk read, resolves
    // not-found, and answers NOT_GENERATED — session-permanent on the client despite the
    // chunk being live in memory. Heals on its first save (dirty broadcast) or reconnect;
    // needs disabled generation + an exhausted budget + a never-saved chunk in one tick.
    private static final int MAX_PROBES_PER_TICK_GLOBAL = 2048;
    /** Rotating start index for the lifecycle pass (pump thread only) — see the loop comment. */
    private int probeRotation;

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

    /** Warn-once latch for the v16 egress splice guard (pump thread only). */
    private boolean v16SpliceWarned;

    /** The per-player column egress (PUMP). For a v16 session, splices the frame
     *  UNCONDITIONALLY into the legacy source-less shape — every producer
     *  (probe/disk/generation/ghost-clear) funnels through here, so no producer can leak a
     *  v18 frame that would hard-kick the old client — and prunes the position from the
     *  synthetic want-set after the send (satisfied-by-data; load-bearing, design §4.4).
     *  A frame the splice cannot parse is DROPPED with a warn-once (design §5): letting
     *  the exception propagate would make flushSendQueue drop the player's WHOLE queue,
     *  and honest re-resolution would re-enqueue the same frame forever. Unreachable
     *  today — every frame comes from encodeVoxelColumnPreEncoded. */
    private ColumnPayloadSender columnPayloadSender = (state, data) -> {
        var uuid = state.getPlayerUUID();
        if (this.v16Compat.isV16(uuid)) {
            byte[] legacy;
            long packedPos;
            try {
                legacy = PaperPayloadHandler.rewriteColumnToV16(data);
                packedPos = PaperPayloadHandler.readColumnPackedPos(data);
            } catch (Exception e) {
                if (!this.v16SpliceWarned) {
                    this.v16SpliceWarned = true;
                    LSSLogger.error("v16-compat: dropping unspliceable column frame for "
                            + state.getPlayerName() + " (further drops are silent)", e);
                }
                return;
            }
            PaperPayloadHandler.sendRawNmsPayload(state.getPlayer().getBukkitEntity(),
                    PaperPayloadHandler.ID_VOXEL_COLUMN, legacy);
            this.v16Compat.onColumnSent(uuid, packedPos);
            return;
        }
        PaperPayloadHandler.sendRawNmsPayload(state.getPlayer().getBukkitEntity(),
                PaperPayloadHandler.ID_VOXEL_COLUMN, data);
    };

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

    /** Pump-only. The batch taken at tick T, released back into the mailbox at T+1 once its
     *  probe task has had a region tick to publish — carrying the offer generation recorded
     *  BEFORE the take, so the release can refuse a batch that was passed-through during the
     *  hold (see {@code AbstractPlayerRequestState.republishHeldBatch}). */
    private record HeldBatch(IncomingBatch batch, long offerGeneration) {}

    private final Map<UUID, HeldBatch> heldForProbe = new HashMap<>();

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
    // Null in test wiring (the test ctor never publishes the static mask manager, so its
    // shutdown must retract nothing) — set by the production ctor only.
    private PaperXrayMaskManager xrayMasks;

    public PaperRequestProcessingService(MinecraftServer server, Plugin plugin, PaperConfig config) {
        this(server, config, productionWiring(server, plugin, config));
        this.plugin = plugin;
        // Production only (the test-wiring ctor must not publish statics): the per-world
        // x-ray mask decisions the serializer choke points consult. The reference makes
        // shutdown's retract guarded — a test-wired service (null here) retracts nothing.
        this.xrayMasks = PaperXrayMaskManager.activate(config);
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
        var diskReader = new PaperChunkDiskReader(config.diskReaderThreads, config.useBackgroundReadPriority);
        PaperChunkGenerationService generationService = config.enableChunkGeneration
                ? new PaperChunkGenerationService(config, plugin) : null;

        var dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
        var offThreadProcessor = new PaperOffThreadProcessor(
                players, diskReader, generationService != null, dataDir,
                config.perDimensionTimestampCacheSizeMB, config.missMemoTtlSeconds);
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
                    LSSConstants.SYNC_ON_LOAD_SLOT_CAP,
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
        // Resets the v16 want-set + arms the ingress grace. Identity survives (dropped only
        // by the PlayerQuit hook), mirroring how capabilities ride the dim-change
        // remove+register cycle. No-op for v18 players.
        this.v16Compat.onServiceRemove(uuid);
    }

    private void cleanupPlayerServices(UUID uuid) {
        this.diskReader.removePlayerResults(uuid);
        if (this.generationService != null)
            this.generationService.removePlayer(uuid);
    }

    public void handleBatchRequest(ServerPlayer player, PaperPayloadHandler.DecodedBatchChunkRequest batch) {
        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDist = this.config.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;

        // v16 compat branch: legacy drip batches MERGE into the synthetic want-set (the 1 Hz
        // pump tick is the sole declarer) instead of replacing the backlog. Placed before the
        // state guard: merges are session-only and must not depend on registration timing —
        // on Paper the handshake reply outruns the mailboxed registration by up to a tick.
        var v16Merge = this.v16Compat.onClientBatch(player.getUUID(), batch.packedPositions(),
                batch.clientTimestamps(), batch.count(), playerCx, playerCz, maxDist);
        if (v16Merge != null) {
            var v16State = this.players.get(player.getUUID());
            if (v16State != null && v16Merge.rangeFiltered() > 0) {
                v16State.recordRangeFiltered(v16Merge.rangeFiltered());
            }
            long[] bounced = v16Merge.overflowBounced();
            if (bounced.length > 0) {
                // Overflow valve: byte 0 comes back to life for exactly this — the old client
                // backs off ~1 s and retries. Sent directly (netty is any-thread safe), off
                // the pipeline's SendActionBatcher.
                var types = new byte[bounced.length];
                java.util.Arrays.fill(types, LSSConstants.RESPONSE_RATE_LIMITED_V16);
                PaperPayloadHandler.sendBatchResponse(player.getBukkitEntity(),
                        types, bounced, bounced.length);
            }
            return;
        }

        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;

        var accepted = new ArrayList<IncomingRequest>(batch.count());
        for (int i = 0; i < batch.count(); i++) {
            long packedPosition = batch.packedPositions()[i];
            int cx = PositionUtil.unpackX(packedPosition);
            int cz = PositionUtil.unpackZ(packedPosition);
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > maxDist) continue;
            accepted.add(new IncomingRequest(cx, cz, batch.clientTimestamps()[i]));
        }
        state.recordRangeFiltered(batch.count() - accepted.size());
        // Offer even when empty: an empty batch is the client's explicit backpressure
        // clear and must replace the backlog with nothing.
        state.offerIncomingBatch(new IncomingBatch(accepted.toArray(new IncomingRequest[0])));
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
        // v16 declares BEFORE the lifecycle pass: the sync probe reads the mailbox during
        // processPlayerLifecycle, and on Folia holdAndScheduleRegionProbe reads ONLY the
        // mailbox — a declare offered after that pass would lose the race to the processing
        // thread's take and route with zero probe coverage (release-review finding 1).
        tickV16Compat();
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

    /** The v16 shim's 1 Hz declare pass (PUMP): the SOLE declarer for legacy sessions. A
     *  server without v16 clients pays one no-op map lookup per player per tick. MUST run
     *  before processPlayerLifecycle: the declare then sits in the mailbox when the sync
     *  probe (or, on Folia, the hold-release take) reads it, giving shim batches the same
     *  arrival-tick probe alignment a network-received client batch gets. */
    private void tickV16Compat() {
        int maxDist = this.config.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;
        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake()) continue;
            var player = state.getPlayer();
            this.v16Compat.tickPlayer(player.getUUID(), state,
                    player.chunkPosition().x(), player.chunkPosition().z(), maxDist);
        }
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
        int globalProbeBudget = MAX_PROBES_PER_TICK_GLOBAL;
        List<UUID> toRemove = null;
        // Rotate the iteration start each tick: ConcurrentHashMap's iteration order is
        // stable, so when the global probe budget exhausts mid-pass the SAME trailing
        // players would otherwise get zero probe coverage every tick.
        var states = new ArrayList<>(this.players.values());
        int playerCount = states.size();
        int start = playerCount == 0 ? 0 : Math.floorMod(this.probeRotation++, playerCount);
        for (int i = 0; i < playerCount; i++) {
            var state = states.get((start + i) % playerCount);
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
            // Ring origin for the generation order-spread gate — must be the REAL player
            // chunk (the want-set's first entry sits at ~viewDistance on a ring perimeter,
            // which wedged the gate — see AbstractPlayerRequestState.updatePlayerChunk).
            state.updatePlayerChunk(player.chunkPosition().x(), player.chunkPosition().z());
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
                probes = this.probeLoadedChunks(state, level, skipPositions, globalProbeBudget);
                globalProbeBudget -= probes.size();   // charge only actual serializations (pump path)
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

    /**
     * Probe loaded chunks for positions the player still wants (Paper's sync path — Folia
     * uses the regionized hold-release instead).
     *
     * <p><b>Source: the mailbox first, then the published want-set.</b> The MAILBOX holds a
     * batch that arrived since the last routing cycle; probing it on its ARRIVAL tick is what
     * puts its probes in the snapshot the router routes it against. Without that a freshly
     * declared position is never probed on its first routing cycle, and a want-set that fits
     * under the per-player slot cap — the converged steady state, and every single-position
     * dirty-broadcast re-request — has no second cycle, so it disk-reads. Folia's one-tick
     * hold-release makes the same alignment deterministic; this is the sync path's
     * equivalent. The PUBLISHED want-set then covers the other ~19 ticks of each second
     * ({@code takeIncomingBatch()} nulls the mailbox within ~50 ms of arrival while batches
     * arrive at only 1 Hz) and carries a want-set too large for the slot cap across the
     * cycles that work it off (published exactly while the backlog is non-empty).
     *
     * <p>Both sources may list already-routed positions; such a probe is simply unused by the
     * router, bounded by {@link #MAX_PROBES_PER_TICK_PER_PLAYER}.
     */
    private Long2ObjectMap<LoadedColumnData> probeLoadedChunks(
            PaperPlayerRequestState state, ServerLevel level,
            LongOpenHashSet skipPositions, int globalBudgetRemaining) {
        var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
        int probed = 0;

        var wantSet = state.peekIncomingBatch();
        if (wantSet == null)
            wantSet = state.peekWantSet();
        if (wantSet == null)
            return probes;   // nothing pending — converged player, no probe cost
        for (var req : wantSet.requests()) {
            if (probed >= MAX_PROBES_PER_TICK_PER_PLAYER)
                break;   // per-player examination cap
            // Global serialization ceiling: probes.size() counts columns actually serialized, so
            // this stops the moment this player would exceed the tick's remaining pump budget.
            if (probes.size() >= globalBudgetRemaining)
                break;
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (probes.containsKey(packed))
                continue;
            if (skipPositions != null && skipPositions.contains(packed))
                continue;
            // Served-head filter: a payload already in the send pipeline means the router
            // resolves this position as a duplicate — the probe is guaranteed-unused, and
            // under backlog retention the published want-set re-lists it every tick until
            // the next 1 Hz declaration, re-serializing the same head for a whole second.
            // Only enqueuedColumns is checked: it is the one served-set structure safe off
            // the processing thread (pendingByPosition/diskReadDone are single-threaded).
            if (state.hasEnqueuedColumn(packed))
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

    /** Pump only. One-tick hold-release at BATCH granularity: release last tick's held batch
     *  back into the mailbox — but only if no newer batch arrived during the hold
     *  (republishHeldBatch: the mailbox CAS catches a newer batch still sitting there; the
     *  offer-generation guard catches one that passed THROUGH the mailbox — offered and
     *  taken by the processing thread — during the hold. A lost republish means the held
     *  batch was superseded, is counted, and is dropped, never resurrected) — otherwise
     *  take whatever is pending now, park it with the pre-take offer generation, and hand
     *  its positions to the player's owning region. The processing thread takes batches
     *  only from the mailbox, so a held batch is invisible to routing until released with
     *  its probe results already published, and no batch is ever both held and pending.
     *
     *  <p>Release strictly precedes the take, and a successful release ends the tick: the CAS
     *  is {@code compareAndSet(null, held)}, so taking first would empty the mailbox and make
     *  the CAS unconditionally succeed — resurrecting a batch the client has already
     *  superseded while parking the newer one behind it. (The offer-generation guard now
     *  also catches that shape, but the release-then-take order stays: it is what keeps the
     *  pump from re-holding a batch routing is about to take.) Returning on a successful
     *  release keeps the pump from immediately stealing back the batch it just handed to
     *  routing.
     *
     *  <p><b>Known limitation.</b> The release-then-return only protects the batch for ONE
     *  pump tick: if the processing cycle overruns and has not taken the released batch by
     *  the NEXT pump tick, this method finds nothing held and takes it back out of the
     *  mailbox — re-holding it for another tick (with fresh probe results) instead of
     *  letting routing have it. A persistently slow processing thread can ping-pong a batch
     *  this way, each bounce adding a tick of routing delay until either the processing
     *  thread wins the race or the next 1 Hz declaration supersedes the batch. Bounded and
     *  self-healing, but worth knowing when reading Folia soak latencies. */
    private void holdAndScheduleRegionProbe(PaperPlayerRequestState state, ServerPlayer player,
                                            ServerLevel level, LongOpenHashSet skipPositions) {
        var released = this.heldForProbe.remove(player.getUUID());
        if (released != null
                && state.republishHeldBatch(released.batch(), released.offerGeneration())) {
            return;
        }

        // Either nothing was held, or the held batch lost the republish (a newer arrival in
        // the mailbox, or a pass-through the offer-generation guard caught — dropped and
        // counted superseded either way). Whatever is pending now is the newest declaration.
        // The generation is recorded BEFORE the take: an offer slipping between the two can
        // only make the eventual republish refuse spuriously (a healed drop), never let a
        // stale batch resurrect.
        long heldAtGeneration = state.offerGeneration();
        var fresh = state.takeIncomingBatch();
        if (fresh == null) return;
        this.heldForProbe.put(player.getUUID(), new HeldBatch(fresh, heldAtGeneration));

        long[] positions = snapshotProbePositions(state, fresh, skipPositions);
        if (positions.length == 0) return;
        UUID uuid = player.getUUID();
        this.regionTaskScheduler.schedule(player, () -> runRegionProbe(uuid, level, positions));
    }

    private static final long[] NO_POSITIONS = new long[0];

    /** Up to {@link #MAX_PROBES_PER_TICK_PER_PLAYER} distinct positions from the held batch.
     *  Served-head filter mirrors the sync probes: a payload already in the send pipeline
     *  resolves as a duplicate, so probing it wastes a region-thread serialization
     *  (enqueuedColumns is the one served-set structure safe off the processing thread). */
    private long[] snapshotProbePositions(PaperPlayerRequestState state, IncomingBatch held,
                                          LongOpenHashSet skipPositions) {
        LongOpenHashSet positions = null;
        for (var req : held.requests()) {
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (skipPositions != null && skipPositions.contains(packed)) continue;
            if (state.hasEnqueuedColumn(packed)) continue;
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
        this.offThreadProcessor.drainSendActions((state, types, positions, count) -> {
            // v16 observation: UP_TO_DATE / NOT_GENERATED terminally answer their positions —
            // prune them from the synthetic want-set. The frame itself is wire-identical.
            this.v16Compat.observeBatchResponse(state.getPlayerUUID(), types, positions, count);
            PaperPayloadHandler.sendBatchResponse(state.getPlayer().getBukkitEntity(),
                    types, positions, count);
        });
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
                // Capacity rejection or removed player — TRANSIENT: feed a transient outcome
                // so the processing thread frees the pending slot silently (superseded); the
                // client's re-declaration retries. Never NOT_GENERATED (session-permanent).
                this.offThreadProcessor.feedGenerationFailure(
                        req.playerUuid(), req.cx(), req.cz(), dimension, req.submissionOrder(), true);
            }
        }
    }

    public Map<UUID, PaperPlayerRequestState> getPlayers() {
        return Collections.unmodifiableMap(this.players);
    }

    public V16CompatManager getV16CompatManager() {
        return this.v16Compat;
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
        PaperXrayMaskManager.deactivate(this.xrayMasks);
    }
}
