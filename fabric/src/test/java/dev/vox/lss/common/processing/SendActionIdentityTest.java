package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@code drainSendActions} delivery gates: an action reaches the wire only while
 * the state it was produced for is STILL the live session for its UUID and that session
 * has completed the handshake. A dimension change or rejoin re-registers the same UUID
 * with a fresh state — actions produced for the old session must die with it, never leak
 * into the new one as answers to requests it never made.
 */
class SendActionIdentityTest {

    private static final String DIM = "minecraft:overworld";

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState(UUID uuid) { super(uuid, 4, 4); }
        @Override public String getPlayerName() { return "test"; }
    }

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        TestProcessor(Map<UUID, TestState> players) {
            super(players, null, false, null, 1);
        }

        @Override
        protected boolean submitDiskRead(UUID playerUuid, String dimension, int cx, int cz, long order) {
            return true;
        }

        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                     long columnTimestamp, long submissionOrder,
                                                     byte[] sectionBytes, int estimatedBytes) {
            // not exercised: generation failures only produce batched send actions
            return true;
        }
    }

    private record Delivered(UUID playerUuid, byte responseType, long packedPosition) {}

    private static TickSnapshot snapshot(UUID uuid) {
        var dims = new HashMap<UUID, String>();
        dims.put(uuid, DIM);
        return new TickSnapshot(dims, Map.of(), 0, false);
    }

    private static List<Delivered> drainOnce(TestProcessor proc) {
        var out = new ArrayList<Delivered>();
        proc.drainSendActions((state, types, positions, count) -> {
            for (int i = 0; i < count; i++) {
                out.add(new Delivered(state.getPlayerUUID(), types[i], positions[i]));
            }
        });
        return out;
    }

    /**
     * Feed one generation failure and wait until the processing thread has produced its
     * ColumnNotGenerated action (the gen-drained counter increments after the action is
     * queued, so observing it proves the action exists before the test interferes).
     */
    private static void produceNotGenerated(TestProcessor proc, UUID uuid, int cx, int cz,
                                            long expectedTotalGenDrained) throws InterruptedException {
        proc.feedGenerationFailure(uuid, cx, cz, DIM, expectedTotalGenDrained);
        proc.postSnapshot(snapshot(uuid), List.of());
        waitFor(() -> proc.getDiagnostics().getTotalGenDrained() == expectedTotalGenDrained,
                "generation outcome " + expectedTotalGenDrained + " processed");
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: " + what);
            Thread.sleep(10);
        }
    }

    @Test
    void actionProducedForReplacedSessionNeverReachesTheFreshOne() throws Exception {
        var u = UUID.randomUUID();
        var old = new TestState(u);
        old.markHandshakeComplete();
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(u, old);
        var proc = new TestProcessor(players);
        try {
            proc.start();
            produceNotGenerated(proc, u, 1, 0, 1);

            // Dimension change: same UUID re-registered with a fresh, fully handshaked state,
            // so a delivered action could only have passed the identity check by mistake.
            var fresh = new TestState(u);
            fresh.markHandshakeComplete();
            players.put(u, fresh);

            assertEquals(List.of(), drainOnce(proc),
                    "action produced for the replaced session must not reach the fresh one");

            // The pipeline still serves the fresh session — only the stale action died.
            produceNotGenerated(proc, u, 2, 0, 2);
            assertEquals(List.of(new Delivered(u, LSSConstants.RESPONSE_NOT_GENERATED,
                            PositionUtil.packPosition(2, 0))),
                    drainOnce(proc),
                    "the fresh session's own action must deliver exactly once, without the stale one");
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void actionProducedForDisconnectedSessionIsDropped() throws Exception {
        var u = UUID.randomUUID();
        var state = new TestState(u);
        state.markHandshakeComplete();
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(u, state);
        var proc = new TestProcessor(players);
        try {
            proc.start();
            produceNotGenerated(proc, u, 3, 0, 1);

            // Disconnect (production removePlayer order: drop the map entry, then notify).
            players.remove(u);
            proc.notifyPlayerRemoved(u);

            assertEquals(List.of(), drainOnce(proc), "actions die with the disconnected session");
        } finally {
            proc.shutdown();
        }
    }

    @Test
    void actionsAreDroppedNotHeldWhileHandshakeIsIncomplete() throws Exception {
        var u = UUID.randomUUID();
        var state = new TestState(u); // registered but handshake never completed
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(u, state);
        var proc = new TestProcessor(players);
        try {
            proc.start();
            produceNotGenerated(proc, u, 1, 0, 1);

            assertEquals(List.of(), drainOnce(proc),
                    "no batched responses may reach a session before its handshake completes");

            state.markHandshakeComplete();
            produceNotGenerated(proc, u, 2, 0, 2);
            assertEquals(List.of(new Delivered(u, LSSConstants.RESPONSE_NOT_GENERATED,
                            PositionUtil.packPosition(2, 0))),
                    drainOnce(proc),
                    "after the handshake only fresh actions deliver — the gated one was dropped, not held");
        } finally {
            proc.shutdown();
        }
    }
}
