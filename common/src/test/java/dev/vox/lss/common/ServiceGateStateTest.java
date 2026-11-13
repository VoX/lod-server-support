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

        state.rememberDenied(uuid, "Steve", 20, 1);
        assertEquals(1, state.permissionDeniedTotal(),
                "a re-handshake while already denied is NOT re-counted (pinned: §2.5)");
        assertEquals(new ServiceGateState.DeniedHandshake(20, 1, "Steve"),
                state.takeDenied(uuid),
                "at the same rung the LATEST handshake is the one worth replaying "
                        + "(capability staleness is accepted; any real re-handshake overwrites)");
        assertNull(state.takeDenied(uuid), "take is a removal — the sweep replays once");
    }

    /** Review fold A11 (supersedes the old "latest wins" pin for lower rungs): an old
     *  client's discovery ladder answers a v20 denial with 19 and 16 rungs on the same
     *  connection — the replay must use the highest rung it announced, or a disarm/grant
     *  would serve a v20-capable client in the v16 dialect. */
    @Test
    void aLaterLowerLadderRungNeverReplacesAStoredHigherOne() {
        state.rememberDenied(uuid, "Steve", 20, 5, ServiceGateState.REASON_MAP_POLICY);
        state.rememberDenied(uuid, "Steve", 19, 1, ServiceGateState.REASON_MAP_POLICY);
        state.rememberDenied(uuid, "Steve", 16, 1, ServiceGateState.REASON_PERMISSION);
        assertEquals(new ServiceGateState.DeniedHandshake(20, 5, "Steve",
                        ServiceGateState.REASON_MAP_POLICY | ServiceGateState.REASON_PERMISSION),
                state.peekDenied(uuid), "the CURRENT handshake (and its capabilities) is kept; reasons still merge");
        state.onDisconnect(uuid);
        state.rememberDenied(uuid, "Steve", 16, 1);
        state.rememberDenied(uuid, "Steve", 19, 3);
        assertEquals(new ServiceGateState.DeniedHandshake(19, 3, "Steve"), state.peekDenied(uuid),
                "a HIGHER later rung replaces a lower one");
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
    // ---- map policy (map-policy plan A3.4): the memo's reason mask ----

    @Test
    void reasonsMergeAndEachCountsItsOwnTransitions() {
        assertTrue(state.rememberDenied(uuid, "Steve", 18, 1, ServiceGateState.REASON_MAP_POLICY),
                "the first policy refusal is a transition");
        assertFalse(state.rememberDenied(uuid, "Steve", 16, 1, ServiceGateState.REASON_MAP_POLICY),
                "a re-refusal while refused is not");
        assertEquals(1, state.mapPolicyRefusedTotal());
        assertEquals(0, state.permissionDeniedTotal(),
                "a policy refusal never counts as a permission denial");

        // A permission denial on the same connection MERGES (the latest handshake wins)
        // and counts a permission transition.
        state.rememberDenied(uuid, "Steve", 20, 9);
        assertEquals(1, state.permissionDeniedTotal());
        assertEquals(1, state.mapPolicyRefusedTotal(), "the policy reason did not re-count");
        var entry = state.peekDenied(uuid);
        assertEquals(new ServiceGateState.DeniedHandshake(20, 9, "Steve",
                ServiceGateState.REASON_PERMISSION | ServiceGateState.REASON_MAP_POLICY), entry,
                "the memo holds BOTH reasons — a replay waits until every one has cleared");
        assertTrue(entry.has(ServiceGateState.REASON_PERMISSION)
                && entry.has(ServiceGateState.REASON_MAP_POLICY));
    }

    @Test
    void theDiagCountsSeparateThePermissionAndPolicyEntries() {
        var other = UUID.randomUUID();
        state.rememberDenied(uuid, "Steve", 20, 1);
        state.rememberDenied(other, "Alex", 19, 1, ServiceGateState.REASON_MAP_POLICY);
        assertEquals(2, state.deniedCount());
        assertEquals(1, state.deniedCount(ServiceGateState.REASON_PERMISSION),
                "Gate: denied= must not mix in policy refusals");
        assertEquals(1, state.deniedCount(ServiceGateState.REASON_MAP_POLICY),
                "MapPolicy: refused= counts only policy refusals");
    }

    @Test
    void thePolicyNoticeLatchIsPerConnectionAndSurvivesRegistration() {
        Object connection = new Object();
        assertTrue(state.claimMapPolicyNotice(uuid, connection), "the first refusal of a connection notifies");
        assertFalse(state.claimMapPolicyNotice(uuid, connection), "never twice on one connection");
        state.onRegistered(uuid);
        assertFalse(state.claimMapPolicyNotice(uuid, connection),
                "a registration (an arm/disarm cycle) must not re-arm the chat line — that is "
                        + "exactly why it cannot share the denial-log latch");
        state.onDisconnect(uuid);
        assertFalse(state.hasMapPolicyNotice(uuid), "the disconnect drops the claim");
        assertTrue(state.claimMapPolicyNotice(uuid, connection), "a new connection gets its line again");
        state.clear();
        assertTrue(state.claimMapPolicyNotice(uuid, connection), "server stop clears it too");
    }

    /** Review fold A7: the claim is tied to the CONNECTION token — a rejoin whose old
     *  connection's cleanup has not drained yet (Paper's mailboxed quit) claims afresh. */
    @Test
    void aNewConnectionTokenClaimsAfreshBeforeTheOldOneIsCleared() {
        Object oldConnection = new Object();
        Object rejoin = new Object();
        assertTrue(state.claimMapPolicyNotice(uuid, oldConnection));
        assertTrue(state.claimMapPolicyNotice(uuid, rejoin), "the rejoin gets its own line");
        assertFalse(state.claimMapPolicyNotice(uuid, rejoin), "once");
    }

    @Test
    void thePermissionOnlyShapeKeepsItsRecordIdentity() {
        assertEquals(ServiceGateState.REASON_PERMISSION,
                new ServiceGateState.DeniedHandshake(20, 1, "Steve").reasons(),
                "every pre-policy deposit is a PERMISSION entry");
    }

    // ---- map-ingestion exemption bookkeeping (map-ingestion permission plan §3.3/§3.4) ----

    @Test
    void theLastFlagsSentRecordOnlyFramesThatCarryTheFieldAndDieWithTheConnection() {
        int bit = LSSConstants.CAPABILITY_MAP_POLICY;
        int v20 = LSSConstants.PROTOCOL_VERSION;
        assertNull(state.lastMapFlagsSent(uuid), "nothing sent yet");
        state.noteMapFlagsSent(uuid, v20, 1, true);
        assertNull(state.lastMapFlagsSent(uuid), "a session without the bit has no flags field");
        state.noteMapFlagsSent(uuid, LSSConstants.V19_COMPAT_PROTOCOL_VERSION, 1 | bit, true);
        assertNull(state.lastMapFlagsSent(uuid), "a 19 echo never carries the field");
        state.noteMapFlagsSent(uuid, v20, 1 | bit, true);
        assertEquals(Boolean.TRUE, state.lastMapFlagsSent(uuid));
        state.noteMapFlagsSent(uuid, v20, 1 | bit, false);
        assertEquals(Boolean.FALSE, state.lastMapFlagsSent(uuid), "the latest send wins");
        state.onRegistered(uuid);
        assertEquals(Boolean.FALSE, state.lastMapFlagsSent(uuid),
                "a registration (also the dimension-change reuse path's neighbour) keeps it");
        state.onDisconnect(uuid);
        assertNull(state.lastMapFlagsSent(uuid), "the connection died: forgotten");
    }

    @Test
    void exemptAdmissionsAreSessionScopedAndEndWithTheEpisode() {
        state.setMapPolicyExemptAdmission(uuid, true);
        assertTrue(state.isMapPolicyExemptAdmission(uuid));
        state.setMapPolicyExemptAdmission(uuid, false);
        assertFalse(state.isMapPolicyExemptAdmission(uuid), "a later non-exempt registration clears it");
        state.setMapPolicyExemptAdmission(uuid, true);
        state.onDisconnect(uuid);
        assertFalse(state.isMapPolicyExemptAdmission(uuid), "disconnect clears it");
        state.setMapPolicyExemptAdmission(uuid, true);
        state.clearMapPolicyExemptAdmissions();
        assertFalse(state.isMapPolicyExemptAdmission(uuid), "a disarm clears every admission");
        state.setMapPolicyExemptCount(3);
        state.noteMapFlagsSent(uuid, LSSConstants.PROTOCOL_VERSION, LSSConstants.CAPABILITY_MAP_POLICY, true);
        state.clear();
        assertEquals(0, state.mapPolicyExemptCount());
        assertNull(state.lastMapFlagsSent(uuid), "server stop clears everything");
    }
}
