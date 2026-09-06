package dev.vox.lss.paper;

import dev.vox.lss.common.LSSLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The LibsDisguises bridge for far players (issue #282, issues-275-282-fix-plan.md WI-B): a
 * reflective zero-compile-dep bridge to {@code me.libraryaddict.disguise.DisguiseAPI} — the
 * {@link dev.vox.lss.compat.MeliusVanishBridge} idiom on the Paper module.
 *
 * <p>Why a SERVER-side rung: the client renderer's handoff draws a far-player proxy exactly
 * when no vanilla player entity with the roster UUID is in the client level. LibsDisguises
 * rewrites the spawn so the client sees the disguise under a disguise-specific UUID — the
 * real UUID is never present, the handoff draws the proxy, and the disguised player is
 * revealed by name and position through the LOD terrain. The client belt is structurally
 * inert against it; only the server knows a player is disguised, so the privacy ladder
 * ({@link PaperFarPlayerSnapshots#hiddenFor}) gains this rung after the vanish read.
 *
 * <p>The bound surface is {@code public static boolean isDisguised(Entity disguised)},
 * verified against LibsDisguises master
 * {@code plugin/src/main/java/me/libraryaddict/disguise/DisguiseAPI.java:410} (2026-09-05)
 * — the drift reference. The per-viewer overload {@code isDisguised(Player, Entity)} exists
 * upstream and is deliberately NOT bound in v1 (far-player frames are not per-viewer).
 *
 * <p>Presence is probed WITHOUT class initialization ({@code Class.forName(name, false,
 * loader)}): {@code DisguiseAPI} drags {@code DisguiseUtilities.<clinit>} (heavy static
 * state) — initialization is left to the first real call, on the pump. The read is GATED
 * per call on the plugin being loaded AND enabled (a plain plugin-manager lookup, no
 * scheduler reference — {@code FoliaWiringContractTest}'s domain): a loaded-but-disabled
 * LibsDisguises rewrites no packets, so hiding anyone there protects nothing, and the gate
 * running BEFORE resolution means an absent plugin never resolves at all. The gate's
 * answer is the running plugin INSTANCE, remembered at resolve time: a single-plugin hot
 * reload (PlugMan-style) gives LibsDisguises a fresh classloader, and a handle bound to
 * the orphaned class would answer "not disguised" for everyone, silently — a different
 * instance re-resolves instead.
 *
 * <p>Fail directions. Class absent or surface drifted with the plugin ENABLED = visible
 * (the pre-fix behavior) with ONE warn — the bridge never makes the ladder throw at
 * resolve time. A THROWING read (any {@link Throwable} — a broken install surfaces as
 * {@code ExceptionInInitializerError}/{@code NoClassDefFoundError}, which the ladder's
 * {@code catch (Exception)} would NOT contain) is wrapped into an
 * {@link IllegalStateException} and rethrown so the ladder's own contained catch answers
 * HIDDEN with its once-warn; there is deliberately NO latch-to-absent on a throw — an
 * enabled-but-throwing LibsDisguises may still be rewriting spawns, and latching would
 * restore exactly the reported leak.
 */
public final class LibsDisguisesBridge {

    /** Resolves the reflected class name — test seam ({@code MeliusVanishBridge} shape). */
    @FunctionalInterface
    interface ClassResolver {
        Class<?> resolve(String name) throws ClassNotFoundException;
    }

    /** The per-call gate — test seam: the running, ENABLED LibsDisguises plugin instance
     *  (an identity token), or {@code null} when it is not loaded and enabled. */
    @FunctionalInterface
    interface PluginProbe {
        Object enabledPlugin();
    }

    private static final String API_CLASS = "me.libraryaddict.disguise.DisguiseAPI";
    private static final String PLUGIN_NAME = "LibsDisguises";

    private static final ClassResolver DEFAULT_RESOLVER =
            name -> Class.forName(name, false, LibsDisguisesBridge.class.getClassLoader());
    /** Null-safe so a JVM without a Bukkit server (the plain-JUnit suites) reads "not
     *  enabled" instead of throwing ({@code Bukkit.getPluginManager()} NPEs there). */
    private static final PluginProbe DEFAULT_PLUGIN_PROBE = () ->
            Bukkit.getServer() == null ? null : enabledPlugin(Bukkit.getPluginManager());

    // The two seams are plain statics written by resetForTest() inside the monitor and
    // read outside it on the call path: test-only, single-threaded by construction —
    // production never writes them. Do not "fix" with volatile.
    static ClassResolver classResolver = DEFAULT_RESOLVER;
    static PluginProbe pluginProbe = DEFAULT_PLUGIN_PROBE;

    // 0 = unresolved, 1 = bound, -1 = absent or drifted (visible; warned once when drifted
    // or invisible with the plugin enabled). Both latches are per PLUGIN INSTANCE
    // (boundPlugin): a different instance — a hot reload — re-resolves from 0.
    private static volatile int state;
    private static volatile Object boundPlugin;
    private static MethodHandle isDisguisedHandle; // (Entity) -> boolean
    private static int resolveWarns;

    /** The exact-name lookup + enabled check behind the default gate (package-private so
     *  the PLUGIN_NAME spelling is test-pinned — a typo would make the rung silently inert). */
    static Object enabledPlugin(org.bukkit.plugin.PluginManager pluginManager) {
        if (pluginManager == null) return null;
        var plugin = pluginManager.getPlugin(PLUGIN_NAME);
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    private LibsDisguisesBridge() {
    }

    /** Test seam: forget the resolution and restore the default seams. */
    static void resetForTest() {
        synchronized (LibsDisguisesBridge.class) {
            state = 0;
            boundPlugin = null;
            isDisguisedHandle = null;
            resolveWarns = 0;
            classResolver = DEFAULT_RESOLVER;
            pluginProbe = DEFAULT_PLUGIN_PROBE;
        }
    }

    /** Test seam: how many resolve-time warns fired (once per JVM by construction). */
    static int resolveWarnsForTest() {
        synchronized (LibsDisguisesBridge.class) {
            return resolveWarns;
        }
    }

    /** Test seam: the bound handle's type, {@code null} while unbound. */
    static MethodType boundTypeForTest() {
        synchronized (LibsDisguisesBridge.class) {
            return isDisguisedHandle == null ? null : isDisguisedHandle.type();
        }
    }

    /** Test seam: whether the API resolved and bound (the gate has passed at least once).
     *  No production caller — the call path reads {@code state} directly. */
    static boolean present() {
        return state == 1;
    }

    /**
     * Whether LibsDisguises reports {@code entity} as disguised. {@code false} when the
     * plugin is not loaded-and-enabled, or its API is absent/drifted; a throwing read
     * surfaces as an {@link IllegalStateException} for the caller's contained catch (the
     * privacy ladder answers HIDDEN there) — never a silent {@code false}, never a latch.
     * A throwing plugin-manager lookup propagates as-is (a {@link RuntimeException}, which
     * the same contained catch answers HIDDEN).
     */
    public static boolean isDisguised(Entity entity) {
        Object plugin = pluginProbe.enabledPlugin();
        if (plugin == null) return false;
        resolve(plugin);
        if (state != 1) return false;
        try {
            return (boolean) isDisguisedHandle.invoke(entity);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            throw new IllegalStateException("LibsDisguises DisguiseAPI.isDisguised threw", t);
        }
    }

    private static void resolve(Object plugin) {
        if (state != 0 && boundPlugin == plugin) return;
        synchronized (LibsDisguisesBridge.class) {
            if (state != 0 && boundPlugin == plugin) return;
            if (state != 0) {
                // A different plugin instance than the one resolved against: a single-plugin
                // hot reload gave LibsDisguises a fresh classloader — the old handle (or a
                // stale absent/drift latch) must not outlive it.
                state = 0;
                isDisguisedHandle = null;
            }
            boundPlugin = plugin; // written BEFORE the state latch — readers see both
            Class<?> api;
            try {
                api = classResolver.resolve(API_CLASS);
            } catch (ClassNotFoundException invisible) {
                // The plugin IS enabled (the gate ran first), so its API class ought to be
                // visible — softdepend orders it before LSS. Visible failure, quiet fallback.
                state = -1;
                resolveWarns++;
                LSSLogger.warn("LibsDisguises is enabled but its DisguiseAPI class is not"
                        + " visible to LSS — disguised players are NOT hidden from far"
                        + " players (the pre-bridge behavior). Is LibsDisguises listed in"
                        + " plugin.yml softdepend? (" + invisible + ")");
                return;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) throw vme;
                state = -1;
                resolveWarns++;
                LSSLogger.warn("LibsDisguises is enabled but its DisguiseAPI class failed to"
                        + " load — disguised players are NOT hidden from far players until"
                        + " LSS is updated for this LibsDisguises version (" + t + ")");
                return;
            }
            try {
                isDisguisedHandle = MethodHandles.publicLookup().findStatic(api, "isDisguised",
                        MethodType.methodType(boolean.class, Entity.class));
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) throw vme;
                state = -1;
                resolveWarns++;
                LSSLogger.warn("LibsDisguises is enabled but DisguiseAPI.isDisguised(Entity) did"
                        + " not resolve (API drift?) — disguised players are NOT hidden from"
                        + " far players until LSS is updated for this LibsDisguises version"
                        + " (" + t + ")");
                return;
            }
            state = 1;
            LSSLogger.info("LibsDisguises detected — disguised players are hidden from far players");
        }
    }
}
