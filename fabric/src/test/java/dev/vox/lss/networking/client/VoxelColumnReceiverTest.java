package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the VoxelColumn receive glue ({@link LSSClientNetworking#handleVoxelColumn}), which
 * was previously an unpinned inline lambda despite carrying two load-bearing rules:
 * heldContentBefore is captured BEFORE onColumnReceived stamps the position, and a
 * 0-section column is an authoritative clear REGARDLESS of the held check (the server only
 * sends clears to data-claiming clients, so a missed held check means the stamp was dropped
 * by an ingest-failure racing the delivery — plain-first-serve treatment would create a
 * validated hole with no air-fill).
 */
class VoxelColumnReceiverTest {

    private static final byte[] CONTENT = {2, 0, 0, 0}; // lead varint 2: non-clear body
    private static final byte[] CLEAR = {0x00};          // 0-section body

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private record Offered(long packed, boolean resync) {}

    private static final class RecordingProcessor extends ClientColumnProcessor {
        final List<Offered> offered = new ArrayList<>();

        RecordingProcessor() {
            super((d, x, z) -> {}, () -> null);
        }

        @Override
        void offer(VoxelColumnS2CPayload payload, boolean resync) {
            offered.add(new Offered(
                    PositionUtil.packPosition(payload.chunkX(), payload.chunkZ()), resync));
        }
    }

    private LodRequestManager manager;
    private RecordingProcessor processor;
    private ResourceKey<Level> dim;

    @BeforeEach
    void setUp() {
        manager = new LodRequestManager();
        manager.onSessionConfig(new SessionConfigS2CPayload(
                dev.vox.lss.common.LSSConstants.PROTOCOL_VERSION, true, 64, true), "recv-test");
        dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:receiver"));
        manager.setLastDimensionForTest(dim);
    }

    private VoxelColumnS2CPayload payload(int cx, int cz, long ts, byte[] body) {
        return new VoxelColumnS2CPayload(cx, cz, dim, ts, body);
    }

    @Test
    void firstServeOfContentIsNotAResync() {
        // Ordering pin: heldContentBefore must be read BEFORE onColumnReceived stamps ts>0
        // — captured after, every first serve would air-fill as a phantom resync.
        LSSClientNetworking.handleVoxelColumn(manager, processorRecording(), payload(3, 4, 900L, CONTENT));
        assertEquals(List.of(new Offered(PositionUtil.packPosition(3, 4), false)), processor.offered);
        assertEquals(900L, manager.columnsForTest().timestampFor(PositionUtil.packPosition(3, 4)));
    }

    @Test
    void contentResyncAirFills() {
        long packed = PositionUtil.packPosition(5, 6);
        var held = new Long2LongOpenHashMap();
        held.put(packed, 500L); // client already holds server data here
        manager.columnsForTest().loadFrom(held);

        LSSClientNetworking.handleVoxelColumn(manager, processorRecording(), payload(5, 6, 900L, CONTENT));
        assertEquals(List.of(new Offered(packed, true)), processor.offered);
    }

    @Test
    void clearRacingADroppedStampStillAirFills() {
        // The stamp was dropped to -1 (ingest-failure report) just before the clear arrived:
        // heldContentBefore misses, but the 0-section body itself proves this is a clear —
        // it must still air-fill, or the consumer keeps ghost terrain under a fresh ts>0
        // stamp that up_to_date validates for the whole session.
        long packed = PositionUtil.packPosition(7, 8);
        assertFalse(manager.heldContentBefore(packed), "premise: stamp already dropped");

        LSSClientNetworking.handleVoxelColumn(manager, processorRecording(), payload(7, 8, 900L, CLEAR));
        assertEquals(List.of(new Offered(packed, true)),
                processor.offered, "a clear must air-fill even when the held check missed");
        assertEquals(900L, manager.columnsForTest().timestampFor(packed));
    }

    @Test
    void nullManagerStillOffersTheColumn() {
        var proc = processorRecording();
        LSSClientNetworking.handleVoxelColumn(null, proc, payload(1, 1, 900L, CONTENT));
        assertEquals(1, processor.offered.size(), "no manager (pre-handshake race) still decodes");
        assertFalse(processor.offered.get(0).resync());
    }

    @Test
    void resyncClearIsFlaggedAuthoritative() {
        // The WS6 contract end-to-end through the glue: a held-content clear records the
        // pre-clear stamp so a consumer rejection re-requests it (not -1).
        long packed = PositionUtil.packPosition(9, 9);
        var held = new Long2LongOpenHashMap();
        held.put(packed, 500L);
        manager.columnsForTest().loadFrom(held);

        LSSClientNetworking.handleVoxelColumn(manager, processorRecording(), payload(9, 9, 900L, CLEAR));
        assertEquals(List.of(new Offered(packed, true)), processor.offered);
        manager.onIngestFailure(dim, packed); // consumer rejects the clear
        assertEquals(500L, manager.columnsForTest().timestampFor(packed),
                "a rejected clear re-requests with the pre-clear stamp (WS6)");
        assertTrue(manager.columnsForTest().classify(packed) > 0,
                "the position must re-request, not park");
    }

    private ClientColumnProcessor processorRecording() {
        processor = new RecordingProcessor();
        return processor;
    }
}
