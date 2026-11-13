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
 * and when the always-present generation controller is dormant.
 */
class PaperCommandsTest {

    private static final String USAGE = "Usage: /lsslod <stats|diag|diagnostics|store|reload|help>";
    private static final String STORE_USAGE = "Usage: /lsslod store <status|invalidate all>";

    private final List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
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

    @Test void diagnosticCaptureUsesTheOwnerGateAndWholeServiceGate() {
        var service = mock(PaperRequestProcessingService.class);
        var generation = mock(PaperChunkGenerationService.class);
        when(service.getGenerationService()).thenReturn(generation);
        when(service.getTickDiag()).thenReturn(mock(dev.vox.lss.common.processing.TickDiagnostics.class));
        var config = new MutablePaperSettings();
        MutablePaperSettings.set(config, "generation.enabled", true);
        var cmd = commands(service, config);

        var pending = cmd.captureDiagnostics(service);
        assertTrue(pending.serviceAvailable());
        assertFalse(pending.generationEnabled(), "published true must not replace the owner's false gate");
        assertTrue(pending.generationConfiguredForRestart(), "legacy JSON value remains configured");

        when(generation.isAdmissionEnabled()).thenReturn(true);
        assertTrue(cmd.captureDiagnostics(service).generationEnabled());
        MutablePaperSettings.set(config, "service.enabled", false);
        var disabled = cmd.captureDiagnostics(service);
        assertTrue(disabled.serviceAvailable(), "Paper retains its dormant service instance");
        assertFalse(disabled.enabled());
        assertFalse(disabled.generationEnabled(), "whole-service disable closes admission");
        assertTrue(disabled.generationConfiguredForRestart());

        MutablePaperSettings.set(config, "service.enabled", true);
        MutablePaperSettings.set(config, "generation.enabled", false);
        var pendingDisable = cmd.captureDiagnostics(service);
        assertTrue(pendingDisable.generationEnabled(), "an owner not yet disabled still reports its actual gate");
        assertFalse(pendingDisable.generationConfiguredForRestart());
        assertFalse(cmd.captureDiagnostics(null).generationEnabled(), "absent owner cannot admit");
    }

    @Test
    void noArgsShowsHelp() {
        // v0.11.0 stage C: bare /lsslod = help (was a usage line), served BEFORE the
        // service null-check so a not-yet-active server still explains itself.
        assertTrue(run(commands(null, null)));
        assertEquals(dev.vox.lss.common.CommandHelp.lines("lsslod", false), messages,
                "the shared CommandHelp builder is the one source of the help text");
        assertTrue(messages.stream().anyMatch(m -> m.contains("reload")),
                "help must document explicit reload: " + messages);
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
        var generation = mock(PaperChunkGenerationService.class);
        when(generation.getDiagnostics()).thenReturn("dormant controller");
        when(service.getGenerationService()).thenReturn(generation);
        var config = new MutablePaperSettings();
        MutablePaperSettings.set(config, "generation.enabled", false);

        assertTrue(run(commands(service, config), "diag"));

        assertEquals("=== LSS LOD Diagnostics ===", messages.get(0));
        assertTrue(messages.contains("Generation: disabled"),
                "a dormant generation controller renders as 'disabled': " + messages);
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
        assertTrue(messages.stream().anyMatch(m ->
                        m.startsWith("Dialects: v20=0, v19=0, v18=1, v16=0, started=0/0/1/0, ended=0/0/0/0 in ")),
                "a live v18 session must render the Dialects line through the command: " + messages);
    }

