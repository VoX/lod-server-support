package dev.vox.lss.compat;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The storage-override diagnostics text (Design B, plan §3 — evolved from PR #243's
 * one-assembler report). Pinned here and nowhere else:
 *
 * <ol>
 *   <li>the skipped-wipe report NAMES BOTH ROOTS — the live one the user can go and
 *       delete by hand, and the one LSS derived — plus a verdict-specific cause that
 *       never claims more than was actually checked (five verdicts, five honest
 *       sentences);</li>
 *   <li>the {@code voxy-force} stage-1 prompt shows the exact path stage 2 would
 *       delete, names the confirm form ONLY when the coordinator armed a grant, and
 *       says in as many words that nothing has been deleted yet.</li>
 * </ol>
 *
 * <p>This class is the single assembler behind BOTH the client log and the in-game
 * feedback (the "log and feedback agree" AC); {@code VoxyCompatTest} and
 * {@code ResetCoordinatorTest} pin that each side actually routes through it.
 */
class VoxyStorageOverrideTest {

    private static final Path LIVE = Path.of("/games/mc/.voxy/saves/origin.example.com");
    private static final Path DERIVED = Path.of("/games/mc/.voxy/saves/current.example.com");

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    private static List<String> skipped(VoxyStorageOverride.Verdict verdict, Path live,
                                        Path expected, boolean offerForce) {
        return VoxyStorageOverride.wipeSkippedLines(verdict, live, expected, offerForce);
    }

    // ---- the verdict ----

    @Test
    void theVerdictLadderDistinguishesAllFiveShapes() {
        assertEquals(VoxyStorageOverride.Verdict.UNAVAILABLE,
                VoxyStorageOverride.verdict(false, false, null, null),
                "an unresolved domain is UNAVAILABLE whatever else holds");
        assertEquals(VoxyStorageOverride.Verdict.NO_INSTANCE,
                VoxyStorageOverride.verdict(true, false, null, DERIVED),
                "Voxy installed but not running");
        assertEquals(VoxyStorageOverride.Verdict.UNAVAILABLE,
                VoxyStorageOverride.verdict(true, true, null, DERIVED),
                "a live instance whose root could not be read");
        assertEquals(VoxyStorageOverride.Verdict.UNVERIFIABLE,
                VoxyStorageOverride.verdict(true, true, LIVE, null),
                "an underivable derived root leaves the live root UNCHECKED — not overridden");
        assertEquals(VoxyStorageOverride.Verdict.OVERRIDDEN,
                VoxyStorageOverride.verdict(true, true, LIVE, DERIVED));
        assertEquals(VoxyStorageOverride.Verdict.MATCHES,
                VoxyStorageOverride.verdict(true, true, LIVE, LIVE));
    }

    @Test
    void theWipeCriterionIsUnchangedAndForceWaivesOnlyTheComparison() {
        assertTrue(VoxyStorageOverride.shouldWipeLiveRoot(LIVE, LIVE, false));
        assertFalse(VoxyStorageOverride.shouldWipeLiveRoot(LIVE, DERIVED, false));
        assertFalse(VoxyStorageOverride.shouldWipeLiveRoot(LIVE, null, false),
                "an unverifiable root is not a wipeable root");
        assertTrue(VoxyStorageOverride.shouldWipeLiveRoot(LIVE, DERIVED, true),
                "force waives exactly the derived-root comparison");
        assertFalse(VoxyStorageOverride.shouldWipeLiveRoot(null, DERIVED, true),
                "force cannot conjure a root that was never read");
    }

    // ---- the skipped-wipe report ----

    @Test
    void skippedWipeReportNamesBothRootsAndTheLikelyCause() {
        String text = joined(skipped(VoxyStorageOverride.Verdict.OVERRIDDEN, LIVE, DERIVED, true));
        assertTrue(text.contains(LIVE.toString()),
                "the user cannot hand-delete a path we never printed: " + text);
        assertTrue(text.contains(DERIVED.toString()),
                "the derived root is what makes the mismatch legible: " + text);
        assertTrue(text.contains("another mod has redirected Voxy's storage location"),
                "the required explanation sentence is missing: " + text);
        assertTrue(text.contains("NOT deleted"), "must state the disk was left alone: " + text);
    }

    @Test
    void skippedWipeReportOffersTheForceOverrideExactlyOnce() {
        List<String> lines = skipped(VoxyStorageOverride.Verdict.OVERRIDDEN, LIVE, DERIVED, true);
        assertEquals(1, lines.stream().filter(l -> l.contains("reset voxy-force")).count(),
                "the escape hatch must be named, once: " + lines);
    }

    /** After a forced run the suggestion would be nonsense — the caller suppresses it. */
    @Test
    void skippedWipeReportCanSuppressTheForceSuggestion() {
        String text = joined(skipped(VoxyStorageOverride.Verdict.OVERRIDDEN, LIVE, DERIVED, false));
        assertFalse(text.contains("voxy-force"),
                "telling a user who just ran voxy-force to run voxy-force is noise: " + text);
        assertTrue(text.contains(LIVE.toString()), "the paths are still the point: " + text);
    }

