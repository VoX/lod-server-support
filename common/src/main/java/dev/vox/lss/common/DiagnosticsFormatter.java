package dev.vox.lss.common;

import dev.vox.lss.common.processing.AbstractPlayerRequestState;
import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import dev.vox.lss.common.processing.ProcessingDiagnostics;
import dev.vox.lss.common.processing.TickDiagnostics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class DiagnosticsFormatter {

    public record PlayerDiag(
            String name, int sendQueue, int maxSendQueue,
            int pendingSync, int pendingGen,
            long sent, long bytes
    ) {}

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
            String xrayLine
    ) {
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
         *  the line is omitted from the rendered diagnostics). */
        public DiagData withV16Line(String line) {
            return new DiagData(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent,
                    totalBytes, cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped,
                    diskCompleted, tickDiagnostics, diskReaderDiagnostics, generationDiagnostics,
                    generationEnabled, genOrderGated, genInversions, bwTotal, bwWindowRate,
                    players, line, xrayLine);
        }

        /** Attach the x-ray masking one-line summary (always shown when non-null — the off
         *  state is what an admin testing masking needs to see). */
        public DiagData withXrayLine(String line) {
            return new DiagData(enabled, lodDist, bwPerPlayer, bwGlobal, uptimeSec, totalSent,
                    totalBytes, cumInMem, cumUtd, cumGen, cumReResolved, cumGraceSkipped,
                    diskCompleted, tickDiagnostics, diskReaderDiagnostics,
                    generationDiagnostics, generationEnabled, genOrderGated, genInversions,
                    bwTotal, bwWindowRate, players, v16Line, line);
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

        // X-ray masking (docs/planning/antixray-compat-design.md §3 Diagnostics)
        if (d.xrayLine != null) {
            lines.add(d.xrayLine);
        }

        // Bandwidth
        lines.add(String.format("Bandwidth: %s/s / %s/s global (%s total)",
                formatBytes(d.bwWindowRate), formatBytes(d.bwGlobal),
                formatBytes(d.bwTotal)));

        // Per-player
        for (var p : d.players) {
            double pRate = d.uptimeSec > 0 ? (double) p.sent / d.uptimeSec : 0;
            lines.add(String.format(
                    "  %s: sq=%d/%d, psync=%d, pgen=%d, sent=%d (%s), rate=%s/s",
                    p.name, p.sendQueue, p.maxSendQueue,
                    p.pendingSync, p.pendingGen,
                    p.sent, formatBytes(p.bytes),
                    formatRate(pRate)
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
     *  service state, never throw at the admin. */
    public static DiagData collectDiagData(boolean enabled, int lodDistanceChunks,
                                           long bwPerPlayer, long bwGlobal, int sendQueueLimitPerPlayer,
                                           long uptimeSec, String tickDiagnostics, long windowBandwidthRate,
                                           ProcessingDiagnostics diag, AbstractChunkDiskReader diskReader,
                                           SharedBandwidthLimiter bwLimiter,
                                           String generationDiagnosticsOrNull,
                                           Collection<? extends AbstractPlayerRequestState<?>> states) {
        long totalSent = 0;
        long totalBytes = 0;
        var players = new ArrayList<PlayerDiag>();
        for (var state : states) {
            totalSent += state.getTotalSectionsSent();
            totalBytes += state.getTotalBytesSent();
            players.add(new PlayerDiag(
                    state.getPlayerName(),
                    state.getSendQueueSize(), sendQueueLimitPerPlayer,
                    state.getHeldSyncSlots(), state.getHeldGenSlots(),
                    state.getTotalSectionsSent(), state.getTotalBytesSent()
            ));
        }

        return new DiagData(
                enabled, lodDistanceChunks,
                bwPerPlayer, bwGlobal,
                uptimeSec, totalSent, totalBytes,
                diag.getTotalInMemory(), diag.getTotalUpToDate(), diag.getTotalGenDrained(),
                diag.getTotalReResolved(), diag.getTotalGraceSkipped(),
                diskReader != null ? diskReader.getDiag().getSuccessfulReadCount() : 0,
                tickDiagnostics,
                // memo_hits: miss-memo rung hits (fresh memoized absence skipped the redundant
                // re-read and escalated straight to generation) — law A5's virtual not-founds.
                diskReader != null
                        ? diskReader.getDiagnostics()
                                + String.format(", memo_hits=%d", diag.getTotalMemoHits())
                        : "disabled",
                generationDiagnosticsOrNull, generationDiagnosticsOrNull != null,
                diag.getTotalGenOrderGated(), diag.getTotalGenCompletionInversions(),
                bwLimiter.getTotalBytesSent(),
                windowBandwidthRate,
                players
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

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
