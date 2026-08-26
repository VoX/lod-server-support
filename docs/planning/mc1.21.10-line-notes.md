# support/mc1.21.10 — line creation notes + decisions log

**Created 2026-08-22** (user request) as a LATERAL port: cut from
`support/mc1.21.11-v0.12` @ b21a67fc — the fully-prepared v0.12.0 tip,
including the ramp window-limited fix + both 2026-08-22 panel fold rounds —
one MC patch DOWN. MC 1.21.10 and 1.21.11 are adjacent patch releases, so the
entire MC-facing surface (mappings renames, count-short NativeSectionShape,
ScopedCarrier, gametest attribute names, split-dir resolver) is the 1.21.11
line's verbatim; the port is a dependency retarget plus two ecosystem-forced
deltas. Tier: **correct, not perfect** (the parent line's tier — full builds +
T1/T2 + representative smokes, no live rig).

## Retarget (mechanical)

- `gradle.properties`: minecraft_version/minecraft_dependency 1.21.10,
  fabric_version 0.138.4+1.21.10, neoforge_version 21.10.64 (loader 0.19.3 and
  loom 1.17.13 unchanged — both span the patch pair).
- `paper/build.gradle`: paperDevBundle 1.21.10-R0.1-SNAPSHOT (exists upstream,
  verified).
- `.github/line.env`: all identities 1.21.10; LINE_NEOFORGE_NAME retargeted;
  LINE_SHIP_NEOFORGE stays false (no client pairing on this line either).
- `fabric.mod.json`: fabric-api floor lowered to >=0.138.0 — the parent's
  >=0.141.0 is UNSATISFIABLE on 1.21.10 (fabric-api tops out at
  0.138.4+1.21.10).
- Benchmark-arm dev pins: moonrise-opt 0.8.0-beta.4+c0e63e9, c2me-fabric
  0.3.6+alpha.0.11+1.21.10 (runtime-only A/B arms).

## Decision 1 (dated 2026-08-22): NO Folia on this line

Folia publishes no MC 1.21.10 build (its version list jumps 1.21.8 → 1.21.11;
verified against fill.papermc.io at line creation). Per the R-7 direction-flip
doctrine, presence of `folia-supported: true` would advertise a platform with
no loadable host, so this line declares **`folia-supported: false`** and drops
`folia` from LINE_PAPER_LOADERS ("paper purpur"). Pinned three ways:
`PluginYmlContractTest.foliaSupportedIsFalseBecauseFoliaSkips12110` (the
inverted flavor test), the `ReleaseWorkflowContractTest` loaders pin, and
`release_check.py`'s inverted raw-line grep (with a selftest arm catching a
resurrected `true`). The Folia code paths (regionized probing, lifecycle
mailbox, `FoliaWiringContractTest`) ship dormant and stay maintained — the
single plugin jar is shared across lines. The SOAK_PLATFORM=folia lane and
`test-server.sh run-folia` are inoperable here by upstream absence, not by
cut.

## Decision 2 (dated 2026-08-22, the §6.2-style cut record): Sodium options page CUT

Sodium for MC 1.21.10 tops out at **0.7.3**, which predates the structured
config API (`net.caffeinemc.mods.sodium.api.config.*`) the LSS options page
binds — the same reality that cut the page on the frozen 1.21.8 line. Cut
surface: `LSSConfigMenu.java` deleted, the `sodium:config_api_user`
entrypoint removed from fabric.mod.json, the sodium modCompileOnly dropped.
The ModMenu integration goes WITH the cut (found at first compile: it was
only a deep-link into Sodium 0.8's VideoSettingsScreen via ConfigManager —
both 0.8 client classes), so the modmenu entrypoint + dep are dropped too.
Kept: `RateSliderStops` (Sodium-import-free; ConfigValidationTest classloads
it) and every config KEY — the JSON config files carry the full surface, so
nothing functional is lost, only the in-game pages. The release notes must
name this cut. Revisit only if Sodium backports 0.8 to 1.21.10 (they will
not).

**Amended 2026-08-23 (the options-page-generations round, main PR #236 ported
per sodium-options-page-generations-plan.md §12): the cut is NARROWED to the
0.8 walker.** The page is BACK on this line — rendered through
`LegacySodiumPage` (Sodium 0.6/0.7's internal options API, bound reflectively
with no compile dependency) injected by the `@Pseudo` `SodiumLegacyOptionsHook`
at `SodiumOptionsGUI.<init>`; this is the 0.7-ONLY proof line, so
`sodium_legacy_golden` pins its own `mc1.21.10-0.7.3-neoforge` (the
`setTooltip(Function)` overload generation). The ModMenu integration is
RESTORED (ModMenu 16.0.1 compile dep + the `modmenu` entrypoint + suggests):
its Configure button opens the legacy screen through `SodiumConfigScreens`'
reflective generation switch. Still cut: `LSSConfigMenu` (the 0.8+ walker) and
the `sodium:config_api_user` entrypoint (`ClientMenuEntrypointContractTest`
pins entrypoint ⇔ file, so both stay absent together) — and there is no
`sodium_version` (no 0.8 artifact), so the resolves-test's modern golden arm is
unresolvable-by-design here (`fabric/build.gradle` guards on the property).
The v0.13.0 line release notes must say the page is back (legacy Sodium
screen) rather than repeat the cut.

