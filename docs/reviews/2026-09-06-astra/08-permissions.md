# Lens 08 — service permissions, far-player privacy, x-ray masking

Reviewed tree: `/home/vox/projects/lss-lines/1.21.1`, content-identical to merged `1b544494` (parent verified), MC 1.21.1 Fabric / NeoForge / Paper. Review and plan only; no repository or runtime changes. Astra, 2026-09-06.

## Result

No additional ordinary stock-operation defect established in the service permission gate, target visibility ladder or x-ray filter. Existing confirmed findings are not duplicated here: interrupted store mask-drop (03/parent), runtime `farPlayers off` retaining ghosts (07 FP0), master client toggle (04), late-reader session ownership (02).

One narrower **P2 lifecycle finding**, conditional on the single-plugin hot reload which the implementation explicitly claims to support. It does not affect a regular server restart or an all-plugin reload which replaces LSS's own classloader too.

## PRIV1 — P2: LibsDisguises hot reload still binds the previous plugin's API and disguise registry

**Locations:** `paper/src/main/java/dev/vox/lss/paper/LibsDisguisesBridge.java:71` (default resolver selects `LibsDisguisesBridge.class.getClassLoader()`), `:159`–`:169` (plugin-instance change clears the handle and then calls that same resolver), `:148` (invokes the rebound stale handle). Visibility consequence: `PaperFarPlayerSnapshots.java:116`–`:119`; common `FarPlayerBroadcastService.java:516`.

**Concrete trigger:** Paper runs LSS with enabled LibsDisguises. At least one far-player snapshot resolves `DisguiseAPI` through the surviving LSS classloader. An administrator replaces/reloads **LibsDisguises alone**, producing a new plugin instance and classloader, then disguises a player in that new plugin instance. LSS remains loaded. This is the precise PlugMan-style lifecycle the bridge's javadoc (`:33`–`:38`), reset branch and CLAUDE.md far-player description say they handle.

**Defect:** plugin identity changes, but the resolver's classloader does not. Java retains the already-initiated `DisguiseAPI` class for that LSS loader. Repeating `Class.forName(name, false, survivingLssLoader)` returns the old API `Class`, so unreflecting again binds the old plugin's static registry. Clearing a `MethodHandle` cannot clear a JVM classloader's initiated class identity. This is not resolve failure or API drift: the old API resolves successfully and can answer a clean `false` indefinitely, so the existing throw-to-hidden containment never fires.

**Impact:** newly disguised players can again be advertised by real UUID/name/position to far-player clients despite the replacement LibsDisguises instance actively rewriting their vanilla spawn. Stale positive entries can over-hide players too. A full restart / replacement of LSS heals it. No claim that ordinary first boot or normal disguise toggles on one plugin instance fail.

**Evidence/regression:** external Paper JUnit source:

`paper-probes/dev/vox/lss/paper/LibsDisguisesReloadReviewTest.java`

`changingPluginInstanceMustReadTheNewPluginsDisguiseRegistry` loads the actual production bridge bytecode under an isolated LSS loader; **its production default `classResolver` remains untouched**. It loads the existing real-package-name API test stub under two distinct plugin loaders, verifies the old instance reports no disguise and the replacement reports the active disguise, then changes the plugin identity and loader routing while LSS remains loaded. The final assertion requires the production bridge to report the new disguise. Parent ran the probe: **RED at the intended assertion, line 79** (expected `true`, actual `false`), after the replacement API control correctly returned `true`. Evidence: `disguise-reload-probe.xml` and `disguise-reload-probe.log`. This is a deterministic JVM/classloader proof of the claimed lifecycle; no live PlugMan server operation was performed.

Fixture note: the injected `PluginProbe` uses each plugin loader object as its identity token, which is sufficient for the current Object-based implementation. A final regression for a fix using `plugin.getClass().getClassLoader()` must supply a plugin-instance fixture actually defined by each respective plugin loader.

The existing `LibsDisguisesBridgeTest` hot-reload case at `:82`–`:99` replaces the injected resolver with one that throws. That proves the resolver is *called* again, but does not prove the production resolver can escape the previously initiated API class.

**Fix/plan:** resolve the API using the enabled **current plugin instance's defining classloader** (the same identity whose lifetime owns the handle), with initialization still deferred as intended. Pass plugin/loader into the resolver seam rather than capturing LSS's loader permanently. Keep the existing enabled-plugin gate, per-instance negative cache and thrown-read-to-hidden containment. Regression must cover real distinct API `Class` objects and opposite registry answers across a reload, plus a stable same-instance fast path, disabled-plugin behavior and the existing exception/drift policy. Alternatively, withdraw the claimed single-plugin hot-reload support and make the required full restart explicit; that is a product-scope decision, not a code correctness fix for the presently claimed behavior.

**Confidence:** high: source mechanism and intended-assertion RED under actual production bridge bytecode agree; test status recorded above. Trigger is an explicitly claimed special lifecycle, **not** generic normal gameplay. Prioritize below established ordinary-session failures if single-plugin hot reload is not a supported administration operation in practice.

## Coverage and accepted decisions (not findings)

### Service permission gate

Read `PlayerServiceGate`, `ServiceGateState`, shared `ServerReceiverGlue` handshake, xplat and Paper sweep/composite methods, both loader ingress/disconnect hooks, `FabricPermissionsBridge`, `LSSNeoPermissions`, Paper gate wiring and declaration/seam tests. Relevant tests include `ServiceGateStateTest`, `PaperServiceGateSweepTest`, `FabricPermissionsBridgeTest`, `LoaderPermissionSeamContractTest`, `LSSNeoPermissionsContractTest`; normative plan `service-permission-gate-plan.md` and CLAUDE's gate paragraph.

