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
    private static AbstractPlayerRequestState.LateProbeCompletion takeAndConsume(State state) {
        var ready = state.takeLateProbe(System.nanoTime());
        if (ready == null) return null;
        assertTrue(state.consumeLateProbe(ready, System.nanoTime()));
        return ready;
    }
    @Test void lateCorrectionIsOneShotAndRequiresOriginalDiskResult() {
        var state = lateState(1); state.noteLateProbeDiskSubmission(PositionUtil.packPosition(1, 0), 20); publishLate(state, 1, new byte[]{7});
        assertNull(takeAndConsume(state), "callback alone cannot create unsolicited work");
        lateResult(state, 1, 20);
        assertArrayEquals(new byte[]{7}, takeAndConsume(state).data().serializedSections());
        publishLate(state, 1, new byte[]{8});
        assertNull(takeAndConsume(state), "repeated callback cannot resurrect completion");
    }
    @Test void oldDiskResultCannotArmOrDiscardNewAttempt() {
        var state = lateState(1); long pos = PositionUtil.packPosition(1, 0);
        state.noteLateProbeDiskSubmission(pos, 20);
        var next = batch(1); state.offerIncomingBatch(next); long generation = state.offerGeneration();
        state.takeFreshIncomingBatchForProbe();
        state.republishHeldBatch(next, generation);
        state.discardRoutingLateProbe(pos); // older route completion cannot discard this new attempt
        state.takeIncomingBatchForRouting(); state.noteLateProbeDiskSubmission(pos, 21); publishLate(state, 1, new byte[]{7});
        state.markLateProbeDiskFallback(pos, 20); state.discardLateProbeDiskResult(pos, 20);
        assertNull(takeAndConsume(state));
        state.markLateProbeDiskFallback(pos, 21);
        assertNotNull(takeAndConsume(state), "old outcome must not delete new authority");
    }
    @Test void lateCorrectionWaitsForPendingAndQueuedOlderWork() {
        var state = lateState(1); state.noteLateProbeDiskSubmission(PositionUtil.packPosition(1, 0), 20); publishLate(state, 1, new byte[]{7}); lateResult(state, 1, 20);
        assertTrue(state.tryAdmit(new PendingRequest(1, 0, SlotType.SYNC_ON_LOAD, -1)));
        assertNull(takeAndConsume(state));
        state.removePendingByPosition(1, 0);
        state.addReadyPayload(new QueuedPayload<>(new Object(), 3, 20, PositionUtil.packPosition(1, 0)));
        assertNull(takeAndConsume(state), "ready queue counts before pump queue snapshot updates");
    }
    @Test void dirtyAndRetirementCannotResurrectLateBytes() {
        for (int mode = 0; mode < 2; mode++) {
            var state = lateState(1); state.noteLateProbeDiskSubmission(PositionUtil.packPosition(1, 0), 20); long generation = state.offerGeneration();
            publishLate(state, 1, new byte[]{7}); lateResult(state, 1, 20);
            if (mode == 0) state.clearDiskReadDone(new long[]{PositionUtil.packPosition(1, 0)});
            if (mode == 1) { state.registration().retire(); state.discardProbeHandoff(); }
            state.publishLateProbe(generation, new LoadedColumnData(1, 0, new byte[]{8}, 1));
            assertNull(takeAndConsume(state));
        }
    }
    @Test void lateBytesExpireWithoutWaitingForSendQueueCapacity() {
        var state = lateState(1); state.noteLateProbeDiskSubmission(PositionUtil.packPosition(1, 0), 20); publishLate(state, 1, new byte[]{7}); lateResult(state, 1, 20);
        state.expireLateProbes(System.nanoTime() + AbstractPlayerRequestState.LATE_PROBE_LIFETIME_NANOS);
        publishLate(state, 1, new byte[]{8});
        assertNull(takeAndConsume(state));
    }
    @Test void lateAttemptCountAndRawBytesAreBounded() {
        int[] xs = java.util.stream.IntStream.range(0, AbstractPlayerRequestState.MAX_LATE_PROBES + 1).toArray();
        var state = lateState(xs);
        for (int x : xs) state.noteLateProbeDiskSubmission(PositionUtil.packPosition(x, 0), x + 2L);
        int beyond = AbstractPlayerRequestState.MAX_LATE_PROBES;
        publishLate(state, beyond, new byte[]{7}); lateResult(state, beyond, 1);
        assertNull(takeAndConsume(state), "overflow attempt was never retained");
        publishLate(state, 0, new byte[1_100_000]); lateResult(state, 0, 2);
        publishLate(state, 1, new byte[1_100_000]); lateResult(state, 1, 3);
        assertEquals(1_100_000, takeAndConsume(state).data().serializedSections().length);
        assertNull(takeAndConsume(state), "second raw body exceeded shared byte budget");
    }
    @Test void movedOutOfRangeLateResultIsDiscarded() {
        var state = lateState(1); state.noteLateProbeDiskSubmission(PositionUtil.packPosition(1, 0), 20); publishLate(state, 1, new byte[]{7}); lateResult(state, 1, 20);
        state.updatePlayerChunk(4096, 4096);
        assertNull(takeAndConsume(state));
    }

    @Test void admittedLateOpportunityIsOneShotAndNullCompletionRetiresIt() {
        var state = lateState(1); long pos = PositionUtil.packPosition(1, 0);
        assertEquals(0, state.claimLateProbes(512).length, "declaration is not a disk admission");
        state.noteLateProbeDiskSubmission(pos, 20);
        state.addReadyPayload(new QueuedPayload<>(new Object(), 3, 20, pos));
        assertTrue(state.skipProbe(pos), "ordinary served-head filtering suppresses queued payload");
        var claims = state.claimLateProbes(512);
        assertEquals(1, claims.length, "exact admitted obligation bypasses only ordinary probe suppression");
        assertEquals(20, claims[0].diskOrder());
        assertEquals(0, state.claimLateProbes(512).length, "one owner opportunity per attempt");
        state.completeLateProbe(claims[0], null);
        state.publishLateProbe(state.offerGeneration(), new LoadedColumnData(1, 0, new byte[]{7}, 1));
        assertEquals(0, state.claimLateProbes(512).length, "null callback cannot recreate its slot");
    }
    @Test void retiredCallbackCannotConsumeReplacementAdmission() {
        var state = lateState(1); long pos = PositionUtil.packPosition(1, 0);
        state.noteLateProbeDiskSubmission(pos, 20); var old = state.claimLateProbes(512)[0];
        var batch = batch(1); state.offerIncomingBatch(batch); long generation = state.offerGeneration();
        state.takeFreshIncomingBatchForProbe(); state.republishHeldBatch(batch, generation);
        state.takeIncomingBatchForRouting(); state.noteLateProbeDiskSubmission(pos, 21);
        var current = state.claimLateProbes(512)[0];
        state.completeLateProbe(old, null);
        state.completeLateProbe(current, new LoadedColumnData(1, 0, new byte[]{7}, 1));
        state.markLateProbeDiskFallback(pos, 21);
        assertArrayEquals(new byte[]{7}, takeAndConsume(state).data().serializedSections());
    }

    @Test void nullClaimCompletionPreservesEarlierOriginalPublication() {
        var state = lateState(1); long pos = PositionUtil.packPosition(1, 0);
        state.noteLateProbeDiskSubmission(pos, 20); var claim = state.claimLateProbes(512)[0];
        publishLate(state, 1, new byte[]{7});
        state.completeLateProbe(claim, null); // original owner won before scheduled-null/refusal
        state.markLateProbeDiskFallback(pos, 20);
        assertArrayEquals(new byte[]{7}, takeAndConsume(state).data().serializedSections());
    }
    @Test void generationCancellationDiscardsAlreadyPublishedClaim() {
        var state = lateState(1); long pos = PositionUtil.packPosition(1, 0);
        state.noteLateProbeDiskSubmission(pos, 20); var claim = state.claimLateProbes(512)[0];
        publishLate(state, 1, new byte[]{7}); state.cancelLateProbe(claim);
        state.markLateProbeDiskFallback(pos, 20);
        assertNull(takeAndConsume(state));
    }
    @Test void overlapPreservesAdmissionIdentityWithoutRenewingOpportunityOrExpiry() {
        var state = lateState(1); long pos = PositionUtil.packPosition(1, 0);
        state.noteLateProbeDiskSubmission(pos, 20); var claim = state.claimLateProbes(512)[0];
        long expiry = System.nanoTime() + AbstractPlayerRequestState.LATE_PROBE_LIFETIME_NANOS;
        for (int i = 0; i < 4; i++) state.offerIncomingBatch(batch(1, 2));
        assertEquals(0, state.claimLateProbes(512).length, "overlap must not renew scheduled opportunity");
        state.completeLateProbe(claim, new LoadedColumnData(1, 0, new byte[]{7}, 1));
        state.markLateProbeDiskFallback(pos, 20);
        state.expireLateProbes(expiry);
        assertNull(takeAndConsume(state), "overlap must not extend original lifetime");
    }
    @Test void selectedCompletionSurvivesFrontierButNotActualInvalidation() {
        for (boolean overlaps : new boolean[]{true, false}) {
            var state = lateState(1); long pos = PositionUtil.packPosition(1, 0);
            state.noteLateProbeDiskSubmission(pos, 20); var claim = state.claimLateProbes(512)[0];
            state.completeLateProbe(claim, new LoadedColumnData(1, 0, new byte[]{7}, 1));
            state.markLateProbeDiskFallback(pos, 20);
            var selected = state.takeLateProbe(System.nanoTime());
            assertNotNull(selected); assertEquals(claim.generation(), selected.generation());
            if (!overlaps) state.clearDiskReadDone(new long[]{pos});
            state.offerIncomingBatch(batch(1, 2));
            assertEquals(overlaps, state.consumeLateProbe(selected, System.nanoTime()));
            assertFalse(state.consumeLateProbe(selected, System.nanoTime()), "one consume only");
        }
    }
    @Test void laterFrontiersIncludingEmptyPreserveOnlyTheExistingAdmission() {
        var state = lateState(1); long pos = PositionUtil.packPosition(1, 0);
        state.noteLateProbeDiskSubmission(pos, 20); var claim = state.claimLateProbes(512)[0];
        // Client halt and ordinary frontier replacement clear only server backlog;
        // LodRequestManager's explicit contract says already-admitted work completes.
        state.offerIncomingBatch(batch()); state.offerIncomingBatch(batch(2));
        assertEquals(0, state.claimLateProbes(512).length, "offers do not create/renew opportunities");
        state.completeLateProbe(claim, new LoadedColumnData(1, 0, new byte[]{7}, 1));
        state.markLateProbeDiskFallback(pos, 20);
        assertArrayEquals(new byte[]{7}, takeAndConsume(state).data().serializedSections());
        assertNull(takeAndConsume(state));
    }
    @Test void offerBeforeReservationCannotCancelAlreadyAdmittedOldRoutingAttempt() {
        for (boolean overlaps : new boolean[]{true, false}) {
            var state = lateState(1); long pos = PositionUtil.packPosition(1, 0);
            long routedGeneration = state.offerGeneration();
            state.offerIncomingBatch(overlaps ? batch(1, 2) : batch(2));
            state.takeFreshIncomingBatchForProbe(); // pending mailbox is no authority source
            state.noteLateProbeDiskSubmission(pos, 20);
            var claims = state.claimLateProbes(512);
            assertEquals(1, claims.length);
            assertEquals(routedGeneration, claims[0].generation());
            state.completeLateProbe(claims[0], new LoadedColumnData(1, 0, new byte[]{7}, 1));
            state.markLateProbeDiskFallback(pos, 20);
            var result = takeAndConsume(state);
            assertNotNull(result); assertEquals(routedGeneration, result.generation());
        }
    }

    @Test void selectedOldCompletionCannotConsumeReplacementDiskOrder() {
        var state = lateState(1); long pos = PositionUtil.packPosition(1, 0);
        state.noteLateProbeDiskSubmission(pos, 20); publishLate(state, 1, new byte[]{7});
        state.markLateProbeDiskFallback(pos, 20); var old = state.takeLateProbe(System.nanoTime());
        assertNotNull(old);
        state.noteLateProbeDiskSubmission(pos, 21); publishLate(state, 1, new byte[]{8});
        state.markLateProbeDiskFallback(pos, 21);
        assertFalse(state.consumeLateProbe(old, System.nanoTime()));
        assertArrayEquals(new byte[]{8}, takeAndConsume(state).data().serializedSections());
    }

}
