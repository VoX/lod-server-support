package dev.vox.lss.compat;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
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
            ResourceKey.create(Registries.DIMENSION, new ResourceLocation("lss_test:voxy"));

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
}
