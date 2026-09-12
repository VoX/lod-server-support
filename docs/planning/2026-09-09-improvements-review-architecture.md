# Independent architecture/config/status plan review

Date: 2026-09-09. Reviewed only `2026-09-09-project-improvements-implementation-plan.md`, with read-only source inspection of the completed 1.21.1 and 26.2 candidates. No builds, runtime checks, network actions or product edits. This is a plan review, not a new audit of the implementation.

Verdict: the overall architecture and exclusions are sound. No scope blocker found. Two major design omissions in preset application should be resolved in the plan before implementation; the remaining amendments sharpen existing requirements rather than require a different architecture.

## Required amendments

### A1 — major: owner-thread execution does not define safe batch publication

**Plan:** §6 P7a step 4; §12 P7b apply sequence.

The plan correctly requires scratch validation and owner-thread publication, but does not define what concurrent readers may observe when a multi-field batch is published. Existing `RuntimeSettings` applies individual assignments followed by `validate()`; its javadoc explicitly identifies Paper ingress/handshake reads off-pump. Merely executing several final assignments on an owner thread does not make the batch atomic to these readers. Correlated fields include `generationConcurrencyLimitGlobal` and `generationConcurrencyLimitPerPlayer`. Calling existing per-setting apply operations in preset order can also clamp against the previous global value rather than the intended final batch value.

**Amendment:** specify two phases: parse all selected values into an isolated candidate, then run cross-field validation against the complete candidate. Explicitly choose and document publication semantics per binding. Use a coherent immutable effective-settings publication where joint consistency is required, or prove an invariant-preserving ordered update where existing independent-field behavior permits it. Do not promise whole-config atomicity without updating readers. Side effects and re-pushes run after publication against the validated final state, once per affected scope. Scratch construction must not load/save files or perform control actions.

**Acceptance addition:** a preset raises and lowers both generation limits with keys supplied in both orders; final results and preview are identical. A controlled ingress-reader interleaving during publication cannot see a prohibited cross-field state. Parsing or validation failure produces no publication, persistence or lifecycle effect. These are focused batch contracts, not a reason to rewrite all config access in P7a.

### A2 — major: preview and undo need stale-state semantics

**Plan:** §12 P7b, “show effective-value diff” and “reversible settings snapshot for the last preset application”; acceptance “preview equals actual post-validation diff.”

A preview can become stale while a command, config reload or another settings screen changes the same configuration. A whole saved-config restore would also undo unrelated changes made after the preset, contradicting the allowlisted-patch model. The current text does not define whether application revalidates, rejects, or silently overwrites such changes.

**Amendment:** bind each preview to a configuration revision (or comparison of the relevant captured inputs), target scope and validated patch. Recheck on the owner immediately before publication. If the inputs affecting the diff changed, return a refreshed preview and require explicit application of that preview. Store undo as the actual changed-key before/after patch, including cross-field changes, rather than an unrestricted config restore. An undo conflicts if an affected key no longer has its preset-applied value; present that conflict instead of silently overwriting it. State whether the single last-application record is in-memory or persistent and when it expires. Restart-only and failed-save outcomes remain distinct.

**Acceptance addition:** intervening same-key edit, unrelated-key edit, changed cross-field input, scope replacement and failed persistence. Undo preserves unrelated edits and cannot restore world/cache identity or protected values. This does not introduce recommendation 7's data-repair feature.

## Clarifications and improvements

### A3 — clarify scope metadata before constructing server presets

**Plan:** §6 descriptor schema and §12 Paper per-world scope.

The 26.2 candidate's `ServerConfigBase.lodDistanceChunksByWorld` is a specific distance override map, not a general per-world configuration container. Its lookup supports Paper world-name precedence and dimension fallback; Fabric/Neo use dimension keys. Generation and bandwidth knobs remain global configuration fields. A “pregenerated-world server” label must not imply world-local generation disabling when the underlying switch is server-wide.

Add explicit supported scopes and inheritance/override behavior to each descriptor. A world-scoped preview must reject global-only keys or show a separately selected global operation; it must never silently promote scope. Preserve fresh-map replacement for distance overrides, lookup precedence and override removal/fallback behavior. Explain that the pregenerated-world preset disables generation across the server if that is its actual scope. This fits existing invariants and needs no new per-world feature.

### A4 — make snapshot invalidation distinct from periodic collection

**Plan:** §7 P1 read model, bounded 2 Hz cache and stale-session acceptance.

The intended lifecycle-tagging requirement is correct. Specify that disconnect/world replacement invalidates the current snapshot immediately, independently of the 2 Hz refresh interval. Reject a late completed collection/export capture for another lifecycle; a same-dimension reconnect is a different lifecycle. Cached historical data can remain only under an explicitly historical label. The inspected Xaero code explicitly handles abrupt disconnect on Netty followed by main-thread settlement, so “owner-thread snapshots” must not assume every invalidation event starts on the owner thread.

Server-local snapshots should collect immutable owner-produced pieces with per-piece freshness; they must not synchronously traverse Folia region state from a global diagnostics command. Export should capture the immutable sanitized data first and perform file writes asynchronously, without carrying live manager/world references into its worker. These details operationalize §2 invariants 2 and 7 and the existing §7 acceptance, rather than add a new package.

### A5 — P5 ownership split is appropriate; pin its boundary table

**Plan:** §10 P5 responsibilities/extraction order.

The separate acquisition and committed-rebuild lifetimes match the inspected implementation: `Origin` owns deferred acceptance and acquisition generation; `onAcquisitionEnd()` retires unresolved work without clearing committed native updates; any-thread `onSessionEnd()` schedules main-thread settlement; committed entries retain processor, world, dimension, region and tile-chunk identity. Keeping the scheduler independent of acquisition retirement is the right decomposition.

Before the scheduler extraction, record a compact ownership table for each mutable collection/gauge: creating thread, permitted readers/writers, protecting lock, retirement event and receipt-release owner. Include the frame-versus-tick budget accounting shared with the pump. Clarify “external/reentrant probes outside locks” as outside internal queue/debt/acquisition locks where required: existing Xaero mutation must still occur under its required native render/writer/region monitors. It must not be interpreted as moving all external calls outside those monitors. Preserve the any-thread retirement/main-thread settlement split and accepted disconnect behavior; do not add a flush during teardown.

Existing adversarial and live acceptance already covers the most important lifecycle regressions. No additional public API, changed scheduling policy or broad lock rewrite is warranted by this review.

## Disposition recommendation

Resolve A1–A2 explicitly in §6/§12. Fold A3–A5 into the existing schema and lifecycle contracts. P1 and P5 can retain the proposed sequencing and architecture. The plan continues to exclude recommendations 6, 7 and 8 and authorizes no product implementation in this planning turn.
