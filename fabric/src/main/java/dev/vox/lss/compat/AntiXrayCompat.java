package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Crash shim for the DrexHD AntiXray mod (mod id {@code antixray}) — design:
 * docs/planning/antixray-compat-design.md §2.
 *
 * <p>AntiXray threads its obfuscation context through {@link ScopedValue}s
 * ({@code me.drex.antixray.common.util.Arguments}) that are bound only inside vanilla
 * chunk-packet construction, and its {@code PalettedContainer} mixins read two of them with
 * raw {@code .get()} — which throws {@link java.util.NoSuchElementException} when unbound.
 * Every LSS {@code section.write(buf)} runs outside that scope. This shim binds the values
 * to {@code null} around LSS's serialization choke points, routing those mixins onto their
 * benign {@code packetInfo == null} skip path — the same null AntiXray's own
 * {@code LevelChunkSectionMixin} binds for the biome write, so the semantics are theirs,
 * not ours.
 *
 * <p>Zero compile-time dependency, mirroring {@code VoxyCompat}: {@code ScopedValue} is
 * public JDK API, only the instances are obtained reflectively. Any resolution failure
 * (AntiXray refactoring {@code Arguments}) leaves the shim inactive with one warning; the
 * probe-path containment in {@code RequestProcessingService} is the crash floor under that
 * case.
 */
public final class AntiXrayCompat {
    private AntiXrayCompat() {}

    /**
     * The {@code Arguments} fields bound to null. PACKET_INFO + CHUNK_SECTION_INDEX are the
     * two read raw on the section write path today (both null-safe by AntiXray's own
     * {@code packetInfo != null} guard); PALETTE_ENTRIES + PRESET_VALUES are bound as
     * future-proofing — their current readers either check {@code isBound()} or bind their
     * own inner values, which win over ours.
     */
    static final String[] BOUND_FIELDS = {
            "PACKET_INFO", "CHUNK_SECTION_INDEX", "PALETTE_ENTRIES", "PRESET_VALUES"};

    /** Resolves AntiXray's ScopedValue instances — test seam for {@link #buildCarrier}. */
    @FunctionalInterface
    interface ArgumentsResolver {
        List<ScopedValue<?>> resolve() throws Throwable;
    }

    // Resolved once at first use (the first column serialization); the statics make the
    // inactive path a JIT-erasable constant check. Warn-once on failure is structural: a
    // static initializer runs exactly once.
    private static final ScopedValue.Carrier CARRIER = buildCarrierProduction();
    private static final boolean ACTIVE = CARRIER != null;

    /**
     * The initializer must be provably throw-free: an escaped throwable here would poison
     * the class (ExceptionInInitializerError → NoClassDefFoundError on every later
     * serialize), which the disk path does NOT contain. buildCarrier floors its own ladder;
     * this floors the loader lookup and the catch-path logging themselves.
     */
    private static ScopedValue.Carrier buildCarrierProduction() {
        try {
            return buildCarrier(FabricLoader.getInstance().isModLoaded("antixray"),
                    AntiXrayCompat::resolveArgumentsScopedValues);
        } catch (Throwable t) {
            return null;   // can't log — logging is part of what may have failed
        }
    }

    /**
     * Runs {@code body} with AntiXray's obfuscation-context ScopedValues bound to null when
     * the shim is active, or calls straight through otherwise. Callers bind once per COLUMN
     * (wrap the whole serialize call, not each section) — one carrier scope per column keeps
     * the cost negligible.
     */
    public static <T> T callSerializing(Supplier<T> body) {
        if (!ACTIVE) return body.get();
        return CARRIER.<T, RuntimeException>call(body::get);
    }

    /**
     * The activation ladder, injectable for tests: mod absent → inactive silently (the
     * normal case, resolver untouched); resolver throwable or an empty resolution →
     * inactive + warning; else a reusable carrier binding every resolved value to null.
     */
    static ScopedValue.Carrier buildCarrier(boolean modLoaded, ArgumentsResolver resolver) {
        if (!modLoaded) return null;
        try {
            List<ScopedValue<?>> values = resolver.resolve();
            ScopedValue.Carrier carrier = null;
            for (ScopedValue<?> sv : values) {
                @SuppressWarnings("unchecked")
                var key = (ScopedValue<Object>) sv;
                carrier = carrier == null
                        ? ScopedValue.where(key, null)
                        : carrier.where(key, null);
            }
            if (carrier == null) {
                throw new IllegalStateException("no ScopedValue instances resolved");
            }
            LSSLogger.info("AntiXray detected — serialization crash shim active "
                    + "(obfuscation context neutralized around LOD serialization)");
            return carrier;
        } catch (Throwable t) {
            LSSLogger.warn("AntiXray is installed but its obfuscation context could not be "
                    + "resolved — the LSS crash shim is INACTIVE for this session. LOD "
                    + "serving will likely fail on this AntiXray version (contained as "
                    + "blank LODs, not a crash).", t);
            return null;
        }
    }

    /** Production resolver: the four {@link #BOUND_FIELDS} statics of AntiXray's Arguments. */
    static List<ScopedValue<?>> resolveArgumentsScopedValues() throws Exception {
        Class<?> arguments = Class.forName("me.drex.antixray.common.util.Arguments");
        var values = new ArrayList<ScopedValue<?>>(BOUND_FIELDS.length);
        for (String name : BOUND_FIELDS) {
            Object value = arguments.getField(name).get(null);
            if (!(value instanceof ScopedValue<?> sv)) {
                throw new IllegalStateException("Arguments." + name + " is not a ScopedValue: "
                        + (value == null ? "null" : value.getClass().getName()));
            }
            values.add(sv);
        }
        return values;
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
     *  world off. ACTIVE: this world obfuscates {@code hiddenStates} below {@code maxBlockHeight}.
     *  UNREADABLE: mod present but its internals did not resolve — callers fall back to the
     *  LSS config keys (masking stays on rather than silently leaking). */
    public sealed interface EngineView {
        record Absent() implements EngineView {}
        record Disabled() implements EngineView {}
        record Active(List<BlockState> hiddenStates, int maxBlockHeight) implements EngineView {}
        record Unreadable() implements EngineView {}
    }

    private static final EngineView ABSENT = new EngineView.Absent();
    private static final EngineView DISABLED = new EngineView.Disabled();
    private static final EngineView UNREADABLE = new EngineView.Unreadable();

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
            return buildEngineProbe(FabricLoader.getInstance().isModLoaded("antixray"), Class::forName);
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
                    // must not disable engine adoption for the whole session.
                    return UNREADABLE;
                }
                if (handles.disabledClass.isInstance(controller)) {
                    return DISABLED;
                }
                if (!handles.antiXrayBase.isInstance(controller)) {
                    // An unknown controller flavor obfuscates in a way we cannot read —
                    // treat as unreadable (LSS-keys fallback), not as disabled.
                    throw new IllegalStateException("unknown controller " + controller.getClass().getName());
                }
                Object rawMap = handles.obfuscateGlobalGetter.invokeExact(controller);
                int maxBlockHeight = (int) handles.maxBlockHeightGetter.invokeExact(controller);
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
                + "be read — LOD masking falls back to the LSS xrayHiddenBlocks/"
                + "xrayMaxBlockHeight config keys for every world.", t);
    }
}
