package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link SendActionBatcher}'s per-player accumulation and the split at
 * MAX_BATCH_RESPONSES. The client decoder rejects any frame over the cap (count guard ->
 * disconnect), so both the split itself and the integrity of every (type, position) pair
 * across the split are wire-safety invariants: a lost or duplicated tail wedges the
 * client's per-position tracking mid-resync.
 */
class SendActionBatcherTest {

    private static final int CAP = LSSConstants.MAX_BATCH_RESPONSES;

    @BeforeAll
    static void setup() {
        // The codec round-trip test touches MC payload classes
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** One emitted frame with arrays trimmed to count (the consumer contract is count-bounded). */
    private record Frame(UUID player, byte[] types, long[] positions, int count) {}

    private static byte typeFor(int i) {
        return switch (i % 3) {
            case 0 -> LSSConstants.RESPONSE_RATE_LIMITED;
            case 1 -> LSSConstants.RESPONSE_UP_TO_DATE;
            default -> LSSConstants.RESPONSE_NOT_GENERATED;
        };
    }

    private static long positionFor(int i) {
        return PositionUtil.packPosition(i, -i);
    }

    private static void fill(SendActionBatcher batcher, UUID player, int n) {
        for (int i = 0; i < n; i++) {
            batcher.add(player, typeFor(i), positionFor(i));
        }
    }

    private static List<Frame> collect(SendActionBatcher batcher) {
        var frames = new ArrayList<Frame>();
        batcher.forEach((player, types, positions, count) ->
                frames.add(new Frame(player, Arrays.copyOf(types, count),
                        Arrays.copyOf(positions, count), count)));
        return frames;
    }

    /** Asserts the frames carry exactly the pairs fill() added, in order, no loss/duplication. */
    private static void assertPairsIntact(List<Frame> frames, int total) {
        int i = 0;
        for (var frame : frames) {
            for (int j = 0; j < frame.count(); j++, i++) {
                assertEquals(typeFor(i), frame.types()[j], "type at global index " + i);
                assertEquals(positionFor(i), frame.positions()[j], "position at global index " + i);
            }
        }
        assertEquals(total, i, "every added pair must be emitted exactly once");
    }

    @Test
    void underCapEmitsOneFrame() {
        var batcher = new SendActionBatcher();
        var player = UUID.randomUUID();
        fill(batcher, player, 3);

        var frames = collect(batcher);
        assertEquals(1, frames.size());
        assertEquals(player, frames.get(0).player());
        assertEquals(3, frames.get(0).count());
        assertPairsIntact(frames, 3);
    }

    @Test
    void exactlyAtCapDoesNotSplit() {
        var batcher = new SendActionBatcher();
        var player = UUID.randomUUID();
        fill(batcher, player, CAP);

        var frames = collect(batcher);
        assertEquals(1, frames.size(), "a batch of exactly MAX_BATCH_RESPONSES must not split");
        assertEquals(CAP, frames.get(0).count());
        assertPairsIntact(frames, CAP);
    }

    @Test
    void overCapSplitsIntoDecoderLegalFramesWithNoLoss() {
        var batcher = new SendActionBatcher();
        var player = UUID.randomUUID();
        int total = CAP + 100;
        fill(batcher, player, total);

        var frames = collect(batcher);
        assertEquals(2, frames.size());
        assertEquals(CAP, frames.get(0).count());
        assertEquals(100, frames.get(1).count(), "the split tail must carry the remainder");
        for (var frame : frames) {
            assertTrue(frame.count() <= CAP, "every emitted frame must fit the decoder cap");
        }
        assertPairsIntact(frames, total);
    }

    @Test
    void exactMultipleOfCapSplitsWithoutEmptyTailFrame() {
        var batcher = new SendActionBatcher();
        var player = UUID.randomUUID();
        fill(batcher, player, CAP * 2);

        var frames = collect(batcher);
        assertEquals(2, frames.size(), "2*CAP must emit exactly two frames, no empty third");
        assertEquals(CAP, frames.get(0).count());
        assertEquals(CAP, frames.get(1).count());
        assertPairsIntact(frames, CAP * 2);
    }

    @Test
    void multiplePlayersBatchAndSplitIndependently() {
        var batcher = new SendActionBatcher();
        var big = UUID.randomUUID();
        var small = UUID.randomUUID();
        fill(batcher, big, CAP + 5);
        fill(batcher, small, 2);

        var byPlayer = new HashMap<UUID, List<Frame>>();
        for (var frame : collect(batcher)) {
            byPlayer.computeIfAbsent(frame.player(), k -> new ArrayList<>()).add(frame);
        }

        assertEquals(2, byPlayer.size());
        assertEquals(2, byPlayer.get(big).size(), "over-cap player splits");
        assertEquals(1, byPlayer.get(small).size(), "under-cap player does not");
        assertPairsIntact(byPlayer.get(big), CAP + 5);
        assertPairsIntact(byPlayer.get(small), 2);
    }

    @Test
    void splitFramesSurviveTheWireCodec() {
        // Mirrors RequestProcessingService.drainSendActions: each emitted frame becomes one
        // BatchResponseS2CPayload. Without the split, the single CAP+7 frame would throw in
        // the decoder's count guard and disconnect the client.
        var batcher = new SendActionBatcher();
        var player = UUID.randomUUID();
        int total = CAP + 7;
        fill(batcher, player, total);

        var roundTripped = new ArrayList<Frame>();
        batcher.forEach((uuid, types, positions, count) -> {
            var b = new FriendlyByteBuf(Unpooled.buffer());
            try {
                new BatchResponseS2CPayload(types, positions, count).write(b);
                var decoded = BatchResponseS2CPayload.read(b);
                assertEquals(0, b.readableBytes(), "decoded frame must consume all its bytes");
                roundTripped.add(new Frame(uuid, decoded.responseTypes(),
                        decoded.packedPositions(), decoded.count()));
            } finally {
                b.release();
            }
        });

        assertEquals(2, roundTripped.size());
        assertPairsIntact(roundTripped, total);
    }

    @Test
    void clearResetsForNextTickReuse() {
        var batcher = new SendActionBatcher();
        var player = UUID.randomUUID();
        fill(batcher, player, 5);
        assertFalse(batcher.isEmpty());

        batcher.clear();
        assertTrue(batcher.isEmpty());
        assertEquals(0, collect(batcher).size(), "cleared batcher must emit nothing");

        // The production batcher is a reused per-tick singleton — it must work after clear()
        fill(batcher, player, 4);
        var frames = collect(batcher);
        assertEquals(1, frames.size());
        assertPairsIntact(frames, 4);
    }
}
