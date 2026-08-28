package dev.vox.lss.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the single-branch release + build workflows (single-branch-consolidation-
 * plan.md §4/§5). The pre-consolidation per-branch pins (LINE_TAG_SUFFIX wrong-line guard,
 * line-scoped PREV_TAG, the RELEASE_FILES glob composition, LINE_PAPER_DISPLAY prose, the
 * single-job step ordering) are RETIRED BY DECISION: there is one branch and one tag family
 * now, so those threat models no longer exist. Their replacements are the fleet-wide
 * {@link dev.vox.lss.LineMatrixContractTest} (line-data value pins) and the new-pipeline
 * structural pins below.
 *
 * <p>Lives in {@code :paper:test} because the release BUILD stage runs {@code :paper:test}
 * before any publish, so a regression here physically blocks the tag run that would ship it.
 */
class ReleaseWorkflowContractTest {

    private static final String LSS_MODRINTH_ID = "lKiXKLvv";
    private static final String VSS_MODRINTH_ID = "84zcagOb";

    private static String releaseYml;
    private static String buildYml;

    @BeforeAll
    static void load() throws Exception {
        releaseYml = Files.readString(locate(".github/workflows/release.yml"));
        buildYml = Files.readString(locate(".github/workflows/build.yml"));
    }

    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative);
    }

    @Test
    void releaseIsFourStagedPipeline() {
        for (String job : new String[] {"  setup:", "  build:", "  github-release:", "  modrinth:", "  finalize:"}) {
            assertTrue(releaseYml.contains(job),
                    "release.yml must declare the " + job.trim() + " stage (§5 four-stage pipeline)");
        }
        // The publishing stages fan in AFTER build; finalize is last.
        assertTrue(releaseYml.contains("needs: [setup, build]"),
                "github-release must fan in on build");
        assertTrue(releaseYml.contains("needs: [setup, github-release]"),
                "modrinth must run after the draft release exists");
        assertTrue(releaseYml.contains("needs: [setup, modrinth]"),
                "finalize must run after modrinth");
    }

    @Test
    void tagTriggerIsPlainVWithNoSuffixGrammar() {
        assertTrue(releaseYml.contains("tags: ['v*']"),
                "release triggers on v* tags");
        // One branch, one tag family — the '+mc' suffix grammar in the trigger is gone.
        assertFalse(Pattern.compile("tags:.*\\*\\+").matcher(releaseYml).find(),
                "the tag trigger must not carry a '+' suffix pattern (single-branch: no per-line suffixes)");
    }

    @Test
    void numericVersionGuardSurvives() {
        assertTrue(releaseYml.contains("[0-9]*.[0-9]*.[0-9]*"),
                "the numeric mod-version guard must survive (a non-numeric tag must not publish)");
    }

    @Test
    void notesAreExtractedOnceInSetupWithTheLightweightTagRepair() {
        // The force-fetch repair for actions/checkout's lightweight tag must be present, ONCE.
        long fetches = Pattern.compile(Pattern.quote("git fetch --force origin \"refs/tags/"))
                .matcher(releaseYml).results().count();
        assertTrue(fetches == 1, "the lightweight-tag force-fetch repair must appear exactly once "
                + "(the single-extraction design point), found " + fetches);
        assertTrue(releaseYml.contains("%(contents)"),
                "notes come from the annotated tag's %(contents)");
        // PREV_TAG keeps the historical '+mc' family exclusion forever.
        assertTrue(releaseYml.contains("grep -v '+mc'"),
                "PREV_TAG must keep excluding the historical '+mc' tag family");
    }

    @Test
    void publishIsDraftThenFinalized() {
        assertTrue(releaseYml.contains("draft: true"),
                "github-release must create a DRAFT (nothing is live until finalize)");
        assertTrue(releaseYml.contains("--draft=false"),
                "finalize must flip the draft to published");
        // make_latest is a release-level fact set once in the fan-in, not per-line data.
        assertTrue(releaseYml.contains("make_latest: 'true'"),
                "make_latest is set once at the github-release fan-in");
    }

    @Test
    void modrinthPublishingIsIdempotentPerVersion() {
        assertTrue(releaseYml.contains("api.modrinth.com/v2/project/lKiXKLvv/version/"),
                "modrinth stage must pre-check each version_number (idempotency is ours — "
                        + "Modrinth accepts duplicates silently)");
        assertTrue(releaseYml.contains("steps.precheck.outputs.fabric == 'absent'"),
                "the Fabric publish must be gated on the idempotency pre-check");
    }

    @Test
    void bestEffortLinesDoNotBlockTheGatingTier() {
        assertTrue(releaseYml.contains("continue-on-error: ${{ matrix.gates != 'true' }}"),
                "non-gating (1.21.x/intermediary) lines must be continue-on-error so a "
                        + "best-effort flake never blocks the 26.x release (§5)");
    }

    @Test
    void dryRunRehearsalSurvives() {
        assertTrue(releaseYml.contains("workflow_dispatch:") && releaseYml.contains("dry_run:"),
                "the workflow_dispatch dry-run rehearsal lever must survive the matrix rework");
        assertTrue(releaseYml.contains("Refuse non-dry dispatch"),
                "a non-dry dispatch (no tag) must be refused");
    }

    @Test
    void releaseYmlPublishesTheLssProjectAndStaysVssFree() {
        assertTrue(releaseYml.contains("modrinth-id: " + LSS_MODRINTH_ID),
                "release.yml publishes the LSS Modrinth project");
        assertFalse(releaseYml.contains(VSS_MODRINTH_ID),
                "release.yml must stay VSS-free (VSS publishes locally, never via CI)");
    }

    @Test
    void workflowsCarryNoHardcodedMcTokens() {
        // The plan's "no hardcoded MC token in the workflow body" pin, in its strongest form:
        // every MC-version value comes from lines/ via the matrix. A stray `26.2`/`1.21.11`
        // literal would silently pin a line. Scan comment-stripped bodies of BOTH workflows.
        var mc = Pattern.compile("\\b(26\\.\\d+|1\\.21\\.\\d+)\\b");
        for (String wf : new String[] {"release.yml", "build.yml"}) {
            String body = "release.yml".equals(wf) ? releaseYml : buildYml;
            StringBuilder noComments = new StringBuilder();
            for (String l : body.split("\n")) {
                if (!l.strip().startsWith("#")) noComments.append(l).append('\n');
            }
            var matcher = mc.matcher(noComments.toString());
            assertFalse(matcher.find(),
                    wf + " carries a hardcoded MC-version token ('" + (matcher.reset().find() ? matcher.group() : "")
                            + "') — every MC value must come from lines/ via the matrix");
        }
    }

    @Test
    void buildYmlIsAMatrixGeneratedFromLines() {
        assertTrue(buildYml.contains("gen_matrix.py"),
                "build.yml derives its matrix from lines/ via gen_matrix.py (no hardcoded MC list)");
        assertTrue(buildYml.contains("fail-fast: false"),
                "the build matrix must be fail-fast:false — the cross-line signal is the point");
        assertTrue(buildYml.contains("java-version: ${{ matrix.java }}"),
                "each matrix job sets up the line's own JDK");
        // No hardcoded MC-version token anywhere in the workflow bodies.
        assertFalse(Pattern.compile("java-version:\\s*'?2[15]'?\\s*$", Pattern.MULTILINE).matcher(buildYml).find(),
                "build.yml must not hardcode a Java version (derives from the matrix)");
    }
}
