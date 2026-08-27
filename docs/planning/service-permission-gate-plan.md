# The per-player service gate: cross-platform `requireServicePermission` with live rechecks

Status: PLANNED, v2 — the from-scratch "best version" of external PR #244
(user direction 2026-08-27), reshaped by the 2-Fable + 2-Opus plan review
(§8, fold applied throughout — all four reviewers converged on the same
grant-liveness and unregistration-composite defects and none disputed the
architecture). Implemented ON TOP of the PR's own commit (§7).

## 1. Intent, extracted from PR #244

One real operator need: **choose who gets LODs** — staged rollouts,
tester-only enablement, keeping the plugin installed without serving stock
clients yet. The PR's design decisions, all KEPT because they are right:

- **A deny lever, not an allowlist.** `requireServicePermission` (default
  false) + permission nodes `lss.use`/`vss.use` declared `default: true`:
  arming the key alone changes nothing for anybody; denial is always an
  explicit negative grant an admin made. No UUID/name list key exists — the
  permission system IS the list.
- **The AND-of-both-spellings deny model.** Both brand spellings are declared
  (Bukkit resolves an UNDECLARED node to the op default, and an LSS↔VSS jar
  swap must keep honoring a revocation), and enforcement requires BOTH — the
  De Morgan mirror of the far-player privacy nodes' grant-model OR: with dual
  `default: true` declarations, an OR would let the untouched spelling
  out-vote the admin's single negative grant and the gate could never deny.
- **Denial rides the DISABLED rung, never silence.** A denied handshake is
  answered with an `enabled=false` SessionConfig in the client's OWN dialect
  (v16/v18/v19 included — the handshake reply construction is the one
  dialect-verified surface) and no registration; silence is the version-skew
  signal and would walk the discovery ladder.
- **Decision-anchored, once-per-session logging** (their `deniedByServiceGate`
  conjunction — the line fires only where the missing grant is what actually
  took the service away).
- The contract discipline: plugin.yml pins, the release_check dual-spelling
  survival pins in both branded jars, the `PlayerServiceGate` seam shape, the
  open-gate-overload landmine documentation.

What the PR leaves on the table — the two axes this plan adds:

**Gap A (platform scope).** Fabric has a de-facto standard
(`fabric-permissions-api`, implemented by LuckPerms et al.) and NeoForge a
native `PermissionAPI`; Paper-only means the feature vanishes on half the
install base and the key cannot move to `ServerConfigBase` (so no `/lsslod
set` row either).

**Gap B (session liveness).** The gate fires at HANDSHAKE only: a revocation
does nothing to a player already in a session, and a grant does nothing to a
player already denied (whose client, correctly, never asks again). For
staged rollouts both directions should apply without a rejoin.

## 2. Design

### 2.1 The shared key + the loader permission seam

`requireServicePermission` moves to `ServerConfigBase` (default false; off =
the handshake path is byte-for-byte pre-gate and no permission backend is
consulted). The node names move to `common`
(`LSSPermissions.SERVICE_LSS/SERVICE_VSS` beside `LSSConstants`) — the one
home xplat, NeoForge and Paper all reference; `PluginYmlContractTest`'s
constant-equality pin re-points there (§8 m7).

Permission reads go through ONE new seam on `LoaderServices`:

```java
/** Whether {@code player} holds {@code node}. {@code defaultValue} is what an
 *  absent/unresolvable provider answers — the service gate always passes TRUE
 *  (the nodes are declared default-true): no provider = serve everyone, the
 *  pre-gate behavior. Implementations never throw; doubt answers the default. */
default boolean checkPermission(ServerPlayer player, String node, boolean defaultValue) {
    return defaultValue;
}
```

- **Fabric (server impl)**: a reflective zero-compile-dep bridge
  (`FabricPermissionsBridge`, the MoonriseReadCompat pattern). PRESENCE is
  probed by `Class.forName("me.lucko.fabric.api.permissions.v0.Permissions")`
  (§8 m10 — providers may shade/JiJ under other mod ids; `isModLoaded` is
  diagnostic only), the static `check(Entity, String, boolean)` resolved BY
  SHAPE, lazy once per JVM, every failure shape → `defaultValue` with a
  once-warned drift message. With the class absent, an ARMED gate warns once
  (at boot AND on a runtime `set` arm — §8 N-2): "requireServicePermission is
  on but no permission provider (fabric-permissions-api) is installed —
  serving everyone".
