package dev.vox.lss.common;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code /lsslod help} text (v0.11.0 stage C) — one shared builder so both
 * platforms render identical lines (the HandshakeGate parity precedent). The root
 * label flows from the caller ({@code Brand.serverCommand()} on Fabric, the Bukkit
 * label on Paper), so VSS jars print {@code /vsslod} for free.
 */
public final class CommandHelp {

    public static List<String> lines(String rootLabel, boolean backfillAvailable) {
        var out = new ArrayList<String>();
        out.add("=== /" + rootLabel + " — " + Brand.shortName() + " LOD server commands ===");
        out.add("/" + rootLabel + " stats — per-player LOD session counters");
        out.add("/" + rootLabel + " diag — full service diagnostics (serve sources, disk"
                + " reader, generation, store, dialects)");
        out.add("/" + rootLabel + " store status — LOD store health, size, and sweep state");
        out.add("/" + rootLabel + " store invalidate all — drop every stored LOD row"
                + " (re-warms from serves)");
        if (backfillAvailable) {
            out.add("/" + rootLabel + " store backfill start|stop|status — background"
                    + " store warm-up walk (status shows progress + remaining estimate)");
        }
        out.add("/" + rootLabel + " set — list runtime-settable config keys with current"
                + " values");
        out.add("/" + rootLabel + " set <key> <value> — apply + persist a config change"
                + " at runtime (values are clamped like the config file). "
                + "lodDistanceChunks also accepts `<world> <n>` for a per-world "
                + "override and `<world> default` to clear it");
        out.add("/" + rootLabel + " help — this list");
        return out;
    }

    private CommandHelp() {}
}
