package dev.vox.lss.testutil;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves production-source files for the source-regex contract-test family.
 *
 * <p>Since stage N-1a (neoforge-support-plan.md §2) loader-neutral MC logic
 * lives in the {@code xplat/} shared source set, so a production file sits
 * under {@code fabric/src/main/java} OR {@code xplat/src/main/java}. This
 * helper hides which — a future move between the two trees is a no-op for
 * callers (the plan's one-line-change rule for the next extraction round).
 * Survives both the Gradle CWD (module dir) and an IDE repo-root CWD.
 */
public final class SourcePaths {
    private SourcePaths() {
    }

    private static final String[] SOURCE_TREES = {
            "src/main/java/",         // CWD = fabric module dir
            "fabric/src/main/java/",  // CWD = repo root
            "xplat/src/main/java/",   // CWD = repo root, xplat tree (module-dir CWD reaches it via the parent walk)
    };

    /**
     * Resolves a REPO-ROOT-relative file (e.g. {@code "neoforge/src/main/resources/..."}),
     * surviving both the Gradle CWD (the module dir) and an IDE repo-root CWD by the same
     * parent walk {@link #mainSource} uses. Contract tests that assert across MODULES —
     * a Fabric test pinning a NeoForge resource, say — need this rather than a
     * {@code Path.of(...)} that only works from one of the two CWDs.
     *
     * @throws AssertionError when the file exists under no reachable root
     */
    public static Path repoFile(String repoRelativePath) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelativePath);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + repoRelativePath
                + " from cwd=" + Path.of("").toAbsolutePath());
    }

    /**
     * @param javaPath package-relative path, e.g. {@code "dev/vox/lss/trace/MoveDesyncHooks.java"}
     * @throws AssertionError when the file exists in neither tree — a moved-without-retarget signal
     */
    public static Path mainSource(String javaPath) {
        // Collect across the WHOLE walk before returning (review: an early depth-0
        // return from the module CWD would skip the repo-root xplat probe, so a
        // copy-instead-of-git-mv would silently prefer fabric's copy in exactly the
        // Gradle case). Distinct real paths only — the same file is reachable through
        // several (dir, tree) combinations.
        java.util.Set<Path> hits = new java.util.LinkedHashSet<>();
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            for (String tree : SOURCE_TREES) {
                Path candidate = dir.resolve(tree + javaPath);
                if (Files.exists(candidate)) {
                    try {
                        hits.add(candidate.toRealPath());
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            }
        }
        // A file present in BOTH trees is a botched half-move (copy instead of
        // git mv) — fail loudly rather than silently preferring fabric's copy.
        if (hits.size() > 1) {
            throw new AssertionError("production source " + javaPath
                    + " exists in more than one tree: " + hits);
        }
        if (hits.size() == 1) {
            return hits.iterator().next();
        }
        throw new AssertionError("cannot locate production source " + javaPath
                + " under fabric/ or xplat/ (cwd=" + Path.of("").toAbsolutePath() + ")");
    }
}
