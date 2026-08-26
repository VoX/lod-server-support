package dev.vox.lss.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;

/**
 * Pure decision ladder for LOD x-ray masking (docs/planning/antixray-compat-design.md §3),
 * shared by both platforms so activation/list-source policy cannot drift — the
 * {@link HandshakeGate} pattern. The governing principle: <b>mask exactly what the packet
 * engine masks</b> — in auto mode, values adopted from the detected engine take precedence
 * and the LSS config keys are the fallback tier.
 *
 * <p>Platform detection (what "engine" means and whether its values resolved) stays in the
 * platform wiring; this class only turns those booleans plus the config tri-state into an
 * activation + list-source decision, evaluated per world.
 */
public final class XrayMaskPolicy {
    private XrayMaskPolicy() {}

    /** The {@code xrayObfuscation} config tri-state. Unknown/missing values parse as AUTO. */
    public enum Mode {
        AUTO, ON, OFF;

        public static Mode parse(String value) {
            if (value == null) return AUTO;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "on" -> ON;
                case "off" -> OFF;
                default -> AUTO;
            };
        }

        /** The canonical config spelling — what {@code validate()} writes back. */
        public String configValue() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    /** Where the effective hidden list + max-block-height come from. */
    public enum ListSource { NONE, LSS_CONFIG, ENGINE }

    public record Decision(boolean active, ListSource listSource) {
        public static final Decision INACTIVE = new Decision(false, ListSource.NONE);
    }

    /** Normalizes a raw config string to its canonical spelling (unknown → "auto"). */
    public static String normalizeMode(String raw) {
        return Mode.parse(raw).configValue();
    }

    /**
     * The per-world ladder.
     *
     * @param engineActiveForWorld the detected anti-xray engine obfuscates THIS world
     *        (Paper: per-world config {@code enabled}; Fabric: controller is not the
     *        Disabled one — and when the controller cannot be resolved at all, the wiring
     *        passes true for every world of a detected mod, per the fallback ladder)
     * @param engineValuesUsable the engine's hidden list + max-block-height resolved for
     *        this world — only meaningful with an active engine; callers pass false when
     *        the engine is absent, disabled for the world, or reflectively unreadable
     */
    public static Decision decide(Mode mode, boolean engineActiveForWorld, boolean engineValuesUsable) {
        return switch (mode) {
            case OFF -> Decision.INACTIVE;
            // ON masks every world regardless of engine state (the admin's explicit ask);
            // it still adopts the engine's values where they exist, same principle.
            case ON -> new Decision(true,
                    engineActiveForWorld && engineValuesUsable ? ListSource.ENGINE : ListSource.LSS_CONFIG);
            case AUTO -> engineActiveForWorld
                    ? new Decision(true, engineValuesUsable ? ListSource.ENGINE : ListSource.LSS_CONFIG)
                    : Decision.INACTIVE;
        };
    }

    /**
     * Replacement-fallback flavor when a masked section has no dominant non-hidden state
     * to adopt — dimension-string heuristic (cosmetic only: it picks the filler block for
     * fully-hidden sections, never affects WHAT is hidden).
     */
    public enum FallbackKind {
        OVERWORLD, NETHER, END;

        public static FallbackKind fromDimension(String dimension) {
            if (dimension == null) return OVERWORLD;
            String d = dimension.toLowerCase(Locale.ROOT);
            if (d.contains("nether")) return NETHER;
            if (d.contains("end")) return END;
            return OVERWORLD;
        }
    }

    /**
     * Permutation-stable fingerprint of the mask SEMANTICS (v0.13.1 —
     * store-registry-permutation-plan.md §3.5), shared by both platforms' MaskSet
     * twins as the LOD store's per-dimension staleness key: FNV-1a over the SORTED,
     * DEDUPED hidden-state identity strings, then the cutoff height. The old hash
     * walked the boolean-per-global-state-id array, so a registry-order permutation
     * (VisualWorkbench-class per-boot dynamic registration) flipped every masked
     * dimension's fingerprint each boot and the freshness sweep dropped its rows —
     * defeating the meta-level permutation tolerance. Identity strings (BlockState
     * toString — the registry fingerprint's own convention) survive a permutation;
     * any REAL semantic change (states added/removed/renamed, cutoff moved) still
     * flips the value. NOTE the value differs from the old array hash for every
     * non-empty mask: masked servers pay a one-time per-dimension re-warm at the
     * first boot on this version.
     */
    public static long maskContentFingerprint(Collection<String> hiddenStateIdentities,
                                              int maxBlockHeight) {
        // Null elements are skipped rather than thrown on (fix-review fold): MaskSet
        // construction happens lazily inside serve choke points where throw-freedom
        // is load-bearing, and this is a public common API.
        var sorted = new ArrayList<String>(hiddenStateIdentities.size());
        for (String s : hiddenStateIdentities) {
            if (s != null) sorted.add(s);
        }
        Collections.sort(sorted);
        long hash = 0xcbf29ce484222325L;
        String prev = null;
        for (String s : sorted) {
            if (s.equals(prev)) continue; // dedupe: a doubled config entry is the same mask
            prev = s;
            for (int i = 0; i < s.length(); i++) {
                hash ^= s.charAt(i);
                hash *= 0x100000001b3L;
            }
            hash ^= ':';
            hash *= 0x100000001b3L;
        }
        hash ^= maxBlockHeight;
        hash *= 0x100000001b3L;
        return hash;
    }
}
