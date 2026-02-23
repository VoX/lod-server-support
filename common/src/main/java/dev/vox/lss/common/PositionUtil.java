package dev.vox.lss.common;

/**
 * Chunk coordinate packing utilities shared by both Fabric and Paper.
 * Encodes two 32-bit chunk coordinates into a single 64-bit long.
 */
public final class PositionUtil {
    private PositionUtil() {}

    public static long packPosition(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackZ(long packed) {
        return (int) packed;
    }
}
