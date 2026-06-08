package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Mirror of the Fabric {@code dev.vox.lss.networking.payloads.WireParityTest}: pins the Paper
 * codec ({@link PaperPayloadHandler}) to the IDENTICAL explicit byte reference. Because both
 * suites assert against the same reference ops, any drift between the Fabric and Paper wire
 * formats fails one of them. S2C payloads: Paper-encoded bytes == reference. C2S payloads: Paper
 * decode of a reference frame yields the expected fields.
 */
class WireParityTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static byte[] ref(Consumer<FriendlyByteBuf> ops) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        ops.accept(b);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    // ---- C2S (Paper decodes a Fabric-shaped frame) ----

    @Test
    void handshake() {
        for (int[] f : new int[][]{{15, 1}, {300, 0}, {15, -1}}) {
            byte[] frame = ref(b -> {
                b.writeVarInt(f[0]);
                b.writeVarInt(f[1]);
            });
            var d = PaperPayloadHandler.decodeHandshake(frame);
            assertNotNull(d);
            assertEquals(f[0], d.protocolVersion(), "handshake protocol " + f[0]);
            assertEquals(f[1], d.capabilities(), "handshake caps " + f[1]);
        }
    }

    @Test
    void batchChunkRequest() {
        int[] ids = {0, 1234567, -7};
        long[] pos = {0L, 0x8000000000000001L, -1L};
        long[] ts = {0L, 1700000000000L, Long.MIN_VALUE};
        byte[] frame = ref(b -> {
            b.writeVarInt(3);
            for (int i = 0; i < 3; i++) {
                b.writeVarInt(ids[i]);
                b.writeLong(pos[i]);
                b.writeLong(ts[i]);
            }
        });
        var d = PaperPayloadHandler.decodeBatchChunkRequest(frame);
        assertNotNull(d);
        assertEquals(3, d.count());
        assertArrayEquals(ids, d.requestIds());
        assertArrayEquals(pos, d.packedPositions());
        assertArrayEquals(ts, d.clientTimestamps());
        // empty
        var empty = PaperPayloadHandler.decodeBatchChunkRequest(new byte[]{0});
        assertNotNull(empty);
        assertEquals(0, empty.count());
    }

    @Test
    void cancelRequest() {
        for (int id : new int[]{0, 42, Integer.MAX_VALUE, -1}) {
            byte[] frame = ref(b -> b.writeVarInt(id));
            var d = PaperPayloadHandler.decodeCancelRequest(frame);
            assertNotNull(d);
            assertEquals(id, d.requestId());
        }
    }

    @Test
    void bandwidthUpdate() {
        for (long rate : new long[]{0L, 127L, 128L, 1_000_000L, Long.MAX_VALUE}) {
            byte[] frame = ref(b -> b.writeVarLong(rate));
            var d = PaperPayloadHandler.decodeBandwidthUpdate(frame);
            assertNotNull(d);
            assertEquals(rate, d.desiredRate());
        }
    }

    // ---- S2C (Paper encodes; bytes must match the reference) ----

    @Test
    void sessionConfig() {
        byte[] expected = ref(b -> {
            b.writeVarInt(3);
            b.writeBoolean(true);
            b.writeVarInt(8);
            b.writeVarInt(42);
            b.writeVarInt(300);
            b.writeVarInt(4);
            b.writeVarInt(128);
            b.writeVarInt(16);
            b.writeBoolean(false);
            b.writeVarLong(8_388_608L);
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeSessionConfig(
                3, true, 8, 42, 300, 4, 128, 16, false, 8_388_608L));
    }

    @Test
    void batchResponse() {
        byte[] types = {LSSConstants.RESPONSE_RATE_LIMITED, LSSConstants.RESPONSE_UP_TO_DATE,
                LSSConstants.RESPONSE_NOT_GENERATED, (byte) 200};
        int[] ids = {0, 1, -1, Integer.MAX_VALUE};
        byte[] expected = ref(b -> {
            b.writeVarInt(4);
            for (int i = 0; i < 4; i++) {
                b.writeByte(types[i]);
                b.writeVarInt(ids[i]);
            }
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeBatchResponse(types, ids, 4));
    }

    @Test
    void dirtyColumns() {
        long[] positions = {PositionUtil.packPosition(10, 20), PositionUtil.packPosition(-5, 100),
                PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MAX_VALUE)};
        byte[] expected = ref(b -> {
            b.writeVarInt(3);
            for (long p : positions) b.writeLong(p);
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeDirtyColumns(positions));
    }

    @Test
    void voxelColumnVanillaDimensions() {
        byte[] sections = {1, 2, 3};
        record Case(String dim, int ordinal) {}
        for (Case c : new Case[]{
                new Case("minecraft:overworld", 0),
                new Case("minecraft:the_nether", 1),
                new Case("minecraft:the_end", 2)}) {
            byte[] expected = ref(b -> {
                b.writeVarInt(300);
                b.writeInt(-5);
                b.writeInt(Integer.MAX_VALUE);
                b.writeVarInt(c.ordinal());
                b.writeLong(-1L);
                b.writeByteArray(sections);
            });
            assertArrayEquals(expected, PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                    300, -5, Integer.MAX_VALUE, c.dim(), -1L, sections), "voxelColumn " + c.dim());
        }
    }

    @Test
    void voxelColumnCustomDimension() {
        byte[] sections = {1, 2, 3};
        byte[] expected = ref(b -> {
            b.writeVarInt(7);
            b.writeInt(0);
            b.writeInt(0);
            b.writeVarInt(LSSConstants.DIM_CUSTOM);
            b.writeUtf("lsstest:custom");
            b.writeLong(42L);
            b.writeByteArray(sections);
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                7, 0, 0, "lsstest:custom", 42L, sections));
    }
}
