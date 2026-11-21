package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.net.StoredDeflate;
import dev.vox.lss.common.store.StoreCodec;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.SharedConstants;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The passthrough in front of vanilla's real compressor, and vanilla's real decompressor
 * (with validation on, as the strictest reader) on the other side.
 */
class ColumnPacketPassthroughTest {
    private static final int THRESHOLD = 256;
    private static final int CUSTOM_PAYLOAD_ID = 0x18;
    private static StoreCodec codec;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null, "zstd native unavailable on this platform");
    }

    /** The outgoing half of a server connection: prepender, compressor, and the handler. */
    private static EmbeddedChannel server(boolean withPassthrough) {
        var channel = new EmbeddedChannel();
        channel.pipeline().addLast("prepender", new Varint21LengthFieldPrepender());
        channel.pipeline().addLast(ColumnPacketPassthrough.COMPRESS, new CompressionEncoder(THRESHOLD));
        if (withPassthrough) {
            assertTrue(ColumnPacketPassthrough.installOn(channel));
        }
        return channel;
    }

    /** The incoming half of a client connection, validating like a server would. */
    private static EmbeddedChannel client() {
        var channel = new EmbeddedChannel();
        channel.pipeline().addLast("splitter", new Varint21FrameDecoder(null));
        channel.pipeline().addLast("decompress", new CompressionDecoder(THRESHOLD, true));
        return channel;
    }

    /** A column packet exactly as the packet encoder writes it: the packet id, the
     *  payload's identifier, and the payload through its real codec. */
    private static byte[] columnPacket(VoxelColumnS2CPayload payload) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(CUSTOM_PAYLOAD_ID);
            buf.writeIdentifier(VoxelColumnS2CPayload.TYPE.id());
            VoxelColumnS2CPayload.CODEC.encode(buf, payload);
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    /** Raw section bytes that deflate well, so a deflated packet is unmistakably smaller. */
    private static byte[] rawSections(int length) {
        byte[] raw = new byte[length];
        var random = new Random(length);
        for (int i = 0; i < length; i += 64) raw[i] = (byte) random.nextInt();
        return raw;
    }

    private static VoxelColumnS2CPayload compressedColumn(byte[] raw) {
        return new VoxelColumnS2CPayload(7, -8, Level.OVERWORLD, 1_763_700_000L,
                LSSConstants.COLUMN_SOURCE_GENERATION, LSSConstants.COLUMN_CODEC_ZSTD,
                codec.compress(raw), raw.length);
    }

    private static VoxelColumnS2CPayload rawColumn(byte[] raw) {
        return new VoxelColumnS2CPayload(7, -8, Level.OVERWORLD, 1_763_700_000L,
                LSSConstants.COLUMN_SOURCE_GENERATION, raw);
    }

    /** Sends one packet through the server half; returns what goes to the socket. */
    private static byte[] send(EmbeddedChannel server, byte[] packet) {
        ByteBuf in = Unpooled.wrappedBuffer(packet.clone());
        assertTrue(server.writeOutbound(in));
        assertEquals(0, in.refCnt(), "the packet buffer must be released exactly once");
        ByteBuf wire = server.readOutbound();
        assertNotNull(wire);
        assertNull(server.readOutbound(), "one packet in, one frame out");
        try {
            return ByteBufUtil.getBytes(wire);
        } finally {
            wire.release();
        }
    }

    /** What a client reads from those bytes. */
    private static byte[] receive(byte[] wire) {
        var client = client();
        assertTrue(client.writeInbound(Unpooled.wrappedBuffer(wire)));
        ByteBuf packet = client.readInbound();
        assertNotNull(packet);
        try {
            return ByteBufUtil.getBytes(packet);
        } finally {
            packet.release();
            client.finishAndReleaseAll();
        }
    }

    /** The declared uncompressed length of a wire frame (after its own length prefix). */
    private static int declaredLength(byte[] wire) {
        ByteBuf buf = Unpooled.wrappedBuffer(wire);
        VarInt.read(buf); // the frame's length
        return VarInt.read(buf);
    }

    @Test
    void aCompressedColumnIsStoredNotDeflatedAndArrivesByteForByte() {
        for (int rawLength : new int[]{4_000, 35_318, 400_000}) {
            // random bytes: zstd cannot shrink them, so the packet is as large as the raw
            // length and the 400,000 case needs more than one stored block
            byte[] raw = new byte[rawLength];
            new Random(rawLength).nextBytes(raw);
            byte[] packet = columnPacket(compressedColumn(raw));
            assertTrue(packet.length >= THRESHOLD);
            long before = ColumnPacketPassthrough.packets();

            byte[] wire = send(server(true), packet);

            assertEquals(before + 1, ColumnPacketPassthrough.packets());
            assertEquals(packet.length, declaredLength(wire),
                    "the frame declares the packet's true length (what Velocity and Krypton check)");
            int framed = VarInt.getByteSize(packet.length) + StoredDeflate.streamLength(packet.length);
            assertEquals(VarInt.getByteSize(framed) + framed, wire.length,
                    "the frame is the packet plus the framing, nothing deflated");
            assertArrayEquals(packet, receive(wire), "raw length " + rawLength);
        }
    }

    /** The same packet without the handler is a different frame that decodes to the same
     *  bytes: the handler changes the framing and nothing else. */
    @Test
    void theClientSeesTheSamePacketWithAndWithoutTheHandler() {
        byte[] packet = columnPacket(compressedColumn(rawSections(35_318)));
        byte[] with = send(server(true), packet);
        byte[] without = send(server(false), packet);
        assertFalse(java.util.Arrays.equals(with, without));
        assertArrayEquals(receive(without), receive(with));
    }

    @Test
    void aRawColumnIsStillDeflated() {
        byte[] packet = columnPacket(rawColumn(rawSections(35_318)));
        long before = ColumnPacketPassthrough.packets();
        byte[] wire = send(server(true), packet);
        assertEquals(before, ColumnPacketPassthrough.packets());
        assertTrue(wire.length < packet.length / 4, "a raw column must still be deflated: " + wire.length);
        assertArrayEquals(packet, receive(wire));
    }

    @Test
    void aColumnBelowTheThresholdTakesTheNormalPath() {
        byte[] packet = columnPacket(compressedColumn(new byte[40]));
        assertTrue(packet.length < THRESHOLD, "premise: " + packet.length);
        long before = ColumnPacketPassthrough.packets();
        byte[] wire = send(server(true), packet);
        assertEquals(before, ColumnPacketPassthrough.packets());
        assertEquals(0, declaredLength(wire), "below the threshold a packet is marked not compressed");
        assertArrayEquals(packet, receive(wire));
    }

    @Test
    void otherPacketsAreUntouched() {
        byte[] other = new byte[5_000];
        other[0] = 0x27;
        long before = ColumnPacketPassthrough.packets();
        byte[] wire = send(server(true), other);
        assertEquals(before, ColumnPacketPassthrough.packets());
        assertTrue(wire.length < 200, "zeros deflate to almost nothing");
        assertArrayEquals(other, receive(wire));
    }

    /** The threshold is read from the live compressor for every packet. */
    @Test
    void aRaisedThresholdIsHonoured() {
        var server = server(true);
        ((CompressionEncoder) server.pipeline().get(ColumnPacketPassthrough.COMPRESS)).setThreshold(1_000_000);
        byte[] packet = columnPacket(compressedColumn(rawSections(35_318)));
        long before = ColumnPacketPassthrough.packets();
        byte[] wire = send(server, packet);
        assertEquals(before, ColumnPacketPassthrough.packets());
        assertEquals(0, declaredLength(wire));
    }

    /** Compression removed after the handler was installed: nothing may be framed for a
     *  compressor that is not there. */
    @Test
    void aRemovedCompressorIsSurvived() {
        var server = server(true);
        server.pipeline().remove(ColumnPacketPassthrough.COMPRESS);
        byte[] packet = columnPacket(compressedColumn(rawSections(35_318)));
        long before = ColumnPacketPassthrough.packets();
        byte[] wire = send(server, packet);
        assertEquals(before, ColumnPacketPassthrough.packets());
        // only the length prepender is left: the frame is the packet itself
        ByteBuf buf = Unpooled.wrappedBuffer(wire);
        assertEquals(packet.length, VarInt.read(buf));
        assertArrayEquals(packet, ByteBufUtil.getBytes(buf));
    }

    /** A stack that put its own class under the name "compress" is left alone. */
    @Test
    void aForeignCompressorIsLeftAlone() {
        var channel = new EmbeddedChannel();
        channel.pipeline().addLast("prepender", new Varint21LengthFieldPrepender());
        channel.pipeline().addLast(ColumnPacketPassthrough.COMPRESS, new ChannelOutboundHandlerAdapter());
        assertFalse(ColumnPacketPassthrough.installOn(channel), "not installed beside a foreign compressor");
        assertNull(channel.pipeline().get(ColumnPacketPassthrough.NAME));

        // and if the compressor is swapped after the install, the handler steps aside
        var server = server(true);
        server.pipeline().replace(ColumnPacketPassthrough.COMPRESS, ColumnPacketPassthrough.COMPRESS,
                new ChannelOutboundHandlerAdapter());
        byte[] packet = columnPacket(compressedColumn(rawSections(35_318)));
        long before = ColumnPacketPassthrough.packets();
        byte[] wire = send(server, packet);
        assertEquals(before, ColumnPacketPassthrough.packets());
        ByteBuf buf = Unpooled.wrappedBuffer(wire);
        assertEquals(packet.length, VarInt.read(buf));
        assertArrayEquals(packet, ByteBufUtil.getBytes(buf));
    }

    @Test
    void installingTwiceLeavesOneHandlerAndNoCompressionMeansNoHandler() {
        var server = server(true);
        assertFalse(ColumnPacketPassthrough.installOn(server));
        assertEquals(1, server.pipeline().names().stream()
                .filter(ColumnPacketPassthrough.NAME::equals).count());
        assertEquals(java.util.List.of("prepender", ColumnPacketPassthrough.COMPRESS, ColumnPacketPassthrough.NAME),
                server.pipeline().names().subList(0, 3), "the handler sits just in front of the compressor");

        var plain = new EmbeddedChannel();
        plain.pipeline().addLast("prepender", new Varint21LengthFieldPrepender());
        assertFalse(ColumnPacketPassthrough.installOn(plain));
    }

    /** If the frame cannot be built, the packet still goes out, deflated as before. */
    @Test
    void aFailingFrameFallsBackToTheNormalPath() {
        var server = server(true);
        ByteBufAllocator real = UnpooledByteBufAllocator.DEFAULT;
        boolean[] fail = {true};
        ChannelHandlerContext[] seen = new ChannelHandlerContext[1];
        // an allocator that refuses the handler's one allocation, then works again
        server.config().setAllocator(new io.netty.buffer.AbstractByteBufAllocator() {
            @Override protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
                if (fail[0]) { fail[0] = false; throw new OutOfMemoryError("test: no buffer"); }
                return real.heapBuffer(initialCapacity, maxCapacity);
            }
            @Override protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
                if (fail[0]) { fail[0] = false; throw new OutOfMemoryError("test: no buffer"); }
                return real.directBuffer(initialCapacity, maxCapacity);
            }
            @Override public boolean isDirectBufferPooled() { return false; }
        });
        byte[] packet = columnPacket(compressedColumn(rawSections(35_318)));
        long packets = ColumnPacketPassthrough.packets();
        long fallbacks = ColumnPacketPassthrough.fallbacks();
        byte[] wire = send(server, packet);
        assertEquals(packets, ColumnPacketPassthrough.packets());
        assertEquals(fallbacks + 1, ColumnPacketPassthrough.fallbacks());
        assertArrayEquals(packet, receive(wire));
        assertNull(seen[0]);
    }

    /** The helper the common tests build their frames with matches the real codec. */
    @Test
    void theRecogniserAcceptsWhatTheRealCodecWrites() {
        byte[] compressed = columnPacket(compressedColumn(rawSections(35_318)));
        byte[] raw = columnPacket(rawColumn(rawSections(35_318)));
        assertTrue(dev.vox.lss.common.net.ColumnFrame.isCompressedColumn(java.nio.ByteBuffer.wrap(compressed)));
        assertFalse(dev.vox.lss.common.net.ColumnFrame.isCompressedColumn(java.nio.ByteBuffer.wrap(raw)));
        var v18 = columnPacket(rawColumn(rawSections(35_318)).asV18());
        var v16 = columnPacket(rawColumn(rawSections(35_318)).asV16());
        assertFalse(dev.vox.lss.common.net.ColumnFrame.isCompressedColumn(java.nio.ByteBuffer.wrap(v18)));
        assertFalse(dev.vox.lss.common.net.ColumnFrame.isCompressedColumn(java.nio.ByteBuffer.wrap(v16)));
    }
}
