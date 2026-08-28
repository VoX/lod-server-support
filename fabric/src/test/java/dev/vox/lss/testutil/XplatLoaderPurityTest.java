package dev.vox.lss.testutil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The N-1b zero-loader-imports pin (neoforge-support-plan.md §2): every source
 * file in the xplat shared source set must be loader-neutral, because N-2
 * compiles the same sources in the neoforge module. Scans WHOLE SOURCES, not
 * import lines — the N-1a review found two fully-qualified {@code net.fabricmc}
 * call sites that an import-grep missed; an import-only pin here would green a
 * broken NeoForge compile.
 *
 * <p>{@code org.spongepowered} (the Mixin library — loader-neutral, bundled by
 * both loaders) is allowed ONLY in the mixin package, where the shared
 * {@code @Accessor} interfaces live; the per-loader {@code @Inject} shims stay
 * in their loader modules.
 */
class XplatLoaderPurityTest {

    private static final Pattern LOADER_REFERENCE = Pattern.compile(
            "net\\.fabricmc|net\\.neoforged|com\\.terraformersmc|net\\.caffeinemc");
    private static final Pattern MIXIN_LIBRARY = Pattern.compile("org\\.spongepowered");

    @Test
    void xplatSourcesReferenceNoLoaderApiAnywhere() throws IOException {
        List<String> offenders = new ArrayList<>();
        // Whole-source scan of the SHARED xplat tree AND the active line's xplat overlay
        // (single-branch consolidation): a line's overlaid xplat file must stay loader-neutral
        // too, or it breaks the same-FQN twin contract on its own line.
        for (Path root : xplatRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                final Path r = root;
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    String text;
                    try {
                        text = Files.readString(p);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    var rel = r.relativize(p).toString().replace('\\', '/');
                    if (LOADER_REFERENCE.matcher(text).find()) {
                        offenders.add(rel + " (loader reference)");
                    }
                    if (!rel.startsWith("dev/vox/lss/mixin/") && MIXIN_LIBRARY.matcher(text).find()) {
                        offenders.add(rel + " (mixin library outside the mixin package)");
                    }
                });
            }
        }
        assertTrue(offenders.isEmpty(),
                "xplat must stay loader-neutral (whole-source scan) — route these through"
                        + " LoaderServices or move them to a loader module: " + offenders);
    }

    /** The shared xplat source root plus the active line's xplat overlay root (if present). */
    private static List<Path> xplatRoots() {
        Path shared = null;
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            Path candidate = dir.resolve("xplat/src/main/java");
            if (Files.isDirectory(candidate)) { shared = candidate; break; }
        }
        if (shared == null) {
            throw new AssertionError("cannot locate xplat/src/main/java from " + Path.of("").toAbsolutePath());
        }
        List<Path> roots = new ArrayList<>();
        roots.add(shared);
        String line = System.getProperty("lss.line", "26.2");
        if (!"26.2".equals(line)) {
            Path overlay = shared.getParent().getParent().resolve("line").resolve(line).resolve("java");
            if (Files.isDirectory(overlay)) roots.add(overlay);
        }
        return roots;
    }
}
