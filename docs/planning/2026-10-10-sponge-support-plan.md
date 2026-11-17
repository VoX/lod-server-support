# Sponge server support: plan (2026-10-10, v1.1)

This version folds the 4-Opus + 1-Fable review (§10) and the S0 spike facts (§4).

It supersedes external PR #314 (StealWonders / EmpireWar fork). That PR proved the
approach live on SpongeVanilla 1.21.11-18.0.0-RC0, with a real Fabric + Voxy client and
106k sections served. It cannot merge as-is, for these reasons:

- It targets a released branch.
- It lands on one line only, so it splits that line off from the other four.
- It reds catalog CI.
- It pins none of the twins it depends on.
- It has no release, release-gate or VSS machinery.
- It leaks vanished players to far viewers.
- It builds against snapshot plugins.
- Its README has two factual errors.

This plan redoes the work to repo conventions on all five lines, and credits the
contributor (§11).

## 0. Scope decisions

### 0.1 What ships

`sponge/` is a **server-only** plugin module for SpongeVanilla. SpongeVanilla exists on
all five lines (dl-api, checked 2026-10-10). The **pin policy** is: use the recommended
build where one exists, otherwise the newest RC. The compile API pin is a release where
one exists, otherwise a *timestamped* snapshot, never a floating `-SNAPSHOT`.

| Line | Server pin | Recommended? | Compile API | Java (build/run) |
|---|---|---|---|---|
| 26.2 | `26.2-20.0.0-RC2743` | no (RC) | `20.0.0-20261005.184902-4` | 25 |
| 26.1 | `26.1.2-19.0.0-RC2742` | no (RC) | `19.0.0-20261005.184029-8` | 25 |
| 1.21.11 | newest `1.21.11-18.0.0-RC*` | no (RC) | `18.0.0-20261002.154539-15` | 21 |
| 1.21.10 | `1.21.10-17.0.0` | **yes** | `17.0.0` | 21 |
| 1.21.1 | `1.21.1-12.0.3` | **yes** | `12.0.0` | 21 |

Out of scope:
- A Sponge client. LOD clients stay Fabric or NeoForge; the wire is unchanged, so any
  LSS/VSS client joins a Sponge server exactly as it joins Paper.
- SpongeForge and **SpongeNeo**. §1.9 adds a guard and an install rule.

### 0.2 Tier and labels

- **Support tier:** best-effort on every line, on the same axis as NeoForge. The cut
  protocol of neoforge-support-plan §6.2 applies.
- **Experimental at launch on every line.** This is the correctness-confidence axis. The
  exit criterion is per line and needs all three of:
  1. a recommended SpongeVanilla build is pinned;
  2. the §5.3 live smoke and the §5.4 minimal soak are green against that build;
  3. one release cycle passes with no Sponge-attributed regression.

  1.21.1 and 1.21.10 can therefore graduate at the next release, and the RC lines when
  Sponge recommends a build. The label appears in:
  - the Modrinth version name (a new convention for Sponge; Folia carries its label in
    the notes only);
  - the release notes;
  - the README.
- **Wire compatibility is never tiered** (the §5.1 parity tests).

### 0.3 Per-line ship flag

Shipping is gated by `LINE_SHIP_SPONGE` in `.github/line.env`, the NeoForge precedent.
`build.yml` builds and tests the module on every line regardless of the flag. A line
flips to `true` only after its §5.3 smoke passes on the **frozen 0.18.0 candidate**
(§3). A line whose smoke fails ships `false` and gets a dated cut entry.

A flag flip touches four files, and each is jar-neutral:
- `line.env`;
- the `release_check` derivation on 26.2, or the hand mirror on the support lines;
- the `ReleaseWorkflowContractTest` pin;
- `config/compatibility/line.json`.

A `false` line ships **no** LSS Sponge artifact and **no** VSS Sponge row.

**Five-line decision (recorded).** The target is all five lines. If rig time runs short,
the minimum shippable set is 26.2 + 1.21.1 + 1.21.10: the full-tier line plus both
recommended-build lines. Any line left at `false` is shipped later through the §2.8
recovery/backfill path.

### 0.4 Release

The program ends with five release candidates ready:
- PRs into `main` and the four `support/**` branches, CI-green;
- a re-pinned catalog;
- frozen, validated jars;
- `0.18.0` notes;
- a VSS plan.

Merging, tagging, publishing and the local VSS publish are irreversible, and each needs
the user's explicit go. The version is 0.18.0, because a new platform is a minor bump.

