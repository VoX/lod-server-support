package dev.vox.lss;

import dev.vox.lss.testutil.RepoPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source wiring pin for {@code far_players.hide_permission_holders} (hide-permission plan
 * §3, 0.21.0). The decision itself is behavior-tested in common
 * ({@code FarPlayerBroadcastServiceTest}); what no behavior test can see is whether each
 * production server core hands the LIVE setting to the broadcast pass. A core that passed a
 * constant would compile, keep every test green, and leave the setting inert on that
 * platform — an operator who turned it on to hide staff would have nobody hidden.
 *
 * <p>Both cores build exactly one {@code FarPlayerBroadcastService.Settings} per pass, and
 * its last argument is the config read at that moment (the tick-poll pattern, so a reload
 * takes effect on the next pass with no restart).
 */
class FarPlayerHidePermissionWiringContractTest {

    private static String code(String repoPath) throws IOException {
        return Files.readString(RepoPaths.locate(repoPath))
                .replaceAll("(?m)^\\s*//.*$", "").replaceAll("\\s+", " ");
    }

    @Test
    void theSharedCorePassesTheLiveSettingIntoEveryBroadcastPass() throws IOException {
        // Fabric, NeoForge and Sponge all run this core.
        String code = code("xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java");
        assertEquals(1, code.split("FarPlayerBroadcastService\\.Settings\\(", -1).length - 1,
                "one Settings construction: the broadcast pass");
        assertTrue(code.contains("config.farPlayersUpdateIntervalTicks(),"
                        + " config.farPlayersHidePermissionHolders()), this::sendFarPlayerFrame);"),
                "the pass must read far_players.hide_permission_holders from the config each time");
    }

    @Test
    void thePaperCorePassesTheLiveSettingIntoEveryBroadcastPass() throws IOException {
        String code = code("paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java");
        assertEquals(1, code.split("FarPlayerBroadcastService\\.Settings\\(", -1).length - 1,
                "one Settings construction: the broadcast pass");
        assertTrue(code.contains("this.config.farPlayersUpdateIntervalTicks(),"
                        + " this.config.farPlayersHidePermissionHolders()), this::sendFarPlayerFrame);"),
                "the pass must read far_players.hide_permission_holders from the config each time");
    }

    @Test
    void theSnapshotBuildersNeverSeeTheSetting() throws IOException {
        // The setting is enforced in ONE place (the service). A builder that read it could
        // only do so to drop or soften a privacy read, and Paper's fail-hidden direction must
        // not depend on configuration.
        for (String path : new String[]{
                "xplat/src/main/java/dev/vox/lss/networking/server/FabricFarPlayerSnapshots.java",
                "paper/src/main/java/dev/vox/lss/paper/PaperFarPlayerSnapshots.java"}) {
            assertTrue(!code(path).contains("farPlayersHidePermissionHolders"),
                    path + " must not read the server setting");
        }
    }
}
