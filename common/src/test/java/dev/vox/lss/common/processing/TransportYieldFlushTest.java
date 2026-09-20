package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The transport-yield gate suite (vanilla-first-lod-yield-plan.md §6, v0.10.0 stage A2),
 * on the {@code FlushSendQueueTest} template. The two poles: {@code writable → no yield}
 * is the CI-inertness pin (loopback channels never go unwritable under our write sizes,
 * so armed soaks are provably identical), and {@code UNKNOWN never yields} is the
 * fail-safe pin — a legacy bare-lambda probe, a no-signal probe, and a closed channel
 * must all leave the gate inert.
 */
class TransportYieldFlushTest {

    private static final long BIG_ALLOCATION = 1_000_000_000L;
    private static final long POS_1 = PositionUtil.packPosition(10, 0);
    private static final long POS_2 = PositionUtil.packPosition(11, 0);
    private static final long POS_FAR = PositionUtil.packPosition(700, 0);

    private static final class TestState extends AbstractPlayerRequestState<String> {
        TestState() { super(UUID.randomUUID(), 1, 1); }
        @Override public String getPlayerName() { return "yield-test"; }
    }

    /** A probe with a controllable writability snapshot. */
    private static ChannelPressureProbe probe(long pending, ChannelPressureProbe.Writability w) {
        return new ChannelPressureProbe() {
            @Override public long pendingOutboundBytes() { return pending; }
            @Override public Snapshot snapshot() {
                return new Snapshot(pending, 65536, w);
            }
        };
    }

    private final SharedBandwidthLimiter limiter = new SharedBandwidthLimiter(BIG_ALLOCATION);
    private final TickDiagnostics diag = new TickDiagnostics();
    private final List<String> sent = new ArrayList<>();
    private final TestState state = new TestState();

