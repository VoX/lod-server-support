package dev.vox.lss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * FLEET-WIDE overlay provenance pin (single-branch-consolidation-plan.md §3.2 — "the
 * highest-value single addition"). Every line overlay under {@code <module>/src/line/<line>/}
 * carries a header stamp {@code // OVERLAY OF <shared repo-path> @ <sha256-of-shared-file>}.
 * This test recomputes each shadowed shared file's current sha256 and asserts it still equals
 * the stamp — so when a shared file drifts, the overlay that shadows it reds HERE (prompting a
 * human to re-examine + refresh the stamp) instead of silently staling. That silent stale is
 * exactly the backport-debt failure the whole single-branch program exists to end.
 *
 * <p>Runs from ANY line (it walks the repo tree, not the active line's classpath). A stamp
 * of {@code NEW} marks an overlay-only file (no shared original) and is exempt from the hash pin.
 */
class OverlayProvenanceContractTest {

    private static final Pattern STAMP =
            Pattern.compile("//\\s*OVERLAY OF\\s+(\\S+)\\s+@\\s+([0-9a-fA-F]{64}|NEW)");

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(".git")) || Files.exists(dir.resolve("settings.gradle"))) {
                return dir;
            }
        }
        throw new IllegalStateException("cannot locate repo root from " + Path.of("").toAbsolutePath());
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    void everyOverlayStampMatchesItsSharedFilesCurrentHash() throws Exception {
        Path root = repoRoot();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String module : new String[] {"common", "xplat", "fabric", "neoforge", "paper"}) {
            Path lineRoot = root.resolve(module).resolve("src/line");
            if (!Files.isDirectory(lineRoot)) continue;
            try (Stream<Path> walk = Files.walk(lineRoot)) {
                for (Path f : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    String head;
                    try {
                        head = Files.readString(f, StandardCharsets.UTF_8);
                    } catch (IOException e) { continue; }
                    Matcher m = STAMP.matcher(head.length() > 400 ? head.substring(0, 400) : head);
                    if (!m.find()) {
                        problems.add("overlay missing provenance stamp: " + root.relativize(f));
                        continue;
                    }
                    checked++;
                    String sharedRel = m.group(1);
                    String recorded = m.group(2);
                    if ("NEW".equals(recorded)) continue;  // overlay-only file, no shared original
                    Path shared = root.resolve(sharedRel);
                    if (!Files.exists(shared)) {
                        problems.add(root.relativize(f) + ": stamped shared path does not exist: " + sharedRel);
                        continue;
                    }
                    String current = sha256(shared);
                    if (!current.equalsIgnoreCase(recorded)) {
                        problems.add(root.relativize(f) + ": shared file " + sharedRel
                                + " drifted (stamp " + recorded.substring(0, 12) + "…, now "
                                + current.substring(0, 12) + "…) — re-examine the overlay against the "
                                + "shared change and refresh the stamp");
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            fail("overlay provenance drift (" + problems.size() + " of " + checked + " checked):\n  "
                    + String.join("\n  ", problems));
        }
    }
}
