# Project improvements implementation plan

Date: 2026-09-09. Status: independently reviewed; required amendments incorporated below. This task produces a plan, not product changes or deployments.

## 1. Scope and baseline

Implement recommendations **1–5, 9, and 10** from the project assessment:

| Package | Recommendation | Deliverable |
| --- | --- | --- |
| P1 | 1 — understandable status | Read-only structured diagnostics, client status screen, local diagnostic export |
| P2 | 2 — compatibility authority | Validated compatibility catalog and generated current documentation |
| P3 | 3 — support-line maintenance | Cross-line classification/checker and isolated port preparation |
| P4 | 4 — reproducible live rigs | Maintained disposable rig runner, locked profiles and external fault fixtures |
| P5 | 5 — Xaero responsibilities | Incremental behavior-preserving bridge decomposition |
| P6 | 9 — test organization and system boundaries | Pure-Java tests in common, test selection, concurrent-region correctness/performance gates |
| P7 | 10 — settings and documentation | Unified setting metadata, opt-in presets, generated reference and current operating guides |

Explicitly excluded: recommendation 6's new public consumer/delivery API; recommendation 7's targeted repair/backfill features; recommendation 8's build-once artifact promotion and publishing redesign. Existing reset, consumer, publication, tagging and branding behavior remain intact. No protocol revision, new network diagnostics packet, blanket module/build rewrite, new loader support, or change to support tiers is included. Tooling may inspect current release metadata but must not redesign the release pipeline.

Plan location is the user's current workspace. Implementation must use the completed Astra work, not overwrite it from this older checkout. Verified candidate baselines:

| MC line | Worktree | Candidate commit | Java | Special boundaries |
| --- | --- | --- | --- | --- |
| 1.21.1 | `/home/vox/projects/lss-lines/1.21.1` | `48268c7d` | 21 | Fabric Tier 3 absent; no Folia; both Sodium generations; Neo renderer live |
| 1.21.10 | `/home/vox/projects/lss-lines/1.21.10` | `948b0bf0` | 21 | Legacy Sodium; Neo maintained build, not shipped |
| 1.21.11 | `/home/vox/projects/lss-lines/1.21.11` | `8ac799a9` | 21 | Modern Sodium; two distinct C2ME profiles; Neo maintained build |
| 26.1 | `/home/vox/projects/lss-lines/26.1` | `1e7c9a9d` | 25 | Common remains Java 21; Neo shipping and renderer availability are separate |
| 26.2 | `/home/vox/projects/lss-lines/26.2` | `98b67abc` | 25 | Mainline; Paper per-world adaptation; Neo renderer currently a stub |

At implementation start, record full SHAs, status and merge bases. Reconcile newer commits before branching; these are a dated starting inventory, not instructions to reset branches. No merge to main or support branches is implicit in this plan. Use isolated implementation branches. Preserve personal Prism profiles and the normal real-map server/world. Paths, Java installations and ports are discovered/configured, not hard-coded from the previous session.

Evidence inputs: the 1.21.1 candidate at `48268c7d` owns `docs/planning/astra-implementation-ledger.md` (do not expect that ledger on sibling branches), `docs/planning/per-version-surfaces.md`, `docs/testing/astra-live-profiles.md`, current build definitions and embedded mod metadata. Historical prose and installed files alone do not establish live support. External prior fixtures/logs are under `/home/vox/.local/state/lss-review/20260908-implementation`; import maintainable fixture source, not credentials, worlds or machine-specific launch state.

## 2. Design invariants

1. Wire bytes, negotiated compatibility and never-tiered wire guarantees remain unchanged. Native encoding axes, Java/mapping flavors, corpus provenance and platform-specific save/ticket behavior remain explicit.
2. A status read cannot create a manager, send a handshake, probe disk, resolve optional classes repeatedly, flush a queue, or mutate gameplay. Unknown is distinct from unsupported, disabled and failed.
3. OFF retires acquisition and unresolved acceptance; it preserves committed native map rebuilds. Disconnect/world replacement retires the old native world. Old callbacks cannot mutate new ownership. P5 cannot relax these recent fixes.
4. Catalogs describe facts and generate views; they do not convert a historical pass into a current pass. Build, shipping, renderer, optional integration and support tier are distinct axes.
5. The rig supervisor owns only its recorded descendants and disposable paths. Existing process ownership, coarse resource locking and failure evidence are retained.
6. Settings retain keys, units, sentinels, defaults, adoption rules, clamps, thread ownership and persistence-failure semantics. Metadata consolidation is not permission to change behavior.
7. No automatic upload of diagnostics or test evidence. Public exports use an allowlist and redact identifying data by default.

## 3. P0 — establish implementation contracts

Deliver before the feature packages:

- Create `docs/implementation/project-improvements-ledger.md`: one row per package/line with source SHA, test commands, actual test counts/skips, artifacts, runtime profile, outcome and remaining work.
- Inventory existing helpers and pins before replacing them: `ClientSessionGate`, `ClientNetGlue`, `ClientCommandActions`, `ClientOptionCatalog`, `RuntimeSettings`, `ServerConfigBase`, `harness-lock.sh`, `owned-process.py`, release checks and real-bytecode compatibility tests.
- Record the current behavior tests for Xaero ownership, settings application, platform contracts and client OFF/ON. Preserve baseline XML and test identities; the prior 14,514 passes are historical evidence, not acceptance for new commits.
- Inventory current Java tests by dependencies, resources and protected invariant. Classify source-text pins as architectural, version-surface, or incidental. Do not remove a pin before its replacement demonstrates the same failure detection.
- Create a package/line applicability matrix and a change-impact manifest. No package can disappear on a line merely because its tests live under Fabric.

Gate: baseline identities and test inventory are reviewable; implementation branches are isolated and normal rigs unchanged.

## 4. P2 — compatibility catalog and generated views

### Authority and schema

Add `tools/compat/` and versioned `config/compatibility/` data. Separate:

- **Line facts:** MC targets, Java/mapping namespace, supported platforms, support tier, shipping flag and renderer/UI capabilities. Existing `.github/line.env` remains authoritative for release-line identity; catalog tooling imports/checks those values rather than adding a second editable shipping flag.
- **Profile locks:** exact loader/mod coordinates, source URLs, SHA256, embedded MC/loader constraints, dependencies, native versus Connector route, disabled components and intended feature checks. No accounts or copied personal settings.
- **Validation records:** artifact/source hashes, profile hash, platform, test scenario version, date, pass/fail/inconclusive result, evidence digest and limitations. A changed profile/artifact invalidates a current-validation claim, but preserves the historical record.

Use stable capability IDs and stable line IDs. Require explicit unknown/unverified and unsupported values; missing records do not become false or green. Validate uniqueness, references, allowed axes, metadata constraints and evidence/profile hashes. A shipped jar with an intentional renderer stub is a valid representable combination.

