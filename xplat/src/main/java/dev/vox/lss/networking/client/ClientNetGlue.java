package dev.vox.lss.networking.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.SoakDialectOverride;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import dev.vox.lss.networking.payloads.ZstdWireSupport;
import dev.vox.lss.platform.LoaderServices;
import dev.vox.lss.seed.ClientWorldSeed;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * The loader-neutral CLIENT session glue (N-3, neoforge-support-plan.md §1.1 —
 * the server side's {@code ServerReceiverGlue} twin): the session state
 * ({@code ClientSessionGate} + {@code ClientColumnProcessor} statics), every
 * receiver body, and the lifecycle ladders, extracted VERBATIM from the Fabric
 * {@code LSSClientNetworking} (which keeps delegating statics + the event and
 * receiver REGISTRATION — the per-loader part). Sends flow through
 * {@link LoaderServices#sendToServer}.
 */
public final class ClientNetGlue {

    private ClientNetGlue() {
    }

    // Log-sweep hygiene (2026-08-13): per-column/per-frame failure sites aggregate to
    // one line/min — a persistent condition must not flood the client log.
    private static final dev.vox.lss.common.LogThrottle UNKNOWN_TYPE_WARN =
            new dev.vox.lss.common.LogThrottle(60_000);
    private static final ClientColumnProcessor columnProcessor = new ClientColumnProcessor();

    // Session state and the JOIN / SessionConfig / DISCONNECT ladders live in the gate
    // (unit-testable); this class wires the production seams — the real handshake send
    // and manager construction with live server-address resolution.
    private static final ClientSessionGate sessionGate = new ClientSessionGate(
            columnProcessor,
            version -> {
                LoaderServices.get().sendToServer(new HandshakeC2SPayload(
                        version, handshakeCapabilities()));
                FarPlayerClientSupport.onHandshakeSent();
                sendClientInfoSidecar();
            },
            ClientNetGlue::createRequestManager);
    static {
        sessionGate.installChannelProbe(DhClientSession::nativeChannelPresent);
    }

    /**
     * The ONE capability mask every handshake send declares (map-policy plan A2): the
     * join/ladder announce above and the LAN-host self-handshake. The map-policy bit is
     * unconditional (with no map mod, honoring the request is trivial) and survives soak
     * and benchmark JVMs (harness clients need it — unlike the far-player bit's property
     * zeroing); the soak lever's v19 emulation strips the bits a real v19 client never
     * declared ({@link SoakDialectOverride#stripCapabilities}).
     */
    static int handshakeCapabilities() {
        return handshakeCapabilitiesFor(ZstdWireSupport.capabilityBit(),
                FarPlayerClientSupport.capabilityBit(), SoakDialectOverride.isV19());
    }

    /** Pure form of {@link #handshakeCapabilities} for the Tier 1 pins. */
    static int handshakeCapabilitiesFor(int zstdBit, int farPlayersBit, boolean v19Emulation) {
        return SoakDialectOverride.stripCapabilitiesFor(v19Emulation,
                LSSConstants.CAPABILITY_VOXEL_COLUMNS | zstdBit | farPlayersBit
                        | LSSConstants.CAPABILITY_MAP_POLICY);
    }

    /** The lss:client_info sidecar rides beside every announce (XVER §2.2): the
     *  handshake shape is frozen, so the client's data version travels on its own
     *  channel. Best-effort — legacy servers discard the unregistered channel, and a
     *  send failure must never take the announce down with it. */
    private static void sendClientInfoSidecar() {
        // A real protocol-19 client has no lss:client_info channel — the soak harness's
        // legacy-dialect emulation must not send one either (C2 lever fidelity).
        if (SoakDialectOverride.isV19()) return;
        try {
            LoaderServices.get().sendToServer(new dev.vox.lss.networking.payloads.ClientInfoC2SPayload(
                    net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version()));
        } catch (Exception e) {
            LSSLogger.debug("client_info sidecar send failed: " + e.getMessage());
        }
    }

    public static boolean isServerEnabled() {
        return sessionGate.isServerEnabled();
    }

    /** Why the server answered "not here", as one {@code SESSION_FLAG_OFF_*} bit; 0 = not said. */
    public static int serverOffReason() {
        return sessionGate.serverOffReason();
    }

    /** Whether the connected server allows LOD map ingestion (map-policy plan A4): the map
     *  backends' write gate ({@code serverAllowsWrites}), the status availability and the
     *  options tooltip. True with no connection and on every server without the policy. */
    public static boolean serverAllowsMapIngestion() {
        return sessionGate.serverAllowsMapIngestion();
    }

    /** C6 observability: the established session's protocol version (0 pre-config). */
    public static dev.vox.lss.common.diagnostics.ClientStatusSnapshot.Discovery discoveryStatus() {
        return sessionGate.discoveryStatus();
    }

    public static int getSessionVersion() {
        return sessionGate.getSessionVersion();
    }

    /** The protocol number the status shows: the session's, or the one a rejected server sent. */
    public static int statusProtocol() {
        return sessionGate.statusProtocol();
    }

    public static boolean hasReceivedSessionConfig() {
        return sessionGate.hasReceivedSessionConfig();
    }

    public static int getServerLodDistance() {
        return sessionGate.getServerLodDistance();
    }

    public static long getColumnsReceived() {
        return sessionGate.getColumnsReceived();
    }

    public static long getBytesReceived() {
        return sessionGate.getBytesReceived();
    }

    public static long getWireBytesReceived() {
        return sessionGate.getWireBytesReceived();
    }

    public static long getColumnsDropped() {
        return columnProcessor.getColumnsDropped();
    }

    /** Columns given an estimated sky light this session (they arrived without any). */
    public static long getSkyEstimatedColumns() {
        return columnProcessor.getSkyEstimatedColumns();
    }

    /** Columns whose sky-light estimate failed this session (delivered as they arrived). */
    public static long getSkyEstimateFailures() {
        return columnProcessor.getSkyEstimateFailures();
    }

    public static long getConnectionStartMs() {
        return sessionGate.getConnectionStartMs();
    }

    public static LodRequestManager getRequestManager() {
        return sessionGate.getRequestManager();
    }

    public static int getQueuedColumnCount() {
        return columnProcessor.getQueuedCount();
    }

    public static long getQueuedColumnBytes() {
        return columnProcessor.getQueuedBytes();
    }

    /**
     * Report a delivered-but-not-ingested column (decode failure or consumer rejection
     * via {@link LSSApi#reportIngestFailure}). Hops to the main thread, where the manager
     * forgets the received-stamp and schedules a re-request. Safe from any thread.
     */
    public static void reportIngestFailure(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        var owner = sessionGate.getRequestManager();
        var mc = Minecraft.getInstance();
        if (mc == null) return; // unit tests / very early startup — no session to repair anyway
        mc.execute(() -> {
            var manager = sessionGate.getRequestManager();
            if (manager != null && manager == owner && manager.isAcquisitionActive()) {
                manager.onIngestFailure(dimension, PositionUtil.packPosition(chunkX, chunkZ));
            }
        });
    }

    static void executeDeliveryEvent(Runnable event) {
        var mc = Minecraft.getInstance();
        if (mc != null) mc.execute(event);
    }

    /**
     * Drain the decode queue and unstamp every undispatched column via the manager —
     * called before any cache persistence (disconnect, dimension change) so stamps for
     * never-ingested data cannot outlive the session state that recorded them.
     */
    public static void reportUndispatchedColumns(LodRequestManager manager) {
        columnProcessor.reportUndispatched(manager);
    }

    /** Bounded wait for the in-flight decode drain — the /lss reset sequence's step-1
     *  await (see {@link ClientColumnProcessor#awaitDecodeIdle}). */
    public static boolean awaitDecodeIdle(long timeoutMs) {
        return columnProcessor.awaitDecodeIdle(timeoutMs);
    }

    /** The LAN host self-handshake needs a consumer other than the map backends. */
    static boolean hostHandshakeHasConsumer() {
        return dev.vox.lss.compat.ModCompat.hasNonMapConsumers();
    }

    public static void triggerHostHandshake() {
        Minecraft.getInstance().execute(() -> {
            sessionGate.onHostServiceReady(); // remember publication even when downloads are OFF
            if (!LSSClientConfig.CONFIG.receiveServerLods()) return;
            if (sessionGate.getRequestManager() != null) return;
            // No LOD consumer -> stay silent. Map ingestion alone is multiplayer-only: a host
            // with only a map mod must not generate a LOD disc around itself (review).
            if (!hostHandshakeHasConsumer()) return;
            try {
                LoaderServices.get().sendToServer(new HandshakeC2SPayload(
                        LSSConstants.PROTOCOL_VERSION, handshakeCapabilities()));
                FarPlayerClientSupport.onHandshakeSent();
                sendClientInfoSidecar();
            } catch (Exception e) {
                LSSLogger.debug("LAN host handshake send failed: " + e.getMessage());
            }
        });
    }

    /**
     * Production {@link ClientSessionGate.ManagerFactory}: builds the per-session manager
     * and resolves the TWO-AXIS cache partition key from the live client
     * (cache-alias-keying-and-reset-override-plan.md §2.1).
     *
     * <p>The ADDRESS axis (multiplayer ip → LAN/local world dir → unknown, aliased to a
     * corroborated {@code cacheAddressAliases} canonical where one applies) is decided
     * ONCE per play session ({@link AliasLatch} — reset at JOIN, reused across manager
     * rebuilds). The WORLD axis (the {@code .world-<16 hex>} sub-bucket) is deliberately
     * NOT decided here: the manager re-derives it from the live level at every build and
     * dimension/cache-phase entry, because per-world seeds arrive via respawn packets and
     * a latched sub-key would be COARSER than Voxy across backend switches and
     * multi-world rotations (§9 M-A1/M-B1).
     *
     * <p>This stays the SINGLE assignment point for the key inputs: every one of the
     * store's entries reads the manager's one composed bucket string, so the axes can
     * never half-apply.
     */
    private static LodRequestManager createRequestManager(SessionConfigS2CPayload payload) {
        var manager = new LodRequestManager();
        var mc = Minecraft.getInstance();
        String serverAddr;
        boolean remote = false;
        var serverData = mc.getCurrentServer();
        var spServer = mc.getSingleplayerServer();
        if (serverData != null && serverData.ip != null) {
            serverAddr = serverData.ip;
            remote = true;
        } else if (spServer != null) {
            var worldDir = spServer.getWorldPath(LevelResource.ROOT).getFileName();
            serverAddr = "local:" + (worldDir != null ? worldDir : "world");
        } else {
            serverAddr = "unknown";
        }
        AliasLatch.Decision decision = remote
                ? AliasLatch.forConnection(serverAddr,
                        () -> computeAliasDecision(serverAddr))
                : unaliasedDecision(serverAddr, "not-remote");
        manager.onSessionConfig(payload, decision.addressComponent());
        manager.configureCacheKeying(decision.addressComponent(), decision.sweepComponents(),
                decision.aliased() ? "alias group" : "address", ClientWorldSeed::context);
        LSSLogger.info("Column cache " + manager.describeCacheKey()
                + " [alias: " + decision.token() + "]");
        // Tier B v16 backward-compat: only a v16 session reaches here with protocolVersion() == 16
        // (the gate rejects a v16 config outright when enableV16ServerCompat is off, before the
        // factory runs), so this is the single place that combines "v16 session" with the client
        // opt-in. A v18 session leaves it false and the egress byte-identical.
        manager.setV16GenerationDrive(shouldDriveV16Generation(
                payload.protocolVersion(), LSSClientConfig.CONFIG.enableV16Generation()));
        return manager;
    }

    /** A latch-shaped decision for the shapes the alias axis never applies to. */
    private static AliasLatch.Decision unaliasedDecision(String connectAddr, String token) {
        String component = CacheKeyAliases.addressComponent(connectAddr);
        return new AliasLatch.Decision(connectAddr, component, List.of(component), false, token);
    }

    /**
     * The once-per-session alias decision (plan §2.2): match the validated config
     * groups, then run the corroboration guard. A matched group defines the reset
     * SWEEP either way ("wipe this server" means every spelling the user declared);
     * only a corroborated match moves the BUCKET to the canonical.
     */
    static java.util.Collection<String> dhCacheSweep(String serverAddress) {
        return computeAliasDecision(serverAddress).sweepComponents();
    }

    private static AliasLatch.Decision computeAliasDecision(String connectAddr) {
        // The field was validated at config load (the user's field is deliberately NEVER
        // rewritten — the §11 fold: a re-save must not erase dropped groups), so
        // this re-parse is silent — a warn sink here would double-log every session.
        var groups = CacheKeyAliases.validated(
                LSSClientConfig.CONFIG.cacheAddressAliases(), warn -> {});
        var group = CacheKeyAliases.match(groups, connectAddr);
        if (group == null) {
            return unaliasedDecision(connectAddr, "no-group");
        }
        // The Xaero gate is evaluated first inside the ladder, so the Voxy probe (a
        // reflective read with its own log line) is skipped when its answer would be
        // discarded anyway.
        boolean mapStoreArmed = dev.vox.lss.compat.ModCompat.isMapStoreArmed();
        var result = AliasCorroboration.evaluate(
                dev.vox.lss.compat.ModCompat.isVoxyBridgeActive(),
                mapStoreArmed,
                mapStoreArmed ? null : dev.vox.lss.compat.ModCompat.observeVoxyStorageDirName(),
                connectAddr, group.canonicalRaw());
        if (result.warn() != null) {
            LSSLogger.warn(result.warn());
        }
        var sweep = new LinkedHashSet<String>();
        for (String member : group.membersRaw()) {
            sweep.add(CacheKeyAliases.addressComponent(member));
        }
        String connectComponent = CacheKeyAliases.addressComponent(connectAddr);
        sweep.add(connectComponent);
        boolean applied = result.outcome() == AliasCorroboration.Outcome.APPLY;
        String component = applied
                ? CacheKeyAliases.addressComponent(group.canonicalRaw()) : connectComponent;
        return new AliasLatch.Decision(connectAddr, component,
                List.copyOf(sweep), applied, result.token());
    }

    /**
     * Tier B decision: drive on-demand generation on the server only for a genuine v16 session
     * AND when the client has opted in. Pure so the truth table is unit-testable
     * (v18 → false regardless of the opt-in; v16 → the opt-in).
     */
    public static boolean shouldDriveV16Generation(int protocolVersion, boolean generationOptIn) {
        return protocolVersion == LSSConstants.V16_COMPAT_PROTOCOL_VERSION && generationOptIn;
    }

    // ---- receiver bodies (each loader's registration calls these) ----

    /** SessionConfig receiver body — hops to the client main thread. */
    public static void onSessionConfigFrame(SessionConfigS2CPayload payload) {
        Object connection = FarPlayerClientSupport.connectionIdentity();
        Minecraft.getInstance().execute(() -> {
            if (connection != FarPlayerClientSupport.connectionIdentity()) return;
            DhClientSession.nativeOffer();
            reconcileClientConfig();
            var previous = sessionGate.getRequestManager();
            sessionGate.onSessionConfig(payload, LSSApi.hasVoxelConsumers(),
                    LSSClientConfig.CONFIG.enableV16ServerCompat(),
                    dev.vox.lss.compat.ModCompat.isMapOnlyIngestion());
            if (previous != null && previous != sessionGate.getRequestManager()) {
                dev.vox.lss.compat.ModCompat.retireClientAcquisition();
            }
            // Far players (E1): the prefs frame follows the session config
            // (send-once-unless-changed, contained; no-op while the capability bit is
            // not composed — all of E1).
            FarPlayerClientSupport.onSessionReady();
        });
    }

    /** BatchResponse receiver body — hops to the client main thread. */
    public static void onBatchResponseFrame(BatchResponseS2CPayload payload) {
        var owner = sessionGate.getRequestManager();
        Minecraft.getInstance().execute(() -> {
            var manager = sessionGate.getRequestManager();
            if (manager == null || manager != owner || !manager.isAcquisitionActive()) return;
            dispatchBatchResponses(manager, payload);
        });
    }

    /** DirtyColumns receiver body — hops to the client main thread. */
    public static void onDirtyColumnsFrame(DirtyColumnsS2CPayload payload) {
        var owner = sessionGate.getRequestManager();
        Minecraft.getInstance().execute(() -> {
            var manager = sessionGate.getRequestManager();
            if (manager != null && manager == owner && manager.isAcquisitionActive()) {
                manager.onDirtyColumns(payload.dirtyPositions());
            }
        });
    }

    /** Far-player roster receiver body — hops to the client main thread. */
    public static void onFarPlayerRosterFrame(byte[] body) {
        Object connection = FarPlayerClientSupport.connectionIdentity();
        Minecraft.getInstance().execute(() -> {
            if (connection == FarPlayerClientSupport.connectionIdentity()) FarPlayerClientSupport.onRosterFrame(body);
        });
    }

    /** Far-player updates receiver body — hops to the client main thread. */
    public static void onFarPlayerUpdatesFrame(byte[] body) {
        Object connection = FarPlayerClientSupport.connectionIdentity();
        Minecraft.getInstance().execute(() -> {
            if (connection == FarPlayerClientSupport.connectionIdentity()) FarPlayerClientSupport.onUpdatesFrame(body);
        });
    }

    /** Region-summary receiver body (region-summary-sync-plan.md §6) — hops to the
     *  client main thread; the manager applies per-column validation (or buffers the
     *  frame until its cache load adopts). */
    public static void onRegionSummaryFrame(byte[] body) {
        var owner = sessionGate.getRequestManager();
        Minecraft.getInstance().execute(() -> {
            var manager = sessionGate.getRequestManager();
            if (manager != null && manager == owner && manager.isAcquisitionActive()) {
                manager.onRegionSummaryFrame(body);
            }
        });
    }

    /** Column-stamps receiver body (stamped-up-to-date-plan.md §4) — same hop; the
     *  manager ratchets cached stamps forward for verified-current columns. */
    public static void onColumnStampsFrame(byte[] body) {
        var owner = sessionGate.getRequestManager();
        Minecraft.getInstance().execute(() -> {
            var manager = sessionGate.getRequestManager();
            if (manager != null && manager == owner && manager.isAcquisitionActive()) {
                manager.onColumnStamps(body);
            }
        });
    }

    /** VoxelColumn receiver body: the wire counters record on the RECEIVING thread
     *  (network thread on Fabric, main on NeoForge's executesOn(MAIN) — counter timing
     *  only, no ordering dependency), then the ladder runs on the client main thread. */
    public static void onVoxelColumnFrame(VoxelColumnS2CPayload payload) {
        sessionGate.recordColumnFrame(payload.estimatedBytes(), payload.wireEstimatedBytes());
        var owner = sessionGate.getRequestManager();
        Minecraft.getInstance().execute(() -> {
            reconcileClientConfig();
            if (owner != null && owner == sessionGate.getRequestManager()
                    && owner.isAcquisitionActive() && sessionGate.isReceptionEnabled()) {
                handleVoxelColumn(owner, columnProcessor, payload);
            }
        });
    }

    /**
     * VoxelColumn receive glue (main client thread). Ordering is load-bearing: "did the
     * client already hold data here" must be captured BEFORE {@code onColumnReceived}
     * stamps the position — a resync must air-fill absent sections to clear ghost terrain.
     * A 0-section column is an authoritative content→air clear REGARDLESS of the held
     * check: the server only sends it to data-claiming clients, so heldContentBefore==false
     * here means the stamp was dropped moments earlier (an ingest-failure report racing the
     * delivery). Treating that as a plain first serve would dispatch zero sections with no
     * air-fill yet stamp ts&gt;0 — a validated hole that up_to_date pins for the session.
     * Public so each loader's tests can pin the ladder without a network receiver.
     */
    public static void handleVoxelColumn(LodRequestManager manager, ClientColumnProcessor processor,
                                         VoxelColumnS2CPayload payload) {
        if (manager != null && !manager.isAcquisitionActive()) return;
        long packed = PositionUtil.packPosition(payload.chunkX(), payload.chunkZ());
        boolean resync = manager != null && manager.heldContentBefore(packed);
        // Codec-gated (plan §0.8): only raw bytes can be varint-peeked here. A compliant
        // server always ships clears raw (1-byte body, far below the compress threshold);
        // a compressed "clear" from a non-compliant server reads as not-a-clear —
        // fail-safe, it still decodes correctly at the drain.
        boolean clear = payload.codec() == LSSConstants.COLUMN_CODEC_RAW
                && ClientColumnProcessor.isClearColumn(payload.shippedSections());
        long preClearStamp = manager != null ? manager.contentStampBeforeDelivery(packed) : -1L;
        if (manager != null && !manager.onColumnReceived(packed, payload.columnTimestamp(),
                payload.dimension(), clear, payload.source())) {
            // Out-of-range unsolicited drop: the state map refused the stamp, and the
            // consumers must not ingest it either — a hostile/buggy server could otherwise
            // grow the LOD store without bound while /lss diag shows nothing tracked.
            return;
        }
        // A clear air-fills even when the held check missed (see above) — the consumer must
        // overwrite whatever it renders there with air.
        processor.offer(payload, resync || clear,
                manager == null ? null : manager.trackDelivery(payload.dimension(), packed, preClearStamp));
    }

    /**
     * Routes each batch entry to its per-type manager callback. An unknown responseType
     * skips that entry only, never the rest of the batch (forward compat with newer
     * servers). That same skip covers the RETIRED byte 0 (v16's rate-limited bounce): a
     * pre-v17 server never gets this far (the handshake gate rejects the version mismatch),
     * but the inert skip is what makes byte 0 safe to leave reserved forever.
     * Public so each loader's tests can exercise it without a network receiver.
     */
    public static void dispatchBatchResponses(LodRequestManager manager, BatchResponseS2CPayload payload) {
        for (int i = 0; i < payload.count(); i++) {
            long packed = payload.packedPositions()[i];
            byte type = payload.responseTypes()[i];
            switch (type) {
                case LSSConstants.RESPONSE_UP_TO_DATE -> manager.onColumnUpToDate(packed);
                case LSSConstants.RESPONSE_NOT_GENERATED -> manager.onColumnNotGenerated(packed);
                case LSSConstants.RESPONSE_RATE_LIMITED_V16 ->
                        // A v16 server's soft back-off bounce (retired byte 0). The position
                        // stays unsatisfied and is re-declared on the next scan — the v18
                        // self-heal that approximates v16's ~1 s retry. Debug, not warn: on a
                        // v16 session this is routine, and a v18 server never sends it.
                        LSSLogger.debug("v16 rate-limited position " + packed + " (re-declared next scan)");
                default -> {
                    // Throttled (log sweep): this sits INSIDE the per-element batch loop —
                    // protocol drift would emit hundreds of lines per second bare.
                    long n = UNKNOWN_TYPE_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                    if (n > 0) {
                        LSSLogger.warn("Unknown batch response type: " + type + " (" + n
                                + " unknown element(s) since the last report)");
                    }
                }
            }
        }
    }

    // ---- lifecycle bodies ----

    /** JOIN ladder body (each loader's connection-join event calls this). */
    public static void onJoin() {
        DhClientSession.onJoin();
        LSSClientConfig.CONFIG.beginConnection(FarPlayerClientSupport.connectionIdentity());
        ClientStatus.invalidate();
        // Don't activate on singleplayer/integrated servers (unless testing)
        boolean localIntegratedServer = Minecraft.getInstance().hasSingleplayerServer()
                && !Boolean.getBoolean("lss.test.integratedServer");
        sessionGate.onJoin(LSSClientConfig.CONFIG.receiveServerLods(), localIntegratedServer,
                LSSApi.hasVoxelConsumers(), LSSClientConfig.CONFIG.enableV16ServerCompat(),
                LSSClientConfig.CONFIG.enableV19ServerCompat());
        ClientNotices.onJoin();
        // After the gate: a handshake it sent has already reset the delivery.
        FarPlayerClientSupport.onJoin(localIntegratedServer);
    }

    /** DISCONNECT ladder body. Known shared residual (N-3 review, pre-existing and
     *  loader-equivalent): a server-initiated play→config RECONFIGURATION fires
     *  neither Fabric's DISCONNECT nor NeoForge's LoggingOut, so this body is
     *  skipped there — {@code ClientSessionGate.onJoin}'s defensive teardown
     *  covers the manager on the next join; the processor/far-player/trace
     *  teardowns wait for the real disconnect. */
    public static void onDisconnect() {
        ClientStatus.invalidate();
        DhClientSession.disconnect();
        sessionGate.onDisconnect();
        ClientNotices.onDisconnect();
        FarPlayerClientSupport.onSessionEnd();
        FarPlaneExtension.onDisconnect();
        dev.vox.lss.compat.ModCompat.onDisconnect();
        LSSClientConfig.CONFIG.endConnection();
    }

    public static void validateClientSettings(dev.vox.lss.common.config.ClientSettings candidate) {
        ClientIdentityResolver.validateConfiguredFallbacks(candidate);
        CacheKeyAliases.validated(candidate.cache().addressAliases(),
                warning -> LSSLogger.warn("cache.address_aliases: " + warning));
    }

    /** Apply and tick share the same idempotent, client-thread option transition. */
    public static void reconcileClientConfig() {
        var mc = Minecraft.getInstance();
        if (mc == null) return;
        if (!mc.isSameThread()) {
            mc.execute(ClientNetGlue::reconcileClientConfig);
            return;
        }
        if (sessionGate.reconcileReception(LSSClientConfig.CONFIG.receiveServerLods() && !DhClientSession.selected(), LSSApi.hasVoxelConsumers())
                && !LSSClientConfig.CONFIG.receiveServerLods()) {
            // Receipt retirement/save has completed; cancel queued map work but retain
            // texture rebuilds already committed to the still-connected native world.
            dev.vox.lss.compat.ModCompat.retireClientAcquisition();
        }
    }

    /** End-of-client-tick body. */
    public static void onEndClientTick() {
        FarPlayerClientSupport.tickPreferenceRetry();
        DhClientSession.tick();
        reconcileClientConfig();
        // Runs even before a session: the v16-server discovery fallback (no-op on the v18
        // happy path, which disarms it before the delay elapses).
        sessionGate.tickDiscoveryLadder();
        var manager = sessionGate.getRequestManager();
        if (manager != null && sessionGate.isServerEnabled()) {
            manager.tick();
        }
        columnProcessor.scheduleProcessing(sessionGate.isServerEnabled());
        // The Xaero map bridge's budgeted commit pump (xaero-map-bridge-plan.md §2.4) —
        // main client thread, no-op unless Xaero is present with queued tiles.
        dev.vox.lss.compat.ModCompat.clientTick();
        ClientStatus.tick();
        ClientNotices.tick();
    }

    /** Per-frame body (render thread) — the Xaero bridge's texture-rebuild slice
     *  (xaero-map-bridge-plan.md §17): one budgeted recolor per frame, Xaero's own
     *  sweep cadence, so rebuild cost never bunches on the client tick. */
    public static void onRenderFrame() {
        dev.vox.lss.compat.ModCompat.renderFrame();
    }
}
