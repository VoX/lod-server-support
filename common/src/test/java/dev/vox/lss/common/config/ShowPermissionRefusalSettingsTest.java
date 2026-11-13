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
        for (boolean paper : new boolean[]{false, true}) {
            var codec = new YamlSettingsCodec<>(SettingsSchema.server(paper));
            assertTrue(SettingsSchema.server(paper).defaults().service().showPermissionRefusal());
            assertTrue(codec.parse(bytes("config_version: 1\n")).configured().service().showPermissionRefusal());
            assertFalse(codec.parse(bytes("config_version: 1\nservice:\n  show_permission_refusal: false\n"))
                    .configured().service().showPermissionRefusal());
        }
    }

    @Test void noTemplateSetsTheKeySoAFreshFileStillLoadsOnOlderVersions() {
        for (boolean paper : new boolean[]{false, true})
            for (String brand : List.of("lss", "vss")) {
                var codec = new YamlSettingsCodec<>(SettingsSchema.server(paper), brand);
                String template = new String(codec.defaults(), StandardCharsets.UTF_8);
                assertFalse(template.matches("(?s).*(?m)^\\s*show_permission_refusal:.*"),
                        "an uncommented key would make 0.22.0 refuse the file (paper=" + paper + ", " + brand + ")");
                assertTrue(template.contains("# show_permission_refusal: true"), "the commented line documents it");
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
