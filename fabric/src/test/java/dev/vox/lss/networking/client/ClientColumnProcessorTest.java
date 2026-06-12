package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the decode-queue accounting in ClientColumnProcessor. queueSize is the client's
 * backpressure signal — LodRequestManager hard-halts all requesting when it nears
 * MAX_QUEUED_COLUMNS and SpiralScanner scales its budget from it — so an over-count
 * freezes the request loop forever while an under-count lets the decode queue grow
 * without bound. The drop counter feeds /lsslod stats and soak delivery accounting.
 */
class ClientColumnProcessorTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private ClientColumnProcessor processor;
    private VoxelColumnS2CPayload payload;

    @BeforeEach
    void setUp() {
        processor = new ClientColumnProcessor();
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:processor"));
        payload = new VoxelColumnS2CPayload(0, 0, dim, 1L, new byte[0]);
    }

    private void offer(int count) {
        for (int i = 0; i < count; i++) processor.offer(payload);
    }

    @Test
    void offerStopsAtCapAndCountsDrops() {
        offer(ClientColumnProcessor.MAX_QUEUED_COLUMNS);
        assertEquals(ClientColumnProcessor.MAX_QUEUED_COLUMNS, processor.getQueuedCount());
        assertEquals(0, processor.getColumnsDropped());

        offer(3);
        assertEquals(ClientColumnProcessor.MAX_QUEUED_COLUMNS, processor.getQueuedCount(),
                "offers past the cap must not grow the queue");
        assertEquals(3, processor.getColumnsDropped());
    }

    @Test
    void shutdownZeroesBackpressureSignalAndKeepsDropStats() {
        offer(ClientColumnProcessor.MAX_QUEUED_COLUMNS + 5);
        assertEquals(5, processor.getColumnsDropped());

        processor.shutdown();
        assertEquals(0, processor.getQueuedCount(),
                "a stale non-zero queueSize would keep the request loop halted after reconnect");
        assertEquals(5, processor.getColumnsDropped(),
                "shutdown must not reset drop stats (resetStats owns that)");

        // Counter integrity: the next session admits again from a consistent zero.
        offer(1);
        assertEquals(1, processor.getQueuedCount());
        assertEquals(5, processor.getColumnsDropped(), "post-shutdown offers fit again, no new drops");
    }

    @Test
    void resetStatsClearsDropCounterButNotQueue() {
        offer(ClientColumnProcessor.MAX_QUEUED_COLUMNS + 2);
        assertEquals(2, processor.getColumnsDropped());

        processor.resetStats();
        assertEquals(0, processor.getColumnsDropped());
        assertEquals(ClientColumnProcessor.MAX_QUEUED_COLUMNS, processor.getQueuedCount(),
                "resetStats clears statistics only, never queued work");
    }

    @Test
    void disabledScheduleClearsBacklogAndZeroesSignal() {
        offer(10);
        assertEquals(10, processor.getQueuedCount());

        // serverEnabled=false short-circuits before any Minecraft client state is touched.
        processor.scheduleProcessing(false);
        assertEquals(0, processor.getQueuedCount(),
                "disabled session must drop the backlog and release the backpressure signal");
    }

    @Test
    void reportUndispatchedUnstampsQueuedColumnsBeforeTheCacheFlush() {
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:processor"));
        var manager = new LodRequestManager();
        manager.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                64, 100, 100, true), "lss-test");
        manager.setLastDimensionForTest(dim);
        long packed = PositionUtil.packPosition(0, 0);
        manager.onColumnReceived(packed, 5000L, dim); // stamped at arrival, decode still pending

        offer(1);
        processor.reportUndispatched(manager);

        assertEquals(0, processor.getQueuedCount(), "undispatched queue must be drained");
        assertEquals(-1L, manager.columnsForTest().classify(packed, true),
                "an undispatched column's stamp must be forgotten before saveCache persists it");
        assertEquals(1, manager.getTotalIngestFailures());
    }
}
