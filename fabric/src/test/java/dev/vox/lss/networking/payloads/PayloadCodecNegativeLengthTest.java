package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
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
    void dirtyColumnsNegativeLength() {
        var b = buf();
        b.writeVarInt(-1); // negative positions count
        var decoded = DirtyColumnsS2CPayload.read(b);
        assertEquals(0, decoded.dirtyPositions().length);
        b.release();
    }

    @Test
    void dirtyColumnsExplicitZeroLength() {
        // The negative branch reaches length 0 via Math.max; an explicit length=0 frame is
        // the legitimate empty-broadcast shape and must decode to long[0] without a throw.
        var b = buf();
        b.writeVarInt(0);
        var decoded = DirtyColumnsS2CPayload.read(b);
        assertEquals(0, decoded.dirtyPositions().length);
        assertEquals(0, b.readableBytes());
        b.release();
    }

    @Test
    void voxelColumnNegativeSectionLengthThrowsPinnedType() {
        // readByteArray's cap check is `len > max` only, so a negative sectionBytes length
        // VarInt reaches `new byte[len]` and surfaces as NegativeArraySizeException — not
        // DecoderException. Exact-type pin: this is what the connection layer reports for
        // the hostile frame, and a guard rewrite that changes it must be reviewed.
        var b = buf();
        b.writeInt(0);
        b.writeInt(0);
        b.writeUtf(LSSConstants.DIM_STR_OVERWORLD, LSSConstants.MAX_DIMENSION_STRING_LENGTH);
        b.writeLong(0L);
        b.writeVarInt(-1); // sectionBytes length
        try {
            assertThrows(NegativeArraySizeException.class,
                    () -> VoxelColumnS2CPayload.read(b));
        } finally {
            b.release();
        }
    }

}
