package dev.vox.lss.config.menu;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Escape is whatever this line's vanilla says it is. On 26.3 the key codes moved
 * ({@code InputConstants.KEY_ESCAPE} is 41 and {@code KeyEvent.isEscape()} tests that), so a
 * screen comparing {@code event.key()} with the old literal 256 never sees Escape: its
 * {@code ScreenEscapeRelease} guard stays idle and vanilla closes the screen on the PRESS. Over
 * Sodium's settings screen that hands Sodium the release of the same key, and Sodium then closes
 * and undoes the player's pending changes. {@code StatusScreenDrawOrderTest} pins that the guard
 * is called; this pins what it is called WITH, on every screen that owns a guard (found by
 * source scan, so a new screen is covered without a list edit).
 */
class ScreenEscapeKeyContractTest {

    /** A key accessor or key-code variable compared with a number, or the old Escape code itself. */
    private static final Pattern LITERAL_KEY = Pattern.compile(
            "(?:\\b(?:key|keycode|keyCode|input|scancode)\\b\\s*(?:\\(\\s*\\))?\\s*[=!]=\\s*\\d+)|(?:[=!]=\\s*256\\b)");

    @Test
    void everyEscapeGuardedScreenAsksTheEventNotALiteralKeyCode() throws IOException {
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
            var literal = LITERAL_KEY.matcher(src);
            if (literal.find()) {
                problems.add(name + ": literal key comparison '" + literal.group() + "'");
            }
            if (!src.contains(".press(event.isEscape())")) {
                problems.add(name + ": keyPressed must feed the guard event.isEscape()");
            }
            if (!src.contains(".release(event.isEscape())")) {
                problems.add(name + ": keyReleased must feed the guard event.isEscape()");
            }
        }
        assertTrue(guarded.contains("ClientStatusScreen.java") && guarded.contains("ClientSettingsSaveScreen.java"),
                "the scan must find the two long-standing guarded screens, found " + guarded);
        assertTrue(problems.isEmpty(), "Escape must come from the event, never a key-code literal:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    void thePatternCatchesTheSpellingsThatShipped() {
        assertTrue(LITERAL_KEY.matcher("escape.press(event.key() == 256)").find());
        assertTrue(LITERAL_KEY.matcher("if (keyCode == 256) {").find());
        assertTrue(LITERAL_KEY.matcher("event.input()==41").find());
        assertFalse(LITERAL_KEY.matcher("escape.press(event.isEscape()) || super.keyPressed(event)").find());
        assertFalse(LITERAL_KEY.matcher("int rows = Math.max(1, (height - 72) / 11);").find());
    }
}
