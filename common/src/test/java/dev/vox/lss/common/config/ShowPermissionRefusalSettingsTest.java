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

    @Test void itIsAReloadableBooleanOnEveryPlatform() {
        var descriptor = SettingsSchema.server(false).descriptorsByPath().get("service.show_permission_refusal");
        assertEquals(SettingsSchema.Kind.BOOLEAN, descriptor.kind());
        assertEquals(SettingsSchema.Timing.H, descriptor.timing());
        assertEquals(SettingsSchema.Platform.ALL, descriptor.platform());
        assertFalse(descriptor.description().isBlank(), "the reference documents it although the template only comments it");
        assertNull(SettingsSchema.client().descriptorsByPath().get("service.show_permission_refusal"));
    }
}
