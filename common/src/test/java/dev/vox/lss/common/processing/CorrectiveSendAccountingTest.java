package dev.vox.lss.common.processing;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Actual send/prune/failure paths; no enqueue is counted as a successful correction. */
class CorrectiveSendAccountingTest {
    private static final long BUDGET = 1_000_000;
    private static final long POS = PositionUtil.packPosition(0, 0);
    private static final class State extends AbstractPlayerRequestState<String> {
        State(AtomicLong clock) { super(UUID.randomUUID(), 1, 1, clock::get); }
        @Override public String getPlayerName() { return "correction-accounting"; }
    }
    private static void corrective(State state, long order, long pos) {
        assertTrue(state.beginCorrectiveEnqueue(order));
        try { state.addReadyPayload(new QueuedPayload<>("corrective", 10, order, pos)); }
        finally { state.endCorrectiveEnqueue(); }
    }
    @Test void onlyActualSuccessfulCorrectiveFrameIsCounted() {
        var clock = new AtomicLong(); var state = new State(clock); var diag = new TickDiagnostics();
        var sent = new ArrayList<String>();
        corrective(state, 1, POS);
        state.addReadyPayload(new QueuedPayload<>("ordinary", 10, 2, POS));
        assertEquals(0, diag.getTotalCorrectiveColumnsSent());
        clock.set(1_000_000_000L);
        state.flushSendQueue(BUDGET, new SharedBandwidthLimiter(BUDGET), diag, sent::add);
        assertEquals(java.util.List.of("corrective", "ordinary"), sent);
        assertEquals(2, diag.getTotalSectionsSent()); assertEquals(1, diag.getTotalCorrectiveColumnsSent());
    }
    @Test void throwingSenderDropsWithoutCountingCorrection() {
        var clock = new AtomicLong(); var state = new State(clock); var diag = new TickDiagnostics();
        corrective(state, 1, POS); clock.set(1_000_000_000L);
        long[] dropped = state.flushSendQueue(BUDGET, new SharedBandwidthLimiter(BUDGET), diag,
                body -> { throw new IllegalStateException("transport failure"); });
        assertArrayEquals(new long[]{POS}, dropped);
        assertEquals(0, diag.getTotalSectionsSent()); assertEquals(0, diag.getTotalCorrectiveColumnsSent());
        assertFalse(state.hasEnqueuedColumn(POS));
    }
    @Test void relevancePruneDoesNotCountCorrection() {
        var clock = new AtomicLong(); var state = new State(clock); var diag = new TickDiagnostics();
        long far = PositionUtil.packPosition(700, 0); state.updatePlayerChunk(0, 0);
        corrective(state, 1, far); clock.set(1_000_000_000L);
        state.setFlushTickCounterForTest(AbstractPlayerRequestState.PRUNE_INTERVAL_TICKS - 1);
        long[] dropped = state.flushSendQueue(BUDGET, new SharedBandwidthLimiter(BUDGET), diag,
                body -> fail("pruned correction must not reach sender"), true, 1);
        assertArrayEquals(new long[]{far}, dropped);
        assertEquals(0, diag.getTotalCorrectiveColumnsSent()); assertEquals(0, diag.getTotalSectionsSent());
    }
    @Test void failedBuildScopeDoesNotTagAnOrdinaryLaterEnqueue() {
        var clock = new AtomicLong(); var state = new State(clock); var diag = new TickDiagnostics();
        assertTrue(state.beginCorrectiveEnqueue(1)); state.endCorrectiveEnqueue(); // failed build: no frame
        state.addReadyPayload(new QueuedPayload<>("ordinary", 10, 1, POS));
        clock.set(1_000_000_000L);
        state.flushSendQueue(BUDGET, new SharedBandwidthLimiter(BUDGET), diag, body -> {});
        assertEquals(1, diag.getTotalSectionsSent()); assertEquals(0, diag.getTotalCorrectiveColumnsSent());
    }
    @Test void otherSubmissionDuringScopeDoesNotInheritCorrectionTag() {
        var clock = new AtomicLong(); var state = new State(clock); var diag = new TickDiagnostics();
        assertTrue(state.beginCorrectiveEnqueue(1));
        try { state.addReadyPayload(new QueuedPayload<>("ordinary", 10, 2, POS)); }
        finally { state.endCorrectiveEnqueue(); }
        clock.set(1_000_000_000L);
        state.flushSendQueue(BUDGET, new SharedBandwidthLimiter(BUDGET), diag, body -> {});
        assertEquals(0, diag.getTotalCorrectiveColumnsSent());
    }
    @Test void retirementDuringSuccessfulSendCannotEraseQueuedClassification() {
        var clock = new AtomicLong(); var state = new State(clock); var diag = new TickDiagnostics();
        corrective(state, 1, POS); clock.set(1_000_000_000L);
        state.flushSendQueue(BUDGET, new SharedBandwidthLimiter(BUDGET), diag, body -> {
            state.registration().retire(); state.discardProbeHandoff();
        });
        assertEquals(1, diag.getTotalSectionsSent()); assertEquals(1, diag.getTotalCorrectiveColumnsSent());
        assertFalse(state.beginCorrectiveEnqueue(2));
    }
    @Test void defaultSentinelAndOrdinaryZeroOrderAreNeverCorrective() {
        var clock = new AtomicLong(); var state = new State(clock); var diag = new TickDiagnostics();
        assertFalse(state.beginCorrectiveEnqueue(Long.MIN_VALUE));
        state.addReadyPayload(new QueuedPayload<>("sentinel", 10, Long.MIN_VALUE, POS));
        state.addReadyPayload(new QueuedPayload<>("zero", 10, 0, POS));
        clock.set(1_000_000_000L);
        state.flushSendQueue(BUDGET, new SharedBandwidthLimiter(BUDGET), diag, body -> {});
        assertEquals(2, diag.getTotalSectionsSent()); assertEquals(0, diag.getTotalCorrectiveColumnsSent());
    }
}
