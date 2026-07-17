package dev.vox.lss.common.compat;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.AbstractPlayerRequestState;
import dev.vox.lss.common.processing.IncomingBatch;
import dev.vox.lss.common.processing.IncomingRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-player recordkeeping for one legacy protocol-16 session: the <b>synthetic
 * want-set</b> that converts the old client's incremental drip-feed into the full-set
 * declarations the v17 pipeline assumes (docs/planning/v16-compat-design.md §4.2–§4.3).
 *
 * <p>All state is guarded by the session monitor. Fabric touches a session from one
 * thread (MAIN); on Paper ingress merges can arrive on Folia region threads while the
 * declare/prune paths run on the pump, so every method that reads or writes the set is
 * {@code synchronized}. Critical sections are tiny and v16 throughput is explicitly not
 * a goal. The declare's batch build and mailbox offer happen inside the same lock so a
 * slower concurrent build can never publish stale membership over a newer one — the set
 * invariant is: <i>an entry absent from a later declaration is satisfied, range-evicted,
 * or TTL-stale — never merely lost.</i>
 */
final class V16CompatSession {

    /** The client's declared timestamp plus the shim's freshness stamp. The client is the
     *  sole authority on {@code clientTs} — a re-merge always overwrites it with whatever
     *  the client last declared (upgrading a declared -1 to a served stamp would let a
     *  later UP_TO_DATE seal a column the client failed to ingest). */
    private static final class Entry {
        long clientTs;
        long lastSeenNanos;

        Entry(long clientTs, long lastSeenNanos) {
            this.clientTs = clientTs;
            this.lastSeenNanos = lastSeenNanos;
        }
    }

    /** Insertion-ordered for stable iteration; declaration order is re-sorted by Chebyshev
     *  distance at declare time (insertion order goes stale the moment the player moves). */
    private final LinkedHashMap<Long, Entry> wantSet = new LinkedHashMap<>();

    /** Ingress merges are discarded while the grace is armed: batches already in netty
     *  flight across a dimension change would otherwise merge old-dimension wants into the
     *  fresh set (Nether↔Overworld coordinate overlap defeats the range filter near the
     *  origin). Armed-flag + elapsed-time form, NOT a deadline compare — nanoTime's origin
     *  is arbitrary and only differences are overflow-safe. */
    private long graceArmedNanos;
    private boolean graceArmed;

    /** Primed so the first declare tick after creation/reset — and, via the stay-primed
     *  empty path in {@link #declareInto}, the first tick after the first MERGE — declares
     *  immediately (a fresh v16 join should not wait a full interval for its first batch). */
    private int ticksSinceDeclare = V16CompatManager.DECLARE_INTERVAL_TICKS - 1;

    /** True while the pipeline holds a declaration from this session that a later empty
     *  declare must explicitly clear: without the one edge-triggered empty batch, a set
     *  that drains by EVICTION (teleport range-evict, TTL on a gone-quiet client) leaves
     *  the previous declaration's backlog — up to a full budget of stale positions — to
     *  grind to completion at the old location (implementation-review finding A2). */
    private boolean clearOwed;

    /** Merge one range-filtered client batch position. Returns false when the set is at
     *  capacity and the position is new (the caller bounces it RATE_LIMITED). */
    private boolean mergeOne(long packed, long clientTs, long nowNanos, int cap) {
        var existing = this.wantSet.get(packed);
        if (existing != null) {
            existing.clientTs = clientTs;
            existing.lastSeenNanos = nowNanos;
            return true;
        }
        if (this.wantSet.size() >= cap) return false;
        this.wantSet.put(packed, new Entry(clientTs, nowNanos));
        return true;
    }

