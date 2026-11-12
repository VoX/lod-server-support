package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.config.SettingsHandle;
import dev.vox.lss.common.config.SettingsSchema;
import dev.vox.lss.common.config.SettingsStore;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.client.ClientCommandActions.ClearCacheScope;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.seed.WorldSubKey;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objenesis.ObjenesisStd;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code clearcache} follows the connection (re-download plan, decision 2). The command and
 * the Re-download Terrain button share {@link ClientCommandActions#clearCacheScope}: an LSS
 * session or a DH terrain connection clears this server; a remote server that is sending
 * nothing clears NOTHING (it used to clear every server: Receive OFF, the map-policy park, a
 * server-side disable and the moment before the session config all leave no request manager);
 * singleplayer and the title screen clear every server. Every state runs the real
 * {@code clearCache} against a scratch cache root and a constructor-free {@link Minecraft};
 * the DH terrain connection is driven in {@code DhClientSessionTest}, which owns that harness.
 */
class ClearCacheScopeTest {

    @TempDir Path dir;
    private Object previousMinecraft;
    private Object previousCacheRoot;
    private Object previousHandle;
    private Object previousNativeAvailability;
    private SettingsHandle<?> settings;
    private TestMinecraft minecraft;
    private Path thisServer;
    private Path otherServer;
    private String component;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setup() throws Exception {
        previousMinecraft = read(null, Minecraft.class, "instance");
        previousCacheRoot = read(null, ColumnCacheStore.class, "cacheDir");
        previousHandle = read(LSSClientConfig.CONFIG, LSSClientConfig.class, "handle");
        previousNativeAvailability = read(null, DhClientSession.class, "nativeAvailable");
        write(null, ColumnCacheStore.class, "cacheDir", dir.resolve("column-cache"));
        // Allocated without Minecraft's graphics constructor; getConnection reads player.connection.
        minecraft = new ObjenesisStd().newInstance(TestMinecraft.class);
        write(null, Minecraft.class, "instance", minecraft);
        var store = new SettingsStore<>(dir, "lss", SettingsSchema.client());
        var handle = new SettingsHandle<>(store);
        handle.commit(handle.prepareReload());
        settings = handle;
        write(LSSClientConfig.CONFIG, LSSClientConfig.class, "handle", handle);
        DhClientSession.disconnect();
        // A server that offers native LSS: DH is never the selected source in these states.
        DhClientSession.installNativeAvailability(() -> true);

        component = "scope-" + UUID.randomUUID();
        thisServer = bucket(component);
        otherServer = bucket("other-" + UUID.randomUUID());
    }

    @AfterEach
    void cleanup() {
        try {
            DhClientSession.disconnect();
            ColumnCacheStore.flushPendingIo();
        } finally {
            if (settings != null) settings.close();
            write(LSSClientConfig.CONFIG, LSSClientConfig.class, "handle", previousHandle);
            write(null, DhClientSession.class, "nativeAvailable", previousNativeAvailability);
            write(null, Minecraft.class, "instance", previousMinecraft);
            write(null, ColumnCacheStore.class, "cacheDir", previousCacheRoot);
        }
    }

    // ---- the rule ----

    @Test
    void theRuleFollowsTheConnection() {
        //                                      lssSession dhTerrain connected integrated
        assertEquals(ClearCacheScope.CURRENT_SERVER, ClearCacheScope.of(true, false, true, false), "LSS session");
        assertEquals(ClearCacheScope.CURRENT_SERVER, ClearCacheScope.of(false, true, true, false), "DH terrain");
        assertEquals(ClearCacheScope.CURRENT_SERVER, ClearCacheScope.of(true, false, true, true),
                "a session on an integrated server (LAN host) still names this server");
        assertEquals(ClearCacheScope.NOT_RECEIVING, ClearCacheScope.of(false, false, true, false),
                "a remote server with no download");
        assertEquals(ClearCacheScope.ALL_SERVERS, ClearCacheScope.of(false, false, true, true), "singleplayer");
        assertEquals(ClearCacheScope.ALL_SERVERS, ClearCacheScope.of(false, false, false, false), "no world");
    }

    @Test
    void everyServerIsNeverTheAnswerOnARemoteServer() {
        for (int bits = 0; bits < 16; bits++) {
            boolean lss = (bits & 1) != 0, dh = (bits & 2) != 0, connected = (bits & 4) != 0, integrated = (bits & 8) != 0;
            ClearCacheScope scope = ClearCacheScope.of(lss, dh, connected, integrated);
            if (connected && !integrated) {
                assertNotEquals(ClearCacheScope.ALL_SERVERS, scope, "bits " + bits);
            }
            assertEquals(lss || dh, scope == ClearCacheScope.CURRENT_SERVER, "bits " + bits);
        }
    }

    // ---- every state, through the real clearCache ----

    @Test
    void anLssSessionClearsOnlyThisServer() {
        connect(false);
        var manager = new LodRequestManager();
        manager.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 64, true), component);
        manager.configureCacheKeying(component, List.of(component), "address", WorldSubKey.Context::disabled);
        assertEquals(ClearCacheScope.CURRENT_SERVER, ClientCommandActions.clearCacheScope(manager));

        var feedback = new ArrayList<Component>();
        ClientCommandActions.clearCache(feedback::add, manager);

        assertFalse(Files.exists(thisServer), "this server's records are cleared");
        assertTrue(Files.exists(otherServer), "another server's records stay");
        assertEquals(1, feedback.size());
        assertTrue(feedback.get(0).getString().contains("current server"), feedback.get(0).getString());
    }

    /** Receive OFF, a map-policy park, a server-side disable, the moment before the session
     *  config: connected to a remote server, no request manager, no DH terrain. */
    @Test
    void aRemoteServerThatSendsNothingClearsNothingAndSaysSo() {
        connect(false);
        assertNull(ClientNetGlue.getRequestManager(), "the unit JVM has no LSS session");
        assertEquals(ClearCacheScope.NOT_RECEIVING, ClientCommandActions.clearCacheScope());

        var feedback = new ArrayList<Component>();
        ClientCommandActions.clearCache(feedback::add);

        assertTrue(Files.exists(thisServer) && Files.exists(otherServer), "nothing is cleared");
        assertEquals(1, feedback.size());
        String line = feedback.get(0).getString();
        assertTrue(line.contains("not receiving distant terrain from this server"), line);
        assertTrue(line.contains("Nothing was cleared"), line);
    }

    /** The same remote server with DH allowed but not offered by it (the default settings on a
     *  server without LSS or DH): still nothing to download again, so still nothing cleared. */
    @Test
    void dhMerelyBeingAllowedOnARemoteServerClearsNothing() {
        connect(false);
        DhClientSession.installNativeAvailability(() -> false);
        assertTrue(DhClientSession.selected(), "DH is the selected source: allowed, and no native offer");
        assertFalse(DhClientSession.dhServerConnection(), "but the server has not negotiated DH");
        assertEquals(ClearCacheScope.NOT_RECEIVING, ClientCommandActions.clearCacheScope());

        var feedback = new ArrayList<Component>();
        ClientCommandActions.clearCache(feedback::add);

        assertTrue(Files.exists(thisServer) && Files.exists(otherServer), "nothing is cleared");
        assertTrue(feedback.get(0).getString().contains("Nothing was cleared"), feedback.get(0).getString());
    }

    @Test
    void singleplayerClearsEveryServer() {
        connect(true);
        assertEquals(ClearCacheScope.ALL_SERVERS, ClientCommandActions.clearCacheScope());

        var feedback = new ArrayList<Component>();
        ClientCommandActions.clearCache(feedback::add);

        assertFalse(Files.exists(thisServer) || Files.exists(otherServer), "every server's records are cleared");
        assertEquals(1, feedback.size());
        assertTrue(feedback.get(0).getString().contains("all servers"), feedback.get(0).getString());
    }

    @Test
    void noWorldClearsEveryServer() {
        assertNull(minecraft.getConnection(), "the title screen has no connection");
        assertEquals(ClearCacheScope.ALL_SERVERS, ClientCommandActions.clearCacheScope());

        var feedback = new ArrayList<Component>();
        ClientCommandActions.clearCache(feedback::add);

        assertFalse(Files.exists(thisServer) || Files.exists(otherServer), "every server's records are cleared");
        assertTrue(feedback.get(0).getString().contains("all servers"), feedback.get(0).getString());
    }

    /** The command's three sentences are told apart by the scope they were decided from. */
    @Test
    void theScopeAndTheFeedbackLineAgreeInEveryState() {
        record State(String name, Runnable enter, BooleanSupplier lssSession) { }
        var manager = new LodRequestManager();
        for (State state : List.of(
                new State("lss session", () -> connect(false), () -> true),
                new State("remote, nothing received", () -> connect(false), () -> false),
                new State("singleplayer", () -> connect(true), () -> false),
                new State("no world", () -> minecraft.player = null, () -> false))) {
            state.enter().run();
            LodRequestManager session = state.lssSession().getAsBoolean() ? manager : null;
            ClearCacheScope scope = ClientCommandActions.clearCacheScope(session);
            var feedback = new ArrayList<Component>();
            ClientCommandActions.clearCache(feedback::add, session);
            String line = feedback.get(0).getString();
            String expected = switch (scope) {
                case CURRENT_SERVER -> "cleared for current server";
                case NOT_RECEIVING -> "Nothing was cleared";
                case ALL_SERVERS -> "cleared for all servers";
            };
            assertTrue(line.contains(expected), state.name() + " decided " + scope + " but said: " + line);
        }
    }

    // ---- harness ----

    /** A connection to a remote server ({@code integrated=false}) or to the integrated one. */
    private void connect(boolean integrated) {
        minecraft.player = new ObjenesisStd().newInstance(LocalPlayer.class);
        write(minecraft.player, LocalPlayer.class, "connection",
                new ObjenesisStd().newInstance(ClientPacketListener.class));
        minecraft.integrated = integrated;
        if (integrated) {
            minecraft.server = null;
        } else {
            minecraft.server = new ObjenesisStd().newInstance(ServerData.class);
            minecraft.server.ip = "scope-test-" + UUID.randomUUID() + ":25565";
        }
    }

    private static Path bucket(String name) throws Exception {
        Path bucket = ColumnCacheStore.cacheRoot().resolve(name);
        Files.createDirectories(bucket);
        Files.writeString(bucket.resolve("minecraft_overworld.bin"), "saved stamps");
        return bucket;
    }

    private static final class TestMinecraft extends Minecraft {
        private ServerData server;
        private boolean integrated;
        private TestMinecraft() { super(null); } // never invoked: no native window in unit tests
        @Override public ServerData getCurrentServer() { return server; }
        @Override public boolean hasSingleplayerServer() { return integrated; }
    }

    private static Object read(Object object, Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void write(Object object, Class<?> type, String name, Object value) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(object, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
