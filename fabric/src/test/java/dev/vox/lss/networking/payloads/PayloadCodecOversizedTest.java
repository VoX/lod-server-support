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
        var decoded = DirtyColumnsS2CPayload.CODEC.decode(b);
        assertEquals(DirtyColumnsS2CPayload.MAX_POSITIONS, decoded.dirtyPositions().length);
        // Excess entries are drained to avoid leaving unconsumed bytes in the buffer
        assertEquals(0, b.readableBytes());
        b.release();
    }

}