- **NeoForge**: native (the module compiles against NeoForge): register
  `PermissionNode<Boolean>` statics for BOTH spellings at
  `PermissionGatherEvent.Nodes` with default-true resolvers, UNCONDITIONALLY
  (an unregistered node THROWS at query time, and a runtime `set` arm must
  work; the event fires during server start, before any LSS query — verified
  §8). `checkPermission` maps the string to the registered node INSTANCE
  (`PermissionAPI.getPermission` is node-object-keyed); an unknown string
  answers `defaultValue`, never queried.
- **Paper**: keeps the PR's direct Bukkit gate, but the read becomes
  CONTAINED (§8 F2-M3): a throwing permissible answers the declared default
  (true) with a once-per-session warn — a throw must never escape
  `handleHandshake` into silence, the exact shape §1's reply discipline
  forbids.

`holdsServicePermission` stays the AND of both spellings everywhere, each
read with `defaultValue=true`. A Tier-1 pin asserts every `checkPermission`
call site passes `true` (§8 m7 — a flipped default is a silent server-wide
black-out on the two platforms with no plugin.yml to catch it).

**Honesty scope (§8 F2-M3, folded into README + §3):** this is a fail-open
rollout lever, NOT a security boundary. A dead permission backend is
indistinguishable from "nodes unset" on every platform (both answer the
declared/check-site default) — the gate then serves everyone silently. The
Fabric warn detects only the API class being absent; it cannot detect a
present-but-dead provider. Conversely a provider that answers `false` for
unset nodes denies everyone on arm — the README tells the admin both
signatures ("if arming denies everyone, grant both nodes to your default
group"; the per-session INFO + `denied=` diag are the observables).

### 2.2 Handshake composition (Fabric/NeoForge = the Paper shape, shared glue)

`ServerReceiverGlue.handleHandshake` composes the gate into the SAME
`configEnabled` input the server-wide kill switch feeds — the PR's Paper
composition transplanted, with the identical decision-anchored log
conjunction (outcome DISABLED ∧ config.enabled ∧ servicePresent). The
permission read happens in the receiver (the real `ServerPlayer` in hand)
and flows into the static core through a gate-seam param mirroring the
existing `viaProtocol` seam pair, with an open-gate legacy overload for the
existing tests — AND (§8 O1-M4, the landmine the PR could only document) a
**census pin**: a source-regex contract test asserting all THREE production
receivers (Fabric, NeoForge, and Paper's call site) pass a real gate, never
the open overload (the `FoliaWiringContractTest`/`stampedSiteCensus`
precedent family).

A denied handshake from an ALREADY-REGISTERED player (a live session
re-handshaking after a revocation, or racing one) runs the §2.3
unregistration composite inline — the existing "an EXISTING registration
deliberately survives" rationale covers protocol facts (VERSION_MISMATCH /
NO_CONSUMER), and a permission denial is an ADMIN fact (§8 O2-M3).

### 2.3 Live rechecks (Gap B)

A `PERMISSION_RECHECK_TICKS = 200` (10 s) cadence inside each service's
existing tick (the Paper pump — pinned AFTER `drainLifecycleMailbox()`, the
same registered-but-flip-pending hazard the `set` re-push ordering MAJOR
closed, §8 O2-m2 — and the shared `RequestProcessingService` tick).

**The denied-session memo.** `deniedHandshakes: uuid → (protocolVersion,
capabilities, playerName)` — written under EXACTLY the `deniedByServiceGate`
conjunction (§8 O2-m3: never for NO_CONSUMER/Via/version denials), AND at
revocation-unregister time (§8 F2-M1: the live session's own version +
capabilities — revoke-then-regrant must heal). Lifecycle (§8 F2-M2 — every
clause load-bearing): a SUCCESSFUL registration by any path removes the
entry; the entry is removed on quit/disconnect (both loaders' network hooks,
beside the client-info sweep) and cleared at server stop/plugin disable (the
C1-9 shape); the grant sweep skips currently-registered UUIDs and drops
entries whose UUID is not online; a replay that terminates in any
non-register terminal reply drops the entry (no 0.1 Hz duplicate configs).
The once-per-session denial LOG is a separate latch from the memo (§8 O1-m6:
revocation repopulates the memo, and each revoke→grant→revoke transition
must log once — map-presence alone cannot express both).

**Revocation sweep — CURRENT-dialect sessions only** (§8 F1-M1/O2-M2/O1-M2:
`repushSessionConfig`'s legacy skip is a recorded, test-pinned decision —
"mid-session-config behavior is release-frozen and unverified" — and this
plan does not overturn it). For each registered CURRENT player failing the
gate on TWO consecutive sweeps (§8 F2 M-2 flap hysteresis; context-scoped
grants oscillate): push a per-player `enabled=false` SessionConfig (the
CURRENT 4-field shape — no legacy sender exists mid-session), then run the
**unregistration composite**: `removePlayer` + `farPlayerService.
removeViewer(uuid)` + `regionSummaries.removePlayer(uuid)` — the Paper
departed-player sweep's trio, NEVER a modified `removePlayer` (that is the
dimension-change reuse path; teaching it to shed viewers would break every
dimension change — §8 O2-M1/O1-M1/F1-m1). The dialect mark and v16 identity
are deliberately KEPT (connection-lifecycle facts; the mark is also what the
push read). Legacy (v16/v18/v19) sessions are NOT live-revoked: they keep
serving until rejoin, where the handshake gate denies in their own dialect —
recorded accepted-open with the frozen-behavior rationale. One throttled
INFO per revocation transition. Permission reads run on the pump thread (the
`PaperFarPlayerSnapshots` Folia precedent); a per-player throw is contained
(counts as HOLDING — fail-open) and must not stop the sweep.

