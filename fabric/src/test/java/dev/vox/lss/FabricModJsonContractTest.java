package dev.vox.lss;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Main-line contract for {@code fabric.mod.json} + {@code gradle.properties}, read from
 * the SOURCE tree (the fabric mirror of paper's
 * {@code PluginYmlContractTest.apiVersionMatchesTheDevBundleMinecraftVersion} lockstep pin).
 * The regression vector: the LOWER Minecraft bound is enforced by fabric-loader in every
 * gametest launch, but the UPPER bound is caught by nothing at build time — and the mixins
 * here are required:true over MC internals, so an unbounded range turns a future 26.3 into
 * a hard mixin-apply crash instead of Loader's clean refusal (the v0.8.0 compat review's
 * MAJOR). The support branches carry their own flavors of this test.
 */
class FabricModJsonContractTest {

    // ---- the line's expected constants (each branch carries its own values) ----
    // The trailing '-' makes the upper bound prerelease-EXCLUSIVE: Fabric semver sorts
    // 26.3-rc below 26.3, so a bare '<26.3' would admit 26.3 prereleases — where the required
    // mixins over MC internals hard-crash at apply (final compat review 2026-07-27).
    // Line-aware (single-branch consolidation): the active line's NAME (lss.line, default
    // 26.2) IS the MC-version prefix by construction (26.1 -> 26.1.2, 1.21.11 -> 1.21.11).
    private static final String EXPECTED_MINECRAFT_VERSION_PREFIX = System.getProperty("lss.line", "26.2");

    private static JsonObject modJson;
    private static Properties gradleProps;

    @BeforeAll
    static void load() throws Exception {
        modJson = JsonParser.parseString(
                Files.readString(locate("fabric/src/main/resources/fabric.mod.json"))).getAsJsonObject();
        gradleProps = new Properties();
        gradleProps.load(new StringReader(Files.readString(locate("gradle.properties"))));
        // Line-aware: overlay the active line's build inputs (single-branch consolidation —
        // lines/<line>/line.properties holds the line-varying keys; -Dlss.line names the line,
        // default 26.2). Loaded AFTER gradle.properties so the line's values win.
        gradleProps.load(new StringReader(Files.readString(
                locate("lines/" + System.getProperty("lss.line", "26.2") + "/line.properties"))));
    }

    /** Walks up from the working dir (fabric/ under Gradle, the repo root elsewhere). */
    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative + " above " + Path.of("").toAbsolutePath());
    }

    @Test
    void suggestsRangesAreTemplatedAndBackedByProperties() {
        // R2-6: the suggests ranges are gradle.properties data expanded at build time —
        // stale hand copies shipped on all three v0.11 ports. The source must carry the
        // placeholders and the properties must expand them non-empty.
        var suggests = modJson.getAsJsonObject("suggests");
        org.junit.jupiter.api.Assertions.assertEquals("${suggests_sodium}",
                suggests.get("sodium").getAsString(),
                "suggests.sodium must be the processResources placeholder");
        org.junit.jupiter.api.Assertions.assertEquals("${suggests_voxy}",
                suggests.get("voxy").getAsString(),
                "suggests.voxy must be the processResources placeholder");
        // Deliberately a literal "*", NOT a templated range: the Xaero bridge binds
        // reflectively and fails soft across Xaero versions (xaero-map-bridge-plan.md
        // §2.2) — a version range here would claim a compatibility pin we don't have.
        org.junit.jupiter.api.Assertions.assertEquals("*",
                suggests.get("xaeroworldmap").getAsString(),
                "suggests.xaeroworldmap must be the literal any-version wildcard");
        for (String key : new String[]{"suggests_sodium", "suggests_voxy",
                "sodium_version", "modmenu_version",
                "moonrise_modrinth_version", "c2me_modrinth_version"}) {
            String v = gradleProps.getProperty(key);
            org.junit.jupiter.api.Assertions.assertNotNull(v,
                    key + " missing from gradle.properties (R2-6 line data)");
            org.junit.jupiter.api.Assertions.assertFalse(v.isBlank(),
                    key + " must expand non-empty");
        }
    }

    @Test
    void dependsMinecraftPinsThisLineBothWays() {
        // V-1/P3: the SOURCE resource carries the template token — the actual range is
        // per-line DATA in gradle.properties (minecraft_dependency), pinned by FORM here
        // because it is NOT derivable (a copied range template on an exact-pin line ships
        // a jar that loads on wire-incompatible MC).
        assertEquals("${minecraft_dependency}",
                modJson.getAsJsonObject("depends").get("minecraft").getAsString(),
                "fabric.mod.json's minecraft depends must stay templated from the data key");
        // The exact range is non-derivable per-line DATA (line.properties minecraft_dependency);
        // the pin that survives the branch->line move is CONSISTENCY: the declared range must
        // reference THIS line's version (a copied range from another line — the exact bug this
        // guards — names the wrong version and reds here).
        String depends = gradleProps.getProperty("minecraft_dependency", "");
        assertTrue(depends.contains(EXPECTED_MINECRAFT_VERSION_PREFIX),
                "minecraft_dependency (" + depends + ") must reference the " + EXPECTED_MINECRAFT_VERSION_PREFIX
                        + " line — a range copied from another line ships a jar that loads on "
                        + "wire-incompatible MC");
        // Structural guard retained across the branch->line move: any UPPER bound must be
        // prerelease-EXCLUSIVE (end with '-'). A bare '<26.3' admits 26.3 prereleases, where
        // the mixins over MC internals hard-crash at apply. Exact-pin lines (no '<') skip this.
        if (depends.contains("<")) {
            assertTrue(depends.trim().endsWith("-"),
                    "minecraft_dependency (" + depends + ") has a '<' upper bound but is not "
                            + "prerelease-exclusive (must end with '-') — a bare bound ships a jar "
                            + "that loads on a wire-incompatible next-MC prerelease and hard-crashes");
        }
        // The G back-flow's sibling key: the fabric-api floor is per-line data too
        // (the 26.1 port found the literal floor naming a 26.2-family version no
        // 26.1 install can satisfy — silently unresolvable at mod load).
        assertEquals("${fabric_api_dependency}",
                modJson.getAsJsonObject("depends").get("fabric-api").getAsString(),
                "fabric.mod.json's fabric-api depends must stay templated from the data key");
        assertTrue(!gradleProps.getProperty("fabric_api_dependency", "").isEmpty(),
                "gradle.properties must carry the line's fabric_api_dependency floor");
    }

    @Test
    void gradlePropertiesTargetsTheSameLine() {
        String mc = gradleProps.getProperty("minecraft_version", "");
        // equals-or-dot: a bare prefix match would also accept e.g. 26.10, which the
        // depends range above would exclude — the two must move in lockstep.
        assertTrue(mc.equals(EXPECTED_MINECRAFT_VERSION_PREFIX)
                        || mc.startsWith(EXPECTED_MINECRAFT_VERSION_PREFIX + "."),
                "gradle.properties minecraft_version (" + mc + ") must stay on the "
                        + EXPECTED_MINECRAFT_VERSION_PREFIX + " line");
    }
}
