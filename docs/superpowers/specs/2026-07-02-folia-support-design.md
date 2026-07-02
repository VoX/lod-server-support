# Folia Support — Design Spec (2026-07-02)

## 1. Goal

Users are asking for Folia support. Folia is PaperMC's regionized-multithreading fork: the world
is partitioned into independently ticking regions (each with its own 20 TPS loop on a thread
pool), there is **no main thread**, the legacy `BukkitScheduler` throws
`UnsupportedOperationException`, and plugins load only if their `plugin.yml` declares
`folia-supported: true`. Deliverable: the LSS server plugin runs correctly on Folia 26.1.2 and we
can prove it with automated tests.

All Folia claims in this spec were verified live on 2026-07-02 (source-level against Folia
`ver/26.1.x` + Paper `ver/26.1.2`, and empirically against a booted `folia-26.1.2-8.jar`).
Research dossier: session scratchpad `folia-research/*.md`.

## 2. Architecture decision: one jar, the Paper plugin becomes Folia-compatible

**Chosen:** extend the existing `paper/` module. The release artifact stays
`lod-server-support-paper.jar`; it declares `folia-supported: true` and runs on Paper, Purpur,
and Folia.

Why this is safe (all verified):

- The four Folia schedulers (`io.papermc.paper.threadedregions.scheduler.*`, obtained via
  `Bukkit.getGlobalRegionScheduler()` etc.) **ship in plain Paper's API** — present in our exact
  dev bundle (`26.1.2.build.69-stable`). On plain Paper, `GlobalRegionScheduler` executes on the
  main thread (`FoliaGlobalRegionScheduler` ticked from the main loop), so swapping our
  `BukkitScheduler` call sites to it preserves Paper semantics exactly. Zero reflection, zero new
  dependencies, no compile-against-Folia needed.
- Our entire NMS surface is identical on Folia (zero Folia patches on these files, plus an
  empirical probe): `ChunkMap.read` (Moonrise `loadDataAsync`, no thread check, reads answered
  from the pending-write cache so they cannot tear against saves), `getChunkNow` (concurrent map
  lookup; the vanilla main-thread check is deleted by Paper's chunk-system rewrite),
  `LevelLightEngine.getLayerListener(...).getDataLayerData(...)` (SWMR clone, thread-safe from
  any thread), `LevelChunkSection.write` / `PalettedContainer.write` (synchronized), and
  `connection.send` (MultiThreadedQueue, callable from any thread). Wire bytes are therefore
  Paper-identical, which the existing golden corpus pins to Fabric parity.
- `CraftPlayer.getHandle()` is explicitly exempt from Folia's off-region thread checks (unlike
  other entities), and NMS field reads (`getBlockX/Z`, `isRemoved`, `level()`) never throw —
  they are racy reads we already tolerate.

**Rejected alternatives:**

- *A separate `folia/` subproject compiling against `dev.folia:dev-bundle`* — creates a third
  twin set (this project already fights Fabric/Paper twin drift), doubles release artifacts, and
  buys nothing: there is no Folia-only API we need.
- *Shading FoliaLib / MorePaperLib* — a dependency plus mandatory relocation to abstract three
  call sites that Paper's own API already abstracts.

## 3. Threading model on Folia

The service keeps its **single global pump**. `service.tick()` moves from
`BukkitRunnable.runTaskTimer(plugin, 1, 1)` to
`Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task, 1, 1)`:

