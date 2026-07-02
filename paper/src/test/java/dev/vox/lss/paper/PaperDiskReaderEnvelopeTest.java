package dev.vox.lss.paper;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.common.processing.ChunkReadResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the Paper-only disk-read glue's failure modes (PP-040): the shared triage envelope
 * is fully covered in common (fabric AbstractChunkDiskReaderTest), but the only NMS call
 * in the Paper read path — {@code chunkMap.read} inside
 * {@link PaperChunkDiskReader#submitReadDirect} / {@code PaperNbtSectionSerializer
 * .readAndSerializeSections} — never executed a failure in any test. The injected
 * {@code ChunkNbtRead} seam drives all five envelope outcomes through the REAL subclass
 * submit path: missing chunk → notFound, FULL-but-empty NBT → all-air with timestamp,
 * exceptionally-completed future and {@code DISK_READ_TIMEOUT_SECONDS} expiry → error →
 * notFound-shaped result, pool saturation → synchronous saturated bounce.
 */
class PaperDiskReaderEnvelopeTest {

    private static RegistryAccess REGISTRY_ACCESS;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, src.getOrThrow(Biomes.PLAINS).value(), Lifecycle.stable());
        biomes.freeze();
        REGISTRY_ACCESS = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }

    private PaperChunkDiskReader reader;
    private ServerLevel level;
    private UUID uuid;

    @BeforeEach
    void buildRig() {
        reader = new PaperChunkDiskReader(1);
        uuid = UUID.randomUUID();
        reader.registerPlayer(uuid);
        level = mock(ServerLevel.class);
        when(level.registryAccess()).thenReturn(REGISTRY_ACCESS);
    }

    @AfterEach
    void teardown() {
        reader.shutdown();
    }

    private ChunkReadResult awaitResult() throws InterruptedException {
        var queue = reader.getPlayerQueue(uuid);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var r = queue.poll();
            if (r != null) return r;
            Thread.sleep(1);
        }
        throw new AssertionError("no disk-read result within 5s");
    }

    /** FULL-status chunk NBT whose only section has no block_states: parses to zero sections. */
    private static CompoundTag fullButEmptyChunkNbt() {
        var tag = new CompoundTag();
        tag.putString("Status", "minecraft:full");
        var sections = new ListTag();
        var sec = new CompoundTag();
        sec.putInt("Y", 0);
        sections.add(sec);
        tag.put("sections", sections);
        return tag;
    }

    @Test
    void missingChunkResolvesAsNotFound() throws Exception {
        reader.setReadOverride((cx, cz) -> CompletableFuture.completedFuture(Optional.empty()));
        reader.submitReadDirect(uuid, "minecraft:overworld", level, 3, -4, 1L);

        var result = awaitResult();
        assertTrue(result.notFound(), "an absent region entry is notFound (escalates to generation)");
        assertFalse(result.saturated());
        assertEquals(3, result.chunkX());
        assertEquals(-4, result.chunkZ());
        assertEquals(1L, reader.getDiag().getNotFoundCount());
        assertEquals(0L, reader.getDiag().getErrorCount());
    }

    @Test
    void fullStatusChunkWithNoVisibleSectionsResolvesAsAllAirWithTimestamp() throws Exception {
        reader.setReadOverride((cx, cz) ->
                CompletableFuture.completedFuture(Optional.of(fullButEmptyChunkNbt())));
        reader.submitReadDirect(uuid, "minecraft:the_end", level, 0, 0, 2L);

        var result = awaitResult();
        assertFalse(result.notFound(), "FULL-but-empty must NOT be notFound — that would loop generation forever");
        assertFalse(result.saturated());
        assertNull(result.sectionBytes(), "all-air ships no bytes (the client treats absent sections as air)");
        assertTrue(result.columnTimestamp() > 0, "all-air still stamps a timestamp for up-to-date convergence");
        assertEquals(1L, reader.getDiag().getAllAirCount());
    }

    @Test
    void exceptionallyCompletedReadFutureResolvesAsErrorThenNotFound() throws Exception {
        reader.setReadOverride((cx, cz) ->
                CompletableFuture.failedFuture(new IOException("region file corrupt")));
        reader.submitReadDirect(uuid, "minecraft:overworld", level, 5, 5, 3L);

        var result = awaitResult();
        assertTrue(result.notFound(), "a throwing chunkMap.read future degrades to notFound, never a hang");
        assertEquals(1L, reader.getDiag().getErrorCount(), "the failure is visible in diagnostics");
        assertEquals(0L, reader.getDiag().getNotFoundCount(), "counted as error, not as a missing chunk");
        assertEquals(1L, reader.getDiag().getCompletedCount(), "exactly one completion — no pending leak");
    }

    @Test
    void readFutureTimeoutSurfacesAsCatchableErrorThenNotFound() throws Exception {
        // A future whose timed get() expires immediately models a region read exceeding
        // DISK_READ_TIMEOUT_SECONDS without making the test wait it out.
        reader.setReadOverride((cx, cz) -> new CompletableFuture<Optional<CompoundTag>>() {
            @Override
            public Optional<CompoundTag> get(long timeout, TimeUnit unit) throws TimeoutException {
                throw new TimeoutException("simulated DISK_READ_TIMEOUT_SECONDS expiry");
            }
        });
        reader.submitReadDirect(uuid, "minecraft:overworld", level, 7, 7, 4L);

        var result = awaitResult();
        assertTrue(result.notFound(), "a timed-out read frees the reader thread with a notFound-shaped result");
        assertEquals(1L, reader.getDiag().getErrorCount());
        assertEquals(1L, reader.getDiag().getCompletedCount());
    }

    @Test
    void poolSaturationBouncesTheSubmitSynchronouslyAsSaturated() throws Exception {
        // 1 thread -> queue capacity 32: one blocked running task + 32 queued fill the pool.
        var release = new CountDownLatch(1);
        reader.setReadOverride((cx, cz) -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(Optional.empty());
        });
        for (int i = 0; i < 33; i++) {
            reader.submitReadDirect(uuid, "minecraft:overworld", level, i, 0, i);
        }
        assertNull(reader.getPlayerQueue(uuid).peek(), "all 33 accepted reads are still in flight");

        reader.submitReadDirect(uuid, "minecraft:overworld", level, 99, 0, 99L);
        var bounced = reader.getPlayerQueue(uuid).poll();
        assertTrue(bounced != null && bounced.saturated(),
                "the 34th submit is bounced synchronously as saturated (client retries later)");
        assertEquals(99, bounced.chunkX());
        assertEquals(1L, reader.getDiag().getSaturationCount());

        release.countDown();
        for (int i = 0; i < 33; i++) {
            assertTrue(awaitResult().notFound(), "released reads drain normally after the bounce");
        }
        assertEquals(34L, reader.getDiag().getSubmittedCount());
        assertEquals(34L, reader.getDiag().getCompletedCount(),
                "completion partition stays exact across the saturation bounce");
    }
}
