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
    // Consecutive transient-null probe counts per dimension (see entryFor's retry window).
    private final ConcurrentHashMap<String, Integer> transientProbes = new ConcurrentHashMap<>();
    private final AtomicLong maskedSections = new AtomicLong();

    /** Transient-null probes tolerated per dimension before latching to the fallback —
     *  bounds the per-serve re-probing when the engine never becomes readable. */
    static final int TRANSIENT_PROBE_LATCH = 100;
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

    /** Store-snapshot rung (review B11): whether this level's mask outcome is TERMINAL
     *  (cached for the session). During the transient probe window {@link
     *  #entryForActive} serves an UNCACHED config fallback that a later serve may
     *  replace with the engine's mask — fingerprinting it into the store Environment
     *  either churns the dimension every boot (safe) or, on two transient boots in a
     *  row, KEEPS engine-masked rows under a config label (the R2-M1 leak class
     *  re-entered through probe transience). Callers snapshot a per-boot nonce when
     *  this returns false. Drives one probe first so an already-available engine
     *  resolves immediately. */
    public static boolean isTerminalForActive(ServerLevel level) {
        var manager = active;
        if (manager == null) return true; // no manager: "off" is a stable answer
        manager.entryFor(level);
        return manager.byDimension.containsKey(level.dimension().identifier().toString());
    }

    public MaskEntry entryFor(ServerLevel level) {
        return entryFor(level.dimension().identifier().toString(),
                () -> AntiXrayCompat.engineForLevel(level));
    }

    /**
     * The cache + decision core, level-free for Tier 1. A TERMINAL probe outcome caches
     * per dimension for the service lifetime; a TRANSIENT-null one (the engine exists but
     * is "not yet known to AntiXray" — the probe deliberately does not latch on it) is
     * served as the uncached fallback and RE-PROBED on the next serve, up to
     * {@link #TRANSIENT_PROBE_LATCH} attempts. Caching it (the pre-R2-7 behavior) locked
     * the dimension to the LSS-keys fallback for the whole session off one early null,
     * defeating the probe's own transient-null intent. Fail direction unchanged: masking
     * stays ON with some list throughout.
     */
    MaskEntry entryFor(String dimension, Supplier<EngineView> view) {
        var cached = this.byDimension.get(dimension);
        if (cached != null) return cached.orElse(null);
        if (this.mode == XrayMaskPolicy.Mode.OFF) {
            // OFF can never mask, so it needs no engine probe at all — cache the no-mask
            // outcome up front instead of paying the transient-null re-probe window (up
            // to TRANSIENT_PROBE_LATCH reflective probes) for a dimension whose answer
            // is fixed by config.
            return this.byDimension.computeIfAbsent(dimension, d -> Optional.empty())
                    .orElse(null);
        }
        EngineView v = view.get();
        if (v instanceof EngineView.Unreadable u && u.transientNull()
                && this.transientProbes.merge(dimension, 1, Integer::sum) < TRANSIENT_PROBE_LATCH) {
            // Quiet: this serves every read until the engine resolves — the "masking
            // active" info line would spam once per serve during the window.
            return evaluate(dimension, v, true);
        }
        return this.byDimension
                .computeIfAbsent(dimension, d -> {
                    if (v instanceof EngineView.Unreadable u && u.transientNull()) {
                        // The latch tripped: the engine never resolved. Say WHY the
                        // fallback caches (the info line below only names the source) —
                        // once, guaranteed by the computeIfAbsent mapping.
                        LSSLogger.warn("AntiXray engine stayed unreadable (transient) for "
                                + d + " after " + TRANSIENT_PROBE_LATCH + " probes — caching "
                                + "the " + dev.vox.lss.common.Brand.shortName() + "-config fallback mask for this session");
                    }
                    return Optional.ofNullable(evaluate(d, v, false));
                })
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

    private MaskEntry evaluate(String dimension, EngineView view, boolean quiet) {
        if (this.mode == XrayMaskPolicy.Mode.OFF) return null;
        boolean engineActiveForWorld = view instanceof EngineView.Active
                || view instanceof EngineView.Unreadable
                || view instanceof EngineView.ReplacementNoise;
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
        } else if (view instanceof EngineView.ReplacementNoise noise) {
            // Engine modes 2/3: the engine's list is the hidden ∪ replacement union (bulk
            // terrain) — flattening it destroyed LOD terrain (sculk/water section-flattening,
            // 2026-07-27). The LOD threat model is the ore-location oracle, so mask the LSS
            // config list, but at the ENGINE's cutoff so coverage height still mirrors the
            // server's anti-xray intent.
            mask = XrayMaskFilter.MaskSet.resolve(
                    this.config.xrayHiddenBlocks, noise.maxBlockHeight());
            source = "antixray-mod+lss-list";
            if (!quiet) {
                LSSLogger.info("AntiXray engine mode 2/3 detected for " + dimension
                        + " — its obfuscation list includes replacement terrain, so LOD masking "
                        + "uses the " + dev.vox.lss.common.Brand.shortName() + " xrayHiddenBlocks list at the engine's height instead");
            }
        } else {
            mask = fallbackMask();
            source = "config";
        }
        if (!quiet) {
            LSSLogger.info("LOD x-ray masking active for " + dimension + " (source=" + source
                    + ", maxY=" + mask.maxBlockHeight() + ")");
        }
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
