package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Crash shim + engine probe for the DrexHD AntiXray mod (mod id {@code antixray}) —
 * design: docs/planning/antixray-compat-design.md §2/§3.
 *
 * <p>Since V-2/S5 (version-port-isolation-plan.md §3) the {@code ScopedValue} half of
 * the crash shim lives in {@link ScopedCarrier} — a VERSION-VOLATILE per-loader twin
 * (Java-25-only API; a Java-21 support line replaces that one file with a pass-through
 * instead of flavoring this shared xplat source). {@link #callSerializing} delegates.
 * This class must stay free of post-Java-21 JDK API — the {@code XplatJava21SurfaceTest}
 * exclusion list is EMPTY since the split, and it stays that way.
 *
 * <p>Zero compile-time dependency on the mod, mirroring {@code VoxyCompat}. Any
 * resolution failure (AntiXray refactoring its internals) degrades with one warning; the
 * probe-path containment in {@code RequestProcessingService} is the crash floor under
 * that case.
 */
public final class AntiXrayCompat {
    private AntiXrayCompat() {}

    /**
     * Runs {@code body} with AntiXray's obfuscation-context ScopedValues bound to null
     * when the shim is active, or calls straight through otherwise — delegated to the
     * version-volatile {@link ScopedCarrier} twin (S5). Callers bind once per COLUMN.
     */
    public static <T> T callSerializing(Supplier<T> body) {
        return ScopedCarrier.callSerializing(body);
    }

    // ------------------------------------------------------------------
    // Engine probe (design §3 Detection): per-world adoption of AntiXray's hidden list +
    // max-block-height — "mask exactly what the packet engine masks". Reflective surface:
    // Util.getBlockController(level) → controller; DisabledChunkPacketBlockController =
    // that world is anti-xray-off; else read ChunkPacketBlockControllerAntiXray's
    // obfuscateGlobal (Object2BooleanOpenHashMap<BlockState>, hidden = true entries) and
    // maxBlockHeight fields.
    // ------------------------------------------------------------------

    /** What the engine says about ONE world. ABSENT: no mod. DISABLED: mod present, this
     *  world off. ACTIVE: this world HIDES {@code hiddenStates} below {@code maxBlockHeight}
     *  (engine mode 1 — the obfuscateGlobal map IS the hidden list there).
     *  REPLACEMENT_NOISE: engine modes 2/3 — the controller's obfuscateGlobal is the
     *  hidden ∪ replacement UNION (every toObfuscate state is marked true in the mod's
     *  ctor, verified against 1.4.16 bytecode), and on real configs that includes bulk
     *  terrain (stone/deepslate/dirt/sand). Adopting it would make the flatten-masker
     *  rewrite essentially every section below the cutoff into its dominant NON-masked
     *  state — sculk/water/bedrock section-flattening, the black-LOD report of
     *  2026-07-27 — so callers mask the LSS config list at the ENGINE's height instead.
     *  UNREADABLE: mod present but its internals did not resolve — callers fall back to
     *  the LSS config keys (masking stays on rather than silently leaking). */
    public sealed interface EngineView {
        record Absent() implements EngineView {}
        record Disabled() implements EngineView {}
        record Active(List<BlockState> hiddenStates, int maxBlockHeight) implements EngineView {}
        record ReplacementNoise(int maxBlockHeight) implements EngineView {}
        /** {@code transientNull}: the null-controller rung only — "not yet known to
         *  AntiXray", the one unreadable flavor callers may retry (the manager's R2-7
         *  re-probe window rides this; every other rung is terminal for the session). */
        record Unreadable(boolean transientNull) implements EngineView {}
    }

    private static final EngineView ABSENT = new EngineView.Absent();
    private static final EngineView DISABLED = new EngineView.Disabled();
    private static final EngineView UNREADABLE = new EngineView.Unreadable(false);
    private static final EngineView UNREADABLE_TRANSIENT = new EngineView.Unreadable(true);

    /** Resolves the classes the probe reflects over — test seam for {@link #buildEngineProbe}. */
    @FunctionalInterface
    interface EngineClassResolver {
        Class<?> resolve(String name) throws ClassNotFoundException;
    }

    /** Per-level probe function; the latch-on-failure ladder lives in {@link #buildEngineProbe}. */
    @FunctionalInterface
    public interface EngineProbe {
        EngineView probe(Level level);
    }

    private static final class EngineHandles {
        final MethodHandle getBlockController;
        final Class<?> disabledClass;
        final Class<?> antiXrayBase;
        final Class<?> hideClass;
        final MethodHandle obfuscateGlobalGetter;
        final MethodHandle maxBlockHeightGetter;

        EngineHandles(EngineClassResolver resolver) throws Exception {
            var lookup = MethodHandles.lookup();
            Class<?> util = resolver.resolve("me.drex.antixray.common.util.Util");
            Class<?> controllerInterface = resolver.resolve(
                    "me.drex.antixray.common.util.controller.ChunkPacketBlockController");
            this.disabledClass = resolver.resolve(
                    "me.drex.antixray.common.util.controller.DisabledChunkPacketBlockController");
            this.antiXrayBase = resolver.resolve(
                    "me.drex.antixray.common.util.controller.ChunkPacketBlockControllerAntiXray");
            // Engine-mode discrimination by controller subclass: only HIDE's obfuscateGlobal
            // is a usable hidden list (modes 2/3 bake in the replacement-noise union).
            this.hideClass = resolver.resolve(
                    "me.drex.antixray.common.util.controller.HideChunkPacketBlockController");
            this.getBlockController = lookup.findStatic(util, "getBlockController",
                            MethodType.methodType(controllerInterface, Level.class))
                    .asType(MethodType.methodType(Object.class, Level.class));
            // Non-public fields (private obfuscateGlobal, protected maxBlockHeight) —
            // setAccessible works because mods share the unnamed-module classpath.
            var obfuscateGlobal = this.antiXrayBase.getDeclaredField("obfuscateGlobal");
            obfuscateGlobal.setAccessible(true);
            this.obfuscateGlobalGetter = lookup.unreflectGetter(obfuscateGlobal)
                    .asType(MethodType.methodType(Object.class, Object.class));
            var maxBlockHeight = this.antiXrayBase.getDeclaredField("maxBlockHeight");
            maxBlockHeight.setAccessible(true);
            this.maxBlockHeightGetter = lookup.unreflectGetter(maxBlockHeight)
                    .asType(MethodType.methodType(int.class, Object.class));
        }
    }

    private static final EngineProbe ENGINE_PROBE = buildEngineProbeProduction();

    /** Per-world engine adoption entry point. Never throws; never logs per call. */
    public static EngineView engineForLevel(Level level) {
        return ENGINE_PROBE.probe(level);
    }

    private static EngineProbe buildEngineProbeProduction() {
        try {
            return buildEngineProbe(dev.vox.lss.platform.LoaderServices.get().isModLoaded("antixray"), Class::forName);
        } catch (Throwable t) {
            return level -> ABSENT;   // same throw-free-initializer floor as the carrier
        }
    }

    /**
     * The probe ladder, injectable for tests: mod absent → a constant ABSENT probe
     * (silent); handle resolution failure → a constant UNREADABLE probe + one warning;
     * else a live probe whose PER-CALL failures latch to UNREADABLE with one warning (a
     * refactored controller shape must not warn once per world per serve).
     */
    static EngineProbe buildEngineProbe(boolean modLoaded, EngineClassResolver resolver) {
        if (!modLoaded) return level -> ABSENT;
        final EngineHandles handles;
        try {
            handles = new EngineHandles(resolver);
        } catch (Throwable t) {
            warnEngineUnreadable(t);
            return level -> UNREADABLE;
        }
        var latched = new java.util.concurrent.atomic.AtomicBoolean();
        return level -> {
            if (latched.get()) return UNREADABLE;
            try {
                Object controller = handles.getBlockController.invokeExact(level);
                if (controller == null) {
                    // "Not yet known to AntiXray" is not evidence the world is anti-xray-off:
                    // on a leak-prevention feature every unresolvable shape fails SAFE
                    // (LSS-keys masking), and this one skips the latch — a transient null
                    // must not disable engine adoption for the whole session. The TRANSIENT
                    // flavor tells the manager it may re-probe instead of caching.
                    return UNREADABLE_TRANSIENT;
                }
                if (handles.disabledClass.isInstance(controller)) {
                    return DISABLED;
                }
                if (!handles.antiXrayBase.isInstance(controller)) {
                    // An unknown controller flavor obfuscates in a way we cannot read —
                    // treat as unreadable (LSS-keys fallback), not as disabled.
                    throw new IllegalStateException("unknown controller " + controller.getClass().getName());
                }
                int maxBlockHeight = (int) handles.maxBlockHeightGetter.invokeExact(controller);
                if (!handles.hideClass.isInstance(controller)) {
                    // Obfuscate / ObfuscateLayer (modes 2/3): obfuscateGlobal is the
                    // hidden ∪ replacement union — see the EngineView doc.
                    return new EngineView.ReplacementNoise(maxBlockHeight);
                }
                Object rawMap = handles.obfuscateGlobalGetter.invokeExact(controller);
                var states = new ArrayList<BlockState>();
                for (var entry : ((java.util.Map<?, ?>) rawMap).entrySet()) {
                    if (Boolean.TRUE.equals(entry.getValue()) && entry.getKey() instanceof BlockState state) {
                        states.add(state);
                    }
                }
                return new EngineView.Active(List.copyOf(states), maxBlockHeight);
            } catch (Throwable t) {
                if (latched.compareAndSet(false, true)) {
                    warnEngineUnreadable(t);
                }
                return UNREADABLE;
            }
        };
    }

    private static void warnEngineUnreadable(Throwable t) {
        LSSLogger.warn("AntiXray is installed but its per-world obfuscation config could not "
                + "be read — LOD masking falls back to the " + dev.vox.lss.common.Brand.shortName() + " xrayHiddenBlocks/"
                + "xrayMaxBlockHeight config keys for every world.", t);
    }
}
