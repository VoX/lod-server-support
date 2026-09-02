package dev.vox.lss.paper;

import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.compat.V16CompatManager;
import dev.vox.lss.common.processing.DiskReaderDiagnostics;
import dev.vox.lss.common.processing.ProcessingDiagnostics;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Graceful-degradation coverage for /lsslod via the Supplier seam: the command must answer
 * (never throw at the admin) when the service is not active, when no players are connected,
 * and — the concrete trap — when generation is disabled and getGenerationService() is null
 * while the diag formatter runs.
 */
class PaperCommandsTest {

    private static final String USAGE = "Usage: /lsslod <stats|diag|store|set|help>";
    private static final String STORE_USAGE = "Usage: /lsslod store <status|invalidate all>";

    private final List<String> messages = new ArrayList<>();
    private CommandSender sender;

    @BeforeEach
    void setup() {
        messages.clear();
        sender = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class},
                (p, m, args) -> {
                    if ("sendMessage".equals(m.getName()) && args != null
                            && args.length == 1 && args[0] instanceof String s) {
                        messages.add(s);
                    }
                    return switch (m.getName()) {
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        case "toString" -> "sender";
                        default -> m.getReturnType() == boolean.class ? false : null;
                    };
                });
    }

    private static PaperCommands commands(PaperRequestProcessingService service, PaperConfig config) {
        return new PaperCommands(() -> service, () -> config);
    }

    private boolean run(PaperCommands cmd, String... args) {
        // The Command parameter is unused by the handler; Bukkit registration guarantees label
        return cmd.onCommand(sender, null, "lsslod", args);
    }

    @Test
    void noArgsShowsHelp() {
        // v0.11.0 stage C: bare /lsslod = help (was a usage line), served BEFORE the
        // service null-check so a not-yet-active server still explains itself.
        assertTrue(run(commands(null, null)));
        assertEquals(dev.vox.lss.common.CommandHelp.lines("lsslod", false), messages,
                "the shared CommandHelp builder is the one source of the help text");
        assertTrue(messages.stream().anyMatch(m -> m.contains("set <key> <value>")),
                "help must document the runtime-set verb: " + messages);
        assertTrue(messages.stream().noneMatch(m -> m.contains("backfill")),
                "backfill verbs are Fabric-only and must not appear in Paper help");
    }

    @Test
    void helpVerbShowsTheSameLines() {
        assertTrue(run(commands(null, null), "help"));
        assertEquals(dev.vox.lss.common.CommandHelp.lines("lsslod", false), messages);
    }

    @Test
    void nullServiceReportsNotActiveInsteadOfThrowing() {
        assertTrue(run(commands(null, null), "stats"));
        assertEquals(List.of("LSS LOD request processing is not active"), messages);
    }

    @Test
    void unknownSubcommandShowsUsage() {
        assertTrue(run(commands(mock(PaperRequestProcessingService.class), null), "bogus"));
        assertEquals(List.of(USAGE), messages);
    }

    @Test
    void unknownSubcommandWithNullServiceReportsNotActive() {
        // Precedence pin: the service-null check answers BEFORE subcommand validation, so
        // /lsslod bogus on an inactive server reports "not active" — never the usage line.
        // A refactor that validates the subcommand first passes both single-rung tests
        // above while flipping this answer.
        assertTrue(run(commands(null, null), "bogus"));
        assertEquals(List.of("LSS LOD request processing is not active"), messages);
    }

    // ---- store verbs (4-agent round R3: Paper parity for the ops surface) ----

    @Test
    void storeStatusWithNoStoreReportsOffUnavailable() {
        var service = mock(PaperRequestProcessingService.class);
        when(service.getLodStore()).thenReturn(null);
        assertTrue(run(commands(service, null), "store", "status"));
        assertEquals(List.of("LOD store: off/unavailable"), messages);
    }

    @Test
    void storeInvalidateAllOnNonPersistentStoreReportsRequiresFull() {
        var service = mock(PaperRequestProcessingService.class);
        when(service.getLodStore())
                .thenReturn(mock(dev.vox.lss.common.store.LodStoreService.class));
        when(service.invalidateStoreAllDimensions()).thenReturn(false);
        assertTrue(run(commands(service, null), "store", "invalidate", "all"));
        assertEquals(List.of("Invalidate-all requires the persistent SQLite store engine"),
                messages);
    }

    @Test
    void storeInvalidateAllOnPersistentStoreAcknowledges() {
        var service = mock(PaperRequestProcessingService.class);
        when(service.getLodStore())
                .thenReturn(mock(dev.vox.lss.common.store.LodStoreService.class));
        when(service.invalidateStoreAllDimensions()).thenReturn(true);
        assertTrue(run(commands(service, null), "store", "invalidate", "all"));
        assertEquals(List.of("LOD store: dropping all rows (background) — re-warms from serves"),
                messages);
    }

    @Test
    void bareStoreVerbShowsStoreUsage() {
        var service = mock(PaperRequestProcessingService.class);
        assertTrue(run(commands(service, null), "store"));
        assertEquals(List.of(STORE_USAGE), messages);
    }

    @Test
    void statsWithZeroPlayersReportsNoPlayers() {
        var service = mock(PaperRequestProcessingService.class);
        when(service.getPlayers()).thenReturn(Map.of());
        assertTrue(run(commands(service, null), "stats"));
        assertEquals(List.of("No players connected with LSS"), messages);
    }

    @Test
    void diagWithGenerationDisabledShowsDisabledLine() {
        var service = mock(PaperRequestProcessingService.class);
        var offThread = mock(PaperOffThreadProcessor.class);
        when(offThread.getDiagnostics()).thenReturn(new ProcessingDiagnostics());
        doReturn(offThread).when(service).getOffThreadProcessor();
        var diskReader = mock(PaperChunkDiskReader.class);
        when(diskReader.getDiag()).thenReturn(new DiskReaderDiagnostics());
        when(diskReader.getDiagnostics()).thenReturn("idle");
        when(service.getDiskReader()).thenReturn(diskReader);
        when(service.getBandwidthLimiter()).thenReturn(new SharedBandwidthLimiter(1024));
        when(service.getV16CompatManager()).thenReturn(new V16CompatManager());
        when(service.getDialectTracker()).thenReturn(new dev.vox.lss.common.compat.WireDialectTracker());
        when(service.getTickDiagnostics()).thenReturn("tick");
        when(service.getTickDiag()).thenReturn(new dev.vox.lss.common.processing.TickDiagnostics());
        when(service.getPlayers()).thenReturn(Map.of());
        // getGenerationService() returns null (generation disabled) — the path that must not NPE

        assertTrue(run(commands(service, new PaperConfig()), "diag"));

        assertEquals("=== LSS LOD Diagnostics ===", messages.get(0));
        assertTrue(messages.contains("Generation: disabled"),
                "null generation service renders as 'disabled': " + messages);
        assertFalse(messages.contains("Generation: null"),
                "disabled generation must not format the null diagnostics string");
        assertTrue(messages.stream().noneMatch(m -> m.startsWith("V18Compat")),
                "an untouched v18 rung must render no line: " + messages);
    }

    @Test
    void diagRendersTheV18CompatLineThroughTheCommandCallSite() {
        // The tracker -> withV18Line -> output plumbing at the COMMAND call site
        // (v18-compat design §2.7): the formatter-level slot test cannot catch a deleted
        // .withV18Line(...) chain link in PaperCommands, this can.
        var service = mock(PaperRequestProcessingService.class);
        var offThread = mock(PaperOffThreadProcessor.class);
        when(offThread.getDiagnostics()).thenReturn(new ProcessingDiagnostics());
        doReturn(offThread).when(service).getOffThreadProcessor();
        var diskReader = mock(PaperChunkDiskReader.class);
        when(diskReader.getDiag()).thenReturn(new DiskReaderDiagnostics());
        when(diskReader.getDiagnostics()).thenReturn("idle");
        when(service.getDiskReader()).thenReturn(diskReader);
        when(service.getBandwidthLimiter()).thenReturn(new SharedBandwidthLimiter(1024));
        when(service.getV16CompatManager()).thenReturn(new V16CompatManager());
        var tracker = new dev.vox.lss.common.compat.WireDialectTracker();
        tracker.onHandshake(java.util.UUID.randomUUID(),
                dev.vox.lss.common.HandshakeGate.WireDialect.V18);
        when(service.getDialectTracker()).thenReturn(tracker);
        when(service.getTickDiagnostics()).thenReturn("tick");
        when(service.getTickDiag()).thenReturn(new dev.vox.lss.common.processing.TickDiagnostics());
        when(service.getPlayers()).thenReturn(Map.of());

        assertTrue(run(commands(service, new PaperConfig()), "diag"));
        assertTrue(messages.contains("Dialects: v20=0, v19=0, v18=1, v16=0, started=0/0/1/0"),
                "a live v18 session must render the Dialects line through the command: " + messages);
    }

    @Test
    void diagWithEnabledFalseConfigRendersAndStaysServiceable() {
        // enabled=false disables serving, not observability: diag must still answer with
        // the full line ladder and the Config line must carry the false flag.
        var service = mock(PaperRequestProcessingService.class);
        var offThread = mock(PaperOffThreadProcessor.class);
        when(offThread.getDiagnostics()).thenReturn(new ProcessingDiagnostics());
        doReturn(offThread).when(service).getOffThreadProcessor();
        var diskReader = mock(PaperChunkDiskReader.class);
        when(diskReader.getDiag()).thenReturn(new DiskReaderDiagnostics());
        when(diskReader.getDiagnostics()).thenReturn("idle");
        when(service.getDiskReader()).thenReturn(diskReader);
        when(service.getBandwidthLimiter()).thenReturn(new SharedBandwidthLimiter(1024));
        when(service.getV16CompatManager()).thenReturn(new V16CompatManager());
        when(service.getDialectTracker()).thenReturn(new dev.vox.lss.common.compat.WireDialectTracker());
        when(service.getTickDiagnostics()).thenReturn("tick");
        when(service.getTickDiag()).thenReturn(new dev.vox.lss.common.processing.TickDiagnostics());
        when(service.getPlayers()).thenReturn(Map.of());
        var config = new PaperConfig();
        config.enabled = false;

        assertTrue(run(commands(service, config), "diag"));

        assertEquals("=== LSS LOD Diagnostics ===", messages.get(0));
        assertTrue(messages.get(1).startsWith("Config: enabled=false, lodDist=512, bw/player="),
                "the Config line must render the disabled flag and the config values: " + messages.get(1));
        assertTrue(messages.stream().anyMatch(m -> m.equals("Xray: active=off, masked_sections=0")),
                "no active mask manager renders the off xray line: " + messages);
        assertTrue(messages.stream().anyMatch(m ->
                        m.equals("Dialects: v20=0, v19=0, v18=0, v16=0, started=0/0/0/0")),
                "the Dialects line renders unconditionally (the v20 count IS the live"
                        + " LOD-session count): " + messages);
        assertTrue(messages.stream().anyMatch(m ->
                        m.equals("Yield: armed=true, ticks_total=0, bytes_withheld=0 B")),
                "the Yield arming receipt renders on the default config (default TRUE"
                        + " since v0.11.0, user decision 2026-08-13): " + messages);
        assertEquals(11, messages.size(),
                "all eleven diagnostic lines render with no players connected: " + messages);
    }

    @Test
    void diagIsCaseInsensitive() {
        var service = mock(PaperRequestProcessingService.class);
        when(service.getPlayers()).thenReturn(Map.of());
        assertTrue(run(commands(service, null), "STATS"));
        assertEquals(List.of("No players connected with LSS"), messages);
    }

    @Test
    void tabCompleteFiltersByPrefix() {
        var cmd = commands(null, null);
        assertEquals(List.of("stats", "diag", "store", "set", "help"),
                cmd.onTabComplete(sender, null, "lsslod", new String[]{""}));
        assertEquals(List.of("stats", "store", "set"), cmd.onTabComplete(sender, null, "lsslod", new String[]{"s"}));
        assertEquals(List.of("diag"), cmd.onTabComplete(sender, null, "lsslod", new String[]{"D"}));
        assertEquals(List.of(), cmd.onTabComplete(sender, null, "lsslod", new String[]{"zz"}));
        assertEquals(List.of(), cmd.onTabComplete(sender, null, "lsslod", new String[]{"stats", "x"}));
        assertEquals(List.of("status", "invalidate"),
                cmd.onTabComplete(sender, null, "lsslod", new String[]{"store", ""}));
        assertEquals(List.of("all"),
                cmd.onTabComplete(sender, null, "lsslod", new String[]{"store", "invalidate", ""}));
        // v0.11.0 stage C: key completion is registry-derived, so it cannot drift.
        assertEquals(dev.vox.lss.common.config.RuntimeSettings.keyNames(),
                cmd.onTabComplete(sender, null, "lsslod", new String[]{"set", ""}));
        assertEquals(List.of("maxConcurrentDiskReads"),
                cmd.onTabComplete(sender, null, "lsslod", new String[]{"set", "max"}));
    }

    // ---- v0.11.0 stage C: /lsslod set ----

    /** A service mock whose runtime-task queue runs INLINE (the pump drain, collapsed)
     *  and whose re-push reports fixed counts. */
    private static PaperRequestProcessingService inlineTaskService(int[] repushCounts) {
        var service = mock(PaperRequestProcessingService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(service).enqueueRuntimeTask(org.mockito.ArgumentMatchers.any());
        when(service.repushSessionConfig()).thenReturn(repushCounts);
        return service;
    }

    @Test
    void setWithNoArgsListsTheRegistryWithCurrentValues() {
        var config = new PaperConfig();
        config.validate();
        assertTrue(run(commands(mock(PaperRequestProcessingService.class), config), "set"));
        assertTrue(messages.get(0).startsWith("Runtime-settable keys"), String.valueOf(messages));
        assertTrue(messages.contains("  lodDistanceChunks = 512"),
                "listing shows current values: " + messages);
        assertEquals(1 + dev.vox.lss.common.config.RuntimeSettings.keyNames().size(),
                messages.size());
    }

    @Test
    void setHappyPathAppliesRepushesAndRepliesWithTheEffectiveValue() {
        var config = new PaperConfig();
        config.validate();
        var service = inlineTaskService(new int[]{2, 1});
        assertTrue(run(commands(service, config), "set", "lodDistanceChunks", "128"));
        assertEquals(128, config.lodDistanceChunks, "the mutation applied through the pump task");
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).startsWith("lodDistanceChunks = 128 — "),
                "reply carries the effective value + the applies note: " + messages);
        assertTrue(messages.get(0).contains("re-pushed to 2 client(s) (1 legacy update on rejoin)"),
                "the distance set must report the re-push counts: " + messages);
    }

    @Test
    void setZeroSemanticsSurviveTheCommandSurface() {
        var config = new PaperConfig();
        config.validate();
        var service = inlineTaskService(new int[]{0, 0});
        assertTrue(run(commands(service, config), "set", "dirtyBroadcastIntervalSeconds", "0"));
        assertEquals(0, config.dirtyBroadcastIntervalSeconds,
                "0 = dirty pushes off must survive end-to-end (the R-2 registry clamp rule)");
        assertTrue(messages.get(0).startsWith("dirtyBroadcastIntervalSeconds = 0 — "));
        messages.clear();
        assertTrue(run(commands(service, config), "set", "maxConcurrentDiskReads", "0"));
        assertEquals(0, config.maxConcurrentDiskReads, "0 = AUTO, never K=1");
    }

    @Test
    void setParseErrorAndUnknownKeyReplyWithoutMutating() {
        var config = new PaperConfig();
        config.validate();
        int before = config.lodDistanceChunks;
        var service = inlineTaskService(new int[]{0, 0});
        assertTrue(run(commands(service, config), "set", "lodDistanceChunks", "many"));
        assertEquals(before, config.lodDistanceChunks, "a parse failure must assign nothing");
        assertTrue(messages.get(0).contains("not an integer"), String.valueOf(messages));
        messages.clear();
        assertTrue(run(commands(service, config), "set", "noSuchKey", "5"));
        assertTrue(messages.get(0).startsWith("Unknown key 'noSuchKey'"), String.valueOf(messages));
        messages.clear();
        assertTrue(run(commands(service, config), "set", "lodDistanceChunks"));
        assertTrue(messages.get(0).startsWith("Usage: /lsslod set lodDistanceChunks"),
                "a missing value shows per-key usage: " + messages);
    }

    @Test
    void setNonDistanceKeysDoNotRepush() {
        var config = new PaperConfig();
        config.validate();
        var service = inlineTaskService(new int[]{9, 9});
        assertTrue(run(commands(service, config), "set", "generationConcurrencyLimitGlobal", "64"));
        assertFalse(messages.get(0).contains("re-pushed"),
                "only lodDistanceChunks triggers the SessionConfig re-push: " + messages);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).repushSessionConfig();
    }

    @Test
    void setPerWorldLodDistanceMutatesMapAndTriggersRepush() {
        var config = new PaperConfig();
        config.validate();
        var service = inlineTaskService(new int[]{1, 0});
        assertTrue(run(commands(service, config), "set", "lodDistanceChunks", "creative", "128"));
        assertEquals(512, config.lodDistanceChunks, "the default distance is unchanged");
        assertEquals(128, config.lodDistanceChunksByWorld.get("creative"));
        assertTrue(messages.get(0).contains("creative=128"),
                "the reply names the per-world override: " + messages);
        org.mockito.Mockito.verify(service).repushSessionConfig();
    }
}
