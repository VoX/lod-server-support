package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.PositionUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayloadCodecOversizedTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void dirtyColumnsTruncation() {
        int rawCount = 11000;
        var b = buf();
        b.writeVarInt(rawCount);
        for (int i = 0; i < rawCount; i++) {
            b.writeLong(PositionUtil.packPosition(i, i));
        }
        var decoded = DirtyColumnsS2CPayload.read(b);
        assertEquals(DirtyColumnsS2CPayload.MAX_POSITIONS, decoded.dirtyPositions().length);
        // Excess entries are drained to avoid leaving unconsumed bytes in the buffer
        assertEquals(0, b.readableBytes());
        b.release();
    }

    @Test
    void dirtyColumnsLyingCountTruncatedInExcessRegionDecodesTheCap() {
        // The count VarInt claims 20000 but the frame physically carries exactly
        // MAX_POSITIONS longs: the decoder reads the cap's worth and the skip clamp
        // (min(excess*8, readableBytes)) absorbs the lie about bytes that never existed.
        // A regression to an unclamped skipBytes(excess*8) throws here.
        int claimed = 20000;
        int max = DirtyColumnsS2CPayload.MAX_POSITIONS;
        var b = buf();
        b.writeVarInt(claimed);
        for (int i = 0; i < max; i++) {
            b.writeLong(PositionUtil.packPosition(i, -i));
        }
        try {
            var decoded = DirtyColumnsS2CPayload.read(b);
            assertEquals(max, decoded.dirtyPositions().length);
            assertEquals(PositionUtil.packPosition(0, 0), decoded.dirtyPositions()[0]);
            assertEquals(PositionUtil.packPosition(max - 1, -(max - 1)),
                    decoded.dirtyPositions()[max - 1]);
            assertEquals(0, b.readableBytes(), "the clamped skip must drain nothing extra");
        } finally {
            b.release();
        }
    }

    @Test
    void dirtyColumnsLyingCountWithBodyShorterThanCapThrowsDeterministically() {
        // Claimed 20000 with only 100 longs present: the read loop still targets
        // min(claimed, MAX_POSITIONS) entries and fails at the 101st read. Allocation is
        // bounded by MAX_POSITIONS (80 KB) no matter what the VarInt claims, so the pinned
        // contract is the deterministic per-frame throw — NOT "return the 100 present".
        var b = buf();
        b.writeVarInt(20000);
        for (int i = 0; i < 100; i++) {
            b.writeLong(PositionUtil.packPosition(i, i));
        }
        try {
            assertThrows(IndexOutOfBoundsException.class,
                    () -> DirtyColumnsS2CPayload.read(b));
        } finally {
            b.release();
        }
    }

}
