package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.23.0 plan §1, decision 0009: a settings file with a mistake still does not load, and the
 * refusal lists every mistake at once. The list is for people; it never changes what loads.
 */
class SettingsProblemsTest {
    private final YamlSettingsCodec<ServerSettings> server = new YamlSettingsCodec<>(SettingsSchema.server(false));
    private final YamlSettingsCodec<ClientSettings> client = new YamlSettingsCodec<>(SettingsSchema.client());

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static List<String> problems(YamlSettingsCodec<?> codec, String document) {
        var refused = assertThrows(SettingsException.class, () -> codec.parse(bytes(document)));
        assertFalse(refused.problems().isEmpty(), "a refused file always has at least one problem");
        return refused.problems().stream().map(SettingsProblems.Problem::render).toList();
    }

    @Test void everyMistakeIsListedInFileOrderWithItsLine() {
        var listed = problems(server, """
                config_version: 1
                service:
                  enabled: true
                  require_permision: true
                generation:
                  enabled: 5
                far_player:
                  mode: "off"
                """);
        assertEquals(List.of(
                "line 4: unknown setting service.require_permision. Did you mean service.require_permission?",
                "line 6: Expected boolean true/false at generation.enabled",
                "line 7: unknown setting far_player. Did you mean far_players?"), listed);
    }

    @Test void theMessageOfTheRefusalIsStillTheStrictReadersFirstProblem() {
        // Every caller and test that reads getMessage() sees what it saw before.
        var refused = assertThrows(SettingsException.class, () -> server.parse(bytes(
                "config_version: 1\nunknown: true\nservice: {enabled: 5}\n")));
        assertEquals("Unknown settings path: unknown", refused.getMessage());
        assertEquals(2, refused.problems().size());
    }

    @Test void aSuggestionNeedsANearAndUnambiguousName() {
        // Three edits away: no "did you mean".
        assertFalse(problems(server, "config_version: 1\nservice:\n  permision: true\n").get(0).contains("Did you mean"));
        // A short name allows one edit only.
        assertTrue(problems(server, "config_version: 1\nfar_players:\n  mod: \"off\"\n").get(0)
                .endsWith("Did you mean far_players.mode?"));
        assertFalse(problems(server, "config_version: 1\nfar_players:\n  mood: \"off\"\n").get(0).contains("Did you mean"));
        // Equally close to two names: neither is suggested.
        assertNull(SettingsProblems.nearest(Set.of("alpha", "alphb"), "alphc"));
        assertEquals("alpha", SettingsProblems.nearest(Set.of("alpha", "gamma"), "alhpa"), "a swap is one edit");
        assertNull(SettingsProblems.nearest(Set.of("alpha"), "omega"));
    }

    @Test void aSettingOfTheOtherSideIsNamedAsSuchWithTheOneThatDoesItsJobHere() {
        // An operator who writes the client's switch to hide far players must not just delete
        // the line and leave far players on: the hint names the server's setting.
        assertEquals(List.of("line 3: unknown setting far_players.enabled. That is a client setting; the server's is far_players.mode."),
                problems(server, "config_version: 1\nfar_players:\n  enabled: false\n"));
        assertEquals(List.of("line 3: unknown setting far_players.mode. That is a server setting; the client's is far_players.enabled."),
                problems(client, "config_version: 1\nfar_players:\n  mode: \"off\"\n"));
        // No certain counterpart: the side alone.
        assertEquals(List.of("line 3: unknown setting lod.receive. That is a client setting, not a server one."),
                problems(server, "config_version: 1\nlod:\n  receive: false\n"));
        // Every pair names real settings, each on its own side.
        var serverPaths = SettingsSchema.server(false).descriptorsByPath().keySet();
        var clientPaths = SettingsSchema.client().descriptorsByPath().keySet();
        SettingsProblems.ON_THE_SERVER.forEach((theirs, ours) -> {
            assertTrue(clientPaths.contains(theirs) && !serverPaths.contains(theirs), theirs);
            assertTrue(serverPaths.contains(ours), ours);
        });
        SettingsProblems.ON_THE_CLIENT.forEach((theirs, ours) -> {
            assertTrue(serverPaths.contains(theirs) && !clientPaths.contains(theirs), theirs);
            assertTrue(clientPaths.contains(ours), ours);
        });
    }

