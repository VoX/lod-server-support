# AntiXray Compatibility: Crash Fix + LSS-Native Ore Masking

Status: Part A (§2) IMPLEMENTED 2026-07-23 — `AntiXrayCompat` shim + probe containment +
Tier 1 pins (`AntiXrayCompatTest`, `ProbeContainmentTest`); upstream issue drafted in
`antixray-upstream-issue.md`, not yet filed. Part B CORE (§3, phase 2) IMPLEMENTED
2026-07-23 — `XrayMaskPolicy` (common), `XrayMaskFilter`/`PaperXrayMaskFilter` twins,
config keys in `ServerConfigBase`, Tier 1 pins incl. the `xray-masked` golden fixture
(byte-identical cross-module, enforced by the corpus parity test). NOTE: the shipped
default hidden list is the VERBATIM current Paper default (23 entries incl.
raw_copper_block/raw_iron_block, NO nether ores — this section's earlier sketch guessed
nether gold/quartz/debris in it; verbatim wins, pinned by
`defaultListCoversTheExpectedPaperSet`). Phase 3 (§3 Detection/wiring/diag) IMPLEMENTED
2026-07-23 — engine probe in `AntiXrayCompat` (controller reflection, latch-on-failure,
pinned via real-name stub classes on the test classpath), `XrayMaskManager`/
`PaperXrayMaskManager` (per-dimension cache, once-per-manager fallback resolution, static
holder published by the service lifecycle), hooks inside both platforms' serializeColumn +
Nbt serializers (disk path captures the entry at submit time), the always-present
`Xray: active=<label>, masked_sections=<n>` diag line, and the masked live-vs-disk parity
gametest (`xrayMaskedDiskReadBytesMatchMaskedLiveBytes` — note its same-tick
static-manager discipline: concurrent gametest service ctors stomp the holder). Phase 4
(validation) RUN 2026-07-23, all gates green:
- Tier 3 client gametest green (masking-off end-to-end regression).
- Soak `fresh-backfill` with `"xrayObfuscation": "on"` PASS (33/33 windows, 0 violations;
  activation line `source=config, maxY=64` in server.log; laws content-agnostic as
  designed). Two prior attempts red on the documented environmental A7 signature
  (read-timeout storm — decisive check passed: `Failed to read chunk` count 0, no A5;
  cause: box IO load — the long-running Paper test server; both failed runs' logs show
  only the SoakPlayer login, so no foreign join reached the soak server itself).
  `check_soak.py --validate` now knows the three xray config keys (string/string-list
  validation added; selftest green incl. the new type branches).
- Benchmark A/B `fresh 60`: masked −4.8% sections/s (28.7 → 27.3), −5.1% bytes/s — inside
  the "low single-digit %" gate (fresh-worldgen run variance + a concurrent server on the
  box; sources mix comparable).
- LIVE AntiXray gate (production Fabric server :25565 with antixray-fabric-1.4.16+26.1 +
  c2me + this build; real dev client via runBenchmarkClient): NO crash across ~2 min of
  full-rate serving — 6759 columns / 207 MB sent, 0 NoSuchElementException, shim line at
  boot, engine adoption live (`Xray: active=antixray-mod, masked_sections=60805`; adopted
  maxY=256 — the MOD's real default, not Paper's 64), C2ME background-read fallback +
  adaptive throttle co-engaged (`read_throttle=ENGAGED`). Client received 7628 columns.
- Remaining live check (user): Paper native anti-xray eyeball via `run-paper` — the
  long-running Paper test server predates masking; `./test-server.sh update` + restart
  before checking LODs. Folia rides the usual pending-26.2 status.

