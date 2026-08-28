# Build-script reconciliation map (Phase 0, derived from main-vs-1.21.1 diffs)

KEY INSIGHT: main's build.gradle files are ALREADY the most line-parameterized
(the port-isolation program left `def tier3`, the folia-supported line.env
derivation, and the neoforge_floor derivation reading line data). The support
branches SIMPLIFIED/hardcoded those. → keep MAIN's versions; only ADD the arms
below. Many "differences" vanish once line.properties carries the absent keys
(modmenu_version, moonrise/c2me, sodium_version) so the property refs work
uniformly.

## fabric/build.gradle — arms to add (keyed off line data)
1. loom plugin id: `net.fabricmc.fabric-loom` (mapping_namespace=official) vs
   `net.fabricmc.fabric-loom-remap` (intermediary). Mechanism: root applies loom
   with `apply false`, module does conditional `apply plugin: <id>` OR use
   `-Pfabric.loom.dontRemap` belt (panel-verified). Spike this FIRST.
2. `mappings loom.officialMojangMappings()` — add iff mapping_namespace==intermediary.
3. dep scopes: implementation/compileOnly (official) vs modImplementation/
   modCompileOnly (intermediary) — a `depMod`/`depModCompileOnly` helper closure.
4. options.release: from line_java_version (25/21).
5. sodium/modmenu/moonrise/c2me: property refs uniformly once line.properties has
   the keys (data, not an arm) EXCEPT the has_modern_sodium guard: the
   `compileOnly sodium` + `sodiumModernGolden` arms are wrapped
   `if (lineData.has_modern_sodium)` (1.21.10 cut) + exclude the 25 caffeinemc
   config-api stub files on that line.
6. processResources suggests_* : provide empty-string data on non-26.2 lines
   (keep main's expand; the placeholders just resolve empty).
7. accesswidener header (v2 official vs v2 named): committed per-line resource
   overlay of lss.accesswidener; accessWidenerPath computed per line.
8. tier3: `def tier3 = lineData.tier3` (was hardcoded true) — the rest of main's
   tier3 machinery is line-neutral already.

## paper/build.gradle — arms
1. paperweight.paperDevBundle(<data: paperweight_bundle>).
2. options.release from line_java_version.
   (folia_supported derivation + processResources: keep main's — already reads
   lines/<line>/line.env.)

## neoforge/build.gradle — arms
1. java.toolchain.languageVersion = of(line_java_version).
2. FML-4 dev-run fold (1.21.1 only): `if (lineData.fml4_devrun_fold)` guarding
   evaluationDependsOn(':common') + `sourceSet project(':common').sourceSets.main`
   + `additionalRuntimeClasspath` sqlite/zstd.
3. gametest filter idiom: `--tests lsstest:*` (fml_gametest_filter=tests) vs
   `systemProperty 'neoforge.enabledGameTestNamespaces','lsstest'` (=namespaces).
   (neoforge_floor derivation: keep main's — already parameterized.)

## common/build.gradle — LINE-INVARIANT (no diff). No changes.

## New line-data keys needed beyond per-line-data.md
- has_modern_sodium (bool), fml4_devrun_fold (bool), fml_gametest_filter
  (tests|namespaces), tier3 (bool), mapping_namespace (official|intermediary,
  from line.env LINE_FABRIC_MAPPING_NAMESPACE — REQUIRED), sqlite_jdbc_version
  (the neoforge additionalRuntimeClasspath uses ${sqliteJdbcVersion}).

## Execution order (Phase 0)
1. Branch feat/single-branch off folded main.
2. gradle/line.gradle: load -PmcLine (default 26.2) → lines/<line>/line.properties
   into ext (CLI -P wins); per-line build dir build/<line>.
3. lines/26.2/ + lines/26.1/ (line.properties + line.env), TOTAL/explicit.
4. Apply line.gradle from each module's build.gradle; move the invariant keys OUT
   of root gradle.properties INTO lines/26.2 (so 26.2 has no privileged position).
5. The loom-id conditional spike (fabric) — the make-or-break.
6. GATE: `-PmcLine=26.2 :fabric:build` class-digest-identical to pre-branch main
   jar; `-PmcLine=26.1 :fabric:build` compiles (26.1 overlays: the LAN-hook census
   + 2 goldens + banner) + T1/T2/paper/neoforge green.
   → DELIVERABLE GO/NO-GO: if the loom/assembly machinery can't be made robust,
   STOP and report (plan §6), else proceed to Phase 1.
