package dev.vox.lss.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads per-chunk timestamps from the region file (.mca) header.
 * Bytes 4096–8191 contain 1024 big-endian int32 epoch-second timestamps,
 * one per chunk, indexed by (chunkX & 31) + (chunkZ & 31) * 32.
 */
public class RegionTimestampReader {

    private static final int TIMESTAMP_TABLE_OFFSET = 4096;

    /**
     * Reads the epoch-second timestamp for a chunk from the region file header.
     *
     * @param regionDir the region directory (e.g. world/region/)
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     * @return epoch seconds, or 0 if the file doesn't exist or can't be read
     */
    public static long readTimestamp(Path regionDir, int chunkX, int chunkZ) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        Path regionFile = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");

        int index = (chunkX & 31) + (chunkZ & 31) * 32;
        long offset = TIMESTAMP_TABLE_OFFSET + (long) index * 4;

        ByteBuffer buf = ByteBuffer.allocate(4);
        try (FileChannel ch = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            if (ch.size() < offset + 4) return 0;
            int bytesRead = ch.read(buf, offset);
            if (bytesRead < 4) return 0;
            buf.flip();
            return Integer.toUnsignedLong(buf.getInt());
        } catch (IOException e) {
            return 0;
        }
    }
}
