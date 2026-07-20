package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidationTest {

    // --- LSSServerConfig ---

    private LSSServerConfig serverConfig() {
        return new LSSServerConfig();
    }

    @Test
    void lodDistanceChunksClamped() {
        var c = serverConfig();
        c.lodDistanceChunks = 0;
        c.validate();
        assertEquals(1, c.lodDistanceChunks);

        c.lodDistanceChunks = 99999;
        c.validate();
        assertEquals(2048, c.lodDistanceChunks);
    }

    @Test
    void bytesPerSecondLimitPerPlayerClamped() {
        var c = serverConfig();
        c.bytesPerSecondLimitPerPlayer = 100;
        c.validate();
        assertEquals(1024, c.bytesPerSecondLimitPerPlayer);

        c.bytesPerSecondLimitPerPlayer = 200_000_000;
        c.validate();
        assertEquals(104_857_600, c.bytesPerSecondLimitPerPlayer);
    }

    @Test
    void diskReaderThreadsClamped() {
        var c = serverConfig();
        c.diskReaderThreads = 0;
        c.validate();
        assertEquals(1, c.diskReaderThreads);

        c.diskReaderThreads = 100;
        c.validate();
        assertEquals(64, c.diskReaderThreads);
    }

    @Test
    void sendQueueLimitPerPlayerClamped() {
        var c = serverConfig();
        c.sendQueueLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.sendQueueLimitPerPlayer);

        c.sendQueueLimitPerPlayer = 999999;
        c.validate();
        assertEquals(100_000, c.sendQueueLimitPerPlayer);
    }

    @Test
    void bytesPerSecondLimitGlobalClamped() {
        var c = serverConfig();
        c.bytesPerSecondLimitGlobal = 100;
        c.validate();
        assertEquals(1024, c.bytesPerSecondLimitGlobal);

        c.bytesPerSecondLimitGlobal = 2_000_000_000;
        c.validate();
        assertEquals(1_073_741_824, c.bytesPerSecondLimitGlobal);
    }

    @Test
    void generationConcurrencyLimitGlobalClamped() {
        var c = serverConfig();
        c.generationConcurrencyLimitGlobal = 0;
        c.validate();
        assertEquals(1, c.generationConcurrencyLimitGlobal);

        c.generationConcurrencyLimitGlobal = 999;
        c.validate();
        assertEquals(256, c.generationConcurrencyLimitGlobal);
    }

    @Test
    void generationTimeoutSecondsClamped() {
        var c = serverConfig();
        c.generationTimeoutSeconds = 0;
        c.validate();
        assertEquals(1, c.generationTimeoutSeconds);

        c.generationTimeoutSeconds = 9999;
        c.validate();
        assertEquals(600, c.generationTimeoutSeconds);
    }

    @Test
    void dirtyBroadcastIntervalSecondsClamped() {
        var c = serverConfig();
        c.dirtyBroadcastIntervalSeconds = 0;
        c.validate();
        assertEquals(1, c.dirtyBroadcastIntervalSeconds);

        c.dirtyBroadcastIntervalSeconds = 9999;
        c.validate();
        assertEquals(300, c.dirtyBroadcastIntervalSeconds);
    }

    @Test
    void generationConcurrencyLimitPerPlayerClamped() {
        var c = serverConfig();
        c.generationConcurrencyLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.generationConcurrencyLimitPerPlayer);

        // Plain per-field bound: the #28 cross-clamp is gone (no client budget derives
        // from this cap anymore; the successor invariant lives in WantSetBudgetInvariantTest).
        c.generationConcurrencyLimitPerPlayer = 9999;
        c.validate();
        assertEquals(LSSConstants.MAX_CONCURRENCY_LIMIT, c.generationConcurrencyLimitPerPlayer);
    }

    @Test
    void perDimensionTimestampCacheSizeMBClamped() {
        var c = serverConfig();
        c.perDimensionTimestampCacheSizeMB = 0;
        c.validate();
        assertEquals(1, c.perDimensionTimestampCacheSizeMB);

        c.perDimensionTimestampCacheSizeMB = 9999;
        c.validate();
        assertEquals(256, c.perDimensionTimestampCacheSizeMB);
    }

    // --- Reflective clamp sweep ---

    private static List<Field> numericServerConfigFields() {
        List<Field> fields = Arrays.stream(LSSServerConfig.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> f.getType().isPrimitive() && f.getType() != boolean.class)
                .toList();
        // Guard against the sweep going vacuous if fields get refactored to non-public.
        // (10 since the syncOnLoadConcurrencyLimitPerPlayer knob became a constant.)
        assertTrue(fields.size() >= 10, "clamp sweep lost fields, found only: " + fields);
        assertTrue(fields.stream().anyMatch(f -> f.getName().equals("perDimensionTimestampCacheSizeMB")),
                "clamp sweep no longer sees perDimensionTimestampCacheSizeMB");
        return fields;
    }

    /**
     * Every numeric server-config field must be pulled back to a sane range by validate(),
     * even from int extremes. Auto-catches future fields added without a clamp — the named
     * tests above pin the exact bounds, this pins that bounds exist at all.
     */
    @Test
    void everyNumericServerFieldClampedAtIntExtremes() throws Exception {
        for (Field f : numericServerConfigFields()) {
            assertEquals(int.class, f.getType(),
                    f.getName() + ": extend the clamp sweep for non-int numeric fields");

            var c = serverConfig();
            f.setInt(c, Integer.MIN_VALUE);
            c.validate();
            // missMemoTtlSeconds is the one field whose legal floor is 0 (the memo kill
            // switch, MIN_MISS_MEMO_TTL_SECONDS) — every other numeric floor is >= 1.
            int floor = f.getName().equals("missMemoTtlSeconds") ? 0 : 1;
            assertTrue(f.getInt(c) >= floor,
                    f.getName() + " not clamped up from Integer.MIN_VALUE, still " + f.getInt(c));

            f.setInt(c, Integer.MAX_VALUE);
            c.validate();
            assertTrue(f.getInt(c) < Integer.MAX_VALUE,
                    f.getName() + " not clamped down from Integer.MAX_VALUE");
        }
    }

    /** Compiled defaults must already sit inside their clamp ranges: validate() may not move them. */
    @Test
    void defaultsSurviveValidateUnchanged() throws Exception {
        var validated = serverConfig();
        validated.validate();
        var pristine = serverConfig();
        for (Field f : LSSServerConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            assertEquals(f.get(pristine), f.get(validated),
                    "default for " + f.getName() + " is outside its clamp range");
        }
    }

    /** LOD reads yield to gameplay out of the box; false is the documented rollback. */
    @Test
    void backgroundReadPriorityDefaultsOn() {
        assertTrue(serverConfig().useBackgroundReadPriority,
                "background read priority must default on");
    }

    // --- LSSClientConfig ---

    private LSSClientConfig clientConfig() {
        return new LSSClientConfig();
    }

    @Test
    void clientLodDistanceChunksClamped() {
        var c = clientConfig();
        c.lodDistanceChunks = -1;
        c.validate();
        assertEquals(0, c.lodDistanceChunks);

        c.lodDistanceChunks = 99999;
        c.validate();
        assertEquals(2048, c.lodDistanceChunks);
    }

}
