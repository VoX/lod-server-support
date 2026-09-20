# Fresh final Astra review 2 of 6 — status and diagnostics export

Date: 2026-09-14. Independent read-only code review, assignment 2 in `final-review-assignments.md`. Repository instructions and the 2026-09-09 implementation plan were read. No product files changed; no Java, Gradle, native client/server, foreground input, or child agents were launched. Only this report was written outside the worktrees; bounded Python checker tests used temporary fixtures.

## Result

**No actionable findings in the reviewed status/export implementation.** No observed production failure and no credible blocker identified. This is a completed code review, not final native acceptance. Final native status/export evidence was pending at assignment and was not supplied during this review. Its final artifact bindings and actual observations still require the coordinator's supplement; historical live records were not substituted for that evidence.

## Exact scope and identities

Primary inspection covered Minecraft 1.21.1 Fabric and NeoForge plus Paper server export. Compared the affected production and test/tool surfaces on all five lines, including actual baseline-to-final additions/changes and the residual differences between final implementations.

| Line | Baseline | Frozen final and observed HEAD |
| --- | --- | --- |
| 1.21.1 | `48268c7d7e8740a93fbc6c7cf60fb36f7b75d690` | `0ae20c5f5f41e9482f865b5da7faf6a1d16bfacd` |
| 1.21.10 | `948b0bf09dd852d357176577d7ccacd589aadb7d` | `8a6457bd4250b495d523f9382ff3f73a1c5e7353` |
| 1.21.11 | `8ac799a901e7eb99cb9403d512b790a0b6576190` | `b8828f5ad550c73e26de158b3c56c877cc6e5d8a` |
| 26.1 | `1e7c9a9d3507c3ba90ac511ba6b62995b8796ab2` | `f8f31bb68432db75b32908c83890587cc0ee0dd6` |
| 26.2 | `98b67abc82283417ad288cbb0cca2f4fb3343425` | `6f7081101b4fbbdb946b6160618bc71cd8d20b44` |

Baselines came from `docs/implementation/baseline.json`; frozen heads came from `/home/vox/.local/state/lss-project-improvements/recovery-20260910/accepted-performance-v27-20260914/docs-and-refs-integration.json`. Worktrees were `/home/vox/projects/lss-improvements/<line>`. Existing modified/untracked evidence and documentation were present; they were neither changed nor treated as committed final runtime proof. The inspected production source matched the frozen heads.

## Production review

- **Immutable client provenance and collection:** `ClientStatus.java:24-118`, `StatusCache.java`, and `ClientNetGlue.onEndClientTick` maintain identity-tagged cached DTO publication. Collection follows the existing manager and Xaero tick work; it does not create a manager, initiate negotiation, read disk, or resolve optional classes. The 500 ms deadline bounds routine collection to 2 Hz. Renderer/UI reads use the cache, with immediate identity comparison for replacement before the next tick. Fixed numeric getters use maintained counts; optional integration availability uses cached facts. The legacy Xaero display string takes its queue-size lock but does not enumerate native regions or trigger native flush/resolve operations.
- **No-manager and failed/OFF states:** `ClientStatusSnapshot`, `ClientSessionGate.discoveryStatus`, and `ClientCommandActions.showDiagnostics` preserve useful reception, discovery, consumer, renderer and version facts when detailed manager diagnostics are absent. Protocol rejection and handshake-send failure are distinct from an explicitly disabled server. OFF can retain a negotiated connection while dropping acquisition; the status code does not infer that reception OFF disables the server. Queue presence is explicitly an observation; local rate gating is an interval delta of the composed gate, not retrospective attribution to a manual cap or remote server resource.
- **Lifecycle and freshness:** cache invalidation rejects a late publication and clears callback references. Native connection/world reference identity, rather than dimension equality alone, identifies replacement. The initial world sample establishes the top-level progress baseline. The established detailed connection counters intentionally retain their original scope, as documented in `docs/operations/status-and-presets.md`; this was not misreported as a stale-world defect. Snapshot age is visible. Receipt/native ownership changes themselves are principally assignment 3's scope.
- **Client async ownership:** `ClientCommandActions.java:166-201`, `ClientStatus.java:43-65`, and `LifecycleFeedback.java` place source/screen callbacks only in a bounded owner-side registry. The writer/owner-task captures contain an immutable ticket and sanitized result text, rather than a live source/world. Disconnect and same-dimension replacement clear registered sinks even with I/O held. Delivery checks the client thread and current native identity before taking the callback; taking under the lifecycle monitor is the pinned delivery linearization point, and callback invocation is outside that monitor. Old tickets cannot consume fresh registrations. Rejection and other synchronous submission failures release reservations.
- **Server owner capture:** shared `LSSServerCommands.java:38-57` and `PaperCommands.java:79-83,104-122` create an immutable DTO before I/O. Fabric/Neo commands capture on the server owner; Paper marshals an active service through its existing pump mailbox. DTO collection reads the service's published/owner gauges without traversing worlds or Folia regions. Generation effective/configured-for-restart values remain separately represented. The DTO explicitly describes independently sampled gauges rather than claiming a joint server tick. Inactive service exports remain possible. Existing command permission gates apply to export.
- **Server async lifetime:** the server command acknowledges an admitted concrete target, with completion subsequently logged. Neither the export worker nor its completion callback retains a command sender, source, server, or world. This means a delayed server completion cannot address a disconnected/reused recipient. The acknowledged path is not falsely described as a completed write.
- **Privacy and local file behavior:** `DiagnosticExport.java:25-122` accepts typed DTOs. Display-only legacy strings, including address/cache/world information, are excluded from client JSON; only finite numeric detailed counters are exported. The text summary is generated from the safe top-level DTO, not the detailed display strings. `DiagnosticVersions` admits only named component keys, copies the input map, bounds version strings and rejects unsafe syntax. Failures use fixed generic text; raw exception messages do not enter reports or export completion feedback. Output paths are intentionally visible to the local requester/log but absent from report payloads. I/O uses one daemon worker and one pending slot, 64 KiB bounds per JSON/text file, CREATE_NEW targets, symlink-component rejection and retention bounded to 20 generated files. No upload path was introduced.

