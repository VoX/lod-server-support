package dev.vox.lss.common.farplayers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The client tracker (E1): the epoch armor (unknown-epoch updates AND stale incremental
 * rosters drop wholesale), full-roster replacement, incremental add/remove, unknown-index
 * skip, and the clear() contract the R-3 reset path relies on.
 */
class FarPlayerClientTrackerTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    private static FarPlayerWire.UpdateEntry entry(int index, int qx) {
        return new FarPlayerWire.UpdateEntry(index, qx, 1024, 0,
                (byte) 0, (byte) 0, (byte) 0, (byte) 0, (short) 0, (short) 0, (short) 0,
                null, null, null, null);
    }

    private static FarPlayerWire.Roster fullRoster(int epoch, FarPlayerWire.RosterEntry... entries) {
        return new FarPlayerWire.Roster(epoch, true, List.of(entries), new int[0]);
    }

    @Test
    void updatesBindThroughTheRosterAndUnknownEpochDropsWholesale() {
        var t = new FarPlayerClientTracker();
        t.onRoster(fullRoster(1, new FarPlayerWire.RosterEntry(0, A, "Alice")));
        t.onUpdates(new FarPlayerWire.Updates(1, "minecraft:overworld", 10,
                List.of(entry(0, 160))), 1000);
        assertEquals(1, t.trackedCount());
        assertEquals("Alice", t.snapshot().get(A).name());
        assertEquals(160, t.snapshot().get(A).latest().quantX());
        assertEquals(10, t.snapshot().get(A).cadenceTicks());

        // A stale updates frame from a pre-rebuild epoch must NEVER bind old indices
        // to new identities — dropped wholesale.
        t.onRoster(fullRoster(2, new FarPlayerWire.RosterEntry(0, B, "Bob")));
        t.onUpdates(new FarPlayerWire.Updates(1, "minecraft:overworld", 10,
                List.of(entry(0, 999))), 2000);
        assertEquals(1, t.droppedWrongEpoch());
        assertNull(t.snapshot().get(B), "the stale frame must not have bound to Bob");

        t.onUpdates(new FarPlayerWire.Updates(2, "minecraft:overworld", 10,
                List.of(entry(0, 320))), 3000);
        assertEquals(320, t.snapshot().get(B).latest().quantX());
    }

    @Test
    void fullRosterReplacesAndIncrementalFramesDiffWithinTheEpoch() {
        var t = new FarPlayerClientTracker();
        t.onRoster(fullRoster(1, new FarPlayerWire.RosterEntry(0, A, "Alice")));
        // Incremental add at the current epoch.
        t.onRoster(new FarPlayerWire.Roster(1, false,
                List.of(new FarPlayerWire.RosterEntry(1, B, "Bob")), new int[0]));
        t.onUpdates(new FarPlayerWire.Updates(1, "d", 10,
                List.of(entry(0, 1), entry(1, 2))), 1000);
        assertEquals(2, t.trackedCount());

        // Incremental removal: identity + tracked state die together.
        t.onRoster(new FarPlayerWire.Roster(1, false, List.of(), new int[]{0}));
        assertEquals(1, t.trackedCount());
        assertNull(t.snapshot().get(A));

        // A STALE incremental frame (old epoch) is dropped, not applied.
        t.onRoster(fullRoster(2, new FarPlayerWire.RosterEntry(0, A, "Alice")));
        t.onUpdates(new FarPlayerWire.Updates(2, "d", 10, List.of(entry(0, 9))), 1500);
        assertEquals(1, t.trackedCount());
        t.onRoster(new FarPlayerWire.Roster(1, false, List.of(), new int[]{0}));
        assertEquals(1, t.trackedCount(), "the stale removal must not have applied");

        // An update addressing an index the roster no longer carries is skipped, not fatal.
        t.onUpdates(new FarPlayerWire.Updates(2, "d", 10, List.of(entry(7, 5))), 2000);
        assertEquals(1, t.trackedCount(), "an unknown index is skipped, the frame survives");
    }

    @Test
    void clearForgetsEverythingIncludingTheSeenEpoch() {
        var t = new FarPlayerClientTracker();
        t.onRoster(fullRoster(3, new FarPlayerWire.RosterEntry(0, A, "Alice")));
        t.onUpdates(new FarPlayerWire.Updates(3, "d", 10, List.of(entry(0, 1))), 1000);
        t.clear();
        assertEquals(0, t.trackedCount());
        assertEquals(-1, t.currentEpoch(), "the seen epoch dies too (the R-3 reset contract "
                + "— the re-sent prefs trigger a fresh full roster that repopulates)");
        t.onUpdates(new FarPlayerWire.Updates(3, "d", 10, List.of(entry(0, 2))), 2000);
        assertEquals(0, t.trackedCount(), "post-clear updates drop until a roster arrives");
    }

    @Test
    void identityAccumulationPastTheCapClearsAndSelfHeals() {
        var t = new FarPlayerClientTracker();
        t.onRoster(fullRoster(1, new FarPlayerWire.RosterEntry(0, A, "Alice")));
        // Incremental adds accumulate past the cap (a buggy/hostile server that never
        // removes): the tracker must clear rather than grow without bound.
        int idx = 1;
        while (t.currentEpoch() != -1) {
            var adds = new java.util.ArrayList<FarPlayerWire.RosterEntry>();
            for (int i = 0; i < 1000; i++, idx++) {
                adds.add(new FarPlayerWire.RosterEntry(idx, new UUID(5, idx), "P" + idx));
            }
            t.onRoster(new FarPlayerWire.Roster(1, false, adds, new int[0]));
            assertTrue(idx < FarPlayerClientTracker.MAX_TRACKED_IDENTITIES + 2000,
                    "the cap must have fired by now");
        }
        assertEquals(1, t.identityCapResets());
        assertEquals(0, t.trackedCount(), "past the cap: clear, wait for a full roster");
        // Self-heal: the next FULL roster repopulates normally.
        t.onRoster(fullRoster(9, new FarPlayerWire.RosterEntry(0, A, "Alice")));
        t.onUpdates(new FarPlayerWire.Updates(9, "d", 10, List.of(entry(0, 1))), 3000);
        assertEquals(1, t.trackedCount());
    }

    @Test void movingAnIdentityToAnotherIndexMakesOldRemovalHarmless() {
        var t = new FarPlayerClientTracker();
        t.onRoster(fullRoster(1, new FarPlayerWire.RosterEntry(0, A, "Alice")));
        t.onUpdates(new FarPlayerWire.Updates(1, "d", 10, List.of(entry(0, 5))), 1000);
        t.onRoster(new FarPlayerWire.Roster(1, false,
                List.of(new FarPlayerWire.RosterEntry(1, A, "Alice")), new int[]{0}));
        assertEquals(1, t.trackedCount());
        t.onUpdates(new FarPlayerWire.Updates(1, "d", 10, List.of(entry(0, 99))), 2000);
        assertEquals(5, t.snapshot().get(A).latest().quantX(), "old index is no longer bound");
        t.onUpdates(new FarPlayerWire.Updates(1, "d", 10, List.of(entry(1, 7))), 3000);
        assertEquals(7, t.snapshot().get(A).latest().quantX());
        t.onRoster(new FarPlayerWire.Roster(1, false, List.of(), new int[]{1}));
        assertEquals(0, t.trackedCount());
    }
}
