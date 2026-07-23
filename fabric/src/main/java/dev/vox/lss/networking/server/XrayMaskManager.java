package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.XrayMaskPolicy;
import dev.vox.lss.common.XrayMaskPolicy.FallbackKind;
import dev.vox.lss.common.config.ServerConfigBase;
import dev.vox.lss.compat.AntiXrayCompat;
import dev.vox.lss.compat.AntiXrayCompat.EngineView;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Per-world x-ray mask decisions (docs/planning/antixray-compat-design.md §3 Detection),
 * owned by the request-processing service. Each world is evaluated ONCE (lazily, cached by
 * dimension string): the {@link XrayMaskPolicy} ladder combines the config tri-state with
 * the engine probe ({@link AntiXrayCompat#engineForLevel} — the AntiXray mod), and an
 * active world gets a resolved {@link XrayMaskFilter.MaskSet} — the engine's own hidden
 * states + max height when adoptable, the LSS config keys otherwise. Twin of
 * {@code PaperXrayMaskManager}.
 *
 * <p>Logging is bounded by construction: one info line per ACTIVE world (evaluation runs
 * once per dimension per service lifetime), nothing on the common inactive path, and the
 * LSS-keys fallback list resolves at most once per manager (its unknown-id warnings
 * cannot repeat per world).
 *
 * <p>The static holder mirrors {@code LSSServerNetworking.requestService}: the serializer
 * choke points are static, so the active manager is published statically by the service
 * lifecycle ({@link #activate}/{@link #deactivate}) — null means "no masking anywhere".
 */
public final class XrayMaskManager {

    /** An active world's resolved mask inputs; absence (null) = serve unmasked. */
    public record MaskEntry(XrayMaskFilter.MaskSet mask, FallbackKind kind, String sourceLabel) {}

    private static volatile XrayMaskManager active;

    private final XrayMaskPolicy.Mode mode;
    private final ServerConfigBase config;
    private final ConcurrentHashMap<String, Optional<MaskEntry>> byDimension = new ConcurrentHashMap<>();
    private final AtomicLong maskedSections = new AtomicLong();
    // The LSS-keys fallback tier, resolved at most once (memoized) so its unknown-id
    // warnings stay once-per-service — and never resolved at all under pure engine adoption.
    private volatile XrayMaskFilter.MaskSet fallbackMask;

    XrayMaskManager(ServerConfigBase config) {
        this.mode = XrayMaskPolicy.Mode.parse(config.xrayObfuscation);
        this.config = config;
    }

    /** Publishes a fresh manager for the starting service (config is read once here). */
    public static XrayMaskManager activate(ServerConfigBase config) {
        var manager = new XrayMaskManager(config);
        active = manager;
        return manager;
    }

    /** Guarded retract: clears the holder only when {@code owner} is still the published
     *  manager, so a stale shutdown (or a test-wired service that never published) cannot
     *  null out a successor service's masking. */
    public static void deactivate(XrayMaskManager owner) {
        if (owner != null && active == owner) {
            active = null;
        }
    }

    /** The serializer hook: the active world entry, or null when nothing masks. */
    public static MaskEntry entryForActive(ServerLevel level) {
        var manager = active;
        return manager == null ? null : manager.entryFor(level);
    }

    public static XrayMaskManager current() {
        return active;
    }

    public MaskEntry entryFor(ServerLevel level) {
        return entryFor(level.dimension().identifier().toString(),
                () -> AntiXrayCompat.engineForLevel(level));
    }

    /** The cache + decision core, level-free for Tier 1 ({@code view} runs at most once per dimension). */
    MaskEntry entryFor(String dimension, Supplier<EngineView> view) {
        return this.byDimension
                .computeIfAbsent(dimension, d -> Optional.ofNullable(evaluate(d, view.get())))
                .orElse(null);
    }

    /** Counted by the serializer hooks; surfaced by the {@code /lsslod diag} xray line. */
    public void countMaskedSection() {
        this.maskedSections.incrementAndGet();
    }

    public long maskedSections() {
        return this.maskedSections.get();
    }

    /**
     * The diag label: the strongest source among evaluated worlds — {@code off} (nothing
     * active), {@code config} (LSS keys), or the engine label ({@code antixray-mod} /
     * {@code paper-config} on the twin). Worlds evaluate lazily, so before any serve this
     * reads off.
     */
    public String diagLine() {
        String label = "off";
        for (var entry : this.byDimension.values()) {
            if (entry.isEmpty()) continue;
            String source = entry.get().sourceLabel();
            if (!source.equals("config")) {
                label = source;
                break;
            }
            label = "config";
        }
        return "Xray: active=" + label + ", masked_sections=" + this.maskedSections.get();
    }

    private MaskEntry evaluate(String dimension, EngineView view) {
        if (this.mode == XrayMaskPolicy.Mode.OFF) return null;
        boolean engineActiveForWorld = view instanceof EngineView.Active
                || view instanceof EngineView.Unreadable;
        boolean engineValuesUsable = view instanceof EngineView.Active;
        var decision = XrayMaskPolicy.decide(this.mode, engineActiveForWorld, engineValuesUsable);
        if (!decision.active()) return null;

        XrayMaskFilter.MaskSet mask;
        String source;
        if (decision.listSource() == XrayMaskPolicy.ListSource.ENGINE
                && view instanceof EngineView.Active engine) {
            // Deliberate twin ASYMMETRY vs Paper's empty-list fallback rung: obfuscateGlobal
            // is the engine's post-resolution ground truth, so an empty adoption means the
            // packets hide nothing and there is nothing to mirror — the mask stays inert
            // (diag tell: masked_sections=0 under an antixray-mod label). Paper's raw
            // config list is ambiguous when empty, so the design gives it the fail-safe.
            mask = XrayMaskFilter.MaskSet.fromStates(engine.hiddenStates(), engine.maxBlockHeight());
            source = "antixray-mod";
        } else {
            mask = fallbackMask();
            source = "config";
        }
        LSSLogger.info("LOD x-ray masking active for " + dimension + " (source=" + source
                + ", maxY=" + mask.maxBlockHeight() + ")");
        return new MaskEntry(mask, FallbackKind.fromDimension(dimension), source);
    }

    private XrayMaskFilter.MaskSet fallbackMask() {
        var resolved = this.fallbackMask;
        if (resolved == null) {
            synchronized (this) {
                resolved = this.fallbackMask;
                if (resolved == null) {
                    resolved = XrayMaskFilter.MaskSet.resolve(
                            this.config.xrayHiddenBlocks, this.config.xrayMaxBlockHeight);
                    this.fallbackMask = resolved;
                }
            }
        }
        return resolved;
    }
}
