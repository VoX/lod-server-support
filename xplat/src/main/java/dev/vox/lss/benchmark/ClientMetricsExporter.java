package dev.vox.lss.benchmark;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.client.LSSClientNetworking;
import dev.vox.lss.networking.client.LodRequestManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The CLIENT half of the benchmark/soak metrics exporter, split out of
 * {@link BenchmarkMetricsExporter} (sponge-support-plan.md §5.4) so the server half
 * compiles on a server-only classpath — the Sponge soak driver reuses it verbatim, the
 * schema-parity mechanism Paper had to replace with a textual twin. Same package, so the
 * soak probe list and the JSON writer stay package-private. Behavior is unchanged: these
 * methods moved, byte-for-byte in their bodies.
 */
public final class ClientMetricsExporter {
    private ClientMetricsExporter() {}

    /**
     * Client diagnostic snapshot keyed by the soak checker contract. `received_columns`
     * and `received_bytes` are the wire-level (pre-dimension-guard) counters used by
     * delivery conservation; `responses.*` are the post-guard request metrics used by
     * request conservation. Manager-scoped fields are zero-filled when no
     * LodRequestManager exists (server replied enabled=false) so the disabled session
     * still satisfies the checker schema, with `server_enabled` naming the cause.
     */
    public static Map<String, Object> buildClientSnapshot() {
        return buildClientSnapshot(LSSClientNetworking.getRequestManager(),
                LSSClientNetworking.isServerEnabled(),
                LSSClientNetworking.getColumnsReceived(),
                LSSClientNetworking.getBytesReceived(),
                LSSClientNetworking.getWireBytesReceived(),
                LSSClientNetworking.getColumnsDropped(),
                LSSClientNetworking.getQueuedColumnCount(),
                LSSClientNetworking.getQueuedColumnBytes(),
                LSSClientNetworking.getSessionVersion());
    }

