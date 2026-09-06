# Issues #275 (NeoForge zstd module clash) and #282 (LibsDisguises far players) — fix plan (v1, 2026-09-06)

Status: v2 after the 2-Fable review (§6 is the record; every MAJOR folded), EXECUTING on all five lines. Line references are
the `main` (26.2) tree unless a line is named.

## 0. Facts

### #275 — `ResolutionException: Modules com.github.luben.zstd_jni and lss export package com.github.luben.zstd.util`

- **Our NeoForge jar shades zstd-jni FLAT and unrelocated.** `neoforge/build.gradle` (26.2
  `:154-155`; the packaging LINES are identical on all five trees — the `implementation` +
  `shade 'com.github.luben:zstd-jni:1.5.7-3'` rows); the shipped
  `lod-server-support-neoforge.jar` carries `com/github/luben/zstd/util/Native.class` and the
  six kept natives (`linux|win|darwin/...libzstd-jni-1.5.7-3.*`) at the jar root. FML makes the
  mod jar the automatic module `lss`, which therefore EXPORTS `com.github.luben.zstd.*`.
- **XMMP nests zstd-jni as a jarJar library** (`zstd-jni-1.5.7-9.jar [parent: xmmp-0.3.2+1.21.1-neoforge.jar]`,
  the reporter's log; its `build.neoforge.gradle.kts:76` is
  `jarJar(implementation("com.github.luben:zstd-jni:1.5.7-9"))` — the STOCK Maven artifact under
  the identifier `com.github.luben:zstd-jni`, MDG range `[1.5.7-9,)`), i.e. a second module
  `com.github.luben.zstd_jni` exporting the same packages into the same layer. Stock zstd-jni is
  an EXPLICIT module (`module-info.class` at the jar ROOT — not a multi-release entry like
  sqlite's — exporting `com.github.luben.zstd` and `…util`). JPMS refuses to resolve two modules exporting one package —
  the crash is at module resolution, before any mod code runs. Reproduction is exact:
  Neo-Voxy + LSS works, adding XMMP fails, removing XMMP heals.
- **Relocation is impossible** (the standing no-relocate rule, `neoforge/build.gradle` packaging comment):
  zstd-jni's JNI symbols encode the package (`Java_com_github_luben_zstd_…`) and its
  `Native` loader looks up `/linux/amd64/libzstd-jni-<ver>.so` by the class's own package.
- **The fix pattern already exists in this repo.** sqlite-jdbc hit the identical collision
  class against the community Voxy NeoForge port and was moved from flat shading to a STOCK
  nested jarJar library (`docs/planning/neoforge-jarjar-sqlite-plan.md`, 2026-08-15): FML then
  dedupes `org.xerial.sqlitejdbc` across mods. That plan explicitly scoped itself to
  "sqlite only … zstd-jni stays flat-shaded: no collision exists … the round-3 P-1 'another
  mod ships zstd-jni' hole stays documented with this plan as the named fix pattern if it ever
  fires" (§2). It fired. Its settled decisions carry over verbatim: stock bytes (never
  natives-trimmed, so whichever copy FML selects is the same bytes), range
  `[<ver>,<major+1>.0.0.0)` (a future-major copy nested by another mod fails HARD at boot
  instead of silently winning), hand-rolled metadata generated from the RESOLVED artifact,
  all trees, atomic per tree (build change + `release_check` retarget in ONE commit — CI runs
  the full check on every push, the intermediate state reds).
- **Per-line state.** zstd-jni is `1.5.7-3` and sqlite-jdbc `3.49.1.0` on all five lines. The
  NeoForge packaging block (`def sqliteJdbcVersion` … `shadowJar {`) has FOUR textual variants
  (26.2; 26.1 = 1.21.11; 1.21.10; 1.21.1) — comments and neighbouring dependencies drift, the
  packaging lines themselves are the same. `release_check.check_store_natives_neoforge` still
  documents "zstd-jni stays flat-shaded (no collision exists for it)" and pins the zstd
  native matrix + strip ON THE SHADOW JAR (`_check_zstd_natives(base, "shadow jar", …)`),
  which inverts once zstd nests.
