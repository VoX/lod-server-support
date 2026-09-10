package dev.vox.lss.common.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenEscapeReleaseTest {
    @Test void pressAndRepeatsAreConsumedBeforeOneReleaseCloses() {
        var key = new ScreenEscapeRelease();
        assertTrue(key.press(true));
        assertTrue(key.press(true));
        assertTrue(key.release(true));
        assertFalse(key.release(true));
    }
    @Test void unpairedReleaseCannotCloseANewScreen() {
        assertFalse(new ScreenEscapeRelease().release(true));
    }
    @Test void unrelatedKeyEventsDoNotConsumeTheEscapePair() {
        var key = new ScreenEscapeRelease();
        assertFalse(key.press(false));
        assertTrue(key.press(true));
        assertFalse(key.release(false));
        assertTrue(key.release(true));
    }
}
