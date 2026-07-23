package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the hoisted shared send path {@code flushSendQueue} — the single implementation
 * both Fabric and Paper flush through every tick: submission-order draining, the
 * per-player bandwidth gate, drop-queue-on-sender-failure (returning the dropped
 * positions for diskReadDone clearing), the enqueued-column lifecycle, and the
 * cross-thread send-queue size snapshot.
 *
 * <p>The per-player token bucket is hard-wired into the state, starts empty, and refills
 * from real elapsed time (&gt;=1ms granularity, burst cap allocationBytes/4), so tests
 * that need send tokens sleep first: ~50ms with a large allocation for "plenty of
 * tokens", ~350ms (past the 250ms burst window) with allocationBytes=4 for "exactly
 * one token".
 */
class FlushSendQueueTest {

    private static final long BIG_ALLOCATION = 1_000_000_000L;

    private static final long POS_1 = PositionUtil.packPosition(10, 0);
    private static final long POS_2 = PositionUtil.packPosition(11, 0);
    private static final long POS_3 = PositionUtil.packPosition(12, 0);

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
        state.addReadyPayload(new QueuedPayload<>("third", 0, 2, POS_3));
        state.addReadyPayload(new QueuedPayload<>("first", 0, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("second", 0, 1, POS_2));
        Thread.sleep(50); // the empty token bucket only refills after >=1ms of real time

        long[] dropped = state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add);

