package dev.vox.lss.common.net;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnFrameTest {

    private enum Layout { CURRENT, V18, V16 }

    private static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void utf(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        varInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    /** The packet as the vanilla encoder writes it (the Fabric test checks this builder
     *  against the real codec). */
    static byte[] packet(int packetId, String channel, String dimension, Layout layout,
                         byte codec, byte[] sections) {
        var out = new ByteArrayOutputStream();
        varInt(out, packetId);
        utf(out, channel);
        out.writeBytes(new byte[]{0, 0, 0, 12, -1, -1, -1, -7}); // chunk x 12, z -7
        utf(out, dimension);
        out.writeBytes(new byte[]{0, 0, 0, 0, 0x69, 0x1F, 0x4B, 0x11}); // timestamp
        if (layout != Layout.V16) out.write(2); // source
        if (layout == Layout.CURRENT) out.write(codec);
        varInt(out, sections.length);
        out.writeBytes(sections);
        return out.toByteArray();
    }

    /** Section bytes that begin like a zstd frame, of the given length. */
    private static byte[] frame(int length) {
        byte[] bytes = new byte[length];
        if (length >= 4) {
            bytes[0] = 0x28;
            bytes[1] = (byte) 0xB5;
            bytes[2] = 0x2F;
            bytes[3] = (byte) 0xFD;
        }
        return bytes;
    }

    private static boolean recognised(byte[] packet) {
        return ColumnFrame.isCompressedColumn(ByteBuffer.wrap(packet));
    }

    private static final String CHANNEL = LSSConstants.CHANNEL_VOXEL_COLUMN;
    private static final byte ZSTD = LSSConstants.COLUMN_CODEC_ZSTD;
    private static final byte RAW = LSSConstants.COLUMN_CODEC_RAW;

    @Test
    void aCompressedColumnIsRecognised() {
        for (int size : new int[]{4, 100, 127, 128, 5868, 16_383, 16_384, 200_000}) {
            assertTrue(recognised(packet(0x18, CHANNEL, "minecraft:overworld", Layout.CURRENT, ZSTD, frame(size))),
                    "sections of " + size);
        }
        assertTrue(recognised(packet(0x18, CHANNEL, "some_mod:a_long_dimension_name/" + "x".repeat(200),
                Layout.CURRENT, ZSTD, frame(700))), "a dimension name with a two-byte length");
        assertTrue(recognised(packet(0x7F, CHANNEL, "minecraft:the_end", Layout.CURRENT, ZSTD, frame(700))),
                "the packet id's value does not matter");
    }

    /** The real thing: a frame made by the compressor the server uses. */
    @Test
    void aRealZstdFrameIsRecognised() {
        byte[] raw = new byte[35_000];
        new java.util.Random(5).nextBytes(raw);
        java.util.Arrays.fill(raw, 2000, 30_000, (byte) 7);
        byte[] compressed = dev.vox.lss.common.store.StoreCodec.zstdOrNull().compress(raw);
        assertTrue(recognised(packet(0x18, CHANNEL, "minecraft:overworld", Layout.CURRENT, ZSTD, compressed)));
    }

    @Test
    void sectionBytesThatAreNotAZstdFrameAreLeftToMinecraft() {
        assertFalse(recognised(packet(0x18, CHANNEL, "minecraft:overworld", Layout.CURRENT, ZSTD, new byte[700])));
        assertFalse(recognised(packet(0x18, CHANNEL, "minecraft:overworld", Layout.CURRENT, ZSTD, new byte[]{0x28, (byte) 0xB5, 0x2F})));
        assertFalse(recognised(packet(0x18, CHANNEL, "minecraft:overworld", Layout.CURRENT, ZSTD, new byte[0])));
    }

    @Test
    void aRawColumnIsLeftToMinecraft() {
        assertFalse(recognised(packet(0x18, CHANNEL, "minecraft:overworld", Layout.CURRENT, RAW, new byte[35_000])));
        assertFalse(recognised(packet(0x18, CHANNEL, "minecraft:overworld", Layout.CURRENT, RAW, frame(35_000))),
                "the codec byte decides, not the content");
    }

    @Test
    void anUnknownCodecIsLeftToMinecraft() {
        assertFalse(recognised(packet(0x18, CHANNEL, "minecraft:overworld", Layout.CURRENT, (byte) 2, frame(700))));
    }

    /**
     * The two older layouts carry no codec byte, so some other byte sits where it would
     * be. Bodies made of the byte that reads as "zstd" there and as a length of one, of
     * every size, and bodies that start like a real section array: none is recognised.
     * (Without the magic-number check a four-byte body of ones in the oldest layout was.)
     */
    @Test
    void olderLayoutsAreNeverRecognised() {
        for (Layout layout : new Layout[]{Layout.V18, Layout.V16}) {
            for (int size = 0; size < 3000; size++) {
                byte[] ones = new byte[size];
                java.util.Arrays.fill(ones, (byte) 1);
                assertFalse(recognised(packet(0x18, CHANNEL, "minecraft:overworld", layout, RAW, ones)),
                        layout + " with " + size + " ones");
                byte[] sectionArray = new byte[size];
                if (size > 0) sectionArray[0] = 1; // one section
                if (size > 1) sectionArray[1] = (byte) Math.max(0, size - 3); // its y
                assertFalse(recognised(packet(0x18, CHANNEL, "minecraft:overworld", layout, RAW, sectionArray)),
                        layout + " with a section array of " + size);
                assertFalse(recognised(packet(0x18, CHANNEL, "minecraft:overworld", layout, RAW, frame(size))),
                        layout + " with a body that starts with the zstd magic, " + size);
            }
        }
    }

    @Test
    void otherPacketsAreNotRecognised() {
        assertEquals(CHANNEL.length(), "lss:dirty_column".length());
        assertFalse(recognised(packet(0x18, "lss:dirty_column", "minecraft:overworld", Layout.CURRENT, ZSTD, frame(700))),
                "another channel of the same length");
        assertFalse(recognised(packet(0x18, "minecraft:brand", "minecraft:overworld", Layout.CURRENT, ZSTD, frame(700))));
        assertFalse(recognised(packet(0x80, CHANNEL, "minecraft:overworld", Layout.CURRENT, ZSTD, frame(700))),
                "a two-byte packet id");
        assertFalse(recognised(new byte[0]));
        assertFalse(recognised(new byte[]{0x18}));
    }

    @Test
    void everyTruncationIsRefusedWithoutAnError() {
        byte[] whole = packet(0x18, CHANNEL, "minecraft:overworld", Layout.CURRENT, ZSTD, frame(300));
        for (int length = 0; length < whole.length; length++) {
            assertFalse(recognised(java.util.Arrays.copyOf(whole, length)), "cut at " + length);
        }
        byte[] longer = java.util.Arrays.copyOf(whole, whole.length + 1);
        assertFalse(recognised(longer), "a byte after the section array");
        assertTrue(recognised(whole));
    }

    @Test
    void theBuffersPositionIsNotMovedAndItsOffsetIsHonoured() {
        byte[] whole = packet(0x18, CHANNEL, "minecraft:overworld", Layout.CURRENT, ZSTD, frame(300));
        byte[] padded = new byte[whole.length + 10];
        System.arraycopy(whole, 0, padded, 6, whole.length);
        ByteBuffer buffer = ByteBuffer.wrap(padded);
        buffer.position(6).limit(6 + whole.length);
        assertTrue(ColumnFrame.isCompressedColumn(buffer));
        assertEquals(6, buffer.position());
        assertTrue(ColumnFrame.isCompressedColumn(buffer.slice()), "a slice with an array offset");
    }

    /** A hostile or broken length field must not send the reader outside the packet. */
    @Test
    void absurdLengthFieldsAreRefused() {
        byte[] whole = packet(0x18, CHANNEL, "minecraft:overworld", Layout.CURRENT, ZSTD, frame(300));
        int dimensionLength = 2 + CHANNEL.length() + 8;
        byte[] hostile = whole.clone();
        hostile[dimensionLength] = (byte) 0xFF;
        hostile[dimensionLength + 1] = (byte) 0xFF;
        hostile[dimensionLength + 2] = (byte) 0xFF;
        hostile[dimensionLength + 3] = (byte) 0xFF;
        hostile[dimensionLength + 4] = (byte) 0x07;
        assertFalse(recognised(hostile), "a dimension length of two billion");
        hostile[dimensionLength + 4] = (byte) 0x0F;
        assertFalse(recognised(hostile), "a negative dimension length");
    }
}
