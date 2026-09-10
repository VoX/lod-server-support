package dev.vox.lss.common.processing;

import dev.vox.lss.common.SharedBandwidthLimiter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The send pacer's pins (send-pacing-plan.md v3 — the refill-floored, burst-clamped
 * proportional drain): budget truth table incl. the deep-backlog ceiling, presence
 * gate incl. the degenerate budget-0 case, retention across ticks, the pingf-cut
 * scaling, RAW denomination, paced= attribution, and the kill-switch/short-overload
 * OFF pins (S-9a). All timing rides the state's injected nano clock (the
 * clock-injected ctor) — no wall-clock sleeps, no token-boundary flakes (the
 * IncomingRequestRouterTest polling-deadline lesson).
 */
class SendPacingFlushTest {

    private static final long POS_1 = 11L;
    private static final long NANOS_PER_SEC = 1_000_000_000L;

    /** Mutable fake clock driving the state's bandwidth tracker. */
    private final long[] nanos = {0};

    private final class TestState extends AbstractPlayerRequestState<String> {
        TestState() { super(UUID.randomUUID(), 1, 1, () -> nanos[0]); }
        @Override public String getPlayerName() { return "pace-test"; }
    }

    private static ChannelPressureProbe writableProbe() {
        return new ChannelPressureProbe() {
            @Override public long pendingOutboundBytes() { return 0; }
            @Override public Snapshot snapshot() {
                return new Snapshot(0, 65536, Writability.WRITABLE);
            }
        };
    }

    private final SharedBandwidthLimiter limiter = new SharedBandwidthLimiter(1L << 40);
    private final TickDiagnostics diag = new TickDiagnostics();
    private final List<String> sent = new ArrayList<>();
    private final TestState state = new TestState();

    /** Queue {@code count} payloads of {@code rawBytes} raw / {@code wireBytes} wire —
     *  through the CANONICAL ctor with distinct submission orders (the review round's
     *  MAJOR: the 4-arg compat ctor silently bound the third argument to
     *  submissionOrder and set wire = raw, making the denomination pin vacuous). */
    private void queue(int count, int rawBytes, int wireBytes) {
        for (int i = 0; i < count; i++) {
            state.addReadyPayload(new QueuedPayload<>("p" + i, rawBytes, wireBytes, i,
                    POS_1 + i));
        }
    }

    /** Advance the fake clock so the next canSend refills the bank to full. */
    private void fillBank() {
        nanos[0] += NANOS_PER_SEC;
    }

    /** Full-overload flush with pacing armed and the yield gate off. */
    private long[] flushPaced(long allocation) {
        return state.flushSendQueue(allocation, limiter, diag, sent::add, false, 0,
                true);
    }

    @Test
    void refillFloorBindsWhenTheQueueIsSmall() {
        // Q = 10 x 30 KB = 300 KB; alloc 2 MB/s -> share 100 KB, Q/10 = 30 KB below
        // it. The floor admits sends until 100 KB is written (payloads 1-4: 120 KB
        // crosses the budget and stops #5) — never fewer than the cap's own rate.
        state.setChannelPressureProbe(writableProbe());
        queue(10, 30_000, 30_000);
        fillBank();
        long[] dropped = flushPaced(2_000_000);
        assertEquals(4, sent.size(), "the refill floor admits ~one share per tick");
        assertEquals(6, state.getSendQueueSize(), "leftover is retained, never dropped");
        assertEquals(0, dropped.length, "a paced stop reports NO dropped positions");
        assertEquals(1, state.getPacedTicks(), "a budget-stopped PARTIAL tick books paced=");
        assertEquals(1, diag.getPacedTicksTotal(), "the service-scoped twin books too");
    }

    @Test
    void deepBacklogsAreClampedAtTheBurstCeiling() {
        // Q = 100 x 30 KB = 3 MB; alloc 2 MB/s: Q/10 = 300 KB would meet the bank on
        // real-terrain waves (the integration review's MAJOR) — the clamp caps the
        // budget at PACE_MAX_BURST_SHARES x share = 200 KB: payloads 1-7 (210 KB)
        // ship, #8 is vetoed. (The pre-clamp v2 shipped 10 here.)
        state.setChannelPressureProbe(writableProbe());
        queue(100, 30_000, 30_000);
        fillBank();
        flushPaced(2_000_000);
        assertEquals(7, sent.size(), "a deep backlog drains at most 2 shares per tick");
        assertEquals(1, state.getPacedTicks());
    }

    @Test
    void bankSizedWaveDrainsInFiveTicks() {
        // The headline identity (plan §4): bank = allocation/4 = 5 refill shares, so a
        // bank-sized wave paces at the floor and clears in exactly 5 ticks — inside
        // the client's own 5-tick fast-fire floor. paced= books the 4 partial ticks;
        // the emptying tick books nothing.
        state.setChannelPressureProbe(writableProbe());
        queue(5, 100_000, 100_000); // 500 KB = the 2 MB/s allocation's bank
        for (int tick = 1; tick <= 5; tick++) {
            fillBank();
            long[] dropped = flushPaced(2_000_000);
            assertEquals(tick, sent.size(), "one share ships per tick (tick " + tick + ")");
            assertEquals(0, dropped.length);
        }
        assertEquals(0, state.getSendQueueSize(), "the wave clears in 5 ticks");
        assertEquals(4, state.getPacedTicks(), "4 partial ticks + the emptying tick");
    }

