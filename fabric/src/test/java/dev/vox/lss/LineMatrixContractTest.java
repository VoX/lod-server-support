package dev.vox.lss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * FLEET-WIDE line-data contract (single-branch-consolidation-plan.md §3.1/§4). Reads ALL
 * {@code lines/<line>/} at once — a value flip anywhere reds here, strictly STRONGER than
 * the per-branch model (where a flip stayed invisible until that branch built). Three jobs:
 *
 * <ul>
 *   <li><b>Key-set totality</b>: every {@code lines/*}/line.properties (and line.env) carries
 *       the SAME key set — key-ABSENCE silently flips a dependency/task/test arm, so absence
 *       is banned; a new line dir with a missing key reds instead of mis-building.</li>
 *   <li><b>Default-line mirror</b>: {@code lines/26.2} equals the values the production line's
 *       {@code gradle.properties} / {@code .github/line.env} still carry (the default line keeps
 *       its gradle.properties values for byte-identity; this pins the two copies together).</li>
 *   <li><b>VALUE pins</b> (the conscious-flip literals): {@code LINE_SHIP_NEOFORGE},
 *       {@code LINE_JAVA_VERSION}/line_java_version, {@code LINE_FABRIC_MAPPING_NAMESPACE}/
 *       mapping_namespace, tier3, has_modern_sodium — asserted per line against the recorded
 *       fleet table, so a stray flip cannot pass unnoticed.</li>
 * </ul>
 */
class LineMatrixContractTest {

    private static Path linesDir() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("lines");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate lines/ above " + Path.of("").toAbsolutePath());
    }

    private static Path repoFile(String rel) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(rel);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + rel);
    }

    private static List<String> lineNames() throws Exception {
        try (var s = Files.list(linesDir())) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .sorted().collect(Collectors.toList());
        }
    }

    private static Properties props(String line) throws Exception {
        var p = new Properties();
        p.load(new StringReader(Files.readString(linesDir().resolve(line).resolve("line.properties"))));
        return p;
    }

    private static Properties env(String line) throws Exception {
        var p = new Properties();
        for (String l : Files.readAllLines(linesDir().resolve(line).resolve("line.env"))) {
            String s = l.strip();
            if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) continue;
            int eq = s.indexOf('=');
            p.setProperty(s.substring(0, eq), s.substring(eq + 1));
        }
        return p;
    }

    @Test
    void everyLineHasTheSamePropertiesKeySet() throws Exception {
        List<String> lines = lineNames();
        assertTrue(lines.contains("26.2"), "the default line dir must exist");
        Set<Object> reference = new TreeSet<>(props("26.2").keySet());
        for (String line : lines) {
            assertEquals(reference, new TreeSet<>(props(line).keySet()),
                    "lines/" + line + "/line.properties key set must equal lines/26.2's — "
                            + "key ABSENCE silently flips a build arm (plan §3.1)");
        }
    }

    @Test
    void everyLineHasTheSameLineEnvKeySet() throws Exception {
        List<String> lines = lineNames();
        Set<Object> reference = new TreeSet<>(env("26.2").keySet());
        for (String line : lines) {
            assertEquals(reference, new TreeSet<>(env(line).keySet()),
                    "lines/" + line + "/line.env key set must equal lines/26.2's");
        }
    }

    @Test
    void mappingNamespaceIsRequiredAndConsistentAcrossPropsAndEnv() throws Exception {
        for (String line : lineNames()) {
            String ns = props(line).getProperty("mapping_namespace");
            assertTrue("official".equals(ns) || "intermediary".equals(ns),
                    "lines/" + line + " mapping_namespace must be official|intermediary, was " + ns);
            assertEquals(ns, env(line).getProperty("LINE_FABRIC_MAPPING_NAMESPACE"),
                    "line.properties mapping_namespace must equal line.env LINE_FABRIC_MAPPING_NAMESPACE for " + line);
        }
    }

    @Test
    void javaVersionAgreesBetweenPropsAndEnv() throws Exception {
        for (String line : lineNames()) {
            assertEquals(env(line).getProperty("LINE_JAVA_VERSION"),
                    props(line).getProperty("line_java_version"),
                    "line_java_version must equal line.env LINE_JAVA_VERSION for " + line);
        }
    }

    @Test
    void booleanValuePinsAreExplicitTrueOrFalse() throws Exception {
        for (String line : lineNames()) {
            var p = props(line);
            for (String key : List.of("has_modern_sodium", "tier3_client_gametests", "fml4_devrun_fold")) {
                String v = p.getProperty(key);
                assertTrue("true".equals(v) || "false".equals(v),
                        "lines/" + line + " " + key + " must be an EXPLICIT true|false (never absent), was " + v);
            }
            assertTrue(Set.of("tests", "namespaces").contains(p.getProperty("fml_gametest_filter")),
                    "lines/" + line + " fml_gametest_filter must be tests|namespaces");
            assertTrue(Set.of("full", "build-only").contains(p.getProperty("fold_status")),
                    "lines/" + line + " fold_status must be full|build-only (gates whether the "
                            + "CI matrix runs the release gate suite or just compiles the line)");
            String ship = env(line).getProperty("LINE_SHIP_NEOFORGE");
            assertTrue("true".equals(ship) || "false".equals(ship),
                    "lines/" + line + " LINE_SHIP_NEOFORGE must be explicit true|false");
        }
    }

    @Test
    void defaultLineMirrorsGradleProperties() throws Exception {
        var gp = new Properties();
        gp.load(new StringReader(Files.readString(repoFile("gradle.properties"))));
        var line = props("26.2");
        // Every line-varying key in lines/26.2 that ALSO appears in gradle.properties must match
        // (the default line keeps its gradle.properties values for byte-identity — plan §3.1).
        List<String> mismatches = new ArrayList<>();
        for (String key : line.stringPropertyNames()) {
            String g = gp.getProperty(key);
            if (g != null && !g.equals(line.getProperty(key))) {
                mismatches.add(key + ": gradle.properties=" + g + " vs lines/26.2=" + line.getProperty(key));
            }
        }
        assertTrue(mismatches.isEmpty(),
                "lines/26.2/line.properties must mirror gradle.properties: " + mismatches);
    }

    @Test
    void defaultLineEnvMirrorsGithubLineEnv() throws Exception {
        var github = env0(repoFile(".github/line.env"));
        var line = env("26.2");
        assertEquals(new TreeSet<>(github.keySet()), new TreeSet<>(line.keySet()),
                "lines/26.2/line.env key set must equal .github/line.env's");
        for (String key : github.stringPropertyNames()) {
            assertEquals(github.getProperty(key), line.getProperty(key),
                    "lines/26.2/line.env " + key + " must mirror .github/line.env");
        }
    }

    private static Properties env0(Path path) throws Exception {
        var p = new Properties();
        for (String l : Files.readAllLines(path)) {
            String s = l.strip();
            if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) continue;
            int eq = s.indexOf('=');
            p.setProperty(s.substring(0, eq), s.substring(eq + 1));
        }
        return p;
    }
}
