package dev.vox.lss.config.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Line-NEUTRAL pins for the options-page entrypoints (sodium-options-page-generations-
 * plan.md §4 — deliberately its own class: {@code FabricModJsonContractTest}'s per-line
 * flavors conflict on every cherry-pick, this one is identical on every line):
 * the {@code sodium:config_api_user} entrypoint is declared EXACTLY when the modern
 * (0.8+) walker source exists — a 0.7-only line deletes the file AND the entrypoint,
 * together; the {@code modmenu} entrypoint is declared exactly when its class exists
 * (present on every line — the switch is reflective); the legacy mixin config is listed.
 */
class ClientMenuEntrypointContractTest {

    @Test
    void theModernEntrypointTracksTheWalkerFile() throws IOException {
        JsonObject entrypoints = modJson().getAsJsonObject("entrypoints");
        boolean walkerExists = exists("fabric/src/main/java/dev/vox/lss/config/LSSConfigMenu.java");
        boolean declared = entrypoints.has("sodium:config_api_user");
        assertEquals(walkerExists, declared,
                "sodium:config_api_user must be declared iff LSSConfigMenu.java exists (delete both on a 0.7-only line)");
        if (declared) {
            assertEquals("dev.vox.lss.config.LSSConfigMenu",
                    entrypoints.getAsJsonArray("sodium:config_api_user").get(0).getAsString());
        }
    }

    @Test
    void theModMenuEntrypointTracksItsClass() throws IOException {
        JsonObject entrypoints = modJson().getAsJsonObject("entrypoints");
        boolean exists = exists("fabric/src/main/java/dev/vox/lss/config/LSSModMenuIntegration.java");
        assertEquals(exists, entrypoints.has("modmenu"),
                "the modmenu entrypoint must be declared iff LSSModMenuIntegration.java exists");
        assertTrue(exists, "the ModMenu switch is reflective and belongs on every line");
    }

    /** The plan's headline ("adding an option is a one-file change") for the renderer T1
     *  cannot classload: no hand-written option, key or id in the walker source. */
    @Test
    void theModernWalkerCarriesNoHandWrittenOptions() throws IOException {
        if (!exists("fabric/src/main/java/dev/vox/lss/config/LSSConfigMenu.java")) {
            return; // a 0.7-only line
        }
        String src = SodiumGenerationTest.stripComments(Files.readString(
                ClientOptionCatalogTest.locate("fabric/src/main/java/dev/vox/lss/config/LSSConfigMenu.java")));
        assertTrue(src.contains("ClientOptionCatalog.pages()"), "the walker must iterate the catalog");
        assertTrue(!src.contains("\"lss.config."), "translation keys come from the catalog, never literals");
        assertTrue(!src.replace("\"lss:icon.png\"", "").contains(".parse(\"lss:"),
                "option ids come from the catalog (the icon fallback is the one literal)");
        // The page buttons (re-download plan): walked from the catalog like the options, opened
        // through the shared dispatcher, and contained so a failure costs only the buttons.
        int buttons = src.indexOf("addActions(builder, page, pageSpec, actions);");
        assertTrue(buttons > 0 && buttons < src.indexOf("mod.addPage(page);"),
                "each page's buttons are added before the page is registered");
        assertTrue(src.contains("for (ActionSpec spec : pageSpec.actions())"), "the walker must iterate the page's actions");
        assertTrue(java.util.regex.Pattern.compile("createExternalButtonOption\\(\\w+\\.parse\\(spec\\.id\\(\\)\\)\\)")
                        .matcher(src).find(), "a button is Sodium's external button, keyed by the catalog id");
        assertTrue(src.contains("ClientMenuActions::open"), "production opens buttons through the dispatcher");
        assertTrue(src.contains("catch (Throwable"), "a button that cannot be built must not cost the page");
    }

    @Test
    void theLegacyMixinConfigIsListedAndPresent() throws IOException {
        assertTrue(modJson().getAsJsonArray("mixins").toString().contains("\"lss-sodium-legacy.mixins.json\""));
        assertTrue(exists("fabric/src/main/resources/lss-sodium-legacy.mixins.json"));
    }

    private static JsonObject modJson() throws IOException {
        return JsonParser.parseString(Files.readString(
                ClientOptionCatalogTest.locate("fabric/src/main/resources/fabric.mod.json"))).getAsJsonObject();
    }

    private static boolean exists(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            if (Files.exists(dir.resolve("xplat/src/main/java"))) {
                return Files.exists(dir.resolve(repoRelative));
            }
        }
        throw new IllegalStateException("cannot locate the repo root");
    }
}
