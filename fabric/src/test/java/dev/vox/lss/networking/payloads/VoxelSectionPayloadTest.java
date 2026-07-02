package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoxelSectionPayloadTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    /** Create a raw blob representing an empty column (VarInt sectionCount=0). */
    private byte[] emptyColumn() {
        var wireBuf = new FriendlyByteBuf(Unpooled.buffer());
        wireBuf.writeVarInt(0);
        byte[] raw = new byte[wireBuf.readableBytes()];
        wireBuf.readBytes(raw);
        wireBuf.release();
        return raw;
    }

    @Test
    void roundtripPreservesHeaderFields() {
        var dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(LSSConstants.DIM_STR_OVERWORLD));
        byte[] sections = emptyColumn();
        var original = new VoxelColumnS2CPayload(10, -20, dim, 99999L, sections);

        var b = buf();
        original.write(b);
        var decoded = VoxelColumnS2CPayload.read(b);

        assertEquals(10, decoded.chunkX());
        assertEquals(-20, decoded.chunkZ());
        assertEquals(dim, decoded.dimension());
        assertEquals(99999L, decoded.columnTimestamp());
        b.release();
    }

    @Test
    void roundtripPreservesSectionData() {
        var dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(LSSConstants.DIM_STR_OVERWORLD));
        // Write VarInt sectionCount=2, then dummy bytes
        var wireBuf = new FriendlyByteBuf(Unpooled.buffer());
        wireBuf.writeVarInt(2);
        wireBuf.writeByte(3); // sectionY
        wireBuf.writeByte(7); // sectionY
        byte[] raw = new byte[wireBuf.readableBytes()];
        wireBuf.readBytes(raw);
        wireBuf.release();

        var original = new VoxelColumnS2CPayload(-5, 100, dim, 12345L, raw);

        var b = buf();
        original.write(b);
        var decoded = VoxelColumnS2CPayload.read(b);

        assertNotNull(decoded.decompressedSections());
        // Verify we can read the VarInt sectionCount back
        var readBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(decoded.decompressedSections()));
        try {
            assertEquals(2, readBuf.readVarInt());
            assertEquals(3, readBuf.readByte());
            assertEquals(7, readBuf.readByte());
        } finally {
            readBuf.release();
        }
        b.release();
    }

    @Test
    void roundtripEmptyColumn() {
        var dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(LSSConstants.DIM_STR_THE_END));
        byte[] sections = emptyColumn();
        var original = new VoxelColumnS2CPayload(0, 0, dim, 0L, sections);

        var b = buf();
        original.write(b);
        var decoded = VoxelColumnS2CPayload.read(b);

        assertNotNull(decoded.decompressedSections());
        var readBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(decoded.decompressedSections()));
        try {
            assertEquals(0, readBuf.readVarInt());
        } finally {
            readBuf.release();
        }
        assertEquals(0L, decoded.columnTimestamp());
        b.release();
    }

    @Test
    void estimatedBytesPositive() {
        var dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(LSSConstants.DIM_STR_OVERWORLD));
        byte[] sections = emptyColumn();
        var payload = new VoxelColumnS2CPayload(0, 0, dim, 0L, sections);
        assertTrue(payload.estimatedBytes() > 0);
    }

    @Test
    void handshakeWithCapabilitiesRoundtrip() {
        var original = new HandshakeC2SPayload(5, 1);
        var b = buf();
        original.write(b);
        var decoded = HandshakeC2SPayload.read(b);
        assertEquals(5, decoded.protocolVersion());
        assertEquals(1, decoded.capabilities());
        b.release();
    }

    @Test
    void sessionConfigRoundtrip() {
        // Must use the live protocol version: the decoder treats any other version as a
        // foreign layout and drains the frame instead of reading the fields.
        var original = new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 128, 100, 40, true);
        var b = buf();
        original.write(b);
        var decoded = SessionConfigS2CPayload.read(b);
        assertEquals(100, decoded.syncOnLoadConcurrencyLimitPerPlayer());
        assertEquals(40, decoded.generationConcurrencyLimitPerPlayer());
        assertTrue(decoded.generationEnabled());
        b.release();
    }

    @Test
    void dimensionOverworldRoundtrip() {
        byte[] sections = emptyColumn();
        var original = new VoxelColumnS2CPayload(1, 3, Level.OVERWORLD, 50000L, sections);
        var b = buf();
        original.write(b);
        var decoded = VoxelColumnS2CPayload.read(b);
        assertEquals(Level.OVERWORLD, decoded.dimension());
        b.release();
    }

    @Test
    void dimensionNetherRoundtrip() {
        byte[] sections = emptyColumn();
        var original = new VoxelColumnS2CPayload(0, 0, Level.NETHER, 0L, sections);
        var b = buf();
        original.write(b);
        var decoded = VoxelColumnS2CPayload.read(b);
        assertEquals(Level.NETHER, decoded.dimension());
        b.release();
    }

    @Test
    void dimensionEndRoundtrip() {
        byte[] sections = emptyColumn();
        var original = new VoxelColumnS2CPayload(0, 0, Level.END, 0L, sections);
        var b = buf();
        original.write(b);
        var decoded = VoxelColumnS2CPayload.read(b);
        assertEquals(Level.END, decoded.dimension());
        b.release();
    }
}