- **Selection semantics.** With both copies nested, FML's jarJar selection picks one version
  satisfying every declared range: XMMP's `1.5.7-9` against our `[1.5.7-3,2.0.0.0)` → 1.5.7-9
  (a patch-level newer build of the same 1.5.7 API our wire codec compiles against). Same
  posture the sqlite plan accepted.
- **Dev runs are unaffected**: MDG dev runs and the NeoForge gametest server use the plain
  classpath, never the shadowJar (1.21.1 additionally puts both deps on
  `additionalRuntimeClasspath` for its FML 4.x dev layer — that row exists ONLY there) — the
  decisive gates are production-jar boots, as the sqlite plan's §6 found.
- **`common` cannot leak zstd**: it declares zstd/sqlite `compileOnly`, so `shade project(':common')`
  pulls no transitive copy; `META-INF/services` holds only `LoaderServices`; the vssJar loop is a
  byte-copy. Deleting the one `shade` row removes every export route.
- **What stays unfixable**: a THIRD mod shading zstd FLAT re-fires the same `ResolutionException`
  against the (now required) `com.github.luben.zstd_jni` module — out of our hands; the reply
  says so.
- **The release_check SELFTEST synthesizes the old shape** (`_store_neoforge_entries`,
  `release_check.py:1271-1281`: flat `com/github/luben/zstd/Zstd.class` + six root natives + a
  one-entry metadata) and every NeoForge positive case runs through `check_store_natives_neoforge`;
  `build.yml` runs `--selftest` on every push. The retarget without the fixture reshape reds CI on
  the first push of every tree (the sqlite plan's review-M1 lesson).

### #282 — LibsDisguises: the disguised player "still shows up" beside the disguise

- **Mechanism (client, `fabric/.../client/FarPlayerRenderer.java:352-359`).** The handoff to
  vanilla is `real = level.getPlayerByUUID(uuid); if (real != null && …shouldRenderAtSqrDistance)
  continue;` — the proxy is drawn exactly when the client has NO player entity for that UUID
  within vanilla's draw range. LibsDisguises replaces the disguised player's spawn packet with
  a different entity TYPE, so the viewer's client has no `Player` for that UUID at all:
  `real == null`, the proxy draws — at the far-player wire position, i.e. on top of the
  enderman, at any distance. The same on every line and both loaders (the 1.21.1 NeoForge
  twin has the same handoff).
- **A client-side UUID belt would be INERT for LibsDisguises** (verified in its source,
  `PacketHandlerSpawn.constructLivingPacket`): mob disguises are spawned with
  `disguise.getUUID()`, a disguise-specific UUID, under the player's real ENTITY ID — the
  far-player wire carries UUIDs, not entity ids, so nothing on the client links the enderman
  to the far-player entry. The fix is server-side.
- **The server already has the right seam.** Paper's far-player privacy ladder
  `PaperFarPlayerSnapshots.hiddenFor(Player)` (`paper/.../PaperFarPlayerSnapshots.java:98-124`):
  the two permission nodes, then the `vanished` metadata bridge, CONTAINED per player and
  failing HIDDEN (Folia review 2026-08-27 R2/R7 — a raced/throwing read must never leak a
  hidden position). `hiddenFor` is byte-identical on all five trees (the file's two variants differ
  elsewhere) — the rung site is line-invariant. `PaperFarPlayerSnapshotsTest` pins the ladder
  with Mockito players (`vanishedMetadataHides`, `aThrowingVanishReadFailsHIDDENNotOpen`,
  `theThrowIsContainedPerPlayerNotPerPass`).
- **LibsDisguises API** (its `DisguiseAPI`, package `me.libraryaddict.disguise`, verified from
  source): `public static boolean isDisguised(Entity)` and the viewer-aware
  `isDisguised(Player observer, Entity)`; `getDisguise(Entity)`. A reflective bridge in the
  `MeliusVanishBridge` / `FabricPermissionsBridge` idiom (`Class.forName` presence probe, one
  `MethodHandle` by exact `MethodType`, resolved once, warn-once on invoke failure) needs no
  compile dependency. Paper plugin classloaders resolve other plugins' classes; a
  `softdepend: [LibsDisguises]` in `plugin.yml` fixes load ORDER and silences Spigot's
  "loaded class … not a depend/softdepend" warning — the exact reason `ViaVersion` is
  already a softdepend (`plugin.yml:13-17`, pinned by
  `PluginYmlContractTest.viaVersionIsASoftdependForTheClassloaderWarning`).
- **Per-viewer disguises.** LibsDisguises can show a disguise to some viewers and not others,
  and a per-(viewer, target) seam DOES exist in common (Fabric wires the Melius bridge through
  it; Paper passes `null`), so `isDisguised(observer, entity)` is reachable later. v1 hides a
  disguised player from EVERY far-player viewer: beyond tracking range the disguise entity does
  not exist for anyone, targeted disguises are a corner, and hide-for-all is the safe direction.
  The reply says "not per-viewer yet".
- **Folia.** LibsDisguises declares `folia-supported: true`; the rung runs on the global region
  thread like the vanish read. `isDisguised(Entity)` is a `ConcurrentHashMap` get keyed by the
  entity id — no hazard beyond the vanish read; the fail-HIDDEN containment is inherited.
- **Fabric/NeoForge servers.** No packet-level disguise mod with a stable API is known on
  those loaders (the morph-style mods keep the entity a `Player`, which the handoff already
  handles); out of scope, documented.

## 1. Goals / non-goals

Goals: (G1) the NeoForge jar coexists with any mod nesting zstd-jni (XMMP today) — one
`com.github.luben.zstd_jni` module however many mods carry it; (G2) a LibsDisguises-disguised
player is never broadcast as a far player on Paper; (G3) both pinned by `release_check` /
Tier 1 so they cannot regress silently; (G4) both issues answered.

Non-goals: relocating zstd (impossible), a client-side disguise heuristic (inert, above),
per-viewer disguise fidelity, a Fabric-server disguise bridge, changing the far-player wire.

## 2. Work items

### WI-A `#275` — nest zstd-jni as a stock jarJar library (all five trees, one commit per tree)

1. `neoforge/build.gradle`: `jarJarStore 'com.github.luben:zstd-jni:1.5.7-3'` beside the
   sqlite line; DELETE `shade 'com.github.luben:zstd-jni:1.5.7-3'` (keep `implementation` and
   `additionalRuntimeClasspath`). Generalize `generateJarJarMetadata` from the single-artifact
   regex to a loop over `configurations.jarJarStore.resolvedConfiguration.resolvedArtifacts`
   (group/artifact from the module id, `artifactVersion` from the resolved version, range
   `[<ver>,<major+1>.0.0.0)` — for `1.5.7-3` that is `[1.5.7-3,2.0.0.0)`), one `jars` entry
   each, deterministic order (sorted by artifact). Native strip: DELETE the
   `storeNativeKeep` list and its "keep in sync with paper/fabric" comment; `storeNativeStrip`
   becomes roots-only (any flat native under `linux/ win/ darwin/ freebsd/ aix/` or
   `org/sqlite/native/` is stripped — the "regression louder" rule, now for both libraries).
   Metadata task: resolve through `cfg.incoming.artifacts.resolvedArtifacts` (a Provider captured
   at configuration time), take group/name/version from `variant.owner`, and ASSERT each
   `file.name == "<name>-<version>.jar"` — the metadata `path` must equal what `from(cfg)` copies.
   zstd-jni's POM carries test-scope deps only, so `transitive = false` stays right. Packaging comment
   (`:137-146`) updated: "Paper-style shading for common; sqlite-jdbc AND zstd-jni ride as
   STOCK jarJar nested libraries".
2. `scripts/release_check.py`: `check_store_natives_neoforge` → (a) flat `com/github/luben/`
   entries FORBIDDEN (the sqlite flat-entry rule, same message shape) AND zero shadow-jar entries
   under `STORE_NATIVE_ROOTS` (flat natives live at the roots, not under the class package — the
   converse of the deleted strip check); (b) exactly one `com.github.luben:zstd-jni` metadata
   entry, path under `META-INF/jarjar/`, present in the jar, `artifactVersion` matching the
   filename, range `[ver,major+1.0.0.0)` — the existing sqlite entry logic factored into a helper
   PARAMETRIZED per artifact (identifier, filename prefix, and the STOCK discriminators: sqlite =
   `org/sqlite/SQLiteDataSource.class` + `META-INF/versions/9/module-info.class` + the trimmed
   probe over `org/sqlite/native/`; zstd = `com/github/luben/zstd/Zstd.class` + a ROOT
   `module-info.class` + ≥ 1 native outside `ZSTD_NATIVE_DIRS`, e.g. `linux/ppc64le/`; no
   in-jar LICENSE pin for zstd — THIRD-PARTY-NOTICES already carries the BSD-2 text); (c) the zstd
   native MATRIX checked INSIDE the nested zstd jar, and the shadow-jar zstd matrix + strip checks
   REMOVED (a stock nested jar carries every platform by design — same treatment as nested
   sqlite); (d) the docstring's "zstd-jni stays flat-shaded" sentence and the stale prose at
   `:361-365`, `:475-476`, `:1090` replaced; (e) THE SELFTEST: reshape `_store_neoforge_entries`
   to synthesize zstd as a stock-like NESTED jar (`Zstd.class`, root `module-info.class`, the six
   matrix natives + one out-of-matrix native) with a second metadata entry `[1.5.7-3,2.0.0.0)`,
   keep `_store_paper_entries` flat, and add zstd NEGATIVES mirroring the sqlite ones
   (`:2198-2262`: flat `com/github/luben/` leak, flat root-native leak, missing/duplicate zstd
   entry, nested zstd missing a matrix native, trimmed, root module-info lost, wrong range). The vssJar pair pins are
   unchanged (the repackage is byte-preserving; nested entries ride along — the sqlite plan's
   §7 "vssJar identity drift" containment).
3. Docs: CLAUDE.md's NeoForge packaging sentences ("shaded EXCEPT sqlite" → "EXCEPT sqlite-jdbc
   and zstd-jni, both nested jarJar libraries — neoforge-jarjar-sqlite-plan.md, extended to
   zstd by this plan for issue #275"); `neoforge-jarjar-sqlite-plan.md` §2 gains a dated
   "SUPERSEDED for zstd: the hole fired (#275)" note; release-notes draft bullet
   (Bug Fixes, NeoForge): "Fixes the crash at startup alongside mods that bundle zstd-jni,
   such as Xaero's Maps Multiplayer+".
4. Gates per tree: `:neoforge:build` (contract tests + both jars) then
   `python3 scripts/release_check.py --selftest` and `release_check.py` against freshly built
   jars of ALL families (the vssJar pair pins need both brands), the NeoForge gametest smoke
   (`:neoforge:runGameTestServer`) on 1.21.1, and the DECISIVE live gate on 1.21.1's rig
   (`test-server/neoforge`, MC 1.21.1, C2ME; `run-neoforge` preserves extra jars): XMMP is
   `server_side: required` and its TOML needs only `kotlinforforge` + `yet_another_config_lib_v3`
   (Xaero maps optional) — stage XMMP 0.3.2+1.21.1-neoforge + KFF + YACL 3.8.2+1.21.1-neoforge.
   FIRST boot the CURRENT (flat) jar and confirm the `ResolutionException` (the BEFORE state — a
   green boot proves nothing otherwise); THEN the fixed jar: the layer resolves, `lsslod diag`
   shows `store=full` (nested sqlite still reads) and no zstd-unavailable latch, and a client join
   (the Fabric soak dummy) shows `cols zstd=` climbing — that exercises XMMP's SELECTED 1.5.7-9
   copy, the real mixed-copy runtime. If the boot is not reachable in the session, say so in the
   PR and ask the reporter to confirm — never claim the live gate.

### WI-B `#282` — hide LibsDisguises-disguised players from far-player rosters (Paper, all five trees)

1. `paper/.../compat/LibsDisguisesBridge.java` (new, Paper module), the `MeliusVanishBridge`
   idiom EXACTLY: a STATIC bridge with a swappable `classResolver` + `resetForTest()`; the presence
   probe is `Class.forName(name, false, LibsDisguisesBridge.class.getClassLoader())` — NO class
   initialization at probe time (`DisguiseAPI` drags `DisguiseUtilities.<clinit>`, heavy static
   state); the handle is `publicLookup().findStatic(cls, "isDisguised", methodType(boolean.class,
   org.bukkit.entity.Entity.class))` (verified against LibsDisguises master `DisguiseAPI.java:410`
   — record that in the javadoc as the drift reference; a later `isDisguised(Player, Entity)`
   per-viewer overload exists but is not bound in v1). `isDisguised(Entity)` catches
   **`Throwable`** (rethrowing `VirtualMachineError`) and wraps into an `IllegalStateException`:
   a broken install surfaces as `ExceptionInInitializerError`/`NoClassDefFoundError`, which the
   ladder's `catch (Exception)` — and the pump's — would NOT contain. The rung is GATED per pass
   on `Bukkit.getPluginManager().getPlugin("LibsDisguises")` being non-null AND `isEnabled()`
   (a plain lookup, no scheduler reference — `FoliaWiringContractTest` stays green): a loaded-
   but-disabled LibsDisguises rewrites no packets, so hiding everyone there protects nothing;
   ENABLED + throwing → HIDDEN with a once-per-JVM warn and NO latch-to-absent (an enabled-but-
   throwing LibsDisguises may still be rewriting spawns; latching would restore exactly the
   reported leak). One INFO line at RESOLVE time ("LibsDisguises detected — disguised players are
   hidden from far players"), never a new enable STEP: the enable plan is order-pinned
   (`LSSPaperPluginGlueTest` over `EnableSteps`).
2. `PaperFarPlayerSnapshots.hiddenFor`: one rung after the vanish metadata loop, inside the
   try: `if (LibsDisguisesBridge.isDisguised(bukkit)) return true;`. Nothing else changes —
   the ladder's containment and direction are inherited.
3. `plugin.yml`: `softdepend: [ViaVersion, LibsDisguises]`; `PluginYmlContractTest` gains a
   sibling `contains("LibsDisguises")` assert (the Via test pins containment and no hard
   `depend`); `release_check`'s `check_vss_pair_paper` wants equal line count + identity prefixes
   and the vssJar rewrite never touches that line — identical in both brands, no change.
   Paper's `PluginClassLoader` resolves other plugins' classes through the global loader group
   without a depend; `softdepend` buys load ORDER and silences the "not a depend/softdepend"
   warning, exactly the ViaVersion precedent.
4. Tests: a real-package-name stub `paper/src/test/java/me/libraryaddict/disguise/DisguiseAPI.java`
   (static set of disguised entities + a throwing switch; `.gitignore` does not ignore `me/`);
   `:paper:test` is ONE JVM with no `forkEvery`, so the stub resolves by default and bridge state
   latches across classes — a `@BeforeEach` resets the bridge AND the stub's switches. The plugin-
   manager gate is a seam too (a `BooleanSupplier` defaulting to the Bukkit lookup; tests inject
   enabled/disabled). `PaperFarPlayerSnapshotsTest` gains `aDisguisedPlayerIsHidden`,
   `aThrowingDisguiseReadFailsHIDDENNotOpen`, `aDisabledLibsDisguisesIsIgnored`,
   `absentLibsDisguisesLeavesTheLadderUnchanged` (resolver seam answering
   `ClassNotFoundException`) and keeps the ladder-ORDER pins (permission → vanish → disguise);
   `LibsDisguisesBridgeTest` pins the MethodType against the stub (the verified upstream signature
   is recorded in the javadoc) and the once-warn. `FoliaWiringContractTest`'s constant-pool scan
   stays green (no scheduler references).
5. Docs: CLAUDE.md far-players sentence (Paper privacy ladder: "… `vanished` metadata, and
   LibsDisguises disguises via the reflective `LibsDisguisesBridge`"); README far-player
   paragraph (disguised players are hidden on Paper); release-notes bullet (Bug Fixes, Paper):
   "Players disguised with LibsDisguises no longer appear as far players".
6. Gates per tree: `:paper:test`; the plugin.yml/VSS pins inside `release_check`; the existing
   Paper live rig only if convenient (LibsDisguises is a paid plugin — the reporter's
   confirmation is the live gate; say so in the reply).

### WI-C — issue replies (after the PRs are green; short, plain, no em dashes)

- #275: root cause in two sentences (flat-shaded zstd exports the package; XMMP nests its
  own copy), the fix (zstd now rides as a nested jarJar library like sqlite, FML dedupes),
  which version carries it, and a request to confirm on their stack.
- #282: root cause (the client draws a far-player proxy exactly when it sees no player
  entity for that UUID — a disguise removes that entity), the fix (Paper/Folia servers now
  leave disguised players out of far-player rosters, so the proxy no longer draws; inside
  normal range nothing changes — the disguise already worked there; beyond tracking range a
  disguised player is now absent entirely, no mob is drawn), hidden from ALL viewers, targeted
  disguises included (per-viewer fidelity is a later step), the version, and that it is
  untested against the paid plugin here — their confirmation is the gate.

## 3. Per-line matrix and phasing

| WI | 26.2 | 26.1 | 1.21.11 | 1.21.10 | 1.21.1 |
|---|---|---|---|---|---|
| A zstd jarJar (build + release_check + docs) | ✔ | ✔ | ✔ | ✔ | ✔ + gametest smoke + LIVE XMMP boot |
| B LibsDisguises rung + softdepend + tests | ✔ | ✔ | ✔ | ✔ | ✔ |
| C replies | after all five PRs are green | | | | |

Phasing: main first (one branch, both WIs, gates), then the four ports via the change-core
applier with SINGLE-LINE cores (the `shade 'com.github.luben:zstd-jni:1.5.7-3'` row, the
`jarJarStore "org.xerial…"` row, the `doLast` body, the keep block — byte-identical on all
five; 1.21.1 has two `additionalRuntimeClasspath` rows right after the shade row and 1.21.10
lacks the `net/caffeinemc/**` exclude and the `sodiumNeoGolden` row, so multi-line hunks with
trailing context would miss); WI-B's `hiddenFor` core is line-invariant. PRs against
each line's live branch (`main`, `support/mc26.1-v0.14`, `support/mc1.21.11-v0.14`,
`support/mc1.21.10`, `support/mc1.21.1`). 2-Opus review per branch after implementation.

