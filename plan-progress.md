# Folia Support — Progress & Decision Log

Standing request (2026-07-02): add Folia server support to LSS. Pipeline: research → design/plan →
subagent plan review + fixes → implementation → creative test strategy (Folia's automated-test
support is weak) → subagent implementation review + fixes. Ultracode workflows drive each stage;
work is autonomous (no user gates). Usage gate checked between stages (currently 40%).

## Stage log

### Stage 1 — Research (DONE 2026-07-02)

Workflow `folia-research` (run wf_968adae3-18c) launched with 7 parallel lanes + completeness
critic + gap-fill round:

- **External lanes:** Folia threading/region model & API legality rules; the four Folia schedulers
  (+ availability on plain Paper — decides single-jar vs separate plugin); dev/test tooling (Folia
  dev bundle for MC 26.1.2, run-paper `runFolia`, downloads API, headless boot for CI/soak);
  prior art (Chunky, map renderers, other chunk-heavy plugins' Folia ports).
- **Codebase lanes:** full threading/NMS/scheduler audit of `paper/` (per-class Folia risk table);
  the `common/` OffThreadProcessor/IncomingRequestRouter thread contract a Folia layer must
  satisfy; soak/CI/build wiring touch points (`soak.sh`, `release_check.py`, workflows,
  settings.gradle) for a Folia variant.

Research dossier: scratchpad `folia-research/*.md` (7 lanes + 6 gap-fill answers, incl. two LIVE
empirical probes against a real folia-26.1.2-8 server). Load-bearing verified facts:

- Folia 26.1.2 exists (build 8 STABLE); dev bundle `dev.folia:dev-bundle:26.1.2.build.8-stable`;
  paperweight-userdev 2.0.0-beta.21 has `foliaDevBundle()`; run-paper 3.0.2 has
  `runPaper.folia.registerTask()` → `runFolia`.
- The four Folia schedulers (`io.papermc.paper.threadedregions.scheduler`) ship in PLAIN Paper's
  API (verified by javap on our exact dev bundle); on Paper, GlobalRegionScheduler runs on the
  main thread. BukkitScheduler throws UnsupportedOperationException on Folia.
- Our NMS surface is Folia-legal: `ChunkMap.read` from LSS pool threads (verified empirically,
  routes through Moonrise pending-write cache — no torn reads); `getChunkNow` = concurrent-map
  lookup, thread check deleted; light reads = thread-safe SWMR clones; `PalettedContainer.write`
  synchronized. Wire bytes identical Paper↔Folia (zero Folia patches on those files).
- Cross-thread player reads (getBlockX/Z) never throw (CraftPlayer.getHandle() exempt; NMS field
  reads unguarded — racy but tolerable); `connection.send` is thread-safe (MultiThreadedQueue).
- Incoming plugin messages arrive on the sending player's OWNING REGION thread (main thread on
  Paper); PlayerQuitEvent likewise; console/driver commands run on the global region thread.
- `save-all` is UNREGISTERED on Folia (silent unknown-command); `MinecraftServer.halt(false)` is
  legal from scheduler threads and saves all worlds via RegionShutdownThread. onEnable runs on the
  bootstrap Server thread; all four schedulers usable from onEnable (verified live). onDisable on
  normal stop runs on the Region shutdown thread — blocking joins are safe there; /reload runs it
  on the global tick thread (Folia norm: document /reload unsupported).
- Folia boots headless in ~9s on 2 pinned cores; harness ready-grep "Done" matches; Folia's
  re-added watchdog is diagnostic-only, and run-paper disables Paper's killing watchdog.
- Ecosystem: nobody CI-tests Folia (runFolia = local smoke only); MockBukkit's Folia mocks are
  API-shape only. A soak-harness port exceeds current ecosystem practice.

### Stage 2 — Design spec + implementation plan (DONE 2026-07-02)

- Spec: `docs/superpowers/specs/2026-07-02-folia-support-design.md` (architecture: single jar,
  GlobalRegionScheduler pump, lifecycle mailbox, save-all mapping, soak-on-Folia test strategy).
- Plan: `docs/superpowers/plans/2026-07-02-folia-support.md` — 14 TDD tasks. Notable: the repo has
  a PINNED test (`PluginYmlContractTest.foliaSupportedIsAbsent`) deliberately guarding against
  declaring Folia support while the plugin was BukkitRunnable-based; the plan flips it in Task 7
  only after Tasks 2–5 remove its reason (pinned-decision migration, not a test deletion).
- Work branches off the unmerged `fix/lod-lighting-and-review-findings` as `feat/folia-support`
  (Folia work builds on the accumulated delivery-honesty state; both merge together or in order).

### Stage 3 — Plan review + fixes (DONE 2026-07-02)

Adversarial workflow `folia-plan-review` (26 agents; 4 lenses: Folia-fact accuracy,
code-consistency, concurrency adversary, completeness → per-finding verification): 22 raw
findings → 20 survived (most CONFIRMED), 2 refuted. Consolidated into 13 distinct fixes applied
to spec + plan (see plan §"Post-review revisions"). Highest-value catches:

