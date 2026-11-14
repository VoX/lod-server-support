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
 * lifecycle — a mid-session SessionConfig re-push ({@code /lsslod reload})
 * rebuilds the manager while the server's subscription state survives untouched, so
 * rebuild-and-resubscribe would turn one distance reload on an N-player
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
    private static final FarPlayerPreferenceDelivery PREFERENCES = new FarPlayerPreferenceDelivery();

    static Object connectionIdentity() {
        var listener = net.minecraft.client.Minecraft.getInstance().getConnection();
        return listener == null ? null : listener.getConnection();
    }

    /**
     * JOIN (0.23.0 plan §2): "do not share my position" goes to the server as soon as its
     * preference channel exists, with or without a session; every other preference waits
     * for the session answer. Not in the player's own world.
     */
    static void onJoin(boolean ownWorld) {
        PREFERENCES.joined(connectionIdentity(), !ownWorld);
        maybeSendPrefs();
    }

    static void onSessionReady() {
        PREFERENCES.sessionReady(connectionIdentity());
        maybeSendPrefs();
    }

    static void tickPreferenceRetry() {
        PREFERENCES.tick(connectionIdentity(), FarPlayerClientSupport::enqueuePreference);
    }

    /** The translation key of the line that follows a settings save: what the server now
     *  knows about "Share My Position". */
    public static String preferenceMessageKey() {
        return switch (PREFERENCES.report(currentPrefs())) {
            case SENT -> "lss.settings.privacy_sent";
            case OPT_OUT_SENT -> "lss.settings.privacy_opt_out_sent";
            case OPT_OUT_KEPT -> "lss.settings.privacy_opt_out_kept";
            case NOT_SENT -> "lss.settings.privacy_no_channel";
            case RETRYING -> "lss.settings.privacy_pending";
        };
    }

    private static FarPlayerPreferenceDelivery.Outcome enqueuePreference(FarPlayerWire.Prefs prefs) {
        return FarPlayerPreferenceDelivery.Outcome.valueOf(dev.vox.lss.platform.LoaderServices.get().enqueueToServer(
                new dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload(FarPlayerWire.encodePrefs(prefs))).name());
    }

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

    /** Explicit reload attempts privacy delivery before retiring acquisition. */
    public static void onClientConfigChanged() {
        maybeSendPrefs();
    }

    /** Monotonic millis for motion state (E2 review m6): wall clock steps freeze or
     *  teleport every proxy; nanoTime is the monotonic source. All motion writers
     *  and samplers must use THIS. */
    public static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Local visibility is an explicit setting, independent of sharing consent. */
    public static boolean effectiveFarPlayersEnabled() {
        return LSSClientConfig.CONFIG.farPlayersEnabled();
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
        PREFERENCES.accept(connectionIdentity(), currentPrefs(), FarPlayerClientSupport::enqueuePreference);
    }

    /** The preference frame the settings now ask for. A settings file that failed to load
     *  reads as "do not share" (the inactive settings), which is the safe side. */
    private static FarPlayerWire.Prefs currentPrefs() {
        var config = LSSClientConfig.CONFIG.snapshot().farPlayers();
        return new FarPlayerWire.Prefs(config.enabled(),
                config.distance().maxBlocks(), config.distance().minBlocks(),
                config.sharing().enabled(), config.sharing().maxDistanceBlocks());
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
        PREFERENCES.handshake();
    }

    /** Disconnect: the tracker + the prefs-sent latch + the renderer's proxy set die
     *  with the connection.
     *
     *  <p>FORWARD CONSTRAINT (N-1b review): {@code FarPlayerRenderer} is a PER-LOADER
     *  class this xplat file compiles against — a coupling XplatLoaderPurityTest cannot
     *  see (it scans loader-API packages, not module homes). Every loader module that
     *  compiles xplat must ship a same-FQN {@code FarPlayerRenderer} exposing
     *  {@code clearInstance()} (the N-3 NeoForge renderer — or its render-path-cut
     *  no-op variant — satisfies this). */
    static void onSessionEnd() {
        TRACKER.clear();
        PREFERENCES.clear();
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
        PREFERENCES.invalidateSent();
        maybeSendPrefs();
    }

    static FarPlayerClientTracker tracker() {
        return TRACKER;
    }

    private FarPlayerClientSupport() {}
}
