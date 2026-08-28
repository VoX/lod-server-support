package dev.vox.lss;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Line-aware resolver for committed test goldens (single-branch-consolidation-plan.md §3.2:
 * "binary goldens use override-resolution, not whole-copy"). Corpus tests read their goldens
 * from the SOURCE tree (so {@code -Dlss.regenGoldens} can rewrite them), which the assembled
 * classpath never reaches — so the OVERRIDE has to happen at the read. Given a corpus subdir
 * and file name, this returns the active line's overlay copy
 * ({@code src/line/<lss.line>/test/resources/<subdir>/<name>}) when present, else the shared
 * {@code src/test/resources/<subdir>/<name>}. On the default line (26.2, no overlays) it is the
 * shared path verbatim — byte-identity preserved.
 */
public final class LineGoldens {
    private LineGoldens() {}

    /** The module root (the dir holding {@code src/test/java/dev/vox/lss}), CWD-independent. */
    public static Path moduleRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && dir != null; depth++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("src/test/java/dev/vox/lss"))) return dir;
            for (String m : new String[] {"fabric", "paper", "neoforge"}) {
                Path nested = dir.resolve(m);
                if (Files.isDirectory(nested.resolve("src/test/java/dev/vox/lss"))) return nested;
            }
        }
        throw new IllegalStateException("cannot locate the module source tree from "
                + Path.of("").toAbsolutePath());
    }

    /** Overlay-first path to a golden file under {@code <subdir>/<name>} for the active line. */
    public static Path resolve(String subdir, String name) {
        Path root = moduleRoot();
        String line = System.getProperty("lss.line", "26.2");
        if (!"26.2".equals(line)) {
            Path overlay = root.resolve("src/line").resolve(line)
                    .resolve("test/resources").resolve(subdir).resolve(name);
            if (Files.exists(overlay)) return overlay;
        }
        return root.resolve("src/test/resources").resolve(subdir).resolve(name);
    }

    /**
     * Overlay-first path to a MAIN source file for the active line (for source-scanning tests
     * — LanHookContractTest, the mixin-descriptor pins). Given a module-relative path under
     * {@code src/main/java/...}, returns the line overlay ({@code src/line/<line>/java/...}) when
     * present, else the shared main source. Default line: shared verbatim.
     */
    public static Path mainSource(String moduleRelMainJavaPath) {
        Path root = moduleRoot();
        String prefix = "src/main/java/";
        String line = System.getProperty("lss.line", "26.2");
        if (moduleRelMainJavaPath.startsWith(prefix) && !"26.2".equals(line)) {
            String rel = moduleRelMainJavaPath.substring(prefix.length());
            Path overlay = root.resolve("src/line").resolve(line).resolve("java").resolve(rel);
            if (Files.exists(overlay)) return overlay;
        }
        return root.resolve(moduleRelMainJavaPath);
    }

    /** Overlay-first path to a corpus DIR (callers that list a whole dir). Prefers the overlay
     *  dir only when it exists; per-file callers should use {@link #resolve} for override-per-file. */
    public static Path dir(String subdir) {
        Path root = moduleRoot();
        String line = System.getProperty("lss.line", "26.2");
        if (!"26.2".equals(line)) {
            Path overlay = root.resolve("src/line").resolve(line).resolve("test/resources").resolve(subdir);
            if (Files.isDirectory(overlay)) return overlay;
        }
        return root.resolve("src/test/resources").resolve(subdir);
    }
}
