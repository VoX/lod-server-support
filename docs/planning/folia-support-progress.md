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

- Spec: `docs/planning/specs/2026-07-02-folia-support-design.md` (architecture: single jar,
  GlobalRegionScheduler pump, lifecycle mailbox, save-all mapping, soak-on-Folia test strategy).
- Plan: `docs/planning/plans/2026-07-02-folia-support.md` — 14 TDD tasks. Notable: the repo has
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

### Stage 4 — Implementation (2026-07-02)

All plan tasks 2–12 implemented TDD on `feat/folia-support`, one commit per task:

- T2: `LSSPaperPlugin.scheduleServiceTick` + soak-driver tick → `GlobalRegionScheduler.runAtFixedRate`;
  driver `onJoin` hops to the pump (join events are region-threaded on Folia).
- T3: generation completion hop → `GlobalRegionScheduler.execute`; dead `plugin` field removed.
- T4: lifecycle mailbox (`enqueueRegister`/`enqueueRemove`, drained BEFORE the enabled guard);
  5 new PP-011 tests incl. disabled-drain + 8-thread foreign-thread visibility.
- T5: handshake registrar + quit listener route through the mailbox.
- T5b: `FoliaWiringContractTest` — upgraded from the plan's string scan to a ~40-line
  constant-pool parser after the naive scan false-positived on
  `HandshakeGate.Decision#registerPlayer()` (same name, different owner); now pins
  (owner, name) method refs precisely.
- T6: `FoliaSupport` probe + driver `save-all`→no-op mapping (`mapped: true` row key);
  `check_soak.py` schema + selftest (120 cases).
- T7: `plugin.yml folia-supported: true`; flipped the pinned absence test
  (`foliaSupportedIsAbsent` → `foliaSupportedIsDeclared`) — its reason (BukkitRunnable
  assumptions) died with T2–T5.
- T8: cross-thread `/lsslod` audit (all reads = CHM iteration or stale-tolerable primitives —
  documented in place); `shuttingDown` volatile + onDisable nulls-before-shutdown +
  PP-012 pinning test.
- T9: `runFolia` soak task, `pluginsMode = INHERIT_NONE` (verified enum via javap on the
  cached run-task 3.0.2 jar); task registers cleanly.
- T10: `SOAK_PLATFORM=folia` in soak.sh (case, FOLIA_SCENARIOS, gating, build step,
  bukkit.yml autosave-100 staging); `soak_report.py` dir-tag regex extracted to
  `_scenario_from_dirname` + selftests (20 cases).
- T11: `release_check.py` paper-jar `folia-supported` gate + selftests (11 cases).
- T12: README (5 touch points, experimental label), CLAUDE.md, release.yml folia loader,
  build.yml gains `:paper:test`.

### Stage 5 — Live validation (DONE 2026-07-02)

**First live Folia run caught a real design bug the plan review missed.** fresh-backfill ran
end-to-end (server booted 12 s, plugin loaded, driver + client + checker all functional) but
failed 3 checker laws with one root cause: 6,887/7,376 generations logged "Chunk was null after
async load completed" — the A5 gap (6,886) equals submitted−completed exactly. On Folia,
`getChunkAtAsync` completes on the chunk's owning REGION thread and the load ticket dies with
the callback; an isolated LOD chunk unloads again before the hop to the next GLOBAL tick, so
the pump's `getChunkNow` re-fetch found nothing. **Fix (D8):** serialize in the completion
callback (owning region thread — the reads were verified legal there by gap-1) and hop only
the immutable `LoadedColumnData` to the pump; new `completeAsyncLoad` seam preserves every
containment pin. *Alternatives considered:* plugin chunk tickets to pin the chunk until the
pump ran (rejected — ticket add/remove is itself region-bound, more moving parts); accepting
disk-read-later semantics (rejected — turns every generation into a generate+unload+reread
cycle and still fails the >500-completions floor). This is the squaremap "extract on owning
thread, publish immutable" pattern from the research dossier.