**Grant sweep — all dialects.** Runs whenever `deniedHandshakes` is
NON-EMPTY, regardless of arming (§8 unanimous MAJOR: `set
requireServicePermission false` — the staged rollout's final step — must
drain the map, not strand every denied player; a disarmed gate trivially
clears everyone). For each remembered, online, unregistered player now
clearing the gate (or with the gate off): REPLAY the stored handshake
through the production handshake body — the full ladder re-runs (version,
Via, consumer, enabled — never a cached decision), the player registers and
receives its reply in its OWN dialect via the verified handshake-reply
construction (this is how legacy players heal too). Replay mechanics (§8
O2-m4/F1-NITs): the player is re-resolved fresh at execution (Paper: at
mailbox-drain time, through the registrar's DEFERRED reply — an inline reply
reopens the Folia pre-registration gap); the log line is tagged
`(re-offer)`; capability staleness is accepted (any real re-handshake
overwrites the entry through the normal path). Known residual: Paper
`/reload` clears the memo (onDisable), so formerly-denied players heal only
on rejoin there; and Paper's 60 s re-attach prompt never fires for denied
clients (they stop declaring), so it neither helps nor loops.

### 2.4 The client half: a mid-session `enabled=false` tears down cleanly

Today a valid `enabled=false` config on an ESTABLISHED session parks the
live manager un-ticked (memory retained; the cache still saves at
disconnect). The revocation push makes that shape common, so: a valid
same-or-higher-rung `enabled=false` config arriving while a manager exists
retires it with the standard teardown (report undispatched → disconnect →
saveCache) and nulls it, PLUS ends the far-player session
(`FarPlayerClientSupport.onSessionEnd` — proxies must not freeze mid-air,
§8 F1-m2). Placement is BELOW the downgrade guard (§8 O1-m12: Paper's
`/reload` re-attach prompt sends a v16-dialect config carrying
`enabled` at a v20 session — it must keep taking the guard's early return,
never the teardown; pinned). The gate PARKS the governor snapshot and the
world-axis sub-key at this teardown and adopts both on the next enable (§8
O2-m1: the #243 carry must survive a revoke→grant cycle, or the client
re-slow-starts into the BARE cache bucket on an unreadable re-read). The
Xaero bridge needs nothing (its own gate is `isServerEnabled`; queued tiles
flush on re-enable — same world, same data). Released pre-plan clients keep
the parked-manager shape — server-side this is safe (they stop declaring;
stray batches hit the silent `state == null` return), recorded.