### Implementation

1. Import the current line inventory and verified profile metadata. Resolve README contradictions; mark native Neo Voxy0.2.9's latest rejected trial honestly without declaring an unproven upstream cause. Retain both 1.21.11 C2ME pins.
2. Generate marked sections of README and a concise `docs/compatibility.md`; preserve historical release notes verbatim. Display tested combinations with dates and feature-specific outcomes.
3. Add `compat validate`, `compat render --check`, and `compat inspect <jar>` commands. Jar inspection is offline/read-only; optional resolution/download is a separate explicit tool action.
4. Add a CI check for schemas, generated-section freshness and line/build consistency. Do not alter release publishing or choose new mod versions automatically.

Acceptance: fixtures reject wrong MC metadata, incompatible dependency ranges, missing hash, stale evidence and conflated renderer/shipping claims. Generation is deterministic. All five line facts match actual definitions. Generated docs no longer conflict about current Neo shipping or absent 1.21.1 Folia/Tier 3. Historic claims remain visibly dated.

## 5. P3 — support-line checking and port preparation

Add `tools/lines/` with a repository-wide file classification manifest. Classify by path plus explicit exceptions: identical source; version adaptation; platform-only; generated/derived; deliberately line-local. The measured 72/78 identical common files are an inventory seed, not a blanket copy rule. `NativeSectionShape` and other genuine encoding axes must stay adapted.

Commands and boundaries:

- `lines inventory`: discover configured refs/worktrees, record full SHAs and classify every tracked production/tooling file; report unclassified additions.
- `lines check`: compare declared identical files at specified refs; report exact differences and missing patches. Adapted files use narrow, named flavor checks or existing semantic/bytecode tests, never unrestricted normalization that could hide logic changes. Generated corpora follow their own provenance rules.
- `lines plan <commit-range>`: list dependency order, affected lines, shared/adapted files, required tests and conflicts without mutation. Require explicit target refs; never guess a branch by directory name.
- `lines prepare`: opt-in creation of clean isolated branches/worktrees; cherry-pick the reviewed ordered range. Stop and preserve conflict evidence on an ambiguous adaptation. No force reset, automatic keep-ours, overwrite of dirty trees, target-branch merge, push or deployment.

Initially compare local refs and make missing refs an actionable error. CI acquires a pinned set of refs and emits a JSON/Markdown report; local checks remain usable offline. Keep the existing per-line Gradle/module boundaries. Main's complete surface IDs should become stable catalog identifiers so cross-line docs stop referring to incompatible row numbers.

Acceptance: temporary-repository tests cover missing/dirty targets, conflicting picks, dependency order, same patch already applied, changed shared source, intentional native shape differences and generated goldens. A dry run over the actual completed Astra range explains all five lines without changing them. A deliberate shared-code edit is detected before port acceptance.

## 6. P7a — settings schema without semantic changes

Extend the existing `ClientOptionCatalog` and `RuntimeSettings`; do not create a parallel registry and leave both alive. Place engine-independent setting metadata in common, with typed client/platform bindings where MC dependencies require them. Prefer explicit Java descriptors over a new runtime reflection framework.

Each descriptor contains stable key/ID, type, units, translation/documentation key, default policy, accepted domain including sentinels, parser/clamp binding, cross-field validator references, availability predicate, apply timing, restart requirement and relevant SaveHook/control action. It must distinguish on-disk value from effective value.

Steps:

1. Inventory every serialized client/server field and every runtime-settable/UI-visible field; explicitly classify advanced/internal fields rather than silently omitting them.
2. Delegate to existing clamp functions and validators initially. Pin zero meanings: auto disk concurrency, disabled dirty pushes, unlimited caps where applicable. Preserve the new-install store-ON versus upgraded missing-key store-OFF rule, LSS/VSS adoption, privacy defaults and unknown/invalid-value behavior.
3. Generate config reference and command help from descriptors; bind existing Sodium generations to the same semantics and retain page order/translation keys. Do not expose previously restart-only values as live controls simply because metadata exists.
4. Apply one-setting changes through existing ownership-safe paths. For future preset batches, define parse/validate-on-scratch and owner-thread publication before implementing them; never expose raw out-of-range values to ingress threads.

Acceptance: differential tests compare old and new parsing/default/clamp/apply results across normal, invalid, boundary, sentinel and upgrade inputs. Preserve failed-save applied-but-unsaved feedback, far-player preference push, OFF/ON lifecycle and Paper per-world behavior. UI golden/real-bytecode tests remain required. No new defaults are introduced in this stage.

## 7. P1 — understandable status and local export

### Read model

Add immutable, versioned `ClientStatusSnapshot` and server-local status DTOs. Separate connection/discovery state, local reception choice, effective server availability, consumer availability, active local limiting conditions, progress/counters and freshness. Use existing metrics and owner-thread snapshots; do not parse display strings to drive the UI.

Known reasons include not connected, awaiting negotiation, reception OFF, no consumer, server explicitly disabled, unsupported integration, local rate cap, decode/ingest pressure and Xaero pending rebuilds. Multiple limiting conditions can coexist. Distinguish measured causes from inferred suggestions. Do not claim a remote server disk/generation/bandwidth cause unless the existing protocol actually reports it; show remote cause unknown and direct operators to server-local diagnostics.

Snapshot collection is bounded, cached at at most 2 Hz, and lifecycle-tagged. UI/render access only reads the latest snapshot. Optional integration status comes from cached resolver results, not new reflection or disk probes. Preserve last-known state with a timestamp where appropriate; never display old-world counters as current.

### Surfaces

1. Make `/lss diag` useful without a request manager, including OFF, failed negotiation and no-consumer states. Retain the detailed active counters for existing troubleshooting; structured data becomes their shared source.
2. Add `/lss status` opening a small loader-neutral Minecraft screen, with thin version/loader adapters. Provide an entry from existing Sodium pages; it must also work without Sodium. Show summary, secondary reasons, progress and supported next actions. Actions use existing commands/settings only—no targeted repair features.
3. Add explicit `/lss diagnostics export` and the corresponding server-local operator action. Export asynchronously to a bounded local directory and display the path. A versioned JSON plus small text summary is sufficient; never auto-upload or dump all logs.
4. Allowlist non-identifying fields: LSS/MC/loader/optional-mod versions, capabilities, sanitized settings, queue/counter snapshots and selected bounded errors. Redact server addresses, player names/UUIDs, seeds/world tokens, absolute personal paths and secrets. Test exception-message sanitization; user initiation is not permission to include arbitrary raw logs.

