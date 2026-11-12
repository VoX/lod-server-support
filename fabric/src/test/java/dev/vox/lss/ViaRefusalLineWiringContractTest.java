package dev.vox.lss;

import dev.vox.lss.testutil.RepoPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Source wiring pin for the refused-legacy-announce log line (0.21.0). Its wording is pinned in
 * common ({@code ViaProbeRefusalLineTest}); what that cannot see is whether each server core
 * still calls the shared builder. A core that went back to its own string would compile, keep
 * every test green, and tell operators again that "the client must update" when a current
 * client merely retried after a slow reply.
 */
class ViaRefusalLineWiringContractTest {

    private static String code(String repoPath) throws IOException {
        return Files.readString(RepoPaths.locate(repoPath))
                .replaceAll("(?m)^\\s*//.*$", "").replaceAll("\\s+", " ");
    }

    private static void assertUsesTheSharedLine(String repoPath) throws IOException {
        String code = code(repoPath);
        assertEquals(1, code.split("ViaProbe\\.legacyAnnounceRefusedLine\\(", -1).length - 1,
                repoPath + ": the Via mismatch rung logs through the shared builder, once");
        assertFalse(code.contains("cross-MC legacy session"),
                repoPath + ": the old hand-built line must not come back");
    }

    @Test
    void theSharedCoreLogsThroughTheSharedBuilder() throws IOException {
        // Fabric, NeoForge and Sponge all run this core.
        assertUsesTheSharedLine("xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java");
    }

    @Test
    void thePaperCoreLogsThroughTheSharedBuilder() throws IOException {
        assertUsesTheSharedLine("paper/src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java");
    }
}
