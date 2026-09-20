package dev.vox.lss.test;

import static dev.vox.lss.test.TestPositions.chunkAt;
import static dev.vox.lss.test.TestPositions.holdChunk;
import static dev.vox.lss.test.TestPositions.releaseChunk;

import dev.vox.lss.benchmark.BenchmarkMetricsExporter;
import dev.vox.lss.common.DiagnosticsFormatter;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.networking.server.RequestProcessingService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
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
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void lsslodRequiresGamemasterPermission(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var commands = server.getCommands();
        var lines = new ArrayList<String>();
        var stripped = new CommandSourceStack(recorder(lines), Vec3.ZERO, Vec2.ZERO, level,
                0, "lss-test", Component.literal("lss-test"),
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
     * C4 (review-fixes round): the /lsslod store tree dispatched through the real
     * Brigadier dispatcher — Paper's twin is unit-pinned but the Fabric literals had
     * zero coverage on any tier, so a dropped literal shipped advertised-but-missing
     * (the release notes name these as the "LODs look stale" remediation lever). The
     * gametest server runs lodStore=off, so the pinned answers are the documented
     * off/unavailable rungs.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void lsslodStoreVerbsDispatchThroughTheRealTree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var commands = server.getCommands();
        var lines = new ArrayList<String>();
        var source = new CommandSourceStack(recorder(lines), Vec3.ZERO, Vec2.ZERO, level,
                4, "lss-test", Component.literal("lss-test"),
                server, null);

        commands.performPrefixedCommand(source, "lsslod store status");
        helper.assertTrue(anyLineContains(lines, "LOD store: off/unavailable"),
                "store status with lodStore=off must answer off/unavailable, got: " + lines);

        lines.clear();
        commands.performPrefixedCommand(source, "lsslod store backfill status");
        helper.assertTrue(anyLineContains(lines, "Store backfill unavailable"),
                "backfill status without a SQLite store must answer unavailable, got: " + lines);

        lines.clear();
        commands.performPrefixedCommand(source, "lsslod store invalidate all");
        helper.assertTrue(anyLineContains(lines, "LOD store not active"),
                "invalidate all with no store must answer not-active, got: " + lines);
        helper.succeed();
    }

    /** v0.11.0 stage C: /lsslod help + the bare root through the real tree. */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void lsslodHelpAndBareRootDispatchTheSharedHelp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var commands = server.getCommands();
        var lines = new ArrayList<String>();
        var source = new CommandSourceStack(recorder(lines), Vec3.ZERO, Vec2.ZERO, level,
                4, "lss-test", Component.literal("lss-test"),
                server, null);

        commands.performPrefixedCommand(source, "lsslod help");
        helper.assertTrue(anyLineContains(lines, "set <key> <value>"),
                "help must document the set verb, got: " + lines);
        helper.assertTrue(anyLineContains(lines, "store backfill start|stop|status"),
                "Fabric help includes the backfill verbs, got: " + lines);

        lines.clear();
        commands.performPrefixedCommand(source, "lsslod");
        helper.assertTrue(anyLineContains(lines, "set <key> <value>"),
                "the bare root must render the same help (was a parse error), got: " + lines);
        helper.succeed();
    }

    /**
     * v0.11.0 stage C: /lsslod set through the real tree — the listing, a real apply
     * (mutate-and-restore per the ServiceLifecycleGameTests precedent), the 0-semantics
     * pin, and the parse-error reply. NOTE the real handler calls config.save(), which
     * writes the gametest run dir's staged config — restored in finally AND re-saved so
     * the file-side effect cannot leak into later tests (fabric/build.gradle's doFirst
     * re-stages each run regardless; the restore keeps the in-memory config honest).
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void lsslodSetAppliesClampsAndRestores(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var commands = server.getCommands();
        var lines = new ArrayList<String>();
        var source = new CommandSourceStack(recorder(lines), Vec3.ZERO, Vec2.ZERO, level,
                4, "lss-test", Component.literal("lss-test"),
                server, null);
        var config = dev.vox.lss.config.LSSServerConfig.CONFIG;
        int savedDistance = config.lodDistanceChunks;
        int savedDirty = config.dirtyBroadcastIntervalSeconds;
        try {
            commands.performPrefixedCommand(source, "lsslod set");
            helper.assertTrue(anyLineContains(lines, "lodDistanceChunks = " + savedDistance),
                    "the listing shows current values, got: " + lines);

            lines.clear();
            commands.performPrefixedCommand(source, "lsslod set lodDistanceChunks 96");
            helper.assertTrue(anyLineContains(lines, "lodDistanceChunks = 96"),
                    "the apply replies with the effective value, got: " + lines);
            helper.assertTrue(config.lodDistanceChunks == 96, "the mutation applied");

            lines.clear();
            commands.performPrefixedCommand(source, "lsslod set dirtyBroadcastIntervalSeconds 0");
            helper.assertTrue(config.dirtyBroadcastIntervalSeconds == 0,
                    "0 = off must survive the real command surface (R-2)");

            lines.clear();
            commands.performPrefixedCommand(source, "lsslod set lodDistanceChunks many");
            helper.assertTrue(anyLineContains(lines, "not an integer"),
                    "a parse error replies without mutating, got: " + lines);
            helper.assertTrue(config.lodDistanceChunks == 96, "parse error assigned nothing");
        } finally {
            config.lodDistanceChunks = savedDistance;
            config.dirtyBroadcastIntervalSeconds = savedDirty;
            config.validate();
            config.save();
        }
        helper.succeed();
    }

    /**
     * v0.11.0 stage C (review F9): the SessionConfig re-push COMPOSED through the real
     * command — `set lodDistanceChunks` dispatched with a registered CURRENT-dialect
     * player must mutate the config AND report the push in its reply (the wire receipt;
     * the client-side apply is Tier 3 territory). The count assert tolerates >=1
     * because other multi-tick gametests can hold registered players concurrently —
     * "re-pushed to 0" (our mock skipped) is the failure this pins.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void setLodDistanceRepushesSessionConfigToRegisteredPlayers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var commands = server.getCommands();
        var service = dev.vox.lss.networking.server.LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "service active on the gametest server");
        var player = placeMockServerPlayer(helper);
        var config = dev.vox.lss.config.LSSServerConfig.CONFIG;
        int savedDistance = config.lodDistanceChunks;
        var lines = new ArrayList<String>();
        var source = new CommandSourceStack(recorder(lines), Vec3.ZERO, Vec2.ZERO, level,
                4, "lss-test", Component.literal("lss-test"),
                server, null);
        try {
            service.registerPlayer(player, dev.vox.lss.common.LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            int target = savedDistance == 96 ? 128 : 96; // must differ or the re-push is skipped
            commands.performPrefixedCommand(source, "lsslod set lodDistanceChunks " + target);
            helper.assertTrue(config.lodDistanceChunks == target,
                    "the real command path applied the distance");
            helper.assertTrue(anyLineContains(lines, "re-pushed to "),
                    "the reply must carry the re-push receipt, got: " + lines);
            helper.assertTrue(!anyLineContains(lines, "re-pushed to 0 "),
                    "the registered CURRENT-dialect mock must be counted, got: " + lines);
        } finally {
            service.removePlayer(player.getUUID());
            config.lodDistanceChunks = savedDistance;
            config.validate();
            config.save();
        }
        helper.succeed();
    }

    /**
     * CG-022: /lsslod stats executed through the dispatcher against the LIVE service with a
     * registered player carrying known counters — the command → service → shared formatter
     * wiring. Registration, execution, and cleanup share one synchronous callback so the
     * live players map is empty again before any other test's callback can run.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
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
            GameTestSeeding.seedRequests(state,
                    new long[]{PositionUtil.packPosition(pcx + 1, pcz),
                            PositionUtil.packPosition(pcx + 2, pcz)},
                    new long[]{-1L, -1L});

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
    @GameTest(template = "fabric-gametest-api-v1:empty")
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
        helper.assertTrue(anyLineContains(lines, "Xray: active="),
                "the diag ladder must carry the always-present xray masking line "
                        + "(pins LSSServerCommands' withXrayLine attach), got: " + lines);
        helper.assertTrue(anyLineContains(lines, "Yield: armed=true"),
                "the Yield line must be PRESENT and armed on the default config (the C-4"
                        + " pin inverted with the v0.11.0 default flip, user decision"
                        + " 2026-08-13: lodYieldsToVanillaTransport now defaults TRUE, so"
                        + " a wrong armed argument would HIDE the true arming receipt on"
                        + " every default install), got: " + lines);
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
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 600)
    public void serverMetricsExporterAgreesWithDiagFormatterCounters(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        var chunkSource = level.getChunkSource();
        var pos1 = chunkAt(pcx - DIAG_CHUNK_OFFSET, pcz);
        var pos2 = chunkAt(pcx - DIAG_CHUNK_OFFSET, pcz + 1);
        long packed1 = PositionUtil.packPosition(pos1.x(), pos1.z());
        holdChunk(chunkSource, pos1);
        holdChunk(chunkSource, pos2);
        level.getChunk(pos1.x(), pos1.z());
        level.getChunk(pos2.x(), pos2.z());

        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        GameTestSeeding.seedRequests(state,
                new long[]{packed1, PositionUtil.packPosition(pos2.x(), pos2.z())},
                new long[]{-1L, -1L});
        var step = new AtomicInteger();

        helper.succeedWhen(() -> {
            if (step.get() == 0) {
                service.tick();
                helper.assertTrue(state.getTotalSectionsSent() == 2,
                        "waiting for both probe serves to flush (columns_sent=2)");
                // Third request: done-bit cleared but the probe-serve timestamp stamp kept,
                // so the ts>0 re-ask resolves through the timestamp ladder (up_to_date=1).
                service.getOffThreadProcessor().clearDiskReadDone(uuid, new long[]{packed1});
                GameTestSeeding.seedRequest(state, packed1, LSSConstants.epochSeconds() + 10_000);
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
                    config.bytesPerSecondPerPlayer(), config.bytesPerSecondGlobal(),
                    config.sendQueueLimitPerPlayer,
                    service.getUptimeSeconds(), service.getTickDiagnostics(),
                    service.getWindowBandwidthRate(),
                    service.getTickDiag().getTotalSectionsSent(),
                    service.getTickDiag().getTotalBytesSent(),
                    service.getTickDiag().getTotalWireBytesSent(),
                    service.getOffThreadProcessor().getDiagnostics(), service.getDiskReader(),
                    service.getBandwidthLimiter(),
                    genService != null ? genService.getDiagnostics() : null,
                    dev.vox.lss.common.store.LodStoreMode.normalize(config.lodStore)
                        == dev.vox.lss.common.store.LodStoreMode.OFF
                        ? dev.vox.lss.common.store.LodStoreMode.OFF
                        : (service.getLodStore() != null ? service.getLodStore().mode() : null),
                    service.getOffThreadProcessor().getStoreDiagnostics(),
                    service.getPlayers().values());

            @SuppressWarnings("unchecked")
            var serviceMap = (Map<String, Object>) metrics.get("service");
            long columnsSent = ((Number) serviceMap.get("columns_sent")).longValue();
            long upToDate = ((Number) serviceMap.get("up_to_date")).longValue();
            long inMemory = ((Number) serviceMap.get("in_memory")).longValue();
            long requests = ((Number) serviceMap.get("requests_received")).longValue();

            // Post-R2-9 the exporter and the formatter's Throughput line BOTH read the
            // service-scoped TickDiagnostics total (deliberate — the per-state sum dies on
            // dimension change), so exporter==formatter holds by construction. The genuinely
            // independent counting site is the per-player state's session counter — same
            // ground truth here (one player, no dimension change).
            helper.assertTrue(columnsSent == 2 && data.totalSent() == 2
                            && state.getTotalSectionsSent() == 2,
                    "columns_sent must match ground truth at the service-scoped total "
                            + "(exporter=" + columnsSent + " formatter=" + data.totalSent()
                            + ") and at the independent per-state session counter (state="
                            + state.getTotalSectionsSent() + ")");
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

            releaseChunk(chunkSource, pos1);
            releaseChunk(chunkSource, pos2);
            service.shutdown();
            playerList.remove(mock);
        });
    }

    /** The actual per-line scenario files, executed and read back through Minecraft's
     * registered command tree. Restore within this callback, before any other test ticks. */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void soakScenarioGamerulesExecuteAndReadBack(GameTestHelper helper) throws Exception {
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isDirectory(root.resolve("scripts/soak-scenarios"))) {
            root = root.getParent();
        }
        helper.assertTrue(root != null, "could not locate actual scenario JSON files");
        var commandsToCheck = new java.util.TreeSet<String>();
        try (var paths = java.nio.file.Files.list(root.resolve("scripts/soak-scenarios"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
                if (!json.has("steps")) continue;
                for (var step : json.getAsJsonArray("steps")) {
                    String command = step.getAsJsonObject().get("cmd").getAsString();
                    if (command.startsWith("gamerule ")) commandsToCheck.add(command);
                }
            }
        }
        helper.assertTrue(commandsToCheck.size() >= 5, "scenario setup command inventory must not be empty");
        var server = helper.getLevel().getServer();
        var commands = server.getCommands();
        var source = server.createCommandSourceStack();
        for (String command : commandsToCheck) {
            int split = command.lastIndexOf(' ');
            String query = command.substring(0, split);
            String value = command.substring(split + 1);
            var before = dev.vox.lss.benchmark.SoakCommandExecutor.executeForResult(commands, source, query);
            helper.assertTrue(before.success(), "real rule query must succeed: " + query);
            try {
                helper.assertTrue(dev.vox.lss.benchmark.SoakCommandExecutor.setGamerule(commands, source, command),
                        "scenario setter and exact readback must succeed (zero is valid): " + command);
            } finally {
                String restore = value.equals("true") || value.equals("false")
                        ? Boolean.toString(before.value() != 0) : Integer.toString(before.value());
                helper.assertTrue(dev.vox.lss.benchmark.SoakCommandExecutor.setGamerule(commands, source, query + " " + restore),
                        "must restore original gamerule: " + query);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void soakCommandsRejectInvalidSyntaxAndMissingCallbacks(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var commands = server.getCommands();
        var source = server.createCommandSourceStack();
        for (String bad : java.util.List.of("gamerule lss_nonexistent_rule 0", "gamerule", "execute in")) {
            boolean rejected = false;
            try {
                dev.vox.lss.benchmark.SoakCommandExecutor.dispatch(commands, source, bad);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "unknown/incomplete command must fail validation: " + bad);
        }
        // Real command context deliberately defers the inner queue. Our tick-only helper
        // must not report semantic success before the callback actually runs.
        dev.vox.lss.benchmark.SoakCommandExecutor.Result[] nested = {null};
        net.minecraft.commands.Commands.executeCommandInContext(source, context -> {
            nested[0] = dev.vox.lss.benchmark.SoakCommandExecutor.executeForResult(commands, source, "list");
        });
        helper.assertTrue(nested[0] != null && !nested[0].success(),
                "missing/deferred callback must not become success");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void soakCommandsDistinguishHandlerFailureAndWrongReadback(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var commands = server.getCommands();
        var source = server.createCommandSourceStack();
        commands.getDispatcher().register(net.minecraft.commands.Commands.literal("lss_soak_test_failure")
                .executes(context -> { throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                        Component.literal("deliberate soak handler failure")).create(); }));
        helper.assertTrue(!dev.vox.lss.benchmark.SoakCommandExecutor.executeForResult(
                        commands, source, "lss_soak_test_failure").success(),
                "command failure callback must remain false");
        helper.assertTrue(dev.vox.lss.benchmark.SoakCommandExecutor.dispatch(
                        commands, source, "lss_soak_test_failure"),
                "generic cleanup retains parse/dispatch semantics, not mandatory effects");
        commands.getDispatcher().register(net.minecraft.commands.Commands.literal("lss_soak_test_readback")
                .executes(context -> 1)
                .then(net.minecraft.commands.Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .executes(context -> com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "value"))));
        helper.assertTrue(!dev.vox.lss.benchmark.SoakCommandExecutor.setGamerule(
                        commands, source, "lss_soak_test_readback 0"),
                "successful zero-valued setter cannot hide wrong readback");
        helper.succeed();
    }
}
