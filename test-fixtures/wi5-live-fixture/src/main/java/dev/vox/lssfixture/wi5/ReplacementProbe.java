package dev.vox.lssfixture.wi5;

import dev.vox.lss.networking.client.ClientNetGlue;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in external race fixture: delays one real prepared tile, never fabricates a body. */
public final class ReplacementProbe {
    private static final AtomicBoolean armed = new AtomicBoolean();
    private static final CountDownLatch release = new CountDownLatch(1);
    private static volatile Object heldOrigin, heldTile, acquisition, session;
    private static volatile boolean held, disconnected, released, complete, failed, nativePendingAtRetire;
    private static boolean initialConnectRequested, replacementConnectRequested;
    private static Object oldLevel, oldConnection, oldDimension, oldManager;
    private static long oldGeneration, started;
    private ReplacementProbe() {}
    private static Object read(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static void log(String event, String detail) {
        LoggerFactory.getLogger("LSS-WI5-Replacement").info("[WI5-REPLACEMENT] {} {}", event, detail);
    }
    private static void require(boolean value, String detail) {
        if (!value) throw new IllegalStateException(detail);
    }
    private static void fail(Throwable error) {
        failed = true;
        release.countDown();
        log("FAIL", error.getClass().getSimpleName() + ": " + error.getMessage());
    }
    public static void tick() {
        if (System.getProperty("lss.rig.runId", "").isBlank() || failed || complete) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!initialConnectRequested && mc.level == null
                    && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen
                    && !System.getProperty("lss.rig.initialEndpoint", "").isBlank()) {
                initialConnectRequested = true;
                connect(mc, "lss.rig.initialEndpoint");
                return;
            }
            if (!armed.get()) {
                if (!Files.isRegularFile(mc.gameDirectory.toPath().resolve("lss-wi5-arm-replacement"))) return;
                if (mc.level == null || mc.getConnection() == null || ClientNetGlue.getRequestManager() == null
                        || !ClientNetGlue.hasReceivedSessionConfig()) return;
                Field instance = Class.forName("dev.vox.lss.compat.XaeroSession").getDeclaredField("instance");
                instance.setAccessible(true); session = instance.get(null);
                if (session == null) return;
                Object rebuilds = read(session, "rebuilds");
                if (((Number) read(rebuilds, "pendingUpdatesGauge")).intValue() <= 0) return;
                oldLevel = mc.level; oldDimension = mc.level.dimension(); oldConnection = mc.getConnection();
                oldManager = ClientNetGlue.getRequestManager(); acquisition = read(session, "acquisition");
                oldGeneration = ((AtomicLong) read(acquisition, "acquisitionGeneration")).get();
                started = System.nanoTime();
                armed.set(true);
                log("ARMED", "realPendingRebuilds=true");
            }
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(120)) throw new IllegalStateException("replacement deadline");
            if (!held) return;
            if (mc.level == null && !disconnected) {
                // Native retirement is finished at a later client tick, not at disconnect entry.
                Object rebuilds = read(session, "rebuilds");
                if (((Number) read(rebuilds, "pendingUpdatesGauge")).intValue() != 0
                        || read(session, "lastWorldId") != null) return;
                require(((AtomicLong) read(acquisition, "acquisitionGeneration")).get() != oldGeneration,
                        "disconnect retained acquisition generation");
                require(nativePendingAtRetire, "disconnect did not retire real pending native work");
                disconnected = true;
                log("NATIVE_RETIRED", "pendingRebuilds=0 nativeWorldCleared=true generationChanged=true");
            }
            if (disconnected && !replacementConnectRequested && mc.level == null
                    && !System.getProperty("lss.rig.replacementEndpoint", "").isBlank()) {
                replacementConnectRequested = true;
                connect(mc, "lss.rig.replacementEndpoint");
                return;
            }
            if (disconnected && !released && mc.level != null && mc.level != oldLevel
                    && mc.getConnection() != null && mc.getConnection() != oldConnection
                    && ClientNetGlue.hasReceivedSessionConfig() && ClientNetGlue.getRequestManager() != null) {
                require(mc.level.dimension().equals(oldDimension), "replacement must preserve dimension key");
                require(ClientNetGlue.getRequestManager() != oldManager, "replacement reused manager");
                released = true;
                log("REPLACEMENT_READY", "newNativeWorld=true newConnection=true newManager=true sameDimension=true");
                release.countDown();
            }
        } catch (Throwable error) { fail(error); }
    }
    private static void connect(Minecraft mc, String property) {
        String endpoint = System.getProperty(property, "");
        require(endpoint.matches("(?:\\[::1\\]|127\\.0\\.0\\.1):[0-9]{1,5}"), "explicit loopback endpoint required");
        var address = net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(endpoint);
        require(address.getPort() > 0 && address.getPort() <= 65535, "invalid loopback port");
        var data = new net.minecraft.client.multiplayer.ServerData("LSS disposable fixture", endpoint,
                net.minecraft.client.multiplayer.ServerData.Type.OTHER);
        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                new net.minecraft.client.gui.screens.TitleScreen(), mc, address, data, false, null);
        log("CONNECT_REQUESTED", "nativeRoute=true loopback=true");
    }
    public static void beforeRetire(Object owner) {
        if (!held || failed || disconnected || nativePendingAtRetire || owner != session) return;
        try {
            nativePendingAtRetire = ((Number) read(read(owner, "rebuilds"), "pendingUpdatesGauge")).intValue() > 0;
            require(nativePendingAtRetire, "missing pending native rebuild at actual retirement");
            log("NATIVE_RETIRE_PREMISE", "pendingRebuildsPositive=true realCallbackHeld=true");
        } catch (Throwable error) { fail(error); }
    }
    public static void beforeOffer(Object queue, Object tile, Object origin) {
        if (!armed.get() || held || failed || queue != acquisition) return;
        synchronized (ReplacementProbe.class) {
            if (held || failed) return;
            heldOrigin = origin; heldTile = tile; held = true;
        }
        try {
            require(read(origin, "handle") != null, "real delivery acceptance handle missing");
            require(!((AtomicBoolean) read(origin, "closed")).get(), "real receipt already closed");
            log("REAL_CALLBACK_HELD", "preparedTile=true originOpen=true");
            require(release.await(120, TimeUnit.SECONDS), "held callback deadline");
            require(released && disconnected && !failed, "release without replacement");
        } catch (Throwable error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            fail(error);
        }
    }
    public static void afterOffer(Object queue, Object tile, Object origin) {
        if (origin != heldOrigin || tile != heldTile || failed) return;
        try {
            require(released && disconnected, "old callback completed before replacement");
            require(((AtomicBoolean) read(origin, "closed")).get(), "old receipt not released");
            synchronized (read(queue, "queueLock")) {
                for (Object entry : ((Map<?, ?>) read(queue, "queue")).values()) {
                    require(read(entry, "tile") != heldTile, "old tile entered replacement queue");
                }
            }
            complete = true;
            log("PASS_REPLACEMENT", "realOldCallbackReturned=true oldReceiptClosed=true oldTileAbsent=true nativeWorldRetired=true");
        } catch (Throwable error) { fail(error); }
    }
}
