package dev.vox.lss.networking.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SectionSerializer.SectionSink} writes long arrays in one block. The bytes must be
 * exactly what a plain {@link FriendlyByteBuf} writes: they are what clients receive and
 * what the change detector hashes.
 */
class SectionSinkTest {

    private static long[] longs(int n, long seed) {
        var random = new Random(seed);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = random.nextLong();
        return out;
    }

    /** Whatever the mix of calls, the sink and a plain buffer hold the same bytes. */
    @Test
    void theSinkWritesTheSameBytesAsAPlainBuffer() {
        for (int initial : new int[]{0, 1, 16, 64 * 1024}) {
            ByteBuf sinkHeap = Unpooled.buffer(initial);
            var sink = new SectionSerializer.SectionSink(sinkHeap);
            var plain = new FriendlyByteBuf(Unpooled.buffer());
            for (FriendlyByteBuf buf : new FriendlyByteBuf[]{sink, plain}) {
                buf.writeVarInt(7);
                buf.writeByte(-4);
                buf.writeShort(4096);
                buf.writeFixedSizeLongArray(longs(342, 1));
                buf.writeBoolean(true);
                buf.writeBytes(new byte[2048]);
                buf.writeLongArray(longs(256, 2));
                buf.writeFixedSizeLongArray(new long[0]);
                buf.writeLongArray(new long[0]);
                buf.writeLong(0x0102030405060708L);
                buf.writeFixedSizeLongArray(longs(1, 3));
                buf.writeFixedSizeLongArray(new long[]{Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE});
                buf.writeVarInt(300);
            }
            assertEquals(plain.readableBytes(), sink.readableBytes(), "initial capacity " + initial);
            assertArrayEquals(ByteBufUtil.getBytes(plain), ByteBufUtil.getBytes(sink), "initial capacity " + initial);
            assertEquals(sinkHeap.writerIndex(), sink.writerIndex(), "the sink and its buffer share one writer index");
        }
    }

    /** Big-endian, like {@code ByteBuf.writeLong}: the first byte written is the top byte. */
    @Test
    void longsAreBigEndian() {
        var sink = new SectionSerializer.SectionSink(Unpooled.buffer(4));
        sink.writeFixedSizeLongArray(new long[]{0x1122334455667788L});
        assertArrayEquals(new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88}, ByteBufUtil.getBytes(sink));
    }

    /** A cleared buffer is reused from its start, as the per-thread scratch does. */
    @Test
    void aClearedSinkStartsOver() {
        ByteBuf heap = Unpooled.buffer(8);
        var sink = new SectionSerializer.SectionSink(heap);
        sink.writeFixedSizeLongArray(longs(500, 9));
        heap.clear();
        sink.writeFixedSizeLongArray(new long[]{5L});
        assertEquals(8, sink.readableBytes());
        assertEquals(5L, sink.readLong());
    }

    /** A buffer without a backing array still gets the right bytes, one long at a time. */
    @Test
    void aDirectBufferFallsBackToSingleWrites() {
        ByteBuf direct = Unpooled.directBuffer(16);
        try {
            assertFalse(direct.hasArray());
            var sink = new SectionSerializer.SectionSink(direct);
            var plain = new FriendlyByteBuf(Unpooled.buffer());
            long[] data = longs(100, 4);
            sink.writeLongArray(data);
            plain.writeLongArray(data);
            assertArrayEquals(ByteBufUtil.getBytes(plain), ByteBufUtil.getBytes(sink));
        } finally {
            direct.release();
        }
    }

    @Test
    void theNonZeroScanFindsABitAnywhere() {
        for (int length : new int[]{0, 1, 7, 8, 9, 15, 16, 2047, 2048}) {
            byte[] data = new byte[length];
            assertFalse(SectionSerializer.hasNonZero(data), "zeros of length " + length);
            for (int i = 0; i < length; i++) {
                data[i] = (byte) (i % 2 == 0 ? 1 : 0x80);
                assertTrue(SectionSerializer.hasNonZero(data), "length " + length + " byte " + i);
                data[i] = 0;
            }
        }
    }
}
