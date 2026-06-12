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

        void enqueue(IncomingRequest r) { enqueueIncomingRequest(r); }
    }

    // Mirrors AbstractPlayerRequestState.MAX_INCOMING_QUEUE — the OOM-DoS bound on a
    // flooding client. A deliberate bound change must update this test.
    private static final int MAX_INCOMING_QUEUE = 16384;

    private TestState state;

    @BeforeEach
    void setUp() {
        state = new TestState(2, 1);
    }

    private static PendingRequest sync(int cx, int cz) {
        return new PendingRequest(cx, cz, RequestType.SYNC, SlotType.SYNC_ON_LOAD);
    }

    private static PendingRequest gen(int cx, int cz) {
        return new PendingRequest(cx, cz, RequestType.GENERATION, SlotType.GENERATION);
    }

    @Test
    void admitsUpToCapThenBounces() {
        assertTrue(state.tryAdmit(sync(0, 0)));
        assertTrue(state.tryAdmit(sync(0, 1)));
        assertFalse(state.tryAdmit(sync(0, 2)), "third sync admission must bounce at cap 2");
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

    // ---- Incoming-queue flood bound ----

    @Test
    void incomingFloodDropsAtTheBoundAndRecoversAfterDraining() {
        for (int i = 0; i < MAX_INCOMING_QUEUE + 100; i++) {
            state.enqueue(new IncomingRequest(i, 0, -1L));
        }
        assertEquals(MAX_INCOMING_QUEUE, state.getTotalRequestsReceived(),
                "accepted requests stop exactly at the bound");
        assertEquals(100, state.getTotalIncomingDropped(), "overflow must be dropped, not queued");

        // The bound holds structurally: exactly MAX entries are pollable
        int drained = 0;
        while (state.pollIncomingRequest() != null) drained++;
        assertEquals(MAX_INCOMING_QUEUE, drained);

        // Recovery: draining must reopen the queue (a wedged-closed queue kills LOD for good)
        state.enqueue(new IncomingRequest(99999, 0, -1L));
        assertEquals(MAX_INCOMING_QUEUE + 1, state.getTotalRequestsReceived());
        assertEquals(100, state.getTotalIncomingDropped(), "post-recovery enqueue must not drop");
        assertNotNull(state.pollIncomingRequest());
    }

    @Test
    void incomingQueueReopensExactlyByDrainedCapacity() {
        // Catches counter desync between poll and enqueue (either direction wedges the gate)
        for (int i = 0; i < MAX_INCOMING_QUEUE; i++) {
            state.enqueue(new IncomingRequest(i, 1, -1L));
        }
        for (int i = 0; i < 50; i++) {
            assertNotNull(state.pollIncomingRequest());
        }
        for (int i = 0; i < 60; i++) {
            state.enqueue(new IncomingRequest(i, 2, -1L));
        }
        assertEquals(MAX_INCOMING_QUEUE + 50, state.getTotalRequestsReceived(),
                "exactly the drained capacity reopens");
        assertEquals(10, state.getTotalIncomingDropped(), "the rest must bounce off the bound");
    }
}