    @Test void aKnownNameWrittenTwiceIsSaidWithTheRest() {
        // The strict reader stops at a duplicate, so without this a typo and a duplicate took
        // two restarts.
        assertEquals(List.of("line 4: service.enabled is written twice; keep one",
                        "line 5: unknown setting service.nonsense_key. Check the spelling. If a newer version of the mod wrote this line, delete it.",
                        "line 6: service is written twice; keep one"),
                problems(server, "config_version: 1\nservice:\n  enabled: true\n  enabled: false\n  nonsense_key: 1\nservice:\n  enabled: true\n"));
    }

    @Test void aNameFromBeforeTheYamlFileIsTranslated() {
        assertEquals(List.of("line 3: unknown setting service.requireServicePermission. Its name in this file is service.require_permission."),
                problems(server, "config_version: 1\nservice:\n  requireServicePermission: true\n"));
    }

    @Test void anOldNameThatCountedSecondsSaysTheNewOneCountsTicks() {
        assertEquals(List.of("line 2: unknown setting generationTimeoutSeconds. Its name in this file is generation.timeout_ticks. It counts ticks: 20 for each second."),
                problems(server, "config_version: 1\ngenerationTimeoutSeconds: 30\n"));
        assertEquals(List.of("line 2: unknown setting dirtyBroadcastIntervalSeconds. Its name in this file is updates.dirty_broadcast_interval_ticks. It counts ticks: 20 for each second."),
                problems(server, "config_version: 1\ndirtyBroadcastIntervalSeconds: 5\n"));
        // Same unit: no remark.
        assertEquals(List.of("line 2: unknown setting missMemoTtlSeconds. Its name in this file is storage.miss_memo_ttl_seconds."),
                problems(server, "config_version: 1\nmissMemoTtlSeconds: 5\n"));
    }

    @Test void aFailedReloadsFirstLineDoesNotRepeatWhatTheListHides() {
        // The strict reader's message names the path as typed; the reload output printed it
        // one line above the list that masks it.
        var refused = assertThrows(SettingsException.class, () -> server.parse(bytes(
                "config_version: 1\nfar_players:\n  SomePlayerName: true\n")));
        assertTrue(refused.getMessage().contains("SomePlayerName"), "the strict message is unchanged");
        assertEquals("the file has 1 problem:", SettingsReload.headline(refused));
        assertEquals("the file has 1 problem:", SettingsReload.headline(new java.util.concurrent.CompletionException(refused)));
        for (String line : SettingsReload.problemLines(refused)) assertFalse(line.contains("SomePlayerName"), line);
        // Nothing to list below it: the message itself.
        assertEquals("disk full", SettingsReload.headline(new java.io.IOException("disk full")));
        assertEquals("Settings file is empty", SettingsReload.headline(
                assertThrows(SettingsException.class, () -> server.parse(bytes("")))));
    }

    @Test void anOldNameIsOnlyTrustedWhereItCannotBeAGuessAtANewOne() {
        // "enabled" was the old name of the service switch. Under far_players it is a guess at a
        // far-player switch, and pointing it at service.enabled would be a wrong instruction.
        var guess = problems(server, "config_version: 1\nnetwork:\n  enabled: false\n").get(0);
        assertFalse(guess.contains("Its name in this file"), guess);
        assertEquals(List.of("line 2: unknown setting enabled. Its name in this file is service.enabled."),
                problems(server, "config_version: 1\nenabled: false\n"));
    }

    @Test void anUnknownNameWithNoHintGetsTheDowngradeAdvice() {
        assertEquals(List.of("line 3: unknown setting network.future_thing. Check the spelling. If a newer version of the mod wrote this line, delete it."),
                problems(server, "config_version: 1\nnetwork:\n  future_thing: 1\n"));
    }