        assertEquals(List.of("first", "second", "third"), sent,
                "PriorityQueue must reorder FIFO arrivals by submissionOrder");
        assertEquals(0, dropped.length, "a clean flush drops nothing");
        assertEquals(0, state.getSendQueueSize());
        assertEquals(3, state.getTotalSectionsSent());
    }

    @Test
    void enqueuedColumnTrackingFollowsThePayloadLifecycle() throws Exception {
        assertFalse(state.hasEnqueuedColumn(POS_1), "nothing enqueued yet");
        state.addReadyPayload(new QueuedPayload<>("first", 0, 0, POS_1));
        assertTrue(state.hasEnqueuedColumn(POS_1), "enqueued at addReadyPayload");
        Thread.sleep(50);

        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add);

        assertFalse(state.hasEnqueuedColumn(POS_1), "cleared once the payload hits the wire");
    }

    @Test
    void duplicateEnqueueForOnePositionIsCountedNotClobbered() throws Exception {
        // A dirty re-serve can put a second payload for the same position in flight: the
        // first send must not clear the flag while the second payload is still queued.
        state.addReadyPayload(new QueuedPayload<>("first", 1000, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("again", 1000, 1, POS_1));
        // allocation=4 -> exactly one token after the burst window: "first" sends and
        // depletes the bucket, "again" stays gated.
        Thread.sleep(350);
        state.flushSendQueue(4, limiter, diag, sent::add);

        assertEquals(List.of("first"), sent);
        assertTrue(state.hasEnqueuedColumn(POS_1),
                "position stays enqueued while its second payload is still queued");

        Thread.sleep(50);
        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add);

        assertEquals(List.of("first", "again"), sent);
        assertFalse(state.hasEnqueuedColumn(POS_1),
                "flag clears once the last payload for the position is sent");
    }

    @Test
    void senderFailureDropsTheRemainingQueueAndReturnsDroppedPositions() throws Exception {
        state.addReadyPayload(new QueuedPayload<>("first", 0, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("second", 0, 1, POS_2));
        state.addReadyPayload(new QueuedPayload<>("third", 0, 2, POS_3));
        Thread.sleep(50);

        long[] dropped = state.flushSendQueue(BIG_ALLOCATION, limiter, diag, p -> {
            if (!sent.isEmpty()) throw new Exception("broken connection");
            sent.add(p);
        });

        assertEquals(List.of("first"), sent, "failure must stop the flush after the first send");
        assertEquals(0, state.getSendQueueSize(), "remaining queue must be dropped on failure");
        // The failed head ("second") and everything behind it are dropped and reported —
        // the caller routes these to clearDiskReadDone so re-requests re-resolve.
        assertEquals(2, dropped.length);
        assertTrue(contains(dropped, POS_2) && contains(dropped, POS_3),
                "dropped positions must identify the discarded columns");
        assertFalse(state.hasEnqueuedColumn(POS_2), "dropped payloads leave the enqueued set");
        assertFalse(state.hasEnqueuedColumn(POS_3), "dropped payloads leave the enqueued set");
        assertFalse(state.hasEnqueuedColumn(POS_1), "sent payload left the enqueued set normally");

        long[] dropped2 = state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add);
        assertEquals(List.of("first"), sent, "dropped payloads must not resurface");
        assertEquals(0, dropped2.length);
    }

    @Test
    void zeroAllocationSendsNothingButStillSnapshotsTheQueue() {
        state.addReadyPayload(new QueuedPayload<>("a", 100, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("b", 100, 1, POS_2));
        assertEquals(0, state.getSendQueueSize(), "snapshot is stale until the main-thread flush");

        state.flushSendQueue(0, limiter, diag, sent::add);

        assertTrue(sent.isEmpty());
        assertEquals(2, state.getSendQueueSize(),
                "snapshot must reflect drained-but-unsent payloads for cross-thread readers");
        assertTrue(state.hasEnqueuedColumn(POS_1) && state.hasEnqueuedColumn(POS_2),
                "gated payloads remain enqueued — a ts<=0 re-declaration of a column still in the "
                        + "send pipeline is skipped silently, never re-resolved "
                        + "(IncomingRequestRouter.resolvedAsDuplicate)");
    }

    @Test
    void bandwidthGateStopsTheFlushMidQueue() throws Exception {
        state.addReadyPayload(new QueuedPayload<>("first", 1000, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("second", 1000, 1, POS_2));
        state.addReadyPayload(new QueuedPayload<>("third", 1000, 2, POS_3));
        // allocationBytes=4 -> burst cap = 1 token; sleeping past the 250ms burst window
        // yields exactly one token, and per-iteration refills truncate to zero.
        Thread.sleep(350);

        state.flushSendQueue(4, limiter, diag, sent::add);

        assertEquals(List.of("first"), sent, "one token admits exactly one send");
        assertEquals(2, state.getSendQueueSize(), "gated payloads must stay queued for the next tick");
    }

    // ---- Duplicate-serve grace: departure stamps (docs/planning/duplicate-serve-grace.md) ----

    @Test
    void departureGraceStampsOnSendSuccessOnly() throws Exception {
        state.addReadyPayload(new QueuedPayload<>("first", 0, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("second", 0, 1, POS_2));
        state.addReadyPayload(new QueuedPayload<>("third", 0, 2, POS_3));
        Thread.sleep(50);

        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, p -> {
            if (!sent.isEmpty()) throw new Exception("broken connection");
            sent.add(p);
        });

        assertTrue(state.isWithinDepartureGrace(POS_1),
                "a successful send stamps the payload's departure");
        assertFalse(state.isWithinDepartureGrace(POS_2),
                "failure-dropped payloads never stamp — they never reached the wire, and "
                        + "suppressing their re-asks would delay exactly the loss class the "
                        + "honesty rung exists for");
        assertFalse(state.isWithinDepartureGrace(POS_3),
                "everything behind the failed send is dropped unstamped too");
    }

    @Test
    void departureGraceExpiresOnReadAndTheSweepPrunesUnaskedStamps() throws Exception {
        var clock = new AtomicLong(1_000_000_000L);
        state.setDepartureGraceForTest(500_000_000L, clock::get);
        state.addReadyPayload(new QueuedPayload<>("a", 0, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("b", 0, 1, POS_2));
        Thread.sleep(50);
        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add);

        assertEquals(2, state.departedColumnCountForTest(), "both sends stamped");
        assertTrue(state.isWithinDepartureGrace(POS_1));

        clock.addAndGet(500_000_001L);
        assertFalse(state.isWithinDepartureGrace(POS_1), "the grace has expired");
        assertEquals(1, state.departedColumnCountForTest(),
                "the expired read removes its own entry");

        // POS_2 is never re-asked (the common case — most sends are never crossed):
        // only the periodic flush-path sweep can prune it, or the map grows with every
        // column ever sent.
        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add);
        assertEquals(0, state.departedColumnCountForTest(),
                "the idle flush sweep prunes stamps nobody ever re-asked");
    }

    @Test
    void zeroGraceDisablesStampingEntirely() throws Exception {
        state.setDepartureGraceForTest(0, System::nanoTime);
        state.addReadyPayload(new QueuedPayload<>("a", 0, 0, POS_1));
        Thread.sleep(50);
        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add);

        assertEquals(List.of("a"), sent);
        assertFalse(state.isWithinDepartureGrace(POS_1));
        assertEquals(0, state.departedColumnCountForTest(), "grace 0 writes no stamps at all");
    }

    @Test
    void productionDefaultEnablesTheDepartureGrace() {
        assertEquals(LSSConstants.SEND_DEPARTURE_GRACE_MILLIS * 1_000_000L,
                new TestState().departureGraceNanosForTest(),
                "the wired production default must stay nonzero — a silent zero would "
                        + "quietly revert every crossing re-ask to a redundant re-serve");
    }

    private static boolean contains(long[] arr, long value) {
        for (long v : arr) if (v == value) return true;
        return false;
    }
}
