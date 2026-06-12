package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sliding-window bandwidth math behind {@code getWindowBytesPerSecond} — the
 * number the /lsslod Bandwidth line and the soak exporter report. Samples enter the ring
 * only at {@code reset()}; the rate is windowed-bytes over the oldest-to-newest sample
 * span; fewer than two samples have no span and must report 0; ring wraparound must evict
 * the overwritten sample's bytes from the running sum (never negative, never inflated by
 * evicted traffic).
 *
 * <p>Timing assertions are bracket-bounds over measured {@code System.nanoTime()} spans
 * (the implementation stamps its own clock), so they hold on any scheduler.
 */
class TickDiagnosticsTest {

    // Mirrors TickDiagnostics.WINDOW_TICKS (private): the ring holds the last 100 samples.
    private static final int WINDOW_TICKS = 100;

    private final TickDiagnostics diag = new TickDiagnostics();
    private final ProcessingDiagnostics pd = new ProcessingDiagnostics();

    @Test
    void windowRateIsZeroWithFewerThanTwoSamples() {
        assertEquals(0, diag.getWindowBytesPerSecond(), "no samples => no rate");

        diag.recordSectionSent(50_000);
        assertEquals(0, diag.getWindowBytesPerSecond(),
                "bytes recorded but never pushed by reset() must not enter the window");

        diag.reset(pd);
        assertEquals(0, diag.getWindowBytesPerSecond(),
                "a single sample has no elapsed span to divide by");
    }

    @Test
    void windowRateEqualsWindowedBytesOverElapsedSampleSpan() throws Exception {
        long before = System.nanoTime();
        diag.recordSectionSent(100_000);
        diag.reset(pd); // sample 1
        long afterFirst = System.nanoTime();

        Thread.sleep(15); // guarantee a measurable span between the two samples

        long beforeSecond = System.nanoTime();
        diag.recordSectionSent(300_000);
        diag.reset(pd); // sample 2
        long after = System.nanoTime();

        long rate = diag.getWindowBytesPerSecond();

        // The implementation's span is bracketed by our measured timestamps:
        // nanos(s1) in [before, afterFirst], nanos(s2) in [beforeSecond, after].
        long windowBytes = 100_000L + 300_000L;
        long minElapsed = Math.max(1, beforeSecond - afterFirst);
        long maxElapsed = after - before;
        long lowerBound = windowBytes * LSSConstants.NANOS_PER_SECOND / maxElapsed;
        long upperBound = windowBytes * LSSConstants.NANOS_PER_SECOND / minElapsed;

        assertTrue(rate > 0, "two byte-bearing samples must produce a positive rate");
        assertTrue(rate >= lowerBound && rate <= upperBound,
                "rate must be windowed bytes over the sample span: rate=" + rate
                        + " expected in [" + lowerBound + ", " + upperBound + "]");
    }

    @Test
    void bytesRecordedAfterTheLastResetDoNotMoveTheRate() throws Exception {
        diag.recordSectionSent(10_000);
        diag.reset(pd);
        Thread.sleep(5);
        diag.recordSectionSent(10_000);
        diag.reset(pd);
        long settled = diag.getWindowBytesPerSecond();

        diag.recordSectionSent(123_456_789);

        assertEquals(settled, diag.getWindowBytesPerSecond(),
                "un-reset accumulator bytes must stay out of the window until the next reset");
    }

    @Test
    void ringWraparoundEvictsOldestSampleAndNeverGoesNegative() throws Exception {
        diag.recordSectionSent(1_000_000);
        diag.reset(pd); // sample 1: 1 MB

        // Samples 2..WINDOW_TICKS: zero bytes — the 1 MB sample is still inside the window
        for (int i = 0; i < WINDOW_TICKS - 1; i++) {
            diag.reset(pd);
            assertTrue(diag.getWindowBytesPerSecond() >= 0,
                    "window rate must never go negative (iteration " + i + ")");
        }

        // Make the in-window span strictly positive so a broken eviction cannot hide
        // behind the elapsed<=0 -> 0 escape, then push the sample that overwrites slot 0.
        Thread.sleep(5);
        diag.reset(pd); // sample WINDOW_TICKS+1 evicts the 1 MB sample

        assertEquals(0, diag.getWindowBytesPerSecond(),
                "after wraparound the window holds only zero-byte samples: evicted bytes "
                        + "must leave the running sum, not inflate the rate forever");
    }
}
