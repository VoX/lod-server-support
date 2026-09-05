package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * The Melius Vanish bridge for far players (far-player-render-hardening-plan.md WI-7b): a
 * reflective zero-compile-dep bridge to {@code me.drex.vanish.api.VanishAPI} — the
 * {@code FabricPermissionsBridge} pattern. PRESENCE is probed by {@code Class.forName} on
 * the API interface itself (never an {@code isModLoaded} call: this file is xplat and
 * loader-pure, and a JiJ'd or renamed carrier would leave an id-keyed probe blind).
 *
 * <p>Argument order is the exposure trap: LSS's seam is {@code canSee(viewer, target)};
 * Melius's is {@code canSeePlayer(actor, observer)} where {@code actor} is the possibly
 * VANISHED player (LSS's target) and {@code observer} is the one looking (LSS's viewer).
 * The UUID overload {@code canSeePlayer(MinecraftServer, UUID actor, ServerPlayer observer)}
 * is bound so the two roles cannot be swapped without a type error, and
 * {@code isVanished(MinecraftServer, UUID)} is the fast path (a non-vanished target is
 * visible without consulting per-viewer rules).
 *
 * <p>Fail directions: API absent = visible (no vanish exists); API present but its surface
 * drifted, or a throwing call = HIDDEN with a once-warn — a leaked vanished admin is the
 * unrecoverable error, a temporarily over-hidden far-player set is not. (The permission
 * read in {@code FabricFarPlayerSnapshots} is fail-VISIBLE for the seam-contract reason
 * recorded there; this bridge owns its own catch, so it can afford the safe direction.)
 * MC parameter types are bound with class literals, never {@code Class.forName} on MC
 * names (Fabric's runtime remapping).
 */
public final class MeliusVanishBridge {

    /** Resolves the reflected class name — test seam ({@code VoxyCompat} shape). */
    @FunctionalInterface
    interface ClassResolver {
        Class<?> resolve(String name) throws ClassNotFoundException;
    }

    static ClassResolver classResolver = Class::forName;

    private static final String API_CLASS = "me.drex.vanish.api.VanishAPI";

    // 0 = unresolved, 1 = resolved, -1 = absent (visible), -2 = present but unusable (hidden).
    private static volatile int state;
    private static MethodHandle isVanishedHandle;   // (MinecraftServer, UUID) -> boolean
    private static MethodHandle canSeePlayerHandle; // (MinecraftServer, UUID actor, ServerPlayer observer) -> boolean
    private static volatile boolean invokeWarned;

    private MeliusVanishBridge() {
    }

    /** Test seam: forget the resolution (lazy resolve reruns). */
    static void resetForTest() {
        state = 0;
        invokeWarned = false;
        classResolver = Class::forName;
    }

    /** Whether a Melius Vanish API is on the classpath at all (resolved or drifted) —
     *  the wiring's cheap pre-check before it resolves the viewer entity. */
    public static boolean present() {
        resolve();
        return state != -1;
    }

    /**
     * Whether {@code observer} may see {@code target} under Melius's rules. {@code true}
     * when the API is absent; {@code false} (HIDDEN) when it is present but unusable or
     * the query throws.
     */
    public static boolean canSee(MinecraftServer server, ServerPlayer observer, UUID target) {
        resolve();
        if (state == -1) return true;
        if (state == -2) return false;
        try {
            if (!(boolean) isVanishedHandle.invoke(server, target)) return true;
            return (boolean) canSeePlayerHandle.invoke(server, target, observer);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            if (!invokeWarned) {
                invokeWarned = true;
                LSSLogger.warn("Melius Vanish query threw — treating the target as HIDDEN from"
                        + " far players (the fail-safe direction; a vanished player must never"
                        + " leak). One warn per session (" + t + ")");
            }
            return false;
        }
    }

    private static void resolve() {
        if (state != 0) return;
        synchronized (MeliusVanishBridge.class) {
            if (state != 0) return;
            try {
                Class<?> api = classResolver.resolve(API_CLASS);
                var lookup = MethodHandles.publicLookup();
                isVanishedHandle = lookup.findStatic(api, "isVanished",
                        MethodType.methodType(boolean.class, MinecraftServer.class, UUID.class));
                canSeePlayerHandle = lookup.findStatic(api, "canSeePlayer",
                        MethodType.methodType(boolean.class, MinecraftServer.class, UUID.class,
                                ServerPlayer.class));
                state = 1;
                LSSLogger.info("Melius Vanish detected — vanished players are filtered from"
                        + " far-player rendering");
            } catch (ClassNotFoundException absent) {
                state = -1; // the ordinary no-vanish-mod install — quiet
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) throw vme;
                state = -2;
                LSSLogger.warn("Melius Vanish is present but its VanishAPI surface was not found"
                        + " (API drift?) — EVERY player is hidden from far-player rendering until"
                        + " LSS is updated for this Melius version (fail-safe direction) (" + t + ")");
            }
        }
    }
}
