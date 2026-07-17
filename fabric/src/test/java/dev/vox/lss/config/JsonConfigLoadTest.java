package dev.vox.lss.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vox.lss.common.config.ServerConfigBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins JsonConfig.load() lifecycle semantics against a real directory:
 * <ul>
 *   <li>a broken config file must never abort startup — defaults are used instead;</li>
 *   <li>a broken file must never be overwritten — the admin's hand-edit survives for fixing;</li>
 *   <li>a successfully parsed file IS re-saved, migrating newly added fields in;</li>
 *   <li>fields absent from the file keep their compiled defaults (GSON must instantiate via
 *       the no-arg constructor so field initializers run — see the landmine test below).</li>
 * </ul>
 * Uses a local ServerConfigBase subclass instead of LSSServerConfig so the test never trips
 * LSSServerConfig's static CONFIG initializer (FabricLoader config dir + real IO).
 */
class JsonConfigLoadTest {

    // The on-disk name admins know; renaming it would orphan every existing install's config.
    private static final String FILE = "lss-server-config.json";

    /** Local stand-in for LSSServerConfig: same fields/defaults/clamps via ServerConfigBase. */
    public static class TestServerConfig extends ServerConfigBase {
        static TestServerConfig load(Path configDir) {
            return load(TestServerConfig.class, FILE_NAME, configDir);
        }
    }

    /**
     * Subclass of the real client config: inherits the real fields, validate() clamp and
     * file name, but loads from a temp dir instead of the FabricLoader config dir.
     */
    public static class TestClientConfig extends LSSClientConfig {
        static final String CLIENT_FILE = new TestClientConfig().getFileName();

        static TestClientConfig load(Path configDir) {
            return load(TestClientConfig.class, CLIENT_FILE, configDir);
        }
    }

    private static List<String> serializedFieldNames() {
        List<String> names = Arrays.stream(TestServerConfig.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .toList();
        assertTrue(names.size() >= 13, "field reflection broke, found only: " + names);
        return names;
    }

    private static JsonObject savedJson(Path configDir) throws Exception {
        return savedJson(configDir, FILE);
    }

    private static JsonObject savedJson(Path configDir, String fileName) throws Exception {
        return JsonParser.parseString(Files.readString(configDir.resolve(fileName))).getAsJsonObject();
    }

    @Test
    void missingFileLoadsDefaultsAndCreatesFileWithAllFields(@TempDir Path tempDir) throws Exception {
        Path configDir = tempDir.resolve("config"); // not yet existing — load must create it
        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(256, c.lodDistanceChunks);
        assertEquals(20_971_520, c.bytesPerSecondLimitPerPlayer);
        assertTrue(Files.isRegularFile(configDir.resolve(FILE)));

        JsonObject saved = savedJson(configDir);
        for (String key : serializedFieldNames()) {
            assertTrue(saved.has(key), "defaults file missing field " + key);
        }
        assertEquals(256, saved.get("lodDistanceChunks").getAsInt());
    }

    @Test
    void truncatedFileLoadsDefaultsAndPreservesFileExactly(@TempDir Path configDir) throws Exception {
        String broken = "{\"lodDistanceChunks\": 64"; // interrupted write: no closing brace
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks); // defaults, not the half-written value
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void garbageTextLoadsDefaultsAndPreservesFileExactly(@TempDir Path configDir) throws Exception {
        String broken = "this is not json at all {{{";
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks);
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void wrongTypedFieldLoadsDefaultsAndPreservesFileExactly(@TempDir Path configDir) throws Exception {
        String broken = "{\"lodDistanceChunks\": \"lots\"}"; // valid JSON, unbindable value
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks);
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void emptyFileLoadsDefaultsAndIsLeftUntouched(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "");

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks);
        assertEquals("", Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void partialFileKeepsCompiledDefaultsForAbsentFields(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "{\"lodDistanceChunks\": 64}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(64, c.lodDistanceChunks);
        // Every absent field must keep its compiled default. GSON only runs field
        // initializers when it can use the no-arg constructor; adding ANY explicit
        // constructor silently switches it to Unsafe.allocateInstance, zeroing every
        // default (then validate() clamps the zeros to the minimums, e.g. 20 MB/s -> 1 KB/s).
        // These exact-value assertions are the only guard against that landmine.
        assertTrue(c.enabled);
        assertEquals(20_971_520, c.bytesPerSecondLimitPerPlayer);
        assertEquals(5, c.diskReaderThreads);
        assertEquals(4000, c.sendQueueLimitPerPlayer);
        assertEquals(104_857_600, c.bytesPerSecondLimitGlobal);
        assertTrue(c.enableChunkGeneration);
        assertEquals(32, c.generationConcurrencyLimitGlobal);
        assertEquals(60, c.generationTimeoutSeconds);
        assertEquals(10, c.dirtyBroadcastIntervalSeconds);
        assertEquals(16, c.generationConcurrencyLimitPerPlayer);
        assertEquals(32, c.perDimensionTimestampCacheSizeMB);
    }

    @Test
    void partialFileLoadResavesWithAllFieldsMigratedIn(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "{\"lodDistanceChunks\": 64}");

        TestServerConfig.load(configDir);

        JsonObject saved = savedJson(configDir);
        assertEquals(64, saved.get("lodDistanceChunks").getAsInt()); // admin's value kept
        for (String key : serializedFieldNames()) {
            assertTrue(saved.has(key), "re-saved file missing migrated field " + key);
        }
        assertEquals(20_971_520, saved.get("bytesPerSecondLimitPerPlayer").getAsInt());
    }

