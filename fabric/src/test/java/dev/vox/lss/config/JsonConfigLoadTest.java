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

        /** Exposes the array-candidate overload (brand-primary first, other brand as fallback). */
        static TestServerConfig load(String[] candidates, Path configDir) {
            return load(TestServerConfig.class, candidates, configDir);
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
        // @HiddenFromFile fields are excluded: at their compiled defaults they are
        // deliberately absent from every written file (the 2026-08-08 config rework).
        List<String> names = Arrays.stream(TestServerConfig.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> f.getAnnotation(dev.vox.lss.common.config.JsonConfig.HiddenFromFile.class) == null)
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

        assertEquals(512, c.lodDistanceChunks);
        assertEquals(25.0, c.mbPerSecondLimitPerPlayer);
        assertEquals(26_214_400, c.bytesPerSecondPerPlayer());
        assertEquals(0, c.maxConcurrentDiskReads,
                "the disk-read gate ships at 0 = AUTO (store-conditional)");
        assertTrue(Files.isRegularFile(configDir.resolve(FILE)));

        JsonObject saved = savedJson(configDir);
        for (String key : serializedFieldNames()) {
            assertTrue(saved.has(key), "defaults file missing field " + key);
        }
        assertEquals(512, saved.get("lodDistanceChunks").getAsInt());
        // The lodStore SPLIT default: a brand-new install generates "on" (onFreshCreate),
        // while the compiled default stays "off" for keys absent from existing files.
        assertEquals("on", c.lodStore, "a fresh install must arm the store");
        assertEquals("on", saved.get("lodStore").getAsString());
    }

    /** The other half of the lodStore split default: an EXISTING file without the key
     *  binds the compiled "off" — an upgrade must never silently arm the store — and
     *  the migration re-save writes that "off" explicitly. */
    @Test
    void existingFileWithoutLodStoreKeyKeepsTheStoreOff(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "{\"lodDistanceChunks\": 64}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals("off", c.lodStore,
                "absent lodStore key in an existing file must mean OFF, not the fresh-install on");
        assertEquals("off", savedJson(configDir).get("lodStore").getAsString(),
                "the migration makes the absent-key semantics explicit on disk");
    }

    @Test
    void truncatedFileLoadsDefaultsAndPreservesFileExactly(@TempDir Path configDir) throws Exception {
        String broken = "{\"lodDistanceChunks\": 64"; // interrupted write: no closing brace
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(512, c.lodDistanceChunks); // defaults, not the half-written value
        assertEquals("off", c.lodStore,
                "a corrupt EXISTING file is not a fresh install — onFreshCreate must not arm the store");
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void garbageTextLoadsDefaultsAndPreservesFileExactly(@TempDir Path configDir) throws Exception {
        String broken = "this is not json at all {{{";
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(512, c.lodDistanceChunks);
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void wrongTypedFieldLoadsDefaultsAndPreservesFileExactly(@TempDir Path configDir) throws Exception {
        String broken = "{\"lodDistanceChunks\": \"lots\"}"; // valid JSON, unbindable value
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(512, c.lodDistanceChunks);
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void emptyFileLoadsDefaultsAndIsLeftUntouched(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "");

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(512, c.lodDistanceChunks);
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
        // (The bandwidth pair asserts POST-VALIDATE resolved values: the compiled
        // default is the -1 "not in the file" sentinel, which load()'s validate
        // resolves to 25/75 MiB/s — the accessor is what every consumer reads.)
        assertTrue(c.enabled);
        assertEquals(25.0, c.mbPerSecondLimitPerPlayer);
        assertEquals(26_214_400, c.bytesPerSecondPerPlayer());
        assertEquals(0, c.diskReaderThreads);           // 0 = AUTO (derived per read path)
        assertEquals(1024, c.sendQueueLimitPerPlayer);
        assertEquals(75.0, c.mbPerSecondLimitGlobal);
        assertEquals(78_643_200, c.bytesPerSecondGlobal());
        assertTrue(c.enableChunkGeneration);
        assertEquals(40, c.generationConcurrencyLimitGlobal);
        assertEquals(60, c.generationTimeoutSeconds);
        assertEquals(10, c.dirtyBroadcastIntervalSeconds);
        assertEquals(40, c.generationConcurrencyLimitPerPlayer);
        assertEquals(0, c.perDimensionTimestampCacheSizeMB); // 0 = AUTO (from lodDistance)
        assertTrue(c.lodDistanceChunksByWorld.isEmpty(),
                "absent lodDistanceChunksByWorld must bind the empty default, not null");
    }

    @Test
    void lodDistanceChunksByWorldRoundTripsFromFile(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE),
                "{\"lodDistanceChunks\": 256, \"lodDistanceChunksByWorld\": {"
                        + "\"minecraft:the_nether\": 64, \"creative\": 128}}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(256, c.lodDistanceChunks);
        assertEquals(64, c.lodDistanceForWorld("minecraft:the_nether"));
        assertEquals(128, c.lodDistanceForWorld("creative", "minecraft:overworld"));
        assertEquals(256, c.lodDistanceForWorld("minecraft:overworld"));

        JsonObject saved = savedJson(configDir);
        JsonObject byWorld = saved.getAsJsonObject("lodDistanceChunksByWorld");
        assertEquals(64, byWorld.get("minecraft:the_nether").getAsInt());
        assertEquals(128, byWorld.get("creative").getAsInt());
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
        assertEquals(25.0, saved.get("mbPerSecondLimitPerPlayer").getAsDouble());
        // The hidden expert switches and the retired byte-denominated spellings must
        // NOT be migrated in — the whole point of @HiddenFromFile (2026-08-08 rework).
        for (String hidden : List.of("useBackgroundReadPriority", "useBackgroundReadSplit",
                "useNbtTranscode", "useSelectiveNbtParse", "useCompressedColumns",
                "bytesPerSecondLimitPerPlayer", "bytesPerSecondLimitGlobal")) {
            assertFalse(saved.has(hidden), "hidden key must stay out of the re-saved file: " + hidden);
        }
    }

    // ---- the 2026-08-08 config rework: @HiddenFromFile honor + the bandwidth key rename ----

    @Test
    void hiddenKeyOverrideIsHonoredAndSurvivesEveryResave(@TempDir Path configDir) throws Exception {
        // "Continue to honor them" must mean FOREVER, not for one boot: a hidden key at a
        // NON-default value is written back by the re-save (only default-valued hidden keys
        // vanish), so the admin's rollback switch survives arbitrarily many restarts.
        Files.writeString(configDir.resolve(FILE),
                "{\"useNbtTranscode\": false, \"useBackgroundReadPriority\": false}");

        TestServerConfig first = TestServerConfig.load(configDir);
        assertFalse(first.useNbtTranscode, "hidden key must still bind from a file that carries it");
        assertFalse(first.useBackgroundReadPriority);

        JsonObject saved = savedJson(configDir);
        assertFalse(saved.get("useNbtTranscode").getAsBoolean(),
                "a NON-default hidden value must survive the write-back");
        assertFalse(saved.get("useBackgroundReadPriority").getAsBoolean());
        assertFalse(saved.has("useSelectiveNbtParse"),
                "hidden keys still at their default must stay absent");

        TestServerConfig second = TestServerConfig.load(configDir); // the boot after the write-back
        assertFalse(second.useNbtTranscode, "the override must not evaporate after one boot");
        assertFalse(second.useBackgroundReadPriority);
    }

    @Test
    void hiddenKeyAtItsDefaultValueDropsFromTheFileOnResave(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE),
                "{\"useNbtTranscode\": true, \"lodDistanceChunks\": 64}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertTrue(c.useNbtTranscode);
        JsonObject saved = savedJson(configDir);
        assertFalse(saved.has("useNbtTranscode"),
                "a default-valued hidden key is dropped by the write-back (no behavior change)");
        assertEquals(64, saved.get("lodDistanceChunks").getAsInt());
    }

    @Test
    void legacyBandwidthKeysStillBindAndMigrateToMbKeysOnResave(@TempDir Path configDir) throws Exception {
        // An old file with only the byte-denominated keys: the values must be honored
        // identically (10 MiB/s stays 10 MiB/s) and the write-back migrates them into the
        // mb keys — the legacy spellings drop, but no information is lost.
        Files.writeString(configDir.resolve(FILE),
                "{\"bytesPerSecondLimitPerPlayer\": 10485760, \"bytesPerSecondLimitGlobal\": 31457280}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(10.0, c.mbPerSecondLimitPerPlayer);
        assertEquals(10_485_760, c.bytesPerSecondPerPlayer());
        assertEquals(30.0, c.mbPerSecondLimitGlobal);
        assertEquals(31_457_280, c.bytesPerSecondGlobal());

        JsonObject saved = savedJson(configDir);
        assertEquals(10.0, saved.get("mbPerSecondLimitPerPlayer").getAsDouble());
        assertEquals(30.0, saved.get("mbPerSecondLimitGlobal").getAsDouble());
        assertFalse(saved.has("bytesPerSecondLimitPerPlayer"), "legacy spelling migrates away");
        assertFalse(saved.has("bytesPerSecondLimitGlobal"));

        TestServerConfig reloaded = TestServerConfig.load(configDir); // the migrated file round-trips
        assertEquals(10_485_760, reloaded.bytesPerSecondPerPlayer());
        assertEquals(31_457_280, reloaded.bytesPerSecondGlobal());
    }

    @Test
    void mbKeysBeatLegacyKeysWhenBothArePresent(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE),
                "{\"mbPerSecondLimitPerPlayer\": 5, \"bytesPerSecondLimitPerPlayer\": 10485760,"
                        + " \"mbPerSecondLimitGlobal\": 20, \"bytesPerSecondLimitGlobal\": 31457280}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(5.0, c.mbPerSecondLimitPerPlayer, "the new key wins over the legacy one");
        assertEquals(5_242_880, c.bytesPerSecondPerPlayer());
        assertEquals(20.0, c.mbPerSecondLimitGlobal);
        assertEquals(20_971_520, c.bytesPerSecondGlobal());
    }

    @Test
    void decimalMbValuesBindResolveAndRoundTrip(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "{\"mbPerSecondLimitPerPlayer\": 12.5}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(12.5, c.mbPerSecondLimitPerPlayer);
        assertEquals(13_107_200, c.bytesPerSecondPerPlayer()); // 12.5 * 1024 * 1024, exact
        assertEquals(12.5, savedJson(configDir).get("mbPerSecondLimitPerPlayer").getAsDouble(),
                "the decimal survives the write-back");
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
                "{\"lodDistanceChunk\": 64, \"diskReaderThreads\": 12}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(512, c.lodDistanceChunks); // the typo'd value never binds
        JsonObject saved = savedJson(configDir);
        assertFalse(saved.has("lodDistanceChunk"), "typo'd key must be dropped by the re-save");
        assertEquals(512, saved.get("lodDistanceChunks").getAsInt());
        assertEquals(12, saved.get("diskReaderThreads").getAsInt()); // bound values survive the rewrite
        // Same mechanism retires a key: lodStoreBackfillTickCeilingMillis and lodStoreMemoryMB
        // are gone as fields, so a file still carrying them loads fine and the re-save drops
        // them (config review §6 — sendQueueLimitPerPlayer and useBackgroundReadPriority were
        // proposed for the same treatment and kept, being live test-harness levers).
        assertFalse(saved.has("lodStoreBackfillTickCeilingMillis"));
        assertFalse(saved.has("lodStoreMemoryMB"));
    }

    @Test
    void singleBadFieldRevertsValidCustomizationsInTheSameFile(@TempDir Path configDir) throws Exception {
        // GSON binds the whole object or nothing: the valid sendQueueLimitPerPlayer=123 (read
        // before the failure) is discarded with the rest. One bad field costs every customization.
        String broken = "{\"diskReaderThreads\": 12, \"lodDistanceChunks\": \"lots\"}";
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(0, c.diskReaderThreads);          // the valid customization is reverted too
        assertEquals(512, c.lodDistanceChunks);
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void literalNullBodyLoadsDefaultsAndIsLeftUntouched(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "null"); // fromJson -> null, same outcome as empty

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(512, c.lodDistanceChunks);
        assertEquals("null", Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void whitespaceOnlyBodyLoadsDefaultsAndIsLeftUntouched(@TempDir Path configDir) throws Exception {
        String body = " \n\t  ";
        Files.writeString(configDir.resolve(FILE), body);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(512, c.lodDistanceChunks);
        assertEquals(body, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void nullValuedPrimitiveKeepsCompiledDefaultAndHealsOnResave(@TempDir Path configDir) throws Exception {
        // JSON null for a primitive field is SKIPPED by GSON (not an error): the compiled default
        // stays, the parse succeeds — so sibling customizations survive and the re-save heals the
        // null into a real number. Contrast with the wrong-typed case, which reverts the whole file.
        Files.writeString(configDir.resolve(FILE),
                "{\"lodDistanceChunks\": null, \"diskReaderThreads\": 12}");

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(512, c.lodDistanceChunks);       // compiled default kept
        assertEquals(12, c.diskReaderThreads);        // parse succeeded: sibling customization kept
        JsonObject saved = savedJson(configDir);
        assertEquals(512, saved.get("lodDistanceChunks").getAsInt()); // healed to a number on disk
        assertEquals(12, saved.get("diskReaderThreads").getAsInt());
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

        assertEquals(512, c.lodDistanceChunks); // NOT truncated to 32
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void intOverflowRevertsToDefaultsInsteadOfClamping(@TempDir Path configDir) throws Exception {
        String broken = "{\"lodDistanceChunks\": 2147483648}"; // Integer.MAX_VALUE + 1
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        // The overflow dies in the PARSER, before validate() ever runs: the result is the
        // compiled default (300), not the clamp ceiling (2048) a successful bind would produce.
        assertEquals(512, c.lodDistanceChunks);
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

        assertEquals(512, c.lodDistanceChunks); // not-a-regular-file -> missing-file path -> defaults
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
            assertEquals(512, c.lodDistanceChunks);
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

    // ---- the XVER §9 client fallback-config file shapes (LSSClientConfig via the real
    // load path — the fields ClientIdentityResolver snapshots at construction) ----

    @Test
    void clientListValuedCuratedTableRevertsWholeFileToDefaults(@TempDir Path configDir) throws Exception {
        // crossVersionBlockFallbacks hand-edited into a LIST: GSON cannot bind a JSON
        // array to Map<String,String>, and it binds the whole object or nothing — the
        // valid receiveServerLods=false customization is discarded with it (the
        // documented whole-file-fallback semantics, same as the server config's
        // wrong-typed case). The broken file must survive untouched for fixing; the
        // client must still boot with a usable resolver config.
        String broken = "{\"receiveServerLods\": false, "
                + "\"crossVersionBlockFallbacks\": [\"minecraft:sulfur\", \"minecraft:sandstone\"]}";
        Files.writeString(configDir.resolve(TestClientConfig.CLIENT_FILE), broken);

        TestClientConfig c = assertDoesNotThrow(() -> TestClientConfig.load(configDir));

        assertTrue(c.receiveServerLods, "whole-file revert: the sibling customization is lost too");
        assertEquals("minecraft:stone", c.unknownBlockFallback);
        assertNotNull(c.crossVersionBlockFallbacks);
        assertTrue(c.crossVersionBlockFallbacks.isEmpty());
        assertEquals(broken, Files.readString(configDir.resolve(TestClientConfig.CLIENT_FILE)),
                "the broken file is preserved for the admin, never overwritten");
    }

    @Test
    void clientObjectValuedCuratedEntryRevertsWholeFileToDefaults(@TempDir Path configDir) throws Exception {
        // The other malformation shape: the map itself parses but an ENTRY VALUE is an
        // object — GSON's String adapter throws mid-bind, so this too is the
        // whole-file revert, not a per-entry skip.
        String broken = "{\"receiveServerLods\": false, \"crossVersionBlockFallbacks\": "
                + "{\"minecraft:sulfur\": {\"target\": \"minecraft:sandstone\"}}}";
        Files.writeString(configDir.resolve(TestClientConfig.CLIENT_FILE), broken);

        TestClientConfig c = assertDoesNotThrow(() -> TestClientConfig.load(configDir));

        assertTrue(c.receiveServerLods);
        assertTrue(c.crossVersionBlockFallbacks.isEmpty());
        assertEquals(broken, Files.readString(configDir.resolve(TestClientConfig.CLIENT_FILE)));
    }

    @Test
    void clientNumberValuedCuratedEntryBindsLenientlyAsItsStringForm(@TempDir Path configDir) throws Exception {
        // GSON's String adapter reads a bare NUMBER token as its string form (the
        // booleanStringYesSilentlyDisables leniency family): {"a": 5} binds as "5",
        // the load succeeds, siblings survive. Harmless downstream — the resolver's
        // curated rung simply fails to resolve "5" and continues down the ladder —
        // but it is today's contract and the boundary of the whole-file revert above,
        // so pin it before anyone assumes non-string values also revert.
        Files.writeString(configDir.resolve(TestClientConfig.CLIENT_FILE),
                "{\"receiveServerLods\": false, \"crossVersionBlockFallbacks\": {\"ancient:sulfur\": 5}}");

        TestClientConfig c = assertDoesNotThrow(() -> TestClientConfig.load(configDir));

        assertFalse(c.receiveServerLods, "leniency path, not the whole-file revert");
        assertEquals("5", c.crossVersionBlockFallbacks.get("ancient:sulfur"));
    }

    @Test
    void clientNullValuedFallbackFieldsHealThroughLoadAndResave(@TempDir Path configDir) throws Exception {
        // Explicit JSON nulls on OBJECT fields (unlike primitives) really are ASSIGNED
        // by GSON — without the validate() heals this would hand a null curated table
        // to ClientIdentityResolver's Map.copyOf (a crash on the first v20 column) and
        // a null terminal fallback every session. The parse itself succeeds, so
        // sibling customizations survive and the load-time re-save heals the nulls
        // into real values on disk.
        Files.writeString(configDir.resolve(TestClientConfig.CLIENT_FILE),
                "{\"receiveServerLods\": false, \"unknownBlockFallback\": null, "
                        + "\"crossVersionBlockFallbacks\": null}");

        TestClientConfig c = assertDoesNotThrow(() -> TestClientConfig.load(configDir));

        assertFalse(c.receiveServerLods, "parse succeeded: the sibling customization is kept");
        assertEquals("minecraft:stone", c.unknownBlockFallback);
        assertNotNull(c.crossVersionBlockFallbacks);
        assertTrue(c.crossVersionBlockFallbacks.isEmpty());

        JsonObject saved = savedJson(configDir, TestClientConfig.CLIENT_FILE);
        assertEquals("minecraft:stone", saved.get("unknownBlockFallback").getAsString(),
                "the re-save must heal the null on disk");
        assertTrue(saved.get("crossVersionBlockFallbacks").isJsonObject(),
                "the curated table must be healed to a real (empty) map on disk");
        assertFalse(saved.get("receiveServerLods").getAsBoolean());
    }

    // ---- brand-fallback candidate resolution: JsonConfig.load(String[], ...) ----
    // A branded config resolves its filename from an ordered candidate list — the running brand's
    // OWN file first, the other brand's file as a fallback — so an LSS<->VSS jar swap keeps the same
    // config file instead of forking a fresh one. These pin the resolution/adopt/create mechanism
    // with explicit arrays (independent of which brand is running; the brand-driven ORDERING itself
    // is pinned in dev.vox.lss.common.ConfigBrandCandidatesTest). "vss"/"lss" here are just two test
    // filenames — position in the array is what matters, not the names.
    private static final String[] VSS_FIRST = {"vss-server-config.json", "lss-server-config.json"};

    @Test
    void candidateLoadPrefersTheFirstExistingCandidate(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve("vss-server-config.json"), "{\"lodDistanceChunks\": 111}");
        Files.writeString(configDir.resolve("lss-server-config.json"), "{\"lodDistanceChunks\": 222}");

        TestServerConfig c = TestServerConfig.load(VSS_FIRST, configDir);

        assertEquals(111, c.lodDistanceChunks, "the primary (first candidate) wins when both exist");
        // primary re-saved in place (write-back); the fallback is left exactly as authored
        assertEquals(111, savedJson(configDir, "vss-server-config.json").get("lodDistanceChunks").getAsInt());
        assertEquals("{\"lodDistanceChunks\": 222}",
                Files.readString(configDir.resolve("lss-server-config.json")), "the unused fallback is untouched");
    }

    @Test
    void candidateLoadFallsBackToSecondAndAdoptsItForWrites(@TempDir Path configDir) throws Exception {
        // Only the fallback (the OTHER brand's file) exists — a VSS jar dropped onto an LSS install.
        Files.writeString(configDir.resolve("lss-server-config.json"), "{\"lodDistanceChunks\": 222}");

        TestServerConfig c = TestServerConfig.load(VSS_FIRST, configDir);

        assertEquals(222, c.lodDistanceChunks, "falls back to the other brand's existing file");
        // adopt-and-write-the-SAME-file: the fallback is migrated in place, NO second primary forked
        assertTrue(savedJson(configDir, "lss-server-config.json").has("mbPerSecondLimitPerPlayer"),
                "the adopted fallback is written back (migrated), not abandoned");
        assertFalse(Files.exists(configDir.resolve("vss-server-config.json")),
                "adopting the fallback must NOT fork a fresh primary that would shadow it");
    }

    @Test
    void candidateLoadCreatesThePrimaryWhenNoCandidateExists(@TempDir Path configDir) throws Exception {
        TestServerConfig c = TestServerConfig.load(VSS_FIRST, configDir);

        assertEquals(512, c.lodDistanceChunks); // defaults
        assertEquals("on", c.lodStore, "the candidate-create path is a fresh install: onFreshCreate applies");
        // This is the assertion that distinguishes activeFileName (candidates[0]) from getFileName()
        // (which is "lss-..." here): a genuinely fresh install creates the brand-PRIMARY, not getFileName.
        assertTrue(Files.isRegularFile(configDir.resolve("vss-server-config.json")),
                "a fresh install creates the brand-primary (first candidate)");
        assertFalse(Files.exists(configDir.resolve("lss-server-config.json")),
                "the fallback file is never created from scratch");
    }

    @Test
    void adoptedFallbackStaysTheWriteTargetAcrossReloads(@TempDir Path configDir) throws Exception {
        // The swap-keeps-config guarantee across restarts: once adopted, the fallback stays the write
        // target — an admin editing it after the swap keeps seeing their edits, and no primary is ever
        // spawned to shadow it.
        Files.writeString(configDir.resolve("lss-server-config.json"), "{\"lodDistanceChunks\": 96}");

        TestServerConfig.load(VSS_FIRST, configDir);                                       // adopt lss-*
        Files.writeString(configDir.resolve("lss-server-config.json"), "{\"lodDistanceChunks\": 128}"); // admin re-edits it
        TestServerConfig c = TestServerConfig.load(VSS_FIRST, configDir);                  // still resolves lss-*

        assertEquals(128, c.lodDistanceChunks, "the adopted file's later edits still take effect");
        assertFalse(Files.exists(configDir.resolve("vss-server-config.json")),
                "no primary is ever spawned to shadow the adopted fallback");
    }

    @Test
    void corruptPrimaryIsLeftUntouchedAndDoesNotPromoteTheFallback(@TempDir Path configDir) throws Exception {
        // The primary EXISTS but is corrupt; the fallback is valid. Resolution stops at the first
        // existing candidate (the corrupt primary), which is left for the admin to fix — the valid
        // fallback is NOT silently promoted (that would hide the corruption). Defaults in memory.
        String brokenPrimary = "{\"lodDistanceChunks\": 111"; // interrupted write: no closing brace
        Files.writeString(configDir.resolve("vss-server-config.json"), brokenPrimary);
        Files.writeString(configDir.resolve("lss-server-config.json"), "{\"lodDistanceChunks\": 222}");

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(VSS_FIRST, configDir));

        assertEquals(512, c.lodDistanceChunks, "defaults — not the fallback's 222 nor the corrupt 111");
        assertEquals(brokenPrimary, Files.readString(configDir.resolve("vss-server-config.json")),
                "the corrupt primary is preserved for the admin to fix, never overwritten");
        assertEquals("{\"lodDistanceChunks\": 222}",
                Files.readString(configDir.resolve("lss-server-config.json")),
                "the fallback stays untouched — resolution stopped at the existing (corrupt) primary");
    }

    @Test
    void transientBookkeepingFieldsAreNeverSerialized(@TempDir Path configDir) throws Exception {
        // activeFileName (the adopted save target) and configDir are transient bookkeeping. GSON must
        // never write them into the config file — otherwise an admin sees bogus keys and a re-load
        // would bind them. serializedFieldNames() only reflects PUBLIC fields, so it can't catch a
        // private transient regressing to non-transient; this asserts their ABSENCE on disk directly.
        TestServerConfig.load(VSS_FIRST, configDir); // fresh create -> writes candidates[0]
        JsonObject saved = savedJson(configDir, "vss-server-config.json");
        assertFalse(saved.has("activeFileName"), "activeFileName must stay transient (not serialized)");
        assertFalse(saved.has("configDir"), "configDir must stay transient (not serialized)");
        assertTrue(saved.has("lodDistanceChunks"), "sanity: real fields ARE serialized");
    }
}
