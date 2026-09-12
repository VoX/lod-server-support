package dev.vox.lss.common.processing;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PairedIngressLifecycleTest {
    private static final class State extends AbstractPlayerRequestState<Object> {
        State() { super(UUID.randomUUID(), 4, 4); }
        @Override public String getPlayerName() { return "paired-control"; }
    }
    private static IncomingBatch batch(int... xs) {
        var requests = new IncomingRequest[xs.length];
        for (int i = 0; i < xs.length; i++) requests[i] = new IncomingRequest(xs[i], 0, -1);
        return new IncomingBatch(requests);
    }
    private static Long2ObjectOpenHashMap<LoadedColumnData> probes(int... xs) {
        var result = new Long2ObjectOpenHashMap<LoadedColumnData>();
        for (int x : xs) result.put(PositionUtil.packPosition(x, 0), new LoadedColumnData(x, 0, new byte[]{7}, 1));
        return result;
    }
    private static void release(State s, IncomingBatch batch, Long2ObjectOpenHashMap<LoadedColumnData> probes) {
        s.offerIncomingBatch(batch);
        long generation = s.offerGeneration();
        assertSame(batch, s.takeFreshIncomingBatchForProbe());
        assertTrue(s.republishHeldBatch(batch, generation, probes));
    }
    private static void apply(State s) { var b = s.takeIncomingBatchForRouting(); assertNotNull(b); s.replaceBacklogWith(b); }

    @Test void ordinaryIngressStillRoutesImmediatelyButFoliaFreshWaitsForPump() {
        var ordinary = new State(); var fresh = batch(1); ordinary.offerIncomingBatch(fresh);
        assertSame(fresh, ordinary.takeIncomingBatchForRouting());
        var folia = new State(); folia.requireProbeHandoff(); folia.offerIncomingBatch(fresh);
        assertNull(folia.takeIncomingBatchForRouting()); assertSame(fresh, folia.peekIncomingBatch());
    }
    @Test void releasedEnvelopeCannotBeStolenByLaterPumpAndCopiesOnlyMatchingBoundedProbes() {
        var s = new State(); s.requireProbeHandoff();
        int[] xs = java.util.stream.IntStream.range(0, 600).toArray();
        var ready = probes(xs); ready.put(PositionUtil.packPosition(900, 0), new LoadedColumnData(900, 0, new byte[]{1}, 1));
        var b = batch(xs); release(s, b, ready); ready.clear();
        assertNull(s.takeFreshIncomingBatchForProbe()); assertSame(b, s.peekIncomingBatch()); apply(s);
        for (int x = 0; x < 512; x++) assertNotNull(s.pairedLoadedProbe(PositionUtil.packPosition(x, 0)));
        assertNull(s.pairedLoadedProbe(PositionUtil.packPosition(512, 0)));
        assertNull(s.pairedLoadedProbe(PositionUtil.packPosition(900, 0)));
    }
    @Test void pairedBytesSurvivePollThenRetainedRestoreButNotConvergence() {
        var s = new State(); var key = PositionUtil.packPosition(1, 0); release(s, batch(1), probes(1)); apply(s);
        var req = s.pollBacklog(); assertNotNull(s.pairedLoadedProbe(key));
        s.restoreBacklog(List.of(req)); s.finishProbeRoutingPass(); assertNotNull(s.pairedLoadedProbe(key));
        s.pollBacklog(); s.finishProbeRoutingPass(); assertNull(s.pairedLoadedProbe(key));
    }
    @Test void newerEmptyOfferDropsActiveBytesAndSupersedesOldHeldRelease() {
        var s = new State(); var b = batch(1); release(s, b, probes(1)); apply(s);
        long oldGeneration = s.offerGeneration(); s.offerIncomingBatch(batch());
        assertNull(s.pairedLoadedProbe(PositionUtil.packPosition(1, 0)));
        assertFalse(s.republishHeldBatch(b, oldGeneration, probes(1)));
        apply(s); s.finishProbeRoutingPass(); assertEquals(0, s.getBacklogSize());
    }
    @Test void generationOutcomeFiltersBothPendingAndAlreadyActivePairedBytes() {
        var s = new State(); release(s, batch(1, 2), probes(1, 2));
        s.discardPairedProbe(PositionUtil.packPosition(1, 0)); apply(s);
        assertNull(s.pairedLoadedProbe(PositionUtil.packPosition(1, 0)));
        assertNotNull(s.pairedLoadedProbe(PositionUtil.packPosition(2, 0)));
        s.discardPairedProbe(PositionUtil.packPosition(2, 0)); assertNull(s.pairedLoadedProbe(PositionUtil.packPosition(2, 0)));
    }
    @Test void retirementDropsPendingAndActiveHolders() {
        var s = new State(); release(s, batch(1), probes(1)); apply(s);
        s.registration().retire(); s.discardProbeHandoff();
        assertNull(s.pairedLoadedProbe(PositionUtil.packPosition(1, 0))); assertNull(s.peekIncomingBatch());
        var pending = new State(); release(pending, batch(1), probes(1)); pending.registration().retire(); pending.discardProbeHandoff();
        assertNull(pending.peekIncomingBatch());
        assertFalse(pending.republishHeldBatch(batch(1), pending.offerGeneration(), probes(1)));
    }
    @Test void explicitBacklogReplacementDropsPreviousPair() {
        var s = new State(); release(s, batch(1), probes(1)); apply(s); s.replaceBacklogWith(batch(2));
        assertNull(s.pairedLoadedProbe(PositionUtil.packPosition(1, 0)));
    }
    private static State lateState(int... xs) {
        var state = new State(); state.requireProbeHandoff();
        state.updatePlayerChunk(0, 0); state.updateLateProbeRange(1024);
        var offered = batch(xs); state.offerIncomingBatch(offered);
        long generation = state.offerGeneration();
        assertSame(offered, state.takeFreshIncomingBatchForProbe());
        long[] positions = java.util.Arrays.stream(xs).mapToLong(x -> PositionUtil.packPosition(x, 0)).toArray();
        state.beginLateProbes(generation, positions);
        assertTrue(state.republishHeldBatch(offered, generation));
        assertSame(offered, state.takeIncomingBatchForRouting());
        return state;
    }
    private static void lateResult(State state, int x, long order) {
        long pos = PositionUtil.packPosition(x, 0);
        state.noteLateProbeDiskSubmission(pos, order); state.markLateProbeDiskFallback(pos, order);
    }
    private static void publishLate(State state, int x, byte[] data) {
        state.publishLateProbe(state.offerGeneration(), new LoadedColumnData(x, 0, data, data.length));
    }
    @Test void lateCorrectionIsOneShotAndRequiresOriginalDiskResult() {
        var state = lateState(1); publishLate(state, 1, new byte[]{7});
        assertNull(state.takeLateProbe(System.nanoTime()), "callback alone cannot create unsolicited work");
        lateResult(state, 1, 20);
        assertArrayEquals(new byte[]{7}, state.takeLateProbe(System.nanoTime()).data().serializedSections());
        publishLate(state, 1, new byte[]{8});
        assertNull(state.takeLateProbe(System.nanoTime()), "repeated callback cannot resurrect completion");
    }
    @Test void oldDiskResultCannotArmOrDiscardNewAttempt() {
        var state = lateState(1); long pos = PositionUtil.packPosition(1, 0);
        state.noteLateProbeDiskSubmission(pos, 20);
        var next = batch(1); state.offerIncomingBatch(next); long generation = state.offerGeneration();
        state.takeFreshIncomingBatchForProbe(); state.beginLateProbes(generation, new long[]{pos});
        state.republishHeldBatch(next, generation); publishLate(state, 1, new byte[]{7});
        state.discardRoutingLateProbe(pos); // older route completion cannot discard this new attempt
        state.takeIncomingBatchForRouting(); state.noteLateProbeDiskSubmission(pos, 21);
        state.markLateProbeDiskFallback(pos, 20); state.discardLateProbeDiskResult(pos, 20);
        assertNull(state.takeLateProbe(System.nanoTime()));
        state.markLateProbeDiskFallback(pos, 21);
        assertNotNull(state.takeLateProbe(System.nanoTime()), "old outcome must not delete new authority");
    }
    @Test void lateCorrectionWaitsForPendingAndQueuedOlderWork() {
        var state = lateState(1); publishLate(state, 1, new byte[]{7}); lateResult(state, 1, 20);
        assertTrue(state.tryAdmit(new PendingRequest(1, 0, SlotType.SYNC_ON_LOAD, -1)));
        assertNull(state.takeLateProbe(System.nanoTime()));
        state.removePendingByPosition(1, 0);
        state.addReadyPayload(new QueuedPayload<>(new Object(), 3, 20, PositionUtil.packPosition(1, 0)));
        assertNull(state.takeLateProbe(System.nanoTime()), "ready queue counts before pump queue snapshot updates");
    }
    @Test void dirtyEmptyOfferAndRetirementCannotResurrectLateBytes() {
        for (int mode = 0; mode < 3; mode++) {
            var state = lateState(1); long generation = state.offerGeneration();
            publishLate(state, 1, new byte[]{7}); lateResult(state, 1, 20);
            if (mode == 0) state.clearDiskReadDone(new long[]{PositionUtil.packPosition(1, 0)});
            if (mode == 1) state.offerIncomingBatch(batch());
            if (mode == 2) { state.registration().retire(); state.discardProbeHandoff(); }
            state.publishLateProbe(generation, new LoadedColumnData(1, 0, new byte[]{8}, 1));
            assertNull(state.takeLateProbe(System.nanoTime()));
        }
    }
    @Test void lateBytesExpireWithoutWaitingForSendQueueCapacity() {
        var state = lateState(1); publishLate(state, 1, new byte[]{7}); lateResult(state, 1, 20);
        state.expireLateProbes(System.nanoTime() + AbstractPlayerRequestState.LATE_PROBE_LIFETIME_NANOS);
        publishLate(state, 1, new byte[]{8});
        assertNull(state.takeLateProbe(System.nanoTime()));
    }
    @Test void lateAttemptCountAndRawBytesAreBounded() {
        int[] xs = java.util.stream.IntStream.range(0, AbstractPlayerRequestState.MAX_LATE_PROBES + 1).toArray();
        var state = lateState(xs);
        int beyond = AbstractPlayerRequestState.MAX_LATE_PROBES;
        publishLate(state, beyond, new byte[]{7}); lateResult(state, beyond, 1);
        assertNull(state.takeLateProbe(System.nanoTime()), "overflow attempt was never retained");
        publishLate(state, 0, new byte[1_100_000]); lateResult(state, 0, 2);
        publishLate(state, 1, new byte[1_100_000]); lateResult(state, 1, 3);
        assertEquals(1_100_000, state.takeLateProbe(System.nanoTime()).data().serializedSections().length);
        assertNull(state.takeLateProbe(System.nanoTime()), "second raw body exceeded shared byte budget");
    }
    @Test void movedOutOfRangeLateResultIsDiscarded() {
        var state = lateState(1); publishLate(state, 1, new byte[]{7}); lateResult(state, 1, 20);
        state.updatePlayerChunk(4096, 4096);
        assertNull(state.takeLateProbe(System.nanoTime()));
    }
}
