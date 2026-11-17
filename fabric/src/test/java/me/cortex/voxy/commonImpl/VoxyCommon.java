package me.cortex.voxy.commonImpl;

/**
 * Test stub of Voxy's VoxyCommon, mirroring the shape VoxyCompat's ingest-backlog probe
 * resolves via MethodHandles: {@code static VoxyInstance getInstance()} (null before world
 * creation / after shutdown). Control {@link #instance}, call {@link #reset()} between tests.
 */
public final class VoxyCommon {

    /** Returned by {@link #getInstance}; set null to simulate "no instance yet". */
    public static volatile VoxyInstance instance = new VoxyInstance();

    public static VoxyInstance getInstance() {
        return instance;
    }

    // ---- Reset-domain surface (v0.11.0 stage D): shape-mirrors of the statics the
    // ---- reset resolver binds (javap-verified stable across 0.2.11/0.2.18/dev).

    public static volatile Runnable shutdownBody = () -> {};
    public static volatile Runnable createBody = () -> {};

    public static void shutdownInstance() {
        shutdownBody.run();
    }

    public static void createInstance() {
        createBody.run();
    }

    /** Voxy's "a factory was installed": false on a computer whose graphics it cannot run on. */
    public static volatile boolean available = true;

    public static boolean isAvailable() {
        return available;
    }

    public static void reset() {
        available = true;
        instance = new VoxyInstance();
        shutdownBody = () -> {};
        createBody = () -> {};
    }
}
