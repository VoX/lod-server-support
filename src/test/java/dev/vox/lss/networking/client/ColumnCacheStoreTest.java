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
}
