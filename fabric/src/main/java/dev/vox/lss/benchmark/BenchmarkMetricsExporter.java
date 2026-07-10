package dev.vox.lss.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import dev.vox.lss.common.processing.AbstractPlayerRequestState;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.TickDiagnostics;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import dev.vox.lss.networking.server.DirtyContentFilter;
import dev.vox.lss.networking.client.LSSClientNetworking;
import dev.vox.lss.networking.client.LodRequestManager;
import dev.vox.lss.networking.server.ChunkGenerationService;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.networking.server.RequestProcessingService;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkMetricsExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // JSONL rows must be one line each — the pretty-printing instance is unusable for them
    private static final Gson COMPACT_GSON = new Gson();
    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    // FNV-1a 64 (same constants as DirtyContentFilter) for probe_hashes byte parity
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private BenchmarkMetricsExporter() {}

    /**
     * Value sources for one server snapshot row. The production {@link #buildServerMetrics()}
     * adapts the live {@link RequestProcessingService}; the schema contract tests supply
     * hand-rolled fakes built from the constructible common types (test seam, D9 — every
     * method maps to a distinct type, so the adapter wiring is compile-pinned).
     */
    interface ServerSource {
        OffThreadProcessor<?> processor();
        TickDiagnostics tickDiag();
        /** Null when disk reading is disabled — the {@code disk} map is then emitted empty. */
        AbstractChunkDiskReader diskReader();
        /** Null when {@code enableChunkGeneration=false} — {@code generation.*} is then zero-filled. */
        ChunkGenerationService generationService();
        DirtyColumnTracker dirtyTracker();
        /** Fabric's content-hash dirty filter; Paper has none (event-driven) and zero-fills. */
        DirtyContentFilter dirtyContentFilter();
        SharedBandwidthLimiter bandwidthLimiter();
        Collection<? extends AbstractPlayerRequestState<?>> players();
    }

    static ServerSource asSource(RequestProcessingService service) {
        return new ServerSource() {
            @Override public OffThreadProcessor<?> processor() { return service.getOffThreadProcessor(); }
            @Override public TickDiagnostics tickDiag() { return service.getTickDiag(); }
            @Override public AbstractChunkDiskReader diskReader() { return service.getDiskReader(); }
            @Override public ChunkGenerationService generationService() { return service.getGenerationService(); }
            @Override public DirtyColumnTracker dirtyTracker() { return service.getDirtyTracker(); }
            @Override public DirtyContentFilter dirtyContentFilter() { return service.getDirtyContentFilter(); }
            @Override public SharedBandwidthLimiter bandwidthLimiter() { return service.getBandwidthLimiter(); }
            @Override public Collection<? extends AbstractPlayerRequestState<?>> players() {
                return service.getPlayers().values();
            }
        };
    }

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
     * no {@code probes}/{@code probe_hashes} field at all. Parsed once at class init in
     * production; tests re-arm via {@link #setProbesForTest}.
     */
    private static volatile List<Map.Entry<String, int[]>> soakProbes =
            parseSoakProbes(System.getProperty("lss.soak.probes", ""));
    private static volatile Map<Long, String> probeTokensByPacked = tokensByPacked(soakProbes);

    static List<Map.Entry<String, int[]>> parseSoakProbes(String spec) {
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
     * {@code mspt_avg_window}. The soak driver calls this once per server tick; without it
     * the high-water fields degrade to snapshot point samples and mspt reads -1.
     */
    public static void sampleServerGauges() {
        var service = LSSServerNetworking.getRequestService();
        sampleServerGauges(service != null ? asSource(service) : null);
    }

    static void sampleServerGauges(ServerSource src) {
        MSPT_TICKS_IN_WINDOW.incrementAndGet();
        if (src != null) foldGauges(src);
    }

    private static void foldGauges(ServerSource src) {
        var reader = src.diskReader();
        if (reader != null) DISK_PENDING_HW.accumulateAndGet(reader.getPendingResultCount(), Math::max);
        var gen = src.generationService();
        if (gen != null) GEN_ACTIVE_HW.accumulateAndGet(gen.getActiveCount(), Math::max);
        MAILBOX_DEPTH_HW.accumulateAndGet(src.processor().getHarnessInternals().mailboxDepth(), Math::max);
        for (var state : src.players()) {
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
     * Full server diagnostic snapshot keyed by the soak checker contract
     * (docs/planning/soak-test-design.md). Cumulative counters here are
     * service-scoped so they survive per-player state teardown on kick/dimension change.
     * Returns null when the processing service isn't running yet.
     */
    public static Map<String, Object> buildServerMetrics() {
        var service = LSSServerNetworking.getRequestService();
        if (service == null) return null;
        return buildServerMetrics(asSource(service));
    }

    /**
     * Schema-owning overload — the exact key set and value types are pinned by
     * ExporterContractTest against the checked-in contract literal, lockstep with
     * PaperSoakMetricsExporter (same contract file). Emitting a snapshot closes the
     * high-water/mspt window (single consumer: the soak driver).
     */
    static Map<String, Object> buildServerMetrics(ServerSource src) {
        foldGauges(src);

        var result = new LinkedHashMap<String, Object>();

        var diag = src.processor().getDiagnostics();
        var serviceMap = new LinkedHashMap<String, Object>();
        serviceMap.put("requests_received", diag.getTotalRequestsRouted());
        serviceMap.put("columns_sent", src.tickDiag().getTotalSectionsSent());
        serviceMap.put("bytes_sent", src.tickDiag().getTotalBytesSent());
        serviceMap.put("duplicate_skips", diag.getTotalDuplicateSkips());
        serviceMap.put("queue_full", diag.getTotalQueueFull());
        serviceMap.put("up_to_date", diag.getTotalUpToDate());
        serviceMap.put("in_memory", diag.getTotalInMemory());
        serviceMap.put("gen_drained", diag.getTotalGenDrained());
        var diskReader = src.diskReader();
        serviceMap.put("disk_resolved", diskReader != null ? diskReader.getDiag().getSuccessfulReadCount() : 0L);
        serviceMap.put("sync_rate_limited", diag.getTotalSyncRateLimited());
        serviceMap.put("gen_rate_limited", diag.getTotalGenRateLimited());
        serviceMap.put("re_resolved", diag.getTotalReResolved());
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
        }
        result.put("disk", diskMap);

        var genMap = new LinkedHashMap<String, Object>();
        var genService = src.generationService();
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
        result.put("generation", genMap);

        var dirtyMap = new LinkedHashMap<String, Object>();
        var dirtyTracker = src.dirtyTracker();
        dirtyMap.put("pending", dirtyTracker.pendingCount());
        dirtyMap.put("broadcast_positions", dirtyTracker.getTotalDrained());
        dirtyMap.put("marked_total", dirtyTracker.getTotalMarked());
        var contentFilter = src.dirtyContentFilter();
        dirtyMap.put("suppressed_total", contentFilter != null ? contentFilter.getTotalSuppressed() : 0L);
        result.put("dirty", dirtyMap);

        var bandwidthMap = new LinkedHashMap<String, Object>();
        bandwidthMap.put("total_bytes", src.bandwidthLimiter().getTotalBytesSent());
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

        var internals = src.processor().getHarnessInternals();
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

        var players = new java.util.ArrayList<Map<String, Object>>();
        for (var state : src.players()) {
            var p = new LinkedHashMap<String, Object>();
            p.put("name", state.getPlayerName());
            p.put("held_sync", state.getHeldSyncSlots());
            p.put("held_gen", state.getHeldGenSlots());
            p.put("send_queue", state.getSendQueueSize());
            p.put("send_queue_hw", SEND_QUEUE_HW.getOrDefault(state.getPlayerUUID(), state.getSendQueueSize()));
            p.put("sent", state.getTotalSectionsSent());
            p.put("bytes", state.getTotalBytesSent());
            p.put("requests", state.getTotalRequestsReceived());
            p.put("incoming_dropped", state.getTotalIncomingDropped());
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
                LSSClientNetworking.getColumnsDropped(),
                LSSClientNetworking.getQueuedColumnCount());
    }

    /** Schema-owning overload (test seam — the public method binds the live static reads). */
    static Map<String, Object> buildClientSnapshot(LodRequestManager manager, boolean serverEnabled,
                                                   long receivedColumns, long receivedBytes,
                                                   long dropped, int queued) {
        var result = new LinkedHashMap<String, Object>();
        result.put("server_enabled", serverEnabled);
        result.put("received_columns", receivedColumns);
        result.put("received_bytes", receivedBytes);
        result.put("dropped", dropped);
        result.put("queued", queued);

        result.put("dimension", manager != null ? manager.getCurrentDimensionId() : "none");
        result.put("effective_lod", manager != null ? manager.getEffectiveLodDistanceChunks() : 0);

        var responses = new LinkedHashMap<String, Object>();
        responses.put("columns", manager != null ? manager.getTotalColumnsReceived() : 0L);
        responses.put("up_to_date", manager != null ? manager.getTotalUpToDate() : 0L);
        responses.put("not_generated", manager != null ? manager.getTotalNotGenerated() : 0L);
        responses.put("rate_limited", manager != null ? manager.getTotalRateLimited() : 0L);
        result.put("responses", responses);

        result.put("ingest_failures", manager != null ? manager.getTotalIngestFailures() : 0L);
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
        scan.put("ring", manager != null ? manager.getScanRing() : 0);
        scan.put("missing_vanilla", manager != null ? manager.getMissingVanillaChunks() : 0);
        scan.put("budget", manager != null ? manager.getLastBudget() : 0);
        scan.put("sync_queued", manager != null ? manager.getLastSyncQueued() : 0);
        scan.put("gen_queued", manager != null ? manager.getLastGenQueued() : 0);
        result.put("scan", scan);

        result.put("tracker_in_flight", manager != null ? manager.getPendingCount() : 0);
        // Positions parked in the RequestQueue between scanner accept and send drip
        result.put("request_queue", manager != null ? manager.getQueueRemaining() : 0);

        // Request→receive round-trip latency distribution (-1.0 doubles when no samples yet)
        var rtt = new LinkedHashMap<String, Object>();
        rtt.put("p50_ms", manager != null ? manager.getRttP50Ms() : -1.0);
        rtt.put("p95_ms", manager != null ? manager.getRttP95Ms() : -1.0);
        result.put("rtt", rtt);

        var probes = soakProbes;
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
