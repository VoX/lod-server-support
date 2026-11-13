package dev.vox.lss.networking.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.dh.*;
import dev.vox.lss.common.diagnostics.DhStatusSnapshot;
import dev.vox.lss.common.diagnostics.DhStatusSnapshot.Activity;
import dev.vox.lss.common.diagnostics.DhStatusSnapshot.Reason;
import dev.vox.lss.common.diagnostics.DhStatusSnapshot.State;
import dev.vox.lss.compat.DhVoxyBridge;
import dev.vox.lss.compat.VoxyTerrainProbe;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Experimental DH source. Loader transport is installed only by a client entrypoint.
 *
 * <p>Threads: the client thread owns policy, receipts, requests and every Voxy
 * handoff; one worker per session decodes, converts and delivers in wire-arrival
 * order. A whole tile costs two client-thread hops (authority prefilter, then one
 * batched ingest+accept), never one per column.
 *
 * <p>Ordering: frames are processed in arrival order on the single worker. A push
 * cancels every pending request for its tile (synchronously when applied, via
 * deferUpdate otherwise), and a whole reply is written only while its request is
 * still pending, so no reply that predates a push can overwrite it.
 * Server timestamps order nothing locally (official pushes always carry 0).
 */
public final class DhClientSession {
    /** Voxy section backlog above which DH stops admitting work. One overworld tile is up to 384 sections. */
    public static final int INGEST_GATE = 1024;
    /** Worker-to-client handoff deadline. A test seam: tests shorten it instead of racing 5 s. */
    static volatile long handoffTimeoutMillis = 5_000;
    private static Consumer<byte[]> sender;
    private static String unavailable = "DH transport is unavailable on this loader";
    private static Reason transportReason = Reason.TRANSPORT_UNAVAILABLE;
    private static volatile Session session;
    private static final DhConnectionState connectionState = new DhConnectionState();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final AtomicLong RANGE_EPOCHS = new AtomicLong();
    private static final Map<Object, DhBandwidthRamp> bandwidthRamps = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Session> retiring = ConcurrentHashMap.newKeySet();
    /** Receipt files are tiny; one shared daemon keeps their IO off the client thread and off session workers. */
    private static final ExecutorService RECEIPT_IO = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), r -> { var t = new Thread(r, "LSS-DH-Receipts"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy()); // callers handle rejection; a silently dropped load would wedge
    private static String status = "DH source idle";
    private static boolean resetFailed;
    private static volatile BooleanSupplier nativeAvailable = () -> false, channelAvailable = () -> false;
    private static volatile boolean nativeOffered;
    /** Reception was turned off on this connection; the next session treats resume as a load. */
    private static boolean receptionPaused;
    /** Same-connection DH Support rediscovery, keyed by the play listener. */
    private static final Map<Object, DhProfile> discoveredProfiles = Collections.synchronizedMap(new WeakHashMap<>());
    /** One in-game stop notice per play listener. */
    private static final Map<Object, Boolean> notified = Collections.synchronizedMap(new WeakHashMap<>());
    /** In-game notice sink; a seam so tests can observe notices without a GUI. */
    static Consumer<Component> notice = ClientStatusScreen::sendChatFeedback;
    private DhClientSession() {}

    public static void installTransport(Consumer<byte[]> transport, String reason) {
        installTransport(transport, reason, Reason.TRANSPORT_UNAVAILABLE);
    }
    public static void installTransport(Consumer<byte[]> transport, String reason, Reason diagnosticReason) {
        sender = transport; unavailable = reason; transportReason = Objects.requireNonNull(diagnosticReason);
    }
    public static void installNativeAvailability(BooleanSupplier available) { nativeAvailable = available; nativeProbeInstalled = true; }
    private static volatile boolean nativeProbeInstalled;
    /** Whether the server registered the native handshake channel on this connection. Answers
     *  "yes" when no loader probe is installed or the probe fails: its one reader turns "no"
     *  into "this server does not run the mod", which must rest on evidence. */
    public static boolean nativeChannelPresent() {
        if (!nativeProbeInstalled) return true;
        try { return nativeAvailable.getAsBoolean(); }
        catch (RuntimeException | LinkageError unknown) { return true; }
    }
    public static void installChannelAvailability(BooleanSupplier available) { channelAvailable = available; }
    private static boolean hasDhChannel() {
        try { return channelAvailable.getAsBoolean(); }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }
    /** Reset treats this connection as a refillable DH terrain session only once the server
     *  actually negotiated DH (config received). A vanilla server, an unanswered hello (e.g.
     *  official DH 3.2.x) or a stopped source cannot refill Voxy: those keep the prompt. */
    public static boolean dhServerConnection() {
        var s = session;
        return selected() && hasDhChannel() && Minecraft.getInstance().getConnection() != null
                && s != null && s.active && s.config != null;
    }
    public static boolean fallbackEnabled() { return LSSClientConfig.CONFIG.dhSourceAllowed(); }
    public static boolean selected() {
        if (!fallbackEnabled() || nativeOffered) return false;
        try { return !nativeAvailable.getAsBoolean(); }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }
    /** A native offer wins even when it disables service; DH must not bypass an admin's denial. */
    public static void nativeOffer() {
        nativeOffered = true;
        var old = session; session = null; if (old != null) old.beginRetire();
        connectionState.disconnect(); resetFailed = false;
        status = "Native " + dev.vox.lss.common.Brand.shortName() + " offer received; DH fallback suppressed";
    }
    public static void onJoin() {
        disconnect(false);
        discoveredProfiles.clear();
        receptionPaused = false;
    }
    public static boolean running() { var s = session; return s != null && s.active; }
    /** A negotiated DH terrain session (config received), not one waiting on a server
     *  that may never answer. Gates the vanilla-border pump. */
    public static boolean terrainSessionActive() { var s = session; return s != null && s.active && s.config != null; }
    /** Permission alone is not a recoverable terrain service for a destructive reset. */
    public static boolean canAcquire() {
        var s = session; var mc = Minecraft.getInstance();
        if (!selected() || !LSSClientConfig.CONFIG.receiveServerLods() || sender == null || !hasDhChannel()
                || s == null || !s.active || s.config == null || s.levelKey == null
                || mc.getConnection() != s.connection || mc.level != s.level || DhVoxyBridge.failed()) return false;
        var cfg = s.config;
        return !s.profile.support() && s.syncAvailable() && cfg.syncDistance() > 0
                || cfg.plan() != DhMessage.GeneratorPlan.DISABLED && cfg.generationRate() > 0 && cfg.generationDistance() > 0;
    }
    public static boolean ownsWork() { return session != null || !retiring.isEmpty(); }
    public static String status() { var s = session; return s == null ? status : s.describe(); }
    private static DhProfile probeProfile() {
        var mc = Minecraft.getInstance();
        var known = discoveredProfiles.get(mc == null ? null : mc.getConnection());
        return known != null ? known : DhProfile.OFFICIAL_16;
    }

    /** Client-owner capture; immutable allowlisted export never retains the live session. */
    public static DhStatusSnapshot diagnostics() {
        var s = session;
        if (s != null) return s.diagnostics();
        var level = Minecraft.getInstance().level;
        String dimension = level == null ? null : level.dimension().identifier().toString();
        var block = dimension == null ? null : connectionState.blockReason(dimension);
        var state = !LSSClientConfig.CONFIG.receiveServerLods() || level == null ? State.PAUSED
                : sender == null ? State.NEED_TRANSPORT
                : block != null ? State.BLOCKED
                : resetFailed ? State.STOPPED
                : !DhVoxyBridge.available() ? State.NEED_VOXY : State.BETWEEN_SESSIONS;
        var reason = nativeOffered ? Reason.NATIVE_OFFER : !selected() ? Reason.NATIVE_PREFERRED
                : block != null ? blockReason(block)
                : resetFailed ? Reason.SOURCE_STOPPED : sender == null ? transportReason : Reason.NONE;
        var dh = LSSClientConfig.CONFIG;
        return new DhStatusSnapshot(probeProfile(), state, reason, state == State.STOPPED || state == State.BLOCKED ? Activity.STOPPED : Activity.IDLE,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, DhVoxyBridge.backlog(), 0, 0, 0, 0,
                dh.dhSyncOnLoad(), false, dh.dhRealTimeUpdates(), false, 0, 0, -1, 0, 0, 0);
    }
    private static Reason blockReason(DhConnectionState.Block block) {
        return switch (block) {
            case WORLD_CHANGED -> Reason.WORLD_CHANGED;
            case VANILLA_LIMIT -> Reason.VANILLA_LIMIT;
            case DIMENSION_LIMIT -> Reason.DIMENSION_LIMIT;
        };
    }
    public static void disconnect() { disconnect(true); }
    private static void disconnect(boolean withdraw) {
        var old = session; session = null;
        if (old != null) old.beginRetire(withdraw);
        connectionState.disconnect(); resetFailed = false; status = "DH source disconnected";
        nativeOffered = false;
    }

    public static void tick() {
        if (ClientTraceLog.enabled() && selected() && DhClientTrace.sampleDue(System.nanoTime())) traceSnapshot();
        for (var old : List.copyOf(retiring)) if (old.worker.isTerminated()) { old.closeResources(); retiring.remove(old); }
        var mc = Minecraft.getInstance();
        if (!selected() || !LSSClientConfig.CONFIG.receiveServerLods() || mc.level == null || mc.getConnection() == null) {
            var old = session; session = null;
            if (old != null) {
                // Reception OFF on a live connection: the next session is a resume (revalidates with sync).
                if (mc.getConnection() != null && selected() && !LSSClientConfig.CONFIG.receiveServerLods()) receptionPaused = true;
                old.beginRetire();
            }
            resetFailed = false;
            status = nativeOffered ? "Native " + dev.vox.lss.common.Brand.shortName() + " offer received; DH fallback suppressed"
                    : selected() ? "DH source paused (receiving disabled or no world)" : "DH fallback idle (native preferred)";
            return;
        }
        if (sender == null) { status = unavailable; return; }
        if (resetFailed) return; // Another explicit reset or a new session boundary may retry the failed sweep.
        if (!DhVoxyBridge.available()) { var old = session; session = null; if (old != null) old.beginRetire(); status = "DH source requires an initialized Voxy bridge"; return; }
        if (session != null && session.level != mc.level) { var old = session; session = null; old.beginRetire(); }
        if (connectionState.blocked(mc.level.dimension().identifier().toString())) { status = "DH stopped for this connection; reconnect to resume"; return; }
        if (session == null) {
            try { session = new Session(mc.level, receptionPaused); }
            catch (IllegalStateException e) { status = e.getMessage(); return; }
            receptionPaused = false;
            // Events cover every future load. Seed chunks already held before the first tick.
            var player = mc.player;
            int radius = Math.min(64, mc.options.renderDistance().get() + 2);
            if (player != null) for (int x = player.chunkPosition().x() - radius; x <= player.chunkPosition().x() + radius; x++)
                for (int z = player.chunkPosition().z() - radius; z <= player.chunkPosition().z() + radius; z++)
                    if (loaded(mc.level, x, z)) vanillaLoaded(mc.level, x, z);
        }
        session.tick();
    }
    /** Client-owner sampling is independent of the native request manager and remains active while DH waits. */
    private static void traceSnapshot() {
        var s = session;
        int inbound = 0, generation = 0; long oldest = -1, losses = 0, queuedBytes = 0;
        boolean recovery = false;
        if (s != null) {
            synchronized (s.frames) { inbound = s.frames.size(); for (var frame : s.frames) if (!frame.processing && frame.bytes != null) queuedBytes += frame.bytes.length; }
            long now = System.nanoTime();
            for (var request : s.pending.values()) {
                oldest = Math.max(oldest, Math.max(0, (now - request.started) / 1_000_000L));
                if (request.generation) generation++;
            }
            losses = s.rawTerrainLosses.get(); recovery = s.pushRecovery.get();
        }
        var mc = Minecraft.getInstance();
        var player = mc.player;
        var chunk = player == null ? null : player.chunkPosition();
        if (player != null && mc.level != null) {
            int view = mc.options == null ? 8 : mc.options.getEffectiveRenderDistance();
            ClientNetTrace.maybeEmit(mc, player, mc.level, view, s == null ? 0 : s.worker.getQueue().size(),
                    queuedBytes, DhVoxyBridge.backlog(), s == null ? 0 : s.pending.size(),
                    -1, -1, -1, -1, ClientNetTrace.Source.DH, s == null ? mc.getConnection() : s,
                    s == null ? 0 : s.wireBytes.get(), -1);
        }
        DhClientTrace.sample(diagnostics(), inbound, losses, oldest, generation, recovery,
                chunk == null ? null : chunk.x(), chunk == null ? null : chunk.z());
    }
    /** Vanilla authority is recorded only where DH could actually be selected. */
    public static void vanillaLoaded(ClientLevel level, int x, int z) {
        if (!fallbackEnabled() || nativeOffered) return;
        var mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) return; // integrated hosts always offer native service
        var s = session;
        try { connectionState.vanilla(level.dimension().identifier().toString(), x, z); }
        catch (IllegalStateException e) {
            if (s != null && s.level == level && s.active) s.fail(e.getMessage(), Reason.VANILLA_LIMIT);
        }
    }
    static boolean loaded(ClientLevel level, int x, int z) {
        return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null;
    }
    /** Called in the payload codec, before Fabric queues a main-thread callback. */
    public static void rejectTransportFrame(String reason) { var s = session; if (s != null) s.fail(reason, Reason.SOURCE_STOPPED); }
    public static Inbound capture(FriendlyByteBuf buffer) { return capture(buffer, 0, false); }
    /** Explicit timestamps are a deterministic test seam. Production captures timestamp under the ownership lock. */
    public static Inbound capture(FriendlyByteBuf buffer, long arrivalNanos) { return capture(buffer, arrivalNanos, true); }
    private static Inbound capture(FriendlyByteBuf buffer, long arrivalNanos, boolean explicitArrival) {
        int size = buffer.readableBytes(); var s = session;
        if (size > DhLimits.DEFAULT.frameBytes()) { buffer.skipBytes(size); if (s != null) s.fail("Oversized DH frame", Reason.SOURCE_STOPPED); return null; }
        if (s == null || !s.active) { buffer.skipBytes(size); return null; }
        int kind = size >= 4 ? buffer.getUnsignedShort(buffer.readerIndex() + 2) : -1;
        boolean terrain = kind == 9 || kind == 10;
        synchronized (s.frames) {
            // Publication and cleanup share this lock. An actual new capture cannot
            // publish an older timestamp after a maintenance cutoff has passed it.
            if (!explicitArrival) arrivalNanos = System.nanoTime();
            if (s.active && terrain && s.frames.size() >= 24) {
                DhClientTrace.pressure(DhClientTrace.Pressure.INBOUND_QUEUE);
                buffer.skipBytes(size); s.terrainLost(); return null;
            }
            if (!s.active || s.frames.size() >= 32) { buffer.skipBytes(size); s.fail("DH inbound queue limit reached", Reason.SOURCE_STOPPED); return null; }
            try {
                var lease = s.budget.reserve(size + 256L);
                try {
                    byte[] bytes = new byte[size]; buffer.readBytes(bytes);
                    var frame = new Inbound(s, bytes, lease, arrivalNanos, s.rawTerrainLosses.get());
                    s.frames.add(frame); s.lastInboundArrival = arrivalNanos; return frame;
                } catch (RuntimeException | Error e) { lease.close(); throw e; }
            } catch (DhCodecException e) {
                buffer.skipBytes(buffer.readableBytes());
                if (terrain) { DhClientTrace.pressure(DhClientTrace.Pressure.OWNED_BYTES); s.terrainLost(); }
                else s.fail(e.getMessage(), Reason.SOURCE_STOPPED);
                return null;
            }
        }
    }
    /** Payload ownership is bounded even while it waits for the loader callback. */
    public static final class Inbound implements AutoCloseable {
        private final Session owner;
        private byte[] bytes;
        private final DhMemoryBudget.Lease lease;
        private boolean processing;
        private final long arrivalNanos, lossEpoch;
        private Inbound(Session owner, byte[] bytes, DhMemoryBudget.Lease lease, long arrivalNanos, long lossEpoch) {
            this.owner = owner; this.bytes = bytes; this.lease = lease; this.arrivalNanos = arrivalNanos; this.lossEpoch = lossEpoch;
        }
        public void receive() {
            try { owner.worker.execute(() -> {
                synchronized (owner.frames) { if (bytes == null) return; processing = true; }
                try { if (owner.active) owner.receive(bytes, arrivalNanos, lossEpoch); }
                catch (DhCodecException e) {
                    switch (e.kind()) {
                        case RESOURCE_LIMIT -> owner.recoverResourcePressure();
                        case EXPIRED, UNOWNED -> owner.applyLostClaims(); // owners retry; lost pushes reacquire their tile
                        default -> owner.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), Reason.SOURCE_STOPPED);
                    }
                }
                catch (InterruptedException | CancellationException retired) { /* session retired */ }
                catch (Exception | LinkageError e) { owner.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), Reason.SOURCE_STOPPED); }
                finally { close(); if (!owner.active) owner.closeResources(); }
            }); } catch (RejectedExecutionException e) { close(); if (owner.active) owner.fail("DH worker queue full", Reason.SOURCE_STOPPED); }
        }
        @Override public void close() {
            synchronized (owner.frames) { bytes = null; lease.close(); owner.frames.remove(this); }
        }
    }
    /** Reset must abort its wipe if the conversion worker cannot retire. */
    public static void drainForReset() {
        var s = session;
        if (s != null) s.discardReceipts();
        for (var old : retiring) old.discardReceipts();
        for (var old : List.copyOf(retiring)) if (!old.retire())
            throw new IllegalStateException("Previous DH conversion did not stop; reset aborted");
        if (s != null && !s.retire()) { s.problem = "DH conversion did not stop; reset aborted"; throw new IllegalStateException(s.problem); }
        session = null;
        try {
            // Saves queued before the drain must land before the clear, never after it.
            try { RECEIPT_IO.submit(() -> {}).get(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("DH receipt flush interrupted", e); }
            catch (ExecutionException | TimeoutException e) { throw new IllegalStateException("DH receipt flush did not finish; reset aborted", e); }
            invalidateCurrentNativeCache();
            var server = Minecraft.getInstance().getCurrentServer();
            if (server != null && server.ip != null) ColumnCacheStore.clearDhReceipts(server.ip);
        }
        catch (RuntimeException e) {
            if (s != null) s.problem = "DH reset failed: " + sanitizeReason(e.getMessage());
            status = "DH reset failed: " + sanitizeReason(e.getMessage());
            resetFailed = true;
            throw e;
        }
    }
    /** After a successful drain: receipts are gone; the next tick starts a fresh session. */
    public static void resumeAfterReset() { connectionState.resetReceipts(); session = null; resetFailed = false; status = "DH reset complete; reconnecting terrain source"; }

    private static String sanitizeReason(String text) {
        if (text == null) return "Unknown DH failure";
        String clean = text.replaceAll("§.", "").replaceAll("[\\p{Cntrl}]", " ");
        return clean.substring(0, Math.min(512, clean.length()));
    }
    private static void invalidateCurrentNativeCache() {
        var server = Minecraft.getInstance().getCurrentServer();
        if (server == null || server.ip == null) return; // No remote cache exists in single-player.
        ColumnCacheStore.clearForServersStrict(ClientNetGlue.dhCacheSweep(server.ip));
    }

    private static final class Session {
        final ClientLevel level;
        final String dimension;
        final Object connection = Minecraft.getInstance().getConnection();
        final Object physicalConnection = Minecraft.getInstance().getConnection().getConnection();
        final net.minecraft.client.multiplayer.ServerData remote = Minecraft.getInstance().getCurrentServer();
        final DhProfile profile;
        final DhProtocolCodec codec;
        // Physical-connection ownership preserves earned ramp progress across dimensions and reception toggles.
        final DhBandwidthRamp bandwidthRamp = bandwidthRamps.computeIfAbsent(physicalConnection,
                key -> new DhBandwidthRamp(LSSClientConfig.CONFIG.enableAdaptiveTransferRate(),
                        LSSClientConfig.CONFIG.enableJoinSlowStart()));
        final DhLimits sessionLimits = bandwidthRamp.startedSlow() ? DhLimits.DEFAULT.withLifetime(120_000_000_000L) : DhLimits.DEFAULT;
        final DhMemoryBudget budget = new DhMemoryBudget(sessionLimits.ownedBytes());
        final DhAssembly assembly = new DhAssembly(sessionLimits, budget, System::nanoTime, this::requestLifetime);
        final DhSessionPolicy policy;
        final Set<Inbound> frames = new HashSet<>();
        final ThreadPoolExecutor worker;
        final ConcurrentHashMap<Integer, Pending> pending = new ConcurrentHashMap<>();
        final DhColumnConverter converter;
        final boolean resumed;
        volatile boolean active = true;
        volatile DhMessage.SessionConfig config;
        volatile String levelKey, serverKey;
        volatile String problem = "waiting for DH config";
        volatile Reason reason = Reason.NONE;
        volatile Activity activity = Activity.IDLE;
        final long started = System.nanoTime();
        int sentBandwidth;
        long probeRetryAt; int probeFailures;
        long sentAt, lastRequest, lastGenerationRequest, lastSyncRequest, maintenanceAt, nextScanAt, receiptsSavedAt;
        long generationBackoffUntil, syncBackoffUntil;
        volatile long lastInboundArrival, lastReply;
        boolean sentSync = true, sentUpdates = true, announced, cacheInvalidated, resumeApplied;
        final AtomicBoolean preferencesWithdrawn = new AtomicBoolean(), maintenanceQueued = new AtomicBoolean();
        final AtomicBoolean pushRecovery = new AtomicBoolean();
        final AtomicLong rawTerrainLosses = new AtomicLong(), droppedUpdateFrames = new AtomicLong();
        final AtomicLong receivedUpdates = new AtomicLong(), deferredUpdates = new AtomicLong(), unknownControls = new AtomicLong();
        final AtomicLong tiles = new AtomicLong(), columns = new AtomicLong(), wireBytes = new AtomicLong();
        final AtomicLong requested = new AtomicLong(), incomplete = new AtomicLong(), refused = new AtomicLong();
        final AtomicLong skipped = new AtomicLong(), failedColumns = new AtomicLong(), malformedTiles = new AtomicLong();
        /** Request/push ordering. Client thread stamps requests; the worker stamps applied pushes. */
        Set<DhPosition> visible = new HashSet<>();
        ChunkPos visibleCenter;
        int visibleRange = -1;
        record RangeContext(int x, int z, int range, int generationDistance, int syncDistance, boolean generation,
                            boolean synchronize, int centerX, int centerZ, int borderRadius) {}
        RangeContext rangeContext;
        long rangeEpoch;
        int unanswered, rejectedTiles, retryBurst;
        // Persisted receipts (client thread): loaded once per session, probed before trust.
        DhReceiptStore receiptStore;
        boolean receiptsLoading, receiptsDirty, probeInFlight;
        final LinkedHashMap<DhPosition, DhReceiptStore.Entry> stored = new LinkedHashMap<>();
        final Set<DhPosition> unprobed = new HashSet<>();
        final AtomicLong restored = new AtomicLong();
        boolean receiptsUnverified, receiptsUnavailable;

        static final class Pending {
            final DhPosition position; final boolean generation; final long started, timestamp, rangeEpoch, lifetime;
            // Worker completion remains protected until the client applies its queued bookkeeping.
            volatile boolean answered;
            Pending(DhPosition position, boolean generation, long started, long timestamp, long rangeEpoch, long lifetime) {
                this.position = position; this.generation = generation; this.started = started; this.timestamp = timestamp;
                this.rangeEpoch = rangeEpoch; this.lifetime = lifetime;
            }
            Pending(DhPosition position, boolean generation, long started, long timestamp) {
                this(position, generation, started, timestamp, 0, DhLimits.DEFAULT.lifetimeNanos());
            }
            boolean generation() { return generation; }
        }
        Session(ClientLevel level, boolean resumed) {
            this.level = level; this.resumed = resumed;
            dimension = level.dimension().identifier().toString();
            policy = connectionState.policy(dimension);
            profile = probeProfile();
            codec = new DhProtocolCodec(profile, DhLimits.DEFAULT);
            converter = new DhColumnConverter(level);
            worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(40), r -> {
                var t = new Thread(r, "LSS-DH-Decode"); t.setDaemon(true); return t;
            });
        }
        String describe() {
            return "DH " + profile + ": " + problem + "; enqueued tiles=" + tiles + ", columns=" + columns
                    + ", stored receipts=" + stored.size() + ", restored=" + restored + ", wire=" + wireBytes
                    + " bytes, owned=" + budget.used() + ", requests=" + pending.size();
        }
        int effectiveRange() {
            var cfg = config;
            if (cfg == null) return 0;
            int maximum = profile.support() ? Math.min(32, cfg.generationDistance())
                    : Math.min(128, cfg.synchronize() ? cfg.syncDistance() : cfg.generationDistance());
            if (updatesAllowed()) maximum = Math.max(maximum, Math.min(128, cfg.updateDistance()));
            int override = LSSClientConfig.CONFIG.lodDistanceChunks();
            return override == 0 ? maximum : Math.min(override, maximum);
        }
        DhStatusSnapshot diagnostics() {
            var cfg = config;
            var block = connectionState.blockReason(dimension);
            boolean quiet = announced && cfg == null && System.nanoTime() - sentAt > 15_000_000_000L;
            boolean noChannel = !announced && System.nanoTime() - started > 15_000_000_000L;
            var state = !LSSClientConfig.CONFIG.receiveServerLods() ? State.PAUSED
                    : block != null ? State.BLOCKED
                    : !active ? State.STOPPED
                    : noChannel || quiet ? State.NO_SERVICE
                    : cfg == null || levelKey == null ? State.CONNECTING
                    : !DhVoxyBridge.ready(level) ? State.NEED_VOXY : State.READY;
            var why = block != null ? blockReason(block)
                    : !active ? reason
                    : noChannel ? Reason.NO_DH_CHANNEL
                    : quiet ? Reason.NO_DH_REPLY
                    : receiptsUnavailable ? Reason.RECEIPTS_UNAVAILABLE
                    : receiptsUnverified ? Reason.RECEIPTS_UNVERIFIED
                    : cfg != null && cfg.synchronize() && !LSSClientConfig.CONFIG.dhSyncOnLoad() ? Reason.SYNC_DISABLED_LOCALLY
                    : Reason.NONE;
            var act = state == State.STOPPED || state == State.BLOCKED ? Activity.STOPPED
                    : state == State.NEED_VOXY ? Activity.WAITING_VOXY
                    : state == State.READY ? activity : Activity.WAITING_SERVER;
            return new DhStatusSnapshot(profile, state, why, act, requested.get(), tiles.get(), incomplete.get(), refused.get(),
                    columns.get(), skipped.get(), failedColumns.get(), wireBytes.get(), budget.used(),
                    worker.getQueue().size(), pending.size(), DhVoxyBridge.backlog(), effectiveRange(),
                    cfg == null ? 0 : cfg.generationRate(), cfg == null ? 0 : cfg.syncRate(), cfg == null ? 0 : cfg.bandwidthKB(),
                    LSSClientConfig.CONFIG.dhSyncOnLoad(), syncAvailable(),
                    LSSClientConfig.CONFIG.dhRealTimeUpdates(), updatesAllowed(), receivedUpdates.get(), deferredUpdates.get(),
                    lastReply == 0 ? -1 : Math.max(0, (System.nanoTime() - lastReply) / 1_000_000L),
                    stored.size(), restored.get(), droppedUpdateFrames.get());
        }
        void closeResources() { assembly.close(); }
        void fail(String text, Reason why) {
            if (!active) return;
            problem = sanitizeReason(text); reason = why; active = false; activity = Activity.STOPPED;
            LSSLogger.warn("DH source stopped: " + problem + " (ordinary Minecraft remains connected)");
            Minecraft.getInstance().execute(() -> {
                notifyStopped(why);
                retire();
            });
        }
        /** Testers must see that DH stopped; one line per play connection. */
        void notifyStopped(Reason why) {
            var mc = Minecraft.getInstance();
            if (mc.getConnection() != connection || notified.putIfAbsent(connection, Boolean.TRUE) != null) return;
            // The command root is an argument: the VSS jar rewrites "LSS" in lang values but
            // not a lowercase command, so a literal there told VSS players to run /lss.
            notice.accept(Component.translatable("lss.status.dh.notice.stopped",
                    Component.translatable("lss.status.dh.reason." + why.name().toLowerCase(Locale.ROOT)),
                    dev.vox.lss.common.Brand.clientCommand()));
        }
        void beginRetire() { beginRetire(true); }
        void beginRetire(boolean withdraw) {
            if (!withdraw) preferencesWithdrawn.set(true); // Reconfiguration permanently disarms old-family controls.
            // Reception OFF and native takeover leave Minecraft connected. Withdraw pushes
            // on this listener whenever preferences were announced on it.
            if (withdraw && announced && Minecraft.getInstance().getConnection() == connection
                    && preferencesWithdrawn.compareAndSet(false, true)) {
                try { sender.accept(codec.encode(preferences(false, false, sentBandwidth > 0 ? sentBandwidth : DhBandwidthRamp.OPEN_KB))); }
                catch (Exception | LinkageError ignored) { /* connection may already be closed */ }
            }
            if (withdraw && !profile.support() && Minecraft.getInstance().getConnection() == connection)
                for (var entry : pending.entrySet()) if (entry.getValue().generation) {
                    try { sender.accept(codec.encode(new DhMessage.Cancel(entry.getKey()))); }
                    catch (Exception | LinkageError ignored) { /* connection may already be closed */ }
                }
            pending.clear();
            active = false;
            saveReceipts(true);
            worker.shutdownNow();
            synchronized (frames) { for (var f : List.copyOf(frames)) if (!f.processing) f.close(); }
            retiring.add(this);
        }
        boolean retire() {
            beginRetire();
            try {
                if (worker.awaitTermination(2, TimeUnit.SECONDS)) { closeResources(); retiring.remove(this); return true; }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            retiring.add(this);
            return false;
        }
        void send(DhMessage message) {
            if (!active) return;
            try { sender.accept(codec.encode(message)); }
            catch (Exception e) { fail("DH send failed: " + e.getMessage(), Reason.SOURCE_STOPPED); }
        }
        void tick() {
            if (!active) return;
            long now = System.nanoTime();
            if (!announced) {
                // Paper must receive Fabric's minecraft:register announcement first. A
                // server without the channel is simply not a DH service: wait cheaply.
                if (now - started < 1_000_000_000L || !hasDhChannel()) { problem = "waiting for an advertised DH server channel"; activity = Activity.WAITING_SERVER; return; }
                announced = true; sentAt = now;
                sendPreferences();
                send(new DhMessage.RequestLevelInit(dimension));
            }
            boolean syncActivated = !sentSync && LSSClientConfig.CONFIG.dhSyncOnLoad();
            boolean sourcePrefsChanged = sentSync != LSSClientConfig.CONFIG.dhSyncOnLoad() || sentUpdates != LSSClientConfig.CONFIG.dhRealTimeUpdates();
            // DH Support ignores bandwidth and resets its buffer ids on every config; never re-send it for bandwidth alone.
            boolean bandwidthChanged = !profile.support() && sentBandwidth != 0 && sentBandwidth != bandwidthLimit();
            if (sourcePrefsChanged || bandwidthChanged) {
                sendPreferences(); nextScanAt = 0;
                if (syncActivated) { connectionState.forgetLoads(dimension); visibleCenter = null; visible = new HashSet<>(); }
            }
            if (config == null) {
                activity = Activity.WAITING_SERVER;
                problem = now - sentAt > 15_000_000_000L
                        ? "no DH reply to protocol " + profile.version() + "; the server may run an older DH or have networking disabled"
                        : "waiting for DH config";
                return; // A late reply still works; nothing is sent while waiting.
            }
            if (levelKey == null) { activity = Activity.WAITING_SERVER; problem = "waiting for DH world initialization"; return; }
            if (resumed && !resumeApplied) {
                // Reception resumed: treat as a load (revalidates where sync is available).
                // Without sync, held terrain stays as it is, like the official DH client.
                resumeApplied = true;
                connectionState.forgetLoads(dimension);
            }
            expireRequests(now);
            // A silent official sync request may retain its upstream permit forever.
            // Official DH keeps a sync permit for a request it failed silently; more asks would
            // only queue behind it on the server, so stop until the next connection.
            if (unanswered >= 2) { fail("The DH server stopped answering terrain requests (two in a row); reconnect to resume", Reason.SOURCE_STOPPED); return; }
            if (now - maintenanceAt > 1_000_000_000L && maintenanceQueued.compareAndSet(false, true)) {
                maintenanceAt = now;
                try { worker.execute(() -> {
                    try { if (pushRecovery.get()) recoverLoss(); expireAssembly(); }
                    catch (Exception error) { if (active) fail("DH maintenance failed: " + error.getClass().getSimpleName(), Reason.SOURCE_STOPPED); }
                    finally { maintenanceQueued.set(false); }
                }); }
                catch (RejectedExecutionException e) { maintenanceQueued.set(false); }
            }
            if (now - receiptsSavedAt > 10_000_000_000L) { receiptsSavedAt = now; saveReceipts(false); }
            if (DhVoxyBridge.failed()) { fail("Voxy DH bridge is incompatible; restart with a compatible Voxy build", Reason.SOURCE_STOPPED); return; }
            if (!DhVoxyBridge.ready(level)) { activity = Activity.WAITING_VOXY; problem = "waiting for Voxy world/ingest service"; return; }
            if (!cacheInvalidated) {
                try { invalidateNativeCache(); } catch (RuntimeException e) { fail(e.getMessage(), Reason.SOURCE_STOPPED); return; }
            }
            var player = Minecraft.getInstance().player;
            if (player == null) { activity = Activity.IDLE; return; }
            var center = player.chunkPosition();
            observeLoaded(now, center); pruneGeneration(now, center);
            if (DhVoxyBridge.backlog() > INGEST_GATE || worker.getQueue().size() > 4) { activity = Activity.WAITING_VOXY; return; }
            if (pending.size() >= 2) { activity = Activity.WORKING; return; }
            if (now < nextScanAt) return;
            nextScanAt = now + 500_000_000L;
            requestNext(now, center);
            if (lastRequest == now) nextScanAt = now + 50_000_000L;
        }
        void expireRequests(long now) {
            synchronized (frames) {
                long cutoff = arrivalCutoff(now);
                for (var entry : pending.entrySet()) if (!entry.getValue().answered && cutoff - entry.getValue().started > entry.getValue().lifetime) {
                    if (pending.remove(entry.getKey(), entry.getValue())) {
                        DhClientTrace.timeout(entry.getKey(), entry.getValue().position);
                        policy.defer(entry.getValue().position, now);
                        if (!entry.getValue().generation || profile.support()) unanswered++;
                        if (entry.getValue().generation && !profile.support()) send(new DhMessage.Cancel(entry.getKey()));
                    }
                }
            }
        }
        /** Worker only. */
        void expireAssembly() { synchronized (frames) { assembly.expire(arrivalCutoff(System.nanoTime())); } applyLostClaims(); }
        /** Caller holds frames. Include captures awaiting the loader callback AND processing frames. */
        long arrivalCutoff(long now) {
            long cutoff = now;
            for (var frame : frames) if (cutoff - frame.arrivalNanos > 0) cutoff = frame.arrivalNanos;
            return cutoff;
        }
        /** Cancel only official generation outside interest, with spatial/time hysteresis. */
        void pruneGeneration(long now, ChunkPos player) {
            if (profile.support() || config == null) return;
            int range = Math.min(effectiveRange(), Math.min(32, config.generationDistance()));
            synchronized (frames) {
                if (!frames.isEmpty()) return; // Let captured/processing replies finish under their existing ownership.
                for (var entry : pending.entrySet()) {
                    var request = entry.getValue();
                    int distance = Math.max(Math.abs(request.position.x() * 4 + 2 - player.x()), Math.abs(request.position.z() * 4 + 2 - player.z()));
                    if (request.answered || !request.generation || now - request.started < 2_000_000_000L || distance <= range + 12) continue;
                    if (!pending.remove(entry.getKey(), request)) continue;
                    send(new DhMessage.Cancel(entry.getKey())); policy.defer(request.position, now);
                    DhClientTrace.cancel(entry.getKey(), request.position);
                    try { worker.execute(() -> assembly.cancel(entry.getKey())); }
                    catch (RejectedExecutionException full) { fail("DH worker queue full while cancelling obsolete generation", Reason.SOURCE_STOPPED); return; }
                    nextScanAt = 0;
                }
            }
        }
        long requestInterval(boolean generation) {
            long interval = generation ? 500_000_000L : 50_000_000L;
            if (profile.support()) interval = Math.max(interval, 1_000_000_000L);
            if (DhVoxyBridge.backlog() < 0) interval = Math.max(interval, 2_000_000_000L);
            return interval;
        }
        /** Nanoseconds until this request class may be sent; 0 = now, Long.MAX_VALUE = service unavailable. */
        long requestWait(boolean generation, long now) {
            int rate = generation ? config.generationRate() : config.syncRate();
            if (rate <= 0) return Long.MAX_VALUE;
            if (pending.values().stream().filter(p -> p.generation == generation).count() >= (generation ? 1 : Math.min(2, rate)))
                return Long.MAX_VALUE;
            long elapsed = now - (generation ? lastGenerationRequest : lastSyncRequest);
            long interval = Math.max(requestInterval(generation), 1_000_000_000L / rate);
            long wait = Math.max(0, interval - elapsed);
            if (profile.support()) {
                bandwidthLimit(); long paced = bandwidthRamp.supportRequestInterval(interval, now);
                if (paced > elapsed) wait = Math.max(wait, paced - elapsed);
            }
            int manual = LSSClientConfig.CONFIG.lodColumnsPerSecondLimit();
            if (manual > 0 && requested.get() > 0) wait = Math.max(wait, 16_000_000_000L / manual - (now - lastRequest));
            return Math.max(wait, (generation ? generationBackoffUntil : syncBackoffUntil) - now);
        }
        boolean syncAvailable() {
            var cfg = config;
            return cfg != null && cfg.synchronize() && cfg.syncRate() > 0 && LSSClientConfig.CONFIG.dhSyncOnLoad();
        }
        boolean updatesAllowed() {
            var cfg = config;
            return cfg != null && cfg.updates() && cfg.updateDistance() > 0 && LSSClientConfig.CONFIG.dhRealTimeUpdates();
        }
        long requestLifetime() {
            return !profile.support() && bandwidthLimit() < DhBandwidthRamp.OPEN_KB ? 120_000_000_000L : DhLimits.DEFAULT.lifetimeNanos();
        }
        int bandwidthLimit() {
            return bandwidthRamp.limit(LSSClientConfig.CONFIG.enableAdaptiveTransferRate(),
                    LSSClientConfig.CONFIG.snapshot().lod().download().slowStartOnJoin());
        }
        void sendPreferences() {
            sentSync = LSSClientConfig.CONFIG.dhSyncOnLoad(); sentUpdates = LSSClientConfig.CONFIG.dhRealTimeUpdates();
            sentBandwidth = bandwidthLimit();
            send(preferences(sentSync, sentUpdates, sentBandwidth));
        }
        DhMessage.SessionConfig preferences(boolean synchronize, boolean updates, int bandwidth) {
            return new DhMessage.SessionConfig(profile == DhProfile.OFFICIAL_16
                    ? DhMessage.GeneratorPlan.SURFACE_THEN_CHUNKS : DhMessage.GeneratorPlan.CHUNKS_ONLY,
                    2048, 0, 0, 0, 20, updates, requestedUpdateDistance(), synchronize, 2048, 50, bandwidth);
        }
        /** Pushes beyond the client's own LOD distance would only be dropped on arrival. */
        static int requestedUpdateDistance() {
            int override = LSSClientConfig.CONFIG.lodDistanceChunks();
            return override > 0 ? Math.min(128, override) : 128;
        }
        /** A load transition triggers synchronization; elapsed time alone never polls terrain. */
        void observeLoaded(long now, ChunkPos player) {
            int range = effectiveRange();
            if (player.equals(visibleCenter) && range == visibleRange) return;
            var next = new HashSet<DhPosition>(visible.size() * 2 + 16);
            var loads = connectionState.loadedAt(dimension);
            boolean sync = syncAvailable();
            int cx = Math.floorDiv(player.x(), 4), cz = Math.floorDiv(player.z(), 4), rings = (range + 3) / 4;
            for (int x = cx - rings; x <= cx + rings; x++) for (int z = cz - rings; z <= cz + rings; z++) {
                if (Math.max(Math.abs(x * 4 + 2 - player.x()), Math.abs(z * 4 + 2 - player.z())) > range) continue;
                var p = new DhPosition(6, x, z);
                next.add(p);
                if (sync && !visible.contains(p) && loadDue(loads, p, now) && policy.syncTimestamp(p) > 0) {
                    policy.revalidate(p, now);
                    markLoaded(loads, p, now);
                }
            }
            visible = next; visibleCenter = player; visibleRange = range;
        }
        static boolean loadDue(Map<DhPosition, Long> loads, DhPosition p, long now) {
            return now - loads.getOrDefault(p, Long.MIN_VALUE / 2) >= 20 * 60_000_000_000L;
        }
        static void markLoaded(Map<DhPosition, Long> loads, DhPosition p, long now) {
            loads.remove(p); loads.put(p, now);
            if (loads.size() > DhSessionPolicy.MAX_RECEIPTS) loads.remove(loads.keySet().iterator().next());
        }
        static boolean completeColumn(DhTerrainTile tile, int column) {
            int x = column / 4 * 16, z = column % 4 * 16;
            for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++)
                if (tile.coverage((x + dx) * 64 + z + dz) != DhTerrainTile.Coverage.COMPLETE) return false;
            return true;
        }

        // ---- receipts ---------------------------------------------------------------
        /** Client thread, once the logical world is bound. */
        void openReceipts() {
            if (receiptStore != null || receiptsLoading || receiptsUnavailable) return;
            var mc = Minecraft.getInstance(); var server = mc.getCurrentServer();
            if (mc.gameDirectory == null || server == null || server.ip == null) { receiptsUnavailable = true; return; }
            var seed = dev.vox.lss.seed.ClientWorldSeed.context(true);
            // Never reuse receipts under an ambiguous world identity.
            if (!seed.seedAvailable() || seed.seed() == 0L) { receiptsUnavailable = true; return; }
            var shape = new DhTerrainTile.WorldShape(level.getMinY(), level.getHeight(), level.dimensionType().hasSkyLight());
            DhReceiptStore store;
            try {
                store = new DhReceiptStore(ColumnCacheStore.cacheRoot(), new DhReceiptStore.Key(server.ip, serverKey, levelKey,
                        dimension, seed.seed(), profile, shape));
            } catch (IllegalArgumentException e) { receiptsUnavailable = true; return; }
            loadReceipts(store);
        }
        /** Client thread; IO on the shared receipt daemon. Test seam for a store without a world seed. */
        void loadReceipts(DhReceiptStore store) {
            receiptsLoading = true;
            try {
                RECEIPT_IO.execute(() -> {
                    LinkedHashMap<DhPosition, DhReceiptStore.Entry> loaded;
                    try { loaded = store.load(); }
                    catch (Throwable e) { loaded = null; LSSLogger.warn("DH receipts unavailable: " + e.getClass().getSimpleName()); }
                    var result = loaded;
                    main(() -> {
                        receiptsLoading = false;
                        if (result == null) { receiptsUnavailable = true; return; }
                        receiptStore = store;
                        for (var e : result.entrySet()) {
                            if (!e.getValue().current()) { receiptsDirty = true; continue; } // older conversion: re-download
                            stored.putIfAbsent(e.getKey(), e.getValue()); // kept even when held, or the next save drops it
                            if (!policy.hasSnapshot(e.getKey())) unprobed.add(e.getKey()); // this connection already holds it
                        }
                        nextScanAt = 0;
                    });
                });
            } catch (RejectedExecutionException e) { receiptsLoading = false; receiptsUnavailable = true; }
        }
        /** Receipts are kept with or without sync. Without sync a restored receipt is never
         *  rechecked, so offline edits stay unseen (the official DH client's behaviour, chosen
         *  by the user on 2026-10-10 to avoid full downloads); with sync it is revalidated. */
        void rememberReceipt(DhPosition p, long timestamp) {
            if (receiptStore == null || timestamp <= 0) return;
            stored.remove(p); stored.put(p, new DhReceiptStore.Entry(timestamp, DhReceiptStore.CONVERSION_VERSION));
            unprobed.remove(p);
            while (stored.size() > DhReceiptStore.MAX_ENTRIES) stored.remove(stored.keySet().iterator().next());
            receiptsDirty = true;
        }
        void forgetReceipt(DhPosition p) {
            if (stored.remove(p) != null) receiptsDirty = true;
            unprobed.remove(p);
        }
        /** Reset: this session's receipts must never be written back after the clear. */
        void discardReceipts() { receiptStore = null; receiptsDirty = false; stored.clear(); unprobed.clear(); }
        void saveReceipts(boolean force) {
            var store = receiptStore;
            if (store == null || !receiptsDirty) return;
            receiptsDirty = false;
            var snapshot = new LinkedHashMap<>(stored);
            try {
                RECEIPT_IO.execute(() -> {
                    try { store.save(snapshot); }
                    catch (java.io.IOException | RuntimeException e) {
                        LSSLogger.warn("DH receipts not saved: " + e.getClass().getSimpleName());
                        main(() -> { if (receiptStore == store) receiptsDirty = true; }); // retry on the next save pass
                    }
                });
            } catch (RejectedExecutionException full) { receiptsDirty = true; }
        }
        /** Client thread: probe the nearest persisted receipts in range, one batch at a time. */
        void probeReceipts(ChunkPos player, int range) {
            if (probeInFlight || unprobed.isEmpty() || System.nanoTime() < probeRetryAt) return;
            var batch = new ArrayList<DhPosition>();
            int cx = Math.floorDiv(player.x(), 4), cz = Math.floorDiv(player.z(), 4), rings = (range + 3) / 4;
            for (int ring = 0; ring <= rings && batch.size() < 64; ring++)
                for (int dx = -ring; dx <= ring && batch.size() < 64; dx++) for (int dz = -ring; dz <= ring && batch.size() < 64; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    var p = new DhPosition(6, cx + dx, cz + dz);
                    if (unprobed.contains(p)) batch.add(p);
                }
            if (batch.isEmpty()) return;
            probeInFlight = true;
            int minY = level.getMinY(), height = level.getHeight();
            try { worker.execute(() -> {
                Map<DhPosition, Boolean> present = new HashMap<>();
                VoxyTerrainProbe probe;
                try { probe = onMain(() -> DhVoxyBridge.openProbe(level)); }
                catch (InterruptedException | CancellationException retired) { return; }
                catch (Exception busy) { main(() -> probeFailed()); return; } // client thread busy: retry, never trust
                if (probe == null) { main(() -> applyProbe(batch, null)); return; } // no readback ABI at all
                for (var p : batch) {
                    if (Thread.currentThread().isInterrupted()) return;
                    try { present.put(p, probe.hasTileTerrain(p.x(), p.z(), minY, height)); }
                    catch (ReflectiveOperationException | RuntimeException | LinkageError e) { main(() -> probeFailed()); return; }
                }
                main(() -> applyProbe(batch, present));
            }); } catch (RejectedExecutionException full) { probeInFlight = false; }
        }
        /** A transient probe failure (busy client thread, engine swap): retry with backoff. After
         *  repeated failures, drop the unprobed receipts so their tiles download again. */
        void probeFailed() {
            probeInFlight = false;
            if (++probeFailures >= 5) {
                LSSLogger.warn("Voxy readback keeps failing; unverified DH receipts are dropped and downloaded again");
                for (var p : List.copyOf(unprobed)) forgetReceipt(p);
                probeFailures = 0;
            } else probeRetryAt = System.nanoTime() + 2_000_000_000L * probeFailures;
            nextScanAt = 0;
        }
        /** {@code present==null}: Voxy has no readback ABI; trust receipts (logged once). */
        void applyProbe(List<DhPosition> batch, Map<DhPosition, Boolean> present) {
            probeInFlight = false;
            if (present != null) probeFailures = 0;
            long now = System.nanoTime();
            if (present == null && !receiptsUnverified) {
                receiptsUnverified = true;
                LSSLogger.warn("Voxy readback unavailable; DH receipts are trusted without a destination check");
            }
            var loads = connectionState.loadedAt(dimension);
            boolean sync = syncAvailable();
            for (var p : batch) {
                if (!unprobed.remove(p)) continue;
                var entry = stored.get(p);
                if (entry == null) continue;
                if (present != null && !Boolean.TRUE.equals(present.get(p))) { forgetReceipt(p); continue; }
                boolean revalidate = sync && loadDue(loads, p, now); // without sync: held as is
                if (!policy.restore(p, entry.timestamp(), now, revalidate)) continue;
                if (revalidate) markLoaded(loads, p, now);
                restored.incrementAndGet();
            }
            nextScanAt = 0;
        }

        // ---- loss recovery -------------------------------------------------------------
        /** Network thread under frames lock. Buffers spanning the loss self-discard by epoch. */
        void terrainLost() {
            // A dropped fragment invalidates the buffers it spans (epoch). A dropped update
            // message leaves its buffer orphaned until expiry. Either way the lost buffer's
            // header names its tile (applyLostClaims); only a buffer whose first fragment
            // was lost is unidentifiable and falls back to window recovery.
            rawTerrainLosses.incrementAndGet(); droppedUpdateFrames.incrementAndGet();
        }
        /** Worker. Assembly slots or owned memory are exhausted: free incomplete buffers now
         *  (their owners retry) rather than waiting for idle expiry with every slot taken. */
        void recoverResourcePressure() {
            DhClientTrace.pressure(DhClientTrace.Pressure.ASSEMBLY_OR_DECODE);
            // Later fragments of a discarded buffer resolve as UNOWNED (missing first
            // fragment), never as a gapped payload; discarded pushes reacquire their tiles.
            assembly.discardIncomplete();
            applyLostClaims();
        }
        /** Worker. Reacquire the tiles named by lost buffers. True when any tile was identified. */
        boolean applyLostClaims() {
            var claims = assembly.drainLostClaims();
            if (claims.isEmpty()) return false;
            if (updatesAllowed()) for (var p : claims) deferUpdate(p, "live update lost in transit; tile reacquired");
            return true;
        }
        /**
         * Worker. A lost push fragment without a readable header has no tile identity, but pushes only
         * arrive within the server's update distance of the player. With sync, revalidate held
         * tiles in that window and cancel requests in flight there, whose replies may predate
         * the lost push. Without sync, held terrain is kept (official DH client behaviour).
         */
        void recoverLoss() throws Exception {
            if (!pushRecovery.compareAndSet(true, false)) return;
            main(() -> {
                var cfg = config; var player = Minecraft.getInstance().player;
                nextScanAt = 0;
                if (cfg == null || !updatesAllowed()) return;
                if (player == null) { pushRecovery.set(true); return; } // e.g. respawn: retry on the next pass
                long now = System.nanoTime();
                int radius = Math.min(effectiveRange(), cfg.updateDistance()) + 8;
                var center = player.chunkPosition();
                int cx = Math.floorDiv(center.x(), 4), cz = Math.floorDiv(center.z(), 4), rings = (radius + 3) / 4 + 1;
                boolean sync = syncAvailable();
                // Without sync, held terrain is accepted as possibly stale (official DH client
                // behaviour): an unidentifiable lost push never costs a re-download of the window.
                if (!sync) { problem = "DH update lost in transit; held terrain kept (sync off)"; return; }
                var cancelledIds = new ArrayList<Integer>();
                for (int x = cx - rings; x <= cx + rings; x++) for (int z = cz - rings; z <= cz + rings; z++) {
                    var p = new DhPosition(6, x, z);
                    // An in-flight reply may predate the lost push: cancel it so it cannot re-hold the tile.
                    var cancelled = cancelPending(p);
                    if (!cancelled.isEmpty()) cancelledIds.addAll(cancelled);
                    if (!policy.hasSnapshot(p)) continue;
                    if (policy.syncTimestamp(p) > 0) policy.revalidate(p, now); else { policy.invalidate(p); forgetReceipt(p); }
                }
                if (!cancelledIds.isEmpty()) {
                    try { worker.execute(() -> cancelledIds.forEach(assembly::cancel)); }
                    catch (RejectedExecutionException full) { DhClientTrace.pressure(DhClientTrace.Pressure.RECOVERY_QUEUE); }
                }
                problem = "DH terrain burst dropped; nearby tiles revalidate";
            });
        }
        /** Worker: a push that could not be applied. The reacquisition request is sent after it, so its reply is newer. */
        void deferUpdate(DhPosition position, String why) {
            deferredUpdates.incrementAndGet();
            main(() -> {
                policy.invalidate(position); forgetReceipt(position); nextScanAt = 0;
                var cancelled = cancelPending(position);
                try { worker.execute(() -> cancelled.forEach(assembly::cancel)); }
                catch (RejectedExecutionException full) { DhClientTrace.pressure(DhClientTrace.Pressure.RECOVERY_QUEUE); }
                problem = why;
            });
        }
        /** Client thread. */
        List<Integer> cancelPending(DhPosition position) {
            var ids = new ArrayList<Integer>();
            pending.forEach((id, request) -> {
                if (request.position.equals(position) && pending.remove(id, request)) {
                    ids.add(id);
                    if (request.generation && !profile.support()) send(new DhMessage.Cancel(id));
                }
            });
            return ids;
        }
        private enum PushAdmission { APPLY, OUT_OF_RANGE, PRESSURE }
        private record PushPlan(PushAdmission admission, List<Integer> cancelled, boolean hadSnapshot, long previousStamp) {}
        void receiveUpdate(DhMessage.PartialUpdate update, long arrival, long lossEpoch) throws Exception {
            if (!updatesAllowed() || levelKey == null || !levelKey.equals(update.level())) { assembly.discard(update.bufferId()); return; }
            assembly.allowPush(true);
            DhPosition affected = null;
            try (var completed = assembly.push(update, arrival, lossEpoch)) {
                affected = completed.claimedPosition(); // decode failures then defer this tile only
                var shape = new DhTerrainTile.WorldShape(level.getMinY(), level.getHeight(), level.dimensionType().hasSkyLight());
                try (var tile = completed.decode(new DhTerrainDecoder(DhLimits.DEFAULT), profile, shape)) {
                    var p = tile.position(); affected = p;
                    var plan = onMain(() -> {
                        var player = Minecraft.getInstance().player;
                        boolean inRange = updatesAllowed() && player != null
                                && DhSessionPolicy.inBorder(profile, p, config.centerX(), config.centerZ(), config.borderRadius())
                                && Math.max(Math.abs(p.x() * 4 + 2 - player.chunkPosition().x()), Math.abs(p.z() * 4 + 2 - player.chunkPosition().z()))
                                <= Math.min(effectiveRange(), config.updateDistance());
                        if (!inRange) {
                            // Any local claim to this tile is now stale, held or not.
                            if (policy.hasSnapshot(p) || stored.containsKey(p) || pending.values().stream().anyMatch(r -> r.position.equals(p)))
                                return new PushPlan(PushAdmission.OUT_OF_RANGE, List.of(), false, 0);
                            return null;
                        }
                        if (DhVoxyBridge.backlog() > INGEST_GATE || worker.getQueue().size() > 4)
                            return new PushPlan(PushAdmission.PRESSURE, List.of(), false, 0);
                        boolean had = policy.hasSnapshot(p); long previous = policy.timestamp(p);
                        if (!had) policy.invalidate(p);
                        nextScanAt = 0;
                        return new PushPlan(PushAdmission.APPLY, cancelPending(p), had, previous);
                    });
                    if (plan == null) return;
                    if (plan.admission() == PushAdmission.OUT_OF_RANGE) { deferUpdate(p, "out-of-range update; tile reacquired when in range"); return; }
                    if (plan.admission() == PushAdmission.PRESSURE) {
                        DhClientTrace.pressure(DhClientTrace.Pressure.LIVE_BACKLOG);
                        deferUpdate(p, "Voxy or decode backlog; live update deferred for reacquisition"); return;
                    }
                    plan.cancelled().forEach(assembly::cancel);
                    receivedUpdates.incrementAndGet(); lastReply = System.nanoTime();
                    int[] written = new int[1];
                    var delivered = deliver(tile, p, false, 0, true, 0, null, written);
                    int touched = 0, described = 0;
                    for (int c = 0; c < 16; c++) {
                        if (completeColumn(tile, c)) described |= 1 << c;
                        int x = c / 4 * 16, z = c % 4 * 16;
                        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
                            int column = (x + dx) * 64 + z + dz;
                            if (tile.spanCount(column) > 0 || tile.generation(column) > 0) { touched |= 1 << c; break; }
                        }
                    }
                    boolean completeEdit = delivered && (touched & ~described) == 0;
                    // DH bakes sky light on the merged stored tile; a push that wrote columns
                    // without describing the whole tile was baked without its neighbours.
                    boolean refetch = written[0] != 0 && described != 0xFFFF;
                    main(() -> {
                        // The receipt keeps the pre-push stamp: a later conditional request then
                        // learns the server row changed. Incomplete edits are reacquired normally.
                        if (refetch) { policy.refetchAfterPush(p, System.nanoTime()); forgetReceipt(p); }
                        else if (plan.hadSnapshot() && completeEdit) policy.accepted(p, 1, 1, plan.previousStamp(), System.nanoTime());
                        else { policy.invalidate(p); forgetReceipt(p); }
                    });
                }
            } catch (TimeoutException busy) {
                assembly.discard(update.bufferId());
                if (affected != null) deferUpdate(affected, "client thread was busy; live update reacquisition scheduled");
            } catch (DhCodecException e) {
                assembly.discard(update.bufferId());
                switch (e.kind()) {
                    case UNOWNED, EXPIRED, UNSUPPORTED -> {
                        // A buffer discarded after a capture loss is a lost push: scope it.
                        if (affected == null && e.kind() == DhCodecException.Kind.UNOWNED && !applyLostClaims()) { pushRecovery.set(true); recoverLoss(); }
                    }
                    case RESOURCE_LIMIT -> {
                        DhClientTrace.pressure(DhClientTrace.Pressure.LIVE_RESOURCE);
                        if (affected != null) deferUpdate(affected, "live update resource pressure; reacquisition scheduled");
                        else recoverResourcePressure();
                    }
                    default -> {
                        // A malformed push is tile-scoped where identifiable, never session-ending.
                        malformedTiles.incrementAndGet();
                        if (affected != null) deferUpdate(affected, "malformed live update; reacquisition scheduled");
                        else if (!applyLostClaims()) { pushRecovery.set(true); recoverLoss(); }
                    }
                }
            }
        }
        boolean generationAvailable(DhPosition position, ChunkPos player) {
            return player != null && config.plan() != DhMessage.GeneratorPlan.DISABLED && config.generationRate() > 0
                    && Math.max(Math.abs(position.x() * 4 + 2 - player.x()), Math.abs(position.z() * 4 + 2 - player.z()))
                    <= Math.min(32, config.generationDistance());
        }
        void requestNext(long now, ChunkPos player) {
            int range = effectiveRange();
            var context = new RangeContext(player.x(), player.z(), range, config.generationDistance(), config.syncDistance(),
                    config.plan() != DhMessage.GeneratorPlan.DISABLED && config.generationRate() > 0,
                    config.synchronize() && config.syncRate() > 0, config.centerX(), config.centerZ(), config.borderRadius());
            if (!context.equals(rangeContext)) { rangeContext = context; rangeEpoch = RANGE_EPOCHS.incrementAndGet(); }
            if (receiptStore == null && !receiptsLoading && levelKey != null) openReceipts();
            // Until persisted receipts are known, a tile could be full-requested and
            // overwrite terrain whose receipt would have made it a cheap revalidation.
            if (receiptsLoading) { activity = Activity.WORKING; return; }
            probeReceipts(player, range);
            boolean waitingServer = false;
            int cx = Math.floorDiv(player.x(), 4), cz = Math.floorDiv(player.z(), 4), rings = (range + 3) / 4;
            // Bound retry preference while retaining the untouched frontier's first priority.
            for (int priority : retryBurst >= 3 ? new int[]{0, 2, 1} : new int[]{0, 1, 2}) {
                DhPosition oldest = null; long oldestDue = Long.MAX_VALUE;
                for (int ring = 0; ring <= rings; ring++) for (int dx = -ring; dx <= ring; dx++) for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    var p = new DhPosition(6, cx + dx, cz + dz);
                    int distance = Math.max(Math.abs(p.x() * 4 + 2 - player.x()), Math.abs(p.z() * 4 + 2 - player.z()));
                    if (distance > range) continue;
                    boolean inBorder = DhSessionPolicy.inBorder(profile, p, config.centerX(), config.centerZ(), config.borderRadius());
                    boolean reachable = inBorder
                            && (policy.rangeNeedsGeneration(p) || !policy.hasSnapshot(p) && !config.synchronize() ? generationAvailable(p, player)
                            : config.synchronize() && config.syncRate() > 0 && distance <= config.syncDistance());
                    policy.reconcileRange(p, rangeEpoch, reachable, now);
                    policy.reconcileGeneration(p, generationAvailable(p, player), now);
                    if (policy.acquisitionPriority(p) != priority) continue;
                    // A persisted receipt waits for its destination probe; never full-request it first.
                    // That includes one the policy evicted on a long flight (bounded window).
                    // Only a true eviction re-probes: a tile on cooldown or deferred keeps its policy state.
                    if (!unprobed.contains(p) && stored.containsKey(p) && !policy.contains(p)) unprobed.add(p);
                    if (unprobed.contains(p)) continue;
                    int x = p.x() * 4, z = p.z() * 4;
                    boolean any = false;
                    for (int ox = 0; ox < 4; ox++) for (int oz = 0; oz < 4; oz++) any |= policy.eligible(x + ox, z + oz);
                    if (!any || !policy.due(p, now) || !inBorder) continue;
                    if (pending.values().stream().anyMatch(r -> r.position.equals(p))) continue;
                    if (!policy.syncAllowed(p, syncAvailable(), config.syncRate())) continue;
                    boolean gen = profile.support() || !config.synchronize() || policy.generationNeeded(p);
                    if (gen && (!generationAvailable(p, player) || pending.values().stream().anyMatch(Pending::generation))) {
                        if (!profile.support() && policy.generationNeeded(p)
                                && (config.plan() == DhMessage.GeneratorPlan.DISABLED || config.generationRate() == 0
                                || Math.max(Math.abs(x + 2 - player.x()), Math.abs(z + 2 - player.z())) > Math.min(32, config.generationDistance())))
                            policy.absent(p, now, false);
                        else waitingServer = true;
                        continue;
                    }
                    if (pending.size() >= 2) { waitingServer = true; continue; }
                    if (requestWait(gen, now) > 0) { waitingServer = true; continue; }
                    // A minimum interval shorter than a sweep must still service every due
                    // tile. Stable ring order breaks equal-deadline ties; renewal then moves
                    // the tile behind older outstanding work.
                    if (priority == 2) {
                        long due = policy.dueNanos(p);
                        if (oldest == null || due < oldestDue) { oldest = p; oldestDue = due; }
                        continue;
                    }
                    request(p, priority, gen, now); activity = Activity.WORKING; return;
                }
                if (oldest != null) {
                    request(oldest, priority, profile.support() || !config.synchronize() || policy.generationNeeded(oldest), now);
                    activity = Activity.WORKING; return;
                }
            }
            activity = !pending.isEmpty() || probeInFlight || receiptsLoading ? Activity.WORKING
                    : waitingServer ? Activity.WAITING_SERVER : Activity.IDLE;
        }
        void request(DhPosition p, int priority, boolean gen, long now) {
            int id = IDS.incrementAndGet();
            if (id <= 0) { fail("DH request ID space exhausted; restart client", Reason.SOURCE_STOPPED); return; }
            long stamp = priority == 2 ? policy.syncTimestamp(p) : 0;
            long lifetime = requestLifetime();
            pending.put(id, new Pending(p, gen, now, stamp, rangeEpoch, lifetime)); lastRequest = now;
            nextScanAt = Math.min(nextScanAt, now + 50_000_000L);
            if (gen) lastGenerationRequest = now; else lastSyncRequest = now;
            if (priority == 1) retryBurst = Math.min(3, retryBurst + 1); else retryBurst = 0;
            requested.incrementAndGet();
            try { worker.execute(() -> {
                try {
                    if (!active || !pending.containsKey(id)) return; assembly.expect(id, p, lifetime);
                    main(() -> { if (pending.containsKey(id)) {
                        DhClientTrace.request(id, p, gen, stamp);
                        send(new DhMessage.Request(id, levelKey, p, gen && stamp == 0 ? null : stamp));
                    }});
                } catch (Exception e) { fail(e.getMessage(), Reason.SOURCE_STOPPED); }
            }); } catch (RejectedExecutionException e) { fail("DH worker queue full", Reason.SOURCE_STOPPED); }
        }
        void main(Runnable task) { Minecraft.getInstance().execute(() -> { if (active && session == this && Minecraft.getInstance().level == level) task.run(); }); }
        void receive(byte[] bytes, long arrivalNanos, long lossEpoch) throws Exception {
            if (pushRecovery.get()) recoverLoss();
            wireBytes.addAndGet(bytes.length);
            if (config == null) {
                DhProfile advertised;
                try (var discovery = budget.reserve(bytes.length * 8L + 1024)) { advertised = DhProtocolDiscovery.advertised(bytes); }
                if (advertised == DhProfile.SUPPORT_15 && profile != DhProfile.SUPPORT_15) {
                    // DH Support answers on the same connection; switch families without reconnecting.
                    main(() -> {
                        if (Minecraft.getInstance().getConnection() != connection) return;
                        discoveredProfiles.put(connection, DhProfile.SUPPORT_15);
                        session = null; beginRetire(false);
                        status = "Detected DH Support; negotiating on the existing Minecraft connection";
                    });
                    return;
                }
                if (advertised == DhProfile.OFFICIAL_15 || DhProtocolDiscovery.version(bytes) == 15 && profile.version() != 15) {
                    fail("Server runs Distant Horizons protocol 15 (3.2.x); ask the admin to update to DH 3.3.x", Reason.OLD_DH_PROTOCOL);
                    return;
                }
                if (DhProtocolDiscovery.version(bytes) != profile.version()) {
                    fail("Unsupported DH protocol advertisement; ordinary Minecraft remains connected", Reason.OLD_DH_PROTOCOL);
                    return;
                }
            }
            try (var owned = codec.decodeOwned(bytes, budget)) {
                var message = owned.message();
                if (message instanceof DhMessage.Unknown) { unknownControls.incrementAndGet(); return; }
                if (message instanceof DhMessage.Split split) bandwidthRamp.received(split.length(), arrivalNanos);
                if (message instanceof DhMessage.CloseReason close) { fail(close.reason(), Reason.SOURCE_STOPPED); return; }
                if (message instanceof DhMessage.SessionConfig cfg) {
                    boolean priorSync = syncAvailable(); config = cfg; assembly.allowPush(updatesAllowed());
                    if (!priorSync && syncAvailable()) main(() -> { connectionState.forgetLoads(dimension); visibleCenter = null; visible = new HashSet<>(); });
                    main(() -> nextScanAt = 0); problem = "acquiring terrain"; return;
                }
                if (message instanceof DhMessage.LevelInit init) {
                    if (!init.dimension().equals(dimension)) return;
                    String key = init.levelKey().isEmpty() ? init.dimension() : init.levelKey();
                    if (levelKey != null && !levelKey.equals(key) && !pending.isEmpty()) {
                        fail("DH world key changed; reconnect to bind the new world", Reason.WORLD_CHANGED); return;
                    }
                    main(() -> {
                        if (!connectionState.bindWorld(init.dimension(), key)) fail("DH logical world changed; reconnect before acquiring terrain", Reason.WORLD_CHANGED);
                        else if (serverKey != null && !serverKey.equals(init.serverKey())) fail("DH server key changed; reconnect to bind the new source", Reason.WORLD_CHANGED);
                        else { levelKey = key; serverKey = init.serverKey(); nextScanAt = 0; }
                    }); return;
                }
                if (message instanceof DhMessage.Split split) {
                    assembly.allowPush(updatesAllowed());
                    if (!pending.isEmpty() || updatesAllowed()) assembly.accept(split, arrivalNanos, lossEpoch);
                    return;
                }
                if (message instanceof DhMessage.Refusal refusal) {
                    var request = pending.remove(refusal.requestId()); assembly.cancel(refusal.requestId());
                    if (request != null) {
                        DhClientTrace.refusal(refusal.requestId(), refusal.reason());
                        refused.incrementAndGet();
                        // ExceptionMessage's pinned list: 0 rate limit, 1 range, 2 rejected,
                        // 3 requires splitting. Unknown reasons get the bounded retry policy.
                        main(() -> { unanswered = 0; if (refusal.reason() == 0) {
                                long now = System.nanoTime(); policy.defer(request.position, now);
                                // Official DH drains the affected queue's request allowance after rate limiting.
                                if (request.generation) generationBackoffUntil = Math.max(generationBackoffUntil, now + 1_000_000_000L);
                                else syncBackoffUntil = Math.max(syncBackoffUntil, now + 1_000_000_000L);
                            }
                            else if (refusal.reason() == 1) policy.rangeRefused(request.position, request.rangeEpoch, request.generation);
                            else policy.cooldown(request.position, System.nanoTime()); });
                    }
                    problem = "server refusal " + refusal.reason() + ": " + sanitizeReason(refusal.message()); return;
                }
                if (message instanceof DhMessage.PartialUpdate update) { receiveUpdate(update, arrivalNanos, lossEpoch); return; }
                if (message instanceof DhMessage.Response response) receiveResponse(response, arrivalNanos, lossEpoch);
            }
        }
        void receiveResponse(DhMessage.Response response, long arrivalNanos, long lossEpoch) throws Exception {
            var request = pending.get(response.requestId());
            if (request == null) { if (response.bufferId() != null) assembly.discard(response.bufferId()); return; } // obsolete request ID
            try (var completed = assembly.response(response, arrivalNanos, lossEpoch)) {
                request.answered = true;
                DhClientTrace.reply(response.requestId(), request.position, completed != null,
                        Math.max(0, (arrivalNanos - request.started) / 1_000_000L));
                lastReply = System.nanoTime();
                main(() -> { unanswered = 0; nextScanAt = 0; problem = "acquiring terrain"; });
                if (completed == null) {
                    main(() -> {
                        if (!pending.remove(response.requestId(), request)) return;
                        long now = System.nanoTime();
                        if (request.timestamp > 0) {
                            // Conditional request answered "unchanged": the destination was probed
                            // (persisted receipt) or written this connection under this stamp.
                            if (policy.syncTimestamp(request.position) == request.timestamp && policy.accepted(request.position, 1, 1, request.timestamp, now))
                                rememberReceipt(request.position, request.timestamp);
                            else policy.deferUnverified(request.position, now);
                        } else if (request.generation) policy.defer(request.position, now);
                        else {
                            var player = Minecraft.getInstance().player;
                            policy.absent(request.position, now, generationAvailable(request.position, player == null ? null : player.chunkPosition()));
                        }
                    });
                    return;
                }
                var shape = new DhTerrainTile.WorldShape(level.getMinY(), level.getHeight(), level.dimensionType().hasSkyLight());
                try (var tile = completed.decode(new DhTerrainDecoder(DhLimits.DEFAULT), profile, shape)) {
                    deliver(tile, request.position, request.generation, tile.modifiedTimestamp(), false, response.requestId(), request, null);
                    main(() -> pending.remove(response.requestId(), request));
                }
            } catch (TimeoutException e) {
                request.answered = true; assembly.cancel(response.requestId());
                main(() -> {
                    if (pending.remove(response.requestId(), request)) {
                        policy.defer(request.position, System.nanoTime());
                        if (request.generation && !profile.support()) send(new DhMessage.Cancel(response.requestId()));
                        problem = "client thread was busy; terrain retry scheduled";
                    }
                });
            } catch (DhCodecException e) {
                request.answered = true; assembly.cancel(response.requestId());
                boolean malformed = e.kind() == DhCodecException.Kind.MALFORMED || e.kind() == DhCodecException.Kind.UNSUPPORTED
                        || e.kind() == DhCodecException.Kind.PROTOCOL_MISMATCH;
                if (malformed) {
                    malformedTiles.incrementAndGet();
                    LSSLogger.warn("DH tile rejected as malformed (" + e.kind() + "); it is retried with backoff");
                }
                main(() -> {
                    if (pending.remove(response.requestId(), request)) {
                        // Tile-scoped: a malformed tile takes a rejection strike; pressure defers.
                        if (malformed) policy.cooldown(request.position, System.nanoTime()); else policy.defer(request.position, System.nanoTime());
                        if (request.generation && !profile.support()) send(new DhMessage.Cancel(response.requestId()));
                    }
                });
            }
        }

        /**
         * Worker. Converts once on the worker and hands the whole tile to the client
         * thread in ONE task (authority recheck, ingest, acceptance). A cheap prefilter
         * hop first skips vanilla-held columns before conversion.
         * {@code partial}: a push; only its complete columns are written.
         * {@code owner}: a whole reply is written only while its request is still pending;
         * a push, recovery or reset that cancelled it meanwhile voids the reply.
         * {@code written}: when given, receives the mask of columns handed to Voxy.
         */
        boolean deliver(DhTerrainTile tile, DhPosition position, boolean generation, long stamp, boolean partial,
                int requestId, Pending owner, int[] written) throws Exception {
            int tx = tile.position().x() * 4, tz = tile.position().z() * 4;
            int complete = 0;
            for (int c = 0; c < 16; c++) if (completeColumn(tile, c)) complete |= 1 << c;
            int eligible = onMain(() -> {
                int mask = 0;
                if (owner != null && pending.get(requestId) != owner) return -1;
                if (partial && !updatesAllowed()) return 0;
                for (int c = 0; c < 16; c++) if (policy.eligible(tx + c / 4, tz + c % 4) && !loaded(level, tx + c / 4, tz + c % 4)) mask |= 1 << c;
                return mask;
            });
            if (eligible < 0) return false;
            int convert = eligible & complete;
            skipped.addAndGet(16 - Integer.bitCount(eligible));
            VoxelColumnData[] data;
            try (var conversion = budget.reserve(Integer.bitCount(convert) * ((long) tile.world().height() / 16 * 65_536 + (long) tile.materials().size() * 128))) {
                data = converter.convertTile(tile, convert, partial);
            }
            int eligibleMask = eligible, completeMask = complete;
            return onMain(() -> {
                int accepted = 0, attempted = 0;
                if (owner != null && pending.get(requestId) != owner) return false;
                if (partial && !updatesAllowed()) return false;
                for (int c = 0; c < 16; c++) {
                    if ((eligibleMask & (1 << c)) == 0) continue;
                    int x = tx + c / 4, z = tz + c % 4;
                    if (data[c] == null) continue; // incomplete column: never fabricated as air
                    attempted |= 1 << c;
                    // Main-thread authority at handoff: vanilla may have resolved this column meanwhile.
                    if (!policy.eligible(x, z) || loaded(level, x, z)) { skipped.incrementAndGet(); accepted |= 1 << c; continue; }
                    invalidateNativeCache();
                    var rejected = new AtomicBoolean();
                    var handle = new LSSApi.IngestFailureHandle() {
                        public void report() { rejected.set(true); }
                        public boolean isActive() { return active && session == Session.this; }
                        public Runnable deferAcceptance() { throw new IllegalStateException("Voxy sink cannot defer DH acceptance"); }
                    };
                    var column = data[c];
                    LSSApi.withIngestFailureHandle(level.dimension(), x, z, handle, () -> DhVoxyBridge.ingest(level, x, z, column));
                    if (written != null) written[0] |= 1 << c;
                    if (!rejected.get()) { columns.incrementAndGet(); accepted |= 1 << c; }
                    else failedColumns.incrementAndGet();
                }
                if (partial) return (accepted & attempted) == attempted && (eligibleMask & completeMask & ~attempted) == 0;
                boolean success = policy.accepted(position, eligibleMask, accepted, stamp, System.nanoTime());
                // A tile just delivered is as fresh as a load check: the first portal return
                // within the debounce must not revalidate the whole disc again.
                if (success) markLoaded(connectionState.loadedAt(dimension), position, System.nanoTime());
                if (success) {
                    if (accepted != 0) tiles.incrementAndGet();
                    rejectedTiles = 0;
                    if (attempted == (eligibleMask & completeMask) && (eligibleMask & ~completeMask) == 0) rememberReceipt(position, stamp);
                } else {
                    incomplete.incrementAndGet();
                    if (attempted != 0 && (accepted & attempted) == 0 && ++rejectedTiles >= 3)
                        fail("Voxy rejected three whole DH tiles; check ingest settings and restart/reset", Reason.SOURCE_STOPPED);
                }
                if (active && (eligibleMask & ~attempted) != 0 && !generation && !policy.retryExhausted(position)) {
                    var player = Minecraft.getInstance().player;
                    policy.absent(position, System.nanoTime(), player != null && generationAvailable(position, player.chunkPosition()));
                }
                return success;
            });
        }

        <T> T onMain(Callable<T> action) throws Exception {
            var result = new CompletableFuture<T>();
            Minecraft.getInstance().execute(() -> {
                if (result.isDone()) return; // A timed-out tile must not execute a late handoff.
                if (!active || session != this || Minecraft.getInstance().level != level) {
                    result.completeExceptionally(new CancellationException("DH epoch retired")); return;
                }
                try { if (!result.complete(action.call())) { /* timed out first */ } }
                catch (Exception e) { result.completeExceptionally(e); }
            });
            try { return result.get(handoffTimeoutMillis, TimeUnit.MILLISECONDS); }
            catch (ExecutionException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                throw e;
            }
            catch (TimeoutException | InterruptedException e) { result.cancel(false); throw e; }
        }
        void invalidateNativeCache() {
            if (cacheInvalidated) return;
            invalidateCurrentNativeCache(); cacheInvalidated = true;
        }
    }
}
