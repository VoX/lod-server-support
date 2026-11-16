package dev.vox.lss.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 0.23.0 plan §4: the extra client command name never hides a server command. */
class ExtraCommandRootGuardTest {
    @Test void theAnswerIsTakenWhenAListIsFirstSeenAndKeptWhileItIsCurrent() {
        var guard = new ExtraCommandRootGuard();
        Object list = new Object();
        assertTrue(guard.allows(list, false), "the server has no such command: the extra name works");
        // The loader has now merged the client's own node into the same list: still allowed.
        assertTrue(guard.allows(list, true));
        assertTrue(guard.allows(list, true));
    }

    @Test void aServerCommandOfThatNameKeepsTheExtraNameAway() {
        var guard = new ExtraCommandRootGuard();
        Object list = new Object();
        assertFalse(guard.allows(list, true), "the server's own command: the extra name steps aside");
        assertFalse(guard.allows(list, true));
    }

    @Test void aNewListIsANewQuestion() {
        var guard = new ExtraCommandRootGuard();
        Object first = new Object(), second = new Object();
        assertTrue(guard.allows(first, false));
        assertFalse(guard.allows(second, true), "the server re-sent its commands, now with that name");
        assertTrue(guard.allows(new Object(), false), "and without it again");
    }

    @Test void beforeAnyListTheExtraNameWorks() {
        assertTrue(new ExtraCommandRootGuard().allows(null, false));
    }
}
