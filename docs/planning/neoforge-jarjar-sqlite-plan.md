# NeoForge jarJar'd sqlite plan (the G-4 P-1 fix)

> **Amended 2026-09-06 (issue #275, `issues-275-282-fix-plan.md`):** zstd-jni now rides NESTED as a second stock jarJar library — the same mechanism, the XMMP clash as the trigger. Every "zstd stays flat" statement below records the 2026-08-15 state; the NeoForge flat jar carries NO native library at all now, and `release_check` forbids flat `com/github/luben/**` beside flat `org/sqlite/**`.

**Status: v1.1, 2026-08-15 — Fable review folded (verdict: sound, 0 MAJOR / 6 MINOR),
executing.** User decision: fix the P-1 sqlite module collision via the jarJar route
(option C of the G-4 decision), for the v0.11.0 release, superseding the recorded
relocate-vs-caveat binary (relocation was never viable — the build's own no-relocate
rule: sqlite-jdbc resolves JNI symbols by fully-qualified class name, so relocated
classes die with `UnsatisfiedLinkError`).

## 1. Problem (settled facts, live-diagnosed 2026-08-15)

The LSS NeoForge shadowJar flat-shades sqlite-jdbc at `org/sqlite/**`, so the `lss`
module exports the `org.sqlite.*` packages. The community Voxy NeoForge fork
(j-shelfwood, 1.21.1 — the client pairing that justifies shipping NeoForge on that
line) carries the SAME artifact as a jarJar nested module. Two modules exporting one
package is a JPMS hard error: FML aborts at boot with `ResolutionException: Modules
org.xerial.sqlitejdbc and lss export package org.sqlite...`. As shipped, the LSS
NeoForge client and the community Voxy port are mutually exclusive.

Facts the fix rests on (all verified in-session; review-verified items marked ✓R):

- The fork's `META-INF/jarjar/metadata.json` sqlite entry: identifier
  `{group: "org.xerial", artifact: "sqlite-jdbc"}`, version
  `{range: "[3.40.0.0,4.0.0.0)", artifactVersion: "3.49.1.0"}`, path
  `META-INF/jarjar/sqlite-jdbc-3.49.1.0.jar`, `isObfuscated: false`. Our declared
  version is the identical `3.49.1.0`. JarJar selection collects all candidates per
  identifier and loads ONE jar whose artifactVersion satisfies every declared range.
- ✓R sqlite-jdbc 3.49.1.0 is a NAMED JPMS module (MR-jar module-info: `module
  org.xerial.sqlitejdbc`, requires only java.base/java.sql/java.sql.rowset hard;
  `provides java.sql.Driver` via the module directive). It resolves standalone —
  matches the live exception's module name.
- ✓R Shadow 9.4.2 writes non-`shade` `from()` files VERBATIM (only `configurations`
  entries go through `zipTree()`; `mergeServiceFiles`' transformer matches
  `META-INF/services/` only) — the nested jar arrives byte-identical, deflated, the
  same shape the fork live-proves on this exact loader.
- The fork does NOT nest `com.github.luben:zstd-jni` (its zstd is `org.lwjgl:lwjgl-zstd`,
  different packages). LSS's flat-shaded zstd does not collide with it — proven live:
  the nosqlite-stripped jar (zstd still flat) boots and plays beside the fork.
- ✓R The store's only sqlite entry point is `new org.sqlite.SQLiteDataSource()`
  (SqliteLodStore:361/:702, compile-time reference; no Class.forName/DriverManager
  anywhere in common/), the exact cross-module pattern the fork's own code uses.
- ✓R `transitive = false` is parity-safe: the 3.49.1.0 POM has zero runtime deps
  (slf4j optional, graalvm provided) — flat shading pulled no transitives either.
- The store works on NeoForge today (live 2026-08-15: store active, natives loaded,
  backfill deposited 2115 columns, 0 errors) — the fix must preserve that.

## 2. Decision

Move sqlite-jdbc from the `shade` configuration to a **nested jarJar library** in the
NeoForge shadowJar: the STOCK upstream `sqlite-jdbc-3.49.1.0.jar`, byte-identical to
the Maven artifact, at `META-INF/jarjar/sqlite-jdbc-3.49.1.0.jar`, declared by a
`META-INF/jarjar/metadata.json` whose identifier matches the fork's
(`org.xerial:sqlite-jdbc`). FML then sees ONE `org.xerial.sqlitejdbc` module however
many mods carry it, and the collision class is closed for sqlite.

Scope decisions:

- **sqlite only.** zstd-jni stays flat-shaded: no collision exists with the fork
  (fact above), zstd is core wire functionality (protocol-19 frames) where packaging
  churn is pure risk, and the round-3 P-1 "another mod ships zstd-jni" hole stays
  documented with this plan as the named fix pattern if it ever fires.
- **Stock bytes, all platforms.** The nested jar is the resolved Maven artifact
  verbatim — NOT natives-trimmed like the flat shading was. Rationale: when FML picks
  between our copy and another mod's copy of the same version the outcome must not
  depend on which copy wins, so our copy must be the same bytes everybody else nests.
  Cost: the neoforge jar grows to carry the stock artifact (~14.3 MB). Accepted.
- **Range `[3.49.1.0,4.0.0.0)`** — lower bound our version, upper bound the next
  major, mirroring the fork's convention (review M4, decided with eyes open): a
  hypothetical future sqlite-jdbc 4.x nested by another mod then fails as a HARD,
  attributable jarjar version conflict at boot instead of silently winning selection
  and running under our 3.49-compiled store (the silent-API-skew mode `[ver,)` would
  allow). The upper bound is computed from the resolved version's major (major+1).
- **Hand-rolled metadata, not MDG's jarJar wiring.** MDG2's `JarJarPlugin` wires its
  `jarJar` task into the `jar` task, which this module DISABLES (shadowJar is the
  artifact, the -slim glob guard). ✓R a ~2-line `shadowJar { from(tasks.named('jarJar')) }`
  re-wire exists and was considered — rejected for release week in favor of a ~25-line
  hand-rolled task that is plugin-version-independent and produces a byte-pinnable
  format (release_check pins it, §4); the fork's known-working metadata is the
  reference shape, including an explicit `isObfuscated: false`.
- **All four trees** (main, 26.1, 1.21.11, 1.21.1). Only 1.21.1 ships NeoForge, but
  build.yml builds the module everywhere ("stays maintained for re-enable"), the
  collision surface is version-independent, and divergent packaging across lines is
  exactly the port-isolation pain class. The sqlite version is read from each tree's
  own dependency declaration (metadata is generated from the RESOLVED artifact, so a
  future version bump cannot desync the metadata).
- **Atomic per tree** (review M6): build.yml runs the FULL release_check on every
  push on every line (the `neo` loop runs regardless of SHIP_NEOFORGE), so the §3
  build change and the §4 release_check retarget MUST land as ONE commit per tree —
  the intermediate state reds CI.

## 3. Build changes (`neoforge/build.gradle`, per tree)

1. Hoist one version literal: `def sqliteJdbcVersion = '3.49.1.0'`; both the
   `implementation` row and the new configuration reference it (review NIT).
2. Remove `shade "org.xerial:sqlite-jdbc:..."` (keep `implementation` — compilation
   and dev runs are unchanged).
3. Add a resolution-only configuration `jarJarStore { transitive = false }` +
   `jarJarStore "org.xerial:sqlite-jdbc:${sqliteJdbcVersion}"`.
4. Register `generateJarJarMetadata`: writes `build/generated/jarjar/metadata.json`
   from the RESOLVED artifact of `jarJarStore` (group/artifact fixed; artifactVersion
   + path filename from the resolved file; range `[<version>,<major+1>.0.0.0)`;
   explicit `isObfuscated: false`). Inputs: the configuration; outputs: the file.
5. `shadowJar`: add
   `from(configurations.jarJarStore) { into 'META-INF/jarjar' }` and
   `from(generateJarJarMetadata) { into 'META-INF/jarjar' }`.
6. Drop the sqlite rows from `storeNativeKeep`; leave `storeNativeRoots` untouched
   (review M5: with no flat org/sqlite files the root matches nothing and is
   harmless; it is independent of release_check's `STORE_NATIVE_ROOTS`).
7. Rewrite the N-2 comment block: shading remains for common+zstd; sqlite is nested
   for module dedupe (name this plan); the no-relocate rule note stays.

Not changed: `mergeServiceFiles()` (sqlite no longer rides the shade path; the named
module's `provides java.sql.Driver` directive is its provider mechanism — moot
anyway, the store never uses DriverManager), neoforge.mods.toml (jarJar needs no
declaration), THIRD-PARTY-NOTICES text (we still redistribute sqlite, now verbatim;
its license-delegation TARGET moves — see §4 license pin).

## 4. What must be re-pinned (`scripts/release_check.py`, per tree)

Today `neo`/`vneo` jars run `check_store_natives_paper` verbatim, which after this
change would red on the missing flat `org/sqlite/JDBC.class`. Changes:

- **`_nested_jars` gains `META-INF/jarjar/*.jar`** (review M2): `_scan_forbidden`
  then looks inside the new nested entry too, keeping CLAUDE.md's "no dev-only
  packages ship, incl. inside nested Jar-in-Jar entries" claim true for neoforge.
  ✓R all other `_nested_jars` consumers key on exact `META-INF/jars/` paths or
  fabric jars — safe.
- **New `check_store_natives_neoforge(jar, problems)`** replacing the paper check at
  both call sites (neo + vneo):
  - `META-INF/jarjar/metadata.json` present and parses; exactly one entry with
    identifier `org.xerial`/`sqlite-jdbc`; its `path` exists in the jar; its
    `artifactVersion` equals the version embedded in the nested jar's filename.
  - The nested jar at `path` opens as a zip; `_check_sqlite_natives` runs against
    ITS entry names. Do NOT run `_check_native_strip` against the nested jar —
    stock bytes are the point (comment this; the inverse of the flat-jar rule).
  - License delegation (review M3): the nested jar must carry
    `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE` — THIRD-PARTY-NOTICES delegates
    sqlite's Apache-2.0 text to that path (mirrors the fabric nested-jar pin).
  - The FLAT jar has NO `org/sqlite/` entries at all (class or native — the
    collision surface must be provably gone; reds if the shade config regresses).
  - zstd stays flat: `_check_zstd_natives` + `_check_native_strip` on the flat
    names (release_check's `STORE_NATIVE_ROOTS` tuple stays untouched; its sqlite
    root matches nothing flat and is harmless).
- **Selftest** (review M1): RESHAPE `_write_tree_neoforge`'s store entries to the
  nested jarjar shape (synthesized nested zip + metadata.json) so the existing ~10
  neoforge selftest cases stay green, THEN add negative cases: metadata present but
  nested jar missing; flat org/sqlite leaking back beside the nested jar; wrong
  identifier group; nested jar missing a native. Follow the existing in-memory
  fixture style.

✓R No other consumer changes: `check_wire_identity_neoforge`'s `_class_digest` is
classes-only; the vssJar copy loop is content-preserving (nested entries ride along
identically into the vneo jar, where the same new check runs); no contract test pins
jar contents; fabric/paper checks untouched.

## 5. Docs

- `CLAUDE.md`: project-structure line ("MDG 2, shaded (no jarJar)") and the output-jar
  bullet — sqlite is nested-jarJar'd for module dedupe against other sqlite-carrying
  mods, everything else stays shaded; name this plan.
- `docs/planning/v0.11.0-progress.md` decisions log: G-4 sqlite decision RESOLVED as
  jarJar (dated, user decision); note the relocate option was structurally void; add
  the resolution pointer to the round-3 P-1 hole entry (sqlite half closed, zstd half
  remains documented, this plan the named pattern). A pointer suffices for the
  historical N-2 record in neoforge-support-plan.md.
- Release notes: NO change — the collision never shipped, and the approved short
  notes stay as approved.

## 6. Execution order

A. Build change on main (§3) + `./gradlew :neoforge:build` green.
B. Jar forensics on the built jar: no flat `org/sqlite/`; metadata.json byte-review;
   nested jar SHA-256 == the Gradle-cache artifact's SHA-256 (stock-bytes proof).
C. release_check retarget (§4) + `--selftest` green + a full release_check run
   against freshly built fabric+paper+neoforge families on main.
D. `./gradlew vssJars` + the neoforge VSS pair checks green.
E. Docs (§5).
F. Port the same diff to g26 / g21 / g211 worktrees; per tree:
   `:neoforge:build` + release_check `--selftest`; g211 additionally the full
   pre-flight build line (it is the shipping line). ONE commit per tree (§2 atomic).
G. Live gates (the decisive ones):
   1. **Server store gate (mainline)**: multi-test `neoforge-26.2` with
      `lodStore: "on"`, new jar → `LOD store active`, store.db written, backfill
      deposits, 0 errors (repeat of the 2026-08-15 flat-shape verification).
   2. **End-to-end serve gate (mainline)**: headless soak client
      (`:fabric:runSoakClient -Psoak.server=localhost:25588`) joins that server →
      handshake + columns received (proves wire + serving unaffected by packaging).
   3. **Collision A/B via the PROBE (1.21.1, the reason this plan exists)**:
      `sqlite-collision-probe.jar` — a purpose-built `lowcodefml` mod nesting ONLY
      stock sqlite-jdbc with fork-identical metadata (built 2026-08-15; the
      fork-on-server idea was tried first and is UNREACHABLE, empirically: the
      fork's nested `lwjgl-zstd` requires the `org.lwjgl` module, absent on
      dedicated servers, and that FindException aborts the layer before the sqlite
      report; the review's mods.toml reachability argument is superseded by this
      live result). CONTROL ARM ALREADY RUN (2026-08-15): current-shape jar +
      probe → `ResolutionException: Modules org.xerial.sqlitejdbc and lss export
      package org.sqlite.jdbc4` in a 5 s automated server boot. Fix arm: new
      1.21.1 jar + probe → boot must get PAST module resolution to a clean
      `Done`; then remove the probe.
   4. **Prism pairing gate (user-assisted, authoritative)**: replace the
      instance-local nosqlite jar in `lss-test-neo-1.21.1` with the REAL new
      client jar (fork + FFAPI + sodium unchanged) → user boots, joins, sees LODs.
      Stage it; the user clicks.
H. Commit per tree (support branches direct-push, main via PR), CI green ×4.

## 7. Risks and containments

- **FML metadata-format drift**: the format is copied from the fork's live-working
  metadata on the SAME NeoForge version, and gate G-3 exercises the real loader.
- **Module-boundary access breaks the store**: `SQLiteDataSource` is a compile-time
  reference resolved through the game layer exactly like the fork's own usage; gate
  G-1 proves it live (natives extraction included) before anything ships.
- **Dev runs / gametests see nothing**: MDG dev runs use classes dirs +
  `implementation` classpath, not the shadowJar — unchanged either way; that is WHY
  every decisive gate in §6-G is a live production-jar boot.
- **vssJar identity drift**: step D runs the pair gate; the repackage is
  byte-preserving so the nested entries ride along identically.
- **Tie-break between our copy and the fork's**: moot by construction — both nest
  the stock 3.49.1.0 bytes (that is what the stock-bytes rule buys).
- **Rollback**: revert the commit(s); the flat-shaded shape remains in history.
  No config flag — packaging cannot be config-gated.
