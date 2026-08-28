// OVERLAY OF fabric/src/gametest/java/dev/vox/lss/test/LSSClientGameTests.java @ d8cde25acc760d9f7605018092a2a495fafdb3566139e0a1560a05e9ad541a25
// 26.1 line overlay (single-branch-consolidation-plan.md §3.2). Refresh the stamp when the shared file changes.
package dev.vox.lss.test;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.client.LSSClientNetworking;
import dev.vox.lss.networking.server.LSSServerNetworking;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * End-to-end client gametest, two singleplayer worlds on one client boot:
 *
 * <ul>
 *   <li><b>Main flow + consumer content</b> — handshake, session config, spiral scan, column
 *       receipt, and the product's core promise: a registered {@link LSSApi} consumer receives
 *       DECODED {@code LevelChunkSection}s whose block states, biome, and sky light match the
 *       known superflat test world (bedrock −64, dirt −63/−62, grass −61, plains). This is the
 *       only place the hand-rolled {@code ClientColumnProcessor} decode is checked against the
 *       server encode end-to-end — every counter-based assertion passes even if columns decode
 *       to garbage.</li>
 *   <li><b>LAN-publish activation</b> — with the {@code lss.test.integratedServer} override
 *       cleared, a private integrated server must defer the request service and the client must
 *       stay silent; publishing to LAN must start the service via {@code IntegratedServerLanHook}
 *       and complete the host handshake (the only production activation path on integrated
 *       servers, otherwise never exercised because gametest JVMs force the override).</li>
 * </ul>
 */