1. **runFolia duplicate-plugin hazard (CONFIRMED from run-task 3.0.2 bytecode):** default
   `pluginsMode=PLUGIN_JAR_DETECTION` auto-adds the *release* shadowJar next to the explicitly
   wired soakShadowJar → two same-named plugins, nondeterministic winner, soak hangs when the
   soak-stripped release jar wins. Fix: `pluginsMode = INHERIT_NONE`.
2. **Driver onJoin region-thread race:** PlayerJoinEvent fires on the joining player's region
   thread on Folia; the driver's join-anchor state is global-thread-owned. Fix: hop the body to
   the pump via GlobalRegionScheduler.execute.
3. **Mailbox drain placement:** draining after the `enabled` guard would leak the queue forever
   on enabled=false servers (quits enqueue unconditionally). Fix: drain before the guard (+ test).
4. **Wrong exception fact:** disabled-plugin scheduling throws `IllegalPluginAccessException`
   (same type as legacy scheduler — exact parity), not IllegalStateException.
5. **No durable wiring pin:** added a class-reference contract test (constant-pool scan: no
   BukkitRunnable/BukkitScheduler refs in Folia-critical classes; plugin wires enqueue*).
6. Checker/report integration: `mapped` key → `KNOWN_SERVER_KEYS` + only-when-true emission;
   soak_report.py results-dir regex learns the `folia-` tag; CI gains `:paper:test` (was never
   in CI — all new pins would have been dev-machine-only).

## Major decisions

- **D1 — Single jar, not a new subproject (2026-07-02).** The existing Paper plugin becomes
  Folia-compatible: `folia-supported: true` + swap the 3 BukkitScheduler sites to
  GlobalRegionScheduler (identical main-thread semantics on plain Paper, zero reflection, zero
  new deps). *Alternatives rejected:* separate `folia/` module compiling against
  `foliaDevBundle` (a third twin set to drift, doubled release artifacts, no API need — Paper API
  ships the schedulers and the NMS surface is verified identical); shading FoliaLib/MorePaperLib
  (dependency + relocation overhead for 3 call sites).
- **D2 — Keep the single global pump (2026-07-02).** `service.tick()` moves to
  `GlobalRegionScheduler.runAtFixedRate(plugin, …, 1, 1)`; probes (`getChunkNow` + serialize) stay
  on the pump thread — verified legal off-region, racy-but-safe (synchronized palettes, cloned
  light), staleness healed by dirty-broadcast. *Alternatives rejected:* per-region probe fan-out
  with merged snapshots (breaks latest-wins postSnapshot, violates the bandwidth-limiter
  single-flusher invariant, big redesign for no user-visible freshness gain); disk-reads-only on
  Folia (loses loaded-chunk freshness for no reason once probes were proven safe).
- **D3 — Lifecycle mailbox for cross-thread ingress (2026-07-02).** On Folia, handshake
  registration and PlayerQuit arrive on region threads; both now enqueue into a CLQ mailbox
  drained at the top of tick(), keeping register/remove single-owner on the pump (fixes the
  quit-path mutation of the generation service's non-concurrent maps; preserves arrival order).
  Batch chunk-request ingress already lands in per-player ConcurrentLinkedQueues — unchanged.
  *Alternatives rejected:* direct region-thread registerPlayer (races tick's dimension-change
  remove+register); making all lifecycle maps concurrent (breaks single-owner invariants the
  twin tests pin, subtler to verify).
- **D4 — `save-all` on Folia: driver maps it to a no-op + aggressive autosave staging
  (2026-07-02).** Folia unregisters save-all; the soak driver detects Folia and treats timeline
  `save-all` as an acknowledged no-op while the staged Folia config sets a short autosave
  interval; end-of-scenario halt() saves all worlds anyway. *Alternatives rejected:* NMS
  mid-run global save (no legal cross-region path); dropping fresh-backfill on Folia (it builds
  the base world).
- **D5 — Test strategy: Tier-1 JUnit + a full `SOAK_PLATFORM=folia` harness port (2026-07-02).**
  Unit tests pin the mailbox/scheduler/glue seams; the real gate is the existing soak scenario
  suite (fresh-backfill, warm-rejoin, dimension-trip, paper-dirty-falling-block) against a real
  Folia 26.1.2 server via a run-paper Folia task, unchanged Fabric soak client + checker.
  *Alternatives rejected:* MockBukkit Folia mocks (API-shape only, no region semantics); manual
  smoke only (the harness already exists and is the project's Paper-runtime gate).
- **D6 — /reload documented as unsupported on Folia (2026-07-02);** normal stop verified safe for
  our blocking onDisable joins. Runtime plugin-manager disables get cheap containment (volatile
  `shuttingDown` checked by tick(); onDisable nulls the service field before shutdown) and are
  documented best-effort.
- **D7 — First Folia release ships labeled "experimental" (2026-07-02, from review finding).**
  The live gate exercises one player region + the global region; concurrent multi-region ingress
  (a second simultaneous soak client) is the recorded exit criterion for dropping the label.
  *Alternative rejected for now:* building two-client soak support into the harness first —
  real harness surgery that would gate all Folia value on it; revisit post-release.