    @Test
    void outOfRangeValueInFileIsClampedInMemoryAndOnDisk(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "{\"lodDistanceChunks\": 99999}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(2048, c.lodDistanceChunks); // LSSConstants.MAX_LOD_DISTANCE
        assertEquals(2048, savedJson(configDir).get("lodDistanceChunks").getAsInt());
    }

    @Test
    void retiredSyncOnLoadKnobInOldConfigLoadsCleanlyAndDropsOnSave(@TempDir Path configDir) throws Exception {
        // v0.6.x config files carry syncOnLoadConcurrencyLimitPerPlayer (retired to the
        // SYNC_ON_LOAD_SLOT_CAP constant in v0.7.0). Loading one must neither crash nor
        // preserve the dead key: GSON ignores unknown fields and the load-time re-save
        // rewrites the file from live fields only — the release-noted "dropped on next
        // save" contract, pinned here against an actual old-shaped file.
        Files.writeString(configDir.resolve(FILE),
                "{\"syncOnLoadConcurrencyLimitPerPlayer\": 400, \"lodDistanceChunks\": 96}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(96, c.lodDistanceChunks, "live keys from the old file still apply");
        var saved = savedJson(configDir);
        assertTrue(!saved.has("syncOnLoadConcurrencyLimitPerPlayer"),
                "the retired knob must be dropped from the re-saved file");
        assertEquals(96, saved.get("lodDistanceChunks").getAsInt());
    }

    @Test
    void unknownKeyIsSilentlyDroppedByLoadTimeResave(@TempDir Path configDir) throws Exception {
        // Typo'd key ("lodDistanceChunk", no s): GSON ignores it, the load succeeds, and the
        // write-back rewrites the file from the bound object — the typo line is erased without
        // any hint that the intended override never applied. Destructive, but today's contract.
        Files.writeString(configDir.resolve(FILE),
                "{\"lodDistanceChunk\": 64, \"sendQueueLimitPerPlayer\": 123}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(256, c.lodDistanceChunks); // the typo'd value never binds
        JsonObject saved = savedJson(configDir);
        assertFalse(saved.has("lodDistanceChunk"), "typo'd key must be dropped by the re-save");
        assertEquals(256, saved.get("lodDistanceChunks").getAsInt());
        assertEquals(123, saved.get("sendQueueLimitPerPlayer").getAsInt()); // bound values survive the rewrite
    }

    @Test
    void singleBadFieldRevertsValidCustomizationsInTheSameFile(@TempDir Path configDir) throws Exception {
        // GSON binds the whole object or nothing: the valid sendQueueLimitPerPlayer=123 (read
        // before the failure) is discarded with the rest. One bad field costs every customization.
        String broken = "{\"sendQueueLimitPerPlayer\": 123, \"lodDistanceChunks\": \"lots\"}";
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(4000, c.sendQueueLimitPerPlayer); // the valid customization is reverted too
        assertEquals(256, c.lodDistanceChunks);
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void literalNullBodyLoadsDefaultsAndIsLeftUntouched(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "null"); // fromJson -> null, same outcome as empty

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks);
        assertEquals("null", Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void whitespaceOnlyBodyLoadsDefaultsAndIsLeftUntouched(@TempDir Path configDir) throws Exception {
        String body = " \n\t  ";
        Files.writeString(configDir.resolve(FILE), body);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks);
        assertEquals(body, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void nullValuedPrimitiveKeepsCompiledDefaultAndHealsOnResave(@TempDir Path configDir) throws Exception {
        // JSON null for a primitive field is SKIPPED by GSON (not an error): the compiled default
        // stays, the parse succeeds — so sibling customizations survive and the re-save heals the
        // null into a real number. Contrast with the wrong-typed case, which reverts the whole file.
        Files.writeString(configDir.resolve(FILE),
                "{\"lodDistanceChunks\": null, \"sendQueueLimitPerPlayer\": 123}");

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks);       // compiled default kept
        assertEquals(123, c.sendQueueLimitPerPlayer); // parse succeeded: sibling customization kept
        JsonObject saved = savedJson(configDir);
        assertEquals(256, saved.get("lodDistanceChunks").getAsInt()); // healed to a number on disk
        assertEquals(123, saved.get("sendQueueLimitPerPlayer").getAsInt());
    }

    @Test
    void booleanStringYesSilentlyDisables(@TempDir Path configDir) throws Exception {
        // GSON parses string booleans with Boolean.parseBoolean: anything but "true" is false.
        // "enabled": "yes" therefore silently disables the mod instead of failing the load.
        Files.writeString(configDir.resolve(FILE), "{\"enabled\": \"yes\", \"lodDistanceChunks\": 64}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertFalse(c.enabled, "\"yes\" must parse as false (silent disable)");
        assertEquals(64, c.lodDistanceChunks); // leniency path, not the whole-file revert
    }

    @Test
    void booleanStringsTrueAndFalseParseAsBooleans(@TempDir Path tempDir) throws Exception {
        Path trueDir = tempDir.resolve("string-true");
        Files.createDirectories(trueDir);
        Files.writeString(trueDir.resolve(FILE), "{\"enabled\": \"true\", \"lodDistanceChunks\": 64}");
        TestServerConfig viaTrue = TestServerConfig.load(trueDir);
        assertTrue(viaTrue.enabled);
        assertEquals(64, viaTrue.lodDistanceChunks); // proves the string parsed instead of reverting

        Path falseDir = tempDir.resolve("string-false");
        Files.createDirectories(falseDir);
        Files.writeString(falseDir.resolve(FILE), "{\"enabled\": \"false\"}");
        assertFalse(TestServerConfig.load(falseDir).enabled);
    }

    @Test
    void numericStringParsesAndClamps(@TempDir Path tempDir) throws Exception {
        Path inRange = tempDir.resolve("in-range");
        Files.createDirectories(inRange);
        Files.writeString(inRange.resolve(FILE), "{\"lodDistanceChunks\": \"64\"}");
        assertEquals(64, TestServerConfig.load(inRange).lodDistanceChunks);

        Path outOfRange = tempDir.resolve("out-of-range");
        Files.createDirectories(outOfRange);
        Files.writeString(outOfRange.resolve(FILE), "{\"lodDistanceChunks\": \"99999\"}");
        assertEquals(2048, TestServerConfig.load(outOfRange).lodDistanceChunks); // parsed, then clamped
    }

    @Test
    void floatForIntFieldRevertsWholeFileToDefaults(@TempDir Path configDir) throws Exception {
        String broken = "{\"lodDistanceChunks\": 32.5}"; // not representable as int
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks); // NOT truncated to 32
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void intOverflowRevertsToDefaultsInsteadOfClamping(@TempDir Path configDir) throws Exception {
        String broken = "{\"lodDistanceChunks\": 2147483648}"; // Integer.MAX_VALUE + 1
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        // The overflow dies in the PARSER, before validate() ever runs: the result is the
        // compiled default (256), not the clamp ceiling (2048) a successful bind would produce.
        assertEquals(256, c.lodDistanceChunks);
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void resaveIsIdempotentAcrossRepeatedLoads(@TempDir Path configDir) throws Exception {
        String seed = "{\"lodDistanceChunks\": 64}";
        Files.writeString(configDir.resolve(FILE), seed);

        TestServerConfig.load(configDir); // first load migrates the partial file to full form
        String afterFirst = Files.readString(configDir.resolve(FILE));
        TestServerConfig.load(configDir); // steady state: parse -> validate -> write-back
        String afterSecond = Files.readString(configDir.resolve(FILE));

        assertNotEquals(seed, afterFirst, "first load must have rewritten the file");
        assertEquals(afterFirst, afterSecond, "steady-state write-back must be byte-identical (no churn)");
        assertEquals(64, savedJson(configDir).get("lodDistanceChunks").getAsInt());
    }

    @Test
    void configPathBeingADirectoryLoadsDefaultsWithoutCrashing(@TempDir Path configDir) throws Exception {
        Files.createDirectories(configDir.resolve(FILE)); // a DIRECTORY squatting on the config path

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks); // not-a-regular-file -> missing-file path -> defaults
        assertTrue(Files.isDirectory(configDir.resolve(FILE)),
                "the failed defaults-save must be swallowed and leave the directory alone");
    }

    @Test
    void saveIntoUnwritableDirectoryIsNonfatalAndObjectStaysUsable(@TempDir Path tempDir) throws Exception {
        Path locked = tempDir.resolve("locked");
        Files.createDirectories(locked);
        assumeTrue(locked.toFile().setWritable(false, false) && !Files.isWritable(locked),
                "filesystem does not enforce write permissions here (e.g. running as root)");
        try {
            TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(locked));
            assertEquals(256, c.lodDistanceChunks);
            assertFalse(Files.exists(locked.resolve(FILE)), "failed save must not leave a partial file");

            c.lodDistanceChunks = 64;
            assertDoesNotThrow(c::save); // explicit save: logged and swallowed, never thrown

            assertTrue(locked.toFile().setWritable(true, true));
            c.save(); // same object once writable: it kept its configDir and saves normally
            assertEquals(64, savedJson(locked).get("lodDistanceChunks").getAsInt());
        } finally {
            locked.toFile().setWritable(true, true);
        }
    }

    @Test
    void clientConfigZeroDistanceSentinelSurvivesSaveLoadCycle(@TempDir Path configDir) throws Exception {
        // lodDistanceChunks=0 means "use the server's distance"; the client clamp floor is 0
        // (the server config's is 1). A save/load cycle must not bump the sentinel or drop it.
        Files.writeString(configDir.resolve(TestClientConfig.CLIENT_FILE),
                "{\"receiveServerLods\": false, \"lodDistanceChunks\": 0}");

        TestClientConfig first = TestClientConfig.load(configDir);
        assertFalse(first.receiveServerLods); // non-default: proves the file actually bound
        assertEquals(0, first.lodDistanceChunks);

        JsonObject saved = savedJson(configDir, TestClientConfig.CLIENT_FILE);
        assertEquals(0, saved.get("lodDistanceChunks").getAsInt()); // write-back keeps the sentinel
        assertFalse(saved.get("receiveServerLods").getAsBoolean());

        TestClientConfig second = TestClientConfig.load(configDir); // reload the write-back
        assertEquals(0, second.lodDistanceChunks);
        assertFalse(second.receiveServerLods);
    }
}
