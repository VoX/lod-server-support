package dev.vox.lss.networking.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the RequestMetrics surface the soak conservation laws lean on: lifetime totals are
 * monotonic — reset() (dimension change / fresh session config) clears only the rolling-rate
 * state — and totalPositionsRequested counts exactly at recordSendCycle (send time), so the
 * soak checker can balance requests against responses across dimension trips.
 */
class RequestMetricsTest {

    @Test
    void resetClearsRollingWindowsAndRatesButTotalsSurvive() {
        var m = new RequestMetrics();
        m.recordSendCycle(5);
        m.recordColumnReceived();
        m.recordUpToDate();
        m.recordNotGenerated();
        m.recordIngestFailure();
        m.updateRollingRates(); // first update window: counts > 0 -> rates strictly > 0
        assertTrue(m.getReceiveRate() > 0, "premise: a live rolling receive rate");
        assertTrue(m.getRequestRate() > 0, "premise: a live rolling request rate");

        m.reset();

        assertEquals(1, m.getTotalSendCycles(),
                "totals are lifetime-monotonic across reset (soak A1/A6 anchors)");
        assertEquals(5, m.getTotalPositionsRequested());
        assertEquals(1, m.getTotalColumnsReceived());
        assertEquals(1, m.getTotalUpToDate());
        assertEquals(1, m.getTotalNotGenerated());
        assertEquals(1, m.getTotalIngestFailures());
        assertEquals(0.0, m.getReceiveRate(), "rolling rates cleared");
        assertEquals(0.0, m.getRequestRate());

        // The window counters were cleared too: an immediate rate update right after reset
        // must compute from empty windows, not resurrect pre-reset activity.
        m.updateRollingRates();
        assertEquals(0.0, m.getReceiveRate(),
                "pre-reset window counts must not leak into post-reset rates");
        assertEquals(0.0, m.getRequestRate());
    }

    @Test
    void totalPositionsRequestedIncrementsOnlyAtRecordSendCycle() {
        var m = new RequestMetrics();
        m.recordColumnReceived();
        m.recordUpToDate();
        m.recordNotGenerated();
        m.recordIngestFailure();
        m.updateRollingRates();
        assertEquals(0, m.getTotalPositionsRequested(),
                "no response or ingest-failure path may count as a request");
        assertEquals(0, m.getTotalSendCycles());

        m.recordSendCycle(7);
        m.recordSendCycle(0); // an empty cycle counts as a cycle, never as positions

        assertEquals(7, m.getTotalPositionsRequested(),
                "requested positions count at send time (recordSendCycle)");
        assertEquals(2, m.getTotalSendCycles());
    }

    @Test
    void discardingDeadStampsRevivesRttSamplingAfterTheCapFills() {
        var m = new RequestMetrics();
        // Fill the pending-stamp map to its 4096 cap with sends that get a terminal non-column
        // answer (up-to-date / not-generated) — their awaited column never arrives.
        for (int i = 0; i < 4096; i++) m.recordRequestSent(i, 1000L);
        // At the cap, a brand-new position cannot be stamped, so its receive records no sample.
        m.recordRequestSent(999_999L, 1000L);
        m.recordColumnReceived(999_999L, 1500L);
        assertEquals(-1.0, m.getRttP50Ms(),
                "premise: once the stamp cap fills, new sends are unstamped and RTT sampling dies");

        // The manager now calls discardRttStamp on every up-to-date / not-generated answer.
        for (int i = 0; i < 4096; i++) m.discardRttStamp(i);

        m.recordRequestSent(999_999L, 2000L);
        m.recordColumnReceived(999_999L, 2400L);
        assertEquals(400.0, m.getRttP50Ms(),
                "discarding dead stamps frees room so RTT sampling resumes for the session");
    }

    // ---- RTT distribution (request->receive latency) ----

    @Test
    void rttPercentilesAreMinusOneWithNoSamples() {
        var m = new RequestMetrics();
        assertEquals(-1.0, m.getRttP50Ms(), "no samples -> sentinel, never a fabricated 0");
        assertEquals(-1.0, m.getRttP95Ms());
    }

    @Test
    void matchedSendThenReceiveRecordsOneRttSampleAndCountsTheColumn() {
        var m = new RequestMetrics();
        long p = 0x1234L;
        m.recordRequestSent(p, 1_000L);
        m.recordColumnReceived(p, 1_100L);
        assertEquals(100.0, m.getRttP50Ms(), "sample == receive - send");
        assertEquals(1, m.getTotalColumnsReceived(), "the position-keyed receive also counts the column");
    }

    @Test
    void reSendOverwritesStampSoTheSampleMeasuresTheLatestAttempt() {
        var m = new RequestMetrics();
        long p = 0x55L;
        m.recordRequestSent(p, 1_000L);
        m.recordRequestSent(p, 1_500L); // re-send before any reply
        m.recordColumnReceived(p, 1_600L);
        assertEquals(100.0, m.getRttP50Ms(), "latest send (1500) is measured, not the original (1000)");
    }

    @Test
    void receiveWithoutAPriorSendStampRecordsNoSampleButStillCountsTheColumn() {
        var m = new RequestMetrics();
        m.recordColumnReceived(0x99L, 1_100L); // never sent (e.g. a dirty-push delivery)
        assertEquals(-1.0, m.getRttP50Ms(), "no stamp -> no RTT sample");
        assertEquals(1, m.getTotalColumnsReceived());
    }

    @Test
    void receiveBeforeTheStampedSendTimeIsDiscardedNotNegative() {
        var m = new RequestMetrics();
        long p = 7L;
        m.recordRequestSent(p, 2_000L);
        m.recordColumnReceived(p, 1_900L); // clock went backwards
        assertEquals(-1.0, m.getRttP50Ms(), "a negative interval is dropped, never stored");
    }

    @Test
    void resetClearsRttSamplesAndPendingStamps() {
        var m = new RequestMetrics();
        m.recordRequestSent(1L, 1_000L);
        m.recordColumnReceived(1L, 1_050L);
        assertEquals(50.0, m.getRttP50Ms(), "premise: one live sample");

        m.recordRequestSent(2L, 1_000L); // a stamp that outlives the reset
        m.reset();

        assertEquals(-1.0, m.getRttP50Ms(), "reset clears the sample ring");
        m.recordColumnReceived(2L, 1_500L); // the pre-reset stamp must be gone
        assertEquals(-1.0, m.getRttP50Ms(), "a stamp cleared by reset yields no sample on later receipt");
    }

    @Test
    void percentilesAreNearestRankOverAKnownSampleSet() {
        var m = new RequestMetrics();
        // Ten samples 10,20,...,100 ms (distinct positions). Send base is non-zero: epoch ms 0
        // is the "absent stamp" sentinel, so a 0 send time would record no sample.
        for (int i = 1; i <= 10; i++) {
            m.recordRequestSent(i, 1_000L);
            m.recordColumnReceived(i, 1_000L + i * 10L);
        }
        // nearest-rank: p50 -> ceil(0.50*10)-1 = index 4 -> 50; p95 -> ceil(0.95*10)-1 = index 9 -> 100
        assertEquals(50.0, m.getRttP50Ms());
        assertEquals(100.0, m.getRttP95Ms());
    }
}
