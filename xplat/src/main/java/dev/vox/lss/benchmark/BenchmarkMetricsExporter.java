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
        dev.vox.lss.common.farplayers.FarPlayerBroadcastService farPlayerService();
        /** Null only in partial rigs; the summary group zero-fills then. */
        dev.vox.lss.common.region.RegionSummaryDiagnostics summaryDiagnostics();
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
            @Override public dev.vox.lss.common.farplayers.FarPlayerBroadcastService farPlayerService() {
                return service.getFarPlayerService();
            }
            @Override public dev.vox.lss.common.region.RegionSummaryDiagnostics summaryDiagnostics() {
                var rs = service.getRegionSummaries();
                return rs == null ? null : rs.diagnostics();
            }
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
        // Compressed columns (protocol 19): SHIPPED payload volume (codec-1 frames) next
        // to the raw-denominated bytes_sent, plus the per-payload codec split. Law A2
        // stays raw==raw on both ends; wire_bytes is the observed-bandwidth match.
        serviceMap.put("wire_bytes", src.tickDiag().getTotalWireBytesSent());
        serviceMap.put("cols_zstd", diag.getTotalColumnsCompressed());
        serviceMap.put("cols_raw", diag.getTotalColumnsRaw());
        serviceMap.put("duplicate_skips", diag.getTotalDuplicateSkips());
        serviceMap.put("queue_full", diag.getTotalQueueFull());
        serviceMap.put("up_to_date", diag.getTotalUpToDate());
        serviceMap.put("in_memory", diag.getTotalInMemory());
        serviceMap.put("gen_drained", diag.getTotalGenDrained());
        var diskReader = src.diskReader();
        serviceMap.put("disk_resolved", diskReader != null ? diskReader.getDiag().getSuccessfulReadCount() : 0L);
        serviceMap.put("re_resolved", diag.getTotalReResolved());
        serviceMap.put("grace_skipped", diag.getTotalGraceSkipped());
        serviceMap.put("superseded", diag.getTotalSuperseded());
        serviceMap.put("range_filtered", diag.getTotalRangeFiltered());
        serviceMap.put("miss_dropped", diag.getTotalMissDropped());
        // send-pacing-plan.md v3: the pacer's soak-visible receipt — inertness on
        // loopback is EMPIRICAL, so a moved guard-soak baseline needs attribution.
        serviceMap.put("paced_ticks", src.tickDiag().getPacedTicksTotal());
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
            // DiskReadGate refusals (disk-read-concurrency-gate-plan.md): never part of
            // the submitted/completed partition — its own monotonic counter.
            diskMap.put("gated", dd.getGatedCount());
            diskMap.put("gate_stops", dd.getGateStopsCount());
            diskMap.put("successful", dd.getSuccessfulReadCount());
            diskMap.put("pending", diskReader.getPendingResultCount());
            diskMap.put("pending_hw", DISK_PENDING_HW.get());
            diskMap.put("read_ms_total", dd.getTotalReadTimeNanos() / LSSConstants.NANOS_PER_MS);
            // Miss-memo rung hits (law A5's virtual not-founds) — sourced from the
            // processing diagnostics; the rung requires a reader, so the no-reader
            // disk-map-empty contract is preserved.
            diskMap.put("memo_hits", diag.getTotalMemoHits());
            // Header freshness rung hits (region-summary-sync-plan.md P1): ts>0 reads
            // answered from the region header without region IO. A mechanism counter —
            // excluded from the submitted/completed partition like store hits.
            diskMap.put("header_hits", dd.getHeaderHitsCount());
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
        // Ordering observability (the miss-memo pacing rules' success metrics) — from
        // ProcessingDiagnostics, so present even with generation disabled (schema-required):
        // order_gated = ordering refusals (frontier window + pacing rules), inversions =
        // completions that finished while a nearer ticket was outstanding.
        genMap.put("order_gated", diag.getTotalGenOrderGated());
        genMap.put("inversions", diag.getTotalGenCompletionInversions());
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

        // Far players (E1, FARP §3.2): OWN counter group — the frames ride a dedicated
        // lane, so these bytes are deliberately NOT part of service.bytes_sent/
        // wire_bytes (which feed soak_report's cross-identity audits against client
        // received counters). All-zero on every soak/benchmark run by construction (the
        // client property gate keeps harness clients unsubscribed — E1 baseline
        // neutrality, asserted by the checker's far-players zero check).
        var farPlayers = src.farPlayerService();
        var farMap = new LinkedHashMap<String, Object>();
        farMap.put("subscribers", (long) farPlayers.subscriberCount());
        farMap.put("roster_frames", farPlayers.rosterFramesSent());
        farMap.put("update_frames", farPlayers.updateFramesSent());
        farMap.put("entries", farPlayers.entriesSent());
        farMap.put("suppressed", farPlayers.suppressedUnchanged());
        farMap.put("bytes", farPlayers.bytesSent());
        result.put("far_players", farMap);

        // Region summaries (P2 §8): OWN counter group on the dedicated send lane —
        // deliberately NOT part of service.bytes_sent/wire_bytes (the far-player lane
        // precedent). All-zero on every soak/benchmark run by construction (harness
        // clients are property-gated off requesting — the summary-inert check mirrors
        // the far-players one). refresh_ms_hw is a GAUGE (high-water), not monotonic.
        var summary = src.summaryDiagnostics();
        var summaryMap = new LinkedHashMap<String, Object>();
        summaryMap.put("requests", summary == null ? 0L : summary.getRequests());
        summaryMap.put("range_filtered", summary == null ? 0L : summary.getRangeFiltered());
        summaryMap.put("frames", summary == null ? 0L : summary.getFrames());
        summaryMap.put("tiles_known", summary == null ? 0L : summary.getTilesKnown());
        summaryMap.put("tiles_never_clean", summary == null ? 0L : summary.getTilesNeverClean());
        summaryMap.put("tiles_no_region", summary == null ? 0L : summary.getTilesNoRegion());
        summaryMap.put("bytes", summary == null ? 0L : summary.getBytes());
        summaryMap.put("refresh_ms_hw", summary == null ? 0L : summary.getRefreshMsMax());
        summaryMap.put("stamps_frames", summary == null ? 0L : summary.getStampsFrames());
        summaryMap.put("stamps_entries", summary == null ? 0L : summary.getStampsEntries());
        summaryMap.put("stamps_bytes", summary == null ? 0L : summary.getStampsBytes());
        result.put("summary", summaryMap);

        // LOD store (docs/planning/lod-store-implementation-plan.md): counters live on the
        // processor unconditionally (all-zero while lodStore=off) so this group's shape is
        // identical across the kill-switch A/B arms. `queue` (batcher depth) is a DRAIN
        // gauge (must be 0 at quiescence endpoints); the byte fields are plain gauges.
        var storeDiag = src.processor().getStoreDiagnostics();
        var storeMap = new LinkedHashMap<String, Object>();
        storeMap.put("hits", storeDiag.getHits());
        storeMap.put("misses", storeDiag.getMisses());
        storeMap.put("deposits", storeDiag.getDeposits());
        storeMap.put("deposit_drops", storeDiag.getDepositDrops());
        storeMap.put("deposit_skips", storeDiag.getDepositSkips());
        storeMap.put("errors", storeDiag.getErrors());
        storeMap.put("sweep_drops", storeDiag.getSweepDrops());
        storeMap.put("backfill_reads", storeDiag.getBackfillReads());
        storeMap.put("backfill_deposits", storeDiag.getBackfillDeposits());
        storeMap.put("backfill_skips", storeDiag.getBackfillSkips());
        storeMap.put("queue", storeDiag.getQueueDepth());
        storeMap.put("db_bytes", storeDiag.getDbBytes());
        storeMap.put("wal_bytes", storeDiag.getWalBytes());
        storeMap.put("checkpoint_ms_max", storeDiag.getCheckpointMsMax());
        storeMap.put("read_avg_us", storeDiag.getReadAvgMicros());
        storeMap.put("read_p95_us", storeDiag.getReadP95Micros());
        result.put("store", storeMap);

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

        // Declared-and-unanswered (the awaiting-answer set), replaced per scan.
        result.put("tracker_in_flight", manager != null ? manager.getPendingCount() : 0);

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
            diskReaderMap.put("memo_hits", diag.getTotalMemoHits());
            diskReaderMap.put("header_hits", dd.getHeaderHitsCount());
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

        // Backpressure — v17 has no rate-limited bounce; a want that cannot be served this pass is
        // dropped silently (superseded) or filtered out of range, and the client re-declares it.
        var backpressure = new LinkedHashMap<String, Object>();
        backpressure.put("queue_full", diag.getTotalQueueFull());
        backpressure.put("superseded", diag.getTotalSuperseded());
        backpressure.put("range_filtered", diag.getTotalRangeFiltered());
        result.put("backpressure", backpressure);

        // Bandwidth
        var bandwidth = new LinkedHashMap<String, Object>();
        bandwidth.put("total_bytes_sent", service.getBandwidthLimiter().getTotalBytesSent());
        bandwidth.put("total_wire_bytes_sent", service.getTickDiag().getTotalWireBytesSent());
        result.put("bandwidth", bandwidth);

        // LOD store (second exporter site — the benchmark server.json; the warm-join gate
        // reads hits/misses + disk.submitted from here). All-zero while lodStore=off.
        var storeDiag = service.getOffThreadProcessor().getStoreDiagnostics();
        var store = new LinkedHashMap<String, Object>();
        store.put("hits", storeDiag.getHits());
        store.put("misses", storeDiag.getMisses());
        store.put("deposits", storeDiag.getDeposits());
        store.put("deposit_drops", storeDiag.getDepositDrops());
        store.put("deposit_skips", storeDiag.getDepositSkips());
        store.put("errors", storeDiag.getErrors());
        store.put("sweep_drops", storeDiag.getSweepDrops());
        store.put("backfill_reads", storeDiag.getBackfillReads());
        store.put("backfill_deposits", storeDiag.getBackfillDeposits());
        store.put("backfill_skips", storeDiag.getBackfillSkips());
        store.put("queue", storeDiag.getQueueDepth());
        store.put("db_bytes", storeDiag.getDbBytes());
        store.put("wal_bytes", storeDiag.getWalBytes());
        store.put("checkpoint_ms_max", storeDiag.getCheckpointMsMax());
        store.put("read_avg_us", storeDiag.getReadAvgMicros());
        store.put("read_p95_us", storeDiag.getReadP95Micros());
        result.put("store", store);

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
