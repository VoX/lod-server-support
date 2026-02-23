package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.PositionUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
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

    // --- HandshakeC2SPayload ---

    @Test
    void handshakeRoundtrip() {
        var original = new HandshakeC2SPayload(4);
        var b = buf();
        HandshakeC2SPayload.CODEC.encode(b, original);
        var decoded = HandshakeC2SPayload.CODEC.decode(b);
        assertEquals(original.protocolVersion(), decoded.protocolVersion());
        b.release();
    }

    // --- SessionConfigS2CPayload ---

    @Test
    void sessionConfigRoundtrip() {
        var original = new SessionConfigS2CPayload(4, true, 128, 256, 512, 8, 64);
        var b = buf();
        SessionConfigS2CPayload.CODEC.encode(b, original);
        var decoded = SessionConfigS2CPayload.CODEC.decode(b);
        assertEquals(original.protocolVersion(), decoded.protocolVersion());
        assertEquals(original.enabled(), decoded.enabled());
        assertEquals(original.lodDistanceChunks(), decoded.lodDistanceChunks());
        assertEquals(original.maxRequestsPerBatch(), decoded.maxRequestsPerBatch());
        assertEquals(original.maxPendingRequests(), decoded.maxPendingRequests());
        assertEquals(original.maxGenerationRequestsPerBatch(), decoded.maxGenerationRequestsPerBatch());
        assertEquals(original.generationDistanceChunks(), decoded.generationDistanceChunks());
        b.release();
    }

    // --- RequestCompleteS2CPayload ---

    @Test
    void requestCompleteRoundtrip() {
        var original = new RequestCompleteS2CPayload(42, RequestCompleteS2CPayload.STATUS_DONE);
        var b = buf();
        RequestCompleteS2CPayload.CODEC.encode(b, original);
        var decoded = RequestCompleteS2CPayload.CODEC.decode(b);
        assertEquals(original.batchId(), decoded.batchId());
        assertEquals(original.status(), decoded.status());
        b.release();
    }

    // --- ColumnUpToDateS2CPayload ---

    @Test
    void columnUpToDateRoundtrip() {
        var original = new ColumnUpToDateS2CPayload(100, -200);
        var b = buf();
        ColumnUpToDateS2CPayload.CODEC.encode(b, original);
        var decoded = ColumnUpToDateS2CPayload.CODEC.decode(b);
        assertEquals(original.chunkX(), decoded.chunkX());
        assertEquals(original.chunkZ(), decoded.chunkZ());
        b.release();
    }

    // --- CancelRequestC2SPayload ---

    @Test
    void cancelRequestRoundtrip() {
        var original = new CancelRequestC2SPayload(new int[]{1, 2, 3, 42});
        var b = buf();
        CancelRequestC2SPayload.CODEC.encode(b, original);
        var decoded = CancelRequestC2SPayload.CODEC.decode(b);
        assertArrayEquals(original.batchIds(), decoded.batchIds());
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

    @Test
    void dirtyColumnsTruncation() {
        long[] positions = new long[5000]; // exceeds MAX_POSITIONS (4096)
        for (int i = 0; i < positions.length; i++) {
            positions[i] = PositionUtil.packPosition(i, i);
        }
        var original = new DirtyColumnsS2CPayload(positions);
        var b = buf();
        DirtyColumnsS2CPayload.CODEC.encode(b, original);
        var decoded = DirtyColumnsS2CPayload.CODEC.decode(b);
        assertEquals(DirtyColumnsS2CPayload.MAX_POSITIONS, decoded.dirtyPositions().length);
        b.release();
    }

    // --- ChunkRequestC2SPayload ---

    @Test
    void chunkRequestRoundtrip() {
        long[] positions = {
                PositionUtil.packPosition(10, 20),
                PositionUtil.packPosition(-5, 100)
        };
        long[] timestamps = {1000L, 2000L};
        var original = new ChunkRequestC2SPayload(7, positions, timestamps);
        var b = buf();
        ChunkRequestC2SPayload.CODEC.encode(b, original);
        var decoded = ChunkRequestC2SPayload.CODEC.decode(b);
        assertEquals(original.batchId(), decoded.batchId());
        assertArrayEquals(original.positions(), decoded.positions());
        assertArrayEquals(original.timestamps(), decoded.timestamps());
        b.release();
    }

    @Test
    void chunkRequestTruncation() {
        long[] positions = new long[1500]; // exceeds MAX_POSITIONS (1024)
        long[] timestamps = new long[1500];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = PositionUtil.packPosition(i, i);
            timestamps[i] = i * 100L;
        }
        var original = new ChunkRequestC2SPayload(1, positions, timestamps);
        var b = buf();
        ChunkRequestC2SPayload.CODEC.encode(b, original);
        var decoded = ChunkRequestC2SPayload.CODEC.decode(b);
        assertEquals(ChunkRequestC2SPayload.MAX_POSITIONS, decoded.positions().length);
        assertEquals(ChunkRequestC2SPayload.MAX_POSITIONS, decoded.timestamps().length);
        b.release();
    }

    // --- ChunkSectionS2CPayload ---

    @Test
    void chunkSectionRoundtripNoLight() {
        byte[] sectionData = new byte[256];
        for (int i = 0; i < sectionData.length; i++) sectionData[i] = (byte) (i & 0xFF);
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld"));
        var original = new ChunkSectionS2CPayload(
                10, 3, -20, dim, sectionData, (byte) 0x00,
                null, null, (byte) 0, (byte) 0, 12345L
        );
        var b = buf();
        ChunkSectionS2CPayload.CODEC.encode(b, original);
        var decoded = ChunkSectionS2CPayload.CODEC.decode(b);
        assertEquals(original.chunkX(), decoded.chunkX());
        assertEquals(original.sectionY(), decoded.sectionY());
        assertEquals(original.chunkZ(), decoded.chunkZ());
        assertEquals(original.dimension(), decoded.dimension());
        assertArrayEquals(original.sectionData(), decoded.sectionData());
        assertEquals(original.lightFlags(), decoded.lightFlags());
        assertEquals(original.columnTimestamp(), decoded.columnTimestamp());
        b.release();
    }

    @Test
    void chunkSectionRoundtripWithFullLight() {
        byte[] sectionData = new byte[128];
        byte[] blockLight = new byte[2048];
        byte[] skyLight = new byte[2048];
        for (int i = 0; i < 2048; i++) {
            blockLight[i] = (byte) (i & 0xFF);
            skyLight[i] = (byte) ((i + 1) & 0xFF);
        }
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld"));
        var original = new ChunkSectionS2CPayload(
                5, -1, 8, dim, sectionData, (byte) (0x01 | 0x02),
                blockLight, skyLight, (byte) 0, (byte) 0, 99999L
        );
        var b = buf();
        ChunkSectionS2CPayload.CODEC.encode(b, original);
        var decoded = ChunkSectionS2CPayload.CODEC.decode(b);
        assertArrayEquals(original.sectionData(), decoded.sectionData());
        assertArrayEquals(original.blockLight(), decoded.blockLight());
        assertArrayEquals(original.skyLight(), decoded.skyLight());
        assertEquals(original.lightFlags(), decoded.lightFlags());
        b.release();
    }

    @Test
    void chunkSectionRoundtripWithUniformLight() {
        byte[] sectionData = new byte[64];
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:the_nether"));
        var original = new ChunkSectionS2CPayload(
                0, 0, 0, dim, sectionData, (byte) (0x04 | 0x08),
                null, null, (byte) 7, (byte) 15, 55555L
        );
        var b = buf();
        ChunkSectionS2CPayload.CODEC.encode(b, original);
        var decoded = ChunkSectionS2CPayload.CODEC.decode(b);
        assertArrayEquals(original.sectionData(), decoded.sectionData());
        assertEquals(original.uniformBlockLight(), decoded.uniformBlockLight());
        assertEquals(original.uniformSkyLight(), decoded.uniformSkyLight());
        assertEquals(original.lightFlags(), decoded.lightFlags());
        b.release();
    }
}
