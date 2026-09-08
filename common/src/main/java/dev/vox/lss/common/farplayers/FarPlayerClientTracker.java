package dev.vox.lss.common.farplayers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The client-side far-player tracker (E1, FARP §3.3 phase A — tracked state only, no
 * rendering until E2). Mirrors SeeU's generational latest-wins map plus the roster
 * layer: identities arrive once per epoch (index ↔ UUID+name), updates address them by
 * index, and an update stamped with an epoch this tracker has not seen is DROPPED — the
 * misbinding armor (a stale in-flight updates frame crossing a roster rebuild must
 * never bind old indices to new identities).
 *
 * <p>R-5 (decided at E1, recorded in the progress doc): the tracker is owned OUTSIDE
 * the request-manager lifecycle — a mid-session SessionConfig re-push (stage C's
 * `/lsslod set`) retires and rebuilds the manager, but the SERVER's subscription state
 * survives untouched, so the tracker must too (rebuild-and-resubscribe would turn one
 * `set lodDistanceChunks` on an N-player server into N roster floods). It dies at
 * disconnect and at the R-3 reset re-subscribe.
 *
 * <p>Single-threaded contract: the client network thread hands frames to the main
 * client thread before applying (the platform glue's job), matching every other
 * client-side state owner.
 */
public final class FarPlayerClientTracker {

    /** One tracked far player: identity + the latest update + the E2 motion
     *  interpolator (declared-cadence lerp + velocity extrapolation — see
     *  {@link FarPlayerMotion}). */
    public record TrackedFarPlayer(UUID uuid, String name,
                                   FarPlayerWire.UpdateEntry latest,
                                   int cadenceTicks, long receivedAtMillis,
                                   FarPlayerMotion motion,
                                   String[] equipmentIdentities, int[] equipmentCounts) {}

    private final Map<Integer, UUID> uuidByIndex = new HashMap<>();
    private final Map<UUID, Integer> indexByUuid = new HashMap<>();
    private final Map<UUID, String> nameByUuid = new HashMap<>();
    private final Map<UUID, TrackedFarPlayer> tracked = new HashMap<>();
    private int epoch = -1; // -1 = no roster seen; epoch 0 is never valid (server starts at 1)
    private String dimension;

    private long rostersApplied;
    private long updatesApplied;
    private long entriesApplied;
    private long droppedWrongEpoch;
    private long identityCapResets;

    /** Hostile/buggy-server armor (review m5): each frame is bounded (decode caps), but
     *  incremental adds accumulate across frames — a server that never removes could
     *  grow the maps without bound. Past this, the tracker clears and waits for the
     *  next full roster (self-healing: the server's roster is epoch'd). 4x the wire's
     *  per-roster bound; a legitimate hub never approaches it (the serve set caps at
     *  MAX_UPDATE_ENTRIES). */
    static final int MAX_TRACKED_IDENTITIES = 4096;

    /** Applies a roster frame. A full roster REPLACES everything at the new epoch;
     *  an incremental frame must match the current epoch or it is dropped. */
    public void onRoster(FarPlayerWire.Roster roster) {
        if (roster.full()) {
            uuidByIndex.clear();
            indexByUuid.clear();
            nameByUuid.clear();
            tracked.clear();
            epoch = roster.epoch();
        } else if (roster.epoch() != epoch) {
            droppedWrongEpoch++;
            return;
        }
        for (var e : roster.added()) {
            // A peer may rebind an occupied index or alias a UUID. Keep one live
            // binding per identity; old motion/equipment cannot outlive its binding.
            Integer previousIndex = indexByUuid.put(e.uuid(), e.index());
            if (previousIndex != null && previousIndex != e.index()) {
                uuidByIndex.remove(previousIndex);
            }
            UUID displaced = uuidByIndex.put(e.index(), e.uuid());
            if (displaced != null && !displaced.equals(e.uuid())) {
                indexByUuid.remove(displaced);
                nameByUuid.remove(displaced);
                tracked.remove(displaced);
            }
            nameByUuid.put(e.uuid(), e.name());
            var previous = tracked.get(e.uuid());
            if (previous != null && !java.util.Objects.equals(previous.name(), e.name())) {
                tracked.put(e.uuid(), new TrackedFarPlayer(previous.uuid(), e.name(),
                        previous.latest(), previous.cadenceTicks(), previous.receivedAtMillis(),
                        previous.motion(), previous.equipmentIdentities(), previous.equipmentCounts()));
            }
        }
        if (uuidByIndex.size() > MAX_TRACKED_IDENTITIES
                || indexByUuid.size() > MAX_TRACKED_IDENTITIES
                || nameByUuid.size() > MAX_TRACKED_IDENTITIES
                || tracked.size() > MAX_TRACKED_IDENTITIES) {
            clear();
            identityCapResets++;
            return;
        }
        for (int idx : roster.removedIndices()) {
            UUID gone = uuidByIndex.remove(idx);
            if (gone != null) {
                indexByUuid.remove(gone);
                nameByUuid.remove(gone);
                tracked.remove(gone);
            }
        }
        rostersApplied++;
    }

    /** Applies an updates frame. Unknown epoch = dropped wholesale (misbinding armor);
     *  unknown indices within a valid frame are skipped (a race with an incremental
     *  removal — the next roster frame reconciles). */
    public void onUpdates(FarPlayerWire.Updates updates, long nowMillis) {
        if (updates.epoch() != epoch) {
            droppedWrongEpoch++;
            return;
        }
        this.dimension = updates.dimension();
        for (var e : updates.entries()) {
            UUID uuid = uuidByIndex.get(e.rosterIndex());
            if (uuid == null) continue;
            var prev = tracked.get(uuid);
            FarPlayerMotion motion;
            if (prev == null) {
                motion = new FarPlayerMotion(e, updates.cadenceTicks(), nowMillis);
            } else {
                motion = prev.motion();
                motion.apply(e, updates.cadenceTicks(), nowMillis);
            }
            // Equipment is STICKY (the wire carries it only when changed — a movement
            // frame must not strip the renderer's armor state); pose/vehicle are
            // authoritative per entry.
            String[] equipIds = e.equipmentIdentities() != null
                    ? e.equipmentIdentities()
                    : (prev != null ? prev.equipmentIdentities() : null);
            int[] equipCounts = e.equipmentIdentities() != null
                    ? e.equipmentCounts()
                    : (prev != null ? prev.equipmentCounts() : null);
            tracked.put(uuid, new TrackedFarPlayer(uuid, nameByUuid.get(uuid), e,
                    updates.cadenceTicks(), nowMillis, motion, equipIds, equipCounts));
            entriesApplied++;
        }
        updatesApplied++;
    }

    /** Disconnect + the R-3 reset path: forget everything including the seen epoch —
     *  the re-sent prefs trigger a bumped-epoch full roster that repopulates. */
    public void clear() {
        uuidByIndex.clear();
        indexByUuid.clear();
        nameByUuid.clear();
        tracked.clear();
        epoch = -1;
        dimension = null;
    }

    public int trackedCount() {
        return tracked.size();
    }

    public int currentEpoch() {
        return epoch;
    }

    public String dimension() {
        return dimension;
    }

    public Map<UUID, TrackedFarPlayer> snapshot() {
        return Map.copyOf(tracked);
    }

    public long identityCapResets() { return identityCapResets; }

    public long rostersApplied() { return rostersApplied; }
    public long updatesApplied() { return updatesApplied; }
    public long droppedWrongEpoch() { return droppedWrongEpoch; }

    /** The /lss diag line (phase A's observability — the debug HUD proper arrives with
     *  the E2 renderer, when there is something visual to debug). */
    public String diagLine() {
        return "FarPlayers: tracked=" + tracked.size() + ", epoch=" + epoch
                + ", rosters=" + rostersApplied + ", updates=" + updatesApplied
                + ", entries=" + entriesApplied + ", dropped_epoch=" + droppedWrongEpoch;
    }
}