Acceptance: table-driven snapshot tests cover every state and stale-session transition; no-manager diagnostics retain useful facts; screen works on both 1.21.1 loaders and Sodium generations plus modern/legacy Fabric sibling lines. Stub Neo renderers are reported as unavailable, not failures. Export tests cover bounds, redaction, denied writes and lifecycle changes. Profiling on the defined reference rig must show no synchronous I/O or per-frame collection and no material frame/tick regression against disabled status collection.

## 8. P4 — maintained disposable rigs

Create a Python CLI at `tools/rig/`, with source-only test mods under `test-fixtures/`. Reuse the existing ownership supervisor, harness lock and script regression suite. The existing coarse lock is the safe default; allocate distinct paths/ports before considering concurrency, and keep performance runs exclusive on the host.

Commands: `rig doctor`, `rig plan <profile> <scenario>`, `rig create`, `rig run`, `rig status`, `rig stop`, and `rig collect`. Run IDs own configuration, caches, disposable world copies, process identities, temporary mods, logs and result manifests. Profiles come from P2. Doctor checks Java, cached dependencies, loader/mod metadata, display/input availability, disk space, ownership support and loopback connectivity without starting Minecraft.

Key behavior:

- Separate Linux headless and Windows Prism observer backends. Configure bind endpoint and client-visible endpoint independently; Windows fixture connections support `[::1]:port` and preserve brackets. Do not use the demonstrated broken Prism `--server` route; use a verified backend connection mechanism.
- Create independent regular-file clones. Strip inherited accounts, hooks, custom wrappers and environment bindings. Never copy personal worlds/caches by default. Missing dependencies produce an exact resolution plan; no automatic mod upgrades or guessed filenames.
- Keep Minecraft readiness, actual handshake, required test count and scenario-specific semantic proof separate from process exit. Capture first failed attempt before an explicitly allowed retry; record both attempts.
- Import WI5 reception, WI6 send-admission and WI9 seated-draw fixtures from the prior external projects. Remove machine-specific paths; opt in by run ID/fixture manifest. Keep runtime helpers outside Mixin-owned packages, use durable game logging, and verify remapped Fabric versus native Neo descriptors.
- Automate state assertions and input where supported. Visual observations requiring a person remain an explicit pending/accepted/rejected step with artifact/profile identity; an unattended run cannot claim a screenshot was reviewed. No physical Netty saturation claim from an adapter-denial fixture.
- Stop uses verified process ownership, graceful shutdown followed by bounded owned-descendant termination. Persist recovery information after runner failure. Cleanup never follows symlinks out of a run root and cannot stop a normal server because its port matches.

Acceptance: unit/actual-script tests cover dry-run purity, stale PIDs, foreign occupied ports, nested launch cleanup, missing/mismatched mods, failed startup, lost GUI input capability, timeout, absent proof, stale evidence and interrupted collection. Run actual Fabric/Paper/Neo 1.21.1 smoke, both 1.21.1 loader lifecycle/render profiles, both Elytra versions, both 1.21.11 C2ME profiles, and a 26.2 Folia scenario. Keep unsupported combinations as explicit skips requiring an applicability reason. All test fixture classes/metadata are absent from shipping LSS/VSS artifacts.

## 9. P6a — test placement and selection

Set up `common:test` with Java 21/JUnit and explicit test-only runtime dependencies for compile-only libraries. Move only tests whose entire dependency/resource closure is MC/loader-free. Preserve package names where package-private seams matter. MC stubs, registry bootstrap, real-mapping tests and loader integration remain outside common. Do not make common depend on Fabric merely to move a test file.

Create a before/after test-ID inventory mapping moves/renames; no double-counting moved tests or silently lost parameter cases. Move reusable pure test helpers into a test-fixtures source set only if used by multiple suites; no helper classes in production jars. Keep line-adapted native-shape tests executing on every relevant line.

Add documented `fast`, `platform`, `integration`, and `full` verification entry points backed by the dependency/impact manifest. Fast selection is advisory; main/release-required checks are not silently weakened. Broad common changes select all dependent platforms/lines. Unknown impact falls back to full validation.

Review incidental source-text tests one at a time: replace with behavior, generated-data validation or bytecode checks that catch the same forbidden mutation. Retain deliberate architecture/mapping/entrypoint/exclusion pins. Prove each replacement rejects a representative broken fixture before removing the old assertion.

Acceptance: old-versus-new test identity/count accounting, common tests with no MC classpath, all platform parity tests still executed, correct selection under representative shared/platform/schema/docs changes, and actual before/after test-duration measurements. Do not promise a speedup until measured.

## 10. P5 — Xaero bridge decomposition

Primary files: `xplat/.../compat/XaeroMapCompat.java`, `XaeroTileExtractor`, `ModCompat`, existing Xaero unit/wiring/bytecode tests. Preserve the external facade, public consumer API, config keys, defaults, budgets, thread topology and all P0 baseline behavioral contracts.

Target responsibilities (names provisional; ownership contracts are mandatory):

- `XaeroBindings`: resolve/cache exact external member descriptors and report cached availability.
- `XaeroAcquisitionQueue`: origin-owned queued entries, bounded membership/bytes, overflow and deferred failure/debt bookkeeping.
- `XaeroTileWriter`: prepared-tile application and required neighbor/shading interactions under the existing main-thread rules.
- `XaeroRebuildScheduler`: committed native updates, frame budget and rebuild batching; independent of acquisition retirement.
- `XaeroSession`: orchestrate native-world identity and acquisition generations; expose immutable diagnostics for P1.

Extraction order: bindings first; committed rebuild scheduler second; acquisition/debt third; tile writer last. One responsibility per commit, no simultaneous scheduling optimization. Preserve lock order; external/reentrant probes stay outside internal queue/debt/acquisition locks where required, while native writes retain their required Xaero monitors. Callback generation and receipt identity must be explicit parameters/owned records, not inferred from current globals. Do not centralize distinct lifetimes into a single catch-all close operation.

Acceptance after each extraction: affected behavior and descriptor tests pass; adversarial old-origin extraction/overflow/deferred-probe/commit/cancellation controls still reject replacement-session mutation; gauges match actual membership; exactly-once lease release remains pinned. After the complete extraction run P4's same-world backlog OFF/ON and abrupt same-dimension server replacement on both 1.21.1 loaders, preserving pending native rebuilds only for OFF. Re-run map boundary/shading/save-race regression coverage. Compare bounded-work and frame-time distributions to the pre-refactor candidate on the same workload; investigate regression before accepting. Port narrow MC flavor points explicitly across five lines, retaining existing renderer stubs.

## 11. P6b — concurrent regions and performance acceptance

