package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.V16ClientWire;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * Client twin of the server's {@code HandshakeGate}: owns the per-connection session
 * state and the JOIN / SessionConfig / DISCONNECT ladders so they are unit-testable
 * without a running client. {@code LSSClientNetworking} wires the production seams
 * (real handshake send, manager construction with live server-address resolution)
 * and delegates its event handlers and static getters here.
 *
 * <p>Ladder contracts pinned by {@code ClientSessionGateTest}:
 * <ul>
 *   <li>A foreign-version SessionConfig only disables ({@code serverEnabled=false}):
 *       {@code sessionConfigReceived} is untouched and any existing manager is kept —
 *       the reply is a statement about the server, not a session boundary.</li>
 *   <li>A second valid SessionConfig (a server can legitimately re-send one, e.g.
 *       Paper {@code /reload}) retires the old manager with the same teardown as
 *       disconnect — report undispatched, then save cache — before building the
 *       fresh one, so a session's stamps are never silently dropped.</li>
 *   <li>The DISCONNECT routine zeroes every piece of session state, including the
 *       columnsReceived / bytesReceived / connectionStartMs counters.</li>
 * </ul>
 */
final class ClientSessionGate {

    /** Builds the per-session request manager for a valid enabled config. Seam for tests. */
    @FunctionalInterface
    interface ManagerFactory {
        LodRequestManager create(SessionConfigS2CPayload config);
    }

    /** Ticks (client tick = 1/20 s) to wait for a SessionConfig after the v18 handshake
     *  before re-handshaking as v16 — the discovery fallback for an old server that silently
     *  drops a version-18 handshake. Generous, so a healthy v18 server (config in well under
     *  a second) never triggers it. */
    static final int V16_DISCOVERY_DELAY_TICKS = 60;

    private final ClientColumnProcessor columnProcessor;
    // Sends the C2S handshake announcing the given protocol version; injected so tests can
    // drive both the discovery fallback and the send-failure swallow.
    private final IntConsumer handshakeSender;
    private final ManagerFactory managerFactory;

    // ---- v16 server backward-compat discovery (main thread) ----
    // Armed at JOIN when a v18 handshake was sent and compat is enabled; disarmed the instant
    // any SessionConfig arrives. If it expires with no config, we announce version 16 once.
    private boolean v16DiscoveryArmed = false;
    private int ticksSinceHandshake = 0;
    private boolean v16FallbackSent = false;
    private volatile boolean isV16Server = false;

    private volatile boolean serverEnabled = false;
    // True once a SessionConfig reply (compatible version) arrived for the current
    // connection — distinguishes "server said disabled" from "no LSS server / not yet
    // answered". Read by the dev-only soak hook to snapshot disabled sessions.
    private volatile boolean sessionConfigReceived = false;
    private volatile int serverLodDistance = 0;
    private final AtomicLong columnsReceived = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private volatile long connectionStartMs = 0;
    private volatile LodRequestManager requestManager;

    ClientSessionGate(ClientColumnProcessor columnProcessor, IntConsumer handshakeSender,
                      ManagerFactory managerFactory) {
        this.columnProcessor = columnProcessor;
        this.handshakeSender = handshakeSender;
        this.managerFactory = managerFactory;
    }

    boolean isServerEnabled() { return this.serverEnabled; }
    boolean isV16Server() { return this.isV16Server; }
    boolean hasReceivedSessionConfig() { return this.sessionConfigReceived; }
    int getServerLodDistance() { return this.serverLodDistance; }
    long getColumnsReceived() { return this.columnsReceived.get(); }
    long getBytesReceived() { return this.bytesReceived.get(); }
    long getConnectionStartMs() { return this.connectionStartMs; }
    LodRequestManager getRequestManager() { return this.requestManager; }

    /** Network-thread accounting for an arriving column frame (before the main-thread hop). */
    void recordColumnFrame(long estimatedBytes) {
        this.columnsReceived.incrementAndGet();
        this.bytesReceived.addAndGet(estimatedBytes);
    }