### 2.5 Observability + the `set` row

- `service.permission_denied` counter (both platform exporters + BOTH
  exporter contract fixtures and their mocked-counter twins, §8 O1-m8):
  handshake denial transitions + revocation transitions — NOT re-counted per
  re-handshake or per sweep while already denied (pinned).
- `/lsslod diag` gains a conditional `Gate:` line while the key is armed:
  `Gate: requireServicePermission=on denied=<n> provider=<token>` — the
  provider token is a loader fact carried through `LoaderServices` (a
  `permissionProviderToken()` default returning "none"; Paper supplies
  "bukkit"), since `DiagnosticsFormatter` lives in `common` (§8 n18); the
  `DiagData` shape rev follows the MoveTrace/Summary conditional-line
  precedent, with the formatter + Paper command output pins updated.
- `RuntimeSettings` row #12: `requireServicePermission`, strict true/false
  parse (boolean rows carry no clamp — R-2 conformant). Apply-note:
  "existing sessions re-checked within ~20 s; denied players are re-offered
  when granted or when the gate is disarmed". Disarming via `set` (or a
  config reload) leaves the grant sweep draining the memo (§2.3).

### 2.6 What survives from the PR, precisely

Verbatim: plugin.yml's dual `default: true` declarations + comments; the
`PluginYmlContractTest` node/default pins (the constant-equality pin
re-points at the `common` constants); `release_check.py`'s dual-spelling
survival pins + selftest fixtures — EXTENDED (§8 O1-m13/n14): the node
presence + `default: true` VALUE assertions move into the unconditional
`check_paper_jar` (the pair check only runs when a VSS jar exists);
`holdsServicePermission`'s AND; the open-gate overload + landmine doc; the
denial log text; the README row (reworded). Reshaped (§8 O1-m6 — §7's
"nearly verbatim" is scoped to semantics, not signatures): the
`PlayerServiceGate` seam gains the memo deposit (the decoded
version/capabilities live inside the static core, so the seam carries a
deposit callback or the core returns the record), `serviceGateFor` follows,
and `SERVICE_DENIAL_LOGGED` is replaced by the §2.3 memo + separate log
latch — the PR's glue tests migrate accordingly.

## 3. Failure-shape doctrine

Fail-open, everywhere, on purpose — and SAID OUT LOUD (§2.1 honesty scope):
an unresolvable bridge, an absent provider, a throwing permissible, an
unknown node string — all answer the declared default (true) and the player
is served; denial is always an explicit admin act, and this gate is not a
security boundary. The recheck contains per-player throws; the replay
re-runs the FULL ladder; the per-player disable push rides the existing
send-failure containment; sweeps are entirely inert with the gate off and
the memo empty (pinned).

## 4. Implementation steps

1. **Key + constants + seam**: `requireServicePermission` on
   `ServerConfigBase` (Paper field removed; the PR's default-ships-off tests
   survive by inheritance), `LSSPermissions` in common (PluginYmlContractTest
   re-pointed), `LoaderServices.checkPermission` +
   `permissionProviderToken()` defaults, `FabricPermissionsBridge`
   (real-package-name stubs: resolve-by-shape, class-absent, throwing check,
   drift-warn-once, Class.forName presence), NeoForge node statics +
   gather-event registration + string→node map (contract test: registered
   names == shared constants == the literals; the queried nodes are the
   registered INSTANCES). Widen `NeoForgeLoaderSeamContractTest`'s method
   regex to `(?:default\s+)?` and re-derive its count guard, plus explicit
   per-loader override pins for `checkPermission` (§8 O1-M3 — without this
   the whole feature can ship inert with a green suite). Tier-1 pin: every
   call site passes `defaultValue=true`.
