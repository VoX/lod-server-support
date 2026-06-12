package dev.vox.lss.networking.client;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ColumnCacheStoreTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ResourceKey<Level> testDimension(String name) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:" + name));
    }

    private static Path getCacheFile(String serverAddress, ResourceKey<Level> dimension) {
        String dimKey = dimension.identifier().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
        String serverKey = serverAddress.replaceAll("[^a-zA-Z0-9._-]", "_");
        return FabricLoader.getInstance().getConfigDir()
                .resolve("lss").resolve("cache").resolve(serverKey).resolve(dimKey + ".bin");
    }

    @Test
    void saveAndLoadRoundtrip() {
        var dim = testDimension("roundtrip");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(100L, 1000L);
        map.put(200L, 2000L);
        map.put(300L, 3000L);

        ColumnCacheStore.save("test-server-rt", dim, map);
        var loaded = ColumnCacheStore.load("test-server-rt", dim);

        assertEquals(3, loaded.size());
        assertEquals(1000L, loaded.get(100L));
        assertEquals(2000L, loaded.get(200L));
        assertEquals(3000L, loaded.get(300L));
    }

    @Test
    void clearForServerRunsAfterQueuedAsyncSave() {
        var dim = testDimension("fifo_clear");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(7L, 700L);

        ColumnCacheStore.saveAsync("test-fifo-clear", dim, map);
        // clearForServer runs on the same FIFO IO thread and waits, so it must execute
        // AFTER the queued save — the save cannot resurrect the files we just cleared.
        ColumnCacheStore.clearForServer("test-fifo-clear");

        assertTrue(ColumnCacheStore.load("test-fifo-clear", dim).isEmpty(),
                "a queued async save must not survive a subsequent synchronous clear");
    }

    @Test
    void flushPendingIoWaitsForQueuedAsyncSave() {
        var dim = testDimension("flush_wait");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(9L, 900L);

        ColumnCacheStore.saveAsync("test-flush-wait", dim, map);
        // The soak/benchmark harness halts the JVM right after this call — it must
        // guarantee the queued save has landed on disk.
        ColumnCacheStore.flushPendingIo();

        assertEquals(900L, ColumnCacheStore.load("test-flush-wait", dim).get(9L));
        ColumnCacheStore.clearForServer("test-flush-wait");
    }

    @Test
    void missingFileReturnsEmpty() {
        var dim = testDimension("missing");
        var loaded = ColumnCacheStore.load("nonexistent-server", dim);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void invalidFormatVersionReturnsEmpty() throws IOException {
        var dim = testDimension("bad_version");
        Path file = getCacheFile("test-bad-version", dim);
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(999); // wrong version
            out.writeInt(1);
            out.writeLong(1L);
            out.writeLong(2L);
        }

        var loaded = ColumnCacheStore.load("test-bad-version", dim);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void excessiveCountReturnsEmpty() throws IOException {
        var dim = testDimension("excess_count");
        Path file = getCacheFile("test-excess", dim);
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(1); // correct version
            out.writeInt(3_000_000); // exceeds 2_000_000 guard
        }

        var loaded = ColumnCacheStore.load("test-excess", dim);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void truncatedDataReturnsPartial() throws IOException {
        var dim = testDimension("truncated");
        Path file = getCacheFile("test-truncated", dim);
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(1); // correct version
            out.writeInt(5); // claims 5 entries
            // Only write 1 complete entry
            out.writeLong(42L);
            out.writeLong(100L);
            // Truncated — rest is missing
        }

        var loaded = ColumnCacheStore.load("test-truncated", dim);
        // IOException during read → returns partial map (whatever was read before error)
        // The implementation catches IOException and returns whatever was loaded
        assertTrue(loaded.size() <= 1);
    }

    @Test
    void clearForServerRemovesFiles() {
        var dim = testDimension("clear_test");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(1L, 1L);

        ColumnCacheStore.save("test-clear-server", dim, map);
        ColumnCacheStore.clearForServer("test-clear-server");

        var loaded = ColumnCacheStore.load("test-clear-server", dim);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void v2FileMigrationStripsLevelBits() throws IOException {
        var dim = testDimension("v2_migration");
        Path file = getCacheFile("test-v2-migration", dim);
        Files.createDirectories(file.getParent());
        long ts1 = 1_750_000_000L;
        long ts2 = 12_345L;
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(2); // v2 encoded values as (timestamp << 8 | detail level)
            out.writeInt(2);
            out.writeLong(100L);
            out.writeLong((ts1 << 8) | 7L);
            out.writeLong(200L);
            out.writeLong((ts2 << 8) | 0L);
        }

        var loaded = ColumnCacheStore.load("test-v2-migration", dim);
        assertEquals(2, loaded.size());
        // Loading v2 values raw would inflate timestamps ~256x — the server would then
        // answer up-to-date for stale columns forever.
        assertEquals(ts1, loaded.get(100L), "v2 entries must have the packed level bits stripped");
        assertEquals(ts2, loaded.get(200L));
    }

    @Test
    void v1FileLoadsTimestampsVerbatim() throws IOException {
        var dim = testDimension("v1_verbatim");
        Path file = getCacheFile("test-v1-verbatim", dim);
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(1); // v1: raw timestamps, nothing packed
            out.writeInt(1);
            out.writeLong(42L);
            out.writeLong(1_700_000_000L);
        }

        var loaded = ColumnCacheStore.load("test-v1-verbatim", dim);
        assertEquals(1, loaded.size());
        assertEquals(1_700_000_000L, loaded.get(42L), "v1 values must not be shifted on load");
    }

    @Test
    void saveAsyncSnapshotsBeforeCallerMutates() {
        var dim = testDimension("snapshot");
        // Gate: occupy the FIFO IO thread with a slow save so the snapshot save below
        // cannot start until long after the caller's mutations have completed. Without
        // the defensive copy in saveAsync, the mutated map is what would get written.
        var gateMap = new Long2LongOpenHashMap();
        gateMap.defaultReturnValue(-1L);
        for (int i = 0; i < 20_000; i++) gateMap.put(i, i);
        ColumnCacheStore.saveAsync("test-snapshot-gate", dim, gateMap);

        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(1L, 111L);
        map.put(2L, 222L);
        ColumnCacheStore.saveAsync("test-snapshot", dim, map);
        // Mutate immediately — mirrors onDimensionChange's saveCache-then-clear ordering.
        map.put(1L, 999_999L);
        map.remove(2L);
        map.put(3L, 333L);

        ColumnCacheStore.flushPendingIo();
        var loaded = ColumnCacheStore.load("test-snapshot", dim);
        assertEquals(2, loaded.size(), "saved file must reflect the map as it was at saveAsync time");
        assertEquals(111L, loaded.get(1L));
        assertEquals(222L, loaded.get(2L));

        ColumnCacheStore.clearForServer("test-snapshot-gate");
        ColumnCacheStore.clearForServer("test-snapshot");
    }

    @Test
    void dimensionFilesDoNotBleedAcrossDimensions() {
        var dimA = testDimension("iso_a");
        var dimB = testDimension("iso_b");
        var mapA = new Long2LongOpenHashMap();
        mapA.defaultReturnValue(-1L);
        mapA.put(100L, 1111L);
        var mapB = new Long2LongOpenHashMap();
        mapB.defaultReturnValue(-1L);
        mapB.put(100L, 2222L); // same packed position, different dimension
        mapB.put(200L, 3333L);

        ColumnCacheStore.save("test-dim-iso", dimA, mapA);
        ColumnCacheStore.save("test-dim-iso", dimB, mapB);

        var loadedA = ColumnCacheStore.load("test-dim-iso", dimA);
        var loadedB = ColumnCacheStore.load("test-dim-iso", dimB);
        assertEquals(1, loadedA.size());
        assertEquals(1111L, loadedA.get(100L), "dimension A keeps its own timestamp for the shared position");
        assertEquals(2, loadedB.size());
        assertEquals(2222L, loadedB.get(100L));
        assertEquals(3333L, loadedB.get(200L));
    }

    @Test
    void clearForServerRemovesAllDimensionFiles() {
        var dimA = testDimension("clear_multi_a");
        var dimB = testDimension("clear_multi_b");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(1L, 10L);

        ColumnCacheStore.save("test-clear-multi", dimA, map);
        ColumnCacheStore.save("test-clear-multi", dimB, map);
        ColumnCacheStore.clearForServer("test-clear-multi");

        assertTrue(ColumnCacheStore.load("test-clear-multi", dimA).isEmpty());
        assertTrue(ColumnCacheStore.load("test-clear-multi", dimB).isEmpty());
    }

    @Test
    void v2MigrationSignExtendsNegativeValues() throws IOException {
        var dim = testDimension("v2_negative");
        Path file = getCacheFile("test-v2-negative", dim);
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(2); // v2 encoded values as (timestamp << 8 | detail level)
            out.writeInt(2);
            out.writeLong(100L);
            out.writeLong((-1L << 8) | 7L); // ts=-1 (unknown sentinel) packed with level bits
            out.writeLong(200L);
            out.writeLong(-1000L); // corrupt negative raw value
        }

        var loaded = ColumnCacheStore.load("test-v2-negative", dim);
        assertEquals(2, loaded.size());
        assertTrue(loaded.containsKey(100L));
        // The migration shift must be arithmetic (>>), sign-extending: a packed -1
        // timestamp recovers exactly the -1 unknown sentinel, and negative garbage stays
        // negative. A logical >>> would turn -1000 into ~7.2e16 — a far-future epoch the
        // server answers up-to-date forever, black-holing the position.
        assertEquals(-1L, loaded.get(100L), "packed -1 timestamp must migrate back to the -1 sentinel");
        assertEquals(-4L, loaded.get(200L), "negative raw value must sign-extend, not become a huge positive epoch");

        // The recovered -1 composes with the classify ladder: unknown, so it re-requests.
        // Values below -1 (the 200L entry) are ColumnStateMap's loadFrom guard's concern.
        var states = new ColumnStateMap();
        states.loadFrom(loaded);
        assertEquals(-1L, states.classify(100L, true), "migrated -1 classifies unknown (re-request), not satisfied");
    }

    @Test
    void minusOneEntriesRoundtripToUnknownClassify() {
        var dim = testDimension("minus_one");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(100L, -1L); // never-seen sentinel explicitly present in the saved map
        map.put(200L, 5000L);

        ColumnCacheStore.save("test-minus-one", dim, map);
        var loaded = ColumnCacheStore.load("test-minus-one", dim);

        assertEquals(2, loaded.size(), "-1 entries roundtrip verbatim, not filtered at save or load");
        assertTrue(loaded.containsKey(100L));
        assertEquals(-1L, loaded.get(100L));

        var states = new ColumnStateMap();
        states.loadFrom(loaded);
        assertEquals(-1L, states.classify(100L, true), "a roundtripped -1 stays unknown — re-requested, never satisfied");
        assertEquals(1, states.receivedCount(), "only the positive entry counts as received");
        assertEquals(0, states.emptyCount(), "-1 is not a not-generated stamp");
    }

    @Test
    void sanitizationCollapsesColonAndUnderscoreServerIds() {
        // host:port and host_port sanitize to the same directory — a known, accepted
        // collision (worst case two server ids share a cache; resync corrects stale stamps).
        assertEquals(ColumnCacheStore.sanitizeForFilePath("coll.example:25565"),
                ColumnCacheStore.sanitizeForFilePath("coll.example_25565"));

        var dim = testDimension("collision");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(1L, 1234L);
        ColumnCacheStore.save("coll.example:25565", dim, map);

        assertEquals(1234L, ColumnCacheStore.load("coll.example_25565", dim).get(1L),
                "colliding server ids read each other's cache files");
        ColumnCacheStore.clearForServer("coll.example_25565");
        assertTrue(ColumnCacheStore.load("coll.example:25565", dim).isEmpty(),
                "colliding server ids clear each other's cache files");
    }

    @Test
    void hostileServerAddressCannotEscapeCacheDir() {
        String hostile = "lss-evil/../../traversal-target";
        String sanitized = ColumnCacheStore.sanitizeForFilePath(hostile);
        assertEquals("lss-evil_.._.._traversal-target", sanitized);
        assertFalse(sanitized.contains("/"));
        assertFalse(sanitized.contains("\\"));

        // ".." survives sanitization only embedded inside a single path segment, where it
        // has no traversal meaning — the server dir stays a direct child of the cache dir.
        Path cacheDir = FabricLoader.getInstance().getConfigDir().resolve("lss").resolve("cache").normalize();
        assertEquals(cacheDir, cacheDir.resolve(sanitized).normalize().getParent());

        var dim = testDimension("traversal");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(1L, 1L);
        ColumnCacheStore.save(hostile, dim, map);

        assertTrue(Files.exists(getCacheFile(hostile, dim)), "file must land inside the sanitized server dir");
        // Where the raw id would have landed if separators were honored: config/lss/traversal-target.
        assertFalse(Files.exists(cacheDir.resolve(hostile).normalize()),
                "a traversal id must not write outside the cache dir");
        ColumnCacheStore.clearForServer(hostile);
    }

    @Test
    void oversizedSaveLoadsBackEmptyNotTruncated() {
        var dim = testDimension("oversized");
        // One entry over the 2_000_000 load guard (~32 MB file; kept a single test method
        // so it can be excluded if CI memory ever becomes an issue).
        var map = new Long2LongOpenHashMap(2_000_001);
        map.defaultReturnValue(-1L);
        for (long i = 0; i < 2_000_001; i++) map.put(i, 1L);

        ColumnCacheStore.save("test-oversized", dim, map);
        assertTrue(Files.exists(getCacheFile("test-oversized", dim)),
                "save writes the oversized file verbatim — the entry-count guard lives load-side");

        // Wholesale discard (load warns and rebuilds from scratch), never truncation —
        // silently keeping an arbitrary 2M-entry prefix would resync stale stamps as
        // authoritative. The discarded load must not throw and must keep the -1 sentinel.
        var loaded = ColumnCacheStore.load("test-oversized", dim);
        assertTrue(loaded.isEmpty(), "an oversized cache must be discarded wholesale, not truncated to the cap");
        assertEquals(-1L, loaded.defaultReturnValue(), "discarded load still answers the unknown sentinel");

        ColumnCacheStore.clearForServer("test-oversized");
    }

    @Test
    void clearAllRunsAfterQueuedAsyncSave() {
        var dim = testDimension("fifo_clear_all");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(7L, 700L);

        ColumnCacheStore.saveAsync("test-fifo-clear-all", dim, map);
        // Same FIFO contract as clearForServer: clearAll runs on the single IO thread and
        // waits, so the queued save cannot re-create files after the global clear.
        ColumnCacheStore.clearAll();

        assertTrue(ColumnCacheStore.load("test-fifo-clear-all", dim).isEmpty(),
                "a queued async save must not survive a subsequent global clear");
    }

    @Test
    void clearForServerLeavesOtherServersIntact() {
        var dim = testDimension("server_iso");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(5L, 500L);

        ColumnCacheStore.save("test-server-iso-a", dim, map);
        ColumnCacheStore.save("test-server-iso-b", dim, map);
        ColumnCacheStore.clearForServer("test-server-iso-a");

        assertTrue(ColumnCacheStore.load("test-server-iso-a", dim).isEmpty());
        assertEquals(500L, ColumnCacheStore.load("test-server-iso-b", dim).get(5L),
                "clearing one server must not touch another server's cache");
        ColumnCacheStore.clearForServer("test-server-iso-b");
    }

    @Test
    void clearAllRemovesEveryServersCache() {
        var dimA = testDimension("clear_all_a");
        var dimB = testDimension("clear_all_b");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(5L, 500L);

        ColumnCacheStore.save("test-clear-all-1", dimA, map);
        ColumnCacheStore.save("test-clear-all-2", dimB, map);
        ColumnCacheStore.clearAll(); // the /lss clearcache fallback when no manager is active

        assertTrue(ColumnCacheStore.load("test-clear-all-1", dimA).isEmpty());
        assertTrue(ColumnCacheStore.load("test-clear-all-2", dimB).isEmpty());
    }
}
