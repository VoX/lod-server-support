package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.farplayers.FarPlayerClientTracker;
import dev.vox.lss.common.farplayers.FarPlayerWire;
import dev.vox.lss.config.LSSClientConfig;

/**
 * The client half of far players, phase A (E1, FARP §3.3 — tracker + prefs, no
 * rendering at E1; the E2 renderer consumes the tracker). ARMED since E2 (the
 * defaults flip, user decision 2026-08-12): the capability bit composes when the
 * config enables it and the soak/benchmark property gate passes. E1 shipped this
 * compiled false (the v0.10.0 transport-yield default-FALSE pattern).
 *
 * <p>R-5 (decided at E1): the tracker lives HERE, outside the request-manager
 * lifecycle — a mid-session SessionConfig re-push (stage C's {@code /lsslod set})
 * rebuilds the manager while the server's subscription state survives untouched, so
 * rebuild-and-resubscribe would turn one {@code set lodDistanceChunks} on an N-player
 * server into N roster floods. Death sites: disconnect + the R-3 reset re-subscribe.
 */
public final class FarPlayerClientSupport {

    // Log-sweep hygiene (2026-08-13): per-column/per-frame failure sites aggregate to
    // one line/min — a persistent condition must not flood the client log.
    private static final dev.vox.lss.common.LogThrottle MALFORMED_FRAME_WARN =
            new dev.vox.lss.common.LogThrottle(60_000);

    /** Flipped true at E2 (the defaults decision — E1 shipped false/inert).
     *  Package-visible for the pin test. */
    static final boolean CLIENT_ARMED = true;

    private static final FarPlayerClientTracker TRACKER = new FarPlayerClientTracker();
    private static volatile FarPlayerWire.Prefs lastSentPrefs;

    /** The handshake-composition term. The soak/benchmark property gate (FARP §3.3):
     *  those clients are full Loom clients distinguished only by the system
     *  properties — without the explicit check they would subscribe and shift soak
     *  baselines. DELIBERATELY independent of {@code farPlayersEnabled} (E2 review
     *  M2, both reviewers): the subscription is the PREFS CARRIER — a client whose
     *  master toggle is off but whose shareSelf opt-out is set must still deliver
     *  that opt-out, and the server already skips serving disabled subscribers
     *  before any frame work. Coupling the bit to `enabled` made "turn everything
     *  off" strand the opt-out: MORE visible, not less. */
    public static int capabilityBit() {
        return capabilityBitFor(CLIENT_ARMED,
                Boolean.getBoolean("lss.soak"), Boolean.getBoolean("lss.benchmark"));
    }

    /** Pure form for the Tier 1 property-gate pin. */
    static int capabilityBitFor(boolean armed, boolean soakJvm, boolean benchmarkJvm) {
        if (!armed || soakJvm || benchmarkJvm) return 0;
        return LSSConstants.CAPABILITY_FAR_PLAYERS;
    }

    /**
     * Called by the Sodium screen's storage handler for the far-player options (E2
     * review m4): a mid-session "Share My Position" flip must reach the server NOW,
     * not on the next rejoin — maybeSendPrefs's changed-guard makes this free when
     * nothing changed. Sessions that never handshook (receiveServerLods off, no LOD
     * consumer) have no channel to send on; the tooltip documents that residue.
     */
    public static void onClientConfigChanged() {
        maybeSendPrefs();
    }

    /** Monotonic millis for motion state (E2 review m6): wall clock steps freeze or
     *  teleport every proxy; nanoTime is the SeeU-proven source. All motion writers
     *  and samplers must use THIS. */
    public static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private static volatile Boolean seeuPresent;
    private static volatile boolean seeuInfoLogged;

    /**
     * The SeeU-coexist gate (E3, FARP plan §6 as amended — decisions log): both mods
     * rendering the same distant player = double proxies, so SeeU's presence disables
     * the LSS renderer/receive half UNLESS `farPlayersWithSeeU` overrides (a separate
     * key because `farPlayersEnabled` defaults true — "set it true" cannot express an
     * explicit preference). This gates the EFFECTIVE enabled term only: the capability
     * bit stays composed and prefs still deliver (the prefs-carrier rule survives —
     * `enabled=false` in the prefs stops the server streaming frames; the shareSelf
     * opt-out still arrives). An INFO names the override once so the fix is
     * discoverable from the log; the Sodium tooltip carries it too.
     */
    public static boolean effectiveFarPlayersEnabled() {
        var config = LSSClientConfig.CONFIG;
        boolean gate = effectiveEnabledFor(config.farPlayersEnabled, isSeeuPresent(),
                config.farPlayersWithSeeU);
        if (!gate && config.farPlayersEnabled && isSeeuPresent() && !seeuInfoLogged) {
            seeuInfoLogged = true;
            LSSLogger.info("SeeU detected — LSS stops drawing far players to avoid"
                    + " double proxies (your own Share My Position setting still"
                    + " applies); set farPlayersWithSeeU=true in lss-client-config.json"
                    + " (the 'Prefer LSS Far Players' option in the Sodium screen)"
                    + " to use LSS instead");
        }
        return gate;
    }

