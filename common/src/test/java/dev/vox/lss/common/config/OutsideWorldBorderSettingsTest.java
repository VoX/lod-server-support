package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.24.0 plan §2: {@code generation.outside_world_border}. Like
 * {@code service.show_permission_refusal}, its default is in the schema and the templates
 * carry it as a comment only: 0.23 and older reject unknown keys, and a settings file this
 * version creates must still load there.
 */
class OutsideWorldBorderSettingsTest {
    private static final String KEY = "generation.outside_world_border";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test void theDefaultIsToStopAtTheBorderAndTheFileCanLiftIt() {
        for (boolean paper : new boolean[]{false, true}) {
            var codec = new YamlSettingsCodec<>(SettingsSchema.server(paper));
            assertFalse(SettingsSchema.server(paper).defaults().generation().outsideWorldBorder());
            assertFalse(codec.parse(bytes("config_version: 1\n")).configured().generation().outsideWorldBorder());
            assertTrue(codec.parse(bytes("config_version: 1\ngeneration:\n  outside_world_border: true\n"))
                    .configured().generation().outsideWorldBorder());
            assertFalse(new ServerConfigBase(SettingsSchema.server(paper).defaults(), paper).generateOutsideWorldBorder());
        }
    }

    @Test void itNeedsARestart() {
        // A client that was refused a position is not told when the rule is lifted, so the
        // key does not pretend to apply on reload.
        for (boolean paper : new boolean[]{false, true}) {
            var descriptor = SettingsSchema.server(paper).descriptors().stream()
                    .filter(d -> d.path().equals(KEY)).findFirst().orElseThrow();
            assertEquals(SettingsSchema.Timing.R, descriptor.timing());
            assertTrue(descriptor.description().startsWith("[restart]"), descriptor.description());
        }
    }

    @Test void followingTheTemplatesInstructionGivesAFileThatLoads() {
        for (boolean paper : new boolean[]{false, true}) {
            var codec = new YamlSettingsCodec<>(SettingsSchema.server(paper));
            String template = new String(codec.defaults(), StandardCharsets.UTF_8);
            assertEquals(1, template.split("\n  #outside_world_border: true ", -1).length - 1,
                    "one commented line, indented like its neighbours, no space after the #");
            String edited = template.replace("\n  #outside_world_border: true ", "\n  outside_world_border: true ");
            assertTrue(codec.parse(bytes(edited)).configured().generation().outsideWorldBorder(),
                    "the line switches the rule off");
        }
    }

    @Test void noTemplateSetsTheKeySoAFreshFileStillLoadsOnOlderVersions() {
        for (boolean paper : new boolean[]{false, true})
            for (String brand : List.of("lss", "vss")) {
                var codec = new YamlSettingsCodec<>(SettingsSchema.server(paper), brand);
                String template = new String(codec.defaults(), StandardCharsets.UTF_8);
                assertFalse(template.matches("(?s).*(?m)^\\s*outside_world_border:.*"),
                        "an uncommented key would make 0.23 refuse the file (paper=" + paper + ", " + brand + ")");
                assertTrue(template.contains("#outside_world_border: true"), "the commented line documents it");
                var document = codec.parse(codec.defaults());
                assertFalse(document.configured().generation().outsideWorldBorder());
                assertEquals(java.util.Set.of(), document.declaredPaths(List.of(KEY)), "the template does not declare it");
            }
    }

    @Test void theCommentedLineSitsAboveAKeySoARewrittenFileKeepsItsIndent() {
        for (boolean paper : new boolean[]{false, true}) {
            String template = new String(new YamlSettingsCodec<>(SettingsSchema.server(paper)).defaults(), StandardCharsets.UTF_8);
            int commented = template.indexOf("\n  #outside_world_border: true ");
            int next = template.indexOf("\n  concurrency:");
            assertTrue(commented > 0 && next > commented, "above concurrency (paper=" + paper + ")");
            assertTrue(template.substring(commented, next).lines().skip(1).allMatch(line -> line.startsWith("  #")),
                    "nothing but its own comment lines in between");
        }
    }

    @Test void aMigrationFromTheOldJsonFileDoesNotWriteTheKeyEither(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        for (boolean paper : new boolean[]{false, true}) {
            var home = java.nio.file.Files.createDirectories(directory.resolve(paper ? "paper-json" : "mod-json"));
            java.nio.file.Files.writeString(home.resolve("lss-server-config.json"), "{\"lodDistanceChunks\":128}");
            try (var config = new YamlServerConfig(home, paper, s -> {})) {
                assertFalse(config.generateOutsideWorldBorder(), "the default still applies");
            }
            String migrated = java.nio.file.Files.readString(home.resolve("lss-server-config.yaml"));
            assertFalse(migrated.matches("(?s).*(?m)^\\s*outside_world_border:.*"),
                    "the migrated file must load on 0.23 (paper=" + paper + ")");
        }
    }

    /** Both commented keys are handled by one list, in the schema and in the migration. */
    @Test void theListOfCommentedKeysNamesBothAndTheirLegacyNames() {
        assertEquals(java.util.Set.of("service.show_permission_refusal", KEY), SettingsSchema.COMMENTED_SERVER_KEYS.keySet());
        assertEquals(SettingsSchema.COMMENTED_SERVER_KEYS.keySet(), SettingsSchema.COMMENTED_SERVER_LEGACY_NAMES.keySet());
        assertEquals(false, SettingsSchema.COMMENTED_SERVER_KEYS.get(KEY));
        assertEquals(true, SettingsSchema.COMMENTED_SERVER_KEYS.get("service.show_permission_refusal"));
    }
}