Use P4 with multiple independently identified clients and distinct owned connections. Add a real multi-region Folia fixture on 26.2 and comparable Fabric/Paper workloads. Verify the players are simultaneously handled by distinct region contexts; distance alone or sequential teleporting is insufficient. Use platform-safe instrumentation to record region ownership overlap; never read another region's world state to produce diagnostics.

Scenario set:

1. Four clients in two or more independently ticking regions; warm stored terrain for one, disk-only pregenerated terrain for another, and a bounded fresh-generation area for another.
2. Sustain concurrent requests; churn one client's connection/dimension while others keep making progress. Exercise a content update in each occupied region and verify current bodies/stamps reach the right consumers.
3. Introduce a bounded slow consumer and send-admission denial; verify unaffected players progress and queues obey configured bounds. Preserve the difference between intentional throttling and starvation.
4. Quiesce, disconnect all subjects, and verify requests, tickets, receipt/debt counts and owned processes return to their defined baseline; inspect any plateauing retained memory separately from normal JVM heap reservation.

First implement hard correctness laws independent of speed: actual region overlap, every eligible non-stalled client progresses in each 30-second observation window, no stale-session delivery, no permanently lost current update, bounded queue/capacity, and completed cleanup. Export per-player latency/throughput, effective throttles, p50/p95/p99 tick/frame samples, GC and process RSS using existing instrumentation where available. Preserve sample counts and units; server-average MSPT is not p99 latency.

Performance protocol: pin hardware, world snapshot, JVM flags, candidate/profile hashes and durations. Alternate baseline/candidate order for at least three runs each after warmup, without competing builds or normal workload on that host. Proposed durations: 2-minute warmup, 10-minute measured workload, 2-minute drain. Pre-register a reference-machine regression budget of +10% for p95/p99 tick latency, -10% for eligible-client useful throughput and +10% peak RSS, with absolute noise floors calibrated by repeated baseline runs before testing the candidate. If baseline variation exceeds a budget, mark performance inconclusive and improve the environment; do not widen thresholds after a candidate failure. Correctness failures cannot be waived by performance variance.

Gate: actual multi-region correctness passes on Folia 26.2 and comparable multi-client correctness on Fabric/Paper; representative non-Folia smoke on sibling lines. 1.21.1 never requests a nonexistent Folia/Tier 3 task. Passing this bounded gate is evidence for the named scenario, not automatic removal of Folia's experimental support label or certification of every Folia line.

## 12. P7b — presets and documentation migration

After schema parity and P6b measurements, add explicit, previewable presets. Candidate set: conservative server, pregenerated-world server and map-only client. Define each as an allowlisted patch to user-facing settings, not replacement of an entire config. No preset changes permissions/privacy, anti-xray correctness, address aliases, storage identity or compatibility fallback switches. No preset silently enables persistent Xaero map writes; its preview requires explicit selection of that behavior.

Server conservative numeric values must be selected from P6b/reference measurements and documented before becoming the shipped preset fixture. Pregenerated-world disables new generation without claiming every requested chunk already exists. Map-only changes LSS settings and explains required consumers; it does not disable/uninstall another mod or promise consumer-specific delivery routing.

Apply sequence: show effective-value diff and restart-only changes; parse/clamp/validate all values on scratch; publish through each platform's owner; persist and report per-scope applied/persisted/restart-required outcome. Preserve existing applied-but-unsaved semantics. Reconcile receive/far-player controls once after the batch, not once per field. Provide a reversible settings snapshot for the last preset application without resetting world/cache data. For per-world Paper settings show the selected scope; never expand implicitly to every world.

Documentation layout: `docs/architecture/` for current ownership/flow/platform seams, `docs/operations/` for installation/troubleshooting/performance/rig use, `docs/compatibility.md` for generated current capabilities, `docs/reference/` for generated settings, and `docs/history/` for explicitly dated plans/evidence. Shorten CLAUDE/AGENTS guidance to essential commands, invariants and links, retaining the canonical flake/decision records. Preserve stable links with redirects/stubs when moving old documents; no indiscriminate deletion or reformatting of hashed evidence.

Acceptance: preset preview equals actual post-validation diff; unrelated and protected values remain unchanged; denied persistence and restart-only changes are honest; applicable client generations and server scopes pass; references and translations resolve; generated outputs are reproducible. Documentation labels distinguish current guidance from historical validation, and examples can be executed by the rig/checker where appropriate.

## 13. Sequencing, review boundaries and estimates

| Order | Work | Dependency / review gate | Relative size |
| --- | --- | --- | --- |
| 0 | P0 baseline/invariant inventory | Required before edits | Small |
| 1 | P2 catalog and immediate doc corrections | Review authority model and evidence schema | Medium |
| 2 | P3 inventory/check; P7a descriptors | P2 line IDs; preserve config semantics | Medium each |
| 3 | P4 core runner and imported fixtures; P6a pure tests | P2 profiles/P0 tests; can proceed independently | Large / Medium |
| 4 | P1 status/export/UI | P7a effective settings; P2 availability; P4 live validation | Large |
| 5 | P5 narrow Xaero extractions | P0 behavior baselines and P4 reproductions | Large |
| 6 | P6b multi-region/performance (P3 dry run occurs with P4 core) | Stable candidate and P4 ownership | Large / Small |
| 7 | P7b measured presets/current docs; final all-line gates | P7a and P6b; all package results | Medium |

Use one coherent reviewable change per responsibility: catalog/schema; generated views; cross-line check; isolated port prep; settings descriptors; status model; status UI/export; rig core; each fixture; test moves; each Xaero extraction; concurrent scenario/checker; presets/docs. Stage changes on 1.21.1 first where appropriate, while making 26.2's divergent data/Java surfaces an early compilation control. Validate each package across applicable lines before the next large refactor; do not accumulate an unported stack until the end.

Estimates are relative scope, not a time promise. Stop a refactor that requires wire changes, a new consumer API, targeted data repair or publication redesign and propose it separately; those are expressly excluded.

## 14. Final acceptance and handoff

- Every package has implementation, regression, applicability and evidence rows. P2/P3 generated checks agree with all five actual refs and build definitions.
- Build/test Fabric, Paper and Neo maintained modules on every line using its JDK; execute common tests and all applicable server/client gametests. Tier 3 remains absent on 1.21.1. Record executed versus cached tasks and named skips; don't compare headline counts without the migration map.
- Existing exact-artifact checks pass for LSS/VSS with no fixture/helper classes shipped, correct mappings/class major/native packaging and preserved wire parity. This is validation, not item 8's artifact-promotion redesign.
- P1/P7 UI and apply paths pass both supported Sodium generations on both 1.21.1 loaders and relevant sibling dialects. P4/P5 live lifecycle, render-fault and map regression checks run on actual final candidate identities.
- P6b produces valid concurrent-region proof and pre-registered performance results; inconclusive or failed gates remain unresolved in the ledger, not green by prose.
- Normal profiles/world/server unchanged; all owned rigs stopped and disposable injectors removed. Final docs include run commands, limitations, unresolved optional-profile failures and precise next maintenance steps.
- Product implementation, merge, publication and deployment remain outside this planning task. The user separately authorized the bounded WSL launcher installation and feasibility smoke recorded in §16. The resulting reviewed plan is the handoff for subsequent implementation.

