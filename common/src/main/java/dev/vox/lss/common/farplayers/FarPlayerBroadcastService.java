package dev.vox.lss.common.farplayers;

import dev.vox.lss.common.LSSConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The far-player broadcast core (v0.11.0 stage E1 — far-player-proxies-plan.md §3.2 as
 * amended by R-3/R-10). Platform-neutral: the owning service calls {@link #tick} from
 * its existing tick (Fabric server thread / Paper GlobalRegionScheduler pump — Folia-
 * safe by construction) with the per-tick ONLINE snapshots built ONCE (the inversion of
 * SeeU's per-viewer×per-target snapshot construction), and every side effect rides the
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
     *  identity strings are version-neutral registry identities (R-7). */
    public record PlayerSnapshot(UUID uuid, String name, String dimension,
                                 double x, double y, double z,
                                 float yaw, float headYaw, float pitch,
                                 byte poseFlags,
                                 double velXPerSecond, double velYPerSecond, double velZPerSecond,
                                 boolean spectator, boolean invisible, boolean alive,
                                 boolean hidden,
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
     *  (WI-7a), so the exclude LIST is no longer the only lever there. */
    @FunctionalInterface
    public interface VanishBridge {
        boolean canSee(UUID viewer, UUID target);
    }

    /** Per-viewer configuration snapshot the platform passes each tick (config is
     *  runtime-mutable via /lsslod set — R-9 — so it is re-read per tick, the
     *  tick-poll pattern). */
    public record Settings(String mode, int maxDistanceBlocks, int minDistanceBlocks,
                           boolean sendSpectators, List<String> exclude,
                           int updateIntervalTicks) {}

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
        boolean fullRosterPending = true; // subscribe = the first full roster
        long lastFullRosterMillis;
        final Map<UUID, TargetRow> rows = new HashMap<>();
    }

    private final Map<UUID, ViewerState> viewers = new HashMap<>();
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

    public FarPlayerBroadcastService(VanishBridge vanishBridge) {
        this.vanishBridge = vanishBridge == null ? (v, t) -> true : vanishBridge;
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
    }

    /** The connection died: subscription AND retained prefs go with it. */
    public void onDisconnect(UUID viewer) {
        viewers.remove(viewer);
        retainedPrefs.remove(viewer);
    }

    /** ANY prefs receipt — changed or byte-identical — queues a full roster (R-3).
     *  The receipt is RETAINED even for an unsubscribed sender (a service-gate-denied
     *  client never registers a viewer, but its shareSelf opt-out must still bind the
     *  target filter); only the viewer-state half needs a live subscription. */
    public void onPrefs(UUID viewer, FarPlayerWire.Prefs prefs) {
        retainedPrefs.put(viewer, prefs);
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

    /** The /lsslod diag line (rendered only while subscribers exist or counters moved —
     *  the conditional-slot convention). */
    public String diagLine() {
        return "FarPlayers: subs=" + viewers.size()
                + ", rosters=" + rosterFramesSent.get()
                + ", updates=" + updateFramesSent.get()
                + ", entries=" + entriesSent.get()
                + ", suppressed=" + suppressedUnchanged.get()
                + ", bytes=" + bytesSent.get();
    }

    // ---- the broadcast tick ----

    /**
     * One broadcast pass. The platform calls this every {@code updateIntervalTicks}
     * server ticks while {@code settings.mode() != "off"} (an "off" mode simply stops
     * the calls — subscriptions and prefs survive a runtime mode flip).
     *
     * @param nowMillis   wall clock (injected for the rate-bound tests)
     * @param online      every online player's snapshot, built once (O(P))
     * @param settings    the tick's config snapshot
     * @param sender      the dedicated send lane
     */
    public void tick(long nowMillis, List<PlayerSnapshot> online, Settings settings,
                     FrameSender sender) {
        if (viewers.isEmpty() || "off".equals(settings.mode())) return;
        Map<UUID, PlayerSnapshot> byUuid = new HashMap<>(online.size());
        for (var s : online) {
            byUuid.put(s.uuid(), s);
        }
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
        // Visible set under the filter ladder (SeeU's proven order + LSS privacy).
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
                && nowMillis - state.lastFullRosterMillis >= FULL_ROSTER_MIN_INTERVAL_MILLIS) {
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
            state.lastFullRosterMillis = nowMillis;
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

    /** The filter ladder, in SeeU's proven order plus the LSS privacy additions. */
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

        // Server-authoritative privacy (the ESP-oracle fix). hidden = the platform's
        // permission lever (Paper: lss.farplayers.hidden) — enforced HERE, not just
        // declared (stage-E1 review MAJOR).
        if (target.hidden()) return false;
        if (isExcluded(target, settings.exclude())) return false;
        // The TARGET side reads the retained map — an opt-out survives viewer sheds
        // (gate revocation, NO_CONSUMER downgrade) for as long as the player is online.
        var targetPrefs = retainedPrefs.get(target.uuid());
        if ("opt-in".equals(settings.mode())) {
            // Only targets whose own client said shareSelf=true are served at all.
            if (targetPrefs == null || !targetPrefs.shareSelf()) return false;
        } else if (targetPrefs != null && !targetPrefs.shareSelf()) {
            // Mode "on": a target that EXPLICITLY opted out is honored.
            return false;
        }
        if (targetPrefs != null && targetPrefs.shareDistanceBlocks() > 0
                && distSq > (double) targetPrefs.shareDistanceBlocks()
                        * targetPrefs.shareDistanceBlocks()) {
            return false;
        }
        return true;
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
