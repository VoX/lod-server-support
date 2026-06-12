package dev.vox.lss.paper.soak;

import com.google.gson.Gson;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.paper.PaperRequestProcessingService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Paper twin of the server-side half of Fabric's BenchmarkMetricsExporter. Emits the
 * exact snapshot schema the soak checker contracts on (docs/soak-test-design.md) so
 * scripts/check_soak.py judges Paper runs with zero changes. Dev-only: lives in the
 * soak package excluded from the release shadowJar.
 */
public final class PaperSoakMetricsExporter {
    // JSONL rows must be one line each — never use a pretty-printing instance here
    private static final Gson COMPACT_GSON = new Gson();

    private PaperSoakMetricsExporter() {}

    /**
     * Full server diagnostic snapshot keyed by the soak checker contract. Cumulative
     * counters are service-scoped so they survive per-player state teardown on
     * kick/dimension change. Returns null when the processing service isn't running yet.
     */
    public static Map<String, Object> buildServerMetrics(PaperRequestProcessingService service) {
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

        var players = new ArrayList<Map<String, Object>>();
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
