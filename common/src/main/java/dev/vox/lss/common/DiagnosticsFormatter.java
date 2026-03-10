package dev.vox.lss.common;

import java.util.ArrayList;
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
            long cumInMem, long cumUtd, long cumGen,
            long diskCompleted,
            String tickDiagnostics,
            String diskReaderDiagnostics,
            String generationDiagnostics, boolean generationEnabled,
            long bwTotal,
            long bwWindowRate,
            List<PlayerDiag> players
    ) {}

    private DiagnosticsFormatter() {}

    public static List<String> formatDiagnostics(DiagData d) {
        var lines = new ArrayList<String>();
        lines.add("=== LSS LOD Diagnostics ===");

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

        // Sources (total)
        lines.add(String.format(
                "Sources (total): in_mem=%d, disk=%d, up_to_date=%d, gen=%d",
                d.cumInMem, Math.max(0, d.diskCompleted), d.cumUtd, d.cumGen
        ));

        // Sources (tick)
        lines.add("Sources (tick): " + d.tickDiagnostics);

        // DiskReader
        lines.add("DiskReader: " + d.diskReaderDiagnostics);

        // Generation
        if (d.generationEnabled) {
            lines.add("Generation: " + d.generationDiagnostics);
        } else {
            lines.add("Generation: disabled");
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
