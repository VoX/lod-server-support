package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * The single owner of per-column client state: known column timestamps plus the dirty /
 * rate-limit-retry / validated-this-session marks, with derived received/empty counts.
 * {@link #classify} is the one request-need ladder consulted by both the scanner and the
 * queue drain.
 *
 * <p>Timestamp semantics: absent (-1) = never seen; 0 = server said not-generated;
 * &gt;0 = received/validated at that epoch-second.
 *
 * <p><b>Thread safety:</b> Not thread-safe. Main client thread only.
 */
class ColumnStateMap {

    /** Sentinel returned by {@link #classify} when the position needs no request. */
    static final long SATISFIED = Long.MIN_VALUE;

    private final Long2LongOpenHashMap timestamps = new Long2LongOpenHashMap();
    {
        timestamps.defaultReturnValue(-1L);
    }

    // Positions flagged by the server's dirty broadcast that need re-requesting.
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    // Positions bounced with rate-limited that need retry on a later scan.
    private final LongOpenHashSet retry = new LongOpenHashSet();
    // Positions confirmed current (data or up-to-date) in this session; cleared on
    // reconnect/dimension change so cached-but-stale positions get revalidated.
    private final LongOpenHashSet validated = new LongOpenHashSet();
    // Positions resolved for THIS session that hold no server data — all-air up-to-date and
    // ingest-parked columns. Stops the within-session re-request loop WITHOUT fabricating a
    // client-clock timestamp (which would persist and lie next session). Never persisted;
    // cleared on reconnect/dimension change and pruned by distance.
    private final LongOpenHashSet sessionSatisfied = new LongOpenHashSet();
    // Per-position ingest-failure counts; bounds the reject -> re-serve loop a permanently
    // failing consumer would otherwise drive forever (see onIngestFailed).
    private final Long2IntOpenHashMap ingestFailures = new Long2IntOpenHashMap();

    // Derived counts, maintained on every timestamp transition.
    private int receivedCount;
    private int emptyCount;

    /**
     * Decide whether a position needs a request and which clientTimestamp to send.
     * Priority: unknown &gt; generation retry &gt; dirty &gt; rate-limit retry &gt; revalidation.
     *
     * @return the timestamp to send (-1 unknown, 0 generation, &gt;0 resync),
     *         or {@link #SATISFIED} when nothing should be sent
     */
    long classify(long packed, boolean generationEnabled) {
        // Dirty outranks EVERYTHING (incl. sessionSatisfied): a server-pushed change must
        // re-request even a settled all-air/parked position, or an air->content edit becomes a
        // permanent hole. An unknown-but-dirty position re-asks as a first serve (-1).
        if (this.dirty.contains(packed)) {
            long dirtyStored = this.timestamps.get(packed);
            return dirtyStored == -1L ? -1L : dirtyStored;
        }
        if (this.sessionSatisfied.contains(packed)) return SATISFIED; // resolved this session, no server data
        long stored = this.timestamps.get(packed);
        if (stored == -1L) return -1L; // Unknown — sync-on-load first; server generates only on explicit retry
        if (stored == 0L && generationEnabled) return 0L; // Not generated — generation retry
        if (this.retry.contains(packed)) return stored; // Rate-limit retry
        if (stored > 0 && !this.validated.contains(packed)) return stored; // Cached but not validated this session
        return SATISFIED;
    }

    void markSessionSatisfied(long packed) { this.sessionSatisfied.add(packed); }

    boolean isSessionSatisfied(long packed) { return this.sessionSatisfied.contains(packed); }

    int sessionSatisfiedCount() { return this.sessionSatisfied.size(); }

    private void put(long packed, long timestamp) {
        long old = this.timestamps.put(packed, timestamp);
        if (old >= 0) {
            if (old > 0) this.receivedCount--;
            else this.emptyCount--;
        }
        if (timestamp > 0) this.receivedCount++;
        else if (timestamp == 0) this.emptyCount++;
    }

    /**
     * Mark dirty if the client has any recorded disposition for the column (data OR a
     * not-generated stamp). A dirty broadcast means the server just SAVED content there,
     * so a ts==0 stamp is stale — without this rescue, a not-generated answer under
     * enableChunkGeneration=false (or any probe-miss race on a fresh world) parks the
     * position permanently even after the chunk exists on disk. The re-request goes out
     * with ts=0, which the server routes disk-first and can now serve. Unknown (-1)
     * positions stay unmarked — the scan ladder requests those anyway.
     */
    boolean markDirtyIfKnown(long packed) {
        // Fire if the client has ANY disposition for the column: a stored timestamp (data or
        // not-generated) OR a session-satisfied mark (an all-air/parked position with no stored
        // value). The sessionSatisfied clause is the fix for the air->content permanent hole:
        // without it, a settled all-air column that gains content is never re-requested, because
        // classify's sessionSatisfied rung would keep returning SATISFIED. Removing it from
        // sessionSatisfied (and dirty outranking it in classify) lets the position re-request.
        // Truly-unknown -1 positions (not session-satisfied) stay unmarked — the scan ladder
        // requests those anyway, and marking them would add retry marks nothing can consume.
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

    /** A request for this position is going on the wire — its pending marks are consumed. */
    void markSent(long packed) {
        this.retry.remove(packed);
        this.dirty.remove(packed);
    }

    /** Column data arrived (authoritative for the position, even if no longer tracked). */
    void onReceived(long packed, long columnTimestamp) {
        this.dirty.remove(packed);
        this.sessionSatisfied.remove(packed); // real data supersedes any session-satisfied mark
        // An answer supersedes any pending retry. Rate-limit bounces (the original retry
        // writer) guarantee no response is coming, but the timeout sweep retries positions
        // whose response may still arrive late — leaving the mark would re-request every
        // late-delivered column once and keep confirmedRing pinned at 0 for an extra scan.
        this.retry.remove(packed);
        put(packed, columnTimestamp);
        this.validated.add(packed);
    }

    /** Server confirmed the column is current. */
    void onUpToDate(long packed) {
        this.retry.remove(packed); // an up-to-date answer supersedes a pending retry (see onReceived)
        // Up-to-date must satisfy BOTH unsatisfied states, or classify re-requests forever:
        // -1 (all-air columns never get a VoxelColumn response) and 0 (a not-generated stamp
        // whose chunk resolved as all-air on the server) — in the End this looped ~50 req/s
        // and starved the outer disc. For those the client holds NO server data, so mark it
        // session-satisfied instead of fabricating a client-clock stamp (which persists and
        // reads as a false up_to_date next session). A real >0 position validates as before.
        long stored = this.timestamps.get(packed);
        if (stored == -1L || stored == 0L) {
            this.sessionSatisfied.add(packed);
        } else {
            this.validated.add(packed);
        }
    }

    /** Server cannot serve the column (not generated / not servable). */
    void onNotGenerated(long packed) {
        this.sessionSatisfied.remove(packed); // a not-generated answer re-opens a satisfied position
        put(packed, 0L);
    }

    /** Re-serve attempts allowed per position per session before parking it. */
    static final int MAX_INGEST_FAILURES = 3;

    /**
     * A column that was stamped received never actually reached a consumer (decode error,
     * consumer rejection, undispatched at disconnect) — forget the stamp so the position
     * re-requests with ts=-1, and mark retry so the scanner's confirmed-ring reset makes
     * it reachable again (an unmarked unstamp inside a confirmed ring is never rescanned).
     *
     * <p>Ignores positions with no recorded disposition: every legitimate report path
     * stamps at receive before failing, so an absent position means a pruned straggler or
     * a buggy consumer passing arbitrary coordinates — marking those would add retry marks
     * nothing can ever consume (confirmedRing pinned at 0 for a stationary player).
     *
     * <p>After {@link #MAX_INGEST_FAILURES} failures the position is parked as satisfied:
     * a consumer that permanently rejects it (incompatible mod, storage that never comes
     * up) would otherwise drive a full re-download of it every scan for the whole session.
     * A parked position heals like any stale stamp (dirty broadcast or next session).
     */
    void onIngestFailed(long packed) {
        long old = this.timestamps.get(packed);
        if (old == -1L) return;

        int priorFailures = this.ingestFailures.addTo(packed, 1);
        if (priorFailures + 1 > MAX_INGEST_FAILURES) {
            // Park WITHOUT a fabricated or retained >0 stamp: the consumer never stored the
            // data, so claiming ts>0 next session would be the same lie that causes a permanent
            // hole. Drop the timestamp to -1 (honest "I hold nothing") and mark session-
            // satisfied so it stops re-downloading THIS session. Next session it re-asks -1;
            // a transient failure (storage briefly down) heals, a permanent one (incompatible
            // consumer) costs one re-attempt per session, bounded by this cap.
            this.timestamps.remove(packed);
            if (old > 0) this.receivedCount--;
            else if (old == 0) this.emptyCount--;
            this.sessionSatisfied.add(packed);
            this.validated.remove(packed);
            this.retry.remove(packed);
            this.dirty.remove(packed);
            return;
        }

        this.timestamps.remove(packed);
        if (old > 0) this.receivedCount--;
        else if (old == 0) this.emptyCount--;
        this.validated.remove(packed);
        this.dirty.remove(packed);
        this.retry.add(packed);
    }

    void pruneOutOfRange(int playerCx, int playerCz, int pruneDistance) {
        var iter = this.timestamps.long2LongEntrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            if (PositionUtil.isOutOfRange(entry.getLongKey(), playerCx, playerCz, pruneDistance)) {
                long ts = entry.getLongValue();
                if (ts > 0) this.receivedCount--;
                else if (ts == 0) this.emptyCount--;
                iter.remove();
            }
        }
        pruneSet(this.dirty, playerCx, playerCz, pruneDistance);
        pruneSet(this.retry, playerCx, playerCz, pruneDistance);
        pruneSet(this.validated, playerCx, playerCz, pruneDistance);
        pruneSet(this.sessionSatisfied, playerCx, playerCz, pruneDistance);
        var failIter = this.ingestFailures.long2IntEntrySet().iterator();
        while (failIter.hasNext()) {
            if (PositionUtil.isOutOfRange(failIter.next().getLongKey(), playerCx, playerCz, pruneDistance)) {
                failIter.remove();
            }
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
        this.sessionSatisfied.clear();
        this.ingestFailures.clear();
        this.receivedCount = 0;
        this.emptyCount = 0;
    }

    /** Bulk-load cached timestamps (resync across sessions). */
    void loadFrom(Long2LongOpenHashMap loaded) {
        for (var entry : loaded.long2LongEntrySet()) {
            long ts = entry.getLongValue();
            // Clamp a corrupt/garbage stamp below the -1 "unknown" sentinel (a truncated cache
            // file, or negative v2-migration sign-extension) to -1: it matches no classify rung
            // and would otherwise park the position SATISFIED for the whole session. As -1 it
            // re-requests, and the next save rewrites it clean.
            put(entry.getLongKey(), ts < -1L ? -1L : ts);
        }
    }

    /** The raw timestamp map, for {@link ColumnCacheStore} persistence (v3 format). */
    Long2LongOpenHashMap mapForSave() {
        return this.timestamps;
    }

    /** Raw stored timestamp for one position: -1 absent, 0 not-generated, &gt;0 received. */
    long timestampFor(long packed) { return this.timestamps.get(packed); }

    boolean isEmptyMap() { return this.timestamps.isEmpty(); }
    boolean hasRetries() { return !this.retry.isEmpty(); }
    int receivedCount() { return this.receivedCount; }
    int emptyCount() { return this.emptyCount; }
    int dirtyCount() { return this.dirty.size(); }
}
