package dev.vox.lss.common;

import dev.vox.lss.common.config.JsonConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Pins the brand-driven config-filename ORDER: the running brand's own file first, the other
 * brand's file as a fallback. This is the wiring that makes a VSS jar prefer {@code vss-*-config.json}
 * while still adopting an existing {@code lss-*-config.json} on a jar swap (the load/adopt mechanism
 * that consumes this order is pinned separately in {@code dev.vox.lss.config.JsonConfigLoadTest}).
 *
 * <p>Lives in package {@code dev.vox.lss.common} for access to the package-private {@link Brand#apply}
 * test seam, and reaches the {@code protected static} resolver through a {@link JsonConfig} subclass.
 */
class ConfigBrandCandidatesTest {

    /** Minimal JsonConfig subclass: only there to expose the protected static candidate resolver. */
    static final class Probe extends JsonConfig {
        @Override protected String getFileName() { return "unused.json"; }
        static String[] candidates(String kind) { return brandedConfigCandidates(kind); }
    }

    @AfterEach
    void resetBrand() {
        // Restore the default (LSS) so brand state never leaks into another test in this JVM.
        Brand.apply("LSS", "LOD Server Support", "lss", "lsslod");
    }

    @Test
    void lssBrandPrefersItsOwnFileThenFallsBackToVss() {
        Brand.apply("LSS", "LOD Server Support", "lss", "lsslod");
        assertArrayEquals(
                new String[]{"lss-client-config.json", "vss-client-config.json"},
                Probe.candidates("client"));
    }

    @Test
    void vssBrandPrefersItsOwnFileThenFallsBackToLss() {
        Brand.apply("VSS", "Voxy Server Side", "vss", "vsslod");
        assertArrayEquals(
                new String[]{"vss-client-config.json", "lss-client-config.json"},
                Probe.candidates("client"));
    }

    @Test
    void kindIsInterpolatedIntoBothCandidates() {
        // Exercises the helper's generic {kind} interpolation only. NOTE: no config actually loads
        // the "server" kind through this helper — ServerConfigBase is deliberately brand-invariant
        // (lss-server-config.json on both brands). This asserts the string-building contract, not
        // that server config uses the fallback.
        Brand.apply("VSS", "Voxy Server Side", "vss", "vsslod");
        assertArrayEquals(
                new String[]{"vss-server-config.json", "lss-server-config.json"},
                Probe.candidates("server"));
    }

    @Test
    void unknownBrandFallsBackToLssPrimaryOrdering() {
        // Brand defaults/normalizes anything non-VSS to the LSS ordering — a garbled brand token
        // must not strand a real install on a file it will never create.
        Brand.apply("LSS", "LOD Server Support", "lss", "lsslod");
        assertArrayEquals(
                new String[]{"lss-client-config.json", "vss-client-config.json"},
                Probe.candidates("client"));
    }
}