    /** RESET_WIPE_SKIPPED also fires when the root was unreadable — a "does not match"
     *  headline would then be a lie. */
    @Test
    void unreadableLiveRootGetsItsOwnHeadlineAndPlaceholders() {
        String text = joined(skipped(VoxyStorageOverride.Verdict.UNAVAILABLE, null, DERIVED, true));
        assertTrue(text.contains("could not be read"), text);
        assertFalse(text.contains("does not match"),
                "nothing was compared — do not claim a mismatch: " + text);
        assertTrue(text.contains(VoxyStorageOverride.UNRESOLVED),
                "an absent path must render as the placeholder, never as 'null': " + text);
        assertFalse(text.contains("null"), "'null' is not a path a user can act on: " + text);
        assertFalse(text.contains("another mod has redirected"),
                "no root was read — there is nothing to blame a mod for: " + text);
    }

    /** Several ways to fail, several headlines. Claiming "does not match" when nothing
     *  was compared is the dishonesty this pins. */
    @Test
    void anUnverifiableRootGetsItsOwnHeadlineAndCauseInsteadOfAnOverrideGuess() {
        String text = joined(skipped(VoxyStorageOverride.Verdict.UNVERIFIABLE, LIVE, null, true));
        assertTrue(text.contains("could not be verified"), text);
        assertFalse(text.contains("does not match"),
                "nothing was compared — do not claim a mismatch: " + text);
        assertFalse(text.contains("another mod has redirected"),
                "an underivable derived root is not evidence of another mod: " + text);
        assertTrue(text.contains("could not derive the expected root"), text);
        assertTrue(text.contains(LIVE.toString()), "the live root is still actionable: " + text);
    }

    /** voxy-force answers a missing live root with "nothing to force-wipe" — offering it
     *  there would send the user down a dead end. */
    @Test
    void noLiveRootIsNeverOfferedTheForceOverride() {
        String text = joined(skipped(VoxyStorageOverride.Verdict.UNAVAILABLE, null, DERIVED, true));
        assertFalse(text.contains("voxy-force"),
                "voxy-force cannot act on this branch — do not advertise it: " + text);
        assertTrue(text.contains(DERIVED.toString()),
                "the derived root is the only path this branch has; it must be shown: " + text);
    }

    @Test
    void mismatchHeadlineSaysMismatch() {
        String text = joined(skipped(VoxyStorageOverride.Verdict.OVERRIDDEN, LIVE, DERIVED, true));
        assertTrue(text.contains("does not match"), text);
        assertFalse(text.contains("could not be read"), text);
    }

    // ---- the voxy-force prompt (stage 1) ----

    private static ModCompat.VoxyStorageProbe probe(Path live, Path expected, boolean contained) {
        return new ModCompat.VoxyStorageProbe(true,
                VoxyStorageOverride.verdict(true, true, live, expected), live, expected, contained);
    }

    private static List<String> prompt(ModCompat.VoxyStorageProbe p, boolean managerActive,
                                       boolean armed) {
        return VoxyStorageOverride.forcePromptLines(p, managerActive, armed);
    }

    @Test
    void forcePromptShowsTheDoomedPathAndNamesTheConfirmForm() {
        String text = joined(prompt(probe(LIVE, DERIVED, true), true, true));
        assertTrue(text.contains(LIVE.toString()), "the path about to be deleted: " + text);
        assertTrue(text.contains(DERIVED.toString()), "the derived root, for contrast: " + text);
        assertTrue(text.contains("reset voxy-force confirm"),
                "stage 1 must name stage 2 verbatim: " + text);
        assertTrue(text.contains("Stage 2 deletes exactly: " + LIVE),
                "the shown==wiped promise names the exact target: " + text);
        assertTrue(text.contains("Nothing has been deleted"),
                "the two-stage promise must be stated, not implied: " + text);
        assertTrue(text.contains("60 seconds"), "the grant's TTL is part of the promise: " + text);
    }

    @Test
    void forcePromptWarnsWhenAnOverrideIsActuallyActive() {
        String text = joined(prompt(probe(LIVE, DERIVED, true), true, true));
        assertTrue(text.contains("another mod has redirected Voxy's storage location"), text);
        assertTrue(text.contains("DO NOT confirm"),
                "deleting another server's (or a replay's) store is the real hazard: " + text);
    }

    @Test
    void forcePromptSaysSoWhenThereIsNoOverrideToOverride() {
        String text = joined(prompt(probe(LIVE, LIVE, true), true, true));
        assertTrue(text.contains("No override detected"), text);
        assertFalse(text.contains("DO NOT confirm"),
                "no hazard here — the scary line must not cry wolf: " + text);
    }

