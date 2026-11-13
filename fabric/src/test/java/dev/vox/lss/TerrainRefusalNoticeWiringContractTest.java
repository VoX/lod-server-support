package dev.vox.lss;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the permission line is sent (0.22.0 plan §1), on both server cores. The latch and the
 * send are tested where they live ({@code ServiceGateStateTest}, the Paper sweep test, the
 * service gametests); a refused handshake needs a real permission backend, which no test JVM
 * has, so the call sites are pinned here at the source level.
 */
class TerrainRefusalNoticeWiringContractTest {

    private static String normalized(String repoPath) throws IOException {
        return Files.readString(SourcePaths.repoFile(repoPath)).replaceAll("\\s+", " ");
    }

    private static void assertOrdered(String source, String... fragments) {
        int from = 0;
        for (String fragment : fragments) {
            int at = source.indexOf(fragment, from);
            assertTrue(at >= 0, "missing, or out of order: " + fragment);
            from = at + fragment.length();
        }
    }

    @Test
    void aRefusedHandshakeTellsThePlayerOnBothCores() throws IOException {
        // Fabric, NeoForge and Sponge: the shared glue's denial hook, on the server thread.
        assertOrdered(normalized("xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java"),
                "public void onServiceDenied(int protocolVersion, int capabilities) {",
                "service.getServiceGateState().rememberDenied(player.getUUID(),",
                "service.noticePermissionRefusal(player, false);",
                "service.unregisterForServiceGate(player.getUUID());",
                "public boolean onMapPolicyRefused(");
        // Paper: marshaled to the pump, where the service claims the latch.
        String paper = normalized("paper/src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java");
        assertOrdered(paper,
                "public void onServiceDenied(int protocolVersion, int capabilities) {",
                "state.rememberDenied(uuid, playerName, protocolVersion, capabilities);",
                "if (permissionNotice != null) permissionNotice.run();",
                "public boolean onMapPolicyRefused(");
        assertTrue(paper.contains("() -> service.enqueueRuntimeTask( () -> service.noticePermissionRefusal(nmsPlayer, false)));"),
                "the production gate passes the pump-marshaled line");
    }

    @Test
    void aRevocationTellsThePlayerOnBothCores() throws IOException {
        for (String core : new String[]{
                "xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java",
                "paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java"}) {
            assertOrdered(normalized(core),
                    "if (!gateState.bumpRevocationStreak(uuid)) continue;",
                    "unregisterForServiceGate(uuid);",
                    "noticePermissionRefusal(player, true);",
                    "LSSLogger.info(\"LOD service revoked for \"");
        }
    }

    @Test
    void theLineIsATranslatableWithTheEnglishFallbackOnBothCores() throws IOException {
        for (String core : new String[]{
                "xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java",
                "paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java"}) {
            assertTrue(normalized(core).contains("player.sendSystemMessage(net.minecraft.network.chat.Component"
                            + ".translatableWithFallback( dev.vox.lss.common.TerrainRefusalNotice.KEY,"
                            + " dev.vox.lss.common.TerrainRefusalNotice.FALLBACK));"),
                    core + ": a system message every client version can show");
        }
    }
}
