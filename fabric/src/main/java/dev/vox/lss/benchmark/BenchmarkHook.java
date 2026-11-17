package dev.vox.lss.benchmark;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.client.LSSClientNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.nio.file.Path;
import java.util.Map;

public final class BenchmarkHook {
    private static final boolean ENABLED = Boolean.getBoolean("lss.benchmark");
    private static final int DURATION_SECONDS = Integer.getInteger("lss.benchmark.duration", 60);

    // Latest client metrics snapshot — captured every second before disconnect clears them
    private static volatile Map<String, Object> latestClientSnapshot;

    private BenchmarkHook() {}

    public static void initServer() {
        String soakScenario = System.getProperty("lss.soak.scenario");
        if (soakScenario != null && !soakScenario.isBlank()) {
            // Soak mode: the scenario timeline owns the server lifetime — the benchmark
            // tick-halt below must not arm or it would kill a multi-minute scenario at 60s.
            SoakScenarioDriver.init(soakScenario);
            return;
        }
        if (!ENABLED) return;
        LSSLogger.info("[Benchmark] Server hook active, duration=" + DURATION_SECONDS + "s");

        final int targetTicks = DURATION_SECONDS * LSSConstants.TICKS_PER_SECOND;
        final int[] tickCount = {0};

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LSSLogger.info("[Benchmark] Server started, counting " + targetTicks + " ticks");
            tickCount[0] = 0;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCount[0]++;
            if (tickCount[0] == targetTicks) {
                LSSLogger.info("[Benchmark] Duration reached (" + DURATION_SECONDS + "s), exporting metrics");
                Path outputFile = Path.of("benchmark-results", "server.json");
                BenchmarkMetricsExporter.exportServer(outputFile, DURATION_SECONDS);
                LSSLogger.info("[Benchmark] Halting server");
                server.halt(false);
            }
        });
    }

    public static void initClient() {
        if (Boolean.getBoolean("lss.soak")) {
            initSoakClient();
            return;
        }
        if (!ENABLED) return;
        LSSLogger.info("[Benchmark] Client hook active");

        // Register a no-op consumer so the handshake includes CAPABILITY_VOXEL_COLUMNS.
        // Without this, the server skips request routing for players with capabilities=0.
        LSSApi.registerColumnConsumer((level, dimension, chunkX, chunkZ, columnData) -> {});

        // Snapshot client metrics every second so we have data even after disconnect clears them
        final int[] clientTick = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            clientTick[0]++;
            if (clientTick[0] % LSSConstants.TICKS_PER_SECOND == 0 && LSSClientNetworking.isServerEnabled()) {
                latestClientSnapshot = ClientMetricsExporter.buildClientMetrics();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LSSLogger.info("[Benchmark] Client disconnected, exporting metrics");
            Path outputFile = Path.of("benchmark-results", "client.json");
            // Use the latest snapshot: the mod's disconnect cleanup may have already cleared state
            var snapshot = latestClientSnapshot;
            if (snapshot != null) {
                ClientMetricsExporter.writeClientSnapshot(outputFile, snapshot);
            } else {
                ClientMetricsExporter.exportClient(outputFile);
            }
            LSSLogger.info("[Benchmark] Exiting client");
            // halt(0) instead of exit(0): skip MC shutdown hooks for fast benchmark teardown
            Runtime.getRuntime().halt(0);
        });
    }

    /**
     * Soak client mode (-Dlss.soak): append a snapshot row to soak-results/client.jsonl
     * every 5 seconds, and on disconnect write a final row, then synchronously flush the
     * column-cache IO queue BEFORE halting — the disconnect save runs async on a daemon
     * thread and halt(0) would otherwise lose it, silently turning a warm-rejoin scenario
     * into a cold one.
     *
     * <p>Snapshots are gated on the session-config REPLY, not on enabled: an
     * enabled=false server must still produce rows (with server_enabled=false and
     * zero-filled manager fields) or the kill-switch scenario has nothing to judge.
     * Gating on the reply also keeps title-screen/boot ticks out of the series, which
     * would otherwise prepend dimension="none" rows and shift every scenario's
     * dimension-segment numbering.
     *
     * <p>{@code -Dlss.soak.clientActionAt=SECONDS:ACTION} runs one scripted client-side
     * action (currently {@code clearcache} = LodRequestManager.flushCache, the /lss
     * clearcache body) that many enabled-seconds into the session, appending an
     * {@code action} event row — the server driver can only run server commands.
     */
    private static void initSoakClient() {
        LSSLogger.info("[Soak] Client hook active");
        Path outputFile = Path.of("soak-results", "client.jsonl");

        // Register a no-op consumer so the handshake includes CAPABILITY_VOXEL_COLUMNS.
        // -Dlss.soak.mapOnly=true skips it for the map-only live gate (map-policy plan A4):
        // a map mod on the client is then the session's only consumer.
        if (!Boolean.getBoolean("lss.soak.mapOnly")) {
            LSSApi.registerColumnConsumer((level, dimension, chunkX, chunkZ, columnData) -> {});
        }

        int[] actionAt = {-1};
        String[] actionName = {null};
        String actionSpec = System.getProperty("lss.soak.clientActionAt", "");
        if (!actionSpec.isBlank()) {
            int sep = actionSpec.indexOf(':');
            try {
                actionAt[0] = Integer.parseInt(actionSpec.substring(0, sep));
                actionName[0] = actionSpec.substring(sep + 1);
                LSSLogger.info("[Soak] Client action armed: " + actionName[0] + " at +" + actionAt[0] + "s");
            } catch (RuntimeException e) {
                throw new IllegalStateException("[Soak] Malformed lss.soak.clientActionAt '"
                        + actionSpec + "' (want SECONDS:ACTION)", e);
            }
        }

        armFrameThrottle(System.getProperty("lss.soak.frameThrottle", ""));

        // Snapshot every second (like the benchmark hook) but only WRITE every 5s — the
        // mod's disconnect cleanup clears all counters, and it can run before Fabric's
        // DISCONNECT event reaches us (the event comes from netty), so the disconnect
        // row must re-emit the last live snapshot instead of reading zeroed state.
        var latest = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        final int[] clientTick = {0};
        final int[] enabledSeconds = {0};
        final boolean[] actionFired = {false};
        // The mod's disconnect cleanup, which queues the cache save, runs on the client thread
        // inside the game's own disconnect (ClientDisconnectHook), AFTER Fabric's DISCONNECT
        // event below. So the event only writes the final row; the flush and the exit wait
        // here for the first tick that finds the level gone, which is after that cleanup.
        var disconnected = new java.util.concurrent.atomic.AtomicBoolean();
        final boolean[] exiting = {false};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (disconnected.get()) {
                if (client.level == null && !exiting[0]) {
                    exiting[0] = true;
                    flushCacheAndExitSoakClient();
                }
                return;
            }
            clientTick[0]++;
            if (!LSSClientNetworking.hasReceivedSessionConfig()) return;
            if (clientTick[0] % LSSConstants.TICKS_PER_SECOND != 0) return;
            if (LSSClientNetworking.isServerEnabled()) {
                enabledSeconds[0]++;
                if (!actionFired[0] && actionName[0] != null && enabledSeconds[0] >= actionAt[0]) {
                    actionFired[0] = true;
                    fireClientAction(outputFile, actionName[0], actionAt[0]);
                }
            }
            latest.set(ClientMetricsExporter.buildClientSnapshot());
            if (clientTick[0] % (5 * LSSConstants.TICKS_PER_SECOND) == 0) {
                appendSoakClientRow(outputFile, "snapshot", latest.get());
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LSSLogger.info("[Soak] Client disconnected, writing final snapshot");
            var snapshot = latest.get() != null ? latest.get() : ClientMetricsExporter.buildClientSnapshot();
            appendSoakClientRow(outputFile, "disconnect", snapshot);
            disconnected.set(true);
        });
    }

    /** Client thread, after the mod's disconnect cleanup queued the cache save. */
    private static void flushCacheAndExitSoakClient() {
        // Drains the single-threaded IO executor so the write lands.
        dev.vox.lss.networking.client.ColumnCacheStore.flushPendingIo();
        long grace = Long.getLong("lss.soak.haltGraceMillis", 0L);
        if (grace > 0) {
            // The JourneyMap smoke: JourneyMap saves its map in its own disconnect
            // handling, which a halt from here would cut short. Halt from a side
            // thread so this one returns and the client finishes its disconnect.
            LSSLogger.info("[Soak] Cache flushed, exiting client in " + grace + " ms (halt grace)");
            var halt = new Thread(() -> {
                try {
                    Thread.sleep(grace);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                Runtime.getRuntime().halt(0);
            }, "LSS-SoakHaltGrace");
            halt.start();
            return;
        }
        LSSLogger.info("[Soak] Cache flushed, exiting client");
        Runtime.getRuntime().halt(0);
    }

    /**
     * {@code -Dlss.soak.frameThrottle=MS[:SECONDS]}: sleep MS at the end of every rendered
     * frame for the first SECONDS after the first throttled frame (absent = the whole
     * session) — Dynamic FPS's unfocused frame rate, for the Xaero memory gate (map-ingestion
     * plan §6.4 L2): Xaero releases map memory only per rendered frame. Ticks keep running
     * (Minecraft catches up to ten per frame), exactly the starved-release shape.
     */
    private static void armFrameThrottle(String spec) {
        if (spec.isBlank()) return;
        String[] parts = spec.split(":");
        long sleepMs = Long.parseLong(parts[0]);
        long forMs = parts.length > 1 ? Long.parseLong(parts[1]) * 1000 : Long.MAX_VALUE;
        if (sleepMs <= 0) return;
        long[] liftAt = {0};
        LSSLogger.info("[Soak] Frame throttle armed: " + sleepMs + " ms per frame"
                + (forMs == Long.MAX_VALUE ? "" : " for " + forMs / 1000 + " s"));
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.END_MAIN.register(context -> {
            long now = System.currentTimeMillis();
            if (liftAt[0] == 0) liftAt[0] = forMs == Long.MAX_VALUE ? Long.MAX_VALUE : now + forMs;
            if (now >= liftAt[0]) {
                if (liftAt[0] > 0) {
                    LSSLogger.info("[Soak] Frame throttle lifted");
                    liftAt[0] = -1;
                }
                return;
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Runs one scripted client-side action on the main client thread and logs it as an
     *  {@code action} row BEFORE the post-action snapshot, so the checker can segment the
     *  client series at the counter reset (flushCache resets the request metrics). */
    private static void fireClientAction(Path outputFile, String action, int atSeconds) {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("event", "action");
        row.put("wallMs", System.currentTimeMillis());
        row.put("action", action);
        row.put("atSeconds", atSeconds);
        BenchmarkMetricsExporter.appendJsonLine(outputFile, row);
        if ("clearcache".equals(action)) {
            var manager = LSSClientNetworking.getRequestManager();
            if (manager != null) {
                LSSLogger.info("[Soak] Client action: clearcache (flushing column cache)");
                manager.flushCache();
            } else {
                LSSLogger.warn("[Soak] Client action clearcache skipped — no request manager");
            }
        } else {
            LSSLogger.warn("[Soak] Unknown client action '" + action + "' — row logged, nothing executed");
        }
    }

    private static void appendSoakClientRow(Path outputFile, String event, Map<String, Object> snapshot) {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("event", event);
        row.put("wallMs", System.currentTimeMillis());
        row.putAll(snapshot);
        BenchmarkMetricsExporter.appendJsonLine(outputFile, row);
    }
}