- `requireServicePermission` is an optional default-off **deny** lever. BOTH default-true `lss.use` and `vss.use` must hold, so a deny on either spelling works across brands. It is intentionally fail-open on absent/throwing providers and is expressly not a security boundary. Fabric API presence without a functioning provider cannot be distinguished and is accepted.
- Denial replies in the client's own wire dialect through the existing disabled rung; version/Via/no-consumer ordering is deliberate. Accepted no-consumer/Via cross-handshake registration residues are not new permission vulnerabilities.
- Current clients alone are live revoked, after two consecutive 200-tick failing sweeps. Legacy v16/v18/v19 sessions intentionally heal at rejoin. Flapping context grants, delayed revocation and legacy persistence are test-pinned rollout semantics.
- Composite revocation sheds service, far-player viewer and summary subscription, but preserves online target privacy prefs and denied replay information. Successful handshake registration clears the denied episode; dimension reuse preserves revocation streaks and memo. Disarming clears streaks and grants remembered sessions through the full handshake ladder. Disconnect clears session state.
- A failed re-offer transport can leave the memo dropped and requires rejoin; that is documented alongside the ordinary lost-config behavior, not a new finding.

### Far-player privacy

Read common `FarPlayerBroadcastService` membership/update flow and retained prefs, Fabric/NeoForge and Paper snapshots, Melius bridge/wiring, LibsDisguises bridge, client prefs carrier and receipt site. Read privacy, opt-in/opt-out, excludes, hidden-node, vanish, retained-pref and withheld-send tests, plus current render-hardening and issues-275-282 plans.

- Visibility is checked before roster membership: self/dimension/alive/spectator/invisible/vanish, ring bounds, permission hidden/exclusion and target sharing policy. A hidden target is removed from membership; withheld roster sends do not commit unsent removals and retry. No continued-motion privacy leak found in this flow.
- Mode `on` deliberately includes vanilla/nonconsenting-by-default targets until an explicit opt-out arrives. Mode `opt-in` requires explicit sharing. Reconnect drops connection-scoped prefs and starts from the mode default again (explicit test). These are accepted product semantics, not a claim of persistent account-level consent.
- Target prefs survive viewer revocation and no-consumer downgrade; denied/unsubscribed senders' prefs are retained. The client sends prefs even after a disabled session-config reply, and the far-player capability remains composed independently of the receive toggle to carry `shareSelf=false`.
- The two hidden nodes are a default-false **grant** model (OR), intentionally unlike the service nodes. Fabric/NeoForge loader seam failures return the passed false and therefore fail visible; the limitation is expressly recorded and pinned. Paper's per-target permission/metadata catch instead fails hidden.
- Melius argument order is correct (target as actor, viewer as observer), with per-target/tick memoization. Absent API is visible; drifted or throwing API hides; partial per-observer drift hides vanished targets without blacking out everyone else. This policy differs intentionally from permission-seam fallback.
- Paper metadata vanish and LibsDisguises are global hide-for-all rungs. Per-viewer disguise fidelity is deferred. Enabled API absent/drifted visibility fallback is explicitly chosen; throwing reads are wrapped and hide without latching. I do not reclassify those chosen directions as new defects.

### X-ray masking

Read shared policy/fingerprint, both mask managers/filters, AntiXray controller bridge, live/disk choke points and captured disk mask, dirty save/seed paths and store environment snapshot wiring. Relevant suites: `XrayMaskPolicyTest`, twin manager/filter tests, `AntiXrayCompatTest`, masked golden/parity/transcode coverage. Read `antixray-compat-design.md` including its mode-2/3 addendum and CLAUDE anti-xray guidance.

- `auto` adopts detected engine mode-1 states/height; `on` masks with engine values when available and LSS fallback otherwise; `off` explicitly disables masking. Engine modes 2/3 use the LSS list at engine height to avoid known bulk-terrain flattening. Custom engine-only blocks missing from that fallback are a documented adoption limitation, not newly discovered behavior.
- Per-world decisions are service-lifetime cached, with a bounded transient-null reprobe on xplat. These mask keys are restart/config-load surfaces, not live `/lsslod set` controls. Engine reload without rebuilding LSS's manager is not advertised live remasking.
- Hidden states are rebuilt into a replacement-seeded palette, pruning the historical unused-palette ore oracle. Replacement is deterministic and non-air; sources are never mutated; straddling sections keep cells at/above cutoff. Paper rounds adopted height to the engine's section boundary.
- Serializers apply masking on live/probe, generation and disk paths; disk work captures the immutable mask at submission. Dirty comparisons hash the same masked serialization, and store deposits consume serialized served bytes. The separate interrupted mask-drop persistence finding belongs to the parent's store lens.
- Fingerprints identify sorted hidden-state identities and cutoff, intentionally surviving registry permutation. Filler tie changes under ID permutation are cosmetic, explicitly accepted. Store startup publishes the mask manager before fingerprint capture, with a nonce for unsettled outcomes.
- Historical client caches cannot be recalled. Light nibbles, cave shapes and cells above cutoff remain real; exposed distant ores are blanket-filled. Lit-redstone residue and no per-player anti-xray exemption are expressly accepted. No new finding is based on these design limits.

## Validation limits

No Gradle task, Minecraft runtime, live permission provider or live plugin reload was launched by this reviewer. Existing tests were read rather than represented as newly run. Parent owns the external probe execution and full validation scheduling. This pass is source/test evidence of behavior, not a fresh three-loader live compatibility certification.
