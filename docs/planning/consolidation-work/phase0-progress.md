# Phase 0 progress — single-branch consolidation (feat/single-branch)

## Machinery BUILT + empirically de-risked (2026-08-28)
- **Data plane** `gradle/line.gradle`: loads `lines/<mcLine>/line.properties` into ext
  (CLI -P wins); PROVEN: `ext.set` overrides gradle.properties-backed props, `project.<key>`
  reads the override (probe). Default line 26.2 keeps gradle.properties (byte-identity);
  non-default lines override + isolate to `build/<line>`.
- **Loom conditional** PROVEN: root `apply false` + per-module conditional
  `apply plugin:` resolves BOTH markers (`net.fabricmc.fabric-loom` / `-remap`, one jar);
  `releaseJarTask` = jar|remapJar tracks the arm; vssJar dependsOn parameterized.
- **Source assembly** `assembleLineTree(name, sharedRoots, overlayRoots)`: per-line Sync
  that EXCLUDES overlaid/cut rel-paths from the shared copy (no Gradle-9 dup), applies
  excludes.txt + optional rename-table.tsv, overlay wins. Wired into common/xplat(fabric+neoforge)/
  fabric/neoforge/paper main+test+gametest. Default line bypasses (plain src/).
- **line data**: `lines/26.2/` + `lines/26.1/` (line.properties 19 keys + line.env);
  paperweight/folia_supported/neoforge_floor all data-driven (no resource overlays on 26.1).

## 26.2 IDENTITY GATE: PASS
- All LSS classes + resources byte-identical vs pre-machinery baseline; only variance is
  pre-existing `slimStoreDepJars` nested-zip-wrapper non-determinism (nested CONTENT identical,
  147/147 entries). Not caused by the machinery.

## 26.1 FOLD status
- **Main builds GREEN all 3 loaders** with 6 source overlays, 0 resource overlays.
- Source overlays (9 total, provenance-stamped):
  main: IntegratedServerLanHook, MovementRejectHook, FarPlayerRenderer (fabric);
        VoxyCompat, RegionFileRawRead, SelectiveChunkNbtLoader (xplat)
  test: FarPlayerMountLadderTest, XaeroTileExtractorTest, LanHookContractTest (fabric)
  gametest: LSSClientGameTests (fabric)
  resource: nbt-corpus/xray-masked.bin (fabric+paper, override-resolution)
- **26.1 ALL GREEN**: fabric T1 + T2 gametest + neoforge smoke + paper T1 all EXIT=0.
- Line-aware infra added: LineGoldens (override-resolution for goldens + main-source scanning),
  contract tests made line-aware (FabricModJson/Toolchain fabric+paper, PluginYml, ReleaseWorkflow),
  LineMatrixContractTest (fleet key-set/mirror/value pins), OverlayProvenanceContractTest (anti-drift).

## PHASE 0 GATE: GO (2026-08-28)
- 26.2 identity: all 8 jar families class+resource BYTE-IDENTICAL to pre-machinery baseline.
- 26.1: fabric T1 + T2 gametest + neoforge smoke + paper T1 all green; 26.2 T1/paper no-regression.
- release_check.py --line (build/<line>/libs + lines/<line>/ data); matrix build.yml + gen_matrix.py; LineMatrix + OverlayProvenance pins.
- Harness line axis DEFERRED (soak.sh/test-server.sh read gradle.properties/.github/line.env which the default line keeps — not broken; add SOAK_LINE when a non-default line soaks).

## NEXT
- Phase 1: fold 1.21.11 (Java-21 axis, loom-remap arm, remapJar release task).
- Release rework: 4-stage matrix pipeline + v0.14.0 prep.

## Phase 1 (1.21.11) — BUILD-ONLY proven (2026-08-28)
- The loom-remap + Java-21 axis is proven: 1.21.11 compiles on ALL THREE loaders.
- fabric/build.gradle arms added (all no-ops on the official arm — 26.2 stays byte-identical):
  dep scopes via add(modImplScope/modCompileScope), conditional mappings
  officialMojangMappings(), line-aware accessWidenerPath (named-namespace overlay).
- 14 source overlays: 12 fabric-main (fabric+xplat), 1 paper (PaperRequestProcessingService
  — m-3 rebase is a follow-up), 1 neoforge ScopedCarrier twin; + the named AW resource overlay.
- Line data lines/1.21.11/ (fold_status=build-only). gen_matrix.py emits a compile_matrix;
  build.yml has a compile-check job (main compile all loaders) so the axis stays exercised in CI.
- REMAINING for a FULL 1.21.11 fold (release-ready): ~18 nbt + 3 v20 corpus golden overlays
  (fabric+paper, mechanical — LineGoldens already routes them), ~20 test .java overlays
  (compiler+test oracle), the mixins.json/fabric.mod.json resource resolution, T1/T2 green,
  representative smoke soaks. Then flip fold_status=full.
- 1.21.10 (Sodium cut) + 1.21.1 (NBT-accessor seams, multi-week) NOT started — GO/NO-GO pending.
