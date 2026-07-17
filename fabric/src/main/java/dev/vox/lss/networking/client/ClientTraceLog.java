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

    /** Toggle the trace; returns the active file path when starting, null when stopping. */
    static synchronized Path toggle() {
        if (enabled) {
            enabled = false;
            try {
                writer.close();
            } catch (IOException e) {
                LSSLogger.error("Failed to close LSS trace", e);
            }
            writer = null;
            return null;
        }
        var name = "lss-trace-" + LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl";
        var path = Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve(name);
        try {
            Files.createDirectories(path.getParent());
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            startMs = System.currentTimeMillis();
            enabled = true;
            return path;
        } catch (IOException e) {
            LSSLogger.error("Failed to open LSS trace at " + path, e);
            writer = null;
            return null;
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
            LSSLogger.error("LSS trace write failed — trace stopped", e);
        }
    }
}
