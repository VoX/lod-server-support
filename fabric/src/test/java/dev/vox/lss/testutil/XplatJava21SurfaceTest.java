package dev.vox.lss.testutil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The N-1b Java-21 tripwire (neoforge-support-plan.md §1.1): xplat is compiled
 * by each consumer module, so nothing builds it standalone and a post-Java-21
 * JDK API leak would surface months later on a 1.21.x backport branch (built at
 * Java 21 — the paperweight constraint). The plan's preferred {@code --release 21}
 * compile is structurally impossible here: MC 26.2 ships class-file major 69
 * (Java 25), which a --release 21 javac refuses to read. So this scans the
 * COMPILED xplat classes' constant pools (raw-bytes substring, the
 * FoliaWiringContractTest precedent) for post-21 JDK API references instead.
 *
 * <p>Known-25-only exclusion list: EMPTY since V-2/S5 — {@code ScopedCarrier}
 * (the one ScopedValue user) moved to the per-loader trees as a version-volatile
 * twin, so a Java-21 line swaps that file instead of patching shared xplat source.
 * Keep it empty: a new entry means a new per-line patch burden on every support
 * line — seam the API into a per-loader twin instead.
 *
 * <p>Limitation, stated: a constant-pool scan catches API references, not
 * post-21 SYNTAX — the backport line's own Java-21 compile catches that.
 */
class XplatJava21SurfaceTest {

    /** Post-Java-21 JDK API surfaces that could plausibly leak in. */
    private static final List<String> POST_21_API = List.of(
            // NOTE: raw-substring matching — every entry must not be a prefix of a
            // pre-21 API name ("java/io/IO" matched IOException and is deliberately
            // absent; the Java 25 console class is not a plausible server-code leak).
            "java/lang/ScopedValue",
            "java/util/concurrent/StructuredTaskScope",
            "java/lang/classfile",
            "java/lang/foreign/",
            "java/util/stream/Gatherer");

    /** xplat files allowed to use Java-25-only API (EMPTY — see the class javadoc). */
    private static final Set<String> KNOWN_25_ONLY = Set.of();

    @Test
    void compiledXplatClassesReferenceNoPost21JdkApi() throws IOException {
        // Line-aware (single-branch consolidation): non-default lines compile into
        // fabric/build/<line>/ and their xplat source is shared + overlay. Scan the ACTIVE
        // line's compiled classes (a hardcoded default path scans stale/absent classes on a
        // per-line build dir — the exact CI red this fixes) and union the overlay's xplat FQNs.
        String line = System.getProperty("lss.line", "26.2");
        String buildSub = "26.2".equals(line) ? "" : line + "/";
        Path srcRoot = find("xplat/src/main/java");
        Path classesRoot = find("fabric/build/" + buildSub + "classes/java/main");
        // FQN list = shared xplat ∪ this line's xplat overlay (same FQNs win; new ones added).
        java.util.LinkedHashMap<String, Boolean> fqcns = new java.util.LinkedHashMap<>();
        collectFqcns(srcRoot, fqcns);
        Path overlayRoot = Path.of(find("xplat/src/main/java").getParent().getParent().toString(),
                "line", line, "java");
        if (Files.isDirectory(overlayRoot)) {
            collectFqcns(overlayRoot, fqcns);
        }
        List<String> offenders = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        {
            for (String fqcnPath : fqcns.keySet()) {
                if (KNOWN_25_ONLY.contains(fqcnPath)) {
                    continue;
                }
                List<Path> classFiles = classFilesFor(classesRoot, fqcnPath);
                if (classFiles.isEmpty()) {
                    missing.add(fqcnPath);
                    continue;
                }
                for (Path cf : classFiles) {
                    byte[] bytes;
                    try {
                        bytes = Files.readAllBytes(cf);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    String pool = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                    for (String api : POST_21_API) {
                        if (pool.contains(api)) {
                            offenders.add(fqcnPath + " -> " + api);
                        }
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "xplat sources with no compiled class under fabric — run compileJava, or fix"
                        + " this test's class lookup: " + missing);
        assertTrue(offenders.isEmpty(),
                "post-Java-21 JDK API leaked into xplat (breaks the Java-21 backport lines;"
                        + " seam it, avoid it, or add a documented KNOWN_25_ONLY entry): "
                        + offenders);
    }

    /** Collect the FQN paths of every .java under a root into the (insertion-ordered) set. */
    private static void collectFqcns(Path root, java.util.Map<String, Boolean> out) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                out.put(rel.substring(0, rel.length() - ".java".length()), Boolean.TRUE);
            });
        }
    }

    /** The class file plus any nested classes (Foo$Bar, Foo$1...). */
    private static List<Path> classFilesFor(Path classesRoot, String fqcnPath) {
        List<Path> out = new ArrayList<>();
        Path dir = classesRoot.resolve(fqcnPath).getParent();
        String simple = fqcnPath.substring(fqcnPath.lastIndexOf('/') + 1);
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> siblings = Files.list(dir)) {
            siblings.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.equals(simple + ".class")
                        || (name.startsWith(simple + "$") && name.endsWith(".class"))) {
                    out.add(p);
                }
            });
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    private static Path find(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("cannot locate " + repoRelative + " from "
                + Path.of("").toAbsolutePath());
    }
}
