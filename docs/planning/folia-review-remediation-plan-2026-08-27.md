# Folia implementation review — remediation plan (2026-08-27)

Six-agent review (3 Fable + 3 Opus) of the ENTIRE featureset as it runs on Folia,
main @ 5f4275ab (post #243/#244). Lenses: pump+lifecycle, regionized data path,
cross-thread memory safety, Folia API conformance, feature-completeness matrix,
test coverage + the experimental exit criterion. All findings below were verified
in code by their reporting agent; the four highest-impact single-witness claims
(R1, R3, R4, and the probe-arm gap) were independently re-verified at the exact
lines during compilation. STATUS (2026-08-27, feat/folia-hardening): sections A, B, and D are
IMPLEMENTED as recommended — R1 (the published-want-set probe arm), R2/R7
(per-player containment, fail-HIDDEN direction chosen for both the permission
and vanish reads — R18's counter therefore skipped), R3 (onRegistered moved to
the handshake register paths), R4 (connection-epoch-guarded Remove belts), R5,
R8 (retract-guard seam + deterministic differential, mutation-verified, + the
3-thread stress), R9-R13 (the batteries + the generalized pump-only census +
the reflective-scheduler scan), R14, R15, R17. Deferred with rationale: R6
(measure-first — the gauge belongs to the C-program's instrumented soak), R16
(a user decision the plan poses), and the whole C-program (its own arc: the
multi-region harness, scenario-set expansion, CI-lane decision).
store-second-join VALIDATED on Folia (first ever run: PASS 0/0, 2026-08-28) and on Paper (PASS 0/0), and joined BOTH Bukkit `all` lists — the store SERVE path is now soak-covered on Folia (single-player; the multi-region caveat and the R16 fresh-install question remain with the C-program). Live validation at landing: Folia fresh-backfill + warm-rejoin + store-second-join and Paper store-second-join, all 0 violations 0 warnings, with the R1 arm live.

## Verdict

**Zero MAJORs across all six lenses.** The Folia core — the pump/mailbox
discipline, the hold-release probe CAS ladder, region-thread generation
completions, disk reads, the dirty chain, and every cross-thread structure —
was traced clean; three lenses explicitly verified the load-bearing
single-thread contracts hold. The "experimental" label remains justified for
exactly the recorded reason: the concurrent multi-region regime has never been
produced, and several young surfaces (far players on Paper, the gate's Folia
corners, the retract guard) are prose-covered rather than test-covered. The
work splits into: **A** functional hardening (7 items), **B** the test program
(6 items), **C** the multi-region exit-criterion harness (the path out of
"experimental"), **D** observability + docs (5 items).

---

## A. Functional hardening

### R1 — Restore probe coverage on Folia: the missing published-want-set arm
**Found by:** feature-matrix (minor) + API-conformance (NIT), independently; re-verified.
**Defect:** the sync path probes from the mailbox OR `state.peekWantSet()` every
tick (the arm that "covers the other ~19 ticks of each second");
`holdAndScheduleRegionProbe` (PaperRequestProcessingService:1734-1735) sources
ONLY the mailbox — `peekWantSet` has exactly one caller in the tree (:1638, sync
path). On Folia the probe window advances at the client's 1 Hz declaration
cadence, ≤512 positions per declaration; routing cycles 2..N run with zero probe
coverage, so loaded chunks take disk reads (IO + freshness cost), and the
recorded gen-disabled loaded-never-saved NOT_GENERATED park widens from a
budget-exhaustion corner (Paper) into the steady state for any want-set larger
than 512 or the 200 sync slot cap (Folia).
**Fix shape:** on ticks where a player's mailbox is empty and `heldForProbe` has
no entry, schedule a budgeted region probe over `state.peekWantSet()` (same
512/player cap, same ownership gating, position-keyed results as today — the
consume path needs no change since probes are position-keyed, not batch-bound).
Alignment with the held-batch snapshot is NOT required for this arm (the sync
path's published-want-set probes have the same properties). Add the
`service.in_memory` Folia-vs-Paper baseline measurement to the wrs/fresh-backfill
comparison to confirm the recovery.
**Risk:** medium — touches the most Folia-specific scheduling code; must not
re-open the wrong-batch binding the hold-release exists to prevent (it cannot:
position-keyed). **Effort:** ~0.5-1 day + a `RegionProbeSchedulingTest` battery.
**Alternative (if deferred):** record the degradation honestly (the ":1614
'equivalent' javadoc is wrong today" — see R17) and accept the widened
NOT_GENERATED corner in CLAUDE.md. Not recommended: gen-disabled Folia servers
park live terrain permanently at steady state.

### R2 — Contain the far-player per-player reads (permission + the pass)
**Found by:** API-conformance (minor) + feature-matrix (NIT), independently.
**Defect:** `PaperFarPlayerSnapshots:94-96` reads two `hasPermission` nodes bare
(stock `PermissibleBase` is a plain-HashMap check-then-act; Folia recalculates
on the TARGET's region thread while the pump reads) — a raced NPE aborts the
snapshot loop for ALL players via the whole-pass catch (:1893), going dark one
interval per throw with a once-per-session warn; a raced miss leaks one frame of
a hidden player (`default: false` node). The adjacent `isVanished` read IS
contained; the service gate's two reads ARE contained.
**Fix shape:** per-player try/catch inside `snapshot()` covering the two
permission reads + vanish, failing toward `hidden=true` for the PERMISSION half
(privacy-safe direction: a raced read hides rather than leaks — note this
inverts the vanish bridge's documented fail-open, deliberately, because the
node's semantics are "hide me") — or, minimum, fail-per-player instead of
fail-pass. Decide the direction explicitly in review.
**Risk:** low. **Effort:** hours, + the R10 FARP test battery pins it.

### R3 — The dimension-change register wipes a fresh denial memo (gate, Folia)
**Found by:** pump+lifecycle (minor); re-verified.
**Defect:** `registerPlayer` (:1055 `serviceGateState.onRegistered`) is also the
dim-change reuse path (:1503-1504). A denied mid-session re-handshake on a
region thread deposits the memo + queues the composite; a dimension change
crossing the same pump tick runs registerPlayer BEFORE the composite drains →
memo+latch wiped, composite then unregisters → disarmed client, empty memo, no
re-offer, heals only on rejoin. The #244 §9 fold protected the STREAK from this
exact reuse path but left the memo/latch clear.
**Fix shape:** split the semantics — `onRegistered` keeps clearing memo+latch
ONLY when invoked from the handshake register drain (an explicit
`onHandshakeRegistered` call there), and the dim-change cycle calls plain
`registerPlayer` without the gate clear; OR make the composite's runtime task
re-deposit-aware. First option is cleaner and mirrors the §9 streak reasoning.
**Risk:** low (one call-site split + ServiceGateStateTest/PaperServiceGateSweep
differentials). **Effort:** hours. Reachability is low (sub-tick window), but
the failure is silent-permanent — same class the §9 skip fix closed.

### R4 — The late mailboxed Remove wipes a fast-rejoiner's session state
**Found by:** pump+lifecycle (minor); re-verified.
**Defect:** the quit-race mailbox Remove drains up to a pump tick (or more, on a
lagging Folia global thread) after the quit; its belts (:1017 gate
`onDisconnect`, :1020 `regionSummaries.removePlayer`) then wipe state a FAST
REJOIN's region threads already wrote: (a) a fresh denial memo → stranded
disarmed rejoiner; (b) the summary request + `requestedThisSession` eligibility
→ summaries AND stamped-up_to_date dark for the whole dimension visit (the
client requests only at dimension entry, fire-and-forget).
**Fix shape:** make the mailboxed Remove's belts session-generation-aware: stamp
a monotonic per-UUID connection generation at handshake ingress (region thread,
CHM), carry the generation in the Remove event, and skip the belts when the
current generation is newer. The quit hook's immediate sweeps (which run before
any rejoin can handshake) stay unconditional. Alternative: drop the gate/summary
belts from the mailboxed twin entirely and rely on the quit hook + departed
sweep — REJECTED unless the quit-race analysis shows the twin is redundant for
these two structures (the twin exists for quits whose event raced service
absence; verify before choosing).
**Risk:** medium (touches the quit-race guard — heavily reasoned code); needs
the race enumerated in the test battery. **Effort:** ~0.5 day.

### R5 — Contain the one unguarded scheduler call (shutdown window)
**Found by:** API-conformance (NIT).
**Defect:** `regionTaskScheduler.schedule` (:1741 → `EntityScheduler.run`) has
no try/catch; a plugin-manager disable from a region thread between the
`shuttingDown` check and this call throws `IllegalPluginAccessException` out of
`processPlayerLifecycle`, aborting the rest of that tick's per-player pass. The
sibling (`PaperChunkGenerationService`) documents AND contains the same type.
**Fix shape:** the sibling's containment, verbatim. **Effort:** minutes.

### R6 — DirtyColumnTracker's global monitor as a cross-region convergence point
**Found by:** API-conformance (minor); correctness intact, magnitude unmeasured.
**Defect:** every region thread's block events block on one monitor; the pump's
`drainDirty` holds it across an O(dirty-set) copy each broadcast interval. On a
build-heavy multi-region server, every region's block handling stalls for the
copy — a Folia-only cross-region tick-latency coupling.
**Fix shape:** MEASURE FIRST (this is exactly what the C-program's multi-region
soak + a `dirty.drain_lock_micros` gauge would quantify); if real, shard the
tracker per dimension (drains are already per-dimension) or swap the mark set to
a concurrent accumulation with a swap-on-drain. Do not restructure ahead of a
number — the monitor is uncontended on Paper and the design is sound.
**Effort:** gauge = hours; shard = ~1 day if warranted.

### R7 — Vanish-bridge fail-open + latched warn under Folia
**Found by:** API-conformance (minor, medium-low confidence).
**Defect:** a `LazyMetadataValue` whose callable touches region-owned state
throws on the pump under Folia (same callable succeeds on Paper's main thread);
the catch answers "not vanished" forever with one warn ever — a vanished staff
member is broadcast for the rest of the run.
**Fix shape:** decide the direction: (a) fail-CLOSED for vanish (hidden on
throw — consistent with R2's direction), or (b) keep fail-open but make the warn
once-per-PLAYER and surface a `vanish_read_errors` counter in the far_players
diag group so the operator can see it. (a) recommended: the far-player feature
hiding too much is recoverable; leaking a vanished admin is not.
**Effort:** hours.

---

## B. The test program (Folia-specific coverage)

### R8 — Concurrent `republishHeldBatch` stress test — TOP GAP
The retract guard (`AbstractPlayerRequestState:341-345`) — the armor on the
genuine three-thread Folia race — has ZERO coverage: deleting it leaves the
suite green. Multi-threaded loop (pump republish × offer × take), asserting the
mailbox never regresses to an older batch and `received == taken + superseded`
balances. Cheap: the `BacklogReplaceTest` TestState rig exists, no MC types.

### R9 — Region-probe race + multi-player batteries
All 22 `RegionProbeSchedulingTest` cases run the region task on the JUnit thread
with the pump idle — the one interleaving that cannot race. Add: (a) the task on
a real thread concurrent with `tick()` (N iterations; no lost/cross-dim batch,
`regionProbeResults` merge-vs-remove consistent); (b) an N≥3 multi-player case
(per-player `heldForProbe` isolation, departed sweep with others in flight,
budget interaction — also the only cheap pin for the recorded clustered-cap
corner); (c) with R1: the peekWantSet-arm coverage differentials.

### R10 — Paper far-player module tests (currently ZERO)
`PaperFarPlayerSnapshots` + `tickFarPlayers` have no paper-module coverage at
all: snapshot fields, the cadence counter, `sendFarPlayerFrame`, the containment
latch, R2's per-player containment, the permission/vanish reads against a
throwing permissible, offline `getBukkitEntity()`. The recorded cross-region
read corner is prose-only AND structurally unsoakable — this battery is its only
realistic gate.

### R11 — Generalize the pump-only-surface contract test
`FoliaWiringContractTest`'s mailbox pin covers two methods on one class-name
prefix. Generalize: every class reachable from a Bukkit listener/command/
messenger entry point is forbidden direct refs to a pump-only method list
(`registerPlayer`, `removePlayer`, `repushSessionConfig`,
`runServiceGateSweeps`, `unregisterForServiceGate`, `getFarPlayerService`
mutators). Today a new listener calling `registerPlayer` from a region thread —
the exact D3 bug the mailbox exists to prevent — passes green.

### R12 — Wiring-test blind-spot notes + cheap extensions
Record (in the test's javadoc) what the constant-pool scan cannot see:
reflective scheduler use, non-scheduler Folia-fatal APIs, thread-of-call,
shaded code. Cheap extensions worth taking: scan for `org/bukkit/Bukkit
.getScheduler` name strings; assert the scan runs against the shadowJar's
own classes on CI (or record why not).

### R13 — Race-class differentials for R3/R4 + gen-completion concurrency
Pin the R3 split (dim-change keeps the memo; handshake register clears it) and
R4's generation-stamped Remove in `PaperServiceGateSweepTest` +
`PaperRequestProcessingServiceTest`; add a two-thread `completeAsyncLoad`
concurrency smoke (the multi-writer counters' contract is atomics-by-argument
only today).

---

## C. The multi-region exit criterion (the path out of "experimental")

Adopt the test-coverage agent's harness design (full detail in its report),
sequenced so each step is independently valuable:

1. **Region-identity instrument first** (cheap, unblocks everything): record the
   executing thread identity of each player's region-probe task into server
   snapshots (`players[].region_thread`); premise law: ≥2 distinct sustained
   thread identities. Without this, any "multi-region" scenario is
   unfalsifiable — two players MERGE into one region when footprints meet, and
   LSS's own generation tickets grow footprints all run.
2. **Probe/hold observability counters:** `service.hold_release_drops` split
   from `superseded`; probe scheduled/published/consumed/missed counters (also
   quantifies R1 and the recorded ping-pong corner, today unobservable).
3. **Harness surgery:** a second soak-client Loom run (own runDir + username —
   offline-UUID collision otherwise), `CLIENT_PIDS` arrays in mc-run.sh, a
   `CONCURRENT_CLIENTS` scenario knob, `client-pN-runN.jsonl` naming, an
   `all-joined`/name-keyed driver anchor policy (join anchors collide today).
4. **Checker generalization:** A1/A2/A5 to sum-form over concurrent clients;
   quiescence = every client still; the premise law; and the **client-side
   probe content hash** (decoded-column hash vs server `probe_hashes`) — the
   only instrument that can detect a torn cross-region palette read, the
   failure the regionized design exists to prevent; today invisible end to end.
5. **The scenario:** both clients spawn together, then teleport P2 several
   thousand blocks; generation DISABLED for iteration one (footprint-merge
   trap), gen-enabled variant after the split proves stable. Success = premise
   law + generalized conservation + probe hashes + far-player counters under a
   cross-region viewer/target split (needs a `-Dlss.soak.farplayers` opt-in
   mirroring the summaries lever — far players are structurally unsoakable
   today).
6. **Folia scenario-set expansion** (independent of 1-5, cheap):
   `teleport-prune` (a real region HANDOFF — the cheapest unclaimed milestone),
   `generation-capacity-stress`, `generation-disabled`, `disk-read-gate`,
   `enabled-false`, `dimension-rejoin-warm` — six of the 18 scenarios that have
   never run on Folia, each exercising a Folia-specific path.
7. **Decide the CI question:** there is NO Folia lane in CI; every runtime claim
   is a manual local run. Minimum: a scheduled (not per-PR) workflow running the
   Folia five; or record the decision not to.

Label policy: "experimental" is retired only when 1-5 are green plus the
R10 battery exists — matching the recorded D7 exit criterion, now with a
concrete falsifiable definition of "multi-region".

---

## D. Observability + docs

- **R14** — doc drift: CLAUDE.md says four Folia soak scenarios (it's FIVE incl.
  warm-rejoin-summary) and 22 scenarios (23 since hybrid-boundary);
  store-second-join is Folia-permitted but in no `all` list — add or remove.
- **R15** — the Paper config silently accepts the inert `lodStoreBackfill*`
  keys: one advisory line (validate() or the config echo) naming the deferral.
- **R16** — Folia + fresh install = store ON (fresh-create hook) with only a
  transition WARN, while the store is UNVALIDATED on Folia: decide — either run
  the store scenarios on Folia (C6 covers store-second-join) or gate the
  fresh-create default to "off" under Folia until validated.
- **R17** — comment/doc accuracy: the ":1614 'sync path's equivalent'" javadoc
  (it is strictly narrower — R1); the ticket-drain dim-mismatch comment (a
  same-tick flip reaches it before any removal; one-tick slot hold); name
  doubles in PaperCommands' stale-tolerable audit note (torn 64-bit diag read).
- **R18** — counters from C2 double as production diagnostics; add
  `far_players.vanish_read_errors` (R7b) if fail-open is kept.

## Suggested sequencing

1. **Quick wins** (one sitting): R5, R17, R14, R15, R8.
2. **Correctness batch** (one PR): R2 + R7 (one direction decision), R3, R4,
   with R13 differentials.
3. **R1 + R9** (one PR): the probe arm + its battery, with the in_memory
   baseline measurement.
4. **R10-R12** (one PR): the coverage program.
5. **C-program** as its own arc (instrument → harness → scenario → label
   decision), R6 measured inside it, R16 decided by its store leg.
