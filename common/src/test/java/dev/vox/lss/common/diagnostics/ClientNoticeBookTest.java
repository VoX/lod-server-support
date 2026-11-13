package dev.vox.lss.common.diagnostics;

import dev.vox.lss.common.diagnostics.ClientNoticePolicy.Caps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.22.0 plan §1: what the client may say in chat across connections and game starts. */
class ClientNoticeBookTest {

    private static final int SETTLE = ClientNoticePolicy.SETTLE_TICKS;

    /** Ticks {@code verdict} for {@code ticks}; returns what the book said, or null. */
    private static ClientVerdict run(ClientNoticeBook book, ClientVerdict verdict, int ticks) {
        ClientVerdict said = null;
        for (int i = 0; i < ticks; i++) {
            ClientVerdict now = book.tick(verdict);
            if (now != null) {
                assertNull(said, "at most one notice per connection");
                said = now;
            }
        }
        return said;
    }

    @Test
    void aSessionThatWorksNeverReadsTheCountersFile(@TempDir Path dir) {
        var reads = new AtomicInteger();
        var book = new ClientNoticeBook(() -> {
            reads.incrementAndGet();
            return dir.resolve("notices.properties");
        });
        book.onJoin();
        assertNull(run(book, ClientVerdict.WAITING, 600));
        assertNull(run(book, ClientVerdict.WORKING, 5000));
        assertNull(run(book, ClientVerdict.NO_ANSWER, 5000), "a silent server is never announced");
        assertEquals(0, reads.get());
        assertFalse(Files.exists(dir.resolve("notices.properties")));
    }

    @Test
    void theCountIsOnDiskBeforeTheLineIsSpokenAndCarriesOverAGameStart(@TempDir Path dir) {
        Path file = dir.resolve("state").resolve("notices.properties");
        for (int start = 1; start <= ClientNoticePolicy.LIFETIME_CAP; start++) {
            var book = new ClientNoticeBook(() -> file); // a new game start
            book.onJoin();
            assertEquals(ClientVerdict.NO_SUPPORT, run(book, ClientVerdict.NO_SUPPORT, SETTLE + 50), "start " + start);
            assertEquals(start, ClientNoticeCapsFile.load(file).noSupportShown());
        }
        var book = new ClientNoticeBook(() -> file);
        book.onJoin();
        assertNull(run(book, ClientVerdict.NO_SUPPORT, 5000), "said its three times: never again");
        book.onJoin();
        assertEquals(ClientVerdict.SERVER_OFF, run(book, ClientVerdict.SERVER_OFF, SETTLE + 50), "another kind still may");
    }

    @Test
    void aCountThatCannotBeSavedIsNotSpoken(@TempDir Path dir) throws Exception {
        // The state directory is a file: nothing can be written beside it.
        Path blocked = dir.resolve("a-file");
        Files.writeString(blocked, "x");
        var book = new ClientNoticeBook(() -> blocked.resolve("notices.properties"));
        for (int join = 0; join < 5; join++) {
            book.onJoin();
            assertNull(run(book, ClientVerdict.NO_SUPPORT, SETTLE + 50),
                    "an unsaved count would repeat the line on every game start");
        }
        // A place that cannot even be named.
        var nowhere = new ClientNoticeBook(() -> {
            throw new IllegalStateException("no game directory");
        });
        nowhere.onJoin();
        assertNull(run(nowhere, ClientVerdict.SERVER_OFF, SETTLE + 50));
    }

    @Test
    void anUnreadableFileMeansSilence(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notices.properties");
        Files.writeString(file, "no_support=lots\n");
        var book = new ClientNoticeBook(() -> file);
        for (ClientVerdict verdict : new ClientVerdict[]{ClientVerdict.NO_SUPPORT, ClientVerdict.SERVER_OFF,
                ClientVerdict.NOTHING_TO_SHOW, ClientVerdict.CLIENT_OUTDATED}) {
            book.onJoin();
            assertNull(run(book, verdict, SETTLE + 50), verdict.name());
        }
        assertEquals("no_support=lots\n", Files.readString(file), "and the file is left as it was found");
    }

    @Test
    void aNetworksLobbyIsNotCalledAServerWithoutTheModAfterItsGameServerWorked(@TempDir Path dir) {
        Path file = dir.resolve("notices.properties");
        var book = new ClientNoticeBook(() -> file);
        book.onJoin();                                       // the game server
        assertNull(run(book, ClientVerdict.WORKING, 200));
        book.onJoin();                                       // the proxy moves the player to the lobby
        assertNull(run(book, ClientVerdict.NO_SUPPORT, 5000), "the connection has seen the mod work");
        assertEquals(Caps.NONE, ClientNoticeCapsFile.load(file), "and no count was spent");
        // Other answers on that connection are still given.
        book.onJoin();
        assertEquals(ClientVerdict.SERVER_OFF, run(book, ClientVerdict.SERVER_OFF, SETTLE + 50));

        // A new connection that starts on a server without the mod is told.
        book.onDisconnect();
        book.onJoin();
        assertEquals(ClientVerdict.NO_SUPPORT, run(book, ClientVerdict.NO_SUPPORT, SETTLE + 50));
        assertTrue(Files.exists(file));
    }
}