## 4. Risks and containments

- **FML picks XMMP's 1.5.7-9 over our 1.5.7-3**: same API line, a patch-level newer build —
  and it is what happens for every other zstd-nesting pair already; a future MAJOR nests →
  hard, attributable version conflict by the range rule. Our wire codec uses the stable
  `Zstd.compress/decompress` surface only.
- **Jar growth**: the stock zstd-jni jar is 7.4 MB (all platforms' natives) against the ~2.4 MB
  of flat classes + six natives it replaces → about +5 MB on the NeoForge jar; accepted (the
  stock-bytes rule).
- **Module boundary**: `lss` reads `com.github.luben.zstd_jni` the same way it reads
  `org.xerial.sqlitejdbc` (automatic modules in one layer read each other) — proven live for
  sqlite; the live gate proves it for zstd.
- **Plugin classloader visibility** for the bridge: `softdepend` orders LibsDisguises before
  LSS; a missing class reads as absent → visible, the pre-fix behavior, never a throw at enable;
  a loaded-but-DISABLED LibsDisguises is skipped by the plugin-manager gate.
- **API drift in LibsDisguises**: resolution failure → absent + one warn (the bridge never
  makes `hiddenFor` throw at resolve time); an invoke throw (any `Throwable`) → HIDDEN via the
  ladder's catch (fail-safe direction, one warn, no latch). Blast radius: every far player
  hidden while an ENABLED LibsDisguises keeps throwing — accepted, that is the state in which
  it may still be rewriting spawns.
- **A third mod shading zstd flat** re-opens #275 against the required zstd module — not ours
  to fix; the reply names it.
- **Rollback**: revert per tree; packaging cannot be config-gated (sqlite plan §7).

## 5. Decisions log

- 2026-09-06: v1 drafted. WI-A reuses the sqlite jarJar decisions unchanged (stock bytes,
  major-bounded range, hand-rolled metadata, all trees, atomic per tree). WI-B is server-side
  only — the client belt was dropped on the disguise-UUID fact. Per-viewer disguise fidelity
  deliberately not attempted.

## 6. Review record (2026-09-06, 2 Fable, read-only)

- Fable A (WI-A): diagnosis and mechanism verified against the jar, `common`'s `compileOnly`
  deps, the vssJar byte-copy and XMMP's own build (stock `com.github.luben:zstd-jni:1.5.7-9`,
  range `[1.5.7-9,)`; XMMP is `server_side: required`, deps KFF + YACL only). MAJOR: the
  release_check SELFTEST synthesizes flat zstd and reds CI on the first push → §2 WI-A 2(e).
  MINORs folded: per-artifact stock discriminators (root `module-info`, no LICENSE pin), the
  flat-native converse rule, the BEFORE-state reproduction on the rig, the deleted keep list,
  corrected line refs and the +5 MB growth, the Provider-based resolution + filename assert,
  the stale-prose sweep, single-line port cores.
