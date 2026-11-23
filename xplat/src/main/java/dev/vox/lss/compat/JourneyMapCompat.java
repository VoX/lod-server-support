package dev.vox.lss.compat;

/** Loader-neutral JourneyMap facade: {@link ModCompat} reaches JourneyMap code only through
 *  here, and only after {@code isModLoaded("journeymap")} (map-ingestion plan §3.1). */
final class JourneyMapCompat {
    private JourneyMapCompat() {}

    static boolean init() { return JourneyMapSession.init(); }
    static void clientTick() { JourneyMapSession.clientTick(); }
    static void onDisconnect() { JourneyMapSession.onDisconnect(); }
    static void retireClientAcquisition() { JourneyMapSession.retireClientAcquisition(); }
    static boolean isSupported() { return JourneyMapSession.isSupported(); }
    static boolean isArmed() { return JourneyMapSession.isArmed(); }
    static boolean isWriting() { return JourneyMapSession.isWriting(); }
    static dev.vox.lss.api.VoxelColumnConsumer mapConsumer() { return JourneyMapSession.mapConsumer(); }
    static String diagLine() { return JourneyMapSession.diagLine(); }
    static java.util.Map<String, Object> metrics() { return JourneyMapSession.metrics(); }
    static ModCompat.JourneyMapStatus cachedStatus(boolean discoveryComplete) {
        return JourneyMapSession.cachedStatus(discoveryComplete);
    }
}
