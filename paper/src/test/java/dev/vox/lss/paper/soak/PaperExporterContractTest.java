package dev.vox.lss.paper.soak;

import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.DiskReaderDiagnostics;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.ProcessingDiagnostics;
import dev.vox.lss.common.processing.TickDiagnostics;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import dev.vox.lss.paper.PaperChunkDiskReader;
import dev.vox.lss.paper.PaperChunkGenerationService;
import dev.vox.lss.paper.PaperPlayerRequestState;
import dev.vox.lss.paper.PaperRequestProcessingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * HD-022/HD-023/HD-029 + the round's data-capture fields, Paper side: pins that
 * PaperSoakMetricsExporter emits the EXACT Fabric key set and value types by asserting
 * against the SAME checked-in contract literal the Fabric ExporterContractTest uses
 * (fabric/src/test/resources/exporter-contract/server-snapshot.contract) — the
 * copy-paste-twin drift guard — plus the null-service contract, the generation
 * zero-fill leg, value-to-key mappings via distinct mocked counters, probe-hash twin
 * capture, high-water/mspt window semantics, and the JSONL append shape.
 */
class PaperExporterContractTest {

    private static final String DIM = "minecraft:overworld";

    // ---- Mocked service graph (Mockito is an existing paper test dependency) ----

    private static final class Fixture {
        final PaperRequestProcessingService service = mock(PaperRequestProcessingService.class);
        @SuppressWarnings("rawtypes")
        final OffThreadProcessor processor = mock(OffThreadProcessor.class);
        final ProcessingDiagnostics diag = new ProcessingDiagnostics();
        final TickDiagnostics tickDiag = new TickDiagnostics();
        final PaperChunkDiskReader diskReader = mock(PaperChunkDiskReader.class);
        final DiskReaderDiagnostics diskDiag = new DiskReaderDiagnostics();
        final PaperChunkGenerationService genService = mock(PaperChunkGenerationService.class);
        final DirtyColumnTracker dirtyTracker = new DirtyColumnTracker();
        final SharedBandwidthLimiter bandwidthLimiter = new SharedBandwidthLimiter(1_000_000);
        final PaperPlayerRequestState player = mock(PaperPlayerRequestState.class);
        final UUID playerUuid = UUID.randomUUID();

        Fixture() {
            doReturn(this.diag).when(this.processor).getDiagnostics();
            doReturn(new OffThreadProcessor.HarnessInternals(0, 2, 5L, Map.of(DIM, 1)))
                    .when(this.processor).getHarnessInternals();
            doReturn(this.diskDiag).when(this.diskReader).getDiag();
            doReturn(3).when(this.diskReader).getPendingResultCount();
            doReturn(11L).when(this.genService).getTotalSubmitted();
            doReturn(12L).when(this.genService).getTotalCompleted();
            doReturn(13L).when(this.genService).getTotalTimeouts();
            doReturn(14L).when(this.genService).getTotalRemovedInFlight();
            doReturn(15).when(this.genService).getActiveCount();
            doReturn("paper-player").when(this.player).getPlayerName();
            doReturn(this.playerUuid).when(this.player).getPlayerUUID();
            doReturn(1).when(this.player).getHeldSyncSlots();
            doReturn(2).when(this.player).getHeldGenSlots();
            doReturn(3).when(this.player).getSendQueueSize();
            doReturn(4L).when(this.player).getTotalSectionsSent();
            doReturn(5L).when(this.player).getTotalBytesSent();
            doReturn(6L).when(this.player).getTotalRequestsReceived();
            doReturn(7).when(this.player).getBacklogSize();

            var players = new LinkedHashMap<UUID, PaperPlayerRequestState>();
            players.put(this.playerUuid, this.player);
            doReturn(this.processor).when(this.service).getOffThreadProcessor();
            doReturn(this.tickDiag).when(this.service).getTickDiag();
            doReturn(this.diskReader).when(this.service).getDiskReader();
            doReturn(this.genService).when(this.service).getGenerationService();
            doReturn(this.dirtyTracker).when(this.service).getDirtyTracker();
            doReturn(this.bandwidthLimiter).when(this.service).getBandwidthLimiter();
            doReturn(players).when(this.service).getPlayers();
        }
    }

