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
}