    @Test
    void diagRendersTheMapPolicyLineOnlyWhileArmedAndApartFromTheGateLine() {
        // Map-policy plan A3.5: a separate "MapPolicy: on refused=<n>" line while armed —
        // the Gate line renders only with requireServicePermission on, and its denied=
        // counts permission denials only (the memo now carries both reasons).
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
        var gate = new dev.vox.lss.common.ServiceGateState();
        gate.rememberDenied(java.util.UUID.randomUUID(), "denied", 20, 1);
        gate.rememberDenied(java.util.UUID.randomUUID(), "refused", 19, 1,
                dev.vox.lss.common.ServiceGateState.REASON_MAP_POLICY);
        when(service.getServiceGateState()).thenReturn(gate);
        var config = new MutablePaperSettings();

        assertTrue(run(commands(service, config), "diag"));
        assertTrue(messages.stream().noneMatch(m -> m.startsWith("MapPolicy:")),
                "no line while the policy is off: " + messages);

        messages.clear();
        MutablePaperSettings.set(config, "privacy.request_no_map_ingestion", true);
        gate.setMapPolicyExemptCount(2); // the sweep's cache (map-ingestion permission plan §3.5)
        assertTrue(run(commands(service, config), "diag"));
        assertTrue(messages.contains("MapPolicy: on refused=1 exempt=2"), messages.toString());
        assertTrue(messages.stream().noneMatch(m -> m.startsWith("Gate:")),
                "the policy line never needs the permission gate armed");

        messages.clear();
        MutablePaperSettings.set(config, "service.require_permission", true);
        assertTrue(run(commands(service, config), "diag"));
        assertTrue(messages.contains("Gate: requireServicePermission=on denied=1 provider=bukkit"),
                "denied= counts only the permission entry: " + messages);
        assertTrue(messages.contains("MapPolicy: on refused=1 exempt=2"));
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
        var config = new MutablePaperSettings();
        MutablePaperSettings.set(config, "service.enabled", false);

        assertTrue(run(commands(service, config), "diag"));

        assertEquals("=== LSS LOD Diagnostics ===", messages.get(0));
        assertTrue(messages.get(1).startsWith("Config: enabled=false, lodDist=512, bw/player="),
                "the Config line must render the disabled flag and the config values: " + messages.get(1));
        assertTrue(messages.stream().anyMatch(m -> m.equals("Xray: active=off, masked_sections=0")),
                "no active mask manager renders the off xray line: " + messages);
        assertTrue(messages.stream().anyMatch(m ->
                        m.startsWith("Dialects: v20=0, v19=0, v18=0, v16=0, started=0/0/0/0, ended=0/0/0/0 in ")),
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
        assertEquals(List.of("stats", "diag", "diagnostics", "store", "reload", "help"),
                cmd.onTabComplete(sender, null, "lsslod", new String[]{""}));
        assertEquals(List.of("stats", "store"), cmd.onTabComplete(sender, null, "lsslod", new String[]{"s"}));
        assertEquals(List.of("diag", "diagnostics"), cmd.onTabComplete(sender, null, "lsslod", new String[]{"D"}));
        assertEquals(List.of(), cmd.onTabComplete(sender, null, "lsslod", new String[]{"zz"}));
        assertEquals(List.of(), cmd.onTabComplete(sender, null, "lsslod", new String[]{"stats", "x"}));
        assertEquals(List.of("status", "invalidate"),
                cmd.onTabComplete(sender, null, "lsslod", new String[]{"store", ""}));
        assertEquals(List.of("all"),
                cmd.onTabComplete(sender, null, "lsslod", new String[]{"store", "invalidate", ""}));
        assertEquals(List.of(), cmd.onTabComplete(sender, null, "lsslod", new String[]{"set", ""}));
        assertEquals(List.of(), cmd.onTabComplete(sender, null, "lsslod", new String[]{"preset", ""}));
    }

    private static PaperRequestProcessingService inlineTaskService(
            java.util.concurrent.CompletableFuture<Void> adoption, int legacyReconnects) {
        var service = mock(PaperRequestProcessingService.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            try {
                Object value = ((java.util.function.Supplier<?>) invocation.getArgument(0)).get();
                return java.util.concurrent.CompletableFuture.completedFuture(value);
            } catch (Throwable failure) {
                return java.util.concurrent.CompletableFuture.failedFuture(failure);
            }
        }).when(service).submitSettingsControl(org.mockito.ArgumentMatchers.any());
        var receipt = new java.util.concurrent.atomic.AtomicReference<PaperRequestProcessingService.SettingsFeedback>();
        when(service.reconcileSettings(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation -> {
                    long revision = invocation.getArgument(2);
                    return adoption.thenRun(() -> receipt.set(new PaperRequestProcessingService.SettingsFeedback(
                            revision, legacyReconnects, List.of("Generation disabled; 2 admitted job(s) draining"))));
                });
        when(service.settingsFeedback()).thenAnswer(invocation -> receipt.get());
        return service;
    }

    private void awaitMessage(java.util.function.Predicate<String> expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (messages.stream().noneMatch(expected)) {
            if (System.nanoTime() > deadline) throw new AssertionError("No terminal reload feedback: " + messages);
            Thread.sleep(5);
        }
    }

    private static void yaml(PaperConfig config, String contents) throws Exception {
        java.nio.file.Files.writeString(config.settingsPath(), "config_version: 1\n" + contents);
    }

    @Test void removedMutationVerbsCannotChangeSettings() {
        var config = new PaperConfig();
        var before = config.snapshot();
        var command = commands(mock(PaperRequestProcessingService.class), config);
        assertTrue(run(command, "set", "lodDistanceChunks", "128"));
        assertTrue(run(command, "preset", "apply", "conservative"));
        assertEquals(before, config.snapshot());
        assertEquals(List.of(USAGE, USAGE), messages);
    }

    @Test void reloadReadsDraftOnceAppliesZerosAndWorldReplacementWithoutWriting(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var config = new PaperConfig(directory)) {
            var service = inlineTaskService(java.util.concurrent.CompletableFuture.completedFuture(null), 1);
            yaml(config, """
                    # Keep my comment and exact bytes.
                    lod:
                      distance:
                        default_chunks: 128
                        by_dimension: {}
                        by_world:
                          creative: 96
                    updates:
                      dirty_broadcast_interval_ticks: 0
                    storage:
                      disk:
                        max_concurrent_reads: 0
                    """);
            var bytes = java.nio.file.Files.readAllBytes(config.settingsPath());
            assertEquals(512, config.lodDistanceChunks(), "a disk draft is inert");
            assertTrue(run(commands(service, config), "reload"));
            awaitMessage(line -> line.contains("job(s) draining"));
            assertEquals(128, config.lodDistanceChunks());
            assertTrue(config.snapshot().lod().distance().byDimension().isEmpty());
            assertEquals(Map.of("creative", 96), config.snapshot().lod().distance().byWorld());
            assertEquals(0, config.dirtyBroadcastIntervalTicks());
            assertEquals(0, config.maxConcurrentDiskReads());
            org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(config.settingsPath()));
            org.mockito.Mockito.verify(service).reconcileSettings(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
            assertTrue(messages.stream().anyMatch(line -> line.startsWith("1 legacy client(s)")), messages.toString());
            assertTrue(messages.stream().anyMatch(line -> line.contains("job(s) draining")), messages.toString());
        }
    }

