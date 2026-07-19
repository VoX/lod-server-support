package dev.vox.lss.paper.soak;

import com.google.gson.Gson;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.paper.PaperRequestProcessingService;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Paper twin of the server-side half of Fabric's BenchmarkMetricsExporter. Emits the
 * exact snapshot schema the soak checker contracts on (docs/planning/soak-test-design.md) so
 * scripts/check_soak.py judges Paper runs with zero changes — the key set and value
 * types are pinned by PaperExporterContractTest against the SAME checked-in contract
 * literal as the Fabric exporter (fabric/src/test/resources/exporter-contract/).
 * Dev-only: lives in the soak package excluded from the release shadowJar.
 */
public final class PaperSoakMetricsExporter {
    // JSONL rows must be one line each — never use a pretty-printing instance here
    private static final Gson COMPACT_GSON = new Gson();
    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    // FNV-1a 64 (same constants as the Fabric twin) for probe_hashes byte parity
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private PaperSoakMetricsExporter() {}

    // ---- Harness capture state (static: one server per JVM in dev harness runs). ----
    // High-water marks accumulate across sampleServerGauges() calls (the soak driver calls
    // it once per server tick) and reset each snapshot; a snapshot folds in one final
    // sample, so *_hw can never read below the point sample even when nothing ticks it.
    private static final ConcurrentHashMap<UUID, Integer> SEND_QUEUE_HW = new ConcurrentHashMap<>();
    private static final AtomicInteger DISK_PENDING_HW = new AtomicInteger();
    private static final AtomicInteger GEN_ACTIVE_HW = new AtomicInteger();
    private static final AtomicInteger MAILBOX_DEPTH_HW = new AtomicInteger();
    private static final AtomicInteger MSPT_TICKS_IN_WINDOW = new AtomicInteger();
    private static final AtomicLong MSPT_WINDOW_START_NANOS = new AtomicLong(System.nanoTime());
    // Latest FNV-1a 64 of the serialized wire bytes served for each probed position
    private static final ConcurrentHashMap<String, Long> PROBE_HASHES = new ConcurrentHashMap<>();

    /**
     * Per-position probe list parsed from {@code -Dlss.soak.probes=x:z,...} (chunk
     * coordinates). Empty when the property is unset/blank, so non-probe scenarios emit
     * no {@code probe_hashes} field at all. Parsed once at class init in production;
     * tests re-arm via {@link #setProbesForTest}.
     */
    private static volatile List<Map.Entry<String, int[]>> soakProbes =
            parseSoakProbes(System.getProperty("lss.soak.probes", ""));
    private static volatile Map<Long, String> probeTokensByPacked = tokensByPacked(soakProbes);

    static List<Map.Entry<String, int[]>> parseSoakProbes(String spec) {
        var probes = new ArrayList<Map.Entry<String, int[]>>();
        for (String token : spec.split(",")) {
            token = token.trim();
            if (token.isEmpty()) continue;
            int sep = token.indexOf(':');
            try {
                int cx = Integer.parseInt(token.substring(0, sep));
                int cz = Integer.parseInt(token.substring(sep + 1));
                probes.add(Map.entry(token, new int[]{cx, cz}));
            } catch (RuntimeException e) {
                LSSLogger.warn("[Soak] Ignoring malformed probe token '" + token + "' (want x:z)");
            }
        }
        return probes;
    }

    private static Map<Long, String> tokensByPacked(List<Map.Entry<String, int[]>> probes) {
        var tokens = new HashMap<Long, String>();
        for (var probe : probes) {
            int[] pos = probe.getValue();
            tokens.put(PositionUtil.packPosition(pos[0], pos[1]), probe.getKey());
        }
        return tokens;
    }

    /** Test seam: re-parse the probe spec and clear captured hashes. Production parses the
     *  property once at class init and never calls this. */
    static void setProbesForTest(String spec) {
        soakProbes = parseSoakProbes(spec);
        probeTokensByPacked = tokensByPacked(soakProbes);
        PROBE_HASHES.clear();
    }

    /** Test seam: reset high-water/mspt/probe-hash capture state between tests. */
    static void resetHarnessCaptureForTest() {
        SEND_QUEUE_HW.clear();
        DISK_PENDING_HW.set(0);
        GEN_ACTIVE_HW.set(0);
        MAILBOX_DEPTH_HW.set(0);
        MSPT_TICKS_IN_WINDOW.set(0);
        MSPT_WINDOW_START_NANOS.set(System.nanoTime());
        PROBE_HASHES.clear();
    }

    /**
     * Per-tick gauge sampler feeding the {@code *_hw} high-water fields and
     * {@code mspt_avg_window}. The Paper soak driver calls this once per server tick;
     * without it the high-water fields degrade to snapshot point samples and mspt
     * reads -1. Null-tolerant before the service starts (counts the tick only).
     */
    public static void sampleServerGauges(PaperRequestProcessingService service) {
        MSPT_TICKS_IN_WINDOW.incrementAndGet();
        if (service != null) foldGauges(service);
    }