    private long[] flush(boolean yield, int pruneRadius) {
        return state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add, yield, pruneRadius);
    }

    // ---- the gate ----

    @Test
    void writableNeverYields() throws Exception {
        // The CI-inertness pin, DIFFERENTIAL (review C-1 — the F2-1 blocker's regression
        // pin): an armed WRITABLE flush must drain to the limiter exactly like an
        // unarmed one — three payloads leave in ONE tick. A hidden per-tick throttle on
        // the armed arm (the v3 "smoothing cap" lever applied early) fails this and
        // would silently confound the E3 A/B.
        state.setChannelPressureProbe(probe(50_000, ChannelPressureProbe.Writability.WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("a", 10, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("b", 10, 1, POS_2));
        state.addReadyPayload(new QueuedPayload<>("c", 10, 2, POS_FAR));
        Thread.sleep(50);
        flush(true, 0);
        assertEquals(List.of("a", "b", "c"), sent,
                "one armed writable flush drains the whole queue, in order");
        assertEquals(0, state.getSendQueueSize());
        assertEquals(3, diag.getTotalSectionsSent());
        assertEquals(0, state.getYieldedTicks());
        assertEquals(0, diag.getYieldTicksTotal());
    }

    @Test
    void notWritableRetainsCountsSweepsAndNeverStamps() throws Exception {
        var clock = new AtomicLong(0);
        state.setDepartureGraceForTest(500_000_000L, clock::get);
        // Seed one expired departure stamp so the sweep's work is observable.
        state.setChannelPressureProbe(probe(0, ChannelPressureProbe.Writability.WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("pre", 10, 0, POS_2));
        Thread.sleep(50);
        flush(true, 0);
        assertEquals(1, state.departedColumnCountForTest(), "premise: one departure stamped");
        clock.addAndGet(2_000_000_000L);
        sent.clear();

        state.setChannelPressureProbe(probe(120_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("a", 64, 1, POS_1));
        long[] dropped = flush(true, 0);

        assertTrue(sent.isEmpty(), "an unwritable channel withholds the whole flush");
        assertEquals(0, dropped.length, "yield RETAINS — never reports drops");
        assertEquals(1, state.getSendQueueSize(), "queue retained + snapshot published");
        assertEquals(1, state.getYieldedTicks(), "the withheld tick is counted");
        assertEquals(1, diag.getYieldTicksTotal(), "…and service-scoped (survives teardown)");
        assertEquals(64, diag.getYieldBytesWithheldTotal(),
                "the byte-tick integral counts this tick's held bytes");
        assertEquals(0, state.departedColumnCountForTest(),
                "the departed sweep must still run on the yield path");
        assertFalse(state.isProbeSuppressed(POS_1),
                "a yielded tick must not suppress-stamp the held head (S-11's sibling —"
                        + " review C-9): the position was never sent");
        assertEquals(120_000, state.getOutboundPendingBytes(),
                "the ONE probe read still feeds the diag gauge");

        // Channel drains: the retained queue goes out untouched.
        state.setChannelPressureProbe(probe(0, ChannelPressureProbe.Writability.WRITABLE));
        Thread.sleep(50);
        flush(true, 0);
        assertEquals(List.of("a"), sent);
    }

    @Test
    void emptyQueueUnwritableTickIsNotCountedYielded() {
        state.setChannelPressureProbe(probe(120_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
        flush(true, 0);
        assertEquals(0, state.getYieldedTicks(), "an idle-queue tick withheld nothing");
        assertEquals(0, diag.getYieldTicksTotal());
    }

    @Test
    void maxSizePayloadSendsWritableWaitsUnwritableNeverBlockedForever() throws Exception {
        // The retargeted A-1/S-3 pin: a maximum-size payload is only ever written to a
        // WRITABLE channel — it cannot be blocked by a threshold, only waited (and the
        // floor guarantees eventual progress).
        int maxSize = LSSConstants.MAX_SEND_SECTIONS_SIZE;
        state.setChannelPressureProbe(probe(0, ChannelPressureProbe.Writability.WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("big", maxSize, 0, POS_1));
        Thread.sleep(60);
        flush(true, 0);
        assertEquals(List.of("big"), sent, "writable: the max-size payload sends normally");

        sent.clear();
        state.setChannelPressureProbe(probe(500_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("big2", maxSize, 1, POS_2));
        Thread.sleep(60);
        for (int i = 0; i < AbstractPlayerRequestState.YIELD_FLOOR_TICKS - 1; i++) {
            flush(true, 0);
        }
        assertTrue(sent.isEmpty(), "unwritable: it waits");
        flush(true, 0);
        assertEquals(List.of("big2"), sent,
                "the floor sends it on tick " + AbstractPlayerRequestState.YIELD_FLOOR_TICKS
                        + " — never permanently blocked");
    }

    @Test
    void starvationFloorSendsExactlyOnePayloadThenResets() throws Exception {
        state.setChannelPressureProbe(probe(500_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("a", 10, 0, POS_1));
        state.addReadyPayload(new QueuedPayload<>("b", 10, 1, POS_2));
        Thread.sleep(50);
        for (int i = 0; i < AbstractPlayerRequestState.YIELD_FLOOR_TICKS - 1; i++) {
            flush(true, 0);
        }
        assertTrue(sent.isEmpty(), "no floor before the threshold");
        flush(true, 0);
        assertEquals(List.of("a"), sent, "the floor sends EXACTLY ONE payload — b stays queued");
        assertEquals(1, state.getSendQueueSize());
        // §1.4 "charges the limiter normally" = the FULL send path (review C-2): the
        // enqueued mark released (a miss here is a permanent per-position LOD hole via
        // skipProbe/resolvedAsDuplicate), the departure stamped, the diag counted.
        assertFalse(state.hasEnqueuedColumn(POS_1),
                "the floor payload must release its enqueued mark");
        assertEquals(1, state.departedColumnCountForTest(),
                "the floor payload departs like any send — the grace stamp fires");
        assertEquals(1, diag.getTotalSectionsSent(),
                "the floor payload is counted like any send");
        // The counter reset: another full window before the next floor send.
        for (int i = 0; i < AbstractPlayerRequestState.YIELD_FLOOR_TICKS - 1; i++) {
            flush(true, 0);
        }
        assertEquals(List.of("a"), sent, "the floor cadence is one payload per window");
        flush(true, 0);
        assertEquals(List.of("a", "b"), sent);
    }

    @Test
    void refusedTickDoesNotResetTheFloorCounter() throws Exception {
        // Review A-1/B-5 (re-pinned here after the AUTO suite's only such test was
        // deleted with the mechanism): a zero-allocation tick with queued work resets
        // NOTHING — the floor counter resets only on send-success or empty-queue-at-
        // entry. A revert to the pre-v2 any-non-yield-tick reset would let refused
        // ticks silently spend the starvation window.
        state.setChannelPressureProbe(probe(500_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("a", 10, 0, POS_1));
        Thread.sleep(50);
        int half = AbstractPlayerRequestState.YIELD_FLOOR_TICKS / 2;
        for (int i = 0; i < half; i++) {
            flush(true, 0);
        }
        // A WRITABLE tick whose allocation is 0: the limiter refuses, nothing sends,
        // the queue stays — and the counter must survive it.
        state.setChannelPressureProbe(probe(0, ChannelPressureProbe.Writability.WRITABLE));
        state.flushSendQueue(0L, limiter, diag, sent::add, true, 0);
        assertTrue(sent.isEmpty(), "the refused tick sent nothing");
        state.setChannelPressureProbe(probe(500_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
        for (int i = 0; i < AbstractPlayerRequestState.YIELD_FLOOR_TICKS - half - 1; i++) {
            flush(true, 0);
        }
        assertTrue(sent.isEmpty(), "one short of the floor");
        flush(true, 0);
        assertEquals(List.of("a"), sent,
                "the floor fires at " + AbstractPlayerRequestState.YIELD_FLOOR_TICKS
                        + " WITHHELD ticks total — the refused tick reset nothing");
    }

    // ---- degrade pins: UNKNOWN never yields ----

    @Test
    void legacyBareLambdaProbeNeverYields() throws Exception {
        // The R-2 split: A1 pinned the snapshot degrade VALUES; this is the behavior half
        // — a legacy probe shape leaves the gate inert no matter what it reports.
        state.setChannelPressureProbe(() -> 8_000_000L);
        state.addReadyPayload(new QueuedPayload<>("a", 10, 0, POS_1));
        Thread.sleep(50);
        flush(true, 0);
        assertEquals(List.of("a"), sent, "writability UNKNOWN must never yield");
        assertEquals(0, state.getYieldedTicks());
    }

    @Test
    void armedThrowingSnapshotProbeNeverYields() throws Exception {
        // The class javadoc's third degrade claim (review C-3): a probe that THROWS from
        // snapshot() reads UNKNOWN — an armed server with a broken probe must run at
        // full speed, never pinned to the floor.
        state.setChannelPressureProbe(new ChannelPressureProbe() {
            @Override public long pendingOutboundBytes() { return -1; }
            @Override public Snapshot snapshot() { throw new IllegalStateException("probe blew up"); }
        });
        state.addReadyPayload(new QueuedPayload<>("a", 10, 0, POS_1));
        Thread.sleep(50);
        flush(true, 0);
        assertEquals(List.of("a"), sent, "a throwing snapshot degrades to UNKNOWN = no yield");
        assertEquals(0, state.getYieldedTicks());
    }

    @Test
    void noSignalProbeNeverYields() throws Exception {
        state.setChannelPressureProbe(ChannelPressureProbe.NO_SIGNAL);
        state.addReadyPayload(new QueuedPayload<>("a", 10, 0, POS_1));
        Thread.sleep(50);
        flush(true, 0);
        assertEquals(List.of("a"), sent);
        assertEquals(0, state.getYieldedTicks());
    }

    @Test
    void shortOverloadsPinTheYieldOff() throws Exception {
        // S-9a: only the full overload can arm the gate — the 4/5-arg overloads are the
        // pre-yield contract and must stay byte-identical.
        state.setChannelPressureProbe(probe(500_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("a", 10, 0, POS_1));
        Thread.sleep(50);
        state.flushSendQueue(BIG_ALLOCATION, limiter, diag, sent::add);
        assertEquals(List.of("a"), sent, "the short overload never yields");

    }

    @Test
    void disarmedFullOverloadNeverYields() throws Exception {
        state.setChannelPressureProbe(probe(500_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("a", 10, 0, POS_1));
        Thread.sleep(50);
        flush(false, 0);
        assertEquals(List.of("a"), sent, "yield=false is today's exact behavior");
    }

    // ---- the relevance prune (§2.1) ----

    @Test
    void pruneDropsOutOfRangeEntriesAtTheCadenceTickOnly() throws Exception {
        state.updatePlayerChunk(10, 0);
        state.setChannelPressureProbe(probe(500_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("far", 10, 0, POS_FAR));
        state.addReadyPayload(new QueuedPayload<>("near", 10, 1, POS_1));
        Thread.sleep(50);

        // Non-cadence tick: no prune work at all.
        flush(true, 300);
        assertEquals(2, state.getSendQueueSize(), "no prune on a non-cadence tick");

        // Position the cadence counter at the boundary: the next flush prunes.
        state.setFlushTickCounterForTest(AbstractPlayerRequestState.PRUNE_INTERVAL_TICKS - 1);
        long[] dropped = flush(true, 300);
        assertEquals(1, state.getSendQueueSize(), "the 690-chunk-away entry is pruned");
        assertArrayEquals(new long[] {POS_FAR}, dropped,
                "pruned positions ride the return array to the caller's clearDiskReadDone");
        assertFalse(state.hasEnqueuedColumn(POS_FAR), "the pruned entry released its mark");
        assertTrue(state.hasEnqueuedColumn(POS_1), "the in-range entry is untouched");

        // The floor later drains from the post-prune head: the near entry, not the far.
        state.setChannelPressureProbe(probe(0, ChannelPressureProbe.Writability.WRITABLE));
        Thread.sleep(50);
        flush(true, 300);
        assertEquals(List.of("near"), sent);
    }

    @Test
    void floorSendDrawsFromThePostPruneHead() throws Exception {
        // §1.4's claim as an ACTUAL floor send (reviews C-5/B-7/A-4): the far entry is
        // the queue HEAD (older submission order), the player is near POS_1, and the
        // floor tick itself prunes — its one payload must go to terrain the player can
        // still see, never to the entry the prune was about to discard.
        state.updatePlayerChunk(10, 0);
        state.setChannelPressureProbe(probe(500_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("far", 10, 0, POS_FAR));
        state.addReadyPayload(new QueuedPayload<>("near", 10, 1, POS_1));
        Thread.sleep(50);
        for (int i = 0; i < AbstractPlayerRequestState.YIELD_FLOOR_TICKS - 1; i++) {
            flush(true, 300);
        }
        assertTrue(sent.isEmpty(), "still yielding");
        long[] dropped = flush(true, 300);
        assertEquals(List.of("near"), sent,
                "the floor's one payload goes to the post-prune head, not the pruned far entry");
        assertArrayEquals(new long[] {POS_FAR}, dropped,
                "the floor tick's own prune reports the far position for done-bit clearing");
    }

    @Test
    void pruneKeepsInGraceEntries() throws Exception {
        // An out-of-range entry inside the departure grace is KEPT: sweeping it would
        // break the duplicate-serve stamp semantics the grace map pins.
        var clock = new AtomicLong(0);
        state.setDepartureGraceForTest(500_000_000L, clock::get);
        state.updatePlayerChunk(10, 0);
        state.setChannelPressureProbe(probe(0, ChannelPressureProbe.Writability.WRITABLE));
        // Send far once so its position is inside the grace window…
        state.addReadyPayload(new QueuedPayload<>("far1", 10, 0, POS_FAR));
        Thread.sleep(50);
        flush(true, 300);
        assertEquals(List.of("far1"), sent);
        // …then a SECOND far payload queued while the stamp is fresh survives the prune.
        state.setChannelPressureProbe(probe(500_000, ChannelPressureProbe.Writability.NOT_WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("far2", 10, 1, POS_FAR));
        state.setFlushTickCounterForTest(AbstractPlayerRequestState.PRUNE_INTERVAL_TICKS - 1);
        long[] dropped = flush(true, 300);
        assertEquals(0, dropped.length, "in-grace entries are kept (§2.1)");
        assertEquals(1, state.getSendQueueSize());
    }

    @Test
    void sendFailureMergesPrunedPositionsIntoTheDropReport() throws Exception {
        // A prune and a send failure on the SAME tick: both position classes need the
        // caller's clearDiskReadDone routing — the failure path must not clobber the
        // pruned ones.
        state.updatePlayerChunk(10, 0);
        state.setChannelPressureProbe(probe(0, ChannelPressureProbe.Writability.WRITABLE));
        state.addReadyPayload(new QueuedPayload<>("far", 10, 0, POS_FAR));
        state.addReadyPayload(new QueuedPayload<>("near", 10, 1, POS_1));
        Thread.sleep(50);
        state.setFlushTickCounterForTest(AbstractPlayerRequestState.PRUNE_INTERVAL_TICKS - 1);
        long[] dropped = state.flushSendQueue(BIG_ALLOCATION, limiter, diag,
                p -> { throw new IllegalStateException("send blew up"); }, true, 300);
        assertEquals(2, dropped.length, "pruned + failure-dropped both reported: "
                + java.util.Arrays.toString(dropped));
    }
}
