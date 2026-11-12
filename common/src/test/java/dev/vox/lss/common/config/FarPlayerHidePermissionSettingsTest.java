package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code far_players.hide_permission_holders} (hide-permission plan §2/§3, 0.21.0): default
 * false on every platform (the hide permission hides nobody by itself), hot via
 * {@code /lsslod reload}, a legacy JSON name that never existed (a migrated file reads false),
 * {@code config_version} still 1, and the upgraded-file rule: a file without the key reads
 * false, is never rewritten at startup, and gains the key only when the setting is changed.
 */
class FarPlayerHidePermissionSettingsTest {
    private static final String PATH = "far_players.hide_permission_holders";

    @TempDir Path directory;

    @Test void defaultsOffOnEveryPlatform() {
        for (boolean paper : new boolean[]{false, true}) {
            var schema = SettingsSchema.server(paper);
            assertFalse(schema.defaults().farPlayers().hidePermissionHolders(), "default off, paper=" + paper);
            var d = schema.descriptorsByPath().get(PATH);
            assertNotNull(d, "the key exists, paper=" + paper);
            assertEquals(SettingsSchema.Kind.BOOLEAN, d.kind());
            assertEquals(SettingsSchema.Timing.H, d.timing(), "hot: edit the file, then reload");
            assertEquals(SettingsSchema.Platform.ALL, d.platform());
            assertEquals(Boolean.FALSE, d.defaultValue());
            assertFalse(d.sensitive(), "a boolean policy, not private data");
            assertEquals("Hide every player who holds the far-player hide permission.", d.description());
            assertFalse(new ServerConfigBase(schema.defaults(), paper).farPlayersHidePermissionHolders());
        }
        assertNull(SettingsSchema.client().descriptorsByPath().get(PATH), "a server setting only");
    }

    @Test void theTemplatesSayWhatOffAndOnMean() {
        for (boolean paper : new boolean[]{false, true}) {
            String template = SettingsSchema.server(paper).template();
            int key = template.indexOf("  hide_permission_holders: false");
            assertTrue(key > 0, "template key, paper=" + paper);
            String before = template.substring(template.lastIndexOf("\nfar_players:", key), key);
            assertTrue(before.contains("lss.farplayers.hidden (vss.farplayers.hidden)"), before);
            assertTrue(before.contains("in every mode (the behaviour before 0.21.0)"), before);
            assertTrue(before.contains("Off: the\n  # permission hides nobody"), before);
            assertTrue(before.contains("Share My Position"), before);
            assertTrue(before.contains("(in mode \"required\", only holders can)"), before);
            // The VSS file names the same two nodes (both spellings are declared in both jars).
            String vss = new String(new YamlSettingsCodec<>(SettingsSchema.server(paper), "vss").defaults(),
                    StandardCharsets.UTF_8);
            assertTrue(vss.contains("lss.farplayers.hidden (vss.farplayers.hidden)"), "paper=" + paper);
        }
    }

    @Test void theLegacyKeyNeverExistedSoMigrationReadsFalse() {
        var d = SettingsSchema.server(false).descriptorsByPath().get(PATH);
        assertEquals("farPlayersHidePermissionHolders", d.legacyKey());
        var migrated = LegacySettingsMigration.migrate(
                "{\"farPlayers\":\"on\"}".getBytes(StandardCharsets.UTF_8), SettingsSchema.server(false));
        assertFalse(migrated.settings().farPlayers().hidePermissionHolders());
    }

