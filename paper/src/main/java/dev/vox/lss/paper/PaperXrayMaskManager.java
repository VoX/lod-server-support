package dev.vox.lss.paper;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.XrayMaskPolicy;
import dev.vox.lss.common.XrayMaskPolicy.FallbackKind;
import dev.vox.lss.common.config.ServerConfigBase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Per-world x-ray mask decisions — twin of the Fabric {@code XrayMaskManager}
 * (docs/planning/antixray-compat-design.md §3 Detection). The engine here is Paper's
 * built-in anti-xray, read per world through the compile-time-typed
 * {@code level.paperConfig().anticheat.antiXray} (enabled / hiddenBlocks / maxBlockHeight —
 * no reflection). Engine adoption follows "mask exactly what the packet engine masks";
 * the LSS config keys are the fallback tier, incl. when an enabled world's hidden list
 * resolves empty.
 *
 * <p>Logging bounds mirror the Fabric twin: one info line per ACTIVE world, silence on the
 * common inactive path, fallback list resolved (and its warnings emitted) at most once.
 * Folia-safe: evaluation is a read of the immutable world config, cached in a concurrent
 * map, callable from any serializing thread.
 */
final class PaperXrayMaskManager {

    /** An active world's resolved mask inputs; absence (null) = serve unmasked. */
    record MaskEntry(PaperXrayMaskFilter.MaskSet mask, FallbackKind kind, String sourceLabel) {}

    /** Paper's per-world anti-xray inputs, extracted so Tier 1 can inject them level-free.
     *  DELIBERATELY hidden-list-only: engine modes 2/3 also obfuscate the replacement list
     *  (stone/deepslate/oak_planks by default), but adopting that union masked essentially
     *  every underground section — needsMasking's palette fast-path went constant-true, the
     *  per-serve rebuild cost exploded, and chooseReplacement's fallback ladder could pick a
     *  state that was itself in the mask (an all-stone+ore section has no non-hidden
     *  candidate), flattening underground LOD to a single block (final review 2026-07-27).
     *  LOD masking's threat model is the ore-presence oracle; the hidden list is the mask. */
    record EngineConfig(boolean enabled, List<Block> hiddenBlocks, int maxBlockHeight) {}

    private static volatile PaperXrayMaskManager active;

    private final XrayMaskPolicy.Mode mode;
    private final ServerConfigBase config;
    private final ConcurrentHashMap<String, Optional<MaskEntry>> byDimension = new ConcurrentHashMap<>();
    private final AtomicLong maskedSections = new AtomicLong();
    private volatile PaperXrayMaskFilter.MaskSet fallbackMask;

    PaperXrayMaskManager(ServerConfigBase config) {
        this.mode = XrayMaskPolicy.Mode.parse(config.xrayObfuscation);
        this.config = config;
    }

    /** Publishes a fresh manager for the starting service (config is read once here). */
    static PaperXrayMaskManager activate(ServerConfigBase config) {
        var manager = new PaperXrayMaskManager(config);
        active = manager;
        return manager;
    }

    /** Guarded retract: clears the holder only when {@code owner} is still the published
     *  manager, so a stale shutdown (or a test-wired service that never published) cannot
     *  null out a successor service's masking. */
    static void deactivate(PaperXrayMaskManager owner) {
        if (owner != null && active == owner) {
            active = null;
        }
    }

    /** The serializer hook: the active world entry, or null when nothing masks. */
    static MaskEntry entryForActive(ServerLevel level) {
        var manager = active;
        return manager == null ? null : manager.entryFor(level);
    }

    static PaperXrayMaskManager current() {
        return active;
    }

    MaskEntry entryFor(ServerLevel level) {
        return entryFor(level.dimension().location().toString(), () -> {
            try {
                var antiXray = level.paperConfig().anticheat.antiXray;
                return new EngineConfig(antiXray.enabled, antiXray.hiddenBlocks, antiXray.maxBlockHeight);
            } catch (Throwable t) {
                // Paper-internal config shape (not API) — a future build renaming any of it
                // would otherwise throw LinkageError INTO the pump tick (the probe path has
                // no Fabric-style serialize containment). Degrade exactly like Fabric's
                // UNREADABLE probe: pretend "engine on, list unreadable", which evaluate()
                // routes to the LSS config-key mask — fail-safe, masking stays ON.
                LSSLogger.warn("Paper anti-xray config unreadable for "
                        + level.dimension().location() + " — LOD masking falls back to the "
                        + dev.vox.lss.common.Brand.shortName() + " xrayHiddenBlocks config keys", t);
                return new EngineConfig(true, List.of(), 0);
            }
        });
    }

