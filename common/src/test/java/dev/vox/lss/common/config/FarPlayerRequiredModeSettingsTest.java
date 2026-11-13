package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code far_players.mode: required} (hide-permission plan §2b, 0.21.0): the fourth mode
 * validates on every platform, reaches the broadcast core as the string {@code required},
 * applies on reload without a restart, and is listed with the other three in the
 * templates. The default stays {@code on}, an unknown value still fails, and the legacy
 * JSON normaliser keeps unknown values privacy-safe ({@code off}).
 */
class FarPlayerRequiredModeSettingsTest {
    private static final String PATH = "far_players.mode";

    @TempDir Path directory;

    @Test void theFourthModeValidatesAndReachesTheCoreUnchanged() {
        for (boolean paper : new boolean[]{false, true}) {
            var schema = SettingsSchema.server(paper);
            assertEquals("on", schema.defaults().farPlayers().mode(), "the default stays on, paper=" + paper);
            var codec = new YamlSettingsCodec<>(schema);
            var document = codec.parse("config_version: 1\nfar_players:\n  mode: \"required\"\n"
                    .getBytes(StandardCharsets.UTF_8));
            assertEquals("required", document.configured().farPlayers().mode());
            assertTrue(document.normalizations().isEmpty());
            assertEquals("required", new ServerConfigBase(document.normalized(), paper).farPlayers(),
                    "the core compares against exactly this string");
        }
        // The three older values keep their core spellings (opt_in is opt-in there).
        var schema = SettingsSchema.server(false);
        for (var entry : Map.of("off", "off", "opt_in", "opt-in", "on", "on").entrySet()) {
            var settings = schema.fromValues(Map.of(PATH, entry.getKey())).normalized();
            assertEquals(entry.getValue(), new ServerConfigBase(settings, false).farPlayers());
        }
    }

    @Test void anUnknownValueStillFailsAndNamesAllFourValues() {
        var codec = new YamlSettingsCodec<>(SettingsSchema.server(false));
        for (String value : List.of("always", "Required", "require", "opt-in", "")) {
            var failure = assertThrows(SettingsException.class, () -> codec.parse(
                    ("config_version: 1\nfar_players:\n  mode: \"" + value + "\"\n").getBytes(StandardCharsets.UTF_8)),
                    "'" + value + "' must not load: YAML takes the exact spellings only");
            assertEquals("Expected off, opt_in, on or required at far_players.mode", failure.getMessage());
        }
    }

    @Test void theTemplatesListAllFourModesWithOneLineEach() {
        for (boolean paper : new boolean[]{false, true}) {
            var schema = SettingsSchema.server(paper);
            String template = schema.template();
            int key = template.indexOf("  mode: \"on\" # \"off\", \"opt_in\", \"on\" (players may opt out) or \"required\"");
            assertTrue(key > 0, "the key's own comment lists the four values, paper=" + paper);
            String before = template.substring(template.lastIndexOf("\n# Sends lightweight", key), key);
            for (String mode : List.of("\"off\"      - ", "\"opt_in\"   - ", "\"on\"       - ", "\"required\" - "))
                assertTrue(before.contains("#   " + mode), mode + " is described: " + before);
            assertTrue(before.contains("cannot opt out, except players who hold the"), before);
            assertTrue(before.contains("lss.farplayers.hidden (vss.farplayers.hidden) permission"), before);
            assertTrue(before.contains("is told so once in chat"), before);
            assertFalse(template.contains("all modes honor exclusions and sharing opt-outs"),
                    "that sentence is false for required");
            assertEquals("\"off\", \"opt_in\", \"on\" (players may opt out) or \"required\" (only permission holders may).",
                    schema.descriptorsByPath().get(PATH).description());
        }
    }

    @Test void aReloadAppliesTheModeWithoutARestart() throws Exception {
        for (boolean paper : new boolean[]{false, true}) {
            Path target = Files.createDirectories(directory.resolve(paper ? "paper" : "mod"));
            try (var config = new YamlServerConfig(target, paper, s -> {})) {
                assertEquals("on", config.farPlayers());
                var handle = config.settingsHandle();
                handle.store().saveDraft(handle.store().read().hash(), Map.of(PATH, "required"));
                assertEquals("on", config.farPlayers(), "a disk edit alone changes nothing");
                var outcome = config.reload(Runnable::run,
                        (a, b, r) -> CompletableFuture.completedFuture(null)).get(30, TimeUnit.SECONDS);
                assertEquals("required", config.farPlayers(), "applied by the reload");
                assertTrue(Files.readString(config.settingsPath()).contains("  mode: \"required\""));
                var lines = new ArrayList<>(ReloadFeedback.lines(config.settingsPath(), outcome));
                assertTrue(outcome.commit().changedPaths().contains(PATH), lines.toString());
                assertTrue(lines.stream().noneMatch(l -> l.startsWith("Restart required")), lines.toString());
            }
        }
    }

    @Test void theLegacyNormalisersKnowTheValueAndKeepUnknownOnesOff() {
        for (String spelled : List.of("required", " Required ", "REQUIRED")) {
            var migrated = LegacySettingsMigration.migrate(
                    ("{\"farPlayers\":\"" + spelled + "\"}").getBytes(StandardCharsets.UTF_8),
                    SettingsSchema.server(false));
            assertEquals("required", migrated.settings().farPlayers().mode(), spelled);
        }
        var unknown = LegacySettingsMigration.migrate(
                "{\"farPlayers\":\"mandatory\"}".getBytes(StandardCharsets.UTF_8), SettingsSchema.server(false));
        assertEquals("off", unknown.settings().farPlayers().mode(), "unknown stays privacy-safe");
        assertEquals("required", ServerConfigBase.clampFarPlayersMode(" Required "));
        assertEquals("opt-in", ServerConfigBase.clampFarPlayersMode("opt_in"));
        assertEquals("on", ServerConfigBase.clampFarPlayersMode("on"));
        assertEquals("off", ServerConfigBase.clampFarPlayersMode("mandatory"));
        assertEquals("off", ServerConfigBase.clampFarPlayersMode(null));
    }
}
