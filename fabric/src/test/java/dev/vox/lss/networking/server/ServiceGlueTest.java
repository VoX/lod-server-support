package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.QueuedPayload;
import dev.vox.lss.common.processing.TickDiagnostics;
import dev.vox.lss.common.processing.TickSnapshot;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@link RequestProcessingService} per-tick glue that the MinecraftServer-bound
 * constructor otherwise keeps untestable, via the package-private static
 * {@code flushSendQueues} seam and real (unstarted or started) {@link FabricOffThreadProcessor}
 * collaborators: a thrown send routing every dropped position into
 * {@code clearDiskReadDone}, the pre-handshake flush gate, per-player failure isolation of
 * the batched-response drain, fresh per-tick snapshot buffer identity (the buffer-reuse JMM
 * race class), and the soak/gametest-only send-drop fault seam ({@code armSendDrops}).
 */
class ServiceGlueTest {

    private static final long BIG_ALLOCATION = 1_000_000_000L;
    private static final String DIM = LSSConstants.DIM_STR_OVERWORLD;

    private static final long POS_1 = PositionUtil.packPosition(10, 0);
    private static final long POS_2 = PositionUtil.packPosition(11, 0);
    private static final long POS_3 = PositionUtil.packPosition(12, 0);

    /** Same pattern as FlushSendQueueTest's TestState, but a real PlayerRequestState (the
     *  type the service glue is declared over), via its no-player test-seam constructor. */
    private static final class TestPlayerState extends PlayerRequestState {
        TestPlayerState() { super(UUID.randomUUID(), 4, 4); }
        @Override public String getPlayerName() { return "glue-test"; }
    }

    /** Opaque stand-in payload: the flush path never reads the type. */
    private record TestPayload(String name) implements CustomPacketPayload {
        @Override public Type<? extends CustomPacketPayload> type() { return null; }
    }

    /** Records what the glue routes into the real {@code clearDiskReadDone} entry point
     *  (synchronously, on the calling thread — the processing thread is never started). */
    private static final class RecordingProcessor extends FabricOffThreadProcessor {
        record Cleared(UUID playerUuid, long[] positions) {}
        final List<Cleared> cleared = new ArrayList<>();

        RecordingProcessor(Map<UUID, PlayerRequestState> players) {
            super(players, null, false, null, 1, 0);  // memo off: these rigs pin the ttl=0 (pre-memo) read path
        }

        @Override
        public void clearDiskReadDone(UUID playerUuid, long[] positions) {
            this.cleared.add(new Cleared(playerUuid, positions));
            super.clearDiskReadDone(playerUuid, positions);
        }
    }

    @AfterEach
    void disarmFaultSeam() {
        RequestProcessingService.armSendDrops(0);
    }

    private static ConcurrentHashMap<UUID, PlayerRequestState> playersOf(PlayerRequestState... states) {
        var players = new ConcurrentHashMap<UUID, PlayerRequestState>();
        for (var s : states) players.put(s.getPlayerUUID(), s);
        return players;
    }

    private static TestPlayerState handshakenState() {
        var state = new TestPlayerState();
        state.markHandshakeComplete();
        return state;
    }

    private static void flush(Map<UUID, PlayerRequestState> players,
                              RequestProcessingService.ColumnPayloadSender sender,
                              FabricOffThreadProcessor proc) {
        RequestProcessingService.flushSendQueues(players.values(), BIG_ALLOCATION,
                new SharedBandwidthLimiter(BIG_ALLOCATION), new TickDiagnostics(), sender, proc);
    }

