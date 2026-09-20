package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * THE REFERENCE ORACLE for {@link SectionStateFuzzTest}: the pre-quadtree
 * {@link ColumnStateMap} implementation (hash-map backing, main @ 79e49951 / v0.11.1),
 * preserved verbatim apart from the class name FOR THE v0.11.1 SURFACES. The
 * summary-era surfaces ({@code applyTileValidation}, {@code ratchetStamp}, the
 * {@code summaryValidated} set) did not exist in v0.11.1 — they are co-designed
 * hash-backed twins written alongside the leaf implementation they audit (panel
 * record 2026-08-22), so for those ops the fuzz's value is an INDEPENDENT-STRUCTURE
 * differential (hash sets vs leaf bitmasks — it still catches the index/mask-math
 * class), not shipped-semantics preservation. The section-leaf rewrite
 * (docs/planning/quadtree-client-state-plan.md) must be observationally equivalent to
 * this code — the fuzz drives identical op sequences into both and compares every
 * observable after every op. Do not "improve" this class: for the v0.11.1 surfaces
 * its value is that it IS the shipped semantics, bug-for-bug (the one sanctioned
 * divergence — sub- -1 timestamp normalization — is documented in the new class's
 * javadoc and normalized by the fuzz's input domain).
 */
class ReferenceColumnStateMap {

    static final long SATISFIED = Long.MIN_VALUE;

    private final Long2LongOpenHashMap timestamps = new Long2LongOpenHashMap();
    {
        timestamps.defaultReturnValue(-1L);
    }

    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private final LongOpenHashSet retry = new LongOpenHashSet();
    private final LongOpenHashSet validated = new LongOpenHashSet();
    // Provenance twin (final review, client lens MAJOR-2): bits set by tile validation,
    // revocable; server per-column proofs are not. Subset of `validated`.
    private final LongOpenHashSet summaryValidated = new LongOpenHashSet();
    private final LongOpenHashSet sessionSatisfied = new LongOpenHashSet();
    private final LongOpenHashSet staleInFlight = new LongOpenHashSet();
    private final Long2IntOpenHashMap ingestFailures = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap clearedResync = new Long2LongOpenHashMap();
    private final LongOpenHashSet persistentRemovals = new LongOpenHashSet();

    private int receivedCount;
    private int emptyCount;

    long classify(long packed) {
        if (this.dirty.contains(packed)) {
            long dirtyStored = this.timestamps.get(packed);
            return dirtyStored <= 0L ? -1L : dirtyStored;
        }
        if (this.sessionSatisfied.contains(packed)) return SATISFIED;
        long stored = this.timestamps.get(packed);
        if (stored <= 0L) return -1L;
        if (this.retry.contains(packed)) return stored;
        if (!this.validated.contains(packed)) return stored;
        return SATISFIED;
    }

    void markSessionSatisfied(long packed) { this.sessionSatisfied.add(packed); }

    boolean isSessionSatisfied(long packed) { return this.sessionSatisfied.contains(packed); }

    int sessionSatisfiedCount() { return this.sessionSatisfied.size(); }

    void noteStaleIfInFlight(long packed, boolean inFlight) {
        if (inFlight) this.staleInFlight.add(packed);
    }

    boolean resolveStale(long packed) {
        return this.staleInFlight.remove(packed);
    }

    void markAuthoritativeClear(long packed, long preClearStamp) {
        if (preClearStamp > 0) this.clearedResync.put(packed, preClearStamp);
    }

    private void put(long packed, long timestamp) {
        long old = this.timestamps.put(packed, timestamp);
        if (old >= 0) {
            if (old > 0) this.receivedCount--;
            else this.emptyCount--;
        }
        if (timestamp > 0) this.receivedCount++;
        else if (timestamp == 0) this.emptyCount++;
    }

    boolean markDirtyIfKnown(long packed) {
        if (this.timestamps.get(packed) != -1L || this.sessionSatisfied.contains(packed)) {
            this.sessionSatisfied.remove(packed);
            this.dirty.add(packed);
            return true;
        }
        return false;
    }

    void markRetry(long packed) {
        this.retry.add(packed);
    }

    void onReceived(long packed, long columnTimestamp) {
        this.dirty.remove(packed);
        this.sessionSatisfied.remove(packed);
        this.clearedResync.remove(packed);
        this.retry.remove(packed);
        put(packed, columnTimestamp);
        this.validated.add(packed);
        this.summaryValidated.remove(packed); // upgraded to a per-column proof
    }

    void onUpToDate(long packed) {
        this.retry.remove(packed);
        this.dirty.remove(packed);
        long stored = this.timestamps.get(packed);
        if (stored == -1L || stored == 0L) {
            this.sessionSatisfied.add(packed);
            if (stored == 0L) {
                this.timestamps.remove(packed);
                this.persistentRemovals.add(packed);
                this.emptyCount--;
            }
        } else {
            this.validated.add(packed);
            this.summaryValidated.remove(packed); // upgraded to a per-column proof
        }
    }

    void onNotGenerated(long packed) {
        this.dirty.remove(packed);
        this.retry.remove(packed);
        this.sessionSatisfied.add(packed);
        if (this.timestamps.get(packed) == 0L) {
            this.timestamps.remove(packed);
            this.persistentRemovals.add(packed);
            this.emptyCount--;
        }
    }

    static final int MAX_INGEST_FAILURES = 3;

    void onIngestFailed(long packed) {
        long old = this.timestamps.get(packed);
        if (old == -1L) return;

        if (this.sessionSatisfied.contains(packed)
                && this.ingestFailures.get(packed) > MAX_INGEST_FAILURES) return;

        long clearPreStamp = this.clearedResync.getOrDefault(packed, -1L);
        if (clearPreStamp > 0 && old == clearPreStamp && this.retry.contains(packed)) return;

        int priorFailures = this.ingestFailures.addTo(packed, 1);
        if (priorFailures + 1 > MAX_INGEST_FAILURES) {
            long parkPreStamp = this.clearedResync.getOrDefault(packed, -1L);
            if (parkPreStamp > 0) {
                put(packed, parkPreStamp);
            } else {
                this.timestamps.remove(packed);
                this.persistentRemovals.add(packed);
                if (old > 0) this.receivedCount--;
                else if (old == 0) this.emptyCount--;
            }
            this.sessionSatisfied.add(packed);
            this.validated.remove(packed);
        this.summaryValidated.remove(packed);
            this.retry.remove(packed);
            this.dirty.remove(packed);
            this.clearedResync.remove(packed);
            return;
        }

        if (clearPreStamp > 0) {
            put(packed, clearPreStamp);
            this.validated.remove(packed);
        this.summaryValidated.remove(packed);
            this.dirty.remove(packed);
            this.retry.add(packed);
            return;
        }

        this.timestamps.remove(packed);
        this.persistentRemovals.add(packed);
        if (old > 0) this.receivedCount--;
        else if (old == 0) this.emptyCount--;
        this.validated.remove(packed);
        this.summaryValidated.remove(packed);
        this.dirty.remove(packed);
        this.retry.add(packed);
    }

    void pruneOutOfRange(int playerCx, int playerCz, int pruneDistance) {
        int removed = 0;
        var iter = this.timestamps.long2LongEntrySet().fastIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            if (PositionUtil.isOutOfRange(entry.getLongKey(), playerCx, playerCz, pruneDistance)) {
                long ts = entry.getLongValue();
                if (ts > 0) this.receivedCount--;
                else if (ts == 0) this.emptyCount--;
                iter.remove();
                removed++;
            }
        }
        pruneSet(this.dirty, playerCx, playerCz, pruneDistance);
        pruneSet(this.retry, playerCx, playerCz, pruneDistance);
        pruneSet(this.validated, playerCx, playerCz, pruneDistance);
        pruneSet(this.summaryValidated, playerCx, playerCz, pruneDistance);
        pruneSet(this.sessionSatisfied, playerCx, playerCz, pruneDistance);
        pruneSet(this.staleInFlight, playerCx, playerCz, pruneDistance);
        var failIter = this.ingestFailures.long2IntEntrySet().fastIterator();
        while (failIter.hasNext()) {
            if (PositionUtil.isOutOfRange(failIter.next().getLongKey(), playerCx, playerCz, pruneDistance)) {
                failIter.remove();
            }
        }
        var clearIter = this.clearedResync.long2LongEntrySet().fastIterator();
        while (clearIter.hasNext()) {
            if (PositionUtil.isOutOfRange(clearIter.next().getLongKey(), playerCx, playerCz, pruneDistance)) {
                clearIter.remove();
            }
        }
        if (removed > this.timestamps.size()) {
            this.timestamps.trim();
            this.dirty.trim();
            this.retry.trim();
            this.validated.trim();
            this.sessionSatisfied.trim();
            this.staleInFlight.trim();
            this.ingestFailures.trim();
            this.clearedResync.trim();
        }
    }

    private static void pruneSet(LongOpenHashSet set, int playerCx, int playerCz, int pruneDistance) {
        var iter = set.iterator();
        while (iter.hasNext()) {
            if (PositionUtil.isOutOfRange(iter.nextLong(), playerCx, playerCz, pruneDistance)) {
                iter.remove();
            }
        }
    }

    void clear() {
        this.timestamps.clear();
        this.dirty.clear();
        this.retry.clear();
        this.validated.clear();
        this.summaryValidated.clear();
        this.sessionSatisfied.clear();
        this.staleInFlight.clear();
        this.ingestFailures.clear();
        this.clearedResync.clear();
        this.persistentRemovals.clear();
        this.receivedCount = 0;
        this.emptyCount = 0;
    }

    void loadFrom(Long2LongOpenHashMap loaded) {
        for (var entry : loaded.long2LongEntrySet()) {
            long ts = entry.getLongValue();
            put(entry.getLongKey(), ts < -1L ? -1L : ts);
        }
    }

    Long2LongOpenHashMap mapForSave() {
        return this.timestamps;
    }

    LongOpenHashSet persistentRemovalsForSave() {
        return this.persistentRemovals;
    }

    boolean hasPersistentRemovals() { return !this.persistentRemovals.isEmpty(); }

    long timestampFor(long packed) { return this.timestamps.get(packed); }

    boolean isEmptyMap() { return this.timestamps.isEmpty(); }
    boolean hasRetries() { return !this.retry.isEmpty(); }

    boolean hasActionableRetries(int playerCx, int playerCz, int exclusionRadius) {
        if (this.retry.isEmpty()) return false;
        var iter = this.retry.iterator();
        while (iter.hasNext()) {
            long packed = iter.nextLong();
            if (!SpiralScanner.isVanillaRendered(PositionUtil.unpackX(packed),
                    PositionUtil.unpackZ(packed), playerCx, playerCz, exclusionRadius)) {
                return true;
            }
        }
        return false;
    }

    void collectActionableRetryRings(int playerCx, int playerCz, int exclusionRadius,
                                     java.util.function.IntConsumer ringVisitor) {
        if (this.retry.isEmpty()) return;
        var iter = this.retry.iterator();
        while (iter.hasNext()) {
            long packed = iter.nextLong();
            int cx = PositionUtil.unpackX(packed);
            int cz = PositionUtil.unpackZ(packed);
            if (!SpiralScanner.isVanillaRendered(cx, cz, playerCx, playerCz, exclusionRadius)) {
                ringVisitor.accept(Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz)));
            }
        }
    }
    int receivedCount() { return this.receivedCount; }
    int emptyCount() { return this.emptyCount; }
    int dirtyCount() { return this.dirty.size(); }

    /** The provenance-scoped tile-validation twin (final review MAJOR-1/2): validate
     *  strictly-newer stamps as SUMMARY provenance; revoke ONLY summary-set bits on a
     *  failing compare (server per-column proofs survive); report revocations. Returns
     *  {newlyValidated, fullyValidated ? 1 : 0}. */
    long[] applyTileValidation(int tileX, int tileZ, long stampM,
                               java.util.function.LongConsumer revokedOut) {
        long newly = 0;
        boolean fully = true;
        int cx0 = tileX << 5, cz0 = tileZ << 5;
        for (int cz = cz0; cz < cz0 + 32; cz++) {
            for (int cx = cx0; cx < cx0 + 32; cx++) {
                long packed = dev.vox.lss.common.PositionUtil.packPosition(cx, cz);
                long ts = this.timestamps.get(packed);
                if (ts <= 0 || this.sessionSatisfied.contains(packed)) continue;
                if (ts > stampM) {
                    if (this.validated.add(packed)) {
                        this.summaryValidated.add(packed);
                        newly++;
                    }
                } else if (!this.validated.contains(packed)) {
                    fully = false;
                } else if (this.summaryValidated.contains(packed)) {
                    fully = false;
                    this.validated.remove(packed);
                    this.summaryValidated.remove(packed);
                    if (revokedOut != null) revokedOut.accept(packed);
                }
            }
        }
        return new long[]{newly, fully ? 1 : 0};
    }

    /** Loss of evidence revokes only positions whose proof came from a summary. */
    int revokeTileSummaryProof(int tileX, int tileZ, java.util.function.LongConsumer revokedOut) {
        int count = 0;
        var iterator = this.summaryValidated.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            if ((PositionUtil.unpackX(packed) >> 5) != tileX
                    || (PositionUtil.unpackZ(packed) >> 5) != tileZ) continue;
            iterator.remove();
            this.validated.remove(packed);
            count++;
            if (revokedOut != null) revokedOut.accept(packed);
        }
        return count;
    }

    /** Twin of {@code ColumnStateMap.ratchetStamp} — pure monotonic ts advance on an
     *  existing positive, mark-free stamp (stamped-up-to-date-plan.md §4). */
    boolean ratchetStamp(long packed, long second) {
        if (second <= 0) return false;
        long stored = this.timestamps.get(packed);
        if (stored <= 0) return false;
        if (this.dirty.contains(packed) || this.retry.contains(packed)) return false;
        if (this.sessionSatisfied.contains(packed)) return false;
        if (second <= stored) return false;
        this.timestamps.put(packed, second);
        return true;
    }
}