- Fable B (WI-B): diagnosis verified in LibsDisguises source (mob AND player disguises spawn
  under disguise UUIDs; `getPlayerByUUID` walks entities, not the tab list). MAJORs folded:
  `Throwable` containment + no-init probe; the plugin-manager ENABLED gate with no latch; the
  static reset seam + INFO at resolve (the enable plan is order-pinned). MINORs folded: the
  per-viewer seam exists (hide-for-all is v1, not "ruled out"), Folia support stated, the
  `contains` pins, `hiddenFor` is byte-identical on all five, the reply wording.

## 7. Execution record (2026-09-06)

Executed on main (`fix/issues-275-282`, 6630bc3b) and ported literally to the four lines
(the change-core applier; every replacement asserted exactly one old-core occurrence —
nofind=0 on every line).

| Tree | Branch | `:paper:test` | `:neoforge:build` | release_check selftest / jars | extra |
|---|---|---|---|---|---|
| 26.2 | `fix/issues-275-282` | 503/0 | ✔ | 104 / OK (all six families) | NeoForge gametest smoke 8/8 |
| 26.1 | `fix/issues-275-282-mc26.1` | ✔ | ✔ | 104 / OK | |
| 1.21.11 | `fix/issues-275-282-mc1.21.11` | ✔ | ✔ | 104 / OK | |
| 1.21.10 | `fix/issues-275-282-mc1.21.10` | ✔ | ✔ | 104 / OK | |
| 1.21.1 | `fix/issues-275-282-mc1.21.1` | ✔ | ✔ | 104 / OK | gametest smoke 8/8 + the LIVE gate below |

