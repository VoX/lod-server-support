package dev.vox.lss.networking.client;

import dev.vox.lss.networking.payloads.SoakDialectOverride;
import dev.vox.lss.common.Brand;
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

    /** Ticks (client tick = 1/20 s) of SessionConfig silence before the discovery ladder
     *  advances one rung (C3, XVER §6: announce 20 → 19 → 16). Generous (10 s per rung —
     *  raised from 5 s, 2026-08-13): a healthy same-version server replies in well under a
     *  second, but a SATURATED LINK can hold the whole join-time burst — the config echo
     *  included — past 5 s (measured live through a 1 Mbps throttled proxy: the ladder
     *  walked all three rungs against a healthy current server), and every false walk
     *  costs a server-side dialect flip plus manager-rebuild churn that the establish
     *  re-announce then has to heal. The margin's cost lands only on genuine legacy
     *  servers — slower first LOD (worst case to a v16 session: ~20 s of silence after
     *  the first announce), accepted per user decision 2026-08-13. Should a rung slip
     *  through anyway, establishment re-announces the accepted version (see
     *  {@link #onSessionConfig}) and the downgrade guard re-asserts it on raced echoes. */
    static final int V16_DISCOVERY_DELAY_TICKS = 200;

    private final ClientColumnProcessor columnProcessor;
    // Sends the C2S handshake announcing the given protocol version; injected so tests can
    // drive both the discovery ladder and the send-failure swallow.
    private final IntConsumer handshakeSender;
    private final ManagerFactory managerFactory;

    // ---- legacy-server discovery ladder (main thread; C3, XVER §6) ----
    // Armed at JOIN when a handshake went out and a lower enabled rung exists; disarmed the
    // instant any SessionConfig arrives, on a send throw, and at the terminal rung. Each
    // V16_DISCOVERY_DELAY_TICKS of silence advances one rung: 20 → 19 (enableV19ServerCompat)
    // → 16 (enableV16ServerCompat). NO v18 rung (§6 decision: every v0.7.x-v0.8.x server
    // carries the server-side v16 rung, so the ladder still lands every legacy server).
    private boolean discoveryArmed = false;
    private int ticksSinceHandshake = 0;
    /** The rung this connection last announced (JOIN or a ladder advance). The downgrade
     *  guard re-announces {@link #sessionVersion}, never this. */
    private int currentAnnounce = 0;
    /** This connection announced 19 at some point — the acceptance gate for a 19 echo
     *  (an unsolicited 19 config on a never-announced-19 connection stays foreign). */
    private boolean announced19ThisConnection = false;
    /** The version JOIN announced first (the acceptance primary — review m7: a
     *  per-connection field, not the static lever read; 0 before any join falls back
     *  to the static, keeping direct-drive test rigs meaningful). */
    private int primaryAnnounce = 0;
    // Captured at JOIN (the ladder advance needs them at tick time).
    private boolean v19CompatEnabled = true;
    private boolean v16CompatEnabled = true;
    /** The ESTABLISHED session's protocol version (0 = none) — the downgrade guard's
     *  reference and the re-announce value. */
    private volatile int sessionVersion = 0;

    /** The ESTABLISHED session's protocol version (0 before any SessionConfig accept) —
     *  the C6 negotiated-protocol observability read (soak snapshot `session_version`):
     *  a dialect-lever soak that silently degraded to another rung becomes visible
     *  instead of passing format-blind laws on the wrong dialect. */
    int getSessionVersion() {
        return this.sessionVersion;
    }
    private volatile boolean isV16Server = false;

    private volatile boolean serverEnabled = false;
    // True once a SessionConfig reply (compatible version) arrived for the current
    // connection — distinguishes "server said disabled" from "no LSS server / not yet
    // answered". Read by the dev-only soak hook to snapshot disabled sessions.
    private volatile boolean sessionConfigReceived = false;
    private volatile int serverLodDistance = 0;
    private final AtomicLong columnsReceived = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong wireBytesReceived = new AtomicLong();
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
    long getWireBytesReceived() { return this.wireBytesReceived.get(); }
    long getConnectionStartMs() { return this.connectionStartMs; }
    LodRequestManager getRequestManager() { return this.requestManager; }

    /** Network-thread accounting for an arriving column frame (before the main-thread hop).
     *  {@code estimatedBytes} is RAW-denominated (decode-work accounting, law A2's client
     *  half); {@code wireBytes} is the SHIPPED size (codec-1 frames — the observed-bandwidth
     *  match). */
    void recordColumnFrame(long estimatedBytes, long wireBytes) {
        this.columnsReceived.incrementAndGet();
        this.bytesReceived.addAndGet(estimatedBytes);
        this.wireBytesReceived.addAndGet(wireBytes);
    }

    /** Pre-compression shape (wire == estimated) — existing tests/rigs. */
    void recordColumnFrame(long estimatedBytes) {
        recordColumnFrame(estimatedBytes, estimatedBytes);
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
        onJoin(receiveServerLods, localIntegratedServer, hasConsumers, enableV16ServerCompat, true);
    }

    void onJoin(boolean receiveServerLods, boolean localIntegratedServer, boolean hasConsumers,
                boolean enableV16ServerCompat, boolean enableV19ServerCompat) {
        // Defensive: under Fabric's lifecycle DISCONNECT always precedes the next JOIN,
        // but if a manager ever survived to here, dropping it without teardown would lose
        // its cache save. Self-sufficiency over the lifecycle assumption.
        if (this.requestManager != null) {
            teardownManager(this.requestManager);
        }
        this.serverEnabled = false;
        this.sessionConfigReceived = false;
        this.serverLodDistance = 0;
        this.requestManager = null;
        // Clear ladder + decode state so nothing survives from a prior connection.
        this.discoveryArmed = false;
        this.ticksSinceHandshake = 0;
        this.currentAnnounce = 0;
        this.primaryAnnounce = 0;
        this.announced19ThisConnection = false;
        this.sessionVersion = 0;
        this.isV16Server = false;
        this.v16CompatEnabled = enableV16ServerCompat;
        this.v19CompatEnabled = enableV19ServerCompat;
        V16ClientWire.reset();
        // The alias axis's per-session latch resets HERE and not at disconnect: a
        // play→config reconfiguration fires neither loader's disconnect, and JOIN is
        // the one event that reliably brackets a play session (plan §2.1, §9 M-B2).
        AliasLatch.resetForJoin();
        // The force grant gets the same JOIN bracket (panel fix): a reconfiguration
        // fires no disconnect, and a grant armed with a live manager must not survive
        // into a rejoined session where the no-session branch would do MORE than the
        // armed prompt disclosed.
        ResetCoordinator.clearForceGrant();

        if (!receiveServerLods) return;
        if (localIntegratedServer) return;
        // Without a consumer the server would ignore our requests anyway — stay silent.
        if (!hasConsumers) return;

        try {
            // Mark-before-send: the wire's legacy-dialect arming keys on the LAST version
            // this client announced (V16ClientWire.markAnnouncedVersion), and the mark must be
            // visible to the netty thread before any reply can arrive (wire causality).
            // announceVersion() is PROTOCOL_VERSION in every production launch; only the
            // soak harness's -Dlss.soak.dialect legacy-emulation lever lowers it (C2/C3 —
            // under the lever the ladder simply STARTS at rung 19).
            int announce = SoakDialectOverride.announceVersion();
            this.currentAnnounce = announce;
            this.primaryAnnounce = announce;
            this.announced19ThisConnection = announce == LSSConstants.V19_COMPAT_PROTOCOL_VERSION;
            V16ClientWire.markAnnouncedVersion(announce);
            this.handshakeSender.accept(announce);
            // Arm the ladder only after the handshake actually went out AND a lower enabled
            // rung exists. On a send throw (vanilla / no-LSS server) there is nothing to
            // discover — every further rung is equally doomed.
            this.discoveryArmed = nextRung(announce) != 0;
        } catch (Exception e) {
            LSSLogger.debug("Handshake send failed (server likely doesn't have " + Brand.shortName() + "): " + e.getMessage());
        }
    }

    /** The next enabled discovery rung below {@code from}, or 0 at the ladder's end
     *  (C3, XVER §6: 20 → 19 → 16, no v18 rung). */
    private int nextRung(int from) {
        if (from > LSSConstants.V19_COMPAT_PROTOCOL_VERSION && this.v19CompatEnabled) {
            return LSSConstants.V19_COMPAT_PROTOCOL_VERSION;
        }
        if (from > LSSConstants.V16_COMPAT_PROTOCOL_VERSION && this.v16CompatEnabled) {
            return LSSConstants.V16_COMPAT_PROTOCOL_VERSION;
        }
        return 0;
    }

    /**
     * Main-thread discovery-ladder tick (runs every client tick, even before a session).
     * No-op on the same-version happy path: the server's SessionConfig disarms it well
     * before the delay. Each {@link #V16_DISCOVERY_DELAY_TICKS} of silence advances one
     * rung (20 → 19 → 16); the terminal rung disarms.
     */
    void tickDiscoveryLadder() {
        if (!this.discoveryArmed) return;
        if (this.sessionConfigReceived) {
            this.discoveryArmed = false;
            return;
        }
        if (++this.ticksSinceHandshake < V16_DISCOVERY_DELAY_TICKS) return;
        int next = nextRung(this.currentAnnounce);
        if (next == 0) {
            this.discoveryArmed = false;
            return;
        }
        this.ticksSinceHandshake = 0;
        LSSLogger.debug("No session config after " + V16_DISCOVERY_DELAY_TICKS
                + " ticks at announce v" + this.currentAnnounce
                + " — retrying handshake as protocol " + next + " (legacy-server discovery)");
        int previous = this.currentAnnounce;
        try {
            // Mark-before-send: only this announce enables the wire's per-rung decode
            // arming for the reply that follows (see V16ClientWire). The gate's own rung
            // state commits only AFTER a successful send (review m4): a thrown send must
            // not widen the acceptance surface to an announce the server never saw.
            V16ClientWire.markAnnouncedVersion(next);
            this.handshakeSender.accept(next);
        } catch (Exception e) {
            LSSLogger.debug("discovery-ladder handshake send failed: " + e.getMessage());
            V16ClientWire.retractAnnounce(next, previous);
            this.discoveryArmed = false;
            return;
        }
        this.currentAnnounce = next;
        if (next == LSSConstants.V19_COMPAT_PROTOCOL_VERSION) {
            this.announced19ThisConnection = true;
        }
        if (nextRung(this.currentAnnounce) == 0) {
            // Terminal rung announced — nothing further to try; stop ticking.
            this.discoveryArmed = false;
        }
    }

    /** SessionConfig reply ladder. Main client thread. */
    void onSessionConfig(SessionConfigS2CPayload payload, boolean hasConsumers,
                         boolean enableV16ServerCompat) {
        // Any SessionConfig — even an incompatible one — disarms the discovery ladder: the
        // server answered, so there is nothing to re-discover.
        this.discoveryArmed = false;

        LSSLogger.info("Server session config received (protocol v" + payload.protocolVersion()
                + ", LOD distance: " + payload.lodDistanceChunks() + " chunks"
                + ", enabled: " + payload.enabled() + ")");

        int version = payload.protocolVersion();
        boolean v16 = version == LSSConstants.V16_COMPAT_PROTOCOL_VERSION && enableV16ServerCompat;
        // A 19 echo is accepted only when THIS CONNECTION announced 19 (the ladder rung, or
        // the soak lever's initial rung) — an unsolicited 19 config stays foreign.
        boolean v19 = version == LSSConstants.V19_COMPAT_PROTOCOL_VERSION
                && this.announced19ThisConnection;
        // The primary is whatever JOIN announced first: PROTOCOL_VERSION in production, 19
        // under the soak lever (which must REJECT a 20 echo exactly like the real v19
        // client it emulates would). The SLOW-ECHO race is load-bearing here: a late
        // primary echo arriving after the ladder advanced must still be ACCEPTED —
        // rejecting would disable LOD against a healthy current server on a slow join.
        int primary = this.primaryAnnounce != 0
                ? this.primaryAnnounce : SoakDialectOverride.announceVersion();
        if (version != primary && !v19 && !v16) {
            LSSLogger.warn("Server has incompatible " + Brand.shortName() + " protocol version " + version
                    + " (client: " + primary + "), LOD distribution disabled");
            this.serverEnabled = false;
            return;
        }

        // Downgrade guard, per rung (C3 generalization of the v16-on-current guard). A
        // LOWER-rung config arriving once a higher session is established on this
        // connection is never a real legacy server (a server is one dialect or the other):
        // it is a raced ladder reply (our fallback announce crossed the slow echo — the
        // server now considers this session legacy, so the re-announce ALSO heals the
        // server-side dialect flip), or a Paper /reload RE-ATTACH PROMPT (deliberately
        // spoken in the v16 dialect). Do NOT downgrade the working session; re-announce
        // the ESTABLISHED version so the server (re-)registers us and sheds any spurious
        // compat session. Bounded: the reply echoes the established version, which never
        // re-enters this branch — no handshake ping-pong. A HIGHER-rung config on an
        // established lower session is the UPGRADE path and rides the normal re-sent
        // config establishment below.
        if (this.sessionConfigReceived && rungOrder(version) < rungOrder(this.sessionVersion)) {
            LSSLogger.info("Late protocol-" + version + " session config on an established v"
                    + this.sessionVersion + " session (a raced discovery reply, or a server "
                    + "plugin reload prompting us to re-attach) — re-announcing v"
                    + this.sessionVersion + ".");
            try {
                // Mark-before-send: re-asserting the established dialect also retires any
                // leftover legacy announce (a raced ladder rung earlier this connection),
                // so a LATER unsolicited legacy frame — e.g. a second /reload prompt — can
                // never arm legacy decode against the re-established session's stream.
                int reAnnounce = this.sessionVersion;
                V16ClientWire.markAnnouncedVersion(reAnnounce);
                this.handshakeSender.accept(reAnnounce);
                // Commit the announce (send-success only, matching the ladder's m4 rule)
                // so the server's echo to this re-assert skips the establish-path
                // re-announce below — without this the echo would observe a stale
                // currentAnnounce and burn one redundant handshake round-trip.
                this.currentAnnounce = reAnnounce;
            } catch (Exception e) {
                LSSLogger.debug("re-assert handshake send failed: " + e.getMessage());
            }
            return;
        }

        // Dialect-flip heal at ESTABLISH (found live 2026-08-13 through a throttled 1 Mbps
        // link, as a client hard-disconnect): when the ladder advanced past the version
        // being established — the SLOW-ECHO race accepted above — the server's dialect
        // mark follows this connection's LAST announce, so at this instant the server
        // considers the session legacy and will serve LEGACY-LAYOUT columns. C2S batches
        // are byte-identical across dialects (v16-compat design), so the new manager's
        // first want-set would be accepted and answered in the wrong dialect — and a
        // dialect-mismatched column frame is a netty-level DecoderException HARD KICK
        // ("found N bytes extra"), not a contained ingest failure. Re-announce the
        // accepted version BEFORE the manager exists: TCP ordering then guarantees the
        // server's dialect flip precedes the first batch, so it can never answer a
        // current client in a legacy layout. The raced lower echoes still land in the
        // downgrade guard above (idempotent re-asserts). Bounded: the server's reply
        // echoes this same version and currentAnnounce is committed below (send-success
        // only — a thrown send leaves it stale so a re-sent config retries the heal),
        // which makes the echo skip this branch. The != 0 conjunct keeps a connection
        // that never announced (no handshake sent) from ever handshaking from here.
        if (this.currentAnnounce != 0 && this.currentAnnounce != version) {
            LSSLogger.info("Establishing protocol " + version + " after the discovery ladder "
                    + "had advanced to v" + this.currentAnnounce + " (slow echo) — re-announcing v"
                    + version + " so the server sheds the ladder's dialect mark before any "
                    + "column is served.");
            try {
                // Mark-before-send: this also re-arms our own column decode for the
                // ESTABLISHED dialect — the ladder had armed the legacy rung's decode,
                // and a column arriving before the raced echoes heal it would misparse.
                V16ClientWire.markAnnouncedVersion(version);
                this.handshakeSender.accept(version);
                this.currentAnnounce = version;
            } catch (Exception e) {
                LSSLogger.debug("establish re-announce send failed: " + e.getMessage());
            }
        }

        this.isV16Server = v16;
        this.sessionVersion = version;
        if (v16) {
            LSSLogger.info("Connected to a legacy (protocol " + version + ") server — using "
                    + "v16 backward-compat wire (load-only: already-generated terrain)");
        } else if (v19) {
            LSSLogger.info("Connected to a legacy (protocol " + version + ") server — native "
                    + "section bodies at the current frame layout (C3 ladder rung 19)");
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
            // Same connection, same link: SNAPSHOT the old governor before teardown
            // resets it, and hand the state to the rebuilt manager (review-wave C-M1 —
            // a /lsslod set re-push must not un-cap a governed slow link; the far-
            // player tracker solved this same rebuild event by living OUTSIDE the
            // manager, R-5). The teardown-then-rebuild ORDER is itself load-bearing
            // (pinned: the old manager's cache save must land before the new one's
            // load), hence the snapshot rather than a reorder. A legacy-fallback
            // re-push self-corrects: legacy sessions hard-reset the governor on tick.
            // Known ~1-tick corner (final-review B-N1): a re-push landing between a
            // dimension swap and the old manager's next tick carries the OLD
            // dimension's missing-vanilla floor (the rebuilt manager's dim phase takes
            // the initial-load branch); a stale-LOW floor then over-cuts for ~a dozen
            // intervals until the per-interval upward drift heals it. Accepted:
            // self-healing, engaged-sessions-only, needs an admin re-push to coincide
            // with a dimension trip inside one tick.
            TransferRateGovernor carried = null;
            java.util.Optional<String> carriedSubKey = java.util.Optional.empty();
            if (previous != null) {
                carried = new TransferRateGovernor();
                carried.adoptFrom(previous.governor);
                carriedSubKey = previous.worldSubKeySnapshot();
                teardownManager(previous);
            }
            this.connectionStartMs = System.currentTimeMillis();
            this.requestManager = this.managerFactory.create(config);
            if (carried != null) {
                this.requestManager.governor.adoptFrom(carried);
                // The world axis's carry-forward must survive the rebuild too (panel
                // fix): applied only when the NEW manager's own fresh read was
                // unreadable — a readable answer always wins.
                this.requestManager.adoptCarriedSubKey(carriedSubKey);
            }
        }
    }

    /** Ladder ordering for the downgrade guard: 20 above 19 above 16; anything else
     *  (incl. the pre-establishment {@code sessionVersion} 0) bottoms out. */
    private static int rungOrder(int version) {
        if (version == LSSConstants.PROTOCOL_VERSION) return 3;
        if (version == LSSConstants.V19_COMPAT_PROTOCOL_VERSION) return 2;
        if (version == LSSConstants.V16_COMPAT_PROTOCOL_VERSION) return 1;
        return 0;
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
        this.wireBytesReceived.set(0);
        this.connectionStartMs = 0;
        this.requestManager = null;
        // Clear ladder + decode state so nothing leaks into the next connection.
        this.discoveryArmed = false;
        this.ticksSinceHandshake = 0;
        this.currentAnnounce = 0;
        this.primaryAnnounce = 0;
        this.announced19ThisConnection = false;
        this.sessionVersion = 0;
        this.isV16Server = false;
        V16ClientWire.reset();
        // A voxy-force grant never survives its connection (best-effort belt — the
        // stage-2 samePath re-probe is the real invariant; plan §3.2).
        ResetCoordinator.clearForceGrant();
        // A trace belongs to the session it was started in. It used to survive
        // disconnect, server switches and world reloads with no size cap and no
        // reminder — and its 1 Hz net event kept sending a ping packet to whatever
        // server the player joined next. (v0.9.0 review.)
        ClientTraceLog.stop();
    }
}
