# Folia Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The existing Paper plugin (`lod-server-support-paper.jar`) runs correctly on Folia 26.1.2, declared via `folia-supported: true`, validated by unit tests plus a `SOAK_PLATFORM=folia` port of the live soak harness.

**Architecture:** Single jar (no new subproject). Swap the three `BukkitScheduler` sites to `GlobalRegionScheduler` (present in plain Paper's API; main-thread semantics on Paper, global-region thread on Folia — the service keeps its single-pump model). Add a lifecycle mailbox so handshake registration and player-quit removal (which arrive on region threads on Folia) are drained on the pump. Map the soak driver's `save-all` (unregistered on Folia) to an acknowledged no-op with aggressive autosave staging. Spec: `docs/superpowers/specs/2026-07-02-folia-support-design.md`.

**Tech Stack:** Java 25, Paper API 26.1.2 (`paperweight.paperDevBundle '26.1.2.build.69-stable'`), run-paper 3.0.2 (`runPaper.folia.registerTask()`), JUnit 5 + Mockito, bash soak harness + Python checker.

## Global Constraints

- Minecraft 26.1.2; Folia server = `folia-26.1.2` build 8 (downloaded by run-paper; never vendored).
- Protocol stays **v16**; zero wire/client changes.
- No new runtime dependencies; no reflection except the one `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` platform probe.
- One release artifact: `lod-server-support-paper.jar` (globs/manifest in `release_check.py` unchanged except the new plugin.yml assertion).
- The soak package stays out of the release shadowJar (`dev/vox/lss/paper/soak/**` exclusion untouched).
- Paper behavior must not regress: on plain Paper, `GlobalRegionScheduler` == main thread; full Paper soak suite re-runs as the gate.
- Tests-first for every behavioral change; run `./gradlew :paper:test` after each task (fast, ~209 tests).

---

### Task 1: Branch + docs

**Files:**
- Create: branch `feat/folia-support` off `fix/lod-lighting-and-review-findings`
- Commit: `docs/superpowers/specs/2026-07-02-folia-support-design.md`, `docs/superpowers/plans/2026-07-02-folia-support.md`, `plan-progress.md`

- [ ] **Step 1:** `git checkout -b feat/folia-support`
- [ ] **Step 2:** `git add docs/superpowers/specs/2026-07-02-folia-support-design.md docs/superpowers/plans/2026-07-02-folia-support.md plan-progress.md && git commit -m "docs: Folia support design spec + implementation plan"`

### Task 2: Scheduler swap — service tick + soak driver tick

`GlobalRegionScheduler.runAtFixedRate(Plugin, Consumer<ScheduledTask>, initialDelayTicks, periodTicks)` replaces `BukkitRunnable.runTaskTimer(plugin, 1L, 1L)`. Identical cadence/thread on Paper; the only thread that exists for this on Folia. No seam change: `LSSPaperPluginGlueTest` pins step *order* through the `EnableSteps` interface, which is untouched.

**Files:**
- Modify: `paper/src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java:143-154` (`scheduleServiceTick`), drop the now-unused `org.bukkit.scheduler.BukkitRunnable` import
- Modify: `paper/src/main/java/dev/vox/lss/paper/soak/PaperSoakScenarioDriver.java:96-101` (`init` tick task), drop its `BukkitRunnable` import

**Interfaces:** none change.

- [ ] **Step 1: Swap the service tick.** In `LSSPaperPlugin`, replace the `scheduleServiceTick()` body:

```java
@Override
public void scheduleServiceTick() {
    // GlobalRegionScheduler, not BukkitScheduler: on Folia the legacy scheduler throws
    // UnsupportedOperationException; on plain Paper this runs on the main thread every
    // tick, exactly like the BukkitRunnable it replaces. The global-region thread is
    // the plugin's single pump — every single-owner structure in the pipeline hangs
    // off this cadence (see the Folia design spec §3).
    getServer().getGlobalRegionScheduler().runAtFixedRate(LSSPaperPlugin.this,
            scheduledTask -> {
                var service = requestService;
                if (service != null) {
                    service.tick();
                }
            }, 1L, 1L);
}
```

- [ ] **Step 2: Swap the driver tick.** In `PaperSoakScenarioDriver.init`, replace the `new BukkitRunnable(){...}.runTaskTimer(plugin, 1L, 1L)` block:

```java
plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
        plugin, scheduledTask -> driver.onTick(), 1L, 1L);
```

Update the class javadoc sentence "a 1-tick repeating task for the tick clock — both main thread" to "a 1-tick repeating GlobalRegionScheduler task for the tick clock — main thread on Paper, the global region thread on Folia".

- [ ] **Step 3:** Run: `./gradlew :paper:test` — Expected: PASS (no seam touched).
- [ ] **Step 4:** Commit: `git commit -am "folia: service + soak driver tick via GlobalRegionScheduler"`

### Task 3: Generation completion hop via GlobalRegionScheduler

**Files:**
- Modify: `paper/src/main/java/dev/vox/lss/paper/PaperChunkGenerationService.java:92` (constructor default), `:65-71` seam javadoc; drop the `org.bukkit.Bukkit` import.
- Test (existing, must stay green): `paper/src/test/java/dev/vox/lss/paper/PaperChunkGenerationServiceTest.java` (throwing-scheduler containment tests pin the behavior through the `MainThreadScheduler` seam).

**Interfaces:** `MainThreadScheduler` seam unchanged.

- [ ] **Step 1:** Replace the constructor default:

```java
this.mainThreadScheduler = task ->
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
```

Update the seam javadoc: "Production default is the GlobalRegionScheduler (main thread on Paper, the pump's global-region thread on Folia), which throws once the plugin is disabled — tests inject throwing schedulers to pin that rejection containment." Rename the seam-facing comments from "main thread" to "pump thread" where they describe the hop target (`onChunkReady` javadoc line 152, field comment line 57).

- [ ] **Step 2:** Run: `./gradlew :paper:test --tests '*PaperChunkGenerationServiceTest*'` — Expected: PASS.
- [ ] **Step 3:** Commit: `git commit -am "folia: generation completion hop via GlobalRegionScheduler"`

### Task 4: Lifecycle mailbox in PaperRequestProcessingService (TDD)

**Files:**
- Modify: `paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java`
- Test: `paper/src/test/java/dev/vox/lss/paper/PaperRequestProcessingServiceTest.java`

**Interfaces:**
- Produces: `public void enqueueRegister(ServerPlayer player, int capabilities)`; `public void enqueueRemove(UUID uuid)`. Both callable from any thread; effects apply at the top of the next `tick()`. `registerPlayer`/`removePlayer` remain public, pump-context-only.

- [ ] **Step 1: Write failing tests** (use the file's existing `playerIn(uuid, level)` mock helper and Wiring-based `service` from `setUp`):

```java
@Test
void enqueuedRegisterAppliesAtNextTick() {
    var player = playerIn(UUID.randomUUID(), overworld);
    service.enqueueRegister(player, 1);
    assertTrue(service.getPlayers().isEmpty(), "mailbox must not apply before tick");
    service.tick();
    assertEquals(1, service.getPlayers().size());
    assertTrue(service.getPlayers().get(player.getUUID()).hasCompletedHandshake());
}

@Test
void enqueuedRemoveAppliesAtNextTick() {
    var player = playerIn(UUID.randomUUID(), overworld);
    service.registerPlayer(player, 1);
    service.enqueueRemove(player.getUUID());
    assertEquals(1, service.getPlayers().size(), "mailbox must not apply before tick");
    service.tick();
    assertTrue(service.getPlayers().isEmpty());
}

@Test
void kickThenRejoinSameUuidPreservesArrivalOrder() {
    var uuid = UUID.randomUUID();
    var first = playerIn(uuid, overworld);
    service.registerPlayer(first, 1);
    // Quit and re-handshake land in the mailbox before the pump runs (Folia region threads).
    service.enqueueRemove(uuid);
    var rejoined = playerIn(uuid, overworld);
    service.enqueueRegister(rejoined, 1);
    service.tick();
    assertEquals(1, service.getPlayers().size());
    assertSame(rejoined, service.getPlayers().get(uuid).getPlayer(),
            "remove must apply before the re-register that followed it");
}
```

- [ ] **Step 2:** Run: `./gradlew :paper:test --tests '*PaperRequestProcessingServiceTest*'` — Expected: FAIL (`enqueueRegister` undefined).
- [ ] **Step 3: Implement.** In `PaperRequestProcessingService` add (imports: `java.util.concurrent.ConcurrentLinkedQueue`):

```java
/** Cross-thread lifecycle ingress. On Folia, handshakes and PlayerQuit arrive on region
 *  threads; registerPlayer/removePlayer mutate pump-owned state (including the generation
 *  service's non-concurrent maps), so region-thread callers enqueue here and tick() drains
 *  first — one queue preserves arrival order across a kick→rejoin of the same UUID. */
private sealed interface LifecycleEvent {
    record Register(ServerPlayer player, int capabilities) implements LifecycleEvent {}
    record Remove(UUID uuid) implements LifecycleEvent {}
}

private final ConcurrentLinkedQueue<LifecycleEvent> lifecycleMailbox = new ConcurrentLinkedQueue<>();

/** Any thread. Applied at the top of the next tick(). */
public void enqueueRegister(ServerPlayer player, int capabilities) {
    this.lifecycleMailbox.add(new LifecycleEvent.Register(player, capabilities));
}

/** Any thread. Applied at the top of the next tick(). */
public void enqueueRemove(UUID uuid) {
    this.lifecycleMailbox.add(new LifecycleEvent.Remove(uuid));
}

private void drainLifecycleMailbox() {
    LifecycleEvent ev;
    while ((ev = this.lifecycleMailbox.poll()) != null) {
        switch (ev) {
            case LifecycleEvent.Register r -> registerPlayer(r.player(), r.capabilities());
            case LifecycleEvent.Remove r -> removePlayer(r.uuid());
        }
    }
}
```

and insert `drainLifecycleMailbox();` as the first statement of `tick()` after the `config.enabled` guard.

- [ ] **Step 4:** Run: `./gradlew :paper:test --tests '*PaperRequestProcessingServiceTest*'` — Expected: PASS.
- [ ] **Step 5:** Commit: `git commit -am "folia: lifecycle mailbox — pump-owned register/remove"`

### Task 5: Route quit + handshake registration through the mailbox

**Files:**
- Modify: `paper/src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java:243` (registrar lambda), `:307-313` (`onPlayerQuit`)
- Test: `paper/src/test/java/dev/vox/lss/paper/LSSPaperPluginGlueTest.java` (registrar contract already drives the `HandshakeRegistrar` seam — unchanged; the production lambda swap is covered by Task 4's unit tests + soak)

**Interfaces:** `HandshakeRegistrar` seam unchanged (production lambda now enqueues).

- [ ] **Step 1:** In `handleHandshake` (instance method), change the registrar lambda to `capabilities -> service.enqueueRegister(nmsPlayer, capabilities)`. In `onPlayerQuit`, change `service.removePlayer(...)` to `service.enqueueRemove(event.getPlayer().getUniqueId())`. Update the `HandshakeRegistrar` javadoc ("production-wired to `PaperRequestProcessingService#enqueueRegister` — on Folia this runs on the player's region thread; the pump applies it next tick").
- [ ] **Step 2:** Run: `./gradlew :paper:test` — Expected: PASS.
- [ ] **Step 3:** Commit: `git commit -am "folia: region-thread ingress (handshake register, quit) via lifecycle mailbox"`

### Task 6: Folia detection + driver `save-all` mapping (TDD)

**Files:**
- Create: `paper/src/main/java/dev/vox/lss/paper/FoliaSupport.java`
- Modify: `paper/src/main/java/dev/vox/lss/paper/soak/PaperSoakScenarioDriver.java` (`fireDueSteps`/`executeStepCommand`)
- Test: `paper/src/test/java/dev/vox/lss/paper/FoliaSupportTest.java`, extend `paper/src/test/java/dev/vox/lss/paper/soak/PaperExporterContractTest.java`'s sibling — new `paper/src/test/java/dev/vox/lss/paper/soak/PaperSoakDriverCommandTest.java`

**Interfaces:**
- Produces: `FoliaSupport.IS_FOLIA` (final boolean), `FoliaSupport.detect(ClassLookup)` (seam for tests); `PaperSoakScenarioDriver.mapsToFoliaNoOp(String cmd, boolean folia)` (static, package-visible).

- [ ] **Step 1: Failing tests.**

```java
// FoliaSupportTest.java
class FoliaSupportTest {
    @Test
    void detectFalseWhenClassAbsent() {
        assertFalse(FoliaSupport.detect(name -> { throw new ClassNotFoundException(name); }));
    }

    @Test
    void detectTrueWhenClassPresent() {
        assertTrue(FoliaSupport.detect(name -> Object.class));
    }

    @Test
    void unitTestClasspathIsNotFolia() {
        assertFalse(FoliaSupport.IS_FOLIA, "paper-api test classpath must not look like Folia");
    }
}

// PaperSoakDriverCommandTest.java
class PaperSoakDriverCommandTest {
    @Test
    void saveAllMapsToNoOpOnFoliaOnly() {
        assertTrue(PaperSoakScenarioDriver.mapsToFoliaNoOp("save-all", true));
        assertTrue(PaperSoakScenarioDriver.mapsToFoliaNoOp("save-all flush", true));
        assertTrue(PaperSoakScenarioDriver.mapsToFoliaNoOp("/save-all flush", true));
        assertFalse(PaperSoakScenarioDriver.mapsToFoliaNoOp("save-all", false));
        assertFalse(PaperSoakScenarioDriver.mapsToFoliaNoOp("setblock 0 64 0 stone", true));
        assertFalse(PaperSoakScenarioDriver.mapsToFoliaNoOp("gamerule doDaylightCycle false", true));
    }
}
```

- [ ] **Step 2:** Run: `./gradlew :paper:test --tests '*FoliaSupportTest*' --tests '*PaperSoakDriverCommandTest*'` — Expected: FAIL (classes missing).
- [ ] **Step 3: Implement `FoliaSupport`:**

```java
package dev.vox.lss.paper;

/** Platform probe. Folia's presence is detected by its regionizer class — the canonical
 *  check per PaperMC docs. Behavioral differences stay tiny by design (the plugin runs the
 *  same GlobalRegionScheduler pump on both platforms); this flag exists for the few spots
 *  where Folia removes functionality outright (e.g. the save-all command). */
public final class FoliaSupport {
    @FunctionalInterface
    interface ClassLookup {
        Class<?> lookup(String name) throws ClassNotFoundException;
    }

    public static final boolean IS_FOLIA = detect(Class::forName);

    static boolean detect(ClassLookup lookup) {
        try {
            lookup.lookup("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private FoliaSupport() {}
}
```

- [ ] **Step 4: Implement the driver mapping.** In `PaperSoakScenarioDriver`:

```java
/** Folia unregisters /save-all (docs.papermc.io/folia/faq); executing it would log
 *  "Unknown command" and save nothing. The Folia soak staging compensates with an
 *  aggressive bukkit.yml autosave and the end-of-scenario halt(false) full save
 *  (RegionShutdownThread saves all worlds), so the timeline step becomes an
 *  acknowledged no-op instead of a per-platform scenario fork. */
static boolean mapsToFoliaNoOp(String cmd, boolean folia) {
    if (!folia) return false;
    String bare = cmd.startsWith("/") ? cmd.substring(1) : cmd;
    return bare.equals("save-all") || bare.startsWith("save-all ");
}
```

and in `fireDueSteps`, replace the `executeStepCommand(step.cmd)` call site:

```java
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
```

and add `row.put("mapped", mapped);` next to the existing `row.put("ok", !threw);` (checker treats unknown keys as inert; `ok` stays true for mapped rows).

- [ ] **Step 5:** Run: `./gradlew :paper:test` — Expected: PASS.
- [ ] **Step 6:** Commit: `git commit -am "folia: platform probe + soak driver save-all mapping"`

### Task 7: plugin.yml `folia-supported: true` + flip the pinned contract test

The existing pin (`PluginYmlContractTest.foliaSupportedIsAbsent`, line 101) exists precisely because the plugin used to be built on BukkitRunnable assumptions — Tasks 2–5 removed that reason, so the pin flips (this is a deliberate pinned-decision migration, not a test deletion).

**Files:**
- Modify: `paper/src/main/resources/plugin.yml` (add `folia-supported: true` after `api-version`)
- Modify: `paper/src/test/java/dev/vox/lss/paper/PluginYmlContractTest.java:101-103` + class javadoc line 27

- [ ] **Step 1: Flip the test first:**

```java
@Test
void foliaSupportedIsDeclared() {
    assertTrue(yml.getBoolean("folia-supported"),
            "folia-supported: true is required for Folia to load the plugin; the scheduler "
            + "and lifecycle work that makes it safe is pinned by the mailbox + "
            + "GlobalRegionScheduler tests");
}
```

Update the class javadoc: the flag's absence guard becomes "…and `folia-supported` must stay `true` now that the pump runs on GlobalRegionScheduler — removing it silently drops Folia support."

- [ ] **Step 2:** Run: `./gradlew :paper:test --tests '*PluginYmlContractTest*'` — Expected: FAIL (flag absent).
- [ ] **Step 3:** Add to `paper/src/main/resources/plugin.yml` (after `api-version` line): `folia-supported: true`
- [ ] **Step 4:** Run: `./gradlew :paper:test --tests '*PluginYmlContractTest*'` — Expected: PASS.
- [ ] **Step 5:** Commit: `git commit -am "folia: declare folia-supported in plugin.yml (flips the absence pin)"`

### Task 8: Cross-thread command reads — audit + document

On Folia `/lsslod` runs on the sender's region thread (player) or the global thread (console). Reads must not iterate non-concurrent collections that the pump mutates.

**Files:**
- Inspect: `common/src/main/java/dev/vox/lss/common/DiagnosticsFormatter.java`, `common/src/main/java/dev/vox/lss/common/processing/TickDiagnostics.java`, `paper/src/main/java/dev/vox/lss/paper/PaperCommands.java`, `paper/src/main/java/dev/vox/lss/paper/PaperChunkGenerationService.java:275-289`
- Modify: comments only, unless the audit finds a non-concurrent iteration — then fix minimally (e.g. snapshot copy on the pump) with a test.

- [ ] **Step 1:** Trace every read reachable from `PaperCommands.onCommand`: `service.getPlayers()` (CHM — safe), per-state metrics (verify atomic/volatile), `getTickDiagnostics()` → `TickDiagnostics.format` (verify primitives only), `genService.getDiagnostics()` (volatile counters + racy `active.size()` — safe, stale-tolerable), disk-reader diagnostics (verify atomics). Record findings in the commit message.
- [ ] **Step 2:** Update `PaperChunkGenerationService.getActiveCount()`/`getDiagnostics()` comments: "Pump thread for exact values; command threads read racily (stale-tolerable admin diagnostics — never iterate `active` off-pump)." Add a sentence to `PaperCommands` class javadoc: "On Folia, command dispatch is region-threaded; every read on this path is a concurrent structure or a stale-tolerable primitive (audited 2026-07-02)."
- [ ] **Step 3:** If (and only if) Step 1 found a non-concurrent iteration: write the failing test proving the unsafe read, fix minimally, re-run `./gradlew :paper:test`.
- [ ] **Step 4:** Commit: `git commit -am "folia: audit + document cross-thread command reads"`

### Task 9: `runFolia` soak server task in paper/build.gradle

**Files:**
- Modify: `paper/build.gradle` (after the `runSoakServer` registration, lines 77-86)

- [ ] **Step 1:** Add:

```groovy
// Folia soak twin (scripts/soak.sh SOAK_PLATFORM=folia). run-paper's opt-in Folia task
// downloads folia-<mc>-<build>.jar from the PaperMC downloads service; we point it at the
// same soakShadowJar + scenario plumbing as runSoakServer. It doubles as the manual Folia
// smoke task; without -Psoak.scenario the driver stays inert (empty system property).
runPaper.folia.registerTask()
tasks.named('runFolia', xyz.jpenilla.runpaper.task.RunServer) {
    minecraftVersion(project.minecraft_version)
    runDirectory(file('build/run/folia-soak-server'))
    pluginJars(soakShadowJar.flatMap { it.archiveFile })
    systemProperty 'lss.soak.scenario', findProperty('soak.scenario') ?: ''
    maxHeapSize = '2G'
}
```

- [ ] **Step 2:** Run: `./gradlew :paper:tasks --all | grep -i folia` — Expected: `runFolia` and `cleanFoliaCache` listed. Then `./gradlew :paper:test` still green.
- [ ] **Step 3:** Commit: `git commit -am "folia: runFolia soak server task (run-paper folia download)"`

### Task 10: soak.sh `SOAK_PLATFORM=folia`

**Files:**
- Modify: `scripts/soak.sh` — scenario arrays (~line 30), platform case (~52-75), usage, `all` dispatch (~92-104), platform gating (~119-131), build step (~215-220), staging step 6b (bukkit.yml)

- [ ] **Step 1:** Add after `PAPER_SCENARIOS=(...)`:

```bash
# Folia runs the identical Paper scenario set: same plugin jar, same timelines, same
# checker. save-all steps are mapped to no-ops by the driver (Folia unregisters the
# command); an aggressive bukkit.yml autosave keeps chunks flushing mid-run instead.
FOLIA_SCENARIOS=("${PAPER_SCENARIOS[@]}")
```

- [ ] **Step 2:** Add the platform case (after the `paper)` block):

```bash
    folia)
        SERVER_RUN_DIR="$PROJECT_ROOT/paper/build/run/folia-soak-server"
        SERVER_GRADLE_TASK=":paper:runFolia"
        SERVER_CONFIG_DIR="$SERVER_RUN_DIR/plugins/LodServerSupport"
        BASE_WORLD_DIR="$WORLDS_DIR/base-folia"
        PLATFORM_TAG="folia-"
        # run-paper downloads the Folia server jar inside the gradle task on first run
        SERVER_READY_TIMEOUT=240
        ;;
```

and update the error message to `(want fabric, paper or folia)` and `usage()` to list folia scenarios.

- [ ] **Step 3:** `all` dispatch: change the paper branch to

```bash
    if [[ "$SOAK_PLATFORM" == "paper" ]]; then
        for s in "${PAPER_SCENARIOS[@]}"; do "$SELF" "$s"; done
    elif [[ "$SOAK_PLATFORM" == "folia" ]]; then
        for s in "${FOLIA_SCENARIOS[@]}"; do "$SELF" "$s"; done
    else
        for s in "${ALL_SCENARIOS[@]}"; do "$SELF" "$s"; done
    fi
```

- [ ] **Step 4:** Platform gating: generalize the paper gate to

```bash
if [[ "$SOAK_PLATFORM" == "paper" && " ${PAPER_SCENARIOS[*]} " != *" $SCENARIO "* ]]; then
    echo "[soak] ERROR: Scenario '$SCENARIO' is not ported to SOAK_PLATFORM=paper"
    usage; exit 1
fi
if [[ "$SOAK_PLATFORM" == "folia" && " ${FOLIA_SCENARIOS[*]} " != *" $SCENARIO "* ]]; then
    echo "[soak] ERROR: Scenario '$SCENARIO' is not ported to SOAK_PLATFORM=folia"
    usage; exit 1
fi
if [[ "$SOAK_PLATFORM" == "fabric" && "$SCENARIO" == paper-* ]]; then
```

- [ ] **Step 5:** Build step: `if [[ "$SOAK_PLATFORM" == "paper" || "$SOAK_PLATFORM" == "folia" ]]; then ./gradlew :paper:soakShadowJar --quiet; fi`
- [ ] **Step 6:** Staging (after the `server.properties` heredoc in step 6b):

```bash
# Folia: save-all is unregistered (the driver no-ops it), so flush chunks continuously —
# every 100 ticks — to keep mid-run disk state close to what the shared timelines assume.
if [[ "$SOAK_PLATFORM" == "folia" ]]; then
    cat > "$SERVER_RUN_DIR/bukkit.yml" <<'BUKKIT'
ticks-per:
  autosave: 100
BUKKIT
fi
```

- [ ] **Step 7:** Syntax check: `bash -n scripts/soak.sh` — Expected: silent. `SOAK_PLATFORM=folia ./scripts/soak.sh definitely-not-a-scenario` — Expected: unknown-scenario error mentioning folia usage.
- [ ] **Step 8:** Commit: `git commit -am "folia: SOAK_PLATFORM=folia harness platform"`

### Task 11: release_check.py — folia-supported gate (TDD via selftest)

**Files:**
- Modify: `scripts/release_check.py` (the paper plugin.yml check + selftest fixtures)

- [ ] **Step 1:** Locate the existing paper-jar plugin.yml assertions (version expansion; `grep -n "plugin.yml" scripts/release_check.py`). Add to the same check: parse the jar's `plugin.yml` text for a line matching `^folia-supported:\s*true\s*$`; failure message `plugin.yml must declare folia-supported: true (Folia refuses to load the plugin without it)`.
- [ ] **Step 2:** Extend the selftest: the existing fixture builder that fabricates a passing paper jar must now include `folia-supported: true` (otherwise the selftest's happy path fails — that IS the red step), plus one new catch case: a paper jar whose plugin.yml lacks the flag must fail with the new message.
- [ ] **Step 3:** Run: `python3 scripts/release_check.py --selftest` — Expected: all cases pass (count increases by ≥1).
- [ ] **Step 4:** Commit: `git commit -am "folia: release gate — paper jar must declare folia-supported"`

### Task 12: Docs + release loaders

**Files:**
- Modify: `README.md` (supported-platforms section: add Folia; note /reload unsupported on Folia), `CLAUDE.md` (Project line: "Paper (server only)" → "Paper/Folia (server only)"; Soak Testing section: add `SOAK_PLATFORM=folia`; Architecture: one sentence on the GlobalRegionScheduler pump + lifecycle mailbox), `.github/workflows/release.yml` (paper mc-publish step: add `folia` to `loaders`)
- [ ] **Step 1:** Make the three edits. In release.yml the paper step's loaders list (currently `paper` + `purpur`) gains `folia`.
- [ ] **Step 2:** Run: `python3 scripts/release_check.py --selftest && bash -n scripts/soak.sh` — Expected: green (no functional change).
- [ ] **Step 3:** Commit: `git commit -am "folia: docs + release loaders"`

### Task 13: Static verification suite

- [ ] **Step 1:** `./gradlew :paper:test` — Expected: PASS (~215+ tests).
- [ ] **Step 2:** `./gradlew :fabric:test -x runGameTest -x runClientGameTest` — Expected: PASS (Fabric untouched; guards against accidental common/ changes).
- [ ] **Step 3:** `CI=true ./gradlew :fabric:build -x runClientGameTest :paper:shadowJar -Pmod_version=folia-smoke && python3 scripts/release_check.py` — Expected: `OK` (proves the release jar still excludes soak classes and now carries the flag).
- [ ] **Step 4:** `python3 scripts/check_soak.py --selftest && python3 scripts/soak_report.py --selftest` — Expected: PASS.
- [ ] **Step 5:** Commit anything outstanding; push nothing yet.

### Task 14: Live validation (Stage 5 of the pipeline)

- [ ] **Step 1:** `SOAK_PLATFORM=folia ./scripts/soak.sh fresh-backfill` — first run downloads `folia-26.1.2-8.jar` via run-paper; watch for the `Done (` ready line, driver activation, and a passing verdict. Expected: `verdict.json` all-green; base world saved to `soak-worlds/base-folia`.
- [ ] **Step 2:** `SOAK_PLATFORM=folia ./scripts/soak.sh all` — Expected: all four scenarios pass.
- [ ] **Step 3:** Paper regression: `SOAK_PLATFORM=paper ./scripts/soak.sh all` — Expected: all four pass (the scheduler swap must not regress Paper).
- [ ] **Step 4:** Inspect one Folia `server.jsonl`: confirm `command` rows show `"mapped": true` only for `save-all` steps, and `soak_report.py` output flags no new anomaly class.
- [ ] **Step 5:** Update `plan-progress.md` (results, timings, surprises) and commit.

## Self-Review notes

- Spec coverage: §2→T7/T11/T12, §3→T2/T3, §4→T4/T5, §5→T2/T3/T6/T7/T8, §6→T6/T9/T10/T13/T14, §7→T11/T12. §8 has no tasks by design.
- The pinned `foliaSupportedIsAbsent` test flips in T7 *after* the scheduler/mailbox work (T2–T5) removes its reason — ordering is load-bearing.
- Type consistency: `enqueueRegister(ServerPlayer, int)` / `enqueueRemove(UUID)` used identically in T4 (definition) and T5 (call sites); `mapsToFoliaNoOp(String, boolean)` in T6 only.