Deviations from §2, all deliberate:
- WI-B's bridge lives in the flat `dev.vox.lss.paper` package (`LibsDisguisesBridge.java`
  beside `PaperFarPlayerSnapshots`), not a `compat` subpackage — the Paper module keeps
  everything in one package (`PaperSoakBridge`, `FoliaSupport`), and the package-private
  test seams need the tests beside it.
- The class-INVISIBLE flavor (`ClassNotFoundException`) warns once as well: it is only
  reachable with the plugin ENABLED (the gate runs before resolution), so an invisible API
  is a softdepend/classloader problem worth one line — not `MeliusVanishBridge`'s quiet
  "no mod installed" case, which here never resolves at all.
- Every resolve-time failure flavor (invisible / load failure / drift) latches absent with
  one warn; invoke throws never latch (WI-B.1 as written).
- The release-notes NeoForge bullet appears only on the lines that SHIP NeoForge (26.2,
  26.1, 1.21.1 — `LINE_SHIP_NEOFORGE`); the Paper bullet on all five.

Live gate (1.21.1 rig `test-server/neoforge`: NeoForge 21.1.248 + C2ME 0.3.0+alpha.0.93;
XMMP 0.3.2+1.21.1-neoforge + kotlinforforge 5.12.0 + YACL 3.8.2+1.21.1-neoforge staged):
- BEFORE (the v0.14.0-shape flat jar — 39 flat `com/github/luben` entries): exits in ~4 s
  with `ResolutionException: Modules com.github.luben.zstd_jni and lss export package
  com.github.luben.zstd to module c2me_client_uncapvd` — the issue's exact shape.
