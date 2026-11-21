package dev.vox.lss.common.processing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;

/**
 * Holds LOD-triggered generation while the world's volume is nearly full, so that LSS is
 * never what fills a disk: generated chunks become region files (and store rows), and a full
 * volume first breaks Minecraft's own saves.
 *
 * <p>The hold starts below {@link #HOLD_BELOW_BYTES} and ends at {@link #RELEASE_AT_BYTES};
 * the gap keeps a server that hovers at the floor from flapping, because every change is
 * told to the clients. A reading that fails changes nothing: an unknown disk is not a full
 * one. Owned by the processing thread; {@link #poll} is cheap between readings.
 */
public final class DiskSpaceHold {
    /** The store backfill's floor: room for the server to keep saving after LSS stops. */
    public static final long HOLD_BELOW_BYTES = 2L << 30;
    public static final long RELEASE_AT_BYTES = 3L << 30;
    static final long INTERVAL_NANOS = 5_000_000_000L;

    private final LongSupplier usableBytes;
    private final long intervalNanos;
    private long nextReadingNanos;
    private boolean first = true;
    private boolean held;
    private long lastReading = -1L;

    /** @param usableBytes free bytes on the volume, or a negative number when unknown */
    public DiskSpaceHold(LongSupplier usableBytes) {
        this(usableBytes, INTERVAL_NANOS);
    }

    /** With the time between readings given (tests read every time). */
    public DiskSpaceHold(LongSupplier usableBytes, long intervalNanos) {
        this.usableBytes = usableBytes;
        this.intervalNanos = intervalNanos;
    }

    /**
     * Soak and rig seam: {@code -Dlss.soak.freeDiskFile=<file>} makes every hold read the
     * free space, in bytes, from that file, so a run can make the disk "fill" and "empty"
     * without filling one. A file that is missing or unreadable means the real reading.
     */
    private static final String FREE_DISK_FILE = System.getProperty("lss.soak.freeDiskFile");

    private static long soakReading() {
        if (FREE_DISK_FILE == null) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(Files.readString(Path.of(FREE_DISK_FILE)).trim());
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    /** A hold that reads the volume {@code directory} is on; null never holds. */
    public static DiskSpaceHold forDirectory(Path directory) {
        if (directory == null) {
            return new DiskSpaceHold(() -> -1L);
        }
        return new DiskSpaceHold(() -> {
            long soak = soakReading();
            if (soak != Long.MIN_VALUE) {
                return soak;
            }
            try {
                Path existing = directory;
                while (existing != null && !Files.exists(existing)) {
                    existing = existing.getParent();
                }
                return existing == null ? -1L : Files.getFileStore(existing).getUsableSpace();
            } catch (Exception | LinkageError e) {
                return -1L;
            }
        });
    }

    /**
     * Takes a reading if one is due. Returns true when the hold started or ended with it.
     */
    public boolean poll(long nowNanos) {
        if (!this.first && nowNanos - this.nextReadingNanos < 0) {
            return false;
        }
        this.first = false;
        this.nextReadingNanos = nowNanos + this.intervalNanos;
        long usable;
        try {
            usable = this.usableBytes.getAsLong();
        } catch (RuntimeException e) {
            usable = -1L;
        }
        this.lastReading = usable;
        if (usable < 0) {
            return false;
        }
        boolean next = this.held ? usable < RELEASE_AT_BYTES : usable < HOLD_BELOW_BYTES;
        if (next == this.held) {
            return false;
        }
        this.held = next;
        return true;
    }

    public boolean held() {
        return this.held;
    }

    /** The last reading in bytes, negative when it failed or none was taken yet. */
    public long lastReadingBytes() {
        return this.lastReading;
    }
}
