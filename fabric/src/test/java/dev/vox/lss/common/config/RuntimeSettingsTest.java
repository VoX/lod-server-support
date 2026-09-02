package dev.vox.lss.common.config;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The /lsslod set registry (v0.11.0 stage C, runtime-settings-commands-plan.md + R-2).
 * The load-bearing pins: per-key clamp FUNCTIONS shared with validate() — so `set
 * dirtyBroadcastIntervalSeconds 0` stays 0 (DIRTY0 through the command surface) and
 * `set maxConcurrentDiskReads 0` stays AUTO, never K=1 (the R-2 registry clamp rule);
 * the overflow/negative bandwidth cases; the cross-field gen-cap clamp via validate();
 * and the save() round-trip (runtime saves are the first post-startup save() callers).
 */
class RuntimeSettingsTest {

    /** Loaded from a temp dir so applyAndPersist's save() has a real target. */
    public static class TestServerConfig extends ServerConfigBase {
        static TestServerConfig load(Path configDir) {
            return load(TestServerConfig.class, FILE_NAME, configDir);
        }
    }

    private static TestServerConfig loaded(Path dir) {
        var c = TestServerConfig.load(dir);
        assertNotNull(c);
        return c;
    }

    private static String apply(ServerConfigBase c, String key, String value) {
        return applyResult(c, key, value).display();
    }

    private static RuntimeSettings.ApplyResult applyResult(ServerConfigBase c, String key, String value) {
        var k = RuntimeSettings.byName(key);
        assertNotNull(k, "registry must carry " + key);
        return RuntimeSettings.applyAndPersist(c, k, value);
    }

    @Test
    void everyRegisteredKeyRoundTripsAnInBandValue(@TempDir Path dir) {
        var c = loaded(dir);
        assertEquals("128", apply(c, "lodDistanceChunks", "128"));
        assertEquals(128, c.lodDistanceChunks);
        assertEquals("64", apply(c, "generationConcurrencyLimitGlobal", "64"));
        assertEquals(64, c.generationConcurrencyLimitGlobal);
        assertEquals("32", apply(c, "generationConcurrencyLimitPerPlayer", "32"));
        assertEquals(32, c.generationConcurrencyLimitPerPlayer);
        assertEquals("12.5", apply(c, "mbPerSecondLimitPerPlayer", "12.5"));
        assertEquals(12.5, c.mbPerSecondLimitPerPlayer);
        assertEquals("40.0", apply(c, "mbPerSecondLimitGlobal", "40.0"));
        assertEquals(40.0, c.mbPerSecondLimitGlobal);
        assertEquals("30", apply(c, "dirtyBroadcastIntervalSeconds", "30"));
        assertEquals(30, c.dirtyBroadcastIntervalSeconds);
        assertEquals("2", apply(c, "maxConcurrentDiskReads", "2"));
        assertEquals(2, c.maxConcurrentDiskReads);
    }

    /** THE R-2 pins: a bare (min,max) registry row would break both of these. */
    @Test
    void zeroRoundTripsUnchangedForTheTwoZeroSemanticKeys(@TempDir Path dir) {
        var c = loaded(dir);
        assertEquals("0", apply(c, "dirtyBroadcastIntervalSeconds", "0"),
                "0 = dirty pushes off must survive the registry — never clamp to 1 s");
        assertEquals(0, c.dirtyBroadcastIntervalSeconds);
        assertEquals("0", apply(c, "maxConcurrentDiskReads", "0"),
                "0 = AUTO must survive the registry — never clamp to K=1");
        assertEquals(0, c.maxConcurrentDiskReads);
        // Negatives normalize to the same 0 semantics, not to the nonzero floor.
        assertEquals("0", apply(c, "dirtyBroadcastIntervalSeconds", "-5"));
        assertEquals("0", apply(c, "maxConcurrentDiskReads", "-3"));
    }

    @Test
    void bandwidthOverflowClampsAndNegativeResetsToTheCompiledDefault(@TempDir Path dir) {
        var c = loaded(dir);
        // A huge value must clamp into the byte band — the (int) accessor cast must
        // never go negative (the SET plan's overflow case).
        apply(c, "mbPerSecondLimitPerPlayer", "999999999");
        assertTrue(c.bytesPerSecondPerPlayer() > 0, "overflowed cast would throttle to zero");
        assertEquals("25.0", apply(c, "mbPerSecondLimitPerPlayer", "-1"),
                "negative = the file-absent sentinel resets to the compiled default");
        assertEquals("75.0", apply(c, "mbPerSecondLimitGlobal", "-2"));
    }

