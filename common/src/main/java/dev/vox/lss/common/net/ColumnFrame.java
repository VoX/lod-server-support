package dev.vox.lss.common.net;

import dev.vox.lss.common.LSSConstants;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Recognises an encoded column packet whose section bytes are already compressed.
 *
 * <p>The packet as Minecraft's encoder writes it: one packet id (a VarInt), the channel
 * identifier (a length byte and {@code lss:voxel_column}), then the current column layout:
 * chunk x and z, the dimension string, the timestamp, the source byte, the codec byte and
 * the length-prefixed section bytes. Only a packet that fits that layout exactly, with the
 * zstd codec and a zstd frame as its section bytes, is reported. Older layouts carry no
 * codec byte and always carry raw bytes, which Minecraft's own compression should keep
 * handling.
 */
public final class ColumnFrame {
    private ColumnFrame() {}

    private static final byte[] CHANNEL =
            LSSConstants.CHANNEL_VOXEL_COLUMN.getBytes(StandardCharsets.US_ASCII);

    /**
     * Whether the remaining bytes of {@code packet} are a column packet with zstd section
     * bytes. Reads only; the buffer's position is not moved.
     */
    public static boolean isCompressedColumn(ByteBuffer packet) {
        final int start = packet.position();
        final int end = packet.limit();
        int p = start;
        // The packet id: a one-byte VarInt on every protocol version that has this channel.
        if (end - p < 2 + CHANNEL.length || packet.get(p) < 0) return false;
        p++;
        if (packet.get(p) != CHANNEL.length) return false;
        p++;
        for (int i = 0; i < CHANNEL.length; i++) {
            if (packet.get(p + i) != CHANNEL[i]) return false;
        }
        p += CHANNEL.length;
        p += 8; // chunk x, chunk z
        long dimension = readVarInt(packet, p, end);
        if (dimension < 0) return false;
        int dimensionBytes = (int) dimension;
        if (dimensionBytes > LSSConstants.MAX_DIMENSION_STRING_LENGTH * 3) return false;
        p += (int) (dimension >>> 32) + dimensionBytes;
        p += 8; // timestamp
        p += 1; // source
        if (p >= end || p < start) return false;
        if (packet.get(p) != LSSConstants.COLUMN_CODEC_ZSTD) return false;
        p++;
        long sections = readVarInt(packet, p, end);
        if (sections < 0) return false;
        p += (int) (sections >>> 32);
        if ((int) sections != end - p || end - p < 4) return false;
        // The section bytes are a standard zstd frame, so they start with its magic
        // number. An older layout has other bytes where the codec byte would be; this is
        // what keeps one of them from being read as a compressed column by coincidence.
        return packet.get(p) == (byte) 0x28 && packet.get(p + 1) == (byte) 0xB5
                && packet.get(p + 2) == (byte) 0x2F && packet.get(p + 3) == (byte) 0xFD;
    }

    /** The VarInt at {@code p} as {@code (bytes read << 32) | value}, or -1 when it is
     *  malformed, negative or runs past {@code end}. */
    private static long readVarInt(ByteBuffer buffer, int p, int end) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (p + i >= end || p + i < 0) return -1L;
            byte b = buffer.get(p + i);
            value |= (b & 0x7F) << (7 * i);
            if (b >= 0) {
                return value < 0 ? -1L : ((long) (i + 1) << 32) | value;
            }
        }
        return -1L;
    }
}
