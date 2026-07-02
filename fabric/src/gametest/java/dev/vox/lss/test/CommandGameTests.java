package dev.vox.lss.test;

import dev.vox.lss.benchmark.BenchmarkMetricsExporter;
import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.networking.server.RequestProcessingService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Fabric /lsslod command surface executed through the real Brigadier dispatcher, plus
 * the exporter-vs-formatter counter consistency check. Tests that need the LIVE service to
 * expose specific state (a registered player, a generation-less service) confine the
 * mutation — registration or a {@code swapServiceForTesting} window — to one synchronous
 * callback: gametest callbacks own the main thread, so neither another test's callback nor
 * the live tick can observe the window, and {@code LSSGameTests}' live-service invariants
 * (players empty, bandwidth zero) hold before and after.
 */
public class CommandGameTests {

    private static final int DIAG_CHUNK_OFFSET = 240;

    /** Deprecated upstream without a replacement; it is the only factory that places a real
     *  ServerPlayer (player list entry + embedded-channel connection) inside a gametest. */
    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer placeMockServerPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    /** CommandSource that records every line the command sends (success and failure). */
    private static CommandSource recorder(List<String> lines) {
        return new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                lines.add(component.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
    }

    private static boolean anyLineContains(List<String> lines, String needle) {
        for (var line : lines) {
            if (line.contains(needle)) return true;
        }
        return false;
    }

    /**
     * CG-020: the {@code .requires(gamemaster)} gate on /lsslod. For a permission-stripped
     * source the root literal must be invisible — the parse consumes no nodes and execution
     * surfaces no LSS output — while a full-permission source parses it cleanly. (Brigadier
     * skips unusable literals silently, so "no parse exceptions" would NOT discriminate;
     * the consumed-node check does.)
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void lsslodRequiresGamemasterPermission(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var commands = server.getCommands();
        var lines = new ArrayList<String>();
        var stripped = new CommandSourceStack(recorder(lines), Vec3.ZERO, Vec2.ZERO, level,
                PermissionSet.NO_PERMISSIONS, "lss-test", Component.literal("lss-test"),
                server, null);

        var strippedParse = commands.getDispatcher().parse("lsslod diag", stripped);
        helper.assertTrue(strippedParse.getContext().getNodes().isEmpty(),
                "a non-gamemaster source must not see the lsslod root (deleting the requires "
                        + "gate makes this parse consume nodes)");

        var fullParse = commands.getDispatcher().parse("lsslod diag",
                server.createCommandSourceStack());
        helper.assertTrue(!fullParse.getContext().getNodes().isEmpty()
                        && fullParse.getExceptions().isEmpty(),
                "control: a full-permission source must parse lsslod cleanly");

        commands.performPrefixedCommand(stripped, "lsslod diag");
        helper.assertTrue(!anyLineContains(lines, "LSS"),
                "executing as a stripped source must never reach an LSS handler (diag header "
                        + "or not-active line), got: " + lines);
        helper.succeed();
    }

    /**
     * CG-022: /lsslod stats executed through the dispatcher against the LIVE service with a
     * registered player carrying known counters — the command → service → shared formatter
     * wiring. Registration, execution, and cleanup share one synchronous callback so the
     * live players map is empty again before any other test's callback can run.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void lsslodStatsRendersRegisteredPlayerLineThroughDispatcher(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var liveService = LSSServerNetworking.getRequestService();
        helper.assertTrue(liveService != null, "live service required (/lsslod reads it)");
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var lines = new ArrayList<String>();
        try {
            var state = liveService.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            int pcx = mock.getBlockX() >> 4;
            int pcz = mock.getBlockZ() >> 4;
            state.addRequest(PositionUtil.packPosition(pcx + 1, pcz), -1L);
            state.addRequest(PositionUtil.packPosition(pcx + 2, pcz), -1L);

            var source = server.createCommandSourceStack().withSource(recorder(lines));
            server.getCommands().performPrefixedCommand(source, "lsslod stats");

            helper.assertTrue(anyLineContains(lines, "=== LSS LOD Request Stats ==="),
                    "stats must render the header for a populated service, got: " + lines);
            String expected = DiagnosticsFormatter.formatStatsLine(state);
            helper.assertTrue(expected.contains("requests=2"),
                    "premise: the seeded state must read back its two requests");
            helper.assertTrue(lines.contains(expected),
                    "the command must render the registered player through the shared "
                            + "formatter, expected line: " + expected + " got: " + lines);
        } finally {
            liveService.removePlayer(uuid);
            playerList.remove(mock);
        }
        helper.assertTrue(liveService.getPlayers().isEmpty(),
                "cleanup premise: the live service must leave this test player-free");
        helper.succeed();
    }

    /**
     * CG-027 (Fabric call-site leg): /lsslod diag against a service whose generation
     * service is null (enableChunkGeneration=false). The command call site passes
     * {@code genService != null ? getDiagnostics() : null} — removing that guard NPEs here
     * and the diag lines never render. The generation-less service is swapped in for one
     * synchronous execution window and restored.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void lsslodDiagRendersGenerationDisabledWithoutNpe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var config = LSSServerConfig.CONFIG;
        boolean prevGenEnabled = config.enableChunkGeneration;
        RequestProcessingService genless;
        try {
            config.enableChunkGeneration = false;
            genless = new RequestProcessingService(server);
        } finally {
            config.enableChunkGeneration = prevGenEnabled;
        }
        var lines = new ArrayList<String>();
        try {
            helper.assertTrue(genless.getGenerationService() == null,
                    "premise: enableChunkGeneration=false must leave the generation service null");
            var source = server.createCommandSourceStack().withSource(recorder(lines));
            var previous = LSSServerNetworking.swapServiceForTesting(genless);
            try {
                server.getCommands().performPrefixedCommand(source, "lsslod diag");
            } finally {
                LSSServerNetworking.swapServiceForTesting(previous);
            }
        } finally {
            genless.shutdown();
        }
        helper.assertTrue(anyLineContains(lines, "=== LSS LOD Diagnostics ==="),
                "diag must render against a generation-less service (an NPE at the call site "
                        + "surfaces as a missing header), got: " + lines);
        helper.assertTrue(anyLineContains(lines, "Generation: disabled"),
                "the null generation service must render as 'Generation: disabled', got: " + lines);
        helper.succeed();
    }

    /**
     * HD-038: after a real served workload, the soak exporter and the /lsslod diag
     * formatter must agree on the counters they both report — columns_sent vs the
     * per-player bandwidth sum (two INDEPENDENT counting sites: TickDiagnostics at the
     * flush vs PlayerBandwidthTracker), up_to_date and in_memory vs the shared processing
     * diagnostics, and requests. The workload makes the values pairwise distinct where the
     * keys could transpose (up_to_date=1 vs in_memory=2 vs requests=3).
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 600)
    public void serverMetricsExporterAgreesWithDiagFormatterCounters(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        var chunkSource = level.getChunkSource();
        var pos1 = new ChunkPos(pcx - DIAG_CHUNK_OFFSET, pcz);
        var pos2 = new ChunkPos(pcx - DIAG_CHUNK_OFFSET, pcz + 1);
        long packed1 = PositionUtil.packPosition(pos1.x, pos1.z);
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, pos1, 0);
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, pos2, 0);
        level.getChunk(pos1.x, pos1.z);
        level.getChunk(pos2.x, pos2.z);

        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        state.addRequest(packed1, -1L);
        state.addRequest(PositionUtil.packPosition(pos2.x, pos2.z), -1L);
        var step = new AtomicInteger();

        helper.succeedWhen(() -> {
            if (step.get() == 0) {
                service.tick();
                helper.assertTrue(state.getTotalSectionsSent() == 2,
                        "waiting for both probe serves to flush (columns_sent=2)");
                // Third request: done-bit cleared but the probe-serve timestamp stamp kept,
                // so the ts>0 re-ask resolves through the timestamp ladder (up_to_date=1).
                service.getOffThreadProcessor().clearDiskReadDone(uuid, new long[]{packed1});
                state.addRequest(packed1, LSSConstants.epochSeconds() + 10_000);
                step.set(1);
                helper.assertTrue(false, "served workload staged, awaiting the up-to-date leg");
            }
            if (step.get() == 1) {
                service.tick();
                helper.assertTrue(
                        service.getOffThreadProcessor().getDiagnostics().getTotalUpToDate() == 1,
                        "waiting for the timestamp-ladder up-to-date resolution");
                step.set(2);
                helper.assertTrue(false, "workload complete, comparing exporter and formatter");
            }

            Map<String, Object> metrics;
            var previous = LSSServerNetworking.swapServiceForTesting(service);
            try {
                metrics = BenchmarkMetricsExporter.buildServerMetrics();
            } finally {
                LSSServerNetworking.swapServiceForTesting(previous);
            }
            helper.assertTrue(metrics != null, "exporter must produce metrics for a live service");

            var config = LSSServerConfig.CONFIG;
            var genService = service.getGenerationService();
            var data = DiagnosticsFormatter.collectDiagData(
                    config.enabled, config.lodDistanceChunks,
                    config.bytesPerSecondLimitPerPlayer, config.bytesPerSecondLimitGlobal,
                    config.sendQueueLimitPerPlayer,
                    service.getUptimeSeconds(), service.getTickDiagnostics(),
                    service.getWindowBandwidthRate(),
                    service.getOffThreadProcessor().getDiagnostics(), service.getDiskReader(),
                    service.getBandwidthLimiter(),
                    genService != null ? genService.getDiagnostics() : null,
                    service.getPlayers().values());

            @SuppressWarnings("unchecked")
            var serviceMap = (Map<String, Object>) metrics.get("service");
            long columnsSent = ((Number) serviceMap.get("columns_sent")).longValue();
            long upToDate = ((Number) serviceMap.get("up_to_date")).longValue();
            long inMemory = ((Number) serviceMap.get("in_memory")).longValue();
            long requests = ((Number) serviceMap.get("requests_received")).longValue();

            helper.assertTrue(columnsSent == 2 && data.totalSent() == 2,
                    "columns_sent must agree across BOTH independent counting sites "
                            + "(flush diagnostics vs per-player bandwidth sum): exporter="
                            + columnsSent + " formatter=" + data.totalSent());
            helper.assertTrue(upToDate == 1 && data.cumUtd() == 1,
                    "up_to_date must agree: exporter=" + upToDate + " formatter=" + data.cumUtd());
            helper.assertTrue(inMemory == 2 && data.cumInMem() == 2,
                    "in_memory must agree: exporter=" + inMemory + " formatter=" + data.cumInMem());
            helper.assertTrue(requests == 3 && state.getTotalRequestsReceived() == 3,
                    "requests must agree with the player's received total: exporter="
                            + requests + " state=" + state.getTotalRequestsReceived());
            @SuppressWarnings("unchecked")
            var players = (List<Map<String, Object>>) metrics.get("players");
            helper.assertTrue(players.size() == 1
                            && ((Number) players.get(0).get("requests")).longValue() == 3,
                    "the exporter's per-player row must carry the same request total");

            chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, pos1, 0);
            chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, pos2, 0);
            service.shutdown();
            playerList.remove(mock);
        });
    }
}
