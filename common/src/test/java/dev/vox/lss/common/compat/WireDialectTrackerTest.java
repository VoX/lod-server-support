package dev.vox.lss.common.compat;

import dev.vox.lss.common.HandshakeGate.WireDialect;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code V18CompatTracker} lifecycle pins, generalized to the single-map dialect
 * registry (XVER §4.3 / §9): pre-register marking semantics, cross-dialect sheds (now
 * the overwrite), the non-register shed, disconnect drops, untracked-defaults-CURRENT,
 * and the {@code Dialects:} diag line. Platform-side lifecycle (Paper quit-race
 * mailbox-Remove drop, dimension-change survival) stays pinned where it always was —
 * the service twins and gametests.
 */
class WireDialectTrackerTest {

    private final WireDialectTracker tracker = new WireDialectTracker();
    private final UUID uuid = UUID.randomUUID();

    @Test
    void handshakeMarksAndDisconnectDrops() {
        assertEquals(WireDialect.CURRENT, tracker.dialectOf(uuid), "untracked defaults CURRENT");
        assertFalse(tracker.isCurrent(uuid),
                "untracked is CURRENT for egress, not a marked current session");
        tracker.onHandshake(uuid, WireDialect.V18);
        assertTrue(tracker.isV18(uuid));
        assertFalse(tracker.isCurrent(uuid));
        assertEquals(WireDialect.V18, tracker.dialectOf(uuid));
        assertEquals(1, tracker.sessionCount(WireDialect.V18));
        tracker.onDisconnect(uuid);
        assertFalse(tracker.isV18(uuid));
        assertEquals(0, tracker.sessionCount(WireDialect.V18));
        assertEquals(WireDialect.CURRENT, tracker.dialectOf(uuid), "dropped falls back to CURRENT");
    }

    @Test
    void everyDialectMarksAndAnswersItsOwnPredicate() {
        tracker.onHandshake(uuid, WireDialect.V16);
        assertTrue(tracker.isV16(uuid));
        tracker.onHandshake(uuid, WireDialect.V19);
        assertTrue(tracker.isV19(uuid));
        assertFalse(tracker.isV16(uuid), "the overwrite IS the cross-dialect shed");
        tracker.onHandshake(uuid, WireDialect.CURRENT);
        assertFalse(tracker.isV19(uuid));
        assertTrue(tracker.isCurrent(uuid));
        assertEquals(WireDialect.CURRENT, tracker.dialectOf(uuid));
        assertEquals(1, tracker.sessionCount(WireDialect.CURRENT));
    }

    @Test
    void duplicateHandshakeKeepsMembershipAndIsNotRecounted() {
        tracker.onHandshake(uuid, WireDialect.V18);
        tracker.onHandshake(uuid, WireDialect.V18);
        assertTrue(tracker.isV18(uuid));
        assertEquals("Dialects: v20=0, v19=0, v18=1, v16=0, started=0/0/1/0",
                tracker.diagLine(), "a same-dialect re-handshake must not recount started");
    }

    @Test
    void crossDialectRehandshakeRecountsTheNewDialect() {
        tracker.onHandshake(uuid, WireDialect.V18);
        tracker.onHandshake(uuid, WireDialect.CURRENT);   // client updated mid-session
        tracker.onHandshake(uuid, WireDialect.V18);       // and downgraded back
        assertEquals("Dialects: v20=0, v19=0, v18=1, v16=0, started=1/0/2/0",
                tracker.diagLine());
    }

    @Test
    void nonRegisterHandshakeShedsOnlyCrossDialectMembership() {
        tracker.onHandshake(uuid, WireDialect.V18);
        // Same-dialect non-register handshake (e.g. consumer-less re-announce): keeps it.
        tracker.onNonRegisterHandshake(uuid, WireDialect.V18);
        assertTrue(tracker.isV18(uuid),
                "a same-dialect non-register handshake keeps the membership (old tracker semantics)");
        // Cross-dialect non-register handshake: sheds the stale membership.
        tracker.onNonRegisterHandshake(uuid, WireDialect.CURRENT);
        assertFalse(tracker.isV18(uuid),
                "a cross-dialect non-register handshake must shed — the surviving "
                        + "registration would keep stripping frames for a re-armed decoder");
        // On an untracked player it is a no-op, never a mark.
        var other = UUID.randomUUID();
        tracker.onNonRegisterHandshake(other, WireDialect.V16);
        assertEquals(WireDialect.CURRENT, tracker.dialectOf(other));
        assertEquals(0, tracker.sessionCount(WireDialect.V16));
    }

    @Test
    void disconnectIsIdempotentAndPerPlayer() {
        var other = UUID.randomUUID();
        tracker.onHandshake(uuid, WireDialect.V19);
        tracker.onHandshake(other, WireDialect.V19);
        tracker.onDisconnect(uuid);
        tracker.onDisconnect(uuid);
        assertFalse(tracker.isV19(uuid));
        assertTrue(tracker.isV19(other), "disconnect must be per-player");
        assertEquals(1, tracker.sessionCount(WireDialect.V19));
    }

    @Test
    void diagLineRendersUnconditionallyAndStartedPersistsAfterDisconnect() {
        assertEquals("Dialects: v20=0, v19=0, v18=0, v16=0, started=0/0/0/0",
                tracker.diagLine(), "the line renders before any session — the v20 count"
                        + " IS the live LOD-session count");
        tracker.onHandshake(uuid, WireDialect.V16);
        tracker.onDisconnect(uuid);
        assertEquals("Dialects: v20=0, v19=0, v18=0, v16=0, started=0/0/0/1",
                tracker.diagLine(), "history survives the last legacy client leaving");
    }

    @Test
    void startedCountsDistinctSessionsNotPlayers() {
        var other = UUID.randomUUID();
        tracker.onHandshake(uuid, WireDialect.V18);
        tracker.onDisconnect(uuid);
        tracker.onHandshake(uuid, WireDialect.V18);   // rejoin: a NEW session
        tracker.onHandshake(other, WireDialect.CURRENT);
        assertEquals("Dialects: v20=1, v19=0, v18=1, v16=0, started=1/0/2/0",
                tracker.diagLine());
    }
}
