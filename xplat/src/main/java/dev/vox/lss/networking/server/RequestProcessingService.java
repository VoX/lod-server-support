package dev.vox.lss.networking.server;

import dev.vox.lss.common.processing.RequestRegistration;

import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
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
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
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
    // Service gate (service-permission-gate-plan.md §2.3): the denied-handshake memo,
    // the once-per-episode denial-log latch, and the revocation streaks — dying with
    // this service (the server-stop clear is shutdown()'s).
    private final dev.vox.lss.common.ServiceGateState serviceGateState =
            new dev.vox.lss.common.ServiceGateState();
    /** The recheck cadence (plan §2.3): both sweeps run every 200 ticks (~10 s). */
    static final int PERMISSION_RECHECK_TICKS = 200;
    private int permissionRecheckCounter;
    private boolean noProviderWarned;
    /** The sweep's permission read — seam-injected for tests (no live permission
     *  backend exists in any test JVM); production reads the LoaderServices seam with
     *  the doctrine default TRUE. A throwing probe is contained at the sweep site. */
    private java.util.function.BiPredicate<ServerPlayer, String> permissionProbe =
            (p, node) -> dev.vox.lss.platform.LoaderServices.get().checkPermission(p, node, true);

    /** Test seam. */
    public void setPermissionProbeForTest(
            java.util.function.BiPredicate<ServerPlayer, String> probe) {
        this.permissionProbe = probe;
    }

    /** The map-ingestion exemption read (map-ingestion permission plan §3.1) — the ONE
     *  named fail-closed exception to the service-gate doctrine above (default FALSE, a
     *  throwing backend answers not exempt). Seam-injected for tests; production reads
     *  {@link #mapIngestionExemptionViaLoader}. The handshake gate, the grant replay and
     *  the sweeps all read it through {@link #mapIngestionExemption} (three states: the
     *  10 s sweep keeps a session's last-sent flags on a FAILED read) or its fail-closed
     *  boolean form {@link #mapIngestionExempt}. */
    private java.util.function.Function<ServerPlayer, dev.vox.lss.common.MapPolicy.Exemption>
            mapIngestionExemptProbe = RequestProcessingService::mapIngestionExemptionViaLoader;

    /** The production exemption read: either brand spelling through the loader seam with
     *  default FALSE — no provider (a Fabric server without a permissions mod) means
     *  nobody is exempt; a throwing backend is {@code FAILED}. LoaderPermissionSeamContractTest
     *  allowlists exactly this {@code checkPermission} call as the fail-closed exception. */
    static dev.vox.lss.common.MapPolicy.Exemption mapIngestionExemptionViaLoader(ServerPlayer player) {
        return dev.vox.lss.common.MapPolicy.readExemption(
                node -> dev.vox.lss.platform.LoaderServices.get().checkPermission(player, node, false));
    }

    /** The production read's fail-closed boolean form (the glue's read without a service). */
    static boolean mapIngestionExemptViaLoader(ServerPlayer player) {
        return mapIngestionExemptionViaLoader(player).exempt();
    }

    /** Test seam; {@code null} restores the production read. A throwing probe reads FAILED. */
    public void setMapIngestionExemptProbeForTest(java.util.function.Predicate<ServerPlayer> probe) {
        this.mapIngestionExemptProbe = probe != null
                ? player -> dev.vox.lss.common.MapPolicy.Exemption.of(() -> probe.test(player))
                : RequestProcessingService::mapIngestionExemptionViaLoader;
    }

    /** The exemption in three states: a throwing or absent read is {@code FAILED}. */
    public dev.vox.lss.common.MapPolicy.Exemption mapIngestionExemption(ServerPlayer player) {
        try {
            var read = this.mapIngestionExemptProbe.apply(player);
            return read != null ? read : dev.vox.lss.common.MapPolicy.Exemption.FAILED;
        } catch (Exception e) {
            return dev.vox.lss.common.MapPolicy.Exemption.FAILED;
        }
    }

    /** Whether {@code player} holds the map-ingestion exemption — fail-closed: a throwing
     *  (FAILED) read is not exempt. */
    public boolean mapIngestionExempt(ServerPlayer player) {
        try {
            return mapIngestionExemption(player).exempt();
        } catch (Exception e) {
            return false;
        }
    }

    /** The per-player map-ingestion decision (map-ingestion permission plan §3.2): the
     *  request is on and this player is not exempt. Every flag-carrying send and policy
     *  decision on this service goes through it (MapPolicyWiringContractTest's census). */
    public boolean denyMapIngestionFor(ServerPlayer player, LSSServerConfig config) {
        return dev.vox.lss.common.MapPolicy.decide(config.requestNoMapIngestion(),
                () -> mapIngestionExempt(player)).deny();
    }

    /** The same decision from an exemption the caller already read (the sweep's
     *  three-state read; a FAILED read is not exempt here). */
    public boolean denyMapIngestionFor(LSSServerConfig config,
                                       dev.vox.lss.common.MapPolicy.Exemption exemption) {
        return dev.vox.lss.common.MapPolicy.decide(config.requestNoMapIngestion(),
                exemption::exempt).deny();
    }

    /** The map policy's chat-line send (map-policy plan A3.3): a system message on the
     *  server thread. Seam-injected for tests (gametest mock players have no client). */
    private java.util.function.BiConsumer<ServerPlayer, String> mapPolicyNoticeSender =
            (player, text) -> player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(text));

    /** Test seam; {@code null} restores the production send. */
    public void setMapPolicyNoticeSenderForTest(
            java.util.function.BiConsumer<ServerPlayer, String> sender) {
        this.mapPolicyNoticeSender = sender != null ? sender
                : (player, text) -> player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal(text));
    }

    /** Test seam: the policy the sessions were last told, as the reconcile would adopt it
     *  (the gametests flip the config without a reload; the 200-tick cadence sweep runs
     *  only once the arm is adopted — review fold A9). */
    public void adoptMapPolicyForTest(boolean armed) {
        this.adoptedMapPolicy = armed;
    }

    /** The permission line's send (0.22.0 plan §1): a system message, translatable with the
     *  English fallback, so every client version shows it. Seam-injected for tests. */
    private java.util.function.Consumer<ServerPlayer> permissionNoticeSender =
            RequestProcessingService::sendPermissionNotice;

    private static void sendPermissionNotice(ServerPlayer player) {
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatableWithFallback(
                dev.vox.lss.common.TerrainRefusalNotice.KEY,
                dev.vox.lss.common.TerrainRefusalNotice.FALLBACK));
    }

    /** Test seam; {@code null} restores the production send. */
    public void setPermissionNoticeSenderForTest(java.util.function.Consumer<ServerPlayer> sender) {
        this.permissionNoticeSender = sender != null ? sender
                : RequestProcessingService::sendPermissionNotice;
    }

    /**
     * Tells a player refused for lack of permission why no distant terrain arrives: once per
     * server start per player, and again on a revocation (a served player who stops being
     * served). The grant sweep's replays re-enter here and find the claim taken. A send
     * failure is contained: the line is a courtesy, the refusal already happened. Server thread.
     */
    public void noticePermissionRefusal(ServerPlayer player, boolean revocation) {
        if (!this.serviceGateState.claimPermissionNotice(player.getUUID(), revocation)) return;
        try {
            this.permissionNoticeSender.accept(player);
        } catch (Exception e) {
            LSSLogger.warn("Permission notice to " + player.getName().getString() + " failed: " + e);
        }
    }

    /**
     * Sends the map policy's once-per-CONNECTION chat line to a refused player (map-policy
     * plan A3.3) — from the handshake refusal, the arm sweep, and a grant-sweep replay
     * whose refusal is a MAP_POLICY transition (review fold A6). Its own latch on the gate
     * state (swept at disconnect). A send failure is contained: the line is a courtesy,
     * the refusal already happened. Server thread.
     */
    public void noticeMapPolicyRefusal(ServerPlayer player) {
        if (!this.serviceGateState.claimMapPolicyNotice(player.getUUID(),
                mapPolicyConnectionToken(player))) return;
        try {
            this.mapPolicyNoticeSender.accept(player, dev.vox.lss.common.MapPolicy.refusalNotice());
        } catch (Exception e) {
            LSSLogger.warn("Map-policy notice to " + player.getName().getString()
                    + " failed: " + e);
        }
    }
    /** The chat-line latch's per-CONNECTION token (review fold A7; 0.19.0 review O1):
     *  the packet listener, not the {@code ServerPlayer}. Vanilla's respawn (death, End
     *  exit) builds a NEW {@code ServerPlayer} and moves the SAME listener onto it, so a
     *  player-instance token re-sent the line on the same connection (and kept the dead
     *  instance in the latch until disconnect); the sweep can also still hold the
     *  pre-respawn instance until the lifecycle pass swaps it. A rejoin gets a new
     *  listener, so it still claims afresh. The player instance is only the fallback
     *  for a listener-less player (none in production). Paper keeps its player token:
     *  CraftBukkit reuses the entity on respawn. */
    static Object mapPolicyConnectionToken(ServerPlayer player) {
        var listener = player.connection;
        return listener != null ? listener : player;
    }
    private final MinecraftServer server;
    private final ChunkDiskReader diskReader;
    private final ChunkGenerationService generationService;
    private final SharedBandwidthLimiter bandwidthLimiter;
    private final FabricOffThreadProcessor offThreadProcessor;
    // Null while lodStore=off or when the codec native cannot load (degrade, never crash).
    private final dev.vox.lss.common.store.LodStoreService lodStore;
    // Null unless lodStore=full with a live SQLite store (the backfill's only target).
    private final dev.vox.lss.common.store.StoreBackfill storeBackfill;

    private final DirtyColumnTracker dirtyTracker;
    // Region freshness stamps (region-summary-sync-plan.md): the P1 header rung's oracle,
    // fed by the hoisted region-dir resolver + the save hook's dirty marks. Store-independent.
    private final dev.vox.lss.common.region.RegionStampTable regionStamps;
    // Region summaries (P2): sweeper + mailboxes; requests offered by the payload
    // handler, admissions/sends pumped from tick(). Cleanup rides the NETWORK
    // disconnect (removePlayer on the summary service — the far-player precedent),
    // deliberately NOT this service's removePlayer, which also fires on dimension
    // change and would race the client's at-entry request; TTL + vanished-send
    // checks are the belt for a missed disconnect.
    private final dev.vox.lss.common.region.RegionSummaryService regionSummaries;
    private final DirtyContentFilter dirtyContentFilter = new DirtyContentFilter();
    // Far players (E1, FARP §3.2): subscription identity lives HERE (the dialect-tracker
    // precedent) — subscribed at handshake, dropped only at the network DISCONNECT, and
    // the dimension-change re-registration path notifies instead of removing. Built in the
    // CONSTRUCTOR (not a field initializer — that runs before `server` is assigned, and a
    // null captured there would be swallowed by tickFarPlayers' once-warn containment: far
    // players silently dead behind one log line). The vanish bridge is the reflective
    // Melius Vanish ladder (far-player-render-hardening-plan.md WI-7b); see the ctor.
    private final dev.vox.lss.common.farplayers.FarPlayerBroadcastService farPlayerService;
    /** Per-tick vanish memo for the far-player visibility predicate (review fold B3);
     *  the broadcast runs on the server tick thread. */
    private final java.util.Map<java.util.UUID, Boolean> vanishMemo = new java.util.HashMap<>();
    private int vanishMemoTick = -1;
    private int farPlayerTickCounter;
    // Compressed-column shipping is live: useCompressedColumns AND the server-side zstd
    // native probe succeeded (latched once in the ctor — plan §0.11). A term of every
    // session's wantsCompressedColumns derivation at registration.
    private final boolean wireCompressionLive;

    private final long startTimeNanos = System.nanoTime();

    private final XrayMaskManager xrayMasks;
    private final DirtyColumnBroadcaster dirtyBroadcaster;
    // The v16 compat shim's per-player sessions (legacy protocol-16 clients). The pipeline
    // never consults it: a v16 player is an ordinary registered player whose want-set is
    // declared by the shim at 1 Hz. See docs/planning/v16-compat-design.md.
    private final V16CompatManager v16Compat = new V16CompatManager();
    // Every session's wire dialect (cross-version-identity-encoding-plan §4.3): the
    // single source of truth for egress shape decisions — replaces the old v18 bare set
    // AND the v16 egress checks (the manager keeps its session objects for the ingress
    // shim and consults this tracker for membership; its 75 s session prune is also the
    // v16 tracker-removal hook — the single-lifetime rule).
    private final WireDialectTracker dialects = new WireDialectTracker();
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

    private final LSSServerConfig serviceConfig;

    /** Stable configuration facade belonging to this service lifecycle. */
    public LSSServerConfig settingsConfig() { return serviceConfig; }

    public RequestProcessingService(MinecraftServer server) {
        this(server, LSSServerConfig.CONFIG);
    }

    public RequestProcessingService(MinecraftServer server, LSSServerConfig config) {
        this.server = server;
        this.serviceConfig = java.util.Objects.requireNonNull(config);
        this.advertisedGeneration = config.enableChunkGeneration();
        this.refreshedGeneration = this.advertisedGeneration;
        this.advertisedLod = config.snapshot().lod();
        this.adoptedServicePolicy = config.snapshot().service();
        this.adoptedMapPolicy = config.snapshot().privacy().requestNoMapIngestion();
        this.adoptedFarPlayerPolicy = config.snapshot().farPlayers();
        // The vanish source (WI-7b; the loader seam since sponge-support-plan.md §1.5 —
        // the Melius Vanish bridge by default, Sponge's native vanish state on Sponge), per
        // (viewer, target): resolved through the constructor's own `server` (non-null by
        // construction). A departed viewer answers hidden — nothing is sendable to it
        // anyway; absent source = visible.
        var vanish = dev.vox.lss.platform.LoaderServices.get().vanishVisibility();
        this.farPlayerService = new dev.vox.lss.common.farplayers.FarPlayerBroadcastService(
                (viewer, target) -> {
                    if (!vanish.present()) return true;
                    // Review fold B3: one isVanished per TARGET per tick (the plan's memo),
                    // not per viewer×target pair; the per-observer query only for the vanished.
                    int tick = server.getTickCount();
                    if (vanishMemoTick != tick) {
                        vanishMemo.clear();
                        vanishMemoTick = tick;
                    }
                    if (!vanishMemo.computeIfAbsent(target,
                            t -> vanish.isVanished(server, t))) {
                        return true;
                    }
                    var observer = server.getPlayerList().getPlayer(viewer);
                    return observer != null
                            && vanish.canSee(server, observer, target);
                }, LSSLogger::warn, this::sendFarPlayerNotice);

        // Publishes the per-world x-ray mask decisions the (static) serializer choke
        // points consult — before any serve can run. The reference is kept so shutdown's
        // retract is guarded (a stale shutdown must not null a successor's masking).
        this.xrayMasks = XrayMaskManager.activate(config);

        this.dirtyTracker = new DirtyColumnTracker();

        // Pool size honours diskReaderThreads=0 (AUTO). The tier question is whether the
        // resolved read path carries REAL priority: Moonrise's Priority.LOW defers to gameplay
        // regardless of how many reads we have outstanding, whereas on vanilla's
        // single-threaded IOWorker our concurrency IS the vanilla-delay tradeoff. Probing the
        // bridge here is the same per-JVM resolution the read path itself uses, so the two
        // cannot disagree at startup. (If it latches incompatible LATER the pool is already
        // sized — acceptable: every latched fallback engages the adaptive throttle, which
        // narrows hasHeadroom() and makes the pool size non-binding.)
        // useBackgroundReadPriority=false short-circuits chooseReadPath to foregroundRead,
        // so the Moonrise rung never runs and the pool must be sized by the UNprioritized
        // tier. Sizing it off Moonrise presence alone gave an admin who disabled background
        // priority — precisely because LSS reads were hurting vanilla chunk loading — up to
        // 8 FOREGROUND readers where the historic default was 5. (v0.9.0 review.)
        boolean prioritizedReads = config.useBackgroundReadPriority()
                && dev.vox.lss.compat.MoonriseReadCompat.resolveOrNull() != null;
        int readerThreads = config.effectiveDiskReaderThreads(prioritizedReads);
        this.diskReader = new ChunkDiskReader(readerThreads,
                config.useBackgroundReadPriority(), config.useNbtTranscode(),
                config.useBackgroundReadSplit(), config.useSelectiveNbtParse());
        this.generationService = new ChunkGenerationService(config);
        this.generationService.setDirtyContentFilter(this.dirtyContentFilter);
        this.bandwidthLimiter = new SharedBandwidthLimiter(config.bytesPerSecondGlobal());

        var dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
        this.offThreadProcessor = new FabricOffThreadProcessor(
                this.players,
                this.diskReader, config.enableChunkGeneration(), dataDir,
                config.effectiveTimestampCacheMB(), config.missMemoTtlSeconds(),
                config.maxConfiguredLodDistanceChunks() + LSSConstants.LOD_DISTANCE_BUFFER
                        + OffThreadProcessor.SWEEP_RADIUS_MARGIN_CHUNKS);
        // C2: the per-recipient enqueue consults the session dialect to translate
        // legacy (v19/v18/v16) column bodies to the native layout at build time.
        this.offThreadProcessor.attachDialectTracker(this.dialects);
        // Right after the constructor's cache load — see the field's javadoc.
        this.timestampCacheBootedEmpty = this.offThreadProcessor.isTimestampCacheEmpty();

        // Compressed-column shipping (protocol 19, plan §0.11): the server-side native
        // probe latches ONCE here. Probe failure (musl servers — zstd-jni publishes no
        // musl natives) degrades to raw sessions with one warning; without this term a
        // capable client against a natives-less default-on server would throw at every
        // payload build, forever. Independent of the store's own probe below.
        boolean wireCompressionLive = false;
        if (config.useCompressedColumns()) {
            var wireCodec = dev.vox.lss.common.store.StoreCodec.zstdOrNull();
            if (wireCodec == null) {
                LSSLogger.warn("useCompressedColumns is enabled but the "
                        + dev.vox.lss.common.store.StoreCodec.NAME + " native cannot load"
                        + " on this platform — LOD columns will ship uncompressed for"
                        + " every session");
            } else {
                this.offThreadProcessor.attachWireCodec(wireCodec);
                // Frame-form store serving (plan §3): with compression live, the store
                // rung ships stored frames verbatim instead of decompress-then-recompress.
                this.diskReader.setServeStoreFrames(true);
                wireCompressionLive = true;
            }
        }
        this.wireCompressionLive = wireCompressionLive;

        // LOD store (docs/planning/lod-store-implementation-plan.md): the SQLite engine
        // for "on"/"full" (the memory tier is deleted). Attached to BOTH consumers before any
        // submit/tick: the reader owns the hit rung, the processor owns deposits +
        // invalidation fan-out. Environment resolved eagerly on the main thread (levels
        // are loaded at SERVER_STARTED): per-dimension region dirs via the same API the
        // game uses (getStorageFolder — never hand-derived layouts), and per-dimension
        // mask fingerprints (deposited bytes are post-mask; a mask change drops the
        // dimension's rows at the sweep). A failed codec/native probe degrades to
        // store-off with one warning — never a crash.
        // `enabled: false` must mean the LOD store is not opened and the backfill does
        // not run. tick() checks the flag, but the store is built HERE, in the
        // constructor, and SERVER_STARTED constructs the service unconditionally — so
        // before this guard a server with LSS switched off still created
        // <world>/lss-lod/, walked every region file in every dimension at
        // lodStoreBackfillColumnsPerSecond, and wrote a DB roughly the size of the
        // region files, for a feature the admin had turned off and with nothing to
        // read any of it. Unreachable until v0.9.0 defaulted lodStore=full +
        // lodStoreBackfill=true; the enabled-false soak scenario cannot catch it,
        // because its config pins lodStore=off. (v0.9.0 review.)
        var storeMode = config.enabled()
                ? dev.vox.lss.common.store.LodStoreMode.normalize(config.lodStore())
                : dev.vox.lss.common.store.LodStoreMode.OFF;
        if (!config.enabled() && !dev.vox.lss.common.store.LodStoreMode
                .normalize(config.lodStore()).equals(dev.vox.lss.common.store.LodStoreMode.OFF)) {
            LSSLogger.info(dev.vox.lss.common.Brand.shortName() + " is disabled (enabled=false) — the LOD store and its "
                    + "backfill stay off; no store is created and no regions are walked");
        }
        if (storeMode == dev.vox.lss.common.store.LodStoreMode.OFF) {
            // Never on Folia — this is the Fabric service. Null when enabled=false.
            var advice = dev.vox.lss.common.store.LodStores
                    .offRecommendationOrNull(config.enabled(), false);
            if (advice != null) {
                LSSLogger.info(advice);
            }
        }
        // Region-dir resolver, HOISTED out of the store branch (region-summary-sync-plan.md
        // §5 integration M2): the P1 header freshness rung (and P2's summary table) must
        // work on store-LESS servers — the compiled store default is off, so building this
        // only store-armed would silently no-op the feature on most servers. Same API the
        // game uses (getStorageFolder — never hand-derived layouts); shared with the
        // store env and the backfill below.
        var worldRoot = server.getWorldPath(LevelResource.ROOT).normalize();
        var regionDirs = new HashMap<String, java.nio.file.Path>();
        for (ServerLevel level : server.getAllLevels()) {
            // Per-level belt: this loop now runs on STORE-LESS servers too, where the
            // storage-folder API was never touched before — an exotic dimension key
            // must degrade that one dimension to UNKNOWN (the table's designed
            // fail-safe), never take down service start.
            try {
                regionDirs.put(level.dimension().identifier().toString(),
                        net.minecraft.world.level.dimension.DimensionType
                                .getStorageFolder(level.dimension(), worldRoot)
                                .resolve("region").normalize());
            } catch (Throwable t) {
                LSSLogger.warn("Could not resolve the region directory for "
                        + level.dimension().identifier() + " — region freshness there"
                        + " falls through to full reads", t);
            }
        }
        this.regionStamps = new dev.vox.lss.common.region.RegionStampTable(regionDirs::get);
        this.diskReader.attachRegionStamps(this.regionStamps);
        // Region summaries (P2, plan §5): the sweeper daemon + per-player mailboxes over
        // the same stamp table. The tick pumps admissions/sends; ingress is the payload
        // handler (kill switch checked there).
        this.regionSummaries = new dev.vox.lss.common.region.RegionSummaryService(
                this.regionStamps::tileStampSeconds,
                () -> serviceConfig.maxConfiguredLodDistanceChunks());
        // Every hash-confirmed change mark (the save hook) bumps the region's live save
        // mark, closing the save-submitted-but-write-pending mtime lag before the header
        // rung can claim freshness across it. May run off-main — the bump is atomic.
        this.dirtyTracker.setMarkListener((dim, cx, cz) -> this.regionStamps
                .bumpLiveSaveMark(dim, cx, cz, LSSConstants.epochSeconds()));
        // Stamped up_to_date (stamped-up-to-date-plan.md §9.2): the compare-backed
        // rungs stamp "verified now" UNLESS the position's change is marked-but-
        // undrained or the region latch is armed — a stamp issued inside the
        // save-to-drain window would launder invalidation latency into a permanent
        // cross-session seal (the drain interval, up to 300 s, is not pinned inside
        // the 15 s freshness margin).
        this.offThreadProcessor.setUpToDateStampSource((player, dim, packed) -> {
            // Eligibility FIRST (3-Opus fold): on a server with no summary-requesting
            // session the predicate must not put the dirty tracker's monitor (shared
            // with the save hook) on the router's hot path for discarded work.
            if (!this.regionSummaries.hasRequestedThisSession(player)) return -1L;
            if (this.dirtyTracker.isPending(dim, packed)) return -1L;
            if (this.regionStamps.isClaimSuppressed(dim,
                    PositionUtil.unpackX(packed), PositionUtil.unpackZ(packed))) {
                return -1L;
            }
            return LSSConstants.epochSeconds();
        });

        if (storeMode != dev.vox.lss.common.store.LodStoreMode.OFF) {
            var maskFingerprints = new HashMap<String, String>();
            for (ServerLevel level : server.getAllLevels()) {
                String dim = level.dimension().identifier().toString();
                var maskEntry = XrayMaskManager.entryForActive(level);
                String maskFp = maskEntry == null ? "off"
                        : maskEntry.sourceLabel() + ":"
                                + Long.toHexString(maskEntry.mask().fingerprint());
                // Review B11: a NON-TERMINAL AntiXray probe (controller not yet
                // registered at SERVER_STARTED) serves an uncached config fallback
                // that later serves may replace with the engine mask — snapshotting
                // it could KEEP engine-masked rows under a config label across two
                // transient boots. A per-boot nonce never matches across boots, so
                // the affected dimension drops-and-re-warms instead (the safe
                // direction; churn only while the engine keeps resolving late).
                if (!XrayMaskManager.isTerminalForActive(level)) {
                    maskFp = "transient:" + Long.toHexString(System.nanoTime());
                }
                maskFingerprints.put(dim, maskFp);
            }
            // ONE registry walk feeds both fingerprints (plan §3.2; ~84k
            // BlockState.toString() calls on big packs — never doubled). The
            // contract test pins the of()/contentOf() delegation AT this call so
            // a swap or a dropped argument cannot compile green.
            var registryIds = storeRegistryIdentity(server);
            var env = new dev.vox.lss.common.store.SqliteLodStore.Environment(
                    dev.vox.lss.common.store.LodStores.brandedStoreDir(worldRoot), server.getServerVersion(),
                    LSSConstants.PROTOCOL_VERSION, regionDirs::get, maskFingerprints::get,
                    config.lodStoreResweepSeconds(), config.lodStoreMaxBytes(),
                    dev.vox.lss.common.store.RegistryFingerprint.of(
                            registryIds.states(), registryIds.biomes()),
                    dev.vox.lss.common.store.RegistryFingerprint.contentOf(
                            registryIds.states(), registryIds.biomes()),
                    // Build marker (paper-version-guard-store-plan §3.2): the jar's build
                    // version, and this loader's one-shot rule for an unmarked store; the
                    // jar's own Minecraft rule keeps a same-family marker (26.3.1 → 26.3).
                    dev.vox.lss.common.BuildInfo.minecraftVersion(),
                    dev.vox.lss.platform.LoaderServices.get().storePlatform(),
                    dev.vox.lss.platform.LoaderServices.get().legacyStoreMigration(),
                    dev.vox.lss.common.BuildInfo.minecraftVersionRule());
            this.lodStore = dev.vox.lss.common.store.LodStores.createOrNull(env);
            if (this.lodStore == null) {
                // LodStores.createOrNull logged the per-cause warn (codec vs SQLite init —
                // final-review A-M1: one shared message here misattributed SQLite failures).
            } else {
                this.diskReader.attachStore(this.lodStore);
                // C4: pre-migration wirefmt=19 store rows translate to the canonical
                // v20 form at the serve rung, against this server's own registries.
                this.diskReader.setStoreLegacyTranslator(nativeRaw ->
                        NbtSectionSerializer.toV20(nativeRaw, this.server.registryAccess()));
                this.lodStore.setLegacyMigrationTranslator(nativeRaw ->
                        NbtSectionSerializer.toV20(nativeRaw, this.server.registryAccess()));
                this.offThreadProcessor.attachStore(this.lodStore);
            }
            // Opt-in background backfill (Phase 4): built only over the SQLite store
            // (a memory store's backfill would evaporate at restart). Config-started;
            // /lsslod store backfill start|stop controls it at runtime either way.
            if (this.lodStore instanceof dev.vox.lss.common.store.SqliteLodStore sqlite) {
                var levelByDim = new HashMap<String, ServerLevel>();
                for (ServerLevel level : server.getAllLevels()) {
                    levelByDim.put(level.dimension().identifier().toString(), level);
                }
                this.storeBackfill = new dev.vox.lss.common.store.StoreBackfill(
                        sqlite, regionDirs::get,
                        // Traversal anchor: the real shared spawn via the 26.2
                        // respawn-data accessor (review B7 — the cap-stop made
                        // "nearest-spawn first" load-bearing: a far-spawn world with
                        // an opt-in cap warmed the wrong terrain, then stopped).
                        // Origin fallback on any shape drift: the anchor only ORDERS
                        // the walk.
                        dim -> {
                            try {
                                var level = levelByDim.get(dim);
                                var pos = level == null ? null
                                        : level.getRespawnData().pos();
                                return pos == null ? new long[]{0, 0}
                                        : new long[]{pos.getX() >> 4, pos.getZ() >> 4};
                            } catch (Throwable t) {
                                return new long[]{0, 0};
                            }
                        },
                        List.copyOf(levelByDim.keySet()),
                        (dim, cx, cz) -> {
                            var level = levelByDim.get(dim);
                            return level == null ? null
                                    : this.diskReader.readColumnBytesSyncForBackfill(level, cx, cz);
                        },
                        this.diskReader::hasHeadroom,
                        // Tick-health ceiling: pause the backfill while the smoothed
                        // tick time is over the configured MSPT gate.
                        () -> server.getCurrentSmoothedTickTime()
                                < LSSConstants.LOD_STORE_BACKFILL_TICK_CEILING_MS,
                        config.lodStoreBackfillColumnsPerSecond());
                this.storeBackfill.updatePolicy(config.lodStoreBackfill(), config.lodStoreBackfillColumnsPerSecond(), 0);
                // 0 = no LOD service in a dimension: never warm the store for it.
                this.storeBackfill.setDimensionFilter(dim -> dev.vox.lss.common.config.ServerConfigBase
                        .servesWorld(serviceConfig.lodDistanceForWorld(dim)));
                if (config.lodStoreBackfill()) {
                    this.storeBackfill.startWhenReady();
                }
            } else {
                this.storeBackfill = null;
            }
        } else {
            this.lodStore = null;
            this.storeBackfill = null;
        }

        // Disk-read concurrency gate K (disk-read-concurrency-gate-plan.md): resolved
        // against the POST-DEGRADE store state — `this.lodStore != null`, never the
        // config string, or half-pool K would re-arm on exactly the store-less servers
        // the store-conditional AUTO carves out (failed codec probe, enabled=false).
        int gateCapacity = config.effectiveMaxConcurrentDiskReads(readerThreads,
                this.lodStore != null);
        this.diskReader.configureReadGate(gateCapacity);
        // Script-consumed contract: the measurement harnesses assert their staged knobs
        // against this line (ServerConfigBase.effectiveConfigEcho). Deliberately AFTER
        // the zstd probe (the compression value echoed is the LIVE state, not the
        // request — B0 review M1) and AFTER store attachment (the echoed K is the
        // store-conditional resolution, which does not exist until the store's own
        // degrade ladder has run — v1.3 review MAJOR).
        LSSLogger.info(config.effectiveConfigEcho(readerThreads, wireCompressionLive,
                gateCapacity));

        this.offThreadProcessor.start();

        this.dirtyBroadcaster = new DirtyColumnBroadcaster(
                server, this.players, this.offThreadProcessor, this.dirtyTracker);
    }

    public PlayerRequestState registerPlayer(ServerPlayer player, int capabilities) {
        var config = serviceConfig;
        // One-way latch for the save hook's skip gate (review P3): until the first LSS
        // client handshakes, no session state (timestamp cache, client-held columns)
        // exists for the dirty-content hash to protect.
        boolean gateOpens = !this.everRegisteredPlayer;
        this.everRegisteredPlayer = true;
        if (gateOpens) {
            // The gate just opened: seed the chunks loaded while it was shut (WI-1b, the
            // storm floor) — main thread (both loaders run the handshake receiver there).
            ServerReceiverGlue.flushPendingLoadSeeds(this.server, this);
        }
        var state = this.players.computeIfAbsent(player.getUUID(), uuid -> {
            var s = new PlayerRequestState(player, LSSConstants.SYNC_ON_LOAD_SLOT_CAP,
                    config.generationLimits().perPlayer());
            // Session identity for the router's stale-snapshot guard (set before the map
            // publish so the processing thread never sees it null on a live state).
            s.setRegisteredDimension(player.level().dimension().identifier().toString());
            // Transport-pressure gauge (elytra-wall §8.3). The probe re-reads the player's
            // channel on every call, so a reconnect on the SAME ServerPlayer is picked up;
            // a player-object swap that keeps this state degrades to isActive()==false =>
            // no signal, never a wrong number.
            s.setChannelPressureProbe(FabricChannelPressure.forPlayer(player));
            return s;
        });
        this.diskReader.registerPlayer(player.getUUID(), state.registration());
        state.setCapabilities(capabilities);
        // The five-term AND (plan §2 + v18-compat §2.5): capability bit x config+native
        // latch x NOT-v16 x NOT-v18. Both dialect marks land BEFORE registerPlayer on the
        // handshake path (and a dimension-change re-registration re-derives through the
        // surviving membership), so the dialect terms are reliable here — a legacy
        // handshake maliciously setting 0x2 never gets a codec byte its wire layout has
        // nowhere to carry.
        state.setWantsCompressedColumns(this.wireCompressionLive
                && (capabilities & LSSConstants.CAPABILITY_ZSTD_COLUMNS) != 0
                && !this.dialects.isV16(player.getUUID())
                && !this.dialects.isV18(player.getUUID()));
        state.markHandshakeComplete();
        // Service gate: deliberately NOT cleared here (Folia review 2026-08-27 R3) —
        // registerPlayer is also the dimension-change reuse path, and clearing the
        // denied-handshake memo there raced a region-thread deposit against its own
        // queued unregister composite (stranding the player past its revocation, no
        // re-offer). The HANDSHAKE register paths clear it: the shared glue's register
        // branch, and Paper's Register drain.
        return state;
    }

    public void removePlayer(UUID uuid) {
        var removed = this.players.remove(uuid);
        if (removed != null) removed.registration().retire();
        if (removed != null) {
            this.offThreadProcessor.notifyPlayerRemoved(uuid, removed.registration());
            cleanupPlayerServices(uuid, removed.registration());
        }
        // Resets the v16 want-set + arms the ingress grace. Identity survives (dropped only
        // by the network DISCONNECT hook), mirroring how capabilities ride the dim-change
        // remove+register cycle. No-op for v18 players.
        this.v16Compat.onServiceRemove(uuid);
    }

    /**
     * The service-gate unregistration composite (service-permission-gate-plan.md
     * §2.3): {@code removePlayer} + the far-player viewer shed + the region-summary
     * cleanup — the departed-player sweep's trio, NEVER a modified removePlayer
     * (that is the dimension-change reuse path; teaching it to shed viewers would
     * break every dimension change). The dialect mark and v16 identity are
     * deliberately KEPT — connection-lifecycle facts, and the mark is what any
     * per-player disable push read. No-op for an unregistered uuid. Server thread.
     */
    public void unregisterForServiceGate(UUID uuid) {
        if (!this.players.containsKey(uuid)) return;
        removePlayer(uuid);
        this.farPlayerService.removeViewer(uuid);
        var summaries = getRegionSummaries();
        if (summaries != null) summaries.removePlayer(uuid);
    }

    /** The service-gate bookkeeping — see {@link dev.vox.lss.common.ServiceGateState}. */
    public dev.vox.lss.common.ServiceGateState getServiceGateState() {
        return this.serviceGateState;
    }

    private void cleanupPlayerServices(UUID uuid, RequestRegistration registration) {
        this.diskReader.removePlayerResults(uuid, registration);
        if (this.generationService != null) this.generationService.removePlayer(uuid, registration);
    }

    int lodDistanceFor(ServerPlayer player) {
        return ServerWorldLod.distance(serviceConfig, player);
    }

    public void handleBatchRequest(ServerPlayer player, BatchChunkRequestC2SPayload payload) {
        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        // -1 in a world whose distance is 0 (no LOD service there): every ask is range-filtered.
        int maxDist = dev.vox.lss.common.config.ServerConfigBase.requestRadius(lodDistanceFor(player));

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
                dev.vox.lss.platform.LoaderServices.get().sendToPlayer(player,
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

    // Per-owner progress survives failure in another owner. Publication's previous
    // snapshot cannot describe partial adoption, especially when the next edit reverts.
    private dev.vox.lss.common.config.ServerSettings.Lod advertisedLod;
    private dev.vox.lss.common.config.ServerSettings.Service adoptedServicePolicy;
    /** The map-ingestion policy the sessions were last told (map-policy plan A3.4): the
     *  service-policy compare never sees a {@code privacy.*} key, so the arm/disarm
     *  trigger needs its own adoption mark. */
    private boolean adoptedMapPolicy;
    private dev.vox.lss.common.config.ServerSettings.FarPlayers adoptedFarPlayerPolicy;
    private boolean refreshedGeneration;
    private volatile boolean advertisedGeneration;
    public boolean generationEnabledForSession() { return advertisedGeneration; }

    private volatile boolean settingsStopped;
    private volatile long settingsRevision;
    private volatile int settingsLegacyReconnects;
    private volatile int settingsMapPolicyRevoked = -1;
    private volatile boolean settingsMapPolicyArmed;
    /** @param mapPolicyRevoked sessions the map-policy arm sweep revoked at this revision,
     *                          or -1 when the policy did not change (map-policy plan A3.1:
     *                          the reload feedback's own line, never the legacy-reconnect
     *                          text)
     *  @param mapPolicyArmed   the policy THIS revision adopted (review fold A9: the reply
     *                          never reads the live config, which a later reload may have
     *                          changed by the time it is written) */
    public record SettingsFeedback(long revision, int legacyReconnects, java.util.List<String> draining,
                                   int mapPolicyRevoked, boolean mapPolicyArmed) {
        public SettingsFeedback { draining = java.util.List.copyOf(draining); }
        public SettingsFeedback(long revision, int legacyReconnects, java.util.List<String> draining) {
            this(revision, legacyReconnects, draining, -1, false);
        }
    }
    private volatile SettingsFeedback settingsFeedback = new SettingsFeedback(0, 0, java.util.List.of());
    public SettingsFeedback settingsFeedback() { return settingsFeedback; }
    public int lastSettingsLegacyReconnects() { return settingsLegacyReconnects; }
    public java.util.List<String> settingsDrainingStatus() {
        var status = new java.util.ArrayList<String>();
        if (!generationEnabledForSession() && generationService != null && generationService.getActiveCount() > 0)
            status.add("Generation disabled; " + generationService.getActiveCount() + " admitted job(s) draining");
        if (storeBackfill != null && storeBackfill.isRunning() && !serviceConfig.lodStoreBackfill())
            status.add("Backfill stop requested; current column draining");
        return java.util.List.copyOf(status);
    }
    private volatile boolean generationRefreshPending;
    private final java.util.Set<java.util.concurrent.CompletableFuture<?>> settingsReceipts =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Owner dispatch with a terminal shutdown outcome, unlike fire-and-forget tasks. */
    public <T> java.util.concurrent.CompletableFuture<T> submitSettingsControl(java.util.function.Supplier<T> action) {
        var receipt = new java.util.concurrent.CompletableFuture<T>();
        settingsReceipts.add(receipt);
        receipt.whenComplete((value, failure) -> settingsReceipts.remove(receipt));
        Runnable task = () -> {
            if (receipt.isDone()) return;
            if (settingsStopped) { receipt.completeExceptionally(new java.util.concurrent.CancellationException("Server stopped")); return; }
            try { receipt.complete(action.get()); }
            catch (Throwable failure) { receipt.completeExceptionally(failure); }
        };
        try {
            if (settingsStopped) throw new java.util.concurrent.RejectedExecutionException("Server stopped");
            server.execute(task);
            if (settingsStopped) receipt.completeExceptionally(new java.util.concurrent.CancellationException("Server stopped"));
        } catch (Throwable failure) { receipt.completeExceptionally(failure); }
        return receipt;
    }

    private void cancelSettingsReceipts() {
        settingsStopped = true;
        for (var receipt : settingsReceipts)
            receipt.completeExceptionally(new java.util.concurrent.CancellationException("Server stopped"));
    }

    /** Called on the service owner after one immutable publication. No cross-owner waits. */
    public java.util.concurrent.CompletableFuture<Void> reconcileSettings(
            dev.vox.lss.common.config.ServerSettings previous,
            dev.vox.lss.common.config.ServerSettings next, long revision) {
        if (settingsStopped) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Server stopped"));
        if (revision < settingsRevision) return java.util.concurrent.CompletableFuture.failedFuture(
                new java.util.concurrent.CancellationException("Superseded settings revision"));
        // Keep the session owner's reconnect count while retrying an operation
        // that another owner failed; a successful prior report starts fresh.
        if (settingsFeedback.revision() == settingsRevision) settingsLegacyReconnects = 0;
        settingsMapPolicyRevoked = -1;
        settingsRevision = revision;
        var view = new dev.vox.lss.common.config.ServerConfigBase(next, false);
        boolean generationChanged = refreshedGeneration != next.generation().enabled();
        if (generationChanged) generationRefreshPending = true;
        var generation = next.generation();
        if (generationService != null) generationService.updatePolicy(generation.enabled(),
                generation.concurrency().global(), generation.concurrency().perPlayer(), generation.timeoutTicks(), revision);
        for (var state : players.values()) state.updateGenSlotCap(generation.concurrency().perPlayer());
        bandwidthLimiter.reconfigure(view.bytesPerSecondGlobal());
        diskReader.reapplyGateCapacity(view);
        diskReader.updateSerializationPolicy(next.serialization().nbtTranscode(), next.serialization().selectiveNbtParse());
        offThreadProcessor.updateSweepRadius(view.maxConfiguredLodDistanceChunks()
                + LSSConstants.LOD_DISTANCE_BUFFER + dev.vox.lss.common.processing.OffThreadProcessor.SWEEP_RADIUS_MARGIN_CHUNKS);
        var processor = offThreadProcessor.updateSettingsPolicy(generation.enabled(),
                view.effectiveTimestampCacheMB() * 1024L * 1024L,
                java.util.concurrent.TimeUnit.SECONDS.toNanos(next.storage().missMemoTtlSeconds()), revision);
        java.util.concurrent.CompletableFuture<Void> store = java.util.concurrent.CompletableFuture.completedFuture(null);
        var newStore = next.storage().lodStore();
        long desiredCap = view.lodStoreMaxBytes() <= 0 ? Long.MAX_VALUE : view.lodStoreMaxBytes();
        boolean capIncreased = false;
        if (lodStore instanceof dev.vox.lss.common.store.SqliteLodStore sqlite) {
            var adopted = sqlite.adoptedPolicy();
            capIncreased = desiredCap > adopted.maxDbBytes();
            if (desiredCap != adopted.maxDbBytes()
                    || newStore.resweepIntervalSeconds() != adopted.resweepSeconds())
                store = sqlite.updatePolicy(view.lodStoreMaxBytes(), newStore.resweepIntervalSeconds(), revision);
        }
        if (storeBackfill != null) {
            boolean resumeCappedBackfill = capIncreased;
            store = store.thenCompose(ignored -> {
                if (revision != settingsRevision || settingsStopped)
                    return java.util.concurrent.CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("Superseded settings revision"));
                var backfill = newStore.backfill();
                return storeBackfill.updatePolicy(backfill.enabled(), backfill.columnsPerSecond(),
                        revision, resumeCappedBackfill);
            });
        }
        // Generation/session ordering waits for its processing owner, never an unrelated
        // long store sweep or backfill read. Overall reporting still awaits every owner.
        var session = processor.thenCompose(ignored -> submitSettingsControl(() -> {
            if (revision != settingsRevision) throw new java.util.concurrent.CancellationException("Superseded settings revision");
            advertisedGeneration = next.generation().enabled();
            boolean mapPolicy = next.privacy().requestNoMapIngestion();
            boolean mapPolicyChanged = adoptedMapPolicy != mapPolicy;
            // Map-policy plan A3.4: REVOKE FIRST — a session about to lose service must
            // not first get an enabled=true manager rebuild from the re-push below. -1 on
            // an unchanged policy: a later reload must not repeat this revision's count.
            // A distance/generation change re-pushes every CURRENT session in full below, and
            // that push carries (and records) each one's flags: the sweep then leaves the
            // capable sessions to it, so an arming reload never pushes one twice.
            boolean fullRepush = generationChanged || !advertisedLod.equals(next.lod());
            settingsMapPolicyRevoked = mapPolicyChanged
                    ? runMapPolicySweep(serviceConfig, !fullRepush) : -1;
            settingsMapPolicyArmed = mapPolicy;
            if (fullRepush)
                settingsLegacyReconnects = repushSessionConfig()[1];
            else if (mapPolicyChanged)
                repushSessionConfig(true); // the flags change alone; legacy sessions are not told
            refreshedGeneration = next.generation().enabled();
            advertisedLod = next.lod();
            generationRefreshPending = false;
            if (mapPolicyChanged) {
                adoptedMapPolicy = mapPolicy;
                // Disarm: the players refused at handshake or revoked heal NOW through
                // the memo replay (their own dialect, the full production ladder); the
                // exempt admissions end with the episode (a later arm decides afresh).
                if (!mapPolicy) {
                    this.serviceGateState.clearMapPolicyExemptAdmissions();
                    replayDeniedHandshakes(serviceConfig);
                }
            }
            if (!adoptedServicePolicy.equals(next.service())) {
                runServiceGateSweeps(serviceConfig);
                adoptedServicePolicy = next.service();
            }
            if (!adoptedFarPlayerPolicy.equals(next.farPlayers())) {
                farPlayerTickCounter = Integer.MAX_VALUE - 1;
                tickFarPlayers(serviceConfig);
                adoptedFarPlayerPolicy = next.farPlayers();
            }
            return (Void) null;
        }));
        var result = java.util.concurrent.CompletableFuture.allOf(session, store)
                .thenCompose(ignored -> submitSettingsControl(() -> {
                    if (revision != settingsRevision) throw new java.util.concurrent.CancellationException("Superseded settings revision");
                    settingsFeedback = new SettingsFeedback(revision, settingsLegacyReconnects, settingsDrainingStatus(),
                            settingsMapPolicyRevoked, settingsMapPolicyArmed);
                    return (Void) null;
                }));
        settingsReceipts.add(result);
        result.whenComplete((value, failure) -> {
            settingsReceipts.remove(result);
            if (revision == settingsRevision && failure != null) generationRefreshPending = false;
        });
        return result;
    }

    private final dev.vox.lss.common.config.UnservedWorldsLog unservedWorldsLog =
            new dev.vox.lss.common.config.UnservedWorldsLog();

    public void tick() {
        if (!serviceConfig.enabled()) return;
        this.unservedWorldsLog.note(serviceConfig);

        this.diag.reset(this.offThreadProcessor.getDiagnostics());

        var config = serviceConfig;
        applyRuntimeConfig(config);
        // Service gate (plan §2.3): permission rechecks are tick-cadenced — the reads
        // run on this thread only, every PERMISSION_RECHECK_TICKS.
        if (++this.permissionRecheckCounter >= PERMISSION_RECHECK_TICKS) {
            this.permissionRecheckCounter = 0;
            // Map-policy plan A3.4: the idempotent enforcement (armed ∧ registered ∧
            // CURRENT ∧ ¬bit → revoke) — closes a handshake decided on the pre-arm
            // config that registered after the reload's one-shot sweep. Only once the
            // reconcile ADOPTED the arm (review fold A9): the reload owns the transition,
            // so its sweep — and its feedback's revoked count — always runs first.
            if (this.adoptedMapPolicy) runMapPolicySweep(config);
            runServiceGateSweeps(config);
        }
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
        this.lastActiveCount = lifecycle.activeCount;
        flushSendQueues(lifecycle.activeCount, config);
        tickDirtyBroadcast(config);
        tickFarPlayers(config);
        tickRegionSummaries();
        tickDiagnosticsLog(config);
    }

    /** Region summaries (P2, plan §5): admit dimension-matched requests into sweep jobs
     *  and drain ready frames onto the dedicated send lane. Player state is only read
     *  HERE (the tick thread) — ingress stores pure data. */
    private void tickRegionSummaries() {
        try {
            this.regionSummaries.pump(uuid -> {
                var state = this.players.get(uuid);
                if (state == null || !state.hasCompletedHandshake()) return null;
                String dim = state.registeredDimension();
                long pc = state.playerChunkPackedOrSentinel();
                if (dim == null || pc == Long.MIN_VALUE) return null;
                // No LOD service in this world: no summary (the request expires on its TTL).
                if (!dev.vox.lss.common.config.ServerConfigBase.servesWorld(
                        serviceConfig.lodDistanceForWorld(dim))) return null;
                return new dev.vox.lss.common.region.RegionSummaryService.PlayerAnchor(
                        dim, PositionUtil.unpackX(pc), PositionUtil.unpackZ(pc));
            }, (uuid, frame) -> {
                var player = this.server.getPlayerList().getPlayer(uuid);
                if (player == null) { // disconnected while assembling — unsendable forever
                    return dev.vox.lss.common.region.RegionSummaryService.SendOutcome.DROP;
                }
                // CURRENT-dialect re-check at the sink (final panel — the stamps
                // lane's rule, mirrored): the ingress guard runs at REQUEST time, but
                // a pre-handshake request reads as CURRENT (untracked UUID) and a
                // session can re-handshake DOWN before the frame drains; a legacy
                // session must never receive a summary frame.
                if (this.dialects.dialectOf(uuid)
                        != dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT) {
                    return dev.vox.lss.common.region.RegionSummaryService.SendOutcome.DROP;
                }
                // The far-player lane's writability discipline (final review F2), with
                // RETENTION (live-diagnosed 2026-08-20, rig reqs=7/frames=5): the frame
                // drains at the join/portal moment lodYieldsToVanillaTransport protects
                // — exactly when the serve flood makes the channel unwritable — and the
                // client never re-requests, so an unwritable channel answers RETRY (the
                // service retains the frame, TTL-bounded) instead of eating the whole
                // session's exchange.
                var snap = FabricChannelPressure.forPlayer(player).snapshot();
                if (snap.writable() == dev.vox.lss.common.processing.ChannelPressureProbe
                        .Writability.NOT_WRITABLE) {
                    return dev.vox.lss.common.region.RegionSummaryService.SendOutcome.RETRY;
                }
                dev.vox.lss.platform.LoaderServices.get().sendToPlayer(player,
                        new dev.vox.lss.networking.payloads.RegionSummaryS2CPayload(frame));
                return dev.vox.lss.common.region.RegionSummaryService.SendOutcome.SENT;
            });
        } catch (Exception e) {
            // Containment: a pump/send bug must degrade summaries, never the tick.
            if (!this.regionSummaryTickErrorWarned) {
                this.regionSummaryTickErrorWarned = true;
                LSSLogger.error("Region-summary pump failed — contained (once per session)", e);
            }
        }
    }

    private boolean regionSummaryTickErrorWarned;

    /** Ingress for {@code lss:region_summary_req} (any thread — stores pure data). The
     *  HANDLER-checked kill switch (plan §5): checked per request, though the key is
     *  boot-set in practice (not in the {@code /lsslod set} registry — a flip needs a
     *  restart). */
    public void handleRegionSummaryRequest(ServerPlayer player, byte[] body) {
        if (!serviceConfig.enabled() || !serviceConfig.enableRegionSummaries()) {
            return;
        }
        dev.vox.lss.common.region.RegionSummaryWire.Request request;
        try {
            request = dev.vox.lss.common.region.RegionSummaryWire.decodeRequest(body);
        } catch (Exception e) {
            // Hostile/malformed frame: contained drop (throttled — any authenticated
            // client can spam these at packet rate).
            long n = SUMMARY_REQ_DECODE_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) {
                LSSLogger.warn("Malformed region-summary request from "
                        + player.getName().getString() + " — ignored (" + n
                        + " since the last report): " + e);
            }
            return;
        }
        // CURRENT dialect only (plan §9.4 — the far-player subscription discipline):
        // a legacy-dialect session must not become stamps-eligible; the conforming
        // client gates its own request on the CURRENT dialect already, so this only
        // rejects nonconforming senders.
        if (this.dialects.dialectOf(player.getUUID())
                != dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT) {
            return;
        }
        this.regionSummaries.offerRequest(player.getUUID(), request);
    }

    private static final dev.vox.lss.common.LogThrottle SUMMARY_REQ_DECODE_WARN =
            new dev.vox.lss.common.LogThrottle(60_000);

    /** The region-summary service (diag + tests). */
    public dev.vox.lss.common.region.RegionSummaryService getRegionSummaries() {
        return this.regionSummaries;
    }

    /** Far players (E1): one broadcast pass every {@code farPlayersUpdateIntervalTicks}
     *  while the mode is armed and anyone subscribed. Mode transitions drain control
     *  frames every tick; mode "off" skips position and equipment snapshots. */
    private void tickFarPlayers(LSSServerConfig config) {
        if (this.farPlayerService.subscriberCount() == 0) return;
        try {
            if (!this.farPlayerService.applyMode(config.farPlayers(), this::sendFarPlayerFrame)) return;
            if (++this.farPlayerTickCounter < config.farPlayersUpdateIntervalTicks()) return;
            this.farPlayerTickCounter = 0;
            var online = new java.util.ArrayList<dev.vox.lss.common.farplayers
                    .FarPlayerBroadcastService.PlayerSnapshot>();
            for (var p : this.server.getPlayerList().getPlayers()) {
                online.add(FabricFarPlayerSnapshots.snapshot(p));
            }
            this.farPlayerService.tick(System.currentTimeMillis(), online,
                    new dev.vox.lss.common.farplayers.FarPlayerBroadcastService.Settings(
                            config.farPlayers(), config.farPlayersMaxDistanceBlocks(),
                            config.farPlayersMinDistanceBlocks(), config.farPlayersSendSpectators(),
                            config.farPlayersExclude(), config.farPlayersUpdateIntervalTicks(),
                            config.farPlayersHidePermissionHolders(),
                            config.farPlayersHidePermissionHoldersUndecided()),
                    this::sendFarPlayerFrame);
        } catch (Exception e) {
            // Containment (review): END_SERVER_TICK has no catch of its own — a
            // snapshot/encode bug here must degrade far players, never the server tick.
            if (!this.farPlayerTickErrorWarned) {
                this.farPlayerTickErrorWarned = true;
                LSSLogger.error("Far-player broadcast pass failed — contained (once per session)", e);
            }
        }
    }

    private boolean farPlayerTickErrorWarned;

    /** The dedicated far-player send lane (FARP §3.2 — never the column send queue):
     *  consults channel writability (an unwritable channel WITHHOLDS — the service
     *  retries next tick) and charges the shared bandwidth governor. */
    private boolean sendFarPlayerFrame(UUID viewer, String channel, byte[] body) {
        var player = this.server.getPlayerList().getPlayer(viewer);
        if (player == null) return false;
        var snap = FabricChannelPressure.forPlayer(player).snapshot();
        if (snap.writable() == dev.vox.lss.common.processing.ChannelPressureProbe
                .Writability.NOT_WRITABLE) {
            return false;
        }
        net.minecraft.network.protocol.common.custom.CustomPacketPayload payload =
                LSSConstants.CHANNEL_FAR_PLAYER_ROSTER.equals(channel)
                        ? new dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload(body)
                        : new dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload(body);
        dev.vox.lss.platform.LoaderServices.get().sendToPlayer(player, payload);
        this.bandwidthLimiter.recordSend(body.length);
        return true;
    }

    /** The far-player notices' chat-line send (hide-permission plan §2b; the map-policy
     *  chat line's shape): an ordinary system message on the server thread, so it reaches
     *  every client version. Translatable with the English text as its fallback: a client
     *  whose lang files carry the key shows its own language, every other client (an
     *  older mod, no mod) shows the fallback. Seam-injected for tests (gametest mock
     *  players have no client). */
    private java.util.function.BiConsumer<ServerPlayer,
            dev.vox.lss.common.farplayers.FarPlayerBroadcastService.ChatLine> farPlayerNoticeSender =
            (player, line) -> player.sendSystemMessage(
                    net.minecraft.network.chat.Component.translatableWithFallback(
                            line.translationKey(), line.fallback()));

    /** Test seam; {@code null} restores the production send. */
    public void setFarPlayerNoticeSenderForTest(java.util.function.BiConsumer<ServerPlayer,
            dev.vox.lss.common.farplayers.FarPlayerBroadcastService.ChatLine> sender) {
        this.farPlayerNoticeSender = sender != null ? sender
                : (player, line) -> player.sendSystemMessage(
                        net.minecraft.network.chat.Component.translatableWithFallback(
                                line.translationKey(), line.fallback()));
    }

    /** The broadcast core's {@code PlayerNotifier}: one system chat line to an online
     *  player (mode "required" overriding their Share My Position choice, or the holder
     *  notice). False = not online right now, so the core retries on a later pass. */
    private boolean sendFarPlayerNotice(UUID uuid,
            dev.vox.lss.common.farplayers.FarPlayerBroadcastService.ChatLine line) {
        var player = this.server.getPlayerList().getPlayer(uuid);
        if (player == null) return false;
        this.farPlayerNoticeSender.accept(player, line);
        return true;
    }

    // Active-settings reconciliation on the service owner: each formerly
    // capture-at-construction consumer re-applies config at the top of the tick, on the
    // thread that owns its state (the broadcaster's live-read precedent). Change-guarded
    // so the steady state costs a few field compares.
    private int lastAppliedGenGlobal = -1;
    private int lastAppliedGenPerPlayer = -1;

    private void applyRuntimeConfig(LSSServerConfig config) {
        this.bandwidthLimiter.reconfigure(config.bytesPerSecondGlobal());
        this.diskReader.reapplyGateCapacity(config);
        this.offThreadProcessor.updateSweepRadius(config.maxConfiguredLodDistanceChunks()
                + LSSConstants.LOD_DISTANCE_BUFFER + OffThreadProcessor.SWEEP_RADIUS_MARGIN_CHUNKS);
        var generationLimits = config.generationLimits();
        int genGlobal = generationLimits.global();
        int genPerPlayer = generationLimits.perPlayer();
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

    /** Test seam: delivers a mid-session SessionConfig push (the dimension-change push and
     *  the reload re-push). Production default is {@code LoaderServices.sendToPlayer};
     *  gametests record the pushes to pin their {@code enabled}/distance fields. */
    @FunctionalInterface
    public interface SessionConfigPusher {
        void push(ServerPlayer player, SessionConfigS2CPayload payload) throws Exception;
    }

    private volatile SessionConfigPusher sessionConfigPusher =
            (player, payload) -> dev.vox.lss.platform.LoaderServices.get().sendToPlayer(player, payload);

    /** Test seam (see {@link SessionConfigPusher}); {@code null} restores the production send. */
    public void setSessionConfigPusher(SessionConfigPusher pusher) {
        this.sessionConfigPusher = pusher != null ? pusher
                : (player, payload) -> dev.vox.lss.platform.LoaderServices.get().sendToPlayer(player, payload);
    }

    /**
     * Push a fresh SessionConfig to every CURRENT-dialect (v20) session after a
     * explicit settings reload changing terrain distance (SET plan §"Pushing the new distance").
     * Legacy sessions (v19/v18/v16) are deliberately skipped — their clients'
     * mid-session-config behavior is release-frozen and unverified; they keep the
     * handshake distance until rejoin. Fabric: commands run on the server thread (=
     * tick thread), so reload completion returns here through the server owner.
     *
     * @return {pushed, legacySkipped}
     */
    public int[] repushSessionConfig() {
        return repushSessionConfig(false);
    }

    /** @param flagsOnly a map-policy-only change (review fold A9): the sessions' distance
     *                   and generation did not move, so an off world's session is NOT
     *                   re-registered and its far-player roster NOT re-sent — the push
     *                   only carries the new flags, so it reaches only a session that
     *                   declared the capability AND whose decision differs from the
     *                   flags it was last sent (map-ingestion permission plan §3.3: an
     *                   exempt session, or one the arm sweep already told, is not
     *                   pushed twice) */
    public int[] repushSessionConfig(boolean flagsOnly) {
        var config = serviceConfig;
        var gateState = this.serviceGateState;
        int pushed = 0;
        int legacy = 0;
        for (var state : List.copyOf(this.players.values())) {
            if (this.dialects.dialectOf(state.getPlayerUUID())
                    != dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT) {
                legacy++;
                continue;
            }
            var player = state.getPlayer();
            boolean denyMapIngestion = denyMapIngestionFor(player, config);
            if (flagsOnly && ((state.getCapabilities() & LSSConstants.CAPABILITY_MAP_POLICY) == 0
                    || Boolean.valueOf(denyMapIngestion)
                            .equals(gateState.lastMapFlagsSent(state.getPlayerUUID())))) {
                continue; // nothing new to tell this session
            }
            int dist = lodDistanceFor(player);
            if (dist <= 0 && !flagsOnly) {
                // The reload turned this player's world off: abandon its in-flight work the
                // way a dimension change does (remove + fresh register — retires admitted
                // reads/generations and resets the served-set, so a world later turned back
                // on cannot answer up_to_date off a done-bit from before). The client stops
                // declaring on the enabled=false below.
                int capabilities = state.getCapabilities();
                removePlayer(player.getUUID());
                registerPlayer(player, capabilities);
                // Far players keep running in an off world, but the client ends its far-player
                // session on the enabled=false below (its tracker forgets the epoch), so the
                // roster must re-send exactly as on a dimension change — otherwise every
                // later update carries the old epoch, is dropped, and the proxies vanish until
                // the player changes dimension or rejoins.
                this.farPlayerService.onViewerDimensionChange(player.getUUID());
            }
            var payload = SessionConfigS2CPayload.forSession(
                    LSSConstants.PROTOCOL_VERSION,
                    config.enabled() && dist > 0, // 0 = no LOD service in this world
                    dist,
                    generationEnabledForSession(),
                    net.minecraft.SharedConstants.getCurrentVersion()
                            .dataVersion().version(),
                    // Map-policy plan A2: decided at send time, per player — the
                    // arm/disarm re-push is how a capable session learns the new flags.
                    state.getCapabilities(), denyMapIngestion);
            try {
                this.sessionConfigPusher.push(player, payload);
                gateState.noteMapFlagsSent(state.getPlayerUUID(), payload.protocolVersion(),
                        state.getCapabilities(), denyMapIngestion);
                pushed++;
            } catch (Exception e) {
                LSSLogger.error("Session-config re-push failed for " + state.getPlayerName(), e);
            }
        }
        return new int[]{pushed, legacy};
    }

    /**
     * The two service-gate sweeps (service-permission-gate-plan.md §2.3). Public so the
     * gametests can drive one sweep directly instead of burning 200 real ticks; the one
     * production caller is the tick cadence above. Server thread only.
     *
     * <p><b>Revocation</b> (armed only, CURRENT-dialect sessions only — legacy clients'
     * mid-session-config behavior is release-frozen and unverified, the same recorded
     * decision {@link #repushSessionConfig} pins; they keep serving until rejoin, where
     * the handshake gate denies in their own dialect): a registered player failing the
     * gate on TWO consecutive sweeps (flap hysteresis — context-scoped grants
     * oscillate) gets a per-player enabled=false SessionConfig (the CURRENT 4-field
     * shape), the denied-handshake memo deposit (the live session's own
     * version+capabilities — revoke-then-regrant must heal), and the unregistration
     * composite. A per-player permission-read throw is contained and counts as HOLDING
     * (fail-open) without stopping the sweep.
     *
     * <p><b>Grant</b> (whenever the memo is non-empty, REGARDLESS of arming — disarming
     * the gate is the staged rollout's final step and must drain the map): each
     * remembered, online, unregistered player now clearing the gate (trivially everyone
     * with the gate off) gets its stored handshake REPLAYED through the full production
     * ladder — version, Via, consumer, enabled all re-run, the reply lands in the
     * client's own dialect via the verified handshake-reply construction (this is how
     * legacy players heal too). A replay that terminates in any non-register terminal
     * outcome leaves the entry dropped (no 0.1 Hz duplicate configs); a replay the gate
     * denies AGAIN re-deposits through the denial hook.
     */
    public void runServiceGateSweeps(LSSServerConfig config) {
        var gateState = this.serviceGateState;
        if (config.requireServicePermission()) {
            // The armed-gate-without-provider warn (plan §2.1/§8 N-2, as-built at the
            // sweep so ONE site covers boot AND a runtime set arm — it fires within
            // one recheck interval of either). Only Fabric can lack a backend
            // (NeoForge registers native nodes; Paper is Bukkit): the LoaderServices
            // token is "none" exactly when no fabric-permissions-api resolved.
            if (!this.noProviderWarned && "none".equals(
                    dev.vox.lss.platform.LoaderServices.get().permissionProviderToken())) {
                this.noProviderWarned = true;
                LSSLogger.warn("requireServicePermission is on but no permission provider "
                        + "(fabric-permissions-api) is installed — serving everyone");
            }
            for (var state : this.players.values()) {
                UUID uuid = state.getPlayerUUID();
                if (this.dialects.dialectOf(uuid)
                        != dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT) {
                    continue;
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
                // Push the disarm FIRST (plan order: push, then unregister), then the
                // memo deposit + composite. The push rides the existing send-failure
                // containment — a failed send still revokes (the client re-handshake
                // path heals the shape either way).
                try {
                    boolean denyMapIngestion = denyMapIngestionFor(player, config);
                    dev.vox.lss.platform.LoaderServices.get().sendToPlayer(player,
                            SessionConfigS2CPayload.forSession(
                                    LSSConstants.PROTOCOL_VERSION, false,
                                    // Inert under enabled=false, but resolved per-world for
                                    // consistency (no raw per-player lodDistanceChunks reads left).
                                    lodDistanceFor(player), generationEnabledForSession(),
                                    net.minecraft.SharedConstants.getCurrentVersion()
                                            .dataVersion().version(),
                                    state.getCapabilities(), denyMapIngestion));
                    gateState.noteMapFlagsSent(uuid, LSSConstants.PROTOCOL_VERSION,
                            state.getCapabilities(), denyMapIngestion);
                } catch (Exception e) {
                    LSSLogger.error("Service-gate disable push failed for "
                            + state.getPlayerName(), e);
                }
                gateState.rememberDenied(uuid, state.getPlayerName(),
                        LSSConstants.PROTOCOL_VERSION, state.getCapabilities());
                unregisterForServiceGate(uuid);
                noticePermissionRefusal(player, true);
                LSSLogger.info("LOD service revoked for " + state.getPlayerName()
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
        replayDeniedHandshakes(config);
    }

    /**
     * The grant sweep (service-permission-gate-plan.md §2.3; map-policy plan A3.4):
     * whenever the memo is non-empty, each remembered, online, unregistered player whose
     * EVERY memo reason has cleared gets its stored handshake replayed through the full
     * production ladder. PERMISSION clears as before (gate disarmed, or the probe now
     * holds); MAP_POLICY clears only when the policy is disarmed or the stored handshake
     * is CURRENT with the capability bit — without the reason mask, a disarmed gate's
     * "trivially clears everyone" would replay a policy-refused player every sweep for as
     * long as the policy is armed (each replay re-sending enabled=false). Also run by a
     * reload that disarms the map policy, so refused players heal at once. Server thread.
     */
    public void replayDeniedHandshakes(LSSServerConfig config) {
        var gateState = this.serviceGateState;
        if (gateState.hasDenied()) {
            for (UUID uuid : gateState.deniedSnapshot()) {
                if (this.players.containsKey(uuid)) {
                    // SKIP, never clear (implementation review, 2026-08-27): on Folia a
                    // denied re-handshake deposits the memo on a region thread while the
                    // unregister composite is still queued — clearing here would wipe the
                    // deposit and strand the player past its own revocation. A genuinely
                    // stale entry is retained one sweep and replays correctly on the
                    // next (registration removed it in every non-race case anyway).
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
                var entry = gateState.peekDenied(uuid);
                if (entry == null) continue;
                boolean permissionCleared;
                if (!entry.has(dev.vox.lss.common.ServiceGateState.REASON_PERMISSION)
                        || !config.requireServicePermission()) {
                    permissionCleared = true; // not a permission denial, or a disarmed gate
                } else {
                    try {
                        permissionCleared = this.permissionProbe.test(player,
                                        dev.vox.lss.common.LSSPermissions.SERVICE_LSS)
                                && this.permissionProbe.test(player,
                                        dev.vox.lss.common.LSSPermissions.SERVICE_VSS);
                    } catch (Exception e) {
                        permissionCleared = true; // fail-open, the doctrine
                    }
                }
                // Per player (map-ingestion permission plan §3.2): a refused player who
                // now holds the exemption clears too — the replay is its new handshake.
                boolean denyMapIngestion = denyMapIngestionFor(player, config);
                boolean mapPolicyCleared = mapPolicyCleared(entry, denyMapIngestion);
                if (!permissionCleared || !mapPolicyCleared) continue; // the entry stays
                var remembered = gateState.takeDenied(uuid);
                if (remembered == null) continue;
                LSSLogger.info("Re-offering " + dev.vox.lss.common.Brand.shortName() + " to "
                        + remembered.playerName() + " (re-offer): "
                        + reofferReason(remembered, config.requireServicePermission(),
                                denyMapIngestion, config.requestNoMapIngestion())
                        + " — replaying the stored handshake");
                try {
                    ServerReceiverGlue.replayHandshake(
                            new dev.vox.lss.networking.payloads.HandshakeC2SPayload(
                                    remembered.protocolVersion(), remembered.capabilities()),
                            player, this,
                            reply -> dev.vox.lss.platform.LoaderServices.get()
                                    .sendToPlayer(player, reply));
                } catch (Exception e) {
                    // Contained: a throwing reply send (player vanishing mid-sweep) must
                    // not abort the remaining re-offers; the entry stays dropped — the
                    // client's own rejoin handshake is the heal, as for any lost config.
                    LSSLogger.error("Service-gate re-offer failed for "
                            + remembered.playerName(), e);
                }
            }
        }
    }

    /** Whether a memo entry's MAP_POLICY reason has cleared (map-policy plan A3.4): it
     *  holds no such reason, the request no longer applies to the player (disarmed, or —
     *  map-ingestion permission plan §3.2 — the player is now exempt), or the stored
     *  handshake is CURRENT with the capability bit (a later handshake overwrote the
     *  stored capabilities).
     *
     *  @param denyMapIngestion the per-player decision ({@link #denyMapIngestionFor}) */
    static boolean mapPolicyCleared(dev.vox.lss.common.ServiceGateState.DeniedHandshake entry,
                                    boolean denyMapIngestion) {
        return !entry.has(dev.vox.lss.common.ServiceGateState.REASON_MAP_POLICY)
                || !denyMapIngestion
                || (entry.protocolVersion() == LSSConstants.PROTOCOL_VERSION
                        && (entry.capabilities() & LSSConstants.CAPABILITY_MAP_POLICY) != 0);
    }

    /** The re-offer INFO line's reason — policy refusals get their own text.
     *  @param denyMapIngestion the per-player decision; {@code policyOn} tells an exempt
     *                          player (on, not denied) from a disarmed policy */
    static String reofferReason(dev.vox.lss.common.ServiceGateState.DeniedHandshake entry,
                                boolean gateArmed, boolean denyMapIngestion, boolean policyOn) {
        var reasons = new ArrayList<String>(2);
        if (entry.has(dev.vox.lss.common.ServiceGateState.REASON_PERMISSION)) {
            reasons.add(gateArmed ? "the service permission cleared"
                    : "requireServicePermission was disarmed");
        }
        if (entry.has(dev.vox.lss.common.ServiceGateState.REASON_MAP_POLICY)) {
            reasons.add(denyMapIngestion ? "the client can now honor the map-ingestion request"
                    : policyOn ? "the player now holds " + dev.vox.lss.common.LSSPermissions.MAP_INGESTION_LSS
                            + " or " + dev.vox.lss.common.LSSPermissions.MAP_INGESTION_VSS
                    : "privacy.request_no_map_ingestion was turned off");
        }
        return String.join(" and ", reasons);
    }

    /**
     * The map-ingestion policy's enforcement sweep (map-policy plan A3.4; per player since
     * the map-ingestion permission plan §3.3/§3.4). While the policy is armed, over the
     * REGISTERED CURRENT-dialect sessions, in two passes so a revocation always precedes
     * any flags push:
     * <ol>
     *   <li><b>Older clients</b> (no {@code CAPABILITY_MAP_POLICY}) are decided ONCE: a
     *       session admitted under the exemption at handshake is left alone (a grant or
     *       revoke applies at its next join); any other is read now — exempt, it is
     *       recorded as admitted and keeps serving; not exempt, it is revoked (a
     *       per-player enabled=false push — no flags: the session has no bit — the
     *       unregistration composite, the memo deposit with reason MAP_POLICY so a disarm
     *       re-offers it, and the once-per-connection chat line).</li>
     *   <li><b>Capable clients</b> (0.19+) are re-evaluated every pass: one flags-only
     *       SessionConfig goes out only when the decision differs from the flags the
     *       session was last sent, so a grant or revoke lands within one sweep and an
     *       unchanged session (also across a dimension change) is never re-pushed.</li>
     * </ol>
     * Idempotent, and run both by the reload that arms the policy (BEFORE the re-push, which
     * then skips every session this pass already told) and on the 200-tick cadence. Legacy
     * dialects are NOT touched mid-session — the service-gate precedent: they keep serving
     * until rejoin, where the handshake decides them, and they have no map ingestion to
     * leak. Caches the exempt count for the diag line. Server thread.
     *
     * <p>A FAILED exemption read (a throwing permission backend) keeps the flags a capable
     * session was last sent — no push, no flip — and the next sweep reads again; the
     * handshake and the reload's re-push stay fail-closed.
     *
     * @return how many sessions this pass revoked
     */
    public int runMapPolicySweep(LSSServerConfig config) {
        return runMapPolicySweep(config, true);
    }

    /** @param pushCapable false when the caller's full re-push follows and tells every
     *                     capable session its flags (an arming reload that also changes
     *                     distance or generation): the capable pass then only counts */
    public int runMapPolicySweep(LSSServerConfig config, boolean pushCapable) {
        if (!config.requestNoMapIngestion()) return 0;
        var gateState = this.serviceGateState;
        int revoked = 0;
        int exempt = 0;
        var capable = new ArrayList<PlayerRequestState>();
        for (var state : List.copyOf(this.players.values())) {
            UUID uuid = state.getPlayerUUID();
            // An EXPLICIT CURRENT mark (review fold A9): dialectOf defaults an unmarked
            // session to CURRENT, so it cannot tell a shed mark from a real one.
            if (!this.dialects.isCurrent(uuid)) {
                continue; // legacy sessions keep serving until rejoin (recorded)
            }
            int capabilities = state.getCapabilities();
            if ((capabilities & LSSConstants.CAPABILITY_MAP_POLICY) != 0) {
                capable.add(state);
                continue;
            }
            var player = state.getPlayer();
            if (gateState.isMapPolicyExemptAdmission(uuid)) {
                exempt++; // decided at its handshake
                continue;
            }
            if (!denyMapIngestionFor(player, config)) {
                // Exempt, but decided on the pre-arm config (a reload arming the policy, or
                // a registration that raced it): decided now, then left alone like an
                // admission at handshake.
                gateState.setMapPolicyExemptAdmission(uuid, true);
                exempt++;
                continue;
            }
            String name = state.getPlayerName();
            try {
                this.sessionConfigPusher.push(player, SessionConfigS2CPayload.forSession(
                        LSSConstants.PROTOCOL_VERSION, false,
                        lodDistanceFor(player), generationEnabledForSession(),
                        net.minecraft.SharedConstants.getCurrentVersion()
                                .dataVersion().version(),
                        capabilities, true));
            } catch (Exception e) {
                LSSLogger.error("Map-policy disable push failed for " + name, e);
            }
            boolean transition = gateState.rememberDenied(uuid, name,
                    LSSConstants.PROTOCOL_VERSION, capabilities,
                    dev.vox.lss.common.ServiceGateState.REASON_MAP_POLICY);
            unregisterForServiceGate(uuid);
            noticeMapPolicyRefusal(player);
            if (transition) {
                LSSLogger.info(dev.vox.lss.common.MapPolicy.revocationLogLine(name,
                        LSSConstants.PROTOCOL_VERSION, capabilities));
            }
            revoked++;
        }
        for (var state : capable) {
            var player = state.getPlayer();
            UUID uuid = state.getPlayerUUID();
            Boolean lastSent = gateState.lastMapFlagsSent(uuid);
            var exemption = mapIngestionExemption(player);
            if (exemption == dev.vox.lss.common.MapPolicy.Exemption.FAILED && lastSent != null) {
                // Keep what the session was last told: a transient backend error must not
                // flip a granted player's map (nor push anything); the next sweep re-reads.
                // A session never told any flags has nothing to keep: fail-closed below.
                if (Boolean.FALSE.equals(lastSent)) exempt++;
                continue;
            }
            boolean denyMapIngestion = denyMapIngestionFor(config, exemption);
            if (!denyMapIngestion) exempt++;
            if (!pushCapable) continue; // the caller's full re-push tells it
            if (Boolean.valueOf(denyMapIngestion).equals(lastSent)) {
                continue; // nothing changed: no push (the client keeps its flag)
            }
            int dist = lodDistanceFor(player);
            int capabilities = state.getCapabilities();
            try {
                // The flags-only shape: version, enabled, distance and generation as the
                // session already has them, so the client takes its fast path (no manager
                // rebuild); a map-only client parks or un-parks on the flag.
                this.sessionConfigPusher.push(player, SessionConfigS2CPayload.forSession(
                        LSSConstants.PROTOCOL_VERSION, config.enabled() && dist > 0, dist,
                        generationEnabledForSession(),
                        net.minecraft.SharedConstants.getCurrentVersion()
                                .dataVersion().version(),
                        capabilities, denyMapIngestion));
                gateState.noteMapFlagsSent(uuid, LSSConstants.PROTOCOL_VERSION, capabilities,
                        denyMapIngestion);
            } catch (Exception e) {
                LSSLogger.error("Map-policy flags push failed for " + state.getPlayerName(), e);
            }
        }
        gateState.setMapPolicyExemptCount(exempt);
        return revoked;
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

    // The last tick's bandwidth-sharing player count (tick thread; a gametest observation point).
    private int lastActiveCount;

    /** How many players the last tick split the global bandwidth between (players in a world
     *  with no LOD service are not counted). Tick thread. */
    public int lastBandwidthSharingPlayerCount() {
        return this.lastActiveCount;
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
        Map<RequestRegistration, LongOpenHashSet> genReadyPositions = TickSnapshot.groupPositionsByRegistration(generationReady);

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
            // Counted AFTER the removal check (R2-11): a disconnecting player must not
            // dilute getPerPlayerAllocation for its final tick. Idle-but-connected
            // players DO count — the pinned SP-062 dilution — but a player parked in a
            // world with no LOD service (distance 0) does not: every ask it could make is
            // range-filtered, so it can never spend, and counting it would only shrink the
            // served players' shares. It counts again from its first tick in a served world.
            if (dev.vox.lss.common.config.ServerConfigBase.servesWorld(lodDistanceFor(state.getPlayer()))) {
                activeCount++;
            }

            // Captured BEFORE checkDimensionChange() mutates the stored dimension, so
            // the conditional re-push below can compare the OLD world's distance.
            var prevDim = state.getLastDimension();
            if (state.checkDimensionChange()) {
                // A dimension change abandons all in-flight work. Reuse the (well-tested)
                // disconnect teardown + a fresh registration instead of a second, hand-rolled
                // partial-reset protocol: the processing thread unwinds the old state's dedup
                // groups via the removal event, and the fresh state starts clean next tick.
                var player = state.getPlayer();
                int capabilities = state.getCapabilities();
                removePlayer(player.getUUID());
                registerPlayer(player, capabilities);
                // Far players: identity SURVIVES the remove+register cycle (the v18-rung
                // checklist); the roster does not — a bumped-epoch full roster follows.
                this.farPlayerService.onViewerDimensionChange(player.getUUID());
                // Re-push ONLY when the new world's distance differs (load-bearing, not an
                // optimization): the client rebuilds its whole request manager on ANY
                // SessionConfig, so an unconditional push would tax every portal trip even
                // with no overrides configured (empty map ⇒ both sides resolve the default
                // ⇒ equal ⇒ no push ⇒ byte-identical to pre-feature behavior). Gated on
                // CURRENT because dialectOf defaults untracked ids to CURRENT.
                int newDist = lodDistanceFor(player);
                int prevDist = serviceConfig.lodDistanceForWorld(
                        prevDim == null ? null : prevDim.identifier().toString());
                if (newDist != prevDist && this.dialects.isCurrent(player.getUUID())) {
                    try {
                        boolean denyMapIngestion = denyMapIngestionFor(player, config);
                        this.sessionConfigPusher.push(player,
                                SessionConfigS2CPayload.forSession(
                                        LSSConstants.PROTOCOL_VERSION,
                                        // 0 = no LOD service in this world: the client parks
                                        // its session until a served world re-enables it.
                                        config.enabled() && newDist > 0,
                                        newDist,
                                        generationEnabledForSession(),
                                        net.minecraft.SharedConstants.getCurrentVersion()
                                                .dataVersion().version(),
                                        // Map-policy plan A2: every Nether trip at stock
                                        // settings pushes — the flags must ride it.
                                        capabilities, denyMapIngestion));
                        // The last-sent flags live on the gate state, not on this
                        // per-dimension request state (map-ingestion permission plan §3.3).
                        this.serviceGateState.noteMapFlagsSent(player.getUUID(),
                                LSSConstants.PROTOCOL_VERSION, capabilities, denyMapIngestion);
                    } catch (Exception e) {
                        LSSLogger.error("Session-config dimension-change push failed for "
                                + player.getName().getString(), e);
                    }
                }
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
                    ? genReadyPositions.get(state.registration()) : null;
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
        for (var state : this.players.values()) {
            if (!state.hasCompletedHandshake()) continue;
            var player = state.getPlayer();
            int maxDist = dev.vox.lss.common.config.ServerConfigBase.requestRadius(lodDistanceFor(player));
            this.v16Compat.tickPlayer(player.getUUID(), state,
                    player.chunkPosition().x(), player.chunkPosition().z(), maxDist);
        }
    }

    private void postSnapshot(LifecycleResult lifecycle,
                               List<TickSnapshot.GenerationReadyData> generationReady,
                               LSSServerConfig config) {
        this.offThreadProcessor.postSnapshot(
                lifecycle.buffers.toSnapshot(config.sendQueueLimitPerPlayer()), generationReady);
    }

    /** Test seam (D9): puts one column payload on the wire for one player. Production default
     *  is {@code LoaderServices.sendToPlayer}; ServiceGlueTest injects recording/throwing senders. */
    @FunctionalInterface
    interface ColumnPayloadSender {
        void send(PlayerRequestState state, CustomPacketPayload payload) throws Exception;
    }

    private void flushSendQueues(int activeCount, LSSServerConfig config) {
        long perPlayerAllocation = this.bandwidthLimiter.getPerPlayerAllocation(activeCount);
        long perPlayerCap = Math.min(perPlayerAllocation, config.bytesPerSecondPerPlayer());
        // The ping backstop's observe pass (adaptive-transfer-rate-plan.md Mechanism
        // B): one keepalive-latency read per player per tick on the pump; the factor
        // is APPLIED inside the flush loop below so it rides allocationBytes into the
        // bandwidth bucket's bank clamp (the m12 plumbing). Disabled = factors reset,
        // so a live kill-switch flip cannot leave a stale cut behind.
        if (config.enablePingBackstop()) {
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
        flushSendQueues(this.players.values(), perPlayerCap, this.bandwidthLimiter, this.diag,
                this::sendColumnPayload, this.offThreadProcessor,
                config.lodYieldsToVanillaTransport(),
                // The prune is the YIELD's companion (§2.1 — long queue residency is a
                // yield phenomenon) and must not ship armed under the default-FALSE
                // posture (review B-2): radius 0 disables it while the gate is off.
                config.lodYieldsToVanillaTransport()
                        ? config.maxConfiguredLodDistanceChunks() + LSSConstants.LOD_DISTANCE_BUFFER
                                + OffThreadProcessor.SWEEP_RADIUS_MARGIN_CHUNKS
                        : 0,
                config.enableSendPacing());
    }

    /** Warn-once latch for the v16 egress guard (MAIN thread only). */
    private boolean v16UnconvertibleWarned;

    /** Whether a column may be converted to a legacy (v16 OR v18) shape. Extracted so the
     *  guard's decision is pinnable: {@code sendColumnPayload} is private and needs a
     *  live server, so this — the only thing standing between a codec-1 payload and a
     *  hard-kicked legacy client — had no test on either side of the Fabric module, while
     *  Paper pins its equivalent twice. (v0.9.0 review.) Neither legacy layout has a
     *  place to carry a codec byte (v18 reads the byte after the source as the
     *  section-array length VarInt), so only RAW converts; anything else must be
     *  dropped. */
    static boolean isLegacyConvertible(dev.vox.lss.networking.payloads.VoxelColumnS2CPayload col) {
        return col.codec() == LSSConstants.COLUMN_CODEC_RAW;
    }

    /** Warn-once latch for the v18 egress guard (MAIN thread only). */
    private boolean v18UnconvertibleWarned;

    /** The per-player column egress (MAIN) — every producer (probe/disk/generation/
     *  ghost-clear/store-hit) funnels through here, so no producer can leak a
     *  wrong-dialect frame. Legacy (v19/v18/v16) sessions' BODIES are already native:
     *  the C2 translation runs at the per-recipient ENQUEUE choke point
     *  ({@code FabricOffThreadProcessor.buildAndEnqueueColumnPayload}) so every queued
     *  size — gauges, bandwidth budget, diag books, soak law A2 — matches what the
     *  legacy client decodes. This seam applies only the HEADER shapes: v16 splices to
     *  the source-less layout and prunes the synthetic want-set (satisfied-by-data; the
     *  prune is load-bearing, design §4.4), v18 strips the codec byte, v19 IS the
     *  current header. Every failure shape is a warn-once DROP (design §5): a dropped
     *  frame self-heals by re-declaration, a wrong-shaped one kicks the client. */
    private void sendColumnPayload(PlayerRequestState state, CustomPacketPayload payload)
            throws Exception {
        var uuid = state.getPlayerUUID();
        if (this.dialects.isV16(uuid)) {
            if (!(payload instanceof dev.vox.lss.networking.payloads.VoxelColumnS2CPayload col)) {
                if (!this.v16UnconvertibleWarned) {
                    this.v16UnconvertibleWarned = true;
                    LSSLogger.warn("v16-compat: dropping unconvertible column-queue payload "
                            + payload.getClass().getName() + " for " + state.getPlayerName()
                            + " (further drops are silent)");
                }
                return;
            }
            if (!isLegacyConvertible(col)) {
                // Codec-0 assert at the seam (plan review A6): the legacy layout has
                // nowhere to carry a codec, so a framed payload converted here would
                // ship a zstd body the old client decodes as garbage (hard-kick class).
                // REACHABLE in one narrow window (4-agent round, pipeline F2): an
                // established v19+0x2 session downgrading to v16 (discovery re-handshake)
                // can drain already-queued codec-1 payloads into this guard. The drop
                // self-heals by re-declaration; note it books send-success accounting
                // (bytes/wire/grace) for a payload that never shipped — bounded to the
                // downgrade instant, same shape as the unconvertible-payload drop above.
                if (!this.v16UnconvertibleWarned) {
                    this.v16UnconvertibleWarned = true;
                    LSSLogger.warn("v16-compat: dropping codec-" + col.codec()
                            + " column for v16 session " + state.getPlayerName()
                            + " — the session flag should have forced raw"
                            + " (further drops are silent)");
                }
                return;
            }
            dev.vox.lss.platform.LoaderServices.get().sendToPlayer(state.getPlayer(), col.asV16());
            this.v16Compat.onColumnSent(uuid,
                    PositionUtil.packPosition(col.chunkX(), col.chunkZ()));
            return;
        }
        if (this.dialects.isV18(uuid)) {
            // v18 egress (v18-compat design §2.6): strip the codec byte, keep the source
            // byte. No prune bookkeeping — there is no synthetic want-set; the client's
            // own re-declaration heals any drop. The RAW guard mirrors the v16 one and is
            // reachable in the same narrow window (an established zstd session's queued
            // codec-1 payloads draining across a cross-dialect re-handshake).
            if (!(payload instanceof dev.vox.lss.networking.payloads.VoxelColumnS2CPayload col)
                    || !isLegacyConvertible(col)) {
                if (!this.v18UnconvertibleWarned) {
                    this.v18UnconvertibleWarned = true;
                    // Name the actual failure shape (execution-review finding 5): a
                    // foreign payload class and a codec-1 column are different bugs.
                    String why = payload instanceof
                            dev.vox.lss.networking.payloads.VoxelColumnS2CPayload c
                            ? "codec-" + c.codec() + " column — a v18 frame has nowhere to carry a codec"
                            : "non-column payload " + payload.getClass().getName();
                    LSSLogger.warn("v18-compat: dropping unconvertible column-queue payload for "
                            + state.getPlayerName() + " (" + why + "; further drops are silent)");
                }
                return;
            }
            dev.vox.lss.platform.LoaderServices.get().sendToPlayer(state.getPlayer(), col.asV18());
            return;
        }
        dev.vox.lss.platform.LoaderServices.get().sendToPlayer(state.getPlayer(), payload);
    }

    // Package-private static: ServiceGlueTest drives this glue with hand-rolled states and an
    // unstarted processor (constructing RequestProcessingService needs a MinecraftServer).
    static void flushSendQueues(Iterable<PlayerRequestState> states, long perPlayerCap,
                                 SharedBandwidthLimiter bandwidthLimiter, TickDiagnostics diag,
                                 ColumnPayloadSender sender,
                                 FabricOffThreadProcessor offThreadProcessor) {
        flushSendQueues(states, perPlayerCap, bandwidthLimiter, diag, sender, offThreadProcessor,
                false, 0);
    }

    static void flushSendQueues(Iterable<PlayerRequestState> states, long perPlayerCap,
                                 SharedBandwidthLimiter bandwidthLimiter, TickDiagnostics diag,
                                 ColumnPayloadSender sender,
                                 FabricOffThreadProcessor offThreadProcessor,
                                 boolean yieldToTransport, int pruneRadiusChunks) {
        flushSendQueues(states, perPlayerCap, bandwidthLimiter, diag, sender,
                offThreadProcessor, yieldToTransport, pruneRadiusChunks, false);
    }

    static void flushSendQueues(Iterable<PlayerRequestState> states, long perPlayerCap,
                                 SharedBandwidthLimiter bandwidthLimiter, TickDiagnostics diag,
                                 ColumnPayloadSender sender,
                                 FabricOffThreadProcessor offThreadProcessor,
                                 boolean yieldToTransport, int pruneRadiusChunks,
                                 boolean sendPacing) {
        for (var state : states) {
            if (!state.hasCompletedHandshake()) continue;
            // pingFactor rides the ALLOCATION argument (m12): the per-player bucket
            // clamps its banked burst to allocation/4, so a cut shrinks the bank on
            // the first post-cut tick. Factor is 1.0 unless the backstop cut.
            long[] dropped = state.flushSendQueue(
                    state.getPingBackstop().apply(perPlayerCap), bandwidthLimiter, diag,
                    payload -> {
                        if (consumeSendDropFault()) return;
                        sender.send(state, payload);
                    }, yieldToTransport, pruneRadiusChunks, sendPacing);
            if (dropped.length > 0) {
                // A send failure or the relevance prune discarded resolved-but-undelivered
                // columns: clear their done-bits so the client's re-requests re-resolve
                // instead of being answered up-to-date for data that never arrived.
                offThreadProcessor.clearDiskReadDone(state.getPlayerUUID(), dropped);
            }
        }
    }

    /**
     * Fault seam: arms the flush path to silently discard the next {@code count}
     * column-payload sends. A dropped payload vanishes after resolution — the flush treats
     * it as delivered (queue advances, diskReadDone stays set, no clearDiskReadDone, and it
     * STAMPS the departure grace like any send success) — so the honest re-resolution
     * ladder (a ts&le;0 re-request of a served position re-resolves) can be exercised live
     * by the soak {@code fault send-drop N} command and the client gametests. Since the
     * duplicate-serve grace, that recovery includes up to one extra 1 Hz scan: the first
     * re-ask inside {@code SEND_DEPARTURE_GRACE_MILLIS} of the injected "success" is
     * absorbed silently; a scenario asserting on the re-resolve must budget for it. Inert in production: no production code path calls this, and arming is
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
                    config.bytesPerSecondGlobal(), this.bandwidthLimiter, this.players.values());
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
     *       batches arrive at 1-4 Hz (the client's adaptive cadence), so the mailbox alone would see null on almost every
     *       tick. It stays published exactly while the backlog is non-empty (cleared on
     *       drain-to-empty, republished by {@code restoreBacklog}), which is what carries a
     *       want-set too large for the slot cap across the cycles that work it off.</li>
     * </ul>
     * Both sources list positions that may already be routed; a probe for an already-routed
     * position is simply unused by the router, and the cost is bounded by
     * {@link #MAX_PROBES_PER_TICK_PER_PLAYER}, which the declaration-order drain spends on the
     * head of the want-set. Positions whose payload sits in the send pipeline, left it
     * within the probe-suppress TTL (send success), or were answered up_to_date are
     * filtered below (review P1 — the enqueued-only filter used to re-serialize each
     * served or answered head EVERY tick until the next declaration dropped it, ~1-2 ms/tick
     * bursts for up to a second per warm-rejoin/teleport episode; the D4 "one re-probe"
     * claim this javadoc used to make was wrong). The residual is one probe per position
     * in the sub-tick window between a processing-thread up_to_date resolution and the
     * next main-thread action drain.
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
            // Served-head filter: a payload in the send pipeline, recently sent, or just
            // answered up_to_date means the router resolves this position without the
            // probe — the serialization is guaranteed-unused, and under backlog retention
            // the published want-set re-lists it every tick until the next declaration
            // (review P1: the enqueued-only filter left served/answered heads
            // re-serializing for up to a second). Both structures are any-thread safe
            // (pendingByPosition/diskReadDone are single-threaded and stay unconsulted).
            if (state.skipProbe(packed)) continue;

            LevelChunk chunk = level.getChunkSource().getChunkNow(req.cx(), req.cz());
            if (chunk != null) {
                // Deliberately NOT seeded into the DirtyContentFilter: a probe serve can land
                // between another player's edit and the chunk's cooldown save, and a seed here
                // would make that save hash equal — silencing the dirty broadcast every OTHER
                // client holding the old column needs. Only generation serves seed (freshly
                // generated content cannot be stale-held by anyone).
                var data = serializeCapturedProbe(
                        () -> this.offThreadProcessor.captureLoadedProbe(level.dimension().identifier().toString(), packed, state.registration()), SectionSerializer::serializeColumn,
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

    /** Production capture seam: the token is acquired before the serializer, including reentrant invalidation. */
    static LoadedColumnData serializeCapturedProbe(
            java.util.function.Supplier<dev.vox.lss.common.processing.LoadedProbeGuard.Capture> capture,
            ProbeColumnSerializer serializer, ServerLevel level, LevelChunk chunk,
            int cx, int cz, LogThrottle warnThrottle) {
        return serializeProbeContained((l,c,x,z) -> {
            var before = capture.get();
            return before.bind(serializer.serialize(l,c,x,z));
        }, level, chunk, cx, cz, warnThrottle);
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
            if (state == null || state.registration() != req.registration()
                    || req.registration().isRetired() || !state.hasCompletedHandshake()) continue;

            var player = state.getPlayer();
            var level = player.level();
            String dimension = this.dimensionStringCache.computeIfAbsent(level.dimension(),
                    k -> k.identifier().toString());
            // Ticket queued before a dimension change targets the old dimension's coordinates.
            // Dropping it leaks nothing: the admitting state was discarded by
            // removePlayer+registerPlayer (its slot dies with it), AND that same removePlayer
            // enqueues the removal event that sweeps the processing thread's registration-keyed
            // generation in-flight tracking (removeGenerationTracking) — without that sweep the
            // dropped ticket's tracking would leak (do not add a drop path that skips it).
            if (!dimension.equals(req.dimension())) continue;
            boolean accepted = req.policyRevision() == this.settingsRevision
                    && !player.isRemoved() && this.generationService.submitGeneration(
                    req.playerUuid(), req.registration(), level, req.cx(), req.cz(),
                    req.submissionOrder());
            if (!accepted) {
                // Capacity rejection or removed player — TRANSIENT: feed a transient outcome
                // so the processing thread frees the pending slot silently (superseded); the
                // client's re-declaration retries. Never NOT_GENERATED (session-permanent).
                this.offThreadProcessor.feedGenerationFailure(
                        req.playerUuid(), req.registration(), req.cx(), req.cz(), dimension, req.submissionOrder(), true);
            }
        }
    }

    private void drainSendActions() {
        if (generationRefreshPending) return;
        this.offThreadProcessor.drainSendActions((state, types, positions, count) -> {
            // v16 observation: UP_TO_DATE / NOT_GENERATED terminally answer their positions —
            // prune them from the synthetic want-set. The frame itself is wire-identical.
            this.v16Compat.observeBatchResponse(state.getPlayerUUID(), types, positions, count);
            dev.vox.lss.platform.LoaderServices.get().sendToPlayer(state.getPlayer(),
                    new BatchResponseS2CPayload(types, positions, count));
        }, new dev.vox.lss.common.processing.OffThreadProcessor.StampsSink<>() {
            // Stamped up_to_date (plan §3): the summary request is the eligibility
            // declaration; frames are fire-and-forget (loss = today's behavior, heals
            // on the next rejoin) and counted only on a completed send call.
            @Override public boolean eligible(UUID uuid) {
                // CURRENT dialect conjunct (3-Opus fold, plan §9.4): a session that
                // re-handshakes DOWN to a legacy dialect keeps its request mark until
                // disconnect — the far-player subscription discipline drops on the
                // re-handshake, so this lane must too.
                return RequestProcessingService.this.dialects.dialectOf(uuid)
                        == dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT
                        && RequestProcessingService.this.regionSummaries.hasRequestedThisSession(uuid);
            }
            @Override public void send(PlayerRequestState state, byte[] frame, int entries) {
                // Writability drop (3-Opus fold, all three lenses): the frames land at
                // exactly the join/portal moment the yield/pacing machinery protects,
                // and this is the only raw-body S2C lane without a pressure consult.
                // A plain UNCOUNTED drop, never RETRY — loss is the designed-tolerant
                // case (the positions re-stamp on the next rejoin).
                var snap = FabricChannelPressure.forPlayer(state.getPlayer()).snapshot();
                if (snap.writable() == dev.vox.lss.common.processing.ChannelPressureProbe
                        .Writability.NOT_WRITABLE) {
                    return;
                }
                dev.vox.lss.platform.LoaderServices.get().sendToPlayer(state.getPlayer(),
                        new dev.vox.lss.networking.payloads.ColumnStampsS2CPayload(frame));
                RequestProcessingService.this.regionSummaries.diagnostics()
                        .recordStampsFrame(entries, frame.length);
            }
        });
    }

    public Map<UUID, PlayerRequestState> getPlayers() {
        return Collections.unmodifiableMap(this.players);
    }

    public V16CompatManager getV16CompatManager() {
        return this.v16Compat;
    }

    public WireDialectTracker getDialectTracker() {
        return this.dialects;
    }

    public dev.vox.lss.common.farplayers.FarPlayerBroadcastService getFarPlayerService() {
        return this.farPlayerService;
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

    private record RegistryIdentity(java.util.List<String> states,
                                    java.util.List<String> biomes) {}

    /** Registry identity for the LOD store meta guard (4-agent round R2-M3): stored
     *  wire bytes embed GLOBAL block-state ids and biome ids, both assignment-order
     *  dependent — a mod or datapack change shifts them while region files stay
     *  untouched, so only the fingerprints derived from THIS walk can trigger the
     *  rebuild. Both identity lists are id-ordered (review A3: the old block half was
     *  a bare COUNT, so an id-permuting registry change of identical total size — a
     *  mod swap landing on the same state count — served every warm column as the
     *  wrong blocks with no self-heal; state iteration order of BLOCK_STATE_REGISTRY
     *  IS global-id order, and BlockState toString carries block id + property
     *  values); the store construction derives the ordered {@code of} AND
     *  order-insensitive {@code contentOf} (v0.13.1 permutation plan §3.2)
     *  fingerprints from ONE walk. Textual twin in PaperRequestProcessingService;
     *  the call shape is pinned by StoreEnvironmentContractTest. */
    private static RegistryIdentity storeRegistryIdentity(MinecraftServer server) {
        var states = new java.util.ArrayList<String>();
        for (var state : net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY) {
            states.add(String.valueOf(state));
        }
        var biomeKeys = new java.util.ArrayList<String>();
        var biomes = server.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
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

    /** One-way latch: set by the first {@link #registerPlayer} this session (review P3 —
     *  the save hook skips the dirty-content hash while it is false AND the store is off
     *  AND the persisted timestamp cache booted empty: with no session ever started ON
     *  ANY BOOT, nothing the hash maintains is observable). */
    private volatile boolean everRegisteredPlayer;

    public boolean hasEverRegisteredPlayer() {
        return this.everRegisteredPlayer;
    }

    /** The P3 gate's third conjunct (three-lens review, correctness MAJOR): the timestamp
     *  cache PERSISTS across restarts ({@code <world>/data/lss-timestamps.bin}), so a
     *  server that served LSS clients last session boots with stamps a pre-first-join
     *  edit must invalidate — skipping the hash there would answer a warm rejoin
     *  up_to_date for pre-edit terrain. Captured once at construction, right after the
     *  cache load; only serves populate the cache afterwards, and serves imply the
     *  registration latch flipped first. */
    private final boolean timestampCacheBootedEmpty;

    public boolean timestampCacheBootedEmpty() {
        return this.timestampCacheBootedEmpty;
    }

    /** TEST-ONLY (Tier 2): arm the save-hook latch without a handshake. The fan-out
     *  gametest registers its mock players on its own service instance but asserts the
     *  save hook through the LIVE service's filter/tracker — without arming, the P3 skip
     *  gate would red that assertion (plan review finding 1). One-way, so arming cannot
     *  invalidate other tests: the skip is an optimization no Tier 2 test pins. */
    public void armSaveHookForTest() {
        this.everRegisteredPlayer = true;
    }

    public DirtyColumnTracker getDirtyTracker() {
        return this.dirtyTracker;
    }

    /** The region freshness stamp table (P1 header rung; P2 summary sweeper). */
    public dev.vox.lss.common.region.RegionStampTable getRegionStamps() {
        return this.regionStamps;
    }

    /** Phase 5 ops (/lsslod store invalidate all): drop every stored row (batcher-side,
     *  tombstoned). The tscache is deliberately untouched: its stamps describe REGION
     *  truth, not store contents — re-asks re-resolve via tscache/probe/NBT as normal
     *  and re-warm the store. Only meaningful for the persistent store. */
    public boolean invalidateStoreAllDimensions() {
        if (this.lodStore instanceof dev.vox.lss.common.store.SqliteLodStore sqlite) {
            sqlite.requestDropAllRows();
            return true;
        }
        return false;
    }

    public dev.vox.lss.common.store.StoreBackfill getStoreBackfill() {
        return this.storeBackfill;
    }

    public DirtyContentFilter getDirtyContentFilter() {
        return this.dirtyContentFilter;
    }

    public String getTickDiagnostics() {
        return this.diag.format(serviceConfig.sendQueueLimitPerPlayer());
    }

    public TickDiagnostics getTickDiag() {
        return this.diag;
    }

    public long getWindowBandwidthRate() {
        return this.diag.getWindowBytesPerSecond();
    }

    public void shutdown() {
        cancelSettingsReceipts();
        this.serviceGateState.clear();
        try {
            // Own containment, FIRST (P2 review I-m2): no ordering dependency on the
            // dirty drain, and a throw there must not leak the sweeper daemon (which
            // holds the stamp table's header snapshots) across /reload or world swaps.
            this.regionSummaries.shutdown();
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
            if (this.storeBackfill != null) this.storeBackfill.shutdown();
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
        XrayMaskManager.deactivate(this.xrayMasks);
    }
}