    private static void foldGauges(PaperRequestProcessingService service) {
        var reader = service.getDiskReader();
        if (reader != null) DISK_PENDING_HW.accumulateAndGet(reader.getPendingResultCount(), Math::max);
        var gen = service.getGenerationService();
        if (gen != null) GEN_ACTIVE_HW.accumulateAndGet(gen.getActiveCount(), Math::max);
        MAILBOX_DEPTH_HW.accumulateAndGet(
                service.getOffThreadProcessor().getHarnessInternals().mailboxDepth(), Math::max);
        for (var state : service.getPlayers().values()) {
            SEND_QUEUE_HW.merge(state.getPlayerUUID(), state.getSendQueueSize(), Math::max);
        }
    }

    /**
     * Record the serialized wire bytes served for one column. Cheap no-op unless the
     * position is an armed {@code -Dlss.soak.probes} probe; then stores the FNV-1a 64 of
     * the bytes (latest serve wins) for the {@code probe_hashes} snapshot field. Call from
     * the send/enqueue path with the exact bytes that go on the wire — never with decoded
     * objects (PP-050 byte parity).
     */
    public static void recordServedColumnBytes(int cx, int cz, byte[] wireBytes) {
        var tokens = probeTokensByPacked;
        if (tokens.isEmpty() || wireBytes == null) return;
        String token = tokens.get(PositionUtil.packPosition(cx, cz));
        if (token == null) return;
        PROBE_HASHES.put(token, fnv1a64(wireBytes));
    }

    private static long fnv1a64(byte[] bytes) {
        long h = FNV_OFFSET;
        for (byte b : bytes) {
            h ^= (b & 0xFF);
            h *= FNV_PRIME;
        }
        return h;
    }

    /**
     * Full server diagnostic snapshot keyed by the soak checker contract. Cumulative
     * counters are service-scoped so they survive per-player state teardown on
     * kick/dimension change. Returns null when the processing service isn't running yet.
     * Emitting a snapshot closes the high-water/mspt window (single consumer: the driver).
     */
    public static Map<String, Object> buildServerMetrics(PaperRequestProcessingService service) {
        if (service == null) return null;
        foldGauges(service);

        var result = new LinkedHashMap<String, Object>();

        var diag = service.getOffThreadProcessor().getDiagnostics();
        var serviceMap = new LinkedHashMap<String, Object>();
        serviceMap.put("requests_received", diag.getTotalRequestsRouted());
        serviceMap.put("columns_sent", service.getTickDiag().getTotalSectionsSent());
        serviceMap.put("bytes_sent", service.getTickDiag().getTotalBytesSent());
        serviceMap.put("duplicate_skips", diag.getTotalDuplicateSkips());
        serviceMap.put("queue_full", diag.getTotalQueueFull());
        serviceMap.put("up_to_date", diag.getTotalUpToDate());
        serviceMap.put("in_memory", diag.getTotalInMemory());
        serviceMap.put("gen_drained", diag.getTotalGenDrained());
        var diskReader = service.getDiskReader();
        serviceMap.put("disk_resolved", diskReader != null ? diskReader.getDiag().getSuccessfulReadCount() : 0L);
        serviceMap.put("re_resolved", diag.getTotalReResolved());
        serviceMap.put("superseded", diag.getTotalSuperseded());
        serviceMap.put("range_filtered", diag.getTotalRangeFiltered());
        serviceMap.put("miss_dropped", diag.getTotalMissDropped());
        result.put("service", serviceMap);

        var diskMap = new LinkedHashMap<String, Object>();
        if (diskReader != null) {
            var dd = diskReader.getDiag();
            diskMap.put("submitted", dd.getSubmittedCount());
            diskMap.put("completed", dd.getCompletedCount());
            diskMap.put("not_found", dd.getNotFoundCount());
            diskMap.put("all_air", dd.getAllAirCount());
            diskMap.put("errors", dd.getErrorCount());
            diskMap.put("saturated", dd.getSaturationCount());
            diskMap.put("successful", dd.getSuccessfulReadCount());
            diskMap.put("pending", diskReader.getPendingResultCount());
            diskMap.put("pending_hw", DISK_PENDING_HW.get());
            diskMap.put("read_ms_total", dd.getTotalReadTimeNanos() / LSSConstants.NANOS_PER_MS);
            // Miss-memo rung hits (law A5's virtual not-founds) — sourced from the
            // processing diagnostics; the rung requires a reader, so the no-reader
            // disk-map-empty contract is preserved.
            diskMap.put("memo_hits", diag.getTotalMemoHits());
        }
        result.put("disk", diskMap);

        var genMap = new LinkedHashMap<String, Object>();
        var genService = service.getGenerationService();
        if (genService != null) {
            genMap.put("submitted", genService.getTotalSubmitted());
            genMap.put("completed", genService.getTotalCompleted());
            genMap.put("timeouts", genService.getTotalTimeouts());
            genMap.put("removed_in_flight", genService.getTotalRemovedInFlight());
            genMap.put("active", genService.getActiveCount());
            genMap.put("active_hw", GEN_ACTIVE_HW.get());
        } else {
            // enableChunkGeneration=false leaves the service null; the soak checker schema
            // requires every generation.* field, so zero-fill instead of emitting {}.
            genMap.put("submitted", 0L);
            genMap.put("completed", 0L);
            genMap.put("timeouts", 0L);
            genMap.put("removed_in_flight", 0L);
            genMap.put("active", 0);
            genMap.put("active_hw", 0);
        }
        // Ordering observability (the miss-memo pacing rules' success metrics) — from
        // ProcessingDiagnostics, so present even with generation disabled (schema-required):
        // order_gated = ordering refusals (frontier window + pacing rules), inversions =
        // completions that finished while a nearer ticket was outstanding.
        genMap.put("order_gated", diag.getTotalGenOrderGated());
        genMap.put("inversions", diag.getTotalGenCompletionInversions());
        result.put("generation", genMap);

        var dirtyMap = new LinkedHashMap<String, Object>();
        var dirtyTracker = service.getDirtyTracker();
        dirtyMap.put("pending", dirtyTracker.pendingCount());
        dirtyMap.put("broadcast_positions", dirtyTracker.getTotalDrained());
        dirtyMap.put("marked_total", dirtyTracker.getTotalMarked());
        // Paper detects dirt by event (PaperWorldHandler), not content-hash — no suppression
        // counter exists; zero-fill keeps the server snapshot schema identical to Fabric's.
        dirtyMap.put("suppressed_total", 0L);
        result.put("dirty", dirtyMap);

        var bandwidthMap = new LinkedHashMap<String, Object>();
        bandwidthMap.put("total_bytes", service.getBandwidthLimiter().getTotalBytesSent());
        result.put("bandwidth", bandwidthMap);

        var jvm = new LinkedHashMap<String, Object>();
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        jvm.put("heap_used_mb", heap.getUsed() / BYTES_PER_MB);
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gc.getCollectionCount();
            long t = gc.getCollectionTime();
            if (c >= 0) gcCount += c;
            if (t >= 0) gcTime += t;
        }
        jvm.put("gc_count", gcCount);
        jvm.put("gc_time_ms", gcTime);
        result.put("jvm", jvm);