Final 3-lens whole-changeset review (2026-07-23, post-commit; correctness / tests /
docs-release): no blockers. Fixed from it: null/blank `xrayHiddenBlocks` entries no longer
NPE inside serve choke points (the one real bug — generation's catch would have turned it
session-permanent NOT_GENERATED; guard + pins in both twins), the static holder retract is
owner-guarded (`deactivate(owner)` — a stale or test-wired shutdown cannot null a
successor's masking; pinned both platforms), a null engine controller now reads UNREADABLE
(fail-safe masking, unlatched) instead of DISABLED, the Paper masked disk path + the
Fabric diag attach got their missing failing-test pins, the masked gametest's activate
windows are finally-restored, and the stale pre-fix "expect a crash" framing in
test-server.sh + the README config table + the §3 lazy-evaluation/mode-2-3-degradation
wording were corrected. Known accepted residue: the Paper production ctor's activate call
is live-gated only (Tier-1-unreachable), and the live-path masked_sections counter can
over-count on stale-palette maybeHas false positives (diag cosmetics). Investigation 2026-07-23 (source-read of
DrexHD/AntiXray branch `26.1`, matching the `fabric-1.4.16+26.1` release that lists MC
26.2 support).

## 1. Problem

Two independent defects when an anti-xray system is present, one per platform family:

**P1 — Fabric + DrexHD AntiXray (Modrinth `sml2FMaA`, mod id `antixray`): server crash.**
AntiXray is a Paper anti-xray port that threads obfuscation context through Java
`ScopedValue`s (`me.drex.antixray.common.util.Arguments`), bound ONLY inside vanilla
chunk-packet construction (`ClientboundLevelChunkWithLightPacket` → `extractChunkData`).
Its `PalettedContainer$DataMixin` (wraps `BitStorage.getRaw()` inside `Data.write`) and
`PalettedContainerMixin.setPresetValues` (inject in `PalettedContainer.write`) read
`Arguments.PACKET_INFO` / `CHUNK_SECTION_INDEX` with raw `.get()` — no `isBound()` guard —
and an unbound `ScopedValue.get()` throws `NoSuchElementException`. Every LSS
`section.write(buf)` runs outside that scope, so:

- **Probe path:** `SectionSerializer.serializeColumn` (RequestProcessingService.probeLoadedChunks,
  main thread) throws; `tick()` has no containment and the `END_SERVER_TICK` handler
  (LSSServerNetworking:168) propagates → **server crash** on the first loaded-chunk serve
  after an LSS client joins.
- **Disk path:** `NbtSectionSerializer` throws per read; the reader's broad catch triages
  it (error/not-found) → escalates to generation.
- **Generation path:** extraction's `catch (Throwable)` (ChunkGenerationService:181)
  resolves it as a PERMANENT failure → `NOT_GENERATED`, session-blanking every position.
- `DirtyContentFilter` catches and fails open (saves survive).

AntiXray's mixins apply unconditionally (its own config cannot disable them); only its
Immersive Portals mixins are conditional — precedent that mods shipping terrain outside
vanilla packets need explicit accommodation.

**P2 — both platforms: silent obfuscation bypass.** Paper's built-in anti-xray and the
Fabric mod both obfuscate by rewriting vanilla chunk-packet buffers. LSS's `lss:*` payloads
never pass through that machinery, so LOD columns carry REAL ore states — cached on the
client's disk (`ColumnCacheStore` + the consumer's own store). A distant-ore x-ray vector
on exactly the servers that opted into anti-xray.

Why we cannot reuse their engine (settled — see the 2026-07-23 conversation): entry points
are welded to the vanilla packet object (`ChunkPacketInfo` records buffer offsets during
packet write; `modifyBlocks` rewrites in place and marks the packet ready through
batch/connection machinery), the bit-rewrite requires replacement states pre-injected into
palettes at chunk load (absent from our NBT-parsed sections), the obscured-block analysis
needs neighbor sections (never loaded on our disk path), obfuscation is async with
packet-holding, and `usePermission` makes output per-player (collides with cross-player
dedup + the position-keyed idempotent model). Blanket LSS-side masking is the design.

## 2. Part A — crash fix (Fabric only; Paper native anti-xray does not crash)

### A1. `AntiXrayCompat` ScopedValue shim

New `fabric/.../compat/AntiXrayCompat`, mirroring the `VoxyCompat` pattern (zero
compile-time dependency, `MethodHandle`/reflection, MC types via class literals):

- Activation: `FabricLoader.isModLoaded("antixray")` AND successful resolution of the
  `ScopedValue` instances from `me.drex.antixray.common.util.Arguments` static fields
  (`PACKET_INFO`, `CHUNK_SECTION_INDEX`, `PALETTE_ENTRIES`, `PRESET_VALUES`). `ScopedValue`
  itself is public JDK API (Java 25) — no reflection needed to *bind*, only to obtain their
  instances.
- API: `AntiXrayCompat.callSerializing(Supplier<T>)` — inactive: call through unchanged;
  active: run inside `ScopedValue.where(PACKET_INFO, null).where(CHUNK_SECTION_INDEX, null)
  .where(PALETTE_ENTRIES, null).where(PRESET_VALUES, null)`. Null bindings are exactly what
  AntiXray's own `LevelChunkSectionMixin` uses for the biome write; all four are bound so a
  future raw `.get()` on any of them degrades to the benign null path instead of throwing.
  (PALETTE_ENTRIES/PRESET_VALUES today only matter on the codec `unpack` ctor, which binds
  its own — binding them is future-proofing, not correctness for 1.4.16.)
