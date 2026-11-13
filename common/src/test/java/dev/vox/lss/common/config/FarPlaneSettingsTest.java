package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code far_players.extend_far_plane} is on by default (0.22.0, decision 0004) and lives in the
 *  experimental sidecar, so turning it off never makes the main file unreadable by older clients. */
class FarPlaneSettingsTest {
    private static final String PATH = "far_players.extend_far_plane";

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test void onByDefaultAndAbsentFromNewFiles() {
        var schema = SettingsSchema.client();
        var codec = new YamlSettingsCodec<>(schema);
        assertTrue(schema.defaults().farPlayers().extendFarPlane());
        assertNull(SettingsSchema.lookup(YamlSettingsCodec.readTree(codec.defaults()), PATH),
                "new main files carry no key an older client would reject");
        assertTrue(YamlSettingsCodec.SIDECAR_PATHS.contains(PATH));
        var descriptor = schema.descriptorsByPath().get(PATH);
        assertNotNull(descriptor);
        assertFalse(descriptor.description().isBlank(), "the reference documents it");
    }

    @Test void turningItOffWritesOnlyTheSidecar() {
        var codec = new YamlSettingsCodec<>(SettingsSchema.client());
        var off = codec.editSplit(codec.parse(codec.defaults()), Map.of(PATH, false));
        String main = new String(off.main(), StandardCharsets.UTF_8);
        assertFalse(main.contains("extend_far_plane:"), main);
        assertEquals(Boolean.FALSE, SettingsSchema.lookup(YamlSettingsCodec.readTree(off.sidecar()), PATH));
        assertFalse(codec.parse(off.main(), off.sidecar()).normalized().farPlayers().extendFarPlane());
        var on = codec.editSplit(codec.parse(off.main(), off.sidecar()), Map.of(PATH, true));
        assertNull(on.sidecar(), "the default needs no sidecar");
    }

    @Test void aSidecarFromBeforeTheDefaultChangedStaysOnAndIsDroppedOnTheNextSave() {
        // A player who had turned it on under 0.20/0.21 has `true` in the sidecar. That now
        // repeats the default: still on, and the next save has nothing left to store.
        var codec = new YamlSettingsCodec<>(SettingsSchema.client());
        var document = codec.parse(codec.defaults(), bytes("far_players:\n  extend_far_plane: true\n"));
        assertTrue(document.normalized().farPlayers().extendFarPlane());
        var saved = codec.editSplit(document, Map.of("lod.receive", false));
        assertNull(saved.sidecar());
        assertTrue(codec.parse(saved.main(), saved.sidecar()).normalized().farPlayers().extendFarPlane());
    }

    @Test void aMainFileCopyIsHonouredAndMovedOutOnTheNextSave() {
        var codec = new YamlSettingsCodec<>(SettingsSchema.client());
        String template = new String(codec.defaults(), StandardCharsets.UTF_8);
        String withKey = template.replace("far_players:\n", "far_players:\n  extend_far_plane: false\n");
        assertNotEquals(template, withKey);
        var document = codec.parse(bytes(withKey));
        assertFalse(document.configured().farPlayers().extendFarPlane());
        var saved = codec.editSplit(document, Map.of("lod.receive", false));
        assertFalse(new String(saved.main(), StandardCharsets.UTF_8).contains("extend_far_plane:"));
        assertFalse(codec.parse(saved.main(), saved.sidecar()).configured().farPlayers().extendFarPlane());
    }
}