    @Test
    void loweringTheGlobalGenCapDragsPerPlayerDownViaValidate(@TempDir Path dir) {
        var c = loaded(dir);
        apply(c, "generationConcurrencyLimitPerPlayer", "40");
        apply(c, "generationConcurrencyLimitGlobal", "8");
        assertEquals(8, c.generationConcurrencyLimitPerPlayer,
                "validate()'s cross-field clamp (§9.1) must run on every set");
    }

    @Test
    void requireServicePermissionRowIsAStrictBooleanWithTheReofferNote(@TempDir Path dir) {
        // Row #12 (service-permission-gate-plan.md §2.5): strict true/false parse, no
        // clamp (R-2 — boolean rows carry none), and the apply-note must tell the admin
        // the recheck/re-offer story — arming or disarming without a restart is the
        // feature's whole rollout path.
        var c = loaded(dir);
        assertFalse(c.requireServicePermission, "ships OFF");
        assertEquals("true", apply(c, "requireServicePermission", "true"));
        assertTrue(c.requireServicePermission);
        assertEquals("false", apply(c, "requireServicePermission", "false"));
        assertFalse(c.requireServicePermission);
        var key = RuntimeSettings.byName("requireServicePermission");
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSettings.applyAndPersist(c, key, "yes"),
                "strict parse: only true/false");
        assertFalse(c.requireServicePermission, "a parse failure must assign nothing");
        assertTrue(key.applyNote().contains("re-offered"),
                "the note carries the recheck/re-offer contract: " + key.applyNote());
    }

    @Test
    void malformedValuesThrowWithoutAssigningAndUnknownKeysResolveNull(@TempDir Path dir) {
        var c = loaded(dir);
        int before = c.lodDistanceChunks;
        var key = RuntimeSettings.byName("lodDistanceChunks");
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSettings.applyAndPersist(c, key, "many"));
        assertEquals(before, c.lodDistanceChunks, "a parse failure must assign nothing");
        var dbl = RuntimeSettings.byName("mbPerSecondLimitPerPlayer");
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSettings.applyAndPersist(c, dbl, "NaN"));
        assertNull(RuntimeSettings.byName("noSuchKey"));
        assertNull(RuntimeSettings.byName("diskReaderThreads"),
                "pool size is deliberately NOT runtime-settable (the pool is built at boot)");
    }

    @Test
    void applyPersistsToTheConfigFileAndKeepsHiddenKeysDropped(@TempDir Path dir) throws Exception {
        var c = loaded(dir);
        apply(c, "lodDistanceChunks", "96");
        var saved = JsonParser.parseString(
                        Files.readString(dir.resolve("lss-server-config.json")))
                .getAsJsonObject();
        assertEquals(96, saved.get("lodDistanceChunks").getAsInt(),
                "runtime sets persist — a restart must keep the value");
        assertFalse(saved.has("useNbtTranscode"),
                "hidden expert keys stay dropped at their defaults through runtime saves");
    }

    @Test
    void clampedValuesReportTheEffectiveValueNotTheRequest(@TempDir Path dir) {
        var c = loaded(dir);
        assertEquals(dev.vox.lss.common.LSSConstants.MAX_LOD_DISTANCE + " (clamped from '999999')",
                apply(c, "lodDistanceChunks", "999999"),
                "the reply value is the post-clamp truth (with its clamp note), never an echo of the request");
    }

    @Test
    void farPlayersModeRowParsesStrictlyNormalizesAliasesAndAssignsNothingOnFailure(
            @TempDir Path dir) {
        var c = loaded(dir);
        var key = RuntimeSettings.byName("farPlayers");
        assertNotNull(key, "the R-9 privacy row exists");
        assertEquals("on", apply(c, "farPlayers", "ON"), "case-normalized");
        assertEquals("opt-in", apply(c, "farPlayers", "optin"),
                "aliases normalize through the validate() helper (the R-2 rule)");
        assertEquals("opt-in", apply(c, "farPlayers", "opt_in"));
        String before = c.farPlayers;
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSettings.applyAndPersist(c, key, "sometimes"),
                "a strict parse rejects garbage at the command line");
        assertEquals(before, c.farPlayers, "a parse failure must assign nothing");
    }

    @Test
    void farPlayersMaxDistanceRowClampsLikeBootValidation(@TempDir Path dir) {
        var c = loaded(dir);
        assertEquals("16384", apply(c, "farPlayersMaxDistanceBlocks", "999999"),
                "clamps to the shared ceiling — a registry row can never clamp"
                        + " differently from validate()");
        assertEquals("128", apply(c, "farPlayersMaxDistanceBlocks", "1"),
                "floors to the shared minimum");
        assertEquals("4096", apply(c, "farPlayersMaxDistanceBlocks", "4096"));
    }

    @Test
    void listingsCoverEveryKeyExactlyOnce() {
        var names = RuntimeSettings.keyNames();
        assertEquals(names.size(), names.stream().distinct().count());
        assertTrue(names.containsAll(java.util.List.of(
                "lodDistanceChunks", "generationConcurrencyLimitGlobal",
                "generationConcurrencyLimitPerPlayer", "mbPerSecondLimitPerPlayer",
                "mbPerSecondLimitGlobal", "dirtyBroadcastIntervalSeconds",
                "maxConcurrentDiskReads", "farPlayers", "farPlayersMaxDistanceBlocks",
                "enablePingBackstop", "enableSendPacing", "requireServicePermission")));
        assertEquals(12, names.size(),
                "row #12 (requireServicePermission) — a drifted count means a row was "
                        + "added or dropped without updating this census");
        var c = new TestServerConfig();
        assertEquals(names.size(), RuntimeSettings.listLines(c).size());
    }

    /** The registry's first BOOLEAN row (adaptive-transfer-rate-plan.md — the ping
     *  backstop's live A/B lever): strict parse, so a typo is a command-line error,
     *  never a silent disable. */
    @Test
    void pingBackstopRowParsesStrictBooleans() {
        var c = new TestServerConfig();
        apply(c, "enablePingBackstop", "false");
        assertEquals(false, c.enablePingBackstop, "false applies");
        apply(c, "enablePingBackstop", "TRUE");
        assertEquals(true, c.enablePingBackstop, "case-insensitive true applies");
        assertThrows(IllegalArgumentException.class,
                () -> apply(c, "enablePingBackstop", "yes"),
                "anything but true/false is a parse error");
        assertEquals(true, c.enablePingBackstop, "a rejected value changes nothing");
    }

    /** The send pacer's row (send-pacing-plan.md v2): strict boolean like its sibling. */
    @Test
    void sendPacingRowParsesStrictBooleans() {
        var c = new TestServerConfig();
        apply(c, "enableSendPacing", "false");
        assertEquals(false, c.enableSendPacing, "false applies");
        assertThrows(IllegalArgumentException.class,
                () -> apply(c, "enableSendPacing", "off"),
                "anything but true/false is a parse error");
        assertEquals(false, c.enableSendPacing, "a rejected value changes nothing");
        apply(c, "enableSendPacing", "true");
        assertEquals(true, c.enableSendPacing, "true applies");
    }

    @Test
    void lodDistanceWorldOverrideRoundTripsAndClears(@TempDir Path dir) {
        var c = loaded(dir);
        // Per-world set: display "world=n", changed=true. The changed flag is what the
        // command surface re-pushes on — a per-world set leaves the SCALAR unchanged, so
        // a per-world set leaves the scalar
        // unchanged, so a reply-string comparison would silently miss it and never re-push.
        var r = applyResult(c, "lodDistanceChunks", "minecraft:the_nether 64");
        assertEquals("minecraft:the_nether=64", r.display());
        assertTrue(r.repush(), "a per-world set must signal a re-push");
        assertEquals(512, c.lodDistanceChunks, "the default is unchanged by a per-world set");
        assertEquals(64, c.lodDistanceChunksByWorld.get("minecraft:the_nether"));
        assertEquals(64, c.lodDistanceForWorld("minecraft:the_nether"));

        // Re-applying the same value changes nothing → no re-push.
        assertFalse(applyResult(c, "lodDistanceChunks", "minecraft:the_nether 64").repush(),
                "an unchanged per-world set must NOT re-push");

        // A per-world clamp is reported on the distance token only, inside the display —
        // clampedSuffix itself stays PURELY numeric (no per-world branch).
        assertEquals("resource=2048 (clamped from '99999')",
                applyResult(c, "lodDistanceChunks", "resource 99999").display());
        assertEquals("", RuntimeSettings.clampedSuffix("64", "64"));
        assertEquals(" (clamped from '99999')", RuntimeSettings.clampedSuffix("2048", "99999"));

        apply(c, "lodDistanceChunks", "creative 128");
        var lines = RuntimeSettings.listLines(c);
        assertTrue(lines.contains("lodDistanceChunks = 512"));
        assertTrue(lines.contains("lodDistanceChunks[minecraft:the_nether] = 64"));
        assertTrue(lines.contains("lodDistanceChunks[creative] = 128"));

        // Clear an existing override → "world=default", changed=true.
        var cleared = applyResult(c, "lodDistanceChunks", "minecraft:the_nether default");
        assertEquals("minecraft:the_nether=default", cleared.display());
        assertTrue(cleared.repush(), "clearing an existing override must re-push");
        assertFalse(c.lodDistanceChunksByWorld.containsKey("minecraft:the_nether"));
        assertEquals(128, c.lodDistanceChunksByWorld.get("creative"));

        // Clearing an ABSENT override changes nothing → no re-push.
        assertFalse(applyResult(c, "lodDistanceChunks", "minecraft:the_nether default").repush(),
                "clearing an absent override must NOT re-push");

        // A scalar change re-pushes; an unchanged scalar does not.
        assertTrue(applyResult(c, "lodDistanceChunks", "256").repush());
        assertFalse(applyResult(c, "lodDistanceChunks", "256").repush(),
                "an unchanged scalar set must NOT re-push");

        int before = c.lodDistanceChunks;
        assertThrows(IllegalArgumentException.class,
                () -> apply(c, "lodDistanceChunks", "minecraft:the_end many"));
        assertEquals(before, c.lodDistanceChunks);
        assertFalse(c.lodDistanceChunksByWorld.containsKey("minecraft:the_end"),
                "a parse failure must assign no override");
        assertEquals(128, c.lodDistanceChunksByWorld.get("creative"),
                "a parse failure must leave existing overrides");
    }

    @Test
    void lodDistanceGrammarCornersAndReplyRendering(@TempDir Path dir) {
        var c = loaded(dir);
        var lod = RuntimeSettings.byName("lodDistanceChunks");

        // All three clear tokens remove an override.
        apply(c, "lodDistanceChunks", "a 100");
        apply(c, "lodDistanceChunks", "b 100");
        apply(c, "lodDistanceChunks", "c 100");
        apply(c, "lodDistanceChunks", "a -");
        apply(c, "lodDistanceChunks", "b clear");
        apply(c, "lodDistanceChunks", "c default");
        assertTrue(c.lodDistanceChunksByWorld.isEmpty(), "-, clear, and default all remove");

        // An over-long world key is rejected at the command surface (the file path drops).
        String longKey = "x".repeat(dev.vox.lss.common.LSSConstants.MAX_DIMENSION_STRING_LENGTH + 1);
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSettings.applyAndPersist(c, lod, longKey + " 64"));

        // renderReplyValue: lodDistanceChunks renders its own (baked) text verbatim, a
        // non-lod key gets the pure-numeric clampedSuffix appended — the ONE split home.
        var lodRes = applyResult(c, "lodDistanceChunks", "the_nether 96");
        assertEquals("the_nether=96", RuntimeSettings.renderReplyValue(lod, lodRes, "the_nether 96"));
        var mb = RuntimeSettings.byName("mbPerSecondLimitPerPlayer");
        var mbRes = RuntimeSettings.applyAndPersist(c, mb, "-1"); // resets to default 25.0
        assertEquals("25.0 (clamped from '-1')", RuntimeSettings.renderReplyValue(mb, mbRes, "-1"));
    }

    @Test
    void lodDistanceWorldOverridePersistsAcrossSave(@TempDir Path dir) throws Exception {
        var c = loaded(dir);
        apply(c, "lodDistanceChunks", "world_nether 32");
        var tree = JsonParser.parseString(Files.readString(
                dir.resolve("lss-server-config.json"))).getAsJsonObject();
        assertEquals(32, tree.getAsJsonObject("lodDistanceChunksByWorld")
                .get("world_nether").getAsInt());
        var reloaded = TestServerConfig.load(dir);
        assertEquals(32, reloaded.lodDistanceForWorld("world_nether", "minecraft:the_nether"));
    }

}
