package dev.vox.lss.sponge;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which Sponge event ends a player's LSS session (v0.23.0 plan §11). It must be
 * {@code ServerSideConnectionEvent.Leave}: SpongeVanilla posts it on the server thread, from
 * the play listener's {@code removePlayerFromWorld}, for a player who was in the world only.
 * {@code Disconnect} also fires for a connection that never reached play (with that
 * connection's profile), and from Sponge's login thread when the drop lands during
 * authentication, so a refused second login of an online account would end the online
 * player's session off the server thread. Read from the compiled plugin class.
 */
class SpongeConnectionEndContractTest {

    private static final String PLUGIN = "dev/vox/lss/sponge/LSSSpongePlugin";
    private static final String LISTENER = "Lorg/spongepowered/api/event/Listener;";
    private static final String LEAVE = "org/spongepowered/api/event/network/ServerSideConnectionEvent$Leave";
    private static final String DISCONNECT = "org/spongepowered/api/event/network/ServerSideConnectionEvent$Disconnect";
    private static final String GLUE = "dev/vox/lss/networking/server/LSSServerNetworking";

    private static ClassNode plugin() throws IOException {
        var node = new ClassNode();
        new ClassReader(Files.readAllBytes(SpongeTestSupport.classesDir().resolve(PLUGIN + ".class")))
                .accept(node, 0);
        return node;
    }

    private static boolean isListener(MethodNode method) {
        if (method.visibleAnnotations == null) return false;
        for (AnnotationNode a : method.visibleAnnotations) {
            if (a.desc.equals(LISTENER)) return true;
        }
        return false;
    }

    /** The event type of every {@code @Listener} method, as internal names. */
    private static List<String> listenedEvents(ClassNode plugin) {
        var out = new ArrayList<String>();
        for (MethodNode m : plugin.methods) {
            if (!isListener(m)) continue;
            Type[] args = Type.getArgumentTypes(m.desc);
            assertEquals(1, args.length, m.name + ": a listener takes exactly the event");
            out.add(args[0].getInternalName());
        }
        return out;
    }

    private static MethodNode listenerFor(ClassNode plugin, String event) {
        MethodNode found = null;
        for (MethodNode m : plugin.methods) {
            if (isListener(m) && Type.getArgumentTypes(m.desc)[0].getInternalName().equals(event)) {
                assertTrue(found == null, "two listeners for " + event);
                found = m;
            }
        }
        assertTrue(found != null, "no @Listener for " + event);
        return found;
    }

    private static boolean calls(MethodNode m, int opcode, String owner, String name, String desc) {
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == opcode && call.owner.equals(owner)
                    && call.name.equals(name) && (desc == null || call.desc.equals(desc))) {
                return true;
            }
        }
        return false;
    }

    private static boolean readsActive(MethodNode m) {
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(PLUGIN) && field.name.equals("active")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void noListenerTakesDisconnect() throws IOException {
        var events = listenedEvents(plugin());
        assertTrue(!events.contains(DISCONNECT), "ServerSideConnectionEvent.Disconnect fires for "
                + "connections that never reached play and from the login thread; listen to Leave");
        for (String event : events) {
            assertTrue(!event.contains("Disconnect"), "no disconnect-flavoured listener: " + event);
        }
    }

    @Test
    void leaveEndsTheSessionByThePlayersUniqueId() throws IOException {
        MethodNode leave = listenerFor(plugin(), LEAVE);
        assertTrue(readsActive(leave), "the Leave listener keeps the SpongeVanilla-only active guard");
        assertTrue(calls(leave, Opcodes.INVOKEINTERFACE, LEAVE, "player", null),
                "the id comes from the leaving ServerPlayer");
        boolean uniqueId = false;
        for (AbstractInsnNode insn : leave.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("uniqueId")
                    && call.desc.equals("()Ljava/util/UUID;")) {
                uniqueId = true;
            }
        }
        assertTrue(uniqueId, "the session is keyed by the player's unique id");
        assertTrue(calls(leave, Opcodes.INVOKESTATIC, GLUE, "onPlayerDisconnect", "(Ljava/util/UUID;)V"),
                "the Leave listener runs the connection-scoped cleanup");
    }

    @Test
    void onlyTheLeaveListenerEndsSessions() throws IOException {
        var plugin = plugin();
        MethodNode leave = listenerFor(plugin, LEAVE);
        for (MethodNode m : plugin.methods) {
            if (m == leave) continue;
            assertTrue(!calls(m, Opcodes.INVOKESTATIC, GLUE, "onPlayerDisconnect", null),
                    m.name + " also ends sessions; only the Leave listener may");
        }
    }
}