    /**
     * JOIN: reset all session state, then handshake only when server LODs are wanted,
     * this is not the local integrated server, and an LSSApi consumer exists. A send
     * failure (e.g. vanilla server without the LSS channel) is swallowed at debug —
     * the client stays dormant for the connection: no manager, no retry, no crash.
     *
     * @param localIntegratedServer connected to this client's own integrated server
     *                              (with the test override off) — never activate there
     */
    void onJoin(boolean receiveServerLods, boolean localIntegratedServer, boolean hasConsumers,
                boolean enableV16ServerCompat) {
        this.serverEnabled = false;
        this.sessionConfigReceived = false;
        this.serverLodDistance = 0;
        this.requestManager = null;
        // Clear v16 discovery + decode state so nothing survives from a prior connection.
        this.v16DiscoveryArmed = false;
        this.ticksSinceHandshake = 0;
        this.v16FallbackSent = false;
        this.isV16Server = false;
        V16ClientWire.reset();

        if (!receiveServerLods) return;
        if (localIntegratedServer) return;
        // Without a consumer the server would ignore our requests anyway — stay silent.
        if (!hasConsumers) return;

        try {
            this.handshakeSender.accept(LSSConstants.PROTOCOL_VERSION);
        } catch (Exception e) {
            LSSLogger.debug("Handshake send failed (server likely doesn't have LSS): " + e.getMessage());
        }
        // Arm the discovery fallback only after a real v18 handshake attempt: if no
        // SessionConfig arrives, tickV16Discovery() re-handshakes as v16 exactly once.
        this.v16DiscoveryArmed = enableV16ServerCompat;
    }

    /**
     * Main-thread discovery tick (runs every client tick, even before a session). No-op on
     * the v18 happy path: a v18 server's SessionConfig disarms it well before the delay, so
     * the v16 handshake never sends. Fires exactly once — if the delay elapses with no
     * SessionConfig, announce version 16 (the old server accepts that where it dropped v18).
     */
    void tickV16Discovery() {
        if (!this.v16DiscoveryArmed) return;
        if (this.sessionConfigReceived || this.v16FallbackSent) return;
        if (++this.ticksSinceHandshake < V16_DISCOVERY_DELAY_TICKS) return;
        this.v16FallbackSent = true;
        LSSLogger.debug("No v18 session config after " + V16_DISCOVERY_DELAY_TICKS
                + " ticks — retrying handshake as protocol " + LSSConstants.V16_COMPAT_PROTOCOL_VERSION
                + " (legacy-server discovery)");
        try {
            this.handshakeSender.accept(LSSConstants.V16_COMPAT_PROTOCOL_VERSION);
        } catch (Exception e) {
            LSSLogger.debug("v16 fallback handshake send failed: " + e.getMessage());
        }
    }

