package dev.vox.lss.testutil;

import java.nio.file.Files;
import java.nio.file.Path;

/** Repo-relative path resolution for source-pin tests (the parent-walk every contract test
 *  carries privately — shared here for the tests outside {@code dev.vox.lss.config.menu}). */
public final class RepoPaths {
    private RepoPaths() {
    }

    public static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            if (Files.exists(dir.resolve("xplat/src/main/java"))) return dir.resolve(repoRelative);
        }
        throw new IllegalStateException("cannot locate the repo root from " + Path.of("").toAbsolutePath());
    }
}
