package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
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
    private ClientColumnProcessor processor;
    private ClientSessionGate gate;

    @BeforeEach
    void setUp() {
        processor = new ClientColumnProcessor();
        gate = new ClientSessionGate(processor, handshakesSent::incrementAndGet,
                cfg -> {
                    events.add("rebuild");
                    return new RecordingManager(events);
                });
    }

    private static SessionConfigS2CPayload config(int protocolVersion, boolean enabled) {
        return new SessionConfigS2CPayload(protocolVersion, enabled, 64, 100, 100, true);
    }

    /** The synthetic disabled shape the codec's drain branch produces for a foreign version. */
    private static SessionConfigS2CPayload codecForeignShape(int protocolVersion) {
        return new SessionConfigS2CPayload(protocolVersion, false, 0, 0, 0, false);
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
        gate.onSessionConfig(codecForeignShape(V + 1), true);

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
        gate.onSessionConfig(config(V - 1, true), true);

        assertFalse(gate.isServerEnabled());
        assertFalse(gate.hasReceivedSessionConfig());
        assertNull(gate.getRequestManager());
        assertEquals(List.of(), events);
    }

    @Test
    void foreignVersionMidSessionKeepsStaleManagerWithoutTeardown() {
        gate.onSessionConfig(config(V, true), true);
        var stale = gate.getRequestManager();
        events.clear();

        gate.onSessionConfig(codecForeignShape(V + 1), true);

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
        gate.onSessionConfig(config(V, true), true);

        assertTrue(gate.isServerEnabled());
        assertTrue(gate.hasReceivedSessionConfig());
        assertEquals(64, gate.getServerLodDistance());
        assertNotNull(gate.getRequestManager());
        assertEquals(List.of("rebuild"), events, "exactly one manager build, no teardown of nothing");
        assertTrue(gate.getConnectionStartMs() > 0, "session uptime clock starts at activation");
    }

    @Test
    void validDisabledConfigRecordsReplyWithoutSession() {
        gate.onSessionConfig(config(V, false), true);

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
        // becomes the client's scan budget (RequestQueue.ensureCapacity allocation) and its
        // LOD distance bounds the scan-ring loop. Without a client-side clamp a single
        // SessionConfig packet forces a multi-gigabyte allocation (OOM crash). The gate must
        // clamp every numeric field to the same bounds the server enforces on itself, and
        // hand the CLAMPED config to the manager it builds.
        var captured = new java.util.concurrent.atomic.AtomicReference<SessionConfigS2CPayload>();
        var g = new ClientSessionGate(processor, handshakesSent::incrementAndGet,
                cfg -> { captured.set(cfg); return new RecordingManager(events); });

        g.onSessionConfig(new SessionConfigS2CPayload(V, true,
                Integer.MAX_VALUE, 200_000_000, 500_000_000, true), true);

        var used = captured.get();
        assertNotNull(used, "the manager must be built with the clamped config");
        assertEquals(LSSConstants.MAX_LOD_DISTANCE, used.lodDistanceChunks(),
                "LOD distance clamped to the protocol max");
        assertEquals(LSSConstants.MAX_CONCURRENCY_LIMIT, used.syncOnLoadConcurrencyLimitPerPlayer(),
                "sync concurrency clamped to the protocol max — bounds the scan-budget allocation");
        assertEquals(LSSConstants.MAX_CONCURRENCY_LIMIT, used.generationConcurrencyLimitPerPlayer(),
                "generation concurrency clamped to the protocol max");
        assertEquals(LSSConstants.MAX_LOD_DISTANCE, g.getServerLodDistance(),
                "the stored server LOD distance is the clamped value");
    }

    @Test
    void belowRangeServerConfigIsClampedUpToProtocolMinimum() {
        var captured = new java.util.concurrent.atomic.AtomicReference<SessionConfigS2CPayload>();
        var g = new ClientSessionGate(processor, handshakesSent::incrementAndGet,
                cfg -> { captured.set(cfg); return new RecordingManager(events); });

        g.onSessionConfig(new SessionConfigS2CPayload(V, true, 0, 0, -5, true), true);

        var used = captured.get();
        assertEquals(LSSConstants.MIN_LOD_DISTANCE, used.lodDistanceChunks());
        assertEquals(LSSConstants.MIN_CONCURRENCY_LIMIT, used.syncOnLoadConcurrencyLimitPerPlayer());
        assertEquals(LSSConstants.MIN_CONCURRENCY_LIMIT, used.generationConcurrencyLimitPerPlayer());
    }

    @Test
    void validConfigWithoutConsumersSkipsSessionSetup() {
        gate.onSessionConfig(config(V, true), false);

        assertTrue(gate.isServerEnabled(), "the flag mirrors the server's answer even with no consumer");
        assertTrue(gate.hasReceivedSessionConfig());
        assertNull(gate.getRequestManager(), "nothing to deliver columns to — no session setup");
        assertEquals(List.of(), events);
    }

    @Test
    void secondValidConfigRunsDisconnectTeardownBeforeReplacingManager() {
        gate.onSessionConfig(config(V, true), true);
        var first = gate.getRequestManager();
        events.clear();
        // One column still queued for decode: the teardown must report it, not drop it —
        // its received-stamp would otherwise persist for data no consumer ever saw.
        processor.offer(new VoxelColumnS2CPayload(3, -4, dim("overworld"), 1L, new byte[0]), false);

        gate.onSessionConfig(config(V, true), true);

        assertEquals(List.of("report", "disconnect", "save", "rebuild"), events,
                "order is load-bearing: report undispatched → save cache → build the replacement");
        assertNotSame(first, gate.getRequestManager(), "a fresh manager must replace the old one");
        assertEquals(0, processor.getQueuedCount(), "the old session's decode backlog is drained");
        assertTrue(gate.isServerEnabled());
    }

    // ---- JOIN ladder ----

    @Test
    void joinResetsSessionStateEvenWhenLodsDisabled() {
        gate.onSessionConfig(config(V, true), true); // stale state from a previous connection
        gate.recordColumnFrame(512);

        gate.onJoin(false, false, true);

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
        gate.onJoin(true, true, true);

        assertEquals(0, handshakesSent.get(),
                "the local integrated server must never receive an LSS handshake"
                        + " (the lss.test.integratedServer override is folded in by the caller)");
    }

    @Test
    void joinWithoutConsumersStaysSilent() {
        gate.onJoin(true, false, false);

        assertEquals(0, handshakesSent.get(),
                "no consumer means no handshake at all — the client never advertises capabilities=0");
    }

    @Test
    void joinSendsExactlyOneHandshakeWhenAllGuardsPass() {
        gate.onJoin(true, false, true);

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
                () -> {
                    sends.incrementAndGet();
                    throw new IllegalStateException("channel lss:handshake not registered");
                },
                cfg -> {
                    events.add("rebuild");
                    return new RecordingManager(events);
                });

        assertDoesNotThrow(() -> throwingGate.onJoin(true, false, true),
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
        gate.onSessionConfig(config(V, true), true);
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
}
