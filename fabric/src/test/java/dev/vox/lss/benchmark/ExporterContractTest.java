package dev.vox.lss.benchmark;

import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import dev.vox.lss.common.processing.AbstractPlayerRequestState;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.TickDiagnostics;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import dev.vox.lss.common.voxel.ColumnTimestampCache;
import dev.vox.lss.networking.client.LodRequestManager;
import dev.vox.lss.networking.server.ChunkGenerationService;
import dev.vox.lss.networking.server.DirtyContentFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HD-021/HD-023/HD-024/HD-025/HD-029 + the round's data-capture fields: pins the FULL
 * nested key set and value types of buildServerMetrics/buildClientSnapshot against the
 * checked-in contract literals in fabric/src/test/resources/exporter-contract/ (shared
 * verbatim with PaperExporterContractTest — the lockstep twin-drift guard), plus the
 * disk-map-empty/generation-zero-fill/disabled-client variant shapes, probe parsing,
 * probe-hash capture, high-water/mspt window semantics, and JSONL append shape.
 */
class ExporterContractTest {

    // ---- Hand-rolled fakes (constructible common types; D11 — no mock framework) ----

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        int sendQueueSize;
        TestState(UUID uuid) { super(uuid, 4, 4); }
        @Override public String getPlayerName() { return "soak-player"; }
        @Override public int getSendQueueSize() { return this.sendQueueSize; }
    }

    private static final class TestProcessor extends OffThreadProcessor<TestState> {
        TestProcessor(Map<UUID, TestState> players) {
            super(players, null, false, null, 1, 0);  // memo off: these rigs pin the ttl=0 (pre-memo) read path
        }
        @Override
        protected boolean submitDiskRead(UUID playerUuid, String dimension, int cx, int cz, long order) {
            return true;
        }
        @Override
        protected boolean buildAndEnqueueColumnPayload(TestState state, int cx, int cz, String dimension,
                                                       long columnTimestamp, long submissionOrder,
                                                       byte[] sectionBytes, int estimatedBytes,
                                                       byte source) {
            return true;
        }
        /** Routes one column through enqueueLoadedColumn so the real timestamp cache is fed. */
        void putColumn(TestState state, int cx, int cz, byte[] bytes, long ts, String dimension) {
            enqueueLoadedColumn(state, new LoadedColumnData(cx, cz, bytes, bytes.length), ts, 1L, dimension, (byte) 2);
        }
    }

    private static final class TestDiskReader extends AbstractChunkDiskReader {
        int pendingResultCount;
        TestDiskReader() { super(1); }
        @Override public int getPendingResultCount() { return this.pendingResultCount; }
    }

    /** Mutable ServerSource fixture wiring the constructible collaborators. */
    private static final class FakeSource implements BenchmarkMetricsExporter.ServerSource {
        final TestProcessor processor;
        final TickDiagnostics tickDiag = new TickDiagnostics();
        TestDiskReader diskReader;
        final DirtyColumnTracker dirtyTracker = new DirtyColumnTracker();
        final DirtyContentFilter dirtyContentFilter = new DirtyContentFilter();
        final SharedBandwidthLimiter bandwidthLimiter = new SharedBandwidthLimiter(1_000_000);
        final Map<UUID, TestState> players;
        final TestState state;

        FakeSource(boolean withDiskReader) {
            this.players = new ConcurrentHashMap<>();
            this.state = new TestState(UUID.randomUUID());
            this.players.put(this.state.getPlayerUUID(), this.state);
            this.processor = new TestProcessor(this.players);
            this.diskReader = withDiskReader ? new TestDiskReader() : null;
        }

        @Override public OffThreadProcessor<?> processor() { return this.processor; }
        @Override public TickDiagnostics tickDiag() { return this.tickDiag; }
        @Override public AbstractChunkDiskReader diskReader() { return this.diskReader; }
        @Override public ChunkGenerationService generationService() { return null; }
        @Override public DirtyColumnTracker dirtyTracker() { return this.dirtyTracker; }
        @Override public DirtyContentFilter dirtyContentFilter() { return this.dirtyContentFilter; }
        @Override public SharedBandwidthLimiter bandwidthLimiter() { return this.bandwidthLimiter; }
        @Override public Collection<? extends AbstractPlayerRequestState<?>> players() {
            return this.players.values();
        }
    }

    /** Full-shape fixture matching the contract file: one player, disk reader present,
     *  one overworld timestamp-cache entry, two parked mailbox events. */
    private static FakeSource fullShapeSource() {
        var src = new FakeSource(true);
        src.processor.putColumn(src.state, 5, 5, new byte[]{1, 2, 3}, 100L, "minecraft:overworld");
        src.processor.notifyPlayerRemoved(UUID.randomUUID());
        src.processor.invalidateTimestamps("minecraft:overworld", new long[]{0L});
        return src;
    }

    @AfterEach
    void resetExporterStatics() {
        BenchmarkMetricsExporter.setProbesForTest("");
        BenchmarkMetricsExporter.resetHarnessCaptureForTest();
    }

    // ---- Contract flattening helpers (mirrored in PaperExporterContractTest) ----

    static void flatten(String prefix, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> map) {
            for (var e : map.entrySet()) {
                flatten(prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey(),
                        e.getValue(), out);
            }
        } else if (value instanceof List<?> list) {
            for (var element : list) flatten(prefix + "[]", element, out);
        } else {
            out.put(prefix, typeName(value));
        }
    }

    static String typeName(Object value) {
        if (value instanceof Long) return "long";
        if (value instanceof Integer) return "int";
        if (value instanceof Double) return "double";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof String) return "string";
        return value == null ? "null" : value.getClass().getName();
    }

    static String flattenedLines(Map<String, Object> snapshot) {
        var flat = new TreeMap<String, String>();
        flatten("", snapshot, flat);
        var sb = new StringBuilder();
        flat.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sb.toString().stripTrailing();
    }

    static String contractLines(String fileName) throws Exception {
        var lines = Files.readAllLines(locate("fabric/src/test/resources/exporter-contract/" + fileName));
        return String.join("\n", lines.stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList());
    }

    /** Walks up from the working directory to find a repo-relative path (same convention
     *  as paper's PluginYmlContractTest — lets both modules read the SAME contract file). */
    static Path locate(String repoRelative) {
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

    // ---- HD-021: full nested schema vs the checked-in contract literal ----

    @Test
    void serverSnapshotMatchesTheCheckedInContract() throws Exception {
        BenchmarkMetricsExporter.setProbesForTest("7:-3");
        var snapshot = BenchmarkMetricsExporter.buildServerMetrics(fullShapeSource());
        assertEquals(contractLines("server-snapshot.contract"), flattenedLines(snapshot),
                "server snapshot schema drifted from exporter-contract/server-snapshot.contract"
                        + " — schema changes must move lockstep with the Paper twin and check_soak.py");
    }

    @Test
    void clientSnapshotMatchesTheCheckedInContract() throws Exception {
        BenchmarkMetricsExporter.setProbesForTest("7:-3");
        var snapshot = BenchmarkMetricsExporter.buildClientSnapshot(
                new LodRequestManager(), true, 10L, 1000L, 0L, 2);
        assertEquals(contractLines("client-snapshot.contract"), flattenedLines(snapshot),
                "client snapshot schema drifted from exporter-contract/client-snapshot.contract");
    }

    // ---- Value mappings: distinct real counters land on their contract keys ----

    @Test
    void serverValueMappingsFlowFromTheRealCounters() {
        BenchmarkMetricsExporter.setProbesForTest("");
        var src = fullShapeSource();
        var diag = src.processor.getDiagnostics();
        diag.incrementReResolved();
        diag.incrementReResolved();
        for (int i = 0; i < 5; i++) diag.incrementRequestRouted();
        src.tickDiag.recordSectionSent(100);
        src.diskReader.getDiag().recordCompleted(3_000_000L);
        src.diskReader.getDiag().recordSuccess();
        src.bandwidthLimiter.recordSend(64);
        src.dirtyTracker.markDirty("minecraft:overworld", 1, 2);
        src.dirtyTracker.markDirty("minecraft:overworld", 1, 2); // re-mark: not net-new
        src.dirtyTracker.markDirty("minecraft:overworld", 3, 4);

        var m = BenchmarkMetricsExporter.buildServerMetrics(src);
        assertEquals(2L, section(m, "service").get("re_resolved"));
        assertEquals(5L, section(m, "service").get("requests_received"));
        assertEquals(1L, section(m, "service").get("columns_sent"));
        assertEquals(100L, section(m, "service").get("bytes_sent"));
        assertEquals(1L, section(m, "service").get("disk_resolved"));
        assertEquals(1L, section(m, "disk").get("completed"));
        assertEquals(3L, section(m, "disk").get("read_ms_total"));
        assertEquals(64L, section(m, "bandwidth").get("total_bytes"));
        assertEquals(2L, section(m, "dirty").get("marked_total"));
        assertEquals(2, section(m, "dirty").get("pending"));
        assertEquals(0L, section(m, "dirty").get("broadcast_positions"));
        assertEquals(2, m.get("mailbox_depth_hw"), "two parked mailbox events must be visible");
        assertEquals(Map.of("minecraft:overworld", 1),
                section(m, "tscache").get("size_per_dimension"));
        assertEquals(0L, section(m, "tscache").get("evictions"));
        assertEquals(0, section(m, "dedup").get("groups"));

        // Mark/drain conservation closes: marked_total == broadcast_positions + pending
        src.dirtyTracker.drainDirty("minecraft:overworld");
        var m2 = BenchmarkMetricsExporter.buildServerMetrics(src);
        assertEquals(2L, section(m2, "dirty").get("marked_total"));
        assertEquals(2L, section(m2, "dirty").get("broadcast_positions"));
        assertEquals(0, section(m2, "dirty").get("pending"));
    }

    // ---- HD-021 leg: disk map stays empty (not zero-filled) when no reader exists ----

    @Test
    void diskMapIsEmptyWhenNoReaderIsConfigured() {
        BenchmarkMetricsExporter.setProbesForTest("");
        var src = new FakeSource(false);
        var m = BenchmarkMetricsExporter.buildServerMetrics(src);
        assertTrue(section(m, "disk").isEmpty(), "disk map must be {} when the reader is null");
        assertEquals(0L, section(m, "service").get("disk_resolved"),
                "disk_resolved must zero-fill (it is a service-section key, not a disk-section key)");
    }

    // ---- HD-023: null generation service zero-fills every generation.* field ----

    @Test
    void generationDisabledZeroFillsEveryGenerationField() {
        BenchmarkMetricsExporter.setProbesForTest("");
        var gen = section(BenchmarkMetricsExporter.buildServerMetrics(fullShapeSource()), "generation");
        assertEquals(0L, gen.get("submitted"));
        assertEquals(0L, gen.get("completed"));
        assertEquals(0L, gen.get("timeouts"));
        assertEquals(0L, gen.get("removed_in_flight"));
        assertEquals(0, gen.get("active"));
        assertEquals(0, gen.get("active_hw"));
        assertEquals(6, gen.size(), "zero-fill must mirror the live branch's exact key count");
    }

    // ---- HD-024: disabled session (no manager) zero-fills with the identical key set ----

    @Test
    void disabledClientSessionZeroFillsManagerFieldsWithTheSameKeySet() {
        BenchmarkMetricsExporter.setProbesForTest("7:-3");
        var enabled = BenchmarkMetricsExporter.buildClientSnapshot(
                new LodRequestManager(), true, 10L, 1000L, 0L, 2);
        var disabled = BenchmarkMetricsExporter.buildClientSnapshot(null, false, 0L, 0L, 0L, 0);

        var enabledFlat = new TreeMap<String, String>();
        var disabledFlat = new TreeMap<String, String>();
        flatten("", enabled, enabledFlat);
        flatten("", disabled, disabledFlat);
        assertEquals(enabledFlat, disabledFlat,
                "the disabled session must satisfy the exact same schema (keys AND types)");

        assertEquals(false, disabled.get("server_enabled"));
        assertEquals("none", disabled.get("dimension"));
        assertEquals(-1L, section(disabled, "probes").get("7:-3"),
                "armed probes must read -1 when no manager exists");
        assertEquals(0L, section(disabled, "responses").get("columns"));
        assertEquals(0L, disabled.get("ingest_failures"));
        assertEquals(0L, disabled.get("requested_total"));
        assertEquals(0L, disabled.get("send_cycles"));
        assertEquals(0, disabled.get("effective_lod"));
        assertEquals(0, disabled.get("tracker_in_flight"));
        assertEquals(0, section(disabled, "scan").get("budget"));
        assertEquals(0, section(disabled, "columns").get("known"));
    }

    // ---- HD-025: probe spec parsing ----

    @Test
    void malformedProbeTokensAreSkippedAndValidOnesKept() {
        var probes = BenchmarkMetricsExporter.parseSoakProbes("8:8, banana,:5,4:-2x,,7:-3");
        assertEquals(2, probes.size(), "only the two well-formed x:z tokens survive");
        assertEquals("8:8", probes.get(0).getKey());
        assertEquals(8, probes.get(0).getValue()[0]);
        assertEquals(8, probes.get(0).getValue()[1]);
        assertEquals("7:-3", probes.get(1).getKey());
        assertEquals(7, probes.get(1).getValue()[0]);
        assertEquals(-3, probes.get(1).getValue()[1]);
    }

    @Test
    void emptyProbeSpecEmitsNoProbesOrProbeHashesKey() {
        BenchmarkMetricsExporter.setProbesForTest("");
        var client = BenchmarkMetricsExporter.buildClientSnapshot(null, false, 0L, 0L, 0L, 0);
        assertFalse(client.containsKey("probes"), "no probes key when the property is blank");
        var server = BenchmarkMetricsExporter.buildServerMetrics(fullShapeSource());
        assertFalse(server.containsKey("probe_hashes"), "no probe_hashes key when the property is blank");
    }

    // ---- probe_hashes (§3.9): FNV-1a of wire bytes at send, keyed by probed position ----

    @Test
    void probeHashesCaptureFnvOfWireBytesKeyedByProbedPositionOnly() {
        BenchmarkMetricsExporter.setProbesForTest("7:-3,8:8");
        // Canonical FNV-1a 64 vector: "foobar" -> 0x85944171f73967e8
        BenchmarkMetricsExporter.recordServedColumnBytes(7, -3, "foobar".getBytes(StandardCharsets.UTF_8));
        // Non-probed position must be ignored entirely
        BenchmarkMetricsExporter.recordServedColumnBytes(1, 1, new byte[]{42});

        var hashes = section(BenchmarkMetricsExporter.buildServerMetrics(fullShapeSource()), "probe_hashes");
        assertEquals(2, hashes.size(), "exactly the armed probes, nothing else");
        assertEquals(0x85944171f73967e8L, hashes.get("7:-3"));
        assertEquals(-1L, hashes.get("8:8"), "armed but never served reads -1");

        // Latest serve wins: canonical FNV-1a 64 vector "a" -> 0xaf63dc4c8601ec8c
        BenchmarkMetricsExporter.recordServedColumnBytes(7, -3, "a".getBytes(StandardCharsets.UTF_8));
        var rehashed = section(BenchmarkMetricsExporter.buildServerMetrics(fullShapeSource()), "probe_hashes");
        assertEquals(0xaf63dc4c8601ec8cL, rehashed.get("7:-3"));
    }

    // ---- High-water gauges (§3.4): bursts between snapshots survive, windows reset ----

    @Test
    void highWaterGaugesCaptureBurstsBetweenSnapshotsAndResetEachWindow() {
        BenchmarkMetricsExporter.setProbesForTest("");
        var src = fullShapeSource();

        src.diskReader.pendingResultCount = 7;
        src.state.sendQueueSize = 9;
        BenchmarkMetricsExporter.sampleServerGauges(src);
        src.diskReader.pendingResultCount = 3;
        src.state.sendQueueSize = 1;

        var m = BenchmarkMetricsExporter.buildServerMetrics(src);
        assertEquals(3, section(m, "disk").get("pending"), "point sample reads the current value");
        assertEquals(7, section(m, "disk").get("pending_hw"), "high-water keeps the sampled burst");
        @SuppressWarnings("unchecked")
        var row = (Map<String, Object>) ((List<?>) m.get("players")).get(0);
        assertEquals(1, row.get("send_queue"));
        assertEquals(9, row.get("send_queue_hw"));

        var m2 = BenchmarkMetricsExporter.buildServerMetrics(src);
        assertEquals(3, section(m2, "disk").get("pending_hw"),
                "window must reset: without new samples hw equals the snapshot's own point sample");
        @SuppressWarnings("unchecked")
        var row2 = (Map<String, Object>) ((List<?>) m2.get("players")).get(0);
        assertEquals(1, row2.get("send_queue_hw"));
    }

    // ---- mspt_avg_window (§3.2): tick-sampled wall time, -1 when the sampler is unwired ----

    @Test
    void msptAvgWindowCountsSamplerTicksAndReadsMinusOneUnwired() {
        BenchmarkMetricsExporter.setProbesForTest("");
        var src = fullShapeSource();

        var unwired = BenchmarkMetricsExporter.buildServerMetrics(src);
        assertEquals(-1.0, unwired.get("mspt_avg_window"),
                "no sampler ticks in the window must read -1, never 0 (0 would mean a perfect server)");

        for (int i = 0; i < 5; i++) BenchmarkMetricsExporter.sampleServerGauges(src);
        var wired = BenchmarkMetricsExporter.buildServerMetrics(src);
        double mspt = (Double) wired.get("mspt_avg_window");
        assertTrue(mspt > 0.0, "5 sampled ticks must yield a positive wall-ms-per-tick, got " + mspt);

        var drained = BenchmarkMetricsExporter.buildServerMetrics(src);
        assertEquals(-1.0, drained.get("mspt_avg_window"), "tick count must reset with the window");
    }

    // ---- HD-029: JSONL append shape ----

    @Test
    void appendJsonLineWritesOneCompactRowPerCallCreatesDirsAndAppends(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("nested").resolve("deeper").resolve("rows.jsonl");
        var row1 = new LinkedHashMap<String, Object>();
        row1.put("event", "a");
        row1.put("n", 1);
        var row2 = new LinkedHashMap<String, Object>();
        row2.put("event", "b");

        BenchmarkMetricsExporter.appendJsonLine(file, row1);
        assertTrue(Files.exists(file), "parent directories must be created on first use");
        BenchmarkMetricsExporter.appendJsonLine(file, row2);

        var lines = Files.readAllLines(file);
        assertEquals(2, lines.size(), "append must not truncate: both rows survive");
        assertEquals("{\"event\":\"a\",\"n\":1}", lines.get(0),
                "rows must be compact single-line JSON (pretty-printing breaks the checker's parser)");
        assertEquals("{\"event\":\"b\"}", lines.get(1));
    }

    // ---- §3.7: the ColumnTimestampCache counters feeding tscache.* ----

    @Test
    void timestampCacheEvictionCounterAndPerDimensionSizesTrackMutations() {
        var cache = new ColumnTimestampCache(2, 0);
        cache.put("d", 100L, 10L, 1L);
        cache.put("d", 200L, 20L, 2L);
        cache.put("d", 300L, 30L, 3L);
        assertEquals(Map.of("d", 3), cache.sizesPerDimension());
        assertEquals(0L, cache.getEvictionCount());

        assertEquals(1, cache.evictIfOversized());
        assertEquals(1L, cache.getEvictionCount(), "evictions accumulate the removed entry count");
        assertEquals(Map.of("d", 2), cache.sizesPerDimension(), "sizes reflect the post-eviction count");
        assertEquals(0L, cache.get("d", 100L), "the oldest-inserted entry is the one evicted");
        assertEquals(30L, cache.get("d", 300L));

        cache.invalidate("d", new long[]{300L});
        assertEquals(Map.of("d", 1), cache.sizesPerDimension());
        assertEquals(1L, cache.getEvictionCount(), "invalidation is not an eviction");
    }
}
