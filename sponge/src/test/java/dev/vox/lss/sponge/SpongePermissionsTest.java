package dev.vox.lss.sponge;

import org.junit.jupiter.api.Test;
import org.spongepowered.api.util.Tristate;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sponge's permission table equals Paper's plugin.yml (sponge-support-plan.md §1.6): both
 * brand spellings of the privacy and service nodes with the same defaults, and the admin
 * node with NO default (Sponge's op-level fallback = Paper's {@code default: op}). Brand is
 * not loaded in plain JUnit, so the admin node reads the LSS spelling, as plugin.yml does.
 */
class SpongePermissionsTest {

    private static Map<String, String> paperDefaults() {
        var out = new TreeMap<String, String>();
        String yml = SpongeTestSupport.read(SpongeTestSupport.repoRoot().resolve("paper/src/main/resources/plugin.yml"));
        String section = yml.substring(yml.indexOf("\npermissions:") + 1);
        String node = null;
        for (String line : section.split("\n")) {
            // `_` allowed: lss.map_ingestion (map-ingestion permission plan §6).
            if (line.matches("^  [a-z._]+:\\s*$")) node = line.strip().replace(":", "");
            else if (node != null && line.strip().startsWith("default:")) out.put(node, line.strip().substring(8).strip());
        }
        return out;
    }

    @Test
    void nodesAndDefaultsMirrorPaperPluginYml() {
        var sponge = new TreeMap<String, String>();
        for (var node : SpongePermissions.nodes()) {
            Tristate d = node.defaultValue();
            sponge.put(node.id(), d == null ? "op" : d == Tristate.TRUE ? "true" : "false");
        }
        assertEquals(paperDefaults(), sponge);
        // The parse must actually see the underscore nodes (a regex that skipped them would
        // drop them from BOTH sides and still compare equal).
        assertEquals("false", paperDefaults().get("lss.map_ingestion"));
        assertEquals("false", paperDefaults().get("vss.map_ingestion"));
    }

    /** Hide-permission plan §2 (0.21.0): the far-player hide nodes stay registered default
     *  FALSE, and their descriptions say the node hides only while the server setting is on. */
    @Test
    void theFarPlayerHideNodesSayTheyNeedTheServerSetting() {
        int seen = 0;
        for (var node : SpongePermissions.nodes()) {
            if (!node.id().endsWith(".farplayers.hidden")) continue;
            seen++;
            assertEquals(Tristate.FALSE, node.defaultValue(), node.id());
            org.junit.jupiter.api.Assertions.assertTrue(node.description()
                            .contains("only while far_players.hide_permission_holders is true"),
                    node.id() + ": " + node.description());
            org.junit.jupiter.api.Assertions.assertFalse(node.description().contains("never shown"),
                    node.id() + ": " + node.description());
        }
        assertEquals(2, seen, "both brand spellings are registered");
    }
}
