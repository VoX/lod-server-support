package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayloadCodecTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    /** Create a minimal raw empty column (0 sections). */
    private byte[] emptyColumn() {
        var wireBuf = new FriendlyByteBuf(Unpooled.buffer());
        wireBuf.writeVarInt(0);
        byte[] raw = new byte[wireBuf.readableBytes()];
        wireBuf.readBytes(raw);
        wireBuf.release();
        return raw;
    }

    // --- HandshakeC2SPayload ---

    @Test
    void handshakeRoundtrip() {
        var original = new HandshakeC2SPayload(4, 1);
        var b = buf();
        HandshakeC2SPayload.CODEC.encode(b, original);
        var decoded = HandshakeC2SPayload.CODEC.decode(b);
        assertEquals(original.protocolVersion(), decoded.protocolVersion());
        assertEquals(original.capabilities(), decoded.capabilities());
        b.release();
    }

    // --- SessionConfigS2CPayload ---

    @Test
    void sessionConfigRoundtrip() {
        // Must use the live protocol version: the decoder treats any other version as a
        // foreign layout and drains the frame instead of reading the fields.
        var original = new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 128, true);
        var b = buf();
        SessionConfigS2CPayload.CODEC.encode(b, original);
        var decoded = SessionConfigS2CPayload.CODEC.decode(b);
        assertEquals(original.protocolVersion(), decoded.protocolVersion());
        assertEquals(original.enabled(), decoded.enabled());
        assertEquals(original.lodDistanceChunks(), decoded.lodDistanceChunks());
        assertEquals(original.generationEnabled(), decoded.generationEnabled());
        b.release();
    }

    // --- BatchResponseS2CPayload ---

    @Test
    void batchResponseRoundtrip() {
        byte[] types = {
                (byte) 0, // the retired-and-reserved v16 rate-limited tag: the codec is
                          // type-agnostic and must still round-trip it unaltered
                LSSConstants.RESPONSE_UP_TO_DATE,
                LSSConstants.RESPONSE_NOT_GENERATED
        };
        long[] positions = {
                PositionUtil.packPosition(42, -1),
                PositionUtil.packPosition(99, 3),
                PositionUtil.packPosition(-77, 77)
        };
        var original = new BatchResponseS2CPayload(types, positions, 3);
        var b = buf();
        BatchResponseS2CPayload.CODEC.encode(b, original);
        var decoded = BatchResponseS2CPayload.CODEC.decode(b);
        assertEquals(3, decoded.count());
        for (int i = 0; i < 3; i++) {
            assertEquals(types[i], decoded.responseTypes()[i]);
            assertEquals(positions[i], decoded.packedPositions()[i]);
        }
        assertEquals(0, b.readableBytes());
        b.release();
    }

    // --- DirtyColumnsS2CPayload ---

    @Test
    void dirtyColumnsRoundtrip() {
        long[] positions = {
                PositionUtil.packPosition(10, 20),
                PositionUtil.packPosition(-5, 100)
        };
        var original = new DirtyColumnsS2CPayload(positions);
        var b = buf();
        DirtyColumnsS2CPayload.CODEC.encode(b, original);
        var decoded = DirtyColumnsS2CPayload.CODEC.decode(b);
        assertArrayEquals(original.dirtyPositions(), decoded.dirtyPositions());
        b.release();
    }

    // --- BatchChunkRequestC2SPayload ---

    @Test
    void batchChunkRequestRoundtrip() {
        long[] positions = {
                PositionUtil.packPosition(10, 20),
                PositionUtil.packPosition(-5, 100),
                PositionUtil.packPosition(7, -3)
        };
        long[] timestamps = {1000L, 0L, -1L};
        var original = new BatchChunkRequestC2SPayload(positions, timestamps, 3);
        var b = buf();
        BatchChunkRequestC2SPayload.CODEC.encode(b, original);
        var decoded = BatchChunkRequestC2SPayload.CODEC.decode(b);
        assertEquals(3, decoded.count());
        assertArrayEquals(positions, decoded.packedPositions());
        assertArrayEquals(timestamps, decoded.clientTimestamps());
        b.release();
    }

    // --- VoxelColumnS2CPayload dimension encoding ---

    @Test
    void voxelColumnOverworldDimensionRoundtrip() {
        byte[] sections = emptyColumn();
        var original = new VoxelColumnS2CPayload(10, 20, Level.OVERWORLD, 12345L, sections);
        var b = buf();
        VoxelColumnS2CPayload.CODEC.encode(b, original);
        var decoded = VoxelColumnS2CPayload.CODEC.decode(b);
        assertEquals(Level.OVERWORLD, decoded.dimension());
        assertEquals(original.chunkX(), decoded.chunkX());
        assertEquals(original.chunkZ(), decoded.chunkZ());
        assertEquals(original.columnTimestamp(), decoded.columnTimestamp());
        assertEquals(0, b.readableBytes());
        b.release();
    }

    @Test
    void voxelColumnNetherDimensionRoundtrip() {
        byte[] sections = emptyColumn();
        var original = new VoxelColumnS2CPayload(5, -5, Level.NETHER, 99L, sections);
        var b = buf();
        VoxelColumnS2CPayload.CODEC.encode(b, original);
        var decoded = VoxelColumnS2CPayload.CODEC.decode(b);
        assertEquals(Level.NETHER, decoded.dimension());
        b.release();
    }

    @Test
    void voxelColumnEndDimensionRoundtrip() {
        byte[] sections = emptyColumn();
        var original = new VoxelColumnS2CPayload(0, 0, Level.END, 0L, sections);
        var b = buf();
        VoxelColumnS2CPayload.CODEC.encode(b, original);
        var decoded = VoxelColumnS2CPayload.CODEC.decode(b);
        assertEquals(Level.END, decoded.dimension());
        b.release();
    }

}