- AFTER (the fixed jar — 0 flat entries, 2 nested): RCON up in ~12 s; FML selects XMMP's
  `zstd-jni-1.5.7-9.jar` (jarjar logs only the SELECTED candidate per identifier — our
  1.5.7-3 loses on version, as §4 predicted); `store=full`, the store backfill deposited
  13128 columns through the selected copy with 0 errors. The Fabric soak dummy CANNOT
  join a server carrying XMMP ("You are trying to connect to a server that is running
  NeoForge, but you are not" — the XMMP/KFF/YACL stack registers client-required
  content), so the wire leg ran on the solo boot.
- SOLO (the fixed jar, C2ME only): `Found library file "zstd-jni-1.5.7-3.jar" [parent:
  lod-server-support-neoforge.jar, locator: jarinjar]` — our own nested copy is discovered
  and used; the dummy joined in ~10 s and `cols zstd=` climbed 17116 → 17802 (raw=0) over
  20 s with no zstd/store warnings.
- WI-B has no live gate here (LibsDisguises is a paid plugin) — the reporter's
  confirmation is the gate, and the reply says so.

## 8. Implementation review fold (2026-09-06, 2 Opus — A packaging, B bridge)

Both verdicts **ship, no MAJORs**. Reviewer A mutation-tested the NeoForge gate against
the REAL built jar (nine regression shapes incl. the exact pre-fix flat layout and a
deleted `jarJarStore` row — every one reds with its pinned message), reproduced the
metadata task standalone (re-runs on a version bump, `UP-TO-DATE` otherwise), and
confirmed the nested jars are byte-identical to the Maven artifacts and the vssJar
carries `META-INF/jarjar/` verbatim. Reviewer B verified the bound signature and plugin
name against upstream `master` (`plugin/src/main/java/.../DisguiseAPI.java:410`,
`name: LibsDisguises`; `DisguiseUtilities.disguises` is a `ConcurrentHashMap`), traced
`hidden()` to the wire gate in `FarPlayerBroadcastService.isVisible`, and confirmed the
`Throwable`→`IllegalStateException` wrap is load-bearing against BOTH pump belts'
`catch (Exception)`.

