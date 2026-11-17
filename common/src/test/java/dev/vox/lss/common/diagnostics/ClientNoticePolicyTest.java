package dev.vox.lss.common.diagnostics;

import dev.vox.lss.common.diagnostics.ClientNoticePolicy.Caps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.22.0 plan §1: when the client says, in chat, why no distant terrain is arriving. */
class ClientNoticePolicyTest {

    private static final int CAP = ClientNoticePolicy.LIFETIME_CAP;
    private final ClientNoticePolicy policy = new ClientNoticePolicy();

    /** Ticks {@code verdict} until the policy speaks or {@code ticks} run out; returns what it said. */
    private ClientVerdict run(ClientVerdict verdict, Caps caps, int ticks) {
        for (int i = 0; i < ticks; i++) {
            ClientVerdict said = this.policy.tick(verdict, caps);
            if (said != null) return said;
        }
        return null;
    }

    @Test
    void nothingIsSaidUntilTheVerdictHasHeldStillForTheWholeSettleTime() {
        this.policy.onJoin();
        assertNull(run(ClientVerdict.NO_SUPPORT, Caps.NONE, ClientNoticePolicy.SETTLE_TICKS),
                "the first tick only records the verdict; the settle time counts from the next");
        assertEquals(ClientVerdict.NO_SUPPORT, this.policy.tick(ClientVerdict.NO_SUPPORT, Caps.NONE));
    }

    @Test
    void aVerdictThatChangesStartsTheSettleTimeAgain() {
        // A slow server: waiting, then no answer, then the config arrives. Nothing may be said.
        this.policy.onJoin();
        assertNull(run(ClientVerdict.WAITING, Caps.NONE, 300));
        assertNull(run(ClientVerdict.SERVER_OFF, Caps.NONE, 300), "300 ticks of a new verdict are not enough");
        assertNull(run(ClientVerdict.WORKING, Caps.NONE, 5000), "working is never announced");
    }

    @Test
    void atMostOneNoticePerConnectionAndTheNextJoinMaySpeakAgain() {
        this.policy.onJoin();
        assertEquals(ClientVerdict.SERVER_OFF, run(ClientVerdict.SERVER_OFF, Caps.NONE, 1000));
        assertNull(run(ClientVerdict.SERVER_OFF, Caps.NONE, 5000));
        assertNull(run(ClientVerdict.CLIENT_OUTDATED, Caps.NONE, 5000), "one per connection, whatever comes next");
        this.policy.onJoin();
        assertEquals(ClientVerdict.CLIENT_OUTDATED, run(ClientVerdict.CLIENT_OUTDATED, Caps.NONE, 1000));
    }

    @Test
    void eachKindIsSaidThreeTimesEverAndNothingToShowOnce() {
        Caps caps = Caps.NONE;
        for (ClientVerdict kind : new ClientVerdict[]{ClientVerdict.NO_SUPPORT, ClientVerdict.SERVER_OFF}) {
            for (int join = 0; join < CAP; join++) {
                this.policy.onJoin();
                assertEquals(kind, run(kind, caps, 1000), kind + " join " + join);
                caps = caps.after(kind);
            }
            this.policy.onJoin();
            assertNull(run(kind, caps, 5000), kind + ": the fourth is not announced");
        }
        this.policy.onJoin();
        assertEquals(ClientVerdict.NOTHING_TO_SHOW, run(ClientVerdict.NOTHING_TO_SHOW, caps, 1000));
        caps = caps.after(ClientVerdict.NOTHING_TO_SHOW);
        this.policy.onJoin();
        assertNull(run(ClientVerdict.NOTHING_TO_SHOW, caps, 5000));
        assertEquals(new Caps(CAP, true, CAP, 0), caps);
        this.policy.onJoin();
        assertNull(run(ClientVerdict.RENDERER_NOT_CONNECTED, caps, 5000), "the same once-ever nudge");
        this.policy.onJoin();
        assertEquals(ClientVerdict.RENDERER_NOT_CONNECTED,
                run(ClientVerdict.RENDERER_NOT_CONNECTED, new Caps(CAP, false, CAP, CAP), 1000));
    }

    @Test
    void theThreeVersionAnswersShareOneCount() {
        // A player three protocol versions behind is not told three times per direction.
        Caps caps = Caps.NONE;
        for (ClientVerdict kind : new ClientVerdict[]{ClientVerdict.CLIENT_OUTDATED, ClientVerdict.SERVER_OUTDATED,
                ClientVerdict.VERSION_MISMATCH}) {
            this.policy.onJoin();
            assertEquals(kind, run(kind, caps, 1000));
            caps = caps.after(kind);
        }
        assertEquals(new Caps(0, false, 0, CAP), caps);
        for (ClientVerdict kind : new ClientVerdict[]{ClientVerdict.CLIENT_OUTDATED, ClientVerdict.SERVER_OUTDATED,
                ClientVerdict.VERSION_MISMATCH}) {
            this.policy.onJoin();
            assertNull(run(kind, caps, 5000), kind.name());
        }
    }

