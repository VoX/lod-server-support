package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Receipt ownership and cancellation, independent of graphics and the live connection. */
class ColumnDeliveryLifecycleTest {
    private static final ResourceKey<Level> DIM = Level.OVERWORLD;
    private static final long POS = PositionUtil.packPosition(1, 1);

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static LodRequestManager manager(String bucket) {
        var manager = new LodRequestManager();
        manager.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 12, true), bucket);
        manager.setLastDimensionForTest(DIM);
        manager.markCacheLoadedForTest();
        manager.deliveryExecutor = Runnable::run;
        manager.setBatchSenderForTest(payload -> {});
        return manager;
    }

    private static ColumnDelivery deliver(LodRequestManager manager, long stamp, boolean clear) {
        long previous = manager.contentStampBeforeDelivery(POS);
        assertTrue(manager.onColumnReceived(POS, stamp, DIM, clear));
        return manager.trackDelivery(DIM, POS, previous);
    }

    @Test void cancellationOfUndispatchedClearRetainsTheDataClaimAcrossSaveAndLoad() throws Exception {
        String bucket = "receipt-clear-" + UUID.randomUUID();
        var manager = manager(bucket);
        try {
            deliver(manager, 7000L, false).complete();
            deliver(manager, 9000L, true); // queued clear has not reached any consumer
            manager.retireAcquisition();
            assertEquals(7000L, manager.columnsForTest().classify(POS));
            assertEquals(0, manager.getIngestParkedCount());
            manager.saveCache();
            var loaded = ColumnCacheStore.loadStateAsync(bucket, DIM).get(30, TimeUnit.SECONDS);
            var restored = new ColumnStateMap();
            restored.adoptLoaded(loaded);
            assertEquals(7000L, restored.classify(POS), "positive claim causes the server to send the clear again");
        } finally { ColumnCacheStore.clearForServer(bucket); }
    }

    @Test void completedContentSurvivesButUnfinishedContentCannotBecomeAWarmProof() {
        var manager = manager("receipt-content");
        deliver(manager, 7000L, false).complete();
        long other = PositionUtil.packPosition(2, 1);
        assertTrue(manager.onColumnReceived(other, 8000L, DIM));
        manager.trackDelivery(DIM, other, -1L);
        manager.retireAcquisition();
        assertEquals(7000L, manager.columnsForTest().timestampFor(POS));
        assertEquals(-1L, manager.columnsForTest().timestampFor(other));
        assertEquals(0, manager.getIngestParkedCount());
    }

    @Test void queuedFailureCannotCrossRetirementOrEraseReplacementProof() {
        var old = manager("receipt-old");
        var events = new ArrayList<Runnable>();
        old.deliveryExecutor = events::add;
        var receipt = deliver(old, 7000L, false);
        receipt.report();
        old.retireAcquisition();
        assertEquals(-1L, old.columnsForTest().timestampFor(POS), "failure observed before event dispatch still retires honestly");
        var replacement = manager("receipt-new");
        deliver(replacement, 9000L, false).complete();
        events.forEach(Runnable::run);
        receipt.report();
        assertFalse(receipt.isActive());
        assertEquals(9000L, replacement.columnsForTest().timestampFor(POS));
        assertEquals(0, replacement.getIngestParkedCount());
    }

    @Test void olderCancellationCannotUndoALaterAcceptedReceiptAtTheSamePosition() {
        var manager = manager("receipt-newer");
        var old = deliver(manager, 7000L, false);
        deliver(manager, 8000L, false).complete();
        manager.cancelDelivery(old);
        assertEquals(8000L, manager.columnsForTest().timestampFor(POS));
        assertEquals(ColumnStateMap.SATISFIED, manager.columnsForTest().classify(POS));
    }

    @Test void realConsumerFailuresStillReachThePerDeliveryCap() {
        var manager = manager("receipt-failures");
        dev.vox.lss.api.VoxelColumnConsumer consumer = (level, dimension, x, z, data) -> {
            throw new IllegalStateException("consumer rejected delivery");
        };
        dev.vox.lss.api.LSSApi.registerColumnConsumer(consumer);
        try {
            for (int i = 0; i <= ColumnStateMap.MAX_INGEST_FAILURES; i++) {
                var receipt = deliver(manager, 7000L + i, false);
                dev.vox.lss.api.LSSApi.withIngestFailureHandle(DIM, 1, 1, receipt,
                        () -> dev.vox.lss.api.LSSApi.dispatchColumn(null, DIM, 1, 1,
                                new dev.vox.lss.api.VoxelColumnData(
                                        new dev.vox.lss.api.VoxelColumnData.SectionData[0], 7000L)));
                receipt.complete();
            }
        } finally { dev.vox.lss.api.LSSApi.removeColumnConsumer(consumer); }
        assertEquals(1, manager.getIngestParkedCount());
        assertEquals(ColumnStateMap.SATISFIED, manager.columnsForTest().classify(POS));
        assertEquals(-1L, manager.columnsForTest().timestampFor(POS));
    }
    @Test void deferredConsumerAcceptanceRemainsCancellableWithoutAFailureStrike() {
        var manager = manager("receipt-deferred");
        var receipt = deliver(manager, 7000L, false);
        Runnable release = receipt.deferAcceptance(); // Voxy accepted; Xaero still queued
        receipt.complete();
        manager.retireAcquisition();
        release.run();
        release.run(); // disposal is idempotent
        assertEquals(-1L, manager.columnsForTest().timestampFor(POS));
        assertEquals(0, manager.getIngestParkedCount());
    }

    @Test void committedDeferredWorkSurvivesRetirement() {
        var manager = manager("receipt-committed");
        var receipt = deliver(manager, 7000L, false);
        Runnable release = receipt.deferAcceptance();
        receipt.complete();
        release.run();
        manager.retireAcquisition();
        assertEquals(7000L, manager.columnsForTest().timestampFor(POS));
    }

    @Test void severalQueuedClearsRestoreTheLastAcceptedContent() {
        var manager = manager("receipt-clear-chain");
        deliver(manager, 7000L, false).complete();
        deliver(manager, 8000L, true);
        deliver(manager, 9000L, true);
        manager.retireAcquisition();
        assertEquals(7000L, manager.columnsForTest().classify(POS));
    }

    @Test void dimensionCancelledClearCannotEraseItsSavedContentClaimLater() throws Exception {
        assertDimensionCancelledClearSurvivesLateReport(false);
    }

    @Test void aPostedFailureEventCannotOutliveDimensionCancellation() throws Exception {
        assertDimensionCancelledClearSurvivesLateReport(true);
    }

    private void assertDimensionCancelledClearSurvivesLateReport(boolean alreadyPosted) throws Exception {
        String bucket = "receipt-dimension-clear-" + UUID.randomUUID();
        var manager = manager(bucket);
        try {
            deliver(manager, 7000L, false).complete();
            var clear = deliver(manager, 9000L, true);
            var events = new ArrayList<Runnable>();
            if (alreadyPosted) {
                manager.deliveryExecutor = events::add;
                clear.report();
            }
            manager.cancelOutstandingDeliveries(); // dimension transition keeps this manager active
            manager.saveCache();
            manager.setLastDimensionForTest(Level.NETHER);
            clear.report(); // deferred Xaero old-dimension drop after the cache was saved
            events.forEach(Runnable::run);
            var loaded = ColumnCacheStore.loadStateAsync(bucket, DIM).get(30, TimeUnit.SECONDS);
            var restored = new ColumnStateMap();
            restored.adoptLoaded(loaded);
            assertEquals(7000L, restored.classify(POS), "return must still claim data to request the clear again");
            assertFalse(clear.isActive());
            assertTrue(manager.isAcquisitionActive());
        } finally { ColumnCacheStore.clearForServer(bucket); }
    }

}
