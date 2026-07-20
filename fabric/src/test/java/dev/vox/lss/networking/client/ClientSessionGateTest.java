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

    /** The synthetic disabled shape the codec's drain branch produces for a foreign version. */
    private static SessionConfigS2CPayload codecForeignShape(int protocolVersion) {
        return new SessionConfigS2CPayload(protocolVersion, false, 0, false);
    }

    private static ResourceKey<Level> dim(String name) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:" + name));
    }

    /** Records the teardown-relevant manager calls; overrides keep the tests off disk. */
    private static final class RecordingManager extends LodRequestManager {
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
        for (int i = 0; i < n; i++) gate.tickV16Discovery();
    }

    @Test
    void joinAnnouncesV18ThenDiscoveryReHandshakesAsV16ExactlyOnce() {
        gate.onJoin(true, false, true, true);
        assertEquals(List.of(V), handshakeVersions, "JOIN announces the current protocol first");

        // No SessionConfig arrives: nothing fires until the delay boundary.
        tickDiscovery(ClientSessionGate.V16_DISCOVERY_DELAY_TICKS - 1);
        assertEquals(List.of(V), handshakeVersions, "no v16 handshake before the delay elapses");

        gate.tickV16Discovery(); // the delay-th tick fires the fallback
        assertEquals(List.of(V, V16), handshakeVersions,
                "the v16 fallback handshake fires exactly on the delay boundary");

        // One-shot: never a second v16 handshake however long it idles.
        tickDiscovery(200);
        assertEquals(List.of(V, V16), handshakeVersions,
                "the fallback is one-shot — no v16 handshake storm");
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
        gate.onJoin(true, false, true, false); // enableV16ServerCompat = false

        tickDiscovery(ClientSessionGate.V16_DISCOVERY_DELAY_TICKS + 50);
        assertEquals(List.of(V), handshakeVersions,
                "a strict-v18 client never falls back to a v16 handshake");
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
        assertEquals(List.of(V), handshakeVersions, "a SessionConfig reply sends no further handshake");
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
        V16ClientWire.observeSessionConfigVersion(V16); // a prior v16 session left it armed
        assertTrue(V16ClientWire.isColumnSourceless());

        gate.onJoin(true, false, true, true);

        assertFalse(V16ClientWire.isColumnSourceless(),
                "JOIN must clear the source-less flag — otherwise a v16 server's state leaks into "
                        + "the next v18 connection, whose columns would decode with the source byte "
                        + "skipped (array misalignment → decoder kick)");
    }

    @Test
    void onDisconnectClearsTheStaticSourcelessDecodeFlag() {
        V16ClientWire.observeSessionConfigVersion(V16);
        assertTrue(V16ClientWire.isColumnSourceless());

        gate.onDisconnect();

        assertFalse(V16ClientWire.isColumnSourceless(),
                "DISCONNECT must clear the source-less flag so no v16 decode state survives the session");
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
                throwingGate.tickV16Discovery();
            }
        }, "ticking a disarmed discovery must never crash the client tick");
        assertEquals(1, sends.get(),
                "a thrown v18 handshake leaves discovery disarmed — no v16 fallback attempt");
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
        assertEquals(List.of(V), handshakeVersions, "no v18 re-assert — this was not a downgrade");
    }
}
