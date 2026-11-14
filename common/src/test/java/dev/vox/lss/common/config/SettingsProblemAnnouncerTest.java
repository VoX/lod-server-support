package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 0.23.0 plan §1: the settings mistake is said again after start, and to each admin once. */
class SettingsProblemAnnouncerTest {
    private static final List<SettingsProblems.Problem> TWO = List.of(
            new SettingsProblems.Problem(4, "unknown setting a"), new SettingsProblems.Problem(9, "unknown setting b"));
    private final List<String> log = new ArrayList<>();
    private final List<String> chat = new ArrayList<>();
    private int adminReads;

    private SettingsProblemAnnouncer.Recipient admin(UUID id) {
        return new SettingsProblemAnnouncer.Recipient(id, line -> chat.add(id + " " + line));
    }
    private void run(SettingsProblemAnnouncer announcer, int ticks, List<SettingsProblems.Problem> problems,
                     boolean dedicated, List<SettingsProblemAnnouncer.Recipient> online) {
        for (int i = 0; i < ticks; i++)
            announcer.tick(problems, "lss-server-config.yaml", "LSS", "lsslod", dedicated,
                    () -> { adminReads++; return online; }, log::add);
    }

    @Test void theBlockIsLoggedOnceAfterTheServerStarted() {
        var announcer = new SettingsProblemAnnouncer();
        run(announcer, SettingsProblemAnnouncer.REPEAT_AFTER_TICKS - 1, TWO, true, List.of());
        assertTrue(log.isEmpty(), "not during startup");
        run(announcer, 1, TWO, true, List.of());
        assertEquals(SettingsProblemText.serverBlock("lss-server-config.yaml", TWO), log);
        run(announcer, 5000, TWO, true, List.of());
        assertEquals(4, log.size(), "once");
    }

    @Test void eachAdminIsToldOncePerServerRunWheneverTheyAppear() {
        var announcer = new SettingsProblemAnnouncer();
        UUID first = UUID.randomUUID(), late = UUID.randomUUID();
        run(announcer, SettingsProblemAnnouncer.POLL_TICKS, TWO, true, List.of(admin(first)));
        assertEquals(List.of(first + " [LSS] Switched off: the settings file has 2 problems. Run /lsslod to see them."), chat);
        run(announcer, 10 * SettingsProblemAnnouncer.POLL_TICKS, TWO, true, List.of(admin(first), admin(late)));
        assertEquals(2, chat.size(), "the first admin is not told again; the late one is told once");
        assertTrue(chat.get(1).startsWith(late.toString()));
    }

    @Test void theOnlinePlayersAreOnlyLookedAtOnAPollTick() {
        var announcer = new SettingsProblemAnnouncer();
        run(announcer, SettingsProblemAnnouncer.POLL_TICKS - 1, TWO, true, List.of());
        assertEquals(0, adminReads);
        run(announcer, 1, TWO, true, List.of());
        assertEquals(1, adminReads);
    }

    @Test void aFileThatLoadedSaysNothingAndReadsNothing() {
        var announcer = new SettingsProblemAnnouncer();
        run(announcer, 5000, List.of(), true, List.of(admin(UUID.randomUUID())));
        assertTrue(log.isEmpty() && chat.isEmpty());
        assertEquals(0, adminReads);
    }

    @Test void singleplayerAndALanHostAreNeverTold() {
        // The player there has cheats, which is the admin right, and no console to read.
        var announcer = new SettingsProblemAnnouncer();
        run(announcer, 5000, TWO, false, List.of(admin(UUID.randomUUID())));
        assertTrue(log.isEmpty() && chat.isEmpty());
        assertEquals(0, adminReads);
    }
}
