package dev.vox.lss.common;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service gate's per-service bookkeeping (service-permission-gate-plan.md §2.3):
 * the denied-handshake memo, the SEPARATE once-per-episode denial-log latch, the
 * transition-only counter, and the two-sweep revocation hysteresis. The lifecycle
 * clauses are each load-bearing (§8 F2-M2) — a missing sweep strands a player or
 * leaks an entry for the server's life.
 */
class ServiceGateStateTest {

    private final ServiceGateState state = new ServiceGateState();
    private final UUID uuid = UUID.randomUUID();

    @Test
    void theMemoRemembersTheLatestDeniedHandshakeAndCountsOnlyTransitions() {
        state.rememberDenied(uuid, "Steve", 20, 5);
        assertTrue(state.isDenied(uuid));
        assertEquals(1, state.permissionDeniedTotal(), "absent->present is the transition");

        state.rememberDenied(uuid, "Steve", 16, 1);
        assertEquals(1, state.permissionDeniedTotal(),
                "a re-handshake while already denied is NOT re-counted (pinned: §2.5)");
        assertEquals(new ServiceGateState.DeniedHandshake(16, 1, "Steve"),
                state.takeDenied(uuid),
                "the LATEST handshake is the one worth replaying (capability staleness "
                        + "is accepted; any real re-handshake overwrites)");
        assertNull(state.takeDenied(uuid), "take is a removal — the sweep replays once");
    }

    @Test
    void registrationEndsTheEpisodeMemoAndLatchButNotTheStreak() {
        state.rememberDenied(uuid, "Steve", 20, 5);
        assertTrue(state.claimDenialLog(uuid));
        assertFalse(state.claimDenialLog(uuid), "one line per episode");
        state.bumpRevocationStreak(uuid);

        state.onRegistered(uuid);

        assertFalse(state.isDenied(uuid), "a successful registration by ANY path removes the entry");
        assertTrue(state.claimDenialLog(uuid),
                "the latch re-arms: a revoke->grant->revoke session logs each episode once (§8 O1-m6)");
        assertTrue(state.bumpRevocationStreak(uuid),
                "the streak SURVIVES registration (implementation review 2026-08-27): "
                        + "registerPlayer is also the dimension-change reuse path, and a reset "
                        + "there would let a frequently-portalling player outrun the hysteresis "
                        + "forever — a passing sweep is what clears it");
    }

    @Test
    void disarmingClearsEveryStreak() {
        var other = UUID.randomUUID();
        state.bumpRevocationStreak(uuid);
        state.bumpRevocationStreak(other);

        state.clearRevocationStreaks();

        assertFalse(state.bumpRevocationStreak(uuid),
                "a disarm/re-arm cycle must restart the two-sweep hysteresis");
        assertFalse(state.bumpRevocationStreak(other));
    }

    @Test
    void disconnectSweepsEverythingSessionScoped() {
        state.rememberDenied(uuid, "Steve", 20, 5);
        state.claimDenialLog(uuid);
        state.bumpRevocationStreak(uuid);

        state.onDisconnect(uuid);

        assertFalse(state.isDenied(uuid), "without the sweep every denied joiner leaks for the server's life");
        assertTrue(state.claimDenialLog(uuid), "a rejoin gets its line again — per session, not per run");
        assertEquals(0, state.deniedCount());
    }

    @Test
    void clearIsTheServerStopShape() {
        var other = UUID.randomUUID();
        state.rememberDenied(uuid, "Steve", 20, 5);
        state.rememberDenied(other, "Alex", 18, 1);
        state.claimDenialLog(uuid);

        state.clear();

        assertFalse(state.hasDenied());
        assertEquals(0, state.deniedCount());
        assertTrue(state.claimDenialLog(uuid), "latches cleared too (the C1-9 shape)");
        assertEquals(2, state.permissionDeniedTotal(),
                "the transition counter is cumulative diagnostics, never cleared mid-run");
    }

    @Test
    void revocationHysteresisNeedsTwoConsecutiveFailingSweeps() {
        assertFalse(state.bumpRevocationStreak(uuid), "one failing sweep never revokes (flap armor)");
        assertTrue(state.bumpRevocationStreak(uuid), "the second consecutive failure revokes");
        assertFalse(state.bumpRevocationStreak(uuid),
                "the streak reset at the threshold — a third failing sweep starts a new pair, "
                        + "not a re-revocation of an already-revoked player");

        state.resetRevocationStreak(uuid);
        assertFalse(state.bumpRevocationStreak(uuid),
                "a passing sweep between failures resets: hysteresis is CONSECUTIVE failures");
    }

    @Test
    void snapshotIsIndependentOfTheLiveMap() {
        state.rememberDenied(uuid, "Steve", 20, 5);
        var snapshot = state.deniedSnapshot();
        state.onDisconnect(uuid);
        assertEquals(1, snapshot.size(), "the sweep iterates a copy — concurrent sweeps are safe");
        assertTrue(state.deniedSnapshot().isEmpty());
    }
}