    /** SessionConfig reply ladder. Main client thread. */
    void onSessionConfig(SessionConfigS2CPayload payload, boolean hasConsumers,
                         boolean enableV16ServerCompat) {
        // Any SessionConfig — even an incompatible one — disarms the discovery fallback: the
        // server answered, so there is nothing to re-discover.
        this.v16DiscoveryArmed = false;

        LSSLogger.info("Server session config received (protocol v" + payload.protocolVersion()
                + ", LOD distance: " + payload.lodDistanceChunks() + " chunks"
                + ", enabled: " + payload.enabled() + ")");

        int version = payload.protocolVersion();
        boolean v16 = version == LSSConstants.V16_COMPAT_PROTOCOL_VERSION && enableV16ServerCompat;
        if (version != LSSConstants.PROTOCOL_VERSION && !v16) {
            LSSLogger.warn("Server has incompatible LSS protocol version " + version
                    + " (client: " + LSSConstants.PROTOCOL_VERSION + "), LOD distribution disabled");
            this.serverEnabled = false;
            return;
        }
        this.isV16Server = v16;
        if (v16) {
            LSSLogger.info("Connected to a legacy (protocol " + version + ") server — using "
                    + "v16 backward-compat wire (load-only: already-generated terrain)");
        }

        // Clamp the server-supplied LOD distance to the same bounds the server enforces on
        // itself (ServerConfigBase.validate) — the one numeric field left on the wire. A
        // hostile or compromised server is fully untrusted input here: the LOD distance
        // bounds the scan-ring loop. The unbounded-allocation vector that first motivated
        // this clamp is closed by construction (the send buffers are fixed-size and the
        // scan budget is the WANT_SET_BUDGET constant), but the clamp still bounds the ring
        // walk itself — an unclamped LOD distance is a CPU-stall vector, so it stays.
        var config = clampToProtocolBounds(payload);

        this.serverEnabled = config.enabled();
        this.sessionConfigReceived = true;
        this.serverLodDistance = config.lodDistanceChunks();

        // Without a registered LSSApi consumer there is nothing to deliver columns
        // to — skip session setup entirely (capability is sampled at JOIN).
        if (config.enabled() && hasConsumers) {
            // A re-sent config mid-session replaces the manager: retire the old one
            // exactly like a disconnect so its session's stamps are reported and saved,
            // not silently dropped. (A consumer rejection racing the swap reports to the
            // NEW manager, whose dimension guard drops it until its first tick — same
            // accepted residual as the disconnect race.)
            var previous = this.requestManager;
            if (previous != null) {
                teardownManager(previous);
            }
            this.connectionStartMs = System.currentTimeMillis();
            this.requestManager = this.managerFactory.create(config);
        }
    }

    /**
     * Re-clamp a server-supplied session config to the protocol's own bounds. The server
     * clamps these on load; this defends against a server that does not (hostile, buggy, or
     * a different implementation), so a downstream consumer can never turn an out-of-range
     * value into an unbounded allocation or loop.
     */
    private static SessionConfigS2CPayload clampToProtocolBounds(SessionConfigS2CPayload p) {
        return new SessionConfigS2CPayload(
                p.protocolVersion(),
                p.enabled(),
                Math.clamp(p.lodDistanceChunks(), LSSConstants.MIN_LOD_DISTANCE, LSSConstants.MAX_LOD_DISTANCE),
                p.generationEnabled());
    }

    /**
     * Teardown for a manager leaving service (disconnect, or replacement by a re-sent
     * session config): unstamp columns still queued for decode BEFORE the cache flush —
     * their received-stamps would otherwise persist for data no consumer ever saw. The
     * report → disconnect → saveCache order is load-bearing.
     */
    void teardownManager(LodRequestManager manager) {
        this.columnProcessor.reportUndispatched(manager);
        manager.disconnect();
        manager.saveCache();
    }

    /** DISCONNECT: tear down the live manager, then zero all session state and counters. */
    void onDisconnect() {
        var manager = this.requestManager;
        if (manager != null) {
            // (A column the drain thread polled concurrently still dispatches; if its
            // consumer then rejects, that single report lands after requestManager is
            // nulled and is dropped — at most one stale stamp per disconnect, healed
            // by the next session unless the server kept its timestamp cache. Accepted
            // residual.)
            teardownManager(manager);
        }
        this.columnProcessor.shutdown();
        this.columnProcessor.resetStats();
        this.serverEnabled = false;
        this.sessionConfigReceived = false;
        this.serverLodDistance = 0;
        this.columnsReceived.set(0);
        this.bytesReceived.set(0);
        this.connectionStartMs = 0;
        this.requestManager = null;
        // Clear v16 discovery + decode state so nothing leaks into the next connection.
        this.v16DiscoveryArmed = false;
        this.ticksSinceHandshake = 0;
        this.v16FallbackSent = false;
        this.isV16Server = false;
        V16ClientWire.reset();
    }
}
