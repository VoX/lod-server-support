package dev.vox.lss.common.processing;

import dev.vox.lss.common.tracking.DirtyColumnTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The soak checker's conservation laws lean on these counters partitioning exactly —
 * every completion is one of {successful, notFound, allAir, error, saturated}, and the
 * cumulative twins of per-tick counters must survive tick resets.
 */
class DiagnosticsCountersTest {

    @Test
    void diskReaderCompletionPartitionIsExact() {
        var d = new DiskReaderDiagnostics();
        // 2 successful, 3 not-found, 1 all-air, 1 error, 1 saturated — 8 completions
        for (int i = 0; i < 8; i++) d.recordCompleted(1_000_000);
        d.recordSuccess(); d.recordSuccess();
        d.recordNotFound(); d.recordNotFound(); d.recordNotFound();
        d.recordAllAir();
        d.recordError();
        d.recordSaturation();

        assertEquals(8, d.getCompletedCount());
        assertEquals(2, d.getSuccessfulReadCount());
        // The at-rest partition the soak checker's A5 law relies on
        assertEquals(d.getCompletedCount(),
                d.getSuccessfulReadCount() + d.getNotFoundCount() + d.getAllAirCount()
                        + d.getErrorCount() + d.getSaturationCount());
    }

    @Test
    void saturatedReadsAreNotCountedSuccessful() {
        var d = new DiskReaderDiagnostics();
        d.recordCompleted(0);
        d.recordSaturation();
        assertEquals(0, d.getSuccessfulReadCount());
    }

    @Test
    void duplicateSkipsAccumulateAcrossTickResets() {
        var p = new ProcessingDiagnostics();
        p.incrementSkippedDuplicate();
        p.incrementSkippedDuplicate();
        p.resetTickCounters();
        p.incrementSkippedDuplicate();

        assertEquals(1, p.getLastSkippedDuplicate());
        assertEquals(3, p.getTotalDuplicateSkips());
    }

    @Test
    void requestsRoutedAccumulate() {
        var p = new ProcessingDiagnostics();
        for (int i = 0; i < 5; i++) p.incrementRequestRouted();
        p.resetTickCounters();
        assertEquals(5, p.getTotalRequestsRouted());
    }

    @Test
    void tickDiagnosticsKeepsCumulativeSendTotals() {
        var t = new TickDiagnostics();
        t.recordSectionSent(100);
        t.recordSectionSent(250);
        t.reset(new ProcessingDiagnostics());
        t.recordSectionSent(50);

        assertEquals(3, t.getTotalSectionsSent());
        assertEquals(400, t.getTotalBytesSent());
    }

    @Test
    void dirtyTrackerCountsPendingAndDrained() {
        var tracker = new DirtyColumnTracker();
        tracker.markDirty("minecraft:overworld", 1, 2);
        tracker.markDirty("minecraft:overworld", 3, 4);
        tracker.markDirty("minecraft:the_nether", 5, 6);
        assertEquals(3, tracker.pendingCount());
        assertEquals(0, tracker.getTotalDrained());

        long[] drained = tracker.drainDirty("minecraft:overworld");
        assertEquals(2, drained.length);
        assertEquals(1, tracker.pendingCount());
        assertEquals(2, tracker.getTotalDrained());

        // Re-marking the same position counts once per drain, not per mark
        tracker.markDirty("minecraft:the_nether", 5, 6);
        assertEquals(1, tracker.pendingCount());
        assertEquals(1, tracker.drainDirty("minecraft:the_nether").length);
        assertEquals(3, tracker.getTotalDrained());
        assertNull(tracker.drainDirty("minecraft:the_nether"));
    }
}