## 15. Independent plan review

Three independent Astra reviews inspected the draft and relevant source:

- [Architecture/config/status](2026-09-09-improvements-review-architecture.md): A1–A5.
- [Catalog/lines/tooling](2026-09-09-improvements-review-lines.md): R1–R6.
- [Runtime/validation](2026-09-09-improvements-review-validation.md): V1–V6.

All findings are accepted. The following implementation contracts amend the package sections above and take precedence over their abbreviated descriptions. Review establishes plan completeness, not runtime success. No product implementation occurred. The subsequently authorized WSL feasibility smoke is recorded separately in §16 and is not acceptance for the future feature packages.

### Catalog authority and reproducible port batches — R1–R4

Each branch owns only its line-local authored facts and profiles. Aggregate views import those records from `config/compatibility/source-refs.json`, a versioned five-line manifest containing full commit IDs and schema versions. Generated aggregates carry every source identity; no branch copy claims authority over an unexamined sibling. Build targets are platform-specific (including 26.1 versus Paper/Neo 26.1.2). Existing build definitions, line.env and required hand mirrors remain authoritative. Unsupported schema versions fail explicitly; schema upgrades maintain the previous reader version through a port batch.

Checked-in generated views use only the fixed source-refs.json snapshot. CI candidate substitution produces an ephemeral comparison report, never a checked-in file embedding its own containing commit SHA. CI separates the recorded comparison baseline from a reviewed port-batch manifest. Substitute the actual PR candidate SHA for its line and record the resolved set. Fetch exact missing objects explicitly; fail on unavailable objects instead of falling back to branch tips. The batch lists expected outstanding line adaptations, prerequisite edges and final shared invariants. A first port can pass its candidate gate while the batch remains incomplete; only all required target results close the batch. Unexplained divergence fails. A lightweight docs/catalog workflow runs on generated README/docs and schema/tool edits despite the Minecraft workflow's docs exclusions.

Adapted files record stable surface IDs, axis regions, line/platform values and semantic tests. Residual shared logic must also be compared or explicitly reviewed; a passing flavor-token check cannot certify uncovered edits. Uncovered changes remain review-required. Include gamerule names AND disabled values, removal of spawnChunkRadius from 1.21.10, world layout, native packaging and golden provenance. Port manifests explicitly list prerequisites and merge handling: reject merges unless reviewed mainline-parent selection is provided. Exact patch identity may prove unchanged picks; adapted equivalence requires mapped tests and review. Save conflict/resumption state bound to original input SHAs and verify it before continuing; subjects/path overlap never prove a pick applied.

Dependency profiles are immutable. A separate run manifest binds candidate LSS/VSS and fixture hashes, loader/server, scenario/checker version, world digest/seed, effective config, JVM flags and backend. Record original and transformed Connector identities. Interpret constraints using the applicable loader/range semantics and nested-library routes. Validation is feature/scenario-specific; select the latest recorded attempt for the exact identity by a stable sequence (timestamp plus unique run ID), retaining all earlier evidence. Reimporting an older pass cannot supersede a newer rejection. Xaero-only legacy Neo success and optional native Voxy rejection remain separate observations.

Offline materialization reports missing hashes and source locations. An explicit fetch stage verifies hashes before cache admission; unavailable artifacts produce blocked profiles, never substituted versions. Acceptance injects same-filename/different-bytes candidates, fixture/config-only changes, wrong Connector routes, replayed old passes, moving sibling tips, missing objects, docs-only drift, a correct flavor with deleted shared logic, wrong gamerule values and missing prerequisites. Each must fail or require a named review, never silently pass.

### Settings publication, scope and lifecycle — A1–A5

Descriptors declare supported scopes and inheritance. Existing world/dimension distance overrides do not imply all settings are world-local: reject global-only keys in a world operation or require a separately selected global operation. Preserve world-name/dimension precedence, fresh-map override replacement and removal fallback. The pregenerated-world preset explicitly describes server-wide generation disabling.

Parse all batch inputs into a side-effect-free scratch candidate before cross-field validation; never apply per-key setters in input order. Inventory concurrent readers for correlated fields. Publish immutable effective snapshots where joint consistency is required, or document and test an invariant-preserving ordered update where independent-field semantics permit it. Do not claim atomic whole-config changes without adapting the readers. Reconcile side effects once after final publication per affected scope. Pin raising/lowering both generation limits in both key orders and a controlled ingress-reader interleaving; parse/validation failure cannot publish, save or trigger lifecycle effects.

Bind previews to scope identity, relevant captured inputs and validated changed-key diff. Recheck on the owner before publication; changes affecting validation/diff require a refreshed preview and explicit application. Unrelated-key edits need not invalidate it. Last-application undo is an in-memory changed-key before/after patch, including validator-derived changes, expiring on restart, scope replacement or another preset application. Refuse conflicting undo when any affected key differs from its applied value; preserve unrelated edits and validate the final undo candidate. Failed persistence records applied-but-unsaved outcomes honestly. Test stale previews, cross-field changes, scope replacement, undo conflicts and save failure.

Disconnect/world replacement immediately invalidates status independent of 2 Hz collection, including any-thread invalidation and same-dimension reconnect. Late snapshots/export captures must match lifecycle identity. Server diagnostics compose immutable owner-produced pieces with freshness; never synchronously traverse Folia regions. Export captures sanitized immutable data before async I/O and retains no live managers/worlds.

Before Xaero extraction, record every mutable queue/gauge's creator, permitted readers/writers, protecting lock, retirement event and receipt-release owner, plus frame/tick shared budgets. Keep any-thread retirement/main-thread settlement, native render/writer/region monitors and no-flush teardown semantics.

### Rig ownership and required live matrix — R5–R6, V1, V6

Linux runners acquire the existing coarse `/tmp/lss-harness-$UID/port-25565.lock` before staging and retain inherited-lock semantics; configurable endpoints do not bypass that resource lock. Keep the legacy 25565 occupancy check for legacy scenarios and separately check configured endpoints for new scenarios. Performance runs remain exclusive. Reused supervision is Linux-specific, not portable POSIX ownership.

Windows automated ownership is a feasibility gate: use a dedicated disposable launcher plus a tested native Job Object helper with process creation identity, nested/escaped-child controls and controller-death cleanup. Forwarding to a preexisting Prism process establishes no ownership. If ownership cannot be proven, the backend is observation-only with manual launch and witnessed teardown; it cannot claim unattended cleanup or terminate a shared launcher. Add real Windows backend tests for forwarding, unrelated clients, interruption and controller death. PID, port and command text alone never authorize termination.

