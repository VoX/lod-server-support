package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the Fabric wire format for all 6 payloads to an explicit byte reference built from raw
 * {@link FriendlyByteBuf} ops. The Paper module has a mirror test
 * ({@code dev.vox.lss.paper.WireParityTest}) asserting its codec against the IDENTICAL reference
 * ops — so if either implementation drifts, one of the two suites fails. This is the cross-impl
 * parity guard (a Fabric client must understand a Paper server's frames and vice-versa).
 */
class WireParityTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Build the reference wire bytes from explicit FriendlyByteBuf ops. */
    private static byte[] ref(Consumer<FriendlyByteBuf> ops) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        ops.accept(b);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    private static <T> byte[] encode(StreamCodec<FriendlyByteBuf, T> codec, T payload) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(b, payload);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    private static <T> T decode(StreamCodec<FriendlyByteBuf, T> codec, byte[] frame) {
        var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(frame));
        T r = codec.decode(b);
        b.release();
        return r;
    }

    // ---- C2S ----

    @Test
    void handshake() {
        for (int[] f : new int[][]{{15, 1}, {300, 0}, {15, -1}}) {
            byte[] expected = ref(b -> {
                b.writeVarInt(f[0]);
                b.writeVarInt(f[1]);
            });
            assertArrayEquals(expected, encode(HandshakeC2SPayload.CODEC, new HandshakeC2SPayload(f[0], f[1])),
                    "handshake " + f[0] + "," + f[1]);
            var d = decode(HandshakeC2SPayload.CODEC, expected);
            assertEquals(f[0], d.protocolVersion());
            assertEquals(f[1], d.capabilities());
        }
    }

    @Test
    void batchChunkRequest() {
        long[] pos = {0L, 0x8000000000000001L, -1L};
        long[] ts = {0L, 1700000000000L, Long.MIN_VALUE};
        byte[] expected = ref(b -> {
            b.writeVarInt(3);
            for (int i = 0; i < 3; i++) {
                b.writeLong(pos[i]);
                b.writeLong(ts[i]);
            }
        });
        assertArrayEquals(expected, encode(BatchChunkRequestC2SPayload.CODEC,
                new BatchChunkRequestC2SPayload(pos, ts, 3)));
        // empty
        assertArrayEquals(new byte[]{0}, encode(BatchChunkRequestC2SPayload.CODEC,
                new BatchChunkRequestC2SPayload(new long[0], new long[0], 0)));
    }

    @Test
    void batchChunkRequestExtremeCorners() {
        // ts=-1 (unknown/first request) and ts=Long.MAX_VALUE plus the (MAX,MAX)/(MAX,MIN)
        // corner positions — the matrix cells the base parity frame does not carry.
        long[] pos = {PositionUtil.packPosition(Integer.MAX_VALUE, Integer.MAX_VALUE),
                PositionUtil.packPosition(Integer.MAX_VALUE, Integer.MIN_VALUE)};
        long[] ts = {-1L, Long.MAX_VALUE};
        byte[] expected = ref(b -> {
            b.writeVarInt(2);
            for (int i = 0; i < 2; i++) {
                b.writeLong(pos[i]);
                b.writeLong(ts[i]);
            }
        });
        assertArrayEquals(expected, encode(BatchChunkRequestC2SPayload.CODEC,
                new BatchChunkRequestC2SPayload(pos, ts, 2)));
        var d = decode(BatchChunkRequestC2SPayload.CODEC, expected);
        assertArrayEquals(pos, d.packedPositions());
        assertArrayEquals(ts, d.clientTimestamps());
    }

    // ---- S2C ----

    @Test
    void sessionConfig() {
        var p = new SessionConfigS2CPayload(3, true, 8, false);
        byte[] expected = ref(b -> {
            b.writeVarInt(3);
            b.writeBoolean(true);
            b.writeVarInt(8);
            b.writeBoolean(false);
        });
        assertArrayEquals(expected, encode(SessionConfigS2CPayload.CODEC, p));
    }

    @Test
    void sessionConfigToleratesForeignVersionLayout() {
        // A v15 server's 10-field SessionConfig frame. The current decoder must consume the whole
        // frame (no trailing bytes -> no decoder kick) and surface the version so the
        // client-side protocol gate can fire.
        byte[] v15Frame = ref(b -> {
            b.writeVarInt(15);          // protocolVersion
            b.writeBoolean(true);       // enabled
            b.writeVarInt(8);           // lodDistanceChunks
            b.writeVarInt(1);           // serverCapabilities
            b.writeVarInt(40);          // syncOnLoadRateLimitPerPlayer
            b.writeVarInt(300);         // syncOnLoadConcurrencyLimitPerPlayer
            b.writeVarInt(20);          // generationRateLimitPerPlayer
            b.writeVarInt(16);          // generationConcurrencyLimitPerPlayer
            b.writeBoolean(true);       // generationEnabled
            b.writeVarLong(1_000_000L); // playerBandwidthLimit
        });
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(v15Frame));
        var p = SessionConfigS2CPayload.CODEC.decode(buf);
        assertEquals(0, buf.readableBytes(), "decoder must consume the full foreign frame");
        buf.release();
        assertEquals(15, p.protocolVersion());
        assertEquals(false, p.enabled());
    }

    @Test
    void sessionConfigVarIntBoundaries() {
        // lodDistance crossing the 1->2 byte VarInt boundary (127/128) and the 2048 config
        // max — the one variable-width field left in the 4-field frame.
        int[] cases = {127, 128, 2048};
        for (int lod : cases) {
            byte[] expected = ref(b -> {
                b.writeVarInt(LSSConstants.PROTOCOL_VERSION);
                b.writeBoolean(true);
                b.writeVarInt(lod);
                b.writeBoolean(false);
            });
            var p = new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                    lod, false);
            assertArrayEquals(expected, encode(SessionConfigS2CPayload.CODEC, p),
                    "sessionConfig lod=" + lod);
            var d = decode(SessionConfigS2CPayload.CODEC, expected);
            assertEquals(lod, d.lodDistanceChunks());
        }
    }

    @Test
    void batchResponse() {
        // Type 0 is the RETIRED-and-RESERVED v16 rate-limited tag and 200 is a hypothetical
        // future tag: neither has a constant, and both are here on purpose. The codec is
        // type-agnostic by contract — it must ship any byte through unaltered, or a future
        // response type could not be added without a second wire break. Keep both.
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
        assertArrayEquals(expected, encode(BatchResponseS2CPayload.CODEC,
                new BatchResponseS2CPayload(types, positions, 4)));
    }

    @Test
    void batchResponseAtMaxCountMatchesReference() {
        // 4096 entries puts the count VarInt in its 2-byte regime — the only count regime
        // production ever ships at full flush. Identical fill in the Paper twin.
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
        assertArrayEquals(expected, encode(BatchResponseS2CPayload.CODEC,
                new BatchResponseS2CPayload(types, positions, max)));
    }

    @Test
    void dirtyColumns() {
        long[] positions = {PositionUtil.packPosition(10, 20), PositionUtil.packPosition(-5, 100),
                PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MAX_VALUE)};
        byte[] expected = ref(b -> {
            b.writeVarInt(3);
            for (long p : positions) b.writeLong(p);
        });
        assertArrayEquals(expected, encode(DirtyColumnsS2CPayload.CODEC, new DirtyColumnsS2CPayload(positions)));
    }

    @Test
    void voxelColumnVanillaDimensions() {
        byte[] sections = {1, 2, 3};
        record Case(ResourceKey<Level> key, String dim) {}
        for (Case c : new Case[]{new Case(Level.OVERWORLD, "minecraft:overworld"),
                new Case(Level.NETHER, "minecraft:the_nether"), new Case(Level.END, "minecraft:the_end")}) {
            byte[] expected = ref(b -> {
                b.writeInt(-5);
                b.writeInt(Integer.MAX_VALUE);
                b.writeUtf(c.dim());
                b.writeLong(-1L);
                b.writeByteArray(sections);
            });
            assertArrayEquals(expected, encode(VoxelColumnS2CPayload.CODEC,
                    new VoxelColumnS2CPayload(-5, Integer.MAX_VALUE, c.key(), -1L, sections)),
                    "voxelColumn dim " + c.dim());
        }
    }

    @Test
    void voxelColumnCustomDimension() {
        byte[] sections = {1, 2, 3};
        var custom = ResourceKey.create(Registries.DIMENSION, Identifier.parse("lsstest:custom"));
        byte[] expected = ref(b -> {
            b.writeInt(0);
            b.writeInt(0);
            b.writeUtf("lsstest:custom");
            b.writeLong(42L);
            b.writeByteArray(sections);
        });
        assertArrayEquals(expected, encode(VoxelColumnS2CPayload.CODEC,
                new VoxelColumnS2CPayload(0, 0, custom, 42L, sections)));
    }

    @Test
    void voxelColumnEmptySectionBytes() {
        // sectionBytes=byte[0] is the legitimate "nothing visible" shape: it must encode as
        // a single 0x00 length VarInt and decode back to byte[0] — the client's defensive
        // empty-bytes ingest report depends on actually receiving it.
        byte[] expected = ref(b -> {
            b.writeInt(11);
            b.writeInt(-7);
            b.writeUtf("minecraft:overworld");
            b.writeLong(5L);
            b.writeByteArray(new byte[0]);
        });
        assertArrayEquals(expected, encode(VoxelColumnS2CPayload.CODEC,
                new VoxelColumnS2CPayload(11, -7, Level.OVERWORLD, 5L, new byte[0])));
        assertEquals(0, expected[expected.length - 1],
                "empty section bytes must encode as a single 0x00 length VarInt");
        var d = decode(VoxelColumnS2CPayload.CODEC, expected);
        assertEquals(0, d.decompressedSections().length);
    }

    // ---- Meta: the parity corpus must cover the whole v17 payload surface ----

    @Test
    void referenceFramesCoverEveryDeclaredChannel() throws IllegalAccessException {
        // A 7th payload registered in LSSNetworking needs a new CHANNEL_* constant; this
        // set-equality trips then, forcing a reference frame in BOTH WireParityTests (and
        // the Paper twin's literal list) before the suite goes green again. Also pins that
        // each payload class binds exactly one declared channel — no extras, no orphans.
        var declared = new HashSet<String>();
        for (var f : LSSConstants.class.getDeclaredFields()) {
            if (f.getName().startsWith("CHANNEL_") && f.getType() == String.class) {
                declared.add((String) f.get(null));
            }
        }
        var covered = Set.of(
                HandshakeC2SPayload.TYPE.id().toString(),
                BatchChunkRequestC2SPayload.TYPE.id().toString(),
                SessionConfigS2CPayload.TYPE.id().toString(),
                DirtyColumnsS2CPayload.TYPE.id().toString(),
                VoxelColumnS2CPayload.TYPE.id().toString(),
                BatchResponseS2CPayload.TYPE.id().toString());
        assertEquals(covered, declared,
                "every LSS channel must map to exactly one payload with a reference frame in "
                + "this suite — a new payload requires frames in BOTH WireParityTests");
        assertEquals(6, declared.size());
    }
}
