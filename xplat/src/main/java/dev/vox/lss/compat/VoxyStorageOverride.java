package dev.vox.lss.compat;

import dev.vox.lss.common.Brand;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Voxy storage-override cross-check: the criterion that decides whether the live
 * storage root may be deleted, and everything the user is told about that decision
 * (cache-alias-keying-and-reset-override-plan.md §3 — Design B, evolved from PR #243's
 * one-assembler report). Both halves live here on purpose — the
 * {@code /lss reset voxy-force} prompt promises "this is the directory that will be
 * deleted", and it can only keep that promise while it and the wipe share one
 * predicate. The text is likewise ONE assembler behind BOTH the client log and the
 * in-game feedback, so the two can never drift apart.
 *
 * <p>The wipe-skip itself is CORRECT and deliberate — a Flashback/ReplayMod storage
 * override points at the ORIGIN server's real store, which passes directory
 * containment, so the derived-root cross-check in {@link #shouldWipeLiveRoot} is the
 * only thing standing between {@code /lss reset} and someone else's LODs. What this
 * class adds is what the user is TOLD: both roots, the verdict-specific cause, and —
 * through the coordinator's two-stage force grant — a deliberate, path-first way to
 * accept the risk on purpose.
 *
 * <p>Pure by construction: no MC, no IO, no state beyond {@link Brand}. Paths are
 * rendered verbatim ({@code toString}) — they arrive absolute from both Voxy's
 * {@code getStorageBasePath()} and LSS's own derivation, and re-absolutising a path
 * here against the process CWD would print a directory that does not exist.
 */
public final class VoxyStorageOverride {

    /** Rendered in place of a root that could not be read or derived. Never "null" —
     *  the user has to be able to tell "we did not look" from a real path. */
    public static final String UNRESOLVED = "<unresolved>";

    private VoxyStorageOverride() {
    }

    /**
     * What the probe/cross-check found — FIVE verdicts (plan §3.1), because the ways
     * of not-matching are NOT the same thing to a user: a domain nobody could resolve,
     * an instance that is not running, a root nobody could check, and a root that was
     * checked and disagreed. Only OVERRIDDEN licenses "another mod redirected Voxy's
     * storage"; only NO_INSTANCE carries the "run a plain reset instead" hint (a plain
     * reset DOES wipe there, via the fallback derivation); UNAVAILABLE wipes nothing
     * and hints nothing.
     */
    public enum Verdict {
        /** live == derived: an ordinary session. Forcing changes nothing. */
        MATCHES,
        /** live != derived: a storage override is active (Flashback/ReplayMod/other). */
        OVERRIDDEN,
        /** the derived root is unavailable, so the live root was never checked at all —
         *  "could not be verified against", never "does not match". */
        UNVERIFIABLE,
        /** Voxy is installed but not RUNNING (config-disabled / GPU-unsupported): there
         *  is no live root to force — a plain reset wipes the derived root directly. */
        NO_INSTANCE,
        /** the reflective domain is unresolvable, or the probe/read failed: nothing can
         *  be checked and a plain reset wipes nothing. */
        UNAVAILABLE
    }

    /**
     * The verdict, from the shapes only the probing caller knows. Single source of
     * truth for both the wipe decision's report and everything the user is told.
     *
     * @param domainResolved  the reflective surface resolved and the instance probe
     *                        did not throw
     * @param instancePresent a live Voxy instance exists
     * @param liveRoot        the root it reported (null = unreadable)
     * @param expectedRoot    the root LSS derived for this connection (null = underivable)
     */
    public static Verdict verdict(boolean domainResolved, boolean instancePresent,
                                  Path liveRoot, Path expectedRoot) {
        if (!domainResolved) return Verdict.UNAVAILABLE;
        if (!instancePresent) return Verdict.NO_INSTANCE;
        if (liveRoot == null) return Verdict.UNAVAILABLE;
        if (expectedRoot == null) return Verdict.UNVERIFIABLE;
        return samePath(liveRoot, expectedRoot) ? Verdict.MATCHES : Verdict.OVERRIDDEN;
    }

    /**
     * May the LIVE storage root be deleted?
     *
     * <p>The default answer is the stage-D review MAJOR and Design B does not relax it:
     * only a root that was read AND verified equal to this connection's own derivation
     * is wipeable. Everything else — unreadable, unverifiable, or disagreeing — is not.
     *
     * <p>{@code force} waives the derived-root comparison and NOTHING else. It is
     * reachable only through the coordinator's consumed, TTL'd, connection-bound
     * {@code ForceGrant} — i.e. after the user has been shown the exact path and the
     * stage-2 re-probe confirmed it unchanged — and the wipe it authorises is still
     * gated by {@code VoxyCompat.wipeVoxyStore}'s containment fence, the second fence.
     */
    public static boolean shouldWipeLiveRoot(Path liveRoot, Path expectedRoot, boolean force) {
        if (liveRoot == null) return false;
        if (force) return true;
        return expectedRoot != null && samePath(liveRoot, expectedRoot);
    }

    /** Absolute-normalised path equality — the cross-check's comparison, isolated so
     *  every caller of it compares roots the same way. */
    public static boolean samePath(Path a, Path b) {
        if (a == null || b == null) return false;
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /** Display form of a possibly-absent root. */
    public static String render(Path root) {
        return root == null ? UNRESOLVED : root.toString();
    }

    /** The two root lines — the actionable core of every report here, written ONCE so
     *  the skipped-wipe report and the force prompt cannot drift apart. */
    static List<String> rootLines(Path liveRoot, Path expectedRoot) {
        return List.of("  Voxy's live storage root: " + render(liveRoot),
                "  " + Brand.shortName() + "'s expected root: " + render(expectedRoot));
    }

    /** Why the roots look the way they do — one sentence per {@link Verdict}, shared by
     *  both reports so a reworded cause changes in exactly one place. */
    static String causeLine(Verdict verdict) {
        return "  " + switch (verdict) {
            case OVERRIDDEN -> "This usually means another mod has redirected Voxy's storage "
                    + "location (a replay mod, or any other storage override).";
            case UNVERIFIABLE -> Brand.shortName() + " could not derive the expected root for "
                    + "this connection, so the live root was never checked against anything.";
            case MATCHES -> "The live root matches the root " + Brand.shortName()
                    + " derived for this connection.";
            case NO_INSTANCE -> "Voxy is installed but not running for this session, so "
                    + "there is no live storage root.";
            case UNAVAILABLE -> "Voxy's storage could not be probed on this Voxy version, "
                    + "so nothing was checked and nothing can be deleted.";
        };
    }

    /**
     * The whole "your LODs are still on disk, here is where" report: headline + the two
     * roots + the cause + (where it would actually help) the override that lifts the
     * cross-check.
     *
     * <p>{@code offerForce} is false when the caller IS the forced run — telling someone
     * who just ran {@code voxy-force} to run {@code voxy-force} is noise. It is also
     * suppressed when there is no live root: {@code voxy-force} can only answer that
     * case with "nothing to force-wipe", so offering it would send the user down a dead
     * end.
     *
     * @param verdict      what the ladder's own probe found (carried on the report — the
     *                     roots alone cannot distinguish NO_INSTANCE from UNAVAILABLE)
     * @param liveRoot     what the running Voxy instance reports, or null if unreadable
     * @param expectedRoot what LSS derived for this connection, or null if underivable
     */
    public static List<String> wipeSkippedLines(Verdict verdict, Path liveRoot,
                                                Path expectedRoot, boolean offerForce) {
        var out = new ArrayList<String>();
        out.add("Voxy's LOD store was NOT deleted: " + switch (verdict) {
            case NO_INSTANCE, UNAVAILABLE ->
                    "its live storage root could not be read (fail-safe).";
            case UNVERIFIABLE -> "the live storage root could not be verified against the root "
                    + Brand.shortName() + " derives for this connection (fail-safe).";
            default -> "the live storage root does not match the root " + Brand.shortName()
                    + " derived for this connection (fail-safe).";
        });
        out.addAll(rootLines(liveRoot, expectedRoot));
        out.add(causeLine(verdict));
        if (offerForce && liveRoot != null) {
            out.add("  To delete the live root anyway, run '/" + Brand.clientCommand()
                    + " reset voxy-force' — it shows the path before deleting anything.");
        }
        return List.copyOf(out);
    }

    /**
     * Stage 1 of {@code /lss reset voxy-force}: the user sees the exact directory that
     * stage 2 would delete, why the safety check fired, and — only when the coordinator
     * actually ARMED a grant — the confirm form. The branches that cannot act (no Voxy,
     * no live root, an outside-fence root) deliberately do NOT name the confirm form:
     * offering a stage 2 that cannot act would be a lie.
     *
     * @param probe         the read-only storage probe
     * @param managerActive whether an LSS session backs this connection (the no-session
     *                      force discloses the ALL-servers cache clear verbatim, like
     *                      the plain no-session reset)
     * @param armed         whether the coordinator armed a grant for this prompt (the
     *                      fence pre-check refused outside-fence roots BEFORE arming)
     */
    public static List<String> forcePromptLines(ModCompat.VoxyStorageProbe probe,
                                                boolean managerActive, boolean armed) {
        if (!probe.voxyPresent()) {
            return List.of("Voxy is not installed — there is no Voxy store to force-wipe.");
        }
        Verdict verdict = probe.verdict();
        if (verdict == Verdict.NO_INSTANCE) {
            // The ONE verdict carrying the plain-reset hint (plan §3.1): with no live
            // instance the ordinary reset already wipes the derived root directly —
            // promised only when that root actually derived (panel fix).
            return List.of(probe.expectedRoot() != null
                    ? "Voxy is not running for this session — there is no live root to "
                            + "force-wipe. Run '/" + Brand.clientCommand() + " reset' instead; it "
                            + "clears the root " + Brand.shortName() + " derives for this "
                            + "connection (" + probe.expectedRoot() + ")."
                    : "Voxy is not running for this session — there is no live root to "
                            + "force-wipe, and no derived root could be resolved; run '/"
                            + Brand.clientCommand() + " reset' for the "
                            + Brand.shortName() + " half.");
        }
        if (verdict == Verdict.UNAVAILABLE || probe.liveRoot() == null) {
            return List.of("Voxy's live storage root could not be read — there is nothing to "
                    + "force-wipe, and nothing has been deleted.");
        }
        var out = new ArrayList<String>();
        if (!armed) {
            // The coordinator armed nothing. Say WHY structurally (panel fix: the text
            // must key on the probe's own containment fact, not on an inference from
            // the arming decision — a future arming term must not turn this into a lie).
            out.add(probe.containedForWipe()
                    ? "The voxy-force confirmation was not armed for this root:"
                    : "Voxy's live storage root is OUTSIDE Voxy's own storage locations, so it "
                            + "cannot be wiped even with force (the containment fail-safe is not "
                            + "waivable):");
            out.addAll(rootLines(probe.liveRoot(), probe.expectedRoot()));
            out.add(causeLine(verdict));
            out.add("Nothing has been deleted, and nothing was armed.");
            return List.copyOf(out);
        }
        out.add("FORCED Voxy wipe — this DELETES the directory below, overriding the "
                + "storage-override safety check:");
        out.addAll(rootLines(probe.liveRoot(), probe.expectedRoot()));
        out.add(causeLine(verdict));
        switch (verdict) {
            case OVERRIDDEN -> out.add("  If that store belongs to ANOTHER server or to a "
                    + "replay, DO NOT confirm.");
            case UNVERIFIABLE -> out.add("  Confirm only if you recognise that path as this "
                    + "connection's own Voxy store.");
            case MATCHES -> out.add("  No override detected — this will behave exactly like "
                    + "'/" + Brand.clientCommand() + " reset'.");
            case NO_INSTANCE, UNAVAILABLE -> { /* unreachable: returned above */ }
        }
        if (!managerActive) {
            out.add("  There is no active " + Brand.shortName() + " session, so confirming "
                    + "ALSO clears the " + Brand.shortName() + " caches for ALL servers, with "
                    + "NO re-stream — terrain repopulates only from vanilla chunk loading.");
        }
        out.add("Nothing has been deleted yet. Stage 2 deletes exactly: "
                + render(probe.liveRoot()));
        out.add("Run '/" + Brand.clientCommand() + " reset voxy-force confirm' within 60 "
                + "seconds, on this same connection, to proceed.");
        return List.copyOf(out);
    }
}
