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

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the Fabric wire format for all 8 payloads to an explicit byte reference built from raw
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
        int[] ids = {0, 1234567, -7};
        long[] pos = {0L, 0x8000000000000001L, -1L};
        long[] ts = {0L, 1700000000000L, Long.MIN_VALUE};
        byte[] expected = ref(b -> {
            b.writeVarInt(3);
            for (int i = 0; i < 3; i++) {
                b.writeVarInt(ids[i]);
                b.writeLong(pos[i]);
                b.writeLong(ts[i]);
            }
        });
        assertArrayEquals(expected, encode(BatchChunkRequestC2SPayload.CODEC,
                new BatchChunkRequestC2SPayload(ids, pos, ts, 3)));
        // empty
        assertArrayEquals(new byte[]{0}, encode(BatchChunkRequestC2SPayload.CODEC,
                new BatchChunkRequestC2SPayload(new int[0], new long[0], new long[0], 0)));
    }

    @Test
    void cancelRequest() {
        for (int id : new int[]{0, 42, Integer.MAX_VALUE, -1}) {
            byte[] expected = ref(b -> b.writeVarInt(id));
            assertArrayEquals(expected, encode(CancelRequestC2SPayload.CODEC, new CancelRequestC2SPayload(id)));
        }
    }

    @Test
    void bandwidthUpdate() {
        for (long rate : new long[]{0L, 127L, 128L, 1_000_000L, Long.MAX_VALUE}) {
            byte[] expected = ref(b -> b.writeVarLong(rate));
            assertArrayEquals(expected, encode(BandwidthUpdateC2SPayload.CODEC, new BandwidthUpdateC2SPayload(rate)));
        }
    }

    // ---- S2C ----

    @Test
    void sessionConfig() {
        var p = new SessionConfigS2CPayload(3, true, 8, 42, 300, 4, 128, 16, false, 8_388_608L);
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
        assertArrayEquals(expected, encode(SessionConfigS2CPayload.CODEC, p));
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
        assertArrayEquals(expected, encode(BatchResponseS2CPayload.CODEC,
                new BatchResponseS2CPayload(types, ids, 4)));
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
        record Case(ResourceKey<Level> key, int ordinal) {}
        for (Case c : new Case[]{new Case(Level.OVERWORLD, 0), new Case(Level.NETHER, 1), new Case(Level.END, 2)}) {
            byte[] expected = ref(b -> {
                b.writeVarInt(300);
                b.writeInt(-5);
                b.writeInt(Integer.MAX_VALUE);
                b.writeVarInt(c.ordinal());
                b.writeLong(-1L);
                b.writeByteArray(sections);
            });
            assertArrayEquals(expected, encode(VoxelColumnS2CPayload.CODEC,
                    new VoxelColumnS2CPayload(300, -5, Integer.MAX_VALUE, c.key(), -1L, sections)),
                    "voxelColumn dim ordinal " + c.ordinal());
        }
    }

    @Test
    void voxelColumnCustomDimension() {
        byte[] sections = {1, 2, 3};
        var custom = ResourceKey.create(Registries.DIMENSION, Identifier.parse("lsstest:custom"));
        byte[] expected = ref(b -> {
            b.writeVarInt(7);
            b.writeInt(0);
            b.writeInt(0);
            b.writeVarInt(LSSConstants.DIM_CUSTOM);
            b.writeUtf("lsstest:custom");
            b.writeLong(42L);
            b.writeByteArray(sections);
        });
        assertArrayEquals(expected, encode(VoxelColumnS2CPayload.CODEC,
                new VoxelColumnS2CPayload(7, 0, 0, custom, 42L, sections)));
    }
}