- Wrap the TWO Fabric choke points (this covers probe + generation + DirtyContentFilter,
  which all funnel through `serializeColumn`, and the disk path):
  - `SectionSerializer.serializeColumn`
  - `NbtSectionSerializer.readAndSerializeSections`
  Bind once per column, not per section (one carrier per serialize call; negligible cost —
  and zero cost when inactive: a static-final boolean fast path, JIT-erasable).
- Failure latch: any `Throwable` during resolution (AntiXray refactors `Arguments`) →
  inactive + warn once, mirroring `backgroundReaderOrFallback`'s style. A2 is the floor
  under that case.

### A2. Probe-path containment (independent hardening)

`probeLoadedChunks` gets a per-column try/catch: a throwing serialize skips that column
(throttled WARN, same aggregation style as the disk-timeout warn). The position then falls
through the existing ladder (disk read → triage → generation → permanent failure →
`NOT_GENERATED`), so an unshimmed foreign-mixin conflict degrades to blank LODs + log
noise instead of killing the server. This is worth having regardless of AntiXray — the
probe is today the ONLY serve path without containment, and it runs inside the server tick.
No new counters (schema churn not warranted); the WARN is the signal.

### A3. Upstream

File an issue with DrexHD: raw `.get()` breaks any mod serializing sections off-packet
(LSS, Distant-Horizons-style servers, map mods); ask for `isBound()`-safe reads (their
`Arguments` javadoc already anticipates values being "present 'unexpectedly'"). Offer the
one-line-per-site PR. The shim ships regardless — released AntiXray versions stay broken.

### A tests

- Unit (Tier 1): shim decision ladder via an injected resolver seam — mod-absent inactive,
  resolver-throws → latch + warn-once, active path actually binds (assert via a probe
  Supplier that reads the bound values), a throwing body stays exception-transparent and
  unwinds. Containment: per-column throwing serializer seam → column skipped ("no probe"),
  no propagation, VirtualMachineErrors excluded. AS SHIPPED the seam is a parameter of the
  static `serializeProbeContained` helper, not injected into `probeLoadedChunks` itself
  (the loop needs a live ServerLevel, out of Tier 1's reach) — so the two-line loop glue
  ("others still served" = null is simply never put into the probes map, the loop
  continues) is covered by inspection, not a pin. Accepted at Phase-1 review.
- No AntiXray-in-JUnit: applying their mixins in-tier is version-coupled machinery for
  little value. The live gate is `./test-server.sh run-fabric-antixray` — with the shim the
  server must survive an LSS client join and serve normally (masked, per Part B).

## 3. Part B — LSS-native x-ray masking

### Semantics (deliberately naive)

Blanket replacement of configured hidden block states in every served column — no
exposure analysis, no reveal-on-approach. Rationale: LODs render beyond the vanilla view
distance, where per-block fidelity of ore faces is visually negligible; near terrain is
vanilla-rendered and properly handled by the real anti-xray system, and our only refresh
channel (dirty broadcast) cannot express reveal anyway. Applied identically on all three
serve paths (probe / disk / generation), both platforms.

### Config (ServerConfigBase — shared verbatim Fabric/Paper)

```json
"xrayObfuscation": "auto",       // "auto" | "on" | "off"  (validate(): unknown → "auto")
"xrayHiddenBlocks": [ ... ],     // block ids; default = Paper's engine-mode-1 ore set
"xrayMaxBlockHeight": 64         // mask only below this world Y (Paper's default); clamp ±2048
```

The LSS keys are the FALLBACK tier: in auto mode, values adopted from the detected engine
(below) take precedence — the governing principle is **mask exactly what the packet engine
masks**. Above the engine's max-block-height the data is already sent unobfuscated in
vanilla chunk packets, so masking it in LODs would over-hide (surface ores visible in near
terrain, stone at LOD range) while protecting nothing; likewise our own block list must not
diverge from the admin's (a block they hid that we don't = a leak; the reverse = mismatch).

- `auto` (default): active iff an anti-xray system is detected (below).
- `on`: always mask (admin runs an anti-xray we can't detect, or just wants LOD ore privacy).
- `off`: never mask — the explicit kill switch (crash shim A1 stays active regardless;
  `off` re-opens the leak knowingly, it must not re-open the crash).
- Default hidden list: Paper's default engine-mode-1 `hidden-blocks` (all overworld ores +
  deepslate variants, nether gold/quartz, ancient debris, plus chest/ender_chest — copy the
  list verbatim at impl time from the current Paper default config). All states of each
  block are hidden (AntiXray does the same). Unknown ids: warn + skip at resolve time.

