package dev.vox.lss.common.processing;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks cross-player disk read deduplication on the processing thread.
 * When multiple players request the same packed position, only the first triggers a disk read;
 * subsequent players attach to the existing group and receive the result when it arrives.
 *
 * <p>Single-threaded — must only be called from the processing thread.
 */
class DedupTracker {

    record Attachment(UUID playerUuid, long submissionOrder) {}
    record Group(UUID primaryPlayer, String dimension, ArrayList<Attachment> attached) {}
    record RemovedGroup(long packed, Group group) {}

    // Keyed by dimension first, then packed XZ: the same (cx, cz) in two different
    // dimensions must NOT share a dedup group — they resolve to different chunk data.
    private final Map<String, Long2ObjectOpenHashMap<Group>> pending = new HashMap<>();

    /**
     * Try to attach to an existing dedup group for the given packed position in the given dimension.
     * If no group exists, creates one (empty — the caller submits the actual disk read).
     *
     * @return {@code true} if an existing group was found (caller should NOT submit a disk read),
     *         {@code false} if a new group was created (caller SHOULD submit a disk read)
     */
    boolean tryAttachOrCreate(long packed, String dimension, UUID primaryPlayer, long submissionOrder) {
        var dimMap = this.pending.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>());
        var existing = dimMap.get(packed);
        if (existing != null) {
            existing.attached().add(new Attachment(primaryPlayer, submissionOrder));
            return true;
        }
        dimMap.put(packed, new Group(primaryPlayer, dimension, new ArrayList<>(2)));
        return false;
    }

    /**
     * Remove and return the dedup group for the given packed position in the given dimension.
     * Called when the primary disk read completes.
     *
     * @return the group, or {@code null} if no group existed
     */
    Group removeGroup(long packed, String dimension) {
        var dimMap = this.pending.get(dimension);
        if (dimMap == null) return null;
        var group = dimMap.remove(packed);
        if (group != null && dimMap.isEmpty()) {
            this.pending.remove(dimension);
        }
        return group;
    }

    /** Number of pending dedup groups across all dimensions (live diagnostics export). */
    int size() {
        int total = 0;
        for (var dimMap : this.pending.values()) {
            total += dimMap.size();
        }
        return total;
    }

    /**
     * Number of dimensions currently holding a group map. {@link #removeGroup} and
     * {@link #removePlayer} prune a dimension's map when its last group leaves, so a
     * nonzero value here with {@link #size()} == 0 is a leak (long-running servers with
     * datapack dimensions would accumulate empty maps otherwise).
     */
    int trackedDimensionCount() {
        return this.pending.size();
    }

    /**
     * Remove all references to the given player from all groups in all dimensions.
     * If the player is the primary of a group, the entire group is removed and returned
     * so the caller can clean up attached players' concurrency slots.
     * If the player is an attachment, they are removed from the group's attached list.
     *
     * <p>Called when a player disconnects or changes dimension.
     *
     * @return groups that were removed because the player was the primary (empty list if none)
     */
    List<RemovedGroup> removePlayer(UUID playerUuid) {
        List<RemovedGroup> removed = null;
        var dimIter = this.pending.values().iterator();
        while (dimIter.hasNext()) {
            var dimMap = dimIter.next();
            var iter = dimMap.long2ObjectEntrySet().iterator();
            while (iter.hasNext()) {
                var entry = iter.next();
                var group = entry.getValue();
                if (group.primaryPlayer().equals(playerUuid)) {
                    if (removed == null) removed = new ArrayList<>();
                    removed.add(new RemovedGroup(entry.getLongKey(), group));
                    iter.remove();
                } else {
                    group.attached().removeIf(a -> a.playerUuid().equals(playerUuid));
                }
            }
            if (dimMap.isEmpty()) dimIter.remove();
        }
        return removed != null ? removed : List.of();
    }
}