    @Test
    void oversizedPayloadShipsWholeThroughThePresenceGate() {
        // One 5 MB payload vs a 200 KB clamped budget: the budget never vetoes the
        // FIRST send, and an emptied queue is not a paced stop.
        state.setChannelPressureProbe(writableProbe());
        queue(1, 5_000_000, 5_000_000);
        fillBank();
        flushPaced(2_000_000);
        assertEquals(1, sent.size(), "a legal oversized payload ships whole");
        assertEquals(0, state.getPacedTicks(), "an emptied queue books nothing");
    }

    @Test
    void degenerateBudgetZeroStillShipsOnePayloadPerTick() {
        // allocation < 20 makes the share (and the clamp ceiling) 0 — the presence
        // gate's paceWritten>0 conjunct is what keeps one payload per tick flowing.
        // Deleting that conjunct sends NOTHING forever; this is its pin (the review
        // round's MINOR: at any budget > 0 the conjunct is unobservable).
        state.setChannelPressureProbe(writableProbe());
        queue(5, 2, 2);
        for (int tick = 1; tick <= 2; tick++) {
            fillBank();
            flushPaced(19);
            assertEquals(tick, sent.size(),
                    "budget 0 degrades to exactly one payload per tick");
        }
        assertEquals(2, state.getPacedTicks());
    }

    @Test
    void pingfCutAllocationShrinksTheBudgetWithIt() {
        // The cut composition (plan §3): the floor AND the ceiling derive from the
        // CUT allocation (200 KB/s -> share 10 KB, ceiling 20 KB), so a backstop cut
        // paces harder in the same direction — the 30 KB first payload crosses the
        // whole budget and #2 is vetoed.
        state.setChannelPressureProbe(writableProbe());
        queue(100, 30_000, 30_000);
        fillBank();
        flushPaced(200_000);
        assertEquals(1, sent.size(), "the budget scales down with a cut allocation");
        assertEquals(1, state.getPacedTicks());
    }

    @Test
    void budgetIsRawDenominated() {
        // Raw 30 KB / wire 2 B payloads at alloc 400 KB/s: the raw-denominated budget
        // (clamp(300K/10, 20K, 40K) = 30 KB) vetoes after payload #1. A
        // wire-denominated mutant reads paceWritten = 2 B, never vetoes, and the
        // limiter then admits 4 (bank 100 KB at 30 KB raw each) — exactly-one-sent
        // pins the denomination for real (the canonical-ctor fix made this
        // discriminate; the compat ctor had silently set wire = raw).
        state.setChannelPressureProbe(writableProbe());
        queue(10, 30_000, 2);
        fillBank();
        flushPaced(400_000);
        assertEquals(1, sent.size(), "the budget counts RAW bytes");
        assertEquals(1, state.getPacedTicks());
    }

    @Test
    void killSwitchOffRestoresTheUnpacedFlush() {
        // The 7-arg overload (pacing false): the same queue ships limiter-bound only —
        // the bank (500 KB) admits exactly 5 x 100 KB (the frozen clock makes the
        // token boundary deterministic), and paced= never moves.
        state.setChannelPressureProbe(writableProbe());
        queue(10, 100_000, 100_000);
        fillBank();
        state.flushSendQueue(2_000_000, limiter, diag, sent::add, false, 0);
        assertEquals(5, sent.size(), "unpaced: the bank dumps five shares in one tick");
        assertEquals(0, state.getPacedTicks(), "pacing off books nothing");
    }

    @Test
    void shortOverloadsPinPacingOff() {
        // S-9a: only the fullest overload can arm pacing. The 4-arg and 5-arg
        // (ceiling-only) overloads both dump the bank.
        state.setChannelPressureProbe(writableProbe());
        queue(10, 100_000, 100_000);
        fillBank();
        state.flushSendQueue(2_000_000, limiter, diag, sent::add);
        assertEquals(5, sent.size(), "the 4-arg overload never paces");
        sent.clear();
        var second = new TestState();
        second.setChannelPressureProbe(writableProbe());
        for (int i = 0; i < 10; i++) {
            second.addReadyPayload(new QueuedPayload<>("q" + i, 100_000, 100_000, i, 200L + i));
        }
        fillBank();
        second.flushSendQueue(2_000_000, limiter, diag, sent::add, false, 0);
        assertEquals(5, sent.size(), "the yield/prune overload never paces either");
        assertEquals(0, state.getPacedTicks() + second.getPacedTicks());
    }

    @Test
    void emptyQueueBooksNothing() {
        state.setChannelPressureProbe(writableProbe());
        fillBank();
        long[] dropped = flushPaced(2_000_000);
        assertTrue(sent.isEmpty());
        assertEquals(0, dropped.length);
        assertEquals(0, state.getPacedTicks(), "an idle armed tick books nothing");
    }

    @Test
    void starvationFloorTickShipsDespitePacing() {
        // The floor tick's one-payload contract survives an armed pacer, and books no
        // paced= (behaviorally exempt: the budget is not even computed on floor ticks;
        // the in-loop guard is belt-and-braces documentation).
        state.setChannelPressureProbe(new ChannelPressureProbe() {
            @Override public long pendingOutboundBytes() { return 500_000; }
            @Override public Snapshot snapshot() {
                return new Snapshot(500_000, 65536, Writability.NOT_WRITABLE);
            }
        });
        queue(3, 100_000, 100_000);
        fillBank();
        for (int i = 0; i < AbstractPlayerRequestState.YIELD_FLOOR_TICKS - 1; i++) {
            state.flushSendQueue(2_000_000, limiter, diag, sent::add, true, 0, true);
        }
        assertTrue(sent.isEmpty(), "yielding until the floor");
        state.flushSendQueue(2_000_000, limiter, diag, sent::add, true, 0, true);
        assertEquals(1, sent.size(), "the floor tick ships its one payload");
        assertEquals(0, state.getPacedTicks(), "the floor tick never books paced=");
    }
}
