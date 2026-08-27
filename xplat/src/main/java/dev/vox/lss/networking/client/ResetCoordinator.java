package dev.vox.lss.networking.client;

import dev.vox.lss.common.Brand;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.compat.VoxyStorageOverride;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The {@code /lss reset} sequence (v0.11.0 stage D —
 * client-reset-command-and-cache-relocation-plan.md Part 1), seam-injected so JUnit
 * pins the ordering and both fallbacks without MC. The command body stays thin.
 *
 * <p>Order is load-bearing: decode-queue drain + in-flight await FIRST (a late decode
 * dispatch can open a fresh Voxy store handle inside the directory the Voxy half is
 * about to wipe), the Voxy half SECOND (teardown + wipe + rebuild), the LSS flush LAST
 * (after the new Voxy instance is live, so every re-served column lands in the fresh
 * engine; columns arriving mid-sequence enqueue into the NEW instance and their stamps
 * are cleared at the flush → re-served → duplicate ingest, idempotent by protocol
 * design).
 *
 * <p>The no-manager branch (LSS inactive on this server) is destructive with NO
 * re-stream — no LSS server exists to refill Voxy, it repopulates only from vanilla
 * chunk loading — so it is gated on an explicit {@code confirm} token. With an active
 * LSS session the single-step command stands: the wipe is recoverable by construction.
 *
 * <h2>The force grant (plan §3.2)</h2>
 * {@code reset voxy-force} is the escape hatch for a declined wipe, and its two stages
 * are bound by a one-shot {@link ForceGrant}: stage 1 probes read-only, runs the
 * containment fence READ-ONLY first (an outside-fence root prints "cannot be wiped even
 * with force" and arms NOTHING), then shows "stage 2 deletes exactly: &lt;live root&gt;"
 * and arms. Stage 2 proceeds only when a grant exists, is under 60 s old, matches the
 * CONNECTION (listener object identity; null is the no-session sentinel), and
 * {@code samePath}-equals the FRESHLY probed live root; the grant is consumed either
 * way, and any mismatch re-prompts (re-arming where armable) and deletes nothing. A
 * direct {@code confirm} with no grant IS stage 1. Disconnect clears the grant — but
 * identity + disconnect are best-effort belts: <b>the stage-2 samePath re-probe is the
 * shown==wiped invariant</b> (both stages run on the main client thread). Force waives
 * exactly the derived-root comparison; the containment fence applies unchanged, and the
 * no-session branch discloses the ALL-servers cache clear exactly like the plain path.
 */
final class ResetCoordinator {

    /** The Voxy half: the force flag plus the exact root the consumed grant was armed
     *  for (null on every unforced path) — the ladder wipes under force ONLY a live
     *  root samePath-equal to it, so shown==wiped is enforced at the wipe itself. */
    @FunctionalInterface
    interface VoxyReset {
        ModCompat.VoxyResetReport reset(boolean forceWipe, java.nio.file.Path grantedLiveRoot);
    }

    /** The armed stage-1 evidence: the exact root the user was shown, when, and for
     *  which connection. One-shot — stage 2 consumes it valid or not. */
    record ForceGrant(Path normalizedLiveRoot, long armedAtNanos, Object connectionIdentity) {
    }

    static final long FORCE_GRANT_TTL_NANOS = 60_000_000_000L;

    private static final AtomicReference<ForceGrant> FORCE_GRANT = new AtomicReference<>();

    /** DISCONNECT belt (the gate calls this): a grant never survives its connection. */
    static void clearForceGrant() {
        FORCE_GRANT.set(null);
    }

    /** Test seam: the currently armed grant, or null. */
    static ForceGrant peekForceGrantForTest() {
        return FORCE_GRANT.get();
    }

    /** Injectable dependencies; production wiring lives in {@code ClientCommandActions}. */
    record Deps(boolean managerActive,
                Runnable drainAndAwaitDecode,
                VoxyReset voxyReset,
                Supplier<ModCompat.VoxyStorageProbe> voxyStorageProbe,
                Runnable lssFlush,
                Runnable clearAllCaches,
                Runnable farPlayerResubscribe,
                Consumer<String> feedback,
                Supplier<Object> connectionIdentity,
                LongSupplier nanoTime) {}

    /** The default (never-forcing) entry point — {@code /lss reset [confirm]}. */
    static boolean run(Deps deps, boolean confirmed) {
        return run(deps, confirmed, false);
    }

    /** Runs the sequence; returns true when anything was actually reset (false = an
     *  unconfirmed destructive branch replied with its prompt only). */
    static boolean run(Deps deps, boolean confirmed, boolean forceVoxyWipe) {
        java.nio.file.Path grantedRoot = null;
        if (forceVoxyWipe && !confirmed) {
            return forceStage1(deps);
        }
        if (forceVoxyWipe) {
            // Stage 2: consume the grant EITHER WAY, then re-probe and demand the exact
            // root the user was shown. The re-probe is the invariant; the TTL and the
            // connection identity are the belts.
            ForceGrant grant = FORCE_GRANT.getAndSet(null);
            var probe = deps.voxyStorageProbe().get();
            boolean valid = grant != null
                    && deps.nanoTime().getAsLong() - grant.armedAtNanos() < FORCE_GRANT_TTL_NANOS
                    && grant.connectionIdentity() == deps.connectionIdentity().get()
                    && probe.liveRoot() != null
                    && VoxyStorageOverride.samePath(grant.normalizedLiveRoot(), probe.liveRoot());
            if (!valid) {
                deps.feedback().accept(grant == null
                        ? "No armed voxy-force confirmation — showing stage 1 instead "
                                + "(nothing was deleted):"
                        : "The voxy-force confirmation is no longer valid (expired, a "
                                + "different connection, or the storage root changed or "
                                + "could not be re-read) — showing stage 1 again (nothing "
                                + "was deleted):");
                return forceStage1(deps);
            }
            grantedRoot = grant.normalizedLiveRoot();
        }
        if (!deps.managerActive()) {
            if (!confirmed) {
                deps.feedback().accept("No active " + Brand.shortName() + " session — this wipes "
                        + "the Voxy and " + Brand.shortName() + " caches for ALL servers with NO "
                        + "re-stream (Voxy repopulates only from vanilla chunk loading). Run '/"
                        + Brand.clientCommand() + " reset confirm' to proceed.");
                return false;
            }
            deps.drainAndAwaitDecode().run(); // a reset racing a just-died session's final
                                              // dispatch must still close the wipe window
            var report = voxyResetContained(deps, forceVoxyWipe, grantedRoot);
            deps.clearAllCaches().run();
            deps.feedback().accept(voxyLine(report) + Brand.shortName() + " caches cleared for "
                    + "ALL servers. No " + Brand.shortName() + " server on this connection — "
                    + "terrain repopulates only from vanilla chunk loading.");
            emitStorageDetail(deps, report, forceVoxyWipe);
            return true;
        }

        deps.drainAndAwaitDecode().run();
        var report = voxyResetContained(deps, forceVoxyWipe, grantedRoot);
        deps.lssFlush().run();
        // R-3 (filled at E1): clear the far-player tracker + seen-epoch state and
        // re-send prefs AFTER the flush — the server answers ANY prefs receipt with a
        // bumped-epoch full roster, which repopulates. Inert while unsubscribed.
        deps.farPlayerResubscribe().run();
        deps.feedback().accept(voxyLine(report) + Brand.shortName()
                + " cache cleared — re-requesting everything from the server.");
        emitStorageDetail(deps, report, forceVoxyWipe);
        return true;
    }

    /**
     * Stage 1: read-only probe, fence pre-check, prompt — and arm the grant only for a
     * root stage 2 could actually wipe (present AND inside the containment fence).
     * Nothing is drained, torn down or deleted here.
     */
    private static boolean forceStage1(Deps deps) {
        var probe = deps.voxyStorageProbe().get();
        boolean armable = probe.voxyPresent()
                && probe.liveRoot() != null
                && probe.verdict() != VoxyStorageOverride.Verdict.NO_INSTANCE
                && probe.verdict() != VoxyStorageOverride.Verdict.UNAVAILABLE
                && probe.containedForWipe();
        if (armable) {
            FORCE_GRANT.set(new ForceGrant(
                    probe.liveRoot().toAbsolutePath().normalize(),
                    deps.nanoTime().getAsLong(),
                    deps.connectionIdentity().get()));
        } else {
            // A dead-end prompt must not leave an earlier grant armed behind it.
            FORCE_GRANT.set(null);
        }
        VoxyStorageOverride.forcePromptLines(probe, deps.managerActive(), armable)
                .forEach(deps.feedback());
        return false;
    }

    /** The last containment belt (stage-D review m2): a throw escaping the Voxy half
     *  must never skip the LSS flush — a wiped Voxy plus surviving LSS stamps is the
     *  persisted-false-stamps hole the feedback branches exist to prevent. */
    private static ModCompat.VoxyResetReport voxyResetContained(Deps deps, boolean forceVoxyWipe,
                                                                java.nio.file.Path grantedRoot) {
        try {
            return deps.voxyReset().reset(forceVoxyWipe, grantedRoot);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            dev.vox.lss.common.LSSLogger.error("Voxy reset threw — treating as restart failure "
                    + "so the " + Brand.shortName() + " flush still runs", t);
            return ModCompat.VoxyResetReport.of(ModCompat.VoxyResetOutcome.RESTART_FAILED);
        }
    }

    /**
     * A declined disk wipe leaves LODs on disk that the user may want to remove by
     * hand, so the feedback names both roots — the SAME lines the client log got, from
     * the same assembler.
     *
     * <p>The trigger is {@code wipeDeclined}, NOT the {@code RESET_WIPE_SKIPPED} outcome.
     * A wipe the cross-check refused can still finish as UNAVAILABLE, SHUTDOWN_FAILED or
     * RESTART_FAILED when a later rung fails, and those users need the report just as
     * much. The flag is set exactly where the log emits, so the two cannot diverge.
     */
    private static void emitStorageDetail(Deps deps, ModCompat.VoxyResetReport report,
                                          boolean forceVoxyWipe) {
        if (!report.wipeDeclined()) return;
        // Offer voxy-force only where stage 1 could actually arm it — an outside-fence
        // live root gets the report without the dead-end instruction (panel fix).
        VoxyStorageOverride.wipeSkippedLines(report.verdict(), report.liveRoot(),
                report.expectedRoot(), !forceVoxyWipe && report.liveRootContained())
                .forEach(deps.feedback());
    }

    /** The per-outcome Voxy prefix of the feedback line. The UNAVAILABLE and
     *  RESET_WIPE_SKIPPED branches must not claim more than actually happened;
     *  RESET_WIPE_SKIPPED's detail (both storage roots) follows on its own lines. */
    private static String voxyLine(ModCompat.VoxyResetReport report) {
        return switch (report.outcome()) {
            case RESET -> "Voxy LODs cleared (disk + memory). ";
            case RESET_WIPE_SKIPPED -> "Voxy engine reset (memory cleared) — the disk wipe was "
                    + "SKIPPED (fail-safe); rejoin or re-run to clear disk. ";
            case WIPED_NO_INSTANCE -> "Voxy disk cache cleared (Voxy not running). ";
            case NOT_PRESENT -> "";
            case UNAVAILABLE -> "Voxy reset unavailable on this Voxy version — clearing the "
                    + Brand.shortName() + " half only. ";
            case SHUTDOWN_FAILED -> "Voxy reset incomplete — rejoin to fully clear. ";
            case RESTART_FAILED -> "Voxy failed to restart — rejoin to recover. ";
        };
    }

    private ResetCoordinator() {}
}
