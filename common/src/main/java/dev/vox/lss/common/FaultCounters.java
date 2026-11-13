package dev.vox.lss.common;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Counts failures the mod deliberately survives, so they show up in diagnostics instead of
 * vanishing. A site that catches and carries on calls {@link #record}: the failure is counted
 * under the site's name and logged at most once every ten minutes per site.
 *
 * <p>Invariant: a swallowed failure is always visible in {@code diag}. Without this, a store that
 * stopped evicting or a permission plugin that started throwing looked exactly like a healthy
 * server.
 *
 * <p>Thread-safe; cheap enough for any path that already took an exception.
 */
public final class FaultCounters {

    /** Where a failure was survived. {@link #label} is what diagnostics print. */
    public enum Site {
        STORE_MAINTENANCE("store.maintenance"),
        BACKFILL_FREE_SPACE("backfill.free_space"),
        DIRTY_LISTENER("dirty.listener"),
        PERMISSION_CHECK("permission.check"),
        VANISH_CHECK("vanish.check"),
        CLIENT_CACHE_LOAD("client.cache_load");

        public final String label;

        Site(String label) {
            this.label = label;
        }
    }

    /** Ten minutes: several of these sites used to warn once ever, and a backend that fails on
     *  every call must not write a line a minute into a server log. The count is in diagnostics. */
    private static final long LOG_INTERVAL_MS = 600_000;
    private static final FaultCounters GLOBAL = new FaultCounters(
            () -> System.nanoTime() / 1_000_000, LSSLogger::warn);

    private final Map<Site, LongAdder> counts = new EnumMap<>(Site.class);
    private final Map<Site, LogThrottle> throttles = new EnumMap<>(Site.class);
    private final LongSupplier clockMs;
    private final Consumer<String> log;

    /** A registry with its own clock and log sink. Production code uses {@link #global()}. */
    public FaultCounters(LongSupplier clockMs, Consumer<String> log) {
        this.clockMs = clockMs;
        this.log = log;
        for (Site site : Site.values()) {
            this.counts.put(site, new LongAdder());
            this.throttles.put(site, new LogThrottle(LOG_INTERVAL_MS));
        }
    }

    /** The process-wide registry that diagnostics read. */
    public static FaultCounters global() {
        return GLOBAL;
    }

    /** Counts one survived failure and logs it, rate-limited per site. */
    public void record(Site site, Throwable cause) {
        record(site, () -> describe(cause));
    }

    /** Counts one survived failure described in words. */
    public void record(Site site, String what) {
        record(site, () -> what);
    }

    /** Counts one survived failure; the description is built only when a line is written. */
    public void record(Site site, java.util.function.Supplier<String> what) {
        this.counts.get(site).increment();
        long released = this.throttles.get(site).recordAndTryAcquire(this.clockMs.getAsLong());
        if (released > 0) {
            String described;
            try {
                described = what.get();
            } catch (RuntimeException | LinkageError unprintable) {
                described = "a failure that could not be described";
            }
            this.log.accept("Survived a failure in " + site.label + (released > 1 ? " (" + released + " since the last report)" : "")
                    + ": " + described + ". Total so far: " + count(site) + ".");
        }
    }

    /** A cause in words. Called inside the supplier guard above: a failing {@code toString}
     *  never escapes a caller that survives everything else. */
    private static String describe(Throwable cause) {
        return cause == null ? "no cause given" : cause.toString();
    }

    public long count(Site site) {
        return this.counts.get(site).sum();
    }

    /** {@code "Recovered errors: store.maintenance=2 permission.check=1"}, or an empty string when nothing failed. */
    public String diagLine() {
        var out = new StringBuilder();
        for (Site site : Site.values()) {
            long n = count(site);
            if (n > 0) {
                out.append(out.length() == 0 ? "Recovered errors:" : "").append(' ').append(site.label).append('=').append(n);
            }
        }
        return out.toString();
    }
}
