package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.V16ClientWire;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the client session ladder extracted from {@code LSSClientNetworking} (the client
 * twin of the server's {@code HandshakeGate}):
 * <ul>
 *   <li>A version-mismatch reply disables WITHOUT becoming a session boundary: no
 *       received-flag, no manager build, and an existing manager is kept so a later
 *       disconnect still reports and saves its session.</li>
 *   <li>A second valid SessionConfig (a server can legitimately re-send one, e.g. Paper
 *       {@code /reload}) retires the old manager with the disconnect teardown — report
 *       undispatched → saveCache — before the rebuild; silent replacement would persist
 *       received-stamps for columns no consumer ever saw (cross-session holes).</li>
 *   <li>DISCONNECT zeroes the session counters — the soak checker's per-run conservation
 *       laws and {@code /lsslod stats} assume a reconnect counts from zero.</li>
 *   <li>On a server without the LSS channel the handshake send may throw; the client
 *       must stay dormant, never crash or retry.</li>
 * </ul>
 */
class ClientSessionGateTest {

    private static final int V = LSSConstants.PROTOCOL_VERSION;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final List<String> events = new ArrayList<>();
    private final AtomicInteger handshakesSent = new AtomicInteger();
    private final List<Integer> handshakeVersions = new java.util.ArrayList<>();
    private ClientColumnProcessor processor;
    private ClientSessionGate gate;

    @BeforeEach
    void setUp() {
        processor = new ClientColumnProcessor();
        gate = new ClientSessionGate(processor, v -> { handshakeVersions.add(v); handshakesSent.incrementAndGet(); },
                cfg -> {
                    events.add("rebuild");
                    return new RecordingManager(events);
                });
    }

    // V16ClientWire.columnSourceless is a process-global static; the gate resets it as part of
    // its lifecycle. Clear it after each test so an armed flag never leaks to another suite.
    @org.junit.jupiter.api.AfterEach
    void clearStaticSourcelessFlag() {
        V16ClientWire.reset();
    }

    private static SessionConfigS2CPayload config(int protocolVersion, boolean enabled) {
        return new SessionConfigS2CPayload(protocolVersion, enabled, 64, true);
    }

    @org.junit.jupiter.api.Test
    void sessionConfigRePushCarriesTheGovernorAcrossTheManagerRebuild() {
        // Review-wave C-M1: a mid-session re-push (/lsslod set, a Paper /reload
        // re-attach) rebuilds the manager — the fresh governor must ADOPT the old
        // one's state, or a governed slow link is un-capped for the seconds a fresh
        // loop needs to re-learn congestion (the round-5 runaway shape) and the ping
        // baseline reseeds from the congested current reading.
        gate.onSessionConfig(config(V, true), true, true);
        var first = gate.getRequestManager();
        // Engage the first manager's governor with a known shape (the manager-test rig).
        first.governor.tick(1, 0, 0, 0, 0, 1, false, 50, true);
        first.governor.tick(1 + TransferRateGovernor.INTERVAL_MILLIS,
                800 * 1024, 100, 20_000, 20_000, 1, false, 2_000, true);
        first.governor.tick(1 + 2 * TransferRateGovernor.INTERVAL_MILLIS,
                1600 * 1024, 200, 40_000, 40_000, 1, false, 2_000, true);
        org.junit.jupiter.api.Assertions.assertTrue(first.governor.isEngaged(), "rig engagement");
        long desired = first.governor.getDesiredBytesPerSec();

        gate.onSessionConfig(config(V, true), true, true); // the re-push
        var second = gate.getRequestManager();
        org.junit.jupiter.api.Assertions.assertNotSame(first, second, "the re-push rebuilds");
        org.junit.jupiter.api.Assertions.assertTrue(second.governor.isEngaged(),
                "the rebuilt manager's governor must stay engaged");
        org.junit.jupiter.api.Assertions.assertEquals(desired,
                second.governor.getDesiredBytesPerSec(),
                "the governed rate carries across the rebuild");
    }

    /** The synthetic disabled shape the codec's drain branch produces for a foreign version. */
    private static SessionConfigS2CPayload codecForeignShape(int protocolVersion) {
        return new SessionConfigS2CPayload(protocolVersion, false, 0, false);
    }

    private static ResourceKey<Level> dim(String name) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:" + name));
    }

    /** Records the teardown-relevant manager calls; overrides keep the tests off disk. */
    private static class RecordingManager extends LodRequestManager {
        private final List<String> events;

        RecordingManager(List<String> events) {
            this.events = events;
        }

        @Override
        public void onIngestFailure(ResourceKey<Level> dimension, long packed) {
            events.add("report");
        }

        @Override
        public void disconnect() {
            events.add("disconnect");
        }

        @Override
        public void saveCache() {
            events.add("save");
        }
    }

    // ---- SessionConfig ladder: version gate ----

    @Test
    void foreignVersionFirstReplyDisablesWithoutCreatingSession() {
        gate.onSessionConfig(codecForeignShape(V + 1), true, true);

        assertFalse(gate.isServerEnabled());
        assertFalse(gate.hasReceivedSessionConfig(),
                "a mismatched reply must not count as a received session config"
                        + " — it says nothing about a compatible server's answer");
        assertNull(gate.getRequestManager(), "no manager may be built for a foreign version");
        assertEquals(List.of(), events, "the manager factory must not run");
        assertEquals(0, gate.getConnectionStartMs());
    }

    @Test
    void foreignVersionOutranksEnabledFlagAndConsumers() {
        // Crafted frame the codec never produces: foreign version with enabled=true. The
        // version gate must still win — activating would create a session against a server
        // whose other payload layouts this client cannot decode.
        gate.onSessionConfig(config(V - 1, true), true, true);

        assertFalse(gate.isServerEnabled());
        assertFalse(gate.hasReceivedSessionConfig());
        assertNull(gate.getRequestManager());
        assertEquals(List.of(), events);
    }

    @Test
    void foreignVersionMidSessionKeepsStaleManagerWithoutTeardown() {
        gate.onSessionConfig(config(V, true), true, true);
        var stale = gate.getRequestManager();
        events.clear();

        gate.onSessionConfig(codecForeignShape(V + 1), true, true);

        assertFalse(gate.isServerEnabled(), "mismatch must stop the tick loop");
        assertSame(stale, gate.getRequestManager(),
                "the stale manager is kept — only a later DISCONNECT reports and saves its session");
        assertTrue(gate.hasReceivedSessionConfig(),
                "the earlier valid reply already latched the received flag; mismatch leaves it");
        assertEquals(64, gate.getServerLodDistance(), "mismatch touches serverEnabled only");
        assertEquals(List.of(), events, "no teardown and no rebuild on a mismatch");
    }

    // ---- SessionConfig ladder: valid replies ----

    @Test
    void validEnabledConfigActivatesSessionAndBuildsManager() {
        gate.onSessionConfig(config(V, true), true, true);

        assertTrue(gate.isServerEnabled());
        assertTrue(gate.hasReceivedSessionConfig());
        assertEquals(64, gate.getServerLodDistance());
        assertNotNull(gate.getRequestManager());
        assertEquals(List.of("rebuild"), events, "exactly one manager build, no teardown of nothing");
        assertTrue(gate.getConnectionStartMs() > 0, "session uptime clock starts at activation");
    }

    @Test
    void validDisabledConfigRecordsReplyWithoutSession() {
        gate.onSessionConfig(config(V, false), true, true);

        assertFalse(gate.isServerEnabled());
        assertTrue(gate.hasReceivedSessionConfig(),
                "'server said disabled' must stay distinguishable from 'no LSS server'");
        assertEquals(64, gate.getServerLodDistance());
        assertNull(gate.getRequestManager());
        assertEquals(List.of(), events);
        assertEquals(0, gate.getConnectionStartMs());
    }

    @Test
    void hostileServerConfigIsClampedToProtocolBoundsBeforeUse() {
        // A hostile/compromised server is untrusted input: its syncOnLoad concurrency limit
        // becomes the client's scan budget and its LOD distance bounds the scan-ring loop.
        // Historically the budget also sized a heap allocation (RequestQueue.ensureCapacity), so an
        // unclamped SessionConfig forced a multi-gigabyte OOM; the want-set closed that particular
        // vector by construction (fixed send buffers + a budget clamped to MAX_BATCH_CHUNK_REQUESTS).
        // The clamp is still load-bearing — an unclamped lodDistance still bounds the ring walk, and
        // clamping untrusted numerics at the boundary is not a defence to relax on a technicality.
        // The gate must clamp every numeric field to the same bounds the server enforces on itself,
        // and hand the CLAMPED config to the manager it builds.
        var captured = new java.util.concurrent.atomic.AtomicReference<SessionConfigS2CPayload>();
        var g = new ClientSessionGate(processor, v -> { handshakeVersions.add(v); handshakesSent.incrementAndGet(); },
                cfg -> { captured.set(cfg); return new RecordingManager(events); });

        g.onSessionConfig(new SessionConfigS2CPayload(V, true,
                Integer.MAX_VALUE, true), true, true);

        var used = captured.get();
        assertNotNull(used, "the manager must be built with the clamped config");
        assertEquals(LSSConstants.MAX_LOD_DISTANCE, used.lodDistanceChunks(),
                "LOD distance clamped to the protocol max — the one numeric field left on the "
                        + "wire (the concurrency caps are server-internal now)");
        assertEquals(LSSConstants.MAX_LOD_DISTANCE, g.getServerLodDistance(),
                "the stored server LOD distance is the clamped value");
    }

    @Test
    void belowRangeServerConfigIsClampedUpToProtocolMinimum() {
        var captured = new java.util.concurrent.atomic.AtomicReference<SessionConfigS2CPayload>();
        var g = new ClientSessionGate(processor, v -> { handshakeVersions.add(v); handshakesSent.incrementAndGet(); },
                cfg -> { captured.set(cfg); return new RecordingManager(events); });

        g.onSessionConfig(new SessionConfigS2CPayload(V, true, 0, true), true, true);

        var used = captured.get();
        assertEquals(LSSConstants.MIN_LOD_DISTANCE, used.lodDistanceChunks());
    }

    @Test
    void validConfigWithoutConsumersSkipsSessionSetup() {
        gate.onSessionConfig(config(V, true), false, true);

        assertTrue(gate.isServerEnabled(), "the flag mirrors the server's answer even with no consumer");
        assertTrue(gate.hasReceivedSessionConfig());
        assertNull(gate.getRequestManager(), "nothing to deliver columns to — no session setup");
        assertEquals(List.of(), events);
    }

    @Test
    void secondValidConfigRunsDisconnectTeardownBeforeReplacingManager() {
        gate.onSessionConfig(config(V, true), true, true);
        var first = gate.getRequestManager();
        events.clear();
        // One column still queued for decode: the teardown must report it, not drop it —
        // its received-stamp would otherwise persist for data no consumer ever saw.
        processor.offer(new VoxelColumnS2CPayload(3, -4, dim("overworld"), 1L, new byte[0]), false);

        gate.onSessionConfig(config(V, true), true, true);

        assertEquals(List.of("report", "disconnect", "save", "rebuild"), events,
                "order is load-bearing: report undispatched → save cache → build the replacement");
        assertNotSame(first, gate.getRequestManager(), "a fresh manager must replace the old one");
        assertEquals(0, processor.getQueuedCount(), "the old session's decode backlog is drained");
        assertTrue(gate.isServerEnabled());
    }

    // ---- JOIN ladder ----

    @Test
    void joinResetsSessionStateEvenWhenLodsDisabled() {
        gate.onSessionConfig(config(V, true), true, true); // stale state from a previous connection
        gate.recordColumnFrame(512);

        gate.onJoin(false, false, true, true);

        assertNull(gate.getRequestManager(), "JOIN must always clear the previous connection's manager");
        assertFalse(gate.isServerEnabled());
        assertFalse(gate.hasReceivedSessionConfig());
        assertEquals(0, gate.getServerLodDistance());
        assertEquals(0, handshakesSent.get(), "receiveServerLods=false must suppress the handshake");
        assertEquals(1, gate.getColumnsReceived(),
                "counter zeroing is DISCONNECT's job — JOIN leaves the counters untouched");
    }

    @Test
    void joinSkipsHandshakeOnLocalIntegratedServer() {
        gate.onJoin(true, true, true, true);

        assertEquals(0, handshakesSent.get(),
                "the local integrated server must never receive an LSS handshake"
                        + " (the lss.test.integratedServer override is folded in by the caller)");
    }

    @Test
    void joinWithoutConsumersStaysSilent() {
        gate.onJoin(true, false, false, true);

        assertEquals(0, handshakesSent.get(),
                "no consumer means no handshake at all — the client never advertises capabilities=0");
    }

    @Test
    void joinSendsExactlyOneHandshakeWhenAllGuardsPass() {
        gate.onJoin(true, false, true, true);

        assertEquals(1, handshakesSent.get());
        assertNull(gate.getRequestManager(), "the manager is built only by a session-config reply");
    }

    /**
     * Scoped pin for "LSS client on a vanilla server stays dormant": full vanilla-server
     * fidelity is impossible in any test tier — the mod registers its payload channels on
     * every server it builds — so dormancy is pinned at its observable seam, the handshake
     * send failing. The throw is swallowed (logged at debug), no manager exists, and
     * nothing retries the send.
     */
    @Test
    void vanillaServerScopedHandshakeSendThrowIsSwallowedAndClientStaysDormant() {
        var sends = new AtomicInteger();
        var throwingGate = new ClientSessionGate(processor,
                v -> {
                    sends.incrementAndGet();
                    throw new IllegalStateException("channel lss:handshake not registered");
                },
                cfg -> {
                    events.add("rebuild");
                    return new RecordingManager(events);
                });

        assertDoesNotThrow(() -> throwingGate.onJoin(true, false, true, true),
                "a server without the LSS channel must never crash the client at join");

        assertEquals(1, sends.get(), "exactly one handshake attempt — no retry loop");
        assertNull(throwingGate.getRequestManager(), "dormant: no manager without a session-config reply");
        assertFalse(throwingGate.isServerEnabled());
        assertFalse(throwingGate.hasReceivedSessionConfig());
        assertEquals(List.of(), events, "the manager factory must never run on a failed handshake");
    }

    // ---- DISCONNECT routine ----

    @Test
    void disconnectZeroesCountersAndSessionState() {
        gate.onSessionConfig(config(V, true), true, true);
        gate.recordColumnFrame(2048);
        gate.recordColumnFrame(1024);
        assertEquals(2, gate.getColumnsReceived());
        assertEquals(3072, gate.getBytesReceived());
        assertTrue(gate.getConnectionStartMs() > 0);

        gate.onDisconnect();

        assertEquals(0, gate.getColumnsReceived(), "columnsReceived must restart from zero next session");
        assertEquals(0, gate.getBytesReceived(), "bytesReceived must restart from zero next session");
        assertEquals(0, gate.getConnectionStartMs(), "connectionStartMs must clear (diag uptime source)");
        assertNull(gate.getRequestManager());
        assertFalse(gate.isServerEnabled());
        assertFalse(gate.hasReceivedSessionConfig());
        assertEquals(0, gate.getServerLodDistance());
    }

    // ---- v16 server backward-compat: discovery fallback + version acceptance ----
    // (docs/planning/v16-client-compat-design.md — the mirror of the server's v16 shim)

    private static final int V16 = LSSConstants.V16_COMPAT_PROTOCOL_VERSION;

    private void tickDiscovery(int n) {
        for (int i = 0; i < n; i++) gate.tickDiscoveryLadder();
    }

    private static final int V19 = LSSConstants.V19_COMPAT_PROTOCOL_VERSION;

    @Test
    void joinAnnouncesCurrentThenTheLadderWalks19Then16ExactlyOnce() {
        // The C3 discovery ladder (XVER §6): 20 → 5 s silence → 19 → 5 s silence → 16,
        // then terminal — never a handshake storm.
        gate.onJoin(true, false, true, true);
        assertEquals(List.of(V), handshakeVersions, "JOIN announces the current protocol first");

        tickDiscovery(ClientSessionGate.V16_DISCOVERY_DELAY_TICKS - 1);
        assertEquals(List.of(V), handshakeVersions, "no fallback before the delay elapses");

        gate.tickDiscoveryLadder(); // the delay-th tick fires the 19 rung
        assertEquals(List.of(V, V19), handshakeVersions,
                "the first fallback rung is 19, exactly on the delay boundary");

        tickDiscovery(ClientSessionGate.V16_DISCOVERY_DELAY_TICKS - 1);
        assertEquals(List.of(V, V19), handshakeVersions, "the 16 rung waits its own full delay");

        gate.tickDiscoveryLadder();
        assertEquals(List.of(V, V19, V16), handshakeVersions,
                "the second fallback rung is 16");

        // Terminal: never a fourth handshake however long it idles.
        tickDiscovery(400);
        assertEquals(List.of(V, V19, V16), handshakeVersions,
                "the ladder is finite — no handshake storm");
    }

    @Test
    void v19RungDisabledSkipsStraightTo16() {
        gate.onJoin(true, false, true, true, false); // enableV19ServerCompat = false
        tickDiscovery(ClientSessionGate.V16_DISCOVERY_DELAY_TICKS);
        assertEquals(List.of(V, V16), handshakeVersions,
                "with the 19 rung disabled the ladder is the pre-C3 20→16 shape");
    }

    @Test
    void v18SessionConfigBeforeTheDelayDisarmsDiscovery() {
        gate.onJoin(true, false, true, true);
        tickDiscovery(5); // a healthy v18 server answers well within the delay
        gate.onSessionConfig(config(V, true), true, true);

        tickDiscovery(ClientSessionGate.V16_DISCOVERY_DELAY_TICKS + 50);
        assertEquals(List.of(V), handshakeVersions,
                "a v18 SessionConfig disarms the fallback — no v16 handshake on the happy path");
        assertFalse(gate.isV16Server());
    }

    @Test
    void compatDisabledAtJoinNeverArmsTheV16Fallback() {
        gate.onJoin(true, false, true, false); // enableV16ServerCompat = false (19 rung stays)

        tickDiscovery(2 * ClientSessionGate.V16_DISCOVERY_DELAY_TICKS + 50);
        assertEquals(List.of(V, V19), handshakeVersions,
                "with v16 compat off the ladder stops at the 19 rung — never a v16 handshake");
    }

    @Test
    void bothRungsDisabledNeverArmsTheLadderAtAll() {
        gate.onJoin(true, false, true, false, false); // strict current-version client

        tickDiscovery(3 * ClientSessionGate.V16_DISCOVERY_DELAY_TICKS);
        assertEquals(List.of(V), handshakeVersions,
                "a strict client announces once and never falls back");
    }

    @Test
    void discoveryNeverArmsWhenJoinSentNoHandshake() {
        // No consumer → JOIN sends no handshake at all → nothing to re-discover.
        gate.onJoin(true, false, false, true);
        assertEquals(0, handshakesSent.get());

        tickDiscovery(ClientSessionGate.V16_DISCOVERY_DELAY_TICKS + 50);
        assertEquals(0, handshakesSent.get(),
                "discovery is armed only after a real v18 handshake attempt");
    }

    @Test
    void v16SessionConfigWhenCompatEnabledActivatesTheLegacySession() {
        gate.onJoin(true, false, true, true);
        gate.onSessionConfig(SessionConfigS2CPayload.v16Legacy(true, 64, 200, 7, true), true, true);

        assertTrue(gate.isServerEnabled());
        assertTrue(gate.hasReceivedSessionConfig());
        assertTrue(gate.isV16Server(), "a protocol-16 reply marks the session legacy");
        assertEquals(64, gate.getServerLodDistance());
        assertNotNull(gate.getRequestManager(), "a v16 session still builds a manager (load-only)");
        assertEquals(List.of("rebuild"), events, "the legacy session builds exactly one manager");
        assertEquals(List.of(V, V16), handshakeVersions,
                "accepting a v16 session when the last announce was 20 re-announces 16 — the "
                        + "establish heal aligns the server's dialect mark with the accepted "
                        + "session before any column flows (unreachable against a real v16 "
                        + "server, which never replies cross-version; harmless there — a "
                        + "re-register plus config re-send)");
    }

    @Test
    void v16SessionConfigWhenCompatDisabledIsTreatedAsForeignAndDisables() {
        gate.onJoin(true, false, true, false);
        gate.onSessionConfig(SessionConfigS2CPayload.v16Legacy(true, 64, 200, 7, true), true, false);

        assertFalse(gate.isServerEnabled(), "compat off → version 16 is just another foreign version");
        assertFalse(gate.isV16Server());
        assertFalse(gate.hasReceivedSessionConfig(), "a foreign version is not a received session");
        assertNull(gate.getRequestManager());
        assertEquals(List.of(), events, "no manager built for a rejected legacy config");
    }

    @Test
    void aSecondV16ConfigOnALiveV16SessionRebuildsWithoutReAssertingV18() {
        // A legacy server legitimately re-sending its config (its own reload) must rebuild the
        // v16 manager like the v18 re-send path — NOT trip the downgrade guard's v18 re-assert.
        // The guard's `!isV16Server` term is exactly what distinguishes this legitimate v16→v16
        // re-send from a spurious v18→v16 downgrade.
        gate.onSessionConfig(SessionConfigS2CPayload.v16Legacy(true, 64, 200, 7, true), true, true);
        var first = gate.getRequestManager();
        assertTrue(gate.isV16Server());
        // A queued column so the teardown has something to report (proves the rebuild path ran).
        processor.offer(new VoxelColumnS2CPayload(3, -4, dim("overworld"), 1L, new byte[0]), false);
        events.clear();
        handshakeVersions.clear();

        gate.onSessionConfig(SessionConfigS2CPayload.v16Legacy(true, 64, 200, 7, true), true, true);

        assertTrue(gate.isV16Server(), "still a v16 session — no downgrade");
        assertNotSame(first, gate.getRequestManager(),
                "a re-sent v16 config rebuilds the manager (guard did NOT early-return with the same one)");
        assertEquals(List.of("report", "disconnect", "save", "rebuild"), events,
                "the re-sent v16 config runs the disconnect teardown then rebuilds, like a v18 re-send");
        assertEquals(List.of(), handshakeVersions,
                "no v18 re-assert handshake — a v16→v16 re-send is not a downgrade");
    }

    @Test
    void isV16ServerResetsOnJoinAndDisconnect() {
        gate.onSessionConfig(SessionConfigS2CPayload.v16Legacy(true, 64, 200, 7, true), true, true);
        assertTrue(gate.isV16Server());

        gate.onJoin(true, false, true, true);
        assertFalse(gate.isV16Server(), "JOIN clears the legacy flag for the new connection");

        gate.onSessionConfig(SessionConfigS2CPayload.v16Legacy(true, 64, 200, 7, true), true, true);
        assertTrue(gate.isV16Server());
        gate.onDisconnect();
        assertFalse(gate.isV16Server(), "DISCONNECT clears the legacy flag");
    }

    // ---- The process-global column-decode flag: the design's stated primary stability risk.
    // The gate's onJoin/onDisconnect are the ONLY production callers of V16ClientWire.reset();
    // these pin that wiring directly (isV16Server is a separate instance field — clearing it is
    // not evidence the static flag cleared, which is what actually misaligns a v18 column decode).

    @Test
    void onJoinClearsTheStaticSourcelessDecodeFlag() {
        // A prior v16 session left it armed (announce-then-observe, the genuine flow).
        V16ClientWire.markAnnouncedVersion(V16);
        V16ClientWire.observeSessionConfigVersion(V16);
        assertTrue(V16ClientWire.isColumnSourceless());

        gate.onJoin(true, false, true, true);

        assertFalse(V16ClientWire.isColumnSourceless(),
                "JOIN must clear the source-less flag — otherwise a v16 server's state leaks into "
                        + "the next v18 connection, whose columns would decode with the source byte "
                        + "skipped (array misalignment → decoder kick)");
    }

    @Test
    void onJoinTearsDownASurvivingManagerBeforeResetting() {
        // The defensive branch (R2-11): Fabric's lifecycle sends DISCONNECT before the
        // next JOIN, but if a manager ever survives to onJoin, dropping it without the
        // full teardown would silently lose its session's cache save. Assert the events,
        // not just the null-out — a regression to a bare drop stays green otherwise.
        gate.onSessionConfig(config(V, true), true, true);
        assertNotNull(gate.getRequestManager(), "premise: a live manager exists");
        // An undispatched column makes the teardown's report leg observable (the report
        // fires per queued column, so an empty queue would legitimately skip it).
        processor.offer(new VoxelColumnS2CPayload(3, -4, dim("overworld"), 1L, new byte[0]), false);
        events.clear();

        gate.onJoin(true, false, true, true);

        assertEquals(List.of("report", "disconnect", "save"), events,
                "a surviving manager gets the full teardown (report -> disconnect -> save)");
        assertNull(gate.getRequestManager(), "the survivor is dropped after teardown");
    }

    @Test
    void onDisconnectClearsTheStaticSourcelessDecodeFlag() {
        V16ClientWire.markAnnouncedVersion(V16);
        V16ClientWire.observeSessionConfigVersion(V16);
        assertTrue(V16ClientWire.isColumnSourceless());

        gate.onDisconnect();

        assertFalse(V16ClientWire.isColumnSourceless(),
                "DISCONNECT must clear the source-less flag so no v16 decode state survives the session");
    }

    // ---- The announce gate on sourceless arming (the R2-3 prompt hazard's root fix): only
    // the gate's own v16 discovery announce enables arming, and the downgrade guard's v18
    // re-assert retires it — pinned through the gate's production send paths, not by calling
    // markAnnouncedVersion directly.

    @Test
    void discoveryFallbackAnnounceEnablesSourcelessArming() {
        gate.onJoin(true, false, true, true); // v18 announce — arming disabled
        V16ClientWire.observeSessionConfigVersion(V16);
        assertFalse(V16ClientWire.isColumnSourceless(),
                "after the JOIN v18 announce, a v16 config (e.g. an unsolicited re-attach "
                        + "prompt) must NOT arm sourceless decode");

        for (int i = 0; i < ClientSessionGate.V16_DISCOVERY_DELAY_TICKS; i++) {
            gate.tickDiscoveryLadder(); // fires the 19 rung on the last tick
        }
        assertEquals(List.of(V, V19), handshakeVersions,
                "premise: the ladder's first fallback announced 19");
        V16ClientWire.observeSessionConfigVersion(V16);
        assertFalse(V16ClientWire.isColumnSourceless(),
                "a v16 config mid-ladder (announced 19, not 16) must NOT arm sourceless");

        for (int i = 0; i < ClientSessionGate.V16_DISCOVERY_DELAY_TICKS; i++) {
            gate.tickDiscoveryLadder(); // fires the 16 rung on the last tick
        }
        assertEquals(List.of(V, V19, V16), handshakeVersions,
                "premise: the ladder reached the v16 rung");

        V16ClientWire.observeSessionConfigVersion(V16);
        assertTrue(V16ClientWire.isColumnSourceless(),
                "after the client's own v16 announce, the genuine v16 reply arms decode");
    }

    @Test
    void downgradeGuardReassertRetiresTheV16Announce() {
        // Raced discovery: v18 session established, but the client HAS announced 16 earlier
        // this connection. The guard's v18 re-assert must retire that announce so a LATER
        // unsolicited v16 frame (a second /reload prompt) cannot arm against the live stream.
        gate.onJoin(true, false, true, true);
        for (int i = 0; i < ClientSessionGate.V16_DISCOVERY_DELAY_TICKS; i++) {
            gate.tickDiscoveryLadder();
        }
        gate.onSessionConfig(config(V, true), true, true); // slow v18 reply lands first
        handshakeVersions.clear();

        // The v16 reply to the raced announce hits the downgrade guard → v18 re-assert.
        gate.onSessionConfig(SessionConfigS2CPayload.v16Legacy(true, 64, 200, 7, true), true, true);
        assertEquals(List.of(V), handshakeVersions,
                "premise: the guard re-announced v18");

        V16ClientWire.observeSessionConfigVersion(V16);
        assertFalse(V16ClientWire.isColumnSourceless(),
                "after the guard's v18 re-assert, a later unsolicited v16 config must not "
                        + "arm sourceless decode against the re-established v18 stream");
    }

    @Test
    void aThrownJoinHandshakeLeavesDiscoveryDisarmed() {
        // On a vanilla / no-LSS server the v18 handshake send throws (swallowed at debug). The
        // arm sits INSIDE the try after the send, so a throw leaves discovery disarmed — there
        // is no server to re-discover, and firing a second doomed v16 handshake from the client
        // tick would be pointless. Ticking past the delay must produce no further send and never
        // propagate out of the END_CLIENT_TICK handler.
        var sends = new AtomicInteger();
        var throwingGate = new ClientSessionGate(processor,
                v -> { sends.incrementAndGet(); throw new IllegalStateException("no LSS channel"); },
                cfg -> { events.add("rebuild"); return new RecordingManager(events); });

        throwingGate.onJoin(true, false, true, true);
        assertEquals(1, sends.get(), "one v18 join send attempt (thrown, swallowed)");

        assertDoesNotThrow(() -> {
            for (int i = 0; i < ClientSessionGate.V16_DISCOVERY_DELAY_TICKS + 5; i++) {
                throwingGate.tickDiscoveryLadder();
            }
        }, "ticking a disarmed discovery must never crash the client tick");
        assertEquals(1, sends.get(),
                "a thrown v18 handshake leaves discovery disarmed — no v16 fallback attempt");
    }

    // ---- C3 ladder: the 19 rung's acceptance, session, and race pins (XVER §6) ----

    @Test
    void nineteenEchoAfterTheLadderAnnouncedItEstablishesANativeBodySession() {
        gate.onJoin(true, false, true, true);
        tickDiscovery(ClientSessionGate.V16_DISCOVERY_DELAY_TICKS); // → announce 19
        assertEquals(List.of(V, V19), handshakeVersions, "premise: the 19 rung fired");

        V16ClientWire.observeSessionConfigVersion(V19); // the netty half of the echo
        gate.onSessionConfig(config(V19, true), true, true);

        assertTrue(gate.isServerEnabled(), "the 19 echo must establish a live session");
        assertNotNull(gate.getRequestManager());
        assertTrue(V16ClientWire.isNativeBodySession(),
                "a 19 session's column bodies arrive native — the drain must skip the "
                        + "v20 translation");
        assertFalse(V16ClientWire.isColumnSourceless(),
                "a 19 session keeps the CURRENT frame layout (source + codec bytes)");
    }

    @Test
    void unsolicitedNineteenConfigWithoutTheAnnounceStaysForeign() {
        gate.onJoin(true, false, true, true); // announced 20 only — the ladder never fired
        gate.onSessionConfig(config(V19, true), true, true);
        assertFalse(gate.isServerEnabled(),
                "a 19 config this connection never solicited is a foreign version — a real "
                        + "v19 server cannot reply 19 to a 20 announce");
        assertNull(gate.getRequestManager());
    }

    @Test
    void slowTwentyEchoAfterTheLadderAdvancedStillEstablishesAndTheRacedNineteenReasserts() {
        // The load-bearing race (C3 plan): a healthy v20 server whose echo was slow enough
        // that the ladder already announced 19. The late 20 echo must be ACCEPTED (rejecting
        // would disable LOD against a healthy server). Establishment itself now heals the
        // server-side dialect flip FIRST (the establish re-announce — see
        // establishAfterTheLadderAdvancedReAnnouncesBeforeTheManagerIsBuilt); the server's
        // 19 echo to our raced announce then hits the downgrade guard, whose re-announce of
        // the ESTABLISHED 20 stays as the idempotent second assert.
        gate.onJoin(true, false, true, true);
        tickDiscovery(ClientSessionGate.V16_DISCOVERY_DELAY_TICKS); // → announce 19
        assertEquals(List.of(V, V19), handshakeVersions);

        // Real frame order per config: netty observe FIRST, then the main-thread handling
        // (review MAJOR-3 — the earlier version inverted this and pinned a property the
        // production ordering does not have).
        V16ClientWire.observeSessionConfigVersion(V);
        gate.onSessionConfig(config(V, true), true, true); // the slow 20 echo lands
        assertTrue(gate.isServerEnabled(), "the late current-version echo must establish");
        assertEquals(List.of(V, V19, V), handshakeVersions,
                "the establish heal re-announces the accepted 20 immediately — the manager's "
                        + "first batch must never race the server's dialect flip");
        handshakeVersions.clear();

        V16ClientWire.observeSessionConfigVersion(V19); // the raced 19 echo, netty half
        assertFalse(V16ClientWire.isNativeBodySession(),
                "the establish heal already retired the 19 announce, so the raced echo can "
                        + "no longer arm native-body decode — the pre-heal transient window "
                        + "(a raced 19 frame mis-arming against the live v20 stream) is CLOSED");
        gate.onSessionConfig(config(V19, true), true, true); // …then the main half
        assertEquals(List.of(V), handshakeVersions,
                "the guard re-announces the ESTABLISHED version — the idempotent second assert");
        assertFalse(V16ClientWire.isNativeBodySession(),
                "still disarmed after the guard's re-assert");
        V16ClientWire.observeSessionConfigVersion(V19);
        assertFalse(V16ClientWire.isNativeBodySession(),
                "after the re-assert, a later unsolicited 19 frame must not arm "
                        + "native-body decode against the live v20 stream");
    }

    // ---- The establish-path dialect-flip heal (found live 2026-08-13: a 1 Mbps throttled
    // link walked the full ladder against a healthy v20 server; the late 20 echo
    // established, the manager's first want-set raced ahead of any heal on the C2S stream,
    // and the still-v16-marked server answered it with legacy-layout columns — a netty
    // DecoderException hard kick at the client's v20 codec, "found 26871 bytes extra").

    @Test
    void establishAfterTheLadderAdvancedReAnnouncesBeforeTheManagerIsBuilt() {
        // THE ordering pin: the re-announce must be SENT before the manager exists. TCP
        // ordering is the entire correctness argument — the server's dialect flip must
        // precede the first want-set batch, and the batch cannot exist before the manager.
        var ordered = new ArrayList<String>();
        var orderedGate = new ClientSessionGate(processor,
                v -> ordered.add("hs:" + v),
                cfg -> { ordered.add("manager"); return new RecordingManager(new ArrayList<>()); });

        orderedGate.onJoin(true, false, true, true);
        for (int i = 0; i < 2 * ClientSessionGate.V16_DISCOVERY_DELAY_TICKS; i++) {
            orderedGate.tickDiscoveryLadder(); // walks 19 then 16
        }
        assertEquals(List.of("hs:" + V, "hs:" + V19, "hs:" + V16), ordered,
                "premise: the full ladder walked before any echo arrived");

        orderedGate.onSessionConfig(config(V, true), true, true); // the slow 20 echo lands

        assertEquals(List.of("hs:" + V, "hs:" + V19, "hs:" + V16, "hs:" + V, "manager"), ordered,
                "the establish re-announce goes out BEFORE the manager is built — TCP "
                        + "ordering then guarantees the server sheds its legacy dialect mark "
                        + "before the first batch can arrive");

        V16ClientWire.observeSessionConfigVersion(V16);
        assertFalse(V16ClientWire.isColumnSourceless(),
                "the establish re-announce also retires the ladder's v16 announce — a late "
                        + "legacy frame must not arm sourceless decode against the v20 stream");
    }

    @Test
    void establishReAnnounceEchoTakesThePlainReconfigPathWithoutAnotherHandshake() {
        // Boundedness: the server replies to the establish re-announce with a fresh v20
        // config. currentAnnounce was committed at the re-announce, so the echo is the
        // normal re-config path — never a handshake ping-pong.
        gate.onJoin(true, false, true, true);
        tickDiscovery(2 * ClientSessionGate.V16_DISCOVERY_DELAY_TICKS); // → announced 19, 16
        gate.onSessionConfig(config(V, true), true, true); // slow echo → establish + heal
        assertEquals(List.of(V, V19, V16, V), handshakeVersions,
                "premise: the establish heal re-announced exactly once");

        gate.onSessionConfig(config(V, true), true, true); // the heal's own echo

        assertEquals(List.of(V, V19, V16, V), handshakeVersions,
                "the heal echo re-configures without any further handshake");
        assertTrue(gate.isServerEnabled());
    }

    @Test
    void thrownEstablishReAnnounceStillEstablishesAndTheNextConfigRetriesTheHeal() {
        // A thrown heal send must not block establishment (the session is still viable —
        // the connection may just be mid-teardown), and the uncommitted announce means a
        // re-sent config RETRIES the heal instead of silently accepting the stale mark.
        var sends = new ArrayList<Integer>();
        boolean[] failNext = {false};
        var throwingGate = new ClientSessionGate(processor,
                v -> {
                    if (failNext[0]) { failNext[0] = false; throw new IllegalStateException("send failed"); }
                    sends.add(v);
                },
                cfg -> { events.add("rebuild"); return new RecordingManager(events); });

        throwingGate.onJoin(true, false, true, true);
        for (int i = 0; i < 2 * ClientSessionGate.V16_DISCOVERY_DELAY_TICKS; i++) {
            throwingGate.tickDiscoveryLadder();
        }
        assertEquals(List.of(V, V19, V16), sends, "premise: the full ladder walked");

        failNext[0] = true;
        throwingGate.onSessionConfig(config(V, true), true, true); // heal send THROWS
        assertEquals(List.of(V, V19, V16), sends, "the thrown heal sent nothing");
        assertTrue(throwingGate.isServerEnabled(), "a thrown heal must not block establishment");
        assertNotNull(throwingGate.getRequestManager());

        throwingGate.onSessionConfig(config(V, true), true, true); // a re-sent config
        assertEquals(List.of(V, V19, V16, V), sends,
                "the stale announce is retried on the next accepted config");

        throwingGate.onSessionConfig(config(V, true), true, true);
        assertEquals(List.of(V, V19, V16, V), sends, "committed — no further handshake");
    }

    @Test
    void slowNineteenEchoAfterTheLadderReachedSixteenReAnnouncesNineteen() {
        // The heal generalizes per rung: a v19 server whose echo lands after the ladder
        // announced 16 establishes the 19 session AND re-announces 19 — a v19-native
        // client fed source-less v16-layout columns is the same decoder kick one rung down.
        gate.onJoin(true, false, true, true);
        tickDiscovery(2 * ClientSessionGate.V16_DISCOVERY_DELAY_TICKS); // 19 then 16
        assertEquals(List.of(V, V19, V16), handshakeVersions);

        V16ClientWire.observeSessionConfigVersion(V19);
        gate.onSessionConfig(config(V19, true), true, true);

        assertTrue(gate.isServerEnabled(), "the late 19 echo must establish");
        assertNotNull(gate.getRequestManager());
        assertEquals(List.of(V, V19, V16, V19), handshakeVersions,
                "establishing 19 after the 16 announce re-announces 19 before any column flows");
    }

    @Test
    void nineteenConfigOnAnEstablishedNineteenSessionIsTheNormalReconfigPath() {
        gate.onJoin(true, false, true, true);
        tickDiscovery(ClientSessionGate.V16_DISCOVERY_DELAY_TICKS);
        V16ClientWire.observeSessionConfigVersion(V19);
        gate.onSessionConfig(config(V19, true), true, true);
        var first = gate.getRequestManager();
        assertNotNull(first);
        handshakeVersions.clear();

        gate.onSessionConfig(config(V19, true), true, true); // a re-sent config (e.g. /reload)
        assertTrue(handshakeVersions.isEmpty(), "same-rung re-config never re-announces");
        assertNotSame(first, gate.getRequestManager(),
                "the re-sent config replaces the manager (the established re-config path)");
    }

    @Test
    void sixteenConfigOnAnEstablishedNineteenSessionReassertsNineteen() {
        // The guard's per-rung generalization: 16 < 19 too — an established 19 session
        // must not degrade to v16 on a late/prompted 16 config.
        gate.onJoin(true, false, true, true);
        tickDiscovery(ClientSessionGate.V16_DISCOVERY_DELAY_TICKS);
        V16ClientWire.observeSessionConfigVersion(V19);
        gate.onSessionConfig(config(V19, true), true, true);
        handshakeVersions.clear();

        V16ClientWire.observeSessionConfigVersion(V16);
        gate.onSessionConfig(SessionConfigS2CPayload.v16Legacy(true, 64, 200, 7, true), true, true);
        assertEquals(List.of(V19), handshakeVersions,
                "the guard re-announces the established 19, never the primary constant");
        assertFalse(V16ClientWire.isColumnSourceless(),
                "the 16 rung was never announced, so the prompt cannot arm sourceless");
        // The prompt's own netty observe DISARMS session19 (any other frame disarms) —
        // the accepted transient window; already-queued columns stay correct via the
        // decode-time stamp. The heal completes when the re-asserted 19's echo lands:
        V16ClientWire.observeSessionConfigVersion(V19);
        assertTrue(V16ClientWire.isNativeBodySession(),
                "the re-asserted 19's echo re-arms native-body decode (BIT_V19 survived)");
    }

    @Test
    void lateV16ConfigDoesNotDowngradeALiveV18SessionAndReassertsV18() {
        // The reviewers' headline scenario: a healthy v18 server whose SessionConfig was slow
        // enough that the client already fired the v16 discovery fallback, then the (compat-on)
        // server answered it with a v16 config. The client must NOT downgrade its working v18
        // session; it re-announces v18 so the server sheds the spurious compat session.
        gate.onJoin(true, false, true, true);
        assertEquals(List.of(V), handshakeVersions);
        gate.onSessionConfig(config(V, true), true, true); // the (late) v18 config lands first
        var v18Manager = gate.getRequestManager();
        assertNotNull(v18Manager);
        assertFalse(gate.isV16Server());
        events.clear();

        // The spurious v16 reply arrives after the v18 session is live.
        gate.onSessionConfig(SessionConfigS2CPayload.v16Legacy(true, 64, 200, 7, true), true, true);

        assertFalse(gate.isV16Server(), "a live v18 session must never downgrade to v16");
        assertSame(v18Manager, gate.getRequestManager(),
                "the v18 manager is kept — no teardown/rebuild on the spurious v16 config");
        assertTrue(gate.isServerEnabled(), "the v18 session stays enabled");
        assertEquals(List.of(), events, "no teardown, no rebuild fired");
        assertEquals(List.of(V, V), handshakeVersions,
                "the client re-announces v18 to shed the server's spurious compat session");
    }

    @Test
    void genuineV16ConfigWithoutAPriorV18SessionStillBuildsTheLegacySession() {
        // The guard must fire ONLY on a downgrade of a live v18 session — a real v16 server
        // (no prior v18 config) must still activate normally.
        gate.onJoin(true, false, true, true); // v18 handshake sent; no v18 config ever arrives
        gate.onSessionConfig(SessionConfigS2CPayload.v16Legacy(true, 64, 200, 7, true), true, true);

        assertTrue(gate.isV16Server(), "a first-and-only v16 config builds the legacy session");
        assertNotNull(gate.getRequestManager());
        assertEquals(List.of("rebuild"), events);
        assertEquals(List.of(V, V16), handshakeVersions,
                "no DOWNGRADE-guard re-assert (this was not a downgrade of a live session) — "
                        + "the second handshake is the establish heal re-announcing the "
                        + "accepted 16, because the last announce was 20");
    }

    // ---- the service-gate mid-session disable (plan §2.4) ----

    @Test
    void midSessionDisableRetiresTheManagerWithTheStandardTeardown() {
        // The revocation push makes the valid enabled=false-on-established shape common:
        // the manager must retire exactly like a disconnect (disconnect -> saveCache; the
        // report leg is the processor's and has nothing queued here) — never sit parked
        // un-ticked with its session stamps unsaved.
        gate.onSessionConfig(config(V, true), true, true);
        assertNotNull(gate.getRequestManager());
        FarPlayerClientSupport.tracker().onRoster(new dev.vox.lss.common.farplayers.FarPlayerWire.Roster(
                7, true, List.of(new dev.vox.lss.common.farplayers.FarPlayerWire.RosterEntry(
                        0, java.util.UUID.randomUUID(), "afar")), new int[0]));
        assertEquals(7, FarPlayerClientSupport.tracker().currentEpoch(),
                "premise: a live far-player roster epoch");
        events.clear();

        gate.onSessionConfig(config(V, false), true, true);

        assertNull(gate.getRequestManager(), "the manager is retired, not parked");
        assertEquals(List.of("disconnect", "save"), events,
                "the standard teardown order fires (report -> disconnect -> saveCache)");
        assertFalse(gate.isServerEnabled());
        assertTrue(gate.hasReceivedSessionConfig(), "'server said disabled' stays latched");
        assertEquals(-1, FarPlayerClientSupport.tracker().currentEpoch(),
                "the far-player session ends with the LOD session — proxies must not "
                        + "freeze mid-air (§8 F1-m2; -1 = tracker cleared, roster forgotten)");
    }

    @Test
    void reEnableAfterMidSessionDisableAdoptsTheParkedGovernorAndSubKey() {
        // The #243 carry must survive a revoke->grant cycle: the disable teardown PARKS
        // the governor (and world sub-key) and the next enable adopts it — otherwise a
        // governed slow link is un-capped and the client re-slow-starts into the bare
        // cache bucket.
        gate.onSessionConfig(config(V, true), true, true);
        var first = gate.getRequestManager();
        first.governor.tick(1, 0, 0, 0, 0, 1, false, 50, true);
        first.governor.tick(1 + TransferRateGovernor.INTERVAL_MILLIS,
                800 * 1024, 100, 20_000, 20_000, 1, false, 2_000, true);
        first.governor.tick(1 + 2 * TransferRateGovernor.INTERVAL_MILLIS,
                1600 * 1024, 200, 40_000, 40_000, 1, false, 2_000, true);
        assertTrue(first.governor.isEngaged(), "rig engagement");
        long desired = first.governor.getDesiredBytesPerSec();

        gate.onSessionConfig(config(V, false), true, true);   // revoke: teardown + park
        assertNull(gate.getRequestManager());
        gate.onSessionConfig(config(V, true), true, true);    // regrant: rebuild + adopt

        var second = gate.getRequestManager();
        assertNotNull(second);
        assertNotSame(first, second);
        assertTrue(second.governor.isEngaged(),
                "the re-enabled manager's governor must stay engaged");
        assertEquals(desired, second.governor.getDesiredBytesPerSec(),
                "the governed rate survives the revoke->grant cycle via the park");
    }

    @Test
    void theParkCarriesTheWorldSubKeyThroughARevokeGrantCycle() {
        // The #243 world-axis half of the park (implementation review: the governor
        // half alone was pinned): the disable teardown must snapshot the sub-key and
        // the re-enable must hand it to the rebuilt manager — otherwise an unreadable
        // re-read drops the client into the BARE cache bucket.
        var subKeyed = new java.util.concurrent.atomic.AtomicReference<java.util.Optional<String>>();
        var g = new ClientSessionGate(processor, v -> { }, cfg -> new RecordingManager(events) {
            @Override
            java.util.Optional<String> worldSubKeySnapshot() {
                return java.util.Optional.of("world-abc");
            }

            @Override
            void adoptCarriedSubKey(java.util.Optional<String> previousSubKey) {
                subKeyed.set(previousSubKey);
            }
        });
        g.onSessionConfig(config(V, true), true, true);
        g.onSessionConfig(config(V, false), true, true); // revoke: park
        g.onSessionConfig(config(V, true), true, true);  // regrant: adopt

        assertEquals(java.util.Optional.of("world-abc"), subKeyed.get(),
                "the parked world sub-key must reach the rebuilt manager's adopt hook");
    }

    @Test
    void theParkDiesWithTheConnection() {
        // A parked governor is same-connection state: JOIN (a new link) must clear it,
        // or a stale slow-link cap from the previous server throttles the new session.
        gate.onSessionConfig(config(V, true), true, true);
        var first = gate.getRequestManager();
        first.governor.tick(1, 0, 0, 0, 0, 1, false, 50, true);
        first.governor.tick(1 + TransferRateGovernor.INTERVAL_MILLIS,
                800 * 1024, 100, 20_000, 20_000, 1, false, 2_000, true);
        first.governor.tick(1 + 2 * TransferRateGovernor.INTERVAL_MILLIS,
                1600 * 1024, 200, 40_000, 40_000, 1, false, 2_000, true);
        assertTrue(first.governor.isEngaged(), "rig engagement");

        gate.onSessionConfig(config(V, false), true, true); // park
        gate.onJoin(true, false, true, true);               // new connection
        gate.onSessionConfig(config(V, true), true, true);

        assertFalse(gate.getRequestManager().governor.isEngaged(),
                "a fresh link starts with a fresh governor — the park never crosses joins");
    }

    @Test
    void theDowngradeGuardOutranksTheDisableTeardown() {
        // §8 O1-m12, pinned: Paper's /reload re-attach prompt is a v16-DIALECT config
        // carrying an enabled flag at an established v20 session. It must keep taking
        // the downgrade guard's early return (re-announce, keep the manager) — never
        // the mid-session teardown, whatever its enabled flag says.
        gate.onSessionConfig(config(V, true), true, true);
        var manager = gate.getRequestManager();
        int sent = handshakesSent.get();
        events.clear();

        gate.onSessionConfig(new SessionConfigS2CPayload(
                        LSSConstants.V16_COMPAT_PROTOCOL_VERSION, false, 64, true,
                        200, 40, true, 0),
                true, true);

        assertSame(manager, gate.getRequestManager(),
                "the re-attach prompt must never tear down the working session");
        assertEquals(List.of(), events, "no teardown, no rebuild");
        assertEquals(sent + 1, handshakesSent.get(), "the guard re-announces instead");
        assertTrue(gate.isServerEnabled(), "the established session's state is untouched");
    }
}