**After the fix: fresh-backfill PASS — 39 windows (39 client-laws), 39 quiescent snapshots,
0 violations, 0 warnings, zero null-chunk errors** (before: 6 windows, 3 violations, 6,887
errors). Server: `Folia version 26.1.2-8` (build 8 — the exact build all research facts were
verified against). Exactly one LSS plugin loaded (`INHERIT_NONE` confirmed working; run-paper
injects via --add-plugin, so the check is the plugin-load log line, not a jars listing).
**Stage 5 result (2026-07-02): ALL GREEN — 8/8.** `SOAK_PLATFORM=folia ./scripts/soak.sh all`:
fresh-backfill, warm-rejoin, dimension-trip, paper-dirty-falling-block all PASS against Folia
26.1.2 build 8. Paper regression `SOAK_PLATFORM=paper ./scripts/soak.sh all`: same four PASS
(the GlobalRegionScheduler swap did not regress Paper). Spot-checks: `"mapped": true` appears
exactly on the mapped save-all command row; soak_report parses `-folia-` result dirs correctly;
the single report anomaly is the expected fresh-backfill startup request spike (lens, not
gate). Plus the full static suite: Fabric Tier 1+2, Paper Tier 1 (~230 tests incl. the new
seams), release_check OK, all three selftests (120/20/11).

### Stage 6 — Implementation review + fixes (DONE 2026-07-02)

Adversarial workflow `folia-impl-review` (20 agents; 6 lenses over `27ba6ab..HEAD`, prompts
aimed at what a single-player stationary soak cannot catch): 14 raw → 12 CONFIRMED survived,
2 refuted (deferred-registration batch race — self-heals by protocol design; mapped-row
ok-semantics — checker-verified). 9 distinct fixes applied:

1. **FoliaWiringContractTest was blind to nested classes** (the standout: a `BukkitRunnable`
   revert compiles its only scheduler constants into `LSSPaperPlugin$1$1.class`, which the
   test never opened — verified by probe compile). Now walks EVERY production class under
   `dev/vox/lss/paper` (nested + soak), checks CONSTANT_Class entries as well as method-ref
   owners (an anonymous BukkitRunnable subclass's call is owned by the subclass — only the
   tag-7 superclass constant names org/bukkit/scheduler), and a coverage canary asserts the
   pump's threadedregions ref is inside the scanned set.
2. **`completeAsyncLoad`'s Error rethrow was dead code** — whenComplete captures a rethrow
   into an unobserved dependent future. Errors now go to the thread's uncaught handler
   (books-first preserved; test pins the handoff via a capturing handler).
3. **Guard order:** `shuttingDown` is checked BEFORE the mailbox drain (an overlapped
   runtime-disable tick must not registerPlayer into mid-teardown collaborators); the
   drain-before-enabled behavior is preserved and both are pinned.
4. `PaperSectionSerializer`'s "must be called on the server thread" contract rewritten to the
   real concurrent contract (completion threads + pump; stateless/reentrant).
5. CLAUDE.md gains the experimental qualifier (spec §6.5 drift).
6. soak.sh header + check_soak.py docstrings updated for the folia platform.
7. `runFolia` build.gradle comment now honest about manual-smoke reuse of soak staging (EULA,
   leftover scenario config).
8. release.yml Modrinth display name → "Paper/Folia (MC 26.1.2)"; CLAUDE.md notes Folia
   release-notes must carry the experimental status.
9. Bookkeeping: Stage 5 heading fixed; plan doc carries an EXECUTED banner pointing here.

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
  *Revisited (2026-07-03, shipped v0.5.1):* a narrower regionized-probe design was adopted — probes
  run on the chunk's owning region thread (`EntityScheduler` + `isOwnedByCurrentRegion`) and publish
  into a per-player batch that the pump consumes, so the pump stays the single flusher and
  latest-wins postSnapshot is untouched (the specific objections above do not apply). A one-tick
  hold-release aligns each request with its probe result. Gated `regionizedProbing = IS_FOLIA`;
  motivation is ownership-contract correctness (off-owner palette reads), not a freshness gain —
  still experimental. See `PaperRequestProcessingService` + `RegionProbeSchedulingTest`.
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