public class LSSClientGameTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        // Register a recording consumer so the handshake includes CAPABILITY_VOXEL_COLUMNS
        // (without a consumer, capabilities=0 and the server skips all request routing) AND
        // so decoded section content can be asserted against the known flat world.
        var recorder = new RecordingColumnConsumer();
        LSSApi.registerColumnConsumer(recorder);
        // Rejects exactly the FIRST column it ever sees via LSSApi.reportIngestFailure and
        // then watches for that position to be re-delivered — the end-to-end pin for the
        // delivery-honesty loop (consumer reject -> unstamp -> ts=-1 re-request -> server
        // re-resolves instead of answering up-to-date -> re-serve). Before the honest
        // re-resolution fix, the re-request was answered up-to-date with no data and the
        // position stayed a permanent hole.
        var rejector = new FirstColumnRejectingConsumer();
        LSSApi.registerColumnConsumer(rejector);
        // Same loop via the implicit path: a consumer that THROWS from its callback must be
        // treated as an ingest failure by LSSApi.dispatchColumn's catch (third-party
        // consumers signal failure by throwing far more often than by calling the API).
        var thrower = new FirstColumnThrowingConsumer();
        LSSApi.registerColumnConsumer(thrower);

        // Low values prevent llvmpipe from starving the integrated server on CI
        context.runOnClient(client -> {
            client.options.renderDistance().set(2);
            client.options.simulationDistance().set(2);
        });

        runMainFlowTest(context, recorder, rejector, thrower);
        runLanPublishActivationTest(context);
    }

    private static void runMainFlowTest(ClientGameTestContext context, RecordingColumnConsumer recorder,
                                        FirstColumnRejectingConsumer rejector,
                                        FirstColumnThrowingConsumer thrower) {
        try (TestSingleplayerContext _ = context.worldBuilder().create()) {
            // Wait for join -> handshake -> session config -> LodRequestManager creation
            context.waitTicks(40);

            // Test 1: LSS activates on join
            if (!LSSClientNetworking.isServerEnabled()) {
                throw new AssertionError("LSS should be enabled after handshake");
            }
            if (LSSClientNetworking.getServerLodDistance() <= 0) {
                throw new AssertionError("Server LOD distance should be set");
            }
            if (LSSClientNetworking.getRequestManager() == null) {
                throw new AssertionError("LodRequestManager should be created");
            }

            // C1: Session config LOD distance propagation
            if (LSSClientNetworking.getServerLodDistance() != LSSServerConfig.CONFIG.lodDistanceChunks) {
                throw new AssertionError("Server LOD distance should match config: expected "
                        + LSSServerConfig.CONFIG.lodDistanceChunks + ", got " + LSSClientNetworking.getServerLodDistance());
            }

            // C2: Effective LOD distance calculation
            var manager = LSSClientNetworking.getRequestManager();
            if (manager.getEffectiveLodDistanceChunks() <= 0) {
                throw new AssertionError("Effective LOD distance should be positive");
            }

            // Wait for scanning + server processing + response.
            // CI runners (2 vCPU, llvmpipe software rendering) are heavily starved —
            // the server can fall 2+ seconds behind during spawn prep, so we need
            // generous tick budgets for generation + voxelization + send cycles.
            context.waitTicks(400);

            // Test 2: Client receives columns
            if (LSSClientNetworking.getColumnsReceived() <= 0) {
                throw new AssertionError("Client should have received at least one column");
            }
            if (LSSClientNetworking.getBytesReceived() <= 0) {
                throw new AssertionError("Client should have received bytes");
            }

            // Test 3: Request manager tracks state
            manager = LSSClientNetworking.getRequestManager();
            if (manager == null) {
                throw new AssertionError("No request manager");
            }
            if (manager.getReceivedColumnCount() <= 0) {
                throw new AssertionError("Should have received column timestamps");
            }
            if (manager.getTotalSendCycles() <= 0) {
                throw new AssertionError("Should have sent at least one send cycle");
            }

            // C3: Spiral scan made progress
            if (manager.getTotalPositionsRequested() <= 0) {
                throw new AssertionError("Should have requested at least one position");
            }

            // C4: Request/response lifecycle (v9 — individual responses, no batch completion)
            long totalResponses = manager.getTotalColumnsReceived()
                    + manager.getTotalUpToDate()
                    + manager.getTotalNotGenerated();
            if (totalResponses <= 0) {
                throw new AssertionError("Should have received at least one response: "
                        + "columns=" + manager.getTotalColumnsReceived()
                        + " upToDate=" + manager.getTotalUpToDate()
                        + " notGenerated=" + manager.getTotalNotGenerated());
            }

            // C5: Bandwidth is bounded
            long maxExpectedBytes = (long) LSSServerConfig.CONFIG.bytesPerSecondPerPlayer() * 15;
            if (LSSClientNetworking.getBytesReceived() > maxExpectedBytes) {
                throw new AssertionError("Bytes received exceeds bandwidth budget: "
                        + LSSClientNetworking.getBytesReceived() + " > " + maxExpectedBytes);
            }

            // C6: Columns received after settling
            context.waitTicks(200); // 600 total ticks

            if (manager.getTotalColumnsReceived() <= 0) {
                throw new AssertionError("Should have received columns after settling: "
                        + manager.getTotalColumnsReceived());
            }

            // C7: Registered consumer receives correctly DECODED section content. Columns are
            // dispatched off the render thread after an extra decode hop, so allow a settle.
            waitForOrFail(context, () -> recorder.receivedCount() > 0, 400,
                    "registered consumer never received a column although the client received "
                            + LSSClientNetworking.getColumnsReceived()
                            + " (decode/dispatch pipeline broken)");
            assertDecodedFlatWorldContent(recorder.snapshot());

            // C7b: the compressed session actually negotiated (plan §5.1 — this test must
            // not silently pass raw): with useCompressedColumns default-on and the dev
            // client's zstd native present, at least one codec-1 column arrived, which is
            // provable without a codec counter: wire (shipped) strictly below the
            // raw-denominated received volume — codec-0 columns count both identically.
            // Read RAW first (4-agent round, wire F2): a column arriving between the two
            // reads then only pushes WIRE up, so a regressed all-raw session stays
            // deterministically red; the reverse order could false-PASS on one in-window
            // arrival. wire < raw <=> >=1 genuinely-compressed codec-1 column (the holder
            // ships only strictly-shrinking frames), which itself proves 0x2 negotiated.
            long rawReceived = LSSClientNetworking.getBytesReceived();
            long wireReceived = LSSClientNetworking.getWireBytesReceived();
            if (wireReceived >= rawReceived) {
                throw new AssertionError("no compressed column arrived (wire " + wireReceived
                        + " >= raw " + rawReceived
                        + ") — the compression capability failed to negotiate end-to-end");
            }

            // C8: ingest-failure recovery loop. The rejector reported its first column as
            // not-ingested; that exact position must be re-served end-to-end.
            waitForOrFail(context, rejector::sawRejection, 100,
                    "rejecting consumer never saw a column (nothing was dispatched?)");
            waitForOrFail(context, rejector::sawReDelivery, 600,
                    "rejected column " + rejector.describeRejected() + " was never re-served — "
                            + "the ingest-failure report did not trigger an honest re-resolution "
                            + "(permanent invisible hole)");

            // C9: the implicit path — a consumer throw must heal identically.
            waitForOrFail(context, thrower::sawReDelivery, 600,
                    "thrown-on column was never re-served — dispatchColumn's catch must "
                            + "treat a consumer throw as an ingest failure");

            // C10 (XVER §9 Tier 3, C6): a column carrying a SYNTHETIC UNKNOWN identity
            // must decode to the fallback state AT THE CONSUMER — the whole §3 ladder
            // (v20 parse → resolver terminal rung → native re-emit → dispatch) driven
            // end-to-end through the production receive glue on the live client.
            runIngestFallbackTest(context, recorder);
        }
    }

    /** Injects a v20 column whose every block identity is unknown (a real corpus body
     *  translated under a LYING identity lookup) at an already-served position with a
     *  newer stamp, then asserts the consumer's delivered sections are wall-to-wall
     *  {@code minecraft:stone} — the shipped {@code unknownBlockFallback} default. A
     *  broken terminal rung surfaces as air (coercion path), the original blocks
     *  (resolver bypassed), or a decode failure (nothing delivered). */
    private static void runIngestFallbackTest(ClientGameTestContext context,
                                              RecordingColumnConsumer recorder) {
        var served = recorder.snapshot().stream()
                .filter(rec -> rec.dimension().equals(Level.OVERWORLD))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "premise: no served overworld column to piggyback on"));
        int cx = served.chunkX();
        int cz = served.chunkZ();
        byte[] nativeBody;
        try {
            nativeBody = java.nio.file.Files.readAllBytes(
                    locateFabricModule().resolve("src/test/resources/nbt-corpus/multi-section.bin"));
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read the nbt-corpus fixture", e);
        }
        byte[] v20 = dev.vox.lss.common.wire.NativeToV20Translator.translate(nativeBody,
                id -> "lss:c6_does_not_exist", id -> "minecraft:plains");
        var payload = new dev.vox.lss.networking.payloads.VoxelColumnS2CPayload(cx, cz,
                Level.OVERWORLD, System.currentTimeMillis() / 1000L + 3600,
                (byte) 2, v20);
        // A FRESH consumer for this phase: the main recorder's record list caps at
        // MAX_RECORDED (256) and the full-disc backfill exhausted it long ago — the
        // first version of this test polled records that could never be added.
        var fallbackRecorder = new RecordingColumnConsumer();
        LSSApi.registerColumnConsumer(fallbackRecorder);
        context.runOnClient(client -> {
            try {
                // The static processor + receive glue live in ClientNetGlue since N-3.
                var netClass = dev.vox.lss.networking.client.ClientNetGlue.class;
                var procField = netClass.getDeclaredField("columnProcessor");
                procField.setAccessible(true);
                var handle = netClass.getDeclaredMethod("handleVoxelColumn",
                        dev.vox.lss.networking.client.LodRequestManager.class,
                        procField.getType(),
                        dev.vox.lss.networking.payloads.VoxelColumnS2CPayload.class);
                handle.setAccessible(true);
                handle.invoke(null, LSSClientNetworking.getRequestManager(),
                        procField.get(null), payload);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot drive the production receive glue", e);
            }
        });
        waitForOrFail(context, () -> fallbackRecorder.snapshot().stream()
                        .anyMatch(rec -> rec.chunkX() == cx && rec.chunkZ() == cz), 400,
                "the unknown-identity column was never dispatched to the consumer — the "
                        + "fallback ladder is failing the whole column instead of resolving");
        var delivered = fallbackRecorder.snapshot().stream()
                .filter(rec -> rec.chunkX() == cx && rec.chunkZ() == cz)
                .reduce((a, b) -> b).orElseThrow();
        // The lying lookup turns EVERY payload palette entry (air included) into the
        // unknown identity, so every payload-carried section decodes wall-to-wall
        // stone. The resync air-fill legitimately ADDS pure-air sections around them
        // (this position was served before), so the pin is: every section is all-stone
        // OR all-air at the sample point, at least one stone. Any OTHER state means
        // the resolver was bypassed (original blocks) or mis-coerced.
        int stoneSections = 0;
        for (var section : delivered.data().sections()) {
            var state = section.section().getBlockState(7, 7, 7);
            if (state.is(net.minecraft.world.level.block.Blocks.STONE)) {
                stoneSections++;
            } else if (!state.isAir()) {
                throw new AssertionError("unknown-identity voxel decoded to " + state
                        + " at sectionY " + section.sectionY() + " — neither the "
                        + "unknownBlockFallback default (stone) nor a fill-pass air");
            }
        }
        if (stoneSections == 0) {
            throw new AssertionError("no section decoded to the fallback state — "
                    + "nothing proved the terminal rung reached the consumer");
        }
    }

    /** Walks up from the client gametest working dir to the fabric module root (the
     *  corpusDir pattern — the fixture lives in the TEST resources, a different source
     *  set from this gametest). */
    private static java.nio.file.Path locateFabricModule() {
        var dir = java.nio.file.Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && dir != null; depth++, dir = dir.getParent()) {
            if (java.nio.file.Files.isDirectory(dir.resolve("src/test/java/dev/vox/lss"))) {
                return dir;
            }
            var nested = dir.resolve("fabric");
            if (java.nio.file.Files.isDirectory(nested.resolve("src/test/java/dev/vox/lss"))) {
                return nested;
            }
        }
        throw new IllegalStateException("cannot locate the fabric module from "
                + java.nio.file.Path.of("").toAbsolutePath());
    }

    /**
     * Every decoded column of the consistent-settings test world (classic flat preset) must
     * carry its blocks in the one content section at sectionY −4 — bedrock at local Y 0, dirt
     * at 1–2, grass at 3, air above — with any extra decoded sections air-only, and at least
     * one column must carry sky light (15 in open air, 0 inside the buried dirt). A framing bug
     * in the ClientColumnProcessor decode (sectionY sign, light flag order, palette
     * misalignment) garbles one of these exact expectations. (A heavily starved CI runner can
     * serialize a column before its all-air sections are culled, so the section count itself is
     * not asserted — only that exactly one section holds content and the rest are air.)
     */
    private static void assertDecodedFlatWorldContent(List<RecordingColumnConsumer.RecordedColumn> records) {
        if (records.isEmpty()) {
            throw new AssertionError("No recorded columns to assert content on");
        }

        int skyLitColumns = 0;
        for (var rec : records) {
            String at = "decoded column (" + rec.chunkX() + ", " + rec.chunkZ() + ")";

            if (!rec.dimension().equals(Level.OVERWORLD)) {
                throw new AssertionError(at + ": expected overworld dimension, got " + rec.dimension());
            }
            // Documented threading contract of VoxelColumnConsumer: dispatch happens on the
            // dedicated LSS-ColumnProcessor thread, never the render thread.
            if (!"LSS-ColumnProcessor".equals(rec.threadName())) {
                throw new AssertionError(at + ": consumer must be called on the LSS-ColumnProcessor thread, got "
                        + rec.threadName());
            }
            if (rec.data().columnTimestamp() <= 0) {
                throw new AssertionError(at + ": served columns must carry a real (positive) timestamp, got "
                        + rec.data().columnTimestamp());
            }

            // The superflat column's blocks live in the one content section at sectionY -4 (a
            // signed/unsigned sectionY read bug yields 252, so nothing matches -4 and this fails).
            // A heavily starved CI runner can serialize a column during spawn-prep before its
            // all-air sections are culled, so the decoded column may carry extra sections; assert
            // the content on the one that holds it and require the extras to be air-only below.
            var sections = rec.data().sections();
            var contentSections = java.util.Arrays.stream(sections)
                    .filter(s -> s.sectionY() == -4)
                    .toList();
            if (contentSections.size() != 1) {
                throw new AssertionError(at + ": expected exactly one content section at sectionY -4, got "
                        + contentSections.size() + " (of " + sections.length + " decoded sections)");
            }
            var sectionData = contentSections.get(0);

            var section = sectionData.section();
            // Superflat layers: bedrock -64, dirt -63/-62, grass -61 -> local Y 0..3.
            for (int[] xz : new int[][] {{0, 0}, {15, 15}, {8, 3}}) {
                int x = xz[0];
                int z = xz[1];
                assertBlock(section, at, x, 0, z, Blocks.BEDROCK);
                assertBlock(section, at, x, 1, z, Blocks.DIRT);
                assertBlock(section, at, x, 2, z, Blocks.DIRT);
                assertBlock(section, at, x, 3, z, Blocks.GRASS_BLOCK);
                if (!section.getBlockState(x, 4, z).isAir()) {
                    throw new AssertionError(at + ": expected air above the grass surface at local ("
                            + x + ", 4, " + z + "), got " + section.getBlockState(x, 4, z));
                }
            }
            if (!section.getNoiseBiome(0, 0, 0).is(Biomes.PLAINS)) {
                throw new AssertionError(at + ": expected plains biome, got " + section.getNoiseBiome(0, 0, 0));
            }

            var skyLight = sectionData.skyLight();
            if (skyLight != null) {
                skyLitColumns++;
                if (skyLight.get(8, 4, 8) != 15) {
                    throw new AssertionError(at + ": open air above the surface must have full sky light, got "
                            + skyLight.get(8, 4, 8));
                }
                if (skyLight.get(8, 2, 8) != 0) {
                    throw new AssertionError(at + ": buried dirt must have zero sky light, got "
                            + skyLight.get(8, 2, 8));
                }
            }

            // Any extra sections (the un-culled serialization-window case) must be genuinely
            // air-only — a decode bug that fabricated non-air content in them must still fail.
            for (var extra : sections) {
                if (extra.sectionY() == -4) {
                    continue;
                }
                var es = extra.section();
                for (int[] xz : new int[][] {{0, 0}, {15, 15}, {8, 3}}) {
                    for (int y : new int[] {0, 4, 8, 15}) {
                        if (!es.getBlockState(xz[0], y, xz[1]).isAir()) {
                            throw new AssertionError(at + ": extra decoded section at sectionY "
                                    + extra.sectionY() + " must be air-only, found "
                                    + es.getBlockState(xz[0], y, xz[1]) + " at local ("
                                    + xz[0] + ", " + y + ", " + xz[1] + ")");
                        }
                    }
                }
            }
        }

        // In-memory probes of the lit spawn area always include sky light; columns read back
        // from disk may legitimately omit it, hence at-least-one instead of all.
        if (skyLitColumns == 0) {
            throw new AssertionError("No decoded column carried sky light data across "
                    + records.size() + " columns (light encode/decode broken)");
        }
    }

    private static void assertBlock(LevelChunkSection section, String at, int x, int y, int z, Block expected) {
        var state = section.getBlockState(x, y, z);
        if (!state.is(expected)) {
            throw new AssertionError(at + ": block at local (" + x + ", " + y + ", " + z
                    + ") in section -4 should be " + expected + ", got " + state);
        }
    }

    /**
     * LAN-publish activation path. The gametest JVM forces {@code lss.test.integratedServer};
     * clear it so this world exercises the real singleplayer gating, and restore it afterwards
     * for anything else that runs in this JVM.
     */
    private static void runLanPublishActivationTest(ClientGameTestContext context) {
        String override = System.clearProperty("lss.test.integratedServer");
        try (TestSingleplayerContext _ = context.worldBuilder().create()) {
            // A (buggy) join-time handshake + session config roundtrip would land well within
            // this window, so the negative assertions below are meaningful.
            context.waitTicks(40);

            if (LSSServerNetworking.getRequestService() != null) {
                throw new AssertionError("Request service must stay deferred on a private integrated server");
            }
            if (LSSClientNetworking.hasReceivedSessionConfig()) {
                throw new AssertionError("Client must not handshake on a private integrated server");
            }
            if (LSSClientNetworking.getRequestManager() != null) {
                throw new AssertionError("No LodRequestManager may exist before LAN publish");
            }

            // Publish from the client thread, exactly like ShareToLanScreen does. 26.1-line
            // flavor: this line has the single (GameType, boolean, int) overload — the 26.2
            // MultiplayerScope split (and its M2 wrong-overload trap) does not exist here.
            boolean published = context.computeOnClient(client ->
                    client.getSingleplayerServer().publishServer(
                            GameType.SURVIVAL, false, HttpUtil.getAvailablePort()));
            if (!published) {
                throw new AssertionError("publishServer must succeed (LAN port bind failed?)");
            }

            // The hook fires at publishServer RETURN but defers construction to the server
            // thread (startServiceForLan's hop — the publish call runs on the render thread),
            // so the service appears within a tick, not synchronously.
            waitForOrFail(context, () -> LSSServerNetworking.getRequestService() != null, 100,
                    "LAN publish must start the request processing service");

            // Host handshake: triggerHostHandshake -> C2S handshake -> SessionConfig -> manager.
            waitForOrFail(context, LSSClientNetworking::isServerEnabled, 400,
                    "host handshake after LAN publish never completed (no SessionConfig applied)");
            if (LSSClientNetworking.getRequestManager() == null) {
                throw new AssertionError("Host handshake must create the LodRequestManager");
            }
            if (LSSClientNetworking.getServerLodDistance() != LSSServerConfig.CONFIG.lodDistanceChunks) {
                throw new AssertionError("LAN session config must carry the configured LOD distance: expected "
                        + LSSServerConfig.CONFIG.lodDistanceChunks + ", got "
                        + LSSClientNetworking.getServerLodDistance());
            }
            // Registration happens server-side before the SessionConfig the client just applied.
            int registered = LSSServerNetworking.getRequestService().getPlayers().size();
            if (registered != 1) {
                throw new AssertionError("LAN host must be registered for request processing, got "
                        + registered + " registered players");
            }
        } finally {
            if (override != null) {
                System.setProperty("lss.test.integratedServer", override);
            }
        }
    }

    /** Framework waitFor throws a generic timeout message; this names the stuck condition. */
    private static void waitForOrFail(ClientGameTestContext context, BooleanSupplier condition,
                                      int timeoutTicks, String failureMessage) {
        for (int waited = 0; waited < timeoutTicks && !condition.getAsBoolean(); waited += 2) {
            context.waitTicks(2);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError(failureMessage);
        }
    }

    /** Throws on the first column it ever receives; the re-delivery proves the implicit path. */
    private static final class FirstColumnThrowingConsumer implements VoxelColumnConsumer {
        private static final long NONE = Long.MIN_VALUE;

        private final java.util.concurrent.atomic.AtomicLong thrownPacked =
                new java.util.concurrent.atomic.AtomicLong(NONE);
        private volatile boolean reDelivered;

        @Override
        public void onVoxelColumnReceived(ClientLevel level, ResourceKey<Level> dimension,
                                          int chunkX, int chunkZ, VoxelColumnData columnData) {
            long packed = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            if (this.thrownPacked.compareAndSet(NONE, packed)) {
                throw new IllegalStateException("test consumer rejecting its first column by throwing");
            }
            if (packed == this.thrownPacked.get()) {
                this.reDelivered = true;
            }
        }

        boolean sawReDelivery() {
            return this.reDelivered;
        }
    }

    /**
     * Rejects the first column it ever receives (reports it via the public ingest-failure
     * API) and records whether that position is later delivered again. One-shot across the
     * whole client boot: the LAN world reuses the registered consumer list, so the rejection
     * must not re-trigger there.
     */
    private static final class FirstColumnRejectingConsumer implements VoxelColumnConsumer {
        private static final long NONE = Long.MIN_VALUE;

        private final java.util.concurrent.atomic.AtomicLong rejectedPacked =
                new java.util.concurrent.atomic.AtomicLong(NONE);
        private volatile boolean reDelivered;

        @Override
        public void onVoxelColumnReceived(ClientLevel level, ResourceKey<Level> dimension,
                                          int chunkX, int chunkZ, VoxelColumnData columnData) {
            long packed = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            if (this.rejectedPacked.compareAndSet(NONE, packed)) {
                LSSApi.reportIngestFailure(dimension, chunkX, chunkZ);
                return;
            }
            if (packed == this.rejectedPacked.get()) {
                this.reDelivered = true;
            }
        }

        boolean sawRejection() {
            return this.rejectedPacked.get() != NONE;
        }

        boolean sawReDelivery() {
            return this.reDelivered;
        }

        String describeRejected() {
            long packed = this.rejectedPacked.get();
            return packed == NONE ? "(none)" : "(" + (int) (packed >> 32) + ", " + (int) packed + ")";
        }
    }

    /**
     * Records dispatched columns for content assertions. Written on the LSS-ColumnProcessor
     * thread, read from the gametest thread (the concurrent queue safe-publishes the decoded
     * sections). Bounded: superflat content is uniform, so the first columns prove decoding.
     */
    private static final class RecordingColumnConsumer implements VoxelColumnConsumer {
        record RecordedColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                              VoxelColumnData data, String threadName) {}

        private static final int MAX_RECORDED = 256;

        private final ConcurrentLinkedQueue<RecordedColumn> records = new ConcurrentLinkedQueue<>();
        private final AtomicInteger received = new AtomicInteger();

        @Override
        public void onVoxelColumnReceived(ClientLevel level, ResourceKey<Level> dimension,
                                          int chunkX, int chunkZ, VoxelColumnData columnData) {
            if (this.received.getAndIncrement() < MAX_RECORDED) {
                this.records.add(new RecordedColumn(dimension, chunkX, chunkZ, columnData,
                        Thread.currentThread().getName()));
            }
        }

        int receivedCount() {
            return this.received.get();
        }

        List<RecordedColumn> snapshot() {
            return List.copyOf(this.records);
        }
    }
}
