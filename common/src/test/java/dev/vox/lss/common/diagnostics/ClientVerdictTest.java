package dev.vox.lss.common.diagnostics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.diagnostics.ClientNoticePolicy.Caps;
import dev.vox.lss.common.diagnostics.ClientStatusSnapshot.Availability;
import dev.vox.lss.common.diagnostics.ClientStatusSnapshot.Discovery;
import dev.vox.lss.common.diagnostics.DhStatusSnapshot.Activity;
import dev.vox.lss.common.diagnostics.DhStatusSnapshot.Reason;
import dev.vox.lss.common.diagnostics.DhStatusSnapshot.State;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.22.0 plan §1: one answer to "is distant terrain working here, and if not, why?". */
class ClientVerdictTest {

    private static final int V = LSSConstants.PROTOCOL_VERSION;

    private static ClientStatusSnapshot snapshot(boolean connected, boolean receiving, boolean serverEnabled,
                                                 boolean consumer, Discovery discovery, int protocol,
                                                 DhStatusSnapshot dh) {
        return new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION, 1, 0, connected,
                discovery == Discovery.NEGOTIATED, receiving, serverEnabled, consumer, true, protocol, 512,
                0, 0, 0, 0, -1, 0, 0, 0, 0, discovery, Availability.UNKNOWN, null, DiagnosticVersions.unknown(),
                null, dh);
    }

    /** A connected player with receiving on and something to show the terrain. */
    private static ClientVerdict nativeVerdict(Discovery discovery, boolean serverEnabled) {
        return ClientVerdict.of(snapshot(true, true, serverEnabled, true, discovery, V, null));
    }

    private static DhStatusSnapshot dh(State state, Reason reason, Activity activity) {
        return new DhStatusSnapshot(dev.vox.lss.common.dh.DhProfile.values()[0], state, reason, activity,
                10, 5, 2, 1, 45, 3, 2, 1000, 100, 1, 2, 3, 32, 1, 2, 500, true, true, true, true, 0, 0, 100,
                4096, 7, 0);
    }

    private static ClientVerdict dhVerdict(State state, Activity activity, boolean consumer, Discovery discovery) {
        return ClientVerdict.of(snapshot(true, true, false, consumer, discovery, 0,
                dh(state, Reason.NONE, activity)));
    }

    // ---- 0.23.0 plan §3: which "switched off" it is ----

    private static ClientStatusSnapshot answered(boolean enabled, int reason) {
        return new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION, 1, 0, true, true, true,
                enabled, true, true, V, 512, 0, 0, 0, 0, -1, 0, 0, 0, 0, Discovery.NEGOTIATED, Availability.UNKNOWN, null,
                DiagnosticVersions.unknown(), null, null, Availability.UNKNOWN, 0, 0, false, reason);
    }

    @Test
    void theServersReasonPicksTheSentenceAndNoReasonKeepsTheOldOne() {
        assertEquals(ClientVerdict.SERVER_OFF, ClientVerdict.of(answered(false, 0)),
                "not said: an older server, or a private reason");
        assertEquals(ClientVerdict.SERVER_OFF_EVERYONE, ClientVerdict.of(answered(false, LSSConstants.SESSION_FLAG_OFF_SERVER)));
        assertEquals(ClientVerdict.SERVER_OFF_WORLD, ClientVerdict.of(answered(false, LSSConstants.SESSION_FLAG_OFF_WORLD)));
        assertEquals(ClientVerdict.SERVER_OFF_PERMISSION, ClientVerdict.of(answered(false, LSSConstants.SESSION_FLAG_OFF_PERMISSION)));
        assertEquals(ClientVerdict.SERVER_OFF, ClientVerdict.of(answered(false, 0x40)), "an unknown bit is not a reason");
        // A reason never turns a working session into a refusal.
        assertEquals(ClientVerdict.WORKING, ClientVerdict.of(answered(true, LSSConstants.SESSION_FLAG_OFF_SERVER)));
        for (ClientVerdict verdict : ClientVerdict.values())
            assertEquals(verdict.name().startsWith("SERVER_OFF"), verdict.serverOff(), verdict.name());
    }

    @Test
    void theFourServerOffAnswersShareOneNoticeCounter() {
        Caps caps = Caps.NONE;
        for (ClientVerdict verdict : List.of(ClientVerdict.SERVER_OFF_WORLD, ClientVerdict.SERVER_OFF_PERMISSION,
                ClientVerdict.SERVER_OFF_EVERYONE)) {
            assertTrue(caps.allows(verdict), verdict.name());
            assertTrue(ClientNoticePolicy.announces(verdict), verdict.name());
            caps = caps.after(verdict);
        }
        assertEquals(new Caps(0, false, 3, 0), caps);
        for (ClientVerdict verdict : ClientVerdict.values())
            if (verdict.serverOff()) assertFalse(caps.allows(verdict), "three in all, whatever the reason: " + verdict);
        assertTrue(caps.allows(ClientVerdict.NO_SUPPORT), "another kind is unaffected");
    }

    // ---- 0.23.0 plan §1: a settings file that did not load ----

    private static ClientSettingsStatus settings(boolean loaded) {
        var defaults = dev.vox.lss.common.config.SettingsSchema.client().defaults();
        return ClientSettingsStatus.capture(loaded, defaults, defaults, defaults, java.util.Set.of())
                .withFile("lss-client-config.yaml", loaded ? List.of() : List.of("line 3: unknown setting lod.recieve. Did you mean lod.receive?"), null);
    }

    private static ClientStatusSnapshot withSettings(boolean connected, boolean receiving, Discovery discovery,
                                                     ClientSettingsStatus settings) {
        return new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION, 1, 0, connected,
                discovery == Discovery.NEGOTIATED, receiving, true, true, true, V, 512,
                0, 0, 0, 0, -1, 0, 0, 0, 0, discovery, Availability.UNKNOWN, null, DiagnosticVersions.unknown(),
                settings);
    }

    @Test
    void aSettingsFileThatDidNotLoadIsTheAnswerInEveryStateAndNeverOtherwise() {
        // The stand-in settings of a broken file have receiving off. Without the verdict the
        // player reads "you turned receiving off in the settings", which they did not do.
        for (Discovery discovery : Discovery.values())
            for (boolean connected : new boolean[] {true, false})
                for (boolean receiving : new boolean[] {true, false}) {
                    assertEquals(ClientVerdict.SETTINGS_BROKEN,
                            ClientVerdict.of(withSettings(connected, receiving, discovery, settings(false))),
                            discovery + " connected=" + connected + " receiving=" + receiving);
                    assertNotEquals(ClientVerdict.SETTINGS_BROKEN,
                            ClientVerdict.of(withSettings(connected, receiving, discovery, settings(true))));
                }
        // No settings observation at all (older callers): not this verdict.
        assertEquals(ClientVerdict.RECEIVING_OFF, ClientVerdict.of(withSettings(true, false, Discovery.DORMANT, null)));
        assertFalse(ClientVerdict.SETTINGS_BROKEN.working());
    }

    @Test
    void theStatusNamesTheFileAndListsWhatIsWrongWithIt() {
        var broken = withSettings(true, false, Discovery.DORMANT, settings(false));
        var lines = ClientStatusText.lines(broken, 0);
        assertEquals("lss.status.verdict.settings_broken", lines.get(0).key());
        assertEquals(List.of(), lines.get(0).arguments(), "a verdict line carries no arguments");
        assertEquals("lss.status.settings.file", lines.get(1).key());
        assertEquals(List.of("lss-client-config.yaml"), lines.get(1).arguments());
        assertEquals("lss.status.settings.problem", lines.get(2).key());
        assertEquals(List.of("line 3: unknown setting lod.recieve. Did you mean lod.receive?"), lines.get(2).arguments());
        var plain = broken.lines();
        assertEquals(List.of("Verdict: SETTINGS_BROKEN", "Settings file lss-client-config.yaml did not load:",
                "  line 3: unknown setting lod.recieve. Did you mean lod.receive?"), plain.subList(0, 3));
        // A file that loaded adds nothing.
        var fine = ClientStatusText.lines(withSettings(true, true, Discovery.NEGOTIATED, settings(true)), 0);
        assertTrue(fine.stream().noneMatch(m -> m.key().equals("lss.status.settings.file")
                || m.key().equals("lss.status.settings.problem") || m.key().equals("lss.status.settings.experimental_ignored")));
    }

    @Test
    void anIgnoredExperimentalFileIsALineUnderAWorkingVerdict() {
        // The mod is working; the experimental file's options are back at their defaults (the
        // Distant Horizons source a player had switched off, for one), and the status says so.
        var ignored = settings(true).withFile("lss-client-config.yaml", List.of(), "lss-client-experimental.yaml");
        var snapshot = withSettings(true, true, Discovery.NEGOTIATED, ignored);
        assertEquals(ClientVerdict.WORKING, ClientVerdict.of(snapshot));
        var lines = ClientStatusText.lines(snapshot, 0);
        assertEquals("lss.status.verdict.working", lines.get(0).key());
        assertEquals("lss.status.settings.experimental_ignored", lines.get(1).key());
        assertEquals(List.of("lss-client-experimental.yaml"), lines.get(1).arguments());
        assertEquals("Experimental settings file ignored: lss-client-experimental.yaml", snapshot.lines().get(1));
    }

    @Test
    void theHappyPathIsWorkingAndNothingElseIs() {
        assertEquals(ClientVerdict.WORKING, nativeVerdict(Discovery.NEGOTIATED, true));
        for (ClientVerdict verdict : ClientVerdict.values()) {
            assertEquals(verdict == ClientVerdict.WORKING || verdict == ClientVerdict.WORKING_DH, verdict.working());
        }
    }

    @Test
    void aSilentServerIsNeverCalledAServerWithoutTheMod() {
        // The channel exists and nobody answered: a proxy, a version skew or a saturated link
        // are silent too. Only a server that never registered the channel is "no support".
        assertEquals(ClientVerdict.NO_ANSWER, nativeVerdict(Discovery.NO_ANSWER, false));
        assertEquals(ClientVerdict.WAITING, nativeVerdict(Discovery.AWAITING_NEGOTIATION, false));
        assertEquals(ClientVerdict.WAITING, nativeVerdict(Discovery.DORMANT, false));
        assertEquals(ClientVerdict.NO_SUPPORT, nativeVerdict(Discovery.NO_CHANNEL, false));
        // A handshake this client could not send says nothing about the server.
        assertEquals(ClientVerdict.WAITING, nativeVerdict(Discovery.SEND_FAILED, false));
    }

    @Test
    void eachNativeAnswerHasItsOwnVerdict() {
        assertEquals(ClientVerdict.SERVER_OFF, nativeVerdict(Discovery.NEGOTIATED, false));
        assertEquals(ClientVerdict.NOT_CONNECTED, ClientVerdict.of(
                snapshot(false, true, false, true, Discovery.NOT_CONNECTED, 0, null)));
        assertEquals(ClientVerdict.WAITING, ClientVerdict.of(null));
    }

    @Test
    void aMapOnlyClientOnAServerThatBlocksMapWritesIsNotWorking() {
        // The session is negotiated and enabled, and nothing arrives: the map mods are the only
        // consumers and the server asks clients not to write terrain into maps.
        var blocked = Availability.SERVER_BLOCKED;
        assertEquals(ClientVerdict.MAPS_BLOCKED, ClientVerdict.of(mapSnapshot(true, blocked, Availability.ABSENT)));
        assertEquals(ClientVerdict.MAPS_BLOCKED, ClientVerdict.of(mapSnapshot(true, Availability.ABSENT, blocked)));
        assertEquals(ClientVerdict.MAPS_BLOCKED, ClientVerdict.of(mapSnapshot(true, blocked, blocked)));
        // With an LOD renderer the terrain still has somewhere to go.
        assertEquals(ClientVerdict.WORKING, ClientVerdict.of(mapSnapshot(false, blocked, blocked)));
        // Map-only and allowed: working.
        assertEquals(ClientVerdict.WORKING, ClientVerdict.of(mapSnapshot(true, Availability.AVAILABLE, Availability.ABSENT)));
        // Never in chat: the status says it.
        assertFalse(Caps.NONE.allows(ClientVerdict.MAPS_BLOCKED));
    }

    private static ClientStatusSnapshot mapSnapshot(boolean mapOnly, Availability xaero, Availability journeyMap) {
        return new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION, 1, 0, true, true, true, true, true,
                true, V, 512, 0, 0, 0, 0, -1, 0, 0, 0, 0, Discovery.NEGOTIATED, xaero, null,
                DiagnosticVersions.unknown(), null, null, journeyMap, 0, 0, mapOnly);
    }

    @Test
    void aPlayerWhoHasVoxyIsNotToldToAddVoxy() {
        // The bridge to Voxy did not start (its internals changed): no consumer, Voxy installed.
        for (Discovery discovery : new Discovery[]{Discovery.DORMANT, Discovery.NO_CHANNEL, Discovery.NEGOTIATED}) {
            assertEquals(ClientVerdict.RENDERER_NOT_CONNECTED,
                    ClientVerdict.of(noConsumer(discovery, "0.2.20-beta")), discovery.name());
            // Installed with a version string the status will not print: still installed.
            assertEquals(ClientVerdict.RENDERER_NOT_CONNECTED,
                    ClientVerdict.of(noConsumer(discovery, "not a version!")), discovery.name());
            // Not installed, or the loader did not say: the plain advice.
            for (String version : new String[]{"absent", "unknown"}) {
                assertEquals(ClientVerdict.NOTHING_TO_SHOW,
                        ClientVerdict.of(noConsumer(discovery, version)), discovery + " " + version);
            }
        }
        // Terrain reaches the maps, but the Voxy the player installed gets none: not "Working".
        var versions = new DiagnosticVersions(Map.of(DiagnosticVersions.Component.VOXY, "0.2.20-beta"));
        assertEquals(ClientVerdict.RENDERER_NOT_CONNECTED, ClientVerdict.of(new ClientStatusSnapshot(
                ClientStatusSnapshot.SCHEMA_VERSION, 1, 0, true, true, true, true, true, true, V, 512, 0, 0, 0, 0,
                -1, 0, 0, 0, 0, Discovery.NEGOTIATED, Availability.AVAILABLE, null, versions, null, null,
                Availability.ABSENT, 0, 0, true)));
        // The same once-ever nudge as "nothing to show": never both.
        assertTrue(Caps.NONE.allows(ClientVerdict.RENDERER_NOT_CONNECTED));
        assertFalse(Caps.NONE.after(ClientVerdict.NOTHING_TO_SHOW).allows(ClientVerdict.RENDERER_NOT_CONNECTED));
        assertFalse(Caps.NONE.after(ClientVerdict.RENDERER_NOT_CONNECTED).allows(ClientVerdict.NOTHING_TO_SHOW));
    }

    private static ClientStatusSnapshot noConsumer(Discovery discovery, String voxyVersion) {
        var versions = new DiagnosticVersions(Map.of(DiagnosticVersions.Component.VOXY, voxyVersion));
        return new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION, 1, 0, true,
                discovery == Discovery.NEGOTIATED, true, true, false, true, V, 512, 0, 0, 0, 0, -1, 0, 0, 0, 0,
                discovery, Availability.UNKNOWN, null, versions, null, null);
    }

    // ---- the LOD renderer's own answer (0.23.0 plan §6) ----

    private static ClientStatusSnapshot voxy(ClientStatusSnapshot.Renderer renderer, String voxyVersion,
                                             Discovery discovery, boolean serverEnabled, boolean receiving,
                                             boolean consumer, boolean mapOnly, DhStatusSnapshot dh) {
        var versions = new DiagnosticVersions(Map.of(DiagnosticVersions.Component.VOXY, voxyVersion));
        return new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION, 1, 0, true,
                discovery == Discovery.NEGOTIATED, receiving, serverEnabled, consumer, true, V, 512, 0, 0, 0, 0,
                -1, 0, 0, 0, 0, discovery, mapOnly ? Availability.AVAILABLE : Availability.ABSENT, null, versions,
                null, dh, Availability.ABSENT, 0, 0, mapOnly, 0, renderer);
    }

    private static ClientVerdict voxyVerdict(ClientStatusSnapshot.Renderer renderer) {
        return ClientVerdict.of(voxy(renderer, "0.2.20-beta", Discovery.NEGOTIATED, true, true, true, false, null));
    }

    @Test
    void aVoxyThatSaysItIsNotDrawingIsNotCalledWorking() {
        var unsupported = ClientStatusSnapshot.Renderer.UNSUPPORTED;
        var off = ClientStatusSnapshot.Renderer.SWITCHED_OFF;
        assertEquals(ClientVerdict.RENDERER_UNSUPPORTED, voxyVerdict(unsupported));
        assertEquals(ClientVerdict.RENDERER_OFF, voxyVerdict(off));
        // No answer, or "drawing": nothing changes. Unknown is never a guess.
        assertEquals(ClientVerdict.WORKING, voxyVerdict(ClientStatusSnapshot.Renderer.READY));
        assertEquals(ClientVerdict.WORKING, voxyVerdict(ClientStatusSnapshot.Renderer.UNKNOWN));
        assertEquals(ClientVerdict.WORKING, ClientVerdict.of(new ClientStatusSnapshot(
                ClientStatusSnapshot.SCHEMA_VERSION, 1, 0, true, true, true, true, true, true, V, 512, 0, 0, 0, 0,
                -1, 0, 0, 0, 0, Discovery.NEGOTIATED, Availability.ABSENT, null,
                new DiagnosticVersions(Map.of(DiagnosticVersions.Component.VOXY, "0.2.20-beta")), null, null,
                Availability.ABSENT, 0, 0, false, 0, null)), "a caller that passes no answer");
        // The answer belongs to an installed renderer only.
        for (String version : new String[]{"absent", "unknown"}) {
            assertEquals(ClientVerdict.WORKING, ClientVerdict.of(
                    voxy(unsupported, version, Discovery.NEGOTIATED, true, true, true, false, null)), version);
        }
        // Terrain still reaches a map: the Voxy the player installed draws none, so not "Working".
        assertEquals(ClientVerdict.RENDERER_UNSUPPORTED, ClientVerdict.of(
                voxy(unsupported, "0.2.20-beta", Discovery.NEGOTIATED, true, true, true, true, null)));
        assertEquals(ClientVerdict.RENDERER_OFF, ClientVerdict.of(
                voxy(off, "0.2.20-beta", Discovery.NEGOTIATED, true, true, true, true, null)));
    }

    @Test
    void aCauseOnTheServerOrAPlayersSwitchIsNamedBeforeTheRenderer() {
        var unsupported = ClientStatusSnapshot.Renderer.UNSUPPORTED;
        assertEquals(ClientVerdict.NO_SUPPORT, ClientVerdict.of(
                voxy(unsupported, "0.2.20-beta", Discovery.NO_CHANNEL, false, true, true, false, null)));
        assertEquals(ClientVerdict.NO_ANSWER, ClientVerdict.of(
                voxy(unsupported, "0.2.20-beta", Discovery.NO_ANSWER, false, true, true, false, null)));
        assertEquals(ClientVerdict.SERVER_OFF, ClientVerdict.of(
                voxy(unsupported, "0.2.20-beta", Discovery.NEGOTIATED, false, true, true, false, null)));
        assertEquals(ClientVerdict.WAITING, ClientVerdict.of(
                voxy(unsupported, "0.2.20-beta", Discovery.AWAITING_NEGOTIATION, false, true, true, false, null)));
        assertEquals(ClientVerdict.RECEIVING_OFF, ClientVerdict.of(
                voxy(unsupported, "0.2.20-beta", Discovery.NEGOTIATED, true, false, true, false, null)));
    }

    @Test
    void aVoxyThatSaysWhyItIsIdleBeatsCouldNotConnect() {
        var unsupported = ClientStatusSnapshot.Renderer.UNSUPPORTED;
        // The bridge did not start and nothing else shows terrain.
        assertEquals(ClientVerdict.RENDERER_UNSUPPORTED, ClientVerdict.of(
                voxy(unsupported, "0.2.20-beta", Discovery.NEGOTIATED, true, true, false, false, null)));
        assertEquals(ClientVerdict.RENDERER_NOT_CONNECTED, ClientVerdict.of(
                voxy(ClientStatusSnapshot.Renderer.UNKNOWN, "0.2.20-beta", Discovery.NEGOTIATED, true, true, false, false, null)));
        // The bridge did not start and the maps fill.
        assertEquals(ClientVerdict.RENDERER_OFF, ClientVerdict.of(voxy(ClientStatusSnapshot.Renderer.SWITCHED_OFF,
                "0.2.20-beta", Discovery.NEGOTIATED, true, true, true, true, null)));
    }

    @Test
    void theDistantHorizonsSourceAsksTheRendererToo() {
        // That source feeds the LOD renderer only. "Ready" with a renderer that cannot draw is
        // not working, and "waiting for the renderer" would otherwise wait for ever.
        var unsupported = ClientStatusSnapshot.Renderer.UNSUPPORTED;
        var off = ClientStatusSnapshot.Renderer.SWITCHED_OFF;
        var ready = dh(State.READY, Reason.NONE, Activity.IDLE);
        var needVoxy = dh(State.NEED_VOXY, Reason.NONE, Activity.WAITING_VOXY);
        assertEquals(ClientVerdict.RENDERER_UNSUPPORTED, ClientVerdict.of(
                voxy(unsupported, "0.2.20-beta", Discovery.NO_CHANNEL, false, true, true, false, ready)));
        assertEquals(ClientVerdict.RENDERER_OFF, ClientVerdict.of(
                voxy(off, "0.2.20-beta", Discovery.NO_CHANNEL, false, true, true, false, needVoxy)));
        assertEquals(ClientVerdict.WORKING_DH, ClientVerdict.of(voxy(ClientStatusSnapshot.Renderer.READY,
                "0.2.20-beta", Discovery.NO_CHANNEL, false, true, true, false, ready)));
        assertEquals(ClientVerdict.WAITING, ClientVerdict.of(voxy(ClientStatusSnapshot.Renderer.UNKNOWN,
                "0.2.20-beta", Discovery.NO_CHANNEL, false, true, true, false, needVoxy)));
    }

    @Test
    void onlyTheRendererThatCannotRunIsSaidInChat() {
        // "Cannot run here" is said once ever, on its own count: the player who was told to
        // add an LOD renderer, added one and finds it cannot run here must still be told.
        assertTrue(ClientNoticePolicy.announces(ClientVerdict.RENDERER_UNSUPPORTED));
        assertTrue(Caps.NONE.allows(ClientVerdict.RENDERER_UNSUPPORTED));
        assertTrue(Caps.NONE.after(ClientVerdict.NOTHING_TO_SHOW).allows(ClientVerdict.RENDERER_UNSUPPORTED));
        assertTrue(Caps.NONE.after(ClientVerdict.RENDERER_UNSUPPORTED).allows(ClientVerdict.NOTHING_TO_SHOW));
        assertFalse(Caps.NONE.after(ClientVerdict.RENDERER_UNSUPPORTED).allows(ClientVerdict.RENDERER_UNSUPPORTED));
        assertEquals(new Caps(0, false, 0, 0, true), Caps.NONE.after(ClientVerdict.RENDERER_UNSUPPORTED));
        assertFalse(Caps.EXHAUSTED.allows(ClientVerdict.RENDERER_UNSUPPORTED));
        // Switched off in the renderer's own settings is the player's choice: never announced.
        assertFalse(ClientNoticePolicy.announces(ClientVerdict.RENDERER_OFF));
        assertFalse(Caps.NONE.allows(ClientVerdict.RENDERER_OFF));
        assertEquals(Caps.NONE, Caps.NONE.after(ClientVerdict.RENDERER_OFF));
        assertFalse(ClientVerdict.RENDERER_UNSUPPORTED.working() || ClientVerdict.RENDERER_OFF.working());
    }

    @Test
    void aPlayerWhoHasAMapModIsNotToldToAddOne() {
        // No consumer and no Voxy. The map mod is installed: say what is in the way.
        var off = Availability.DISABLED;
        assertEquals(ClientVerdict.MAP_WRITING_OFF, ClientVerdict.of(noConsumerMaps(off, Availability.ABSENT)));
        assertEquals(ClientVerdict.MAP_WRITING_OFF, ClientVerdict.of(noConsumerMaps(Availability.ABSENT, off)));
        // The switch is the nearest fix, so it wins over a second map mod's broken bridge.
        assertEquals(ClientVerdict.MAP_WRITING_OFF, ClientVerdict.of(noConsumerMaps(off, Availability.FAILED)));
        for (Availability broken : new Availability[]{Availability.UNAVAILABLE, Availability.FAILED}) {
            assertEquals(ClientVerdict.MAP_NOT_CONNECTED, ClientVerdict.of(noConsumerMaps(broken, Availability.ABSENT)));
            assertEquals(ClientVerdict.MAP_NOT_CONNECTED, ClientVerdict.of(noConsumerMaps(Availability.UNKNOWN, broken)));
        }
        for (Availability none : new Availability[]{Availability.ABSENT, Availability.UNKNOWN}) {
            assertEquals(ClientVerdict.NOTHING_TO_SHOW, ClientVerdict.of(noConsumerMaps(none, none)));
        }
        // The player's own switch is never announced; a broken bridge shares the once-ever nudge.
        assertFalse(Caps.NONE.allows(ClientVerdict.MAP_WRITING_OFF));
        assertTrue(Caps.NONE.allows(ClientVerdict.MAP_NOT_CONNECTED));
        assertFalse(Caps.NONE.after(ClientVerdict.MAP_NOT_CONNECTED).allows(ClientVerdict.NOTHING_TO_SHOW));
    }

    private static ClientStatusSnapshot noConsumerMaps(Availability xaero, Availability journeyMap) {
        return new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION, 1, 0, true, false, true, false, false,
                true, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, Discovery.NO_CHANNEL, xaero, null,
                new DiagnosticVersions(Map.of(DiagnosticVersions.Component.VOXY, "absent")), null, null,
                journeyMap, 0, 0, false);
    }

    @Test
    void aRejectedVersionSaysWhichSideIsOlder() {
        // The player can act on only one of the two: say which.
        assertEquals(ClientVerdict.CLIENT_OUTDATED, ClientVerdict.of(
                snapshot(true, true, false, true, Discovery.PROTOCOL_REJECTED, V + 1, null)));
        assertEquals(ClientVerdict.SERVER_OUTDATED, ClientVerdict.of(
                snapshot(true, true, false, true, Discovery.PROTOCOL_REJECTED, V - 2, null)));
        assertEquals(ClientVerdict.VERSION_MISMATCH, ClientVerdict.of(
                snapshot(true, true, false, true, Discovery.PROTOCOL_REJECTED, 0, null)));
        assertEquals(ClientVerdict.VERSION_MISMATCH, ClientVerdict.of(
                snapshot(true, true, false, true, Discovery.PROTOCOL_REJECTED, V, null)));
        for (ClientVerdict verdict : ClientVerdict.values()) {
            assertEquals(verdict == ClientVerdict.CLIENT_OUTDATED || verdict == ClientVerdict.SERVER_OUTDATED
                    || verdict == ClientVerdict.VERSION_MISMATCH, verdict.versionMismatch(), verdict.name());
        }
    }

    @Test
    void thePlayersOwnWorldAndOwnChoiceComeBeforeEverythingElse() {
        // Own world first: nothing is downloaded there, whatever else is true.
        assertEquals(ClientVerdict.LOCAL_WORLD, ClientVerdict.of(
                snapshot(true, false, false, false, Discovery.LOCAL, 0, null)));
        assertEquals(ClientVerdict.RECEIVING_OFF, ClientVerdict.of(
                snapshot(true, false, true, true, Discovery.NEGOTIATED, V, null)));
        assertEquals(ClientVerdict.NOTHING_TO_SHOW, ClientVerdict.of(
                snapshot(true, true, false, false, Discovery.DORMANT, 0, null)));
        assertEquals(ClientVerdict.NOTHING_TO_SHOW, ClientVerdict.of(
                snapshot(true, true, false, false, Discovery.NO_CHANNEL, 0, null)));
    }

    @Test
    void theDistantHorizonsSourceAnswersOnlyWhileItCanRun() {
        assertEquals(ClientVerdict.WORKING_DH, dhVerdict(State.READY, Activity.WORKING, true, Discovery.DORMANT));
        assertEquals(ClientVerdict.WAITING, dhVerdict(State.CONNECTING, Activity.WAITING_SERVER, true, Discovery.NO_CHANNEL));
        assertEquals(ClientVerdict.WAITING, dhVerdict(State.BETWEEN_SESSIONS, Activity.IDLE, true, Discovery.NO_CHANNEL));
        assertEquals(ClientVerdict.WAITING, dhVerdict(State.PAUSED, Activity.IDLE, true, Discovery.NO_CHANNEL));
        // A Distant Horizons server answered and the renderer is still starting: not "no support".
        assertEquals(ClientVerdict.WAITING, dhVerdict(State.NEED_VOXY, Activity.WAITING_VOXY, true, Discovery.NO_CHANNEL));
        assertEquals(ClientVerdict.SOURCE_NOT_RUNNING, dhVerdict(State.BLOCKED, Activity.STOPPED, true, Discovery.NO_CHANNEL));
        assertEquals(ClientVerdict.SOURCE_NOT_RUNNING, dhVerdict(State.STOPPED, Activity.STOPPED, true, Discovery.NO_CHANNEL));
    }

    @Test
    void whereTheDistantHorizonsSourceCannotRunTheNativeAnswerDecides() {
        // No Distant Horizons terrain here, and the server never registered the channel.
        assertEquals(ClientVerdict.NO_SUPPORT, dhVerdict(State.NO_SERVICE, Activity.WAITING_SERVER, true, Discovery.NO_CHANNEL));
        // A map-only client (the source feeds an LOD renderer only) on a server without the
        // mod: the player HAS something to show terrain, so the answer is the server's.
        assertEquals(ClientVerdict.NO_SUPPORT, dhVerdict(State.NEED_VOXY, Activity.IDLE, true, Discovery.NO_CHANNEL));
        assertEquals(ClientVerdict.NO_SUPPORT, dhVerdict(State.NEED_TRANSPORT, Activity.IDLE, true, Discovery.NO_CHANNEL));
        // The player runs the Distant Horizons client mod. On a server without either mod,
        // "remove Distant Horizons" would be useless advice: the answer is the server's. Its
        // words speak about this mod only, so they stay true when the server does run Distant
        // Horizons and the player's own mod is showing that terrain.
        assertEquals(ClientVerdict.NO_SUPPORT, ClientVerdict.of(snapshot(true, true, false, true,
                Discovery.NO_CHANNEL, 0, dh(State.NEED_TRANSPORT, Reason.CLIENT_MOD_CONFLICT, Activity.IDLE))));
        // The same client with nothing to show it.
        assertEquals(ClientVerdict.NOTHING_TO_SHOW, dhVerdict(State.NEED_VOXY, Activity.IDLE, false, Discovery.NO_CHANNEL));
        // Before the channel fact settles: waiting, never a claim.
        assertEquals(ClientVerdict.WAITING, dhVerdict(State.NO_SERVICE, Activity.WAITING_SERVER, true, Discovery.DORMANT));
        // Native won: the Distant Horizons block is informational only.
        assertEquals(ClientVerdict.WORKING, ClientVerdict.of(snapshot(true, true, true, true, Discovery.NEGOTIATED, V,
                dh(State.BETWEEN_SESSIONS, Reason.NATIVE_OFFER, Activity.IDLE))));
    }

    @Test
    void everyVerdictAndEveryNoticeHasAllThreeTranslations() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("fabric/src/main/resources/assets/lss/lang"))) root = root.getParent();
        Map<String, JsonObject> locales = new LinkedHashMap<>();
        for (String locale : List.of("en_us", "zh_cn", "zh_tw")) {
            locales.put(locale, JsonParser.parseString(Files.readString(root.resolve(
                    "fabric/src/main/resources/assets/lss/lang/" + locale + ".json"))).getAsJsonObject());
        }
        var problems = new ArrayList<String>();
        var keys = new HashSet<String>();
        for (ClientVerdict verdict : ClientVerdict.values()) {
            assertTrue(keys.add(verdict.key()), verdict.key());
            assertFalse(verdict.key().isBlank());
            var needed = new ArrayList<>(List.of("lss.status.verdict." + verdict.key()));
            // Every verdict the caps allow is one the policy announces; one more is announced
            // without a cap (a settings file that did not load).
            assertTrue(!Caps.NONE.allows(verdict) || ClientNoticePolicy.announces(verdict), verdict.name());
            if (ClientNoticePolicy.announces(verdict)) needed.add("lss.notice." + verdict.key());
            for (String key : needed) {
                for (var locale : locales.entrySet()) {
                    String where = locale.getKey() + " " + key;
                    if (!locale.getValue().has(key)) {
                        problems.add(where + ": missing");
                        continue;
                    }
                    String value = locale.getValue().get(key).getAsString();
                    if (value.isBlank()) problems.add(where + ": blank");
                    // One notice takes one argument, the build's own command (0.23.0 plan §1);
                    // every other line is shown as it is written.
                    if (key.equals("lss.notice.settings_broken")) {
                        if (!value.contains("/%s status") || value.replace("/%s status", "").contains("%"))
                            problems.add(where + ": takes exactly the command as /%s status");
                    } else if (value.contains("%")) problems.add(where + ": the line is shown without arguments");
                    // The notices and verdicts say "this mod": a VSS client on an LSS server (or
                    // the reverse) must not name the server's copy by the client's brand.
                    if (value.matches("(?s).*(\\bLSS\\b|\\bVSS\\b|LOD Server Support|Voxy Server Side).*")) {
                        problems.add(where + ": names a brand; say \"this mod\"");
                    }
                    // "No support" is shown to players whose own Distant Horizons mod may be
                    // showing this server's terrain: it must speak about this mod only.
                    if (key.endsWith(".no_support") && locale.getKey().equals("en_us")
                            && value.contains("isn't sending distant terrain")) {
                        problems.add(where + ": claims the server sends no distant terrain at all");
                    }
                    if (!locale.getKey().equals("en_us")
                            && value.equals(locales.get("en_us").get(key).getAsString())) {
                        problems.add(where + ": not translated");
                    }
                }
            }
        }
        // A notice exists for exactly the verdicts the policy can announce.
        for (String key : locales.get("en_us").keySet()) {
            if (!key.startsWith("lss.notice.")) continue;
            String suffix = key.substring("lss.notice.".length());
            boolean announceable = false;
            for (ClientVerdict verdict : ClientVerdict.values()) {
                announceable |= verdict.key().equals(suffix) && ClientNoticePolicy.announces(verdict);
            }
            if (!announceable) problems.add(key + ": a notice the policy never announces");
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
