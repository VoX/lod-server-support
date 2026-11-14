package dev.vox.lss;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source wiring pins for the settings-problem announcements (0.23.0 plan §1). The words and the
 * rules are tested in common ({@code SettingsProblemAnnouncerTest}, {@code SettingsProblemsTest});
 * what no behavior test sees is that each platform calls them where a disabled service still
 * reaches them: a settings file that did not load leaves the service disabled.
 */
class SettingsProblemWiringContractTest {
    private static String source(String repoPath) throws IOException {
        return Files.readString(SourcePaths.repoFile(repoPath)).replaceAll("\\s+", " ");
    }

    private static void before(String source, String first, String second, String what) {
        int a = source.indexOf(first), b = source.indexOf(second);
        assertTrue(a >= 0, what + ": missing " + first);
        assertTrue(b >= 0, what + ": missing " + second);
        assertTrue(a < b, what + ": " + first + " must come before " + second);
    }

    @Test
    void bothServiceTicksAnnounceBeforeTheirDisabledReturn() throws IOException {
        before(source("xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java"),
                "public void tick() { announceSettingsProblems();", "if (!serviceConfig.enabled()) return; this.unservedWorldsLog",
                "the Fabric, NeoForge and Sponge service");
        before(source("paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java"),
                "drainRuntimeTasks(); announceSettingsProblems();", "if (!this.config.enabled()) return; this.unservedWorldsLog",
                "the Paper pump");
    }

    @Test
    void theModdedCommandOpensHelpDiagAndReloadWithTheProblems() throws IOException {
        String commands = source("xplat/src/main/java/dev/vox/lss/networking/server/LSSServerCommands.java");
        for (String entry : new String[] {
                "private static int showHelp(CommandSourceStack source) { sendSettingsProblems(source);",
                "private static int showDiagnostics(CommandSourceStack source) { sendSettingsProblems(source);",
                "private static int reload(CommandSourceStack source) { sendSettingsProblems(source);"})
            assertTrue(commands.contains(entry), "missing: " + entry);
        assertTrue(commands.contains("SettingsReload.problemLines(failure)"),
                "a failed reload lists every problem, not the first one");
    }

    @Test
    void thePaperCommandOpensEveryUseWithTheProblems() throws IOException {
        String commands = source("paper/src/main/java/dev/vox/lss/paper/PaperCommands.java");
        before(commands, "SettingsProblemText.serverBlock(", "if (args.length == 0 || args[0].equalsIgnoreCase(\"help\"))",
                "the Paper command");
        assertTrue(commands.contains("SettingsReload.problemLines(failure)"),
                "a failed reload lists every problem, not the first one");
    }

    @Test
    void theAdminRightIsTheCommandsOwn() throws IOException {
        // Fabric and NeoForge: the permission level the command requires. Sponge and Paper: the
        // build's admin node. A second definition of "admin" would tell people who cannot run
        // the command to run it.
        String commands = source("xplat/src/main/java/dev/vox/lss/networking/server/LSSServerCommands.java");
        assertTrue(commands.contains(".requires(LSSServerCommands::permissionLevelAllows)"));
        assertTrue(commands.contains("mayRunServerCommand(player, permissionLevelAllows(player.createCommandSourceStack()))"));
        assertTrue(source("sponge/src/main/java/dev/vox/lss/platform/SpongeLoaderServices.java")
                .contains(".hasPermission(dev.vox.lss.common.Brand.lowerShortName() + \".admin\")"));
        assertTrue(source("sponge/src/main/java/dev/vox/lss/sponge/SpongeCommands.java")
                .contains("String adminNode = Brand.lowerShortName() + \".admin\";"));
        assertTrue(source("paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java")
                .contains("String node = dev.vox.lss.common.Brand.lowerShortName() + \".admin\";"));
    }
}