    @Test void nothingTheAuthorTypedAsAValueReachesTheList() {
        // A mis-indented address or player name becomes a key. Dotted names are never printed,
        // and an unknown name under a group that holds a private setting is hidden.
        var dotted = problems(client, "config_version: 1\ncache:\n  play.example.org: [backup.example.org]\n");
        assertEquals(List.of("line 3: a name under cache contains a dot; settings are written as nested groups, one name per line"), dotted);
        var hidden = problems(client, "config_version: 1\ncache:\n  secretserver: [a]\n");
        assertEquals(List.of("line 3: unknown setting cache.<private>. Check the spelling. If a newer version of the mod wrote this line, delete it."), hidden);
        var player = problems(server, "config_version: 1\nfar_players:\n  SomePlayerName: true\n");
        assertTrue(player.get(0).startsWith("line 3: unknown setting far_players.<private>."), player.get(0));
        // A wrong value is described by its type, never quoted.
        var value = problems(server, "config_version: 1\nfar_players:\n  excluded_players: SomePlayerName\n");
        assertEquals(List.of("line 3: Expected list of strings at far_players.excluded_players"), value);
        for (var list : List.of(dotted, hidden, player, value))
            for (String line : list)
                assertFalse(line.contains("example") || line.contains("secretserver") || line.contains("SomePlayerName"), line);
    }

    @Test void aFileForAnotherSchemaVersionIsOneProblem() {
        assertEquals(List.of("line 1: config_version must be the integer 1; newer versions are not supported"),
                problems(server, "config_version: 2\nbrand_new_group:\n  key: 1\nanother: 2\n"));
        assertEquals(List.of("config_version must be the integer 1; newer versions are not supported"),
                problems(server, "service:\n  enabled: true\n"));
    }

    @Test void aSyntaxErrorIsOneProblemWithItsLine() {
        var listed = problems(server, "config_version: 1\nservice:\n  enabled: true\n   require_permission: [\n");
        assertEquals(1, listed.size());
        assertTrue(listed.get(0).matches("line \\d+: Invalid YAML syntax; check indentation, keys and scalar values"), listed.get(0));
    }

    @Test void aGroupWrittenAsAValueAndANullAreExplained() {
        assertEquals(List.of("line 2: service is a group of settings; its settings go on the lines below it, indented",
                        "line 4: Null or unsupported scalar at generation.enabled"),
                problems(server, "config_version: 1\nservice: true\ngeneration:\n  enabled:\n"));
    }

    @Test void aLongListIsCountedInFullAndPrintedCut() {
        var document = new StringBuilder("config_version: 1\nnetwork:\n");
        int count = SettingsProblemText.MAX_LINES + 7;
        for (int i = 0; i < count; i++) document.append("  future_").append(i).append(": 1\n");
        var refused = assertThrows(SettingsException.class, () -> server.parse(bytes(document.toString())));
        assertEquals(count, refused.problems().size());
        var block = SettingsProblemText.serverBlock("lss-server-config.yaml", refused.problems());
        assertEquals("lss-server-config.yaml has " + count + " problems, so the mod is switched off:", block.get(0));
        assertEquals(SettingsProblemText.MAX_LINES + 3, block.size());
        assertEquals("  and 7 more", block.get(block.size() - 2));
        assertEquals("Fix the file and restart the server. The file was not changed.", block.get(block.size() - 1));
    }

    @Test void theWordsNameTheRealFileAndTheBuildsOwnCommand() {
        var one = List.of(new SettingsProblems.Problem(4, "unknown setting x"));
        assertEquals(List.of("vss-server-config.yaml has 1 problem, so the mod is switched off:",
                        "  line 4: unknown setting x",
                        "Fix the file and restart the server. The file was not changed."),
                SettingsProblemText.serverBlock("vss-server-config.yaml", one));
        assertEquals("[VSS] Switched off: the settings file has 1 problem. Run /vsslod to see them.",
                SettingsProblemText.adminLine("VSS", 1, "vsslod"));
        assertEquals("[LSS] Switched off: the settings file has 3 problems. Run /lsslod to see them.",
                SettingsProblemText.adminLine("LSS", 3, "lsslod"));
    }

