package dev.vox.lssfixture.wi5;

import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.config.menu.ClientOptionCatalog;
import dev.vox.lss.config.menu.OptionSpec.BoolSpec;
import dev.vox.lss.networking.client.ClientNetGlue;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** External marker-controlled menu Apply driver. Never alters bridge state or cadence. */
public final class Probe {
    private static final Logger LOG = LoggerFactory.getLogger("LSS-WI5-Fixture");
    private static Path dir;
    private static int phase; // 0 idle, 1 await positive premise, 2 OFF, 3 ON negotiation, 4 complete
    private static long ticks, armedAt, offAt, written, drops, flushes, received;
    private static boolean failed, drained;
    private static Object level, connection, oldManager, bridge;
    private static String worldId, subKey;
    private Probe() {}

    private static long count(String diag, String key) {
        Matcher match = Pattern.compile("(?:^|, )" + Pattern.quote(key) + "=(\\d+)(?:,|$)").matcher(diag);
        if (!match.find()) throw new IllegalStateException("Missing diagnostic " + key);
        return Long.parseLong(match.group(1));
    }
    private static Object read(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static Object bridge() throws ReflectiveOperationException {
        Field field = Class.forName("dev.vox.lss.compat.XaeroSession").getDeclaredField("instance");
        field.setAccessible(true);
        return field.get(null);
    }
    private static String subKey(Object manager) throws ReflectiveOperationException {
        Method method = manager.getClass().getDeclaredMethod("worldSubKeySnapshot");
        method.setAccessible(true);
        return String.valueOf(method.invoke(manager));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static String snapshot() {
        return "receive=" + LSSClientConfig.CONFIG.receiveServerLods
                + " enabled=" + ClientNetGlue.isServerEnabled()
                + " sessionConfig=" + ClientNetGlue.hasReceivedSessionConfig()
                + " version=" + ClientNetGlue.getSessionVersion()
                + " manager=" + System.identityHashCode(ClientNetGlue.getRequestManager())
                + " received=" + ClientNetGlue.getColumnsReceived()
                + " decodeQueued=" + ClientNetGlue.getQueuedColumnCount()
                + " " + ModCompat.xaeroDiagLine();
    }
    private static void log(String event, String detail) throws java.io.IOException {
        String line = Instant.now() + " [WI5-FIXTURE] " + event + " tick=" + ticks + " " + detail;
        LOG.info("{}", line);
        Files.writeString(dir.resolve("lss-wi5-fixture.log"), line + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static void apply(boolean enabled) {
        // Exact menu catalog setter and selected option storage SaveHook; no direct lifecycle call.
        BoolSpec option = (BoolSpec) ClientOptionCatalog.find(ClientOptionCatalog.ID_RECEIVE_SERVER_LODS).orElseThrow();
        option.setter().accept(LSSClientConfig.CONFIG, enabled);
        option.saveHook().run(LSSClientConfig.CONFIG);
    }
    private static void sameWorld(Minecraft mc) throws ReflectiveOperationException {
        require(mc.level == level && mc.getConnection() == connection, "Native world/connection changed");
        require(bridge() == bridge, "Xaero bridge instance changed");
        require(Objects.equals(worldId, read(bridge, "lastWorldId")), "Xaero world identity changed");
    }
    public static void tick() {
        if (System.getProperty("lss.rig.runId", "").isBlank()) return;
        if (failed || phase == 4) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (dir == null) {
                dir = mc.gameDirectory.toPath().toAbsolutePath().normalize();
                log("READY", "markerRoot=" + dir + " hooks=Minecraft.tick.HEAD/catalog.setter/SaveHook.run");
            }
            ticks++;
            if (phase == 0) {
                if (!Files.isRegularFile(dir.resolve("lss-wi5-arm-off"))) return;
                require(!Files.exists(dir.resolve("lss-wi5-arm-on")), "Stale ON marker exists before OFF arming");
                phase = 1;
                armedAt = System.nanoTime();
                log("ARMED", snapshot());
            }
            if (phase == 1) {
                if (System.nanoTime() - armedAt > 180_000_000_000L) {
                    log("PRECONDITION_TIMEOUT", snapshot());
                    phase = 4;
                    return;
                }
                String before = ModCompat.xaeroDiagLine();
                if (mc.level == null || mc.getConnection() == null || !LSSClientConfig.CONFIG.receiveServerLods
                        || !ClientNetGlue.isServerEnabled() || !ClientNetGlue.hasReceivedSessionConfig()
                        || ClientNetGlue.getRequestManager() == null || before == null
                        || !before.startsWith("XaeroMap: state=active")
                        || count(before, "queued") <= 0 || count(before, "pending_updates") <= 0) return;
                level = mc.level;
                connection = mc.getConnection();
                oldManager = ClientNetGlue.getRequestManager();
                bridge = bridge();
                require(bridge != null, "Positive Xaero diagnostics lack bridge");
                worldId = (String) read(bridge, "lastWorldId");
                require(worldId != null, "Positive Xaero premise lacks native world ID");
                subKey = subKey(oldManager);
                written = count(before, "written");
                drops = count(before, "dropped_updates");
                flushes = count(before, "frame_flushes");
                received = ClientNetGlue.getColumnsReceived();
                log("PRECONDITION", "nativeWorld=" + System.identityHashCode(level)
                        + " connection=" + System.identityHashCode(connection) + " xaeroWorld=" + worldId
                        + " subKey=" + subKey + " " + snapshot());
                apply(false);
                String after = ModCompat.xaeroDiagLine();
                log("AFTER_OFF", snapshot());
                sameWorld(mc);
                require(!LSSClientConfig.CONFIG.receiveServerLods && ClientNetGlue.getRequestManager() == null,
                        "SaveHook OFF did not retire manager synchronously");
                require(count(after, "queued") == 0 && count(after, "owed") == 0,
                        "SaveHook OFF retained acquisition queue/debt");
                require(count(after, "pending_updates") == count(before, "pending_updates")
                                && count(after, "pending_updates") > 0,
                        "SaveHook OFF discarded committed native pending updates");
                require(count(after, "dropped_updates") == drops, "SaveHook OFF dropped native updates");
                phase = 2;
                offAt = System.nanoTime();
                return;
            }
            if (phase == 2) {
                sameWorld(mc);
                String diag = ModCompat.xaeroDiagLine();
                require(!LSSClientConfig.CONFIG.receiveServerLods, "External reception change while fixture OFF");
                require(ClientNetGlue.getRequestManager() == null, "Manager resumed before ON marker");
                require(count(diag, "queued") == 0 && count(diag, "owed") == 0, "OFF acquisition queue/debt returned");
                require(count(diag, "written") == written, "New map writes occurred while reception OFF");
                require(count(diag, "dropped_updates") == drops, "Native pending work dropped while OFF");
                if (!drained && count(diag, "pending_updates") == 0 && count(diag, "frame_flushes") > flushes) {
                    drained = true;
                    log("OFF_NATIVE_REBUILDS_DRAINED", snapshot());
                }
                if (ticks % 100 == 0) log("OFF_OBSERVE", snapshot());
                if (!Files.isRegularFile(dir.resolve("lss-wi5-arm-on"))) return;
                // Keep the marker pending until native retained work naturally drains.
                if (!drained) return;
                log("BEFORE_ON", "offMillis=" + (System.nanoTime() - offAt) / 1_000_000 + " " + snapshot());
                apply(true);
                log("AFTER_ON", snapshot());
                require(!ClientNetGlue.hasReceivedSessionConfig(), "ON did not begin fresh real negotiation");
                phase = 3;
                armedAt = System.nanoTime();
                return;
            }
            if (phase == 3) {
                sameWorld(mc);
                require(LSSClientConfig.CONFIG.receiveServerLods, "External reception change during ON negotiation");
                if (System.nanoTime() - armedAt > 180_000_000_000L) {
                    log("RESUME_TIMEOUT", snapshot());
                    phase = 4;
                    return;
                }
                Object manager = ClientNetGlue.getRequestManager();
                if (manager == null || !ClientNetGlue.isServerEnabled() || !ClientNetGlue.hasReceivedSessionConfig()) return;
                require(manager != oldManager, "ON reused retired manager");
                require(Objects.equals(subKey, subKey(manager)), "ON changed server world/cache sub-key");
                String diag = ModCompat.xaeroDiagLine();
                if (ClientNetGlue.getColumnsReceived() > received && count(diag, "written") > written) {
                    log("PASS_SAME_WORLD_OFF_ON", "sameNativeWorld=true sameXaeroWorld=true sameConnection=true"
                            + " freshManager=true nativeRebuildsDrained=true " + snapshot());
                    phase = 4;
                } else if (ticks % 100 == 0) log("ON_NEGOTIATED_AWAIT_WRITES", snapshot());
            }
        } catch (Throwable error) {
            failed = true;
            LOG.error("[WI5-FIXTURE] FAIL tick=" + ticks + " phase=" + phase, error);
            try { if (dir != null) log("FAIL", error.toString()); } catch (Throwable ignored) {}
            // Observation driver fails closed; it never repairs or retries bridge state.
        }
    }
}
