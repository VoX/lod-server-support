package dev.vox.lss.benchmark;

import com.google.gson.Gson;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side soak scenario executor (scripts/soak.sh). Parses a timeline JSON, executes
 * each step's command when its join-anchored offset elapses, appends diagnostic snapshot
 * and event rows to soak-results/server.jsonl, and halts the server at the scenario end.
 *
 * <p>Anchors are join-relative, not absolute: step N's clock starts at the Nth player
 * JOIN event, because a freshly launched headless client takes 30-90s of Gradle/MC boot
 * and absolute offsets would race it. Everything runs on the server main thread.
 *
 * <p>Row schema is the checker contract — see docs/soak-test-design.md.
 */
public final class SoakScenarioDriver {
    private static final Path OUTPUT = Path.of("soak-results", "server.jsonl");

    /** Scenario file schema (GSON-populated). */
    static final class ScenarioFile {
        int snapshotIntervalSeconds = 5;
        int joinTimeoutSeconds = 240;
        List<Step> steps = List.of();
        End end;
    }

    static final class Step {
        int anchor = 1;
        int at;
        String cmd;
        transient boolean fired;
    }

    static final class End {
        int anchor = 1;
        int at;
    }

    private final ScenarioFile scenario;
    private final Map<Integer, Long> joinTicks = new HashMap<>();
    private long tickCount;
    private int joinCount;
    private long lastProgressTick;
    private boolean ended;

    private SoakScenarioDriver(ScenarioFile scenario) {
        this.scenario = scenario;
    }

    static void init(String scenarioPath) {
        ScenarioFile scenario;
        try {
            scenario = new Gson().fromJson(Files.readString(Path.of(scenarioPath)), ScenarioFile.class);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("[Soak] Cannot read scenario " + scenarioPath, e);
        }
        if (scenario == null || scenario.end == null) {
            throw new IllegalStateException("[Soak] Scenario " + scenarioPath + " is empty or missing 'end'");
        }
        var driver = new SoakScenarioDriver(scenario);
        LSSLogger.info("[Soak] Scenario active: " + scenarioPath + " (" + scenario.steps.size()
                + " steps, end anchor " + scenario.end.anchor + " +" + scenario.end.at + "s)");

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> driver.onJoin(handler.getPlayer().getName().getString()));
        ServerTickEvents.END_SERVER_TICK.register(driver::onTick);
    }

    private void onJoin(String playerName) {
        if (this.ended) return;
        this.joinCount++;
        this.joinTicks.put(this.joinCount, this.tickCount);
        this.lastProgressTick = this.tickCount;
        var row = baseRow("join");
        row.put("player", playerName);
        row.put("joinIndex", this.joinCount);
        BenchmarkMetricsExporter.appendJsonLine(OUTPUT, row);
        LSSLogger.info("[Soak] Join #" + this.joinCount + " (" + playerName + ") at tick " + this.tickCount);
    }

    private void onTick(MinecraftServer server) {
        if (this.ended) return;
        this.tickCount++;

        // Per-tick gauge sample so the *_hw high-water marks and mspt window in each 5 s
        // snapshot reflect bursts between snapshots, not just the point sample at snapshot time.
        BenchmarkMetricsExporter.sampleServerGauges();

        fireDueSteps(server);

        if (this.tickCount % ((long) this.scenario.snapshotIntervalSeconds * LSSConstants.TICKS_PER_SECOND) == 0) {
            appendSnapshot();
        }

        if (endReached()) {
            finish(server, "timeline-complete");
            return;
        }

        if (waitingForFutureJoin()
                && this.tickCount - this.lastProgressTick > (long) this.scenario.joinTimeoutSeconds * LSSConstants.TICKS_PER_SECOND) {
            LSSLogger.error("[Soak] Join timeout: waited " + this.scenario.joinTimeoutSeconds
                    + "s for join #" + (this.joinCount + 1));
            finish(server, "join-timeout");
        }
    }

    private void fireDueSteps(MinecraftServer server) {
        for (var step : this.scenario.steps) {
            if (step.fired) continue;
            Long anchorTick = this.joinTicks.get(step.anchor);
            if (anchorTick == null) continue;
            if (this.tickCount - anchorTick < (long) step.at * LSSConstants.TICKS_PER_SECOND) continue;

            step.fired = true;
            this.lastProgressTick = this.tickCount;
            LSSLogger.info("[Soak] Executing step (anchor " + step.anchor + " +" + step.at + "s): " + step.cmd);
            boolean threw = false;
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), step.cmd);
            } catch (RuntimeException e) {
                threw = true;
                LSSLogger.error("[Soak] Step command failed: " + step.cmd, e);
            }
            // Row carries ok=did-not-throw so a step that raised is a visible breadcrumb; a
            // command that parsed but had no effect is caught by soak_report's snapshot-delta
            // "[no observable effect]" tag rather than here (performPrefixedCommand returns void).
            var row = baseRow("command");
            row.put("cmd", step.cmd);
            row.put("anchor", step.anchor);
            row.put("at", step.at);
            row.put("ok", !threw);
            BenchmarkMetricsExporter.appendJsonLine(OUTPUT, row);
        }
    }

    private boolean endReached() {
        Long anchorTick = this.joinTicks.get(this.scenario.end.anchor);
        return anchorTick != null
                && this.tickCount - anchorTick >= (long) this.scenario.end.at * LSSConstants.TICKS_PER_SECOND;
    }

    /** True when an unfired step or the end condition references a join that hasn't happened. */
    private boolean waitingForFutureJoin() {
        if (this.scenario.end.anchor > this.joinCount) return true;
        for (var step : this.scenario.steps) {
            if (!step.fired && step.anchor > this.joinCount) return true;
        }
        return false;
    }

    private void appendSnapshot() {
        var metrics = BenchmarkMetricsExporter.buildServerMetrics();
        if (metrics == null) return; // processing service not started yet
        var row = baseRow("snapshot");
        row.putAll(metrics);
        BenchmarkMetricsExporter.appendJsonLine(OUTPUT, row);
    }

    private void finish(MinecraftServer server, String reason) {
        this.ended = true;
        appendSnapshot();
        var row = baseRow("end");
        row.put("reason", reason);
        BenchmarkMetricsExporter.appendJsonLine(OUTPUT, row);
        LSSLogger.info("[Soak] Scenario finished (" + reason + "), halting server");
        server.halt(false);
    }

    private Map<String, Object> baseRow(String event) {
        var row = new LinkedHashMap<String, Object>();
        row.put("event", event);
        row.put("wallMs", System.currentTimeMillis());
        row.put("tick", this.tickCount);
        return row;
    }
}