2. **Shared-glue gate**: the composition + log conjunction + memo deposit in
   `ServerReceiverGlue` behind the gate-seam param + open overload; the
   THREE-receiver census pin (§8 O1-M4); the inline unregistration composite
   on a denied re-handshake of a registered player (§8 O2-M3). Tests: the
   PR's gate table transplanted (denied → DISABLED reply per dialect, no
   registration; ungated byte-identical; NO_CONSUMER not double-logged AND
   its reply's enabled flag pinned both directions, §8 n17; log-once latch;
   gate off = probe never consulted; contained Paper permissible throw).
3. **Recheck sweeps**: the memo (+ its full two-loader lifecycle: disconnect
   sweep beside client-info, server-stop clear) + both sweeps + the
   CURRENT-only revocation push + composite. Differentials (§8 O1-m11):
   cadence (reads only every Nth tick), two-sweep hysteresis, idempotence
   (still-denied = zero work/logs/counts), revoked player stops being a
   far-player viewer, revocation deposits the live session's version/caps,
   grant replay registers + replies in-dialect (a v16 remembered handshake
   included), replay full-ladder proof (a stored handshake whose rung became
   unservable takes the SILENT rung and drops the entry), replay inertness
   (offline / already-registered / service null), disarm drains the memo,
   gate-off + empty memo = sweeps inert, per-player throw contained,
   Paper sweep ordered after the lifecycle drain.
4. **Client teardown** (`ClientSessionGate`): the §2.4 shape. Tests:
   teardown ordering fires; the downgrade-guard config (v16 frame on a v20
   session) still returns BEFORE the teardown (§8 O1-m12); foreign-version
   keeps the manager (existing pin unweakened); re-enable rebuilds and
   ADOPTS the parked governor + sub-key; far-player session ended.
5. **Observability**: counter (+ both exporter contract files + mocked
   twins), the `Gate:` diag line (+ formatter/Paper command pins), the
   RuntimeSettings row (+ registry test: strict parse, note, count 12).
6. **Docs**: README row reworded for all platforms + the two honesty notes
   (not-a-security-boundary; if-arming-denies-everyone) + Fabric
   needs-a-provider note; CLAUDE.md — the key documented in the
   server-config bullet (§8 O1-m9), the Paper paragraph keeping only the
   Bukkit-specific mechanics; this plan committed.

## 5. Validation

Full Tier 1 both platforms + Tier 2 + Tier 3 + NeoForge build (gate
default-off: every harness baseline untouched, no soak/gametest config
change — asserted, not assumed). Live checks (user-scheduled):
- Paper + LuckPerms: revoke mid-session (≤~20 s, one INFO, far players
  stop), re-grant (≤10 s, re-offer, terrain resumes), disarm with denied
  players online (all heal);
- Fabric + a LuckPerms-Fabric-class provider: the same three;
- Fabric with NO provider, gate armed: the once-warn + everyone served;
- **NeoForge + a real permission mod, nodes UNSET: still served** (§8
  O1-M5 — the default-resolver contract is third-party; this check is the
  arming precondition on that platform, best-effort tier notwithstanding).

## 6. Backports

Later, with the #243 backlog; the NeoForge PermissionAPI surface re-verified
per line.

## 7. Relationship to PR #244 / landing

The problem statement, the deny-lever + dual-spelling + AND model, the
DISABLED-rung reply discipline, the decision-anchored once-per-session log,
the plugin.yml + release_check contract work, and the Paper gate seam shape
are OowhitecatoO's and survive (semantics verbatim, signatures widened per
§2.6) — credit in the changelog. This plan adds the loader seam, the
Fabric/NeoForge rungs, the live rechecks, the client teardown, and the
observability/set row.

Landing follows the #243 §10 strategy: branch from the PR head (4eea9377),
merge main in (never rebase), implement as commits on top with
`Co-Authored-By: WhiteCat <whitecatx6@gmail.com>` where derived, push to the
fork branch `service-permission-gate`, update the PR description, comment
the rework rationale (short, plain, external style), merge PR #244 with
`--merge`.

## 8. Plan-review fold (2 Fable + 2 Opus, 2026-08-27)