    /** Pure form for the Tier 1 coexist pin. */
    static boolean effectiveEnabledFor(boolean configEnabled, boolean seeuPresent,
                                       boolean withSeeUOverride) {
        return configEnabled && (!seeuPresent || withSeeUOverride);
    }

    public static boolean isSeeuPresent() {
        Boolean present = seeuPresent;
        if (present == null) {
            try {
                var loader = dev.vox.lss.platform.LoaderServices.get();
                // "voxyseeu" is the pre-rename mod id (the vendored clone's config
                // migration shows the rename) — an old SeeU build must trip the
                // gate too (E3 review NIT-3).
                present = loader.isModLoaded("seeu") || loader.isModLoaded("voxyseeu");
            } catch (Throwable t) {
                present = false; // loader-less unit contexts: no SeeU
            }
            seeuPresent = present;
        }
        return present;
    }

    /**
     * Prefs send, once per session unless changed (the {@code lss:client_info} sidecar
     * guard doctrine per R-7: a v0.11.0 client reaches PRE-v0.11.0 servers that never
     * registered the channel — containment, and the send must never take the session
     * down). Call sites: session-config receipt (post-handshake) and the R-3 reset
     * re-subscribe. No-op while the capability bit is not composed.
     */
    static void maybeSendPrefs() {
        if (capabilityBit() == 0) return;
        var config = LSSClientConfig.CONFIG;
        var prefs = new FarPlayerWire.Prefs(effectiveFarPlayersEnabled(),
                config.farPlayersMaxDistanceBlocks, config.farPlayersMinDistanceBlocks,
                config.farPlayersShareSelf, config.farPlayersShareDistanceBlocks);
        if (prefs.equals(lastSentPrefs)) return;
        try {
            dev.vox.lss.platform.LoaderServices.get().sendToServer(new dev.vox.lss.networking.payloads
                    .FarPlayerPrefsC2SPayload(FarPlayerWire.encodePrefs(prefs)));
            lastSentPrefs = prefs;
        } catch (Exception e) {
            LSSLogger.debug("Far-player prefs send failed (legacy server?): " + e.getMessage());
        }
    }

    /** Roster frame, main client thread (the receiver hops before calling). */
    static void onRosterFrame(byte[] body) {
        try {
            TRACKER.onRoster(FarPlayerWire.decodeRoster(body));
        } catch (Exception e) {
            long n = MALFORMED_FRAME_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) {
                LSSLogger.warn("Malformed far-player roster frame — ignored (" + e + "; " + n
                        + " malformed frame(s) since the last report)");
            }
        }
    }

    /** Updates frame, main client thread. */
    static void onUpdatesFrame(byte[] body) {
        try {
            TRACKER.onUpdates(FarPlayerWire.decodeUpdates(body), monotonicMillis());
        } catch (Exception e) {
            long n = MALFORMED_FRAME_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) {
                LSSLogger.warn("Malformed far-player updates frame — ignored (" + e + "; " + n
                        + " malformed frame(s) since the last report)");
            }
        }
    }

    /**
     * Called at every handshake SEND (review M3): a re-handshake — the v16 discovery
     * re-announce, a /reload re-attach, a LAN promote — starts a fresh server-side
     * session whose subscription state is new, so a surviving latch would suppress the
     * prefs send that triggers the first roster. Clearing here keeps maybeSendPrefs's
     * once-unless-changed guard scoped to ONE server session, which is its contract.
     */
    static void onHandshakeSent() {
        lastSentPrefs = null;
    }

    /** Disconnect: the tracker + the prefs-sent latch + the renderer's proxy set die
     *  with the connection.
     *
     *  <p>FORWARD CONSTRAINT (N-1b review): {@code FarPlayerRenderer} is a PER-LOADER
     *  class this xplat file compiles against — a coupling XplatLoaderPurityTest cannot
     *  see (it scans loader-API packages, not module homes). Every loader module that
     *  compiles xplat must ship a same-FQN {@code FarPlayerRenderer} exposing
     *  {@code clearInstance()} (both the Fabric and — since v0.14.0 — the live NeoForge
     *  renderer satisfy this; a render-path-cut no-op variant would too). */
    static void onSessionEnd() {
        TRACKER.clear();
        lastSentPrefs = null;
        FarPlayerRenderer.clearInstance();
    }

    /**
     * The R-3 reset re-subscribe (fills stage D's marked seam in ResetCoordinator):
     * clear the tracker + seen-epoch state AND re-send prefs — the server answers ANY
     * prefs receipt with a bumped-epoch full roster, which repopulates. A bare tracker
     * clear without the prefs re-send would strand the client (the reason R-3 rejected
     * it). Inert while unsubscribed (the send no-ops).
     */
    public static void resetAndResubscribe() {
        TRACKER.clear();
        lastSentPrefs = null;
        maybeSendPrefs();
    }

    static FarPlayerClientTracker tracker() {
        return TRACKER;
    }

    private FarPlayerClientSupport() {}
}
