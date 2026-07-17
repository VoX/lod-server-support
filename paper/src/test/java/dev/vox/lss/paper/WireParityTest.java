package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
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
        long[] pos = {0L, 0x8000000000000001L, -1L};
        long[] ts = {0L, 1700000000000L, Long.MIN_VALUE};
        byte[] frame = ref(b -> {
            b.writeVarInt(3);
            for (int i = 0; i < 3; i++) {
                b.writeLong(pos[i]);
                b.writeLong(ts[i]);
            }
        });
        var d = PaperPayloadHandler.decodeBatchChunkRequest(frame);
        assertNotNull(d);
        assertEquals(3, d.count());
        assertArrayEquals(pos, d.packedPositions());
        assertArrayEquals(ts, d.clientTimestamps());
        // empty
        var empty = PaperPayloadHandler.decodeBatchChunkRequest(new byte[]{0});
        assertNotNull(empty);
        assertEquals(0, empty.count());
    }

    @Test
    void batchChunkRequestExtremeCorners() {
        // ts=-1 (unknown/first request) and ts=Long.MAX_VALUE plus the (MAX,MAX)/(MAX,MIN)
        // corner positions — identical reference frame to the Fabric twin's
        // #batchChunkRequestExtremeCorners.
        long[] pos = {PositionUtil.packPosition(Integer.MAX_VALUE, Integer.MAX_VALUE),
                PositionUtil.packPosition(Integer.MAX_VALUE, Integer.MIN_VALUE)};
        long[] ts = {-1L, Long.MAX_VALUE};
        byte[] frame = ref(b -> {
            b.writeVarInt(2);
            for (int i = 0; i < 2; i++) {
                b.writeLong(pos[i]);
                b.writeLong(ts[i]);
            }
        });
        var d = PaperPayloadHandler.decodeBatchChunkRequest(frame);
        assertNotNull(d);
        assertEquals(2, d.count());
        assertArrayEquals(pos, d.packedPositions());
        assertArrayEquals(ts, d.clientTimestamps());
    }

    // ---- S2C (Paper encodes; bytes must match the reference) ----

    @Test
    void sessionConfig() {
        byte[] expected = ref(b -> {
            b.writeVarInt(3);
            b.writeBoolean(true);
            b.writeVarInt(8);
            b.writeBoolean(false);
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeSessionConfig(
                3, true, 8, false));
    }

    @Test
    void sessionConfigVarIntBoundaries() {
        // lodDistance crossing the 1->2 byte VarInt boundary (127/128) and the 2048 config
        // max — the one variable-width field left in the 4-field frame. Identical reference
        // ops to the Fabric twin's #sessionConfigVarIntBoundaries.
        int[] cases = {127, 128, 2048};
        for (int lod : cases) {
            byte[] expected = ref(b -> {
                b.writeVarInt(LSSConstants.PROTOCOL_VERSION);
                b.writeBoolean(true);
                b.writeVarInt(lod);
                b.writeBoolean(false);
            });
            assertArrayEquals(expected, PaperPayloadHandler.encodeSessionConfig(
                    LSSConstants.PROTOCOL_VERSION, true, lod, false),
                    "sessionConfig lod=" + lod);
        }
    }

    @Test
    void batchResponse() {
        // Identical fill to the Fabric twin — type 0 is the retired-and-reserved v16
        // rate-limited tag, 200 a hypothetical future one. Both platforms' encoders must stay
        // type-agnostic and produce the same bytes for both; that is the parity being pinned.
        byte[] types = {(byte) 0, LSSConstants.RESPONSE_UP_TO_DATE,
                LSSConstants.RESPONSE_NOT_GENERATED, (byte) 200};
        long[] positions = {0L, PositionUtil.packPosition(10, 20), -1L,
                PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MAX_VALUE)};
        byte[] expected = ref(b -> {
            b.writeVarInt(4);
            for (int i = 0; i < 4; i++) {
                b.writeByte(types[i]);
                b.writeLong(positions[i]);
            }
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeBatchResponse(types, positions, 4));
    }

    @Test
    void batchResponseAtMaxCountMatchesReference() {
        // 4096 entries puts the count VarInt in its 2-byte regime — the only count regime
        // production ever ships at full flush. Identical fill to the Fabric twin's
        // #batchResponseAtMaxCountMatchesReference, so Fabric codec bytes == Paper encoder
        // bytes at max count.
        int max = LSSConstants.MAX_BATCH_RESPONSES;
        byte[] types = new byte[max];
        long[] positions = new long[max];
        for (int i = 0; i < max; i++) {
            types[i] = (byte) (i % 3);
            positions[i] = PositionUtil.packPosition(i - 2048, 2048 - i);
        }
        byte[] expected = ref(b -> {
            b.writeVarInt(max);
            for (int i = 0; i < max; i++) {
                b.writeByte(types[i]);
                b.writeLong(positions[i]);
            }
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeBatchResponse(types, positions, max));
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
        for (String dim : new String[]{
                "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
            byte[] expected = ref(b -> {
                b.writeInt(-5);
                b.writeInt(Integer.MAX_VALUE);
                b.writeUtf(dim);
                b.writeLong(-1L);
                b.writeByte(-1); // serve-source: unknown (source-less convenience path)
                b.writeByteArray(sections);
            });
            assertArrayEquals(expected, PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                    -5, Integer.MAX_VALUE, dim, -1L, sections), "voxelColumn " + dim);
        }
    }

    @Test
    void voxelColumnCustomDimension() {
        byte[] sections = {1, 2, 3};
        byte[] expected = ref(b -> {
            b.writeInt(0);
            b.writeInt(0);
            b.writeUtf("lsstest:custom");
            b.writeLong(42L);
            b.writeByte(-1); // serve-source: unknown (source-less convenience path)
            b.writeByteArray(sections);
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                0, 0, "lsstest:custom", 42L, sections));
    }

    @Test
    void voxelColumnEmptySectionBytes() {
        // sectionBytes=byte[0] is the legitimate "nothing visible" shape: it must encode as
        // a single 0x00 length VarInt (identical reference to the Fabric twin, whose decoder
        // is what hands the client its defensive empty-bytes ingest report).
        byte[] expected = ref(b -> {
            b.writeInt(11);
            b.writeInt(-7);
            b.writeUtf("minecraft:overworld");
            b.writeLong(5L);
            b.writeByte(-1); // serve-source: unknown (source-less convenience path)
            b.writeByteArray(new byte[0]);
        });
        byte[] encoded = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                11, -7, "minecraft:overworld", 5L, new byte[0]);
        assertArrayEquals(expected, encoded);
        assertEquals(0, encoded[encoded.length - 1],
                "empty section bytes must encode as a single 0x00 length VarInt");
    }

    // ---- Constants pins (Paper-classpath twins of the Fabric ProtocolConstantsTest) ----

    @Test
    void channelConstantsParseDistinctUnderLssAndVersionPinned() {
        String[] channels = {
                LSSConstants.CHANNEL_HANDSHAKE, LSSConstants.CHANNEL_CHUNK_REQUEST,
                LSSConstants.CHANNEL_SESSION_CONFIG, LSSConstants.CHANNEL_DIRTY_COLUMNS,
                LSSConstants.CHANNEL_VOXEL_COLUMN, LSSConstants.CHANNEL_BATCH_RESPONSE};
        var distinct = new HashSet<String>();
        for (String channel : channels) {
            var id = Identifier.parse(channel); // throws on a typo'd channel string
            assertEquals(LSSConstants.MOD_ID, id.getNamespace(),
                    channel + " must live under the lss: namespace");
            distinct.add(id.toString());
        }
        assertEquals(6, distinct.size(), "channel ids must be pairwise distinct");
        // Bump the literal only with a deliberate wire change reviewed on both platforms.
        // 16 -> 17: the declarative want-set model retires the rate-limited bounce (byte 0).
        assertEquals(18, LSSConstants.PROTOCOL_VERSION); // 18: VoxelColumn serve-source byte
    }

    // ---- Meta: the parity corpus must cover the whole v17 payload surface ----

    @Test
    void referenceFramesCoverEveryDeclaredChannel() throws IllegalAccessException {
        // A 7th payload needs a new CHANNEL_* constant; this set-equality trips then,
        // forcing a reference frame in this suite (and the Fabric twin) before it goes
        // green again. The literals below double as this suite's coverage list.
        var declared = new HashSet<String>();
        for (var f : LSSConstants.class.getDeclaredFields()) {
            if (f.getName().startsWith("CHANNEL_") && f.getType() == String.class) {
                declared.add((String) f.get(null));
            }
        }
        var covered = Set.of("lss:handshake_c2s", "lss:batch_chunk_req", "lss:session_config",
                "lss:dirty_columns", "lss:voxel_column", "lss:batch_response");
        assertEquals(covered, declared,
                "every LSS channel must have a reference frame in this suite — a new payload"
                + " requires frames in BOTH WireParityTests");
    }
}
