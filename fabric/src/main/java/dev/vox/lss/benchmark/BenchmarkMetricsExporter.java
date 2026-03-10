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
    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    private BenchmarkMetricsExporter() {}

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
            diskReaderMap.put("empty", dd.getEmptyCount());
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
        rateLimiting.put("queued", diag.getTotalQueued());
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