Authenticate through an explicitly selected existing launcher context or supported disposable test identity; do not copy credentials into cloned profiles, commands or evidence. Missing login/display/ownership capability is a declared precondition. Preserve client-visible bracketed IPv6 endpoints. The user approved desktop-isolated WSL testing after a bounded live smoke; §16 selects this as the default functional-testing backend. Windows ownership work remains a fallback prerequisite, not permission to take over the desktop.

P0 must expand these stable required rows into exact P2 dependency locks and scenario/checker identities before runtime work:

| Profile ID | Required coverage |
| --- | --- |
| mc1211-fabric-modern | Sodium 0.8 UI/apply; receive OFF/ON, same-dimension replacement, Xaero boundary/shading; supported far-render path |
| mc1211-fabric-legacy | Sodium 0.6 UI/apply and applicable receive lifecycle; Xaero-only/no-consumer controls explicitly separate |
| mc1211-neo-modern | Sodium 0.8 UI/apply; verified Connector route where Voxy is used; Xaero lifecycle and far rendering |
| mc1211-neo-legacy-xaero | Sodium 0.6 UI/apply and Xaero lifecycle without requiring rejected native Voxy pairing |
| mc1211-paper | Real server handshake/store and fixture smoke |
| mc12110-elytra / mc12111-elytra | Actual movement/render evidence on each named line |
| mc12111-c2me-a / mc12111-c2me-b | The two distinct existing artifact pins and their actual save/read scenario |
| mc262-folia-concurrent | Proven owning-region overlap, multi-client correctness and performance |
| sibling-ui-platform | Relevant modern/legacy UI dialects and maintained platform smoke; explicit renderer-stub expectations |

Resolve every broad row to concrete line/loader/consumer/scenario subrows and human-versus-checker proof before execution; a generic smoke cannot close unrelated gates. Native Neo Voxy0.2.9 remains a separately rejected optional trial unless the approved capability matrix changes. Required UI/receive/Xaero gates cannot be skipped because optional Voxy fails. Distinguish unsupported, applicable-but-blocked and rejected-optional. Validate packaging and fixture exclusion for maintained unshipped Neo jars without changing release-required families. Final runtime evidence binds final relevant artifact identities; docs-only edits may reuse matching evidence.

### Early feasibility, test migration and measurement — R6, V2–V5

Sequence P0/P2 contracts → P3 inventory/dry-run plus P4 core → verified P4 fixtures → P1/P5 live acceptance → full P6b measurements → measured P7b presets. P6a consumes P0 impact inventory and P3 classification. Prove P3 preparation early rather than accumulating a large unported stack.

Before expanding the concurrent lane, demonstrate two clients on pinned Folia 26.2 with unique identities, separate game/cache/output roots, run-scoped connection IDs and simultaneously registered sessions. Existing sequential SoakPlayer/fixed-directory launching is insufficient. Inspect and pin the actual owning-region/tick API or server descriptors, legal calling contexts and region split/merge identity lifetime. Collect nonblocking monotonic start/end samples of genuine owning-region work with ownership assertions; async/global tasks or distinct thread IDs are insufficient. Never insert blocking tick barriers. A one-region negative control must fail. If legal instrumentation cannot establish proof, mark this milestone blocked before claiming the four-client lane feasible.

Test move records include owning task and parameter-case identity, working directory, properties, discovery engine, assumptions/skips, declared task inputs and resource/classloader provenance. Wire common:test into required aggregate/CI paths using Java 21 everywhere. Keep experiments explicitly opt-in. Demonstrate an external-resource change invalidates the moved test task cache. Compare timings under matched warm/cold task-cache conditions.

Freeze a metric schema before baseline runs: owning-region execution duration separately from tick-start delay; per-client frame duration; useful body bytes/second per eligible subject; server and each owned client RSS sampled every second by process creation identity. Retain units, raw counts and missing-sample flags. Require at least 1,000 tick/frame samples per measured subject per repetition and at least 95% scheduled RSS observations; inadequate samples are inconclusive, never zero. Fixture instrumentation is bounded and identical for baseline/candidate.

Reset world/store/cache from the same digest for every repetition; pin offered targets, edits, generation locations and fault intervals. A run-scoped independent target oracle defines body/validated/cached/policy-terminal expectations and eligibility. Completed subjects exit the denominator; request-manager silence cannot declare a subject ineligible. Every eligible subject must progress each 30-second window; every offered edit must reach its expected outcome within 120 seconds after fault removal, with final cleanup bounded by the 2-minute drain. Checkers must reject starvation, one lost edit and stale-session delivery separately.

Use exactly three preregistered measured pairs with alternating order, after separate baseline calibration. Additional investigations form a separately recorded experiment and cannot replace selected failed pairs. Apply +10% p95/p99 duration (tick execution, tick delay and frame duration), -10% useful throughput and +10% peak RSS budgets per eligible subject and aggregate. Calibrate absolute floors before candidate runs as the maximum paired baseline-only variation of each metric, record numeric floors, then freeze them. Allowed regression is max(relative baseline budget, frozen absolute floor). Compare the median of paired run-level differences to that bound and require at least two of three pairs within it; a correctness failure fails regardless. Unstable baselines beyond relative budgets or insufficient samples remain inconclusive and require improved isolation. P1/P5 use this explicit frame/tick budget; unavailable measurement blocks measured preset derivation. Existing global average MSPT and newest-process samplers cannot substitute for these metrics.

### Handoff disposition

All A1–A5, R1–R6 and V1–V6 amendments are incorporated above. All three reviewers rechecked their amended contracts; their final clarifications about generated-view commit self-reference and the fixed three-pair experiment are also incorporated. Required feasibility gates remain implementation work, not claimed successes. Recommendations 6, 7 and 8 remain excluded. Desktop-isolated play testing was subsequently approved and incorporated in §16.



## 16. Approved addition — WSL playtests without desktop takeover

User decision: 2026-09-09, after authorizing Prism installation, account reuse and a live feasibility check. The user confirmed that Windows remained usable during isolated input tests and explicitly requested incorporation. This extends P4; recommendations 6, 7 and 8 remain excluded.

### Selected backend and observed evidence

Use real Linux Minecraft clients launched by Prism on a private Xvfb display, with GPU acceleration through Mesa D3D12. The successful host probe required `GALLIUM_DRIVER=d3d12` and `MESA_D3D12_DEFAULT_ADAPTER_NAME=NVIDIA`; the default probe selected software llvmpipe. The isolated display reported accelerated OpenGL 4.6 on the RTX 3080 Ti, and Minecraft independently logged the same renderer. Adapter choice must become a discovered/configured profile value rather than a universal NVIDIA assumption.