    /** The cache + decision core, level-free for Tier 1 ({@code view} runs at most once per
     *  dimension). Unlike the Fabric twin (R2-7's transient-null re-probe window), caching
     *  every outcome permanently is CORRECT here: Paper's engine view is a direct
     *  {@code paperConfig()} read with no not-yet-initialized rung — every outcome is
     *  terminal for the service lifetime. */
    MaskEntry entryFor(String dimension, Supplier<EngineConfig> view) {
        return this.byDimension
                .computeIfAbsent(dimension, d -> Optional.ofNullable(evaluate(d, view.get())))
                .orElse(null);
    }

    /** Counted by the serializer hooks; surfaced by the {@code /lsslod diag} xray line. */
    void countMaskedSection() {
        this.maskedSections.incrementAndGet();
    }

    /** Twin of the Fabric diagLine — engine label {@code paper-config}. */
    String diagLine() {
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

    private MaskEntry evaluate(String dimension, EngineConfig view) {
        if (this.mode == XrayMaskPolicy.Mode.OFF) return null;
        boolean engineActiveForWorld = view != null && view.enabled();
        PaperXrayMaskFilter.MaskSet engineMask = null;
        if (engineActiveForWorld) {
            var adopted = view.hiddenBlocks();
            // Paper's engine rounds the configured max-block-height DOWN to a section
            // boundary ((h >> 4) << 4) in its controller ctor; adopting the raw value made
            // LOD mask up to 15 blocks the near view shows real (review 2026-07-27).
            int effectiveHeight = (view.maxBlockHeight() >> 4) << 4;
            engineMask = PaperXrayMaskFilter.MaskSet.fromStates(
                    expandToStates(adopted), effectiveHeight);
            if (engineMask.isEmpty()) {
                // An enabled world whose hidden list resolves to nothing — adopt-nothing
                // would silently leak, so fall back to the LSS keys (design §3 Detection).
                LSSLogger.warn("Paper anti-xray is enabled for " + dimension + " but its "
                        + "hidden-blocks list resolved empty — LOD masking falls back to "
                        + "the " + dev.vox.lss.common.Brand.shortName() + " xrayHiddenBlocks config key for this world");
                engineMask = null;
            }
        }
        var decision = XrayMaskPolicy.decide(this.mode, engineActiveForWorld, engineMask != null);
        if (!decision.active()) return null;

        PaperXrayMaskFilter.MaskSet mask;
        String source;
        if (decision.listSource() == XrayMaskPolicy.ListSource.ENGINE && engineMask != null) {
            mask = engineMask;
            source = "paper-config";
        } else {
            mask = fallbackMask();
            source = "config";
        }
        LSSLogger.info("LOD x-ray masking active for " + dimension + " (source=" + source
                + ", maxY=" + mask.maxBlockHeight() + ")");
        return new MaskEntry(mask, FallbackKind.fromDimension(dimension), source);
    }

    private static List<BlockState> expandToStates(List<Block> blocks) {
        if (blocks == null) return List.of();
        var states = new ArrayList<BlockState>();
        for (Block block : blocks) {
            if (block == null) continue;
            states.addAll(block.getStateDefinition().getPossibleStates());
        }
        return states;
    }

    private PaperXrayMaskFilter.MaskSet fallbackMask() {
        var resolved = this.fallbackMask;
        if (resolved == null) {
            synchronized (this) {
                resolved = this.fallbackMask;
                if (resolved == null) {
                    resolved = PaperXrayMaskFilter.MaskSet.resolve(
                            this.config.xrayHiddenBlocks, this.config.xrayMaxBlockHeight);
                    this.fallbackMask = resolved;
                }
            }
        }
        return resolved;
    }
}
