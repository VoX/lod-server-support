package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 0.23.0 plan §1: a server settings file that does not load is described in full, once. */
class SettingsProblemStartupTest {
    @TempDir Path directory;
    private static final String BROKEN = "config_version: 1\nservice:\n  enabled: true\n  require_permision: true\nfar_player:\n  mode: \"off\"\n";
    private static final List<String> BLOCK = List.of(
            "lss-server-config.yaml has 2 problems, so the mod is switched off:",
            "  line 4: unknown setting service.require_permision. Did you mean service.require_permission?",
            "  line 5: unknown setting far_player. Did you mean far_players?",
            "Fix the file and restart the server. The file was not changed.");

    @Test void aFileThatDoesNotLoadLeavesTheModOffAndListsEverything() throws Exception {
        Path file = directory.resolve("lss-server-config.yaml");
        Files.writeString(file, BROKEN);
        var log = new ArrayList<String>();
        try (var config = new YamlServerConfig(directory, false, line -> {}, log::add)) {
            assertEquals(BLOCK, log);
            assertEquals(2, config.startupProblems().size());
            assertEquals("lss-server-config.yaml", config.settingsFileName());
            // What the mod does with the file is what it did before: everything off.
            assertEquals("Unknown settings path: service.require_permision", config.startupError());
            assertFalse(config.snapshot().service().enabled());
            assertFalse(config.snapshot().generation().enabled());
            assertEquals("off", config.snapshot().farPlayers().mode());
            assertEquals(BROKEN, Files.readString(file), "reads never rewrite");
        }
    }

    @Test void theSameRefusalIsOneBlockAcrossTheClassLoadAndTheServerStartRead() throws Exception {
        // Fabric, NeoForge and Sponge read the file when the class loads and again at server start.
        Path file = directory.resolve("lss-server-config.yaml");
        Files.writeString(file, BROKEN);
        var log = new ArrayList<String>();
        try (var config = new YamlServerConfig(directory, false, line -> {}, log::add)) {
            config.restartSettingsLifecycle();
            config.restartSettingsLifecycle();
            assertEquals(BLOCK, log, "one block, not three");
            // A different mistake is a new block.
            Files.writeString(file, "config_version: 1\nnonsense: 1\n");
            config.restartSettingsLifecycle();
            assertEquals(BLOCK.size() + 3, log.size());
            assertEquals("lss-server-config.yaml has 1 problem, so the mod is switched off:", log.get(BLOCK.size()));
            // Fixed: nothing more is said and nothing is remembered.
            Files.writeString(file, "config_version: 1\n");
            config.restartSettingsLifecycle();
            assertTrue(config.startupProblems().isEmpty());
            assertNull(config.startupError());
            assertEquals(BLOCK.size() + 3, log.size());
            // Broken the same way again after a good start: said again.
            Files.writeString(file, "config_version: 1\nnonsense: 1\n");
            config.restartSettingsLifecycle();
            assertEquals(BLOCK.size() + 6, log.size());
        }
    }

    @Test void aFileThatLoadsHasNoProblems() throws Exception {
        Files.writeString(directory.resolve("lss-server-config.yaml"), "config_version: 1\nservice:\n  require_permission: Yes\n");
        var log = new ArrayList<String>();
        try (var config = new YamlServerConfig(directory, false, line -> {}, log::add)) {
            assertTrue(log.isEmpty());
            assertTrue(config.startupProblems().isEmpty());
            assertTrue(config.snapshot().service().requirePermission(), "the usual spelling of a boolean loads");
        }
    }

    @Test void aFailedReloadCarriesTheWholeListAndKeepsTheRunningSettings() throws Exception {
        Path file = directory.resolve("lss-server-config.yaml");
        Files.writeString(file, "config_version: 1\n");
        try (var config = new YamlServerConfig(directory, false, line -> {}, line -> fail("startup was fine"))) {
            Files.writeString(file, BROKEN);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> config.reload(Runnable::run, (previous, next, revision) ->
                            java.util.concurrent.CompletableFuture.completedFuture(null)).get());
            assertEquals("Unknown settings path: service.require_permision", SettingsReload.message(failure));
            assertEquals(List.of("line 4: unknown setting service.require_permision. Did you mean service.require_permission?",
                    "line 5: unknown setting far_player. Did you mean far_players?"), SettingsReload.problemLines(failure));
            assertTrue(config.snapshot().service().enabled(), "the running settings stay");
            assertTrue(config.startupProblems().isEmpty(), "startup was fine: nothing is announced");
        }
    }

    @Test void aFailureThatIsNotAboutTheFilesContentsHasNoList() {
        assertEquals(List.of(), SettingsReload.problemLines(new IllegalStateException("Server stopped")));
        assertEquals(List.of(), SettingsReload.problemLines(new SettingsException("Settings file is missing: x")));
    }
}