    @Test void aFileWithoutTheKeyReadsFalseAndAFileWithItReadsTrue() {
        var codec = new YamlSettingsCodec<>(SettingsSchema.server(false));
        var bare = codec.parse("config_version: 1\nfar_players:\n  mode: \"on\"\n"
                .getBytes(StandardCharsets.UTF_8));
        assertFalse(bare.configured().farPlayers().hidePermissionHolders(),
                "an upgraded 0.20 file (no key): the permission hides nobody by itself");
        var on = codec.parse("config_version: 1\nfar_players:\n  hide_permission_holders: true\n"
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(on.configured().farPlayers().hidePermissionHolders());
        assertTrue(on.normalizations().isEmpty());
        assertThrows(SettingsException.class, () -> codec.parse(
                "config_version: 1\nfar_players:\n  hide_permission_holders: \"yes\"\n"
                        .getBytes(StandardCharsets.UTF_8)), "a boolean, never a string");
    }

    /** The standing rule: 0.20 and older reject unknown keys, so an upgraded file must not
     *  gain the key unless the operator changes the setting. */
    @Test void anUpgradedFileGainsTheKeyOnlyWhenTheSettingIsChanged() throws Exception {
        for (boolean paper : new boolean[]{false, true}) {
            Path target = Files.createDirectories(directory.resolve(paper ? "paper" : "mod"));
            String template = SettingsSchema.server(paper).template();
            int comment = template.indexOf("  # Hide every player who holds");
            int keyEnd = template.indexOf('\n', template.indexOf("  hide_permission_holders: false")) + 1;
            assertTrue(comment > 0 && keyEnd > comment);
            // The file a 0.20 server left behind: the template without the new key.
            byte[] upgraded = (template.substring(0, comment) + template.substring(keyEnd))
                    .getBytes(StandardCharsets.UTF_8);
            Path file = target.resolve("lss-server-config.yaml");
            Files.write(file, upgraded);

            var store = new SettingsStore<>(target, "lss", SettingsSchema.server(paper));
            var document = store.initialize();
            assertFalse(document.configured().farPlayers().hidePermissionHolders());
            assertArrayEquals(upgraded, Files.readAllBytes(file), "startup never rewrites the file");

            var other = store.saveDraft(document.hash(), Map.of("far_players.send_spectators", true));
            assertFalse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                    .contains("hide_permission_holders"), "an unrelated save does not add the key");
            assertFalse(other.configured().farPlayers().hidePermissionHolders());

            var changed = store.saveDraft(other.hash(), Map.of(PATH, true));
            assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                    .contains("  hide_permission_holders: true"), "changing the setting writes it");
            assertTrue(changed.configured().farPlayers().hidePermissionHolders());
            assertTrue(store.read().configured().farPlayers().sendSpectators(), "the earlier edit survives");
        }
    }

    @Test void aReloadAppliesItWithoutARestartOnBothPlatforms() throws Exception {
        for (boolean paper : new boolean[]{false, true}) {
            Path target = Files.createDirectories(directory.resolve(paper ? "paper-live" : "mod-live"));
            try (var config = new YamlServerConfig(target, paper, s -> {})) {
                assertFalse(config.farPlayersHidePermissionHolders(), "a new file starts off");
                assertTrue(Files.readString(config.settingsPath()).contains("  hide_permission_holders: false"),
                        "a new file carries the key from the template");
                var handle = config.settingsHandle();
                handle.store().saveDraft(handle.store().read().hash(), Map.of(PATH, true));
                assertFalse(config.farPlayersHidePermissionHolders(), "a disk edit alone changes nothing");
                var outcome = config.reload(Runnable::run,
                        (a, b, r) -> CompletableFuture.completedFuture(null)).get(30, TimeUnit.SECONDS);
                assertTrue(config.farPlayersHidePermissionHolders(), "applied by the reload, no restart");
                assertTrue(config.snapshotView().farPlayersHidePermissionHolders());
                var lines = new ArrayList<>(ReloadFeedback.lines(config.settingsPath(), outcome));
                assertTrue(outcome.commit().changedPaths().contains(PATH), lines.toString());
                assertTrue(lines.stream().noneMatch(l -> l.startsWith("Restart required")), lines.toString());
                assertTrue(lines.stream().noneMatch(l -> l.startsWith("Existing legacy clients may")),
                        "not a distance or generation change");

                handle.store().saveDraft(handle.store().read().hash(), Map.of(PATH, false));
                config.reload(Runnable::run,
                        (a, b, r) -> CompletableFuture.completedFuture(null)).get(30, TimeUnit.SECONDS);
                assertFalse(config.farPlayersHidePermissionHolders(), "and back off the same way");
            }
        }
    }

    @Test void configVersionStaysOne() {
        assertTrue(SettingsSchema.server(false).template().contains("config_version: 1 "),
                "no schema version bump for one key");
    }
}
