package dev.vox.lss.paper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for the workflow files ({@code .github/workflows/}) + the release-line
 * data file ({@code .github/line.env}), read from the source tree like
 * {@link PluginYmlContractTest}.
 *
 * <p><b>V-1/P2 (version-port-isolation-plan.md):</b> release.yml is BRANCH-INVARIANT —
 * every per-line value comes from {@code line.env} — and THIS TEST is branch-invariant
 * with it: expectations derive from the same data file, switched on {@code IS_MAINLINE}
 * (an empty {@code LINE_TAG_SUFFIX}). The anti-merge armor that used to live in
 * duplicated per-line constants is now a three-link chain, each link independently
 * red-able: line.env ↔ {@code gradle.properties}' {@code minecraft_version} (pinned
 * here — the build itself consumes it) ↔ the resolved MC artifact
 * ({@code ToolchainContractTest}'s artifact anchor). A forward merge that clobbers
 * line.env back to another line's shape breaks the lockstep pin; one that clobbers
 * gradle.properties too breaks the artifact anchor and the build itself.
 *
 * <p>This lives in {@code :paper:test} because both build.yml and release.yml run
 * {@code :paper:test} BEFORE any publish step, so a regression here physically blocks
 * the tag run that would have shipped it. If a line ever ships without the Paper
 * module, this gate must be re-homed first (the port runbook records this coupling).
 *
 * <p>Assertions are scoped to their STEP block wherever a value could be satisfied by
 * the wrong step. FULL-LINE comments are stripped before asserting.
 */
class ReleaseWorkflowContractTest {

    private static final String LSS_MODRINTH_ID = "lKiXKLvv";
    private static final String VSS_MODRINTH_ID = "84zcagOb";

    private static String releaseYml;   // comment-stripped
    private static String buildYml;     // comment-stripped
    private static Map<String, String> lineEnv;
    private static String minecraftVersion;  // gradle.properties, the build's own token
    private static boolean isMainline;

    @BeforeAll
    static void load() throws Exception {
        releaseYml = stripComments(Files.readString(locate(".github/workflows/release.yml")));
        buildYml = stripComments(Files.readString(locate(".github/workflows/build.yml")));
        // Line-aware (single-branch consolidation): the workflow body is line-invariant but
        // its VALUES come from the active line's data (-Dlss.line, default 26.2).
        String activeLine = System.getProperty("lss.line", "26.2");
        Path lineEnvPath = locate("lines/" + activeLine + "/line.env");
        lineEnv = new HashMap<>();
        for (String line : Files.readAllLines(lineEnvPath)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            assertTrue(eq > 0, "line.env carries a non-KEY=VALUE line: '" + line + "'");
            assertEquals(line, line.strip(),
                    "line.env values are consumed VERBATIM by the workflow — trailing "
                            + "whitespace would make the guard refuse this line's own tags: '"
                            + line + "'");
            lineEnv.put(s.substring(0, eq), s.substring(eq + 1));
        }
        assertFalse(Files.readString(lineEnvPath).contains("\r"),
                "line.env carries CR bytes (a CRLF conversion) — readAllLines hides them "
                        + "but the workflow's grep passes them into $GITHUB_ENV, and the "
                        + "guard then refuses this line's own tags (round-3 review MINOR)");
        minecraftVersion = Files.readAllLines(locate("lines/" + activeLine + "/line.properties")).stream()
                .filter(l -> l.startsWith("minecraft_version="))
                .map(l -> l.substring("minecraft_version=".length()).strip())
                .findFirst().orElseThrow();
        isMainline = env("LINE_TAG_SUFFIX").isEmpty();
    }

    @org.junit.jupiter.api.Test
    void buildYmlDerivesJavaVersionFromLineEnv() throws java.io.IOException {
        // R2-2 (port-isolation round 2): build.yml hardcoded java-version went red by
        // construction on the Java-21 line, twice. Both jobs must source line.env and
        // derive; a literal value is the regression.
        String buildYml = Files.readString(locate(".github/workflows/build.yml"));
        assertFalse(buildYml.matches("(?s).*java-version:\\s*\\d.*"),
                "build.yml must not hardcode java-version — derive from LINE_JAVA_VERSION");
        assertEquals(2, count(buildYml, "java-version: ${{ env.LINE_JAVA_VERSION }}"),
                "both build.yml jobs must derive java-version from line.env");
        assertEquals(2, count(buildYml, "grep -vE '^\\s*(#|$)' .github/line.env >> \"$GITHUB_ENV\""),
                "both build.yml jobs must source line.env before setup-java");
    }

    @org.junit.jupiter.api.Test
    void releaseYmlDerivesPaperDisplayProse() throws java.io.IOException {
        // R2-7: the Paper Modrinth display name composes from LINE_PAPER_LOADERS —
        // a hardcoded "Paper/Purpur..." literal is the last per-line name token class.
        String releaseYml = Files.readString(locate(".github/workflows/release.yml"));
        assertFalse(releaseYml.contains("Paper/Purpur"),
                "release.yml must not hardcode loader display prose (LINE_PAPER_DISPLAY derives it)");
        assertTrue(releaseYml.contains("${{ env.LINE_PAPER_DISPLAY }}"),
                "the Paper Modrinth name must compose from LINE_PAPER_DISPLAY");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static String env(String key) {
        assertTrue(lineEnv.containsKey(key), "line.env must define " + key);
        return lineEnv.get(key);
    }

    private static String stripComments(String yaml) {
        return yaml.lines().filter(l -> !l.strip().startsWith("#"))
                .collect(Collectors.joining("\n"));
    }

    /** Walks up from the working dir (paper/ under Gradle, the repo root elsewhere). */
    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative + " above " + Path.of("").toAbsolutePath());
    }

    /** The step block starting at {@code marker}, ending at the next sibling step header. */
    private static String stepBlock(String marker) {
        int start = releaseYml.indexOf(marker);
        assertTrue(start >= 0, "release.yml lost the step '" + marker + "'");
        int end = releaseYml.indexOf("\n      - ", start + marker.length());
        return end < 0 ? releaseYml.substring(start) : releaseYml.substring(start, end);
    }

    // ---- the line data file itself ----

    @Test
    void lineEnvIsLockedToTheBuildToolchain() {
        // The lockstep link (P2): the data file's MC identity must agree with
        // gradle.properties' minecraft_version — the token the BUILD consumes (CI jar
        // names, the neoforge TOML expansion, release_check's --version matching). A
        // forward merge clobbering line.env alone reds here; clobbering both reds
        // ToolchainContractTest's artifact anchor and the build itself.
        // Refinement is equals-or-dot-anchored (round-3 review MINOR): a bare prefix
        // admits the 1.21.1↔1.21.11 confusion — a PLANNED line pair at stage G — so a
        // forward merge clobbering line.env back to the sibling line's shape stayed
        // green and surfaced only as a tag-time guard refusal (the FabricModJson
        // contract already carries this rule; adopted here).
        assertTrue(refines(minecraftVersion, env("LINE_MC_FABRIC")),
                "LINE_MC_FABRIC (" + env("LINE_MC_FABRIC") + ") must equal or dot-prefix "
                        + "gradle.properties minecraft_version (" + minecraftVersion + ")");
        assertTrue(refines(env("LINE_MC_PAPER"), env("LINE_MC_FABRIC")),
                "the Paper MC token refines the line token (e.g. 26.1.2 vs 26.1), never "
                        + "a different line");
        // The tag suffix must embed the fabric token (or be empty on main): the guard's
        // whole meaning is 'this tag belongs to this MC line'.
        String suffix = env("LINE_TAG_SUFFIX");
        assertTrue(suffix.isEmpty() || suffix.equals("+mc" + env("LINE_MC_FABRIC")),
                "LINE_TAG_SUFFIX must be '' (main) or '+mc<LINE_MC_FABRIC>', got '" + suffix + "'");
        // V-1 review MINOR: single-row drift armor — whole-file clobbers red above, but a
        // typo'd NeoForge token alone would publish into another line's Modrinth id
        // namespace (labrinth rejects nothing). Every publish-feeding row ties back.
        assertTrue(refines(env("LINE_MC_NEOFORGE"), env("LINE_MC_FABRIC")),
                "LINE_MC_NEOFORGE must refine the line token, never another line's");
        for (String loader : new String[]{"FABRIC", "PAPER", "NEOFORGE"}) {
            // Whole-token membership, not substring (round-3 review: "26.11" contains
            // "26.1" — the same 1.21.1x confusion in list form).
            assertTrue(java.util.Arrays.asList(env("LINE_GAME_VERSIONS_" + loader).split(" "))
                            .contains(env("LINE_MC_" + loader)),
                    "LINE_GAME_VERSIONS_" + loader + " must contain its own LINE_MC_" + loader
                            + " token as a whole list entry");
        }
        if (isMainline) {
            assertEquals("true", env("LINE_MAKE_LATEST"),
                    "main-line releases carry the Latest badge");
            assertEquals("26.2", env("LINE_MC_FABRIC"),
                    "mainline is the 26.2 line — a support cut must flip line.env in the "
                            + "same commit that retargets gradle.properties");
        } else {
            assertEquals("false", env("LINE_MAKE_LATEST"),
                    "support-line releases must not steal the Latest badge");
        }
    }

    @Test
    void releaseWorkflowCarriesNoHardcodedMcTokens() {
        // The branch-invariance pin (P1): with every MC token data-driven, NO version
        // literal of any line may appear in the workflow body. This replaces the old
        // FORBIDDEN_LINE_TOKENS list — under parameterization the strongest form is
        // 'no line's token, not even ours'.
        for (String token : new String[]{"26.2", "26.1", "1.21", "1.20"}) {
            assertFalse(releaseYml.contains(token),
                    "release.yml must not hardcode MC " + token + " — line.env owns MC identity");
        }
    }

    // ---- trigger + guards ----

    @Test
    void triggerIsTheBroadGlobWithNoQuantifierConstruct() {
        assertTrue(releaseYml.contains("tags: ['v*']"),
                "release.yml must trigger on the plain v* glob on EVERY line (on: cannot "
                        + "be data-driven; the guard step owns line scoping)");
        assertFalse(Pattern.compile("tags:.*\\*\\+").matcher(releaseYml).find(),
                "no tag filter may contain '*+' — GitHub treats '+' as a quantifier, the "
                        + "pattern is invalid, and a real tag push would trigger NOTHING");
    }

    @Test
    void guardStepRefusesWrongLineTagsBeforeAnyBuildOrPublish() {
        // V-1 re-pin (a deliberate decision, not drift): the guard is data-driven, so it
        // cannot precede checkout anymore. The invariant is now 'before any build, gate,
        // or publish step' — checkout and the env load publish nothing.
        String guard = stepBlock("- name: Refuse wrong-line tags");
        assertTrue(guard.contains("LINE_TAG_SUFFIX") && guard.contains("exit 1"),
                "the guard must compare the tag's +suffix against LINE_TAG_SUFFIX and fail");
        int checkout = releaseYml.indexOf("- uses: actions/checkout");
        int loadLine = releaseYml.indexOf("- name: Load line identity");
        int guardIdx = releaseYml.indexOf("- name: Refuse wrong-line tags");
        int firstBuildAdjacent = releaseYml.indexOf("- uses: actions/setup-java");
        assertTrue(checkout >= 0 && checkout < loadLine && loadLine < guardIdx
                        && guardIdx < firstBuildAdjacent,
                "order must be checkout < load-line < guard < setup-java — the guard sits "
                        + "before every build, gate, and publish step");
    }

    @Test
    void dispatchIsARehearsalThatCanNeverPublish() {
        // The dry-run lever (P1 review MAJOR): dispatch exercises the resolved pipeline
        // end-to-end; publishing stays push-gated, and dry_run=false is refused loudly.
        assertTrue(releaseYml.contains("workflow_dispatch:"),
                "the rehearsal trigger must exist — it is the only safe way to exercise "
                        + "this irreversible pipeline (throwaway tags are forbidden)");
        assertTrue(Pattern.compile("dry_run:\\s*\\n\\s+description:[^\\n]*\\n\\s+type: boolean\\s*\\n\\s+default: true")
                        .matcher(releaseYml).find(),
                "dry_run must stay type: boolean, default: true — a merge flipping the "
                        + "default must red here");
        String refuse = stepBlock("- name: Refuse non-dry dispatch");
        assertTrue(refuse.contains("exit 1"), "dry_run=false must fail the run, not publish");
        // Every publish step is push-event-gated.
        for (String step : new String[]{"- uses: softprops/action-gh-release",
                "- name: Upload Fabric to Modrinth", "- name: Upload Paper to Modrinth",
                "- name: Upload NeoForge to Modrinth"}) {
            assertTrue(stepBlock(step).contains(
                            "if: github.event_name == 'push' && github.ref_type == 'tag'"),
                    step + " must be push-gated so a dispatch rehearsal can never publish");
        }
    }

    @Test
    void prevTagLookupsAreLineScoped() {
        // Both PREV_TAG computations (lightweight-tag notes fallback + compare link) must
        // scope to THIS line's tags via LINE_TAG_SUFFIX (with main's '+mc' exclusion arm),
        // or version-sort slots another line's tag adjacent and the notes/compare span
        // across release lines.
        String pipeline = "git tag -l \"v*${LINE_TAG_SUFFIX}\" --sort=-v:refname | "
                + "if [ -z \"${LINE_TAG_SUFFIX}\" ]; then grep -v '+mc'; else cat; fi | "
                + "grep -v \"^${TAG}$\" | head -1 || true";
        long hits = Pattern.compile(Pattern.quote(pipeline)).matcher(releaseYml).results().count();
        assertEquals(2, hits,
                "release.yml must keep BOTH line-scoped PREV_TAG pipelines: " + pipeline);
    }

    // ---- publish steps ----

    @Test
    void githubReleaseStepShipsExactlyTheComposedLssAssets() {
        // VSS publishing is disabled as of v0.8.0 (user decision at the tri-release): the
        // vssJar tasks still build the branded byte-copies and release_check still gates
        // the pair, but no VSS jar may be distributed — from ANY line. Since the v0.11.0
        // NeoForge scope cut (user decision 2026-08-15) the asset list is COMPOSED in the
        // line.env step: fabric+paper always, neoforge only where LINE_SHIP_NEOFORGE=true
        // (fail_on_unmatched_files makes a static neoforge glob a hard failure on lines
        // that skip its build).
        String gh = stepBlock("- uses: softprops/action-gh-release");
        assertTrue(gh.contains("files: ${{ env.RELEASE_FILES }}"),
                "the gh-release assets must come from the composed RELEASE_FILES list");
        assertFalse(gh.contains("voxy-server-side-"),
                "no VSS jar may be attached to the GitHub release");
        assertTrue(gh.contains("fail_on_unmatched_files: true"),
                "fail_on_unmatched_files guards against publishing an empty release");
        assertTrue(gh.contains("make_latest: ${{ env.LINE_MAKE_LATEST }}"),
                "make_latest is line data (true on main, false on support lines) — "
                        + "lineEnvIsLockedToTheBuildToolchain pins the value per line");
        // The composition itself: fabric+paper unconditional, neoforge flag-guarded.
        assertTrue(releaseYml.contains("echo \"fabric/build/libs/lod-server-support-fabric-*.jar\""),
                "RELEASE_FILES must always include the fabric jar");
        assertTrue(releaseYml.contains("echo \"paper/build/libs/lod-server-support-paper-*.jar\""),
                "RELEASE_FILES must always include the paper jar");
        int guard = releaseYml.indexOf("if [ \"${LINE_SHIP_NEOFORGE}\" = \"true\" ]");
        int neoGlob = releaseYml.indexOf("echo \"neoforge/build/libs/lod-server-support-neoforge-*.jar\"");
        assertTrue(guard >= 0 && neoGlob > guard,
                "the neoforge asset glob must sit INSIDE the LINE_SHIP_NEOFORGE guard");
        // Guard-BOUNDED, not just guard-after (review m1: an echo moved below the fi
        // was the one mutation the ordering pin missed — it would ship the glob
        // unconditionally; fail_on_unmatched_files contains it, but loudly-red here
        // beats a failed release run).
        int fi = releaseYml.indexOf("\n            fi", guard);
        assertTrue(fi > guard && neoGlob < fi,
                "the neoforge asset glob must sit BEFORE the guard's closing fi");
    }

    @Test
    void neoforgeShippingIsGatedPerLine() {
        // v0.13.1 scope (user decision 2026-08-27): NeoForge ships on this line too —
        // the VoX/Foxy fork gives 26.x a working Voxy client pairing, retiring the
        // v0.11.0 1.21.1-only scope. The value pin keeps any future flip a conscious
        // per-line decision, and the step gates keep release.yml branch-invariant
        // (the V-1 principle: behavior from line.env data).
        assertEquals("true", env("LINE_SHIP_NEOFORGE"),
                "this line ships NeoForge since v0.13.1 (Foxy-fork client pairing) —"
                        + " flip line.env consciously (release_check derives from it)");
        assertTrue(stepBlock("- name: Build NeoForge + contract tests")
                        .contains("if: env.LINE_SHIP_NEOFORGE == 'true'"),
                "the release-pipeline NeoForge build must be flag-gated");
        assertTrue(stepBlock("- name: Upload NeoForge to Modrinth")
                        .contains("env.LINE_SHIP_NEOFORGE == 'true'"),
                "the NeoForge Modrinth step must be flag-gated");
        // R2-5 retired the hand mirror: release_check.py DERIVES SHIP_NEOFORGE from
        // line.env, so the forward-merge drift vector is gone by construction. Pin the
        // derivation itself so a revert to a hand constant reds here.
        try {
            String rc = Files.readString(locate("scripts/release_check.py"));
            assertTrue(rc.contains("SHIP_NEOFORGE = _LINE_ENV.get(\"LINE_SHIP_NEOFORGE\") == \"true\""),
                    "release_check.py must DERIVE SHIP_NEOFORGE from line.env (R2-5) — "
                            + "a hand constant re-opens the forward-merge drift vector");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void onlyTheLssModrinthStepsExistAndUseTheDataDrivenIds() {
        assertEquals(3, Pattern.compile(Pattern.quote("modrinth-id: " + LSS_MODRINTH_ID))
                        .matcher(releaseYml).results().count(),
                "all three LSS Modrinth steps must target " + LSS_MODRINTH_ID);
        assertFalse(releaseYml.contains(VSS_MODRINTH_ID),
                "release.yml must not reference the VSS Modrinth project " + VSS_MODRINTH_ID);
        assertFalse(releaseYml.contains("voxy-server-side-"),
                "release.yml must not upload/attach VSS jars (the vssJar tasks still BUILD "
                        + "them — only publishing is out)");
        // The version-id TEMPLATES are branch-invariant; the resolved ids per line come
        // from line.env. Exactly one step per loader family.
        for (String template : new String[]{
                "version: v${{ env.MOD_VERSION }}+fabric+mc${{ env.LINE_MC_FABRIC }}",
                "version: v${{ env.MOD_VERSION }}+paper+mc${{ env.LINE_MC_PAPER }}",
                "version: v${{ env.MOD_VERSION }}+neoforge+mc${{ env.LINE_MC_NEOFORGE }}"}) {
            assertEquals(1, Pattern.compile(Pattern.quote(template)).matcher(releaseYml)
                            .results().count(),
                    "exactly one Modrinth step per version-id template '" + template + "'");
        }
        // Loaders: fabric fixed; paper's list is line data (Folia's presence has flipped
        // per line); neoforge fixed.
        assertTrue(stepBlock("- name: Upload Fabric to Modrinth").contains("loaders: fabric"));
        assertTrue(stepBlock("- name: Upload Paper to Modrinth")
                .contains("loaders: ${{ env.PAPER_LOADERS }}"));
        assertTrue(stepBlock("- name: Upload NeoForge to Modrinth").contains("loaders: neoforge"));
        if (isMainline) {
            assertTrue(env("LINE_PAPER_LOADERS").contains("folia"),
                    "the 26.2 Paper step advertises folia (jar declares folia-supported; "
                            + "all four SOAK_PLATFORM=folia scenarios pass)");
        }
    }

    @Test
    void neoforgeStepNameIsDataDrivenAndUnderTheLabrinthCap() {
        // The version NAME is the only surface every Modrinth browser sees, and labrinth
        // caps names at 64 chars, 400ing longer ones MID-PUBLISH (N-4 review MAJOR). The
        // cap is computed over the RESOLVED name from line.env, not a literal.
        String neo = stepBlock("- name: Upload NeoForge to Modrinth");
        assertTrue(neo.contains("name: v${{ env.MOD_VERSION }} - ${{ env.LINE_NEOFORGE_NAME }}"),
                "the NeoForge name must resolve from LINE_NEOFORGE_NAME");
        String resolved = "v0.99.99 - " + env("LINE_NEOFORGE_NAME");
        assertTrue(resolved.length() <= 64,
                "the resolved NeoForge version name ('" + resolved + "', "
                        + resolved.length() + " chars) must stay under labrinth's 64-char cap");
        assertTrue(env("LINE_NEOFORGE_NAME").contains("server-side")
                        || env("LINE_NEOFORGE_NAME").contains("NeoForge"),
                "the name must still identify the loader/caveat");
        assertTrue(neo.contains("files: neoforge/build/libs/lod-server-support-neoforge-*.jar"),
                "the NeoForge step must ship the neoforge LSS jar, not another loader's");
        // Publish ORDER: the best-effort-tier step runs LAST, so a failure in it strands
        // only the best-effort artifact — never Paper (full tier).
        assertTrue(releaseYml.indexOf("- name: Upload NeoForge to Modrinth")
                        > releaseYml.indexOf("- name: Upload Paper to Modrinth"),
                "the NeoForge Modrinth step must come AFTER the Paper step");
    }

    @Test
    void javaVersionIsLineData() {
        assertTrue(stepBlock("- uses: actions/setup-java")
                        .contains("java-version: ${{ env.LINE_JAVA_VERSION }}"),
                "release.yml's Java version is line data (25 on 26.x, 21 on 1.21.x)");
        if (isMainline) {
            assertEquals("25", env("LINE_JAVA_VERSION"), "MC 26.2 builds on Java 25");
        }
    }

    // ---- build.yml ----

    @Test
    void buildWorkflowRunsOnSupportBranches() {
        // build.yml is shared in spirit across lines; keeping the branches filter identical
        // on main makes the recurring main→support merges conflict-free and ensures a
        // support branch pushed before its own build.yml edit still gets CI.
        long hits = Pattern.compile(
                        Pattern.quote("branches: [main, 'support/**']"))
                .matcher(buildYml).results().count();
        assertEquals(2, hits,
                "build.yml must keep branches: [main, 'support/**']"
                        + " on push AND pull_request");
    }

    /** Equals-or-dot-anchored version refinement: "26.1.2" refines "26.1"; "26.11"
     *  does NOT (the bare-prefix 1.21.1↔1.21.11 trap — FabricModJsonContractTest's
     *  rule, adopted at round 3). */
    private static boolean refines(String version, String line) {
        return version.equals(line) || version.startsWith(line + ".");
    }
}