    @AfterEach
    void resetExporterStatics() {
        PaperSoakMetricsExporter.setProbesForTest("");
        PaperSoakMetricsExporter.resetHarnessCaptureForTest();
    }

    // ---- Contract flattening helpers (mirror of ExporterContractTest's) ----

    private static void flatten(String prefix, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> map) {
            for (var e : map.entrySet()) {
                flatten(prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey(),
                        e.getValue(), out);
            }
        } else if (value instanceof List<?> list) {
            for (var element : list) flatten(prefix + "[]", element, out);
        } else if (value instanceof Long) {
            out.put(prefix, "long");
        } else if (value instanceof Integer) {
            out.put(prefix, "int");
        } else if (value instanceof Double) {
            out.put(prefix, "double");
        } else if (value instanceof Boolean) {
            out.put(prefix, "boolean");
        } else if (value instanceof String) {
            out.put(prefix, "string");
        } else {
            out.put(prefix, value == null ? "null" : value.getClass().getName());
        }
    }

    private static String flattenedLines(Map<String, Object> snapshot) {
        var flat = new TreeMap<String, String>();
        flatten("", snapshot, flat);
        var sb = new StringBuilder();
        flat.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sb.toString().stripTrailing();
    }

    private static String contractLines() throws Exception {
        var path = locate("fabric/src/test/resources/exporter-contract/server-snapshot.contract");
        return String.join("\n", Files.readAllLines(path).stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList());
    }

    /** Walks up from the working directory to find a repo-relative path (PluginYmlContractTest
     *  convention) — both platform tests must read the ONE shared contract file. */
    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + repoRelative + " above " + Path.of("").toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> snapshot, String key) {
        return (Map<String, Object>) snapshot.get(key);
    }

    // ---- HD-022: exact Fabric key set/types for equivalent state + null contract ----

    @Test
    void serverSnapshotMatchesTheSharedFabricContractLiteral() throws Exception {
        PaperSoakMetricsExporter.setProbesForTest("7:-3");
        var snapshot = PaperSoakMetricsExporter.buildServerMetrics(new Fixture().service);
        assertEquals(contractLines(), flattenedLines(snapshot),
                "Paper snapshot schema drifted from the shared Fabric contract literal"
                        + " (fabric/src/test/resources/exporter-contract/server-snapshot.contract)");
    }

    @Test
    void buildServerMetricsOfNullServiceReturnsNull() {
        assertNull(PaperSoakMetricsExporter.buildServerMetrics(null),
                "pre-service snapshots must stay null rows, not empty maps");
    }

    // ---- Value-to-key mappings via distinct mocked counters ----

    @Test
    void distinctValuesLandOnTheirContractKeys() {
        PaperSoakMetricsExporter.setProbesForTest("");
        var fx = new Fixture();
        fx.diag.incrementReResolved();
        fx.diag.incrementReResolved();
        fx.diag.addSuperseded(8);
        fx.diag.addRangeFiltered(9);
        fx.tickDiag.recordSectionSent(100);
        fx.diskDiag.recordCompleted(3_000_000L);
        fx.diskDiag.recordSuccess();
        fx.bandwidthLimiter.recordSend(64);
        fx.dirtyTracker.markDirty(DIM, 1, 2);
        fx.dirtyTracker.markDirty(DIM, 1, 2); // re-mark: not net-new
        fx.dirtyTracker.markDirty(DIM, 3, 4);

        var m = PaperSoakMetricsExporter.buildServerMetrics(fx.service);

        assertEquals(2L, section(m, "service").get("re_resolved"));
        assertEquals(8L, section(m, "service").get("superseded"));
        assertEquals(9L, section(m, "service").get("range_filtered"));
        assertEquals(1L, section(m, "service").get("columns_sent"));
        assertEquals(100L, section(m, "service").get("bytes_sent"));
        assertEquals(1L, section(m, "service").get("disk_resolved"));
        assertEquals(1L, section(m, "disk").get("completed"));
        assertEquals(3L, section(m, "disk").get("read_ms_total"));
        assertEquals(3, section(m, "disk").get("pending"));
        assertEquals(11L, section(m, "generation").get("submitted"));
        assertEquals(12L, section(m, "generation").get("completed"));
        assertEquals(13L, section(m, "generation").get("timeouts"));
        assertEquals(14L, section(m, "generation").get("removed_in_flight"));
        assertEquals(15, section(m, "generation").get("active"));
        assertEquals(15, section(m, "generation").get("active_hw"),
                "the snapshot's own fold makes active_hw at least the point sample");
        assertEquals(2L, section(m, "dirty").get("marked_total"));
        assertEquals(2, section(m, "dirty").get("pending"));
        assertEquals(64L, section(m, "bandwidth").get("total_bytes"));
        assertEquals(5L, section(m, "tscache").get("evictions"));
        assertEquals(Map.of(DIM, 1), section(m, "tscache").get("size_per_dimension"));
        assertEquals(0, section(m, "dedup").get("groups"));
        assertEquals(2, m.get("mailbox_depth_hw"));

        @SuppressWarnings("unchecked")
        var row = (Map<String, Object>) ((List<?>) m.get("players")).get(0);
        assertEquals("paper-player", row.get("name"));
        assertEquals(1, row.get("held_sync"));
        assertEquals(2, row.get("held_gen"));
        assertEquals(3, row.get("send_queue"));
        assertEquals(3, row.get("send_queue_hw"));
        assertEquals(4L, row.get("sent"));
        assertEquals(5L, row.get("bytes"));
        assertEquals(6L, row.get("requests"));
        // Want-set entries not yet routed by the processing thread — the successor gauge to the
        // deleted incoming_dropped (the bounded drop-on-full queue is gone; a latest-wins batch
        // replaces it, so there is nothing to drop, only a backlog left to work through).
        assertEquals(7, row.get("backlog"));

        // Mark/drain conservation closes: marked_total == broadcast_positions + pending
        fx.dirtyTracker.drainDirty(DIM);
        var m2 = PaperSoakMetricsExporter.buildServerMetrics(fx.service);
        assertEquals(2L, section(m2, "dirty").get("marked_total"));
        assertEquals(2L, section(m2, "dirty").get("broadcast_positions"));
        assertEquals(0, section(m2, "dirty").get("pending"));
    }

    // ---- HD-023 (Paper leg): null generation service zero-fills every generation key ----

    @Test
    void generationDisabledZeroFillsEveryGenerationField() {
        PaperSoakMetricsExporter.setProbesForTest("");
        var fx = new Fixture();
        doReturn(null).when(fx.service).getGenerationService();

        var gen = section(PaperSoakMetricsExporter.buildServerMetrics(fx.service), "generation");
        assertEquals(0L, gen.get("submitted"));
        assertEquals(0L, gen.get("completed"));
        assertEquals(0L, gen.get("timeouts"));
        assertEquals(0L, gen.get("removed_in_flight"));
        assertEquals(0, gen.get("active"));
        assertEquals(0, gen.get("active_hw"));
        assertEquals(6, gen.size(), "zero-fill must mirror the live branch's exact key count");
    }

    @Test
    void diskMapIsEmptyWhenNoReaderIsConfigured() {
        PaperSoakMetricsExporter.setProbesForTest("");
        var fx = new Fixture();
        doReturn(null).when(fx.service).getDiskReader();

        var m = PaperSoakMetricsExporter.buildServerMetrics(fx.service);
        assertTrue(section(m, "disk").isEmpty(), "disk map must be {} when the reader is null");
        assertEquals(0L, section(m, "service").get("disk_resolved"));
    }

    // ---- probe_hashes twin capture (§3.9) ----

    @Test
    void probeHashesCaptureFnvOfWireBytesKeyedByProbedPositionOnly() {
        PaperSoakMetricsExporter.setProbesForTest("7:-3,8:8");
        // Canonical FNV-1a 64 vector: "foobar" -> 0x85944171f73967e8
        PaperSoakMetricsExporter.recordServedColumnBytes(7, -3, "foobar".getBytes(StandardCharsets.UTF_8));
        PaperSoakMetricsExporter.recordServedColumnBytes(1, 1, new byte[]{42});

        var hashes = section(PaperSoakMetricsExporter.buildServerMetrics(new Fixture().service), "probe_hashes");
        assertEquals(2, hashes.size(), "exactly the armed probes, nothing else");
        assertEquals(0x85944171f73967e8L, hashes.get("7:-3"));
        assertEquals(-1L, hashes.get("8:8"), "armed but never served reads -1");
    }

    @Test
    void emptyProbeSpecEmitsNoProbeHashesKey() {
        PaperSoakMetricsExporter.setProbesForTest("");
        var m = PaperSoakMetricsExporter.buildServerMetrics(new Fixture().service);
        assertFalse(m.containsKey("probe_hashes"), "no probe_hashes key when the property is blank");
    }

    @Test
    void malformedProbeTokensAreSkippedAndValidOnesKept() {
        var probes = PaperSoakMetricsExporter.parseSoakProbes("8:8, banana,:5,4:-2x,,7:-3");
        assertEquals(2, probes.size(), "only the two well-formed x:z tokens survive");
        assertEquals("8:8", probes.get(0).getKey());
        assertEquals("7:-3", probes.get(1).getKey());
        assertEquals(-3, probes.get(1).getValue()[1]);
    }

    // ---- High-water + mspt twin semantics (§3.2/§3.4) ----

    @Test
    void highWaterGaugesCaptureBurstsAndMsptCountsSamplerTicks() {
        PaperSoakMetricsExporter.setProbesForTest("");
        var fx = new Fixture();

        var unwired = PaperSoakMetricsExporter.buildServerMetrics(fx.service);
        assertEquals(-1.0, unwired.get("mspt_avg_window"),
                "no sampler ticks in the window must read -1, never 0");

        doReturn(9).when(fx.diskReader).getPendingResultCount();
        PaperSoakMetricsExporter.sampleServerGauges(fx.service);
        doReturn(3).when(fx.diskReader).getPendingResultCount();

        var m = PaperSoakMetricsExporter.buildServerMetrics(fx.service);
        assertEquals(3, section(m, "disk").get("pending"));
        assertEquals(9, section(m, "disk").get("pending_hw"), "high-water keeps the sampled burst");
        double mspt = (Double) m.get("mspt_avg_window");
        assertTrue(mspt > 0.0, "a sampled tick must yield a positive wall-ms-per-tick, got " + mspt);

        var m2 = PaperSoakMetricsExporter.buildServerMetrics(fx.service);
        assertEquals(3, section(m2, "disk").get("pending_hw"), "window must reset between snapshots");
        assertEquals(-1.0, m2.get("mspt_avg_window"), "tick count must reset with the window");
    }

    // ---- HD-029 (Paper twin): JSONL append shape ----

    @Test
    void appendJsonLineWritesOneCompactRowPerCallCreatesDirsAndAppends(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("nested").resolve("deeper").resolve("rows.jsonl");
        var row1 = new LinkedHashMap<String, Object>();
        row1.put("event", "a");
        row1.put("n", 1);
        var row2 = new LinkedHashMap<String, Object>();
        row2.put("event", "b");

        PaperSoakMetricsExporter.appendJsonLine(file, row1);
        assertTrue(Files.exists(file), "parent directories must be created on first use");
        PaperSoakMetricsExporter.appendJsonLine(file, row2);

        var lines = Files.readAllLines(file);
        assertEquals(2, lines.size(), "append must not truncate: both rows survive");
        assertEquals("{\"event\":\"a\",\"n\":1}", lines.get(0),
                "rows must be compact single-line JSON (pretty-printing breaks the checker's parser)");
        assertEquals("{\"event\":\"b\"}", lines.get(1));
    }
}