The other unmerged branches (`fix/far-player-light-floor`, the xaero-scatter #277–281
ports, Codex #289–293) stay out of this release. Anything merged to a line before the
0.18.0 tag forces a re-pin, so merge order is: Sponge first, everything else after the
tag.

## 1. Architecture

### 1.1 Module layout

`sponge/` is a peer of `paper/` and `neoforge/`, and the **first server-only consumer of
xplat**. Kinds refer to the line classification (`config/lines/classification.json`).

| Path | Role | Kind |
|---|---|---|
| `sponge/build.gradle` | SpongeGradle `2.3.0` + VanillaGradle (per-line version, §4) + shadow. Java release from `line.env` `LINE_JAVA_VERSION` (the 26.2 paper R2-5 read shape); Sponge versions from `gradle.properties` | **identical** |
| `sponge/src/main/java/dev/vox/lss/sponge/LSSSpongePlugin.java` | entrypoint: Brand → LoaderServices → implementation guard (§1.9) → channels, commands, permissions, lifecycle | identical |
| `.../sponge/SpongeChannels.java` | the 13 `lss:*` channels over raw data channels; codec bytes exactly; no registered-channel pre-check (the Fabric send contract — as-built §10); a quiet per-channel `ChannelExceptionHandler` | identical |
| `.../sponge/SpongePermissions.java` | `PermissionDescription`s registered at `StartingEngineEvent` (§1.6) | identical |
| `.../sponge/SpongeCommands.java` | shared `/lsslod` brigadier tree on a private dispatcher behind `Command.Raw` (the PR #314 design) | identical |
| `.../sponge/SpongeLineSurface.java` | **the one per-line flavor file**: command-source permission elevation (int op level on 1.21.1–1.21.10, `LevelBasedPermissionSet` on 1.21.11+). As built it holds no test literals: the tests read `line.env`/`gradle.properties` | adapted |
| `.../platform/SpongeLoaderServices.java` | the `LoaderServices` impl; facts come from the **injected** `PluginContainer`, never `plugin("lodserversupport")` (a VSS jar has another id) | identical |
| `.../networking/server/LSSServerNetworking.java` | same-FQN twin (static holder, lifecycle, payload adapters) | identical (the NeoForge twin is identical on all five lines) |
| `.../mixin/ChunkSaveDataHook.java` | dirty source; that line's target (`copyOf`; `ChunkSerializer.write` on 1.21.1) | adapted |
| `.../mixin/ChunkLoadHook.java` | FULL-status hook, **descriptor-selected** (§1.4) | identical |
| `.../mixin/ServerTickHook.java` | `MinecraftServer.tickServer` TAIL | identical |
| `.../compat/ScopedCarrier.java` | same-FQN twin, byte-identical to that line's fabric/neoforge copy | adapted |
| `sponge/src/main/resources/lss.sponge.mixins.json` | **`compatibilityLevel: JAVA_21` on every line** (Sponge bundles Mixin 0.8.7, whose enum ends at JAVA_21; a JAVA_25 literal fails to parse a required config; S0-proven on 26.2) | identical |
| `sponge/src/main/resources/lss-sponge.accesswidener` | header `named` (SpongeVanilla's AW reader has no namespace check; S0-proven at runtime on API 12 and 20) | identical |
| `lss-brand.properties`, `META-INF/services/...LoaderServices` | brand + ServiceLoader fallback | identical |
| `sponge/src/test/java/...` | §5.1 | identical on every line; per-line values come from `line.env`/`gradle.properties` (as built) |
| `sponge/src/smoke/java/...` | dev-only boot-smoke plugin (§5.2) | identical |
| `sponge/src/soak/java/...` | dev-only soak driver twin (§5.4) | identical |

**Root and properties:**
- **Identical on all five lines:**
  - `settings.gradle`: `include ':sponge'`, the Sponge plugin repository, and a
    `pluginManagement.plugins` block resolving the VanillaGradle version from
    `gradle.properties`.
  - root `build.gradle`: `vssJars`, and `check` → `:common:test`.
- **Adapted, `gradle.properties`:** `sponge_api_version`, `spongevanilla_version`,
  `vanillagradle_version`, `sponge_api_dependency`.
  - The VanillaGradle version is per line because 0.3.2 requires Gradle to *run* on
    Java 25 (S0), while the 1.21.x lines run Gradle on 21 and use 0.2.2.
  - There is **no** separate Sponge MC property. Sponge's MC is `minecraft_version` on
    every line (26.1's is already `26.1.2`), and `LINE_MC_SPONGE == minecraft_version`
    is contract-pinned.

### 1.2 The xplat server half: a derived boundary

`sponge/build.gradle` compiles `../xplat/src/main/java` with excludes derived, not
hand-listed:

- `networking/client/**`, `config/menu/**`, `config/LSSClientConfig.java`, `api/**`,
  `benchmark/**`, `seed/ClientWorldSeed.java`
- the client compat classes:
  - `compat/Xaero*`, `compat/Voxy*`, `compat/DhVoxyBridge.java`, `compat/ModCompat.java`
  - **every class named in the `client` array of that line's
    `fabric/src/main/resources/lss.mixins.json`**, filtered to those present in xplat
- `trace/**` + `mixin/trace/**`, but only if no server-half class references them.
  Otherwise they compile inert, because the tracer activates only from Fabric's `LSSMod`.
  S1 records which.

Parsing the client array at configuration time keeps the file identical across lines:
`AccessorEntityRenderDispatcher` exists on some lines and not others.

Guards (all identical):
1. **`SpongeServerHalfContractTest`** scans the constant pool of every compiled class. It
   forbids:
   - `dev/vox/lss/networking/client/`, `config/menu/`, `com/mojang/blaze3d/`;
   - `net/fabricmc/`, `net/neoforged/`.

   (`net/minecraft/client/` is a belt: the compile classpath is server-only.)
2. **Boundary completeness.** Every xplat file is either compiled or excluded. The test
   fails on any exclude pattern that matches nothing. Also:
   - `lss.sponge.mixins.json` `mixins` must equal Fabric's `mixins` array
     **+ `ChunkLoadHook` + `ServerTickHook`**, in both directions.
   - A new server accessor added on Fabric without its Sponge counterpart reds here.
3. **`XplatLoaderPurityTest` split.** `org.spongepowered.asm` is allowed in `mixin/`;
   `org.spongepowered.(api|plugin|configurate)` is forbidden everywhere in xplat.

### 1.3 Lifecycle, tick, threading (verified against SpongeVanilla 1.21.1 bytecode)

| Event | Sponge source | Parity |
|---|---|---|
| start | `StartedEngineEvent<Server>` | Fabric `SERVER_STARTED` |
| tick | `ServerTickHook` at `tickServer` TAIL | Fabric `END_SERVER_TICK` (same TAIL; includes the paused-empty early-return skip) |
| stop | `StoppingEngineEvent<Server>`: stop receiving, then `onServerStopping` | fires at `stopServer` HEAD, before the world save = Fabric `SERVER_STOPPING` |
| disconnect | `ServerSideConnectionEvent.Disconnect` (0.23.0: `ServerSideConnectionEvent.Leave`, see the [0.23.0 plan](2026-11-13-v0.23.0-plan.md) §11) | main thread, after player removal = NeoForge `PlayerLoggedOut`; sweeps on never-registered UUIDs are no-ops |

Sponge's sync scheduler ticks at `tickServer` **HEAD**, before world work, which is why
the PR's scheduler task is replaced. Sponge's own `tickServer` mixins (scheduler HEAD,
tracker `@WrapMethod`, autosave constant) coexist; the spike proves it on API 12 and 20.

Raw-channel handlers already hop to the main thread: `handleCustomPayload` HEAD does
`server.execute` before dispatch. The adapter therefore does not hop; a Tier-1 note
records the fact, and the boot smoke logs the handler thread once.

`sendTo` ends in `Connection.send`, so it is thread-safe off-main. It checks only the
play phase, never the peer's registered channels. The adapter installs a quiet exception
handler and does **not** pre-check `isSupportedBy` (as-built correction, 2026-10-11):
Fabric's client sends its handshake on `JOIN`, before its own `minecraft:register`, so a
pre-check dropped every SessionConfig reply and the first Sponge soak settled on protocol
19. This matches Fabric's `ServerPlayNetworking.send`, which never consults `canSend`.

### 1.4 Chunk hooks

**Save.** The same-FQN twin, with that line's target and `require = 0`. It is pinned like
`SaveHookContractTest` (target, require, at), not byte-equal: the fabric and neoforge
copies already differ. On 1.21.1, `ChunkSerializer.write` RETURN coexists with Sponge's
own injection and redirect there.

**Load.** `ChunkLoadHook` selects the FULL lambda by **descriptor**:
`method = "*(L.../ChunkAccess;L.../WorldGenContext;L.../GenerationChunkHolder;)L.../ChunkAccess;"`
with `require = 1, allow = 1`.

| Version | Lambda name | Descriptor |
|---|---|---|
| 26.2 | `lambda$full$0` | identical |
| 1.21.1 | `lambda$full$2` | identical |

S0 proved the descriptor-selected hook fires on both API 12 and API 20. So:
- the file is identical on every line;
- there is no per-line name to get wrong (fabric-api's 1.21.x names are intermediary,
  which a name copy would have missed);
- drift fails the boot loudly instead of silently no-oping.

Strict `require` is acceptable here because the plugin is pinned to one MC version. It
is a deliberate deviation from the save hook's `require = 0`, recorded in the javadoc.

**Contract.** `SpongeMixinTargetContractTest` opens the line's VanillaGradle MC classes
and asserts:
- exactly one `ChunkStatusTasks` method matches the descriptor;
- its body constructs a `LevelChunk`;
- `tickServer(BooleanSupplier)V` exists;
- the save target exists.

**`newlyGenerated` derivation.** This feeds `ServerReceiverGlue.onChunkLoaded`'s
refusal to seed generated chunks (the 2026-09-05 fold). It is pinned in S1 against
Fabric's own semantics on that line:
- 26.x fabric-api passes the flag;
- 1.21.x fabric-api uses `CHUNK_GENERATE` → `onChunkGenerated`.

The PR's `!(proto instanceof ImposterProtoChunk)` is accepted only if it matches Fabric on
all five lines. Otherwise the per-line form goes into `SpongeLineSurface`. The boot smoke
asserts both flavors fire (§5.2).

**Fallback, recorded and not taken:** Sponge's `ChunkEvent.Blocks.Load` (posted inside
the FULL lambda) plus `ChunkEvent.Generated`. It is pure API, but needs an ordering proof.

### 1.5 Far-player vanish: a seam

In xplat:

```java
interface VanishVisibility {
    boolean present();
    boolean isVanished(MinecraftServer s, UUID target);
    boolean canSee(MinecraftServer s, ServerPlayer observer, UUID target);
}

// LoaderServices
default VanishVisibility vanishVisibility() { return MeliusVanishBridge.VISIBILITY; }
```

`RequestProcessingService` swaps its three static calls for the seam's. The `present()`
short-circuit and the per-tick memo stay exactly as they are, so fabric/neoforge
behavior is unchanged (pinned by the existing far-player suites).

The Sponge impl:
- `present()` is `true`;
- `isVanished` reads `Keys.VANISH_STATE` → `invisible()` (it exists in API 12 and 20);
- `canSee` is `false`, because Sponge vanish is global;
- a throw hides the target, with a once-warn.

It is tested through an injected reader in JUnit (registry-backed `Keys` cannot
initialize without a game), and live in the §5.5 floor.

### 1.6 Permissions

Descriptions are registered at `StartingEngineEvent<Server>`, through
`server.serviceProvider().permissionService()`, after any replacement service such as
LuckPerms is final. Both brand spellings are registered:

| Node | `defaultValue` |
|---|---|
| `lss.use`, `vss.use` | TRUE |
| `lss.farplayers.hidden`, `vss.farplayers.hidden` | FALSE |
| `lss.admin`, `vss.admin` | none (UNDEFINED → Sponge's op-level fallback = Paper's `default: op`) |

S0 proved the default service honors the defaults, and that `hidden=FALSE` does not hide
ops. `checkPermission` uses `permissionValue()` (never `hasPermission()`) and maps
UNDEFINED to the caller's default. `LoaderPermissionSeamContractTest` gains the Sponge
call sites.

### 1.7 Config, store, brand, diagnostics

- **Config:** `configDir()` = `@ConfigDir(sharedRoot = true)`, so the file is
  `config/lss-server-config.yaml` or `config/vss-server-config.yaml`, with
  cross-brand adoption.
  - The per-plugin directory was rejected because the plugin ids differ per brand.
  - The settings platform is `mod`: Sponge runs the xplat server path, including the
    backfill.
  - The settings reference section title becomes "Fabric / NeoForge / Sponge server".
- **World layout:** Sponge's dimension layout equals vanilla's on API 12 and 20.
  - **Accepted v1 limitation:** worlds a Sponge plugin loads *after* server start get an
    UNKNOWN region dir, because the map is built at `SERVER_STARTED`. Their header rung,
    summaries, backfill and store sweep degrade toward serving, which is safe. This is
    recorded in troubleshooting.
  - Making the resolver lazy is an xplat follow-up, not in this release.
- **`DiagnosticVersions.LOADER` is `sponge-<implementation version>`** (the Paper
  `paper-` precedent), so `/lsslod diag` distinguishes Sponge from Fabric.

### 1.8 Jar shape

- **shadowJar:** common (SQLite capsule) plus `zstd-jni`, flat and native-stripped,
  Paper's layout. Slf4j, gson and fastutil are provided by Sponge.
- **DH codec excluded, as on Paper:** `dev/vox/lss/common/dh/**`, the private XZ and its
  license. A server needs no DH decoder.
- **Manifest:** `MixinConfigs` and `Access-Widener`. The plain `jar` task is disabled.
- **CI name:** `lod-server-support-sponge-<ver>+<minecraft_version>.jar`.
- **`vssJar`:** a byte-copy rewriting only `META-INF/sponge_plugins.json` (id
  `voxyserverside`, name, description, links) and `lss-brand.properties`, with a loud
  shape check, `finalizedBy` shadowJar.
  - The plugin id is display/local scope only. SpongeVanilla's loader uses the metadata
    entrypoint and never checks `@Plugin`'s value (verified), and the `lss:*` wire is
    unchanged.
- **Dependency declaration:** `spongeapi` is declared with a version **range admitting
  the line's snapshot API** (RC builds report `N.0.0-SNAPSHOT`, which Maven orders below
  `N.0.0`). S1 verifies it is accepted on an RC server, and on the recommended build.
- **Dev-only outputs:** the smoke and soak jars are written outside `build/libs`, with
  names not starting `lod-server-support-sponge`.

### 1.9 Implementation guard (SpongeNeo/SpongeForge)

If `Sponge.platform().container(IMPLEMENTATION).metadata().id()` is not `spongevanilla`,
the plugin logs one ERROR and stays disabled. Otherwise, on SpongeNeo, the LSS NeoForge
mod and the Sponge plugin would run two services on one server. The README states:
"SpongeNeo: use the NeoForge jar on lines that ship one; SpongeForge is not supported."
(As built: SpongeForge runs on MinecraftForge, for which LSS has no jar.)

## 2. Release, CI and publishing machinery

### 2.1 `line.env` (adapted)

| Key | Value |
|---|---|
| `LINE_SHIP_SPONGE` | `true`/`false` (§0.3) |
| `LINE_MC_SPONGE` | `26.2` · `26.1.2` · `1.21.11` · `1.21.10` · `1.21.1` (= `minecraft_version`) |
| `LINE_GAME_VERSIONS_SPONGE` | the same single version |
| `LINE_SPONGE_NAME` | `Sponge (MC <v>, experimental)` |

Contract pins:
- the resolved name is ≤ 64 characters;
- `version_number` is ≤ 32 characters;
- `lineEnvIsLockedToTheBuildToolchain` adds SPONGE to its loop, and
  `LINE_MC_SPONGE == minecraft_version`.

### 2.2 `release.yml` (adapted; the same hunks on all five lines, literal applier)

- **"Expand line lists":** `expand GV_SPONGE`; the Sponge glob inside its own
  `LINE_SHIP_SPONGE` `if`/`fi` in `RELEASE_FILES`.
- **"Build Sponge + contract tests"** (`:sponge:build`), gated, after NeoForge.
- **"Upload Sponge to Modrinth"**, **last**:
  - `lKiXKLvv`, `loaders: sponge`, version `v${MOD_VERSION}+sponge+mc${LINE_MC_SPONGE}`,
    name `v${MOD_VERSION} - ${LINE_SPONGE_NAME}`;
  - `modrinth-featured: false`;
  - `version-type: release` (user can choose `beta`; recorded as an open choice);
  - no inline comments on value lines (the MC-token test strips only full-line
    comments).

`ReleaseWorkflowContractTest` gains Sponge twins of the NeoForge pins:
- gated per line;
- `RELEASE_FILES` bounded within the `if`;
- the build-step `if:`;
- the upload step block (`loaders: sponge`, version template);
- step order after NeoForge;
- the Modrinth-id count goes 3 → 4;
- the name and number caps;
- dispatch-never-publishes;
- the toolchain loop.

On 26.2, `SHIP_SPONGE` is derived from line.env (R2-5). The support lines hand-mirror it,
with the existing two-file coupling pin (mirror vs line.env).

### 2.3 `build.yml` (adapted)

The new steps go in the existing build job (so the Java-version occurrence contract holds)
and before the "Release-jar safety gate" step:
1. `:sponge:build` (Tier 1, contracts, shadowJar, vssJar);
2. **"Sponge boot smoke (retry once on flake)"** (§5.2), with an `actions/cache` of the
   pinned SpongeVanilla jar and its first-boot `libraries/` closure, keyed on
   `spongevanilla_version`.

Sponge reports and test results join the failure evidence, and two artifacts are
uploaded.

### 2.4 `scripts/release_check.py` (adapted)

Sponge branches go **inside the existing generic gates**, not parallel to them:

| Gate | Change |
|---|---|
| `_declared_mixin_configs` | reads the manifest `MixinConfigs` for `sponge` |
| `PRIMARY_MIXIN_CONFIG` | `sponge: lss.sponge.mixins.json` |
| `check_mixin_configs` | applied (incl. the required:false rule for non-primary configs and `FORBIDDEN_MIXIN_CONFIG`) |
| `check_dependency_allowlist` | `SPONGE_DEPENDENCY_ALLOWLIST = {"spongeapi"}` |
| per-family parity | `check_yaml_parser(jar,"sponge")`, `check_third_party_notices`, `check_brand_properties` (LSS + VSS loops), `sqlite_capsule.check` |
| DH | `check_sponge_dh_absent` (the Paper rule; `check_dh_codec`'s loop excludes sponge like paper) |

New checks:
- `check_sponge_jar`:
  - `sponge_plugins.json` parses: id `lodserversupport`, expanded version, the
    entrypoint class present, the `spongeapi` dependency;
  - no `fabric.mod.json` or TOML;
  - manifest `MixinConfigs`/`Access-Widener` resolve, and every config entry is a class
    in the jar.
- `check_store_natives_sponge` (Paper rules).
- `check_vss_sponge_identity` + `check_vss_pair_sponge` (a JSON diff limited to the
  branding keys + id).
- `check_wire_identity_sponge` (`_class_digest` + `sqlite_capsule.check_pair`).
- A server-scoped `check_cross_loader_classes`: every xplat server class in the Fabric
  jar is in the Sponge jar.

Constants:
- `SPONGE_FORBIDDEN`: benchmark, soak, the smoke/soak dev packages, client prefixes,
  Fabric/NeoForge packages.
- The smoke/soak jar prefixes go into `check_glob_hygiene` round-trips.

Discovery: `discover()` gets the libs dir, required entries gated on the flag, version
pinning, ambiguity handling, the wider return tuple, and the summary.

Selftest: the writer plus LSS/VSS fixtures, and one firing case per gate above,
including a missing family under both flag polarities, a forked id, branding drift,
class drift, a leaked client/benchmark/smoke class, natives, brand, a missing or invalid
JSON, a dangling MixinConfigs, a Fabric-style nested DH codec, and the glob round-trip.

`tools/packaging/sqlite_capsule.py` adds `META-INF/sponge_plugins.json` to its metadata
tuples.

### 2.5 Repo tooling (identical on all five lines)

| File | Change |
|---|---|
| `config/test-impact.json` | `"sponge/": "sponge"`; `sponge` joins `common_test_dependents` |
| `tools/verify/verify.py` | `:sponge:test` (platform); `:sponge:build` + boot smoke (full) |
| `tools/tests/check_artifacts.py`, `inventory.py` | the sponge family |
| `tools/compat/catalog.py` | Sponge facts **conditional on `LINE_SHIP_SPONGE` being present** (old pins keep validating; render prints `—`); profile platform enum + the "mislabeled loader" metadata tuple; `inspect_jar` reads `sponge_plugins.json` into `metadata['sponge']`; render/README matrix column; `test_catalog` fixtures |
| `tools/compat/{materialize,server_smoke_identity,candidate_identity,export_validation}.py` | sponge branches |
| `tools/rig/{assemble_platform_recipe,prepare_server_smoke,build_required_target,prepare_prism?}.py` | sponge platform (§5.3) |
| `tools/rig/required-matrix.json` | `sibling-<line>-sponge--server-smoke` / `mc1211-sponge--server-smoke` rows, written **after** S3 from the frozen jars, required only for `true` lines |
| `tools/settings/reference.py` | section title → regenerate the reference and schema |

Before any adapted file is touched, the inventory of the files this program touches is
listed in the S5 checklist with five fresh blobs. This includes `RequestProcessingService`,
`NeoForgeModuleContractTest`, `ReleaseWorkflowContractTest`, `release.yml`, `build.yml`,
`test-server.sh`, `line.json`, `scripts/soak.sh`, `check_soak.py`, and the
tools files classified adapted despite identical bytes.

### 2.6 Cross-line catalog

**No PR into `main`/`support/**` before the pin.** `feat/sponge-mc*` pushes don't trigger
the catalog or build workflows, and `ci.py` cannot be green on a single line before all
five heads exist. Local gates use `lines.py check --refs` with the five feature heads.
The sequence:

1. Product heads P₁..P₅ are frozen (post-S3, §3) and pushed.
2. One **pin commit with byte-identical bytes on all five lines**:
   - `source-refs.json` = P₁..P₅;
   - `classification.json`: basis plus explicit entries for every new path (including
     the S3 profiles and validation JSONs);
   - `port-batch.json` baseline;
   - re-rendered `docs/compatibility.md` + README markers.
3. Gates: `lines.py check` passes with 0 issues, then `catalog.py validate` /
   `render --check`, then `ci.py`.
4. Any product change after the pin requires a re-pin.

### 2.7 VSS (local publish)

`publish-vss.py` for 0.18.0:
- 15 copied rows + one seeded Sponge row per `true` line (`loaders: ["sponge"]`,
  `game_versions` = `LINE_GAME_VERSIONS_SPONGE`).
- The count is computed, not asserted as 15.
- Same conventions: no changelog, bare version, unversioned filenames, deps `[]`,
  `featured: false`.
- Order per line: paper → neoforge → sponge → fabric, with 26.2 fabric last.
- `lss-verified.json` covers the new LSS Sponge versions.
- The memory file `vss-publish-discipline` is updated.

### 2.8 Tag-day safety and recovery

- **Pre-flight** (also added to each CLAUDE.md "Tagging a Release" command):
  `:sponge:build --refresh-dependencies` right before tagging, which catches an expired
  snapshot timestamp or a vanished RC.
- **Emergency lever:** flip `LINE_SHIP_SPONGE=false` (the four files of §0.3) in one
  commit, then re-merge and re-tag. Fabric, Paper and NeoForge are never held hostage.
- **Stranded Sponge publish, or a later flip to `true`:** hand-upload the jar already
  attached to the GitHub release, using the user's `MODRINTH_PAT`, with the exact
  release.yml name/version/loader/game-version strings. Never re-run release.yml.
  - Documented in CLAUDE.md "Release Pitfalls".
  - Generalizing `release-neoforge.yml` is out of scope.

## 3. Phases (reordered per review)

| Phase | Work | Exit |
|---|---|---|
| **S0** spike | Done for the extremes (§4); remaining: the full-module compile against all five API jars; the snapshot-range dependency on an RC; Sponge closure capture for the rig | Facts recorded in `per-version-surfaces.md` §Sponge |
| **S1** 26.2 | §1, §2.1–2.5, the xplat seam, the purity split, boot smoke, soak twin | local gates: `verify full --run`, `release_check --selftest`, catalog/lines with `--refs`, the boot smoke |
| **S2** ports ×4 | Literal old-core→new-core applier; flavors confined to the adapted list | the same gates per line on its JDK |
| **S4** review | 2 Opus (26.2 diff; support-line flavor diff), fold | re-gates |
| **S4b** freeze | `mod_version=0.18.0` on all five lines; final product heads | release_check `--version 0.18.0` per line |
| **S3** live | §5.3 rig + §5.4 soak (26.2) + §5.5 floor, on the frozen jars, sequential under the harness lock (the user's manual DH server stopped and restored via its own script around each window) | records + flag flips (jar-neutral) |
| **S5** pin | §2.6 | all catalog gates |
| **S6** release prep | five `release/v0.18.0-mc*` PRs, hosted CI green, notes, VSS plan, docs record | stop for the user's go |

## 4. Line facts (S0 results so far; canonical home: `per-version-surfaces.md` §Sponge, byte-identical in all five copies)

**Measured on 2026-10-10** (spike: `~/projects/lss-sponge/spike`, servers under
`research/`):
- SpongeGradle 2.3.0 works on Gradle 9.5.1, under both JDK 21 and JDK 25.
- **VanillaGradle 0.3.2 requires JDK 25 to *run* Gradle**, so the 1.21.x lines use 0.2.2.
  Both built the spike (26.2 / 1.21.1).
- **SpongeVanilla 1.21.1-12.0.3 and 26.2-20.0.0-RC2743** both:
  - load plugin `MixinConfigs` + `Access-Widener` from the manifest;
  - fire a `tickServer` TAIL mixin (99/100 ticks);
  - fire a descriptor-selected FULL-lambda hook (`require=1`, `allow=1`);
  - read a widened field at runtime;
  - honor `PermissionDescription` defaults (use=TRUE, hidden=FALSE).
- Mixin compatibility `JAVA_21` loads Java-25 class files on 26.2.
- FULL lambda: `lambda$full$0` (26.2) and `lambda$full$2` (1.21.1), with identical
  descriptors.
- SpongeVanilla rejects a plugin version `unspecified`: the version must start with a
  digit. Gradle's `mod_version` satisfies this.

Still to fill in S0/S1:
- the 26.1 / 1.21.11 / 1.21.10 boots;
- the `newlyGenerated` semantics per line;
- the dependency range on an RC;
- the closure layout for the rig.

Existing surfaces rows to extend:

| Row | Extension |
|---|---|
| 6 | save routing under Sponge's mixins |
| 10 | the fourth mixin config at JAVA_21 + AW namespace |
| 12 | the Sponge tree |
| 13 | `LINE_*_SPONGE` + the mirror (26.2 says derived; support copies say mirrored) |
| 16 | no Moonrise → the vanilla IOWorker `bg-split` path under Sponge's `IOWorkerMixin` |
| 21 | the elevation form |
| 22 | the Sponge chunk-load arm |

## 5. Testing and validation

### 5.1 Tier 1 (`:sponge:test`, CI, every line)

| Test | Covers |
|---|---|
| `SpongeChannelsTest` | channel table ⇔ `LSSConstants.CHANNEL_*` both ways with direction; the codec round-trip; a classfile pin that the send path never calls `isSupportedBy` |
| (folded into `SpongeChannelsTest`, as built) | frames carry exactly the shared codec bytes; the codecs themselves are pinned by the fabric/paper WireParityTests |
| `SpongeServerHalfContractTest` | §1.2 guards 1–2 (incl. mixin list ⇔ Fabric's) |
| `SpongeMixinTargetContractTest` | §1.4 against the line's MC classes |
| `SpongePluginMetadataContractTest` | generated JSON (id, entrypoint exists, version expansion, dependency range); `JAVA_21` literal; services file; no `plugin("lodserversupport")` lookups in sources |
| `SpongeLoaderSeamContractTest` | `SpongeLoaderServices` overrides every method the fabric/neoforge server impls override |
| `ToolchainContractTest` twin | class major = `LINE_JAVA_VERSION`; MC = `minecraft_version` |
| `SpongePermissionsTest` | registered nodes/defaults equal the Paper plugin.yml table, both spellings |
| `SpongeVanishVisibilityTest` | injected reader: vanished → hidden; throw → hidden + once-warn; absent → visible |

Shared tests extended:
- `VersionVolatileFileListTest`: the Sponge tree;
- the twins: as built, a Sponge-owned `SpongeTwinContractTest` (byte-identical `ScopedCarrier`; the save hook pinned by its injection contract) instead of extending `NeoForgeModuleContractTest`;
- `LoaderPermissionSeamContractTest`;
- the `XplatLoaderPurityTest` split;
- `ReleaseWorkflowContractTest` (§2.2).

### 5.2 Boot smoke (`:sponge:bootSmoke`, CI every line, retry-once)

The smoke runs the **built shadowJar** on the pinned SpongeVanilla jar from a staged
`mods/` dir, not a dev classes-dir run, so it exercises the real manifest, shading and
AW. Mechanics:
- an Exec task drives a small Python runner;
- it is offline-mode, superflat;
- the deadline is 300 s from launch and 120 s from `Done (`;
- the SpongeVanilla jar is downloaded sha1-verified and cached.

A dev-only smoke plugin jar beside it asserts:
- the service started;
- 13 channels are registered;
- `/lsslod diag` runs through the Raw command, and its LOADER token starts `sponge-`;
- `ServerTickHook` reaches 20 ticks or more;
- the store state is `ok`;
- the permission defaults (`lss.use`=TRUE, `lss.farplayers.hidden`=FALSE) read through
  the live service;
- a forced chunk generation and a forced re-load of a saved chunk fire `ChunkLoadHook`
  with the correct `newlyGenerated` flag each way;
- a forced save fires `ChunkSaveDataHook`;
- **one disk-read serve** through the service's reader resolves and reports `read_path`
  (Sponge overwrites `IOWorker` internals that the bg-split path reads);
- the implementation guard reads `spongevanilla`;
- the stop is clean.

The result is a JSON file plus an exit code.

### 5.3 Live server smoke (local, frozen candidates, every line)

The native server-smoke rig gets a real `sponge` platform:

- **Bootstrap:** `spongevanilla-<pin>.jar`, its `verify_bootstrap` branch, and
  `mods/` + `config/` staging.
- **Dependency closure:** SpongeVanilla downloads and remaps vanilla and libraries on
  first boot. S0 captures that `libraries/` tree once per line into the frozen
  dependency root (the Paper staging shape), so rig runs are offline and reproducible.
- **Readiness:** `Done (`, the vanilla `lost connection:` line, stdin console, and the
  store at `world/lss-lod/store.db` (verified for Sponge's layout).
- **Plumbing:** `inspect_jar` sponge metadata, so the server row's `platform in metadata`
  check holds; `build_required_target` globs the sponge build outputs.

Each line runs one native Sponge server plus two fresh Fabric clients with the smoke
client jar, leading to a validation record and the line's sponge profile (exact jar
sha256). A pass is required for `LINE_SHIP_SPONGE=true`. The required-matrix row is
written from the frozen jar afterwards.

### 5.4 Minimal soak (26.2, skip clause)

`SOAK_PLATFORM=sponge` runs `fresh-backfill` + `dirty-broadcast`:
- a ~200-line driver twin of `SoakScenarioDriver` in `sponge/src/soak`. Its only loader
  dependencies are start, join and end-tick; the exporter and store-downgrade classes are
  already xplat. It is compiled with `benchmark/**` re-included for that source set only,
  and excluded from release jars (release_check forbids the package).
- a `:sponge:runSoakServer` task (the boot-smoke staging plus the soak jar);
- `soak.sh`: a sponge platform arm, an allowlist of those two scenarios, `base-sponge`,
  and the **settings platform `mod`**. This fixes the two places that map every
  non-fabric platform to `paper`: soak.sh's `-config.paper.yaml` pick, and the
  check_soak settings-platform mapping.

**Skip clause** (the NeoForge §5.3 shape): if the twin cannot be made green within one
day of S3 rig time, the soak is skipped with a dated entry, and the §5.5 floor carries
the release. Both the "experimental" exit and promotion still require the soak.

### 5.5 Manual floor (26.2; agent-run via RCON/rig unless marked)

1. Generation serves on a gen-enabled world.
2. `setblock` → the dirty broadcast → the client re-receives.
3. A vanished target (`Keys.VANISH_STATE` set via a dev command in the smoke plugin) is
   hidden from a far viewer.
4. `requireServicePermission` on → a non-op is still served (default TRUE).
5. An LSS↔VSS jar swap keeps the config and store.
6. A `/lsslod set` row applies.
7. The store reads `state=ok` after a restart.
8. **User-driven:** one real Fabric + Voxy join from a Windows Prism `lss-test-*`
   instance, with terrain past vanilla distance. Headless software GL is not a renderer
   proof (xvfb memory).

### 5.6 What Sponge does not get (pre-authorized cuts)

- Tier 2 gametests: SpongeVanilla has no gametest harness. The xplat serve path is
  gametest-covered on Fabric with the same classes.
- Soak scenarios beyond §5.4.
- Sponge-side Tier 1 beyond the glue: xplat correctness is Fabric-tested, and Sponge
  inherits it.
- Further cuts need a dated entry.

### 5.7 Local manual rig

`test-server.sh run-sponge` on port **25570**, chosen because it is free. It does not
clash with the user's manual DH server on 25585, or with ports 25564–25569 / the rig
ports.

- It downloads the pinned build (sha1), writes eula, `server.properties`, ops and the
  shared config, and installs the shadowJar into `mods/`.
- `update` **stops the server before replacing the jar** (no hot-swaps).
- It does not take the harness lock (unchanged policy).

## 6. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Plugin mixins/AW on the three unprobed API levels | S0 compile + boot on all five before S1 ends; the cut protocol per line |
| R2 | Snapshot timestamps or RC builds expire | Pins fail loudly; §2.8 pre-flight; the flake-catalog entry "refresh the pin, don't re-run" |
| R3 | RC behavior change | Experimental label; exact-sha pins |
| R4 | Sponge's `IOWorkerMixin` (`@Overwrite tellStorePending`, injections in `runStore`/`synchronize`/`close`) vs the bg-split read path | The boot smoke disk-read assertion; the rig serves from disk; fallback: the vanilla `chunkMap.read` rung (already the C2ME fallback) |
| R5 | VanillaGradle per-line versions diverge in DSL | S0 built both; the build file uses only the shared DSL |
| R6 | Catalog/settings CI configures every project, so plugin resolution must work on each line's JDK | S1/S2 run the catalog.yml/settings.yml commands locally |
| R7 | A replacement permission service ignores descriptions | UNDEFINED → default in `checkPermission` keeps the deny-model semantics |
| R8 | Five-line diff surface | Identical core, one flavor file, the literal applier, `lines.py` proof |
| R9 | Runtime-loaded Sponge worlds degrade (§1.7) | Accepted v1 limitation, serve-safe, documented |
| R10 | Sponge's startup "plugin uses Mixins" warning alarms admins | Troubleshooting entry |

## 7. Documentation (every line unless noted)

| Document | Change |
|---|---|
| README | platforms; install (`mods/` on SpongeVanilla; SpongeNeo → NeoForge jar); config path; commands (backfill available on Sponge); matrix (rendered); experimental label |
| CLAUDE.md | structure; build/test commands; tiers (Sponge best-effort + experimental, plus "what Sponge does not get" so the gaps aren't chased as regressions); the Sponge architecture section; release machinery (`LINE_SHIP_SPONGE`, Modrinth step, pre-flight, recovery, VSS count); flake catalog (CI download/RC expiry; soak entries); local rig; one banner line per support branch |
| `docs/planning/per-version-surfaces.md` | §Sponge + extended rows (§4) |
| `docs/operations` | `installation.md`; `troubleshooting.md` (the mixin warning, runtime-loaded worlds, SpongeNeo, RC builds); `support-lines.md`; `test-flakes.md`; `disposable-rigs.md` (closure capture) |
| `docs/testing` | `native-server-smoke.md`, `required-acceptance-targets.md`, `test-placement.md` (the Sponge route/rows) |
| Settings reference | regenerated |
| `docs/compatibility.md` | rendered |
| `docs/implementation/2026-10-xx-sponge-support.md` | decision record: PR #314 provenance, cuts, S0 facts |
| `CLAUDE.local.md` | user-owned: propose a `run-sponge` note, don't edit unasked |
| `/home/vox/projects/notes/modrinth-readme.md` | minimal edits in the user's wording: Sponge (experimental) in supported platforms + one install line |
| 0.18.0 notes (one per line; omit the bullet where the flag is `false`) | one line: "**Sponge server support (experimental)** — LSS now runs on SpongeVanilla servers; drop the jar into `mods/`." |
| Memory | `vss-publish-discipline`, a new `sponge-support` program memory, the index |

## 8. Out of scope

- SpongeForge/SpongeNeo support (guarded, §1.9).
- A Sponge client.
- An R2-5 backport.
- A lazy region-dir resolver.
- Generalizing `release-neoforge.yml`.
- Soak beyond §5.4.

## 9. Effort (re-costed per review)

| Phase | Estimate |
|---|---|
| S0 rest | 0.5 d |
| S1 | 4–5 d |
| S2 | 1.5–2 d |
| S4 | 1 d |
| S3 | 2–3 d of rig time |
| S5–S6 | 1 d |
| **Total** | **≈ 10–13 days**, executed continuously |

## 10. Review record (2026-10-10: 4 Opus + 1 Fable, plus the S0 spike)

Every finding was folded. MAJORs:

| # | Finding | Fold |
|---|---|---|
| T-M1 | The rig can't take `sponge` with the listed edits (bootstrap, closure, metadata, required rows) | §5.3 rewritten, closure capture in S0 |
| T-M2 / C-m4 | Required rows vs the per-line flag | §2.5: rows only for `true` lines, written post-S3 |
| T-M3 / C-M2 | Stale evidence: S3 before the fold; the version bump after the pin | §3 reorder: fold → freeze/bump → S3 → pin |
| T-M4 / F-M1 | The experimental exit contradicts itself; the build table was wrong (17.0.1 is RC; 17.0.0 recommended) | §0.1 table + pin policy; §0.2 three-part per-line criterion |
| T-M5 | Validation thinner than NeoForge's | §5.4 minimal soak with a skip clause + §5.5 floor |
| R-M1 / R-M2 | release_check: Sponge must branch inside the existing generic gates; the per-family parity gates were unlisted | §2.4 table |
| C-M1 | New `facts()` breaks validation of the old pins | §2.5 conditional facts |
| C-M3 | Catalog CI is unusable pre-pin | §2.6 no PRs before the pin; `--refs` local gate |
| C-M4 / A-m2 / F-M2 | Several "identical" files couldn't be identical; the Mixin 0.8.7 JAVA_21 ceiling | `JAVA_21` everywhere (S0-proven), AW `named` (S0-proven), Java from line.env, VanillaGradle via settings, literals via `SpongeLineSurface` |
| A-M1 / F-m3 | The lambda name from fabric-api is intermediary on 1.21.x | Descriptor-selected hook (S0-proven), the `newlyGenerated` derivation pinned |

MINORs folded:
- the derived exclude list and the mixin-list pin (A-m1);
- the twin claims corrected (A-m3);
- the vanish bridge shape (A-m4);
- the send-failure model (A-m5);
- permission timing and admin defaults (A-m6);
- Sponge's IOWorker overwrite (A-m7 / F-m6);
- injected-container ids (A-m8);
- the mixin warning (A-m9);
- the DH-absent Paper precedent (F-m1);
- runtime worlds (F-m2);
- the Java property (F-m4);
- the dependency range (F-m5);
- the diag LOADER token (F-m6);
- the SpongeNeo guard (F-m7);
- VSS rows follow the flag (F-m8 / R-m3);
- the boot-smoke deadline, cache and real-jar boot (F-m9, T-minor);
- tag-day pre-flight and recovery (R-m1 / m2);
- smoke-jar glob hygiene (R-m4);
- the contract-test list (R-m5);
- no separate Sponge MC property (R-m6);
- the docs list (T-minor);
- the manual Voxy join is user-driven (T-minor);
- no hot-swap in `update` (T-minor).

NITs folded:
- Modrinth featured and version-type;
- the Folia wording;
- no inline comments;
- the number cap;
- merge order;
- the scheduler HEAD fact;
- the `permissionValue()` call;
- the Co-authored-by trailer;
- the five-line decision with a fallback minimum.

## 11. Credit and PR #314 closure

**User decision (2026-10-10, mid-implementation):** the 1.21.11 line, the one PR #314
targets, is **based on the PR branch**. `feat/sponge-mc1.21.11` starts at the PR head
(`945e1d81`), with `support/mc1.21.11-v0.14` (0.17.0) merged into it. The five-line
implementation lands on top and supersedes the PR's files: the port compares against
the line base, never the PR's versions.

Merging the 1.21.11 release PR therefore carries the contributor's commits into the line.
GitHub marks a PR merged only when its head reaches the PR's own base branch, and #314's
base is the already-released `release/v0.16.0-mc1.21.11`. So at S6, **with the user's
go**, #314's base is retargeted to `support/mc1.21.11-v0.14` (`gh pr edit 314 --base`).
Our release PR merges with a merge commit, never a squash or rebase, which would orphan
the PR head. #314 then closes as merged.

Catalog note: the PR's commits are ancestors, not product state. Classification is
over the final tree, so the superseded PR files leave no trace beyond history.


PR #314 by StealWonders (EmpireWar) proved the raw-channel transport, the
private-dispatcher command bridge and the FULL-lambda load hook live. The 26.2 module
commit carries
`Co-authored-by: StealWonders <6114858+StealWonders@users.noreply.github.com>`. The
release notes thank them, and the PR is closed with a short, plain pointer to the
five-line implementation (external PR style).