Folded (all five trees, identical cores):
- A-m1/B-m4: the README `farPlayers` row's missing sentence break ("hidden. On Paper, …").
- A-m2 (+ the support-plan nit): the dated amendment brackets in `v0.11.0-progress.md`
  and `neoforge-support-plan.md` now say shading stands for common ONLY.
- A-n2: the two sqlite selftest expects tightened to the `nested sqlite jar …` messages
  (they had become ambiguous between libraries).
- A-n3/n4: `generateJarJarMetadata` guards the empty configuration BEFORE the collect and
  rejects a non-numeric leading version component; release_check reports the same shape
  as a problem line instead of a traceback.
- A-n5/n6: "stock by PROXY" clause in `_check_nested_lib`; the notice comment above the
  shadowJar license block; the port-only double blank line.
- B-m1: `PLUGIN_NAME` is test-pinned — the exact-name lookup + enabled check is the
  package-private `enabledPlugin(PluginManager)`, pinned with a Mockito plugin manager
  (exact name / disabled / absent / null manager).
- B-m2: hot-reload identity — the gate now hands over the running plugin INSTANCE and the
  bridge remembers it at resolve time (`boundPlugin`); a different instance (a PlugMan-
  style single-plugin reload with a fresh classloader) re-resolves from 0 instead of
  answering "not disguised" for everyone off the orphaned handle. Pinned by
  `aHotReloadedPluginInstanceReResolves`.
