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
            super(players, null, false, null, 1, 0);  // memo off (ttl=0): kills only the memo — the pacing rules are ttl-independent
        }

        @Override
        protected boolean submitDiskRead(UUID playerUuid, RequestRegistration registration, String dimension, int cx, int cz, long order, long clientTimestamp) {
            return true;
        }

        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                     long columnTimestamp, long submissionOrder,
                                                     ColumnBytes bytes, int estimatedBytes, byte source) {
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
    private static void produceNotGenerated(TestProcessor proc, TestState state, int cx, int cz,
                                            long expectedTotalGenDrained) throws InterruptedException {
        proc.feedGenerationFailure(state.getPlayerUUID(), state.registration(), cx, cz, DIM, expectedTotalGenDrained, false);
        proc.postSnapshot(snapshot(state.getPlayerUUID()), List.of());
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
            produceNotGenerated(proc, old, 1, 0, 1);

            // Dimension change: same UUID re-registered with a fresh, fully handshaked state,
            // so a delivered action could only have passed the identity check by mistake.
            var fresh = new TestState(u);
            fresh.markHandshakeComplete();
            players.put(u, fresh);

            assertEquals(List.of(), drainOnce(proc),
                    "action produced for the replaced session must not reach the fresh one");

            // The pipeline still serves the fresh session — only the stale action died.
            produceNotGenerated(proc, fresh, 2, 0, 2);
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
            produceNotGenerated(proc, state, 3, 0, 1);

            // Disconnect (production removePlayer order: drop the map entry, then notify).
            players.remove(u);
            proc.notifyPlayerRemoved(u, state.registration());

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
            produceNotGenerated(proc, state, 1, 0, 1);

            assertEquals(List.of(), drainOnce(proc),
                    "no batched responses may reach a session before its handshake completes");

            state.markHandshakeComplete();
            produceNotGenerated(proc, state, 2, 0, 2);
            assertEquals(List.of(new Delivered(u, LSSConstants.RESPONSE_NOT_GENERATED,
                            PositionUtil.packPosition(2, 0))),
                    drainOnce(proc),
                    "after the handshake only fresh actions deliver — the gated one was dropped, not held");
        } finally {
            proc.shutdown();
        }
    }

    /** 2026-08-05 review P1: the drain is the ONE choke point that stamps probe
     *  suppression for up_to_date answers (all five producer sites funnel through it) —
     *  and only for actions that pass the identity gate; a NotGenerated answer must not
     *  stamp (its position may still need the probe on revival). */
    @Test
    void drainStampsProbeSuppressOnDeliveredUpToDateOnly() throws Exception {
        var u = UUID.randomUUID();
        var state = new TestState(u);
        state.markHandshakeComplete();
        var players = new ConcurrentHashMap<UUID, TestState>();
        players.put(u, state);
        var proc = new TestProcessor(players);
        long utdPos = PositionUtil.packPosition(4, 0);
        long ngPos = PositionUtil.packPosition(5, 0);
        long stalePos = PositionUtil.packPosition(6, 0);
        try {
            proc.enqueueSendActionForTest(new SendAction.ColumnUpToDate(u, utdPos, state));
            proc.enqueueSendActionForTest(new SendAction.ColumnNotGenerated(u, ngPos, state));
            // Identity-gated action: produced for a replaced session — must not stamp the
            // LIVE state either (it never delivers).
            var replaced = new TestState(u);
            replaced.markHandshakeComplete();
            proc.enqueueSendActionForTest(new SendAction.ColumnUpToDate(u, stalePos, replaced));

            var delivered = drainOnce(proc);
            assertEquals(2, delivered.size(), "the stale-session action must not deliver");
            assertTrue(state.isProbeSuppressed(utdPos),
                    "a delivered up_to_date suppresses the probe for its position");
            assertFalse(state.isProbeSuppressed(ngPos),
                    "a NotGenerated answer never suppresses");
            assertFalse(state.isProbeSuppressed(stalePos),
                    "an identity-dropped action never suppresses");
        } finally {
            proc.shutdown();
        }
    }
}
