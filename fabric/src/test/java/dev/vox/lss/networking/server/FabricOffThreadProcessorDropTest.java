package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.TickDiagnostics;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the Fabric server-side encoder guard: columns whose serialized sections exceed
 * MAX_SEND_SECTIONS_SIZE are dropped before payload construction (the netty frame cap rejects
 * such frames with a disconnect, so sending one would kick the player on every re-request
 * of the same mega-column), while columns at exactly the cap are enqueued.
 */
class FabricOffThreadProcessorDropTest {

    private static final long BIG_ALLOCATION = 1_000_000_000L;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private record Harness(FabricOffThreadProcessor processor, PlayerRequestState state) {}

    private static Harness harness() {
        var state = new PlayerRequestState(UUID.randomUUID(), 4, 4);
        var players = new ConcurrentHashMap<UUID, PlayerRequestState>();
        players.put(state.getPlayerUUID(), state);
        // Processing thread is never started: buildAndEnqueueColumnPayload is exercised
        // directly, same single-thread model as the processing loop.
        return new Harness(new FabricOffThreadProcessor(players, null, false, null, 1), state);
    }

    /** Flush whatever was enqueued for the player (FlushSendQueueTest token-bucket pattern). */
    private static List<CustomPacketPayload> flush(PlayerRequestState state) throws InterruptedException {
        Thread.sleep(50); // the empty per-player token bucket only refills after >=1ms
        var sent = new ArrayList<CustomPacketPayload>();
        state.flushSendQueue(BIG_ALLOCATION, new SharedBandwidthLimiter(BIG_ALLOCATION),
                new TickDiagnostics(), sent::add);
        return sent;
    }

    @Test
    void oversizedColumnIsDroppedBeforeEnqueue() throws InterruptedException {
        var h = harness();
        byte[] oversized = new byte[LSSConstants.MAX_SEND_SECTIONS_SIZE + 1];

        boolean sent = h.processor().buildAndEnqueueColumnPayload(h.state(), 1, 2,
                LSSConstants.DIM_STR_OVERWORLD,
                5L, 1L, oversized, oversized.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES);
        assertFalse(sent, "the caller answers up-to-date on false so the position resolves terminally");

        // Bandwidth and tokens would admit a send — absence proves the drop, not a gate
        assertEquals(0, flush(h.state()).size(),
                "an over-cap column must never reach the player's send queue");
        assertEquals(0, h.state().getSendQueueSize());
    }

    @Test
    void over256CharDimensionIsDroppedBeforeEnqueue() throws InterruptedException {
        var h = harness();
        byte[] sections = new byte[64];
        // #4 fix: a pathological >256-char dimension id is dropped at the guard (returns false →
        // caller answers up-to-date) instead of letting the send-time writeUtf throw and drop
        // the whole flush queue. The guard runs before the Identifier.parse, so it can't throw.
        String oversizedDim = "lss:" + "a".repeat(LSSConstants.MAX_DIMENSION_STRING_LENGTH - 3); // 257

        boolean sent = h.processor().buildAndEnqueueColumnPayload(h.state(), 1, 2, oversizedDim,
                5L, 1L, sections, sections.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES);
        assertFalse(sent, "an oversized dimension id drops the column (false), it must not throw");
        assertEquals(0, flush(h.state()).size(),
                "a column with an oversized dimension must never reach the send queue");
        assertEquals(0, h.state().getSendQueueSize());
    }

    @Test
    void columnAtExactCapIsEnqueuedWithItsCoordinates() throws InterruptedException {
        var h = harness();
        byte[] atCap = new byte[LSSConstants.MAX_SEND_SECTIONS_SIZE];

        boolean enqueued = h.processor().buildAndEnqueueColumnPayload(h.state(), 3, -4,
                LSSConstants.DIM_STR_THE_END,
                77L, 1L, atCap, atCap.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES);
        assertTrue(enqueued);

        var sent = flush(h.state());
        assertEquals(1, sent.size(), "a column at exactly the cap must be served (guard is >, not >=)");
        assertTrue(sent.get(0) instanceof VoxelColumnS2CPayload);
        var column = (VoxelColumnS2CPayload) sent.get(0);
        assertEquals(3, column.chunkX());
        assertEquals(-4, column.chunkZ());
        assertEquals(77L, column.columnTimestamp());
        assertEquals(LSSConstants.DIM_STR_THE_END, column.dimension().identifier().toString());
        assertEquals(LSSConstants.MAX_SEND_SECTIONS_SIZE, column.decompressedSections().length);
    }
}
