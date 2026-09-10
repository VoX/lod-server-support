package dev.vox.lss.compat;

/** Loader-neutral facade. Native-world and acquisition lifetimes are owned by XaeroSession. */
final class XaeroMapCompat {
    private XaeroMapCompat() {}

    static boolean init() { return XaeroSession.init(); }
    static void clientTick() { XaeroSession.clientTick(); }
    static void renderFrame() { XaeroSession.renderFrame(); }
    static void onDisconnect() { XaeroSession.onDisconnect(); }
    static void retireClientAcquisition() { XaeroSession.retireClientAcquisition(); }
    static boolean isArmed() { return XaeroSession.isArmed(); }
    static String diagLine() { return XaeroSession.diagLine(); }
    static ModCompat.XaeroStatus cachedStatus(boolean discoveryComplete) {
        return XaeroSession.cachedStatus(discoveryComplete);
    }
}
