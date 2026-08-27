package dev.vox.lss.common;

import dev.vox.lss.common.processing.AbstractPlayerRequestState;
import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import dev.vox.lss.common.processing.ProcessingDiagnostics;
import dev.vox.lss.common.processing.TickDiagnostics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class DiagnosticsFormatter {

    /** {@code outboundPending}/{@code outboundHighWater} are netty outbound-buffer depths
     *  in bytes, -1 = no signal (never "empty"). See the elytra-wall investigation §8.3.
     *  (The {@code sendDeferrals}/{@code ceilBytes} components died with the fixed
     *  outbound ceiling, 2026-08-13 — deletion review #2.) */
    public record PlayerDiag(
            String name, int sendQueue, int maxSendQueue,
            int pendingSync, int pendingGen,
            long sent, long bytes,
            long outboundPending, long outboundHighWater,
            long yielded, double pingFactor, long paced
    ) {
    }

    public record DiagData(
            boolean enabled, int lodDist,
            long bwPerPlayer, long bwGlobal,
            long uptimeSec, long totalSent, long totalBytes,
            long cumInMem, long cumUtd, long cumGen, long cumReResolved, long cumGraceSkipped,
            long diskCompleted,
            String tickDiagnostics,
            String diskReaderDiagnostics,
            String generationDiagnostics, boolean generationEnabled,
            long genOrderGated, long genInversions,
            long bwTotal,
            long bwWindowRate,
            List<PlayerDiag> players,
            String v16Line,
            String v18Line,
            String xrayLine,
            String moveTraceLine,
            String yieldLine,
            String farPlayersLine,
            String summaryLine,
            long wireTotal, long colsZstd, long colsRaw,
            String gateLine
    ) {
        /** Pre-service-gate full shape (no Gate line) — keeps existing
         *  constructions/tests intact. */
        public DiagData(boolean enabled, int lodDist, long bwPerPlayer, long bwGlobal,
                        long uptimeSec, long totalSent, long totalBytes,
                        long cumInMem, long cumUtd, long cumGen, long cumReResolved,
                        long cumGraceSkipped,
                        long diskCompleted, String tickDiagnostics, String diskReaderDiagnostics,
                        String generationDiagnostics, boolean generationEnabled,
                        long genOrderGated, long genInversions,
                        long bwTotal, long bwWindowRate, List<PlayerDiag> players,
                        String v16Line, String v18Line, String xrayLine,
                        String moveTraceLine, String yieldLine, String farPlayersLine,
                        String summaryLine,
                        long wireTotal, long colsZstd, long colsRaw) {
            this(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent, totalBytes,
                    cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped, diskCompleted,
                    tickDiagnostics, diskReaderDiagnostics, generationDiagnostics,
                    generationEnabled, genOrderGated, genInversions, bwTotal, bwWindowRate,
                    players, v16Line, v18Line, xrayLine, moveTraceLine, yieldLine,
                    farPlayersLine, summaryLine, wireTotal, colsZstd, colsRaw, null);
        }
        /** Pre-region-summary full shape (no Summary line) — keeps existing
         *  constructions/tests intact. */
        public DiagData(boolean enabled, int lodDist, long bwPerPlayer, long bwGlobal,
                        long uptimeSec, long totalSent, long totalBytes,
                        long cumInMem, long cumUtd, long cumGen, long cumReResolved,
                        long cumGraceSkipped,
                        long diskCompleted, String tickDiagnostics, String diskReaderDiagnostics,
                        String generationDiagnostics, boolean generationEnabled,
                        long genOrderGated, long genInversions,
                        long bwTotal, long bwWindowRate, List<PlayerDiag> players,
                        String v16Line, String v18Line, String xrayLine,
                        String moveTraceLine, String yieldLine, String farPlayersLine,
                        long wireTotal, long colsZstd, long colsRaw) {
            this(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent, totalBytes,
                    cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped, diskCompleted,
                    tickDiagnostics, diskReaderDiagnostics, generationDiagnostics,
                    generationEnabled, genOrderGated, genInversions, bwTotal, bwWindowRate,
                    players, v16Line, v18Line, xrayLine, moveTraceLine, yieldLine,
                    farPlayersLine, null, wireTotal, colsZstd, colsRaw);
        }
        /** Pre-move-tracer full shape (no MoveTrace line) — keeps existing
         *  constructions/tests intact. */
        public DiagData(boolean enabled, int lodDist, long bwPerPlayer, long bwGlobal,
                        long uptimeSec, long totalSent, long totalBytes,
                        long cumInMem, long cumUtd, long cumGen, long cumReResolved,
                        long cumGraceSkipped,
                        long diskCompleted, String tickDiagnostics, String diskReaderDiagnostics,
                        String generationDiagnostics, boolean generationEnabled,
                        long genOrderGated, long genInversions,
                        long bwTotal, long bwWindowRate, List<PlayerDiag> players,
                        String v16Line, String v18Line, String xrayLine,
                        long wireTotal, long colsZstd, long colsRaw) {
            this(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent, totalBytes,
                    cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped, diskCompleted,
                    tickDiagnostics, diskReaderDiagnostics, generationDiagnostics,
                    generationEnabled, genOrderGated, genInversions, bwTotal, bwWindowRate,
                    players, v16Line, v18Line, xrayLine, null, null, null, wireTotal, colsZstd, colsRaw);
        }
        /** Pre-compressed-columns full shape (wire counters zero) — keeps existing
         *  constructions/tests intact. */
        public DiagData(boolean enabled, int lodDist, long bwPerPlayer, long bwGlobal,
                        long uptimeSec, long totalSent, long totalBytes,
                        long cumInMem, long cumUtd, long cumGen, long cumReResolved,
                        long cumGraceSkipped,
                        long diskCompleted, String tickDiagnostics, String diskReaderDiagnostics,
                        String generationDiagnostics, boolean generationEnabled,
                        long genOrderGated, long genInversions,
                        long bwTotal, long bwWindowRate, List<PlayerDiag> players,
                        String v16Line, String xrayLine) {
            this(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent, totalBytes,
                    cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped, diskCompleted,
                    tickDiagnostics, diskReaderDiagnostics, generationDiagnostics,
                    generationEnabled, genOrderGated, genInversions, bwTotal, bwWindowRate,
                    players, v16Line, null, xrayLine, 0L, 0L, 0L);
        }
        /** Pre-v16-compat shape (no shim/xray lines) — keeps existing constructions/tests intact. */
        public DiagData(boolean enabled, int lodDist, long bwPerPlayer, long bwGlobal,
                        long uptimeSec, long totalSent, long totalBytes,
                        long cumInMem, long cumUtd, long cumGen, long cumReResolved,
                        long cumGraceSkipped,
                        long diskCompleted, String tickDiagnostics, String diskReaderDiagnostics,
                        String generationDiagnostics, boolean generationEnabled,
                        long genOrderGated, long genInversions,
                        long bwTotal, long bwWindowRate, List<PlayerDiag> players) {
            this(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent, totalBytes,
                    cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped, diskCompleted,
                    tickDiagnostics, diskReaderDiagnostics, generationDiagnostics,
                    generationEnabled, genOrderGated, genInversions, bwTotal, bwWindowRate,
                    players, null, null);
        }

        /** Attach the v16 compat shim's one-line summary (null when the shim is untouched —
         *  the line is omitted from the rendered diagnostics). Passes every component —
         *  incl. moveTraceLine — through the canonical constructor, so the with-chain
         *  commutes in every order (review B-3). */
        public DiagData withV16Line(String line) {
            return new DiagData(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent,
                    totalBytes, cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped,
                    diskCompleted, tickDiagnostics, diskReaderDiagnostics, generationDiagnostics,
                    generationEnabled, genOrderGated, genInversions, bwTotal, bwWindowRate,
                    players, line, v18Line, xrayLine, moveTraceLine, yieldLine, farPlayersLine, summaryLine, wireTotal, colsZstd, colsRaw, gateLine);
        }

        /** Attach the v18 compat rung's one-line summary (null when the rung is untouched —
         *  the line is omitted, mirroring the v16 line). Renders right after the v16 slot. */
        public DiagData withV18Line(String line) {
            return new DiagData(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent,
                    totalBytes, cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped,
                    diskCompleted, tickDiagnostics, diskReaderDiagnostics, generationDiagnostics,
                    generationEnabled, genOrderGated, genInversions, bwTotal, bwWindowRate,
                    players, v16Line, line, xrayLine, moveTraceLine, yieldLine, farPlayersLine, summaryLine, wireTotal, colsZstd, colsRaw, gateLine);
        }

        /** Attach the x-ray masking one-line summary (always shown when non-null — the off
         *  state is what an admin testing masking needs to see). */
        public DiagData withXrayLine(String line) {
            return new DiagData(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent,
                    totalBytes, cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped,
                    diskCompleted, tickDiagnostics, diskReaderDiagnostics,
                    generationDiagnostics, generationEnabled, genOrderGated, genInversions,
                    bwTotal, bwWindowRate, players, v16Line, v18Line, line, moveTraceLine,
                    yieldLine, farPlayersLine, summaryLine, wireTotal, colsZstd, colsRaw, gateLine);
        }

        /** Attach the move-desync tracer's one-line summary (null while the tracer is
         *  inactive — the line is omitted; present-only-while-active is the tracer's whole
         *  diag contract, move-desync-tracer-plan.md §2). Renders after the xray slot. */
        public DiagData withMoveTraceLine(String line) {
            return new DiagData(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent,
                    totalBytes, cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped,
                    diskCompleted, tickDiagnostics, diskReaderDiagnostics,
                    generationDiagnostics, generationEnabled, genOrderGated, genInversions,
                    bwTotal, bwWindowRate, players, v16Line, v18Line, xrayLine, line,
                    yieldLine, farPlayersLine, summaryLine, wireTotal, colsZstd, colsRaw, gateLine);
        }

        /** Attach the transport yield's one-line summary (null while the gate is unarmed
         *  and has never fired — the line is omitted; yield plan §5 A-7). Renders after
         *  the MoveTrace slot. */
        public DiagData withYieldLine(String line) {
            return new DiagData(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent,
                    totalBytes, cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped,
                    diskCompleted, tickDiagnostics, diskReaderDiagnostics,
                    generationDiagnostics, generationEnabled, genOrderGated, genInversions,
                    bwTotal, bwWindowRate, players, v16Line, v18Line, xrayLine, moveTraceLine,
                    line, farPlayersLine, summaryLine, wireTotal, colsZstd, colsRaw, gateLine);
        }

        /** Attach the far-player one-line summary (E1 — null while the feature is inert
         *  or untouched: no subscribers ever, no frames sent; the line is omitted, so
         *  soak/benchmark diag output is byte-unchanged). Renders after the yield slot. */
        public DiagData withFarPlayersLine(String line) {
            return new DiagData(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent,
                    totalBytes, cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped,
                    diskCompleted, tickDiagnostics, diskReaderDiagnostics,
                    generationDiagnostics, generationEnabled, genOrderGated, genInversions,
                    bwTotal, bwWindowRate, players, v16Line, v18Line, xrayLine, moveTraceLine,
                    yieldLine, line, summaryLine, wireTotal, colsZstd, colsRaw, gateLine);
        }

        /** Attach the region-summary one-line counter group (null while the feature is
         *  untouched — no request ever arrived; the line is omitted, so soak/benchmark
         *  diag output is byte-unchanged). Renders after the far-players slot. */
        public DiagData withSummaryLine(String line) {
            return new DiagData(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent,
                    totalBytes, cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped,
                    diskCompleted, tickDiagnostics, diskReaderDiagnostics,
                    generationDiagnostics, generationEnabled, genOrderGated, genInversions,
                    bwTotal, bwWindowRate, players, v16Line, v18Line, xrayLine, moveTraceLine,
                    yieldLine, farPlayersLine, line, wireTotal, colsZstd, colsRaw, gateLine);
        }

        /** Attach the service-gate one-liner (null while {@code requireServicePermission}
         *  is off — the line is omitted, so default-install diag output is
         *  byte-unchanged; service-permission-gate-plan.md §2.5). Renders after the
         *  Summary slot. */
        public DiagData withGateLine(String line) {
            return new DiagData(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent,
                    totalBytes, cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped,
                    diskCompleted, tickDiagnostics, diskReaderDiagnostics,
                    generationDiagnostics, generationEnabled, genOrderGated, genInversions,
                    bwTotal, bwWindowRate, players, v16Line, v18Line, xrayLine, moveTraceLine,
                    yieldLine, farPlayersLine, summaryLine, wireTotal, colsZstd, colsRaw, line);
        }
    }

    private DiagnosticsFormatter() {}

    public static List<String> formatDiagnostics(DiagData d) {
        var lines = new ArrayList<String>();
        lines.add("=== " + Brand.shortName() + " LOD Diagnostics ===");

        // Config
        lines.add(String.format(
                "Config: enabled=%s, lodDist=%d, bw/player=%s/s, bw/global=%s/s",
                d.enabled, d.lodDist,
                formatBytes(d.bwPerPlayer),
                formatBytes(d.bwGlobal)
        ));

        // Throughput
        double secRate = d.uptimeSec > 0 ? (double) d.totalSent / d.uptimeSec : 0;
        double byteRate = d.uptimeSec > 0 ? (double) d.totalBytes / d.uptimeSec : 0;
        lines.add(String.format(
                "Throughput: sent=%d (%s), rate=%s sections/s (%s/s), uptime=%s",
                d.totalSent, formatBytes(d.totalBytes),
                formatRate(secRate), formatBytes((long) byteRate),
                formatUptime(d.uptimeSec)
        ));

        // Sources (total). grace_skipped = crossing re-asks absorbed by the departure
        // grace — each would otherwise have re-resolved (a redundant disk read + send)
        // and counted re_resolved instead.
        lines.add(String.format(
                "Sources (total): in_mem=%d, disk=%d, up_to_date=%d, gen=%d, re_resolved=%d, grace_skipped=%d",
                d.cumInMem, Math.max(0, d.diskCompleted), d.cumUtd, d.cumGen, d.cumReResolved,
                d.cumGraceSkipped
        ));

        // Sources (tick)
        lines.add("Sources (tick): " + d.tickDiagnostics);

        // DiskReader
        lines.add("DiskReader: " + d.diskReaderDiagnostics);

        // Generation. order_gated = generation-ordering refusals, aggregated across the
        // nearer-read hold, the cohort span, and the (damped) frontier spread gate;
        // inversions = completions that finished while a NEARER ticket was outstanding
        // (the platform scheduler's far-before-near signature, e.g. C2ME).
        if (d.generationEnabled) {
            lines.add("Generation: " + d.generationDiagnostics
                    + String.format(", order_gated=%d, inversions=%d",
                            d.genOrderGated, d.genInversions));
        } else {
            lines.add("Generation: disabled");
        }

        // v16 compat shim (omitted while untouched — most servers never see a legacy client)
        if (d.v16Line != null) {
            lines.add(d.v16Line);
        }

        // v18 compat rung (omitted while untouched — same stance as the v16 line)
        if (d.v18Line != null) {
            lines.add(d.v18Line);
        }

        // X-ray masking (docs/planning/antixray-compat-design.md §3 Diagnostics)
        if (d.xrayLine != null) {
            lines.add(d.xrayLine);
        }

        // Move-desync tracer (present ONLY while active — move-desync-tracer-plan.md §2)
        if (d.moveTraceLine != null) {
            lines.add(d.moveTraceLine);
        }

        // Transport yield (present while armed or after any withheld tick — yield plan §5)
        if (d.yieldLine != null) {
            lines.add(d.yieldLine);
        }
        if (d.farPlayersLine != null) {
            lines.add(d.farPlayersLine);
        }
        // Region summaries (present only once a request has arrived — soak/benchmark
        // clients are property-gated off, so harness diag output is byte-unchanged).
        if (d.summaryLine != null) {
            lines.add(d.summaryLine);
        }
        // Service gate (present only while requireServicePermission is armed —
        // service-permission-gate-plan.md §2.5).
        if (d.gateLine != null) {
            lines.add(d.gateLine);
        }

        // Bandwidth. total = the RAW-denominated counted volume (the limiter's charge —
        // client decode work scales with it); wire = SHIPPED payload bytes (codec-1
        // frames), the number that matches observed network bandwidth (the elytra
        // investigation's §1 confusion). cols = per-payload codec outcomes.
        lines.add(String.format("Bandwidth: %s/s / %s/s global (%s total, %s wire, cols zstd=%d raw=%d)",
                formatBytes(d.bwWindowRate), formatBytes(d.bwGlobal),
                formatBytes(d.bwTotal), formatBytes(d.wireTotal), d.colsZstd, d.colsRaw));

        // Per-player
        for (var p : d.players) {
            double pRate = d.uptimeSec > 0 ? (double) p.sent / d.uptimeSec : 0;
            lines.add(String.format(
                    "  %s: sq=%d/%d, psync=%d, pgen=%d, sent=%d (%s), rate=%s/s, obuf=%s/%s, pingf=%.2f, yielded=%d, paced=%d",
                    p.name, p.sendQueue, p.maxSendQueue,
                    p.pendingSync, p.pendingGen,
                    p.sent, formatBytes(p.bytes),
                    formatRate(pRate),
                    formatOutbound(p.outboundPending), formatOutbound(p.outboundHighWater),
                    // pingf= : Mechanism B's receipt — 1.00 = no cut.
                    p.pingFactor,
                    // yielded/paced: transport-yield + send-pacer receipts (mechanism
                    // counters, never loss signals).
                    p.yielded, p.paced
            ));
        }

        return lines;
    }

    /** The /lsslod stats per-player line, shared by both platform command handlers. */
    public static String formatStatsLine(AbstractPlayerRequestState<?> state) {
        return String.format(
                "%s: handshake=%s, sent=%d sections (%s), pending_sync=%d, pending_gen=%d, send_queue=%d, requests=%d",
                state.getPlayerName(),
                state.hasCompletedHandshake() ? "yes" : "no",
                state.getTotalSectionsSent(),
                formatBytes(state.getTotalBytesSent()),
                state.getHeldSyncSlots(),
                state.getHeldGenSlots(),
                state.getSendQueueSize(),
                state.getTotalRequestsReceived()
        );
    }

    /** Collect the /lsslod diag data from common-typed sources, shared by both platforms.
     *  A null {@code diskReader} (reader not running) renders the DiskReader line as
     *  "disabled" and contributes zero completed reads — the command must answer in every
     *  service state, never throw at the admin. {@code storeMode}/{@code storeDiag} render
     *  the LOD-store TOKEN on the DiskReader line (never a new line — the golden-order
     *  tests pin the line list); a null {@code storeDiag} (bare test rigs) omits it. */
    public static DiagData collectDiagData(boolean enabled, int lodDistanceChunks,
                                           long bwPerPlayer, long bwGlobal, int sendQueueLimitPerPlayer,
                                           long uptimeSec, String tickDiagnostics, long windowBandwidthRate,
                                           long serviceTotalSent, long serviceTotalBytes,
                                           long serviceWireBytes,
                                           ProcessingDiagnostics diag, AbstractChunkDiskReader diskReader,
                                           SharedBandwidthLimiter bwLimiter,
                                           String generationDiagnosticsOrNull,
                                           dev.vox.lss.common.store.LodStoreMode storeMode,
                                           dev.vox.lss.common.store.LodStoreDiagnostics storeDiag,
                                           Collection<? extends AbstractPlayerRequestState<?>> states) {
        // The Throughput totals are SERVICE-scoped (TickDiagnostics — they exist to survive
        // per-player state teardown): summing the live states' counters here (the pre-R2-9
        // shape) collapsed after every dimension change/rejoin while uptime kept the service
        // lifetime, rendering an arithmetically-wrong rate — and disagreed with the same
        // command's Bandwidth line. The per-player lines below keep the per-state counters:
        // session-scoped is the honest scope for a player row.
        var players = new ArrayList<PlayerDiag>();
        for (var state : states) {
            players.add(new PlayerDiag(
                    state.getPlayerName(),
                    state.getSendQueueSize(), sendQueueLimitPerPlayer,
                    state.getHeldSyncSlots(), state.getHeldGenSlots(),
                    state.getTotalSectionsSent(), state.getTotalBytesSent(),
                    state.getOutboundPendingBytes(), state.getOutboundPendingHighWater(),
                    state.getYieldedTicks(),
                    state.getPingBackstop().factor(),
                    state.getPacedTicks()
            ));
        }

        return new DiagData(
                enabled, lodDistanceChunks,
                bwPerPlayer, bwGlobal,
                uptimeSec, serviceTotalSent, serviceTotalBytes,
                diag.getTotalInMemory(), diag.getTotalUpToDate(), diag.getTotalGenDrained(),
                diag.getTotalReResolved(), diag.getTotalGraceSkipped(),
                diskReader != null ? diskReader.getDiag().getSuccessfulReadCount() : 0,
                tickDiagnostics,
                // memo_hits: miss-memo rung hits (fresh memoized absence skipped the redundant
                // re-read and escalated straight to generation) — law A5's virtual not-founds.
                // The LOD-store state rides the same line as a token (store=off / store=<mode>
                // h=... m=...) — a new LINE would break the golden-order pins.
                diskReader != null
                        ? diskReader.getDiagnostics()
                                + String.format(", memo_hits=%d", diag.getTotalMemoHits())
                                + (storeDiag != null ? ", " + storeDiag.formatToken(storeMode) : "")
                        : "disabled",
                generationDiagnosticsOrNull, generationDiagnosticsOrNull != null,
                diag.getTotalGenOrderGated(), diag.getTotalGenCompletionInversions(),
                bwLimiter.getTotalBytesSent(),
                windowBandwidthRate,
                players, null, null, null,
                serviceWireBytes, diag.getTotalColumnsCompressed(), diag.getTotalColumnsRaw()
        );
    }

    /** The periodic server debug summary, shared by both platform tick loops. */
    public static void logDebugSummary(TickDiagnostics diag, long uptimeSec, long globalByteLimit,
                                       SharedBandwidthLimiter bwLimiter,
                                       Collection<? extends AbstractPlayerRequestState<?>> states) {
        if (!LSSLogger.isDebugEnabled()) return;
        long bwRate = uptimeSec > 0 ? bwLimiter.getTotalBytesSent() / uptimeSec : 0;
        LSSLogger.debug(diag.formatSummary(bwRate, globalByteLimit));
        for (var state : states) {
            if (!state.hasCompletedHandshake()) continue;
            LSSLogger.debug(String.format("  %s: sq=%d, syncSlots=%d/%d, genSlots=%d/%d",
                    state.getPlayerName(), state.getSendQueueSize(),
                    state.getHeldSyncSlots(), state.getSyncSlotCap(),
                    state.getHeldGenSlots(), state.getGenSlotCap()));
        }
    }

    public static String formatRate(double rate) {
        if (rate >= 1000) return String.format("%.1fK", rate / 1000);
        return String.format("%.0f", rate);
    }

    public static String formatUptime(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return String.format("%dm %ds", seconds / 60, seconds % 60);
        return String.format("%dh %dm", seconds / 3600, (seconds % 3600) / 60);
    }

    /** Outbound-buffer depth for the diag line: {@code n/a} when the probe has no signal,
     *  so an unresolvable channel never renders as a plausible-looking "0 B". */
    private static String formatOutbound(long bytes) {
        return bytes < 0 ? "n/a" : formatBytes(bytes);
    }

    /** The transport yield's one-line summary, or null while the gate is unarmed AND has
     *  never withheld a tick this service lifetime (yield plan §5 A-7 — armed-then-
     *  disarmed sessions keep their evidence). Counters are service-scoped
     *  (TickDiagnostics) so the log-archive signal survives player-state teardown. */
    public static String yieldDiagLineOrNull(boolean armed,
                                             TickDiagnostics diag) {
        long ticks = diag.getYieldTicksTotal();
        if (!armed && ticks == 0) return null;
        return String.format("Yield: armed=%s, ticks_total=%d, bytes_withheld=%s",
                armed, ticks, formatBytes(diag.getYieldBytesWithheldTotal()));
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
