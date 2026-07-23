package dev.vox.lss.common;

import dev.vox.lss.common.XrayMaskPolicy.Decision;
import dev.vox.lss.common.XrayMaskPolicy.FallbackKind;
import dev.vox.lss.common.XrayMaskPolicy.ListSource;
import dev.vox.lss.common.XrayMaskPolicy.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the x-ray masking activation ladder (docs/planning/antixray-compat-design.md §3):
 * OFF is an unconditional kill switch, ON masks every world (adopting engine values where
 * they exist), AUTO follows the detected engine's per-world enablement — with the LSS
 * config keys as the fallback list whenever engine values are unusable ("mask exactly what
 * the packet engine masks" only works while the engine's values are readable).
 */
class XrayMaskPolicyTest {

    // --- Mode parse / normalization ---

    @Test
    void parseIsCaseAndWhitespaceInsensitive() {
        assertEquals(Mode.ON, Mode.parse("on"));
        assertEquals(Mode.ON, Mode.parse(" ON "));
        assertEquals(Mode.OFF, Mode.parse("Off"));
        assertEquals(Mode.AUTO, Mode.parse("auto"));
    }

    @Test
    void unknownAndNullParseAsAuto() {
        assertEquals(Mode.AUTO, Mode.parse(null));
        assertEquals(Mode.AUTO, Mode.parse(""));
        assertEquals(Mode.AUTO, Mode.parse("enabled"));
        assertEquals(Mode.AUTO, Mode.parse("true"));
    }

    @Test
    void normalizeModeYieldsCanonicalSpelling() {
        assertEquals("on", XrayMaskPolicy.normalizeMode(" ON"));
        assertEquals("off", XrayMaskPolicy.normalizeMode("OFF "));
        assertEquals("auto", XrayMaskPolicy.normalizeMode("bogus"));
        assertEquals("auto", XrayMaskPolicy.normalizeMode(null));
    }

    // --- Decision ladder ---

    @Test
    void offIsAnUnconditionalKillSwitch() {
        for (boolean engine : new boolean[]{false, true}) {
            for (boolean usable : new boolean[]{false, true}) {
                assertEquals(Decision.INACTIVE, XrayMaskPolicy.decide(Mode.OFF, engine, usable),
                        "OFF must stay inactive (engine=" + engine + ", usable=" + usable + ")");
            }
        }
    }

    @Test
    void onMasksEveryWorldAndAdoptsEngineValuesWhereTheyExist() {
        assertEquals(new Decision(true, ListSource.ENGINE),
                XrayMaskPolicy.decide(Mode.ON, true, true));
        // Engine present but its values unreadable (or the world is engine-disabled, so it
        // HAS no values): the LSS keys carry the forced mask.
        assertEquals(new Decision(true, ListSource.LSS_CONFIG),
                XrayMaskPolicy.decide(Mode.ON, true, false));
        assertEquals(new Decision(true, ListSource.LSS_CONFIG),
                XrayMaskPolicy.decide(Mode.ON, false, false));
        // Usable-without-active is a caller contract violation shape; the ladder must still
        // be total and fall to the config keys, not adopt values from an inactive engine.
        assertEquals(new Decision(true, ListSource.LSS_CONFIG),
                XrayMaskPolicy.decide(Mode.ON, false, true));
    }

    @Test
    void autoFollowsEngineDetectionPerWorld() {
        assertEquals(new Decision(true, ListSource.ENGINE),
                XrayMaskPolicy.decide(Mode.AUTO, true, true));
        // The Fabric fallback ladder: mod detected but reflectively unreadable — mask with
        // the LSS keys rather than silently leaking.
        assertEquals(new Decision(true, ListSource.LSS_CONFIG),
                XrayMaskPolicy.decide(Mode.AUTO, true, false));
        assertEquals(Decision.INACTIVE, XrayMaskPolicy.decide(Mode.AUTO, false, false));
        assertEquals(Decision.INACTIVE, XrayMaskPolicy.decide(Mode.AUTO, false, true));
    }

    // --- FallbackKind dimension heuristic ---

    @Test
    void fallbackKindFromDimensionString() {
        assertEquals(FallbackKind.NETHER, FallbackKind.fromDimension("minecraft:the_nether"));
        assertEquals(FallbackKind.END, FallbackKind.fromDimension("minecraft:the_end"));
        assertEquals(FallbackKind.OVERWORLD, FallbackKind.fromDimension("minecraft:overworld"));
        assertEquals(FallbackKind.OVERWORLD, FallbackKind.fromDimension(null));
        // Documented heuristic behavior for modded dimensions: substring match, nether wins.
        assertEquals(FallbackKind.NETHER, FallbackKind.fromDimension("mymod:nether_mirror"));
        assertEquals(FallbackKind.END, FallbackKind.fromDimension("mymod:endless_sky"));
    }
}
