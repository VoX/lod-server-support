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
    void chunkRequestTruncation() {
        int rawCount = 2000;
        var b = buf();
        b.writeVarInt(1); // batchId
        b.writeVarInt(rawCount);
        for (int i = 0; i < rawCount; i++) {
            b.writeLong(PositionUtil.packPosition(i, i));
        }
        for (int i = 0; i < rawCount; i++) {
            b.writeLong(i * 100L);
        }
        var decoded = ChunkRequestC2SPayload.CODEC.decode(b);
        assertEquals(ChunkRequestC2SPayload.MAX_POSITIONS, decoded.positions().length);
        assertEquals(ChunkRequestC2SPayload.MAX_POSITIONS, decoded.timestamps().length);
        // All bytes consumed — buffer should be empty
        assertEquals(0, b.readableBytes());
        b.release();
    }

    @Test
    void dirtyColumnsTruncation() {
        int rawCount = 5000;
        var b = buf();
        b.writeVarInt(rawCount);
        for (int i = 0; i < rawCount; i++) {
            b.writeLong(PositionUtil.packPosition(i, i));
        }
        var decoded = DirtyColumnsS2CPayload.CODEC.decode(b);
        assertEquals(DirtyColumnsS2CPayload.MAX_POSITIONS, decoded.dirtyPositions().length);
        // All bytes consumed
        assertEquals(0, b.readableBytes());
        b.release();
    }

    @Test
    void cancelRequestTruncation() {
        int rawCount = 500;
        var b = buf();
        b.writeVarInt(rawCount);
        for (int i = 0; i < rawCount; i++) {
            b.writeVarInt(i);
        }
        var decoded = CancelRequestC2SPayload.CODEC.decode(b);
        assertEquals(CancelRequestC2SPayload.MAX_BATCH_IDS, decoded.batchIds().length);
        // All bytes consumed — excess VarInts drained
        assertEquals(0, b.readableBytes());
        b.release();
    }
}