    /** Containment is the SECOND fence and force does not lift it — an outside-fence
     *  root gets the TERMINAL refusal (the coordinator armed nothing), never a confirm
     *  offer for a wipe that would be refused anyway (plan §3.2). */
    @Test
    void anOutsideFenceRootIsRefusedTerminallyAndOffersNoConfirm() {
        String text = joined(prompt(probe(Path.of("/etc"), DERIVED, false), true, false));
        assertTrue(text.contains("OUTSIDE"), text);
        assertTrue(text.contains("cannot be wiped even with force"), text);
        assertTrue(text.contains("nothing was armed"), text);
        assertFalse(text.contains("voxy-force confirm"),
                "no stage 2 is offered for a root the fence will refuse: " + text);
    }

    @Test
    void forcePromptDisclosesTheAllServersClearWithoutAnLssSession() {
        String withSession = joined(prompt(probe(LIVE, DERIVED, true), true, true));
        String without = joined(prompt(probe(LIVE, DERIVED, true), false, true));
        assertFalse(withSession.contains("NO re-stream"),
                "with a live session the wipe is recoverable by construction: " + withSession);
        assertTrue(without.contains("NO re-stream"), without);
        assertTrue(without.contains("ALL servers"),
                "confirming also clears every server's caches — say so verbatim, like the "
                        + "plain no-session path: " + without);
        assertTrue(without.contains("vanilla chunk loading"), without);
    }

    @Test
    void forcePromptDegradesHonestlyWithoutVoxyOrWithoutAReadableRoot() {
        String noVoxy = joined(prompt(new ModCompat.VoxyStorageProbe(false,
                VoxyStorageOverride.Verdict.UNAVAILABLE, null, null, false), true, false));
        assertTrue(noVoxy.contains("Voxy is not installed"), noVoxy);
        assertFalse(noVoxy.contains("confirm"), "nothing to confirm: " + noVoxy);

        String unavailable = joined(prompt(new ModCompat.VoxyStorageProbe(true,
                VoxyStorageOverride.Verdict.UNAVAILABLE, null, DERIVED, false), true, false));
        assertTrue(unavailable.contains("could not be read"), unavailable);
        assertFalse(unavailable.contains("voxy-force confirm"),
                "there is no path to force — do not offer stage 2: " + unavailable);
        assertFalse(unavailable.contains("reset' instead"),
                "UNAVAILABLE wipes nothing on the plain path either — no hint: " + unavailable);
    }

    /** NO_INSTANCE is the ONE verdict carrying the plain-reset hint: with no live
     *  instance the ordinary reset wipes the derived root directly (plan §3.1). */
    @Test
    void onlyTheNoInstanceVerdictHintsAtThePlainReset() {
        String noInstance = joined(prompt(new ModCompat.VoxyStorageProbe(true,
                VoxyStorageOverride.Verdict.NO_INSTANCE, null, DERIVED, false), true, false));
        assertTrue(noInstance.contains("reset' instead"), noInstance);
        assertTrue(noInstance.contains(DERIVED.toString()),
                "the hint names what the plain reset clears: " + noInstance);
        assertFalse(noInstance.contains("voxy-force confirm"), noInstance);
    }

    /** The prompt must not guess "another mod did this" when it simply could not look. */
    @Test
    void forcePromptSaysUnverifiableRatherThanGuessingAnOverride() {
        String text = joined(prompt(probe(LIVE, null, true), true, true));
        assertFalse(text.contains("another mod has redirected"), text);
        assertFalse(text.contains("DO NOT confirm"),
                "no override was detected — the override-specific warning must not fire: " + text);
        assertTrue(text.contains("could not derive the expected root"), text);
        assertTrue(text.contains("Confirm only if you recognise"), text);
        assertTrue(text.contains("reset voxy-force confirm"),
                "an unverifiable root is still force-wipeable, so stage 2 is still offered: " + text);
    }

    /** The two root lines come from one place — a reworded pair must move together. */
    @Test
    void bothReportsShareTheSameRootLines() {
        var shared = VoxyStorageOverride.rootLines(LIVE, DERIVED);
        assertTrue(skipped(VoxyStorageOverride.Verdict.OVERRIDDEN, LIVE, DERIVED, true)
                        .containsAll(shared),
                "the skipped-wipe report must use the shared root lines");
        assertTrue(prompt(probe(LIVE, DERIVED, true), true, true).containsAll(shared),
                "the force prompt must use the very same two lines");
        assertTrue(skipped(VoxyStorageOverride.Verdict.OVERRIDDEN, LIVE, DERIVED, true)
                        .contains(VoxyStorageOverride.causeLine(VoxyStorageOverride.Verdict.OVERRIDDEN)),
                "and the very same cause sentence");
        assertTrue(prompt(probe(LIVE, DERIVED, true), true, true)
                        .contains(VoxyStorageOverride.causeLine(VoxyStorageOverride.Verdict.OVERRIDDEN)),
                "and the very same cause sentence");
    }
}
