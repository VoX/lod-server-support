package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins PaperConfig.validate(): the shared ServerConfigBase clamps must run through the Paper
 * subclass (i.e. the super.validate() call survives) and the Paper-only updateEvents null guard
 * must replace null with an empty list. The Fabric ConfigValidationTest exercises the same
 * clamps only via LSSServerConfig, so without this test the Paper override is unpinned.
 */
class PaperConfigValidationTest {

    @Test
    void validateClampsInheritedFieldsAndGuardsUpdateEvents() {
        PaperConfig c = new PaperConfig();
        c.lodDistanceChunks = 99999;
        c.syncOnLoadConcurrencyLimitPerPlayer = 0;
        c.updateEvents = null;
        c.validate();
        assertEquals(2048, c.lodDistanceChunks);                 // LSSConstants.MAX_LOD_DISTANCE via super.validate()
        assertEquals(1, c.syncOnLoadConcurrencyLimitPerPlayer);  // LSSConstants.MIN_CONCURRENCY_LIMIT via super.validate()
        assertEquals(List.of(), c.updateEvents);                 // Paper-only null guard
    }

    /** Paper inherits the shared read-priority default: LOD reads yield to gameplay out of the box. */
    @Test
    void backgroundReadPriorityDefaultsOn() {
        assertTrue(new PaperConfig().useBackgroundReadPriority,
                "background read priority must default on (Paper)");
    }

    // ---- full both-ends clamp sweep: Paper twin of ConfigValidationTest's reflective sweep ----

    private record Bounds(int min, int max) {}

    // Global Constraint #28: the want-set budget clamp pulls the two per-player slot caps below
    // their per-field MAX_CONCURRENCY_LIMIT. Sync loses room for the minimum gen hold-back plus the
    // frontier reserve (a fixed ceiling); gen loses room to whatever remains after the *default*
    // sync cap + reserve (so this couples to the compiled default, exercised at default in the sweep).
    private static final int DEFAULT_SYNC_CAP = new PaperConfig().syncOnLoadConcurrencyLimitPerPlayer;
    private static final int SYNC_BUDGET_MAX =
            LSSConstants.MAX_BATCH_CHUNK_REQUESTS - LSSConstants.WANT_SET_FRONTIER_RESERVE
                    - LSSConstants.MIN_CONCURRENCY_LIMIT * LSSConstants.SCAN_BUDGET_MULTIPLIER;
    private static final int GEN_BUDGET_MAX = Math.max(LSSConstants.MIN_CONCURRENCY_LIMIT,
            (LSSConstants.MAX_BATCH_CHUNK_REQUESTS - DEFAULT_SYNC_CAP - LSSConstants.WANT_SET_FRONTIER_RESERVE)
                    / LSSConstants.SCAN_BUDGET_MULTIPLIER);

    /** Expected clamp bounds per shared field — the same LSSConstants ServerConfigBase clamps with. */
    private static final Map<String, Bounds> SHARED_BOUNDS = Map.ofEntries(
            Map.entry("lodDistanceChunks",
                    new Bounds(LSSConstants.MIN_LOD_DISTANCE, LSSConstants.MAX_LOD_DISTANCE)),
            Map.entry("bytesPerSecondLimitPerPlayer",
                    new Bounds(LSSConstants.MIN_BYTES_PER_SECOND, LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER)),
            Map.entry("diskReaderThreads",
                    new Bounds(LSSConstants.MIN_DISK_READER_THREADS, LSSConstants.MAX_DISK_READER_THREADS)),
            Map.entry("sendQueueLimitPerPlayer",
                    new Bounds(LSSConstants.MIN_SEND_QUEUE_SIZE, LSSConstants.MAX_SEND_QUEUE_SIZE)),
            Map.entry("bytesPerSecondLimitGlobal",
                    new Bounds(LSSConstants.MIN_BYTES_PER_SECOND,
                            Math.toIntExact(LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT))),
            Map.entry("generationConcurrencyLimitGlobal",
                    new Bounds(LSSConstants.MIN_CONCURRENT_GENERATIONS, LSSConstants.MAX_CONCURRENT_GENERATIONS)),
            Map.entry("generationTimeoutSeconds",
                    new Bounds(LSSConstants.MIN_GENERATION_TIMEOUT, LSSConstants.MAX_GENERATION_TIMEOUT)),
            Map.entry("dirtyBroadcastIntervalSeconds",
                    new Bounds(LSSConstants.MIN_DIRTY_BROADCAST_INTERVAL, LSSConstants.MAX_DIRTY_BROADCAST_INTERVAL)),
            Map.entry("syncOnLoadConcurrencyLimitPerPlayer",
                    new Bounds(LSSConstants.MIN_CONCURRENCY_LIMIT, SYNC_BUDGET_MAX)),
            Map.entry("generationConcurrencyLimitPerPlayer",
                    new Bounds(LSSConstants.MIN_CONCURRENCY_LIMIT, GEN_BUDGET_MAX)),
            Map.entry("perDimensionTimestampCacheSizeMB",
                    new Bounds(LSSConstants.MIN_TIMESTAMP_CACHE_SIZE_MB, LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB)));

