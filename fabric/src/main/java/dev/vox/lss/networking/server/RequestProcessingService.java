package dev.vox.lss.networking.server;

import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
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
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RequestProcessingService {
    private final Map<UUID, PlayerRequestState> players = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final ChunkDiskReader diskReader;
    private final ChunkGenerationService generationService;
    private final SharedBandwidthLimiter bandwidthLimiter;
    private final FabricOffThreadProcessor offThreadProcessor;
    private final DirtyColumnTracker dirtyTracker;
    private final DirtyContentFilter dirtyContentFilter = new DirtyContentFilter();

    private final long startTimeNanos = System.nanoTime();

    private final XrayMaskManager xrayMasks;
    private final DirtyColumnBroadcaster dirtyBroadcaster;
    // The v16 compat shim's per-player sessions (legacy protocol-16 clients). The pipeline
    // never consults it: a v16 player is an ordinary registered player whose want-set is
    // declared by the shim at 1 Hz. See docs/planning/v16-compat-design.md.
    private final V16CompatManager v16Compat = new V16CompatManager();
    // Keyed by the lightweight ResourceKey (not ServerLevel): a ServerLevel key strongly
    // retains every world an LSS player ever visited — harmless for vanilla's permanent
    // dimensions, but a leak on world-cycling servers. The dimension string is derivable
    // from the key.
    private final Map<ResourceKey<Level>, String> dimensionStringCache = new HashMap<>();
    private int diagLogCounter = 0;

    private final TickDiagnostics diag = new TickDiagnostics();

    private static final int DIAG_LOG_INTERVAL_TICKS = 100;
    private static final int MAX_PROBES_PER_TICK_PER_PLAYER = 512;
    // Global ceiling on in-memory column SERIALIZATIONS across ALL players in one tick — the
    // per-player cap bounds one player, but N backfilling players would otherwise cost up to
    // 512*N serializations on the main thread. Counts serializations (the expensive work), not
    // examinations (cheap getChunkNow lookups); a converged player costs zero either way. Once
    // spent, later players in the tick fall through to the disk-read path (or the next tick) and
    // the client's 1 Hz re-declaration heals the deferral. Hardcoded: a safety ceiling, not a knob.
    // Gen-disabled corner (accepted): with enableChunkGeneration=false, a LOADED but
    // never-saved chunk whose probe this cap deferred falls through to a disk read, resolves
    // not-found, and answers NOT_GENERATED — session-permanent on the client despite the
    // chunk being live in memory. Heals on its first save (dirty broadcast) or reconnect;
    // needs disabled generation + an exhausted budget + a never-saved chunk in one tick.
    private static final int MAX_PROBES_PER_TICK_GLOBAL = 2048;
    /** Rotating start index for the lifecycle pass (main thread only) — see the loop comment. */
    private int probeRotation;
    /** Throttle for {@link #serializeProbeContained}'s warn — same 60 s aggregation style as
     *  the disk-timeout warn (a broken foreign mixin fails EVERY probe; per-column lines
     *  would flood the console). */
    private final LogThrottle probeFailureWarn = new LogThrottle(60_000);

    // Send-drop fault seam state (see armSendDrops). Static so the soak driver and gametests
    // can arm it without a service reference; production code never arms it.
    private static final AtomicInteger PENDING_SEND_DROPS = new AtomicInteger();
    private static final AtomicLong TOTAL_SEND_DROPS_INJECTED = new AtomicLong();

    public RequestProcessingService(MinecraftServer server) {
        this.server = server;
        var config = LSSServerConfig.CONFIG;

        // Publishes the per-world x-ray mask decisions the (static) serializer choke
        // points consult — before any serve can run. The reference is kept so shutdown's
        // retract is guarded (a stale shutdown must not null a successor's masking).
        this.xrayMasks = XrayMaskManager.activate(config);

        this.dirtyTracker = new DirtyColumnTracker();

        this.diskReader = new ChunkDiskReader(config.diskReaderThreads, config.useBackgroundReadPriority);
        if (config.enableChunkGeneration) {
            this.generationService = new ChunkGenerationService(config);
            this.generationService.setDirtyContentFilter(this.dirtyContentFilter);
        } else {
            this.generationService = null;
        }
        this.bandwidthLimiter = new SharedBandwidthLimiter(config.bytesPerSecondLimitGlobal);

        var dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
        this.offThreadProcessor = new FabricOffThreadProcessor(
                this.players,
                this.diskReader, this.generationService != null, dataDir,
                config.perDimensionTimestampCacheSizeMB, config.missMemoTtlSeconds);
        this.offThreadProcessor.start();

        this.dirtyBroadcaster = new DirtyColumnBroadcaster(
                server, this.players, this.offThreadProcessor, this.dirtyTracker);
    }

    public PlayerRequestState registerPlayer(ServerPlayer player, int capabilities) {
        var config = LSSServerConfig.CONFIG;
        var state = this.players.computeIfAbsent(player.getUUID(), uuid -> {
            var s = new PlayerRequestState(player, LSSConstants.SYNC_ON_LOAD_SLOT_CAP,
                    config.generationConcurrencyLimitPerPlayer);
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
        this.offThreadProcessor.notifyPlayerRemoved(uuid);
        cleanupPlayerServices(uuid);
        // Resets the v16 want-set + arms the ingress grace. Identity survives (dropped only
        // by the network DISCONNECT hook), mirroring how capabilities ride the dim-change
        // remove+register cycle. No-op for v18 players.
        this.v16Compat.onServiceRemove(uuid);
    }

    private void cleanupPlayerServices(UUID uuid) {
        this.diskReader.removePlayerResults(uuid);
        if (this.generationService != null) this.generationService.removePlayer(uuid);
    }

    public void handleBatchRequest(ServerPlayer player, BatchChunkRequestC2SPayload payload) {
        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDist = LSSServerConfig.CONFIG.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;

        // v16 compat branch: legacy drip batches MERGE into the synthetic want-set (the 1 Hz
        // tick is the sole declarer) instead of replacing the backlog. Placed before the
        // state guard: merges are session-only and must not depend on registration timing.
        var v16Merge = this.v16Compat.onClientBatch(player.getUUID(), payload.packedPositions(),
                payload.clientTimestamps(), payload.count(), playerCx, playerCz, maxDist);
        if (v16Merge != null) {
            var state = this.players.get(player.getUUID());
            if (state != null && v16Merge.rangeFiltered() > 0) {
                state.recordRangeFiltered(v16Merge.rangeFiltered());
            }
            long[] bounced = v16Merge.overflowBounced();
            if (bounced.length > 0) {
                // Overflow valve: byte 0 comes back to life for exactly this — the old client
                // backs off ~1 s and retries. Sent directly (MAIN thread), off the pipeline.
                var types = new byte[bounced.length];
                java.util.Arrays.fill(types, LSSConstants.RESPONSE_RATE_LIMITED_V16);
                ServerPlayNetworking.send(player,
                        new BatchResponseS2CPayload(types, bounced, bounced.length));
            }
            return;
        }

        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;

        var accepted = new ArrayList<IncomingRequest>(payload.count());
        for (int i = 0; i < payload.count(); i++) {
            long packedPosition = payload.packedPositions()[i];
            int cx = PositionUtil.unpackX(packedPosition);
            int cz = PositionUtil.unpackZ(packedPosition);
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > maxDist) continue;
            accepted.add(new IncomingRequest(cx, cz, payload.clientTimestamps()[i]));
        }
        state.recordRangeFiltered(payload.count() - accepted.size());
        // Offer even when empty: an empty batch is the client's explicit backpressure
        // clear and must replace the backlog with nothing.
        state.offerIncomingBatch(new IncomingBatch(accepted.toArray(new IncomingRequest[0])));
    }

    public void tick() {
        if (!LSSServerConfig.CONFIG.enabled) return;

        this.diag.reset(this.offThreadProcessor.getDiagnostics());

        var config = LSSServerConfig.CONFIG;
        var generationReady = tickGenerationService();
        // v16 declares BEFORE the lifecycle pass: the probe reads the mailbox during
        // processPlayerLifecycle, and postSnapshot wakes the processing thread which takes
        // the mailbox within milliseconds — a declare offered after the probe pass would
        // route its first cycle with zero probe coverage (release-review finding 1).
        tickV16Compat(config);
        var lifecycle = processPlayerLifecycle(config, generationReady);

        if (lifecycle.toRemove != null) {
            for (UUID uuid : lifecycle.toRemove) this.removePlayer(uuid);
        }

        postSnapshot(lifecycle, generationReady, config);
        this.drainSendActions();
        this.drainGenerationTicketRequests();
        flushSendQueues(lifecycle.activeCount, config);
        tickDirtyBroadcast(config);
        tickDiagnosticsLog(config);
    }

    private List<TickSnapshot.GenerationReadyData> tickGenerationService() {
        if (this.generationService == null) return List.of();
        return this.generationService.tick();
    }

    /**
     * Per-tick snapshot buffers. {@link #newPerTick()} is the only producer and must allocate
     * fresh maps every tick: ownership transfers to the processing thread at
     * {@code postSnapshot}, so a reused buffer would be mutated by the next lifecycle pass
     * while the processing thread iterates it (unsynchronized HashMap). {@link #toSnapshot}
     * wraps exactly these instances — zero-copy, so what the lifecycle pass wrote is what
     * the processing thread reads. ServiceGlueTest pins both halves of the contract.
     */
    record SnapshotBuffers(
            Map<UUID, String> playerDimensions,
            Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes
    ) {
        static SnapshotBuffers newPerTick() {
            return new SnapshotBuffers(new HashMap<>(), new HashMap<>());
        }

        TickSnapshot toSnapshot(int maxSendQueueSize) {
            return new TickSnapshot(this.playerDimensions, this.loadedChunkProbes,
                    maxSendQueueSize, false);
        }
    }

    private record LifecycleResult(
            SnapshotBuffers buffers,
            int activeCount,
            List<UUID> toRemove
    ) {}

    private LifecycleResult processPlayerLifecycle(LSSServerConfig config,
                                                     List<TickSnapshot.GenerationReadyData> generationReady) {
        var buffers = SnapshotBuffers.newPerTick();

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
            if (!state.hasCompletedHandshake()) continue;
            activeCount++;
            this.diag.updateQueuePeak(state.getSendQueueSize());

            boolean removed = false;

            if (state.getPlayer().isRemoved()) {
                var current = this.server.getPlayerList().getPlayer(state.getPlayer().getUUID());
                if (current == null) {
                    if (toRemove == null) toRemove = new ArrayList<>();
                    toRemove.add(state.getPlayer().getUUID());
                    removed = true;
                } else {
                    state.updatePlayer(current);
                }
            }

            if (removed) continue;

            if (state.checkDimensionChange()) {
                // A dimension change abandons all in-flight work. Reuse the (well-tested)
                // disconnect teardown + a fresh registration instead of a second, hand-rolled
                // partial-reset protocol: the processing thread unwinds the old state's dedup
                // groups via the removal event, and the fresh state starts clean next tick.
                var player = state.getPlayer();
                int capabilities = state.getCapabilities();
                removePlayer(player.getUUID());
                registerPlayer(player, capabilities);
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

            buffers.playerDimensions().put(player.getUUID(), dimension);

            var skipPositions = genReadyPositions != null
                    ? genReadyPositions.get(player.getUUID()) : null;
            var probes = this.probeLoadedChunks(state, level, skipPositions, globalProbeBudget);
            globalProbeBudget -= probes.size();   // charge only actual serializations
            if (!probes.isEmpty()) {
                buffers.loadedChunkProbes().put(player.getUUID(), probes);
            }
        }

        return new LifecycleResult(buffers, activeCount, toRemove);
    }

    /** The v16 shim's 1 Hz declare pass (MAIN): the SOLE declarer for legacy sessions. A
     *  server without v16 clients pays one no-op map lookup per player per tick. MUST run
     *  before processPlayerLifecycle so the declare sits in the mailbox when the probe
     *  pass reads it — the same arrival-tick alignment a network-received batch gets. */
    private void tickV16Compat(LSSServerConfig config) {
        int maxDist = config.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;
        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake()) continue;
            var player = state.getPlayer();
            this.v16Compat.tickPlayer(player.getUUID(), state,
                    player.chunkPosition().x(), player.chunkPosition().z(), maxDist);
        }
    }

    private void postSnapshot(LifecycleResult lifecycle,
                               List<TickSnapshot.GenerationReadyData> generationReady,
                               LSSServerConfig config) {
        this.offThreadProcessor.postSnapshot(
                lifecycle.buffers.toSnapshot(config.sendQueueLimitPerPlayer), generationReady);
    }

    /** Test seam (D9): puts one column payload on the wire for one player. Production default
     *  is {@code ServerPlayNetworking.send}; ServiceGlueTest injects recording/throwing senders. */
    @FunctionalInterface
    interface ColumnPayloadSender {
        void send(PlayerRequestState state, CustomPacketPayload payload) throws Exception;
    }

    private void flushSendQueues(int activeCount, LSSServerConfig config) {
        long perPlayerAllocation = this.bandwidthLimiter.getPerPlayerAllocation(activeCount);
        long perPlayerCap = Math.min(perPlayerAllocation, config.bytesPerSecondLimitPerPlayer);
        flushSendQueues(this.players.values(), perPlayerCap, this.bandwidthLimiter, this.diag,
                this::sendColumnPayload, this.offThreadProcessor);
    }

    /** Warn-once latch for the v16 egress guard (MAIN thread only). */
    private boolean v16UnconvertibleWarned;

    /** The per-player column egress (MAIN). For a v16 session, converts UNCONDITIONALLY to
     *  the legacy source-less shape — every producer (probe/disk/generation/ghost-clear)
     *  funnels through here, so no producer can leak a v18 frame that would hard-kick the
     *  old client — and prunes the position from the synthetic want-set after the send
     *  (satisfied-by-data; the prune is load-bearing, see the design §4.4). A payload the
     *  guard cannot convert is DROPPED with a warn-once (design §5): a dropped frame
     *  self-heals by re-declaration, a wrong-shaped one kicks the client. Unreachable
     *  today — only buildAndEnqueueColumnPayload feeds this queue. */
    private void sendColumnPayload(PlayerRequestState state, CustomPacketPayload payload)
            throws Exception {
        var uuid = state.getPlayerUUID();
        if (this.v16Compat.isV16(uuid)) {
            if (!(payload instanceof dev.vox.lss.networking.payloads.VoxelColumnS2CPayload col)) {
                if (!this.v16UnconvertibleWarned) {
                    this.v16UnconvertibleWarned = true;
                    LSSLogger.warn("v16-compat: dropping unconvertible column-queue payload "
                            + payload.getClass().getName() + " for " + state.getPlayerName()
                            + " (further drops are silent)");
                }
                return;
            }
            ServerPlayNetworking.send(state.getPlayer(), col.asV16());
            this.v16Compat.onColumnSent(uuid,
                    PositionUtil.packPosition(col.chunkX(), col.chunkZ()));
            return;
        }
        ServerPlayNetworking.send(state.getPlayer(), payload);
    }

    // Package-private static: ServiceGlueTest drives this glue with hand-rolled states and an
    // unstarted processor (constructing RequestProcessingService needs a MinecraftServer).
    static void flushSendQueues(Iterable<PlayerRequestState> states, long perPlayerCap,
                                 SharedBandwidthLimiter bandwidthLimiter, TickDiagnostics diag,
                                 ColumnPayloadSender sender,
                                 FabricOffThreadProcessor offThreadProcessor) {
        for (var state : states) {
            if (!state.hasCompletedHandshake()) continue;
            long[] dropped = state.flushSendQueue(perPlayerCap, bandwidthLimiter, diag,
                    payload -> {
                        if (consumeSendDropFault()) return;
                        sender.send(state, payload);
                    });
            if (dropped.length > 0) {
                // A send failure discarded resolved-but-undelivered columns: clear their
                // done-bits so the client's re-requests re-resolve instead of being
                // answered up-to-date for data that never arrived.
                offThreadProcessor.clearDiskReadDone(state.getPlayerUUID(), dropped);
            }
        }
    }

    /**
     * Fault seam: arms the flush path to silently discard the next {@code count}
     * column-payload sends. A dropped payload vanishes after resolution — the flush treats
     * it as delivered (queue advances, diskReadDone stays set, no clearDiskReadDone) — so
     * the honest re-resolution ladder (a ts&le;0 re-request of a served position re-resolves)
     * can be exercised live by the soak {@code fault send-drop N} command and the client
     * gametests. Inert in production: no production code path calls this, and arming is
     * refused unless the JVM carries {@code -Dlss.soak.scenario} (soak server) or
     * {@code -Dlss.test.integratedServer} (gametest JVMs). Disarming ({@code count <= 0})
     * is always allowed.
     */
    public static void armSendDrops(int count) {
        if (count > 0 && !sendDropFaultAllowed()) {
            LSSLogger.warn("Refusing to arm send-drop fault injection: not a soak or gametest JVM");
            return;
        }
        PENDING_SEND_DROPS.set(Math.max(0, count));
    }

    /** Sends still armed to be dropped by the {@link #armSendDrops} fault seam. */
    public static int pendingSendDrops() {
        return PENDING_SEND_DROPS.get();
    }

    /** Cumulative column sends discarded by the {@link #armSendDrops} fault seam (this JVM). */
    public static long totalSendDropsInjected() {
        return TOTAL_SEND_DROPS_INJECTED.get();
    }

    private static boolean sendDropFaultAllowed() {
        if (Boolean.getBoolean("lss.test.integratedServer")) return true;
        // Blank counts as unset: the soakServer run config always defines the property,
        // as the empty string when no scenario is staged (BenchmarkBridge convention).
        String scenario = System.getProperty("lss.soak.scenario");
        return scenario != null && !scenario.isBlank();
    }

    private static boolean consumeSendDropFault() {
        int n;
        do {
            n = PENDING_SEND_DROPS.get();
            if (n <= 0) return false;
        } while (!PENDING_SEND_DROPS.compareAndSet(n, n - 1));
        TOTAL_SEND_DROPS_INJECTED.incrementAndGet();
        return true;
    }

    private void tickDirtyBroadcast(LSSServerConfig config) {
        this.dirtyBroadcaster.tick(config);
    }

    private void tickDiagnosticsLog(LSSServerConfig config) {
        if (++this.diagLogCounter >= DIAG_LOG_INTERVAL_TICKS) {
            this.diagLogCounter = 0;
            DiagnosticsFormatter.logDebugSummary(this.diag, this.getUptimeSeconds(),
                    config.bytesPerSecondLimitGlobal, this.bandwidthLimiter, this.players.values());
        }
    }

    /**
     * Probe loaded chunks for positions the player still wants. Called on the main thread.
     * Serializes loaded chunks so the processing thread can compress and send without
     * touching MC world state.
     *
     * <p><b>Source: the mailbox first, then the published want-set.</b> Both are needed and
     * neither alone suffices:
     * <ul>
     *   <li>The MAILBOX holds a batch that arrived since the last routing cycle. It is
     *       probed on its ARRIVAL tick, before the processing thread applies it, so the
     *       snapshot the router routes it against already carries its probes. Without this a
     *       freshly declared position is never probed on its first routing cycle, and for a
     *       want-set that fits under the per-player slot cap — the converged steady state,
     *       and every single-position dirty-broadcast re-request — there IS no second cycle:
     *       it admits everything at once, the backlog drains, the want-set unpublishes, and
     *       the position disk-reads. That is not merely slower: with
     *       {@code useBackgroundReadPriority} the reader bypasses IOWorker's pendingWrites,
     *       so a just-edited column read from disk yields PRE-edit bytes until the save
     *       lands. The edited-chunk re-request is exactly the case that must serve live.
     *       (Folia's regionized path makes the same alignment deterministic by holding the
     *       fresh batch one tick — see {@code holdAndScheduleRegionProbe}; this is the sync
     *       path's equivalent.)</li>
     *   <li>The PUBLISHED want-set covers the other ~19 ticks of each second:
     *       {@code takeIncomingBatch()} nulls the mailbox within ~50 ms of arrival while
     *       batches arrive at only 1 Hz, so the mailbox alone would see null on almost every
     *       tick. It stays published exactly while the backlog is non-empty (cleared on
     *       drain-to-empty, republished by {@code restoreBacklog}), which is what carries a
     *       want-set too large for the slot cap across the cycles that work it off.</li>
     * </ul>
     * Both sources list positions that may already be routed; a probe for an already-routed
     * position is simply unused by the router, and the cost is bounded by
     * {@link #MAX_PROBES_PER_TICK_PER_PLAYER}, which the closest-first order spends on the
     * head of the want-set. Positions whose payload already sits in the send pipeline are
     * filtered below, so a retained backlog does not re-serialize its served head every
     * tick; the residual waste is one re-probe of positions served-and-sent but not yet
     * dropped by the next 1 Hz declaration.
     *
     * @param skipPositions packed positions already extracted by the generation service (may be null)
     */
    private Long2ObjectMap<LoadedColumnData> probeLoadedChunks(
            PlayerRequestState state, ServerLevel level,
            LongOpenHashSet skipPositions, int globalBudgetRemaining) {
        var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
        int probed = 0;

        var batch = state.peekIncomingBatch();
        if (batch == null) batch = state.peekWantSet();
        if (batch == null) return probes;   // nothing pending — converged player, no probe cost
        for (var req : batch.requests()) {
            if (probed >= MAX_PROBES_PER_TICK_PER_PLAYER) break;   // per-player examination cap
            // Global serialization ceiling: probes.size() counts columns actually serialized this
            // call, so this stops the moment this player would exceed the tick's remaining budget.
            if (probes.size() >= globalBudgetRemaining) break;
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (probes.containsKey(packed)) continue;
            if (skipPositions != null && skipPositions.contains(packed)) continue;
            // Served-head filter: a payload already in the send pipeline means the router
            // resolves this position as a duplicate — the probe is guaranteed-unused, and
            // under backlog retention the published want-set re-lists it every tick until
            // the next 1 Hz declaration, re-serializing the same head for a whole second.
            // Only enqueuedColumns is checked: it is the one served-set structure safe off
            // the processing thread (pendingByPosition/diskReadDone are single-threaded).
            if (state.hasEnqueuedColumn(packed)) continue;

            LevelChunk chunk = level.getChunkSource().getChunkNow(req.cx(), req.cz());
            if (chunk != null) {
                // Deliberately NOT seeded into the DirtyContentFilter: a probe serve can land
                // between another player's edit and the chunk's cooldown save, and a seed here
                // would make that save hash equal — silencing the dirty broadcast every OTHER
                // client holding the old column needs. Only generation serves seed (freshly
                // generated content cannot be stale-held by anyone).
                var data = serializeProbeContained(SectionSerializer::serializeColumn,
                        level, chunk, req.cx(), req.cz(), this.probeFailureWarn);
                if (data != null) {
                    probes.put(packed, data);
                }
            }
            probed++;
        }

        return probes;
    }

    /** Serializer seam for {@link #serializeProbeContained} — production passes
     *  {@link SectionSerializer#serializeColumn}. */
    @FunctionalInterface
    interface ProbeColumnSerializer {
        LoadedColumnData serialize(ServerLevel level, LevelChunk chunk, int cx, int cz);
    }

    /**
     * Probe-path containment: the in-memory probe is the ONE serve path with no catch
     * between {@code section.write} and the server tick loop ({@code probeLoadedChunks} →
     * {@code tick()} → END_SERVER_TICK), so a foreign mixin that breaks out-of-band
     * serialization — the AntiXray class of conflict, docs/planning/antixray-compat-design.md
     * §2 — would crash the server here while every other path merely degrades. A throwing
     * serialize resolves as "no probe": the column falls through the existing disk →
     * generation → NOT_GENERATED ladder, i.e. blank LODs plus a throttled warning, never a
     * crash. The contained set — {@link Exception}, {@link LinkageError},
     * {@link AssertionError} — matches VoxyCompat's ingest policy (a foreign {@code assert}
     * under {@code -ea} is a mixin-failure shape, not a VM failure); VirtualMachineErrors
     * still propagate.
     */
    static LoadedColumnData serializeProbeContained(ProbeColumnSerializer serializer,
            ServerLevel level, LevelChunk chunk, int cx, int cz, LogThrottle warnThrottle) {
        try {
            return serializer.serialize(level, chunk, cx, cz);
        } catch (Exception | LinkageError | AssertionError e) {
            long releases = warnThrottle.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (releases > 0) {
                LSSLogger.warn("In-memory probe serialization failed at " + cx + ", " + cz
                        + " — column skipped, falls through to the disk/generation ladder"
                        + (releases > 1 ? " (+" + (releases - 1) + " more since last report)" : ""),
                        e);
            }
            return null;
        }
    }

    /**
     * Drain generation ticket requests from the processing thread and submit MC tickets.
     * Must run on main thread (ticket management requires MC world state).
     */
    private void drainGenerationTicketRequests() {
        if (this.generationService == null) return;

        OffThreadProcessor.GenerationTicketRequest req;
        while ((req = this.offThreadProcessor.pollGenerationTicketRequest()) != null) {
            var state = this.players.get(req.playerUuid());
            if (state == null || !state.hasCompletedHandshake()) continue;

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

    private void drainSendActions() {
        this.offThreadProcessor.drainSendActions((state, types, positions, count) -> {
            // v16 observation: UP_TO_DATE / NOT_GENERATED terminally answer their positions —
            // prune them from the synthetic want-set. The frame itself is wire-identical.
            this.v16Compat.observeBatchResponse(state.getPlayerUUID(), types, positions, count);
            ServerPlayNetworking.send(state.getPlayer(),
                    new BatchResponseS2CPayload(types, positions, count));
        });
    }

    public Map<UUID, PlayerRequestState> getPlayers() {
        return Collections.unmodifiableMap(this.players);
    }

    public V16CompatManager getV16CompatManager() {
        return this.v16Compat;
    }

    public ChunkDiskReader getDiskReader() {
        return this.diskReader;
    }

    public ChunkGenerationService getGenerationService() {
        return this.generationService;
    }

    public SharedBandwidthLimiter getBandwidthLimiter() {
        return this.bandwidthLimiter;
    }

    public long getUptimeSeconds() {
        return (System.nanoTime() - this.startTimeNanos) / LSSConstants.NANOS_PER_SECOND;
    }

    public OffThreadProcessor<?> getOffThreadProcessor() {
        return this.offThreadProcessor;
    }

    public DirtyColumnTracker getDirtyTracker() {
        return this.dirtyTracker;
    }

    public DirtyContentFilter getDirtyContentFilter() {
        return this.dirtyContentFilter;
    }

    public String getTickDiagnostics() {
        return this.diag.format(LSSServerConfig.CONFIG.sendQueueLimitPerPlayer);
    }

    public TickDiagnostics getTickDiag() {
        return this.diag;
    }

    public long getWindowBandwidthRate() {
        return this.diag.getWindowBytesPerSecond();
    }

    public void shutdown() {
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
        XrayMaskManager.deactivate(this.xrayMasks);
    }
}
