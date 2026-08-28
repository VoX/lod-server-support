# NeoForge support plan — client + server, best-effort tier (2026-08-14, v1.2)

**Status: PLANNED — folded into the v0.11.0 mega plan as stage N (post-pause,
pre-G). Sequencing RESOLVED (user, 2026-08-14): N precedes G, and v0.11.0
releases ALL FOUR MC lines (26.2, 26.1, 1.21.11, 1.21.1) × THREE loaders
(Fabric, NeoForge, Paper) simultaneously — §0.6. The 1.21.1 line ships the
Paper module.** Research basis:
docs/planning/neoforge-1.21.1-port-spike.md (+ its all-four-lines addendum) —
availability, API-drift, renderer, and precedent facts live there and are not
re-argued here.

**v1.2 (2026-08-14): user decisions folded.** (1) **§0.6 RESOLVED as Option A**
— stage N before G, one simultaneous four-line × three-loader release; the
pause sign-off now covers manual testing only. (2) **The 1.21.1 no-Paper cut
is REVERSED** — the line ships Fabric + NeoForge + Paper; v1.1's
`ReleaseWorkflowContractTest` relocation work item is dropped (the test keeps
its `paper/src/test` home on all four lines; the tag-guard anchoring in §4.2
STANDS). Effort re-totaled (§8): ~39-60 d added (~8-12 wk).

**v1.1 (2026-08-14): revised after the 3-Opus review round** (architecture
lens; CI/release lens; scoping/tier-honesty lens — verdicts and fold record in
§9). Headline changes from v1.0: the client half's 26.2 reality stated honestly
with an executable gate (§0.1); the best-effort tier made operational — cut
protocol, defined floor, promotion criterion, corrected tier table (§6); the
sequencing decision presented instead of resolved (§0.6); N-1 split into
N-1a/N-1b with the source-path contract-test retarget costed (§2); the
send-guard inversion + upstream #1913 as work items (§1.2); the tag-scheme
collision fix (§4.2); the jarJar-walker and contract-test-relocation fixes
(§4.2); re-costed effort (§8).

## 0. Scope decisions (user directives, 2026-08-14)

### 0.1 Client AND server ship on NeoForge — with the 26.2 reality stated

The client half assumes the player runs *some* Voxy variant exposing the
normal Voxy API surface (the j-shelfwood-class community ports — probed
against the 1.21.1 fork: `commonImpl.VoxyCommon`, `WorldIdentifier.of`,
`rawIngest(...)`, `getTaskCount`, `getStorageBasePath` all match our bridge's
shapes). **Graceful degradation is the contract, not renderer availability**:
`VoxyCompat` already treats every unresolvable handle as no-sink (warn-once,
no capability bit, inert client) — that existing ladder IS the acceptance bar.

**Honesty clause (review MAJOR):** the probe was against a *1.21.1* fork. Per
the spike's availability matrix there is **no Voxy build of any kind on 26.2
NeoForge today** — so on the line stage N actually ships, the client half goes
out **compiled and inert by construction** (no consumer → no capability bit →
the server never serves it). That is a deliberate ship-the-socket decision:
the moment a community port appears, the jar works without an LSS release.
Consequences:

- The N-3 client gate CANNOT be "manual smoke with a community Voxy build" on
  26.2 (no such build exists to smoke with). The executable gate is a
  **throwaway dev-only test mod registering an `LSSApi` consumer** that logs
  decoded column receipts — a real end-to-end client-half proof (handshake,
  capability bit, want-set, decode, dispatch) needing no renderer.
- A renderer-level gate runs where a renderer exists: the 26.1.2 line (Foxy
  shim) or the 1.21.1 line (j-shelfwood fork) at stage G — recorded as the
  place the "LODs actually render on NeoForge" box gets ticked.
