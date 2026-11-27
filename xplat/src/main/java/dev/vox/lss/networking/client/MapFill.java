package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * The map's debt and the player's fill of it, for the dimension the client is in (issue #380).
 *
 * <p>A column that arrives while no map is being written, or that a map lets go without
 * writing it, is owed to the map: one bit in {@link ColumnStateMap}, which the scanners never
 * read (as terrain the column is complete). Until this existed, a map that was switched on
 * after the download stayed empty: the cache says every column is current, the server answers
 * "up to date" with no data, and the only way out was Re-download Terrain, which makes Voxy
 * take everything again as well.
 *
 * <p>When the player asks for a fill, owed columns are asked for again as "I hold nothing", so
 * the server sends them, and the answers go to the map backends only: the other consumers
 * hold those columns, and nothing about the held column changes (not its stamp either).
 * Owed columns ride in a request batch only when the scan found no column that nobody holds
 * yet and budget is left, so a fill never displaces terrain.
 *
 * <p>Nothing here starts by itself. A map that appears does not cause a download (that would
 * be load the server did not ask for, and a server that stops asking for no map ingestion
 * would have every map client download its disc at once): the player is told once that
 * columns are owed, and asks. A fill that was asked for and not finished continues in the
 * next session.
 *
 * <p>Main client thread only.
 */
final class MapFill {
    /** A column may fail to reach the map this often (a reported drop, or the map letting it
     *  go) before the fill leaves it for another session: the bound on a map that refuses. */
    static final int MAX_FAILURES = 3;
    /** Fewer owed columns than this in range and nobody is told: a quarter of a region. */
    static final int HINT_MINIMUM = 256;
    /**
     * A column the scan itself declared is not picked for this long afterwards. The server
     * answers every declaration and an answer does not say which one it answers, so an
     * "up to date" for the scan's own question could otherwise be read as the answer to the
     * fill's ("the column is air now"). After this long no such answer is still on its way.
     */
    static final long WALK_GRACE_NANOS = 3_000_000_000L;
    /** One generation of {@link #askedNow}: a request is remembered for two of them. */
    static final long ASKED_NANOS = 2_000_000_000L;
    /** "Nothing to send" must come twice for a fill's request before it is believed. */
    static final int NO_DATA_ANSWERS = 2;
    /**
     * A request stops when this many of its answers in a row ended without the map writing
     * them. Three tries a column bound a column that fails; this bounds a map that takes
     * nothing while it reads as "being written" (Xaero with "Load New Chunks" off refuses
     * every tile of an area it has not mapped yet): a quarter of a region is sent for
     * nothing, not three times the disc. Columns that were refused are asked again only
     * when no untried column is left to ask from here, so a run of refusals means that
     * what is left is being refused, not that retries happened to come first.
     */
    static final int STALL_LIMIT = 256;
    private static final int RECENT_MAP_ONLY = 16_384;
    private static final long SETTLE_INTERVAL_NANOS = 1_000_000_000L;

    /** What became of a request that was answered without a column. */
    enum NoData {
        /** The position was not asked for the map: the answer is the scan's. */
        NOT_ASKED,
        /** Asked, and the answer may have been meant for an earlier question: asked again. */
        ASK_AGAIN,
        /** The server has nothing to send for it: the debt is dropped. */
        DROPPED
    }

    /** What a fill that ran out of columns to ask for from here did.
     *  @param stands the request goes on: for columns inside the view, or for terrain the
     *                map is missing elsewhere in this cache */
    record Finish(long delivered, int unfilled, boolean stands) {}

    /** A fill that stopped because the map took none of its last answers. */
    record Stall(long delivered, int notWritten) {}

    private final ColumnStateMap columns;
    /** Seam for tests. */
    LongSupplier clock = System::nanoTime;

    private boolean loaded;
    /** This dimension's cache was read: what it owes the map is known here. */
    private boolean cacheRead;
    /** Whether {@link #requested} is known: read from the record, or set by a press. */
    private boolean requestKnown;
    private boolean requested;
    private boolean fileUnknownBecomeOwed;
    private boolean pressPending;
    private boolean announced;
    /** The player has been told about the debt this cache holds (kept in the record): the
     *  line is said once per debt, not at every login. A column that arrives while no map is
     *  being written is new debt and takes it back. */
    private boolean hinted;
    private boolean hintKnown;
    private boolean stopped;
    private boolean stopBeforeLoad;
    private long[] picks = new long[0];
    private int notWrittenInARow;
    private Stall stall;
    /** The player was told this stay that a fill runs: its end is told too. */
    private boolean endOwed;
    private Finish finish;
    /**
     * Asked for the map lately and not answered since. Every scan declares its picks
     * afresh, so a position the fill has stopped declaring (out of range, inside the view,
     * in doubt again, the fill paused or ended) stops being "asked" two to four seconds
     * later: long enough for the answers the server had already queued, and after that an
     * answer is the scan's, or terrain for everyone, never a leftover of a request nobody
     * remembers.
     */
    private LongOpenHashSet askedNow = new LongOpenHashSet();
    private LongOpenHashSet askedBefore = new LongOpenHashSet();
    private long askedSince;
    /** Nothing can be asked from here right now (see {@link #working}). */
    private boolean quiet;
    /** Left alone for the rest of this session. */
    private final LongOpenHashSet settled = new LongOpenHashSet();
    private final Long2IntOpenHashMap failures = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap noData = new Long2IntOpenHashMap();
    /** What the scan declared lately: two generations, each {@link #WALK_GRACE_NANOS} long. */
    private LongOpenHashSet walkedNow = new LongOpenHashSet();
    private LongOpenHashSet walkedBefore = new LongOpenHashSet();
    private long walkedSince;
    /** The last columns delivered to the map alone, for a failure that names no delivery. */
    private final LongLinkedOpenHashSet recentMapOnly = new LongLinkedOpenHashSet();
    /** A fill's answers (either route) the map has not settled yet. */
    private int pendingMapOnly;
    /** {@link #delivered} when the request the player was told about began: its "finished"
     *  line counts what that request put on the map. */
    private long deliveredBefore;
    /** No pick before this: everything the scan declared before the fill was asked for is
     *  then older than the grace. */
    private long notBefore;
    private long lastSettle;
    private long delivered;
    private long withoutData;
    private long failed;
    private long unwritten;

    MapFill(ColumnStateMap columns) {
        this.columns = columns;
    }

    /** A dimension change or a cleared cache: nothing carries over. */
    void reset() {
        this.loaded = false;
        this.cacheRead = false;
        this.requestKnown = false;
        this.requested = false;
        this.fileUnknownBecomeOwed = false;
        this.pressPending = false;
        this.announced = false;
        this.quiet = false;
        this.askedSince = 0;
        this.hinted = false;
        this.hintKnown = false;
        this.stopped = false; // a stop is this dimension's, like the request it took back
        this.stopBeforeLoad = false;
        this.notWrittenInARow = 0;
        this.stall = null;
        this.endOwed = false;
        this.finish = null;
        this.askedNow.clear();
        this.askedBefore.clear();
        this.settled.clear();
        this.failures.clear();
        this.noData.clear();
        this.walkedNow.clear();
        this.walkedBefore.clear();
        this.recentMapOnly.clear();
        this.pendingMapOnly = 0;
        this.deliveredBefore = 0;
        this.delivered = 0;
        this.withoutData = 0;
        this.failed = 0;
        this.unwritten = 0;
    }

    /** This dimension's cache has been adopted (or cleared: nothing requested, nothing said).
     *  @param fillRequested a fill was asked for and had not finished
     *  @param hinted        the player was told about the debt this cache holds */
    void onCacheLoaded(boolean fillRequested, boolean hinted) {
        this.loaded = true;
        this.cacheRead = true;
        this.requestKnown = true;
        if (this.stopBeforeLoad) {
            // The player stopped while this was loading: a request found in the record is
            // the one they meant, and the record has to hear that it no longer stands.
            this.stopBeforeLoad = false;
            this.requested = false;
            this.stopped = fillRequested;
        } else {
            this.requested |= fillRequested;
        }
        if (!this.hintKnown) {
            this.hinted = hinted;
            this.hintKnown = true;
        }
        this.notBefore = this.clock.getAsLong() + WALK_GRACE_NANOS;
        startIfPressed();
    }

    /** The cache could not be loaded: this stay goes on without it, and what the record
     *  says (a fill asked for, the player told) stays what it is unless something here
     *  changes it. */
    void onCacheUnavailable() {
        this.loaded = true;
        if (this.stopBeforeLoad) {
            // Stopped while loading, and the record cannot be read: the save says "none".
            this.stopBeforeLoad = false;
            this.requested = false;
            this.requestKnown = true;
            this.stopped = true;
        }
        startIfPressed();
    }

    private void startIfPressed() {
        if (this.pressPending) {
            this.pressPending = false;
            start();
        }
    }

    boolean requested() {
        return this.requested;
    }

    /**
     * What the next save records. A stop or a press that is still waiting for the cache to
     * load counts: the player may leave this dimension or the server before the load has
     * finished, and what they asked for must reach the record all the same (the save merges
     * with the file, so it needs no loaded state for that).
     */
    ColumnStateMap.MapDebt forSave() {
        Boolean request = this.stopBeforeLoad ? Boolean.FALSE
                : this.pressPending ? Boolean.TRUE
                : this.requestKnown ? this.requested : null;
        return new ColumnStateMap.MapDebt(request, this.fileUnknownBecomeOwed || this.pressPending,
                this.hintKnown ? this.hinted : null, stopped());
    }

    /**
     * The player takes the request back ({@code /lss fillmap stop}). A request stands across
     * sessions until the cache owes the map nothing, which on a large cache is a long
     * download; this is the way out that does not clear the cache. What the map is owed
     * stays owed, so a later press fills it. Nothing more is asked for the map until then,
     * and the part of the cache this request had not got to keeps "unknown". Answers already
     * on their way are still the map's. Like the request, a stop is this dimension's.
     *
     * @return false when no request stood: then nothing changes (a fill that ended by itself,
     *         for one, keeps what its press had decided for the save)
     */
    boolean stop() {
        if (!this.loaded) {
            // The cache is still loading, so whether its record holds a request is not known
            // yet. Take back a press made meanwhile, and whatever the record turns out to hold.
            this.pressPending = false;
            this.stopBeforeLoad = true;
            return true;
        }
        if (!this.requested) {
            return false;
        }
        this.requested = false;
        this.requestKnown = true;
        this.fileUnknownBecomeOwed = false;
        this.stopped = true;
        // Nothing else to undo: without a request nothing is asked, nothing is settled and no
        // line is owed, and the player was told about the debt when they pressed.
        return true;
    }

    /** A request was taken back in this stay (see {@link #stop}), also by a stop that still
     *  waits for the cache to load. */
    boolean stopped() {
        return this.stopped || this.stopBeforeLoad;
    }

    /** Whether the player was already told about the debt this cache holds. */
    boolean hinted() {
        return this.hinted;
    }

    void noteHinted() {
        this.hinted = true;
        this.hintKnown = true;
    }

    /** The player asked for a fill. False while the cache is still loading: the request
     *  then starts as soon as it has loaded. */
    boolean press() {
        if (!this.loaded) {
            // The later word counts, as it does once the load is in: a press after a stop
            // that is still waiting for the load takes that stop back.
            this.stopBeforeLoad = false;
            this.pressPending = true;
            return false;
        }
        start();
        return true;
    }

    private void start() {
        // Columns of which it is not known whether a map has them count as owed once the
        // player asks for the map to be filled: the ones in memory now, the rest of the
        // file at the next save.
        this.columns.markUnknownMapOwed();
        this.fileUnknownBecomeOwed = true;
        this.stopped = false;
        this.settled.clear();
        this.failures.clear();
        this.noData.clear();
        this.requested = true;
        this.requestKnown = true;
        this.hinted = true; // whoever presses knows
        this.hintKnown = true;
        this.announced = true; // and was just told what the fill will do
        this.notWrittenInARow = 0;
        this.stall = null;
        this.endOwed = true;
        this.quiet = false;
        this.deliveredBefore = this.delivered;
        this.finish = null;
        this.lastSettle = 0;
        this.notBefore = this.clock.getAsLong() + WALK_GRACE_NANOS;
    }

    /** A press that found nothing to ask for here: no "finished" line is due. The request
     *  stands only for terrain the map is missing elsewhere in this cache. */
    void nothingHere() {
        this.endOwed = false;
        this.quiet = true;
        if (!debtLeft()) {
            this.requested = false;
            this.requestKnown = true;
        }
    }

    /**
     * Whether this cache may still owe the map something: a column held here is owed; or
     * debt left memory when the player moved on (the cache file still holds it); or the
     * cache could not be read, and nothing is known. Columns of unknown history count only
     * after a press, which makes the file's owed at the save. Answers still in a map's queue
     * need no term here: neither caller decides while one is pending (a second press during
     * a fill is answered before it gets this far, and settle waits for every delivery).
     */
    private boolean debtLeft() {
        return !this.cacheRead || this.columns.mapOwedCount() > 0 || this.columns.prunedMapOwed()
                || (this.fileUnknownBecomeOwed && this.columns.prunedMapUnknown());
    }

    /** A fill's answers the map has not finished with. */
    int pending() {
        return this.pendingMapOnly;
    }

    /** The player asked for a fill during this stay: the record must hear of it, also when
     *  the session holds no column when it ends. */
    boolean pressed() {
        return this.fileUnknownBecomeOwed || this.pressPending;
    }

    /** A fill is asked for and has, or may soon have, something to ask from here. */
    boolean working() {
        return this.requested && !this.quiet;
    }

    private boolean isAsked(long packed) {
        return this.askedNow.contains(packed) || this.askedBefore.contains(packed);
    }

    private void forgetAsked(long packed) {
        this.askedNow.remove(packed);
        this.askedBefore.remove(packed);
        this.noData.remove(packed);
    }

    /** The player was told that a fill asked for earlier goes on. */
    void noteResumeAnnounced() {
        this.endOwed = true;
        this.deliveredBefore = this.delivered;
    }

    /**
     * A column arrived for every consumer. Whether a map is being written decides what is
     * assumed until the delivery ends ({@link #onMapOutcome} then says what happened): with
     * a map on, the column is on its way to it, and a running fill must not ask for it too.
     */
    void onReceived(long packed, boolean mapWriting) {
        forgetAsked(packed);
        this.recentMapOnly.remove(packed);
        if (mapWriting) {
            this.columns.setMapPending(packed);
        } else {
            // New debt: worth a line again when a map is next being written.
            this.hinted = false;
            this.hintKnown = true;
            this.columns.setMapOwed(packed, true);
        }
    }

    /** A column arrived for the map alone. On its way until the delivery ends (see
     *  {@link #onMapOutcome}); the held column, its stamp included, is not touched. */
    void onMapOnlyReceived(long packed) {
        forgetAsked(packed);
        this.columns.setMapPending(packed);
        if (this.recentMapOnly.size() >= RECENT_MAP_ONLY) this.recentMapOnly.removeFirstLong();
        this.recentMapOnly.add(packed);
    }

    /** The column was asked for the map and is still owed: what arrives for it now is a
     *  fill's answer, whichever way it is routed. */
    boolean isFillAsked(long packed) {
        return isAsked(packed) && this.columns.isMapOwed(packed);
    }

    /**
     * Whether an arriving column is a fill's answer that only the map needs: it was asked
     * for the map and is still owed, the scanners are done with the column held (validated
     * this session, no change announced, nothing to retry), and its stamp is not newer than
     * the one held. Then it is the column the other consumers hold, and nothing about the
     * held column changes.
     *
     * <p>"Not newer", not "equal": a validation can move the held stamp past the column's
     * own (the server's verification second), for the same content. A NEWER stamp means the
     * server changed the column or stamped it again (it serves a chunk it has loaded, and
     * every read on a server without a store, with the time of the serve). Which of the two
     * cannot be told here, so that column is terrain for everyone and takes its new stamp:
     * one send settles both. Giving it to the map alone and keeping the old stamp had the
     * server send it a second time at the next join (measured, plan §6).
     */
    boolean isFillAnswer(long packed, long arrivingStamp) {
        return isFillAsked(packed) && this.columns.classify(packed) == ColumnStateMap.SATISFIED
                && arrivingStamp <= this.columns.timestampFor(packed);
    }

    void onMapOnlyTracked() {
        this.pendingMapOnly++;
    }

    void onMapOnlySettled() {
        if (this.pendingMapOnly > 0) this.pendingMapOnly--;
    }

    void clearPending() {
        this.pendingMapOnly = 0;
    }

    /**
     * How a delivery ended for the map: a map wrote the column, or the delivery ended and
     * none had (no map took it, or one let it go). For any delivery, not only a fill's:
     * this is what keeps the debt true when a map is switched off, blocked or busy while
     * columns it accepted are still queued, and when it returns early without a word.
     *
     * @param fillAnswer the delivery answered a fill's request (to the map alone, or to
     *                   everyone because its stamp was newer): counted for the fill
     */
    void onMapOutcome(long packed, boolean written, boolean fillAnswer) {
        if (written) {
            this.columns.setMapOwed(packed, false);
            if (fillAnswer) {
                this.delivered++;
                this.notWrittenInARow = 0;
            }
            return;
        }
        this.columns.setMapOwed(packed, true);
        if (fillAnswer) {
            this.unwritten++;
            strike(packed);
        }
    }

    /** A map wrote a fill's answer after the player had moved on and the column had left
     *  memory: the record's debt is settled at the save, and the fill counts it. */
    void onWrittenAfterPrune(long packed, boolean fillAnswer) {
        this.columns.noteMapSettled(packed);
        if (fillAnswer) {
            this.delivered++;
            this.notWrittenInARow = 0;
        }
    }

    /** A map let go of a column after the player had moved on and the column had left
     *  memory. The record on disk stands as it was (owed, if it was), and this session can no
     *  longer see that debt: it counts as debt that left memory, so a request stands for it. */
    void onLetGoAfterPrune(boolean fillAnswer) {
        this.columns.notePrunedMapOwed();
        if (fillAnswer) this.unwritten++;
    }

    /** The map dropped a column that was sent for it alone and asked for it again: the
     *  held column stands, the debt returns. */
    void onMapOnlyFailed(long packed) {
        this.columns.setMapOwed(packed, true);
        this.failed++;
        strike(packed);
    }

    private void strike(long packed) {
        if (this.failures.addTo(packed, 1) + 1 >= MAX_FAILURES) this.settled.add(packed);
        if (++this.notWrittenInARow >= STALL_LIMIT && this.requested) {
            // The map takes nothing: stop asking. The debt stands; the player is told, and
            // asks again when the map can take the columns. The answers already on their
            // way are still the fill's (see onScan).
            this.stall = new Stall(this.delivered - this.deliveredBefore, this.notWrittenInARow);
            this.requested = false;
            this.requestKnown = true;
            this.endOwed = false;
        }
    }

    /** Once, after a fill stopped because the map took none of its last answers. */
    Stall pollStall() {
        Stall result = this.stall;
        this.stall = null;
        return result;
    }

    /** Whether the last delivery of this column went to the map alone (for a failure
     *  report that does not say which delivery it means). */
    boolean wasMapOnly(long packed) {
        return this.recentMapOnly.contains(packed);
    }

    /**
     * The server answered a request without sending the column. For "up to date" the caller
     * comes here only while the scan has no question of its own open for the position: an
     * answer to an open question of the scan is the scan's.
     *
     * @param conclusive "not generated": the server has no such chunk. "Up to date" is not
     *                   conclusive the first time: it may answer a question the scan asked
     *                   before the fill's (see {@link #WALK_GRACE_NANOS}).
     */
    NoData onAnsweredWithoutData(long packed, boolean conclusive) {
        if (!isAsked(packed)) return NoData.NOT_ASKED;
        // Still asked: the column, if one is on its way after all, is then the fill's
        // answer; if none is, the next scan asks again and the second answer is believed.
        if (!conclusive && this.noData.addTo(packed, 1) + 1 < NO_DATA_ANSWERS) return NoData.ASK_AGAIN;
        forgetAsked(packed);
        this.columns.setMapOwed(packed, false);
        this.withoutData++;
        return NoData.DROPPED;
    }

    /** Owed columns a fill can ask for from here now. Walks the whole map: not per tick. */
    int owedInRange(int playerCx, int playerCz, int viewDistance, int lodDistance) {
        LongPredicate inView = inView(playerCx, playerCz, viewDistance);
        return this.columns.countMapOwedInRange(playerCx, playerCz, lodDistance,
                p -> this.settled.contains(p) || inView.test(p));
    }

    /** The columns vanilla sends with their whole neighbourhood: the scanners never ask
     *  for them, and a fill does not either (a map mod draws real chunks itself). Their
     *  debt stays until they are out of view again: nothing here proves a map drew them. */
    private static LongPredicate inView(int playerCx, int playerCz, int viewDistance) {
        return p -> SpiralScanner.isVanillaRendered(PositionUtil.unpackX(p), PositionUtil.unpackZ(p),
                playerCx, playerCz, viewDistance);
    }

    /**
     * A scan fired. What the fill declared two generations ago is forgotten. Also when no
     * fill runs: the answers to a request that just ended or stopped are still on their
     * way for a moment, and are still the fill's.
     */
    void onScan() {
        if (this.askedNow.isEmpty() && this.askedBefore.isEmpty()) return;
        long now = this.clock.getAsLong();
        if (now - this.askedSince < ASKED_NANOS) return;
        this.askedSince = now;
        var recycled = this.askedBefore;
        this.askedBefore = this.askedNow;
        recycled.clear();
        this.askedNow = recycled;
        if (!this.noData.isEmpty()) {
            var strikes = this.noData.keySet().iterator();
            while (strikes.hasNext()) {
                if (!isAsked(strikes.nextLong())) strikes.remove();
            }
        }
    }

    /** The fill's share of a batch that the scan would fill by itself: one part in this many. */
    static final int SHARE_DIVISOR = 4;

    /**
     * Adds owed columns to a request batch behind the scan's own {@code walked} entries, as
     * "I hold nothing" (-1), nearest first. Every scan declares its picks afresh, like the
     * scan's own wants.
     *
     * <p>The fill gets what the scan leaves of the batch, and never less than a quarter of
     * it: when the scan leaves less, the fill's columns take the place of the scan's last
     * entries (the farthest of a ring walk, the last in region order). Those are not sent:
     * the scan walks them again every time, and they go out when the fill needs fewer
     * places. A fill is the player's own
     * request, and it must not wait for a scan that may never be done: on a world that is
     * still being generated out to the LOD distance the scan asks for columns nobody holds
     * for hours, and every batch is full of them. (Until 0.24.0's visual pass the fill added
     * nothing while the scan asked for a single such column. Two fills of about 1,500 columns
     * then delivered none in two and three minutes, and one of 3,641 finished in nine seconds
     * once the scan had nothing to ask.)
     *
     * <p>The scan keeps three quarters of the batch. Terrain that still has to be generated
     * waits for the fill all the same, and that is the server's doing: it answers what it has
     * for a player before it generates anything farther out (its nearest-first rule), and a
     * fill's columns are near and exist. Measured on a fresh world with a budget of 800: the
     * first delivery 7 s after the press and all 4,681 columns 31 s after it (200 a scan, one
     * scan a second); 440 chunks were generated in the 35 s around the fill, against about
     * 38 a second in the same run without this rule; generation went on when the fill was
     * done. The client's {@code queued} diagnostic counts what the scan walked, also the
     * entries that made way.
     *
     * @param possible a fill can ask now (a map is being written, the session downloads);
     *                 when not, the scan's declarations are still noted for the grace
     * @param deliveriesPending a column of this stay (the fill's or any other) is still on
     *                 its way to the consumers: whether the fill is done here is not decided
     *                 before the map has finished with all of them
     * @return by how many entries the batch grew (0 also when the fill's columns only took
     *         the place of entries of the scan)
     */
    int append(long[] positions, long[] timestamps, int walked, int budget,
               int playerCx, int playerCz, int viewDistance, int lodDistance, boolean possible,
               boolean deliveriesPending) {
        if (!this.requested) return 0;
        long now = this.clock.getAsLong();
        if (now - this.walkedSince > WALK_GRACE_NANOS) {
            this.walkedBefore = this.walkedNow;
            this.walkedNow = new LongOpenHashSet();
            this.walkedSince = now;
        }
        for (int i = 0; i < walked; i++) this.walkedNow.add(positions[i]);
        if (!possible || now - this.notBefore < 0) return 0;
        int cap = Math.max(walked, Math.min(budget, positions.length));
        int room = Math.max(cap - walked, cap / SHARE_DIVISOR);
        if (room <= 0) return 0;
        LongPredicate inView = inView(playerCx, playerCz, viewDistance);
        LongPredicate skipped = p -> this.settled.contains(p) || this.walkedNow.contains(p)
                || this.walkedBefore.contains(p) || inView.test(p);
        // Collected apart from the batch first: only as many of the scan's entries make way
        // as the fill has columns for.
        if (this.picks.length < room) this.picks = new long[room];
        // Untried columns first; what a map refused is asked again with the room they leave.
        int picked = this.columns.collectMapOwed(playerCx, playerCz, lodDistance, this.picks, 0, room,
                p -> skipped.test(p) || this.failures.containsKey(p));
        if (picked < room && !this.failures.isEmpty()) {
            picked += this.columns.collectMapOwed(playerCx, playerCz, lodDistance, this.picks, picked,
                    room - picked, p -> skipped.test(p) || !this.failures.containsKey(p));
        }
        if (picked > 0) {
            if (this.askedNow.isEmpty() && this.askedBefore.isEmpty()) this.askedSince = now;
            this.quiet = false;
        }
        // Behind the scan's entries where they fit; over its farthest ones where they do not.
        int start = Math.min(walked, cap - picked);
        for (int i = 0; i < picked; i++) {
            positions[start + i] = this.picks[i];
            timestamps[start + i] = -1L;
            this.askedNow.add(this.picks[i]);
        }
        if (picked == 0 && !deliveriesPending && now - this.lastSettle >= SETTLE_INTERVAL_NANOS) {
            this.lastSettle = now;
            settle(playerCx, playerCz, lodDistance, inView);
        }
        return start + picked - walked;
    }

    /**
     * Nothing was picked: is the fill done here? Not while answers are still on their way
     * to the map (a column the map lets go comes back as a debt and is asked again), and
     * not while owed columns wait for the scan or for the grace after it. What is left
     * inside the view waits there without holding up the "finished" line: it follows when
     * the player moves on.
     */
    private void settle(int playerCx, int playerCz, int lodDistance, LongPredicate inView) {
        if (this.pendingMapOnly > 0) return;
        int payable = this.columns.countMapOwedInRange(playerCx, playerCz, lodDistance, this.settled::contains);
        int waitingInView = payable == 0 ? 0 : this.columns.countMapOwedInRange(playerCx, playerCz, lodDistance,
                p -> this.settled.contains(p) || !inView.test(p));
        if (payable > waitingInView) return; // some still wait for the scan: asked later
        int unfilled = this.settled.isEmpty() ? 0 : this.columns.countMapOwedInRange(playerCx, playerCz,
                lodDistance, p -> !this.settled.contains(p));
        this.quiet = true;
        if (payable == 0 && (unfilled > 0 || !debtLeft())) {
            // The request ends when the map refused what is left here three times (a new
            // press tries again), or when this cache owes the map nothing more. Otherwise
            // it stands: for the columns inside the view, and for terrain the map is
            // missing elsewhere in this cache, which is filled when the player plays there.
            this.requested = false;
            this.requestKnown = true;
        }
        if (this.endOwed) {
            this.endOwed = false;
            this.finish = new Finish(this.delivered - this.deliveredBefore, unfilled, this.requested);
        }
    }

    /** Once, after a fill the player was told about ran out of columns to ask for. */
    Finish pollFinish() {
        Finish result = this.finish;
        this.finish = null;
        return result;
    }

    /** True once per dimension entry: the moment to tell the player what is owed. */
    boolean pollAnnounce() {
        if (this.announced) return false;
        this.announced = true;
        return true;
    }

    long delivered() {
        return this.delivered;
    }

    long withoutData() {
        return this.withoutData;
    }

    long failed() {
        return this.failed;
    }

    long unwritten() {
        return this.unwritten;
    }

    int pendingForTest() {
        return this.pendingMapOnly;
    }

    int askedForTest() {
        return this.askedNow.size();
    }
}
