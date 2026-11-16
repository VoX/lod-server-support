package dev.vox.lss.common.farplayers;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LSSPermissions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The far-player broadcast core (v0.11.0 stage E1 — far-player-proxies-plan.md §3.2 as
 * amended by R-3/R-10). Platform-neutral: the owning service calls {@link #tick} from
 * its existing tick (Fabric server thread / Paper GlobalRegionScheduler pump — Folia-
 * safe by construction) with the per-tick ONLINE snapshots built ONCE, and every side effect rides the
 * injected {@link FrameSender} lane.
 *
 * <p>THREADING CONTRACT: single-threaded — every method runs on the owning service's
 * processing thread. Paper marshals network-thread arrivals (subscribe, prefs, quits)
 * through its lifecycle mailbox first, exactly like the dialect-flip precedent.
 *
 * <p>Lifecycle (the v18-rung checklist): subscription identity is keyed at THIS level,
 * not on the per-player request state — it survives the dimension-change
 * remove+register cycle (the platform calls {@link #onViewerDimensionChange}, never
 * remove) and dies only at {@link #removeViewer} (disconnect + Paper's quit-originated
 * mailbox Remove).
 *
 * <p>Roster armor: every updates frame is stamped with the viewer's roster epoch;
 * clients drop unknown-epoch updates. A FULL roster (epoch bump, rows cleared) goes out
 * on subscribe, on ANY prefs receipt — changed or byte-identical (R-3: the prefs
 * re-send IS the reset/re-subscribe mechanism) — and on the viewer's dimension change,
 * rate-bounded to one per {@link #FULL_ROSTER_MIN_INTERVAL_MILLIS} per viewer (prefs
 * are client-triggerable; a chat command must not be a roster-flood lever — a pending
 * full roster HOLDS until the bound clears, never drops).
 *
 * <p>Delta suppression is defined over the WHOLE snapshot (position + angles + pose +
 * vehicle UUID/type + equipment hash — R-10: a quantized-position-unchanged dismount
 * must still force the player into the frame), never position alone.
 */
public final class FarPlayerBroadcastService {

    /** R-3's rate bound on client-triggerable full rosters. */
    public static final long FULL_ROSTER_MIN_INTERVAL_MILLIS = 5_000;

    /** Distance-tiered cadence band edge in ABSOLUTE blocks: beyond this the target
     *  updates at half rate; beyond twice it, quarter rate. At the default 2048-block
     *  server cap the tiers are inert by construction (nothing passes the ring beyond
     *  2048) — they bind only when an admin raises the cap. */
    public static final int TIER_HALF_RATE_BLOCKS = 2048;

    /** One online player's per-tick snapshot, built once by the platform adapter.
     *  {@code vehicle} is pre-quantized ({@link FarPlayerWire.Vehicle}); equipment
     *  identity strings are version-neutral registry identities (R-7).
     *
     *  <p>Two separate privacy facts (hide-permission plan §3, 0.21.0):
     *  {@code hidden} is the UNCONDITIONAL ladder (vanish metadata, a disguise, a failed or
     *  raced privacy read) and always withholds the player; {@code holdsHidePermission} is
     *  only the fact that the player holds {@code lss.farplayers.hidden} or
     *  {@code vss.farplayers.hidden}, and withholds the player only while
     *  {@link Settings#hidePermissionHolders} is on. In mode {@code required} the same
     *  fact decides whose own sharing choice still binds (a holder may opt out; nobody
     *  else can), and there "not a holder" counts only once two consecutive passes read
     *  it (see {@link #PERMISSION_CONFIRMING_PASSES}). A platform that could not read the
     *  permission reports {@code hidden=true}, never a guessed permission fact. */
    public record PlayerSnapshot(UUID uuid, String name, String dimension,
                                 double x, double y, double z,
                                 float yaw, float headYaw, float pitch,
                                 byte poseFlags,
                                 double velXPerSecond, double velYPerSecond, double velZPerSecond,
                                 boolean spectator, boolean invisible, boolean alive,
                                 boolean hidden, boolean holdsHidePermission,
                                 long equipmentHash,
                                 String[] equipmentIdentities, int[] equipmentCounts,
                                 FarPlayerWire.Vehicle vehicle) {}

    /** The dedicated send lane (FARP §3.2 — NOT the column send queue): the platform
     *  consults its channel-writability/yield gate and charges the bandwidth governor;
     *  false = withheld this tick (the state is unchanged, next tick retries). */
    @FunctionalInterface
    public interface FrameSender {
        boolean send(UUID viewer, String channel, byte[] body);
    }

    /** Vanish bridge seam, per (viewer, target). Paper feeds the "vanished" metadata read
     *  (SuperVanish/PremiumVanish/EssentialsX) through the snapshot instead and passes null;
     *  Fabric and NeoForge wire the reflective Melius Vanish bridge
     *  ({@code dev.vox.lss.compat.MeliusVanishBridge}, far-player-render-hardening-plan.md
     *  WI-7b — absent mod = visible, present-but-throwing = HIDDEN) since 2026-09-04, and
     *  their snapshots read the lss./vss.farplayers.hidden nodes through the loader seam
     *  (WI-7a) as {@link PlayerSnapshot#holdsHidePermission}, which hides a holder only
     *  while {@link Settings#hidePermissionHolders} is on (0.21.0). */
    @FunctionalInterface
    public interface VanishBridge {
        boolean canSee(UUID viewer, UUID target);
    }

    /** One server chat line: a translation key the 0.21.0 lang files carry, and the
     *  English text every other client shows (an older mod, no mod). The fallback is
     *  the en_us value, word for word, and takes no arguments. */
    public record ChatLine(String translationKey, String fallback) {}

    /** One system chat line to an online player (the two notices below). The platform
     *  resolves the player and sends an ordinary system message as a translatable
     *  component with the fallback, so it reaches every client version (no channel,
     *  capability or session flag is involved). {@code false} = the player cannot be
     *  reached right now; the claim is released and a later pass tries again. */
    @FunctionalInterface
    public interface PlayerNotifier {
        boolean send(UUID player, ChatLine line);
    }

    /** The server mode in which a player's own sharing choice binds only for holders of
     *  the hide permission ({@code far_players.mode: required}, hide-permission plan §2b). */
    public static final String MODE_REQUIRED = "required";

    /** A permission read is acted on in a player-facing way only after this many
     *  CONSECUTIVE broadcast passes agree. Mode {@code required} overrides a player's
     *  own sharing choice, and tells them so, only after that many passes read "not a
     *  holder"; until then their choice binds as a holder's would. One such read is not
     *  evidence: on Folia the pump can read a permissible while its owner's region thread
     *  is rebuilding it (no throw, the node's default {@code false}), and the
     *  Fabric/NeoForge/Sponge seam answers "not a holder" on any doubt. Acting on it
     *  would show an opted-out holder for an interval and send them a false notice,
     *  latched for the connection. The cost is the safe direction: a non-holder who
     *  opted out stays hidden for one extra interval. The same rule guards the holder
     *  notice: one "holder" read never messages a player. */
    static final int PERMISSION_CONFIRMING_PASSES = 2;

    private static final ChatLine REQUIRED_SHARING_NOTICE = new ChatLine(
            "lss.far_players.sharing_required",
            "This server requires position sharing: other players can see where you are"
                    + " from far away, and your Share My Position setting does not apply here.");

    private static final ChatLine HOLDER_NOTICE = new ChatLine(
            "lss.far_players.permission_no_longer_hides",
            "You have the lss.farplayers.hidden permission. Since 0.21.0 it no longer hides"
                    + " you from far players by itself: turn off Share My Position to hide."
                    + " Server owners: set far_players.hide_permission_holders in the server"
                    + " config (true hides holders as before, false keeps this) to stop this"
                    + " message.");

    /** The once-per-connection chat line to a player whose Share My Position opt-out or
     *  share distance mode {@code required} is overriding (project owner's wording,
     *  2026-11-12). A player must never believe they are hidden when they are not.
     *  Unbranded: it names the option by its label, which is the same in both brands. */
    public static ChatLine requiredSharingNotice() {
        return REQUIRED_SHARING_NOTICE;
    }

    /** The once-per-connection chat line to a holder of the hide permission on a server
     *  that has not chosen yet (its settings file predates 0.21.0 and never set
     *  {@code far_players.hide_permission_holders}): the permission used to hide them
     *  and no longer does. Writing the key with either value stops it for everyone. The
     *  node is the literal the server checks (the VSS lang rewrite only touches the
     *  standalone word in capitals, so it survives in both brands). */
    public static ChatLine holderNotice() {
        return HOLDER_NOTICE;
    }

    /** Per-viewer configuration snapshot the platform passes each tick (config is
     *  runtime-mutable via /lsslod reload — R-9 — so it is re-read per tick, the
     *  tick-poll pattern). {@code hidePermissionHolders} is
     *  {@code far_players.hide_permission_holders}: while on, every
     *  {@link PlayerSnapshot#holdsHidePermission} target is withheld (the behaviour
     *  before 0.21.0); off, the permission hides nobody by itself. {@code mode} is one
     *  of {@code off}, {@code opt-in}, {@code on} or {@link #MODE_REQUIRED}.
     *  {@code hidePermissionHoldersUndecided} is true while the loaded settings file
     *  does not set that key at all (a file from before 0.21.0): only then are holders
     *  told in game that the permission no longer hides them ({@link #holderNotice}). */
    public record Settings(String mode, int maxDistanceBlocks, int minDistanceBlocks,
                           boolean sendSpectators, List<String> exclude,
                           int updateIntervalTicks, boolean hidePermissionHolders,
                           boolean hidePermissionHoldersUndecided) {
        /** No settings file to ask (tests, tools): the operator counts as having chosen. */
        public Settings(String mode, int maxDistanceBlocks, int minDistanceBlocks,
                        boolean sendSpectators, List<String> exclude,
                        int updateIntervalTicks, boolean hidePermissionHolders) {
            this(mode, maxDistanceBlocks, minDistanceBlocks, sendSpectators, exclude,
                    updateIntervalTicks, hidePermissionHolders, false);
        }
    }

    private static final class TargetRow {
        int quantX, quantY, quantZ;
        byte yaw, headYaw, pitch, pose;
        long equipmentHash;
        UUID vehicleUuid;
        String vehicleType;
        boolean equipmentEverSent;
        // The last DELIVERED frame carried a nonzero velocity hint (E2 review m7):
        // position deltas gate `changed`, so a decelerating player's final moving
        // frame can park the client extrapolating ~1.5 windows past the true rest
        // point; this forces exactly one zero-velocity rest frame after the stop.
        boolean velWasNonzero;
        // False until a frame carrying this target DELIVERED (commit-on-success):
        // a row created on a withheld tick is blank, and without this flag a target
        // whose real state happens to equal the blank row (exact origin, zero angles)
        // would read "unchanged" forever.
        boolean everSent;
        int tierPhase;
    }

    private static final class ViewerState {
        FarPlayerWire.Prefs prefs;
        int epoch;
        final Map<UUID, Integer> indexByUuid = new HashMap<>();
        int nextIndex;
        boolean clearPending;
        boolean rosterPublished;
        boolean controlFullPending;
        boolean fullRosterPending = true; // subscribe = the first full roster
        long lastFullRosterMillis;
        final Map<UUID, TargetRow> rows = new HashMap<>();
    }

    private final Map<UUID, ViewerState> viewers = new HashMap<>();
    private boolean modeDisabled;
    // Target-privacy prefs retention (service-permission-gate implementation review,
    // 2026-08-27): the TARGET side of the filter reads THIS map, not ViewerState —
    // a viewer removal (the service gate's revocation composite, a NO_CONSUMER
    // re-handshake shed) must never destroy an online player's shareSelf opt-out
    // (the E2 prefs-carrier rule: prefs outrank subscription). Written on every
    // prefs receipt (subscribed or not — a gate-DENIED client still delivers its
    // opt-out), seeded back into a (re)subscribing viewer's state (the grant
    // re-offer resumes serving without a client re-handshake), swept ONLY at
    // {@link #onDisconnect} — the true connection end.
    private final Map<UUID, FarPlayerWire.Prefs> retainedPrefs = new HashMap<>();
    private final VanishBridge vanishBridge;

    // Diag counters (FARP §3.2: far-player bytes get their OWN counters — never folded
    // into service.bytes_sent/wire_bytes, which feed the cross-identity audits).
    private final AtomicLong rosterFramesSent = new AtomicLong();
    private final AtomicLong updateFramesSent = new AtomicLong();
    private final AtomicLong entriesSent = new AtomicLong();
    private final AtomicLong suppressedUnchanged = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();

    // Last-pass privacy counts for the diag line (hide-permission plan §3): written by the
    // owning tick thread, read by the command thread, so volatile. Counted once per ONLINE
    // player per pass (never per viewer×target pair) and zeroed whenever no pass can run.
    private volatile int lastPassHidden;
    private volatile int lastPassPermissionHolders;
    private volatile int lastPassOverridden;
    /** Online players whose kept preference says "do not share my position", session or not
     *  (0.23.0 plan §2). Recounted where the map changes; read by the diag line. */
    private volatile int optedOut;
    // The upgrade notice: ONE warn line per service instance (= per server start), the
    // first time a holder is seen while far_players.hide_permission_holders is off.
    private final AtomicBoolean holderNoticeLogged = new AtomicBoolean();
    private final Consumer<String> notice;
    // The two chat notices: players already told on THIS connection (the map-policy chat
    // line's once-per-connection pattern), one latch each. They live beside retainedPrefs
    // and are swept with it at onDisconnect only, so a same-connection viewer shed, a
    // mode change or a dimension change never repeats a line; a reconnect re-arms it.
    private final Set<UUID> overrideNoticed = new HashSet<>();
    private final Set<UUID> holderNoticed = new HashSet<>();
    private final PlayerNotifier playerNotifier;
    // One WARN per service instance (= per server start), like the upgrade notice above.
    private boolean chatLineFailureWarned;

    /** One player's run of agreeing, clean permission reads. One object per player for
     *  the life of its connection (or until no pass can run): the per-pass update only
     *  writes its three fields. */
    private static final class PermissionRead {
        /** What the current run reads. */
        boolean holder;
        /** Its length in consecutive passes, capped at
         *  {@link #PERMISSION_CONFIRMING_PASSES}; 0 after a failed ({@code hidden}) read. */
        int passes;
        /** The pass of the latest read: a run continues only from the pass before. */
        long lastPass;
    }

    // Beside retainedPrefs: restarted by a different or failed read and by a pass that
    // skipped the player, cleared when no pass can run, removed at onDisconnect.
    private final Map<UUID, PermissionRead> permissionReads = new HashMap<>();
    // Counts the broadcast passes that ran, so "consecutive" means adjacent passes.
    private long passCounter;
    // Consecutive passes in mode "required" (capped): a read under another mode never
    // counts toward overriding a player's choice.
    private int requiredPasses;

    /** No chat lane: for tests and tools that never tell a player anything. Production
     *  cores use the three-argument form (pinned by the wiring contract test). */
    public FarPlayerBroadcastService(VanishBridge vanishBridge) {
        this(vanishBridge, LSSLogger::warn);
    }

    /** {@code notice} receives the once-per-instance upgrade line (tests observe it;
     *  production logs it at WARN). */
    public FarPlayerBroadcastService(VanishBridge vanishBridge, Consumer<String> notice) {
        this(vanishBridge, notice, (player, message) -> false);
    }

    /** {@code playerNotifier} delivers a chat notice to one player. */
    public FarPlayerBroadcastService(VanishBridge vanishBridge, Consumer<String> notice,
                                     PlayerNotifier playerNotifier) {
        this.vanishBridge = vanishBridge == null ? (v, t) -> true : vanishBridge;
        this.notice = Objects.requireNonNull(notice);
        this.playerNotifier = Objects.requireNonNull(playerNotifier);
    }

    // ---- lifecycle (single-threaded, see the class contract) ----

    /** The viewer's session declared CAPABILITY_FAR_PLAYERS. Idempotent. A
     *  RE-subscription (the grant sweep's re-offer after a revocation composite)
     *  seeds the fresh state from the retained prefs, so serving resumes without a
     *  client re-handshake or prefs re-send. */
    public void subscribeViewer(UUID viewer) {
        viewers.computeIfAbsent(viewer, u -> {
            var s = new ViewerState();
            s.prefs = retainedPrefs.get(u);
            return s;
        });
    }

    /** SAME-CONNECTION viewer shed (the service gate's revocation composite, a
     *  NO_CONSUMER/legacy re-handshake): frame delivery stops, but the retained
     *  TARGET prefs survive — the player is still online and their opt-out must
     *  keep being honored. Connection ends call {@link #onDisconnect} instead. */
    public void removeViewer(UUID viewer) {
        viewers.remove(viewer);
        if (viewers.isEmpty()) clearLastPassCounts();
    }

    /** The connection died: subscription AND retained prefs go with it, and both chat
     *  notices are re-armed for the player's next connection. */
    public void onDisconnect(UUID viewer) {
        viewers.remove(viewer);
        retainedPrefs.remove(viewer);
        recountOptedOut();
        overrideNoticed.remove(viewer);
        holderNoticed.remove(viewer);
        permissionReads.remove(viewer);
        if (viewers.isEmpty()) clearLastPassCounts();
    }

    private void recountOptedOut() {
        int count = 0;
        for (var prefs : retainedPrefs.values()) if (!prefs.shareSelf()) count++;
        optedOut = count;
    }

    /** Online players who asked not to be shared (tests/diagnostics). */
    public int optedOut() { return optedOut; }

    /** No broadcast pass can run (no subscriber, or mode off): the last-pass counts
     *  must not linger on the diag line, and a permission read from before the gap must
     *  not count toward a confirmation after it. */
    private void clearLastPassCounts() {
        lastPassHidden = 0;
        lastPassPermissionHolders = 0;
        lastPassOverridden = 0;
        permissionReads.clear();
    }

    /** ANY prefs receipt — changed or byte-identical — queues a full roster (R-3).
     *  The receipt is RETAINED even for an unsubscribed sender (a service-gate-denied
     *  client never registers a viewer, but its shareSelf opt-out must still bind the
     *  target filter); only the viewer-state half needs a live subscription. */
    public void onPrefs(UUID viewer, FarPlayerWire.Prefs prefs) {
        retainedPrefs.put(viewer, prefs);
        recountOptedOut();
        var state = viewers.get(viewer);
        if (state == null) return; // no viewer session (capability bit absent, or shed)
        state.prefs = prefs;
        state.fullRosterPending = true;
    }

    /** The viewer changed dimension: identity survives (v18-rung lifecycle), the roster
     *  does not — epoch bumps on the next send and every row re-sends. */
    public void onViewerDimensionChange(UUID viewer) {
        var state = viewers.get(viewer);
        if (state == null) return;
        state.fullRosterPending = true;
    }

    public boolean isSubscribed(UUID viewer) {
        return viewers.containsKey(viewer);
    }

    public int subscriberCount() {
        return viewers.size();
    }

    public long rosterFramesSent() { return rosterFramesSent.get(); }
    public long updateFramesSent() { return updateFramesSent.get(); }
    public long entriesSent() { return entriesSent.get(); }
    public long suppressedUnchanged() { return suppressedUnchanged.get(); }
    public long bytesSent() { return bytesSent.get(); }

    /** Online players the last broadcast pass withheld for privacy: the unconditional
     *  ladder ({@link PlayerSnapshot#hidden}) or the hide permission while
     *  {@code far_players.hide_permission_holders} is on. */
    public int lastPassHidden() { return lastPassHidden; }

    /** Hide-permission holders the last broadcast pass saw while
     *  {@code far_players.hide_permission_holders} was off (the permission did not hide
     *  them); always 0 while the setting is on. */
    public int lastPassPermissionHolders() { return lastPassPermissionHolders; }

    /** Players whose own Share My Position opt-out or share distance mode
     *  {@code required} overrode in the last broadcast pass and who could be shown to
     *  someone (the players the notice is for); always 0 in other modes. */
    public int lastPassOverridden() { return lastPassOverridden; }

    /** Whether {@code player} was already sent the required-mode notice on this
     *  connection (tests/diagnostics). */
    public boolean hasRequiredSharingNotice(UUID player) {
        return overrideNoticed.contains(player);
    }

    /** Whether {@code player} was already sent the holder notice on this connection
     *  (tests/diagnostics). */
    public boolean hasHolderNotice(UUID player) {
        return holderNoticed.contains(player);
    }

    /** The /lsslod diag line (rendered only while subscribers exist or counters moved —
     *  the conditional-slot convention). {@code opted_out=}, {@code hidden=},
     *  {@code perm_holders=} and {@code overridden=} are appended only when non-zero, so the
     *  line is unchanged on a server with none of them. */
    public String diagLine() {
        var line = new StringBuilder("FarPlayers: subs=").append(viewers.size())
                .append(", rosters=").append(rosterFramesSent.get())
                .append(", updates=").append(updateFramesSent.get())
                .append(", entries=").append(entriesSent.get())
                .append(", suppressed=").append(suppressedUnchanged.get())
                .append(", bytes=").append(bytesSent.get());
        // A standing count first, then the last pass's counts (their tests pin the line's end).
        int declined = optedOut;
        if (declined > 0) line.append(", opted_out=").append(declined);
        int hidden = lastPassHidden;
        int holders = lastPassPermissionHolders;
        int overridden = lastPassOverridden;
        if (hidden > 0) line.append(", hidden=").append(hidden);
        if (holders > 0) line.append(", perm_holders=").append(holders);
        if (overridden > 0) line.append(", overridden=").append(overridden);
        return line.toString();
    }

    /** Server-owned mode transition/control drain, called even between broadcast ticks.
     *  Unlike client prefs, server withdrawals bypass the full-roster rate limit.
     *  No world/player snapshots are needed while disabled. */
    public boolean applyMode(String mode, FrameSender sender) {
        boolean disabled = "off".equals(mode);
        if (disabled != modeDisabled) {
            modeDisabled = disabled;
            for (var state : viewers.values()) {
                state.clearPending = disabled && state.rosterPublished;
                state.fullRosterPending = true;
                state.controlFullPending = true;
            }
        }
        if (!disabled) return true;
        clearLastPassCounts();
        for (var entry : viewers.entrySet()) {
            var state = entry.getValue();
            if (!state.clearPending) continue;
            int nextEpoch = state.epoch + 1;
            byte[] frame = FarPlayerWire.encodeRoster(
                    new FarPlayerWire.Roster(nextEpoch, true, List.of(), new int[0]));
            if (!sender.send(entry.getKey(), LSSConstants.CHANNEL_FAR_PLAYER_ROSTER, frame)) continue;
            state.epoch = nextEpoch;
            state.clearPending = false;
            state.indexByUuid.clear();
            state.rows.clear();
            state.nextIndex = 0;
            state.rosterPublished = true;
            rosterFramesSent.incrementAndGet();
            bytesSent.addAndGet(frame.length);
        }
        return false;
    }

    // ---- the broadcast tick ----

    /**
     * One broadcast pass. The platform calls this every {@code updateIntervalTicks}
     * server ticks while armed. It also calls {@link #applyMode} every tick so an OFF
     * transition can withdraw rosters without expensive world snapshots.
     *
     * @param nowMillis   wall clock (injected for the rate-bound tests)
     * @param online      every online player's snapshot, built once (O(P))
     * @param settings    the tick's config snapshot
     * @param sender      the dedicated send lane
     */
    public void tick(long nowMillis, List<PlayerSnapshot> online, Settings settings,
                     FrameSender sender) {
        if (!applyMode(settings.mode(), sender)) return; // off: counts already cleared
        if (viewers.isEmpty()) {
            clearLastPassCounts();
            return;
        }
        Map<UUID, PlayerSnapshot> byUuid = new HashMap<>(online.size());
        boolean hideHolders = settings.hidePermissionHolders();
        boolean required = MODE_REQUIRED.equals(settings.mode());
        passCounter++;
        requiredPasses = required ? Math.min(requiredPasses + 1, PERMISSION_CONFIRMING_PASSES) : 0;
        boolean tellHolders = !hideHolders && settings.hidePermissionHoldersUndecided();
        int hidden = 0;
        int holders = 0;
        int overridden = 0;
        for (var s : online) {
            byUuid.put(s.uuid(), s);
            notePermissionRead(s);
            if (withheldForPrivacy(s, hideHolders)) hidden++;
            if (s.holdsHidePermission() && !hideHolders) {
                holders++;
                noteHolderNotHidden(s);
            }
            // The server has not chosen yet (and so shows holders): tell each holder it
            // shows, in game, once per connection.
            if (tellHolders && holderShownDespitePermission(s, settings)) {
                tellOnce(holderNoticed, s, HOLDER_NOTICE);
            }
            // Once per ONLINE player per pass, where the target's own preferences are
            // evaluated: covers preferences arriving under "required", the mode switching
            // to it at runtime, and a player losing the permission while opted out.
            if (required && ownChoiceOverridden(s, settings)) {
                overridden++;
                tellOnce(overrideNoticed, s, REQUIRED_SHARING_NOTICE);
            }
        }
        lastPassHidden = hidden;
        lastPassPermissionHolders = holders;
        lastPassOverridden = overridden;
        for (var entry : viewers.entrySet()) {
            var viewerUuid = entry.getKey();
            var state = entry.getValue();
            var viewerSnap = byUuid.get(viewerUuid);
            if (viewerSnap == null) continue; // not in this tick's world (join/quit edge)
            if (state.prefs == null || !state.prefs.enabled()) continue;
            tickViewer(nowMillis, viewerUuid, state, viewerSnap, online, settings, sender);
        }
    }

    private void tickViewer(long nowMillis, UUID viewerUuid, ViewerState state,
                            PlayerSnapshot viewer, List<PlayerSnapshot> online,
                            Settings settings, FrameSender sender) {
        // Visible set under the dimension, visibility, range and privacy filter ladder.
        var visible = new ArrayList<PlayerSnapshot>();
        for (var target : online) {
            if (isVisible(viewerUuid, state, viewer, target, settings)) {
                visible.add(target);
            }
        }
        // Encode-side bound (review MAJOR): the decode caps reject oversize frames
        // WHOLESALE, so the served set is capped nearest-first (the want-set idiom) —
        // beyond-cap players simply wait for churn, never break the session.
        if (visible.size() > FarPlayerWire.MAX_UPDATE_ENTRIES) {
            visible.sort(java.util.Comparator.comparingDouble(t -> {
                double dx = t.x() - viewer.x();
                double dz = t.z() - viewer.z();
                return dx * dx + dz * dz;
            }));
            visible.subList(FarPlayerWire.MAX_UPDATE_ENTRIES, visible.size()).clear();
        }

        // Roster maintenance. A pending full roster (subscribe/prefs/dim change) sends
        // at the rate bound; membership diffs are incremental frames otherwise.
        boolean fullSent = false;
        if (state.fullRosterPending
                && (state.controlFullPending
                    || nowMillis - state.lastFullRosterMillis >= FULL_ROSTER_MIN_INTERVAL_MILLIS)) {
            state.epoch++;
            state.indexByUuid.clear();
            state.nextIndex = 0;
            state.rows.clear();
            var added = new ArrayList<FarPlayerWire.RosterEntry>(visible.size());
            for (var t : visible) {
                int idx = state.nextIndex++;
                state.indexByUuid.put(t.uuid(), idx);
                added.add(new FarPlayerWire.RosterEntry(idx, t.uuid(), t.name()));
            }
            byte[] frame = FarPlayerWire.encodeRoster(
                    new FarPlayerWire.Roster(state.epoch, true, added, new int[0]));
            if (!sender.send(viewerUuid, LSSConstants.CHANNEL_FAR_PLAYER_ROSTER, frame)) {
                // Withheld (yield gate) — the pending flag survives, next tick retries.
                state.epoch--; // the epoch the client never saw must not burn
                state.indexByUuid.clear();
                state.nextIndex = 0;
                return;
            }
            state.fullRosterPending = false;
            state.controlFullPending = false;
            state.lastFullRosterMillis = nowMillis;
            state.rosterPublished = true;
            rosterFramesSent.incrementAndGet();
            bytesSent.addAndGet(frame.length);
            fullSent = true;
        } else {
            // Incremental membership diff. NOTHING mutates until the frame is accepted
            // (stage-E1 review MAJOR: removals applied before a withheld send were lost
            // forever — the departed identity was already gone from indexByUuid, so no
            // later diff could re-emit it and the client kept a ghost roster entry).
            var added = new ArrayList<FarPlayerWire.RosterEntry>();
            int nextIdx = state.nextIndex;
            for (var t : visible) {
                if (!state.indexByUuid.containsKey(t.uuid())) {
                    added.add(new FarPlayerWire.RosterEntry(nextIdx++, t.uuid(), t.name()));
                }
            }
            var removedUuids = new ArrayList<UUID>();
            var removed = new ArrayList<Integer>();
            var visibleUuids = new java.util.HashSet<UUID>(visible.size());
            for (var t : visible) visibleUuids.add(t.uuid());
            for (var e : state.indexByUuid.entrySet()) {
                if (!visibleUuids.contains(e.getKey())) {
                    removedUuids.add(e.getKey());
                    removed.add(e.getValue());
                }
            }
            if (!added.isEmpty() || !removed.isEmpty()) {
                // Encode-side bound (review MAJOR): a frame that exceeds the decode caps
                // is rejected WHOLESALE by the client — cap adds per frame; the overflow
                // re-diffs next tick (removals are ints, far under any practical bound).
                var addsThisFrame = added.size() > FarPlayerWire.MAX_ROSTER_ENTRIES
                        ? added.subList(0, FarPlayerWire.MAX_ROSTER_ENTRIES) : added;
                int[] removedIdx = removed.stream().mapToInt(Integer::intValue).toArray();
                byte[] frame = FarPlayerWire.encodeRoster(
                        new FarPlayerWire.Roster(state.epoch, false,
                                new ArrayList<>(addsThisFrame), removedIdx));
                if (!sender.send(viewerUuid, LSSConstants.CHANNEL_FAR_PLAYER_ROSTER, frame)) {
                    return; // withheld: nothing was mutated, next tick re-diffs
                }
                for (var a : addsThisFrame) {
                    state.indexByUuid.put(a.uuid(), a.index());
                }
                state.nextIndex += addsThisFrame.size();
                for (var gone : removedUuids) {
                    state.indexByUuid.remove(gone);
                    state.rows.remove(gone);
                }
                state.rosterPublished = true;
                rosterFramesSent.incrementAndGet();
                bytesSent.addAndGet(frame.length);
            }
        }

        // Updates with tiering + whole-snapshot delta suppression.
        var entries = new ArrayList<FarPlayerWire.UpdateEntry>();
        var rowCommits = new ArrayList<Runnable>();
        for (var t : visible) {
            var row = state.rows.get(t.uuid());
            if (row == null) {
                row = new TargetRow();
                state.rows.put(t.uuid(), row);
            }
            boolean firstSend = !row.everSent;
            int qx = FarPlayerWire.quantizePos(t.x());
            int qy = FarPlayerWire.quantizePos(t.y());
            int qz = FarPlayerWire.quantizePos(t.z());
            byte yaw = FarPlayerWire.angleToByte(t.yaw());
            byte headYaw = FarPlayerWire.angleToByte(t.headYaw());
            byte pitch = FarPlayerWire.angleToByte(t.pitch());
            UUID vehicleUuid = t.vehicle() == null ? null : t.vehicle().uuid();
            String vehicleType = t.vehicle() == null ? null : t.vehicle().typeIdentity();

            boolean velNowZero = FarPlayerWire.velocityToShort(t.velXPerSecond()) == 0
                    && FarPlayerWire.velocityToShort(t.velYPerSecond()) == 0
                    && FarPlayerWire.velocityToShort(t.velZPerSecond()) == 0;
            boolean changed = firstSend
                    || row.quantX != qx || row.quantY != qy || row.quantZ != qz
                    || row.yaw != yaw || row.headYaw != headYaw || row.pitch != pitch
                    || row.pose != t.poseFlags()
                    || row.equipmentHash != t.equipmentHash()
                    || !java.util.Objects.equals(row.vehicleUuid, vehicleUuid)
                    || !java.util.Objects.equals(row.vehicleType, vehicleType)
                    // The one-shot rest frame (m7): a stop after motion ships once so
                    // the client stops dead-reckoning at the parked position.
                    || (row.velWasNonzero && velNowZero);
            if (!changed) {
                suppressedUnchanged.incrementAndGet();
                continue;
            }
            // Distance tier: far targets update at half/quarter cadence — but a
            // MOUNT/DISMOUNT or equipment change always forces through (R-10).
            boolean forced = firstSend
                    || !java.util.Objects.equals(row.vehicleUuid, vehicleUuid)
                    || !java.util.Objects.equals(row.vehicleType, vehicleType)
                    || row.equipmentHash != t.equipmentHash();
            int divisor = tierDivisor(viewer, t);
            row.tierPhase++;
            if (!forced && divisor > 1 && (row.tierPhase % divisor) != 0) {
                continue; // held to the tier cadence; state row NOT updated — the move
                          // accumulates and ships on the tier boundary
            }

            boolean equipmentDue = t.equipmentIdentities() != null
                    && (!row.equipmentEverSent || row.equipmentHash != t.equipmentHash());
            Integer rosterIndex = state.indexByUuid.get(t.uuid());
            if (rosterIndex == null) {
                // Visible but not (yet) indexed — reachable only if the incremental
                // adds cap ever drops below the visible cap (today both are 1024, so
                // this is armor, not a path); an unboxed get would NPE the tick.
                continue;
            }
            entries.add(new FarPlayerWire.UpdateEntry(
                    rosterIndex,
                    qx, qy, qz, yaw, headYaw, pitch, t.poseFlags(),
                    FarPlayerWire.velocityToShort(t.velXPerSecond()),
                    FarPlayerWire.velocityToShort(t.velYPerSecond()),
                    FarPlayerWire.velocityToShort(t.velZPerSecond()),
                    equipmentDue ? new int[FarPlayerWire.EQUIPMENT_SLOTS] : null,
                    equipmentDue ? t.equipmentCounts() : null,
                    equipmentDue ? t.equipmentIdentities() : null,
                    t.vehicle()));
            // Row content commits ON SEND SUCCESS only (review: a withheld frame that
            // carried a target's FIRST entry left the client with an identity and no
            // position forever if the target stayed still — advance-before-send made
            // the loss permanent for stationary targets).
            final var rowRef = row;
            final boolean equipDue = equipmentDue;
            final byte pose = t.poseFlags();
            final long equipHash = t.equipmentHash();
            final boolean velNonzeroSent = !velNowZero;
            rowCommits.add(() -> {
                rowRef.everSent = true;
                rowRef.velWasNonzero = velNonzeroSent;
                rowRef.quantX = qx;
                rowRef.quantY = qy;
                rowRef.quantZ = qz;
                rowRef.yaw = yaw;
                rowRef.headYaw = headYaw;
                rowRef.pitch = pitch;
                rowRef.pose = pose;
                rowRef.vehicleUuid = vehicleUuid;
                rowRef.vehicleType = vehicleType;
                if (equipDue) {
                    rowRef.equipmentEverSent = true;
                }
                rowRef.equipmentHash = equipHash;
            });
        }
        if (entries.isEmpty() && !fullSent) return;
        // Chunk under the per-payload dictionary bound (review MAJOR: the first frame
        // after a full roster carries EVERY visible player's equipment — a populated
        // hub can exceed MAX_DICT_ENTRIES, and encode THROWS on the server tick).
        int from = 0;
        while (from < entries.size()) {
            int to = from;
            var dict = new java.util.HashSet<String>();
            while (to < entries.size()) {
                var e = entries.get(to);
                int newIds = 0;
                if (e.equipmentIdentities() != null) {
                    for (String id : e.equipmentIdentities()) {
                        if (id != null && !dict.contains(id)) newIds++;
                    }
                }
                if (e.vehicle() != null && !dict.contains(e.vehicle().typeIdentity())) newIds++;
                if (dict.size() + newIds > FarPlayerWire.MAX_DICT_ENTRIES && to > from) break;
                if (e.equipmentIdentities() != null) {
                    for (String id : e.equipmentIdentities()) {
                        if (id != null) dict.add(id);
                    }
                }
                if (e.vehicle() != null) dict.add(e.vehicle().typeIdentity());
                to++;
            }
            byte[] frame = FarPlayerWire.encodeUpdates(new FarPlayerWire.Updates(
                    state.epoch, viewer.dimension(), settings.updateIntervalTicks(),
                    new ArrayList<>(entries.subList(from, to))));
            if (!sender.send(viewerUuid, LSSConstants.CHANNEL_FAR_PLAYER_UPDATES, frame)) {
                return; // withheld: un-sent chunks' rows stay uncommitted, next tick re-sends
            }
            updateFramesSent.incrementAndGet();
            entriesSent.addAndGet(to - from);
            bytesSent.addAndGet(frame.length);
            for (int i = from; i < to; i++) {
                rowCommits.get(i).run();
            }
            from = to;
        }
    }

    /** The filter ladder, including server visibility and client sharing privacy. */
    private boolean isVisible(UUID viewerUuid, ViewerState state, PlayerSnapshot viewer,
                              PlayerSnapshot target, Settings settings) {
        if (target.uuid().equals(viewerUuid)) return false;
        if (!target.dimension().equals(viewer.dimension())) return false;
        if (!target.alive()) return false;
        if (target.spectator() && !settings.sendSpectators()) return false;
        if (target.invisible()) return false;
        if (!vanishBridge.canSee(viewerUuid, target.uuid())) return false;

        // Ring: client prefs intersect the server cap.
        double dx = target.x() - viewer.x();
        double dz = target.z() - viewer.z();
        double distSq = dx * dx + dz * dz;
        int max = settings.maxDistanceBlocks();
        var prefs = state.prefs;
        if (prefs != null && prefs.maxDistanceBlocks() > 0) {
            max = Math.min(max, prefs.maxDistanceBlocks());
        }
        int min = settings.minDistanceBlocks();
        if (prefs != null) {
            min = Math.max(min, prefs.minDistanceBlocks());
        }
        if (distSq > (double) max * max) return false;
        if (min > 0 && distSq < (double) min * min) return false;

        // Server-authoritative privacy (the ESP-oracle fix) — enforced HERE, not just
        // declared (stage-E1 review MAJOR). The ladder (vanish, disguise, a failed read)
        // always withholds; the hide permission withholds only while
        // far_players.hide_permission_holders is on (hide-permission plan §2).
        if (withheldForPrivacy(target, settings.hidePermissionHolders())) return false;
        if (isExcluded(target, settings.exclude())) return false;
        // The TARGET side reads the retained map — an opt-out survives viewer sheds
        // (gate revocation, NO_CONSUMER downgrade) for as long as the player is online.
        var targetPrefs = retainedPrefs.get(target.uuid());
        // Mode "required" (hide-permission plan §2b): the target's OWN sharing choices
        // (opt-out, share distance) bind for a holder of the hide permission, and for
        // anyone not yet CONFIRMED as "not a holder" (two consecutive reads). Every
        // other mode honors them for everyone, exactly as before. Nothing above this
        // line is affected: "required" overrides only the target's own choice.
        boolean ownChoiceBinds = !MODE_REQUIRED.equals(settings.mode())
                || !confirmedNonHolder(target);
        if ("opt-in".equals(settings.mode())) {
            // Only targets whose own client said shareSelf=true are served at all.
            if (targetPrefs == null || !targetPrefs.shareSelf()) return false;
        } else if (ownChoiceBinds && targetPrefs != null && !targetPrefs.shareSelf()) {
            // Mode "on" (and a holder in "required"): an EXPLICIT opt-out is honored.
            return false;
        }
        if (ownChoiceBinds && targetPrefs != null && targetPrefs.shareDistanceBlocks() > 0
                && distSq > (double) targetPrefs.shareDistanceBlocks()
                        * targetPrefs.shareDistanceBlocks()) {
            return false;
        }
        return true;
    }

    /** The one privacy decision the filter ladder and the diag count share: the
     *  unconditional ladder, OR the hide permission while the server setting is on. */
    private static boolean withheldForPrivacy(PlayerSnapshot target, boolean hidePermissionHolders) {
        return target.hidden() || (hidePermissionHolders && target.holdsHidePermission());
    }

    /** The upgrade notice (hide-permission plan §2): servers that granted the node on
     *  purpose see those players again after updating, and that must not be silent. One
     *  line per service instance; the latch read is the only per-tick cost. */
    private void noteHolderNotHidden(PlayerSnapshot holder) {
        if (holderNoticeLogged.get() || !holderNoticeLogged.compareAndSet(false, true)) return;
        notice.accept("Far players: " + holder.name() + " holds the "
                + LSSPermissions.FARPLAYERS_HIDDEN_LSS + " (or "
                + LSSPermissions.FARPLAYERS_HIDDEN_VSS + ") permission."
                + " Since 0.21.0 that permission no longer hides a player by itself; players"
                + " hide themselves with Share My Position. Set"
                + " far_players.hide_permission_holders: true in the server settings to hide"
                + " every holder as before, or list players in far_players.excluded_players"
                + " to hide them whatever their permissions.");
    }

    /** Whether a holder is shown (or would be, to a viewer in range) although they hold
     *  the hide permission: a CONFIRMED holder (two consecutive reads; a failed read is
     *  no read) whom neither {@code excluded_players} nor their own Share My Position
     *  choice hides. Callers check that the setting is off and the server undecided. */
    private boolean holderShownDespitePermission(PlayerSnapshot holder, Settings settings) {
        if (!confirmedRead(holder, true)) return false;
        if (isExcluded(holder, settings.exclude())) return false;
        var prefs = retainedPrefs.get(holder.uuid());
        if ("opt-in".equals(settings.mode())) return prefs != null && prefs.shareSelf();
        return prefs == null || prefs.shareSelf();
    }

    /** Records this pass's permission read. A failed read ({@code hidden}: on
     *  Paper/Folia a raced read carries no permission fact) ends the run; a clean read
     *  extends it only when the previous pass read the same, so a different read or a
     *  pass that skipped the player starts it again. */
    private void notePermissionRead(PlayerSnapshot target) {
        var read = permissionReads.get(target.uuid());
        if (target.hidden()) {
            if (read != null) read.passes = 0;
            return;
        }
        if (read == null) {
            read = new PermissionRead();
            permissionReads.put(target.uuid(), read);
        }
        boolean holder = target.holdsHidePermission();
        boolean continues = read.passes > 0 && read.holder == holder
                && read.lastPass == passCounter - 1;
        read.passes = continues ? Math.min(read.passes + 1, PERMISSION_CONFIRMING_PASSES) : 1;
        read.holder = holder;
        read.lastPass = passCounter;
    }

    /** Whether {@code target} read {@code holder} cleanly in this pass AND in the pass
     *  before it ({@link #PERMISSION_CONFIRMING_PASSES}). */
    private boolean confirmedRead(PlayerSnapshot target, boolean holder) {
        if (target.hidden() || target.holdsHidePermission() != holder) return false;
        var read = permissionReads.get(target.uuid());
        return read != null && read.holder == holder && read.lastPass == passCounter
                && read.passes >= PERMISSION_CONFIRMING_PASSES;
    }

    /** Whether mode {@code required} may treat {@code target} as "not a holder": two
     *  consecutive passes, both in mode {@code required}, read it so. Anything less
     *  binds like a holder. */
    private boolean confirmedNonHolder(PlayerSnapshot target) {
        return requiredPasses >= PERMISSION_CONFIRMING_PASSES && confirmedRead(target, false);
    }

    /** Whether mode {@code required} is overriding this player's own sharing choice AND
     *  the player can be shown to someone, so that "other players can see where you
     *  are" is true when it is said: a confirmed NON-holder who opted out or set a share
     *  distance, whom no unconditional rung withholds (not ladder-hidden, alive, not
     *  invisible, not a spectator the server does not send, not excluded), and whom the
     *  vanish bridge lets at least one other receiving viewer see. Dimension and range
     *  are deliberately not part of it: they change as players move, and the player
     *  should know before someone comes into range. Callers check the mode. */
    private boolean ownChoiceOverridden(PlayerSnapshot target, Settings settings) {
        var prefs = retainedPrefs.get(target.uuid());
        if (prefs == null || (prefs.shareSelf() && prefs.shareDistanceBlocks() <= 0)) return false;
        if (!confirmedNonHolder(target)) return false; // also: a holder, a failed read
        if (!target.alive() || target.invisible()) return false;
        if (target.spectator() && !settings.sendSpectators()) return false;
        if (isExcluded(target, settings.exclude())) return false;
        return someViewerMaySee(target.uuid());
    }

    /** The vanish bridge is per (viewer, target): true when some OTHER viewer that
     *  receives far players passes it for {@code target}. A player the bridge hides
     *  from everyone (the only vanish source on Fabric, NeoForge and Sponge), or one
     *  with nobody else receiving far players, is shown to nobody. */
    private boolean someViewerMaySee(UUID target) {
        for (var entry : viewers.entrySet()) {
            if (entry.getKey().equals(target)) continue;
            var prefs = entry.getValue().prefs;
            if (prefs == null || !prefs.enabled()) continue;
            if (vanishBridge.canSee(entry.getKey(), target)) return true;
        }
        return false;
    }

    /** One chat line per CONNECTION to a player ({@code latch} holds who was told): the
     *  honest notice of mode {@code required} (hide-permission plan §2b) and the holder
     *  notice. Claimed before the send, like the map-policy line: a send that throws is
     *  contained and never retried (it may have delivered, and a retry every pass would
     *  spam the player); a player who cannot be reached right now releases the claim, so
     *  a later pass tries again. */
    private void tellOnce(Set<UUID> latch, PlayerSnapshot target, ChatLine line) {
        UUID uuid = target.uuid();
        if (!latch.add(uuid)) return;
        try {
            if (!playerNotifier.send(uuid, line)) latch.remove(uuid);
        } catch (RuntimeException e) {
            if (!chatLineFailureWarned) {
                chatLineFailureWarned = true;
                LSSLogger.warn("Far-player chat notice to " + target.name()
                        + " failed (contained; one warn per server start): " + e);
            }
        }
    }

    private static boolean isExcluded(PlayerSnapshot target, List<String> exclude) {
        if (exclude == null || exclude.isEmpty()) return false;
        String uuid = target.uuid().toString();
        for (String e : exclude) {
            if (e == null) continue;
            if (e.equalsIgnoreCase(target.name()) || e.equalsIgnoreCase(uuid)) return true;
        }
        return false;
    }

    /** Distance-tiered cadence divisor: 1 inside the near band, 2 beyond
     *  {@link #TIER_HALF_RATE_BLOCKS}, 4 beyond twice it. */
    private static int tierDivisor(PlayerSnapshot viewer, PlayerSnapshot target) {
        double dx = target.x() - viewer.x();
        double dz = target.z() - viewer.z();
        double distSq = dx * dx + dz * dz;
        double half = TIER_HALF_RATE_BLOCKS;
        if (distSq > 4 * half * half) return 4;
        if (distSq > half * half) return 2;
        return 1;
    }
}
