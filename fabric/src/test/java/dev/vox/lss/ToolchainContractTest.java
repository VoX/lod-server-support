package dev.vox.lss;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the line's EFFECTIVE toolchain against the artifacts the build ACTUALLY produced —
 * hoisted to main by V-1/T3c (version-port-isolation-plan.md) from the 1.21.11 line,
 * which invented it after a real bite: {@code lss.mixins.json} carried {@code JAVA_25},
 * parsed fine locally on a 25 JDK, and failed only on the Java-21 CI runner at loader
 * boot. On main the same shape guards the other direction (a support-line merge dragging
 * a 21 target onto the 25 line), and it carries the P2 armor chain's ARTIFACT ANCHOR:
 * {@code .github/line.env} ↔ {@code gradle.properties} ↔ the resolved MC artifact —
 * each link independently red-able, so no forward merge can make the release pipeline
 * green-but-wrong by clobbering the data files consistently.
 */
class ToolchainContractTest {

    private static Properties lineEnv;
    private static Properties gradleProps;

    @BeforeAll
    static void load() throws Exception {
        // Line-aware (single-branch consolidation): -Dlss.line names the active line
        // (default 26.2); its identity lives in lines/<line>/line.env and its build inputs
        // in lines/<line>/line.properties (overlaid onto gradle.properties' fleet-invariants).
        String line = System.getProperty("lss.line", "26.2");
        lineEnv = loadProps(locate("lines/" + line + "/line.env"));
        gradleProps = loadProps(locate("gradle.properties"));
        gradleProps.putAll(loadProps(locate("lines/" + line + "/line.properties")));
        SharedConstants.tryDetectVersion();
    }

    private static Properties loadProps(Path path) throws Exception {
        var props = new Properties();
        for (String line : Files.readAllLines(path)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            if (eq > 0) props.setProperty(s.substring(0, eq), s.substring(eq + 1));
        }
        return props;
    }

    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative);
    }

    static int classFileMajor(Class<?> anchor, String resource) throws Exception {
        try (InputStream in = anchor.getResourceAsStream(resource)) {
            assertNotNull(in, "class bytes not found for " + resource);
            byte[] header = in.readNBytes(8);
            // magic(4) minor(2) major(2), big-endian
            return ((header[6] & 0xff) << 8) | (header[7] & 0xff);
        }
    }

    @Test
    void compiledClassFileTargetsTheLineJavaVersion() throws Exception {
        // Branch-invariant: the expected major derives from line.env (Java N = major - 44),
        // so a support cut edits the data file and this test follows.
        int expectedMajor = 44 + Integer.parseInt(lineEnv.getProperty("LINE_JAVA_VERSION"));
        assertEquals(expectedMajor, classFileMajor(LSSMod.class, "/dev/vox/lss/LSSMod.class"),
                "fabric must compile at --release " + lineEnv.getProperty("LINE_JAVA_VERSION")
                        + " (line.env LINE_JAVA_VERSION; Java N = class major - 44) — a "
                        + "wrong target passes locally on a newer JDK and breaks CI/servers");
    }

    @Test
    void mixinCompatibilityLevelMatchesTheCompiledTarget() throws Exception {
        // ALL THREE mixin configs — the tracer's non-required lss-trace.mixins.json and the
        // legacy-Sodium options hook's lss-sodium-legacy.mixins.json regress just as
        // silently on a forward merge as the required one (per-version-surfaces row 10).
        int major = classFileMajor(LSSMod.class, "/dev/vox/lss/LSSMod.class");
        for (String config : new String[] {"/lss.mixins.json", "/lss-trace.mixins.json",
                "/lss-sodium-legacy.mixins.json"}) {
            JsonObject mixins;
            try (InputStream in = LSSMod.class.getResourceAsStream(config)) {
                assertNotNull(in, config + " not on the classpath");
                mixins = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            assertEquals("JAVA_" + (major - 44), mixins.get("compatibilityLevel").getAsString(),
                    config + " compatibilityLevel must match the compiled class target");
        }
    }

    @Test
    void resolvedMinecraftArtifactMatchesTheDeclaredLine() {
        // The P2 armor chain's third link, HONESTLY SCOPED (round-3 review): fabric's
        // MC dep resolves FROM gradle.properties, so this twin is tautological under a
        // both-files clobber — it pins only that the classpath agrees with the data the
        // build consumed. The GENUINE tree-fact anchor for the double-clobber is the
        // PAPER twin (the dev bundle is a per-line literal in paper/build.gradle) plus
        // FabricModJsonContractTest's per-line constants — recorded in the port
        // runbook's gate-host note. The double-clobber's fabric-side backstop is the
        // compile itself (wrong-MC source rarely compiles).
        String declared = gradleProps.getProperty("minecraft_version");
        assertEquals(declared, SharedConstants.getCurrentVersion().name(),
                "gradle.properties minecraft_version must equal the resolved MC artifact's "
                        + "version — the line.env↔gradle.properties↔artifact chain's last link");
        String line = lineEnv.getProperty("LINE_MC_FABRIC");
        assertTrue(declared.equals(line) || declared.startsWith(line + "."),
                "line.env's LINE_MC_FABRIC must equal or dot-prefix the resolved MC version "
                        + "(bare prefix admits the 1.21.1↔1.21.11 trap)");
    }
}
