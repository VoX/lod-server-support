package dev.vox.lss.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.client.LSSClientNetworking;
import dev.vox.lss.networking.client.LodRequestManager;
import dev.vox.lss.networking.server.LSSServerNetworking;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BenchmarkMetricsExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // JSONL rows must be one line each — the pretty-printing instance is unusable for them
    private static final Gson COMPACT_GSON = new Gson();
    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    private BenchmarkMetricsExporter() {}

    /**
     * Full server diagnostic snapshot keyed by the soak checker contract
     * (docs/soak-test-design.md). Cumulative counters here are
     * service-scoped so they survive per-player state teardown on kick/dimension change.
     * Returns null when the processing service isn't running yet.
     */
    public static Map<String, Object> buildServerMetrics() {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) return null;

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
        var reader = service.getDiskReader();
        serviceMap.put("disk_resolved", reader != null ? reader.getDiag().getSuccessfulReadCount() : 0L);
        serviceMap.put("sync_rate_limited", diag.getTotalSyncRateLimited());
        serviceMap.put("gen_rate_limited", diag.getTotalGenRateLimited());
        serviceMap.put("re_resolved", diag.getTotalReResolved());
        result.put("service", serviceMap);

        var diskMap = new LinkedHashMap<String, Object>();
        var diskReader = service.getDiskReader();
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
        } else {
            // enableChunkGeneration=false leaves the service null; the soak checker schema
            // requires every generation.* field, so zero-fill instead of emitting {}.
            genMap.put("submitted", 0L);
            genMap.put("completed", 0L);
            genMap.put("timeouts", 0L);
            genMap.put("removed_in_flight", 0L);
            genMap.put("active", 0);
        }
        result.put("generation", genMap);

        var dirtyMap = new LinkedHashMap<String, Object>();
        var dirtyTracker = service.getDirtyTracker();
        dirtyMap.put("pending", dirtyTracker.pendingCount());
        dirtyMap.put("broadcast_positions", dirtyTracker.getTotalDrained());
        result.put("dirty", dirtyMap);

        var bandwidthMap = new LinkedHashMap<String, Object>();
        bandwidthMap.put("total_bytes", service.getBandwidthLimiter().getTotalBytesSent());
        result.put("bandwidth", bandwidthMap);

        var players = new java.util.ArrayList<Map<String, Object>>();
        for (var state : service.getPlayers().values()) {
            var p = new LinkedHashMap<String, Object>();
            p.put("name", state.getPlayerName());
            p.put("held_sync", state.getHeldSyncSlots());
            p.put("held_gen", state.getHeldGenSlots());
            p.put("send_queue", state.getSendQueueSize());
            p.put("sent", state.getTotalSectionsSent());
            p.put("bytes", state.getTotalBytesSent());
            p.put("requests", state.getTotalRequestsReceived());
            p.put("incoming_dropped", state.getTotalIncomingDropped());
            players.add(p);
        }
        result.put("players", players);

        return result;
    }

    /**
     * Per-position probe list parsed from {@code -Dlss.soak.probes=x:z,...} (chunk
     * coordinates). Empty when the property is unset/blank, so non-probe scenarios emit
     * no {@code probes} field at all.
     */
    private static final java.util.List<Map.Entry<String, int[]>> SOAK_PROBES = parseSoakProbes();

    private static java.util.List<Map.Entry<String, int[]>> parseSoakProbes() {
        String spec = System.getProperty("lss.soak.probes", "");
        var probes = new java.util.ArrayList<Map.Entry<String, int[]>>();
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

    /**
     * Client diagnostic snapshot keyed by the soak checker contract. `received_columns`
     * and `received_bytes` are the wire-level (pre-dimension-guard) counters used by
     * delivery conservation; `responses.*` are the post-guard request metrics used by
     * request conservation. Manager-scoped fields are zero-filled when no
     * LodRequestManager exists (server replied enabled=false) so the disabled session
     * still satisfies the checker schema, with `server_enabled` naming the cause.
     */
    public static Map<String, Object> buildClientSnapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("server_enabled", LSSClientNetworking.isServerEnabled());
        result.put("received_columns", LSSClientNetworking.getColumnsReceived());
        result.put("received_bytes", LSSClientNetworking.getBytesReceived());
        result.put("dropped", LSSClientNetworking.getColumnsDropped());
        result.put("queued", LSSClientNetworking.getQueuedColumnCount());

        LodRequestManager manager = LSSClientNetworking.getRequestManager();
        result.put("dimension", manager != null ? manager.getCurrentDimensionId() : "none");

        var responses = new LinkedHashMap<String, Object>();
        responses.put("columns", manager != null ? manager.getTotalColumnsReceived() : 0L);
        responses.put("up_to_date", manager != null ? manager.getTotalUpToDate() : 0L);
        responses.put("not_generated", manager != null ? manager.getTotalNotGenerated() : 0L);
        responses.put("rate_limited", manager != null ? manager.getTotalRateLimited() : 0L);
        result.put("responses", responses);

        result.put("requested_total", manager != null ? manager.getTotalPositionsRequested() : 0L);
        result.put("send_cycles", manager != null ? manager.getTotalSendCycles() : 0L);

        var columns = new LinkedHashMap<String, Object>();
        columns.put("known", manager != null ? manager.getReceivedColumnCount() : 0);
        columns.put("empty", manager != null ? manager.getEmptyColumnCount() : 0);
        columns.put("dirty", manager != null ? manager.getDirtyColumnCount() : 0);
        result.put("columns", columns);

        var scan = new LinkedHashMap<String, Object>();
        scan.put("confirmed", manager != null ? manager.getConfirmedRing() : 0);
        scan.put("ring", manager != null ? manager.getScanRing() : 0);
        scan.put("missing_vanilla", manager != null ? manager.getMissingVanillaChunks() : 0);
        result.put("scan", scan);

        result.put("tracker_in_flight", manager != null ? manager.getPendingCount() : 0);

        if (!SOAK_PROBES.isEmpty()) {
            var probes = new LinkedHashMap<String, Object>();
            for (var probe : SOAK_PROBES) {
                int[] pos = probe.getValue();
                probes.put(probe.getKey(),
                        manager != null ? manager.getColumnTimestamp(pos[0], pos[1]) : -1L);
            }
            result.put("probes", probes);
        }

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

    public static void exportServer(Path outputFile, long durationSeconds) {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) {
            LSSLogger.warn("[Benchmark] No RequestProcessingService available, skipping server export");
            return;
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("timestamp", Instant.now().toString());
        result.put("duration_seconds", durationSeconds);

        // Aggregate per-player totals
        long totalSent = 0;
        long totalBytes = 0;
        for (var state : service.getPlayers().values()) {
            totalSent += state.getTotalSectionsSent();
            totalBytes += state.getTotalBytesSent();
        }

        long uptime = service.getUptimeSeconds();

        // Throughput
        var throughput = new LinkedHashMap<String, Object>();
        throughput.put("total_sections_sent", totalSent);
        throughput.put("total_bytes_sent", totalBytes);
        throughput.put("sections_per_second", uptime > 0 ? (double) totalSent / uptime : 0);
        throughput.put("bytes_per_second", uptime > 0 ? (double) totalBytes / uptime : 0);
        result.put("throughput", throughput);

        // Sources
        var diag = service.getOffThreadProcessor().getDiagnostics();
        var sources = new LinkedHashMap<String, Object>();
        sources.put("in_memory", diag.getTotalInMemory());
        sources.put("up_to_date", diag.getTotalUpToDate());
        sources.put("generation", diag.getTotalGenDrained());
        var diskReader = service.getDiskReader();
        sources.put("disk_read", diskReader != null ? diskReader.getDiag().getSuccessfulReadCount() : 0);
        result.put("sources", sources);

        // Disk reader
        var diskReaderMap = new LinkedHashMap<String, Object>();
        if (diskReader != null) {
            var dd = diskReader.getDiag();
            diskReaderMap.put("submitted", dd.getSubmittedCount());
            diskReaderMap.put("completed", dd.getCompletedCount());
            diskReaderMap.put("not_found", dd.getNotFoundCount());
            diskReaderMap.put("all_air", dd.getAllAirCount());
            diskReaderMap.put("errors", dd.getErrorCount());
            long completed = dd.getCompletedCount();
            double avgMs = completed > 0 ? (dd.getTotalReadTimeNanos() / (double) completed) / LSSConstants.NANOS_PER_MS : 0;
            diskReaderMap.put("avg_read_time_ms", avgMs);
            diskReaderMap.put("saturation_events", dd.getSaturationCount());
        }
        result.put("disk_reader", diskReaderMap);

        // Generation
        var genMap = new LinkedHashMap<String, Object>();
        var genService = service.getGenerationService();
        if (genService != null) {
            genMap.put("submitted", genService.getTotalSubmitted());
            genMap.put("completed", genService.getTotalCompleted());
            genMap.put("timeouts", genService.getTotalTimeouts());
        }
        result.put("generation", genMap);

        // Rate limiting
        var rateLimiting = new LinkedHashMap<String, Object>();
        rateLimiting.put("sync_rate_limited", diag.getTotalSyncRateLimited());
        rateLimiting.put("gen_rate_limited", diag.getTotalGenRateLimited());
        rateLimiting.put("queue_full", diag.getTotalQueueFull());
        result.put("rate_limiting", rateLimiting);

        // Bandwidth
        var bandwidth = new LinkedHashMap<String, Object>();
        bandwidth.put("total_bytes_sent", service.getBandwidthLimiter().getTotalBytesSent());
        result.put("bandwidth", bandwidth);

        // JVM
        var jvm = new LinkedHashMap<String, Object>();
        var memBean = ManagementFactory.getMemoryMXBean();
        var heap = memBean.getHeapMemoryUsage();
        jvm.put("heap_used_mb", heap.getUsed() / BYTES_PER_MB);
        jvm.put("heap_max_mb", heap.getMax() / BYTES_PER_MB);
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

        writeJson(outputFile, result);
        LSSLogger.info("[Benchmark] Server metrics written to " + outputFile);
    }

    public static Map<String, Object> buildClientMetrics() {
        var result = new LinkedHashMap<String, Object>();
        result.put("timestamp", Instant.now().toString());

        result.put("columns_received", LSSClientNetworking.getColumnsReceived());
        result.put("bytes_received", LSSClientNetworking.getBytesReceived());

        LodRequestManager manager = LSSClientNetworking.getRequestManager();
        if (manager != null) {
            result.put("total_up_to_date", manager.getTotalUpToDate());
            result.put("total_not_generated", manager.getTotalNotGenerated());
            result.put("total_rate_limited", manager.getTotalRateLimited());
            result.put("send_cycles", manager.getTotalSendCycles());
            result.put("positions_requested", manager.getTotalPositionsRequested());
        }

        return result;
    }

    public static void exportClient(Path outputFile) {
        writeClientSnapshot(outputFile, buildClientMetrics());
    }

    public static void writeClientSnapshot(Path outputFile, Map<String, Object> snapshot) {
        writeJson(outputFile, snapshot);
        LSSLogger.info("[Benchmark] Client metrics written to " + outputFile);
    }

    private static void writeJson(Path outputFile, Map<String, Object> data) {
        try {
            var parent = outputFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputFile, GSON.toJson(data));
        } catch (IOException e) {
            LSSLogger.error("[Benchmark] Failed to write metrics to " + outputFile, e);
        }
    }
}
