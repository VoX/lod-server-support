package dev.vox.lss.common;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * A fast 64-bit content hash for in-memory change detection: the XXH64 algorithm with seed
 * 0, checked against its published values and against an independent implementation
 * ({@code ContentHash64Test}).
 *
 * <p>It reads eight bytes at a time, so a 35 KB column costs a few microseconds where a
 * byte-at-a-time hash costs tens. That matters because the save-time change detector
 * ({@code DirtyContentFilter}) runs on the server thread for every chunk load and save.
 *
 * <p>Not a persistent format: nothing stores these values across a restart, and the LOD
 * store keeps its own hash ({@code LodStoreService.contentHash}).
 */
public final class ContentHash64 {
    private ContentHash64() {}

    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    private static final VarHandle LONG =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    public static long hash(byte[] data) {
        return hash(data, 0, data.length);
    }

    public static long hash(byte[] data, int offset, int length) {
        java.util.Objects.checkFromIndexSize(offset, length, data.length);
        int p = offset;
        final int end = offset + length;
        long h;
        if (length >= 32) {
            long v1 = P1 + P2;
            long v2 = P2;
            long v3 = 0L;
            long v4 = -P1;
            final int limit = end - 32;
            do {
                v1 = round(v1, (long) LONG.get(data, p));
                v2 = round(v2, (long) LONG.get(data, p + 8));
                v3 = round(v3, (long) LONG.get(data, p + 16));
                v4 = round(v4, (long) LONG.get(data, p + 24));
                p += 32;
            } while (p <= limit);
            h = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h = merge(h, v1);
            h = merge(h, v2);
            h = merge(h, v3);
            h = merge(h, v4);
        } else {
            h = P5;
        }
        h += length;
        while (p + 8 <= end) {
            h ^= round(0L, (long) LONG.get(data, p));
            h = Long.rotateLeft(h, 27) * P1 + P4;
            p += 8;
        }
        if (p + 4 <= end) {
            h ^= ((int) INT.get(data, p) & 0xFFFFFFFFL) * P1;
            h = Long.rotateLeft(h, 23) * P2 + P3;
            p += 4;
        }
        while (p < end) {
            h ^= (data[p] & 0xFFL) * P5;
            h = Long.rotateLeft(h, 11) * P1;
            p++;
        }
        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h;
    }

    private static long round(long acc, long input) {
        acc += input * P2;
        return Long.rotateLeft(acc, 31) * P1;
    }

    private static long merge(long h, long v) {
        h ^= round(0L, v);
        return h * P1 + P4;
    }
}
