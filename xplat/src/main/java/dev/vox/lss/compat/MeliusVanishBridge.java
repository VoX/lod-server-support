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
     * Whether {@code target} is vanished under Melius. {@code false} when the API is absent;
     * {@code true} (HIDDEN) when the surface drifted or the query throws. The broadcast service
     * memoizes this per target per tick (review fold B3) — it is the cheap pre-filter before
     * the per-observer {@link #canSee}.
     */
    public static boolean isVanished(MinecraftServer server, UUID target) {
        resolve();
        if (state == -1) return false;
        if (state == -2) return true;
        try {
            return (boolean) isVanishedHandle.invoke(server, target);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            warnInvoke(t);
            return true;
        }
    }

    /**
     * Whether {@code observer} may see {@code target} under Melius's rules. {@code true}
     * when the API is absent; {@code false} (HIDDEN) when it is present but unusable or
     * the query throws. Review fold B4: the two handles bind INDEPENDENTLY — when only
     * {@code isVanished} resolves (a renamed {@code canSeePlayer}), vanished targets hide from
     * everyone and nobody else does, instead of a whole-server blackout.
     */
    public static boolean canSee(MinecraftServer server, ServerPlayer observer, UUID target) {
        resolve();
        if (state == -1) return true;
        if (state == -2) return false;
        try {
            if (!(boolean) isVanishedHandle.invoke(server, target)) return true;
            if (canSeePlayerHandle == null) return false; // partial drift: vanished = hidden from all
            return (boolean) canSeePlayerHandle.invoke(server, target, observer);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            warnInvoke(t);
            return false;
        }
    }

    private static void warnInvoke(Throwable t) {
        if (!invokeWarned) {
            invokeWarned = true;
            LSSLogger.warn("Melius Vanish query threw — treating the target as HIDDEN from"
                    + " far players (the fail-safe direction; a vanished player must never"
                    + " leak). One warn per session (" + t + ")");
        }
    }

    private static void resolve() {
        if (state != 0) return;
        synchronized (MeliusVanishBridge.class) {
            if (state != 0) return;
            Class<?> api;
            try {
                api = classResolver.resolve(API_CLASS);
            } catch (ClassNotFoundException absent) {
                state = -1; // the ordinary no-vanish-mod install — quiet
                return;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) throw vme;
                state = -2;
                LSSLogger.warn("Melius Vanish is present but its VanishAPI class failed to load"
                        + " — EVERY player is hidden from far-player rendering until LSS is"
                        + " updated for this Melius version (fail-safe direction) (" + t + ")");
                return;
            }
            var lookup = MethodHandles.publicLookup();
            MethodHandle vanished = null;
            MethodHandle canSee = null;
            Throwable drift = null;
            try {
                vanished = lookup.findStatic(api, "isVanished",
                        MethodType.methodType(boolean.class, MinecraftServer.class, UUID.class));
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) throw vme;
                drift = t;
            }
            try {
                canSee = lookup.findStatic(api, "canSeePlayer",
                        MethodType.methodType(boolean.class, MinecraftServer.class, UUID.class,
                                ServerPlayer.class));
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) throw vme;
                drift = drift == null ? t : drift;
            }
            isVanishedHandle = vanished;
            canSeePlayerHandle = canSee;
            if (vanished == null) {
                state = -2;
                LSSLogger.warn("Melius Vanish is present but its VanishAPI surface was not found"
                        + " (API drift?) — EVERY player is hidden from far-player rendering until"
                        + " LSS is updated for this Melius version (fail-safe direction) (" + drift + ")");
            } else {
                state = 1;
                if (canSee == null) {
                    LSSLogger.warn("Melius Vanish detected, but canSeePlayer did not resolve (API"
                            + " drift?) — vanished players are hidden from EVERY far-player viewer,"
                            + " per-observer exemptions are unavailable (" + drift + ")");
                } else {
                    LSSLogger.info("Melius Vanish detected — vanished players are filtered from"
                            + " far-player rendering");
                }
            }
        }
    }
}
