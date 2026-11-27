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
    /**
     * The floor: 2 GiB, the store backfill's own, which leaves the server room to keep
     * saving after LSS stops. {@code -Dlss.lowDiskHoldMiB=<n>} moves it and 0 turns the hold
     * off. A JVM flag and not a setting: a host that lives under the default must be able to
     * get out of the way of an upgrade, and nobody else should have to think about it.
     */
    public static final long HOLD_BELOW_BYTES = floorBytes(System.getProperty("lss.lowDiskHoldMiB"));
    /** Half as much again: the gap that keeps a server at the floor from flapping. */
    public static final long RELEASE_AT_BYTES = HOLD_BELOW_BYTES + HOLD_BELOW_BYTES / 2;
    static final long INTERVAL_NANOS = 5_000_000_000L;

    /** The floor in bytes for a configured number of MiB; anything unreadable is the default. */
    static long floorBytes(String configuredMib) {
        long mib = 2048;
        if (configuredMib != null) {
            try {
                mib = Long.parseLong(configuredMib.trim());
            } catch (NumberFormatException e) {
                mib = 2048;
            }
        }
        return Math.max(0L, Math.min(mib, 1L << 20)) << 20; // at most 1 TiB
    }

    private final LongSupplier usableBytes;
    private final long intervalNanos;
    private final long holdBelow;
    private final long releaseAt;
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
        this(usableBytes, intervalNanos, HOLD_BELOW_BYTES, RELEASE_AT_BYTES);
    }

    /** With the two marks given. A floor of 0 never holds. */
    DiskSpaceHold(LongSupplier usableBytes, long intervalNanos, long holdBelow, long releaseAt) {
        this.usableBytes = usableBytes;
        this.intervalNanos = intervalNanos;
        this.holdBelow = holdBelow;
        this.releaseAt = releaseAt;
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
        // Zero is a reading: a server restarted by its panel after the disk ran full reads
        // exactly that, and is the case the hold is for. A volume that reports no free
        // space while it takes writes (some network mounts) is held too; the log line says
        // how to move the floor.
        boolean next = this.held ? usable < this.releaseAt : usable < this.holdBelow;
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