- The Modrinth **version name/description must carry the caveat** (search
  results and version lists don't show release notes): "NeoForge (MC 26.2) —
  server-side; client requires a community Voxy build" (§4.2).
- README/CLAUDE.md say the same (§6).

### 0.2 Best-effort support tier — operational, not vibes

BOTH the NeoForge variant and the MC 1.21.1 line are **best-effort tier**.
The tier is DEFINED in §6 (tier table, cut protocol, floor, promotion
criterion, release-notes rule) — CLAUDE.md carries the durable copy.
Concretely pre-authorized by this plan: no Tier 3 on NeoForge (the framework
does not exist), gametest smoke subset instead of the full 71, the abbreviated
soak (§5.3) with skip pre-authorized, and the 1.21.1 line ships the spike's
feature-drop list (`useBackgroundReadSplit`/`useSelectiveNbtParse` flag-off,
Voxy reset ladder degraded). Anything NOT pre-authorized here goes through the
§6 cut protocol.

### 0.3 Sequencing — DECIDED (user, 2026-08-14): N before G, one simultaneous release

Stage N lands on MAIN after the F-pause sign-off and before G, so the backport
process carries the module; **v0.11.0 then releases ALL FOUR MC lines × THREE
loaders at the same time** — the four tags push in one release session, so the
slowest line gates them all (§0.6 records the decision and the alternatives it
overrode). Two structural consequences:

- **F-gate re-arm (review MAJOR):** stage F's pre-flight and pause validated a
  tree that N then rewrites (~16k lines moved + a new subproject). N-4
  therefore RE-ARMS the release gates: full `CI=true` pre-flight per line,
  `release_check.py` green, a fresh dev deploy to the Modrinth rig, and a
  short re-validation window before G tags anything. Sign-off on the pause
  authorizes N to start — it does NOT carry forward as sign-off on the
  post-N tree.
- **1.21.1 line scope (v1.2 — the v1.1 no-Paper cut is REVERSED by user
  decision): Fabric + NeoForge + Paper.** The Paper module port retargets the
  NMS/Moonrise surfaces per the two in-repo templates —
  `support/mc1.20.1` (pre-Moonrise Paper) and `support/mc1.21.8`
  (early-Moonrise Paper); 1.21.1 sits between them. Recorded gotcha applies:
  paperweight codebook can't parse Java 25, so paper tasks need Java 21
  locally on old lines. ~4-7 d added to G (§8). Side benefit:
  `ReleaseWorkflowContractTest` keeps its `paper/src/test` home on all four
  lines — v1.1's relocation work item is dropped.

### 0.4 Soaks — abbreviated, with the skip expected and the floor defined

NeoForge gets `SOAK_PLATFORM=neoforge` running an ABBREVIATED scenario set
(§5.3). **Honest cost note (review MAJOR):** unlike Folia (which reuses
Paper's driver wholesale), NeoForge shares an event API with neither Fabric
nor Bukkit — the platform needs a third `SoakScenarioDriver` twin (~200 lines)
plus a third metrics exporter (~400-700 lines) satisfying the exporter-schema
parity contract tests. That is well past the ~2-day budget, so **the skip
clause is EXPECTED to fire**, and the tier's floor is the §5.5 manual smoke
checklist — which this plan defines rather than gestures at.

### 0.5 VSS

The branded pair gains a `vssJarNeoforge` (same zip-repackage pattern; the
descriptor rewrite is a `neoforge.mods.toml` TOML rewrite — closer to
plugin.yml's line-based approach than fabric.mod.json's JSON one). XANTHA's
manual publish flow (`vssJars`) extends; release.yml still never publishes
VSS. Two coordination notes (review): adding a loader to the `84zcagOb`
listing is XANTHA's listing — **needs their buy-in before N-4 wires the
task**; and per §4.2, `release_check.py`'s discover step hard-fails on a
missing jar family, so the VSS-neoforge jar becomes a release blocker the
same commit the check lands — the task and the check land together.
(`brandedConfigCandidates` needs no NeoForge analogue of the Paper
data-folder fork: NeoForge configs live in the shared `config/` dir.)

### 0.6 The sequencing decision — RESOLVED (user, 2026-08-14): Option A

**Decision: Option A — stage N before G, and v0.11.0 ships all four MC lines ×
three loaders simultaneously.** The pause sign-off now covers manual testing
only (the F-gate re-arm in N-4 restores the validated-tree property before any
tag). The table below stays as the decision record — what was weighed and
overridden:

| | **Option A — N before G (this plan's default, per the include-it-in-v0.11.0 directive)** | **Option B — v0.11.0 ships first; N + the 1.21.1 line become v0.12.0** |
|---|---|---|
| v0.11.0 publish | slips by N + the 1.21.1 line (~6-9 calendar weeks total added before tags — §8) | ships on the current schedule, from the exact tree the pause validated |
| Pause integrity | repaired by the N-4 F-gate re-arm (fresh pre-flight + re-deploy + re-validation window) — but the re-arm is itself calendar time | unbroken by construction |
| G delta-port | carries the whole-tree xplat move onto the `-v0.10` bases on top of the documented ~90-file 1.21.11 adaptation set — the shape most likely to trigger the mega plan's fresh-re-port escape hatch | `-v0.11` bases cut from a tagged tree; the move lands on the NEXT round where a conflict blocks nothing urgent |
| 1.21.1 line cut from | main-at-G (untagged, mid-program) | a tagged, reviewed v0.11.0 — the spike's own recommendation |
| NeoForge wall-clock | in v0.11.0, ~6-9 weeks out | in v0.12.0 — roughly the same wall-clock (identical total work), while v0.11.0's five features reach users ~6-9 weeks sooner |
| Total work | identical | identical |

**Defensible hybrid:** authorize **N-1a/N-1b only** (the xplat extraction —
behavior-neutral) immediately after the v0.11.0 tags, in the quiet
post-release window, with N-2..N-4 + the 1.21.1 line as v0.12.0. De-risks the
delta-port question entirely and starts the port without blocking the release.

## 1. Architecture

### 1.1 Module layout (the Sodium/Iris/Lithium shape)

```
lod-server-support/
├── common/    unchanged (pure Java, zero MC imports — verified)
├── xplat/     NEW source set: MC-logic shared by fabric/ + neoforge/
│              (compiled per-loader via srcDir inclusion, NOT a Gradle project —
│               it has MC deps, so each loader module compiles it against its
│               own toolchain; Mojang mappings both sides make this source-safe)
├── fabric/    thins to loader glue + mixins + ALL existing test tiers
├── neoforge/  NEW: loader glue + AT + contract tests (MDG 2)
└── paper/     unchanged
```

- **What moves to xplat** (~16k lines): the client stack (SpiralScanner,
  ColumnStateMap, LodRequestManager, TransferRateGovernor, ClientNetTrace,
  ColumnCacheStore, ResetCoordinator, far-player client tracker/support logic),
  the server stack (ChunkDiskReader core, SectionSerializer,
  NbtSectionSerializer, generation service core, dirty pipeline core), payload
  codecs, VoxyCompat/MoonriseReadCompat/AntiXrayCompat, LSSApi, and the
  dev-only `benchmark/` package incl. `SoakScenarioDriver` (review: it was
  homeless in v1.0 — it imports `net.fabricmc` and is exactly what a future
  `SOAK_PLATFORM=neoforge` driver would twin, so it moves with a LoaderServices
  route for its loader touches; release exclusion rules unchanged).
- **What stays per-loader**: entrypoints, event/payload registration, mixin
  classes + configs (near-verbatim copies, different wiring), the far-player
  RENDERER (26.2 Fabric = COLLECT_SUBMITS; 26.2 NeoForge = the state-based
  RenderLevelStageEvent/ExtractLevelRenderStateEvent pair), config-screen glue
  (Fabric-only — Sodium's config API; NeoForge v1 is config-file + `/lsslod
  set` only), LAN hook (Fabric-only — consequence stated: a NeoForge
  integrated server published to LAN does NOT start the service in v1;
  recorded in §5.4), move-tracer bootstrap (mixin-loading differs; hook
  bodies live in xplat).
- **The seam inventory** (review MAJOR — v1.0 undercounted at "~14
  FabricLoader sites"). Per-loader seams routed through `LoaderServices`
  (xplat interface, per-loader impl) or equivalent:
  1. `FabricLoader` sites: isModLoaded, configDir, gameDir, modVersion.
  2. **Payload send** — incl. the static networking holders
     (`LSSClientNetworking`/`LSSServerNetworking` publish send functions the
     stacks call through; the holders' WIRING is per-loader even where the
     logic is xplat).
  3. **`RequestProcessingService` construction/lifecycle** — event wiring is
     per-loader (SERVER_STARTED vs ServerStarted etc.); the service body is
     xplat.
  4. **`ChannelPressureProbe` factories** (`FabricChannelPressure` /
     `PaperChannelPressure` precedent) — a NeoForge impl, else the transport
     yield gate + ping backstop silently read UNKNOWN-never-yields on
     NeoForge = silent feature loss. A contract test pins that the NeoForge
     probe resolves a real channel (review MAJOR).
  5. **Client command source type** — Fabric's client command API vs
     `RegisterClientCommandsEvent`'s source; `/lss` command bodies take a thin
     source adapter.
  6. **`ChunkSaveDataHook`** — the dirty hook is a REAL mixin on NeoForge too
     (same `SerializableChunkData.copyOf` target; vanilla class, loader-neutral
     target verified in the spike); the @Inject lives per-loader, the body
     (`onChunkSaveData`) in xplat.
  7. **Mixin ACCESSORS used by xplat code** (review MAJOR — the coupling v1.0
     missed): `ChunkDiskReader` calls `AccessorSimpleRegionStorage`/
     `AccessorIOWorker`/`AccessorRegionFileStorage`/`AccessorRegionFile` etc.
     Accessor *interfaces* are loader-generated classes. Resolution: the
     accessor interfaces themselves move to xplat as plain interfaces and each
     loader's mixin config targets them (mixin annotations are data — both
     loaders run the same Mixin library and can implement a shared interface),
     OR the reader's accessor touches route through a small per-loader
     `RegionIoAccess` seam. Decide at N-1b; the contract is "xplat compiles
     with zero loader imports", pinned by a source-regex test.
  8. **`FabricFarPlayerSnapshots`** (server-side extraction) — NeoForge twin
     over the same vanilla types.
- **Java discipline (review):** xplat is compiled by each consumer, so nothing
  builds it standalone and a Java-25-only API leak (the tree has exactly one:
  `ScopedValue` in `AntiXrayCompat`) surfaces months later on a 1.21.x
  backport. Tripwire: a CI step compiles xplat at `--release 21` with the
  known-25-only files excluded via an explicit list (currently 1 entry), so
  any NEW leak reds main immediately.

### 1.2 Non-negotiable invariants (carried from the existing architecture)

Rule generalized per review: **every "never tiered" claim names the test that
reds when it's violated.**

- **Wire bytes identical across loaders by construction**: payload classes are
  vanilla `StreamCodec` + `CustomPacketPayload`, shared via xplat verbatim —
  the same guarantee the Fabric/Paper pair already pins (`WireParityTest`).
  A NeoForge server serves Fabric clients and vice versa with zero shims.
  *Test:* the NeoForge contract suite reuses the WireParityTest byte corpus;
  §4.2's cross-loader class-digest check pins the shipped jars.
- **Every `lss:*` payload registers `.optional()`** on NeoForge (a mandatory
  payload refuses vanilla/Fabric clients at login — the fork's mistake).
  *Test:* `.optional()` census (source-regex) in the neoforge contract suite.
- **Send-guard inversion (review MAJOR — v1.0 had this backwards).** NeoForge
  THROWS on sends to unannounced channels where Fabric silently no-ops, and
  the codebase contains **zero `canSend` gates** because Fabric never needed
  them. The exposed sites are not just flush/broadcast races: **the C2S
  handshake is an unprompted FIRST send** — a NeoForge client joining a
  server without LSS (vanilla, or Fabric-without-LSS) would THROW on the
  handshake send, exactly the kind of with-other-mods crash issue #160 is
  about. Work item: a `sendIfListening` wrapper at the LoaderServices seam
  (NeoForge impl checks channel availability / catches `UnsupportedOperation`
  shape; Fabric impl passes through), applied to EVERY send site — handshake,
  client_info, want-set, empty-clear, far-player prefs, and all S2C paths.
  *Test:* an interop matrix in the contract suite enumerating (client-loader ×
  server-has-LSS) with the wrapper's behavior pinned per cell; plus the
  degrade smoke (§5.5) joining a vanilla server.
- **Upstream NeoForge#1913 stated as a load-bearing assumption (review
  MAJOR):** the NeoForge-client→Fabric-server channel-announcement bug means
  a NeoForge client may not see Fabric-server channels as announced. Our
  mitigation assumption — LSS's own C2S handshake (wrapped per above) arms the
  session, and the server only sends S2C after registration — makes the
  mixed-loader pair work even if announcement is one-way-broken. This is an
  ASSUMPTION until N-3 verifies it live (a NeoForge client against the
  Modrinth Fabric rig is the cheapest real test); recorded as risk §7.6.
- **C2S ≤ 32 KiB on NeoForge**: the want-set batch maxes ~16.5 KiB
  (1024 × 16 B + envelope). *Test:* a build-time pin
  (`WantSetBudgetInvariantTest` sibling) asserts
  `MAX_BATCH_CHUNK_REQUESTS * 16 + ENVELOPE_MARGIN < 32768` so a future budget
  raise cannot silently cross the loader bound.
- **Brand/branding**: `Brand.load` stays each entrypoint's first act;
  `lss-brand.properties` rides xplat resources.

## 2. Stage N — NeoForge on main (26.2), phased

### N-1a: xplat extraction — moves + retargets (no behavior change)

File moves + `sourceSets.main.java.srcDir("../xplat/src/main/java")` (and
resources) on the fabric module; jar layout and mixin configs unchanged.
**NOT "every test unchanged" (review MAJOR):** the source-path contract-test
family reads production sources AS TEXT by path — at least 9 files retarget
(`ChannelAccessorContractTest`, `StoreEnvironmentContractTest`,
`SaveHookContractTest`, `LanHookContractTest`, `MoveTraceHookContractTest`,
`ViaGuardWiringContractTest`, `SelectiveChunkNbtLoaderTest`,
`ClientColumnProcessorTest`, `ExporterContractTest`), ideally via one shared
`SourcePaths` helper that resolves fabric-then-xplat so the NEXT move is a
one-line change; and `FoliaWiringContractTest`'s production-class scan set
grows to xplat. **Gate: the full existing suite green (T1 both platforms, T2,
T3, release_check, one fresh-backfill soak) + a jar-diff sanity check** — the
Fabric jar before/after N-1a differs only in metadata (class bytes identical
modulo compile order). This is the blast-radius stage: land it alone, first,
in a quiet window.

### N-1b: the seams (behavior-neutral refactor)

The §1.1 seam inventory: `LoaderServices` + the send wrapper + the accessor
resolution + the static-holder wiring split. Fabric impl = current behavior
verbatim. Gate: the normal suite (no jar-diff claim — call sites change) +
the xplat zero-loader-imports source pin + the `--release 21` tripwire step.

### N-2: neoforge module, server half

MDG 2 (`net.neoforged.moddev` 2.0.14x), NeoForge 26.2.0.x, Java 25.
`@Mod` entrypoint (Brand first), `RegisterPayloadHandlersEvent` registrar
(all 10 channels, `.optional()`, `executesOn` matching each receiver's current
thread contract), event wiring (ServerStarted/Stopping, ServerTickEvent.Post,
PlayerLoggedOut), commands via `RegisterCommandsEvent`, the dirty hook +
disk-read accessor mixins (same vanilla targets as Fabric; AT file for the
2-line accessWidener). **Packaging decision lands HERE, not at N-4** (review
MAJOR): natives via jarJar (`META-INF/jarjar/` — note: a different nesting
path than Loom's `META-INF/jars/`, which is why §4.2's release_check walker
must be generalized BEFORE the neoforge checks can mean anything) with
**fallback pre-authorized: Paper-style shading** — known-good in-repo,
(AMENDED 2026-08-15: sqlite-jdbc moved to a nested jarJar library — the flat
shade collided with the community Voxy port's module; neoforge-jarjar-sqlite-plan.md)
no-relocate-org.sqlite rule applies. Server parity gates: the neoforge
contract suite (§5.1), the gametest smoke subset (§5.2), the §5.3/§5.5
soak-or-floor.

### N-3: neoforge module, client half

Client events (LoggingIn/Out, ClientTickEvent.Post), client commands
(`RegisterClientCommandsEvent` + the source adapter), `VoxyCompat` under the
NeoForge classloader (no runtime remapping — the direct-class-literal rule
relaxes, but keep the code identical; the graceful-degrade ladder is the
contract), `/lss reset` (Voxy half degrades per its existing ladder when the
fork's holder interfaces are absent — the no-holder branch ABORTS the Voxy
half by design (the `isWorldUsed` freeze guard); verify the abort surfaces a
user-visible chat line, since on NeoForge it's a shipped configuration, not an
edge case), the far-player renderer on the state-based
RenderLevelStageEvent/Extract pair. **Best-effort renderer cut, stated
precisely (review):** if the 26.2 NeoForge render-submit surface fights the
proxy-entity idiom, CUT the far-player **render path only** — it no-ops with
a once-per-session INFO. The tracker, wire channels, and **the capability-bit
arm term stay exactly as on Fabric**: the bit is the PREFS CARRIER — dropping
the subscription would stop the `shareSelf` opt-out from ever reaching the
server, a privacy regression wearing a feature cut's clothes. *Test:* the
existing prefs-carrier pins run in the neoforge contract suite.
**Client gates (executable — review MAJOR):** (1) the throwaway `LSSApi`
consumer test mod proves the end-to-end client half on 26.2 (§0.1); (2) the
no-Voxy degrade smoke (clean logs, no capability bit); (3) the vanilla-server
join smoke (the send-wrapper proof); (4) NeoForge-client→Fabric-server
against the Modrinth rig (the #1913 assumption check). Renderer-level
verification is deferred to a line where a renderer exists (§0.1).

**UPDATE (v0.14.0, 1.21.1 line):** the render-path cut above was TAKEN at v0.11.0
but is now REVERSED on the 1.21.1 line — the NeoForge `FarPlayerRenderer` twin renders
immediate-mode on `RenderLevelStageEvent.AFTER_ENTITIES` + `EntityJoinLevelEvent`
(`RENDER_AVAILABLE = true`; docs/planning/neoforge-1.21.1-far-player-render-plan.md,
5-agent reviewed). 26.1/26.2 NeoForge stay render-cut (they need the 26.x submit/extract
pipeline against the Foxy fork — a separate port, recorded in per-version-surfaces.md).

### N-4: CI + release + VSS + docs + F-gate re-arm

Details §4/§6. CLAUDE.md support-tier section finalized, README rows, the
release-notes draft line **naming issue #160 as answered** (our far players
degrade instead of crashing; the NeoForge variant is ours now). Then the
F-gate re-arm (§0.3): fresh per-line pre-flights, release_check green, dev
deploy to the Modrinth rig, short re-validation window. G starts after.

## 3. Stage G additions — 1.21.1 as a backport target

G currently delta-ports onto `support/mc26.1-v0.11` and `support/mc1.21.11-v0.11`.
Added: **cut `support/mc1.21.1` FRESH from main-at-G** (no `-v0.10` ancestor
exists), then apply the spike's MC-retarget recipe (templates:
`support/mc1.20.1` for the old-API family, `support/mc1.21.11-v0.10` for
modern-on-Java-21): dirty hook → `ChunkSerializer.write` (bytecode-verify
1.21.1 Moonrise/C2ME call it), IOWorker `ProcessorMailbox` submit shape, NBT
serializer old-API translation, far-player renderer → 1.21.1 immediate-mode
idiom (old `RenderLevelStageEvent` semantics on the NeoForge side), golden
regen (keep `xver-live-corpus` un-regenerated — the XVER proof), Java 21
(`ScopedValue` → the 1.21.11 line's AntiXray pass-through). Line scope (v1.2):
**Fabric + NeoForge + Paper (the v1.1 no-Paper cut reversed by user decision
2026-08-14 — §0.3), no Tier 3, best-effort tier** — feature cuts per the
spike's drop list are pre-authorized. The Paper module retargets
`PaperChunkDiskReader`/`PaperChunkGenerationService`'s NMS/Moonrise surfaces
per the `support/mc1.20.1` (pre-Moonrise) and `support/mc1.21.8`
(early-Moonrise) templates. **Line-mechanics consequences (review MAJORs):**

- **`ReleaseWorkflowContractTest` stays in `paper/src/test` on all four
  lines** (v1.2 — the line ships Paper, so v1.1's relocation-to-fabric work
  item is moot; uniformity across lines restored).
- **Tag scheme: `+mc1.21.1` is a PREFIX of `+mc1.21.11`** — every glob or
  `contains()` guard collides. §4.2 carries the fix; the line's release.yml
  flavor and contract-test twin use exact-suffix/boundary forms from day one.
- The renderer-level client gate for NeoForge runs HERE (the j-shelfwood fork
  exists on this line — §0.1).

The other two support lines receive the neoforge module through the normal
delta-port (their NeoForge majors: 26.1.2.x and 21.11.x; expected drift is the
documented rename set, each line pins its own MDG/NeoForge pair). Note the
delta-port now carries the whole-tree xplat move (§0.6's Option-A cost; the
fresh-re-port escape hatch's trigger condition is "conflict resolution exceeds
the documented ~90-file adaptation-set effort").

## 4. CI + release workflow modifications

### 4.1 build.yml (main + support flavors)

- The main build job gains: `./gradlew :neoforge:build` (compiles xplat under
  MDG + runs the neoforge contract tests), the xplat `--release 21` tripwire
  step (§1.1), and the gametest smoke step (`:neoforge:runGameTestServer`)
  with the same retry-once + `::warning::` + evidence-artifact pattern as the
  existing tiers (new artifact names `gametest-*-evidence-neoforge`; the
  job's 45-min timeout re-checked against the added steps). `vssJars` CI
  presence: `vssJarNeoforge` builds in the same job so the pair check has
  inputs (review m: the VSS jars must exist before release_check runs).
- Tier 1/2/3 stay on the fabric module unchanged. Docs-only skip rules
  unchanged. The support-branch build.yml flavors gain the same steps with
  their pinned NeoForge versions.

### 4.2 release.yml (main flavor; support flavors mirror per line)

- Build step: add `:neoforge:build -Pmod_version=…` beside the existing two.
  Version expansion under MDG uses its own property idiom — verify the
  `-Pmod_version` flow reaches `neoforge.mods.toml` (open question flagged;
  resolve at N-2 with a `release_check` pin either way).
- **`release_check.py` (review MAJORs folded):**
  - `_nested_jars()` currently walks ONLY `META-INF/jars/*.jar` (Loom's
    layout). NeoForge jarJar nests under **`META-INF/jarjar/`** — without
    generalizing the walker first, the natives/forbidden-package checks on the
    neoforge jar would pass VACUOUSLY. The walker gains the second prefix (or
    the shading fallback makes it moot — the N-2 packaging decision gates
    this check's design; whichever ships, the selftest carries a
    counter-fixture proving the walker sees nested content).
  - New `check_neoforge_jar` family — dev-package exclusion (benchmark/soak
    classes absent), `neoforge.mods.toml` pins (mod id `lss`, display name,
    version expansion), natives presence (per packaging), the VSS-neoforge
    pair checks, `check_third_party_notices` grown to a 3-way (fabric/paper/
    neoforge).
  - **Wire-identity, corrected (review MAJOR):** v1.0 misdescribed the
    existing `check_wire_identity_fabric` — it pins the **LSS↔VSS brand
    pair** (nested common-jar SHA equality), NOT cross-platform bytes. Two
    separate checks land: `check_wire_identity_neoforge` = the same
    brand-pair SHA rule for the neoforge pair; and a NEW cross-loader check =
    a **class-digest comparison** of the xplat+common class SET between the
    Fabric and NeoForge jars (same class names present; byte-equality only
    where compilation is deterministic — else presence+size, with the honest
    limitation noted in the check's docstring).
  - `discover` hardcodes jar families and **hard-fails when one is missing**
    — the neoforge + VSS-neoforge families, the build steps producing them,
    and the check land in the SAME commit or CI reds (the existing R4/S-8
    same-commit rule; a `_write_tree_neoforge` selftest fixture, ~150-250
    lines, lands with them). `RELEASE_GLOBS`, the stale-jar ambiguity guard,
    and the glob-hygiene list all gain the neoforge entries.
- GitHub release `files:` gains `neoforge/build/libs/lod-server-support-neoforge-*.jar`.
- **New Modrinth step** (mirroring the existing two):
  ```yaml
  - name: Upload NeoForge to Modrinth
    uses: Kir-Antipov/mc-publish@v3.3
    with:
      modrinth-id: lKiXKLvv
      modrinth-token: ${{ secrets.MODRINTH_TOKEN }}
      files: neoforge/build/libs/lod-server-support-neoforge-*.jar
      name: ${{ github.ref_name }} - NeoForge (MC 26.2, server-side; client needs a community Voxy build)
      version: ${{ github.ref_name }}+neoforge+mc26.2
      version-type: release
      loaders: neoforge
      game-versions: |
        26.2
      changelog: ${{ steps.release_notes.outputs.notes }}
  ```
  The `name:` carries the §0.1 caveat because search results and version
  lists don't render release notes. mc-publish's dependency metadata for
  NeoForge reads the TOML `[[dependencies]]` block — the mods.toml must
  declare neoforge/minecraft ranges correctly or the listing's environment
  tags mislead (review note). Support-line flavors follow the existing
  support-line convention (a MOD_VERSION derive step, NOT `github.ref_name`,
  which embeds the `+mc…` suffix there); the 1.21.1 flavor publishes all
  three — fabric + paper + neoforge (v1.2).
- **Tag scheme collision (review MAJOR — empirically verified):**
  `+mc1.21.1` is a prefix of `+mc1.21.11`, so `v*+mc1.21.1*`-style globs
  match BOTH lines — PREV_TAG resolution, the wrong-line tag guard, and
  changelog scoping all cross-contaminate. Fixes, pinned on BOTH affected
  lines: the 1.21.1 flavor's tag trigger/guards use the **exact-suffix form**
  (`v*+mc1.21.1` with NO trailing wildcard, plus an end-anchored regex
  `\+mc1\.21\.1$` in shell guards); the 1.21.11 flavor's guards are audited
  for the converse (they must not match the new line — its existing guards
  use the longer string so prefix-matching is safe in that direction, but the
  audit is explicit); and both lines' `ReleaseWorkflowContractTest` twins pin
  the anchored forms — the current `contains()` idiom structurally cannot
  express "1.21.11 but not 1.21.1", so those pins assert the REGEX/anchored
  literals appear verbatim. Same landmine inside the tests themselves:
  `FORBIDDEN_LINE_TOKENS`-style substring checks must use full version-ids or
  word boundaries.
- `ReleaseWorkflowContractTest` (+ per-line twins): the census constants are
  EXACT counts — `assertEquals(2, count("modrinth-id: lKiXKLvv"))` reds the
  moment the third upload step exists, `MODRINTH_VERSION_IDS` gains the
  neoforge id, and the VSS-absence pin must not be tripped by the new step
  names. All updated same-commit with the workflow edit. Partial-publish
  exposure grows to four steps per line — the §4.3 recovery rule now names
  four channels.

### 4.3 The irreversibility discipline (unchanged, restated)

Tags publish irreversibly; the neoforge jar joins the same pre-flight
(`CI=true ./gradlew :fabric:build … :neoforge:build … && release_check.py
--version`) BEFORE tagging (CLAUDE.md's Releasing section gains
`:neoforge:build`; the 1.21.1 line's variant drops `:paper:*`); never re-run
a partially published release — recovery is hand-uploading the
GitHub-attached jars to whichever of the (now four) channels missed.

## 5. Testing strategy (best-effort tier, made concrete)

### 5.1 Tier 1

Stays on the fabric module (fabric-loader-junit compiles xplat + common —
~1250 tests unchanged, minus the N-1a path retargets). The neoforge module
gets CONTRACT tests only (JUnit, no MC boot): mods.toml pins,
registrar/payload census (source-regex), AT-file presence + content,
`.optional()` census, the C2S-bound pin, the send-wrapper interop matrix
(§1.2), the ChannelPressureProbe resolution pin, the prefs-carrier arm-term
pin, LoaderServices completeness (reflective: every interface method has a
neoforge impl — the same pattern as the governor's adoptFrom completeness pin).

### 5.2 Tier 2 smoke subset (NeoForge gametests)

~8-12 tests, not 71: service activation + config load, handshake→register→
serve round-trip (crafted frames), disk-read byte parity vs the live
serializer (THE cross-loader correctness pin), generation serve, dirty
broadcast, idempotent shutdown. Registered via `RegisterGameTestsEvent`
(the 26.x data-driven idiom); run as `runGameTestServer` in CI, wired into
the build like the existing tiers (explicit step — NOT assumed to hang off
`check`). Full-suite parity is explicitly NOT a goal (best-effort tier).

### 5.3 Abbreviated soak — shape corrected, skip expected

**No `soak.sh smoke` subcommand exists and none is added** (review: the
dispatcher takes `<scenario>|all`; unknown args error). The correct shape is
the Paper/Folia precedent: a `NEOFORGE_SCENARIOS=(fresh-backfill
dirty-broadcast)` array selected by `SOAK_PLATFORM=neoforge`, so
`SOAK_PLATFORM=neoforge ./scripts/soak.sh all` runs exactly the two:
`fresh-backfill` (generation + serve + all conservation laws once) and
`dirty-broadcast` (the NeoForge dirty hook end-to-end). Unchanged Fabric soak
client + checker — the run itself is the Fabric-client↔NeoForge-server interop
proof. **Cost honesty (§0.4):** this requires a third scenario driver +
exporter twin satisfying the schema-parity contracts (~600-900 lines total —
Folia's zero-cost reuse does not apply). **The skip clause is expected to
fire**: if the plumbing exceeds ~2 days, SKIP the soak platform — recorded as
a §6-protocol decision entry (not silently), with §5.5 as the floor.

### 5.4 What NeoForge explicitly does NOT get

Tier 3 (no framework), the full soak suite, benchmark harness arms,
Folia-class platform validation, per-release live-rig burn-in (the Modrinth
rig stays Fabric), the Sodium **0.8+ config-API** page (Phase 4 of
sodium-options-page-generations-plan.md — the legacy 0.6/0.7 Sodium options TABS DO
render on NeoForge since 2026-08-23, minus the renderer-only far-player options;
config file + `/lsslod set` remain the full surface), the LAN integrated-server hook (a LAN-published NeoForge integrated
server does not start the service). Recorded in CLAUDE.md so nobody chases
the gaps as regressions.

### 5.5 The manual smoke checklist (the tier floor — normative)

> **The client-run lever (rows 3-6; N-3 review MINOR):**
> `./gradlew :neoforge:runClient -Plss.smoke.join=<host:port> -Plss.smoke.consumer=true`
> — quick-plays into the named server with the lsstest companion's logging
> `LSSApi` consumer armed (omit `-Plss.smoke.consumer` for the no-consumer
> degrade arm; omit `-Plss.smoke.join` for a plain main-menu client). Watch
> for `[lss-smoke] columns received=` lines. These rows are USER-DRIVEN: the
> WSLg dev environment freezes the windowed client in render init (N-3
> decisions-log entry), so they need a real GUI machine.

Run per NeoForge-affecting release when the soak platform is skipped; results
recorded in the release PR description. All on 26.2 unless noted:

1. **Server serve**: NeoForge server + Fabric client w/ Voxy — join, LODs
   stream, `/lsslod diag` shows serves from all three sources (probe/disk/
   generation), no WARN/ERROR in the server log.
2. **Dirty**: `setblock` near a served column → the client re-receives it.
3. **Client degrade**: NeoForge client, NO Voxy — clean logs, no capability
   bit sent, zero LSS traffic after handshake reply.
4. **Client half live**: NeoForge client + the throwaway `LSSApi` consumer
   test mod — columns decode and dispatch (§0.1's executable gate); where a
   community Voxy build exists for the line (1.21.1, 26.1.2), swap it in and
   eyeball actual rendering.
5. **Vanilla-server join**: NeoForge client joins a vanilla server — no
   throw, no log spam (the send-wrapper proof).
6. **Mixed-loader**: NeoForge client → Fabric server (the rig) — session
   arms, columns flow (the #1913 assumption check).
7. **Store + restart**: store-armed server restart — `store status` state=ok,
   warm serves on rejoin.

### 5.6 Test-tier summary

| Surface | Fabric (main) | NeoForge (main) |
|---|---|---|
| Tier 1 JUnit | ~1250 (compiles xplat) | contract suite only |
| Tier 2 gametests | 71 | ~8-12 smoke |
| Tier 3 client | yes | none (no framework) |
| Soak | 20 scenarios | 2-scenario set, skip expected → §5.5 floor |
| Live rig | yes | no |

## 6. The best-effort tier (normative — CLAUDE.md carries the durable copy)

### 6.1 Tier table (corrected per review — v1.0's FULL-tier claim was false)

| Tier | Lines | Commitment |
|---|---|---|
| **Full** | Fabric + Paper on main (26.2) | complete gauntlets (T1/T2/T3), 20-scenario soaks ×3 platforms, live-rig burn-in, first-priority triage |
| **Correct, not perfect** | the 26.1 + 1.21.11 support lines (Fabric + Paper) | full builds + T1/T2, representative smoke soaks, NO live rig (the rig is 26.2 Fabric only), no exhaustive gauntlets — the recorded support-line effort budget |
| **Best-effort** | NeoForge (all lines) + the whole MC 1.21.1 line (Fabric + Paper + NeoForge) | tracks the mainline feature set; cuts allowed via §6.2; coverage per §5; lowest triage priority |

### 6.2 Cut protocol

A feature cut on a best-effort line is legal when: (1) it is pre-authorized
by this plan (§0.2's list), OR (2) it is recorded as a **dated decisions-log
entry in the owning program/progress doc** (the mega plan §6.1 pairing rule)
naming what is cut, why, and the revival condition — release notes alone are
not the record. Out of bounds regardless of tier (**wire safety — explicit
sign-off required**): dropping any of the 10 `lss:*` channel registrations
(WireParityTest census; on NeoForge an unregistered channel makes sends
throw); changing capability-bit composition (the far-player bit is the PREFS
CARRIER — dropping the subscription is a privacy regression: a mode-`on`
server would share targets whose `shareSelf` opt-out never arrived); dropping
a compat rung (v16/v18/v19); altering the v20 identity dictionary or codec
negotiation; raising `WANT_SET_BUDGET`/`MAX_BATCH_CHUNK_REQUESTS` past the
NeoForge C2S bound (already pinned). Wire-safe and unilateral under the tier:
test-tier scope, server-internal read/parse flags (byte-identical by their
own pinned invariants), client-local UX (config screen, LAN hook, tracer
bootstrap, reset-ladder degrade), the far-player **render path** no-op
(§N-3's precise form), per-loader packaging.

### 6.3 Floor, promotion, release notes

- **Floor**: when the soak platform is skipped, §5.5 is the per-release floor.
- **Promotion criterion** (the tier's exit, mirroring Folia's experimental
  exit discipline — the two labels are DIFFERENT axes: *experimental* =
  platform-correctness confidence, *best-effort* = support/coverage
  commitment, and neither implies the other): NeoForge promotes to
  correct-not-perfect when (a) a real Voxy-API renderer exists on the line,
  (b) the soak platform runs the 2-scenario set green in CI-adjacent use, and
  (c) a release cycle passes with no variant-specific regression. Until then
  the label stays.
- **Release-notes rule** (mirrors the Folia-experimental rule): NeoForge- or
  1.21.1-affecting items must name the best-effort tier, and any cut taken
  under §6.2 must appear in that line's notes.
- **The recurring tax, stated**: this strategy makes a full release up to
  4 lines × 3 loaders ≈ 12 artifacts; every future backport carries the
  fourth line and third loader permanently. That is the accepted price of
  main-first NeoForge (spike addendum's finding, now recorded where it is
  read).

## 7. Risks

1. **The xplat extraction (N-1) — true exposure restated (review):** zero
   open PRs cross it today; the real exposure is (a) the two active `-v0.10`
   support bases, whose G delta-port must carry a whole-tree source move on
   top of the documented ~90-file adaptation set — the shape most likely to
   trigger the fresh-re-port escape hatch (cost: re-deriving that set), and
   (b) the fresh 1.21.1 cut, which inherits the move for free (fresh cut).
   Mitigation: N-1a lands alone in a quiet window. (The §0.6 decision accepted
   (a) with eyes open — the fresh-re-port escape hatch's trigger condition in
   §3 is the fallback if the delta-port conflicts mass out.)
2. **Natives under NeoForge's module layer** (sqlite/zstd via jarJar,
   `META-INF/jarjar/`) — least charted; shading fallback pre-authorized;
   decision at N-2 gates the release_check design (§4.2).
3. **Renderer reality on NeoForge clients**: community Voxy forks only
   (1.21.1 fork; Foxy shim at 26.1.2; **nothing on 26.2/1.21.11 today**). The
   client half ships anyway per the user directive — compiled-and-inert on
   26.2 (§0.1), VoxyCompat's degrade the contract, the Modrinth version name
   carrying the caveat, renderer verification deferred to lines where a
   renderer exists.
4. **NeoForge API drift on support lines** (21.1 old render/gametest idioms vs
   26.x) — bounded by the spike's drift map; the glue is thin by design.
5. **Schedule**: RESOLVED-ACCEPTED (§0.6) — every v0.11.0 tag waits on N, the
   F-gate re-arm, and the full four-line × three-loader G; the simultaneous
   release means the slowest line gates all four tags (~8-12 wk added, §8).
6. **Send-throw semantics + upstream #1913** — the two mixed-loader hazards
   (§1.2): the send wrapper covers the throw class; the handshake-arms-the-
   session assumption covers #1913 and is verified live at N-3 against the
   Fabric rig. A false assumption here downgrades the NeoForge client to
   LSS-inert on Fabric servers (degrade, not crash) — visible in the §5.5
   mixed-loader check.
7. **VSS/XANTHA coordination** — the `84zcagOb` listing gaining a loader
   needs XANTHA's buy-in; the VSS-neoforge jar is a release blocker once the
   release_check family lands (§0.5, §4.2).

## 8. Effort (re-costed per review — v1.0 costed the client half ~zero)

| Phase | Estimate |
|---|---|
| N-1a xplat moves + test retargets | 3-5 d |
| N-1b seams (LoaderServices, send wrapper, accessors, holders) | 3-4 d |
| N-2 server half (incl. packaging decision + natives) | 4-6 d |
| N-3 client half (incl. renderer attempt + degrade gates + rig check) | 4-6 d |
| N-4 CI/release/VSS/docs + F-gate re-arm | 3-4 d |
| **Stage N total (main)** | **~17-25 d** |
| G increment: 26.1 + 1.21.11 neoforge carry | ~5-9 d |
| G increment: the 1.21.1 line, Fabric + NeoForge halves (tag-scheme twins) | ~13-19 d |
| G increment: the 1.21.1 Paper module (v1.2 — templates: mc1.20.1 + mc1.21.8) | ~4-7 d |
| **Program total added** | **~39-60 d (~8-12 wk)** |

Exceeds the spike's 27-42 d matrix estimate honestly: the spike's main-line
number was server-first scoped (client dormant, renderer deferred); this plan
restores the full client half per directive, adds the review round's
hardening (seams, send wrapper, CI collision fixes, soak driver OR floor),
and v1.2 adds the 1.21.1 Paper module. The simultaneous-release decision
means these are all on the v0.11.0 critical path.

## 9. Review record (3-Opus round, 2026-08-14)

- **Architecture lens** — verdict: sound shape, 6 MAJORs: accessor/xplat
  coupling unaddressed; LoaderServices seam undercount (static holders,
  service lifecycle, pressure probes, command source, save hook,
  FarPlayerSnapshots); N-1 not pure-moves (9 source-path contract tests +
  FoliaWiringContractTest scan set); send-guard inversion (handshake =
  unprompted first send, zero canSend sites) + interop matrix; soak
  driver/exporter twin unscoped; missing surfaces (transport-yield silent
  loss, LAN consequence). All folded: §1.1 seam inventory, §1.2, §2 N-1a/b
  split, §0.4/§5.3, §5.4.
- **CI/release lens** — verdict: would not have published correctly on the
  first tag, 5 MAJORs: tag-scheme prefix collision (empirically verified);
  ReleaseWorkflowContractTest homeless on the Paper-less line; wire-identity
  clause misidentified the existing check; `_nested_jars` blind to
  `META-INF/jarjar/`; stage N discharges F's gates without re-arming. All
  folded: §4.2, §3, §0.3/N-4. Minors folded across §4.
- **Scoping/tier-honesty lens** — verdict: not ready as written, 7 MAJORs:
  client half had no executable 26.2 gate; sequencing over-read as resolved;
  risk-1 exposure misstated; tier not operational (no cut protocol/floor/
  promotion, false FULL-tier claim about existing lines); scope grew while
  the estimate didn't; missing risks (#1913, recurring tax); the no-Paper cut
  cited phantom spike provenance. All folded: §0.1, §0.6, §7.1, §6, §8,
  §1.2/§6.3, §0.3. The wire-safe/not-wire-safe cut table is theirs (§6.2).
