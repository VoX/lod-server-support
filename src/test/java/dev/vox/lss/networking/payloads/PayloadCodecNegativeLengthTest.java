package dev.vox.lss.networking.payloads;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayloadCodecNegativeLengthTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void chunkRequestNegativeLength() {
        var b = buf();
        b.writeVarInt(1);  // batchId
        b.writeVarInt(-1); // negative positions count
        var decoded = ChunkRequestC2SPayload.CODEC.decode(b);
        assertEquals(0, decoded.positions().length);
        assertEquals(0, decoded.timestamps().length);
        b.release();
    }

    @Test
    void dirtyColumnsNegativeLength() {
        var b = buf();
        b.writeVarInt(-1); // negative positions count
        var decoded = DirtyColumnsS2CPayload.CODEC.decode(b);
        assertEquals(0, decoded.dirtyPositions().length);
        b.release();
    }

    @Test
    void cancelRequestNegativeLength() {
        var b = buf();
        b.writeVarInt(-1); // negative batch IDs count
        var decoded = CancelRequestC2SPayload.CODEC.decode(b);
        assertEquals(0, decoded.batchIds().length);
        b.release();
    }
}
