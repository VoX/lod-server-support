package dev.vox.lss.common.compat;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.AbstractPlayerRequestState;
import dev.vox.lss.common.processing.IncomingBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the v16 compat shim's synthetic want-set semantics
 * (docs/planning/v16-compat-design.md §4.2–§4.4, incl. the review-round amendments):
 * ingress merges only and the 1 Hz tick is the SOLE declarer (S1); declares sort by
 * Chebyshev distance from the CURRENT player chunk and re-apply the range filter (S2);
 * pruning is load-bearing and the client's declared ts always wins a re-merge (S3/C2);
 * the 75 s TTL and the ~1 s post-reset ingress grace (C3/C4); overflow bounces; and the
 * reset-on-service-remove vs drop-on-disconnect identity split (S6).
 */
class V16CompatManagerTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final int MAX_DIST = 288;

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState() { super(PLAYER, 4, 2); }
        @Override public String getPlayerName() { return "legacy"; }
    }

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private V16CompatManager mgr;
    private TestState state;

    @BeforeEach
    void setUp() {
        this.mgr = new V16CompatManager(this.clock::get);
        this.state = new TestState();
        this.mgr.onHandshake(PLAYER);
    }

    private static long packed(int cx, int cz) {
        return PositionUtil.packPosition(cx, cz);
    }

    private V16CompatManager.MergeResult merge(int playerCx, int playerCz, long[]... posTs) {
        var positions = new long[posTs.length];
        var timestamps = new long[posTs.length];
        for (int i = 0; i < posTs.length; i++) {
            positions[i] = posTs[i][0];
            timestamps[i] = posTs[i][1];
        }
        return this.mgr.onClientBatch(PLAYER, positions, timestamps, posTs.length,
                playerCx, playerCz, MAX_DIST);
    }

    /** Runs one full declare interval; returns the single batch it offered (or null). Each
     *  invocation crosses exactly one declare tick: the session is primed to declare on the
     *  first tick after creation/reset, and 20 calls re-arm the next one. */
    private IncomingBatch declareInterval(int playerCx, int playerCz) {
        IncomingBatch taken = null;
        for (int i = 0; i < V16CompatManager.DECLARE_INTERVAL_TICKS; i++) {
            this.mgr.tickPlayer(PLAYER, this.state, playerCx, playerCz, MAX_DIST);
            var b = this.state.takeIncomingBatch();
            if (b != null) {
                assertNull(taken, "one declare interval must offer at most once");
                taken = b;
            }
        }
        return taken;
    }

    private static long[] orderedPositions(IncomingBatch batch) {
        var out = new long[batch.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = packed(batch.requests()[i].cx(), batch.requests()[i].cz());
        }
        return out;
    }

    // ---- S1: ingress merges only, the tick is the sole declarer ----

    @Test
    void ingressNeverDeclaresTheTickDoes() {
        var result = merge(0, 0, new long[]{packed(3, 0), -1L});
        assertNotNull(result, "a v16 session's batch must take the compat branch");
        assertNull(this.state.takeIncomingBatch(),
                "S1: ingress must NEVER offer — per-batch offers starve the Folia hold-release CAS");

        var declared = declareInterval(0, 0);
        assertNotNull(declared, "the 1 Hz tick is the sole declarer");
        assertArrayEquals(new long[]{packed(3, 0)}, orderedPositions(declared));
        assertEquals(-1L, declared.requests()[0].clientTimestamp());
        assertEquals(1, this.mgr.totalRedeclares());
    }

    @Test
    void unchangedSetRedeclaresUnconditionallyEachInterval() {
        merge(0, 0, new long[]{packed(2, 0), -1L});
        var first = declareInterval(0, 0);
        var second = declareInterval(0, 0);
        assertNotNull(first);
        assertNotNull(second);
        assertArrayEquals(orderedPositions(first), orderedPositions(second),
                "re-declaring an UNCHANGED set is what heals individual silent drops");
        assertEquals(2, this.mgr.totalRedeclares());
    }

    @Test
    void emptySetDeclaresNothing() {
        assertNull(declareInterval(0, 0), "a converged v16 player must cost zero offers");
        assertEquals(0, this.mgr.totalRedeclares());
    }

    @Test
    void nonV16PlayerIsUntouched() {
        var stranger = UUID.randomUUID();
        assertFalse(this.mgr.isV16(stranger));
        assertNull(this.mgr.onClientBatch(stranger, new long[]{packed(1, 1)}, new long[]{-1L},
                1, 0, 0, MAX_DIST), "a v18 player's batch must fall through to the normal path");
    }

    // ---- S2: declare-time ordering + range re-filter ----

    @Test
    void declareSortsByChebyshevDistanceFromCurrentPlayerChunk() {
        merge(0, 0, new long[]{packed(50, 0), -1L}, new long[]{packed(1, 1), -1L},
                new long[]{packed(10, -10), -1L});
        var declared = declareInterval(0, 0);
        assertArrayEquals(new long[]{packed(1, 1), packed(10, -10), packed(50, 0)},
                orderedPositions(declared),
                "insertion order goes stale on movement — the declare must sort closest-first "
                        + "(the live-frontier stamp and spread gate assume it)");
    }

    @Test
    void declareReAppliesTheRangeFilterAgainstTheCurrentChunkAndRecordsEvictions() {
        merge(0, 0, new long[]{packed(5, 0), -1L}, new long[]{packed(0, 5), -1L});
        // The player moved far away: both entries are now out of range and must be EVICTED,
        // not re-declared forever (the ingress-time filter cannot cover later movement).
        assertNull(declareInterval(10_000, 0), "out-of-range entries must not be declared");
        assertEquals(2, this.state.drainPendingRangeFiltered(),
                "declare-time evictions count as range_filtered on the player state");
        assertNull(declareInterval(10_000, 0), "evicted means evicted — nothing left");
    }

    @Test
    void ingressRangeFilterDropsAndCounts() {
        var result = merge(0, 0, new long[]{packed(2, 0), -1L},
                new long[]{packed(MAX_DIST + 50, 0), -1L});
        assertEquals(1, result.rangeFiltered());
        assertArrayEquals(new long[]{packed(2, 0)}, orderedPositions(declareInterval(0, 0)));
    }

    // ---- S3/C2: prune is load-bearing; the client's ts always wins ----

    @Test
    void columnSentPrunesThePosition() {
        merge(0, 0, new long[]{packed(1, 0), -1L}, new long[]{packed(2, 0), -1L});
        this.mgr.onColumnSent(PLAYER, packed(1, 0));
        assertArrayEquals(new long[]{packed(2, 0)}, orderedPositions(declareInterval(0, 0)),
                "a served ts<=0 entry left in the set re-resolves HONESTLY (full re-serve) "
                        + "every second — the prune is load-bearing, not an optimization");
    }

    @Test
    void batchResponseObservationPrunesTerminalAnswersOnly() {
        merge(0, 0, new long[]{packed(1, 0), -1L}, new long[]{packed(2, 0), -1L},
                new long[]{packed(3, 0), -1L});
        this.mgr.observeBatchResponse(PLAYER,
                new byte[]{LSSConstants.RESPONSE_UP_TO_DATE, LSSConstants.RESPONSE_NOT_GENERATED,
                        LSSConstants.RESPONSE_RATE_LIMITED_V16},
                new long[]{packed(1, 0), packed(2, 0), packed(3, 0)}, 3);
        assertArrayEquals(new long[]{packed(3, 0)}, orderedPositions(declareInterval(0, 0)),
                "UP_TO_DATE and NOT_GENERATED terminally answer; RATE_LIMITED (the shim's own "
                        + "overflow bounce) is a retry signal and must NOT prune");
    }

    @Test
    void clientDeclaredTsAlwaysWinsARemerge() {
        merge(0, 0, new long[]{packed(1, 0), -1L});
        merge(0, 0, new long[]{packed(1, 0), 12_345L});
        assertEquals(12_345L, declareInterval(0, 0).requests()[0].clientTimestamp());
        // ...and back: a client re-ask with -1 ("I have nothing", e.g. after an ingest
        // failure) must never be upgraded — a fabricated ts>0 lets a later UP_TO_DATE seal
        // a column the client failed to ingest (the invisible hole).
        merge(0, 0, new long[]{packed(1, 0), -1L});
        assertEquals(-1L, declareInterval(0, 0).requests()[0].clientTimestamp());
    }

    // ---- C4: the 75 s TTL ----

    @Test
    void ttlEvictsOnlyAfter75SecondsWithoutARefresh() {
        merge(0, 0, new long[]{packed(1, 0), -1L});
        this.clock.addAndGet(74_000_000_000L);
        assertNotNull(declareInterval(0, 0),
                "74 s idle must survive: the old client's 10 s sweep is starved by sustained "
                        + "movement, so a 30 s-class TTL would evict positions it still wants");
        this.clock.addAndGet(2_000_000_000L);
        assertNull(declareInterval(0, 0), "past 75 s with no client re-ask the entry is stale");
    }

    @Test
    void remergeRefreshesTheTtl() {
        merge(0, 0, new long[]{packed(1, 0), -1L});
        this.clock.addAndGet(74_000_000_000L);
        merge(0, 0, new long[]{packed(1, 0), -1L});   // the client still wants it
        this.clock.addAndGet(2_000_000_000L);
        assertNotNull(declareInterval(0, 0), "a client re-ask restarts the entry's TTL");
    }

    // ---- C3/S6: reset + grace vs disconnect ----

    @Test
    void serviceRemoveResetsTheWantSetKeepsIdentityAndArmsTheGrace() {
        merge(0, 0, new long[]{packed(1, 0), -1L});
        this.mgr.onServiceRemove(PLAYER);
        assertTrue(this.mgr.isV16(PLAYER),
                "identity must survive the dim-change remove+register cycle (like capabilities)");

        // An old-dimension straggler batch in netty flight lands inside the grace: discarded.
        this.clock.addAndGet(500_000_000L);
        merge(0, 0, new long[]{packed(2, 0), -1L});
        assertNull(declareInterval(0, 0), "the straggler must not resurrect into the fresh set");
        assertEquals(1, this.mgr.totalGraceDiscarded());

        // The client's real first new-dimension batch arrives after its transition screen.
        this.clock.addAndGet(600_000_000L);
        merge(0, 0, new long[]{packed(3, 0), -1L});
        assertArrayEquals(new long[]{packed(3, 0)}, orderedPositions(declareInterval(0, 0)));
    }

    @Test
    void disconnectDropsIdentity() {
        merge(0, 0, new long[]{packed(1, 0), -1L});
        this.mgr.onDisconnect(PLAYER);
        assertFalse(this.mgr.isV16(PLAYER));
        assertNull(this.mgr.onClientBatch(PLAYER, new long[]{packed(2, 0)}, new long[]{-1L},
                1, 0, 0, MAX_DIST), "post-disconnect batches take the normal v18 path");
        assertEquals(0, this.mgr.sessionCount());
    }

    @Test
    void duplicateHandshakeResetsTheWantSetWithoutGraceAndKeepsIdentity() {
        merge(0, 0, new long[]{packed(1, 0), -1L});
        this.mgr.onHandshake(PLAYER);
        assertTrue(this.mgr.isV16(PLAYER));
        // No grace on a duplicate handshake: the client's own fresh asks follow immediately.
        merge(0, 0, new long[]{packed(2, 0), -1L});
        assertArrayEquals(new long[]{packed(2, 0)}, orderedPositions(declareInterval(0, 0)),
                "the old set is gone, the fresh ask is accepted");
    }

    // ---- Overflow bounce ----

    @Test
    void overflowBeyondTheBudgetBouncesNewPositionsButRefreshesExistingOnes() {
        var fill = new long[LSSConstants.WANT_SET_BUDGET][];
        for (int i = 0; i < fill.length; i++) {
            fill[i] = new long[]{packed(i % 200, i / 200), -1L};
        }
        var fillResult = merge(100, 1, fill);
        assertEquals(0, fillResult.overflowBounced().length, "the budget itself must fit");

        var over = merge(100, 1, new long[]{packed(201, 5), -1L}, new long[]{packed(0, 0), 99L});
        assertArrayEquals(new long[]{packed(201, 5)}, over.overflowBounced(),
                "a NEW position beyond the budget bounces; an EXISTING one refreshes in place");
        assertEquals(1, this.mgr.totalOverflowBounced());

        var declared = declareInterval(100, 1);
        assertEquals(LSSConstants.WANT_SET_BUDGET, declared.size());
        assertEquals(99L, findTs(declared, packed(0, 0)),
                "the at-capacity refresh must have updated the existing entry's ts");
    }

    private static long findTs(IncomingBatch batch, long packedPos) {
        for (var r : batch.requests()) {
            if (packed(r.cx(), r.cz()) == packedPos) return r.clientTimestamp();
        }
        throw new AssertionError("position not declared: " + packedPos);
    }

    // ---- Diagnostics ----

    @Test
    void diagLineIsNullUntilTouchedThenReportsCounters() {
        assertNull(new V16CompatManager().diagLineOrNull(),
                "an untouched shim must not add a diag line");
        merge(0, 0, new long[]{packed(1, 0), -1L});
        declareInterval(0, 0);
        var line = this.mgr.diagLineOrNull();
        assertNotNull(line);
        assertTrue(line.contains("clients=1") && line.contains("redeclares=1"), line);
    }

    // ---- Concurrency smoke: merge vs declare under contention stays consistent ----

    @Test
    void concurrentMergesAndDeclaresNeverLoseAnUnansweredPosition() throws Exception {
        // Folia shape: region-thread merges race the pump's declares. Every position that was
        // merged (and never pruned/evicted) must appear in the final declaration.
        var errors = new ArrayList<Throwable>();
        var mergers = new ArrayList<Thread>();
        for (int t = 0; t < 4; t++) {
            int base = t * 40;
            var thread = new Thread(() -> {
                try {
                    for (int i = 0; i < 40; i++) {
                        merge(0, 0, new long[]{packed(base + i, 1), -1L});
                    }
                } catch (Throwable e) {
                    synchronized (errors) { errors.add(e); }
                }
            });
            mergers.add(thread);
            thread.start();
        }
        for (int i = 0; i < 10; i++) declareInterval(0, 0);
        for (var t : mergers) t.join(5_000);
        assertEquals(List.of(), errors);

        var declared = declareInterval(0, 0);
        assertEquals(160, declared.size(), "no merged-and-unanswered position may be lost");
    }
}