All four: NEEDS-REWORK, architecture unchallenged. Independent convergence:
THREE reviewers on the disarm-strands hole and the grant-liveness gap (now
§2.3's memo-deposit-at-revocation + always-draining grant sweep), THREE on
the "standard removePlayer path" misnaming (now the explicit composite +
the never-touch-removePlayer rule), TWO on the legacy-push contradiction of
the pinned repush doctrine (resolved: CURRENT-only revocation, all-dialect
grant replay via the handshake surface, legacy revocation = rejoin-only,
recorded). Fable-2's fail-open audit produced the §2.1 honesty scope (the
contained Paper read, the warn's true detection power, the
not-a-security-boundary sentence); Opus-1's contract audit produced the
three structural test MAJORs (the default-method-invisible completeness
regex, the three-receiver census, the NeoForge unset-node live check) and
the constants-home/latch-split/exporter-fixture/release_check-unconditional
corrections; Opus-2's protocol audit verified the DISABLED reply is
dialect-correct on all four rungs and that even a v0.6.2 client stops
declaring on mid-session enabled=false, contributed the
denied-re-handshake-of-registered-player composite, the mailbox-ordering
pin, the write-predicate scoping, and the governor/sub-key carry. Verified
sound and not re-litigated: the seam install points, the glue transplant
target, the Folia pump-thread permission-read precedent, the §2.4 premise,
row #12, xplat purity, R-2 conformance.

## 9. Implementation-review fold (2 Fable + 4 Opus, 2026-08-27) — as-built corrections

All verified findings folded; the deltas vs the plan text above, normative:

- **The §2.1/§8 N-2 no-provider warn lives in the recheck sweep**, not at
  boot/set-arm sites: one site (keyed on the LoaderServices provider token
  answering "none") fires within one recheck interval (~10 s) of EITHER arm
  path. All three doc surfaces say "within one recheck interval of arming".
- **Far-player target prefs are RETAINED across viewer sheds**
  (`FarPlayerBroadcastService.retainedPrefs`): the revocation composite's
  `removeViewer` no longer destroys an online player's shareSelf opt-out (the
  E2 prefs-carrier rule — the review's privacy MAJOR), `subscribeViewer`
  seeds a re-subscription from the retained prefs so the grant re-offer
  resumes far-player serving without a client re-handshake, `onPrefs` retains
  receipts from unsubscribed (gate-denied) senders, and the new
  `onDisconnect(uuid)` — wired at every true connection end on all three
  loaders — is the only sweep.
- **The grant sweep SKIPS registered UUIDs** (never `onRegistered`-clears
  them): the Folia deposit-vs-queued-composite race would otherwise wipe a
  just-deposited memo and strand the player past its own revocation.
- **The offline grant-sweep branch sweeps the whole session state**
  (`onDisconnect`, not `takeDenied`) — the log latch and streak are
  session-scoped too. Paper's departed-player sweep and quit-race mailbox
  Remove sweep the gate state as well.
- **`onRegistered` no longer resets the revocation streak** (registerPlayer
  is the dimension-change reuse path; a reset there let a portal-hopping
  player outrun the hysteresis forever); a DISARMED sweep clears all streaks
  so a re-arm restarts the two-sweep hysteresis.
- **Paper's null-replayer guard precedes `takeDenied`** (a missing replay
  wiring retains the memo instead of draining it), the replay frame is built
  by `PaperPayloadHandler.encodeHandshakeFrame` (round-trip-pinned), and the
  production `handshakeReplayer` wiring + both tick cadences are
  source-pinned in `LoaderPermissionSeamContractTest`.
- The revocation INFO names both node spellings; the re-offer INFO
  distinguishes grant from disarm; `ClientSessionGate.onDisconnect` clears
  the park; the Paper permissible-throw warn latch is CAS; the bridge test
  stub carries the CommandSource-shaped decoy (the by-shape discriminator is
  no longer a vacuous pin); release_check's node regex tolerates trailing
  comments/quotes; README + the set apply-note carry the legacy-dialect
  carve-out. Accepted-open (recorded, not fixed): the Folia
  two-opposite-handshakes drain-window inversion (self-heals via the 60 s
  re-attach prompt — documented at `enqueueServiceGateUnregister`), the
  once-per-JVM (not per-session) throw warns, and the grant-probe-vs-replay
  double-count corner.
