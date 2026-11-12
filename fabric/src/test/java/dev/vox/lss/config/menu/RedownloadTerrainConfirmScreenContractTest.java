package dev.vox.lss.config.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source pins for the Re-download Terrain confirm screen (re-download plan, decisions 2 to 6).
 * A unit JVM cannot construct a screen (it needs a running client), so the behaviour itself
 * runs in the client gametest; these pin the rules that are easy to lose in an edit and that
 * decide whether the dialog can cost a player something:
 *
 * <ul>
 *   <li>it returns to the SAME parent and never builds a settings screen (a new one resets
 *       Sodium's staged edits) and never touches the settings draft;</li>
 *   <li>the clear runs inside {@code try/catch (RuntimeException)}: a click has no command
 *       dispatcher around it, so a thrown clear would be a crash report;</li>
 *   <li>the scope is read again on confirm, and Cancel has the initial focus;</li>
 *   <li>closing answers "no"; the command root in the text comes from the brand.</li>
 * </ul>
 *
 * The Escape press/release rule is {@link ScreenEscapeKeyContractTest}'s (it finds this
 * screen by its guard).
 */
class RedownloadTerrainConfirmScreenContractTest {

    private static final String SOURCE =
            "xplat/src/main/java/dev/vox/lss/config/menu/RedownloadTerrainConfirmScreen.java";

    private static String source() throws IOException {
        return SodiumGenerationTest.stripComments(Files.readString(ClientOptionCatalogTest.locate(SOURCE)));
    }

    @Test
    void itReturnsToTheSameParentAndNeverBuildsOrEditsSettings() throws IOException {
        String src = source();
        assertTrue(src.contains("show(parent)"), "Cancel, Back and Escape show the parent instance");
        assertTrue(src.contains("Screen next = parent;") && src.contains("show(next)"),
                "the confirm path ends on the parent too (or on the dialog again when the scope changed)");
        assertTrue(Pattern.compile("finally\\s*\\{\\s*show\\(next\\);").matcher(src).find(),
                "returning is in a finally block: no failure may leave the dialog open or close the menu");
        for (String barred : List.of("createScreen", "SodiumConfigScreens", "LSSClientConfig", ".edits()",
                "ClientSettingsEditSession", "setScreen(null)", "setScreenAndShow(null)")) {
            assertFalse(src.contains(barred), "the dialog must not reference " + barred);
        }
    }

    @Test
    void aFailedClearIsReportedNeverRethrown() throws IOException {
        String src = source();
        int clear = src.indexOf("clear.accept(");
        assertTrue(clear > 0, "the clear is the injected action");
        assertEquals(clear, src.lastIndexOf("clear.accept("), "exactly one call site");
        int tryStart = src.lastIndexOf("try {", clear);
        int caught = src.indexOf("catch (RuntimeException failure)", clear);
        assertTrue(tryStart > 0 && caught > clear, "the clear must sit inside try/catch (RuntimeException)");
        assertFalse(src.substring(tryStart, clear).contains("}"), "…directly inside that try block");
        assertTrue(src.contains("\"lss.redownload.failed\""), "the failure is told to the player");
        assertFalse(src.contains("throw "), "nothing is rethrown from a screen callback");
    }

    @Test
    void theScopeIsReadAgainOnConfirmAndAWiderOneNeverRuns() throws IOException {
        String src = source();
        int recheck = src.indexOf("ClearCacheScope now = scope.get();");
        int changed = src.indexOf("if (now != shown)");
        int clear = src.indexOf("clear.accept(");
        assertTrue(recheck > 0 && recheck < changed && changed < clear,
                "confirm reads the scope again and compares it with the one shown before clearing");
        String branch = src.substring(changed, clear);
        assertTrue(branch.contains("next = create(parent, now, scope, clear);") && branch.contains("return;"),
                "a changed scope shows the dialog again with the new text and clears nothing");
    }

    @Test
    void cancelHasTheInitialFocusAndClosingAnswersNo() throws IOException {
        String src = source();
        assertTrue(Pattern.compile("protected void setInitialFocus\\(\\)\\s*\\{\\s*setInitialFocus\\(this\\.noButton\\);")
                .matcher(src).find(), "a held Enter must land on Cancel, never on the confirm button");
        assertTrue(Pattern.compile("public void onClose\\(\\)\\s*\\{\\s*answer\\(false\\);").matcher(src).find(),
                "onClose answers no (vanilla's would leave the menu)");
        assertTrue(src.contains("Brand.clientCommand()"), "the reset hint names this jar's own command root");
    }

    /** Every key the screen can show exists (the key-set test carries it to the other locales). */
    @Test
    void everyKeyTheScreenShowsIsInTheLangFile() throws IOException {
        JsonObject lang = JsonParser.parseString(Files.readString(ClientOptionCatalogTest.locate(
                "fabric/src/main/resources/assets/lss/lang/en_us.json"))).getAsJsonObject();
        var keys = Pattern.compile("\"(lss\\.[a-z0-9_.]+)\"").matcher(source());
        List<String> seen = new ArrayList<>();
        while (keys.find()) {
            seen.add(keys.group(1));
            assertTrue(lang.has(keys.group(1)), keys.group(1) + " is missing from en_us.json");
        }
        assertTrue(seen.containsAll(List.of("lss.redownload.this_server", "lss.redownload.all_servers",
                "lss.redownload.not_receiving", "lss.redownload.saved_terrain")), "all three scopes have text: " + seen);
        assertTrue(lang.get("lss.redownload.saved_terrain").getAsString().contains("/%s reset"),
                "every variant names the command that deletes saved terrain, root as an argument");
    }
}
