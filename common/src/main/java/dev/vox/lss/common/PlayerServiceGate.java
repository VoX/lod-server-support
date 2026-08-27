package dev.vox.lss.common;

/**
 * The per-player half of the service gate (service-permission-gate-plan.md §2.2) —
 * the seam both handshake cores ride so the permission read, the once-per-session
 * denial log, and the denial bookkeeping are function-injected: the static cores
 * stay free of loader types and of static mutable state, and the gate table is
 * pinnable with a recording implementation. Production implementations:
 * {@code ServerReceiverGlue.serviceGateFor} (Fabric/NeoForge — the
 * {@code LoaderServices.checkPermission} seam) and
 * {@code LSSPaperPlugin.serviceGateFor} (the Bukkit permissible).
 *
 * <p>Threading: all three methods are consulted INLINE on the thread already handling
 * the handshake message (the server thread on Fabric/NeoForge, the player's region
 * thread on Folia) — no scheduling is added by the read; production
 * {@link #onServiceDenied} implementations marshal any state mutation to its owning
 * thread themselves (Paper enqueues a runtime task onto the pump).
 */
public interface PlayerServiceGate {

    /** Whether the handshaking player holds {@code node}. */
    boolean hasPermission(String node);

    /**
     * Claims the once-per-session denial log. Side-effecting: the FIRST call in a
     * denied episode returns true, every later one false — so the core must call it
     * only when it is actually about to deny, never speculatively. The latch lives in
     * {@link ServiceGateState} (swept on disconnect, cleared by a successful
     * registration so a revoke→grant→revoke session logs each episode once).
     */
    boolean claimDenialLog();

    /**
     * The denial hook, called under EXACTLY the {@code deniedByServiceGate}
     * conjunction (outcome DISABLED ∧ config.enabled ∧ servicePresent) — never for
     * NO_CONSUMER/Via/version denials. Production implementations deposit the
     * denied-handshake memo (so a later grant can re-offer the session,
     * plan §2.3), count the denial TRANSITION, and unregister an already-registered
     * player (a live session re-handshaking after a revocation — a permission denial
     * is an ADMIN fact, unlike the protocol facts an existing registration
     * deliberately survives).
     *
     * @param protocolVersion the denied handshake's protocol version (replayed
     *                        verbatim by the grant sweep)
     * @param capabilities    the denied handshake's capabilities bitmask
     */
    void onServiceDenied(int protocolVersion, int capabilities);

    /**
     * The gate the pre-gate handshake overloads ride: permits everything, never logs,
     * remembers nothing — exactly the behavior of every build before
     * {@code requireServicePermission} existed.
     *
     * <p><b>Test/legacy-overload only.</b> The production paths always build a real
     * gate; a production call site left on an OPEN overload would open the gate for
     * everyone with {@code requireServicePermission=true} still in the file. Same
     * landmine shape as the {@code ViaProbe.NO_SIGNAL} overloads, mitigated the same
     * way — one delegating overload per core, and
     * {@code LoaderPermissionSeamContractTest}'s census pins every production
     * receiver onto a real gate.
     */
    PlayerServiceGate OPEN = new PlayerServiceGate() {
        @Override
        public boolean hasPermission(String node) {
            return true;
        }

        @Override
        public boolean claimDenialLog() {
            return false;
        }

        @Override
        public void onServiceDenied(int protocolVersion, int capabilities) {
        }
    };

    /**
     * Whether the player clears the service gate: BOTH brand spellings must be held,
     * so a negative grant on EITHER one denies. Only called while the gate is armed.
     *
     * <p><b>AND, not OR — the De Morgan mirror of the far-player privacy nodes.</b>
     * Those are a GRANT model ({@code default: false}, holding EITHER spelling takes
     * effect); this is a DENY model ({@code default: true}, revoking EITHER spelling
     * takes effect). The dual declaration means the OTHER spelling always resolves to
     * its declared {@code true}, so an OR here would let it out-vote the admin's
     * single negative grant and the gate could never deny anyone (user, 2026-08-25).
     * With AND, one negative grant is enough on either jar, and an LSS&lt;-&gt;VSS
     * swap keeps honoring it.
     *
     * <p>Short-circuits: a player already missing the first spelling is denied
     * without a second lookup.
     */
    static boolean holdsService(PlayerServiceGate gate) {
        return gate.hasPermission(LSSPermissions.SERVICE_LSS)
                && gate.hasPermission(LSSPermissions.SERVICE_VSS);
    }
}