    @Test void aRefusalTheListCannotExplainKeepsTheStrictMessage() {
        assertEquals(List.of("YAML anchors are not allowed"),
                problems(server, "config_version: 1\nservice: &s {enabled: true}\n"));
        assertEquals(List.of("Settings file is empty"), problems(server, ""));
        var invalid = assertThrows(SettingsException.class, () -> server.parse(new byte[]{(byte) 0xC3, 0x28}));
        assertEquals(List.of("Settings file must be valid UTF-8"),
                invalid.problems().stream().map(SettingsProblems.Problem::render).toList());
    }

    @Test void collectingNeverThrows() {
        var schema = SettingsSchema.server(false);
        for (byte[] garbage : List.of(new byte[0], new byte[]{0}, bytes("- a\n- b\n"), bytes("\t\t:::"), bytes("a: [")))
            assertFalse(SettingsProblems.collect(schema, garbage, new SettingsException("refused")).isEmpty());
    }

    // ---- the one spelling that is accepted ----

    @Test void theUsualSpellingsOfABooleanLoadForABooleanSetting() {
        for (String yes : List.of("True", "TRUE", "yes", "Yes", "on", "ON"))
            assertTrue(server.parse(bytes("config_version: 1\nservice:\n  require_permission: " + yes + "\n"))
                    .configured().service().requirePermission(), yes);
        for (String no : List.of("False", "FALSE", "no", "No", "off", "Off"))
            assertFalse(server.parse(bytes("config_version: 1\ngeneration:\n  enabled: " + no + "\n"))
                    .configured().generation().enabled(), no);
    }

    @Test void theToleranceStopsAtTheCertainCases() {
        // Quoted text is text, as before.
        assertThrows(SettingsException.class, () -> server.parse(bytes("config_version: 1\nservice: {enabled: \"true\"}\n")));
        assertThrows(SettingsException.class, () -> server.parse(bytes("config_version: 1\nservice: {enabled: 'yes'}\n")));
        // Not a boolean word.
        for (String word : List.of("maybe", "1", "y", "enabled", "~"))
            assertThrows(SettingsException.class, () -> server.parse(bytes("config_version: 1\nservice: {enabled: " + word + "}\n")), word);
        // A text setting keeps its word: "on" is a far-player mode, "yes" is not.
        assertEquals("on", server.parse(bytes("config_version: 1\nfar_players:\n  mode: on\n")).configured().farPlayers().mode());
        assertEquals("off", server.parse(bytes("config_version: 1\nfar_players:\n  mode: off\n")).configured().farPlayers().mode());
        assertThrows(SettingsException.class, () -> server.parse(bytes("config_version: 1\nfar_players:\n  mode: yes\n")));
        // A number setting is not helped.
        assertThrows(SettingsException.class, () -> server.parse(bytes("config_version: 1\nlod: {distance: {default_chunks: on}}\n")));
    }

    @Test void theExperimentalFileAcceptsTheSameSpellings() {
        // 0.23.0 review: "real_time_updates: off" there made the whole file ignored, which
        // put every experimental option back at its default.
        byte[] main = bytes("config_version: 1\n");
        assertTrue(client.parse(main).configured().lod().dh().realTimeUpdates(), "the default is on");
        var spelt = client.parse(main, bytes("lod:\n  dh:\n    real_time_updates: off\n"));
        assertFalse(spelt.sidecarIgnored());
        assertFalse(spelt.configured().lod().dh().realTimeUpdates());
        // Still strict about everything else: a word that is not a boolean makes the file ignored.
        var wrong = client.parse(main, bytes("lod:\n  dh:\n    real_time_updates: maybe\n"));
        assertTrue(wrong.sidecarIgnored());
        assertTrue(wrong.configured().lod().dh().realTimeUpdates());
        // A text setting in that file keeps its word.
        assertEquals("lss", client.parse(main, bytes("lod:\n  source: lss\n")).configured().lod().source());
    }

    @Test void aSpeltBooleanIsNotReportedAsAProblemOfARefusedFile() {
        assertEquals(List.of("line 4: unknown setting service.nonsense_key. Check the spelling. If a newer version of the mod wrote this line, delete it."),
                problems(server, "config_version: 1\nservice:\n  enabled: Yes\n  nonsense_key: 1\n"));
    }
}
