package dev.vox.lss.compat;

import java.util.concurrent.atomic.AtomicLong;

/**
 * What a report needs to tell "the terrain reached Voxy without sky light" from "Voxy drew
 * it dark": counted where LSS hands terrain to Voxy, shown by {@code /lss diag} and the
 * diagnostic export. Black terrain was reported three times with three causes (#382, #383,
 * #429) and none of the reports could show which side it was on.
 *
 * <p>Reset when the client disconnects.
 */
public final class VoxyLightStats {
    private VoxyLightStats() {}

    /** Sections of server (or Distant Horizons) columns handed to Voxy with no sky light,
     *  at or above the column's highest block, in a dimension that has sky light. */
    private static final AtomicLong DARK_SECTIONS = new AtomicLong();
    /** Border-fill chunks that were held back at least once because the sky light Voxy
     *  would read was not there yet. */
    private static final AtomicLong BORDER_HOLDS = new AtomicLong();
    /** How many are waiting right now, and since when the oldest (monotonic nanoseconds;
     *  0 = none). Written by the client thread's pump, read by the diag. */
    private static volatile int borderWaiting;
    private static volatile long borderOldestSinceNanos;

    static void addDarkSections(int n) {
        if (n > 0) DARK_SECTIONS.addAndGet(n);
    }

    static void addBorderHold() {
        BORDER_HOLDS.incrementAndGet();
    }

    static void setBorderWaiting(int waiting, long oldestSinceNanos) {
        borderWaiting = waiting;
        borderOldestSinceNanos = waiting == 0 ? 0L : oldestSinceNanos;
    }

    public static long darkSections() {
        return DARK_SECTIONS.get();
    }

    public static long borderHolds() {
        return BORDER_HOLDS.get();
    }

    public static int borderWaiting() {
        return borderWaiting;
    }

    /** How long the oldest waiting chunk has waited, in milliseconds; 0 when none waits. */
    public static long borderOldestWaitMillis(long nowNanos) {
        long since = borderOldestSinceNanos;
        return borderWaiting == 0 || since == 0L ? 0L : Math.max(0L, (nowNanos - since) / 1_000_000L);
    }

    public static void reset() {
        DARK_SECTIONS.set(0);
        BORDER_HOLDS.set(0);
        borderWaiting = 0;
        borderOldestSinceNanos = 0L;
    }

    /** The diag line, or null while every number is zero. */
    public static String diagLineOrNull(long darkSections, long borderHolds, int waiting, long oldestMillis) {
        if (darkSections <= 0 && borderHolds <= 0 && waiting <= 0) {
            return null;
        }
        var line = new StringBuilder("Voxy light: sections_without_sky=").append(darkSections)
                .append(", border_fill_held=").append(borderHolds);
        if (waiting > 0) {
            line.append(", waiting=").append(waiting).append(" (oldest ").append(oldestMillis / 1000).append(" s)");
        }
        return line.toString();
    }
}
