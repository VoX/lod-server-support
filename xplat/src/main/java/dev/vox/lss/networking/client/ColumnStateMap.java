package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * The single owner of per-column client state: known column timestamps plus the dirty /
 * ingest-failure-retry / validated-this-session marks, with derived received/empty counts.
 * {@link #classify} is the one request-need ladder consulted by the scanner when it builds
 * each want-set.
 *
 * <p><b>Backing (2026-08-19, docs/planning/quadtree-client-state-plan.md):</b> state is
 * stored in dense 8×8-chunk section LEAVES — one {@code long} bitmask per boolean state
 * plus a {@code long[64]} timestamp array — keyed by packed section coordinates, instead
 * of the former eight parallel hash structures (whose worst case at the shipped distance
 * 512 was ~60–100 MB plus rehash storms on bulk loads; the leaves cap out around
 * ~10–12 MB). Each leaf carries a derived {@code needs} mask
 * ({@code bit set ⇔ classify(position) != SATISFIED}) maintained on every state
 * transition, which is what lets {@link SpiralScanner}'s ring walk skip fully-satisfied
 * rings in O(leaves-on-ring) instead of O(positions-on-ring) (the
 * {@code enableQuadtreeScan} fast path — see {@link #ringNeedsFree}). Semantics are
 * unchanged from the hash backing (pinned by ColumnStateMapTest and the
 * SectionStateFuzzTest differential against the retained reference implementation), with
 * ONE deliberate normalization: a stored timestamp below -1 (hostile/corrupt input; the
 * old backing kept it as an inert map entry) is clamped to -1 = absent everywhere, not
 * just in {@link #loadFrom} — strictly the safe direction (the position re-requests).
 *
 * <p>Timestamp semantics: absent (-1) = never seen; &gt;0 = received/validated at that
 * epoch-second. A stored 0 is a LEGACY value only (pre-server-owned-generation caches
 * persisted 0 for not-generated answers) and classifies as "no data" — the client never
 * writes one anymore: a NOT_GENERATED answer is a permanent session-satisfy instead.
 *
 * <p><b>Thread safety:</b> Not thread-safe. Main client thread only — EXCEPT the static
 * {@link #buildLoaded} factory (pure function over its input, used by the cache IO
 * thread) and a {@link #detachForSave() detached} state's leaves, which are owned by the
 * IO thread once handed over.
 */
class ColumnStateMap {

    /** Sentinel returned by {@link #classify} when the position needs no request. */
    static final long SATISFIED = Long.MIN_VALUE;

    /**
     * An 8×8-chunk section of per-column state. Bit index within a leaf is
     * {@code ((cz & 7) << 3) | (cx & 7)} (row-major by z). {@code ts[i] == -1} means
     * absent; {@code positiveTs}/{@code zeroTs} mirror the array ({@code ts[i] > 0} /
     * {@code ts[i] == 0}) so {@link #recomputeNeeds} is pure mask math. An all-clear leaf
     * is equivalent to an absent one (needs = all-ones both ways — an untouched position
     * classifies as a -1 first ask, which IS a need).
     */
    static final class Leaf {
        final long[] ts = new long[64];
        long positiveTs;
        long zeroTs;
        long dirty;
        long retry;
        long validated;
        // Provenance sub-mask of `validated` (final review, client lens MAJOR-2):
        // bits set by a region-summary tile validation, as opposed to the server's
        // per-column onReceived/onUpToDate proofs. A summary may only REVOKE its own
        // bits — a coarse tile stamp must never downgrade a per-column terminal
        // answer, or one warm-rejoin frame revokes the whole freshly-answered disc.
        // Invariant: summaryValidated is a subset of validated.
        long summaryValidated;
        long sessionSatisfied;
        long staleInFlight;
        /** Derived: bit set ⇔ classify(position) would return non-SATISFIED. */
        long needs;

        Leaf() {
            java.util.Arrays.fill(this.ts, -1L);
            recomputeNeeds(); // all-ones: every untouched position is a -1 first ask
        }

        boolean isClear() {
            return (this.positiveTs | this.zeroTs | this.dirty | this.retry
                    | this.validated | this.sessionSatisfied | this.staleInFlight) == 0;
        }

        /**
         * The classify ladder as mask algebra — MUST mirror {@link #classify} exactly
         * (the needs-invariant fuzz pins the equivalence bit-for-bit):
         * dirty always needs; else sessionSatisfied never needs; else no positive ts,
         * a retry mark, or a not-yet-validated stamp all need.
         */
        void recomputeNeeds() {
            this.needs = this.dirty
                    | (~this.sessionSatisfied & (~this.positiveTs | this.retry | ~this.validated));
        }
    }

    /** Leaves keyed by {@code packPosition(cx >> 3, cz >> 3)}. */
    private final Long2ObjectOpenHashMap<Leaf> leaves = new Long2ObjectOpenHashMap<>();

    // One-entry leaf memo: ring walks touch the same leaf for up to 8 consecutive
    // positions, so this turns most per-position lookups into a long compare. Must be
    // invalidated by every structural change (prune/clear/adopt/detach).
    private Leaf memoLeaf;
    private long memoKey;

    // Per-position side state that is genuinely sparse (bounded by failures/clears, never
    // by the disc) stays in plain maps — see the field comments below.

    // Per-position ingest-failure counts; bounds the reject -> re-serve loop a permanently
    // failing consumer would otherwise drive forever (see onIngestFailed).
    private final Long2IntOpenHashMap ingestFailures = new Long2IntOpenHashMap();
    /** Positions parked at {@link #MAX_INGEST_FAILURES} this session — the definitive
     *  "this hole is now permanent" signal. Provenance-free: ANY failure source
     *  (a consumer rejection, a decode drop, a §12 bridge drop report) counts
     *  toward the cap — a nonzero value says the re-serve belt exhausted
     *  somewhere, not which consumer drove it. */
    private long ingestParked;

    long ingestParkedCount() {
        return this.ingestParked;
    }
    // Positions whose last authoritative delivery was a 0-section CLEAR (content->air), mapped to
    // the PRE-CLEAR content stamp. If the consumer rejects a clear, onIngestFailed must re-request
    // with that pre-clear stamp (a real server-issued value, < the server's cached clear stamp) so
    // the server re-sends the clearing column — a ts=-1 re-request would draw an all-air up_to_date
    // (a clear is only sent for claimsData/ts>0), stranding ghost terrain. Content supersedes.
    private final Long2LongOpenHashMap clearedResync = new Long2LongOpenHashMap();
    // Honesty removals awaiting persistence: positions whose stamp was deliberately DELETED
    // (ingest-failure unstamp, legacy-0 purge) rather than range-pruned. The merge-on-save
    // cache path preserves file entries the movement prune dropped from memory, so without
    // this record a deliberate removal would be resurrected from the file and answer a false
    // up_to_date next session — the delivery-honesty hole re-opened from disk. Accumulates
    // for the session, ≤ one entry per DISTINCT failed/purged position: normally a handful,
    // but a permanently-rejecting consumer (incompatible mod) during hours of travel is one
    // entry per visited position — memory-growth-only, saves are teardown-frequency.
    // NOT pruned by distance — pruning it would resurrect any removal whose position the
    // player walked away from before the next save.
    private LongOpenHashSet persistentRemovals = new LongOpenHashSet();

    // Derived counts, maintained on every transition (the old backing's receivedCount/
    // emptyCount plus the set sizes the hash sets used to answer for free).
    private int receivedCount;
    private int emptyCount;
    private int entryCount;   // positions with a stored (non-absent) timestamp
    private int dirtyCount;
    private int retryCount;
    private int sessionSatisfiedCount;

    // --- leaf addressing ---

    private static long leafKeyFor(long packed) {
        return PositionUtil.packPosition(PositionUtil.unpackX(packed) >> 3,
                PositionUtil.unpackZ(packed) >> 3);
    }

    private static int bitIndexFor(long packed) {
        return ((PositionUtil.unpackZ(packed) & 7) << 3) | (PositionUtil.unpackX(packed) & 7);
    }

    private static long positionFor(long leafKey, int bit) {
        int cx = (PositionUtil.unpackX(leafKey) << 3) | (bit & 7);
        int cz = (PositionUtil.unpackZ(leafKey) << 3) | (bit >> 3);
        return PositionUtil.packPosition(cx, cz);
    }

    private Leaf leafFor(long packed) {
        long key = leafKeyFor(packed);
        if (this.memoLeaf != null && this.memoKey == key) return this.memoLeaf;
        Leaf leaf = this.leaves.get(key);
        if (leaf != null) {
            this.memoLeaf = leaf;
            this.memoKey = key;
        }
        return leaf;
    }

    private Leaf leafForCreate(long packed) {
        long key = leafKeyFor(packed);
        if (this.memoLeaf != null && this.memoKey == key) return this.memoLeaf;
        Leaf leaf = this.leaves.get(key);
        if (leaf == null) {
            leaf = new Leaf();
            this.leaves.put(key, leaf);
        }
        this.memoLeaf = leaf;
        this.memoKey = key;
        return leaf;
    }

    private void invalidateMemo() {
        this.memoLeaf = null;
    }

    /**
     * Store a timestamp (the old backing's {@code put}), keeping the mirror masks and the
     * derived counts in step. {@code -1} = remove; values below -1 clamp to -1 (see the
     * class javadoc's normalization note).
     */
    private void tsPut(Leaf leaf, int bit, long value) {
        long old = leaf.ts[bit];
        long m = 1L << bit;
        if (old > 0) this.receivedCount--;
        else if (old == 0) this.emptyCount--;
        if (old != -1L) this.entryCount--;
        if (value < -1L) value = -1L;
        leaf.ts[bit] = value;
        leaf.positiveTs &= ~m;
        leaf.zeroTs &= ~m;
        if (value > 0) {
            leaf.positiveTs |= m;
            this.receivedCount++;
            this.entryCount++;
        } else if (value == 0) {
            leaf.zeroTs |= m;
            this.emptyCount++;
            this.entryCount++;
        }
    }

    // Mask mutation helpers — each keeps its derived count exact.

    private void setDirty(Leaf leaf, long m) {
        if ((leaf.dirty & m) == 0) { leaf.dirty |= m; this.dirtyCount++; }
    }

    private void clearDirty(Leaf leaf, long m) {
        if ((leaf.dirty & m) != 0) { leaf.dirty &= ~m; this.dirtyCount--; }
    }

    private void setRetry(Leaf leaf, long m) {
        if ((leaf.retry & m) == 0) { leaf.retry |= m; this.retryCount++; }
    }

    private void clearRetry(Leaf leaf, long m) {
        if ((leaf.retry & m) != 0) { leaf.retry &= ~m; this.retryCount--; }
    }

    private void setSessionSatisfied(Leaf leaf, long m) {
        if ((leaf.sessionSatisfied & m) == 0) { leaf.sessionSatisfied |= m; this.sessionSatisfiedCount++; }
    }

    private void clearSessionSatisfied(Leaf leaf, long m) {
        if ((leaf.sessionSatisfied & m) != 0) { leaf.sessionSatisfied &= ~m; this.sessionSatisfiedCount--; }
    }

    /**
     * Decide whether a position needs a request and which clientTimestamp to send.
     * Priority, highest first: dirty &gt; session-satisfied &gt; unknown &gt; ingest-failure
     * retry &gt; revalidation. There is no generation rung: the client never classifies
     * generation — the server decides it on the disk miss (server-owned generation).
     *
     * @return the timestamp to send (-1 no data, &gt;0 resync),
     *         or {@link #SATISFIED} when nothing should be sent
     */
    long classify(long packed) {
        Leaf leaf = leafFor(packed);
        if (leaf == null) return -1L; // untouched: no marks, no data — a -1 first ask
        int bit = bitIndexFor(packed);
        long m = 1L << bit;
        // Dirty outranks EVERYTHING (incl. sessionSatisfied): a server-pushed change must
        // re-request even a settled all-air/parked position, or an air->content edit becomes a
        // permanent hole. An unknown-but-dirty position re-asks as a first serve (-1); a legacy
        // 0-stamp (pre-server-owned-generation cache) normalizes to -1 the same way.
        if ((leaf.dirty & m) != 0) {
            long dirtyStored = leaf.ts[bit];
            return dirtyStored <= 0L ? -1L : dirtyStored;
        }
        if ((leaf.sessionSatisfied & m) != 0) return SATISFIED; // resolved this session, no server data
        long stored = leaf.ts[bit];
        // No data: absent (-1) or a LEGACY not-generated 0-stamp loaded from a released
        // client's cache — both declare -1 ("I have nothing"); the client never emits 0.
        if (stored <= 0L) return -1L;
        // Ingest-failure retry. LOAD-BEARING for the region-summary path (P2 client
        // review minor-2): the lost-CLEAR failure flavour re-stamps with a positive
        // clearPreStamp, so applyTileValidation can legitimately set `validated` on a
        // retry-marked position — this rung outranking `validated` is the ONLY thing
        // keeping such a rejected column re-asking. Do not demote it back to
        // defence-in-depth.
        if ((leaf.retry & m) != 0) return stored;
        if ((leaf.validated & m) == 0) return stored; // Cached but not validated this session
        return SATISFIED;
    }

    /**
     * The {@code enableQuadtreeScan} fast path's ring test: true when every leaf the
     * Chebyshev ring {@code r} around ({@code px},{@code pz}) crosses has an all-clear
     * {@code needs} mask — i.e. every position on the ring classifies SATISFIED, so the
     * scanner may confirm the ring without touching its positions. Conservative by leaf:
     * any needs bit anywhere in a crossed leaf (even off-ring) returns false and the ring
     * walks per-position exactly as before. An ABSENT leaf is all-needs (untouched
     * positions are -1 first asks) and returns false. O(ring-perimeter / 8) map lookups.
     */
    boolean ringNeedsFree(int px, int pz, int r) {
        int xl0 = (px - r) >> 3, xl1 = (px + r) >> 3;
        int zTop = (pz - r) >> 3, zBot = (pz + r) >> 3;
        for (int sx = xl0; sx <= xl1; sx++) {
            if (leafHasNeeds(sx, zTop) || leafHasNeeds(sx, zBot)) return false;
        }
        int zl0 = zTop, zl1 = zBot;
        int xLeft = xl0, xRight = xl1;
        for (int sz = zl0; sz <= zl1; sz++) {
            if (leafHasNeeds(xLeft, sz) || leafHasNeeds(xRight, sz)) return false;
        }
        return true;
    }

    /**
     * The hybrid walk's residue probe (hybrid-scan-plan.md §2.1): true when every
     * leaf INTERSECTING the chunk rectangle [x0..x1]×[z0..z1] has a clear needs
     * mask. Same conservative partial-leaf convention as {@link #ringNeedsFree}:
     * a leaf straddling the rectangle with needs anywhere — even outside the
     * rectangle — returns false (a needless emit pass, never a wrong skip; the
     * emit pass's own bounds are the clamp), and an ABSENT leaf is all-needs.
     * (This retired {@code regionNeedsFree} — the hybrid residue bounds already
     * carry the lod clamp that method computed itself.)
     */
    boolean rectNeedsFree(int x0, int z0, int x1, int z1) {
        int sx0 = x0 >> 3, sx1 = x1 >> 3;
        int sz0 = z0 >> 3, sz1 = z1 >> 3;
        for (int sx = sx0; sx <= sx1; sx++) {
            for (int sz = sz0; sz <= sz1; sz++) {
                if (leafHasNeeds(sx, sz)) return false;
            }
        }
        return true;
    }

    private boolean leafHasNeeds(int sx, int sz) {
        Leaf leaf = this.leaves.get(PositionUtil.packPosition(sx, sz));
        return leaf == null || leaf.needs != 0;
    }

    /**
     * The region walk's audit rung (region-scan-plan.md §2.2, v1.1 A-6): re-derive the
     * needs mask of every present leaf in the region and report how many CHANGED — a
     * nonzero return means a mutation path forgot {@code recomputeNeeds} (the
     * stranded-forever class the region walk, unlike the legacy per-position walk,
     * cannot heal incidentally). Healing IS the recompute; the caller counts.
     */
    int auditRegionNeeds(int rx, int rz) {
        int healed = 0;
        int baseLeafX = rx << 2;
        int baseLeafZ = rz << 2;
        for (int lx = 0; lx < 4; lx++) {
            for (int lz = 0; lz < 4; lz++) {
                Leaf leaf = this.leaves.get(PositionUtil.packPosition(baseLeafX + lx, baseLeafZ + lz));
                if (leaf == null) continue;
                long before = leaf.needs;
                leaf.recomputeNeeds();
                if (leaf.needs != before) healed++;
            }
        }
        return healed;
    }

    /** One tile's validation outcome (region-summary-sync-plan.md §6): how many stamps
     *  were newly validated, and whether ANY stamped position in the tile ends
     *  UN-validated (the tile is then "stale" — its residue re-declares per column; a
     *  server-proofed position that merely conflicts with the coarse tile stamp is NOT
     *  residue and does not mark the tile). */
    record TileValidation(int newlyValidated, boolean fullyValidated) {}

    /**
     * Region-summary validation (region-summary-sync-plan.md §6): for every STAMPED
     * position in the 32×32-chunk tile at ({@code tileX}, {@code tileZ}), set
     * {@code validated} iff its stamp is STRICTLY above the reported tile stamp
     * {@code stampM} (the wire value already carries the server's serve-latency
     * margin, so this compare inherits the raced-read protection) — and REVOKE
     * summary-set bits otherwise. Load-bearing properties, each pinned:
     * <ul>
     *   <li><b>Two-directional but PROVENANCE-SCOPED</b> (final review, client lens
     *       MAJOR-1/2): a failing compare revokes only bits THIS mechanism set
     *       ({@code summaryValidated}), so a fresher frame corrects a staler one's
     *       over-validation — while the server's per-column {@code onReceived}/
     *       {@code onUpToDate} proofs are strictly stronger evidence than a coarse
     *       tile stamp and are never downgraded by one (revoking them would turn
     *       every field warm-rejoin — where recent regions' headers postdate the
     *       client's stamps — into a full-disc redundant re-declaration). Ordering
     *       note: fresh-then-stale frame delivery is unreachable in-session (one
     *       MIN_PRIORITY sweeper assembling in admission order, a FIFO ready queue,
     *       TCP, latest-wins buffering), so a stale frame can only OVER-validate —
     *       which the next fresher frame now corrects, and which classify's dirty
     *       rung outranks meanwhile.</li>
     *   <li><b>Revocations are reported to the caller</b> ({@code revokedOut}) so the
     *       manager can reopen their rings — a revoked position below the scanner's
     *       confirmed prefix is otherwise structurally outside both walk intervals
     *       and never re-declares (the orphan the final review demonstrated).</li>
     *   <li><b>Mark-preserving</b>: touches ONLY validated bits — dirty and retry
     *       marks survive, and dirty outranks validated in {@link #classify}, so a
     *       dirty notice racing the summary in either order still re-asks.</li>
     *   <li><b>Never creates leaves</b>: a hostile/stale frame must not allocate;
     *       unstamped positions (all-air 0-stamps, session-satisfied, pruned, fresh
     *       installs) are untouchable by construction. Session-satisfied positions
     *       are excluded even when they retain a positive stamp.</li>
     *   <li><b>Sentinel handling is the CALLER's job</b>: pass only real margined
     *       stamps; BOTH sentinels must be skipped before this call — NEVER_CLEAN
     *       (doubt) and NO_REGION (no evidence: a region absent from a booted
     *       server's listing may have been deleted while the server was OFF, and
     *       validating cached stamps against "no file" seals that deleted terrain
     *       forever — the final honesty review's MAJOR-1).</li>
     * </ul>
     * A 32×32 tile is exactly 4×4 leaves; the walk is mask math + one array scan per
     * stamped-position-bearing leaf.
     */
    TileValidation applyTileValidation(int tileX, int tileZ, long stampM,
                                       java.util.function.LongConsumer revokedOut) {
        int validated = 0;
        boolean fully = true;
        int lx0 = tileX << 2, lz0 = tileZ << 2;
        for (int lz = lz0; lz < lz0 + 4; lz++) {
            for (int lx = lx0; lx < lx0 + 4; lx++) {
                long leafKey = PositionUtil.packPosition(lx, lz);
                Leaf leaf = this.leaves.get(leafKey);
                if (leaf == null) continue;
                long stamped = leaf.positiveTs & ~leaf.sessionSatisfied;
                if (stamped == 0) continue;
                long setBits = 0, clearBits = 0;
                for (long m = stamped; m != 0; m &= m - 1) {
                    int bit = Long.numberOfTrailingZeros(m);
                    long b = 1L << bit;
                    if (leaf.ts[bit] > stampM) {
                        if ((leaf.validated & b) == 0) setBits |= b;
                    } else if ((leaf.validated & b) == 0) {
                        fully = false; // unvalidated residue — re-declares per column
                    } else if ((leaf.summaryValidated & b) != 0) {
                        fully = false;
                        clearBits |= b; // revoke ONLY the summary's own earlier claim
                        if (revokedOut != null) revokedOut.accept(positionFor(leafKey, bit));
                    }
                    // else: a per-column server proof outranks the coarse tile stamp.
                }
                if (setBits != 0 || clearBits != 0) {
                    leaf.validated = (leaf.validated | setBits) & ~clearBits;
                    leaf.summaryValidated = (leaf.summaryValidated | setBits) & ~clearBits;
                    leaf.recomputeNeeds();
                    validated += Long.bitCount(setBits);
                }
            }
        }
        return new TileValidation(validated, fully);
    }

    /** Test convenience: the production caller always passes a revocation consumer. */
    TileValidation applyTileValidation(int tileX, int tileZ, long stampM) {
        return applyTileValidation(tileX, tileZ, stampM, null);
    }

    /**
     * Stamped up_to_date (stamped-up-to-date-plan.md §4): ratchet a column's cached
     * acquisition stamp forward to the server's verification second — a PURE ts
     * write, monotonic, so the next summary tile compare validates a column a later
     * header write once passed. Everything else is deliberately untouched:
     *
     * <ul>
     * <li>Never creates leaves (a hostile frame must not allocate — the
     *     applyTileValidation rule) and only ever advances an EXISTING positive
     *     stamp: ts<=0 positions carry no claim to extend, and the ingest-failure
     *     lost-CONTENT path unstamps to -1, so a late frame cannot re-stamp a
     *     rejected column.</li>
     * <li>Dirty/retry-marked positions are SKIPPED, and so are sessionSatisfied ones
     *     (the applyTileValidation exclusion, mirrored — the ingest-failure lost-CLEAR
     *     park retains a positive pre-clear stamp under sessionSatisfied, and
     *     ratcheting it past the server's cached clear stamp would be the F2 ghost
     *     seal). ORDERING (3-Opus fold, corrected): the stamps frame flushes BEFORE
     *     the batch response and FIFO delivery preserves that, so the ratchet runs
     *     while the marks are STILL SET — this skip is live armor for the same-tick
     *     pair, pinned by the flow test's order case. It is still defense-in-depth,
     *     not the load-bearing protection: the server-side rung narrowing (only the
     *     tscache and header rungs stamp, never the done-bit rung) is what closes
     *     the F2 chain by construction. Do not "simplify" either layer away.</li>
     * <li>validated/summaryValidated are untouched — the ordinary onUpToDate path
     *     owns those bits; needs membership is unchanged by a ts VALUE change on an
     *     already-stamped position, so the derived mask stays consistent by
     *     construction.</li>
     * </ul>
     *
     * @return true when the stamp actually advanced (the diag "applied" counter).
     */
    boolean ratchetStamp(long packed, long second) {
        if (second <= 0) return false; // armor: the ts write below bypasses tsPut
        Leaf leaf = leafFor(packed);
        if (leaf == null) return false;
        int bit = bitIndexFor(packed);
        long b = 1L << bit;
        if ((leaf.positiveTs & b) == 0) return false;
        if ((leaf.dirty & b) != 0 || (leaf.retry & b) != 0) return false;
        if ((leaf.sessionSatisfied & b) != 0) return false;
        if (second <= leaf.ts[bit]) return false;
        leaf.ts[bit] = second;
        return true;
    }

    /** Test seam: the "never creates leaves" pin's observable. */
    int leafCountForTest() {
        return this.leaves.size();
    }

    /** Test seam: the raw per-position needs bit (no leaf = a -1 first ask = needs).
     *  The fuzz differential asserts this bit-for-bit against classify — the
     *  aggregated ringNeedsFree probes alone could not see a stuck-OFF bit while any
     *  sibling bit in the leaf legitimately held the aggregate up (final panel). */
    boolean needsBitForTest(long packed) {
        Leaf leaf = leafFor(packed);
        if (leaf == null) return true;
        return (leaf.needs & (1L << bitIndexFor(packed))) != 0;
    }

    /** Test seam: force the position's needs bit OFF against its true state — the
     *  stranded-orphan corruption the region walk's audit rung exists to heal
     *  (region-scan-plan.md §2.2 v1.1 A-6). Creates the leaf if absent. Tests only. */
    void corruptNeedsBitForTest(long packed) {
        Leaf leaf = leafForCreate(packed);
        leaf.needs &= ~(1L << bitIndexFor(packed));
    }

    void markSessionSatisfied(long packed) {
        Leaf leaf = leafForCreate(packed);
        setSessionSatisfied(leaf, 1L << bitIndexFor(packed));
        leaf.recomputeNeeds();
    }

    boolean isSessionSatisfied(long packed) {
        Leaf leaf = leafFor(packed);
        return leaf != null && (leaf.sessionSatisfied & (1L << bitIndexFor(packed))) != 0;
    }

    int sessionSatisfiedCount() { return this.sessionSatisfiedCount; }

    /**
     * Record that a dirty broadcast crossed a position while its FIRST serve was in flight
     * (stored == -1, so {@link #markDirtyIfKnown} could not record it). Resolved at the
     * request's terminal outcome, forcing a re-request because the arriving column predates
     * the edit. A no-op when the position is not in flight (the dirty is handled normally).
     */
    void noteStaleIfInFlight(long packed, boolean inFlight) {
        if (inFlight) {
            Leaf leaf = leafForCreate(packed);
            leaf.staleInFlight |= 1L << bitIndexFor(packed);
            // staleInFlight is not a classify input — needs is unchanged.
        }
    }

    /** True (and clears) if this position was dirtied mid-first-serve. */
    boolean resolveStale(long packed) {
        Leaf leaf = leafFor(packed);
        if (leaf == null) return false;
        long m = 1L << bitIndexFor(packed);
        if ((leaf.staleInFlight & m) == 0) return false;
        leaf.staleInFlight &= ~m;
        return true;
    }

    /**
     * Flag that the column just delivered for {@code packed} was an authoritative 0-section
     * CLEAR (content-&gt;air), recording the pre-clear content stamp so a rejected clear can
     * re-request honestly. Called by the networking layer AFTER {@link #onReceived} (which
     * removes any prior flag — content supersedes). A no-op unless the client actually held
     * content before ({@code preClearStamp > 0}); a first serve had nothing to leave a ghost.
     */
    void markAuthoritativeClear(long packed, long preClearStamp) {
        if (preClearStamp > 0) this.clearedResync.put(packed, preClearStamp);
    }

    /**
     * Mark dirty if the client has any recorded disposition for the column (data or a
     * session-satisfied mark). A dirty broadcast means the server just SAVED content
     * there — this is the ONE revival path for a NOT_GENERATED position (which sits in
     * sessionSatisfied with no stamp): without this rescue, a not-generated answer under
     * enableChunkGeneration=false parks the position permanently even after the chunk
     * exists on disk. The re-request goes out with the stored stamp (or -1), which the
     * server routes disk-first and can now serve. Unknown (-1) positions stay unmarked —
     * the scan ladder requests those anyway.
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
        Leaf leaf = leafFor(packed);
        if (leaf == null) return false;
        long m = 1L << bitIndexFor(packed);
        if (((leaf.positiveTs | leaf.zeroTs | leaf.sessionSatisfied) & m) == 0) return false;
        clearSessionSatisfied(leaf, m);
        setDirty(leaf, m);
        leaf.recomputeNeeds();
        return true;
    }

    /**
     * Test seam: seed a retry mark in isolation. Production writes the mark only inside
     * {@link #onIngestFailed} (v17 retired the rate-limit bounce that was the other writer),
     * and that path necessarily rewrites the timestamp too — so tests that pin the retry
     * mark's OWN lifecycle (prune, clear, confirmed-ring reset) need it without the
     * accompanying stamp change. Not called from production; do not add a production caller.
     */
    void markRetry(long packed) {
        Leaf leaf = leafForCreate(packed);
        setRetry(leaf, 1L << bitIndexFor(packed));
        leaf.recomputeNeeds();
    }

    /** Column data arrived (authoritative for the position, even if no longer tracked). */
    void onReceived(long packed, long columnTimestamp) {
        Leaf leaf = leafForCreate(packed);
        int bit = bitIndexFor(packed);
        long m = 1L << bit;
        clearDirty(leaf, m);
        clearSessionSatisfied(leaf, m); // real data supersedes any session-satisfied mark
        // Content supersedes a pending clear flag. A 0-section clear delivery also lands here, so
        // the networking layer re-flags it via markAuthoritativeClear AFTER this call; a content
        // delivery (>=1 section) does not, so the flag stays cleared and a rejection re-asks ts=-1.
        this.clearedResync.remove(packed);
        // An answer supersedes any pending retry. The rate-limit bounce that originally wrote
        // these marks guaranteed no response was coming; it is gone (byte 0 is a metrics-only
        // stub) and the live writer is now onIngestFailed, whose position CAN still have a
        // column in flight when the mark lands. Leaving the mark would re-request every such
        // late-delivered column once and keep confirmedRing pinned at 0 for an extra scan.
        clearRetry(leaf, m);
        tsPut(leaf, bit, columnTimestamp);
        leaf.validated |= m;
        leaf.summaryValidated &= ~m; // upgraded to a per-column proof — un-revocable
        leaf.recomputeNeeds();
    }

    /** Server confirmed the column is current. */
    void onUpToDate(long packed) {
        Leaf leaf = leafForCreate(packed);
        int bit = bitIndexFor(packed);
        long m = 1L << bit;
        clearRetry(leaf, m); // an up-to-date answer supersedes a pending retry (see onReceived)
        // Answer-time mark consumption: under want-set re-declaration marks are no longer consumed
        // at send (markSent is gone) — the terminal answer is their only consumer. A mark consumed
        // at send would classify SATISFIED while the answer was still in flight, so a server-side
        // supersession would lose the edit until the next session. The dirty-crossed-an-in-flight-
        // serve race stays covered by staleInFlight, which re-marks dirty at the terminal outcome.
        clearDirty(leaf, m);
        // Up-to-date must satisfy BOTH unsatisfied states, or classify re-requests forever:
        // -1 (all-air columns never get a VoxelColumn response) and 0 (a not-generated stamp
        // whose chunk resolved as all-air on the server) — in the End this looped ~50 req/s
        // and starved the outer disc. For those the client holds NO server data, so mark it
        // session-satisfied instead of fabricating a client-clock stamp (which persists and
        // reads as a false up_to_date next session). A real >0 position validates as before.
        long stored = leaf.ts[bit];
        if (stored == -1L || stored == 0L) {
            setSessionSatisfied(leaf, m);
            if (stored == 0L) {
                // Purge the legacy 0-stamp at park time: v17 clients never write 0 (asks are
                // >0 resync or -1 no-data), so a stored 0 is a pre-v17 cache artifact — and
                // left in place it persists to the cache file and resurrects every session,
                // immortal. Removing it converts the position to -1/unknown, which asks the
                // SAME thing on the wire (<=0 = "I hold nothing") but finally lets it die.
                tsPut(leaf, bit, -1L);
                this.persistentRemovals.add(packed); // deliberate delete — must reach the file
            }
        } else {
            leaf.validated |= m;
            leaf.summaryValidated &= ~m; // upgraded to a per-column proof — un-revocable
        }
        leaf.recomputeNeeds();
    }

    /**
     * Server cannot serve the column — PERMANENT for the session: the position joins
     * {@code sessionSatisfied} and the spiral never re-asks, regardless of movement. The
     * only revival is a dirty broadcast ({@link #markDirtyIfKnown} fires on the
     * session-satisfied mark and outranks it in classify) — if the chunk later exists,
     * the server's dirty detection pushes it. Any existing stamp stays untouched: a
     * data-claiming client keeps its stale-but-real terrain (mirrors {@link #onUpToDate}'s
     * no-data handling; no fabricated or zeroed stamps).
     */
    void onNotGenerated(long packed) {
        Leaf leaf = leafForCreate(packed);
        int bit = bitIndexFor(packed);
        long m = 1L << bit;
        clearDirty(leaf, m);  // answer-time consumption — see onUpToDate
        clearRetry(leaf, m);
        setSessionSatisfied(leaf, m);
        // Park-time purge of a legacy pre-v17 0-stamp, mirroring onUpToDate: retained, it
        // persists to the cache and resurrects every session (on a gen-disabled server this
        // was the immortal path — onUpToDate never fires for these). A real >0 stamp stays
        // untouched as documented above.
        if (leaf.ts[bit] == 0L) {
            tsPut(leaf, bit, -1L);
            this.persistentRemovals.add(packed); // deliberate delete — must reach the file
        }
        leaf.recomputeNeeds();
    }

    /** Re-serve attempts allowed per position per session before parking it. Under
     *  §12 backpressure (hybrid-scan-plan.md) drop reports are structurally rare
     *  (the taper prevents the drops), so this map stays small again; it remains
     *  the belt that bounds any report loop (defer-expiry churn, a permanently
     *  rejecting consumer) at ~3 re-serves per position. */
    static final int MAX_INGEST_FAILURES = 3;

    /**
     * A column that was stamped received never actually reached a consumer (decode error,
     * consumer rejection, undispatched at disconnect) — forget the stamp so the position
     * re-requests with ts=-1, and mark retry so the scanner's ring reopen makes
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
        Leaf leaf = leafFor(packed);
        long old = leaf == null ? -1L : leaf.ts[bitIndexFor(packed)];
        if (old == -1L) return;
        int bit = bitIndexFor(packed);
        long m = 1L << bit;

        // CAP-parked: any further report is a sibling consumer's echo of the capping
        // delivery (a parked position is never re-declared this session; revival via
        // markDirtyIfKnown removes the mark before any new delivery can be rejected).
        // Absorbing it protects the clear-flavor park's retained pre-clear stamp — without
        // this, a post-park echo re-enters the cap branch, finds clearedResync already
        // removed, and takes the lost-content flavor, destroying the retained stamp and
        // recreating the permanent ghost-terrain hole the retention exists to prevent
        // (2026-08-05 review F2). The counter term scopes the guard to the cap park it
        // describes (three-lens review): sessionSatisfied has two OTHER producers —
        // onNotGenerated deliberately keeps a real >0 stamp whose report must still take
        // the honest lost-content unstamp below (or the false data claim persists to the
        // cache file), and onUpToDate's all-air park is already absorbed by the old == -1
        // guard above. The failure counter survives the park (the cap branch clears the
        // marks, never the count), so a genuine post-park echo reads > MAX here.
        if ((leaf.sessionSatisfied & m) != 0
                && this.ingestFailures.get(packed) > MAX_INGEST_FAILURES) return;

        long clearPreStamp = this.clearedResync.getOrDefault(packed, -1L);
        // Sibling echo of an already-handled lost clear: the pinned invariant is "strikes
        // count DELIVERIES, not consumer reports". The lost-content path absorbs siblings
        // for free (the first report removes the stamp, so echoes hit the old == -1 guard);
        // the clear path restores a >0 stamp, so without this guard each sibling counted a
        // fresh strike — parking after MAX/N failed clear deliveries with N consumers. The
        // first report leaves exactly (stamp == pre-clear stamp) + a live retry mark; a
        // fresh clear delivery always re-sets the stamp to the CLEAR's ts and consumes the
        // retry mark via onReceived before any report of it can arrive, so this cannot
        // absorb a legitimate first strike. (A straggler report for delivery N landing
        // after delivery N+1's onReceived counts against N+1 and then absorbs N+1's own
        // first report — a one-strike under-count that only delays the park; safe
        // direction, narrow window.)
        if (clearPreStamp > 0 && old == clearPreStamp && (leaf.retry & m) != 0) return;

        int priorFailures = this.ingestFailures.addTo(packed, 1);
        if (priorFailures + 1 > MAX_INGEST_FAILURES) {
            this.ingestParked++;
            long parkPreStamp = this.clearedResync.getOrDefault(packed, -1L);
            if (parkPreStamp > 0) {
                // Parking a lost CLEAR: the consumer still HOLDS the pre-clear content (it
                // rejected the 0-section clearing column), so "I hold nothing" (-1) would be
                // the lie here — next session's -1 re-ask draws an all-air up_to_date without
                // a clearing column (clears are only sent for claimsData/ts>0), stranding the
                // ghost terrain PERMANENTLY instead of for one session. Retain the pre-clear
                // stamp: it is a real server-issued value < the cached clear stamp, so next
                // session's re-ask draws the clearing column again and a recovered consumer
                // heals. Counts net zero: put swaps one >0 stamp for another.
                tsPut(leaf, bit, parkPreStamp);
            } else {
                // Park WITHOUT a fabricated or retained >0 stamp: the consumer never stored the
                // data, so claiming ts>0 next session would be the same lie that causes a
                // permanent hole. Drop the timestamp to -1 (honest "I hold nothing"). Next
                // session it re-asks -1; a transient failure (storage briefly down) heals, a
                // permanent one (incompatible consumer) costs one re-attempt per session.
                tsPut(leaf, bit, -1L);
                this.persistentRemovals.add(packed); // deliberate delete — must reach the file
            }
            // Either way: session-satisfied so it stops re-downloading THIS session.
            setSessionSatisfied(leaf, m);
            leaf.validated &= ~m;
            leaf.summaryValidated &= ~m;
            clearRetry(leaf, m);
            clearDirty(leaf, m);
            this.clearedResync.remove(packed);
            leaf.recomputeNeeds();
            return;
        }

        if (clearPreStamp > 0) {
            // Lost CLEAR (the consumer rejected a 0-section content->air column). Re-request with
            // the pre-clear content stamp — a real server-issued value that is < the server's
            // cached clear stamp, so the up_to_date check fails and the server re-sends the
            // clearing column. Dropping to -1 (the lost-content path below) would instead draw an
            // all-air up_to_date (a clear is only sent for claimsData/ts>0), stranding ghost
            // terrain for the session. Restoring the stamp, not fabricating one, keeps delivery
            // honest. Counts net zero: put swaps one >0 stamp (T_clear) for another (T_content).
            tsPut(leaf, bit, clearPreStamp);
            leaf.validated &= ~m;
            leaf.summaryValidated &= ~m;
            clearDirty(leaf, m);
            setRetry(leaf, m);
            leaf.recomputeNeeds();
            return;
        }

        // Lost CONTENT: forget the stamp (honest "I hold nothing") so the server re-serves on ts=-1.
        tsPut(leaf, bit, -1L);
        this.persistentRemovals.add(packed); // deliberate delete — must reach the file
        leaf.validated &= ~m;
        leaf.summaryValidated &= ~m;
        clearDirty(leaf, m);
        setRetry(leaf, m);
        leaf.recomputeNeeds();
    }

    void pruneOutOfRange(int playerCx, int playerCz, int pruneDistance) {
        int removedEntries = 0;
        var iter = this.leaves.long2ObjectEntrySet().fastIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            long key = entry.getLongKey();
            Leaf leaf = entry.getValue();
            // Leaf bounds in chunk coords (long math — chebyshevDistance's own
            // widen-to-long discipline for extreme coordinates).
            long lx0 = (long) PositionUtil.unpackX(key) << 3, lx1 = lx0 + 7;
            long lz0 = (long) PositionUtil.unpackZ(key) << 3, lz1 = lz0 + 7;
            long adxMin = playerCx < lx0 ? lx0 - playerCx : (playerCx > lx1 ? playerCx - lx1 : 0);
            long adzMin = playerCz < lz0 ? lz0 - playerCz : (playerCz > lz1 ? playerCz - lz1 : 0);
            long minCheb = Math.max(adxMin, adzMin);
            if (minCheb > pruneDistance) {
                // Wholly out of range: identical outcome to the old per-position prune.
                removedEntries += Long.bitCount(leaf.positiveTs | leaf.zeroTs);
                dropLeafCounts(leaf);
                iter.remove();
                continue;
            }
            long adxMax = Math.max(Math.abs(lx0 - playerCx), Math.abs(lx1 - playerCx));
            long adzMax = Math.max(Math.abs(lz0 - playerCz), Math.abs(lz1 - playerCz));
            if (Math.max(adxMax, adzMax) <= pruneDistance) continue; // wholly in range
            // Boundary leaf: position-granular, matching PositionUtil.isOutOfRange exactly
            // (plan v1.1 C-MAJOR-3 — the eviction boundary must be bit-identical to the old
            // backing or the ts>0/ts<=0 declaration mix drifts server-visibly).
            long touched = leaf.positiveTs | leaf.zeroTs | leaf.dirty | leaf.retry
                    | leaf.validated | leaf.sessionSatisfied | leaf.staleInFlight;
            boolean changed = false;
            for (long bits = touched; bits != 0; bits &= bits - 1) {
                int bit = Long.numberOfTrailingZeros(bits);
                long pos = positionFor(key, bit);
                if (!PositionUtil.isOutOfRange(pos, playerCx, playerCz, pruneDistance)) continue;
                long m = 1L << bit;
                if (leaf.ts[bit] != -1L) removedEntries++;
                tsPut(leaf, bit, -1L);
                clearDirty(leaf, m);
                clearRetry(leaf, m);
                clearSessionSatisfied(leaf, m);
                leaf.validated &= ~m;
                leaf.summaryValidated &= ~m;
                leaf.staleInFlight &= ~m;
                changed = true;
            }
            if (changed) {
                if (leaf.isClear()) iter.remove();
                else leaf.recomputeNeeds();
            }
        }
        invalidateMemo();
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
        // persistentRemovals is deliberately NOT pruned — a deliberate delete must reach the
        // next merge-save even after the player walks away from the position (see the field).
        // The old backing trimmed its hash arrays here when a prune removed more than what
        // remained (a big cache-file load pruned down to the local disc kept its peak-size
        // array resident all session). Leaves make that ~automatic — dropping a leaf frees
        // its 600-byte block — so only the leaf MAP and the side maps still want the trim.
        if (removedEntries > this.entryCount) {
            this.leaves.trim();
            this.ingestFailures.trim();
            this.clearedResync.trim();
        }
    }

    private void dropLeafCounts(Leaf leaf) {
        this.receivedCount -= Long.bitCount(leaf.positiveTs);
        this.emptyCount -= Long.bitCount(leaf.zeroTs);
        this.entryCount -= Long.bitCount(leaf.positiveTs | leaf.zeroTs);
        this.dirtyCount -= Long.bitCount(leaf.dirty);
        this.retryCount -= Long.bitCount(leaf.retry);
        this.sessionSatisfiedCount -= Long.bitCount(leaf.sessionSatisfied);
    }

    void clear() {
        this.leaves.clear();
        this.ingestFailures.clear();
        this.clearedResync.clear();
        this.persistentRemovals.clear();
        this.receivedCount = 0;
        this.emptyCount = 0;
        this.entryCount = 0;
        this.dirtyCount = 0;
        this.retryCount = 0;
        this.sessionSatisfiedCount = 0;
        invalidateMemo();
    }

    // --- bulk load / save (the cache lifecycle; docs/planning/quadtree-client-state-plan.md §3.4) ---

    /**
     * The off-thread-buildable half of a cache load: timestamps already in leaf shape.
     * Built by {@link #buildLoaded} on the cache IO thread (A1 — the old per-entry
     * {@code put()} apply cost 100-300 ms of render thread at the 2M-entry cap), adopted
     * wholesale by {@link #adoptLoaded} on the main thread in O(leaves).
     */
    static final class LoadedState {
        final Long2ObjectOpenHashMap<Leaf> leaves;
        /** Positions whose file value carried no data claim — an explicit -1 row (a
         *  v0.11.1 client could persist the old backing's inert clamped entries) or
         *  CORRUPT (below -1, clamped). Either must still OVERWRITE any live stamp at
         *  adopt time — file-wins is the loadFrom contract, and the old backing's
         *  put(-1) deleted the live claim. A dropped entry would silently keep it (the
         *  fuzz caught the corrupt flavor; review round 2 caught the exact--1 flavor). */
        final it.unimi.dsi.fastutil.longs.LongArrayList clampedToAbsent;

        private LoadedState(Long2ObjectOpenHashMap<Leaf> leaves,
                            it.unimi.dsi.fastutil.longs.LongArrayList clampedToAbsent) {
            this.leaves = leaves;
            this.clampedToAbsent = clampedToAbsent;
        }
    }

    /**
     * Build a {@link LoadedState} from a raw cache-file map. Pure function — safe on the
     * IO thread. Applies the load clamp: a corrupt/garbage stamp below the -1 "unknown"
     * sentinel (a truncated cache file, or negative v2-migration sign-extension) is
     * DROPPED (the old backing stored it as an inert -1 entry): it matches no classify
     * rung either way and the position re-requests as -1.
     */
    static LoadedState buildLoaded(Long2LongOpenHashMap loaded) {
        var built = new Long2ObjectOpenHashMap<Leaf>(Math.max(16, loaded.size() / 32));
        var clamped = new it.unimi.dsi.fastutil.longs.LongArrayList();
        var iter = loaded.long2LongEntrySet().fastIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            long ts = entry.getLongValue();
            if (ts <= -1L) {
                // Claim-free row (explicit -1 or corrupt-below): still a file-wins
                // WRITE against any live stamp (see LoadedState.clampedToAbsent).
                clamped.add(entry.getLongKey());
                continue;
            }
            long packed = entry.getLongKey();
            long key = leafKeyFor(packed);
            Leaf leaf = built.get(key);
            if (leaf == null) {
                leaf = new Leaf();
                built.put(key, leaf);
            }
            int bit = bitIndexFor(packed);
            leaf.ts[bit] = ts;
            long m = 1L << bit;
            if (ts > 0) leaf.positiveTs |= m;
            else leaf.zeroTs |= m;
        }
        for (Leaf leaf : built.values()) {
            leaf.recomputeNeeds();
        }
        return new LoadedState(built, clamped);
    }

    /**
     * Adopt an off-thread-built cache load. A leaf with no live twin transfers wholesale
     * (O(1)); a live twin (rare — the cache gate defers scanning until this ran, so live
     * state is at most a few dirty marks from early broadcasts) merges per position with
     * the same file-stamp-wins semantics as the old {@code loadFrom} (which only ever
     * touched timestamps, never marks).
     */
    void adoptLoaded(LoadedState loaded) {
        var iter = loaded.leaves.long2ObjectEntrySet().fastIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            Leaf incoming = entry.getValue();
            Leaf live = this.leaves.get(entry.getLongKey());
            if (live == null) {
                this.leaves.put(entry.getLongKey(), incoming);
                this.receivedCount += Long.bitCount(incoming.positiveTs);
                this.emptyCount += Long.bitCount(incoming.zeroTs);
                this.entryCount += Long.bitCount(incoming.positiveTs | incoming.zeroTs);
                continue;
            }
            for (long bits = incoming.positiveTs | incoming.zeroTs; bits != 0; bits &= bits - 1) {
                int bit = Long.numberOfTrailingZeros(bits);
                tsPut(live, bit, incoming.ts[bit]);
            }
            live.recomputeNeeds();
        }
        // Corrupt-clamped file entries still overwrite (delete) any live stamp — the
        // file-wins contract (see LoadedState.clampedToAbsent). Absent leaves need
        // nothing: there is no live claim to delete.
        for (int i = 0; i < loaded.clampedToAbsent.size(); i++) {
            long packed = loaded.clampedToAbsent.getLong(i);
            Leaf live = this.leaves.get(leafKeyFor(packed));
            if (live != null) {
                tsPut(live, bitIndexFor(packed), -1L);
                live.recomputeNeeds();
            }
        }
        invalidateMemo();
    }

    /** Bulk-load cached timestamps (resync across sessions). Test/compat form of
     *  {@link #adoptLoaded} — production loads build off-thread via {@link #buildLoaded}. */
    void loadFrom(Long2LongOpenHashMap loaded) {
        adoptLoaded(buildLoaded(loaded));
    }

    /**
     * The state a merge-save consumes, detached from the live map: the leaves (timestamp
     * source) and the session's deliberate stamp deletions. Owned by the IO thread after
     * {@link #detachForSave}.
     */
    static final class DetachedState {
        final Long2ObjectOpenHashMap<Leaf> leaves;
        final LongOpenHashSet removals;
        final int entries;

        private DetachedState(Long2ObjectOpenHashMap<Leaf> leaves, LongOpenHashSet removals, int entries) {
            this.leaves = leaves;
            this.removals = removals;
            this.entries = entries;
        }

        boolean isEmpty() { return this.entries == 0 && this.removals.isEmpty(); }

        /** Primitive visitor — a 2M-entry save must not box its way through the overlay. */
        interface TsVisitor {
            void accept(long packed, long ts);
        }

        /** Visit every stored (non-absent) timestamp. IO-thread use. */
        void forEachPresent(TsVisitor visitor) {
            var iter = this.leaves.long2ObjectEntrySet().fastIterator();
            while (iter.hasNext()) {
                var entry = iter.next();
                Leaf leaf = entry.getValue();
                for (long bits = leaf.positiveTs | leaf.zeroTs; bits != 0; bits &= bits - 1) {
                    int bit = Long.numberOfTrailingZeros(bits);
                    visitor.accept(positionFor(entry.getLongKey(), bit), leaf.ts[bit]);
                }
            }
        }
    }

    /**
     * Hand the whole per-column state (leaves + persistent removals) to a save and start
     * fresh — the A2 fix: the old path copied a 1–2M-entry map on the render thread at
     * every dimension change/disconnect (50–150 ms). DESTRUCTIVE by contract: both
     * production callers ({@code onDimensionChange}, the session-gate teardown) clear or
     * drop this map immediately after saving, so the ownership transfer replaces a copy
     * they were about to discard anyway. Side maps (ingestFailures/clearedResync) are
     * untouched — the caller's follow-up {@code clear()} owns those, exactly as before.
     */
    DetachedState detachForSave() {
        var detachedLeaves = new Long2ObjectOpenHashMap<>(this.leaves);
        var detachedRemovals = this.persistentRemovals;
        int entries = this.entryCount;
        this.leaves.clear();
        this.persistentRemovals = new LongOpenHashSet();
        this.receivedCount = 0;
        this.emptyCount = 0;
        this.entryCount = 0;
        this.dirtyCount = 0;
        this.retryCount = 0;
        this.sessionSatisfiedCount = 0;
        invalidateMemo();
        return new DetachedState(detachedLeaves, detachedRemovals, entries);
    }

    /** The stored timestamps as a fresh map — test-facing (production saves detach; see
     *  {@link #detachForSave}). */
    Long2LongOpenHashMap mapForSave() {
        var map = new Long2LongOpenHashMap(Math.max(16, this.entryCount));
        map.defaultReturnValue(-1L);
        var iter = this.leaves.long2ObjectEntrySet().fastIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            Leaf leaf = entry.getValue();
            for (long bits = leaf.positiveTs | leaf.zeroTs; bits != 0; bits &= bits - 1) {
                int bit = Long.numberOfTrailingZeros(bits);
                map.put(positionFor(entry.getLongKey(), bit), leaf.ts[bit]);
            }
        }
        return map;
    }

    /**
     * The session's deliberate stamp deletions, for the merge-save's removal pass (applied
     * to the FILE before the in-memory overlay, so a position re-received after its removal
     * survives via the overlay). This TEST-FACING accessor does not drain; the PRODUCTION
     * save path drains by ownership transfer ({@link #detachForSave}), so a failed IO-thread
     * write loses the detached removals — the same exposure the old copy-then-clear callers
     * had (both cleared the live set right after the async copy), and the same safe-direction
     * outcome: over-deletion of a file entry re-served since a prior save costs one honest
     * re-request on the next visit.
     */
    LongOpenHashSet persistentRemovalsForSave() {
        return this.persistentRemovals;
    }

    boolean hasPersistentRemovals() { return !this.persistentRemovals.isEmpty(); }

    /** Raw stored timestamp for one position: -1 absent, 0 a legacy pre-v17 cache artifact
     *  (never written by v17 clients; purged at park — see onUpToDate), &gt;0 received. */
    long timestampFor(long packed) {
        Leaf leaf = leafFor(packed);
        return leaf == null ? -1L : leaf.ts[bitIndexFor(packed)];
    }

    boolean isEmptyMap() { return this.entryCount == 0; }
    boolean hasRetries() { return this.retryCount > 0; }

    /**
     * True when some retry mark lies OUTSIDE the vanilla-view exclusion — a mark the
     * scanner's walk can actually reach, declare, and (via a terminal answer) consume.
     * Marks INSIDE the exclusion are parked: excluded positions are never declared, so
     * nothing consumes their marks, and letting them invalidate the confirmed prefix
     * forced a full-distance re-walk every scan (see the call site in
     * {@link SpiralScanner#scan}). A parked mark heals via the per-scan
     * {@link #collectActionableRetryRings} recomputation: the moment the exclusion moves
     * off its position it collects as actionable and its ring reopens (2026-08-18 —
     * movement no longer recenters the walk from ring 0). O(leaves) worst case, O(1)
     * when no retry marks exist (the common case — the count short-circuits).
     */
    boolean hasActionableRetries(int playerCx, int playerCz, int exclusionRadius) {
        if (this.retryCount == 0) return false;
        var iter = this.leaves.long2ObjectEntrySet().fastIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            Leaf leaf = entry.getValue();
            for (long bits = leaf.retry; bits != 0; bits &= bits - 1) {
                int bit = Long.numberOfTrailingZeros(bits);
                long packed = positionFor(entry.getLongKey(), bit);
                if (!SpiralScanner.isVanillaRendered(PositionUtil.unpackX(packed),
                        PositionUtil.unpackZ(packed), playerCx, playerCz, exclusionRadius)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The prefix-retention twin of {@link #hasActionableRetries}: visit the Chebyshev RING
     * (around the player) of every actionable retry mark, so the scanner reopens exactly
     * those rings instead of collapsing the whole confirmed prefix
     * (docs/planning/scanner-reopened-rings-plan.md). SAME actionability ladder as the
     * boolean form — a mark inside the vanilla-view exclusion stays parked (unreachable,
     * unconsumable) and is not visited; the two methods must not drift. Marks at/beyond
     * the confirmed prefix are handed over too — the scanner's {@code reopenRing} skips
     * them for free (the frontier walk covers those rings). Same cost shape as the
     * boolean form; the retry population is small.
     */
    void collectActionableRetryRings(int playerCx, int playerCz, int exclusionRadius,
                                     java.util.function.IntConsumer ringVisitor) {
        if (this.retryCount == 0) return;
        var iter = this.leaves.long2ObjectEntrySet().fastIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            Leaf leaf = entry.getValue();
            for (long bits = leaf.retry; bits != 0; bits &= bits - 1) {
                int bit = Long.numberOfTrailingZeros(bits);
                long packed = positionFor(entry.getLongKey(), bit);
                int cx = PositionUtil.unpackX(packed);
                int cz = PositionUtil.unpackZ(packed);
                if (!SpiralScanner.isVanillaRendered(cx, cz, playerCx, playerCz, exclusionRadius)) {
                    ringVisitor.accept(Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz)));
                }
            }
        }
    }

    int receivedCount() { return this.receivedCount; }
    int emptyCount() { return this.emptyCount; }
    int dirtyCount() { return this.dirtyCount; }
}