        var internals = service.getOffThreadProcessor().getHarnessInternals();
        var tscacheMap = new LinkedHashMap<String, Object>();
        tscacheMap.put("size_per_dimension", new TreeMap<>(internals.tscacheSizePerDimension()));
        tscacheMap.put("evictions", internals.tscacheEvictions());
        result.put("tscache", tscacheMap);

        var dedupMap = new LinkedHashMap<String, Object>();
        dedupMap.put("groups", internals.dedupGroups());
        result.put("dedup", dedupMap);

        // Wall-time per tick over the window since the last snapshot (a stalled server
        // reads >> 50). -1 when no sampler ticks were observed (sampler not wired).
        long nowNanos = System.nanoTime();
        int ticks = MSPT_TICKS_IN_WINDOW.get();
        result.put("mspt_avg_window",
                ticks > 0 ? (nowNanos - MSPT_WINDOW_START_NANOS.get()) / 1_000_000.0 / ticks : -1.0);
        result.put("mailbox_depth_hw", MAILBOX_DEPTH_HW.get());

        var players = new ArrayList<Map<String, Object>>();
        for (var state : service.getPlayers().values()) {
            var p = new LinkedHashMap<String, Object>();
            p.put("name", state.getPlayerName());
            p.put("held_sync", state.getHeldSyncSlots());
            p.put("held_gen", state.getHeldGenSlots());
            p.put("send_queue", state.getSendQueueSize());
            p.put("send_queue_hw", SEND_QUEUE_HW.getOrDefault(state.getPlayerUUID(), state.getSendQueueSize()));
            p.put("sent", state.getTotalSectionsSent());
            p.put("bytes", state.getTotalBytesSent());
            p.put("requests", state.getTotalRequestsReceived());
            // Want-set entries still awaiting a routing pass on the processing thread.
            p.put("backlog", state.getBacklogSize());
            players.add(p);
        }
        result.put("players", players);

        var probes = soakProbes;
        if (!probes.isEmpty()) {
            var probeHashes = new LinkedHashMap<String, Object>();
            for (var probe : probes) {
                probeHashes.put(probe.getKey(), PROBE_HASHES.getOrDefault(probe.getKey(), -1L));
            }
            result.put("probe_hashes", probeHashes);
        }

        // Close the high-water/mspt window
        SEND_QUEUE_HW.clear();
        DISK_PENDING_HW.set(0);
        GEN_ACTIVE_HW.set(0);
        MAILBOX_DEPTH_HW.set(0);
        MSPT_TICKS_IN_WINDOW.set(0);
        MSPT_WINDOW_START_NANOS.set(nowNanos);

        return result;
    }

    /** Append one event row to a JSONL file, creating parent directories on first use. */
    public static void appendJsonLine(Path outputFile, Map<String, Object> row) {
        try {
            var parent = outputFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputFile, COMPACT_GSON.toJson(row) + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            LSSLogger.error("[Soak] Failed to append metrics row to " + outputFile, e);
        }
    }
}