    private static List<String> names(List<CustomPacketPayload> payloads) {
        var out = new ArrayList<String>();
        for (var p : payloads) out.add(((TestPayload) p).name());
        return out;
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: " + what);
            Thread.sleep(10);
        }
    }

    // ---- SP-036: thrown send -> dropped positions reach clearDiskReadDone ----

    @Test
    void thrownSendRoutesEveryDroppedPositionIntoClearDiskReadDone() throws Exception {
        var state = handshakenState();
        var players = playersOf(state);
        var proc = new RecordingProcessor(players);
        state.addReadyPayload(new QueuedPayload<>(new TestPayload("first"), 0, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>(new TestPayload("second"), 0, 1, POS_2));
        state.addReadyPayload(new QueuedPayload<>(new TestPayload("third"), 0, 2, POS_3));
        Thread.sleep(50); // the empty per-player token bucket only refills after >=1ms

        var sent = new ArrayList<CustomPacketPayload>();
        flush(players, (s, p) -> {
            if (!sent.isEmpty()) throw new Exception("broken connection");
            sent.add(p);
        }, proc);

        assertEquals(List.of("first"), names(sent), "the failure stops the flush after the first send");
        assertEquals(1, proc.cleared.size(), "exactly one clearDiskReadDone call for the drop batch");
        assertEquals(state.getPlayerUUID(), proc.cleared.get(0).playerUuid(),
                "the clear must target the player whose queue was dropped");
        long[] clearedPositions = proc.cleared.get(0).positions().clone();
        Arrays.sort(clearedPositions);
        assertArrayEquals(new long[]{POS_2, POS_3}, clearedPositions,
                "every dropped position — and never the delivered one — must be routed to clearDiskReadDone");

        // A later clean flush has nothing left to send or clear: drops do not resurface.
        Thread.sleep(50);
        var resent = new ArrayList<CustomPacketPayload>();
        flush(players, (s, p) -> resent.add(p), proc);
        assertEquals(List.of(), resent, "dropped payloads must not resurface on the next flush");
        assertEquals(1, proc.cleared.size(), "a clean flush must never touch clearDiskReadDone");
    }

    @Test
    void preHandshakePlayerIsSkippedAndItsQueueIsRetainedNotDropped() throws Exception {
        var state = new TestPlayerState(); // handshake NOT complete
        var players = playersOf(state);
        var proc = new RecordingProcessor(players);
        state.addReadyPayload(new QueuedPayload<>(new TestPayload("early"), 0, 0, POS_1));
        Thread.sleep(50);

        var sent = new ArrayList<CustomPacketPayload>();
        flush(players, (s, p) -> sent.add(p), proc);
        assertEquals(List.of(), sent, "no payload may go on the wire before the handshake completes");
        assertEquals(List.of(), proc.cleared, "skipping a gated player is not a drop");

        state.markHandshakeComplete();
        Thread.sleep(50);
        flush(players, (s, p) -> sent.add(p), proc);
        assertEquals(List.of("early"), names(sent),
                "the queued payload must be retained while gated and delivered after the handshake");
    }

    // ---- SP-050: one player's batch-send failure must not starve the same drain ----

    @Test
    void onePlayersBatchSendFailureDoesNotStarveOthersInTheSameDrain() throws Exception {
        var a = handshakenState();
        var b = handshakenState();
        var c = handshakenState();
        var players = playersOf(a, b, c);
        var proc = new FabricOffThreadProcessor(players, null, false, null, 1, 0);
        try {
            proc.start();
            // One ColumnNotGenerated response per player, produced by the real processing
            // cycle (feedGenerationFailure is the public lossless-event entry point).
            proc.feedGenerationFailure(a.getPlayerUUID(), 3, 4, DIM, 1L, false);
            proc.feedGenerationFailure(b.getPlayerUUID(), 5, 6, DIM, 2L, false);
            proc.feedGenerationFailure(c.getPlayerUUID(), 7, 8, DIM, 3L, false);
            var dims = new HashMap<UUID, String>();
            for (var uuid : players.keySet()) dims.put(uuid, DIM);
            proc.postSnapshot(new TickSnapshot(dims, Map.of(), 0, false), List.of());
            waitFor(() -> proc.getDiagnostics().getTotalGenDrained() >= 3,
                    "all three generation outcomes drained into send actions");

            var expectedByPlayer = Map.of(
                    a.getPlayerUUID(), LSSConstants.RESPONSE_NOT_GENERATED + "@" + PositionUtil.packPosition(3, 4),
                    b.getPlayerUUID(), LSSConstants.RESPONSE_NOT_GENERATED + "@" + PositionUtil.packPosition(5, 6),
                    c.getPlayerUUID(), LSSConstants.RESPONSE_NOT_GENERATED + "@" + PositionUtil.packPosition(7, 8));

            // The FIRST attempted player throws (player iteration order is unspecified, so the
            // thrower is chosen dynamically — the remaining two must still deliver).
            var attempted = new ArrayList<UUID>();
            var delivered = new ArrayList<String>();
            assertDoesNotThrow(() -> proc.drainSendActions((state, types, positions, count) -> {
                boolean first = attempted.isEmpty();
                attempted.add(state.getPlayerUUID());
                if (first) throw new Exception("broken connection");
                for (int i = 0; i < count; i++) delivered.add(types[i] + "@" + positions[i]);
            }), "one player's send failure must be contained inside the drain");

            assertEquals(3, attempted.size(), "every player's batch is attempted in the same drain");
            var expectedDelivered = new HashSet<String>();
            for (var uuid : attempted.subList(1, attempted.size())) {
                expectedDelivered.add(expectedByPlayer.get(uuid));
            }
            assertEquals(expectedDelivered, new HashSet<>(delivered),
                    "the healthy players' batches must still deliver, with their own responses");

            // The failed batch dies with its drain (the client's rescan re-asks); it must
            // not be redelivered to anyone on the next drain.
            var redelivered = new ArrayList<UUID>();
            proc.drainSendActions((state, types, positions, count) -> redelivered.add(state.getPlayerUUID()));
            assertEquals(List.of(), redelivered, "a drained batch is consumed even when its send failed");
        } finally {
            proc.shutdown();
        }
    }

    // ---- SP-047: per-tick snapshot buffers are fresh and hand off zero-copy ----

    @Test
    void consecutiveTickSnapshotBuffersAreFreshAndHandOffZeroCopy() {
        var tick1 = RequestProcessingService.SnapshotBuffers.newPerTick();
        var tick2 = RequestProcessingService.SnapshotBuffers.newPerTick();

        assertNotSame(tick1.playerDimensions(), tick2.playerDimensions(),
                "a reused dimension buffer would be repopulated under the processing thread's iteration");
        assertNotSame(tick1.loadedChunkProbes(), tick2.loadedChunkProbes(),
                "a reused probe buffer would be repopulated under the processing thread's iteration");
        assertTrue(tick1.playerDimensions().isEmpty() && tick1.loadedChunkProbes().isEmpty(),
                "fresh buffers must start empty — leftovers would resurrect departed players");

        var uuid = UUID.randomUUID();
        tick1.playerDimensions().put(uuid, DIM);
        var probes = new Long2ObjectOpenHashMap<LoadedColumnData>();
        probes.put(POS_1, new LoadedColumnData(10, 0, new byte[]{1}, 1));
        tick1.loadedChunkProbes().put(uuid, probes);

        var snapshot = tick1.toSnapshot(7);
        assertSame(tick1.playerDimensions(), snapshot.playerDimensions(),
                "ownership transfer is zero-copy: the populated buffer IS the snapshot's map");
        assertSame(tick1.loadedChunkProbes(), snapshot.loadedChunkProbes(),
                "ownership transfer is zero-copy: the populated buffer IS the snapshot's map");
        assertEquals(DIM, snapshot.playerDimensions().get(uuid));
        assertSame(probes, snapshot.loadedChunkProbes().get(uuid));
        assertEquals(7, snapshot.maxSendQueueSize());
        assertFalse(snapshot.shutdown(), "per-tick snapshots must never carry the shutdown sentinel");

        assertNotSame(snapshot.playerDimensions(), tick2.toSnapshot(7).playerDimensions(),
                "consecutive ticks' snapshots must not alias each other's maps");
    }

    // ---- SP-081 (fault-seam half): drop-next-N-sends trigger in the flush path ----

    @Test
    void armedSendDropsSilentlyDiscardExactlyNSendsAndLeaveDoneBitsSet() throws Exception {
        System.setProperty("lss.soak.scenario", "service-glue-test");
        try {
            var state = handshakenState();
            state.markDiskReadDone(10, 0);
            state.markDiskReadDone(11, 0);
            state.markDiskReadDone(12, 0);
            var players = playersOf(state);
            var proc = new RecordingProcessor(players);
            state.addReadyPayload(new QueuedPayload<>(new TestPayload("first"), 0, 0, POS_1));
            state.addReadyPayload(new QueuedPayload<>(new TestPayload("second"), 0, 1, POS_2));
            state.addReadyPayload(new QueuedPayload<>(new TestPayload("third"), 0, 2, POS_3));
            Thread.sleep(50);

            long injectedBefore = RequestProcessingService.totalSendDropsInjected();
            RequestProcessingService.armSendDrops(2);
            assertEquals(2, RequestProcessingService.pendingSendDrops(), "soak JVMs may arm the fault");

            var sent = new ArrayList<CustomPacketPayload>();
            flush(players, (s, p) -> sent.add(p), proc);

            assertEquals(List.of("third"), names(sent),
                    "the first two armed sends vanish on the wire, the third goes out normally");
            assertEquals(0, RequestProcessingService.pendingSendDrops(),
                    "the trigger is count-based and self-disarms after N drops");
            assertEquals(2, RequestProcessingService.totalSendDropsInjected() - injectedBefore);
            assertEquals(0, state.getSendQueueSize(),
                    "dropped sends complete the flush exactly like delivered ones");
            assertEquals(List.of(), proc.cleared,
                    "an injected drop must NOT clear done-bits: the server honestly believes it "
                    + "delivered; recovery is the client's ts<=0 re-request re-resolving");
            assertTrue(state.hasDiskReadDone(10, 0) && state.hasDiskReadDone(11, 0)
                    && state.hasDiskReadDone(12, 0), "diskReadDone survives the injected loss");
            assertFalse(state.hasEnqueuedColumn(POS_1) || state.hasEnqueuedColumn(POS_2)
                    || state.hasEnqueuedColumn(POS_3),
                    "nothing stays in the send pipeline, so a re-request re-resolves instead of bouncing");
        } finally {
            System.clearProperty("lss.soak.scenario");
            RequestProcessingService.armSendDrops(0);
        }
    }

    @Test
    void armIsRefusedOutsideSoakAndGametestJvmsAndDisarmIsAlwaysAllowed() throws Exception {
        String soak = System.getProperty("lss.soak.scenario");
        String gametest = System.getProperty("lss.test.integratedServer");
        System.clearProperty("lss.soak.scenario");
        System.clearProperty("lss.test.integratedServer");
        try {
            RequestProcessingService.armSendDrops(3);
            assertEquals(0, RequestProcessingService.pendingSendDrops(),
                    "arming must be refused without the soak/gametest gate properties");

            // The soakServer run config always defines -Dlss.soak.scenario, as the empty
            // string when no scenario is staged: blank must count as unset.
            System.setProperty("lss.soak.scenario", "  ");
            RequestProcessingService.armSendDrops(3);
            assertEquals(0, RequestProcessingService.pendingSendDrops(),
                    "a blank scenario property must not arm the fault");

            System.setProperty("lss.soak.scenario", "scenario.json");
            RequestProcessingService.armSendDrops(3);
            assertEquals(3, RequestProcessingService.pendingSendDrops());
            System.clearProperty("lss.soak.scenario");
            RequestProcessingService.armSendDrops(0);
            assertEquals(0, RequestProcessingService.pendingSendDrops(),
                    "disarming is always allowed, even outside soak/gametest JVMs");

            // Unarmed, the flush path is untouched: the payload goes out.
            var state = handshakenState();
            var players = playersOf(state);
            var proc = new RecordingProcessor(players);
            state.addReadyPayload(new QueuedPayload<>(new TestPayload("prod"), 0, 0, POS_1));
            Thread.sleep(50);
            var sent = new ArrayList<CustomPacketPayload>();
            flush(players, (s, p) -> sent.add(p), proc);
            assertEquals(List.of("prod"), names(sent),
                    "with the seam unarmed the flush must behave exactly like production");
            assertEquals(List.of(), proc.cleared);
        } finally {
            restoreProperty("lss.soak.scenario", soak);
            restoreProperty("lss.test.integratedServer", gametest);
            RequestProcessingService.armSendDrops(0);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