- B-m3: CLAUDE.md wording — an ABSENT plugin is skipped silently (never resolved); only
  enabled-but-invisible/unloadable/drifted warns once.
- B nits: `present()` documented as a test seam; the seams' outside-monitor reads
  commented; the drift reference carries the upstream path; a throwing plugin-manager
  lookup documented (propagates, the ladder's catch answers HIDDEN).

Not folded, deliberate: A-n7 (the "1 … entries" plural — the selftest pins the
substring); B's optional `far_players.privacy_errors` counter (scope creep — the ladder's
once-warn is a pre-existing decision, the far-player diag line going to zero is the
admin's signal); the Folia label on the Paper release bullet (generic plugin logic, not
Folia mechanics).

Port-review addendum (26.1 pair, 2026-09-06 — both ship, no MAJORs): the change-core
applier had left FOUR whitespace-only residues on every port (a joined string-literal
line in `PaperFarPlayerSnapshots.hiddenFor`, and stray double blank lines in the
snapshots test, before `shadowJar {`, and before the selftest's `with tempfile`) — all
removed; the four ports' code diffs (build.gradle, release_check, paper/) now carry ZERO
residue against 26.2's beyond pre-existing line-flavor context. The 26.1 CLAUDE.md
banner's stale `LINE_SHIP_NEOFORGE=false` / `support/mc26.1-v0.13` claim (pre-existing,
contradicted by line.env since v0.13.1) was refreshed in the same fold. Reviewer A also
re-proved "stock" on 26.1's own jar: both nested jars sha256-identical to the Maven
artifacts, in the LSS and the VSS jar alike.
