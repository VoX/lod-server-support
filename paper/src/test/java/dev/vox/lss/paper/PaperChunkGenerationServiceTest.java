package dev.vox.lss.paper;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.TickSnapshot;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import ca.spottedleaf.concurrentutil.util.Priority;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Books-balance coverage for {@link PaperChunkGenerationService}. Paper's accounting is
 * async-callback based (unlike Fabric's ticket polling), so Fabric tests cannot cover it:
 * a leaked perPlayerActiveCount entry starves that player's generation until disconnect.
 * The launchAsyncLoad seam captures the launch instead of touching Bukkit's scheduler;
 * tests then fire completeAsyncLoad exactly as the Moonrise consumer would (the
 * extraction runs on that completion thread — the owning region thread on Folia — and only
 * the immutable result hops to the pump's onChunkReady), or drive onChunkReady directly for
 * pump-side books. Every outcome path (success, null chunk, extraction Throwable, timeout,
 * removePlayer) must drain the active map and free the per-player slots.
 */
// new LevelChunkSection(PalettedContainerFactory) is @Deprecated on Paper (anti-xray overload),
// but is the canonical vanilla ctor; same suppression as NbtSectionSerializerTest.
@SuppressWarnings("deprecation")
class PaperChunkGenerationServiceTest {

    private static PalettedContainerFactory FACTORY;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Same biome-registry construction as NbtSectionSerializerTest (success path needs
        // a real LevelChunkSection for PaperSectionSerializer to serialize).
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        src.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        FACTORY = PalettedContainerFactory.create(
                new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)));
    }

    // ---- harness ----

    record CapturedLaunch(PaperChunkGenerationService.PendingGenerationKey key,
                          ServerLevel level, int cx, int cz, long token, Priority priority) {}

    static class CapturingGenService extends PaperChunkGenerationService {
        final List<CapturedLaunch> launches = new ArrayList<>();

        CapturingGenService(PaperConfig config) {
            super(config, null); // plugin only used by the real launchAsyncLoad, which is overridden
            // completeAsyncLoad hops through the scheduler seam; run the hop inline so tests
            // observe the pump-side books immediately.
            setMainThreadScheduler(Runnable::run);
        }

        @Override
        void launchAsyncLoad(PendingGenerationKey key, ServerLevel level, int cx, int cz, long token,
                             Priority priority) {
            launches.add(new CapturedLaunch(key, level, cx, cz, token, priority));
        }
    }

    private static PaperConfig config(int globalLimit, int perPlayerLimit, int timeoutSeconds) {
        var c = new PaperConfig();
        c.generationConcurrencyLimitGlobal = globalLimit;
        c.generationConcurrencyLimitPerPlayer = perPlayerLimit;
        c.generationTimeoutSeconds = timeoutSeconds;
        c.validate();
        return c;
    }

    private static ServerLevel overworldLevel() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        return level;
    }

    /** NMS ChunkAccess stand-in — completeAsyncLoad only null-checks it, never calls methods. */
    private static ChunkAccess nmsChunk() {
        return mock(ChunkAccess.class);
    }

    /** Pre-serialized column stand-in for pump-side books tests — onChunkReady never
     *  introspects it, only null-checks. */
    private static LoadedColumnData columnData() {
        return new LoadedColumnData(0, 0, new byte[0], 0);
    }

    private static String diag(long submitted, long completed, int active, long timeouts, long removed) {
        // null_failures is 0 in every case here: the failure tests fire onChunkReady(null)
        // directly, which books removed-in-flight — the null_failures counter is incremented
        // only by completeAsyncLoad/extractColumnData's chunk-vanished paths, not exercised here.
        return String.format("submitted=%d, completed=%d, active=%d, timeouts=%d, removed=%d, null_failures=0",
                submitted, completed, active, timeouts, removed);
    }

    // ---- piggyback + launch accounting ----

    @Test
    void secondRequestForSameColumnPiggybacksOnSingleLaunch() {
        var svc = new CapturingGenService(config(8, 8, 60));
        var level = overworldLevel();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 3, -2, 11L));
        assertTrue(svc.submitGeneration(b, level, 3, -2, 22L));
        assertEquals(1, svc.launches.size(), "one async load serves both players");
        assertEquals(3, svc.launches.get(0).cx());
        assertEquals(-2, svc.launches.get(0).cz());
        assertEquals(diag(1, 0, 1, 0, 0), svc.getDiagnostics());

        // Failed load fans a failure outcome out to BOTH callbacks
        svc.onChunkReady(svc.launches.get(0).key(), null, 3, -2, svc.launches.get(0).token());
        var ready = svc.tick();
        assertEquals(2, ready.size());
        for (var r : ready) {
            assertNull(r.columnData(), "failed load reports ColumnNotGenerated");
            assertEquals(3, r.cx());
            assertEquals(-2, r.cz());
            assertEquals("minecraft:overworld", r.dimension());
        }
        assertEquals(11L, byPlayer(ready, a).submissionOrder());
        assertEquals(22L, byPlayer(ready, b).submissionOrder());
        assertEquals(diag(1, 0, 0, 0, 1), svc.getDiagnostics(),
                "one failed launch counts once as removed-in-flight (not per callback)");
        assertTrue(svc.tick().isEmpty(), "outcomes are drained exactly once");
    }

    private static TickSnapshot.GenerationReadyData byPlayer(
            List<TickSnapshot.GenerationReadyData> ready, UUID uuid) {
        return ready.stream().filter(r -> r.playerUuid().equals(uuid)).findFirst().orElseThrow();
    }

    // ---- caps ----

    @Test
    void perPlayerCapBouncesOnlyThatPlayer() {
        var svc = new CapturingGenService(config(32, 2, 60));
        var level = overworldLevel();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        assertTrue(svc.submitGeneration(a, level, 1, 0, 2L));
        assertFalse(svc.submitGeneration(a, level, 2, 0, 3L), "third launch exceeds per-player cap");
        assertTrue(svc.submitGeneration(b, level, 2, 0, 4L), "other players are unaffected");
        assertEquals(3, svc.launches.size());
    }

    @Test
    void globalCapBouncesNewLaunches() {
        var svc = new CapturingGenService(config(2, 16, 60));
        var level = overworldLevel();

        assertTrue(svc.submitGeneration(UUID.randomUUID(), level, 0, 0, 1L));
        assertTrue(svc.submitGeneration(UUID.randomUUID(), level, 1, 0, 2L));
        assertFalse(svc.submitGeneration(UUID.randomUUID(), level, 2, 0, 3L));
        assertEquals(2, svc.launches.size());
        assertEquals(diag(2, 0, 2, 0, 0), svc.getDiagnostics());
    }

    @Test
    void completionFreesThePerPlayerSlot() {
        var svc = new CapturingGenService(config(32, 1, 60));
        var level = overworldLevel();
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        assertFalse(svc.submitGeneration(a, level, 1, 0, 2L), "cap 1: second column bounces while first is active");

        svc.onChunkReady(svc.launches.get(0).key(), null, 0, 0, svc.launches.get(0).token());
        assertEquals(1, svc.tick().size());
        assertTrue(svc.submitGeneration(a, level, 1, 0, 3L), "slot freed by the resolved load");
    }

    // ---- removePlayer pruning + late callbacks ----

    @Test
    void removePlayerPrunesActiveAndLateCallbackIsNoOp() {
        var svc = new CapturingGenService(config(32, 1, 60));
        var level = overworldLevel();
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        svc.removePlayer(a);
        assertEquals(diag(1, 0, 0, 0, 1), svc.getDiagnostics());

        // Async load completes after the player left: must not emit, must not count completed
        svc.onChunkReady(svc.launches.get(0).key(), columnData(), 0, 0, svc.launches.get(0).token());
        assertTrue(svc.tick().isEmpty());
        assertEquals(diag(1, 0, 0, 0, 1), svc.getDiagnostics());

        assertTrue(svc.submitGeneration(a, level, 5, 5, 2L), "rejoining player starts with freed slots");
    }

    @Test
    void removePlayerKeepsSharedLaunchAliveForOtherPlayer() {
        var svc = new CapturingGenService(config(32, 4, 60));
        var level = overworldLevel();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        assertTrue(svc.submitGeneration(b, level, 0, 0, 2L));
        svc.removePlayer(a);
        assertEquals(diag(1, 0, 1, 0, 0), svc.getDiagnostics(),
                "launch survives (b still waits); removedInFlight only counts terminal removals"
                        + " (fully-orphaned or failed launches), not a launch with a surviving waiter");

        svc.onChunkReady(svc.launches.get(0).key(), null, 0, 0, svc.launches.get(0).token());
        var ready = svc.tick();
        assertEquals(1, ready.size(), "only the remaining player gets an outcome");
        assertEquals(b, ready.get(0).playerUuid());
        assertEquals(2L, ready.get(0).submissionOrder());
    }

    // ---- timeout ----

    @Test
    void timeoutEmitsFailureAndFreesSlots() {
        var svc = new CapturingGenService(config(32, 1, 1)); // 1s -> 20 ticks
        var level = overworldLevel();
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        for (int tick = 1; tick <= 20; tick++) {
            assertTrue(svc.tick().isEmpty(), "no timeout before tick 21 (tick " + tick + ")");
        }
        assertFalse(svc.submitGeneration(a, level, 1, 1, 2L), "slot still held right up to the timeout");

        var ready = svc.tick(); // tick 21: ticksWaiting exceeds timeoutTicks
        assertEquals(1, ready.size());
        assertNull(ready.get(0).columnData());
        assertEquals(a, ready.get(0).playerUuid());
        assertEquals(diag(1, 0, 0, 1, 0), svc.getDiagnostics());
        assertTrue(svc.submitGeneration(a, level, 1, 1, 3L), "timeout frees the per-player slot");

        // The real async load finishing after the timeout must be a no-op for the expired key
        svc.onChunkReady(svc.launches.get(0).key(), columnData(), 0, 0, svc.launches.get(0).token());
        assertTrue(svc.tick().isEmpty());
    }

    // ---- completion outcome paths ----

    @Test
    void chunkVanishedInTheCompletionWindowIsTRANSIENT() {
        var svc = new CapturingGenService(config(32, 1, 60));
        var level = overworldLevel();
        // getChunkNow returns null (mock default): "was null after async load completed" branch
        when(level.getChunkSource()).thenReturn(mock(ServerChunkCache.class));
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 4, 4, 1L));
        svc.completeAsyncLoad(svc.launches.get(0).key(), level, nmsChunk(), 4, 4, svc.launches.get(0).token());

        var ready = svc.tick();
        assertEquals(1, ready.size());
        assertNull(ready.get(0).columnData());
        // The chunk GENERATED fine and merely unloaded before the re-fetch — a transient
        // outcome, so it must drop silently and be healed by the next want-set declaration.
        // Answering the session-permanent NOT_GENERATED here blanks the column until
        // reconnect: on Paper walk-in generation does not mark dirty by default, so the
        // dirty-broadcast revival never fires. (Harmless pre-v17, when NOT_GENERATED
        // re-opened the position client-side; v17's permanence inverted that contract.)
        assertTrue(ready.get(0).transientFailure(),
                "a chunk that unloaded in the completion window must never answer NOT_GENERATED");
        // This test uniquely drives the extractColumnData chunk-vanished branch, so it is the
        // one place null_failures is nonzero (the generic diag() helper asserts null_failures=0).
        assertEquals("submitted=1, completed=0, active=0, timeouts=0, removed=1, null_failures=1",
                svc.getDiagnostics(),
                "not completed — counted as removed-in-flight (books balance) and null_failures=1");
        assertEquals(1L, svc.getNullChunkFailures(), "the vanished-chunk failure is counted");
        assertTrue(svc.submitGeneration(a, level, 5, 4, 2L));
    }

    @Test
    void extractionExceptionEmitsFailureAndFreesSlot() {
        var svc = new CapturingGenService(config(32, 1, 60));
        var level = overworldLevel();
        when(level.getChunkSource()).thenThrow(new IllegalStateException("boom"));
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        svc.completeAsyncLoad(svc.launches.get(0).key(), level, nmsChunk(), 0, 0, svc.launches.get(0).token()); // must not throw

        var ready = svc.tick();
        assertEquals(1, ready.size());
        assertNull(ready.get(0).columnData());
        assertFalse(ready.get(0).transientFailure(),
                "an extraction exception stays PERMANENT — a corrupt chunk must not be hammered");
        assertEquals(diag(1, 0, 0, 0, 1), svc.getDiagnostics(),
                "extraction failure counts as removed-in-flight, never as completed");
        assertTrue(svc.submitGeneration(a, level, 1, 0, 2L), "slot freed despite the exception");
    }

    @Test
    void extractionErrorStillEmitsFailureBeforeSurfacing() {
        var svc = new CapturingGenService(config(32, 1, 60));
        var level = overworldLevel();
        when(level.getChunkSource()).thenThrow(new LinkageError("boom"));
        UUID a = UUID.randomUUID();

        // A rethrow would vanish into Moonrise's completion plumbing, so completeAsyncLoad
        // hands Errors to the thread's uncaught handler instead — capture it.
        var surfaced = new ArrayList<Throwable>();
        var thread = Thread.currentThread();
        var previous = thread.getUncaughtExceptionHandler();
        thread.setUncaughtExceptionHandler((t, e) -> surfaced.add(e));
        try {
            assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
            svc.completeAsyncLoad(svc.launches.get(0).key(), level, nmsChunk(), 0, 0,
                    svc.launches.get(0).token());
        } finally {
            thread.setUncaughtExceptionHandler(previous);
        }
        assertEquals(1, surfaced.size(), "the Error surfaces via the uncaught handler");
        assertInstanceOf(LinkageError.class, surfaced.get(0), "Errors stay loud (not swallowed)");

        // ...and the books were balanced first: without the containment the slot would leak
        var ready = svc.tick();
        assertEquals(1, ready.size());
        assertNull(ready.get(0).columnData());
        assertEquals(1L, svc.getTotalRemovedInFlight(),
                "the removal was counted before the Error surfaced");
        assertTrue(svc.submitGeneration(a, level, 1, 0, 2L));
    }

    @Test
    void successFansOutSharedColumnDataToAllCallbacks() {
        var svc = new CapturingGenService(config(32, 1, 60));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        // Real section so PaperSectionSerializer produces actual bytes (mock light engine
        // reports no light data; minSectionY defaults to 0 on the mock).
        var section = new LevelChunkSection(FACTORY);
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        var nmsChunk = mock(LevelChunk.class);
        when(nmsChunk.getSections()).thenReturn(new LevelChunkSection[]{section});

        var lightEngine = mock(LevelLightEngine.class);
        when(lightEngine.getLayerListener(any())).thenReturn(mock(LayerLightEventListener.class));

        var chunkSource = mock(ServerChunkCache.class);
        when(chunkSource.getChunkNow(6, -9)).thenReturn(nmsChunk);

        var level = overworldLevel();
        when(level.getChunkSource()).thenReturn(chunkSource);
        when(level.getLightEngine()).thenReturn(lightEngine);

        assertTrue(svc.submitGeneration(a, level, 6, -9, 100L));
        assertTrue(svc.submitGeneration(b, level, 6, -9, 200L));
        svc.completeAsyncLoad(svc.launches.get(0).key(), level, nmsChunk(), 6, -9, svc.launches.get(0).token());

        var ready = svc.tick();
        assertEquals(2, ready.size());
        var forA = byPlayer(ready, a);
        var forB = byPlayer(ready, b);
        assertNotNull(forA.columnData());
        assertNotNull(forA.columnData().serializedSections(), "stone section serialized to bytes");
        assertSame(forA.columnData(), forB.columnData(), "one serialization shared by all callbacks");
        assertEquals(100L, forA.submissionOrder());
        assertEquals(200L, forB.submissionOrder());
        assertTrue(forA.columnTimestamp() > 0, "completed columns carry a real timestamp");
        assertEquals("minecraft:overworld", forA.dimension());
        assertEquals(diag(1, 1, 0, 0, 0), svc.getDiagnostics());

        // Per-player slots freed for both piggybacked players (cap is 1)
        assertTrue(svc.submitGeneration(a, level, 7, -9, 300L));
        assertTrue(svc.submitGeneration(b, level, 8, -9, 400L));
    }

    // ---- counter getters (soak exporter contract) ----

    /**
     * The soak harness (PaperSoakMetricsExporter) reads these getters — not the diag
     * string — and check_soak.py enforces A4 (submitted == completed + timeouts +
     * removed_in_flight) on them. Each counter must move at exactly its own lifecycle
     * transition, and the books must re-balance once every launch is accounted for.
     */
    @Test
    void counterGettersTrackTheLifecycleTheSoakExporterReads() {
        var svc = new CapturingGenService(config(32, 4, 1)); // 1s timeout -> 20 ticks
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        // Success-path wiring (same as successFansOutSharedColumnDataToAllCallbacks)
        var section = new LevelChunkSection(FACTORY);
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        var nmsChunk = mock(LevelChunk.class);
        when(nmsChunk.getSections()).thenReturn(new LevelChunkSection[]{section});
        var lightEngine = mock(LevelLightEngine.class);
        when(lightEngine.getLayerListener(any())).thenReturn(mock(LayerLightEventListener.class));
        var chunkSource = mock(ServerChunkCache.class);
        when(chunkSource.getChunkNow(0, 0)).thenReturn(nmsChunk);
        var level = overworldLevel();
        when(level.getChunkSource()).thenReturn(chunkSource);
        when(level.getLightEngine()).thenReturn(lightEngine);

        assertEquals(0L, svc.getTotalSubmitted());
        assertEquals(0, svc.getActiveCount());

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        assertTrue(svc.submitGeneration(a, level, 1, 0, 2L));
        assertTrue(svc.submitGeneration(b, level, 2, 0, 3L));
        assertEquals(3L, svc.getTotalSubmitted());
        assertEquals(3, svc.getActiveCount());
        assertEquals(0L, svc.getTotalCompleted());

        // Piggyback on an active launch must NOT count as a new submission
        assertTrue(svc.submitGeneration(b, level, 0, 0, 4L));
        assertEquals(3L, svc.getTotalSubmitted());
        assertEquals(3, svc.getActiveCount());

        // (0,0) completes successfully -> completed only
        svc.completeAsyncLoad(svc.launches.get(0).key(), level, nmsChunk(), 0, 0, svc.launches.get(0).token());
        assertEquals(2, svc.tick().size(), "both piggybacked callbacks drain");
        assertEquals(1L, svc.getTotalCompleted());
        assertEquals(2, svc.getActiveCount());
        assertEquals(0L, svc.getTotalTimeouts());
        assertEquals(0L, svc.getTotalRemovedInFlight());

        // b leaves -> its now-orphaned launch (2,0) counts as removed-in-flight only
        svc.removePlayer(b);
        assertEquals(1L, svc.getTotalRemovedInFlight());
        assertEquals(1, svc.getActiveCount());
        assertEquals(1L, svc.getTotalCompleted());

        // (1,0) never completes -> timeout only
        for (int tick = 1; tick <= 21; tick++) svc.tick();
        assertEquals(1L, svc.getTotalTimeouts());
        assertEquals(0, svc.getActiveCount());

        // A failed async load (null chunk) -> removed_in_flight only: the terminal counter
        // for removals that neither completed nor timed out, so A4 still balances.
        assertTrue(svc.submitGeneration(a, level, 5, 5, 5L));
        svc.onChunkReady(svc.launches.get(3).key(), null, 5, 5, svc.launches.get(3).token());
        assertEquals(1, svc.tick().size());
        assertEquals(2L, svc.getTotalRemovedInFlight());
        assertEquals(1L, svc.getTotalCompleted(), "failure must not count as completed");
        assertEquals(0, svc.getActiveCount());

        // A4 books balance: submitted == completed + timeouts + removed_in_flight
        assertEquals(svc.getTotalSubmitted(),
                svc.getTotalCompleted() + svc.getTotalTimeouts() + svc.getTotalRemovedInFlight());
    }

    // ---- at-cap piggyback (PP-031) ----

    @Test
    void atCapPlayerStillPiggybacksOnAnActiveLaunch() {
        var svc = new CapturingGenService(config(32, 1, 60)); // per-player cap 1
        var level = overworldLevel();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        assertTrue(svc.submitGeneration(b, level, 1, 0, 2L));
        assertFalse(svc.submitGeneration(b, level, 2, 0, 3L), "b is at cap for fresh launches");
        assertTrue(svc.submitGeneration(b, level, 0, 0, 4L),
                "the existingActive branch runs BEFORE the cap checks: an at-cap player still "
                        + "piggybacks (the column is being generated anyway — bouncing would only "
                        + "delay the answer)");
        assertEquals(2, svc.launches.size(), "the piggyback launched nothing new");
    }

    @Test
    void piggybackCountsAgainstThePerPlayerCapUntilTheLaunchCompletes() {
        var svc = new CapturingGenService(config(32, 2, 60)); // per-player cap 2
        var level = overworldLevel();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L)); // a: 1
        assertTrue(svc.submitGeneration(b, level, 1, 0, 2L)); // b: 1
        assertTrue(svc.submitGeneration(a, level, 1, 0, 3L)); // a piggybacks b's launch -> a: 2
        assertFalse(svc.submitGeneration(a, level, 2, 0, 4L),
                "the piggyback consumed a's second slot — fresh launches reject at cap");

        svc.onChunkReady(svc.launches.get(1).key(), null, 1, 0, svc.launches.get(1).token());
        assertEquals(2, svc.tick().size(), "completion fans out to b and the piggybacked a");
        assertTrue(svc.submitGeneration(a, level, 2, 0, 5L), "completion freed the piggybacked slot");
    }

    // ---- #9 fix: timeout/resubmit stale-future race — the generation token rejects a stale completion ----

    /** Success-path wiring so a resubmitted entry's OWN completion serves real column data. */
    private static ServerLevel successWiredLevel() {
        var section = new LevelChunkSection(FACTORY);
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        var nmsChunk = mock(LevelChunk.class);
        when(nmsChunk.getSections()).thenReturn(new LevelChunkSection[]{section});
        var lightEngine = mock(LevelLightEngine.class);
        when(lightEngine.getLayerListener(any())).thenReturn(mock(LayerLightEventListener.class));
        var chunkSource = mock(ServerChunkCache.class);
        when(chunkSource.getChunkNow(0, 0)).thenReturn(nmsChunk);
        var level = overworldLevel();
        when(level.getChunkSource()).thenReturn(chunkSource);
        when(level.getLightEngine()).thenReturn(lightEngine);
        return level;
    }

    @Test
    void staleFailedCompletionIsRejectedAndTheResubmittedEntryIsServedByItsOwnLoad() {
        var svc = new CapturingGenService(config(32, 1, 1)); // cap 1, 1s timeout -> 20 ticks
        var level = successWiredLevel();
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L)); // entry A
        for (int t = 0; t < 21; t++) svc.tick();              // A times out; its failure drains
        assertEquals(1L, svc.getTotalTimeouts());
        assertEquals(0, svc.getActiveCount());

        assertTrue(svc.submitGeneration(a, level, 0, 0, 2L)); // entry B: SAME key value, NEW token
        assertEquals(2, svc.launches.size());

        // #9 fix: A's stale (failed) completion carries A's token, so it is REJECTED — it does
        // not consume entry B by key match. Without the token this answered B not-generated.
        svc.onChunkReady(svc.launches.get(0).key(), null, 0, 0, svc.launches.get(0).token());
        assertTrue(svc.tick().isEmpty(), "the stale completion is rejected — nothing served from it");
        assertEquals(1, svc.getActiveCount(), "entry B survives the stale completion");

        // B is served by ITS OWN completion with real data — the spurious not_generated is gone.
        svc.completeAsyncLoad(svc.launches.get(1).key(), level, nmsChunk(), 0, 0, svc.launches.get(1).token());
        var ready = svc.tick();
        assertEquals(1, ready.size());
        assertNotNull(ready.get(0).columnData(), "B's waiter gets its real column, not a stale failure");
        assertEquals(2L, ready.get(0).submissionOrder());

        assertEquals(0, svc.getActiveCount());
        assertEquals(svc.getTotalSubmitted(),
                svc.getTotalCompleted() + svc.getTotalTimeouts() + svc.getTotalRemovedInFlight(),
                "A4 books balance (2 == 1 completed + 1 timeout + 0 removed)");
        assertTrue(svc.submitGeneration(a, level, 9, 9, 3L),
                "per-player counts intact: cap 1 admits a fresh launch (a leaked count would bounce it)");
    }

    @Test
    void staleSuccessfulCompletionIsAlsoRejectedNotAdoptedByTheResubmittedEntry() {
        var svc = new CapturingGenService(config(32, 1, 1));
        var level = successWiredLevel();
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L)); // entry A
        for (int t = 0; t < 21; t++) svc.tick();              // A times out
        assertTrue(svc.submitGeneration(a, level, 0, 0, 2L)); // entry B

        // The token is identity-strict: even a stale SUCCESS is rejected, never cross-applied to B.
        svc.completeAsyncLoad(svc.launches.get(0).key(), level, nmsChunk(), 0, 0, svc.launches.get(0).token());
        assertTrue(svc.tick().isEmpty(), "a stale success is rejected — B is not served from another launch");
        assertEquals(1, svc.getActiveCount(), "entry B survives");

        // B is served by its own completion.
        svc.completeAsyncLoad(svc.launches.get(1).key(), level, nmsChunk(), 0, 0, svc.launches.get(1).token());
        var ready = svc.tick();
        assertEquals(1, ready.size());
        assertNotNull(ready.get(0).columnData());
        assertEquals(2L, ready.get(0).submissionOrder());
        assertEquals(svc.getTotalSubmitted(),
                svc.getTotalCompleted() + svc.getTotalTimeouts() + svc.getTotalRemovedInFlight(),
                "A4 books balance (2 == 1 + 1 + 0)");
    }

    // ---- Moonrise launch: priority pin + completion routing (PP-030 successors) ----

    @Test
    void submitGenerationLaunchesAtMoonrisePriorityLow() {
        // The load-bearing half of "server owns limiting via real priority": LOD generation
        // must defer to player-driven NORMAL generation. Anything but LOW here silently
        // reverts the deference and competes with gameplay worldgen.
        var svc = new CapturingGenService(config(32, 4, 60));
        var level = overworldLevel();

        assertTrue(svc.submitGeneration(UUID.randomUUID(), level, 7, -3, 1L));

        assertEquals(1, svc.launches.size());
        assertSame(Priority.LOW, svc.launches.get(0).priority(),
                "LOD generation must launch at Moonrise Priority.LOW");
    }

    @Test
    void schedulerRejectionAfterAsyncCompletionNeverRunsTheCallbackInline() {
        // The completion (the Moonrise consumer) fires while the plugin is shutting down:
        // schedule() throws. The rejection must be contained inside completeAsyncLoad — the
        // active map is pump-thread-only, so onChunkReady must NOT run inline off-thread.
        var svc = new CapturingGenService(config(32, 4, 60));
        var level = overworldLevel();
        when(level.getChunkSource()).thenReturn(mock(ServerChunkCache.class));
        svc.setMainThreadScheduler(task -> { throw new RejectedExecutionException("plugin disabled"); });

        UUID a = UUID.randomUUID();
        assertTrue(svc.submitGeneration(a, level, 7, -3, 1L));
        assertEquals(1, svc.getActiveCount());

        assertDoesNotThrow(() -> svc.completeAsyncLoad(svc.launches.get(0).key(), level,
                        nmsChunk(), 7, -3, svc.launches.get(0).token()),
                "the rejection must be contained inside the completion callback");
        assertEquals(1, svc.getActiveCount(),
                "onChunkReady must NOT run inline on the async thread (active map is pump-thread-only)");
        assertTrue(svc.tick().isEmpty(), "no outcome was fabricated off-thread");

        svc.shutdown(); // shutdown() owns the cleanup the skipped callback would have raced
        assertEquals(0, svc.getActiveCount());
    }

    @Test
    void completionRoutesThroughTheMainThreadSchedulerExactlyOnce() {
        var svc = new CapturingGenService(config(32, 4, 60));
        var level = overworldLevel();
        var scheduled = new ArrayList<Runnable>();
        svc.setMainThreadScheduler(scheduled::add);

        UUID a = UUID.randomUUID();
        assertTrue(svc.submitGeneration(a, level, 2, 2, 5L));
        assertTrue(scheduled.isEmpty(), "nothing is scheduled until the async load completes");

        // Moonrise surfaces a failed load as a NULL chunk — no throwable channel exists.
        svc.completeAsyncLoad(svc.launches.get(0).key(), level, null, 2, 2,
                svc.launches.get(0).token());
        assertEquals(1, scheduled.size(), "completion hands exactly one task to the pump");
        assertEquals(1, svc.getActiveCount(), "the entry is consumed by the TASK, not the completion");

        scheduled.get(0).run(); // pump tick runs the callback
        var ready = svc.tick();
        assertEquals(1, ready.size());
        assertNull(ready.get(0).columnData(), "a null-chunk completion is a failure outcome");
        assertEquals(a, ready.get(0).playerUuid());
        assertEquals(0, svc.getActiveCount());
        assertEquals(1L, svc.getTotalRemovedInFlight());
    }

    // ---- shutdown vs in-flight callbacks (PP-038 unit leg) ----

    @Test
    void shutdownClearsActiveAndLateAsyncCallbacksAreInert() {
        var svc = new CapturingGenService(config(32, 4, 60));
        var level = overworldLevel();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        assertTrue(svc.submitGeneration(b, level, 1, 0, 2L));

        svc.shutdown();
        assertEquals(0, svc.getActiveCount());

        // The in-flight async callbacks fire after onDisable: both shapes must be no-ops.
        assertDoesNotThrow(() -> svc.onChunkReady(svc.launches.get(0).key(), columnData(), 0, 0, svc.launches.get(0).token()));
        assertDoesNotThrow(() -> svc.onChunkReady(svc.launches.get(1).key(), null, 1, 0, svc.launches.get(1).token()));
        assertTrue(svc.tick().isEmpty(), "late callbacks emit nothing into a cleared service");
        assertEquals(0L, svc.getTotalCompleted());
    }
}
