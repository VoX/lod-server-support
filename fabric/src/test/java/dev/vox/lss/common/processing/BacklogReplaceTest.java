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
        assertSame(held, s.takeIncomingBatch());
        s.offerIncomingBatch(batch(9)); // newer batch arrives during the Folia hold
        assertFalse(s.republishHeldBatch(held), "a newer batch supersedes the held one");
        assertEquals(2, s.drainPendingSuperseded());
        assertEquals(9, s.takeIncomingBatch().requests()[0].cx());
        // and the CAS succeeds when the mailbox is empty
        assertTrue(s.republishHeldBatch(held));
        assertSame(held, s.peekIncomingBatch());
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
        // The main-thread probe reads peekWantSet(), never the mailbox: takeIncomingBatch()
        // nulls the mailbox within ~50ms while batches arrive at 1Hz, so a mailbox-peeking
        // probe would see null on ~19 of every 20 ticks. The published want-set survives the
        // take and lives exactly as long as there is backlog left to route.
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
    void emptyClearBatchPublishesNothingToProbe() {
        var s = new TestState();
        s.replaceBacklogWith(batch(1, 2));
        assertNotNull(s.peekWantSet());
        s.replaceBacklogWith(batch());
        assertNull(s.peekWantSet(), "the backpressure clear leaves nothing to probe");
    }
}
