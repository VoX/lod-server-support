package dev.vox.lss.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin SLF4J wrapper for consistent mod-wide logging. The logger category tracks
 * {@link Brand#shortName()} ("LSS" by default, "VSS" under the Voxy Server Side repackage),
 * re-resolved whenever the brand changes so it is correct no matter when the platform
 * entrypoint sets branding relative to the first log call. SLF4J caches loggers by name, so
 * the re-resolve is a cheap map lookup.
 */
public final class LSSLogger {
    private static volatile Logger cached = LoggerFactory.getLogger("LSS");

    private LSSLogger() {}

    private static Logger log() {
        Logger l = cached;
        String brand = Brand.shortName();
        if (!l.getName().equals(brand)) {
            l = LoggerFactory.getLogger(brand);
            cached = l;
        }
        return l;
    }

    public static boolean isDebugEnabled() {
        return log().isDebugEnabled();
    }

    public static void debug(String msg) {
        log().debug(msg);
    }

    public static void debug(String msg, Throwable t) {
        log().debug(msg, t);
    }

    public static void info(String msg) {
        log().info(msg);
    }

    public static void info(String msg, Throwable t) {
        log().info(msg, t);
    }

    public static void warn(String msg) {
        log().warn(msg);
    }

    public static void warn(String msg, Throwable t) {
        log().warn(msg, t);
    }

    public static void error(String msg) {
        log().error(msg);
    }

    public static void error(String msg, Throwable t) {
        log().error(msg, t);
    }

}