    /**
     * Ingress merge (never declares — the tick is the sole declarer). Positions and
     * timestamps are the batch's parallel arrays, pre-decoded; the range filter runs here
     * so out-of-range asks match the v18 ingress path's silent drop.
     */
    synchronized V16CompatManager.MergeResult merge(long[] packedPositions, long[] clientTimestamps,
                                                    int count, int playerCx, int playerCz,
                                                    int maxDist, long nowNanos, int cap) {
        if (this.graceArmed) {
            if (nowNanos - this.graceArmedNanos < V16CompatManager.RESET_GRACE_NANOS) {
                return new V16CompatManager.MergeResult(0, count, V16CompatManager.NO_POSITIONS);
            }
            this.graceArmed = false;
        }
        int rangeFiltered = 0;
        var bounced = (ArrayList<Long>) null;
        for (int i = 0; i < count; i++) {
            long packed = packedPositions[i];
            int cx = PositionUtil.unpackX(packed);
            int cz = PositionUtil.unpackZ(packed);
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > maxDist) {
                rangeFiltered++;
                continue;
            }
            if (!mergeOne(packed, clientTimestamps[i], nowNanos, cap)) {
                if (bounced == null) bounced = new ArrayList<>();
                bounced.add(packed);
            }
        }
        long[] bouncedArr = V16CompatManager.NO_POSITIONS;
        if (bounced != null) {
            bouncedArr = new long[bounced.size()];
            for (int i = 0; i < bouncedArr.length; i++) bouncedArr[i] = bounced.get(i);
        }
        return new V16CompatManager.MergeResult(rangeFiltered, 0, bouncedArr);
    }

    private static final IncomingBatch EMPTY_CLEAR = new IncomingBatch(new IncomingRequest[0]);

    /**
     * The 1 Hz declare: TTL sweep, range re-filter against the player's CURRENT chunk
     * (the ingress-time filter cannot cover later movement), Chebyshev sort (restores the
     * closest-first precondition the router's live-frontier stamp, the spread gate, and
     * the probe cap all assume), then build AND OFFER under this monitor — the S7 lock
     * coverage is real, not aspirational: a reset serialized behind this monitor can never
     * interleave between the build and the publish. Returns null on a non-declare tick or
     * a no-op empty tick.
     *
     * <p>An empty set stays PRIMED (the counter is not consumed) so the first merge after
     * an idle stretch declares on the very next tick, and — once, edge-triggered — offers
     * the {@link #EMPTY_CLEAR} batch when a previous declaration is still outstanding:
     * the pipeline treats an empty batch as the explicit backlog clear, so a set drained
     * by eviction cannot leave stale work grinding at the old location.
     */
    synchronized DeclareOutcome declareInto(AbstractPlayerRequestState<?> state,
                                            int playerCx, int playerCz, int maxDist,
                                            long nowNanos) {
        if (++this.ticksSinceDeclare < V16CompatManager.DECLARE_INTERVAL_TICKS) return null;

        int rangeEvicted = 0;
        Iterator<Map.Entry<Long, Entry>> it = this.wantSet.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (nowNanos - e.getValue().lastSeenNanos > V16CompatManager.ENTRY_TTL_NANOS) {
                it.remove();
                continue;
            }
            long packed = e.getKey();
            if (PositionUtil.chebyshevDistance(PositionUtil.unpackX(packed),
                    PositionUtil.unpackZ(packed), playerCx, playerCz) > maxDist) {
                it.remove();
                rangeEvicted++;
            }
        }
        if (this.wantSet.isEmpty()) {
            // Stay primed: an idle session must declare on the first tick after a merge.
            this.ticksSinceDeclare = V16CompatManager.DECLARE_INTERVAL_TICKS - 1;
            if (this.clearOwed) {
                this.clearOwed = false;
                state.offerIncomingBatch(EMPTY_CLEAR);
                return new DeclareOutcome(false, true, rangeEvicted);
            }
            return rangeEvicted == 0 ? null : new DeclareOutcome(false, false, rangeEvicted);
        }
        this.ticksSinceDeclare = 0;

        var requests = new ArrayList<IncomingRequest>(this.wantSet.size());
        for (var e : this.wantSet.entrySet()) {
            long packed = e.getKey();
            requests.add(new IncomingRequest(PositionUtil.unpackX(packed),
                    PositionUtil.unpackZ(packed), e.getValue().clientTs));
        }
        requests.sort(Comparator.comparingInt(r ->
                PositionUtil.chebyshevDistance(r.cx(), r.cz(), playerCx, playerCz)));
        state.offerIncomingBatch(new IncomingBatch(requests.toArray(new IncomingRequest[0])));
        this.clearOwed = true;
        return new DeclareOutcome(true, false, rangeEvicted);
    }

    record DeclareOutcome(boolean offered, boolean clearOffered, int rangeEvicted) {}

    /** Prune: the position was answered (column sent, up_to_date, or not_generated). The
     *  prune is LOAD-BEARING, not an optimization — a surviving ts&le;0 entry re-resolves
     *  honestly (full disk re-read + re-serve) on every declare until removed. */
    synchronized void prune(long packed) {
        this.wantSet.remove(packed);
    }

    /** Service-level reset (dimension change / duplicate handshake): clear the set and —
     *  on the dim-change flavor — arm the ingress grace so old-dimension straggler batches
     *  in netty flight are discarded instead of resurrected into the fresh set. The
     *  clear-owed flag follows the state's fate: a service remove destroys the state (and
     *  its backlog) so nothing is owed; a duplicate handshake keeps the state, so a still-
     *  outstanding declaration must still be cleared by the next empty declare. */
    synchronized void resetWantSet(long nowNanos, boolean armGrace) {
        this.wantSet.clear();
        if (armGrace) {
            this.graceArmedNanos = nowNanos;
            this.graceArmed = true;
            this.clearOwed = false;
        }
        this.ticksSinceDeclare = V16CompatManager.DECLARE_INTERVAL_TICKS - 1;
    }

    synchronized int size() {
        return this.wantSet.size();
    }
}
