package dev.vox.lss.common.processing;

import dev.vox.lss.common.SharedBandwidthLimiter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the hoisted shared send path {@code flushSendQueue} — the single implementation
 * both Fabric and Paper flush through every tick: submission-order draining, the
 * per-player bandwidth gate, drop-queue-on-sender-failure, and the cross-thread
 * send-queue size snapshot.
 *
 * <p>The per-player token bucket is hard-wired into the state, starts empty, and refills
 * from real elapsed time (&gt;=1ms granularity, burst cap allocationBytes/4), so tests
 * that need send tokens sleep first: ~50ms with a large allocation for "plenty of
 * tokens", ~350ms (past the 250ms burst window) with allocationBytes=4 for "exactly
 * one token".
 */
class FlushSendQueueTest {

    private static final long BIG_ALLOCATION = 1_000_000_000L;

    /** Minimal concrete state — same pattern as SlotAdmissionTest, with T=String. */
    private static final class TestState extends AbstractPlayerRequestState<String> {
        TestState() { super(UUID.randomUUID(), 1, 1); }
        @Override public String getPlayerName() { return "test"; }
    }

    private final SharedBandwidthLimiter limiter = new SharedBandwidthLimiter(BIG_ALLOCATION);
    private final TickDiagnostics diag = new TickDiagnostics();
    private final List<String> sent = new ArrayList<>();
    private final TestState state = new TestState();

    @Test
    void drainsInSubmissionOrderAndRefreshesSnapshot() throws Exception {
        // estimatedBytes=0: sends never deplete tokens, so one refill covers the whole queue
        state.addReadyPayload(new QueuedPayload<>("third", 0, 2));
        state.addReadyPayload(new QueuedPayload<>("first", 0, 0));
        state.addReadyPayload(new QueuedPayload<>("second", 0, 1));
        Thread.sleep(50); // the empty token bucket only refills after >=1ms of real time

        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add);

        assertEquals(List.of("first", "second", "third"), sent,
                "PriorityQueue must reorder FIFO arrivals by submissionOrder");
        assertEquals(0, state.getSendQueueSize());
        assertEquals(3, state.getTotalSectionsSent());
    }

    @Test
    void senderFailureDropsTheRemainingQueue() throws Exception {
        state.addReadyPayload(new QueuedPayload<>("first", 0, 0));
        state.addReadyPayload(new QueuedPayload<>("second", 0, 1));
        state.addReadyPayload(new QueuedPayload<>("third", 0, 2));
        Thread.sleep(50);

        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, p -> {
            if (!sent.isEmpty()) throw new Exception("broken connection");
            sent.add(p);
        });

        assertEquals(List.of("first"), sent, "failure must stop the flush after the first send");
        assertEquals(0, state.getSendQueueSize(), "remaining queue must be dropped on failure");

        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add);
        assertEquals(List.of("first"), sent, "dropped payloads must not resurface");
    }

    @Test
    void zeroAllocationSendsNothingButStillSnapshotsTheQueue() {
        state.addReadyPayload(new QueuedPayload<>("a", 100, 0));
        state.addReadyPayload(new QueuedPayload<>("b", 100, 1));
        assertEquals(0, state.getSendQueueSize(), "snapshot is stale until the main-thread flush");

        state.flushSendQueue(0, limiter, diag, sent::add);

        assertTrue(sent.isEmpty());
        assertEquals(2, state.getSendQueueSize(),
                "snapshot must reflect drained-but-unsent payloads for cross-thread readers");
    }

    @Test
    void bandwidthGateStopsTheFlushMidQueue() throws Exception {
        state.addReadyPayload(new QueuedPayload<>("first", 1000, 0));
        state.addReadyPayload(new QueuedPayload<>("second", 1000, 1));
        state.addReadyPayload(new QueuedPayload<>("third", 1000, 2));
        // allocationBytes=4 -> burst cap = 1 token; sleeping past the 250ms burst window
        // yields exactly one token, and per-iteration refills truncate to zero.
        Thread.sleep(350);

        state.flushSendQueue(4, limiter, diag, sent::add);

        assertEquals(List.of("first"), sent, "one token admits exactly one send");
        assertEquals(2, state.getSendQueueSize(), "gated payloads must stay queued for the next tick");
    }
}
