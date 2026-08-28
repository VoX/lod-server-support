package dev.vox.lss.paper;

import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.compat.V16CompatManager;
import dev.vox.lss.common.compat.WireDialectTracker;
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
    // Service gate (service-permission-gate-plan.md §2.3): the denied-handshake memo,
    // the once-per-episode denial-log latch, and the revocation streaks — dying with
    // this service (onDisable's shutdown() is the C1-9 clear).
    private final dev.vox.lss.common.ServiceGateState serviceGateState =
            new dev.vox.lss.common.ServiceGateState();
    /** The recheck cadence (plan §2.3): both sweeps run every 200 ticks (~10 s). */
    static final int PERMISSION_RECHECK_TICKS = 200;
    private int permissionRecheckCounter;
    /** The sweep's permission read — pump-thread Bukkit permissible reads (the
     *  PaperFarPlayerSnapshots Folia precedent); seam-injected for tests. A throwing
     *  probe is contained at the sweep site (counts as HOLDING — fail-open). */
    private java.util.function.BiPredicate<ServerPlayer, String> permissionProbe =
            (p, node) -> p.getBukkitEntity().hasPermission(node);
    /** The grant sweep's replay hook — wired to the plugin's production handshake body
     *  by the production constructor (the full ladder re-runs; the reply lands via the
     *  registrar's DEFERRED path, so the Folia pre-registration gap stays closed);
     *  null in bare test wirings until injected. */
    private java.util.function.BiConsumer<ServerPlayer,
            dev.vox.lss.common.ServiceGateState.DeniedHandshake> handshakeReplayer;

    /** Test seam. */
    public void setPermissionProbeForTest(
            java.util.function.BiPredicate<ServerPlayer, String> probe) {
        this.permissionProbe = probe;
    }

    /** Test seam (production wiring: the constructor). */
    public void setHandshakeReplayer(java.util.function.BiConsumer<ServerPlayer,
            dev.vox.lss.common.ServiceGateState.DeniedHandshake> replayer) {
        this.handshakeReplayer = replayer;
    }
    private final MinecraftServer server;
    private final PaperChunkDiskReader diskReader;
    private final PaperChunkGenerationService generationService;
    private final SharedBandwidthLimiter bandwidthLimiter;
    private final PaperConfig config;
    private final PaperOffThreadProcessor offThreadProcessor;
    // Null while lodStore=off or when the codec native cannot load (degrade, never crash).
    private final dev.vox.lss.common.store.LodStoreService lodStore;
    // Region freshness stamps (region-summary-sync-plan.md): the P1 header rung's oracle.
    // Null in pre-region-stamps test wirings (rung inert there).
    private final dev.vox.lss.common.region.RegionStampTable regionStamps;
    // Region summaries (P2): null iff regionStamps is null.
    private final dev.vox.lss.common.region.RegionSummaryService regionSummaries;
    private final DirtyColumnTracker dirtyTracker;
    private final PaperDirtyColumnBroadcaster dirtyBroadcaster;
    // The v16 compat shim's per-player sessions (legacy protocol-16 clients). The pipeline
    // never consults it: a v16 player is an ordinary registered player whose want-set is
    // declared by the shim at 1 Hz. See docs/planning/v16-compat-design.md.
    private final V16CompatManager v16Compat = new V16CompatManager();
    // Every session's wire dialect (cross-version-identity-encoding-plan §4.3): the
    // single source of truth for egress shape decisions — replaces the old v18 bare set
    // AND the v16 egress checks (the manager keeps its session objects for the ingress
    // shim). Marked ONLY on the pump (the dialectFlip runnable) — see
    // docs/planning/v18-compat-design.md §2.3.
    private final WireDialectTracker dialects = new WireDialectTracker();
    // Far players (E1, FARP §3.2): subscription identity at the service level (the
    // dialect-tracker precedent) — subscribed on the PUMP in the Register drain (after
    // the dialectFlip, so the CURRENT-dialect gate reads post-flip state), dropped at
    // the quit-originated mailbox Remove, notified (never removed) on dimension change.
    // Vanish bridge seam null until the reflective ladder lands (E2/E3).
    private final dev.vox.lss.common.farplayers.FarPlayerBroadcastService farPlayerService =
            new dev.vox.lss.common.farplayers.FarPlayerBroadcastService(null);
    private int farPlayerTickCounter;

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
    /** Warn-once latch for the v18 egress splice guard (pump thread only). */
    private boolean v18SpliceWarned;

    /** The per-player column egress (PUMP): {@link #routeColumnFrame} over the real
     *  NMS send. The routing itself is package-private so the Tier-1 twin can drive
     *  the dialect ladder with a capturing sender. */
    private ColumnPayloadSender columnPayloadSender = (state, data) ->
            routeColumnFrame(state, data, bytes -> PaperPayloadHandler.sendRawNmsPayload(
                    state.getPlayer().getBukkitEntity(), PaperPayloadHandler.ID_VOXEL_COLUMN, bytes));

    /** The column egress routing (PUMP). Legacy (v19/v18/v16) sessions' BODIES are
     *  already native: the C2 translation runs at the per-recipient ENQUEUE choke point
     *  ({@code PaperOffThreadProcessor.buildAndEnqueueColumnPayload}) so every queued
     *  size — gauges, bandwidth budget, diag books, soak law A2 — matches what the
     *  legacy client decodes. This seam applies only the HEADER shapes: v16 splices to
     *  the source-less layout and prunes the synthetic want-set (satisfied-by-data;
     *  load-bearing, design §4.4), v18 strips the codec byte, v19 IS the current
     *  header. Every failure shape is a warn-once DROP (design §5): letting the
     *  exception propagate would make flushSendQueue drop the player's WHOLE queue,
     *  and honest re-resolution would re-enqueue the same frame forever. */
    void routeColumnFrame(PaperPlayerRequestState state, byte[] data,
                          java.util.function.Consumer<byte[]> rawSend) {
        var uuid = state.getPlayerUUID();
        if (this.dialects.isV16(uuid)) {
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
            rawSend.accept(legacy);
            this.v16Compat.onColumnSent(uuid, packedPos);
            return;
        }
        if (this.dialects.isV18(uuid)) {
            // v18 egress (v18-compat design §2.6): strip the codec byte, keep the
            // source byte. No prune — there is no synthetic want-set; the client's
            // own re-declaration heals any drop. The splice THROWS on a non-RAW
            // codec (the narrow cross-dialect downgrade window); the warn-drop
            // contains it, mirroring the v16 branch above.
            byte[] v18Frame;
            try {
                v18Frame = PaperPayloadHandler.rewriteColumnToV18(data);
            } catch (Exception e) {
                if (!this.v18SpliceWarned) {
                    this.v18SpliceWarned = true;
                    LSSLogger.error("v18-compat: dropping unspliceable column frame for "
                            + state.getPlayerName() + " (further drops are silent)", e);
                }
                return;
            }
            rawSend.accept(v18Frame);
            return;
        }
        // CURRENT and V19 ship verbatim — the v19 header IS the current header, and a
        // v19 session's body was translated at enqueue.
        rawSend.accept(data);
    }

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
                  PaperDirtyColumnBroadcaster dirtyBroadcaster,
                  dev.vox.lss.common.store.LodStoreService lodStore,
                  PaperXrayMaskManager xrayMasks,
                  boolean wireCompressionLive,
                  dev.vox.lss.common.region.RegionStampTable regionStamps) {

        /** Pre-region-stamps full shape — test wirings that don't exercise the header
         *  rung (null table = rung inert, exactly the bare-reader behavior). */
        Wiring(Map<UUID, PaperPlayerRequestState> players,
               PaperChunkDiskReader diskReader,
               PaperChunkGenerationService generationService,
               PaperOffThreadProcessor offThreadProcessor,
               DirtyColumnTracker dirtyTracker,
               PaperDirtyColumnBroadcaster dirtyBroadcaster,
               dev.vox.lss.common.store.LodStoreService lodStore,
               PaperXrayMaskManager xrayMasks,
               boolean wireCompressionLive) {
            this(players, diskReader, generationService, offThreadProcessor,
                    dirtyTracker, dirtyBroadcaster, lodStore, xrayMasks,
                    wireCompressionLive, null);
        }

        /** Pre-compression full shape (no wire codec attached) — store-era test wirings. */
        Wiring(Map<UUID, PaperPlayerRequestState> players,
               PaperChunkDiskReader diskReader,
               PaperChunkGenerationService generationService,
               PaperOffThreadProcessor offThreadProcessor,
               DirtyColumnTracker dirtyTracker,
               PaperDirtyColumnBroadcaster dirtyBroadcaster,
               dev.vox.lss.common.store.LodStoreService lodStore,
               PaperXrayMaskManager xrayMasks) {
            this(players, diskReader, generationService, offThreadProcessor,
                    dirtyTracker, dirtyBroadcaster, lodStore, xrayMasks, false);
        }

        /** Pre-store test-wiring shape (no store attached, no mask manager published —
         *  a test-wired service must retract nothing at shutdown). */
        Wiring(Map<UUID, PaperPlayerRequestState> players,
               PaperChunkDiskReader diskReader,
               PaperChunkGenerationService generationService,
               PaperOffThreadProcessor offThreadProcessor,
               DirtyColumnTracker dirtyTracker,
               PaperDirtyColumnBroadcaster dirtyBroadcaster) {
            this(players, diskReader, generationService, offThreadProcessor,
                    dirtyTracker, dirtyBroadcaster, null, null, false);
        }
    }

    // Null in test wiring — only the production-default regionTaskScheduler dereferences it,
    // and probe tests always inject a recording scheduler.
    private Plugin plugin;
    // Null in test wiring (the test ctor never publishes the static mask manager, so its
    // shutdown must retract nothing) — published by productionWiring BEFORE the store
    // Environment snapshots mask fingerprints (R2-M1).
    private PaperXrayMaskManager xrayMasks;
    // Compressed-column shipping is live: useCompressedColumns AND the server-side zstd
    // native probe succeeded (latched in productionWiring — plan §0.11). A term of every
    // session's wantsCompressedColumns derivation at registration; false in test wirings
    // unless injected.
    private final boolean wireCompressionLive;

    public PaperRequestProcessingService(MinecraftServer server, Plugin plugin, PaperConfig config) {
        this(server, config, productionWiring(server, plugin, config));
        this.plugin = plugin;
        if (plugin instanceof LSSPaperPlugin lss) {
            // The grant sweep's re-offer: the stored handshake replays through the
            // plugin's PRODUCTION receiver body — full ladder, real gate, deferred reply.
            this.handshakeReplayer = lss::replayServiceGateHandshake;
        }
    }

    /** Test seam: same field wiring as production, collaborators injected. */
    PaperRequestProcessingService(MinecraftServer server, PaperConfig config, Wiring wiring) {
        this.server = server;
        this.config = config;
        this.players = wiring.players();
        this.diskReader = wiring.diskReader();
        this.generationService = wiring.generationService();
        this.bandwidthLimiter = new SharedBandwidthLimiter(config.bytesPerSecondGlobal());
        this.offThreadProcessor = wiring.offThreadProcessor();
        this.dirtyTracker = wiring.dirtyTracker();
        this.dirtyBroadcaster = wiring.dirtyBroadcaster();
        this.lodStore = wiring.lodStore();
        this.regionStamps = wiring.regionStamps();
        // Region summaries (P2, plan §5): sweeper + mailboxes over the stamp table.
        // Null table (pre-region-stamps test wirings) = feature inert. Disconnect
        // cleanup is TTL-based (see the Fabric twin's field comment).
        this.regionSummaries = this.regionStamps == null ? null
                : new dev.vox.lss.common.region.RegionSummaryService(
                        this.regionStamps::tileStampSeconds,
                        () -> this.config.lodDistanceChunks);
        // Null in test wiring: the guarded retract at shutdown must clear only a
        // manager this service actually published.
        this.xrayMasks = wiring.xrayMasks();
        this.wireCompressionLive = wiring.wireCompressionLive();
        // C2: the per-recipient enqueue consults the session dialect to translate
        // legacy (v19/v18/v16) column bodies to the native layout at build time.
        if (this.offThreadProcessor != null) {
            this.offThreadProcessor.attachDialectTracker(this.dialects);
        }
        // Stamped up_to_date (stamped-up-to-date-plan.md §9.2, the Fabric twin's
        // wiring): compare-backed rungs stamp "verified now" unless the position's
        // change is marked-but-undrained or the region latch is armed. Null table
        // (pre-region-stamps test wirings) keeps the NEVER default — no stamps.
        // Paper residual (plan §9.3 as corrected by §10 item 5, accepted-with-eyes-
        // open and UNCANARIED): an event-blind content change (the unfired-event
        // class) is invisible to BOTH guards; its stamp seals until the chunk's next
        // save — the store resweep bounds the store-rung arm. No soak canaries the
        // class (paper-store-unfired-event's client is summary-gated off — the canary
        // is structurally impossible); theEventBlindStateStampsByAcceptedDesign pins
        // the residual as deliberate.
        if (this.offThreadProcessor != null && this.regionStamps != null
                && this.dirtyTracker != null) {
            this.offThreadProcessor.setUpToDateStampSource((player, dim, packed) -> {
                // Eligibility FIRST (3-Opus fold — see the Fabric twin).
                var s = this.regionSummaries;
                if (s == null || !s.hasRequestedThisSession(player)) return -1L;
                if (this.dirtyTracker.isPending(dim, packed)) return -1L;
                if (this.regionStamps.isClaimSuppressed(dim,
                        PositionUtil.unpackX(packed), PositionUtil.unpackZ(packed))) {
                    return -1L;
                }
                return LSSConstants.epochSeconds();
            });
        }
    }

    private static Wiring productionWiring(MinecraftServer server, Plugin plugin, PaperConfig config) {
        // The x-ray mask manager MUST be published before anything below consults it
        // (4-agent round R2-M1): the store Environment snapshots each level's mask
        // fingerprint via entryForActive, and Java evaluates this whole method BEFORE
        // the delegating ctor body runs — the old ctor-body activate left the holder
        // unset here, every dimension fingerprinted "off", and the mask-drift
        // drop-and-rebuild permanently inert on Paper (an x-ray leak on any mask
        // widening). The Fabric twin activates before its Environment for the same
        // reason.
        var xrayMasks = PaperXrayMaskManager.activate(config);
        Map<UUID, PaperPlayerRequestState> players = new ConcurrentHashMap<>();
        // Paper/Folia reads ALWAYS route through Moonrise at Priority.LOW, so the prioritized
        // AUTO tier applies whenever background priority is on (unlike Fabric, which must
        // also probe for Moonrise). With the flag off the reads run FOREGROUND, so the pool
        // must be sized by the unprioritized tier — see the Fabric twin. (v0.9.0 review.)
        int readerThreads = config.effectiveDiskReaderThreads(config.useBackgroundReadPriority);
        var diskReader = new PaperChunkDiskReader(
                readerThreads,
                config.useBackgroundReadPriority,
                config.useNbtTranscode);
        PaperChunkGenerationService generationService = config.enableChunkGeneration
                ? new PaperChunkGenerationService(config, plugin) : null;

        var dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
        var offThreadProcessor = new PaperOffThreadProcessor(
                players, diskReader, generationService != null, dataDir,
                config.effectiveTimestampCacheMB(), config.missMemoTtlSeconds,
                config.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER
                        + OffThreadProcessor.SWEEP_RADIUS_MARGIN_CHUNKS);

        // Compressed-column shipping (protocol 19, plan §0.11) — twin of the Fabric
        // service's latch: one server-side native probe; failure degrades to raw
        // sessions with one warning (zstd-jni publishes no musl natives, and musl
        // servers are common). Independent of the store's own probe below.
        boolean wireCompressionLive = false;
        if (config.useCompressedColumns) {
            var wireCodec = dev.vox.lss.common.store.StoreCodec.zstdOrNull();
            if (wireCodec == null) {
                LSSLogger.warn("useCompressedColumns is enabled but the "
                        + dev.vox.lss.common.store.StoreCodec.NAME + " native cannot load"
                        + " on this platform — LOD columns will ship uncompressed for"
                        + " every session");
            } else {
                offThreadProcessor.attachWireCodec(wireCodec);
                // Frame-form store serving (plan §3) — twin of the Fabric latch.
                diskReader.setServeStoreFrames(true);
                wireCompressionLive = true;
            }
        }
        // LOD store: the SQLite engine for "on"/"full" (the memory tier is deleted) —
        // attached to both consumers BEFORE the processor starts / any submit. Environment resolved
        // eagerly on the construction thread (levels loaded at plugin enable); the
        // periodic re-sweep (lodStoreResweepSeconds) is PAPER's stale bound for its
        // unfired-event dirty gaps. A failed codec/native probe degrades to store-off
        // with one warning (the Fabric twin is identical).
        dev.vox.lss.common.store.LodStoreService lodStore = null;
        // enabled=false must not open the store (Fabric twin: the same guard). Paper
        // has no backfill so the cost is a DB file and a sweep thread rather than a
        // full-world walk, but "LSS is off" should still mean nothing is created.
        var storeMode = config.enabled
                ? dev.vox.lss.common.store.LodStoreMode.normalize(config.lodStore)
                : dev.vox.lss.common.store.LodStoreMode.OFF;
        if (storeMode == dev.vox.lss.common.store.LodStoreMode.OFF) {
            // Suppressed on Folia: the store is unvalidated there (validate() WARNS on
            // an explicit full) — recommending what we warn about is incoherent.
            var advice = dev.vox.lss.common.store.LodStores
                    .offRecommendationOrNull(config.enabled, FoliaSupport.IS_FOLIA);
            if (advice != null) {
                LSSLogger.info(advice);
            }
        }
        // Region-dir resolver, HOISTED out of the store branch (region-summary-sync-plan.md
        // §5 integration M2 — the P1 header rung must work store-LESS). The per-line
        // layout invariant (surfaces row 17) lives on resolveRegionDirs below.
        var worldRoot = server.getWorldPath(LevelResource.ROOT).normalize();
        var regionDirs = resolveRegionDirs(server, worldRoot);
        var regionStamps = new dev.vox.lss.common.region.RegionStampTable(regionDirs::get);
        diskReader.attachRegionStamps(regionStamps);
        // Tracker + mark listener BEFORE the processor starts (the Fabric twin's
        // ordering): every dirty mark from the first tick onward must bump the region's
        // live save mark. Paper's Bukkit events register later either way; this keeps
        // the two platforms' wiring order identical.
        var dirtyTracker = new DirtyColumnTracker();
        // Marks fire at EDIT time on Paper — strictly no later than the save, so the
        // latch arms before the write can lag the header. Region threads under Folia —
        // the bump is atomic.
        dirtyTracker.setMarkListener((dim, cx, cz) -> regionStamps
                .bumpLiveSaveMark(dim, cx, cz, LSSConstants.epochSeconds()));

        if (storeMode != dev.vox.lss.common.store.LodStoreMode.OFF) {
            var maskFingerprints = new java.util.HashMap<String, String>();
            for (ServerLevel level : server.getAllLevels()) {
                String dim = level.dimension().location().toString();
                var maskEntry = PaperXrayMaskManager.entryForActive(level);
                maskFingerprints.put(dim, maskEntry == null ? "off"
                        : maskEntry.sourceLabel() + ":"
                                + Long.toHexString(maskEntry.mask().fingerprint()));
            }
            // ONE registry walk feeds both fingerprints (plan §3.2) — the twin of
            // the Fabric service's call, pinned the same way by the Paper contract
            // test (of()/contentOf() delegation named at the call site).
            var registryIds = storeRegistryIdentity(server);
            var env = new dev.vox.lss.common.store.SqliteLodStore.Environment(
                    dev.vox.lss.common.store.LodStores.brandedStoreDir(worldRoot), server.getServerVersion(),
                    LSSConstants.PROTOCOL_VERSION, regionDirs::get, maskFingerprints::get,
                    config.lodStoreResweepSeconds, config.lodStoreMaxBytes(),
                    dev.vox.lss.common.store.RegistryFingerprint.of(
                            registryIds.states(), registryIds.biomes()),
                    dev.vox.lss.common.store.RegistryFingerprint.contentOf(
                            registryIds.states(), registryIds.biomes()));
            lodStore = dev.vox.lss.common.store.LodStores.createOrNull(env);
            if (lodStore == null) {
                // LodStores.createOrNull logged the per-cause warn (codec vs SQLite init —
                // final-review A-M1: one shared message here misattributed SQLite failures).
            } else {
                diskReader.attachStore(lodStore);
                // C4: pre-migration wirefmt=19 store rows translate to the canonical
                // v20 form at the serve rung, against this server's own registries.
                diskReader.setStoreLegacyTranslator(nativeRaw ->
                        PaperNbtSectionSerializer.toV20(nativeRaw, server.registryAccess()));
                lodStore.setLegacyMigrationTranslator(nativeRaw ->
                        PaperNbtSectionSerializer.toV20(nativeRaw, server.registryAccess()));
                offThreadProcessor.attachStore(lodStore);
            }
        }

        // Disk-read concurrency gate K (twin of the Fabric wiring): resolved against
        // the POST-DEGRADE store state — `lodStore != null`, never the config string,
        // or half-pool K would re-arm on exactly the store-less servers the
        // store-conditional AUTO carves out (failed codec probe, enabled=false).
        int gateCapacity = config.effectiveMaxConcurrentDiskReads(readerThreads,
                lodStore != null);
        diskReader.configureReadGate(gateCapacity);
        // Script-consumed contract: the measurement harnesses assert their staged knobs
        // against this line (ServerConfigBase.effectiveConfigEcho). Deliberately AFTER
        // the zstd probe (the compression value echoed is the LIVE state, not the
        // request — B0 review M1) and AFTER store attachment (the echoed K is the
        // store-conditional resolution, which does not exist until the store's own
        // degrade ladder has run — v1.3 review MAJOR).
        LSSLogger.info(config.effectiveConfigEcho(readerThreads, wireCompressionLive,
                gateCapacity));

        offThreadProcessor.start();

        var dirtyBroadcaster = new PaperDirtyColumnBroadcaster(
                server, players, dirtyTracker, offThreadProcessor);
        return new Wiring(players, diskReader, generationService, offThreadProcessor,
                dirtyTracker, dirtyBroadcaster, lodStore, xrayMasks, wireCompressionLive,
                regionStamps);
    }

    /** Region-dir resolution for the P1 freshness rungs (one call site in the wiring
     *  builder above; extracted for the v0.12.0 B.0 wiring pin —
     *  {@code PaperRegionFreshnessWiringTest} drives it with mocked levels). The
     *  PER-LINE INVARIANT (surfaces row 17) lives HERE now, at the one swappable
     *  site: Paper 26.x uses the vanilla UNIFIED layout ({@code getStorageFolder}
     *  under the server worldRoot, same as Fabric); the 1.21.x lines use Bukkit's
     *  legacy SPLIT world dirs and must re-root PER LEVEL via
     *  {@code getWorld().getWorldFolder()} — the two forms are NOT interchangeable
     *  (R2-9's live probe, 2026-08-15: 26.2's getWorldFolder() returns the
     *  per-dimension SUBFOLDER, and the unified form broke the 1.21.x port's sweep
     *  the same way). Ports adapt THIS method + the wiring test's expectations. */
    static java.util.HashMap<String, java.nio.file.Path> resolveRegionDirs(
            MinecraftServer server, java.nio.file.Path worldRoot) {
        var regionDirs = new java.util.HashMap<String, java.nio.file.Path>();
        for (ServerLevel level : server.getAllLevels()) {
            // Per-level belt: this loop runs on STORE-LESS servers too — an exotic
            // dimension key must degrade that one dimension to UNKNOWN (the table's
            // designed fail-safe), never take down service start. The catch must not
            // re-invoke anything throwable (B.0 pin finding: the old warn line called
            // level.dimension() AGAIN, so a dimension whose accessor itself throws
            // escaped the belt and killed start — capture the name first).
            String dim = null;
            try {
                dim = level.dimension().location().toString();
                // 1.21.x line (row 17): Bukkit legacy SPLIT world dirs — re-root PER
                // LEVEL via the Bukkit world's own folder (the unified-layout
                // worldRoot resolved world/DIM-1, which does not exist; review
                // 2026-08-15). getStorageFolder keeps the overworld at <folder> and
                // nests DIM-1/DIM1 for the others, matching CraftBukkit's layout.
                var levelRoot = level.getWorld().getWorldFolder().toPath().normalize();
                regionDirs.put(dim,
                        net.minecraft.world.level.dimension.DimensionType
                                .getStorageFolder(level.dimension(), levelRoot)
                                .resolve("region").normalize());
            } catch (Throwable t) {
                LSSLogger.warn("Could not resolve the region directory for "
                        + (dim != null ? dim : "<unresolvable dimension>")
                        + " — region freshness there falls through to full reads", t);
            }
        }
        return regionDirs;
    }

    private record RegistryIdentity(java.util.List<String> states,
                                    java.util.List<String> biomes) {}

    /** Registry identity for the LOD store meta guard (4-agent round R2-M3) — textual
     *  twin of {@code RequestProcessingService.storeRegistryIdentity}: both identity
     *  lists are id-ordered (review A3 — the old block half was a bare COUNT, so an
     *  id-permuting registry change of identical total size served every warm column
     *  as the wrong blocks with no self-heal); a mod/datapack change shifts the
     *  global ids the stored wire bytes embed while no freshness rule can fire. The
     *  store construction derives the ordered {@code of} AND order-insensitive
     *  {@code contentOf} fingerprints from this ONE walk (v0.13.1 permutation plan
     *  §3.2). */
    private static RegistryIdentity storeRegistryIdentity(MinecraftServer server) {
        var states = new java.util.ArrayList<String>();
        for (var state : net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY) {
            states.add(String.valueOf(state));
        }
        var biomeKeys = new java.util.ArrayList<String>();
        var biomes = server.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        for (var biome : biomes) {
            var key = biomes.getKey(biome);
            biomeKeys.add(key == null ? "?" : key.toString());
        }
        return new RegistryIdentity(states, biomeKeys);
    }

    /** The live LOD store (null while lodStore=off OR after the codec-probe degrade). */
    public dev.vox.lss.common.store.LodStoreService getLodStore() {
        return this.lodStore;
    }

    /** Ops (/lsslod store invalidate all) — twin of the Fabric service's method: drop
     *  every stored row + backfill progress (batcher-side, tombstoned). The tscache is
     *  deliberately untouched: its stamps describe REGION truth, not store contents —
     *  re-asks re-resolve via tscache/probe/NBT as normal and re-warm the store. Only
     *  meaningful for the persistent store. Safe from any thread (Folia command
     *  dispatch is region-threaded): tombstones + a control-queue offer. */
    public boolean invalidateStoreAllDimensions() {
        if (this.lodStore instanceof dev.vox.lss.common.store.SqliteLodStore sqlite) {
            sqlite.requestDropAllRows();
            return true;
        }
        return false;
    }

    public DirtyColumnTracker getDirtyTracker() {
        return this.dirtyTracker;
    }

    /** The region freshness stamp table (P1 header rung; P2 summary sweeper). Null in
     *  pre-region-stamps test wirings. */
    public dev.vox.lss.common.region.RegionStampTable getRegionStamps() {
        return this.regionStamps;
    }

    /** Cross-thread lifecycle ingress. On Folia, handshakes and PlayerQuit arrive on region
     *  threads; registerPlayer/removePlayer mutate pump-owned state (including the generation
     *  service's non-concurrent maps), so region-thread callers enqueue here and tick() drains
     *  first — one queue preserves arrival order across a kick→rejoin of the same UUID. */
    private sealed interface LifecycleEvent {
        /** {@code beforeRegister} runs on the PUMP immediately before registerPlayer;
         *  {@code replyAfterRegister} immediately after. See the enqueueRegister javadoc
         *  for why the dialect flip must be the former and the reply the latter. */
        record Register(ServerPlayer player, int capabilities,
                        Runnable beforeRegister, Runnable replyAfterRegister)
                implements LifecycleEvent {}
        /** {@code connectionEpoch} = the dying connection's epoch at quit time —
         *  the R4 guard's comparator (Folia review 2026-08-27). */
        record Remove(UUID uuid, long connectionEpoch) implements LifecycleEvent {}
    }

    private final ConcurrentLinkedQueue<LifecycleEvent> lifecycleMailbox = new ConcurrentLinkedQueue<>();

    /** Any thread. Applied at the top of the next tick(). */
    public void enqueueRegister(ServerPlayer player, int capabilities) {
        enqueueRegister(player, capabilities, () -> { }, () -> { });
    }

    /** Any thread; no pre-register hook. */
    public void enqueueRegister(ServerPlayer player, int capabilities, Runnable replyAfterRegister) {
        enqueueRegister(player, capabilities, () -> { }, replyAfterRegister);
    }

    /**
     * Any thread. Applied at the top of the next tick(); {@code replyAfterRegister} runs on
     * the pump IMMEDIATELY AFTER the player state exists. This ordering is the fix for the
     * Folia pre-registration drop (soak-diagnosed 2026-07-27): the handshake used to reply
     * SessionConfig inline on the region thread while the registration waited here, so a
     * well-behaved client's FIRST want-set could arrive before any state existed and was
     * dropped uncounted. Replying only after the drain makes that window unreachable for
     * clients that declare only after receiving SessionConfig (all of them).
     */
    public void enqueueRegister(ServerPlayer player, int capabilities,
                                Runnable beforeRegister, Runnable replyAfterRegister) {
        this.lifecycleMailbox.add(new LifecycleEvent.Register(
                player, capabilities, beforeRegister, replyAfterRegister));
    }

    /** Any thread. Applied at the top of the next tick(). */
    public void enqueueRemove(UUID uuid) {
        this.lifecycleMailbox.add(new LifecycleEvent.Remove(uuid,
                this.connectionEpochs.getOrDefault(uuid, 0L)));
    }

    // Connection epochs (Folia review 2026-08-27 R4): the mailboxed Remove drains up
    // to a pump tick (or more, on a lagging Folia global thread) after the quit, and
    // two structures are written SYNCHRONOUSLY on the successor session's region
    // threads — the service gate's denial memo (at handshake) and the region-summary
    // request + eligibility mark (at dimension entry). A fast rejoin landing between
    // the old quit and the old Remove's drain would have that fresh state wiped by
    // the Remove's connection-scoped belts: the gate case strands a disarmed rejoiner
    // with no re-offer; the summary case leaves summaries + stamped up_to_date dark
    // for the whole dimension visit (the client requests only at entry). Every
    // handshake marks its connection's epoch (region thread, CHM); the Remove carries
    // the epoch captured at quit; the two belts run only when no NEWER connection has
    // handshaked since. Mailbox-FIFO-protected structures (dialects, far players —
    // whose rejoin writes ride the mailbox or the runtime-task queue, both drained
    // after this) need no guard. Known residual: a duplicate-login kick whose NEW
    // handshake precedes the OLD quit event captures the new epoch and the belts run
    // — the pre-fix window, now needing an ordering inversion as well.
    private final ConcurrentHashMap<UUID, Long> connectionEpochs = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong connectionEpochCounter =
            new java.util.concurrent.atomic.AtomicLong();

    /** Any thread (the plugin's handshake ingress, region threads on Folia). */
    public void markConnection(UUID uuid) {
        this.connectionEpochs.put(uuid, this.connectionEpochCounter.incrementAndGet());
    }

    // Runtime /lsslod set marshaling (v0.11.0 stage C): commands may arrive on a REGION
    // thread on Folia, and the mutation path (config assign + validate + save + the
    // re-push) touches pump-owned state — so the command surface enqueues here and the
    // pump drains after the lifecycle mailbox (ordering: see the tick() comment).
    private final ConcurrentLinkedQueue<Runnable> runtimeTasks = new ConcurrentLinkedQueue<>();

    /** Any thread. Runs on the pump at the top of the next tick(), after the lifecycle
     *  drain. Replies from inside the task reach the sender cross-thread (Bukkit
     *  sendMessage is thread-safe for console and Adventure-backed players). A task
     *  enqueued in the shutdown window (pump cancelled, queue never drained again) is
     *  silently dropped — acceptable for admin commands: the config file write already
     *  happened on the command thread, only the live-apply/reply is lost. */
    public void enqueueRuntimeTask(Runnable task) {
        this.runtimeTasks.add(task);
    }

    private void drainRuntimeTasks() {
        Runnable task;
        while ((task = this.runtimeTasks.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                // Exception, not Throwable: an Error (OOM, linkage) must propagate —
                // swallowing it here would hide a dying JVM behind a log line.
                LSSLogger.error("Runtime settings task failed", e);
            }
        }
    }

    // The tick-poll appliers (v0.11.0 stage C, twin of the Fabric block): each formerly
    // capture-at-construction consumer re-applies config at the top of the tick on the
    // pump. Change-guarded so the steady state costs a few field compares.
    private int lastAppliedGenGlobal = -1;
    private int lastAppliedGenPerPlayer = -1;

    private void applyRuntimeConfig() {
        this.bandwidthLimiter.reconfigure(this.config.bytesPerSecondGlobal());
        this.diskReader.reapplyGateCapacity(this.config);
        this.offThreadProcessor.updateSweepRadius(this.config.lodDistanceChunks
                + LSSConstants.LOD_DISTANCE_BUFFER
                + dev.vox.lss.common.processing.OffThreadProcessor.SWEEP_RADIUS_MARGIN_CHUNKS);
        int genGlobal = this.config.generationConcurrencyLimitGlobal;
        int genPerPlayer = this.config.generationConcurrencyLimitPerPlayer;
        if (genGlobal != this.lastAppliedGenGlobal || genPerPlayer != this.lastAppliedGenPerPlayer) {
            if (this.generationService != null) {
                this.generationService.updateCaps(genGlobal, genPerPlayer);
            }
            for (var state : this.players.values()) {
                state.updateGenSlotCap(genPerPlayer);
            }
            this.lastAppliedGenGlobal = genGlobal;
            this.lastAppliedGenPerPlayer = genPerPlayer;
        }
    }

    /**
     * Push a fresh SessionConfig to every CURRENT-dialect (v20) session (twin of the
     * Fabric method; SET plan §"Pushing the new distance"). PUMP-ONLY, and only via a
     * runtime task drained AFTER the lifecycle mailbox — the ordering pin: a
     * registered-but-flip-pending player must never be enumerated as CURRENT (the
     * tracker defaults untracked to CURRENT, and the flip applies in the drain's
     * beforeRegister). Legacy sessions (v19/v18/v16) are skipped; they keep the
     * handshake distance until rejoin.
     *
     * @return {pushed, legacySkipped}
     */
    /** Test seam: sends one v20 SessionConfig frame. Production default is the raw
     *  plugin-message send (the broadcaster's setDirtySender pattern). */
    @FunctionalInterface
    interface SessionConfigSender {
        /** {@code enabled} is passed explicitly (not read off the config): the service
         *  gate's revocation push advertises {@code false} at a player on a server whose
         *  config says {@code true}. */
        void send(ServerPlayer player, PaperConfig config, boolean enabled) throws Exception;
    }

    private SessionConfigSender sessionConfigSender = (player, cfg, enabled) ->
            PaperPayloadHandler.sendSessionConfig(player.getBukkitEntity(),
                    LSSConstants.PROTOCOL_VERSION, enabled,
                    cfg.lodDistanceChunks, cfg.enableChunkGeneration);

    void setSessionConfigSender(SessionConfigSender sender) {
        this.sessionConfigSender = sender;
    }

    public int[] repushSessionConfig() {
        int pushed = 0;
        int legacy = 0;
        for (var state : this.players.values()) {
            if (this.dialects.dialectOf(state.getPlayerUUID())
                    != dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT) {
                legacy++;
                continue;
            }
            try {
                this.sessionConfigSender.send(state.getPlayer(), this.config, this.config.enabled);
                pushed++;
            } catch (Exception e) {
                LSSLogger.error("Session-config re-push failed for "
                        + state.getPlayer().getName().getString(), e);
            }
        }
        return new int[]{pushed, legacy};
    }

    /**
     * The two service-gate sweeps (service-permission-gate-plan.md §2.3) — the textual
     * twin of {@code RequestProcessingService.runServiceGateSweeps}, with the Paper
     * differences: Bukkit permissible reads on the pump, the widened
     * {@link SessionConfigSender} for the enabled=false push, and the replay through
     * the plugin's production receiver via {@link #setHandshakeReplayer} (deferred
     * reply — the Folia pre-registration gap stays closed). Public so tests drive one
     * sweep directly; the one production caller is the tick cadence. Pump thread only.
     */
    public void runServiceGateSweeps() {
        var gateState = this.serviceGateState;
        var config = this.config;
        if (config.requireServicePermission) {
            for (var state : this.players.values()) {
                UUID uuid = state.getPlayerUUID();
                if (this.dialects.dialectOf(uuid)
                        != dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT) {
                    continue; // legacy sessions heal at rejoin — recorded accepted-open
                }
                var player = state.getPlayer();
                boolean holds;
                try {
                    holds = this.permissionProbe.test(player,
                                    dev.vox.lss.common.LSSPermissions.SERVICE_LSS)
                            && this.permissionProbe.test(player,
                                    dev.vox.lss.common.LSSPermissions.SERVICE_VSS);
                } catch (Exception e) {
                    holds = true; // contained: a throwing backend counts as HOLDING
                }
                if (holds) {
                    gateState.resetRevocationStreak(uuid);
                    continue;
                }
                if (!gateState.bumpRevocationStreak(uuid)) continue;
                try {
                    this.sessionConfigSender.send(player, config, false);
                } catch (Exception e) {
                    LSSLogger.error("Service-gate disable push failed for "
                            + player.getName().getString(), e);
                }
                gateState.rememberDenied(uuid, player.getName().getString(),
                        LSSConstants.PROTOCOL_VERSION, state.getCapabilities());
                unregisterForServiceGate(uuid);
                LSSLogger.info("LOD service revoked for " + player.getName().getString()
                        + ": requireServicePermission is on and a permission recheck found "
                        + "a negative grant on " + dev.vox.lss.common.LSSPermissions.SERVICE_LSS
                        + " or " + dev.vox.lss.common.LSSPermissions.SERVICE_VSS
                        + " (either spelling denies) — the session was told "
                        + dev.vox.lss.common.Brand.shortName() + " is disabled; it is re-offered automatically "
                        + "if the grant returns");
            }
        } else {
            // Disarmed: no streak may survive into a later re-arm (the two-sweep
            // hysteresis must start fresh).
            gateState.clearRevocationStreaks();
        }
        if (gateState.hasDenied()) {
            for (UUID uuid : gateState.deniedSnapshot()) {
                if (this.players.containsKey(uuid)) {
                    // SKIP, never clear (implementation review, 2026-08-27): a denied
                    // re-handshake deposits the memo on a region thread while the
                    // unregister composite is still queued behind the lifecycle drain —
                    // clearing here would wipe the deposit and strand the player past
                    // its own revocation. A stale entry is retained one sweep and
                    // replays correctly on the next.
                    continue;
                }
                var player = this.server.getPlayerList().getPlayer(uuid);
                if (player == null) {
                    // Offline: EVERYTHING gate-side is session-scoped — sweeping only
                    // the memo would leak the log latch (a same-UUID rejoin would then
                    // be denied silently) and any streak.
                    gateState.onDisconnect(uuid);
                    continue;
                }
                boolean cleared;
                if (!config.requireServicePermission) {
                    cleared = true; // a disarmed gate trivially clears everyone
                } else {
                    try {
                        cleared = this.permissionProbe.test(player,
                                        dev.vox.lss.common.LSSPermissions.SERVICE_LSS)
                                && this.permissionProbe.test(player,
                                        dev.vox.lss.common.LSSPermissions.SERVICE_VSS);
                    } catch (Exception e) {
                        cleared = true; // fail-open, the doctrine
                    }
                }
                if (!cleared) continue;
                if (this.handshakeReplayer == null) continue; // bare test wiring: RETAIN
                var remembered = gateState.takeDenied(uuid);
                if (remembered == null) continue;
                LSSLogger.info("Re-offering " + dev.vox.lss.common.Brand.shortName() + " to "
                        + remembered.playerName() + " (re-offer): "
                        + (config.requireServicePermission
                                ? "the service permission cleared"
                                : "requireServicePermission was disarmed")
                        + " — replaying the stored handshake");
                try {
                    this.handshakeReplayer.accept(player, remembered);
                } catch (Exception e) {
                    // Contained — twin of the Fabric sweep: a throwing replay must not
                    // abort the remaining re-offers; the entry stays dropped (rejoin heals).
                    LSSLogger.error("Service-gate re-offer failed for "
                            + remembered.playerName(), e);
                }
            }
        }
    }

    private void drainLifecycleMailbox() {
        LifecycleEvent ev;
        while ((ev = this.lifecycleMailbox.poll()) != null) {
            // Contained per event: one throwing register/remove must not abort the rest of
            // the drain (a register after it would silently never apply) — and a register
            // that DID publish state before throwing must still run its deferred reply, or
            // the client sits SessionConfig-less until the v16 discovery timer degrades the
            // session (final review 2026-07-27).
            try {
                switch (ev) {
                    case LifecycleEvent.Register r -> {
                        // On the PUMP, before registration: the wire-dialect flip. It must
                        // be here rather than on the calling thread, because on Folia the
                        // handshake arrives on a REGION thread and the flip takes effect
                        // instantly, while the SessionConfig that re-arms the client's
                        // decoder is deferred to this drain — so a flip made off-pump can
                        // land mid-tick and let the rest of that tick's flush ship
                        // NEW-dialect columns to a decoder still armed for the OLD one,
                        // which the client reads as a malformed frame and disconnects on.
                        // It must also be before registerPlayer, which derives
                        // wantsCompressedColumns from the dialect. (Round-3 review.)
                        r.beforeRegister().run();
                        try {
                            registerPlayer(r.player(), r.capabilities());
                            // Service gate: a HANDSHAKE registration (the grant replay's deferred
                            // Register rides this same drain) ends the denied episode — memo gone,
                            // log latch re-armed. Not in registerPlayer: that is the dim-change
                            // reuse path (R3).
                            this.serviceGateState.onRegistered(r.player().getUUID());
                            // Far players: post-flip, so the CURRENT-dialect gate is
                            // reliable (legacy layouts predate the capability bit).
                            if ((r.capabilities() & LSSConstants.CAPABILITY_FAR_PLAYERS) != 0
                                    && !this.dialects.isV16(r.player().getUUID())
                                    && !this.dialects.isV18(r.player().getUUID())
                                    && !this.dialects.isV19(r.player().getUUID())) {
                                this.farPlayerService.subscribeViewer(r.player().getUUID());
                            } else {
                                // Re-handshake without the bit / on a legacy dialect
                                // sheds any prior subscription (review: a same-session
                                // downgrade must not keep streaming far-player frames
                                // to a decoder that no longer expects them).
                                this.farPlayerService.removeViewer(r.player().getUUID());
                            }
                        } finally {
                            if (this.players.containsKey(r.player().getUUID())) {
                                // State exists from this line on — the deferred SessionConfig
                                // reply may now invite the client's first declaration.
                                r.replyAfterRegister().run();
                            }
                        }
                    }
                    case LifecycleEvent.Remove r -> {
                        removePlayer(r.uuid());
                        // Quit-race leak guard (v18-compat §2.3, review F2): the quit's
                        // direct onDisconnect can run BEFORE a deferred Register's
                        // dialectFlip marked membership, leaking the entry forever. The
                        // mailbox Remove is quit-originated ONLY (the dimension-change
                        // cycle calls removePlayer directly), so dropping here is exactly
                        // the network-disconnect semantics and cannot break the
                        // identity-survives-dim-change contract.
                        this.dialects.onDisconnect(r.uuid());
                        this.farPlayerService.onDisconnect(r.uuid());
                        // R4: the two belts over REGION-THREAD-WRITTEN state run only
                        // when no newer connection handshaked since the quit — see the
                        // connectionEpochs comment. A skipped sweep leaves at most the
                        // OLD session's residue in UUID-keyed maps the successor session
                        // overwrites/merges; the pump's anchor-less summary eligibility
                        // sweep and the gate's grant sweep are the belts for true leaks.
                        boolean noNewerConnection = r.connectionEpoch()
                                >= this.connectionEpochs.getOrDefault(r.uuid(), 0L);
                        if (noNewerConnection) {
                            // Service gate: connection-scoped sweep (memo, log latch,
                            // streak) — idempotent beside the quit hook's own call.
                            this.serviceGateState.onDisconnect(r.uuid());
                            // Region summaries: connection-scoped cleanup, never the
                            // dim-change cycle.
                            if (this.regionSummaries != null) this.regionSummaries.removePlayer(r.uuid());
                            this.connectionEpochs.remove(r.uuid());
                        }
                    }
                }
            } catch (Exception e) {
                LSSLogger.error("Lifecycle event failed to apply (" + ev.getClass().getSimpleName()
                        + ") — continuing the drain", e);
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
            s.setRegisteredDimension(player.level().dimension().location().toString());
            // Transport-pressure gauge (elytra-wall §8.3), Fabric-parity.
            s.setChannelPressureProbe(PaperChannelPressure.forPlayer(player));
            return s;
        });
        this.diskReader.registerPlayer(player.getUUID());
        state.setCapabilities(capabilities);
        // The five-term AND (plan §2 + v18-compat §2.5) — twin of the Fabric derivation.
        // Both dialect marks run in the drain's beforeRegister (the dialectFlip, pump
        // thread) immediately before this, and the drain runs registerPlayer before the
        // deferred reply, so no serve precedes the flag.
        state.setWantsCompressedColumns(this.wireCompressionLive
                && (capabilities & LSSConstants.CAPABILITY_ZSTD_COLUMNS) != 0
                && !this.dialects.isV16(player.getUUID())
                && !this.dialects.isV18(player.getUUID()));
        state.markHandshakeComplete();
        // Service gate: deliberately NOT cleared here (Folia review 2026-08-27 R3) —
        // registerPlayer is also the dimension-change reuse path (see the Register
        // drain, which clears it for handshake registrations).
        return state;
    }

    public void removePlayer(UUID uuid) {
        this.players.remove(uuid);
        this.regionProbeResults.remove(uuid);
        this.heldForProbe.remove(uuid);
        // STAMP, don't clear: a removal (dimension change on Folia, quit) makes the very
        // next state==null batch the EXPECTED remove→register race, not an orphan — the
        // prompt interval doubles as a post-removal grace, so the Folia window can never
        // fire a spurious prompt at a healthy mid-stream client, and a dimension hop
        // extends the 60 s bound instead of resetting it. Quit entries are pruned by the
        // size-bounded sweep below; a genuine orphan (plugin /reload) hits a FRESH map —
        // the service instance died with the reload — and still prompts immediately.
        this.reattachPromptAt.put(uuid, System.nanoTime() / 1_000_000L);
        if (this.reattachPromptAt.size() > REATTACH_PROMPT_MAP_BOUND) {
            long cutoff = System.nanoTime() / 1_000_000L - REATTACH_PROMPT_INTERVAL_MS;
            this.reattachPromptAt.values().removeIf(stamp -> stamp < cutoff);
        }
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

    /**
     * The service-gate unregistration composite (service-permission-gate-plan.md
     * §2.3): {@code removePlayer} + the far-player viewer shed + the region-summary
     * cleanup — the departed-player sweep's trio, NEVER a modified removePlayer
     * (that is the dimension-change reuse path on Folia; teaching it to shed viewers
     * would break every dimension change). The dialect mark and v16 identity are
     * deliberately KEPT — connection-lifecycle facts, and the mark is what any
     * per-player disable push read. No-op for an unregistered uuid. Pump thread only.
     */
    void unregisterForServiceGate(UUID uuid) {
        if (!this.players.containsKey(uuid)) return;
        removePlayer(uuid);
        this.farPlayerService.removeViewer(uuid);
        if (this.regionSummaries != null) this.regionSummaries.removePlayer(uuid);
    }

    /** Any thread (the handshake's denial hook runs on a region thread on Folia):
     *  marshals the composite onto the pump, where the registered-check happens at
     *  drain time. A registration RACING the denial (two opposite-outcome handshakes
     *  in one drain window) can still invert — the lifecycle Register applies before
     *  this runtime task regardless of arrival order, so the composite may unregister
     *  the newer granted registration; the client keeps declaring (its last config was
     *  the deferred enabled=true), so the 60 s re-attach prompt forces a re-handshake
     *  that settles the correct terminal state. Accepted: vanishingly rare and
     *  self-healing. */
    public void enqueueServiceGateUnregister(UUID uuid) {
        enqueueRuntimeTask(() -> unregisterForServiceGate(uuid));
    }

    /** The service-gate bookkeeping — see {@link dev.vox.lss.common.ServiceGateState}. */
    public dev.vox.lss.common.ServiceGateState getServiceGateState() {
        return this.serviceGateState;
    }

    /** Minimum interval between re-attach prompts per player — also the post-removal grace
     *  (removePlayer stamps the map; see maybeSendReattachPrompt). */
    static final long REATTACH_PROMPT_INTERVAL_MS = 60_000;

    /** Prompt-stamp map size that triggers a stale-entry sweep (quit players' entries have
     *  no per-UUID removal anymore — removals stamp instead). Generous: the map only grows
     *  via removals and prompts, one Long per UUID. */
    static final int REATTACH_PROMPT_MAP_BOUND = 256;

    /** Test seam: the re-attach prompt send (production: the v16-dialect SessionConfig —
     *  see maybeSendReattachPrompt for why that dialect specifically). */
    @FunctionalInterface
    interface ReattachPromptSender {
        void send(ServerPlayer player);
    }

    ReattachPromptSender reattachPromptSender = this::sendReattachPromptPayload;

    private void sendReattachPromptPayload(ServerPlayer player) {
        // C5 note (review m14): this is the one SessionConfig send outside the
        // handshake gate, so it is not Via-guarded. Reachable only through the
        // narrow registered-then-denied window (a no-signal FIRST handshake during
        // Via init) — there it produces a prompt→handshake→denial cycle bounded to
        // one prompt per REATTACH_PROMPT_INTERVAL, which the guard's INFO line makes
        // visible; a rejoin heals it.
        PaperPayloadHandler.sendSessionConfigV16(player.getBukkitEntity(),
                this.config.enabled, this.config.lodDistanceChunks,
                LSSConstants.SYNC_ON_LOAD_SLOT_CAP,
                this.config.generationConcurrencyLimitPerPlayer,
                this.config.enableChunkGeneration);
    }
    // Per-UUID last-prompt/last-removal stamps (millis). Concurrent: batches arrive on
    // region threads on Folia. removePlayer STAMPS entries (the post-removal grace) and
    // size-bounded-sweeps stale ones.
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> reattachPromptAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * /reload re-attach prompt (R2-3). A successfully-DECODED batch from a player with NO
     * state is proof of an orphaned LSS session: vanilla clients never speak the channel,
     * a live client cannot declare before its deferred-reply registration ran, and after a
     * plugin reload the fresh service has an empty player map while every connected LSS
     * client keeps declaring its want-set at 1 Hz forever — silently dropped here until
     * the player manually rejoined.
     *
     * <p>The prompt is the V16-DIALECT 6-field SessionConfig, deliberately: a
     * current-protocol client's downgrade guard treats an unexpected v16 config on an
     * established session as a raced discovery and RE-ANNOUNCES its own version — which
     * re-registers it through the normal deferred path. That is the heal, and it ships
     * with zero client-side changes. It covers v18-compat sessions too: the v0.7.x–v0.8.x
     * client carries the same downgrade guard, its re-announce (protocol 18) lands on the
     * v18 rung, and the in-order echo-18 config disarms its sourceless-decode flag before
     * any post-re-registration column (v18-compat design §4). A genuine v16 client (whose
     * post-reload batches also land here — the fresh compat manager has no session for
     * it) parses the 6-field shape harmlessly and stays broken-until-rejoin, exactly
     * today's behavior; the current 4-field shape would buffer-underflow its decoder and
     * hard-kick it.
     *
     * <p>Rate-limited per UUID (60 s), and removePlayer STAMPS the same map, so the Folia
     * dimension-change remove→register window sits inside a post-removal grace and cannot
     * fire a spurious prompt at a healthy mid-stream client (the client-side backstop:
     * V16ClientWire's announce gate keeps even a delivered stray prompt from flipping
     * column decode). Sent directly from the message-handler thread (netty is any-thread
     * safe — the v16 overflow valve precedent above).
     *
     * <p><b>The heal is DECLARATION-triggered</b> (live-smoke finding, 2026-07-29): a
     * client whose want-set had CONVERGED before the /reload sends nothing — silence at
     * convergence is the v17 protocol — so it stays orphaned, invisibly, until movement
     * mints new rings (its next declaration prompts and heals within a scan) or it
     * rejoins. Until then it also has no dirty-broadcast subscription (the fresh service
     * has no state for it), so post-reload edits don't reach it. Accepted residual: the
     * un-orphaned failure mode (pre-fix: orphaned FOREVER even while declaring) is fixed;
     * the converged-and-stationary corner heals on the first movement, and a converged
     * client's LOD is complete by definition — only edit staleness is at risk.
     */
    private void maybeSendReattachPrompt(ServerPlayer player) {
        var uuid = player.getUUID();
        long now = System.nanoTime() / 1_000_000L;
        boolean[] fire = {false};
        this.reattachPromptAt.compute(uuid, (k, prev) -> {
            if (prev != null && now - prev < REATTACH_PROMPT_INTERVAL_MS) return prev;
            fire[0] = true;
            return now;
        });
        if (!fire[0]) return;
        LSSLogger.info("Re-attach prompt for " + player.getName().getString()
                + " — declared a want-set with no registered session (plugin reloaded?)");
        this.reattachPromptSender.send(player);
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
        if (state == null) {
            // STRICTLY state == null: a registered-but-mid-handshake state (the guard
            // below) is a healthy client whose own deferred reply is in flight.
            maybeSendReattachPrompt(player);
            return;
        }
        if (!state.hasCompletedHandshake()) return;

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
        // Runtime /lsslod set tasks (v0.11.0 stage C): drained AFTER the lifecycle
        // mailbox, deliberately — the SessionConfig re-push enumerates dialects, and a
        // registered-but-flip-pending player must have its dialect flip APPLIED before
        // enumeration (the SET review's ordering MAJOR: an off-pump enumeration could
        // read the untracked-defaults-to-CURRENT dialect and push a protocol-20 config
        // at a legacy client, killing its session until rejoin).
        drainRuntimeTasks();
        if (!this.config.enabled)
            return;

        this.diag.reset(this.offThreadProcessor.getDiagnostics());

        applyRuntimeConfig();
        // Service gate (plan §2.3): tick-cadenced permission rechecks, ordered AFTER
        // drainLifecycleMailbox above — a registered-but-flip-pending player must have
        // its dialect applied before the CURRENT-only revocation enumerates (the same
        // ordering MAJOR the set re-push closed).
        if (++this.permissionRecheckCounter >= PERMISSION_RECHECK_TICKS) {
            this.permissionRecheckCounter = 0;
            runServiceGateSweeps();
        }
        var generationReady = tickGenerationService();
        // v16 declares BEFORE the lifecycle pass: the sync probe reads the mailbox during
        // processPlayerLifecycle, and on Folia holdAndScheduleRegionProbe reads ONLY the
        // mailbox — a declare offered after that pass would lose the race to the processing
        // thread's take and route with zero probe coverage (release-review finding 1).
        tickV16Compat();
        var lifecycle = processPlayerLifecycle(generationReady);

        if (lifecycle.toRemove != null) {
            for (UUID uuid : lifecycle.toRemove) {
                this.removePlayer(uuid);
                // The sweep IS a disconnect (entity removed, no PlayerList entry — the
                // quit event never fired for this player), so drop BOTH compat
                // identities like the quit hook would; without this the entries and the
                // diag clients= counts leak until a same-UUID rejoin (execution-review
                // finding 2 for v18; 2026-08-05 review H2 closed the v16 twin —
                // removePlayer's onServiceRemove deliberately keeps identity, so the
                // sweep was the one removal path that leaked it).
                this.dialects.onDisconnect(uuid);
                this.v16Compat.onDisconnect(uuid);
                // E1 review M1: the sweep must shed the far-player subscription like
                // the other two identities, or a swept viewer's roster state leaks and
                // keeps charging the broadcast loop until a same-UUID rejoin. The
                // onDisconnect flavor also drops the retained target prefs — the
                // player's connection is gone.
                this.farPlayerService.onDisconnect(uuid);
                // Region summaries: same connection-scoped cleanup (pending request,
                // queued job, re-sweep cooldown mark).
                if (this.regionSummaries != null) this.regionSummaries.removePlayer(uuid);
                // Service gate: the sweep IS a disconnect — without this a denied
                // joiner whose quit event never fired leaks its log latch and streak
                // for the service's life (implementation review, 2026-08-27).
                this.serviceGateState.onDisconnect(uuid);
                this.connectionEpochs.remove(uuid);
            }
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
        tickFarPlayers();
        tickRegionSummaries();
        tickDiagnosticsLog();
    }

    /** Region summaries (P2, plan §5) — the Fabric twin's pump: admit dimension-matched
     *  requests into sweep jobs and drain ready frames onto the dedicated send lane.
     *  Player state is only read HERE (the pump thread); ingress stored pure data. */
    private void tickRegionSummaries() {
        if (this.regionSummaries == null) return; // pre-region-stamps test wirings
        try {
            this.regionSummaries.pump(uuid -> {
                var state = this.players.get(uuid);
                if (state == null || !state.hasCompletedHandshake()) return null;
                String dim = state.registeredDimension();
                long pc = state.playerChunkPackedOrSentinel();
                if (dim == null || pc == Long.MIN_VALUE) return null;
                return new dev.vox.lss.common.region.RegionSummaryService.PlayerAnchor(
                        dim, PositionUtil.unpackX(pc), PositionUtil.unpackZ(pc));
            }, (uuid, frame) -> {
                var player = this.server.getPlayerList().getPlayer(uuid);
                if (player == null) { // disconnected while assembling — unsendable forever
                    return dev.vox.lss.common.region.RegionSummaryService.SendOutcome.DROP;
                }
                // CURRENT-dialect re-check at the sink (final panel — the stamps
                // lane's rule, mirrored): a pre-handshake request reads as CURRENT
                // (untracked UUID) and a session can re-handshake DOWN before the
                // frame drains; a legacy session must never receive a summary frame.
                if (this.dialects.dialectOf(uuid)
                        != dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT) {
                    return dev.vox.lss.common.region.RegionSummaryService.SendOutcome.DROP;
                }
                // The far-player lane's writability discipline (final review F2), with
                // RETENTION (live-diagnosed 2026-08-20 — see the Fabric twin): the
                // frame drains at the join/portal moment, exactly when the serve flood
                // makes the channel unwritable, and the client never re-requests — so
                // unwritable answers RETRY (service retains, TTL-bounded) instead of
                // eating the session's whole exchange.
                var snap = PaperChannelPressure.forPlayer(player).snapshot();
                if (snap.writable() == dev.vox.lss.common.processing.ChannelPressureProbe
                        .Writability.NOT_WRITABLE) {
                    return dev.vox.lss.common.region.RegionSummaryService.SendOutcome.RETRY;
                }
                return PaperPayloadHandler.sendRegionSummary(player, frame)
                        ? dev.vox.lss.common.region.RegionSummaryService.SendOutcome.SENT
                        : dev.vox.lss.common.region.RegionSummaryService.SendOutcome.DROP;
            });
        } catch (Exception e) {
            if (!this.regionSummaryTickErrorWarned) {
                this.regionSummaryTickErrorWarned = true;
                LSSLogger.error("Region-summary pump failed — contained (once per session)", e);
            }
        }
    }

    private boolean regionSummaryTickErrorWarned;

    /** Ingress for {@code lss:region_summary_req} (messenger/region thread — stores
     *  pure data, no entity access). The HANDLER-checked kill switch (plan §5). */
    public void handleRegionSummaryRequest(UUID player, byte[] body) throws Exception {
        if (this.regionSummaries == null) return;
        if (!this.config.enabled || !this.config.enableRegionSummaries) return;
        // A malformed frame throws out into dispatchPluginMessage's hostile-frame
        // containment (throttled) — the Fabric twin contains at its own receiver.
        var request = dev.vox.lss.common.region.RegionSummaryWire.decodeRequest(body);
        // CURRENT dialect only (plan §9.4 — the far-player subscription discipline):
        // a legacy-dialect session must not become stamps-eligible.
        if (this.dialects.dialectOf(player)
                != dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT) {
            return;
        }
        this.regionSummaries.offerRequest(player, request);
    }

    /** The region-summary service (diag + tests); null in pre-region-stamps wirings. */
    public dev.vox.lss.common.region.RegionSummaryService getRegionSummaries() {
        return this.regionSummaries;
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
                    player.chunkPosition().x, player.chunkPosition().z, maxDist);
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
            // Counted AFTER the removal check (R2-11) — see the Fabric twin.
            activeCount++;

            if (state.checkDimensionChange()) {
                // A dimension change abandons all in-flight work. Reuse the (well-tested)
                // disconnect teardown + a fresh registration instead of a second, hand-rolled
                // partial-reset protocol: the processing thread unwinds the old state's dedup
                // groups via the removal event, and the fresh state starts clean next tick.
                var changed = state.getPlayer();
                int capabilities = state.getCapabilities();
                removePlayer(changed.getUUID());
                registerPlayer(changed, capabilities);
                // Far players: identity SURVIVES the cycle (v18-rung checklist); the
                // roster does not — a bumped-epoch full roster follows.
                this.farPlayerService.onViewerDimensionChange(changed.getUUID());
                continue;
            }

            var player = state.getPlayer();
            // 1.21.1 line: level() returns plain Level here — cast rather than
            // serverLevel(), which is a distinct method the test twins' mocks (stubbing
            // level(), the cross-line contract) would answer with null.
            var level = (net.minecraft.server.level.ServerLevel) player.level();
            // Ring origin for the generation order-spread gate — must be the REAL player
            // chunk (the want-set's first entry sits at ~viewDistance on a ring perimeter,
            // which wedged the gate — see AbstractPlayerRequestState.updatePlayerChunk).
            state.updatePlayerChunk(player.chunkPosition().x, player.chunkPosition().z);
            String dimension = this.dimensionStringCache.computeIfAbsent(level.dimension(),
                    k -> k.location().toString());

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
        long perPlayerCap = Math.min(perPlayerAllocation, this.config.bytesPerSecondPerPlayer());

        // The ping backstop's observe pass (Mechanism B) — the Fabric twin's comment:
        // observed on the pump, applied to the flush allocation (m12), reset when the
        // kill switch is off so a live flip cannot leave a stale cut.
        if (this.config.enablePingBackstop) {
            long now = System.currentTimeMillis();
            for (var state : this.players.values()) {
                int ping = -1;
                try {
                    ping = state.getPlayer().connection.latency();
                } catch (Throwable ignored) {
                }
                state.getPingBackstop().observe(now, ping, state.getTotalBytesSent(),
                        perPlayerCap);
            }
        } else {
            for (var state : this.players.values()) {
                state.getPingBackstop().resetFactor();
            }
        }

        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake())
                continue;
            long[] dropped = state.flushSendQueue(
                    state.getPingBackstop().apply(perPlayerCap), this.bandwidthLimiter, this.diag,
                    data -> this.columnPayloadSender.send(state, data),
                    this.config.lodYieldsToVanillaTransport,
                    // Prune gated on the yield (review B-2) — the Fabric twin's comment.
                    this.config.lodYieldsToVanillaTransport
                            ? this.config.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER
                                    + OffThreadProcessor.SWEEP_RADIUS_MARGIN_CHUNKS
                            : 0,
                    this.config.enableSendPacing);
            if (dropped.length > 0) {
                // A send failure or the relevance prune discarded resolved-but-undelivered
                // columns: clear their done-bits so the client's re-requests re-resolve
                // instead of being answered up-to-date for data that never arrived.
                this.offThreadProcessor.clearDiskReadDone(state.getPlayerUUID(), dropped);
            }
        }
    }

    private void tickDiagnosticsLog() {
        if (++this.diagLogCounter >= DIAG_LOG_INTERVAL_TICKS) {
            this.diagLogCounter = 0;
            DiagnosticsFormatter.logDebugSummary(this.diag, this.getUptimeSeconds(),
                    this.config.bytesPerSecondGlobal(), this.bandwidthLimiter, this.players.values());
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
     * hold-release makes the same alignment deterministic for the ARRIVAL-tick arm, and
     * since the 2026-08-27 review (R1) the regionized path carries the published-want-set
     * arm too — before that it probed only on arrival ticks, collapsing coverage to the
     * client's declaration cadence. The PUBLISHED want-set covers the other ~19 ticks of each second
     * ({@code takeIncomingBatch()} nulls the mailbox within ~50 ms of arrival while batches
     * arrive at only 1-4 Hz — the client's adaptive cadence) and carries a want-set too large for the slot cap across the
     * cycles that work it off (published exactly while the backlog is non-empty).
     *
     * <p>Both sources may list already-routed positions; such a probe is simply unused by the
     * router, bounded by {@link #MAX_PROBES_PER_TICK_PER_PLAYER}. Positions whose payload
     * sits in the send pipeline, left it within the probe-suppress TTL, or were answered
     * up_to_date are filtered by {@code skipProbe} (review P1 — the Fabric twin's javadoc
     * carries the full story), leaving a sub-tick residual between a processing-thread
     * up_to_date resolution and the next pump action drain.
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
            // Served-head filter: a payload in the send pipeline, recently sent, or just
            // answered up_to_date means the router resolves this position without the
            // probe — the serialization is guaranteed-unused, and under backlog retention
            // the published want-set re-lists it every tick until the next declaration
            // (review P1: the enqueued-only filter left served/answered heads
            // re-serializing for up to a second). Both structures are any-thread safe
            // (pendingByPosition/diskReadDone are single-threaded and stay unconsulted).
            if (state.skipProbe(packed))
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
        if (fresh == null) {
            // The published-want-set arm (Folia review 2026-08-27 R1): before this arm
            // existed, Folia probed ONLY on a declaration's arrival tick — the probe
            // window advanced at the client's 1-4 Hz cadence instead of every tick, so
            // any want-set larger than the 512-position window (or the sync slot cap)
            // routed its later cycles with ZERO probe coverage: loaded chunks took
            // disk reads, and on gen-disabled servers the loaded-but-never-saved
            // NOT_GENERATED park became the steady state. This is the sync path's
            // peekWantSet arm, regionized: same per-player cap, same served-head/
            // suppress filters (probes are position-keyed, so results merge into the
            // same consume path regardless of which arm scheduled them). Runs only on
            // no-fresh-batch ticks, so the one-region-task-per-player-per-tick shape
            // holds; a release-success tick returns above and the arm picks up next
            // tick.
            var published = state.peekWantSet();
            if (published == null) return; // converged player, no probe cost
            long[] positions = snapshotProbePositions(state, published, skipPositions);
            if (positions.length == 0) return;
            UUID uuid = player.getUUID();
            try {
                this.regionTaskScheduler.schedule(player,
                        () -> runRegionProbe(uuid, level, positions));
            } catch (Exception e) {
                // R5 containment — see the sibling below.
            }
            return;
        }
        this.heldForProbe.put(player.getUUID(), new HeldBatch(fresh, heldAtGeneration));

        long[] positions = snapshotProbePositions(state, fresh, skipPositions);
        if (positions.length == 0) return;
        UUID uuid = player.getUUID();
        try {
            this.regionTaskScheduler.schedule(player,
                    () -> runRegionProbe(uuid, level, positions));
        } catch (Exception e) {
            // A plugin-manager disable from a region thread can land between tick()'s
            // shuttingDown check and this schedule: the EntityScheduler then throws
            // IllegalPluginAccessException (Folia review R5 — the identical containment
            // PaperChunkGenerationService's MainThreadScheduler documents). The probe is
            // simply lost — the held batch still releases next tick (or dies with the
            // service), and probe misses are the designed degrade.
        }
    }

    private static final long[] NO_POSITIONS = new long[0];

    /** Up to {@link #MAX_PROBES_PER_TICK_PER_PLAYER} distinct positions from the held batch.
     *  Served-head filter mirrors the sync probes (incl. the review-P1 suppress rung): a
     *  payload in the send pipeline, recently sent, or just answered up_to_date resolves
     *  without the probe, so probing it wastes a region-thread serialization (both
     *  structures are safe off the processing thread). */
    private long[] snapshotProbePositions(PaperPlayerRequestState state, IncomingBatch held,
                                          LongOpenHashSet skipPositions) {
        LongOpenHashSet positions = null;
        for (var req : held.requests()) {
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (skipPositions != null && skipPositions.contains(packed)) continue;
            if (state.skipProbe(packed)) continue;
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
        var batch = new RegionProbeBatch(level.dimension().location().toString(), found);
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
        }, new OffThreadProcessor.StampsSink<>() {
            // Stamped up_to_date (plan §3): the summary request is the eligibility
            // declaration; fire-and-forget, counted on a completed send only.
            @Override public boolean eligible(UUID uuid) {
                // CURRENT dialect conjunct (3-Opus fold — see the Fabric twin).
                var s = PaperRequestProcessingService.this.regionSummaries;
                return s != null
                        && PaperRequestProcessingService.this.dialects.dialectOf(uuid)
                                == dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT
                        && s.hasRequestedThisSession(uuid);
            }
            @Override public void send(PaperPlayerRequestState state, byte[] frame, int entries) {
                // Writability drop (3-Opus fold — see the Fabric twin): uncounted, no
                // retry; loss is the designed-tolerant case.
                var snap = PaperChannelPressure.forPlayer(state.getPlayer()).snapshot();
                if (snap.writable() == dev.vox.lss.common.processing.ChannelPressureProbe
                        .Writability.NOT_WRITABLE) {
                    return;
                }
                if (PaperPayloadHandler.sendColumnStamps(state.getPlayer(), frame)) {
                    PaperRequestProcessingService.this.regionSummaries.diagnostics()
                            .recordStampsFrame(entries, frame.length);
                }
            }
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
            // 1.21.1 line: level() returns plain Level here — cast rather than
            // serverLevel(), which is a distinct method the test twins' mocks (stubbing
            // level(), the cross-line contract) would answer with null.
            var level = (net.minecraft.server.level.ServerLevel) player.level();
            String dimension = this.dimensionStringCache.computeIfAbsent(level.dimension(),
                    k -> k.location().toString());
            // Ticket queued before a dimension change targets the old dimension's coordinates.
            // Dropping it leaks nothing: in the common shape the admitting state was discarded
            // by removePlayer+registerPlayer (its slot dies with it), AND that same removePlayer
            // enqueues the removal event that sweeps the processing thread's UUID-keyed
            // generation in-flight tracking (removeGenerationTracking) — without that sweep the
            // dropped ticket's tracking would leak (do not add a drop path that skips it).
            // Folia timing corner (review 2026-08-27 R17): a region-thread dimension flip
            // landing BETWEEN this tick's lifecycle pass and this drain reaches the mismatch
            // with the OLD state still admitting — its pending GENERATION entry then gets no
            // outcome this tick and the slot is held until the NEXT tick's dimension-change
            // cycle sweeps the whole state. Bounded to one tick, no leak.
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

    /** Far players (E1): one broadcast pass every farPlayersUpdateIntervalTicks while
     *  armed and subscribed — mode "off" short-circuits
     *  before any snapshot work. Pump thread (Folia: cross-region position/equipment
     *  reads are stale-tolerant by design — accepted for display-only data, the
     *  experimental label covers it). */
    private boolean farPlayerSnapshotWarned;

    private void tickFarPlayers() {
        if ("off".equals(this.config.farPlayers)
                || this.farPlayerService.subscriberCount() == 0) {
            return;
        }
        if (++this.farPlayerTickCounter < this.config.farPlayersUpdateIntervalTicks) return;
        this.farPlayerTickCounter = 0;
        try {
            var online = buildFarPlayerSnapshots(this.server.getPlayerList().getPlayers());
            this.farPlayerService.tick(System.currentTimeMillis(), online,
                    new dev.vox.lss.common.farplayers.FarPlayerBroadcastService.Settings(
                            this.config.farPlayers, this.config.farPlayersMaxDistanceBlocks,
                            this.config.farPlayersMinDistanceBlocks,
                            this.config.farPlayersSendSpectators,
                            this.config.farPlayersExclude,
                            this.config.farPlayersUpdateIntervalTicks),
                    this::sendFarPlayerFrame);
        } catch (Exception e) {
            // Containment (review): a snapshot/encode bug must degrade far players,
            // never the pump tick (which owns lifecycle + column serving).
            if (!this.farPlayerTickErrorWarned) {
                this.farPlayerTickErrorWarned = true;
                LSSLogger.error("Far-player broadcast pass failed — contained (once per session)", e);
            }
        }
    }

    private boolean farPlayerTickErrorWarned;

    /** One snapshot per online player, CONTAINED per player (Folia review 2026-08-27
     *  R2): one raced cross-region read (equipment, vehicle, a throwing permissible
     *  the hiddenFor belt did not cover) must not abort the pass for every other
     *  player — the pre-fix shape, where the only catch was around the whole pass.
     *  The skipped player reads as absent this interval; the roster reconciles next
     *  tick (stale-tolerant by the same doctrine as the reads themselves). The
     *  pass-level catch in tickFarPlayers stays as the final belt. Package-private
     *  for the R10 battery. */
    java.util.List<dev.vox.lss.common.farplayers.FarPlayerBroadcastService.PlayerSnapshot>
            buildFarPlayerSnapshots(java.util.List<ServerPlayer> players) {
        var online = new java.util.ArrayList<dev.vox.lss.common.farplayers
                .FarPlayerBroadcastService.PlayerSnapshot>(players.size());
        for (var p : players) {
            try {
                online.add(PaperFarPlayerSnapshots.snapshot(p));
            } catch (Exception e) {
                if (!this.farPlayerSnapshotWarned) {
                    this.farPlayerSnapshotWarned = true;
                    LSSLogger.warn("Far-player snapshot failed for "
                            + p.getName().getString()
                            + " — skipped this interval (once per session): " + e);
                }
            }
        }
        return online;
    }

    /** The dedicated far-player send lane — twin of the Fabric sender: writability
     *  consult (NOT_WRITABLE withholds) + bandwidth governor charge; NMS DiscardedPayload
     *  (no outgoing Bukkit registration needed — the FARP §3.1 doctrine). */
    private boolean sendFarPlayerFrame(UUID viewer, String channel, byte[] body) {
        var player = this.server.getPlayerList().getPlayer(viewer);
        if (player == null || player.connection == null) return false;
        var snap = PaperChannelPressure.forPlayer(player).snapshot();
        if (snap.writable() == dev.vox.lss.common.processing.ChannelPressureProbe
                .Writability.NOT_WRITABLE) {
            return false;
        }
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new net.minecraft.network.protocol.common.custom.DiscardedPayload(
                        FAR_PLAYER_CHANNEL_IDS.computeIfAbsent(channel,
                                net.minecraft.resources.ResourceLocation::parse),
                        // 1.21.1 line: ByteBuf-carrying DiscardedPayload
                        io.netty.buffer.Unpooled.wrappedBuffer(body))));
        this.bandwidthLimiter.recordSend(body.length);
        return true;
    }

    // Two entries ever (roster + updates) — parse once, not per frame (review NIT).
    private static final java.util.concurrent.ConcurrentHashMap<String,
            net.minecraft.resources.ResourceLocation> FAR_PLAYER_CHANNEL_IDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public dev.vox.lss.common.farplayers.FarPlayerBroadcastService getFarPlayerService() {
        return this.farPlayerService;
    }

    public WireDialectTracker getDialectTracker() {
        return this.dialects;
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
        this.serviceGateState.clear();
        try {
            // Own containment, FIRST (P2 review I-m2): no ordering dependency on the
            // dirty drain, and a throw there must not leak the sweeper daemon across
            // /reload cycles (each would hold the old stamp table + world resolver).
            if (this.regionSummaries != null) this.regionSummaries.shutdown();
        } catch (Exception e) {
            LSSLogger.error("Error shutting down region-summary sweeper", e);
        }
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
            // After the reader (no more store rung callers) and after the processor (its
            // sentinel take fanned the final invalidations into the store).
            if (this.lodStore != null) {
                this.lodStore.shutdown();
            }
        } catch (Exception e) {
            LSSLogger.error("Error shutting down LOD store", e);
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
