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
        assertEquals(ClientVerdict.NO_SUPPORT, nativeVerdict(Discovery.SEND_FAILED, false));
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
            // Not installed, or the version could not be read: the plain advice.
            for (String version : new String[]{"absent", "unknown", "not a version!"}) {
                assertEquals(ClientVerdict.NOTHING_TO_SHOW,
                        ClientVerdict.of(noConsumer(discovery, version)), discovery + " " + version);
            }
        }
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
            if (Caps.NONE.allows(verdict)) needed.add("lss.notice." + verdict.key());
            for (String key : needed) {
                for (var locale : locales.entrySet()) {
                    String where = locale.getKey() + " " + key;
                    if (!locale.getValue().has(key)) {
                        problems.add(where + ": missing");
                        continue;
                    }
                    String value = locale.getValue().get(key).getAsString();
                    if (value.isBlank()) problems.add(where + ": blank");
                    if (value.contains("%")) problems.add(where + ": the line is shown without arguments");
                    if (value.matches(".*\\bLSS\\b.*")) problems.add(where + ": use the full name, the short one is the prefix");
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
                announceable |= verdict.key().equals(suffix) && Caps.NONE.allows(verdict);
            }
            if (!announceable) problems.add(key + ": a notice the policy never announces");
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
