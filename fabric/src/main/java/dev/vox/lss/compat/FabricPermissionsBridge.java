package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * The Fabric permission rung of the service gate
 * (service-permission-gate-plan.md §2.1): a reflective zero-compile-dep bridge to
 * {@code fabric-permissions-api} (the de-facto standard LuckPerms et al. implement) —
 * the {@code MoonriseReadCompat} pattern. PRESENCE is probed by {@code Class.forName}
 * on the API class itself, deliberately NOT {@code isModLoaded} (§8 m10 of the plan
 * review: providers routinely JiJ the api, and a shaded/renamed carrier would leave
 * an id-keyed probe blind while the class sits on the classpath); the static
 * {@code check(Entity, String, boolean)} is resolved BY SHAPE (the class also carries
 * CommandSource overloads and TriState variants), once per JVM.
 *
 * <p>Every failure shape — class absent, no matching method, a throwing provider —
 * answers {@code defaultValue} (the gate passes {@code true}: fail-open, serve), with
 * a once-warned drift message for the present-but-unresolvable and throwing shapes.
 * The bridge CANNOT detect a present-API-dead-provider backend (an unset node and a
 * dead provider both answer the check-site default) — the plan's honesty scope. The
 * class-absent shape stays quiet HERE; the armed-gate once-warn lives in the recheck
 * sweep ({@code RequestProcessingService.runServiceGateSweeps}), keyed on
 * {@link #providerToken()} answering "none".
 */
public final class FabricPermissionsBridge {

    /** Resolves the reflected class name — test seam ({@code VoxyCompat} shape). */
    @FunctionalInterface
    interface ClassResolver {
        Class<?> resolve(String name) throws ClassNotFoundException;
    }

    static ClassResolver classResolver = Class::forName;

    private static final String API_CLASS = "me.lucko.fabric.api.permissions.v0.Permissions";

    // 0 = unresolved, 1 = resolved, -1 = absent/unresolvable.
    private static volatile int state;
    private static MethodHandle checkHandle; // (Entity, String, boolean) -> boolean
    private static volatile boolean invokeWarned;

    private FabricPermissionsBridge() {
    }

    /** Test seam: forget the resolution (lazy resolve reruns). */
    static void resetForTest() {
        state = 0;
        invokeWarned = false;
        classResolver = Class::forName;
    }

    /** Whether the api resolved — the armed-gate once-warn's condition and the
     *  provider-token input. */
    public static boolean present() {
        resolve();
        return state > 0;
    }

    /** The {@code Gate:} diag token for this loader. */
    public static String providerToken() {
        return present() ? "fabric-permissions-api" : "none";
    }

    /**
     * The permission read: {@code Permissions.check(player, node, defaultValue)}, or
     * {@code defaultValue} on ANY failure (contained; VirtualMachineErrors propagate).
     */
    public static boolean check(ServerPlayer player, String node, boolean defaultValue) {
        if (!present()) return defaultValue;
        try {
            return (boolean) checkHandle.invoke((Entity) player, node, defaultValue);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            if (!invokeWarned) {
                invokeWarned = true;
                LSSLogger.warn("fabric-permissions-api check threw — answering the default ("
                        + defaultValue + ") for " + node + " (" + t + ")");
            }
            return defaultValue;
        }
    }

    private static void resolve() {
        if (state != 0) return;
        synchronized (FabricPermissionsBridge.class) {
            if (state != 0) return;
            try {
                Class<?> api = classResolver.resolve(API_CLASS);
                Method match = null;
                for (Method m : api.getMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (!"check".equals(m.getName())) continue;
                    if (m.getReturnType() != boolean.class) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 3) continue;
                    // The Entity-flavored overload: the first param must accept a
                    // ServerPlayer (the real API declares Entity).
                    if (!p[0].isAssignableFrom(ServerPlayer.class)) continue;
                    if (p[1] != String.class || p[2] != boolean.class) continue;
                    match = m;
                    break;
                }
                if (match == null) {
                    state = -1;
                    LSSLogger.warn("fabric-permissions-api is present but its check(Entity, "
                            + "String, boolean) surface was not found (API drift?) — the "
                            + "service gate will serve everyone on this loader");
                    return;
                }
                checkHandle = MethodHandles.lookup().unreflect(match);
                state = 1;
            } catch (ClassNotFoundException absent) {
                state = -1; // the ordinary no-provider install — quiet; the ARMED sweep warns
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) throw vme;
                state = -1;
                LSSLogger.warn("fabric-permissions-api resolution failed — the service gate "
                        + "will serve everyone on this loader (" + t + ")");
            }
        }
    }
}
