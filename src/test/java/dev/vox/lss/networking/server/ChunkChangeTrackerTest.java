package dev.vox.lss.networking.server;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkChangeTrackerTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ResourceKey<Level> testDimension(String name) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:" + name));
    }

    @Test
    void markThenDrainReturnsPositions() {
        var dim = testDimension("mark_drain");
        ChunkChangeTracker.markDirty(dim, new ChunkPos(10, 20));
        long[] result = ChunkChangeTracker.drainDirty(dim);
        assertNotNull(result);
        assertEquals(1, result.length);
    }

    @Test
    void secondDrainReturnsNull() {
        var dim = testDimension("second_drain");
        ChunkChangeTracker.markDirty(dim, new ChunkPos(1, 2));
        ChunkChangeTracker.drainDirty(dim);
        long[] result = ChunkChangeTracker.drainDirty(dim);
        assertNull(result);
    }

    @Test
    void unknownDimensionReturnsNull() {
        var dim = testDimension("unknown_dim");
        long[] result = ChunkChangeTracker.drainDirty(dim);
        assertNull(result);
    }

    @Test
    void multiplePositionsAllReturned() {
        var dim = testDimension("multi_pos");
        ChunkChangeTracker.markDirty(dim, new ChunkPos(1, 1));
        ChunkChangeTracker.markDirty(dim, new ChunkPos(2, 2));
        ChunkChangeTracker.markDirty(dim, new ChunkPos(3, 3));
        long[] result = ChunkChangeTracker.drainDirty(dim);
        assertNotNull(result);
        assertEquals(3, result.length);
    }

    @Test
    void separateDimensionsIsolated() {
        var dimA = testDimension("isolated_a");
        var dimB = testDimension("isolated_b");
        ChunkChangeTracker.markDirty(dimA, new ChunkPos(5, 5));
        ChunkChangeTracker.markDirty(dimB, new ChunkPos(6, 6));

        long[] resultA = ChunkChangeTracker.drainDirty(dimA);
        long[] resultB = ChunkChangeTracker.drainDirty(dimB);
        assertNotNull(resultA);
        assertNotNull(resultB);
        assertEquals(1, resultA.length);
        assertEquals(1, resultB.length);
    }
}