## Cross-line adaptations

All five final trees have byte-identical `ClientStatusSnapshot`, `ClientDiagnosticSnapshot`, `ServerStatusSnapshot`, `StatusCache`, `DiagnosticExport`, `DiagnosticVersions`, `LifecycleFeedback`, and `ClientSessionGate` source. The client command-action difference on 26.x is a loader-factory comment; export behavior is identical.

The status collector's only 26.2 difference is `setScreenAndShow`. Inspected screen differences preserve the same state/export model: 1.21.1 renders foreground after its base screen; newer lines use opaque text colors; 1.21.10/1.21.11 use KeyEvent; 26.x use GuiGraphicsExtractor and their own chat entrypoints; 26.2 uses setScreenAndShow on return. Existing behavior/bytecode pins explain those choices. Both 1.21.1 loaders wire the shared status/export commands; cached loader-version acquisition is loader-native. Renderer availability uses the existing same-FQN renderer constant, preserving live 1.21.1 paths and unavailable sibling Neo stubs.

Shared server command differences are the native permission API and, on 26.2, existing per-world setting application. Paper's status/export implementation is identical across all five lines; its 26.2 residual difference is the per-world settings reply/repush path. This review did not reinterpret those settings operations as world-local export traversal.

## Tests and evidence actually inspected

Read behavior pins before evaluating tradeoffs: StatusStateTest, StatusCacheTest, DiagnosticPrivacyTest, DiagnosticExportTest, ServerExportJobTest, LifecycleFeedbackTest, ClientExportLifecycleWiringTest, both ServerExportLifetimeWiringTest twins, screen render/return bindings, relevant session-gate behavior, and the maintained native export/UI fixtures and checkers.

Independently parsed the preserved 1.21.1 final integrated XML for those 11 status/export test classes: **41 cases, zero failures/errors/skips**. These include mutation controls for old requester captures and removed rejection cleanup, held-writer immutable targets, denied writes/symlinks/oversize reports, bounded queue/retention, stale lifecycle, arbitrary private strings, non-consumer state tables, and callback reference retirement. XML root: `/home/vox/.local/state/lss-project-improvements/final-validation-after-preset/1.21.1/attempt-final-integrated-20260914/`.

Read the final integrated manifests for 1.21.1 and 1.21.10. Both explicitly report passed fresh gates, stable shipping inputs and their respective frozen source SHAs. 1.21.1 has 783 common, 1719 Fabric, 539 Paper, and 23 Neo unit cases; four Fabric experiment cases are skipped. Its Fabric/Neo server semantic proofs and artifact exclusion/release checks are recorded as successful; client Tier 3 is unavailable on that line. 1.21.10 has 782/1716/539/19 unit cases; its six Fabric skips include four opt-in experiments and two modern-Sodium checks on this legacy line. These manifest observations are not substitutes for native status UI/export proof. Remaining three lines' final builds were pending at assignment and were not claimed as reviewed passes.

Executed lightweight offline controls from `1.21.1/tools/rig`:

- `python3 -m unittest test_client_ui test_client_ui_negotiated test_ui_snapshot_wait test_client_ui_finalize`: **25 tests passed**.
- `python3 -m unittest test_export_lifecycle`: **6 tests passed**.

These reject missing/changed/out-of-run exports, incorrect connection/consumer/renderer/version facts, incorrect UI evidence identity, stale capture timestamps, dropped draft/save-failure evidence, corrupt screenshots, and missing/duplicate/reordered/foreign/failing native export event sequences. Their synthetic positive fixtures are explicitly not native acceptance.

The actual `ExportProbe` fixture holds the production export writer, tests both screen and command routes, clears old sinks on disconnect, verifies a new native connection/world with the same dimension, releases old I/O, checks old completion suppression and exactly-once fresh success, then fills the real executor queue and verifies rejected submission cleanup plus recovery. `check_export_lifecycle` requires the exact ordered run-bound native event sequence and recomputes the report from the run log. This is a useful concrete verification path; I have reviewed its code, not claimed a final run that was not supplied.

## Acceptance limits

No requested fix emerges from this review. Pending native supplement should bind final relevant artifacts to the status/UI matrix, held-export disconnect/reconnect and rejection scenario, denied-write/redaction/retention observations, and any no-manager failure-state fixtures required by the plan. The accepted V27 performance claim was not independently re-evaluated here; assignment 6 owns the experiment's raw metric/identity review. A code-clean result cannot close those separate evidence gates.
