package dev.vox.lss.common.compat;

import dev.vox.lss.common.LSSLogger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;

/**
 * Reflective probe for a Via-translated client's real protocol version (XVER plan §7):
 * {@code Via.getAPI().getPlayerVersion(uuid)}. A v19/v18/v16 LSS handshake carries no MC
 * version, and a legacy client on a DIFFERENT MC version cannot be served (its column
 * bytes are native-id formats for another registry set) — ViaVersion (Paper/Folia) or
 * ViaFabric (Fabric) being installed is the only way such a client can be connected
 * directly, and Via's own API is the only place its real version exists. ONE class
 * serves both platforms: ViaFabric bundles viaversion-common, so the API entry class is
 * the same name everywhere, and Via classes are not MC classes — no remap concern.
 *
 * <p>Zero compile-time dependency (the {@code MoonriseReadCompat}/{@code AntiXrayCompat}
 * pattern): handles resolve once per JVM off {@code getAPI}'s DECLARED return type (no
 * instance needed at resolve time). Failure shapes, all FAIL-OPEN (the guard denies
 * registration, so no-signal must never deny): class absent = Via not installed =
 * silent no-signal; present-but-unresolvable (API drift) = warn once + no-signal;
 * every per-call invoke failure — including Via's not-yet-initialized guard
 * throw ({@code IllegalArgumentException} out of a Guava precondition, verified
 * upstream) — = no-signal for that call. Via answers {@code -1}
 * (or the native protocol) for players it isn't translating, so {@link #mismatch}
 * additionally requires a positive answer.
 */
public final class ViaProbe {

    private static final String VIA_CLASS = "com.viaversion.viaversion.api.Via";

    /** No-signal sentinel: the probe cannot say anything about this player. */
    public static final int NO_SIGNAL = -1;

    /** Class resolution seam — production is {@code Class::forName}; tests inject
     *  wrong-shape classes without classpath collisions. */
    @FunctionalInterface
    interface ClassLookup {
        Class<?> lookup(String name) throws Throwable;
    }

    /** Drift-warning sink — production logs via {@link LSSLogger}; tests count. Only
     *  invoked when the Via class IS present but resolution failed. */
    @FunctionalInterface
    interface DriftWarn {
        void warn(String detail, Throwable cause);
    }

    @FunctionalInterface
    private interface PlayerProtocol {
        int protocolOrNoSignal(UUID uuid);
    }

    /** Null = Via absent or unresolvable — permanent no-signal for the JVM. */
    private final PlayerProtocol probe;

    private ViaProbe(PlayerProtocol probe) {
        this.probe = probe;
    }

    /** Lazy per-JVM holder: the plugin/mod set cannot change at runtime, so resolution
     *  runs once, on the first consult — a guard-disabled server never resolves. The
     *  lookup is NON-INITIALIZING (m4): a throwing Via {@code <clinit>} must read as
     *  probe-absent, never poison this class with an ExceptionInInitializerError. */
    private static final class Holder {
        static final ViaProbe INSTANCE = build(
                name -> Class.forName(name, false, ViaProbe.class.getClassLoader()),
                (detail, cause) -> LSSLogger.warn("ViaVersion present but its API did not"
                        + " resolve (" + detail + ") — the cross-MC mismatch guard is"
                        + " inactive; legacy " + dev.vox.lss.common.Brand.shortName() + " clients on other MC versions will not be"
                        + " detected", cause));
    }

    /** Instance-scoped for order-independent tests; production rides {@link Holder}. */
    static ViaProbe build(ClassLookup lookup, DriftWarn warn) {
        Class<?> via;
        try {
            via = lookup.lookup(VIA_CLASS);
        } catch (Throwable absent) {
            return new ViaProbe(null); // Via not installed — the common case, silent
        }
        try {
            Method getApi = via.getMethod("getAPI");
            if (!Modifier.isStatic(getApi.getModifiers())) {
                throw new NoSuchMethodException("getAPI is not static");
            }
            Class<?> apiType = getApi.getReturnType();
            Method getVersion = apiType.getMethod("getPlayerVersion", UUID.class);
            if (getVersion.getReturnType() != int.class) {
                throw new NoSuchMethodException("getPlayerVersion(UUID) returns "
                        + getVersion.getReturnType());
            }
            MethodHandle hGetApi = MethodHandles.publicLookup().unreflect(getApi);
            MethodHandle hGetVersion = MethodHandles.publicLookup().unreflect(getVersion);
            return new ViaProbe(uuid -> {
                try {
                    Object api = hGetApi.invoke();
                    if (api == null) return NO_SIGNAL;
                    return (int) hGetVersion.invoke(api, uuid);
                } catch (Throwable perCall) {
                    // Incl. Via's not-yet-initialized precondition throw — a later
                    // call may succeed, so no latch, no log (handshakes are rare).
                    return NO_SIGNAL;
                }
            });
        } catch (Throwable drift) {
            warn.warn(drift.toString(), drift);
            return new ViaProbe(null);
        }
    }

    /** The player's protocol per Via, or {@link #NO_SIGNAL}. Instance form for tests. */
    int playerProtocolOrNoSignal(UUID uuid) {
        var p = this.probe;
        return p == null ? NO_SIGNAL : p.protocolOrNoSignal(uuid);
    }

    /** The §7 mismatch RULE, pure and shared by both platforms' call sites (so the
     *  fail-open semantics cannot drift between them): mismatch requires a POSITIVE
     *  Via answer that differs from the server's native protocol — no-signal never
     *  denies. */
    public static boolean isMismatch(int viaProtocol, int nativeProtocol) {
        return viaProtocol > 0 && viaProtocol != nativeProtocol;
    }

    /** Production probe read — call sites capture this once per handshake (the log
     *  line wants the real number), apply {@link #isMismatch}, and additionally gate
     *  on {@code enableViaMismatchGuard} BEFORE consulting (a disabled guard must
     *  never trigger resolution). Catch-all belt (m4): this runs inside a network
     *  handler, and an escaped Holder-init Throwable would surface there as an
     *  ExceptionInInitializerError, then NoClassDefFoundError forever. */
    public static int playerProtocol(UUID uuid) {
        try {
            return Holder.INSTANCE.playerProtocolOrNoSignal(uuid);
        } catch (Throwable belt) {
            return NO_SIGNAL;
        }
    }
}
