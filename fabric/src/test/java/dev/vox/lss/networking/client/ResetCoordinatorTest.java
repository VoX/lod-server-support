package dev.vox.lss.networking.client;

import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.compat.VoxyStorageOverride;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The /lss reset sequence pins (v0.11.0 stage D): drain-then-Voxy-then-flush ordering,
 * the per-outcome feedback branches (UNAVAILABLE must not claim LODs disappeared), and
 * the confirm-token gate on the destructive no-manager fallback.
 *
 * <p>Design B (plan §3.2) adds the FORCE GRANT axis: {@code voxy-force} stage 1 probes
 * read-only, refuses outside-fence roots before arming, and arms a one-shot grant;
 * stage 2 consumes the grant either way and proceeds only when it is fresh (60 s), on
 * the same connection, and the FRESHLY probed live root {@code samePath}-equals the one
 * the user was shown — the shown==wiped invariant. Every mismatch re-prompts and
 * deletes nothing; a direct confirm with no grant IS stage 1.
 */
class ResetCoordinatorTest {

    private static final Path LIVE = Path.of("/games/mc/.voxy/saves/origin.example.com");
    private static final Path DERIVED = Path.of("/games/mc/.voxy/saves/current.example.com");

    private final List<String> log = new ArrayList<>();
    private final List<String> feedback = new ArrayList<>();

    // Injectable belts: the fake connection identity and clock.
    private Object connection = new Object();
    private long nowNanos = 1_000_000_000L;

    @BeforeEach
    void clearGrant() {
        ResetCoordinator.clearForceGrant();
    }

    private static ModCompat.VoxyResetReport report(ModCompat.VoxyResetOutcome outcome,
                                                    Path live, Path derived,
                                                    boolean wipeDeclined) {
        return new ModCompat.VoxyResetReport(outcome,
                VoxyStorageOverride.verdict(true, true, live, derived),
                live, derived, live != null, wipeDeclined);
    }

    private static ModCompat.VoxyStorageProbe probe(Path live, Path derived, boolean contained) {
        return new ModCompat.VoxyStorageProbe(true,
                VoxyStorageOverride.verdict(true, true, live, derived),
                live, derived, contained);
    }

    private ResetCoordinator.Deps deps(boolean managerActive, ModCompat.VoxyResetOutcome outcome) {
        return deps(managerActive,
                report(outcome, LIVE, DERIVED,
                        outcome == ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED),
                probe(LIVE, DERIVED, true));
    }

    private ResetCoordinator.Deps deps(boolean managerActive,
                                       ModCompat.VoxyResetReport report,
                                       ModCompat.VoxyStorageProbe probe) {
        return new ResetCoordinator.Deps(
                managerActive,
                () -> log.add("drain"),
                (force, granted) -> {
                    log.add("voxy(force=" + force + (granted != null ? ",granted" : "") + ")");
                    return report;
                },
                () -> { log.add("probe"); return probe; },
                () -> log.add("flush"),
                () -> log.add("clearAll"),
                () -> log.add("farp"),
                feedback::add,
                () -> connection,
                () -> nowNanos);
    }

    private String allFeedback() {
        return String.join("\n", feedback);
    }

    /** Arm a grant the way a user would: run stage 1 against the same deps shape. */
    private void armGrant(ResetCoordinator.Deps deps) {
        assertFalse(ResetCoordinator.run(deps, false, true));
        assertNotNull(ResetCoordinator.peekForceGrantForTest(), "stage 1 should have armed");
        log.clear();
        feedback.clear();
    }

