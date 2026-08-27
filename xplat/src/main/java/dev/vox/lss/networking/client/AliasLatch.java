package dev.vox.lss.networking.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The per-play-session latch for the ALIAS decision
 * (cache-alias-keying-and-reset-override-plan.md §2.1): computed at the FIRST manager
 * build of a session and reused by every rebuild (re-sent session configs), reset in
 * {@code ClientSessionGate.onJoin} — the one event that reliably brackets a play
 * session (a server-initiated play→config reconfiguration fires neither loader's
 * disconnect, so disconnect is the wrong anchor; §9 M-B2). ONLY the alias decision is
 * latched: the world sub-key derives fresh at every derive point, per the plan's
 * fresh-per-derive rule.
 *
 * <p>Its own tiny holder class (not a {@code ClientNetGlue} static) so the gate's
 * unit tests can exercise the reset without class-loading the production glue.
 */
final class AliasLatch {

    /**
     * The latched decision: the address axis this session keys by, the sweep members
     * {@code /lss reset} clears, and the observability tokens.
     *
     * @param connectAddr      the raw connect address the decision was computed for
     *                         (a paranoia key — one connection has one address; a
     *                         mismatch recomputes rather than serving a stale latch)
     * @param addressComponent the sanitized/escaped bucket component (canonical when
     *                         the alias applied, the connect spelling otherwise)
     * @param sweepComponents  the address components {@code flushCache} sweeps
     * @param aliased          whether a group was APPLIED (not merely matched)
     * @param token            the corroboration/diag token ("no-group",
     *                         {@code AliasCorroboration.Result#token}, …)
     */
    record Decision(String connectAddr, String addressComponent,
                    List<String> sweepComponents, boolean aliased, String token) {
    }

    private static final AtomicReference<Decision> LATCH = new AtomicReference<>();

    private AliasLatch() {
    }

    /** JOIN: a new play session — nothing of the previous session's decision survives. */
    static void resetForJoin() {
        LATCH.set(null);
    }

    /** The latched decision for this session, computing (and latching) it on first
     *  ask — or recomputing if the stored decision was for a DIFFERENT connect
     *  address (defensive; one connection has one address). Main client thread. */
    static Decision forConnection(String connectAddr, Supplier<Decision> compute) {
        Decision current = LATCH.get();
        if (current != null && current.connectAddr().equals(connectAddr)) return current;
        Decision fresh = compute.get();
        LATCH.set(fresh);
        return fresh;
    }

    /** Test seam: the currently latched decision, or null. */
    static Decision peekForTest() {
        return LATCH.get();
    }
}