    @Test
    void aSilentServerAndThePlayersOwnChoiceAreNeverAnnounced() {
        for (ClientVerdict quiet : new ClientVerdict[]{ClientVerdict.NO_ANSWER, ClientVerdict.WAITING,
                ClientVerdict.RECEIVING_OFF, ClientVerdict.LOCAL_WORLD, ClientVerdict.NOT_CONNECTED,
                ClientVerdict.WORKING, ClientVerdict.WORKING_DH, ClientVerdict.SOURCE_NOT_RUNNING}) {
            this.policy.onJoin();
            assertNull(run(quiet, Caps.NONE, 3000), quiet.name());
            assertFalse(Caps.NONE.allows(quiet), quiet.name());
            assertEquals(Caps.NONE, Caps.NONE.after(quiet), quiet.name());
        }
        // And a quiet verdict does not use up the connection's one notice.
        assertEquals(ClientVerdict.SERVER_OFF, run(ClientVerdict.SERVER_OFF, Caps.NONE, 1000));
    }

    @Test
    void aCappedVerdictStillEndsTheWatchForThisConnection() {
        this.policy.onJoin();
        assertNull(run(ClientVerdict.NO_SUPPORT, new Caps(CAP, false, 0, 0), 1000));
        assertNull(run(ClientVerdict.SERVER_OFF, Caps.NONE, 5000),
                "the connection's verdict was settled; a later change is for the status screen");
    }

    @Test
    void theFifthCounterSurvivesARestartAndAFileFromBeforeItReadsAsNotSaid(@TempDir Path dir) throws Exception {
        // 0.23.0: "the LOD renderer cannot run here" has its own once-ever counter.
        Path file = dir.resolve("notices.properties");
        Caps said = new Caps(1, false, 0, 2, true);
        assertTrue(ClientNoticeCapsFile.save(file, said));
        assertEquals(said, ClientNoticeCapsFile.load(file));
        assertTrue(Files.readString(file).contains("renderer_unsupported=true\n"));
        // A file written by 0.22 has four keys: complete, and the new notice was never said.
        Files.writeString(file, "no_support=3\nnothing_to_show=true\nserver_off=3\nversion_mismatch=3\n");
        Caps old = ClientNoticeCapsFile.load(file);
        assertEquals(new Caps(3, true, 3, 3, false), old);
        assertTrue(old.allows(ClientVerdict.RENDERER_UNSUPPORTED));
        assertFalse(old.allows(ClientVerdict.NOTHING_TO_SHOW));
        // A pack maker silences it with its own line.
        Files.writeString(file, "no_support=3\nnothing_to_show=true\nserver_off=3\nversion_mismatch=3\nrenderer_unsupported=true\n");
        assertEquals(Caps.EXHAUSTED, ClientNoticeCapsFile.load(file));
    }

    @Test
    void theCountsSurviveARestartAndABrokenFileSaysNothing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state").resolve("notices.properties");
        assertEquals(Caps.NONE, ClientNoticeCapsFile.load(file), "no file yet: nothing was said");
        Caps caps = new Caps(2, true, 1, 3);
        assertTrue(ClientNoticeCapsFile.save(file, caps), "creates the directory");
        assertEquals(caps, ClientNoticeCapsFile.load(file));
        assertFalse(Files.readString(file).matches("(?s).*[0-9]+\\.[0-9]+\\.[0-9]+.*"), "no addresses");

        for (String broken : new String[]{"", "no_support=many\n", "no_support=1\n", "\u0000\u0001garbage"}) {
            Files.writeString(file, broken);
            assertEquals(Caps.EXHAUSTED, ClientNoticeCapsFile.load(file), "unreadable means silent: " + broken);
        }
        for (ClientVerdict verdict : ClientVerdict.values()) {
            assertFalse(Caps.EXHAUSTED.allows(verdict), verdict.name());
        }
        // A counter a newer version added is kept out of the way, not treated as damage, and
        // survives this version's save (or that version's notices would start over).
        Files.writeString(file, "no_support=1\nnothing_to_show=false\nserver_off=0\nversion_mismatch=2\nlater=9\n");
        assertEquals(new Caps(1, false, 0, 2), ClientNoticeCapsFile.load(file));
        assertTrue(ClientNoticeCapsFile.save(file, new Caps(2, false, 0, 2)));
        assertTrue(Files.readString(file).contains("later=9\n"), Files.readString(file));
        assertEquals(new Caps(2, false, 0, 2), ClientNoticeCapsFile.load(file));
        assertEquals(1, Files.readString(file).split("no_support=", -1).length - 1, "its own keys are written once");
        // A place that cannot be written: the caller must not speak (the count would not stick).
        Path blocked = dir.resolve("a-file");
        Files.writeString(blocked, "x");
        assertFalse(ClientNoticeCapsFile.save(blocked.resolve("notices.properties"), caps));
    }
}
