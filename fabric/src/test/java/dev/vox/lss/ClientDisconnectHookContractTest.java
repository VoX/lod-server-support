package dev.vox.lss;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's disconnect cleanup runs on the client thread, before the level is torn down.
 *
 * <p>Fabric's {@code ClientPlayConnectionEvents.DISCONNECT} fires from a network thread whenever
 * the socket closes first (a kick, a server stop, usually the player's own Disconnect button).
 * The cleanup ran there, against state the client thread was still ticking and drawing with:
 * the request manager's maps, the far-player proxies, the Distant Horizons session. So the
 * Fabric client no longer listens to that event. {@code ClientDisconnectHook} runs the cleanup
 * at the head of {@code Minecraft.disconnect}, which the game calls on the client thread on
 * every way out of a server and where NeoForge posts its logged-out event too.
 *
 * <p>The mixin source is read as text (mixin-package classes refuse direct classloading under
 * the mixin bootstrap); the game's side is read from its classes, so a Minecraft update that
 * changes the overloads turns this red instead of silently unhooking the cleanup.
 */
class ClientDisconnectHookContractTest {

    private static final String LISTENER = "net/minecraft/client/multiplayer/ClientPacketListener";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final Pattern INJECT =
            Pattern.compile("@Inject\\(((?:[^()]|\\([^()]*\\))*)\\)", Pattern.DOTALL);
    private static final Pattern METHOD = Pattern.compile("method = \"(disconnect\\([^\"]*\\)V)\"");

    private static String source(String repoPath) throws IOException {
        return Files.readString(SourcePaths.repoFile(repoPath));
    }

    private static ClassNode gameClass(String internalName) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream in = ClientDisconnectHookContractTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, internalName + ".class on the test classpath");
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    /** The descriptor the mixin injects into, as written in its source. */
    private static String hookedMethod() throws IOException {
        String mixin = source("fabric/src/main/java/dev/vox/lss/mixin/ClientDisconnectHook.java");
        assertTrue(mixin.contains("@Mixin(Minecraft.class)"));
        var inject = INJECT.matcher(mixin);
        assertTrue(inject.find(), "one @Inject");
        String body = inject.group(1).replaceAll("\\s+", " ");
        assertFalse(inject.find(), "exactly one @Inject");
        assertTrue(body.contains("at = @At(\"HEAD\")"), body);
        assertTrue(mixin.contains("LSSClientNetworking.onConnectionClosing();"));
        var method = METHOD.matcher(body);
        assertTrue(method.find(), "a full descriptor, so resolution cannot drift to another overload: " + body);
        return method.group(1);
    }

    /**
     * The game has several {@code disconnect} overloads that funnel into one. The hook must be
     * on that one: on an outer overload it would miss the callers of the inner one, and on
     * every overload it would run the cleanup several times per disconnect.
     */
    @Test
    void theHookIsAtTheHeadOfTheOverloadTheOthersReach() throws IOException {
        String hooked = hookedMethod();
        List<String> innermost = new ArrayList<>();
        List<String> delegating = new ArrayList<>();
        for (MethodNode method : gameClass(MINECRAFT).methods) {
            if (!method.name.equals("disconnect")) continue;
            boolean callsAnotherDisconnect = false;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(MINECRAFT)
                        && call.name.equals("disconnect")) {
                    callsAnotherDisconnect = true;
                }
            }
            (callsAnotherDisconnect ? delegating : innermost).add(method.name + method.desc);
        }
        assertEquals(List.of(hooked), innermost,
                "exactly one disconnect overload does the work, and it is the hooked one; the others ("
                        + delegating + ") only call on");
    }

    /** The hooked overload is the real teardown: it closes the play listener and clears the level. */
    @Test
    void theHookedOverloadClosesTheListenerAndClearsTheLevel() throws IOException {
        String hooked = hookedMethod();
        ClassNode minecraft = gameClass(MINECRAFT);
        List<String> closers = new ArrayList<>();
        for (MethodNode method : minecraft.methods) {
            boolean closes = false, clearsLevel = false;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(LISTENER)
                        && call.name.equals("close") && call.desc.equals("()V")) {
                    closes = true;
                }
                if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD
                        && put.owner.equals(MINECRAFT) && put.name.equals("level")) {
                    clearsLevel = true;
                }
            }
            if (!closes) continue;
            closers.add(method.name + method.desc);
            assertTrue(clearsLevel, method.name + method.desc + " also clears the level");
        }
        assertEquals(List.of(hooked), closers,
                "the play listener is closed from the hooked overload and nowhere else in Minecraft");
    }

    @Test
    void theFabricClientNoLongerCleansUpFromTheNetworkEvent() throws IOException {
        String glue = source("fabric/src/main/java/dev/vox/lss/networking/client/LSSClientNetworking.java");
        assertFalse(glue.contains("ClientPlayConnectionEvents.DISCONNECT.register"),
                "Fabric fires DISCONNECT from netty when the socket closes first");
        assertEquals(1, glue.split("ClientNetGlue\\.onDisconnect\\(\\)", -1).length - 1,
                "the cleanup has one caller on Fabric");
        int entry = glue.indexOf("public static void onConnectionClosing() {");
        assertTrue(entry >= 0 && glue.indexOf("ClientNetGlue.onDisconnect()") > entry,
                "…and it is the hook's entry point");
    }

    /** A client class in the common list would crash a dedicated server at startup. */
    @Test
    void theHookIsAClientMixin() throws IOException {
        String json = source("fabric/src/main/resources/lss.mixins.json").replaceAll("\\s+", "");
        int client = json.indexOf("\"client\":[");
        assertTrue(client >= 0);
        int hook = json.indexOf("\"ClientDisconnectHook\"");
        assertTrue(hook > client, "listed under \"client\"");
        assertEquals(hook, json.lastIndexOf("\"ClientDisconnectHook\""), "listed once");
    }

    /** NeoForge posts its logged-out event from the same game method, on the client thread. */
    @Test
    void neoForgeCleansUpFromItsLoggedOutEvent() throws IOException {
        String neo = source("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoClientBootstrap.java")
                .replaceAll("\\s+", " ");
        assertTrue(neo.contains("addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> ClientNetGlue.onDisconnect());"));
    }
}
