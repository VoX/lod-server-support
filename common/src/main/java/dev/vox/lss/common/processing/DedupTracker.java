package dev.vox.lss.common.processing;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tracks cross-player disk read deduplication on the processing thread.
 * When multiple players request the same packed position, only the first triggers a disk read;
 * subsequent players attach to the existing group and receive the result when it arrives.
 *
 * <p>Single-threaded — must only be called from the processing thread.
 */
class DedupTracker {

    record Attachment(UUID playerUuid, int requestId, long submissionOrder) {}
    record Group(UUID primaryPlayer, String dimension, ArrayList<Attachment> attached) {}
    record RemovedGroup(long packed, Group group) {}

    private final Long2ObjectOpenHashMap<Group> pending = new Long2ObjectOpenHashMap<>();

    /**
     * Try to attach to an existing dedup group for the given packed position.
     * If no group exists, creates one (empty — the caller submits the actual disk read).
     *
     * @return {@code true} if an existing group was found (caller should NOT submit a disk read),
     *         {@code false} if a new group was created (caller SHOULD submit a disk read)
     */
    boolean tryAttachOrCreate(long packed, String dimension, UUID primaryPlayer, int requestId, long submissionOrder) {
        var existing = this.pending.get(packed);
        if (existing != null) {
            existing.attached().add(new Attachment(primaryPlayer, requestId, submissionOrder));
            return true;
        }
        this.pending.put(packed, new Group(primaryPlayer, dimension, new ArrayList<>(2)));
        return false;
    }

    /**
     * Remove and return the dedup group for the given packed position.
     * Called when the primary disk read completes.
     *
     * @return the group, or {@code null} if no group existed
     */
    Group removeGroup(long packed) {
        return this.pending.remove(packed);
    }

    /**
     * Remove all references to the given player from all groups.
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
        var iter = this.pending.long2ObjectEntrySet().iterator();
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
        return removed != null ? removed : List.of();
    }
}