    /** Schema-owning overload (test seam — the public method binds the live static reads). */
    static Map<String, Object> buildClientSnapshot(LodRequestManager manager, boolean serverEnabled,
                                                   long receivedColumns, long receivedBytes,
                                                   long wireReceivedBytes,
                                                   long dropped, int queued, long queuedBytes,
                                                   int sessionVersion) {
        var result = new LinkedHashMap<String, Object>();
        result.put("server_enabled", serverEnabled);
        // C6 (C3's deferred m8/m11): the ESTABLISHED session's protocol version — a
        // dialect-lever soak that silently degraded to another rung must red the
        // checker's expect_session_version, not pass format-blind laws on the wrong
        // dialect. 0 = no SessionConfig accepted yet. Additive key.
        result.put("session_version", sessionVersion);
        result.put("received_columns", receivedColumns);
        result.put("received_bytes", receivedBytes);
        // Shipped (codec-1 frame) volume next to the raw-denominated received_bytes —
        // the client half of the wire_bytes observability (compressed columns, v19).
        result.put("wire_received_bytes", wireReceivedBytes);
        result.put("dropped", dropped);
        // The decode/ingest queue (ClientColumnProcessor) — unrelated to the request loop, which
        // no longer queues anything (the want-set is scanned and sent in the same tick).
        // Both halt-gate inputs are exported: LodRequestManager.haltedByBackpressure binds on
        // COUNT or BYTES, so a stalled scan with queued=0 is only explainable with the byte
        // gauge visible (the 2026-07-29 early-stop diagnosis needed exactly this field).
        result.put("queued", queued);
        result.put("queued_bytes", queuedBytes);

        result.put("dimension", manager != null ? manager.getCurrentDimensionId() : "none");
        result.put("effective_lod", manager != null ? manager.getEffectiveLodDistanceChunks() : 0);

        var responses = new LinkedHashMap<String, Object>();
        responses.put("columns", manager != null ? manager.getTotalColumnsReceived() : 0L);
        responses.put("up_to_date", manager != null ? manager.getTotalUpToDate() : 0L);
        responses.put("not_generated", manager != null ? manager.getTotalNotGenerated() : 0L);
        result.put("responses", responses);

        result.put("ingest_failures", manager != null ? manager.getTotalIngestFailures() : 0L);
        // Want-set semantics: every scan RE-DECLARES every unsatisfied position, so this counts
        // declarations, not distinct positions — one slow column contributes once per scan.
        // It is a send-side volume gauge, not a distinct-work measure.
        result.put("requested_total", manager != null ? manager.getTotalPositionsRequested() : 0L);
        result.put("send_cycles", manager != null ? manager.getTotalSendCycles() : 0L);

        var columns = new LinkedHashMap<String, Object>();
        columns.put("known", manager != null ? manager.getReceivedColumnCount() : 0);
        columns.put("empty", manager != null ? manager.getEmptyColumnCount() : 0);
        // All-air/ingest-parked positions resolved this session without a server timestamp
        // (delivery-honesty: they no longer fabricate a >0 stamp, so they left "known").
        columns.put("satisfied", manager != null ? manager.getSatisfiedColumnCount() : 0);
        columns.put("dirty", manager != null ? manager.getDirtyColumnCount() : 0);
        result.put("columns", columns);

        var scan = new LinkedHashMap<String, Object>();
        scan.put("confirmed", manager != null ? manager.getConfirmedRing() : 0);
        scan.put("reopened", manager != null ? manager.getReopenedRingCount() : 0);
        scan.put("ring", manager != null ? manager.getScanRing() : 0);
        scan.put("missing_vanilla", manager != null ? manager.getMissingVanillaChunks() : 0);
        scan.put("budget", manager != null ? manager.getLastBudget() : 0);
        scan.put("queued", manager != null ? manager.getLastQueued() : 0);
        // Adaptive-cadence liveness (docs/planning/adaptive-scan-cadence-design.md §8's
        // acceptance criterion): fast-fire count — ~0 across a warm backfill means a gate
        // is suppressing the feature. Additive key; check_soak.py validates top-level
        // client keys only.
        scan.put("fast", manager != null ? manager.getFastScans() : 0L);
        // Manual column-rate cap (client-column-rate-cap-design.md): spacing-gate refusals.
        // Additive key, 0 whenever the cap is off (the shipped default).
        scan.put("rate_gated", manager != null ? manager.getRateGated() : 0L);
        // Section-store fast path (quadtree-client-state-plan.md): rings confirmed
        // leaf-wise without a position walk, and reopened-ring valve overflows (the
        // phase-0 B1 field measurement). Additive keys; check_soak validates top-level
        // client keys only.
        scan.put("quad_ring_skips", manager != null ? manager.getQuadRingSkips() : 0L);
        scan.put("valve_trips", manager != null ? manager.getValveTrips() : 0L);
        // Region-major scan (region-scan-plan.md §9): additive keys, 0 on the legacy arm.
        scan.put("region_span", manager != null ? manager.getRegionSpan() : 0);
        scan.put("near_rings", manager != null ? manager.getNearRings() : 0);
        scan.put("region_skips", manager != null ? manager.getRegionSkips() : 0L);
        scan.put("audit_heals", manager != null ? manager.getAuditHeals() : 0L);
        result.put("scan", scan);

        // Region summaries (region-summary-sync-plan.md §6/§8): the client half's
        // attributability counters — tiles by disposition + columns validated. Additive
        // top-level group (check_soak treats unknown client keys as an aggregated
        // warning until registered; the KNOWN set carries it).
        var summary = new LinkedHashMap<String, Object>();
        summary.put("tiles_clean", manager != null ? manager.getSummaryTilesClean() : 0L);
        summary.put("tiles_stale", manager != null ? manager.getSummaryTilesStale() : 0L);
        summary.put("tiles_unknown", manager != null ? manager.getSummaryTilesUnknown() : 0L);
        summary.put("tiles_no_region", manager != null ? manager.getSummaryTilesNoRegion() : 0L);
        summary.put("columns_validated", manager != null ? manager.getSummaryColumnsValidated() : 0L);
        summary.put("stamps_applied", manager != null ? manager.getSummaryStampsApplied() : 0L);
        summary.put("stamps_ignored", manager != null ? manager.getSummaryStampsIgnored() : 0L);
        result.put("summary", summary);

        // Map ingestion (map-ingestion plan §3.8/§4.4): the xaero/journeymap groups,
        // present only when the map mod is installed (the live gates' instruments).
        var maps = dev.vox.lss.compat.ModCompat.mapIngestionMetrics();
        result.putAll(maps);
        // The map's debt and its fill (issue #380), beside the map groups and like them
        // only with a map mod installed.
        if (!maps.isEmpty() && manager != null) result.put("map_fill", manager.mapFillMetrics());

        // Declared-and-unanswered (the awaiting-answer set), replaced per scan.
        result.put("tracker_in_flight", manager != null ? manager.getPendingCount() : 0);

        // Request→receive round-trip latency distribution (-1.0 doubles when no samples yet)
        var rtt = new LinkedHashMap<String, Object>();
        rtt.put("p50_ms", manager != null ? manager.getRttP50Ms() : -1.0);
        rtt.put("p95_ms", manager != null ? manager.getRttP95Ms() : -1.0);
        result.put("rtt", rtt);

        var probes = BenchmarkMetricsExporter.soakProbes();
        if (!probes.isEmpty()) {
            var probeMap = new LinkedHashMap<String, Object>();
            for (var probe : probes) {
                int[] pos = probe.getValue();
                probeMap.put(probe.getKey(),
                        manager != null ? manager.getColumnTimestamp(pos[0], pos[1]) : -1L);
            }
            result.put("probes", probeMap);
        }

        return result;
    }

    public static Map<String, Object> buildClientMetrics() {
        var result = new LinkedHashMap<String, Object>();
        result.put("timestamp", Instant.now().toString());

        result.put("columns_received", LSSClientNetworking.getColumnsReceived());
        result.put("bytes_received", LSSClientNetworking.getBytesReceived());
        result.put("wire_received_bytes", LSSClientNetworking.getWireBytesReceived());

        LodRequestManager manager = LSSClientNetworking.getRequestManager();
        if (manager != null) {
            result.put("total_up_to_date", manager.getTotalUpToDate());
            result.put("total_not_generated", manager.getTotalNotGenerated());
            result.put("send_cycles", manager.getTotalSendCycles());
            result.put("positions_requested", manager.getTotalPositionsRequested());
        }

        return result;
    }

    public static void exportClient(Path outputFile) {
        writeClientSnapshot(outputFile, buildClientMetrics());
    }

    public static void writeClientSnapshot(Path outputFile, Map<String, Object> snapshot) {
        BenchmarkMetricsExporter.writeJson(outputFile, snapshot);
        LSSLogger.info("[Benchmark] Client metrics written to " + outputFile);
    }

}