    /**
     * Every shared numeric field must clamp to the exact shared bounds THROUGH the Paper subclass
     * at both ends, and keep exact-boundary values. A PaperConfig.validate() override that loses
     * its super.validate() call (or a new ServerConfigBase field added without a clamp) fails here.
     */
    @Test
    void everyNumericFieldClampsToExactSharedBoundsAtBothEnds() throws Exception {
        List<Field> fields = Arrays.stream(PaperConfig.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> f.getType().isPrimitive() && f.getType() != boolean.class)
                .toList();
        // Anti-vacuity twin of the Fabric sweep guards: the sweep must keep seeing every field.
        assertEquals(SHARED_BOUNDS.keySet(),
                fields.stream().map(Field::getName).collect(Collectors.toSet()),
                "numeric field set drifted from SHARED_BOUNDS — update the sweep table");

        for (Field f : fields) {
            assertEquals(int.class, f.getType(), f.getName() + ": extend the sweep for non-int numeric fields");
            Bounds bounds = SHARED_BOUNDS.get(f.getName());

            PaperConfig c = new PaperConfig();
            f.setInt(c, Integer.MIN_VALUE);
            c.validate();
            assertEquals(bounds.min(), f.getInt(c), f.getName() + " must clamp up to the shared minimum");

            f.setInt(c, Integer.MAX_VALUE);
            c.validate();
            assertEquals(bounds.max(), f.getInt(c), f.getName() + " must clamp down to the shared maximum");

            f.setInt(c, bounds.min());
            c.validate();
            assertEquals(bounds.min(), f.getInt(c), f.getName() + " at the exact minimum must be kept");

            f.setInt(c, bounds.max());
            c.validate();
            assertEquals(bounds.max(), f.getInt(c), f.getName() + " at the exact maximum must be kept");
        }
    }

    /**
     * Global Constraint #28 boundary through the Paper subclass: at the MAX legal slot caps the
     * want-set can still carry every in-flight re-declaration plus at least the frontier reserve.
     * Without the shared cross-clamp, syncCap=genCap=1000 would demand 1000 + 1000*4 = 5000
     * in-flight slots against a 1024-position batch and starve frontier discovery.
     */
    @Test
    void maxConfigLeavesFrontierHeadroomInTheWantSetBudget() {
        PaperConfig c = new PaperConfig();
        c.syncOnLoadConcurrencyLimitPerPlayer = LSSConstants.MAX_CONCURRENCY_LIMIT;
        c.generationConcurrencyLimitPerPlayer = LSSConstants.MAX_CONCURRENCY_LIMIT;
        c.validate();

        int inFlightWorstCase = c.syncOnLoadConcurrencyLimitPerPlayer
                + c.generationConcurrencyLimitPerPlayer * LSSConstants.SCAN_BUDGET_MULTIPLIER;
        assertTrue(inFlightWorstCase + LSSConstants.WANT_SET_FRONTIER_RESERVE
                        <= LSSConstants.MAX_BATCH_CHUNK_REQUESTS,
                "MAX config starves the want-set frontier: in-flight re-declarations "
                        + inFlightWorstCase + " + reserve exceed the "
                        + LSSConstants.MAX_BATCH_CHUNK_REQUESTS + "-position batch");
    }

    /** Compiled Paper defaults must already sit inside the clamp ranges: validate() may not move them. */
    @Test
    void defaultsSurviveValidateUnchangedIncludingUpdateEvents() throws Exception {
        PaperConfig validated = new PaperConfig();
        validated.validate();
        PaperConfig pristine = new PaperConfig();
        for (Field f : PaperConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            assertEquals(f.get(pristine), f.get(validated),
                    "default for " + f.getName() + " is outside its clamp range");
        }
    }
}