## Findings at first compile (the real 1.21.10↔1.21.11 API boundary)

The lateral-port premise "1.21.11's MC surface verbatim" was WRONG in three
ways — 1.21.10 sits on the OLD side of the 1.21.11 mappings/API wave:

1. **The ResourceLocation-family renames**: 1.21.10 uses
   `net.minecraft.resources.ResourceLocation` (not `Identifier`),
   `ResourceKey.location()` (not `.identifier()`), `net.minecraft.Util`
   (not `net.minecraft.util.Util`), `ResourceLocationException` (not
   `IdentifierException`) — the exact rename set the 1.21.1 line carries,
   applied mechanically (45 files + accessor/exception sweeps).
2. **The permissions rework is 1.21.11+**: no `net.minecraft.server.permissions`
   package here — `CommandSourceStack.hasPermission(int)` +
   `Commands.LEVEL_GAMEMASTERS` (LSSServerCommands) and the int-level
   `CommandSourceStack` ctor (CommandGameTests: NO_PERMISSIONS→0,
   ALL_PERMISSIONS→4), both the 1.21.1 line's forms.
3. **GameTestHelper takes Component messages** (`assertTrue(boolean,
   Component)` / `fail(Component)` — the 1.21.9/10 window; every sibling has
   String overloads): shimmed via `fabric/src/gametest/.../Gt.java` with a
   mechanical call-prefix reroute (`helper.assertTrue(` →
   `Gt.assertTrue(helper, `, 541 sites + 13 fails) so every condition and
   message expression stays byte-identical to the parent line. The NeoForge
   smoke needed the same twin shim (`neoforge/src/gametest/.../Gt.java`, 31
   sites — its compile only runs on CI's runGameTestServer step, which is how
   it was found).

## Findings at first RUN (Tier-2 receipts — the build cannot see these)

4. **`ChunkMap extends ChunkStorage` here** (the SimpleRegionStorage
   superclass move is 1.21.11+): the parent's
   `(AccessorSimpleRegionStorage) chunkMap` cast CCE'd at runtime, was
   swallowed by the resolver's catch-all, and SILENTLY latched
   `backgroundIncompatible` (throttle fallback) — caught only by the
   serializer-parity gametest's `raw_serves` receipt. Fix: retarget
   `@Mixin(ChunkStorage.class)`, class name kept (the 1.21.1 line's exact
   adaptation).
5. **The client-loaded gate lives on `Player` here with a public setter**
   (`clientLoaded`/`clientLoadedTimeoutTimer` move to the LISTENER at
   1.21.11, as does `waitingForRespawn`): `handleMovePlayer` opens with
   `hasClientLoaded()`, a mock never acks, every move early-returned — zero
   tracer rows AND zero vanilla warns. Fix: `primeListenerForMoves` calls
   `connection.player.setClientLoaded(true)` + the one reflective field.

## Findings at the creation review (2-Opus pair — the packaging layer)

6. `neoforge.mods.toml` loader floor was the parent's `[21.11,)` —
   UNSATISFIABLE on a 21.10.64 line (nothing pins the range; the NeoForge
   smoke's CI compile is the only consumer that noticed anything nearby).
   Now `[21.10,)`.
7. `release.yml`'s Paper Modrinth step NAME advertised Folia — the fourth
   guard Decision 1 needed (now "Paper/Purpur (MC 1.21.10)", matching the
   1.21.1 precedent).
8. `test-server.sh` was fully un-retargeted (a 1.21.11 rig for a 1.21.10
   branch): MC versions, fabric-api/c2me/antixray URLs, the legacy
   protocol-16 rig (no +mc1.21.10 legacy build ever shipped —
   LEGACY_LSS_MC=""), the Folia probe comment, the Java-check message.
9. Assorted label/staleness fixes: contract-test javadocs, the fabric.mod.json
   `suggests` block (sodium unsatisfiable here, modmenu integration cut),
   release_check's dead class prefixes, per-version-surfaces' wrong identity
   paragraph. Carried open: the tree-wide "verified against 1.21.11" prose
   labels on golden/pin-covered surfaces (row 15's hand row is the one
   flagged risk), and the RegionSummaryServiceTest 60 s deadline (the
   flake catalog's second-sighting escalation, applied here first).

## Gates run at creation

Recorded in the v0.12.0 release plan §12.10 as they complete: line
`release_check --selftest` (88 — the new folia-true arm), full clean
pre-flight at `-Pmod_version=0.12.0` + `release_check.py --version 0.12.0`,
T1/T2 via `:fabric:build -x runClientGameTest`, `:paper:test`,
`:neoforge:build`, CI on push, and a 2-Opus review pair (the per-line
discipline this release used everywhere).

## Client pairing status + carries (creation review B)

- **No recorded Voxy build for 1.21.10**: the Fabric client half is inert
  until a Voxy 1.21.10 pairing exists (no consumer -> no capability bit);
  the server side serves wire-compatible clients on other lines regardless.
  The fabric.mod.json voxy suggests row stays (advisory).
- Carries: derive neoforge.mods.toml's loader floor from neoforge_version (a
  pin — this row was the port's one flatly-wrong value and nothing guards
  it); verify row 16 against a real 0.8.x moonrise-opt jar (label updated to
  say UNVERIFIED); the tree-wide stale "verified against 1.21.11" prose
  labels (row 15 is now closed-verified; the rest are golden/pin-covered).
