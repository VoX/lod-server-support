package dev.vox.lss.paper.soak;

import com.google.gson.Gson;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.paper.FoliaSupport;
import dev.vox.lss.paper.LSSPaperPlugin;
import net.minecraft.server.MinecraftServer;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paper twin of Fabric's SoakScenarioDriver (scripts/soak.sh, SOAK_PLATFORM=paper): parses
 * the same timeline JSON, executes each step's command when its join-anchored offset
 * elapses, appends the same diagnostic snapshot and event rows to
 * soak-results/server.jsonl, and halts the server at the scenario end — so the unchanged
 * Fabric soak client and scripts/check_soak.py validate a real Paper server end to end.
 *
 * <p>Bukkit events/scheduler replace the Fabric hooks (PlayerJoinEvent for the join
 * anchors, a 1-tick repeating GlobalRegionScheduler task for the tick clock — main thread
 * on Paper, the global region thread on Folia); commands run
 * through the identical NMS console path Fabric uses, and the end halt is vanilla
 * {@code MinecraftServer.halt(false)} (what /stop does), so the world save-on-exit
 * semantics match. One deliberate divergence: CraftBukkit makes gamerules per-world
 * (vanilla's are global), so a console {@code gamerule} would only reach the overworld —
 * the driver fans gamerule steps out to every loaded dimension via {@code execute in}
 * so the End/nether inherit the timeline's rules exactly as they do under Fabric.
 * Dev-only: gated behind -Dlss.soak.scenario via PaperSoakBridge and
 * excluded from the release shadowJar (runSoakServer feeds the soakShadowJar variant).
 */
public final class PaperSoakScenarioDriver implements Listener {
    private static final Path OUTPUT = Path.of("soak-results", "server.jsonl");

    /** Scenario file schema (GSON-populated) — same contract as the Fabric driver. */
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

    private final LSSPaperPlugin plugin;
    private final MinecraftServer server;
    private final ScenarioFile scenario;
    private final Map<Integer, Long> joinTicks = new HashMap<>();
    private long tickCount;
    private int joinCount;
    private long lastProgressTick;
    private boolean ended;

    private PaperSoakScenarioDriver(LSSPaperPlugin plugin, MinecraftServer server, ScenarioFile scenario) {
        this.plugin = plugin;
        this.server = server;
        this.scenario = scenario;
    }

    /** Invoked reflectively by PaperSoakBridge when -Dlss.soak.scenario is set. */
    public static void init(LSSPaperPlugin plugin) {
        String scenarioPath = System.getProperty("lss.soak.scenario");
        ScenarioFile scenario;
        try {
            scenario = new Gson().fromJson(Files.readString(Path.of(scenarioPath)), ScenarioFile.class);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("[Soak] Cannot read scenario " + scenarioPath, e);
        }
        if (scenario == null || scenario.end == null) {
            throw new IllegalStateException("[Soak] Scenario " + scenarioPath + " is empty or missing 'end'");
        }
        var nmsServer = ((CraftServer) plugin.getServer()).getServer();
        var driver = new PaperSoakScenarioDriver(plugin, nmsServer, scenario);
        LSSLogger.info("[Soak] Scenario active: " + scenarioPath + " (" + scenario.steps.size()
                + " steps, end anchor " + scenario.end.anchor + " +" + scenario.end.at + "s)");

        plugin.getServer().getPluginManager().registerEvents(driver, plugin);
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                plugin, scheduledTask -> driver.onTick(), 1L, 1L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Folia fires join events on the joining player's region thread; all driver state is
        // owned by the global-region pump, so hop (≤1 tick anchor shift — timelines are
        // second-granular). On plain Paper this stays on the main thread either way.
        String name = event.getPlayer().getName();
        this.plugin.getServer().getGlobalRegionScheduler().execute(this.plugin,
                () -> recordJoin(name));
    }

    private void recordJoin(String name) {
        if (this.ended) return;
        this.joinCount++;
        this.joinTicks.put(this.joinCount, this.tickCount);
        this.lastProgressTick = this.tickCount;
        var row = baseRow("join");
        row.put("player", name);
        row.put("joinIndex", this.joinCount);
        PaperSoakMetricsExporter.appendJsonLine(OUTPUT, row);
        LSSLogger.info("[Soak] Join #" + this.joinCount + " (" + name + ") at tick " + this.tickCount);
    }

    private void onTick() {
        if (this.ended) return;
        this.tickCount++;

        // Per-tick gauge sample so the *_hw high-water marks and mspt window in each 5 s
        // snapshot reflect bursts between snapshots, not just the point sample at snapshot time.
        var service = this.plugin.getRequestService();
        if (service != null) PaperSoakMetricsExporter.sampleServerGauges(service);

        fireDueSteps();

        if (this.tickCount % ((long) this.scenario.snapshotIntervalSeconds * LSSConstants.TICKS_PER_SECOND) == 0) {
            appendSnapshot();
        }

        if (endReached()) {
            finish("timeline-complete");
            return;
        }

        if (waitingForFutureJoin()
                && this.tickCount - this.lastProgressTick > (long) this.scenario.joinTimeoutSeconds * LSSConstants.TICKS_PER_SECOND) {
            LSSLogger.error("[Soak] Join timeout: waited " + this.scenario.joinTimeoutSeconds
                    + "s for join #" + (this.joinCount + 1));
            finish("join-timeout");
        }
    }

    private void fireDueSteps() {
        for (var step : this.scenario.steps) {
            if (step.fired) continue;
            Long anchorTick = this.joinTicks.get(step.anchor);
            if (anchorTick == null) continue;
            if (this.tickCount - anchorTick < (long) step.at * LSSConstants.TICKS_PER_SECOND) continue;

            step.fired = true;
            this.lastProgressTick = this.tickCount;
            LSSLogger.info("[Soak] Executing step (anchor " + step.anchor + " +" + step.at + "s): " + step.cmd);
            boolean threw = false;
            boolean mapped = mapsToFoliaNoOp(step.cmd, FoliaSupport.IS_FOLIA);
            if (mapped) {
                LSSLogger.info("[Soak] Folia: mapping to no-op: " + step.cmd);
            } else {
                try {
                    executeStepCommand(step.cmd);
                } catch (RuntimeException e) {
                    threw = true;
                    LSSLogger.error("[Soak] Step command failed: " + step.cmd, e);
                }
            }
            // ok=did-not-throw breadcrumb (twin of the Fabric driver); no-effect commands are
            // flagged by soak_report's snapshot-delta tag, not here.
            var row = baseRow("command");
            row.put("cmd", step.cmd);
            row.put("anchor", step.anchor);
            row.put("at", step.at);
            row.put("ok", !threw);
            if (mapped) row.put("mapped", true); // only when true: Paper rows stay byte-identical
            PaperSoakMetricsExporter.appendJsonLine(OUTPUT, row);
        }
    }

    /**
     * Folia unregisters /save-all (docs.papermc.io/folia/faq); executing it would log
     * "Unknown command" and save nothing. The Folia soak staging compensates with an
     * aggressive bukkit.yml autosave and the end-of-scenario halt(false) full save
     * (RegionShutdownThread saves all worlds), so the timeline step becomes an
     * acknowledged no-op instead of a per-platform scenario fork.
     */
    static boolean mapsToFoliaNoOp(String cmd, boolean folia) {
        if (!folia) return false;
        String bare = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        return bare.equals("save-all") || bare.startsWith("save-all ");
    }

    /**
     * Run one timeline command through the NMS console path. Gamerule steps are fanned out
     * to every loaded world: CraftBukkit stores gamerules per-world (vanilla's are global),
     * so a plain console {@code gamerule} would apply to the overworld only and e.g.
     * dimension-trip's quiescence rules (randomTickSpeed 0, doMobSpawning false, ...) would
     * never reach the End — the shared Fabric timelines assume global gamerule semantics.
     */
    private void executeStepCommand(String cmd) {
        String bare = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        if (bare.startsWith("gamerule ")) {
            for (var level : this.server.getAllLevels()) {
                String scoped = "execute in " + level.dimension().identifier() + " run " + bare;
                this.server.getCommands().performPrefixedCommand(
                        this.server.createCommandSourceStack(), scoped);
            }
            return;
        }
        this.server.getCommands().performPrefixedCommand(
                this.server.createCommandSourceStack(), cmd);
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
        var metrics = PaperSoakMetricsExporter.buildServerMetrics(this.plugin.getRequestService());
        if (metrics == null) return; // processing service not started yet
        var row = baseRow("snapshot");
        row.putAll(metrics);
        PaperSoakMetricsExporter.appendJsonLine(OUTPUT, row);
    }

    private void finish(String reason) {
        this.ended = true;
        appendSnapshot();
        var row = baseRow("end");
        row.put("reason", reason);
        PaperSoakMetricsExporter.appendJsonLine(OUTPUT, row);
        LSSLogger.info("[Soak] Scenario finished (" + reason + "), halting server");
        this.server.halt(false);
    }

    private Map<String, Object> baseRow(String event) {
        var row = new LinkedHashMap<String, Object>();
        row.put("event", event);
        row.put("wallMs", System.currentTimeMillis());
        row.put("tick", this.tickCount);
        return row;
    }
}