Prism 11.1.0 was downloaded from its official GitHub release and verified against the release asset SHA256. It is installed under `~/.local/opt/prismlauncher-11.1.0`, with `~/.local/bin/prismlauncher` as the entry point. Windows account reuse was explicitly authorized: its account file was copied to private Linux launcher storage with mode 0600, without modifying the Windows source. The user confirmed the account appeared signed in; the isolated launcher subsequently downloaded and launched the real client. Future rig profiles reference a separately authorized launcher account context; credentials never enter repository fixtures, hashes, screenshots of authentication dialogs, command lines or exported evidence.

The first client attempt stalled with its render thread in OpenAL `alcGetInteger` using the WSL RDP audio sink. Its logs/thread dump were retained. The second attempt used `ALSOFT_DRIVERS=null`, logged `No Output`, and completed the successful controls below. This is a profile requirement for silent unattended tests; it is not evidence that audio functionality works or that every possible stall is fixed.

Observed profile: MC 1.21.1, Fabric Loader 0.19.3, Java 21, Sodium 0.8.13-beta.2, Voxy 0.2.15-beta community port, Xaero World Map 1.45.0, and the existing LSS candidate. Disposable server used `[::1]:25572`. The normal server and personal instances were not test targets.

| Check | Result / boundary |
| --- | --- |
| Account reuse and genuine client installation | Passed; no fresh interactive account login required |
| Private display GPU probe and actual client renderer | Passed; NVIDIA through D3D12, not software llvmpipe |
| Server connection / LSS v20 negotiation | Passed |
| Keyboard movement | Passed; server Z changed from 3.9302 to 12.1218 during the controlled walk |
| Walking/crouching visual capture | Passed; separate captured poses on the private display |
| Voxy renderer initialization | Passed; NormalRenderPipeline / MDICSectionRenderer created; this alone is not far-terrain correctness proof |
| Xaero fullscreen map and LSS diagnostics UI | Passed; map and diagnostics captured; bridge active |
| Fresh LOD delivery and map bridge writes | Passed after clearcache: 2,704 received sections / 16.4 MB; 2,697 Xaero writes, 7 native skips, zero ingest failures, commit failures or pending rebuilds |
| Graceful shutdown and cleanup | Passed; game quit, server stopped, supervisor exited 0, test port closed and coarse lock released |
| Frame responsiveness | One debug-screen observation showed the configured 30 FPS; not a benchmark or sustained percentile claim |
| Windows coexistence | User confirmed no interference while the agent exercised isolated input |
| Elytra flight assertion | Not accepted: setup/input attempts did not establish server FallFlying=true; retain as a required scenario to stabilize, not a pass |
| Other loaders/MC versions, far-player fault fixtures, long soak, native Windows rendering | Not exercised by this bounded smoke; retain original acceptance matrix |

Raw smoke evidence and exact mod hashes are retained privately under `~/.local/state/lss-wsl-prism/evidence/2026-09-09/`; failed attempts remain in dated attempt directories. The disposable rig directory, server/world, test profile, extra account copy, display and temporary runners were removed. Reusable downloaded assets/libraries/metadata moved into the installed primary Prism data directory. Authentication state and raw launcher logs remain private, outside repository evidence. Preserve sanitized results and relevant screenshots by run identity in the maintained runner. The private `cleanup.json` records verified process, port and lock cleanup; primary Prism and its signed-in account remain installed. At the user’s request, work stops after this incorporation and cleanup.

### P4 implementation steps

1. Add an `isolated-linux-prism` backend with capability discovery for Java, Prism, Xvfb/Xauthority, XTest, image capture, audio backend and actual accelerated GL renderer. Run probes with the same environment as Minecraft. Unexpected software fallback blocks a GPU-required profile; never force unsupported GL versions or hide missing extensions.
2. Allocate a unique display and private Xauthority file per run, disable X TCP listening, retain the coarse harness lock and independent server/client endpoints. All test input and capture uses the recorded private display. Reject the ordinary WSLg/Windows desktop display. Bind input targets to verified window PID, process creation identity and owned descendants before every action; revalidate after reconnect/relaunch. Isolate clipboard as well; type within the test display rather than using the host clipboard.
3. Use the Linux process supervisor for Prism, Xvfb, nested JVMs and disposable servers. Stage only under owned roots after locking. Preserve launcher caches separately from disposable worlds/configs; use exact dependency/candidate manifests. Keep accounts in the authorized launcher context, never ordinary fixture clones. Authenticated and explicitly offline fixture subjects have distinct recorded roles.
4. Default silent functional runs to a null audio backend, 30 FPS, 960×540 or a scenario-required resolution, bounded JVM memory and limited parallel clients. Configure pause-on-focus-loss and VSync explicitly. Provide immediate owned-run stop/pause controls. Functional coexistence allows ordinary desktop use; performance lanes require an idle host and the existing measurement protocol.
5. Automate the same real GUI/input paths where the assertion concerns UI wiring, key handling or animation. Capture frames from the isolated display or game framebuffer and pair them with server/fixture state. Use state-driven waits: a screenshot after sending an input is not proof that the game processed it. In particular, Elytra setup must confirm equipment/game mode/falling state and observe FallFlying=true before claiming a glide. Keep timeouts and first-failure evidence.
6. Port the existing WI5/WI6/WI9 fixtures to this backend and run the required 1.21.1 modern/legacy × Fabric/Neo profiles before making claims about them. Add a distinctive far-terrain target, fresh-body/bridge-write proof, same-world OFF/ON, disconnect/replacement, sustained frame/input responsiveness and graceful shutdown checks. The feasibility smoke does not replace these final product gates.
7. Preserve native Windows as a separately identified coverage lane. WSL D3D12 is a different driver route and Voxy logged unavailable subgroup operations. Native Windows testing must use a proven background/in-client control or isolated guest backend; any foreground session requires a separately agreed window of desktop use. Never silently fall back to host SendKeys, cursor control, focus activation or full-desktop screenshots.

Acceptance: automated negative controls reject default-desktop input, foreign window/PID, stale PID reuse, software fallback, unexpected mod hashes, missing semantic proof and incomplete cleanup. Repeat the positive smoke while the user uses Windows, and record resource interference separately from input isolation. Expand final applicability rows only from actual feature-specific results. Preserve a useful local launcher for the user after shutting down the disposable test processes.

## 17. Completion reconciliation — 2026-09-20

