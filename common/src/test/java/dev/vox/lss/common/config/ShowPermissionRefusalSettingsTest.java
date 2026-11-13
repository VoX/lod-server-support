package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.23.0 plan §3: {@code service.show_permission_refusal}. Its default is in the schema and the
 * templates carry it as a comment only, because 0.22.0 and older reject unknown keys: a settings
 * file this version creates must still load there.
 */
class ShowPermissionRefusalSettingsTest {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test void theDefaultIsToTellAndTheFileCanSwitchItOff() {
        // On by default: the owner's decision of 2026-11-13 (plan §8.1).
        for (boolean paper : new boolean[]{false, true}) {
            var codec = new YamlSettingsCodec<>(SettingsSchema.server(paper));
            assertTrue(SettingsSchema.server(paper).defaults().service().showPermissionRefusal());
            assertTrue(codec.parse(bytes("config_version: 1\n")).configured().service().showPermissionRefusal());
            assertFalse(codec.parse(bytes("config_version: 1\nservice:\n  show_permission_refusal: false\n"))
                    .configured().service().showPermissionRefusal());
        }
    }

    @Test void followingTheTemplatesInstructionGivesAFileThatLoads() {
        // 0.23.0 review: "remove the first #" from "  # show_…" left a three-space indent
        // under service:, which is a syntax error, so the mod was off. The line now has no
        // space after its "#", and deleting that "#" is all the instruction asks for.
        for (boolean paper : new boolean[]{false, true}) {
            var codec = new YamlSettingsCodec<>(SettingsSchema.server(paper));
            String template = new String(codec.defaults(), StandardCharsets.UTF_8);
            assertEquals(1, template.split("\n  #show_permission_refusal: false ", -1).length - 1,
                    "one commented line, indented like its neighbours, no space after the #");
            assertTrue(template.contains("delete the \"#\" in front of the name"));
            String edited = template.replace("\n  #show_permission_refusal: false ", "\n  show_permission_refusal: false ");
            assertFalse(codec.parse(bytes(edited)).configured().service().showPermissionRefusal(),
                    "the line switches the default off");
        }
    }

    @Test void noTemplateSetsTheKeySoAFreshFileStillLoadsOnOlderVersions() {
        for (boolean paper : new boolean[]{false, true})
            for (String brand : List.of("lss", "vss")) {
                var codec = new YamlSettingsCodec<>(SettingsSchema.server(paper), brand);
                String template = new String(codec.defaults(), StandardCharsets.UTF_8);
                assertFalse(template.matches("(?s).*(?m)^\\s*show_permission_refusal:.*"),
                        "an uncommented key would make 0.22.0 refuse the file (paper=" + paper + ", " + brand + ")");
                assertTrue(template.contains("#show_permission_refusal: false"), "the commented line documents it");
                var document = codec.parse(codec.defaults());
                assertTrue(document.configured().service().showPermissionRefusal());
                assertEquals(java.util.Set.of(), document.declaredPaths(List.of("service.show_permission_refusal")),
                        "the template does not declare it");
            }
    }

    @Test void theCommentedLineSitsAboveAKeySoARewrittenFileKeepsItsIndent() {
        // A comment block that ends a group is re-emitted at column 0 by a migration; one that
        // precedes a key keeps that key's indent.
        for (boolean paper : new boolean[]{false, true}) {
            String template = new String(new YamlSettingsCodec<>(SettingsSchema.server(paper)).defaults(), StandardCharsets.UTF_8);
            int commented = template.indexOf("\n  #show_permission_refusal: false ");
            int next = template.indexOf("\n  require_permission: ");
            assertTrue(commented > 0 && next > commented, "above require_permission (paper=" + paper + ")");
            assertTrue(template.substring(commented, next).lines().skip(1).allMatch(line -> line.startsWith("  #")),
                    "nothing but its own comment lines in between");
        }
    }

    @Test void aMigrationFromTheOldJsonFileDoesNotWriteTheKeyEither(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        // Regression review, 2026-11-13: the migration wrote every schema value, this key
        // included, so a migrated file did not load on 0.22 and the template's own
        // instruction ("delete the #") produced a duplicate key.
        for (boolean paper : new boolean[]{false, true}) {
            var home = java.nio.file.Files.createDirectories(directory.resolve(paper ? "paper-json" : "mod-json"));
            java.nio.file.Files.writeString(home.resolve("lss-server-config.json"), "{\"lodDistanceChunks\":128}");
            try (var config = new YamlServerConfig(home, paper, s -> {})) {
                assertTrue(config.showPermissionRefusal(), "the default still applies");
            }
            String migrated = java.nio.file.Files.readString(home.resolve("lss-server-config.yaml"));
            assertFalse(migrated.matches("(?s).*(?m)^\\s*show_permission_refusal:.*"),
                    "an uncommented key would make 0.22.0 refuse the migrated file (paper=" + paper + ")");
            assertEquals(1, migrated.split("\n  #show_permission_refusal: false ", -1).length - 1,
                    "the commented line survives the migration (paper=" + paper + "): "
                            + migrated.substring(0, Math.min(migrated.length(), 900)));
            // The template's instruction still gives a file that loads.
            var codec = new YamlSettingsCodec<>(SettingsSchema.server(paper));
            assertFalse(codec.parse(bytes(migrated.replace("\n  #show_permission_refusal: false ",
                    "\n  show_permission_refusal: false "))).configured().service().showPermissionRefusal());
        }
    }

    @Test void itIsAReloadableBooleanOnEveryPlatform() {
        var descriptor = SettingsSchema.server(false).descriptorsByPath().get("service.show_permission_refusal");
        assertEquals(SettingsSchema.Kind.BOOLEAN, descriptor.kind());
        assertEquals(SettingsSchema.Timing.H, descriptor.timing());
        assertEquals(SettingsSchema.Platform.ALL, descriptor.platform());
        assertFalse(descriptor.description().isBlank(), "the reference documents it although the template only comments it");
        assertNull(SettingsSchema.client().descriptorsByPath().get("service.show_permission_refusal"));
    }
}
