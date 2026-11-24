package dev.vox.lss.common.net;

import java.nio.ByteBuffer;
import java.util.zip.Adler32;

/**
 * Writes bytes as a zlib stream that stores them instead of compressing them (RFC 1950
 * around RFC 1951 "stored" blocks). Every inflater reads it; producing it costs a copy and
 * a checksum.
 *
 * <p>Used for column packets whose payload is already a zstd frame: Minecraft's packet
 * compression would deflate each one again for no gain (measured: 0.054 ms a column, and
 * the result is larger). The packet still goes out as an ordinary compressed packet with
 * its true uncompressed length, which is what proxies and other decoders check.
 */
public final class StoredDeflate {
    private StoredDeflate() {}

    /** The most a stored block can hold: its length field is 16 bits. */
    static final int MAX_BLOCK = 0xFFFF;
    private static final int HEADER = 2;
    private static final int BLOCK_HEADER = 5;
    private static final int TRAILER = 4;

    /** The size of the stream for {@code length} input bytes. */
    public static int streamLength(int length) {
        if (length < 0) throw new IllegalArgumentException("negative length");
        int blocks = length == 0 ? 1 : (length + MAX_BLOCK - 1) / MAX_BLOCK;
        return Math.addExact(HEADER + TRAILER + length, Math.multiplyExact(blocks, BLOCK_HEADER));
    }

    /**
     * Writes the stream for the remaining bytes of {@code source} at {@code target}'s
     * position. Both positions advance; {@code target} needs {@link #streamLength} bytes.
     */
    public static void write(ByteBuffer source, ByteBuffer target) {
        int length = source.remaining();
        if (target.remaining() < streamLength(length)) {
            throw new IllegalArgumentException("target too small: " + target.remaining()
                    + " for " + streamLength(length));
        }
        var adler = new Adler32();
        adler.update(source.duplicate());

        // CMF 0x78: deflate, 32 KB window. FLG 0x01: no dictionary, fastest level, and the
        // check bits that make the pair a multiple of 31.
        target.put((byte) 0x78).put((byte) 0x01);
        int remaining = length;
        do {
            int block = Math.min(remaining, MAX_BLOCK);
            boolean last = block == remaining;
            target.put((byte) (last ? 1 : 0)); // BFINAL, BTYPE 00 (stored)
            target.put((byte) block).put((byte) (block >>> 8));
            target.put((byte) ~block).put((byte) (~block >>> 8));
            if (block > 0) {
                ByteBuffer slice = source.slice(source.position(), block);
                target.put(slice);
                source.position(source.position() + block);
            }
            remaining -= block;
        } while (remaining > 0);
        int sum = (int) adler.getValue();
        target.put((byte) (sum >>> 24)).put((byte) (sum >>> 16)).put((byte) (sum >>> 8)).put((byte) sum);
    }
}
