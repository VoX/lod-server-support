package dev.vox.lss.common.tracking;

import dev.vox.lss.common.FaultCounters;
import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks chunk columns confirmed as dirty (content hash changed).
 * Platform-agnostic — uses String dimension keys, no MC types.
 * Thread-safe via synchronized blocks.
 */
public class DirtyColumnTracker {

    /** Notified on EVERY {@link #markDirty} call (re-marks included — the listener's
     *  consumer is a monotonic max, so duplicates are free). This is the platform-
     *  neutral change choke point: Fabric's hash-confirmed save hook and Paper's dirty
     *  Bukkit events both funnel here, which is exactly the population the region stamp
     *  table's {@code liveSaveMark} must observe (region-summary-sync-plan.md §5). */
    @FunctionalInterface
    public interface MarkListener {
        void onMarkDirty(String dimension, int cx, int cz);
    }

    private final Map<String, LongOpenHashSet> dirtyColumns = new HashMap<>();
    /** Drained-but-invalidation-not-yet-applied (the stamping guard's second phase).
     *  Position -> COUNT of pending invalidation batches (final panel: a plain set
     *  let a stale confirm release a LATER batch's guard — drain1 moves P, P re-marks
     *  and drain2 re-moves it, confirm1 lands and P read released while
     *  invalidation2 was still queued; with dirtyBroadcastIntervalSeconds up to 300
     *  that window is a stamped seal). Each drain increments, each confirm
     *  decrements; the guard holds until EVERY outstanding batch has applied. */
    private final Map<String, it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap> invalidating =
            new HashMap<>();
    private long totalDrained;
    private long totalMarked;
    // Volatile, read outside the monitor: markDirty callers arrive on arbitrary threads
    // (the save hook may run off-main under C2ME/Moonrise; Paper events on region
    // threads under Folia) and the listener target is itself thread-safe.
    private volatile MarkListener markListener;

    /** Attach the mark listener (set once at service init, before any producer runs). */
    public void setMarkListener(MarkListener listener) {
        this.markListener = listener;
    }

    public void markDirty(String dimension, int cx, int cz) {
        // Listener FIRST (P1 review): the freshness bump must be visible before the
        // position becomes drainable, or a reader between the two could claim clean
        // for an already-marked position. Outside the monitor (the listener does its
        // own lock-free synchronization; holding this lock through foreign code
        // invites ordering deadlocks) and throw-contained — an advisory bump must
        // never take down the mark path that feeds dirty broadcasts.
        var listener = this.markListener;
        if (listener != null) {
            try {
                listener.onMarkDirty(dimension, cx, cz);
            } catch (Throwable t) {
                FaultCounters.global().record(FaultCounters.Site.DIRTY_LISTENER, t);
            }
        }
        synchronized (this) {
            long packed = PositionUtil.packPosition(cx, cz);
            if (dirtyColumns.computeIfAbsent(dimension, k -> new LongOpenHashSet()).add(packed)) {
                totalMarked++;
            }
        }
    }

    /** True from MARK until the tscache/store invalidation has ACTUALLY APPLIED — the
     *  whole latency chain the stamped-up_to_date predicate must refuse to stamp
     *  inside (plan §9.2 as corrected by the 3-Opus fold: the tracker set empties at
     *  DRAIN on the tick thread, but the invalidation is a mailbox event applied
     *  later on the processing thread — a guard covering only the marked half leaves
     *  a drain-to-apply window whose stamps seal permanently at any
     *  dirtyBroadcastIntervalSeconds > 15, a legal live-settable value). Two phases
     *  under one monitor: {@code dirtyColumns} (marked-but-undrained) and
     *  {@code invalidating} (drained-but-not-yet-applied, moved by drain, cleared by
     *  {@link #confirmInvalidated} from the invalidation apply). Called from the
     *  processing thread. */
    public synchronized boolean isPending(String dimension, long packedPosition) {
        var set = dirtyColumns.get(dimension);
        if (set != null && set.contains(packedPosition)) return true;
        var inv = invalidating.get(dimension);
        return inv != null && inv.containsKey(packedPosition);
    }

    /** Phase-two release (the invalidation APPLY calls this right after the
     *  tscache/store invalidation lands — see OffThreadProcessor.applyInvalidations'
     *  onApplied callback): ONE batch's evidence is gone — the guard releases only
     *  when the position's LAST outstanding batch confirms (the count, see the
     *  invalidating field). */
    public synchronized void confirmInvalidated(String dimension, long[] positions) {
        var inv = invalidating.get(dimension);
        if (inv == null) return;
        for (long p : positions) {
            int n = inv.get(p);
            if (n <= 1) inv.remove(p);
            else inv.put(p, n - 1);
        }
        if (inv.isEmpty()) invalidating.remove(dimension);
    }

    public synchronized long[] drainDirty(String dimension) {
        var set = dirtyColumns.get(dimension);
        if (set == null || set.isEmpty()) return null;
        long[] result = set.toLongArray();
        // Phase handoff BEFORE the clear (same monitor): isPending must never observe
        // a gap between "drained" and "invalidation applied".
        var inv = invalidating.computeIfAbsent(dimension,
                k -> new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap());
        for (long p : result) inv.addTo(p, 1);
        set.clear();
        totalDrained += result.length;
        return result;
    }

    /**
     * Drain every dimension's pending dirty positions at once. Shutdown path: marks
     * accumulated since the last broadcast interval must still invalidate the timestamp
     * cache before its final save, or the persisted stamps answer false up_to_date for
     * edited columns across the restart.
     */
    public synchronized Map<String, long[]> drainAll() {
        Map<String, long[]> out = new HashMap<>();
        // SHUTDOWN-ONLY contract (final panel — the old comment claimed the phases
        // were "kept consistent anyway", which was false and a trap): this drain
        // deliberately SKIPS the invalidating handoff, so isPending goes false the
        // moment it returns. That is sound only because both callers run at service
        // shutdown, where no stamping predicate will ever consult the guard again.
        // A future non-shutdown caller MUST use drainDirty (per dimension) instead,
        // or it re-opens the drain-to-apply seal window §10.2 closed.
        for (var entry : dirtyColumns.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            long[] positions = entry.getValue().toLongArray();
            entry.getValue().clear();
            totalDrained += positions.length;
            out.put(entry.getKey(), positions);
        }
        return out;
    }

    /** Dirty positions accumulated and not yet drained, across all dimensions. */
    public synchronized int pendingCount() {
        int total = 0;
        for (var set : dirtyColumns.values()) total += set.size();
        return total;
    }

    /** Cumulative count of positions handed to broadcasters across the tracker's lifetime. */
    public synchronized long getTotalDrained() { return totalDrained; }

    /**
     * Cumulative count of net-new dirty marks (re-marking a still-pending position does not
     * count). Closes mark/drain conservation: at any observation point,
     * {@code getTotalMarked() == getTotalDrained() + pendingCount()}.
     */
    public synchronized long getTotalMarked() { return totalMarked; }
}