### Detection (auto mode)

- **Fabric:** adopt from the AntiXray mod's per-world CONTROLLER, not its config classes —
  a smaller and semantically stabler reflective surface, and tags arrive pre-resolved.
  Handles: static `Util.getBlockController(level)` → per-world engine object;
  `instanceof me.drex.antixray...DisabledChunkPacketBlockController` (by class name) = that
  world is anti-xray-OFF (per-world enable detection, matching Paper semantics); on the
  active controller read two fields of the `ChunkPacketBlockControllerAntiXray` base:
  `obfuscateGlobal` (an Object2BooleanOpenHashMap whose keys are every hidden BlockState —
  the engine's actual input, post tag/state expansion) and `maxBlockHeight`. Fallback
  ladder on ANY resolution throwable (their internals refactor): warn once, treat
  mod-loaded as enabled for all dimensions, use the LSS `xrayHiddenBlocks` +
  `xrayMaxBlockHeight` keys.
- **Paper/Folia:** per-world `level.paperConfig().anticheat.antiXray` (compile-time typed
  access via paperweight — no reflection): `enabled` gates that world, and its
  `hiddenBlocks` + `maxBlockHeight` are adopted (resolve whatever representation the config
  holds to states via the registry). Fallback to the LSS keys only if the adopted list
  resolves empty (warn once).
- **Engine-mode is deliberately NOT adopted** — LSS always behaves engine-mode-1-like
  (hide the hidden list). Modes 2/3 disguise terrain AS fake ores and depend on
  block-update reveals as players approach; LODs have no reveal channel, so adopted mode-2
  semantics would render permanently ore-riddled distant terrain for every legitimate
  player. Adopting a mode-2/3 controller's `obfuscateGlobal` under our blanket rule stays
  SAFE but is not a no-op: hidden states are excluded from replacement candidacy, so a
  stone-dominant section whose stone is itself hidden repaints wholesale to the largest
  non-hidden state (or the stone/deepslate fallback when everything is hidden — which for
  mode-2/3 sets happens to be the ideal outcome). Deterministic and never-air either way;
  visually a full-section rewrite, not the "extra entries are inert" of mode-1 sets.
- Evaluated once per world and cached for the service lifetime — AS SHIPPED that happens
  LAZILY on each dimension's first serve (the manager's computeIfAbsent), not eagerly at
  service start; a config change on either engine requires the usual restart (same as
  Paper's own anti-xray).

### Placement & algorithm

- Pure policy (tri-state + detection inputs → active + list source) in `common/`
  (`XrayMaskPolicy`, HandshakeGate-style pure ladder, unit-tested once).
- Masking twins beside the serializer twins: `fabric/.../XrayMaskFilter`,
  `paper/.../PaperXrayMaskFilter` (MC types forbid `common/`). Twin-identical logic, pinned
  by parity tests like the serializers.
- Hook INSIDE the two per-platform choke points (`serializeColumn` +
  `NbtSectionSerializer`/`PaperNbtSectionSerializer`), before `section.write`. That makes
  every consumer — probe, disk, generation, DirtyContentFilter's hash — see identical
  masked bytes by construction.
- Per section:
  0. Height gate: a section whose bottom Y is at/above the effective max-block-height
     skips masking entirely — before even the palette scan (the surface/mountain fast
     path; with the default 64 that is every section from sectionY 4 up).
  1. `states.maybeHas(hiddenPredicate)` — palette-only scan; ore-free sections (air,
     surface) pay nothing and write through untouched.
  2. On hit: `count()` the section (O(4096) int ops) → pick the replacement = the
     section's dominant non-hidden, non-air state; fallback ladder if none: deepslate
     (sectionY < 0) / stone, `*nether*` dims → netherrack, `*end*` → end_stone, else stone.
     Dominant-state adapts to modded/nether/deepslate terrain with zero config and is
     deterministic (DirtyContentFilter-safe).
  3. Replace: live path — `states.copy()` then per-cell get/set of hidden cells (NEVER
     mutate the live chunk's container); NBT path — parsed sections are throwaway, mutate
     in place. A section STRADDLING the max-block-height (a non-multiple-of-16 config
     value) adds a per-cell `worldY < maxBlockHeight` guard in this pass — cells above the
     cutoff stay real, matching what vanilla packets already reveal.
- Hidden-set representation: `boolean[]` indexed by `Block.BLOCK_STATE_REGISTRY` id
  (AntiXray's own trick) — O(1), no equality subtleties. Resolved once per config load.
- Invariant: the replacement is never air — `nonEmptyBlockCount` stays honest and the
  End-void all-air sentinel path is never entered by masking. Pin with a unit test.

### Interactions (checked)

- **Wire/protocol:** none — same payloads, different states. No bump; v16 shim sessions
  inherit masking automatically (egress only strips the source byte). VSS: inert.
- **DirtyContentFilter:** hashes masked bytes on both sides of every compare (it uses
  `serializeColumn`) — re-save suppression unaffected. Changing mask config mid-world
  changes bytes → at most a one-time benign dirty-broadcast churn as columns re-save.
- **Byte parity:** live-vs-NBT parity (SerializerParityGameTests + golden corpus) must
  hold with masking ON — both paths mask before write, same filter code. Extend the golden
  corpus with masked fixtures. (Pre-existing, unrelated: with the AntiXray MOD installed,
  live palettes carry its preset entries, so live-vs-disk bytes already diverge on such
  servers; test envs never load the mod.)
- **Folia:** masking is a pure function over section data on whatever thread serializes
  (region thread for probes/generation completions, reader pool for disk) — no shared
  state beyond the immutable resolved hidden set. Release notes: mention experimental
  Folia status as usual.
- **Perf:** cost only on ore-bearing sections (two O(4096) passes + one container copy on
  the live path). Gate: `./scripts/benchmark.sh fresh 60` A/B with masking forced on —
  expect low single-digit % on `sections_per_second`; anything more, revisit (a palette-
  level rewrite of our own encoder is the known faster follow-up, deliberately out of v1).

### Accepted limitations (document in README + release notes)

- Historical data: columns served BEFORE masking was enabled live in client caches and
  cannot be recalled (true of any anti-xray retrofit; hacked clients archive regardless).
  Protection applies to data served after enablement.
- Lit redstone ore's light data still glows in masked LODs (light nibbles untouched) —
  cosmetic, no ore-location precision beyond what light already leaks at LOD scale.
- Cave shapes/air are not hidden (neither do the anti-xray mods).
- Exposed ore faces on distant cliffs render as the replacement until the chunk enters
  vanilla view distance — the visual price of skipping exposure analysis.
- Ores at/above the effective max-block-height stay real in LODs BY DESIGN — vanilla
  packets already send them unobfuscated, so this is equivalence, not a leak.

### Diagnostics

One `/lsslod diag` line: `Xray: active=<off|config|antixray-mod|paper-config>, masked_sections=<n>`
(counter since service start; always rendered — the off state is what a masking-testing
admin needs to see). AS SHIPPED it touches only the DiagnosticsFormatter tests — the
soak/benchmark exporters deliberately carry no xray field (the soak laws are
content-agnostic; masking engagement in a soak run is verifiable from the per-dimension
activation log line). Note: dimensions evaluate lazily on first serve, so a freshly booted
playerless server reads `active=off` even with `"on"` configured — the first serve fixes it.

## 4. Phases

1. **A1+A2 (crash):** shim + probe containment + Tier 1 tests. Validate live:
   `run-fabric-antixray` survives a client join end-to-end. File the upstream issue.
2. **B core:** policy + twin filters + config + defaults + Tier 1 tests (masking, parity,
   golden corpus, config validation, policy ladder).
3. **B wiring:** detection (Fabric mod probe seam, Paper per-world config), diag line,
   Tier 2 gametest (config-forced masking: served ore column decodes with replacements on
   the client consumer; disk/live parity with masking on).
4. **Validation:** benchmark A/B (masking cost), `run-fabric-antixray` (no crash, ores
   masked), `run-paper` with native anti-xray enabled (ores masked in LODs), one soak
   sanity run with `"xrayObfuscation": "on"` in a scenario config override (laws are
   content-agnostic — expect green untouched).

## 5. Open decisions (defaults chosen, flag disagreement at review)

- Tri-state key name `xrayObfuscation` (vs `antiXrayMasking`).
- chest/ender_chest in the default hidden list (Paper hides them; they're also common
  legitimate builds — masking them in LODs is invisible at range, keep Paper's list).
- Engine adoption is IN for v1 on BOTH platforms (user-decided 2026-07-23): hidden list +
  max-block-height, Paper via typed config, Fabric via controller-field reflection with
  the LSS-keys fallback ladder. Engine-mode itself is never adopted (see Detection).
- Recall/re-serve of stale unmasked columns on enable: OUT (documented limitation).