    @Test
    void activeSessionRunsDrainVoxyFlushInThatOrder() {
        assertTrue(ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESET), false));
        assertEquals(List.of("drain", "voxy(force=false)", "flush", "farp"), log,
                "drain FIRST (a late decode dispatch can open a store inside the wipe dir), "
                        + "Voxy half SECOND, LSS flush, then the R-3 far-player re-subscribe "
                        + "AFTER the flush (the bumped-epoch roster repopulates fresh state)");
        assertEquals(1, feedback.size());
        assertTrue(feedback.get(0).startsWith("Voxy LODs cleared (disk + memory)."), feedback.get(0));
    }

    @Test
    void activeSessionNeedsNoConfirmToken() {
        assertTrue(ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESET), false),
                "with an active LSS session the wipe is recoverable by construction — one step");
        assertTrue(log.contains("flush"));
    }

    @Test
    void unavailableOutcomeMustNotClaimLodsDisappeared() {
        ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.UNAVAILABLE), false);
        assertTrue(feedback.get(0).contains("Voxy reset unavailable"), feedback.get(0));
        assertFalse(feedback.get(0).contains("cleared (disk + memory)"),
                "LODs did NOT visibly disappear on this branch — the message must not claim it");
        assertTrue(log.contains("flush"), "the LSS half still runs");
    }

    @Test
    void failureOutcomesCarryTheirRejoinGuidance() {
        ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.SHUTDOWN_FAILED), false);
        assertTrue(feedback.get(0).contains("rejoin to fully clear"), feedback.get(0));
        feedback.clear();
        log.clear();
        ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESTART_FAILED), false);
        assertTrue(feedback.get(0).contains("rejoin to recover"), feedback.get(0));
    }

    @Test
    void notPresentOutcomeReportsTheLssHalfOnly() {
        ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.NOT_PRESENT), false);
        assertFalse(feedback.get(0).contains("Voxy"), "no Voxy installed — no Voxy claims");
        assertTrue(feedback.get(0).contains("re-requesting"), feedback.get(0));
    }

    @Test
    void noManagerFallbackRequiresTheConfirmToken() {
        assertFalse(ResetCoordinator.run(deps(false, ModCompat.VoxyResetOutcome.RESET), false),
                "the destructive no-re-stream branch must not run unconfirmed");
        assertTrue(log.isEmpty(), "nothing wiped, nothing drained");
        assertEquals(1, feedback.size());
        assertTrue(feedback.get(0).contains("reset confirm"),
                "the prompt must name the confirm form: " + feedback.get(0));
        assertTrue(feedback.get(0).contains("NO re-stream"),
                "the prompt must say exactly why it is destructive");
    }

    @Test
    void confirmedNoManagerFallbackClearsAllAndSaysThereIsNoRestream() {
        assertTrue(ResetCoordinator.run(deps(false, ModCompat.VoxyResetOutcome.WIPED_NO_INSTANCE), true));
        assertEquals(List.of("drain", "voxy(force=false)", "clearAll"), log,
                "no LSS session: drain still closes the wipe window (a reset racing a "
                        + "just-died session's final dispatch), then the Voxy half + clearAll");
        assertTrue(feedback.get(0).contains("vanilla chunk loading"), feedback.get(0));
    }

    /** Review m2: a throw escaping the Voxy half must never skip the LSS flush — the
     *  coordinator belts it to RESTART_FAILED and the flush + feedback still run. */
    @Test
    void voxyHalfThrowStillRunsTheFlushAndFeedback() {
        var deps = new ResetCoordinator.Deps(
                true,
                () -> log.add("drain"),
                (force, granted) -> { throw new IllegalStateException("mixin drift"); },
                () -> probe(LIVE, DERIVED, true),
                () -> log.add("flush"),
                () -> log.add("clearAll"),
                () -> log.add("farp"),
                feedback::add,
                () -> connection,
                () -> nowNanos);
        assertTrue(ResetCoordinator.run(deps, false));
        assertTrue(log.contains("flush"),
                "a skipped flush after a wipe persists false stamps — the belt is load-bearing");
        assertEquals(1, feedback.size());
        assertTrue(feedback.get(0).contains("rejoin to recover"), feedback.get(0));
    }

    /** The RESET_WIPE_SKIPPED line must admit the disk was NOT cleared. */
    @Test
    void wipeSkippedOutcomeIsHonestAboutTheDisk() {
        ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED), false);
        assertTrue(feedback.get(0).contains("disk wipe was SKIPPED"), feedback.get(0));
        assertFalse(feedback.get(0).contains("disk + memory"),
                "must not claim the full-RESET disk line");
        assertTrue(log.contains("flush"), "the LSS half still runs");
    }

    // ---- actionable skipped-wipe feedback ----

    /** AC1: the in-game feedback carries the same two roots the client log gets. */
    @Test
    void wipeSkippedFeedbackCarriesBothStorageRoots() {
        ResetCoordinator.run(deps(true,
                report(ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, LIVE, DERIVED, true),
                probe(LIVE, DERIVED, true)), false);
        String text = allFeedback();
        assertTrue(text.contains(LIVE.toString()), "live root missing from feedback: " + text);
        assertTrue(text.contains(DERIVED.toString()), "derived root missing: " + text);
        assertTrue(text.contains("another mod has redirected Voxy's storage location"), text);
        assertTrue(text.contains("reset voxy-force"),
                "the feedback must point at the escape hatch: " + text);
    }

    /** The detail block belongs to the skipped branch only — a clean RESET must not
     *  spray paths and override talk at a user whose wipe worked. */
    @Test
    void successfulResetGetsNoOverrideDetailBlock() {
        ResetCoordinator.run(deps(true,
                report(ModCompat.VoxyResetOutcome.RESET, LIVE, LIVE, false),
                probe(LIVE, LIVE, true)), false);
        assertEquals(1, feedback.size(), "one line, as before: " + feedback);
        assertFalse(allFeedback().contains("voxy-force"), allFeedback());
    }

    // ---- /lss reset voxy-force: the grant machinery ----

    /** Stage 1 deletes nothing — it does not even drain — and ARMS the grant. */
    @Test
    void unconfirmedForceTouchesNothingShowsThePathsAndArms() {
        assertFalse(ResetCoordinator.run(
                        deps(true, ModCompat.VoxyResetOutcome.RESET), false, true),
                "stage 1 reports; it does not reset");
        assertEquals(List.of("probe"), log,
                "a read-only probe is the ONLY thing stage 1 may do: " + log);
        String text = allFeedback();
        assertTrue(text.contains(LIVE.toString()), "the doomed path must be shown: " + text);
        assertTrue(text.contains(DERIVED.toString()), text);
        assertTrue(text.contains("reset voxy-force confirm"),
                "stage 1 must name stage 2: " + text);
        assertTrue(text.contains("Stage 2 deletes exactly: " + LIVE), text);
        assertTrue(text.contains("Nothing has been deleted"), text);
        var grant = ResetCoordinator.peekForceGrantForTest();
        assertNotNull(grant, "an armable prompt arms");
        assertEquals(LIVE.toAbsolutePath().normalize(), grant.normalizedLiveRoot(),
                "the grant holds EXACTLY the root the user was shown");
        assertSame(connection, grant.connectionIdentity());
    }

    /** Stage 2 after a fresh stage 1 runs the ordinary sequence with the cross-check
     *  waived — same order, same unconditional flush, force flag threaded through. */
    @Test
    void confirmedForceAfterStage1RunsTheFullSequenceWithForce() {
        var deps = deps(true, ModCompat.VoxyResetOutcome.RESET);
        armGrant(deps);
        assertTrue(ResetCoordinator.run(deps, true, true));
        assertEquals(List.of("probe", "drain", "voxy(force=true,granted)", "flush", "farp"), log,
                "stage 2 re-probes (the shown==wiped invariant), then force changes the "
                        + "wipe criterion, never the ordering: " + log);
        assertNull(ResetCoordinator.peekForceGrantForTest(), "the grant is consumed");
    }

    /** A direct confirm with no grant IS stage 1: prompt + arm, nothing deleted. */
    @Test
    void directConfirmWithNoGrantIsStage1() {
        assertFalse(ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESET), true, true));
        assertEquals(List.of("probe", "probe"), log,
                "the stage-2 validation probe, then the stage-1 re-prompt probe — no drain, "
                        + "no voxy, no flush: " + log);
        assertTrue(allFeedback().contains("No armed voxy-force confirmation"), allFeedback());
        assertTrue(allFeedback().contains("reset voxy-force confirm"),
                "the re-prompt re-arms and names stage 2: " + allFeedback());
        assertNotNull(ResetCoordinator.peekForceGrantForTest());
    }

    /** TOCTOU: the live root changed between the stages — the shown path is not the
     *  wiped path, so stage 2 must refuse and re-prompt with the NEW root. */
    @Test
    void aChangedLiveRootBetweenStagesRefusesAndReprompts() {
        armGrant(deps(true, ModCompat.VoxyResetOutcome.RESET));
        var moved = Path.of("/games/mc/.voxy/saves/somewhere-else.example.com");
        assertFalse(ResetCoordinator.run(deps(true,
                report(ModCompat.VoxyResetOutcome.RESET, moved, DERIVED, false),
                probe(moved, DERIVED, true)), true, true));
        assertEquals(List.of("probe", "probe"), log, "nothing destructive ran: " + log);
        assertTrue(allFeedback().contains("no longer valid"), allFeedback());
        assertTrue(allFeedback().contains(moved.toString()),
                "the re-prompt shows the root stage 2 WOULD now delete: " + allFeedback());
        var rearmed = ResetCoordinator.peekForceGrantForTest();
        assertNotNull(rearmed, "the mismatch re-prompt re-arms for the new root");
        assertEquals(moved.toAbsolutePath().normalize(), rearmed.normalizedLiveRoot());
    }

    /** The 60 s TTL: an expired grant refuses and re-prompts. */
    @Test
    void anExpiredGrantRefusesAndReprompts() {
        var deps = deps(true, ModCompat.VoxyResetOutcome.RESET);
        armGrant(deps);
        nowNanos += ResetCoordinator.FORCE_GRANT_TTL_NANOS + 1;
        assertFalse(ResetCoordinator.run(deps, true, true));
        assertFalse(log.contains("voxy(force=true)"), log.toString());
        assertTrue(allFeedback().contains("no longer valid"), allFeedback());
    }

    /** The connection belt: a grant armed on one connection refuses on another. */
    @Test
    void aGrantFromAnotherConnectionRefuses() {
        var deps = deps(true, ModCompat.VoxyResetOutcome.RESET);
        armGrant(deps);
        connection = new Object(); // rejoin/switch between the stages
        assertFalse(ResetCoordinator.run(deps, true, true));
        assertFalse(log.contains("drain"), "nothing destructive ran: " + log);
        assertTrue(allFeedback().contains("no longer valid"), allFeedback());
    }

    /** One-shot: the grant is consumed by a successful stage 2 — an immediate second
     *  confirm is a fresh stage 1, not a second wipe. */
    @Test
    void aConsumedGrantDoesNotAuthorizeASecondConfirm() {
        var deps = deps(true, ModCompat.VoxyResetOutcome.RESET);
        armGrant(deps);
        assertTrue(ResetCoordinator.run(deps, true, true));
        log.clear();
        feedback.clear();
        assertFalse(ResetCoordinator.run(deps, true, true),
                "the second confirm has no grant — stage 1 again");
        assertFalse(log.contains("drain"), log.toString());
        assertTrue(allFeedback().contains("No armed voxy-force confirmation"), allFeedback());
    }

    /** Vanished instance between the stages (plan §4.6): a stage-2 probe with no
     *  readable live root refuses and deletes nothing. */
    @Test
    void aVanishedLiveRootAtStage2Refuses() {
        armGrant(deps(true, ModCompat.VoxyResetOutcome.RESET));
        assertFalse(ResetCoordinator.run(deps(true,
                report(ModCompat.VoxyResetOutcome.RESET, null, DERIVED, false),
                new ModCompat.VoxyStorageProbe(true,
                        VoxyStorageOverride.Verdict.NO_INSTANCE, null, DERIVED, false)),
                true, true));
        assertFalse(log.contains("drain"), "nothing destructive ran: " + log);
        assertTrue(allFeedback().contains("no longer valid"), allFeedback());
    }

    /** force × SHUTDOWN_FAILED (plan §4.6): a forced run whose ladder fails a later
     *  rung still reports honestly and never re-offers the force it just ran. */
    @Test
    void aForcedRunEndingShutdownFailedReportsHonestly() {
        var deps = deps(true,
                report(ModCompat.VoxyResetOutcome.SHUTDOWN_FAILED, LIVE, DERIVED, true),
                probe(LIVE, DERIVED, true));
        armGrant(deps);
        assertTrue(ResetCoordinator.run(deps, true, true));
        String text = allFeedback();
        assertTrue(text.contains("rejoin to fully clear"), text);
        assertTrue(text.contains(LIVE.toString()),
                "the declined-wipe detail still names the roots: " + text);
        assertFalse(text.contains("voxy-force"), "already forced — no re-offer: " + text);
        assertTrue(log.contains("flush"), "the LSS half still runs");
    }

    /** The disconnect belt: the gate clears the grant with the session. */
    @Test
    void aClearedGrantRefuses() {
        var deps = deps(true, ModCompat.VoxyResetOutcome.RESET);
        armGrant(deps);
        ResetCoordinator.clearForceGrant();
        assertFalse(ResetCoordinator.run(deps, true, true));
        assertFalse(log.contains("drain"), log.toString());
    }

    /** The fence pre-check (plan §3.2): an outside-fence live root prints the terminal
     *  refusal and arms NOTHING — and it even clears a stale earlier grant. */
    @Test
    void anOutsideFenceRootArmsNothing() {
        armGrant(deps(true, ModCompat.VoxyResetOutcome.RESET)); // a stale earlier grant
        assertFalse(ResetCoordinator.run(deps(true,
                report(ModCompat.VoxyResetOutcome.RESET, Path.of("/etc"), DERIVED, false),
                probe(Path.of("/etc"), DERIVED, false)), false, true));
        assertTrue(allFeedback().contains("cannot be wiped even with force"), allFeedback());
        assertNull(ResetCoordinator.peekForceGrantForTest(),
                "a dead-end prompt must not leave any grant armed behind it");
    }

    /** No live root: stage 1 prompts terminally and arms nothing. */
    @Test
    void noLiveRootArmsNothing() {
        assertFalse(ResetCoordinator.run(deps(true,
                report(ModCompat.VoxyResetOutcome.RESET, null, DERIVED, false),
                probe(null, DERIVED, false)), false, true));
        assertNull(ResetCoordinator.peekForceGrantForTest());
        assertFalse(allFeedback().contains("voxy-force confirm"), allFeedback());
    }

    /** The confirm token is shared: a forced reset on a dead session is still the
     *  destructive no-re-stream branch, and stage 1 disclosed the ALL-servers clear. */
    @Test
    void unconfirmedForceWithoutASessionAlsoWarnsAboutTheMissingRestream() {
        assertFalse(ResetCoordinator.run(
                deps(false, ModCompat.VoxyResetOutcome.RESET), false, true));
        assertTrue(allFeedback().contains("NO re-stream"), allFeedback());
        assertTrue(allFeedback().contains("ALL servers"), allFeedback());
        assertEquals(List.of("probe"), log, log.toString());
    }

    @Test
    void confirmedForceWithoutASessionStillClearsAllAndSkipsTheFlush() {
        var deps = deps(false, ModCompat.VoxyResetOutcome.WIPED_NO_INSTANCE);
        armGrant(deps);
        assertTrue(ResetCoordinator.run(deps, true, true));
        assertEquals(List.of("probe", "drain", "voxy(force=true,granted)", "clearAll"), log,
                log.toString());
    }

    /** The two-argument entry point — every existing caller of the default path —
     *  must still request an UNFORCED reset. */
    @Test
    void theDefaultEntryPointNeverForces() {
        ResetCoordinator.run(deps(true, ModCompat.VoxyResetOutcome.RESET), false);
        assertTrue(log.contains("voxy(force=false)"), log.toString());
        assertFalse(log.contains("probe"),
                "the default path must not pay for the force-prompt probe: " + log);
    }

    /** A forced run that still ends skipped must not tell the user to run the very
     *  command they just ran. */
    @Test
    void forcedRunThatStillSkipsDoesNotSuggestForcingAgain() {
        var deps = deps(true,
                report(ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, null, DERIVED, true),
                probe(LIVE, DERIVED, true));
        armGrant(deps);
        ResetCoordinator.run(deps, true, true);
        String text = allFeedback();
        assertTrue(text.contains("disk wipe was SKIPPED"), text);
        assertFalse(text.contains("voxy-force"), "already forced — the hint is noise: " + text);
    }

    // ---- the detail block follows the WIPE, not the outcome ----

    /**
     * A wipe the cross-check declined can still end UNAVAILABLE / SHUTDOWN_FAILED /
     * RESTART_FAILED when a later rung fails — and those users need the "your LODs are
     * still at &lt;path&gt;" report just as much. The report carries {@code wipeDeclined}
     * precisely so chat and log agree.
     */
    @Test
    void aDeclinedWipeIsReportedInChatWhateverTheLadderEndedAs() {
        for (var outcome : List.of(ModCompat.VoxyResetOutcome.UNAVAILABLE,
                ModCompat.VoxyResetOutcome.SHUTDOWN_FAILED,
                ModCompat.VoxyResetOutcome.RESTART_FAILED,
                ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED)) {
            log.clear();
            feedback.clear();
            ResetCoordinator.run(deps(true,
                    report(outcome, LIVE, DERIVED, true),
                    probe(LIVE, DERIVED, true)), false);
            String text = allFeedback();
            assertTrue(text.contains(LIVE.toString()),
                    outcome + ": the LODs are still at this path and chat never said so: " + text);
            assertTrue(text.contains(DERIVED.toString()), outcome + ": " + text);
            assertTrue(log.contains("flush"), outcome + ": the LSS half still runs");
        }
    }

    /** The mirror image: a failure that never declined a wipe must stay quiet, because
     *  the client log said nothing either. */
    @Test
    void anUndeclinedWipeGetsNoDetailBlock() {
        for (var outcome : List.of(ModCompat.VoxyResetOutcome.UNAVAILABLE,
                ModCompat.VoxyResetOutcome.SHUTDOWN_FAILED,
                ModCompat.VoxyResetOutcome.RESTART_FAILED,
                ModCompat.VoxyResetOutcome.RESET)) {
            log.clear();
            feedback.clear();
            ResetCoordinator.run(deps(true,
                    report(outcome, LIVE, DERIVED, false),
                    probe(LIVE, DERIVED, true)), false);
            assertEquals(1, feedback.size(),
                    outcome + ": no cross-check refusal, so no report: " + feedback);
        }
    }
}