The September 16 Fable/Claude Code completion on all five branches is retained, including its loader-route and rig-cleanup repairs, the six review reports and their evidence limits. The five draft PRs (#289–#293) passed their applicable GitHub checks. Original September 14 packaged artifacts retain their build commits: recorded shipping source, resources, Gradle/build and license inputs were compared byte-for-byte, including physical worktree inputs. Later harness/catalog/documentation heads are separate provenance.

The final selection covers 14 fixture families and 20 artifacts: three retained successes plus 12 successful recovery builds, including the corrected 26.1 smoke replacement (15 successful compile attempts total). The earlier smoke build genuinely targeted exact 26.1 rather than the profile’s actual 26.1.2; it remains historical and does not qualify that runtime. The replacement derives the engine parameter from owning gradle.properties, with fresh compiler/metadata evidence and unchanged shipping products. The successor fixture index preserves the original index and all failed attempts. Three independent Astra completion reviews, with subsequent integration and plan-acceptance audits by existing reviewers, cover rig ownership, concurrency/performance and the native Connector/Elytra changes. The residual reaped-process signalling and manifest main-section issues are repaired on all five lines. The third reviewer independently assessed the initial native changes, then authored the requested minimal manifest follow-up; this is not a claim of independent review of its own repair.

Before the subsequent visual feedback, all planned game execution had completed. The [dated progress index](../implementation/native-validation-progress-2026-09-20.json) records 46 collected passes and eight automated-green cases awaiting actual user image decisions. Fabric and Folia source checks passed 28/28; Paper’s first final attempt failed 27/28 and its bounded diagnostic replay passed 28/28. Preserve the failed attempt and the replay’s diagnostic scope; no body-content diagnosis is inferred from compressed lengths.

The remaining acceptance sequence is:

1. Obtain the user’s eight image-bound visual dispositions and the separate §16 Windows usability/resource-coexistence observation. Neither response is inferred from silence or automated success. Native Windows remains observation-only/unverified.
2. Retain the completed offline gates: 628 rig tests per line with zero skips and 735 non-rig tests total. Game cleanup passed across 57 retained run inventories and 319 process identities, with loopback bindings available and the coarse lock free. The owned gallery remains live for user review; finish its cleanup after review. Complete the existing catalog/classification checks.
3. Finish exact-target export and full integration only after the user-reviewed cases collect as passes. The contract remains 42 required rows plus four supplemental cases, six preset runs and two additional Fabric/Paper source checks: 54 distinct cases, with Folia counted once. Exact-target external export of all46 passes is complete. Its matrix evaluation is34 passed/eight unverified per line, with “missing complete explicit acceptance target” for the eight awaiting user review. The partial plan cannot be applied as complete acceptance. Final artifact/test-move/exclusion checks also passed on all five lines, and15 local candidate hashes equal the retained September14 artifacts.
4. Commit line-owned evidence and matrix targets, pin those immutable five-line refs, and regenerate catalog/support views. Preserve original build/fixture identities. A final acceptance index may replace the current progress status only when all gates actually close.

Retain V27 without repeating it for tool-only changes: 30 passing calibrated verdicts represent 25 nonduplicate comparisons, not established statistical independence. Its frame pacing, tick noise, fixed operating point, corrected-reference and platform limits remain explicit. Support tiers and experimental Folia are unchanged. The September 16 value assessment remains feedback; its proposed scope reduction was not adopted. The September 17 Xaero memory brainstorm remains future work. Merging and publication are separate actions.

## 18. User visual feedback — 2026-09-20

The [visual follow-up](../implementation/visual-review-followup-2026-09-20.md) supersedes section 17’s pre-feedback assumption that image disposition alone would close the visual gates. The missing gold stripe is a native Xaero region-local shading limitation reproduced by the bridge; eight near-player chunks fall between LSS render-distance exclusion and Xaero’s required loaded 3×3 coverage. The bounded native diagnostic establishes the actual eight-cell case. Neither shipping correction is implemented yet. Add the focused acquisition correction and independent expected-slope/coverage assertions described in that report before final visual acceptance. Preserve generic scanner exclusion, budgets, receipts, region locking and lifecycle contracts.

The seated capture repair is implemented on all five lines and qualified with fresh 1.21.1 Fabric/NeoForge fixture outputs. It uses real spyglass input, captures the healthy seated pose before fault injection, and separately records standing recovery. The initial premature-readiness failure is retained; both successor native runs completed semantic checks and cleanup and still await user image review. The eight original image decisions, updated exact-target selection/export and separate Windows resource-coexistence observation remain open. Historical builds and V27 keep their original scope; no new product build, performance acceptance, merge or publication is implied.

## 19. User sign-off with known limitations — 2026-09-20

The user accepted the eight current gallery images after the map defects were explained and closer healthy seated images replaced the earlier captures. The [sign-off record](../implementation/visual-signoff-2026-09-20.md) closes that visual-review stage for the displayed current behavior, retaining both known map defects explicitly. All 54 selected native cases are integrated with exact original targets; all 42 required matrix rows match on every line. This supersedes the earlier pending-review/export status in sections 17–18, without claiming the section 18 production map corrections are implemented.

Cleanup covers 61 retained native run inventories and 349 recorded identities; the review gallery is stopped. Before-review proof/state snapshots and failed attempts remain retained. The separate Windows resource-coexistence observation was pending at visual sign-off and is now closed by the subsequent confirmation below. Overall project completion, shipping map fixes, deployment and publication are not asserted by this sign-off.

## 20. Desktop coexistence confirmed — 2026-09-20

The user separately confirmed “Desktop stayed usable; no noticeable interference” and “Yes, Windows remained usable” when asked about the recent private WSL runs and disruptive slowdowns. This closes the section 16 desktop-coexistence acceptance check. The [observation record](../implementation/desktop-coexistence-2026-09-20.json) preserves both actual replies. It is subjective usability evidence, separate from performance measurements; V27 retains its original scope and native Windows rendering remains unverified. The two known production map corrections remain open.

## 21. Map follow-up research and implementation plan — 2026-09-20

The user requested research and planning for the accepted map limitations. The [coverage and boundary-shading plan](2026-09-20-xaero-map-coverage-and-shading-plan.md) records the bounded Xaero acquisition correction and a separately gated shading prototype, including the upstream approximation policy, inspected artifacts, lifecycle constraints and support-line validation. It does not change the accepted image/desktop dispositions or claim either production fix is implemented.

## 22. Release scope and deferred map work — 2026-09-20

After reviewing the proposed complexity, the user accepted deferring the Xaero-specific acquisition supplement and region-boundary shading workaround and requested release preparation for the completed work. These remain documented limitations, not release-blocking implementation promises or claimed fixes. The [proposed 0.15.0 notes](release-notes-v0.15.0-proposed.md) describe the actual changes since this line’s 0.14.0 release; exact release preflight and integration status are recorded in the [release preparation](2026-09-20-release-preparation.md).
