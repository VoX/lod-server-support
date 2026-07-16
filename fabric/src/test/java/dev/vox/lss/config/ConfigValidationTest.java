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
    void syncOnLoadConcurrencyLimitPerPlayerClamped() {
        var c = serverConfig();
        c.syncOnLoadConcurrencyLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.syncOnLoadConcurrencyLimitPerPlayer);

        // The per-field bound is MAX_CONCURRENCY_LIMIT (1000), but Global Constraint #28's want-set
        // budget clamp pulls it below that: sync must leave room for the minimum gen hold-back plus
        // the frontier reserve. 1024 - 64 (reserve) - 1*4 (min gen hold-back) = 956.
        c.syncOnLoadConcurrencyLimitPerPlayer = 9999;
        c.validate();
        assertEquals(956, c.syncOnLoadConcurrencyLimitPerPlayer);
    }

    @Test
    void generationConcurrencyLimitPerPlayerClamped() {
        var c = serverConfig();
        c.generationConcurrencyLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.generationConcurrencyLimitPerPlayer);

        // Per-field bound is MAX_CONCURRENCY_LIMIT (1000), but Global Constraint #28 clamps gen to
        // whatever want-set budget remains after the (default 200) sync cap + frontier reserve:
        // (1024 - 200 - 64) / 4 = 190.
        c.generationConcurrencyLimitPerPlayer = 9999;
        c.validate();
        assertEquals(190, c.generationConcurrencyLimitPerPlayer);
    }

    /**
     * Global Constraint #28 boundary: at the MAX legal slot caps — the values nobody exercises but
     * validate() must survive — the client's want-set can still carry every in-flight re-declaration
     * plus at least the frontier reserve. Without the cross-clamp, syncCap=genCap=1000 would demand
     * 1000 + 1000*4 = 5000 in-flight slots against a 1024-position batch and starve discovery.
     */
    @Test
    void maxConfigLeavesFrontierHeadroomInTheWantSetBudget() {
        var c = serverConfig();
        c.syncOnLoadConcurrencyLimitPerPlayer = LSSConstants.MAX_CONCURRENCY_LIMIT;
        c.generationConcurrencyLimitPerPlayer = LSSConstants.MAX_CONCURRENCY_LIMIT;
        c.validate();

        int inFlightWorstCase = c.syncOnLoadConcurrencyLimitPerPlayer
                + c.generationConcurrencyLimitPerPlayer * LSSConstants.SCAN_BUDGET_MULTIPLIER;
        assertTrue(inFlightWorstCase + LSSConstants.WANT_SET_FRONTIER_RESERVE
                        <= LSSConstants.MAX_BATCH_CHUNK_REQUESTS,
                "MAX config starves the want-set frontier: in-flight re-declarations "
                        + inFlightWorstCase + " + reserve leave no headroom in the "
                        + LSSConstants.MAX_BATCH_CHUNK_REQUESTS + "-position batch");
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
        assertTrue(fields.size() >= 11, "clamp sweep lost fields, found only: " + fields);
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
            assertTrue(f.getInt(c) >= 1,
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