- On Paper: identical (main thread, every tick).
- On Folia: the global region thread — a single-threaded 20 TPS loop. Every single-owner
  structure in the pipeline (lifecycle maps, `SendActionBatcher`, `SharedBandwidthLimiter`'s
  single-flusher invariant, `dimensionStringCache`, `TickSnapshot` latest-wins posting, the
  generation service's `active`/`perPlayerActiveCount`/`mainReady`) stays owned by exactly one
  thread, so the `common/` OffThreadProcessor contract is satisfied unchanged.

Loaded-chunk probes (`getChunkNow` + `PaperSectionSerializer.serializeColumn`) stay on the pump
thread. On Folia this reads chunks owned by *other* region threads: legal (no thread checks on
the whole chain) and tear-free at the section level (synchronized palettes, cloned light
nibbles). Worst case is a slightly stale or cross-section-inconsistent snapshot of a chunk being
mutated mid-serialize — the same eventual-consistency tolerance BlueMap ships, and our
dirty-broadcast re-serves edited columns anyway. LSS-owned threads (processing thread, disk-read
pool, save executor) are unaffected — Folia imposes no rules on plugin threads.

**Rejected alternatives:**

- *Per-region probe fan-out* (region-scheduler hop per chunk, merged snapshots) — breaks the
  latest-wins `postSnapshot` contract (partial snapshots strand other regions' disk results),
  violates the bandwidth limiter's single-flusher requirement, and adds a large redesign for
  freshness LOD consumers cannot perceive.
- *Disk-reads-only on Folia* (drop live probes) — unnecessary once probes were proven legal, and
  it would add serve latency for loaded chunks.

## 4. Cross-thread ingress: the lifecycle mailbox

On Folia, callbacks we currently receive on the main thread arrive on **player region threads**:
incoming plugin messages (Paper rewires `PluginMessageListener` through the sending player's
owning region tick) and `PlayerQuitEvent`. Two of those paths mutate pump-owned state:

- handshake → `service.registerPlayer(...)` (mutates `players`, disk-reader registration, state
  flags — and can race the pump's own dimension-change `removePlayer`+`registerPlayer`),
- quit → `service.removePlayer(uuid)` → `generationService.removePlayer` (mutates non-concurrent
  `LinkedHashMap`/`HashMap`).

**Change:** `PaperRequestProcessingService` gains a `ConcurrentLinkedQueue` lifecycle mailbox
with two producer methods, `enqueueRegister(ServerPlayer, int capabilities)` and
`enqueueRemove(UUID)`; `tick()` drains it first, calling the existing `registerPlayer` /
`removePlayer` on the pump thread. The handshake registrar lambda and the quit listener switch
to the enqueue methods. Arrival order (e.g. kick → rejoin with the same UUID) is preserved by
the single queue. `registerPlayer`/`removePlayer` stay public and pump-context-only (the
dimension-change path and tests call them directly).

Cost: registration/removal completes ≤1 tick later than today. A batch request racing ahead of
its registration is dropped by the existing `state == null` guard and self-heals on the client's
next scan (by protocol design). The session-config handshake *reply* still sends synchronously
from the region thread (`connection.send` is thread-safe).

Everything else about ingress is already safe: `handleBatchRequest` reads the `players` CHM and
enqueues into per-player `ConcurrentLinkedQueue`s; `PaperWorldHandler`'s MONITOR listeners (which
fire on region threads on Folia) write only to the synchronized `DirtyColumnTracker`.

## 5. Remaining call-site changes

- **Generation completion hop** (`PaperChunkGenerationService`): the production
  `mainThreadScheduler` default swaps from `Bukkit.getScheduler().runTask(plugin, task)` to
  `plugin.getServer().getGlobalRegionScheduler().execute(plugin, task)`. The seam, the token
  protocol, and the disabled-plugin rejection containment are unchanged (Folia's schedulers also
  throw `IllegalStateException` for disabled plugins). `getChunkAtAsync` itself is callable from
  any thread on Folia (Chunky ships exactly this pattern); `onChunkReady` then serializes on the
  pump thread — legal per §3.
- **Soak driver tick** (`PaperSoakScenarioDriver.init`): same `BukkitRunnable` →
  `GlobalRegionScheduler.runAtFixedRate` swap. Driver commands via
  `server.getCommands().performPrefixedCommand` from the global thread are exactly
  console-equivalent on Folia (verified); `MinecraftServer.halt(false)` is legal from a
  scheduler thread and saves all worlds via Folia's `RegionShutdownThread`.
- **Commands** (`PaperCommands`): on Folia, player-sender commands run on the sender's region
  thread, console commands on the global thread — so `/lsslod` reads cross thread boundaries.
  All reads on those paths are CHM iteration, atomic/volatile counters, or racy-tolerable
  primitive reads (`active.size()`); the implementation audits the diag path for any iteration
  of a non-concurrent collection and fixes what it finds (expected: none beyond
  `TickDiagnostics` primitives). Racy-but-safe admin reads are documented in place.
- **plugin.yml**: `folia-supported: true` (ignored by Paper/Purpur, required by Folia).
- **`/reload`**: documented as unsupported on Folia (it runs disable/enable on the global tick
  thread, where our bounded shutdown joins would stall ticking; Folia ecosystem norm). Normal
  stop runs `onDisable` on the dedicated region-shutdown thread — verified safe for our joins.

## 6. Testing (Folia has no gametest equivalent — this is the creative part)

Ecosystem reality (verified): nobody CI-tests Folia; MockBukkit's Folia scheduler mocks delegate
to the single `BukkitSchedulerMock` (API-shape only, no region semantics); `runFolia` appears in
many plugins' build files but zero CI workflows. Our answer:

1. **Tier-1 JUnit (paper module)** — pins every seam this design touches: mailbox drain order
   (register→remove→re-register with one UUID), enqueue-from-foreign-thread visibility, the
   generation-hop swap (containment behavior preserved), the driver's Folia `save-all` mapping,
   and the `plugin.yml` contract (`folia-supported: true` asserted by `PluginYmlContractTest`).
2. **Live soak harness on real Folia** — `SOAK_PLATFORM=folia ./scripts/soak.sh all` runs the
   Paper scenario set (fresh-backfill, warm-rejoin, dimension-trip, paper-dirty-falling-block)
   against a downloaded `folia-26.1.2-8.jar` with the unchanged Fabric soak client and
   conservation-law checker. This exceeds current ecosystem practice. Mechanics:
   - `paper/build.gradle`: `runPaper.folia.registerTask()` registers `runFolia`
     (a `RunServer` wired to the Folia downloads service); we configure it as the Folia soak
     server (runDirectory `paper/build/run/folia-soak-server`, `pluginJars(soakShadowJar)`,
     `-Dlss.soak.scenario` plumbing, 2G heap) — mirroring `runSoakServer`.
   - `scripts/soak.sh`: new `folia` platform case (task `:paper:runFolia`, config dir
     `plugins/LodServerSupport`, base world `soak-worlds/base-folia`, `PLATFORM_TAG="folia-"`,
     ready timeout 240 s) + `FOLIA_SCENARIOS` (same four as Paper) + gating + `all` dispatch.
     The existing ready-grep (`Done` in `logs/latest.log`) matches Folia's boot line
     (verified empirically: `Done (8.689s)!` on 2 pinned cores). run-paper already disables
     Paper's killing watchdog; Folia's own re-added watchdog is diagnostic-only (verified).
   - **`save-all` gap:** Folia unregisters the `save-all` command (silent "Unknown command").
     Among the four Folia scenarios only fresh-backfill issues it (`save-all flush`). The driver
     detects Folia (`Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`) and
     maps `save-all*` steps to an acknowledged no-op row (ok=true, logged as mapped); the Folia
     staging adds a `bukkit.yml` with an aggressive autosave interval (`ticks-per: autosave:
     100`) so chunks still flush continuously mid-run, and the end-of-scenario `halt(false)`
     performs a full save regardless. If the checker still shows a disk-accounting delta in
     Stage 5, the fallback is a scenario-config override, not a driver redesign.
3. **Paper regression** — the scheduler swap also changes Paper's execution path (same thread,
   possibly different intra-tick phase), so `SOAK_PLATFORM=paper ./scripts/soak.sh all` and the
   full Tier-1/Tier-2 suites re-run as the regression gate.

## 7. Release & docs

- `release_check.py`: assert the paper jar's `plugin.yml` contains `folia-supported: true`
  (extend the existing plugin.yml expansion check + selftest fixtures). No new artifact, so jar
  globs/discovery are unchanged.
- `release.yml`: the paper mc-publish step adds `folia` to its loaders list.
- Docs: README + CLAUDE.md gain Folia support statements, the `SOAK_PLATFORM=folia` harness, and
  the `/reload`-unsupported note. Modrinth listing gains the folia loader (manual, at release).

## 8. Out of scope

- Per-region probe parallelism (see §3 rejection) — revisit only if a real Folia deployment
  shows the global pump saturating.
- A Folia-specific config surface — none needed; `threaded-regions` tuning is the admin's.
- Protocol/wire changes — none; the client is platform-blind.
- `RegionizedServerInitEvent` — unnecessary; all four schedulers are verified usable from
  `onEnable` (they queue until ticking starts).
