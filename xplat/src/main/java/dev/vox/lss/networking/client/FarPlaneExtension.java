package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.farplayers.FarPlanePolicy;
import dev.vox.lss.config.LSSClientConfig;

import java.lang.reflect.Method;

/**
 * The client side of {@code far_players.extend_far_plane} (far-plane plan #364 §4-§6; on by
 * default since 0.22.0, decision 0004). Shared by every loader's hooks and far-player renderer;
 * everything runs on the render thread.
 *
 * <ul>
 *   <li>{@link #frameStart} — once per frame, before the far plane is read: the 26.x
 *       {@code Camera.update} hook, the 1.21.x {@code GameRenderer.renderLevel} HEAD hook;</li>
 *   <li>{@link #apply} — the far-plane hook: {@code max(vanilla, active)};</li>
 *   <li>{@link #report} / {@link #rendererPass} — the far-player renderer, per pass;</li>
 *   <li>{@link #onDisconnect} — the connection ends (1.21.x never runs {@code renderLevel}
 *       without a level, so the frame start alone would keep the old level referenced).</li>
 * </ul>
 *
 * <p>The hooks are {@code require = 0}: another mod rewriting the same code must not crash the
 * game for an optional visual. {@link #rendererPass} turns a missing or overridden hook into one
 * WARN per connection and a {@code far_plane_off=} reason in {@code /lss diag}.
 */
public final class FarPlaneExtension {
    private FarPlaneExtension() {}

    private static final long HOOK_STALE_NANOS = 5_000_000_000L;
    private static final long SHADER_RECHECK_NANOS = 1_000_000_000L;

    private static final FarPlanePolicy POLICY = new FarPlanePolicy();
    private static Object level;
    private static float vanillaFar;
    private static float activeFar;
    private static long lastFrameStartNanos;
    private static long lastApplyNanos;
    private static boolean shaderPack;
    private static long shaderCheckedNanos;
    private static boolean noIris;
    private static boolean warnedProbe;
    private static Method irisInstance, irisInUse;
    /** One WARN per connection each (reset by {@link #onDisconnect}, not by dimension changes). */
    private static boolean warnedStale, warnedOverride;
    /** The last renderer pass's verdict: null, "hook" (a hook is not running) or "override". */
    private static String conflict;
    /**
     * Whether an LOD renderer (Voxy, or another terrain consumer that is not a map) is taking
     * columns. The option is on by default and only acts while this holds: without distant
     * terrain a far player past the vanilla plane is fully fogged, so moving the plane would
     * change every frame for nothing. A field so tests can stand in for the renderer.
     */
    static java.util.function.BooleanSupplier lodRenderer = dev.vox.lss.compat.ModCompat::hasNonMapConsumers;

    /** The setting is on and an LOD renderer is there to make the result visible. */
    private static boolean wanted() {
        return LSSClientConfig.CONFIG.farPlayersExtendFarPlane() && lodRenderer.getAsBoolean();
    }

    /** Frame start. {@code currentLevel} is the client level (identity only; null = none). */
    public static void frameStart(Object currentLevel) {
        lastFrameStartNanos = System.nanoTime();
        boolean enabled = wanted();
        if (currentLevel != level) {
            // A new ClientLevel per dimension and per connection: both reset the latch.
            level = currentLevel;
            POLICY.reset();
        }
        boolean shaders = enabled && shaderPackInUse();
        if (shaders) POLICY.reset();
        POLICY.publish(enabled && !shaders && currentLevel != null);
    }

    /** The far-plane hook: the value to use given vanilla's {@code vanilla}. */
    public static float apply(float vanilla) {
        lastApplyNanos = System.nanoTime();
        vanillaFar = vanilla;
        activeFar = level == null ? vanilla : POLICY.active(vanilla);
        return activeFar;
    }

    /** Renderer: an eligible proxy at {@code distanceBlocks} from the camera. */
    public static void report(double distanceBlocks) {
        POLICY.report(distanceBlocks);
    }

