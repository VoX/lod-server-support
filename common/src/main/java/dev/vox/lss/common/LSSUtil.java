package dev.vox.lss.common;

public final class LSSUtil {
    private LSSUtil() {}

    public static boolean isUniformArray(byte[] data) {
        if (data.length == 0) return true;
        byte first = data[0];
        for (int i = 1; i < data.length; i++) {
            if (data[i] != first) return false;
        }
        return true;
    }
}
