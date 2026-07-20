package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSLogger;
import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Opt-in client-side event trace for diagnosing request-loop behavior live (scan order,
 * movement recentering, receive order). Toggled by {@code /lss trace}; writes compact
 * JSONL to {@code logs/lss-trace-<timestamp>.jsonl} in the game directory. Disabled it
 * costs one volatile read per hook — every formatting happens behind the gate. Main
 * client thread only (every hook site lives on it); the writer flushes per line so a
 * crash or force-quit loses nothing.
 */
final class ClientTraceLog {

    private static volatile boolean enabled;
    private static BufferedWriter writer;
    private static long startMs;

    static boolean enabled() {
        return enabled;
    }

    /** Toggle outcome: {@code path} non-null when a trace started; {@code failed} true when
     *  a start was attempted and could not open its file (so the command can say so instead
     *  of reporting a stop that never happened). */
    record Toggle(Path path, boolean failed) {}

    /** Toggle the trace; see {@link Toggle}. */
    static synchronized Toggle toggle() {
        if (enabled) {
            enabled = false;
            try {
                writer.close();
            } catch (IOException e) {
                LSSLogger.error("Failed to close LSS trace", e);
            }
            writer = null;
            return new Toggle(null, false);
        }
        // A previous trace that died on a write failure leaves its writer for us to close —
        // the stop branch above is unreachable for it (enabled already false).
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                LSSLogger.error("Failed to close leaked LSS trace writer", e);
            }
            writer = null;
        }
        var name = "lss-trace-" + LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl";
        var path = Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve(name);
        try {
            Files.createDirectories(path.getParent());
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            startMs = System.currentTimeMillis();
            enabled = true;
            return new Toggle(path, false);
        } catch (IOException e) {
            LSSLogger.error("Failed to open LSS trace at " + path, e);
            writer = null;
            return new Toggle(null, true);
        }
    }

    /** Write one event line. Callers must pre-check {@link #enabled()} so the argument
     *  string is never built on the disabled path. {@code body} is the JSON tail after
     *  the common fields, e.g. {@code "\"from\":[1,2],\"to\":[2,2]"}. */
    static synchronized void event(String type, String body) {
        if (!enabled || writer == null) return;
        try {
            writer.write("{\"t\":\"" + type + "\",\"ms\":" + (System.currentTimeMillis() - startMs)
                    + (body.isEmpty() ? "" : "," + body) + "}\n");
            writer.flush();
        } catch (IOException e) {
            enabled = false;
            try {
                writer.close();
            } catch (IOException closeFailure) {
                // best effort — the handle must not outlive the dead trace
            }
            writer = null;
            LSSLogger.error("LSS trace write failed — trace stopped", e);
        }
    }
}