    /** The connection ended: drop the level and the latch; the WARNs may fire again. */
    public static void onDisconnect() {
        level = null;
        POLICY.reset();
        warnedStale = warnedOverride = false;
        conflict = null;
    }

    /**
     * Renderer, once per pass: {@code planeInUse} is the far plane the frame actually used
     * (26.x {@code cameraState.depthFar}, 1.21.x {@code getDepthFar()}). Both hooks must have
     * run recently (on 1.21.x the renderer's own {@code getDepthFar()} call stamps the getter
     * hook, so the frame-start stamp is the one that catches a lost {@code renderLevel} hook).
     */
    public static void rendererPass(float planeInUse) {
        if (!wanted() || shaderPack) {
            conflict = null;
            return;
        }
        long now = System.nanoTime();
        if (stale(lastFrameStartNanos, now) || stale(lastApplyNanos, now)) {
            conflict = "hook";
            if (!warnedStale) {
                warnedStale = true;
                LSSLogger.warn("Far plane extension is unavailable: another mod changes the same code.");
            }
        } else if (planeInUse + 0.5f < activeFar) {
            conflict = "override";
            if (!warnedOverride) {
                warnedOverride = true;
                LSSLogger.warn("Another mod overrides the far plane, so far players past "
                        + Math.round(planeInUse) + " blocks are not drawn.");
            }
        } else {
            conflict = null;
        }
    }

    private static boolean stale(long stampNanos, long now) {
        return stampNanos == 0 || now - stampNanos > HOOK_STALE_NANOS;
    }

    /** The {@code /lss diag} fragment for the far-player line (always present). */
    public static String diagFragment() {
        String line = "far_plane=" + Math.round(activeFar) + " (vanilla " + Math.round(vanillaFar) + ")";
        if (!LSSClientConfig.CONFIG.farPlayersExtendFarPlane()) return line + ", extend=off";
        if (!lodRenderer.getAsBoolean()) return line + ", extend=idle (no LOD renderer)";
        String off = shaderPack ? "shaders" : conflict;
        return off == null ? line : line + ", far_plane_off=" + off;
    }

    /** Gametest probes: whether each hook has run, and the last values the far-plane hook saw/returned. */
    public static boolean hookRan() { return lastApplyNanos != 0; }
    public static boolean frameStartRan() { return lastFrameStartNanos != 0; }
    public static float lastVanillaFar() { return vanillaFar; }
    public static float activeFar() { return activeFar; }

    /** Test support: forget everything (the state is static and outlives a test). */
    static void resetForTest() {
        onDisconnect();
        vanillaFar = activeFar = 0;
        lastFrameStartNanos = lastApplyNanos = 0;
        shaderPack = noIris = warnedProbe = false;
        shaderCheckedNanos = 0;
        lodRenderer = dev.vox.lss.compat.ModCompat::hasNonMapConsumers;
    }

    /**
     * Iris reports a shader pack in use (reflection; re-checked at most once a second). No Iris
     * means no pack. Any other failure fails CLOSED (treated as a pack in use, so the plane
     * stays vanilla) and is retried a second later.
     */
    private static boolean shaderPackInUse() {
        long now = System.nanoTime();
        if (shaderCheckedNanos != 0 && now - shaderCheckedNanos < SHADER_RECHECK_NANOS) return shaderPack;
        shaderCheckedNanos = now;
        if (noIris) return shaderPack = false;
        try {
            if (irisInUse == null) {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi", false,
                        FarPlaneExtension.class.getClassLoader());
                irisInstance = api.getMethod("getInstance");
                irisInUse = api.getMethod("isShaderPackInUse");
            }
            return shaderPack = (boolean) irisInUse.invoke(irisInstance.invoke(null));
        } catch (ClassNotFoundException e) {
            noIris = true;
            return shaderPack = false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!warnedProbe) {
                warnedProbe = true;
                LSSLogger.warn("Could not ask Iris whether a shader pack is on; the far plane stays vanilla.", e);
            }
            return shaderPack = true;
        }
    }
}
