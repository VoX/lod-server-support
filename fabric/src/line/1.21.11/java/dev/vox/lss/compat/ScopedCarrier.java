// OVERLAY OF fabric/src/main/java/dev/vox/lss/compat/ScopedCarrier.java @ 86e90303229b5b4b0d657952acef404262cf4c98c63b4116ccd38c256ac51d05
// 1.21.11 line overlay (single-branch-consolidation-plan.md §3.2).
package dev.vox.lss.compat;

import java.util.function.Supplier;

/**
 * The 1.21.11 PASS-THROUGH variant of the ScopedCarrier (V-2/S5's whole-file swap —
 * see main's twin for the 26.x ScopedValue shim): Java 21 has no final ScopedValue,
 * and this line's AntiXray (1.4.14+1.21.11) threads its obfuscation context through
 * ThreadLocals its own mixins null-check, so LSS's serialize paths need no binding
 * shim here — {@code callSerializing} calls straight through. The crash floor on this
 * line is the probe-path containment plus the S2 constraint (never the section
 * deserialization ctor, whose AntiXray wrap is the one non-null-safe site).
 * Byte-identical twins in the fabric and neoforge trees (pinned).
 */
public final class ScopedCarrier {
    private ScopedCarrier() {}

    /** Straight pass-through on this line (see the class javadoc). */
    public static <T> T callSerializing(Supplier<T> body) {
        return body.get();
    }
}
