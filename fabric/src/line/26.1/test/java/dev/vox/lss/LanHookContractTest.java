// OVERLAY OF fabric/src/test/java/dev/vox/lss/LanHookContractTest.java @ 9502458ed45b7ed1b71cac364ab86df8de85b9f5e868a1583e892240636e4d3f
// 26.1 line overlay (single-branch-consolidation-plan.md §3.2). Refresh the stamp when the shared file changes.
package dev.vox.lss;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the LAN-hook mixin's @Inject descriptor to a REAL {@code IntegratedServer} method.
 *
 * <p>The regression class this guards: at the MC 26.2 port the hook was pinned to the
 * 4-arg {@code publishServer} overload, but 26.2's LAN screen calls the 2-arg overload
 * directly (the 4-arg is a delegating wrapper) — so the mixin applied cleanly, resolved
 * cleanly, and simply never fired for "Open to LAN". A descriptor naming a method that
 * exists but is not on the GUI path cannot be caught reflectively; what CAN be pinned is
 * (a) the descriptor resolves against a declared overload at all (goes red on the next MC
 * bump if the signature drifts, instead of silently never firing), and (b) the descriptor
 * is exactly the overload the GUI calls, so a future edit cannot quietly re-target a
 * wrapper.
 *
 * <p>26.1-LINE FLAVOR (D3 re-port): this line's {@code IntegratedServer} declares exactly
 * ONE {@code publishServer} overload — {@code (GameType, boolean, int)} — which both the
 * LAN screen and {@code /publish} call (the 26.2 MultiplayerScope split does not exist
 * here), so the pinned descriptor is that 3-arg form and the single-overload assertion
 * below is this line's version of guard (b).
 *
 * <p>Reads the SOURCE file (the {@code GameTestEntrypointContractTest} idiom): classes in
 * a defined mixin package refuse direct classloading under fabric-loader-junit's mixin
 * bootstrap.
 */
class LanHookContractTest {

    // Arg-order- and line-break-tolerant (DOTALL): a pure reformat must not red this test.
    private static final Pattern INJECT_METHOD =
            Pattern.compile("@Inject\\([^)]*?method\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);

    /** Survives both the Gradle CWD (module dir) and an IDE repo-root CWD. */
    private static Path mixinSource() {
        // Line-aware (single-branch consolidation): read THIS line's overlay of the mixin
        // (26.1's IntegratedServerLanHook has the single-overload descriptor), not the shared
        // 26.2 source — LineGoldens.mainSource resolves overlay-first for the active line.
        return dev.vox.lss.LineGoldens.mainSource("src/main/java/dev/vox/lss/mixin/IntegratedServerLanHook.java");
    }

    @Test
    void injectDescriptorTargetsTheSingleOverloadTheLanGuiCalls() throws Exception {
        String source = Files.readString(mixinSource());
        var matcher = INJECT_METHOD.matcher(source);
        assertTrue(matcher.find(), "the mixin declares exactly one @Inject with a method descriptor");
        String descriptor = matcher.group(1);
        assertFalse(matcher.find(), "exactly one @Inject expected");

        // 26.1-line pin: the one real overload. (On 26.2 this is the 2-arg
        // MultiplayerScope form — each line carries its own flavor of this constant.)
        assertEquals("publishServer(Lnet/minecraft/world/level/GameType;ZI)Z",
                descriptor,
                "the hook must target this line's single (GameType, boolean, int) overload");

        // The descriptor must resolve against a REAL declared overload — the next MC bump
        // that changes the signature turns this red instead of silently unhooking LAN.
        List<String> declared = new ArrayList<>();
        for (Method m : net.minecraft.client.server.IntegratedServer.class.getDeclaredMethods()) {
            if (m.getName().equals("publishServer")) {
                declared.add("publishServer" + descriptorOf(m));
            }
        }
        assertTrue(declared.contains(descriptor),
                "the @Inject descriptor must match a declared IntegratedServer.publishServer "
                        + "overload; declared overloads: " + declared);
        // Guard (b), 26.1 form: the moment a second overload appears (a future 26.1.x
        // backport of the MultiplayerScope split), this line must re-derive which one the
        // GUI calls instead of trusting single-overload dispatch.
        assertEquals(1, declared.size(),
                "this line's IntegratedServer must declare exactly one publishServer "
                        + "overload; a new overload re-opens the M2 wrong-overload trap: " + declared);
    }

    private static String descriptorOf(Method m) {
        var sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) sb.append(jvmName(p));
        return sb.append(')').append(jvmName(m.getReturnType())).toString();
    }

    private static String jvmName(Class<?> c) {
        if (c == int.class) return "I";
        if (c == boolean.class) return "Z";
        if (c == void.class) return "V";
        if (c.isArray()) return c.getName().replace('.', '/');
        if (c.isPrimitive()) throw new AssertionError("unmapped primitive " + c);
        return "L" + c.getName().replace('.', '/') + ";";
    }
}
