package dev.vox.lss.common.processing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Mailbox latest-wins + backlog replace + superseded accounting (protocol v17 core). */
class BacklogReplaceTest {

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState() { super(UUID.randomUUID(), 4, 2); }
        @Override public String getPlayerName() { return "test"; }
    }

    private static IncomingBatch batch(int... cxs) {
        var reqs = new IncomingRequest[cxs.length];
        for (int i = 0; i < cxs.length; i++) reqs[i] = new IncomingRequest(cxs[i], 0, -1L);
        return new IncomingBatch(reqs);
    }

    @Test
    void offerCountsReceivedAndOverwriteCountsSuperseded() {
        var s = new TestState();
        s.offerIncomingBatch(batch(1, 2, 3));
        s.offerIncomingBatch(batch(4));
        assertEquals(4, s.getTotalRequestsReceived(), "every entry of every batch is received");
        assertEquals(3, s.drainPendingSuperseded(), "the overwritten batch is superseded wholesale");
        assertEquals(0, s.drainPendingSuperseded(), "drain is destructive");
        assertEquals(1, s.takeIncomingBatch().size(), "latest batch wins");
        assertNull(s.takeIncomingBatch());
    }

    @Test
    void replaceBacklogDropsOldEntriesAndReportsCount() {
        var s = new TestState();
        assertEquals(0, s.replaceBacklogWith(batch(1, 2)));
        assertEquals(2, s.replaceBacklogWith(batch(7)), "old backlog superseded by the replace");
        assertEquals(7, s.pollBacklog().cx());
        assertNull(s.pollBacklog());
        assertEquals(0, s.getBacklogSize());
    }

    @Test
    void emptyBatchIsTheExplicitClear() {
        var s = new TestState();
        s.replaceBacklogWith(batch(1, 2, 3));
        assertEquals(3, s.replaceBacklogWith(batch()), "empty batch clears the backlog");
        assertEquals(0, s.getBacklogSize());
    }

    @Test
    void restoreBacklogPreservesOriginalOrderAheadOfRemainder() {
        var s = new TestState();
        s.replaceBacklogWith(batch(1, 2, 3, 4));
        var a = s.pollBacklog(); // 1
        var b = s.pollBacklog(); // 2
        s.restoreBacklog(List.of(a, b));
        assertEquals(1, s.pollBacklog().cx());
        assertEquals(2, s.pollBacklog().cx());
        assertEquals(3, s.pollBacklog().cx());
    }

    @Test
    void republishHeldBatchLosesToANewerArrival() {
        var s = new TestState();
        var held = batch(1, 2);
        s.offerIncomingBatch(held);
        long gen = s.offerGeneration(); // the pump records this BEFORE its take
        assertSame(held, s.takeIncomingBatch());
        s.offerIncomingBatch(batch(9)); // newer batch arrives during the Folia hold
        assertFalse(s.republishHeldBatch(held, gen), "a newer batch supersedes the held one");
        assertEquals(2, s.drainPendingSuperseded());
        assertEquals(9, s.takeIncomingBatch().requests()[0].cx());
        // and the republish succeeds when the mailbox is empty AND the generation is quiet
        assertTrue(s.republishHeldBatch(held, s.offerGeneration()));
        assertSame(held, s.peekIncomingBatch());
    }

    @Test
    void republishHeldBatchDetectsAPassThroughDuringTheHold() {
        var s = new TestState();
        var held = batch(1, 2);
        s.offerIncomingBatch(held);
        long gen = s.offerGeneration();
        assertSame(held, s.takeIncomingBatch());

        // During the hold a newer batch passes THROUGH the mailbox: offered AND taken by
        // the processing thread (which replaces the backlog with it). The mailbox is empty
        // again — the plain CAS(null, held) would succeed and resurrect the stale held
        // batch OVER the newer declaration's backlog. The offer-generation guard is what
        // catches this shape.
        var newer = batch(9);
        s.offerIncomingBatch(newer);
        assertSame(newer, s.takeIncomingBatch());
        assertNull(s.peekIncomingBatch(), "premise: the pass-through left the mailbox empty");

        assertFalse(s.republishHeldBatch(held, gen),
                "a batch that passed through the mailbox during the hold must kill the "
                        + "held batch — latest-wins, never resurrect");
        assertNull(s.peekIncomingBatch(), "the stale batch is not put back");
        assertEquals(2, s.drainPendingSuperseded(),
                "the killed held batch is counted superseded (law A1)");
    }

    @Test
    void rangeFilteredAccumulatesAndDrains() {
        var s = new TestState();
        s.recordRangeFiltered(3);
        s.recordRangeFiltered(0);
        assertEquals(3, s.drainPendingRangeFiltered());
        assertEquals(0, s.drainPendingRangeFiltered());
    }

    // ---- publishedWantSet: the probe source (plan Task 3 amendment / rough edge #2) ----

    @Test
    void applyPublishesTheWantSetAndDrainingTheBacklogClearsIt() {
        // peekWantSet() is the probe's FALLBACK source, behind the mailbox: takeIncomingBatch()
        // nulls the mailbox within ~50ms while batches arrive at 1Hz, so a probe reading the
        // mailbox alone would see null on ~19 of every 20 ticks. The published want-set survives
        // the take and lives exactly as long as there is backlog left to route. (The arrival-tick
        // mailbox arm is pinned at the service layer — the only layer that can break it — by
        // Fabric's probeServesLoadedChunkFromMemoryWithoutSeedingDirtyFilter gametest and Paper's
        // freshMailboxBatchIsProbedOnItsArrivalTickWithNothingPublished.)
        var s = new TestState();
        var b = batch(1, 2);
        s.offerIncomingBatch(b);
        assertNull(s.peekWantSet(), "nothing is published until the batch is applied");
        var taken = s.takeIncomingBatch();
        s.replaceBacklogWith(taken);
        assertNull(s.peekIncomingBatch(), "the mailbox is drained...");
        assertSame(b, s.peekWantSet(), "...but the probe source survives the take");
        s.pollBacklog();
        assertSame(b, s.peekWantSet(), "still routable work left → still probed");
        s.pollBacklog();
        assertNull(s.peekWantSet(), "backlog drained: a converged player costs zero probes");
    }

    @Test
    void retainedEntriesKeepTheWantSetPublishedAcrossTheDrainAndRestore() {
        // The steady-state router pass: SLOT_FULL retains-and-CONTINUES, so a pass polls the
        // ENTIRE backlog (emptying it) and only then restores the retained entries. pollBacklog
        // publishes null the moment the deque empties, so without a republish here the probe
        // source would be null on every tick while entries are still owed — collapsing in-memory
        // probe coverage to ~zero in exactly the un-saturated case rough edge #2 budgets for.
        // Invariant: the want-set is published exactly while there is backlog left to route.
        var s = new TestState();
        var b = batch(1, 2);
        s.replaceBacklogWith(b);
        var first = s.pollBacklog();  // admitted
        var second = s.pollBacklog(); // SLOT_FULL → retained; backlog now empty
        assertNull(s.peekWantSet(), "drained mid-pass: transiently unpublished");
        s.restoreBacklog(List.of(second));
        assertSame(b, s.peekWantSet(), "work is still owed → the probe source must be live again");
        assertEquals(1, first.cx());

        // A later cycle with no new batch still republishes the SAME applied want-set.
        assertEquals(2, s.pollBacklog().cx());
        assertNull(s.peekWantSet(), "drained again");
        s.restoreBacklog(List.of(second));
        assertSame(b, s.peekWantSet(), "the applied want-set survives cycles without a new batch");
    }

    @Test
    void emptyClearBatchPublishesNothingToProbe() {
        var s = new TestState();
        s.replaceBacklogWith(batch(1, 2));
        assertNotNull(s.peekWantSet());
        s.replaceBacklogWith(batch());
        assertNull(s.peekWantSet(), "the backpressure clear leaves nothing to probe");
    }
}
