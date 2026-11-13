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
 * from {@code ClientPacketListener.close()}, which the game calls on the client thread on every
 * way out of a server, where NeoForge posts its logged-out event too.
 *
 * <p>The mixin source is read as text (mixin-package classes refuse direct classloading under
 * the mixin bootstrap); the game's side is read from its classes, so a Minecraft update that
 * moves the call turns this red instead of silently unhooking the cleanup.
 */
class ClientDisconnectHookContractTest {

    private static final String LISTENER = "net/minecraft/client/multiplayer/ClientPacketListener";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final Pattern INJECT =
            Pattern.compile("@Inject\\(((?:[^()]|\\([^()]*\\))*)\\)", Pattern.DOTALL);

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

    @Test
    void theHookIsAtTheHeadOfTheListenersClose() throws IOException {
        String mixin = source("fabric/src/main/java/dev/vox/lss/mixin/ClientDisconnectHook.java");
        assertTrue(mixin.contains("@Mixin(ClientPacketListener.class)"));
        var inject = INJECT.matcher(mixin);
        assertTrue(inject.find(), "one @Inject");
        String body = inject.group(1).replaceAll("\\s+", " ");
        assertFalse(inject.find(), "exactly one @Inject");
        assertTrue(body.contains("method = \"close()V\""), body);
        assertTrue(body.contains("at = @At(\"HEAD\")"), body);
        assertTrue(mixin.contains("LSSClientNetworking.onConnectionClosing();"));

        assertTrue(gameClass(LISTENER).methods.stream()
                        .anyMatch(m -> m.name.equals("close") && m.desc.equals("()V")),
                "ClientPacketListener.close() is gone: re-derive where the game closes its play connection");
    }

    /** One caller, the game's own disconnect, and the call comes before the level is cleared. */
    @Test
    void theGameClosesTheListenerOnlyFromItsDisconnectAndBeforeTheLevelIsCleared() throws IOException {
        ClassNode minecraft = gameClass(MINECRAFT);
        List<String> callers = new ArrayList<>();
        for (MethodNode method : minecraft.methods) {
            List<AbstractInsnNode> insns = List.of(method.instructions.toArray());
            int close = -1, levelCleared = -1;
            for (int i = 0; i < insns.size(); i++) {
                AbstractInsnNode insn = insns.get(i);
                if (insn instanceof MethodInsnNode call && call.owner.equals(LISTENER)
                        && call.name.equals("close") && call.desc.equals("()V")) {
                    assertEquals(-1, close, "one close() in " + method.name + method.desc);
                    close = i;
                }
                if (levelCleared < 0 && insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD
                        && put.owner.equals(MINECRAFT) && put.name.equals("level")) {
                    levelCleared = i;
                }
            }
            if (close < 0) continue;
            callers.add(method.name + method.desc);
            assertEquals("disconnect", method.name, "close() is called from the game's disconnect");
            assertTrue(levelCleared > close,
                    "the level is cleared after close() in " + method.name + method.desc
                            + ": the cleanup needs the level it was working for");
        }
        assertEquals(1, callers.size(), "one caller of ClientPacketListener.close(): " + callers);
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