    @Test void reloadWaitsForAdoptionAndUnchangedFileDoesNotRepeatSideEffects(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var config = new PaperConfig(directory)) {
            var adoption = new java.util.concurrent.CompletableFuture<Void>();
            var service = inlineTaskService(adoption, 1);
            yaml(config, "lod:\n  distance:\n    default_chunks: 128\n");
            var command = commands(service, config);
            assertTrue(run(command, "reload"));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (config.lodDistanceChunks() != 128 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(128, config.lodDistanceChunks(), "publication precedes owner adoption");
            assertTrue(messages.stream().noneMatch(line -> line.startsWith("Reloaded ")));
            adoption.complete(null);
            awaitMessage(line -> line.contains("job(s) draining"));
            messages.clear();
            assertTrue(run(command, "reload"));
            awaitMessage(line -> line.contains("no active changes"));
            org.mockito.Mockito.verify(service, org.mockito.Mockito.times(1)).reconcileSettings(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
            assertTrue(messages.stream().noneMatch(line -> line.contains("legacy client(s)")),
                    "an unchanged reload must not reuse the previous transition's report");
        }
    }

    @Test void malformedUnknownAndMissingYamlKeepActiveStateAndDoNotWrite(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var config = new PaperConfig(directory)) {
            var service = inlineTaskService(java.util.concurrent.CompletableFuture.completedFuture(null), 0);
            var before = config.snapshot();
            var command = commands(service, config);
            for (var malformed : List.of("lod: [", "made_up_key: true", "service:\n  enabled: true\n  enabled: false\n")) {
                yaml(config, malformed);
                byte[] bytes = java.nio.file.Files.readAllBytes(config.settingsPath());
                messages.clear();
                assertTrue(run(command, "reload"));
                awaitMessage(line -> line.startsWith("Reload failed:"));
                assertEquals(before, config.snapshot());
                org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(config.settingsPath()));
            }
            java.nio.file.Files.delete(config.settingsPath());
            messages.clear();
            assertTrue(run(command, "reload"));
            awaitMessage(line -> line.startsWith("Reload failed:"));
            assertFalse(java.nio.file.Files.exists(config.settingsPath()));
            assertEquals(before, config.snapshot());
            org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).reconcileSettings(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        }
    }

    @Test void disabledServiceCanReportRestartPendingAndRetainsEffectiveBootValues(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Files.writeString(directory.resolve("lss-server-config.yaml"), "config_version: 1\nservice:\n  enabled: false\n");
        try (var config = new PaperConfig(directory)) {
            int bootThreads = config.diskReaderThreads();
            yaml(config, "service:\n  enabled: true\nstorage:\n  disk:\n    reader_threads: 2\n    max_concurrent_reads: 1\n");
            assertTrue(run(commands(null, config), "reload"));
            awaitMessage(line -> line.startsWith("Restart required:"));
            assertFalse(config.enabled());
            assertEquals(bootThreads, config.diskReaderThreads());
            assertTrue(config.configuredSnapshot().service().enabled());
            assertEquals(2, config.configuredSnapshot().storage().disk().readerThreads());
            assertEquals(1, config.maxConcurrentDiskReads());
        }
    }

    @Test void stoppedOwnerRejectsBeforePublicationAndRepliesTerminally(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var config = new PaperConfig(directory)) {
            var service = mock(PaperRequestProcessingService.class);
            when(service.submitSettingsControl(org.mockito.ArgumentMatchers.any())).thenReturn(
                    java.util.concurrent.CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("Server stopped")));
            yaml(config, "lod:\n  distance:\n    default_chunks: 128\n");
            assertTrue(run(commands(service, config), "reload"));
            awaitMessage(line -> line.startsWith("Reload failed:"));
            assertEquals(512, config.lodDistanceChunks());
            assertTrue(messages.stream().anyMatch(line -> line.contains("Server stopped")));
        }
    }
}
