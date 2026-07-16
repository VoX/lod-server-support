package dev.vox.lss.common.processing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the derived-slot admission model: a request occupies its slot exactly while its
 * pending entry exists, so admission counts can never drift from the pending map
 * (the structural guarantee that replaced the manually balanced ConcurrencyLimiter).
 */
class SlotAdmissionTest {

    /** Minimal concrete state — the queued payload type is irrelevant here. */
    private static final class TestState extends AbstractPlayerRequestState<TestState.NoPayload> {
        record NoPayload() implements Comparable<NoPayload> {
            @Override public int compareTo(NoPayload o) { return 0; }
        }
        TestState(int syncCap, int genCap) {
            super(UUID.randomUUID(), syncCap, genCap);
        }

        @Override
        public String getPlayerName() { return "test"; }

        /** Seeds one want-set batch carrying a single request (the pre-v17 per-request
         *  enqueue has no equivalent — each declaration REPLACES the backlog). Every call
         *  site here is separated from the next by a routing cycle, so nothing is superseded. */
        void enqueue(IncomingRequest r) { offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{r})); }
    }

    private TestState state;

    @BeforeEach
    void setUp() {
        state = new TestState(2, 1);
    }

    private static PendingRequest sync(int cx, int cz) {
        return new PendingRequest(cx, cz, RequestType.SYNC, SlotType.SYNC_ON_LOAD, false);
    }

    private static PendingRequest gen(int cx, int cz) {
        return new PendingRequest(cx, cz, RequestType.GENERATION, SlotType.GENERATION, false);
    }

    @Test
    void admitsUpToCapThenRejectsAdmission() {
        assertTrue(state.tryAdmit(sync(0, 0)));
        assertTrue(state.tryAdmit(sync(0, 1)));
        // Under v17 a refused admission is not an answer to the client: the router RETAINS the
        // entry in the backlog (SLOT_FULL) and re-tries it on a later cycle. The cap arithmetic
        // is unchanged — it now means "dequeue at most N concurrently", not "reject above N".
        assertFalse(state.tryAdmit(sync(0, 2)), "third sync admission must be refused at cap 2");
        assertEquals(2, state.getHeldSyncSlots());
    }

    @Test
    void slotsAreIndependent() {
        assertTrue(state.tryAdmit(sync(0, 0)));
        assertTrue(state.tryAdmit(sync(0, 1)));
        assertTrue(state.tryAdmit(gen(1, 0)), "gen slot is independent of full sync slot");
        assertFalse(state.tryAdmit(gen(1, 1)));
        assertEquals(2, state.getHeldSyncSlots());
        assertEquals(1, state.getHeldGenSlots());
    }

    @Test
    void removeFreesTheSlot() {
        assertTrue(state.tryAdmit(sync(0, 0)));
        assertTrue(state.tryAdmit(sync(0, 1)));
        assertNotNull(state.removePendingByPosition(0, 0));
        assertEquals(1, state.getHeldSyncSlots());
        assertTrue(state.tryAdmit(sync(0, 2)), "freed slot is admittable again");
    }

    @Test
    void removingUntrackedPositionDoesNotUnderflow() {
        assertNull(state.removePendingByPosition(9, 9));
        assertEquals(0, state.getHeldSyncSlots());
        assertEquals(0, state.getHeldGenSlots());
        assertTrue(state.tryAdmit(sync(0, 0)));
        assertNull(state.removePendingByPosition(5, 5));
        assertEquals(1, state.getHeldSyncSlots(), "stray removal must not free a held slot");
    }

    @Test
    void samePositionReadmissionSwapsSlotInsteadOfLeaking() {
        // Production removes the pending entry before re-admitting (OffThreadProcessor
        // .deliverDiskResult -> handleDiskNotFound), so this replace path is defensive-only;
        // pin its accounting anyway so a same-position put can never leak a slot.
        assertTrue(state.tryAdmit(sync(3, 3)));
        assertTrue(state.tryAdmit(gen(3, 3)), "same-position admit replaces the entry");
        assertEquals(0, state.getHeldSyncSlots(), "replaced entry's slot must be freed");
        assertEquals(1, state.getHeldGenSlots());
        assertNotNull(state.removePendingByPosition(3, 3));
        assertEquals(0, state.getHeldGenSlots());
    }

    @Test
    void samePositionReplacementIsRejectedAtCapAndTheExistingEntrySurvives() {
        // The cap check runs BEFORE the replacement swap: a same-position re-admission
        // whose target slot type is full must be rejected without mutating the pending
        // map. A swap-then-check regression would either free the existing entry's slot
        // for a bounced request or admit over cap — both visible here.
        assertTrue(state.tryAdmit(gen(9, 9)), "fill the gen cap (1)");
        assertTrue(state.tryAdmit(sync(3, 3)));

        assertFalse(state.tryAdmit(gen(3, 3)), "replacement must bounce while gen is at cap");
        assertTrue(state.hasPendingRequest(3, 3), "rejected replacement must not evict the existing entry");
        assertEquals(1, state.getHeldSyncSlots(), "the surviving entry still holds its sync slot");
        assertEquals(1, state.getHeldGenSlots());

        var survivor = state.removePendingByPosition(3, 3);
        assertNotNull(survivor);
        assertEquals(SlotType.SYNC_ON_LOAD, survivor.heldSlot(),
                "the original SYNC entry survives, not the bounced GEN replacement");
        assertEquals(0, state.getHeldSyncSlots());
        assertEquals(1, state.getHeldGenSlots());
    }

    @Test
    void diskNotFoundEscalationBounceLeavesNoPendingEntry() {
        // The real escalation sequence (OffThreadProcessor.deliverDiskResult ->
        // handleDiskNotFound): the SYNC slot is freed on delivery, then GENERATION
        // re-admission either succeeds or bounces leaving no pending entry.
        assertTrue(state.tryAdmit(gen(7, 7)), "fill the gen cap (1)");
        assertTrue(state.tryAdmit(sync(3, 3)), "disk-first GENERATION request holds a SYNC slot");
        assertNotNull(state.removePendingByPosition(3, 3), "disk result delivery frees the slot");
        assertFalse(state.tryAdmit(gen(3, 3)), "gen-cap-full escalation must bounce");
        assertFalse(state.hasPendingRequest(3, 3), "bounced escalation must not leave a pending entry");
        assertEquals(0, state.getHeldSyncSlots());
        assertEquals(1, state.getHeldGenSlots());
    }

    @Test
    void hasPendingTracksAdmission() {
        assertFalse(state.hasPendingRequest(0, 0));
        assertTrue(state.tryAdmit(sync(0, 0)));
        assertTrue(state.hasPendingRequest(0, 0));
        state.removePendingByPosition(0, 0);
        assertFalse(state.hasPendingRequest(0, 0));
    }

    // The pre-v17 incoming-queue flood bound (incomingFloodDropsAtTheBoundAndRecoversAfterDraining,
    // incomingQueueReopensExactlyByDrainedCapacity) is deleted: ingress is now bounded
    // STRUCTURALLY at one batch of <= MAX_BATCH_CHUNK_REQUESTS, so there is no counter to
    // desync and no gate to wedge closed. The replacement coverage for a flooding client is
    // BacklogReplaceTest.offerCountsReceivedAndOverwriteCountsSuperseded.
}
