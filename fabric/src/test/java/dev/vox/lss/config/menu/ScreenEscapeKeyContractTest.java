package dev.vox.lss.config.menu;

import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Escape is whatever this line's vanilla says it is. LINE FLAVOR (1.21.1): a screen's key
 * methods take {@code (int keyCode, int scanCode, int modifiers)} and Escape is key code 256
 * ({@code InputConstants.KEY_ESCAPE}, the constant vanilla's own {@code Screen.keyPressed}
 * compares with); there is no {@code KeyEvent} and no {@code isEscape()} on this line. On 26.3
 * the key codes moved (Escape is 41 there) and the screens that still compared with 256 never
 * saw Escape: their {@code ScreenEscapeRelease} guard stayed idle and vanilla closed the screen
 * on the PRESS. Over Sodium's settings screen that hands Sodium the release of the same key,
 * and Sodium then closes and undoes the player's pending changes. So the other lines pin
 * {@code event.isEscape()} and forbid the literal; this line pins the opposite spelling,
 * which is the right one here: the guard is fed {@code keyCode == 256}, the first parameter
 * of the three-int key methods, and 256 is checked against this line's vanilla constant.
 * {@code StatusScreenDrawOrderTest} pins that the guard is called; this pins what it is
 * called WITH, on every screen that owns a guard (found by source scan, so a new screen is
 * covered without a list edit).
 */
class ScreenEscapeKeyContractTest {

    /** A key-code or scan-code variable compared with a number other than this line's Escape,
     *  or the scan code compared with any number: neither is "the Escape key". */
    private static final Pattern WRONG_KEY = Pattern.compile(
            "(?:\\b(?:key|keycode|keyCode|input)\\b\\s*(?:\\(\\s*\\))?\\s*[=!]=\\s*(?!256\\b)\\d+)"
                    + "|(?:\\b(?:scancode|scanCode|modifiers)\\b\\s*[=!]=\\s*\\d+)");

    /** The key-event flavor of the newer lines: it does not exist here. */
    private static final Pattern EVENT_FLAVOR = Pattern.compile("\\bKeyEvent\\b|\\bisEscape\\s*\\(");

    @Test
    void escapeIsKeyCode256OnThisLine() {
        // A compile-time constant of the Minecraft this line builds against: if a port ever
        // moves this file to a line where Escape is another code, this is the red test.
        assertEquals(256, InputConstants.KEY_ESCAPE, "the literal the guarded screens compare with");
    }

    @Test
    void everyEscapeGuardedScreenFeedsTheGuardThisLinesEscapeKeyCode() throws IOException {
        Path root = ClientOptionCatalogTest.locate("xplat/src/main/java");
        List<String> guarded = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<Path> sources;
        try (var files = Files.walk(root)) {
            sources = files.filter(f -> f.toString().endsWith(".java")).toList();
        }
        for (Path file : sources) {
            String src = SodiumGenerationTest.stripComments(Files.readString(file));
            if (!src.contains("new dev.vox.lss.common.diagnostics.ScreenEscapeRelease()")
                    && !src.contains("new ScreenEscapeRelease()")) {
                continue;
            }
            String name = file.getFileName().toString();
            guarded.add(name);
            var wrong = WRONG_KEY.matcher(src);
            if (wrong.find()) {
                problems.add(name + ": key comparison that is not this line's Escape '" + wrong.group() + "'");
            }
            var event = EVENT_FLAVOR.matcher(src);
            if (event.find()) {
                problems.add(name + ": '" + event.group() + "' is the key-event flavor of the newer lines");
            }
            if (!src.contains("boolean keyPressed(int keyCode, int scanCode, int modifiers)")
                    || !src.contains("boolean keyReleased(int keyCode, int scanCode, int modifiers)")) {
                problems.add(name + ": keyPressed and keyReleased must be this line's three-int overrides,"
                        + " with the key code as the first parameter");
            }
            if (!src.contains(".press(keyCode == 256)")) {
                problems.add(name + ": keyPressed must feed the guard keyCode == 256");
            }
            if (!src.contains(".release(keyCode == 256)")) {
                problems.add(name + ": keyReleased must feed the guard keyCode == 256");
            }
        }
        assertTrue(guarded.contains("ClientStatusScreen.java") && guarded.contains("ClientSettingsSaveScreen.java"),
                "the scan must find the two long-standing guarded screens, found " + guarded);
        assertTrue(guarded.contains("RedownloadTerrainConfirmScreen.java"),
                "and the Re-download Terrain confirm screen, found " + guarded);
        assertTrue(problems.isEmpty(), "Escape must be this line's key code, fed from the key methods' first parameter:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    void thePatternsCatchTheSpellingsThatAreWrongHere() {
        assertTrue(WRONG_KEY.matcher("escape.press(keyCode == 41)").find(), "26.3's Escape code");
        assertTrue(WRONG_KEY.matcher("if (keyCode != 257) {").find());
        assertTrue(WRONG_KEY.matcher("escape.press(scanCode == 256)").find(), "the scan code is not the key");
        assertTrue(WRONG_KEY.matcher("event.input()==41").find());
        assertTrue(EVENT_FLAVOR.matcher("escape.press(event.isEscape()) || super.keyPressed(event)").find());
        assertTrue(EVENT_FLAVOR.matcher("public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {").find());
        assertFalse(WRONG_KEY.matcher("return escape.press(keyCode == 256) || super.keyPressed(keyCode, scanCode, modifiers);").find());
        assertFalse(WRONG_KEY.matcher("if (escape.release(keyCode == 256)) {").find());
        assertFalse(WRONG_KEY.matcher("int rows = Math.max(1, (height - 72) / 11);").find());
        assertFalse(EVENT_FLAVOR.matcher("return escape.press(keyCode == 256) || super.keyPressed(keyCode, scanCode, modifiers);").find());
    }
}
