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

    /**
     * The recorded fleet VALUE table (plan §4(b)). Every conscious-flip literal is pinned per
     * line here — a flip anywhere (e.g. {@code fold_status: full→build-only} on 26.2, which
     * would silently drop it from the CI gate matrix AND the release) reds HERE, strictly
     * STRONGER than the per-branch model. A NEW line dir with no row also reds (forcing a
     * conscious registration). Columns: fold_status | line_java_version | mapping_namespace |
     * tier3_client_gametests | has_modern_sodium | LINE_SHIP_NEOFORGE | LINE_MAKE_LATEST.
     */
    private static final java.util.Map<String, String[]> FLEET = java.util.Map.of(
            "26.2",    new String[] {"full",       "25", "official",     "true",  "true",  "true",  "true"},
            "26.1",    new String[] {"full",       "25", "official",     "true",  "true",  "true",  "false"},
            "1.21.11", new String[] {"build-only", "21", "intermediary", "true",  "true",  "false", "false"});

    @Test
    void everyLineValueMatchesTheRecordedFleetTable() throws Exception {
        for (String line : lineNames()) {
            String[] want = FLEET.get(line);
            assertTrue(want != null, "lines/" + line + " has no row in LineMatrixContractTest.FLEET — "
                    + "a new line must be REGISTERED here (its conscious-flip values recorded) before it builds");
            var p = props(line);
            var e = env(line);
            assertEquals(want[0], p.getProperty("fold_status"), line + " fold_status");
            assertEquals(want[1], p.getProperty("line_java_version"), line + " line_java_version");
            assertEquals(want[2], p.getProperty("mapping_namespace"), line + " mapping_namespace");
            assertEquals(want[3], p.getProperty("tier3_client_gametests"), line + " tier3_client_gametests");
            assertEquals(want[4], p.getProperty("has_modern_sodium"), line + " has_modern_sodium");
            assertEquals(want[5], e.getProperty("LINE_SHIP_NEOFORGE"), line + " LINE_SHIP_NEOFORGE");
            assertEquals(want[6], e.getProperty("LINE_MAKE_LATEST"), line + " LINE_MAKE_LATEST");
        }
        // The table must not name a line that no longer exists (a fold/decommission left it stale).
        for (String tabled : FLEET.keySet()) {
            assertTrue(lineNames().contains(tabled),
                    "LineMatrixContractTest.FLEET names line '" + tabled + "' with no lines/ dir — remove the stale row");
        }
    }

    @Test
    void neoforgeModrinthVersionNameStaysUnderTheLabrinthCap() throws Exception {
        // release.yml composes the NeoForge Modrinth version name as `v<MOD_VERSION> - <name>`
        // from LINE_NEOFORGE_NAME; labrinth 400s MID-PUBLISH on a >64-char name. Check the
        // RESOLVED length over a generous version placeholder for every ship_neoforge line.
        for (String line : lineNames()) {
            var e = env(line);
            if (!"true".equals(e.getProperty("LINE_SHIP_NEOFORGE"))) continue;
            String name = "v99.99.99 - " + e.getProperty("LINE_NEOFORGE_NAME", "");
            assertTrue(name.length() <= 64,
                    "lines/" + line + " NeoForge Modrinth version name resolves to " + name.length()
                            + " chars (>64 labrinth cap → 400 mid-publish): '" + name + "'");
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
