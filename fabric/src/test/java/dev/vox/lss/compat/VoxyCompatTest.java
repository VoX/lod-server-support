package dev.vox.lss.compat;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract suite for the reflective Voxy bridge, driven against the stub
 * {@code me.cortex.voxy} classes on the test classpath (same shapes VoxyCompat resolves at
 * runtime). Every rung of the failure ladder encodes a deliberate decision: null worldId
 * and rejected sections REPORT (so the column is re-served instead of becoming a permanent
 * hole — the a9bee8d silent-ignore fix), LinkageError DEAD-LATCHES (incompatible Voxy will
 * never succeed; reporting would re-serve and re-fail forever), non-AssertionError Errors
 * RETHROW to LSSApi.dispatchColumn's catch, and a wrong-shaped Voxy fails init closed.
 */
class VoxyCompatTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final ResourceKey<Level> DIM =
            ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:voxy"));

    private record Report(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}

    /** Error that is neither LinkageError nor AssertionError — the rethrow arm. */
    private static final class TestError extends Error {}

    private final List<Report> reports = new ArrayList<>();
    private final List<VoxelColumnConsumer> registered = new ArrayList<>();

    @BeforeEach
    void wireSeams() {
        WorldIdentifier.reset();
        VoxelIngestService.reset();
        VoxyConfig.reset();
        VoxyCommon.reset();
        VoxyCompat.reportSink = (dimension, chunkX, chunkZ) ->
                reports.add(new Report(dimension, chunkX, chunkZ));
        VoxyCompat.consumerRegistrar = registered::add;
    }

    @AfterEach
    void restoreSeams() {
        VoxyCompat.resetSeams();
        WorldIdentifier.reset();
        VoxelIngestService.reset();
        VoxyConfig.reset();
        VoxyCommon.reset();
    }

    /** Runs init() against the stubs and returns the bridge consumer it registered. */
    private VoxelColumnConsumer initBridge() {
        assertTrue(VoxyCompat.init(), "init must resolve handles against the stub Voxy classes");
        assertEquals(1, registered.size(), "init must register exactly one column consumer");
        return registered.get(0);
    }

    private static VoxelColumnData column(VoxelColumnData.SectionData... sections) {
        return new VoxelColumnData(sections, 99L);
    }

    /** Section with identity-distinct light layers so argument-order swaps are detectable. */
    private static VoxelColumnData.SectionData sectionData(int sectionY) {
        return new VoxelColumnData.SectionData(sectionY, null,
                new DataLayer(new byte[2048]), new DataLayer(new byte[2048]));
    }

    // ---- CL-072: init + report ladder ----

    @Test
    void initResolvesHandlesAndBridgeIngestsEverySection() {
        var consumer = initBridge();
        var worldId = WorldIdentifier.returned;
        var s1 = sectionData(-4);
        var s2 = sectionData(7);

        consumer.onVoxelColumnReceived(null, DIM, 12, -34, column(s1, s2));

        assertEquals(1, WorldIdentifier.ofCalls.get(), "one WorldIdentifier.of per column");
        assertEquals(2, VoxelIngestService.calls.size(), "one rawIngest per section");
        var c1 = VoxelIngestService.calls.get(0);
        assertSame(worldId, c1.worldId(), "resolved worldId must be passed through");
        assertEquals(12, c1.chunkX());
        assertEquals(-4, c1.sectionY());
        assertEquals(-34, c1.chunkZ());
        assertNull(c1.section());
        assertSame(s1.blockLight(), c1.blockLight(), "blockLight/skyLight order must not swap");
        assertSame(s1.skyLight(), c1.skyLight());
        var c2 = VoxelIngestService.calls.get(1);
        assertEquals(7, c2.sectionY());
        assertSame(s2.blockLight(), c2.blockLight());
        assertSame(s2.skyLight(), c2.skyLight());
        assertTrue(reports.isEmpty(), "a fully accepted column must not report");
    }

    @Test
    void nullSkyLightIsSubstitutedWithAllZeroLayerSoNoSkyDimensionsRenderDark() {
        // H-12 regression guard. LSS ships "absent light layer = null at the consumer"
        // (SectionLightDefaultsTest), so a no-sky dimension (nether/end, hasSkyLight()==false)
        // arrives with every section's sky layer null. Voxy renders a null sky layer as full
        // daylight (15), which made the topmost end/nether surfaces glow until vanilla loaded
        // the chunk. The bridge must hand Voxy an explicit all-zero (present, non-empty)
        // DataLayer so those surfaces render dark. Block light is left untouched.
        var consumer = initBridge();
        var section = new VoxelColumnData.SectionData(3, null, null, null);

        consumer.onVoxelColumnReceived(null, DIM, 0, 0, column(section));

        assertEquals(1, VoxelIngestService.calls.size());
        var call = VoxelIngestService.calls.get(0);
        assertNotNull(call.skyLight(), "null sky light must be replaced, not passed through as null");
        for (byte b : call.skyLight().getData()) {
            assertEquals(0, b, "substituted sky light must be all-zero (dark), not daylight");
        }
        assertNull(call.blockLight(), "block light is not substituted — Voxy renders null block light dark");
    }

    @Test
    void nonNullSkyLightPassesThroughUnchanged() {
        // The substitution must be surgical: a real (non-null) sky layer — e.g. an overworld
        // surface's shipped daylight — reaches Voxy byte-identical, never replaced.
        var consumer = initBridge();
        var skyLight = new DataLayer(new byte[2048]);
        skyLight.set(0, 0, 0, 15);
        var section = new VoxelColumnData.SectionData(1, null, new DataLayer(new byte[2048]), skyLight);

        consumer.onVoxelColumnReceived(null, DIM, 0, 0, column(section));

        var call = VoxelIngestService.calls.get(0);
        assertSame(skyLight, call.skyLight(), "a present sky layer must pass through unchanged");
    }

    @Test
    void nullWorldIdReportsOnceWithoutIngestAttempt() {
        var consumer = initBridge();
        WorldIdentifier.returned = null;

        consumer.onVoxelColumnReceived(null, DIM, 3, 5, column(sectionData(0)));

        assertEquals(0, VoxelIngestService.calls.size(), "no ingest attempt without a worldId");
        assertEquals(List.of(new Report(DIM, 3, 5)), reports,
                "exactly one report, or the column is silently lost and never re-requested");

        // Transient condition (e.g. mid open-to-LAN transition) — must NOT dead-latch.
        WorldIdentifier.returned = new WorldIdentifier();
        consumer.onVoxelColumnReceived(null, DIM, 3, 5, column(sectionData(0)));
        assertEquals(1, VoxelIngestService.calls.size(), "bridge must recover once storage exists");
        assertEquals(1, reports.size(), "the accepted re-delivery must not report");
    }

    @Test
    void rejectedSectionsReportOncePerColumnAndDoNotShortCircuit() {
        var consumer = initBridge();
        VoxelIngestService.behavior = call -> call.sectionY() == 0; // reject the last two sections

        consumer.onVoxelColumnReceived(null, DIM, -8, 21,
                column(sectionData(0), sectionData(1), sectionData(2)));

        assertEquals(3, VoxelIngestService.calls.size(),
                "a rejected section must not short-circuit the remaining sections");
        assertEquals(List.of(new Report(DIM, -8, 21)), reports,
                "exactly one report per column even with multiple rejected sections");
    }

    @Test
    void linkageErrorDeadLatchesBridgeButKeepsStampsHonest() {
        // The latch stops re-serve churn (no further ingest attempts), but every delivered
        // column must still REPORT: the receive handler already stamped it received, and a
        // silent return would persist stamps for data Voxy never stored — after a Voxy
        // upgrade every such position resyncs up_to_date and stays a permanent hole. The
        // report parks each position honestly (ts=-1, bounded by the per-position cap).
        var consumer = initBridge();
        VoxelIngestService.behavior = call -> { throw new LinkageError("incompatible voxy"); };

        assertDoesNotThrow(() -> consumer.onVoxelColumnReceived(null, DIM, 1, 2,
                column(sectionData(0), sectionData(1))));
        assertEquals(1, VoxelIngestService.calls.size(), "LinkageError aborts the column mid-loop");
        assertEquals(1, reports.size(), "the latching column itself must park honestly");

        VoxelIngestService.reset(); // healthy again, but the latch already killed the bridge
        consumer.onVoxelColumnReceived(null, DIM, 1, 2, column(sectionData(0)));
        assertEquals(0, VoxelIngestService.calls.size(), "dead bridge must not attempt ingests");
        assertEquals(2, reports.size(),
                "every column delivered to the dead bridge must report, not silently retain its stamp");
    }

    @Test
    void missingRawIngestAtInitFailsClosed() {
        VoxyCompat.classResolver = name ->
                name.equals("me.cortex.voxy.common.world.service.VoxelIngestService")
                        ? BrokenIngestService.class : Class.forName(name);

        assertFalse(VoxyCompat.init(), "a Voxy without rawIngest must report not-loaded");
        assertTrue(registered.isEmpty(), "a half-resolved bridge must never register a consumer");
    }

    /** Resolved in place of the ingest service: right class, no rawIngest method. */
    static final class BrokenIngestService {}

    @Test
    void missingVoxyClassesAtInitFailClosed() {
        VoxyCompat.classResolver = name -> { throw new ClassNotFoundException(name); };

        assertFalse(VoxyCompat.init(), "unresolvable Voxy classes must report not-loaded");
        assertTrue(registered.isEmpty());
    }

    // ---- CL-073: error rethrow policy ----

    @Test
    void nonAssertionErrorEscapesBridgeWithoutLatchOrReport() {
        var consumer = initBridge();
        var error = new TestError();
        VoxelIngestService.behavior = call -> { throw error; };

        var thrown = assertThrows(Error.class,
                () -> consumer.onVoxelColumnReceived(null, DIM, 4, -6, column(sectionData(0))));
        assertSame(error, thrown,
                "non-AssertionError Errors must propagate to LSSApi.dispatchColumn's catch");
        assertTrue(reports.isEmpty(),
                "the bridge must not report what it rethrows — dispatchColumn owns that report");

        VoxelIngestService.behavior = call -> true;
        consumer.onVoxelColumnReceived(null, DIM, 4, -6, column(sectionData(0)));
        assertEquals(2, VoxelIngestService.calls.size(), "a rethrown Error must not dead-latch");
    }

    @Test
    void rethrownErrorIsContainedByDispatchColumnAndBridgeSurvives() {
        var consumer = initBridge();
        VoxelIngestService.behavior = call -> { throw new TestError(); };

        LSSApi.registerColumnConsumer(consumer);
        try {
            assertDoesNotThrow(() -> LSSApi.dispatchColumn(null, DIM, 9, -9, column(sectionData(0))),
                    "dispatchColumn's Throwable catch must absorb the bridge's rethrown Error");
        } finally {
            LSSApi.removeColumnConsumer(consumer);
        }

        VoxelIngestService.behavior = call -> true;
        consumer.onVoxelColumnReceived(null, DIM, 9, -9, column(sectionData(0)));
        assertEquals(2, VoxelIngestService.calls.size(),
                "bridge must stay alive after dispatchColumn absorbed the Error");
        assertTrue(reports.isEmpty(), "the implicit report is dispatchColumn's, not the bridge's");
    }

    @Test
    void assertionErrorReportsInBridgeAndDoesNotLatch() {
        var consumer = initBridge();
        VoxelIngestService.behavior = call -> { throw new AssertionError("voxy assertion"); };

        assertDoesNotThrow(() -> consumer.onVoxelColumnReceived(null, DIM, 6, -2,
                column(sectionData(0))));
        assertEquals(List.of(new Report(DIM, 6, -2)), reports,
                "AssertionError is the carved-out Error: reported in-bridge, not rethrown");

        VoxelIngestService.behavior = call -> true;
        consumer.onVoxelColumnReceived(null, DIM, 6, -2, column(sectionData(0)));
        assertEquals(2, VoxelIngestService.calls.size(), "AssertionError must not dead-latch");
        assertEquals(1, reports.size());
    }

    @Test
    void runtimeExceptionReportsInBridgeAndDoesNotLatch() {
        var consumer = initBridge();
        VoxelIngestService.behavior = call -> { throw new IllegalStateException("ingest bug"); };

        assertDoesNotThrow(() -> consumer.onVoxelColumnReceived(null, DIM, 11, 0,
                column(sectionData(0))));
        assertEquals(List.of(new Report(DIM, 11, 0)), reports,
                "exceptions report so the column is re-served");

        VoxelIngestService.behavior = call -> true;
        consumer.onVoxelColumnReceived(null, DIM, 11, 0, column(sectionData(0)));
        assertEquals(2, VoxelIngestService.calls.size(), "an Exception must not dead-latch");
        assertEquals(1, reports.size());
    }

    // ---- ingest-backlog probe (issue #71) ----

    @Test
    void backlogProbeSurfacesTheLiveTaskCount() {
        VoxelIngestService.taskCount = 1234;
        var consumer = initBridge();
        assertEquals(1234, consumer.pendingIngestBacklog(),
                "the probe must surface VoxyCommon.getInstance().getIngestService().getTaskCount()");
        VoxelIngestService.taskCount = 7;
        assertEquals(7, consumer.pendingIngestBacklog(), "live per-poll read, not a snapshot");
    }

    @Test
    void backlogProbeNullInstanceReportsNoSignal() {
        var consumer = initBridge();
        VoxyCommon.instance = null; // pre-join / post-shutdown — routine, never an error
        assertEquals(-1, consumer.pendingIngestBacklog(),
                "no VoxyInstance yet must read as no-signal");
        VoxyCommon.reset();
        VoxelIngestService.taskCount = 5;
        assertEquals(5, consumer.pendingIngestBacklog(), "the probe recovers once an instance exists");
    }

    @Test
    void backlogProbeNullServiceReportsNoSignal() {
        var consumer = initBridge();
        VoxyCommon.instance.ingestService = null; // production never does this — defensive rung
        assertEquals(-1, consumer.pendingIngestBacklog());
    }

    @Test
    void backlogProbeThrowIsContainedToNoSignal() {
        var consumer = initBridge();
        VoxelIngestService.taskCountThrow = new IllegalStateException("gauge broke");
        assertEquals(-1, consumer.pendingIngestBacklog(),
                "a throwing gauge degrades to no-signal, never up the tick loop");
        VoxelIngestService.taskCountThrow = null;
        VoxelIngestService.taskCount = 9;
        assertEquals(9, consumer.pendingIngestBacklog(), "and recovers when the gauge does");
    }

    @Test
    void missingBacklogChainStillRegistersTheIngestBridge() {
        // SEPARATE failure domains: a Voxy without the backlog surface (renamed/removed)
        // must cost only the pacing signal — the ingest bridge still registers and works.
        VoxyCompat.classResolver = name -> {
            if (name.endsWith("VoxyCommon")) throw new ClassNotFoundException(name);
            return Class.forName(name);
        };
        var consumer = initBridge(); // init still succeeds
        assertEquals(-1, consumer.pendingIngestBacklog(),
                "an unresolved probe permanently reports no-signal");
        VoxelIngestService.taskCount = 1234;
        assertEquals(-1, consumer.pendingIngestBacklog(), "still no-signal — the chain never bound");
    }

    // ---- distance query (the contract the SpiralScanner min-ladder tests lean on) ----

    @Test
    void viewDistanceReadsLiveVoxyConfigInChunks() {
        VoxyConfig.CONFIG.sectionRenderDistance = 2.0f;
        assertEquals(OptionalInt.of(64), VoxyCompat.getViewDistanceChunks(),
                "chunks = round(sectionRenderDistance * 32)");

        VoxyConfig.CONFIG.sectionRenderDistance = 0.4f;
        assertEquals(OptionalInt.of(13), VoxyCompat.getViewDistanceChunks(),
                "the live field is re-read on every query, rounded half-up");
    }

    @Test
    void modCompatStaysInertWithoutVoxyMod() {
        ModCompat.init(); // fabric-loader-junit env has no voxy mod -> gate must hold

        assertTrue(ModCompat.getVoxyViewDistanceChunks().isEmpty(),
                "voxy distance must stay empty when the mod is absent");
        assertTrue(registered.isEmpty(),
                "the bridge must not register (init would succeed against the stubs if the gate broke)");
    }

    // ---- v0.11.0 stage D: the /lss reset Voxy ladder (order-pinned via injected hooks;
    // ---- client-reset-command-and-cache-relocation-plan.md §Tests) ----

    private static VoxyCompat.ResetHooks hooks(java.util.List<String> log,
                                               Object instance,
                                               java.nio.file.Path storageRoot,
                                               java.nio.file.Path fallbackRoot,
                                               Runnable rendererShutdownOrNull,
                                               boolean rendererResolvable,
                                               VoxyCompat.ThrowingRunnable shutdown,
                                               VoxyCompat.ThrowingRunnable create) {
        return new VoxyCompat.ResetHooks(
                () -> { log.add("instance"); return instance; },
                inst -> { log.add("storagePath"); return storageRoot; },
                () -> { log.add("fallbackPath"); return fallbackRoot; },
                () -> {
                    log.add("resolveRenderer");
                    if (!rendererResolvable) return null;
                    return rendererShutdownOrNull != null ? rendererShutdownOrNull
                            : () -> log.add("rendererShutdown");
                },
                () -> { log.add("shutdownInstance"); shutdown.run(); },
                () -> { log.add("createInstance"); create.run(); },
                target -> log.add("wipe:" + target.getFileName()),
                () -> log.add("gc"),
                () -> log.add("allChanged"),
                target -> true);
    }

    private static final VoxyCompat.ThrowingRunnable OK = () -> {};

    /** The DEFAULT (unforced) ladder — every pin below #4 exercises this form, so the
     *  issue-#4 force flag can never silently become the default. */
    private static ModCompat.VoxyResetOutcome resetVoxy(VoxyCompat.ResetHooks h) {
        return VoxyCompat.resetVoxy(h, false, null).outcome();
    }

    @Test
    void resetLadderHappyPathRunsInThePinnedOrder() {
        var log = new java.util.ArrayList<String>();
        var root = java.nio.file.Path.of("srv");
        var outcome = resetVoxy(hooks(log, new Object(), root, root, null, true, OK, OK));
        assertEquals(ModCompat.VoxyResetOutcome.RESET, outcome);
        assertEquals(java.util.List.of("instance", "storagePath", "fallbackPath",
                        "resolveRenderer", "rendererShutdown", "shutdownInstance", "wipe:srv",
                        "gc", "createInstance", "allChanged"), log,
                "the ladder order is load-bearing: wipe root read BEFORE shutdown and "
                        + "cross-checked against the derived root, renderer down BEFORE the "
                        + "instance, wipe inside the down-window");
    }

    @Test
    void unresolvableRendererHolderAbortsBeforeAnyTeardown() {
        var log = new java.util.ArrayList<String>();
        var outcome = resetVoxy(hooks(log, new Object(), java.nio.file.Path.of("srv"),
                null, null, false, OK, OK));
        assertEquals(ModCompat.VoxyResetOutcome.UNAVAILABLE, outcome);
        assertFalse(log.contains("shutdownInstance"),
                "aborting AFTER renderer resolution failed but BEFORE shutdownInstance is the "
                        + "non-negotiable fail-safe (the isWorldUsed busy-wait freeze)");
        assertFalse(log.stream().anyMatch(l -> l.startsWith("wipe:")), "no teardown, no wipe");
        assertFalse(log.contains("createInstance"));
    }

    @Test
    void nullInstanceWipesViaTheFallbackDerivationWithoutLifecycleCalls() {
        var log = new java.util.ArrayList<String>();
        var outcome = resetVoxy(hooks(log, null, java.nio.file.Path.of("live"),
                java.nio.file.Path.of("fallback"), null, true, OK, OK));
        assertEquals(ModCompat.VoxyResetOutcome.WIPED_NO_INSTANCE, outcome);
        assertTrue(log.contains("fallbackPath"), "the wipe root comes from the fallback derivation");
        assertTrue(log.contains("wipe:fallback"), "still wipes (no live storage to race)");
        assertFalse(log.contains("shutdownInstance"), "no instance to shut down");
        assertFalse(log.contains("createInstance"),
                "never create an instance Voxy itself didn't have (config-disabled/GPU cases)");
        assertFalse(log.contains("storagePath"), "no live instance to ask");
    }

    @Test
    void shutdownThrowSkipsTheWipeButStillAttemptsRecoveryCreate() {
        var log = new java.util.ArrayList<String>();
        var srv = java.nio.file.Path.of("srv");
        var outcome = resetVoxy(hooks(log, new Object(), srv,
                srv, null, true, () -> { throw new IllegalStateException("world-cleaner join"); }, OK));
        assertEquals(ModCompat.VoxyResetOutcome.SHUTDOWN_FAILED, outcome);
        assertFalse(log.stream().anyMatch(l -> l.startsWith("wipe:")),
                "storage may hold open handles — deleting over them is the Windows partial-wipe trap");
        assertTrue(log.contains("createInstance"),
                "Voxy is instanceless after the failed shutdown — recovery create still runs");
        assertTrue(log.contains("allChanged"), "the renderer rebuild still runs after recovery");
    }

    @Test
    void shutdownAndCreateBothThrowingIsRestartFailed() {
        var log = new java.util.ArrayList<String>();
        var outcome = resetVoxy(hooks(log, new Object(), java.nio.file.Path.of("srv"),
                null, null, true,
                () -> { throw new IllegalStateException("shutdown"); },
                () -> { throw new IllegalStateException("create"); }));
        assertEquals(ModCompat.VoxyResetOutcome.RESTART_FAILED, outcome);
        assertFalse(log.contains("allChanged"), "no renderer rebuild against a dead instance");
    }

    @Test
    void createThrowOnTheHappyPathIsRestartFailedAfterTheWipe() {
        var log = new java.util.ArrayList<String>();
        var srv2 = java.nio.file.Path.of("srv");
        var outcome = resetVoxy(hooks(log, new Object(), srv2,
                srv2, null, true, OK, () -> { throw new IllegalStateException("create"); }));
        assertEquals(ModCompat.VoxyResetOutcome.RESTART_FAILED, outcome);
        assertTrue(log.contains("wipe:srv"), "the wipe already happened — only the restart failed");
    }

    @Test
    void storagePathThrowSkipsOnlyTheWipe() {
        var log = new java.util.ArrayList<String>();
        var hooks = new VoxyCompat.ResetHooks(
                () -> new Object(),
                inst -> { throw new IllegalStateException("no path"); },
                () -> null,
                () -> () -> log.add("rendererShutdown"),
                () -> log.add("shutdownInstance"),
                () -> log.add("createInstance"),
                target -> log.add("wipe"),
                () -> log.add("gc"),
                () -> log.add("allChanged"),
                target -> true);
        assertEquals(ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, resetVoxy(hooks),
                "a skipped wipe must not report the full-RESET line (stage-D review m1/m4)");
        assertFalse(log.contains("wipe"), "no resolvable root -> no wipe, everything else runs");
        assertTrue(log.contains("createInstance"));
    }

    // ---- reset-domain resolution (all-or-nothing, separate failure domain) ----

    @Test
    void resetDomainResolvesAgainstTheStubSurface() {
        VoxyCompat.resetResetDomainForTest();
        assertTrue(VoxyCompat.initResetDomain(),
                "the reset domain must resolve against the stub Voxy classes");
        VoxyCompat.resetResetDomainForTest();
    }

    @Test
    void resetDomainFailureIsAllOrNothingAndNeverCostsTheProbe() {
        VoxyCompat.resetResetDomainForTest();
        VoxyCompat.classResolver = name -> {
            if (name.equals("me.cortex.voxy.client.VoxyClientInstance")) {
                throw new ClassNotFoundException(name);
            }
            return Class.forName(name);
        };
        assertFalse(VoxyCompat.initResetDomain(), "a partial chain must read as absent");

        // The ingest bridge + backlog probe resolve independently afterwards.
        VoxyCompat.resetSeams();
        VoxyCompat.reportSink = (dimension, chunkX, chunkZ) -> {};
        VoxyCompat.consumerRegistrar = registered::add;
        var consumer = initBridge();
        VoxelIngestService.taskCount = 7;
        assertEquals(7, consumer.pendingIngestBacklog(),
                "a dead reset domain must never cost the backlog probe");
        VoxyCompat.resetResetDomainForTest();
    }

    @Test
    void resetDomainFallsToTheSecondHolderRungWhenThePrimaryIsAbsent() {
        VoxyCompat.resetResetDomainForTest();
        VoxyCompat.classResolver = name -> {
            if (name.equals("me.cortex.voxy.client.core.IVoxyRenderSystemHolder")) {
                throw new ClassNotFoundException(name); // 0.2.11/dev era: only rung 2 exists
            }
            return Class.forName(name);
        };
        assertTrue(VoxyCompat.initResetDomain(),
                "IGetVoxyRenderSystem.shutdownRenderer is the 0.2.11/dev fallback rung");
        VoxyCompat.resetResetDomainForTest();
    }

    // ---- wipe containment (mirrors hostileServerAddressCannotEscapeCacheDir) ----

    @Test
    void wipeContainmentAcceptsOnlyVoxyStorageShapes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        var game = tmp.resolve("game");
        assertTrue(VoxyCompat.isWipeContained(
                game.resolve(".voxy").resolve("saves").resolve("localhost_25565"), game),
                "the multiplayer per-server dir is the canonical wipe target");
        assertTrue(VoxyCompat.isWipeContained(
                game.resolve("saves").resolve("myworld").resolve("voxy"), game),
                "the singleplayer <world>/voxy shape");
        assertFalse(VoxyCompat.isWipeContained(game.resolve(".voxy").resolve("saves"), game),
                "the saves root itself would wipe EVERY server");
        assertFalse(VoxyCompat.isWipeContained(game, game), "never the game dir");
        assertFalse(VoxyCompat.isWipeContained(tmp.resolve("elsewhere").resolve("voxy2"), game),
                "outside the game dir entirely -> refused");
        assertFalse(VoxyCompat.isWipeContained(
                game.resolve(".voxy").resolve("saves").resolve("..").resolve("..").resolve("x"), game),
                "traversal normalizes out of containment");
        // m3 tightening: the singleplayer rung is anchored under <gameDir>/saves —
        // a voxy-NAMED dir anywhere else under the game dir is refused.
        assertFalse(VoxyCompat.isWipeContained(game.resolve("mods").resolve("voxy"), game),
                "a voxy-named dir outside saves/ is not world storage");
        assertFalse(VoxyCompat.isWipeContained(game.resolve("voxy"), game),
                "a bare <gameDir>/voxy is not world storage");
    }

    /** The stage-D review MAJOR: a Flashback replay's storage override is the ORIGIN's
     *  REAL store path (recorded into the replay meta), which PASSES directory
     *  containment — only the derived-root cross-check protects it. A live root that
     *  differs from this connection's own derivation must skip the wipe. */
    @Test
    void storageOverrideMismatchSkipsTheWipeWithTheHonestOutcome() {
        var log = new java.util.ArrayList<String>();
        var outcome = resetVoxy(hooks(log, new Object(),
                java.nio.file.Path.of("origin-real-store"),
                java.nio.file.Path.of("current-derived"), null, true, OK, OK));
        assertEquals(ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, outcome,
                "the feedback must not claim disk was cleared");
        assertFalse(log.stream().anyMatch(l -> l.startsWith("wipe:")),
                "wiping the override target would destroy the ORIGIN server's store");
        assertTrue(log.contains("shutdownInstance") && log.contains("createInstance"),
                "the memory half (teardown + rebuild) still runs — it is override-safe");
    }

    /** "Can't tell" must abort, never route into the destructive fallback-wipe branch
     *  (review n1): an instance probe that THROWS is UNAVAILABLE, not not-running. */
    @Test
    void instanceProbeThrowAbortsInsteadOfFallbackWiping() {
        var log = new java.util.ArrayList<String>();
        var h = new VoxyCompat.ResetHooks(
                () -> { throw new IllegalStateException("probe dead"); },
                inst -> java.nio.file.Path.of("x"),
                () -> { log.add("fallbackPath"); return java.nio.file.Path.of("fb"); },
                () -> () -> {},
                () -> log.add("shutdownInstance"),
                () -> log.add("createInstance"),
                target -> log.add("wipe"),
                () -> {},
                () -> {},
                target -> true);
        assertEquals(ModCompat.VoxyResetOutcome.UNAVAILABLE, resetVoxy(h));
        assertTrue(log.isEmpty(), "no fallback wipe, no lifecycle calls on an unreadable probe");
    }

    /** Post-wipe throws must still resolve to a feedback-driving outcome (review m2):
     *  an allChanged throw is contained to RESTART_FAILED, never propagated (the
     *  coordinator's LSS flush depends on the ladder returning). */
    @Test
    void allChangedThrowIsContainedToRestartFailed() {
        var log = new java.util.ArrayList<String>();
        var srv = java.nio.file.Path.of("srv");
        var h = new VoxyCompat.ResetHooks(
                () -> new Object(),
                inst -> srv,
                () -> srv,
                () -> () -> {},
                () -> {},
                () -> {},
                target -> log.add("wipe"),
                () -> {},
                () -> { throw new IllegalStateException("render-extract gone"); },
                target -> true);
        assertEquals(ModCompat.VoxyResetOutcome.RESTART_FAILED, resetVoxy(h),
                "renderer rebuild never triggered -> the rejoin-to-recover guidance");
        assertTrue(log.contains("wipe"), "the throw came after the wipe — outcome stays honest");
    }

    /** Drive the RESOLVED MethodHandles end-to-end against the stubs (review m3 — the
     *  plan's call-recording requirement): an asType/cast shape bug in the production
     *  glue would pass every hook-injected test and surface only at the live smoke. */
    @Test
    void productionHandleHooksDriveTheResolvedHandlesAgainstTheStubs(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        VoxyCompat.resetResetDomainForTest();
        assertTrue(VoxyCompat.initResetDomain());
        try {
            var game = tmp.resolve("game");
            var serverStore = game.resolve(".voxy").resolve("saves").resolve("localhost_25565");
            java.nio.file.Files.createDirectories(serverStore.resolve("deadbeef"));
            java.nio.file.Files.writeString(serverStore.resolve("deadbeef").resolve("s.db"), "x");

            var client = new me.cortex.voxy.client.VoxyClientInstance();
            client.storageBasePath = serverStore;
            me.cortex.voxy.commonImpl.VoxyCommon.instance = client;
            var lifecycle = new java.util.ArrayList<String>();
            me.cortex.voxy.commonImpl.VoxyCommon.shutdownBody = () -> lifecycle.add("shutdown");
            me.cortex.voxy.commonImpl.VoxyCommon.createBody = () -> lifecycle.add("create");
            me.cortex.voxy.client.core.IVoxyRenderSystemHolder.HOLDER.set(
                    () -> lifecycle.add("rendererShutdown"));

            var hooks = VoxyCompat.productionHandleHooks(game, () -> serverStore,
                    () -> null, () -> lifecycle.add("allChanged"));
            assertEquals(ModCompat.VoxyResetOutcome.RESET, resetVoxy(hooks));
            assertEquals(java.util.List.of("rendererShutdown", "shutdown", "create", "allChanged"),
                    lifecycle, "every resolved handle invoked, in ladder order");
            assertFalse(java.nio.file.Files.exists(serverStore),
                    "the real wipe ran against the handle-resolved storage path");
        } finally {
            me.cortex.voxy.client.core.IVoxyRenderSystemHolder.HOLDER.set(null);
            me.cortex.voxy.commonImpl.VoxyCommon.reset();
            VoxyCompat.resetResetDomainForTest();
        }
    }

    @Test
    void wipeVoxyStoreDeletesOnlyTheContainedTarget(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var game = tmp.resolve("game");
        var server = game.resolve(".voxy").resolve("saves").resolve("localhost_25565");
        var sibling = game.resolve(".voxy").resolve("saves").resolve("other_server");
        java.nio.file.Files.createDirectories(server.resolve("deadbeef"));
        java.nio.file.Files.writeString(server.resolve("deadbeef").resolve("store.db"), "x");
        java.nio.file.Files.createDirectories(sibling);

        VoxyCompat.wipeVoxyStore(server, game);
        assertFalse(java.nio.file.Files.exists(server), "the per-server tree is gone");
        assertTrue(java.nio.file.Files.exists(sibling), "sibling servers untouched");

        // A non-contained target is refused outright.
        var outside = tmp.resolve("outside");
        java.nio.file.Files.createDirectories(outside);
        VoxyCompat.wipeVoxyStore(outside, game);
        assertTrue(java.nio.file.Files.exists(outside), "outside the containment roots -> refused");
    }

    /** Symlink pin (review n2): Files.walk without FOLLOW_LINKS deletes a symlink as a
     *  LINK — the foreign target tree must survive. A future "fix" adding FOLLOW_LINKS
     *  would turn the wipe into a foreign-tree deleter; this test is the tripwire. */
    @Test
    void wipeDeletesSymlinksAsLinksAndNeverFollowsThem(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var game = tmp.resolve("game");
        var server = game.resolve(".voxy").resolve("saves").resolve("localhost_25565");
        var foreign = tmp.resolve("foreign");
        java.nio.file.Files.createDirectories(server);
        java.nio.file.Files.createDirectories(foreign);
        java.nio.file.Files.writeString(foreign.resolve("precious.txt"), "keep me");
        try {
            java.nio.file.Files.createSymbolicLink(server.resolve("link"), foreign);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symlinks unavailable on this filesystem: " + e);
        }
        VoxyCompat.wipeVoxyStore(server, game);
        assertFalse(java.nio.file.Files.exists(server), "the store tree (incl. the link) is gone");
        assertTrue(java.nio.file.Files.exists(foreign.resolve("precious.txt")),
                "the foreign target tree must survive the wipe");
    }

    // ---- issue #4: the force override + the non-destructive storage probe ----

    /**
     * The wipe criterion, isolated. The DEFAULT column is the fail-safe the ticket
     * explicitly refuses to relax: an unverifiable or mismatching live root is never
     * wipeable. Force flips only the equality comparison — never the null guards, and
     * never containment (that fence lives in {@link VoxyCompat#wipeVoxyStore}).
     */
    @Test
    void wipeCriterionRelaxesOnlyTheEqualityComparison() {
        var live = java.nio.file.Path.of("/g/.voxy/saves/origin");
        var derived = java.nio.file.Path.of("/g/.voxy/saves/current");

        assertTrue(VoxyStorageOverride.shouldWipeLiveRoot(live, live, false), "matching roots wipe");
        assertFalse(VoxyStorageOverride.shouldWipeLiveRoot(live, derived, false),
                "the override cross-check is the stage-D review MAJOR — it must hold by default");
        assertFalse(VoxyStorageOverride.shouldWipeLiveRoot(live, null, false),
                "an unverifiable root is not a wipeable root");

        assertTrue(VoxyStorageOverride.shouldWipeLiveRoot(live, derived, true), "force waives the equality");
        assertTrue(VoxyStorageOverride.shouldWipeLiveRoot(live, null, true),
                "force waives the underivable-expected skip too — the user was shown the path");
        assertFalse(VoxyStorageOverride.shouldWipeLiveRoot(null, derived, true),
                "force cannot conjure a root that was never read");
        assertFalse(VoxyStorageOverride.shouldWipeLiveRoot(null, null, true));
    }

    @Test
    void wipeCriterionComparesRootsAfterNormalisation() {
        assertTrue(VoxyStorageOverride.shouldWipeLiveRoot(
                        java.nio.file.Path.of("/g/.voxy/saves/./srv"),
                        java.nio.file.Path.of("/g/.voxy/saves/x/../srv"), false),
                "'.' and '..' are not an override");
    }

    /** AC4: with force OFF the mismatch branch behaves exactly as it did before #4. */
    @Test
    void unforcedOverrideMismatchStillSkipsTheWipeAndReportsBothRoots() {
        var log = new java.util.ArrayList<String>();
        var live = java.nio.file.Path.of("origin-real-store");
        var derived = java.nio.file.Path.of("current-derived");
        var report = VoxyCompat.resetVoxy(
                hooks(log, new Object(), live, derived, null, true, OK, OK), false, null);
        assertEquals(ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, report.outcome());
        assertFalse(log.stream().anyMatch(l -> l.startsWith("wipe:")),
                "wiping the override target would destroy the ORIGIN server's store");
        assertEquals(live, report.liveRoot(), "the report must carry the path to show the user");
        assertEquals(derived, report.expectedRoot());
    }

    /** Force is bound to the GRANTED root: a live root that moved since the stage-2
     *  probe falls back to the ordinary (declined) comparison — never a wipe of an
     *  unshown path (panel fix: shown==wiped enforced at the wipe itself). */
    @Test
    void aForcedWipeOfAMovedLiveRootDeclines() {
        var log = new java.util.ArrayList<String>();
        var report = VoxyCompat.resetVoxy(hooks(log, new Object(),
                java.nio.file.Path.of("moved-elsewhere"),
                java.nio.file.Path.of("current-derived"), null, true, OK, OK),
                true, java.nio.file.Path.of("origin-real-store"));
        assertEquals(ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, report.outcome());
        assertFalse(log.stream().anyMatch(l -> l.startsWith("wipe:")),
                "a moved root is NOT the root the user was shown — nothing wiped");
    }

    /** Under force the no-instance fallback wipe is REFUSED: the grant was armed for a
     *  live root, and the fallback targets the DERIVED root — on the overridden shape a
     *  different directory than the one the user was shown (panel fix). */
    @Test
    void aForcedRunWithAVanishedInstanceWipesNothing() {
        var log = new java.util.ArrayList<String>();
        var report = VoxyCompat.resetVoxy(hooks(log, null,
                java.nio.file.Path.of("unused"),
                java.nio.file.Path.of("current-derived"), null, true, OK, OK),
                true, java.nio.file.Path.of("origin-real-store"));
        assertEquals(ModCompat.VoxyResetOutcome.UNAVAILABLE, report.outcome());
        assertTrue(report.wipeDeclined(), "the report says the store survived");
        assertFalse(log.stream().anyMatch(l -> l.startsWith("wipe:")),
                "the derived-root fallback wipe must not run under force");
    }

    /** AC2: with force ON the same mismatch wipes the LIVE root — and only that one. */
    @Test
    void forcedOverrideMismatchWipesTheLiveRootInThePinnedOrder() {
        var log = new java.util.ArrayList<String>();
        var report = VoxyCompat.resetVoxy(hooks(log, new Object(),
                java.nio.file.Path.of("origin-real-store"),
                java.nio.file.Path.of("current-derived"), null, true, OK, OK), true, java.nio.file.Path.of("origin-real-store"));
        assertEquals(ModCompat.VoxyResetOutcome.RESET, report.outcome(),
                "the disk really was cleared — the feedback may say so");
        assertEquals(java.util.List.of("instance", "storagePath", "fallbackPath",
                        "resolveRenderer", "rendererShutdown", "shutdownInstance",
                        "wipe:origin-real-store", "gc", "createInstance", "allChanged"), log,
                "force changes WHICH root is wiped, never the ladder order");
    }

    /** Force cannot manufacture a target: an unreadable live root still skips. */
    @Test
    void forcedResetWithNoReadableLiveRootStillSkipsTheWipe() {
        var log = new java.util.ArrayList<String>();
        var hooks = new VoxyCompat.ResetHooks(
                () -> new Object(),
                inst -> { throw new IllegalStateException("no path"); },
                () -> java.nio.file.Path.of("derived"),
                () -> () -> log.add("rendererShutdown"),
                () -> log.add("shutdownInstance"),
                () -> log.add("createInstance"),
                target -> log.add("wipe:" + target),
                () -> log.add("gc"),
                () -> log.add("allChanged"),
                target -> true);
        var report = VoxyCompat.resetVoxy(hooks, true, java.nio.file.Path.of("srv"));
        assertEquals(ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, report.outcome());
        assertFalse(log.stream().anyMatch(l -> l.startsWith("wipe:")), log.toString());
        assertNull(report.liveRoot());
    }

    /**
     * AC3 — the load-bearing one: containment is the SECOND fence and {@code voxy-force}
     * does NOT lift it. Driven against the REAL {@link VoxyCompat#wipeVoxyStore} wiring
     * (not the logging stub) so a future refactor that routes force around containment
     * reds here.
     */
    @Test
    void forcedWipeIsStillRefusedOutsideTheContainmentRoots(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var game = tmp.resolve("game");
        var outside = tmp.resolve("elsewhere");
        java.nio.file.Files.createDirectories(game);
        java.nio.file.Files.createDirectories(outside);
        java.nio.file.Files.writeString(outside.resolve("precious.txt"), "keep me");
        var hooks = new VoxyCompat.ResetHooks(
                Object::new,
                inst -> outside,
                () -> game.resolve(".voxy").resolve("saves").resolve("current"),
                () -> () -> {},
                () -> {},
                () -> {},
                target -> VoxyCompat.wipeVoxyStore(target, game),
                () -> {},
                () -> {},
                target -> true);
        var report = VoxyCompat.resetVoxy(hooks, true, outside);
        assertEquals(ModCompat.VoxyResetOutcome.RESET, report.outcome(),
                "the ladder itself completed — the wipe was refused inside wipeVoxyStore");
        assertTrue(java.nio.file.Files.exists(outside.resolve("precious.txt")),
                "force waives the override cross-check ONLY — containment still stands");
    }

    /** And inside the roots, a forced wipe of a real override target does delete. */
    @Test
    void forcedWipeInsideTheContainmentRootsActuallyDeletes(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var game = tmp.resolve("game");
        var saves = game.resolve(".voxy").resolve("saves");
        var live = saves.resolve("origin.example.com");
        java.nio.file.Files.createDirectories(live);
        java.nio.file.Files.writeString(live.resolve("store.db"), "lods");
        var hooks = new VoxyCompat.ResetHooks(
                Object::new,
                inst -> live,
                () -> saves.resolve("current.example.com"),
                () -> () -> {},
                () -> {},
                () -> {},
                target -> VoxyCompat.wipeVoxyStore(target, game),
                () -> {},
                () -> {},
                target -> true);
        assertEquals(ModCompat.VoxyResetOutcome.RESET,
                VoxyCompat.resetVoxy(hooks, true, live).outcome());
        assertFalse(java.nio.file.Files.exists(live), "the forced wipe is a real wipe");
    }

    // ---- the storage probe (stage 1 of voxy-force) ----

    /** AC2: the probe reads the two roots and touches NOTHING else — no renderer, no
     *  lifecycle, no disk. Anything else here would make stage 1 destructive. */
    @Test
    void storageProbeReadsBothRootsWithoutTouchingTheLifecycle() {
        var log = new java.util.ArrayList<String>();
        var live = java.nio.file.Path.of("/g/.voxy/saves/origin");
        var derived = java.nio.file.Path.of("/g/.voxy/saves/current");
        var probe = VoxyCompat.probeStorage(
                hooks(log, new Object(), live, derived, null, true, OK, OK),
                java.nio.file.Path.of("/g"));
        assertEquals(java.util.List.of("instance", "fallbackPath", "storagePath"), log,
                "read-only hooks only — a teardown or wipe call here breaks the two-stage "
                        + "promise: " + log);
        assertEquals(live, probe.liveRoot());
        assertEquals(derived, probe.expectedRoot());
        assertTrue(probe.voxyPresent());
        assertEquals(VoxyStorageOverride.Verdict.OVERRIDDEN, probe.verdict());
        assertTrue(probe.containedForWipe(), "/g/.voxy/saves/origin is inside the roots");
    }

    @Test
    void storageProbeFlagsAnOutOfRootLiveRootAsUncontained() {
        var log = new java.util.ArrayList<String>();
        var probe = VoxyCompat.probeStorage(
                hooks(log, new Object(), java.nio.file.Path.of("/etc"),
                        java.nio.file.Path.of("/g/.voxy/saves/current"), null, true, OK, OK),
                java.nio.file.Path.of("/g"));
        assertFalse(probe.containedForWipe(),
                "the prompt has to tell the user the wipe would be refused anyway");
    }

    @Test
    void storageProbeSurvivesAThrowingInstanceOrStoragePath() {
        var throwingInstance = new VoxyCompat.ResetHooks(
                () -> { throw new IllegalStateException("probe blew up"); },
                inst -> java.nio.file.Path.of("live"),
                () -> java.nio.file.Path.of("derived"),
                () -> () -> {}, () -> {}, () -> {}, target -> {}, () -> {}, () -> {},
                target -> true);
        var probe = VoxyCompat.probeStorage(throwingInstance, java.nio.file.Path.of("/g"));
        assertTrue(probe.voxyPresent());
        assertNull(probe.liveRoot(), "'can't tell' must never present a path to force-wipe");
        assertFalse(probe.verdict() == VoxyStorageOverride.Verdict.OVERRIDDEN);

        var throwingPath = new VoxyCompat.ResetHooks(
                Object::new,
                inst -> { throw new IllegalStateException("no path"); },
                () -> java.nio.file.Path.of("derived"),
                () -> () -> {}, () -> {}, () -> {}, target -> {}, () -> {}, () -> {},
                target -> true);
        assertNull(VoxyCompat.probeStorage(throwingPath, java.nio.file.Path.of("/g")).liveRoot());
    }

    /** No live instance: the normal reset already wipes the derived root, so the probe
     *  reports no live root and the prompt sends the user back to plain /lss reset. */
    @Test
    void storageProbeWithNoInstanceReportsTheDerivedRootOnly() {
        var log = new java.util.ArrayList<String>();
        var derived = java.nio.file.Path.of("/g/.voxy/saves/current");
        var probe = VoxyCompat.probeStorage(
                hooks(log, null, java.nio.file.Path.of("/g/.voxy/saves/live"), derived,
                        null, true, OK, OK),
                java.nio.file.Path.of("/g"));
        assertNull(probe.liveRoot());
        assertEquals(derived, probe.expectedRoot());
        assertFalse(log.contains("storagePath"), "no instance -> nothing to ask: " + log);
    }


    /**
     * Issue #4 follow-up: when the LIVE root is unreadable the ladder used to skip the
     * derived-root read too, so the one branch that most needs a path to show the user
     * was the one branch that had none — while {@code probeStorage} (voxy-force stage 1)
     * printed a real path in the very same situation. The report now carries it.
     */
    @Test
    void anUnreadableLiveRootStillReportsTheDerivedRootToTheUser() {
        var log = new java.util.ArrayList<String>();
        var derived = java.nio.file.Path.of("/g/.voxy/saves/current");
        var hooks = new VoxyCompat.ResetHooks(
                Object::new,
                inst -> { throw new IllegalStateException("no path"); },
                () -> { log.add("fallbackPath"); return derived; },
                () -> () -> log.add("rendererShutdown"),
                () -> log.add("shutdownInstance"),
                () -> log.add("createInstance"),
                target -> log.add("wipe:" + target),
                () -> log.add("gc"),
                () -> log.add("allChanged"),
                target -> true);
        var report = VoxyCompat.resetVoxy(hooks, false, null);
        assertEquals(ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, report.outcome());
        assertNull(report.liveRoot());
        assertEquals(derived, report.expectedRoot(),
                "the user has to be told where LSS thinks the store should be");
        assertTrue(report.wipeDeclined());
        assertFalse(log.stream().anyMatch(l -> l.startsWith("wipe:")),
                "reading the derived root for a MESSAGE must not make it a wipe target");
    }

    /** That extra read exists only to enrich a message, so a throw from it must not turn
     *  a survivable reset into a failed one. */
    @Test
    void aThrowingDerivedRootReadOnTheUnreadableBranchIsContained() {
        var hooks = new VoxyCompat.ResetHooks(
                Object::new,
                inst -> { throw new IllegalStateException("no path"); },
                () -> { throw new IllegalStateException("no game dir"); },
                () -> () -> {},
                () -> {},
                () -> {},
                target -> {},
                () -> {},
                () -> {},
                target -> true);
        var report = VoxyCompat.resetVoxy(hooks, false, null);
        assertEquals(ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, report.outcome(),
                "the ladder still completes — only the report degrades");
        assertNull(report.expectedRoot());
    }

    /** The declined-wipe flag must survive a LATER rung failing, or the coordinator
     *  cannot tell those users where their LODs are. */
    @Test
    void aDeclinedWipeStaysFlaggedWhenALaterRungFails() {
        var log = new java.util.ArrayList<String>();
        var report = VoxyCompat.resetVoxy(hooks(log, new Object(),
                java.nio.file.Path.of("origin-real-store"),
                java.nio.file.Path.of("current-derived"), null, true, OK,
                () -> { throw new IllegalStateException("create blew up"); }), false, null);
        assertEquals(ModCompat.VoxyResetOutcome.RESTART_FAILED, report.outcome());
        assertTrue(report.wipeDeclined(),
                "the cross-check refused BEFORE the restart failed — the user still needs "
                        + "to be told the store survived");
        assertEquals(java.nio.file.Path.of("origin-real-store"), report.liveRoot());
    }

    /** ...and a wipe that was ALLOWED must never be flagged as declined, whatever the
     *  ladder ends as — the client log stays silent there, so chat must too. */
    @Test
    void anAllowedWipeIsNeverFlaggedAsDeclined() {
        var log = new java.util.ArrayList<String>();
        var root = java.nio.file.Path.of("srv");
        var report = VoxyCompat.resetVoxy(hooks(log, new Object(), root, root, null, true, OK,
                () -> { throw new IllegalStateException("create blew up"); }), false, null);
        assertEquals(ModCompat.VoxyResetOutcome.RESTART_FAILED, report.outcome());
        assertFalse(report.wipeDeclined());
    }
}
