package dev.vox.lss.paper;

import dev.vox.lss.common.processing.RequestRegistration;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.common.processing.ChunkReadResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
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
    private static final java.util.Map<UUID, RequestRegistration> TEST_REGISTRATIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static RequestRegistration registration(UUID uuid) {
        return TEST_REGISTRATIONS.computeIfAbsent(uuid, ignored -> new RequestRegistration());
    }


    private static RegistryAccess REGISTRY_ACCESS;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, src.getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        biomes.freeze();
        REGISTRY_ACCESS = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }

    private PaperChunkDiskReader reader;
    private ServerLevel level;
    private UUID uuid;

    @BeforeEach
    void buildRig() {
        reader = new PaperChunkDiskReader(1, false);
        uuid = UUID.randomUUID();
        reader.registerPlayer(uuid, registration(uuid));
        level = mock(ServerLevel.class);
        when(level.registryAccess()).thenReturn(REGISTRY_ACCESS);
        // Explicit world section range (R2-5 amendment 5): the submit path passes
        // level.getMinSectionY()/getMaxSectionY() into the range-gated serialize, and the
        // Mockito default [0,0] only kept these fixtures alive because their lone section
        // sits at Y=0 — a future fixture at any other Y would be silently range-dropped.
        when(level.getMinSectionY()).thenReturn(-4);
        when(level.getMaxSectionY()).thenReturn(19);
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

    /** FULL-status chunk whose one section is a single block this server does not know (a
     *  removed mod's block, or a format this build cannot read): nothing decodes. */
    private static CompoundTag undecodableChunkNbt() {
        var tag = new CompoundTag();
        tag.putString("Status", "minecraft:full");
        var section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        var blocks = new CompoundTag();
        var palette = new ListTag();
        var unknown = new CompoundTag();
        unknown.putString("Name", "lss:removed_mod_block");
        palette.add(unknown);
        blocks.put("palette", palette);
        section.put("block_states", blocks);
        var biomes = new CompoundTag();
        var biomePalette = new ListTag();
        biomePalette.add(net.minecraft.nbt.StringTag.valueOf("minecraft:plains"));
        biomes.put("palette", biomePalette);
        section.put("biomes", biomes);
        var sections = new ListTag();
        sections.add(section);
        tag.put("sections", sections);
        return tag;
    }

    private static final String CLEAN_DISK_LINE =
            "submitted=0, completed=0, not_found=0, all_air=0, errors=0, saturated=0,"
                    + " header_hits=0, avg_read=" + String.format("%.1f", 0.0) + "ms, pending=0,"
                    + " read_gate=0/1, gate_parked=0, gate_stops=0, gated=0";

    @Test
    void undecodableChunkResolvesAsUnservableThroughTheRealSubmitPath() throws Exception {
        // The Paper glue end to end: the REAL serializer reports the column through the
        // notes the shared reader handed to submitReadDirect's operation. Before the fix
        // this chunk resolved as ALL-AIR (found, stamped, stored, sent as a clear); a
        // plain not-found would be wrong too (it escalates to a generation ticket that
        // loads the chunk and lets vanilla turn the unknown block into air).
        var warns = new java.util.concurrent.CopyOnWriteArrayList<String>();
        reader.shutdown();
        reader = new PaperChunkDiskReader(1, false) {
            @Override
            protected void warnUndecodable(String message) {
                warns.add(message);
            }
        };
        reader.registerPlayer(uuid, registration(uuid));
        assertEquals(CLEAN_DISK_LINE, reader.getDiagnostics(),
                "a clean reader's disk line is byte-identical to the pre-counter line");

        reader.setReadOverride((cx, cz) ->
                CompletableFuture.completedFuture(Optional.of(undecodableChunkNbt())));
        reader.submitReadDirect(uuid, registration(uuid), "minecraft:overworld", level, 3, -4, 1L, 0L);

        var result = awaitResult();
        assertTrue(result.notFound(), "nothing can be served from an undecodable chunk");
        assertTrue(result.unservable(), "the unservable flavor: never memoized, never generated");
        assertFalse(result.authoritativeMiss(), "not the memo-seeding, generation-escalating miss");
        assertFalse(result.saturated());
        assertNull(result.sectionBytes(), "never an air answer");
        assertEquals(3, result.chunkX());
        assertEquals(-4, result.chunkZ());
        assertEquals(1L, reader.getDiag().getUndecodableCount());
        assertEquals(1L, reader.getDiag().getNotFoundCount(), "also counted under not_found");
        assertEquals(0L, reader.getDiag().getAllAirCount(), "never booked as an all-air chunk");
        assertEquals(0L, reader.getDiag().getErrorCount());
        assertEquals(1L, reader.getDiag().getCompletedCount());
        assertTrue(reader.getDiagnostics().endsWith(", gated=0, undecodable=1"),
                "the disk line carries the token: " + reader.getDiagnostics());

        reader.submitReadDirect(uuid, registration(uuid), "minecraft:overworld", level, 5, 6, 2L, 0L);
        assertTrue(awaitResult().unservable());
        assertEquals(2L, reader.getDiag().getUndecodableCount());
        assertEquals(1, warns.size(), "one WARN per reader, however many chunks fail: " + warns);
        assertTrue(warns.get(0).contains("[3, -4]") && warns.get(0).contains("minecraft:overworld"),
                "the WARN names the first chunk and its dimension: " + warns.get(0));
        assertTrue(warns.get(0).contains("lss:removed_mod_block"),
                "and carries the serializer's first decode message: " + warns.get(0));
    }

    /** FULL-status chunk with one stone section and no light arrays: what a world upgrade
     *  leaves on disk. */
    private static CompoundTag chunkSavedWithoutLight() {
        var tag = new CompoundTag();
        tag.putString("Status", "minecraft:full");
        var section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        var blocks = new CompoundTag();
        var palette = new ListTag();
        var stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        blocks.put("palette", palette);
        section.put("block_states", blocks);
        var sections = new ListTag();
        sections.add(section);
        tag.put("sections", sections);
        return tag;
    }

    private void dimensionHasSkyLight(boolean hasSkyLight) {
        var type = mock(net.minecraft.world.level.dimension.DimensionType.class);
        when(type.hasSkyLight()).thenReturn(hasSkyLight);
        when(level.dimensionType()).thenReturn(type);
    }

    @Test
    void columnSavedWithoutSkyLightIsCountedOnlyWhereTheDimensionTypeHasSkyLight() throws Exception {
        // The Paper glue for the lightless counter: the REAL serializer marks the notes,
        // and submitReadDirect captures the sky-light fact from the level's dimension
        // type (never from the dimension's name). The served bytes are the same either way.
        var infos = new java.util.concurrent.CopyOnWriteArrayList<String>();
        reader.shutdown();
        reader = new PaperChunkDiskReader(1, false) {
            @Override
            protected void infoLightless(String message) {
                infos.add(message);
            }
        };
        reader.registerPlayer(uuid, registration(uuid));
        reader.setReadOverride((cx, cz) ->
                CompletableFuture.completedFuture(Optional.of(chunkSavedWithoutLight())));

        // Same dimension NAME throughout: only the dimension type's answer changes.
        dimensionHasSkyLight(false);
        reader.submitReadDirect(uuid, registration(uuid), "minecraft:the_end", level, 1, 1, 1L, 0L);
        var noSky = awaitResult();
        assertFalse(noSky.notFound(), "the column is served");
        assertEquals(0L, reader.getDiag().getLightlessCount(),
                "a dimension type without sky light never counts");
        assertFalse(reader.getDiagnostics().contains("lightless"),
                "and the disk line carries no token: " + reader.getDiagnostics());

        dimensionHasSkyLight(true);
        reader.submitReadDirect(uuid, registration(uuid), "minecraft:the_end", level, 3, -4, 2L, 0L);
        var sky = awaitResult();
        assertFalse(sky.notFound());
        assertFalse(sky.unservable());
        org.junit.jupiter.api.Assertions.assertArrayEquals(noSky.sectionBytes(), sky.sectionBytes(),
                "the bytes do not depend on the counter");
        assertEquals(1L, reader.getDiag().getLightlessCount());
        assertEquals(2L, reader.getDiag().getSuccessfulReadCount(), "an ordinary successful read");
        assertEquals(0L, reader.getDiag().getNotFoundCount());
        assertTrue(reader.getDiagnostics().endsWith(", gated=0, lightless=1"),
                "the disk line carries the token: " + reader.getDiagnostics());
        assertEquals(1, infos.size(), "one INFO: " + infos);
        assertTrue(infos.get(0).contains("[3, -4]") && infos.get(0).contains("minecraft:the_end"),
                "naming the chunk and the dimension: " + infos.get(0));
    }

    @Test
    void plainMissAndAllAirNeverRenderTheUndecodableToken() throws Exception {
        reader.setReadOverride((cx, cz) -> CompletableFuture.completedFuture(Optional.empty()));
        reader.submitReadDirect(uuid, registration(uuid), "minecraft:overworld", level, 3, -4, 1L, 0L);
        var miss = awaitResult();
        assertTrue(miss.notFound() && miss.authoritativeMiss() && !miss.unservable(),
                "a missing chunk stays the plain authoritative miss");

        reader.setReadOverride((cx, cz) ->
                CompletableFuture.completedFuture(Optional.of(fullButEmptyChunkNbt())));
        reader.submitReadDirect(uuid, registration(uuid), "minecraft:overworld", level, 0, 0, 2L, 0L);
        var air = awaitResult();
        assertFalse(air.notFound(), "real air is still found");
        assertFalse(air.unservable());

        assertEquals(0L, reader.getDiag().getUndecodableCount());
        assertFalse(reader.getDiagnostics().contains("undecodable"),
                "no token on a reader that met no undecodable chunk: " + reader.getDiagnostics());
    }

    @Test
    void missingChunkResolvesAsNotFound() throws Exception {
        reader.setReadOverride((cx, cz) -> CompletableFuture.completedFuture(Optional.empty()));
        reader.submitReadDirect(uuid, registration(uuid), "minecraft:overworld", level, 3, -4, 1L, 0L);

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
        reader.submitReadDirect(uuid, registration(uuid), "minecraft:the_end", level, 0, 0, 2L, 0L);

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
        reader.submitReadDirect(uuid, registration(uuid), "minecraft:overworld", level, 5, 5, 3L, 0L);

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
        reader.submitReadDirect(uuid, registration(uuid), "minecraft:overworld", level, 7, 7, 4L, 0L);

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
            reader.submitReadDirect(uuid, registration(uuid), "minecraft:overworld", level, i, 0, i, 0L);
        }
        assertNull(reader.getPlayerQueue(uuid).peek(), "all 33 accepted reads are still in flight");

        reader.submitReadDirect(uuid, registration(uuid), "minecraft:overworld", level, 99, 0, 99L, 0L);
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
    @Test
    void serializationReloadCapturesPolicyForQueuedReadsAndKeepsWireBytesEqual() throws Exception {
        var nbt = new CompoundTag();
        nbt.putString("Status", "minecraft:full");
        var section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        var blocks = new CompoundTag();
        var palette = new ListTag();
        var stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        blocks.put("palette", palette);
        section.put("block_states", blocks);
        var biomes = new CompoundTag();
        var biomePalette = new ListTag();
        biomePalette.add(net.minecraft.nbt.StringTag.valueOf("minecraft:plains"));
        biomes.put("palette", biomePalette);
        section.put("biomes", biomes);
        var sections = new ListTag();
        sections.add(section);
        nbt.put("sections", sections);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        reader.setReadOverride((cx, cz) -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("read was not released");
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            return CompletableFuture.completedFuture(Optional.of(nbt));
        });
        long before = PaperNbtSectionSerializer.DIRECT_V20_EMITS.get();
        try {
            reader.submitReadDirect(uuid, registration(uuid), "minecraft:overworld", level, 0, 0, 1, 0);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            reader.submitReadDirect(uuid, registration(uuid), "minecraft:overworld", level, 1, 0, 2, 0);
            reader.updateSerializationPolicy(false, false);
            reader.submitReadDirect(uuid, registration(uuid), "minecraft:overworld", level, 2, 0, 3, 0);
            release.countDown();
            var first = awaitResult();
            var queued = awaitResult();
            var changed = awaitResult();
            assertFalse(first.notFound());
            org.junit.jupiter.api.Assertions.assertNotNull(first.sectionBytes());
            org.junit.jupiter.api.Assertions.assertArrayEquals(first.sectionBytes(), queued.sectionBytes());
            org.junit.jupiter.api.Assertions.assertArrayEquals(first.sectionBytes(), changed.sectionBytes());
            assertEquals(before + 2, PaperNbtSectionSerializer.DIRECT_V20_EMITS.get(),
                    "running and queued submissions retain transcode; only the later read uses object decoding");
        } finally { release.countDown(); }
    }

}
