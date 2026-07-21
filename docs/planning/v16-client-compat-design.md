# Client-side v16 backward compat — design spike (v0.7.0 client ⇄ v0.4.x server)

**Status:** IMPLEMENTED — Tier A (load-only), 2026-07-20, branch `feat/v16-client-compat`.
Mirrors the existing SERVER-side shim (`docs/planning/v16-compat-design.md`,
`common/compat/V16CompatManager`), inverted. Sections 2–8 below are the ORIGINAL plan; read
the "As-built deltas" box first — a few things landed differently, and one review-driven
addition (the downgrade guard) is not in the plan at all.

**Goal.** Let a v0.7.0 Fabric **client** (protocol 18) render LODs from an **old server**
running protocol 16 (releases **v0.4.0 … v0.6.2**), the exact reciprocal of the shipped
server shim (which lets old *clients* talk to a v0.7.0 server). Hard requirement from the
maintainer: **isolate every compat path so the normal v18 client behavior is byte-for-byte
unchanged and unrisked.** Prefer a wire-edge adapter; do not touch the core request loop.

---

## As-built deltas (implementation vs. this plan)

The core loop was indeed untouched (Tier A pass-through — `LodRequestManager` has zero
production changes), and the isolation held. Where the code diverges from §§2–8:

- **No `ClientV16CompatManager` class.** The §5 diagram shows one manager holding everything;
  the implementation splits it into `ClientSessionGate` (discovery timer + `isV16Server` + the
  version-acceptance ladder) and `V16ClientWire` (the one static column-decode flag).
  Functionally equivalent, arguably cleaner — but the §5 diagram names a class that does not exist.
- **Discovery delay is 100 ticks (5 s), not 60/3 s** (`V16_DISCOVERY_DELAY_TICKS`). Widened for
  margin against a healthy v18 server whose SessionConfig is briefly delayed by a join-time stall.
- **NEW — downgrade guard + v18 re-assert (not in the plan; closes the review's headline finding).**
  Two independent review agents found that a slow-join v18 server (SessionConfig later than the
  discovery delay) makes the client fire the v16 fallback, which a compat-on v18 server *answers*,
  silently downgrading a healthy session to v16 load-only for its whole duration. Fix in
  `ClientSessionGate.onSessionConfig`: a v16 config arriving once a v18 session already exists is
  never a real legacy server (a server is one dialect); the client ignores the downgrade and
  re-announces v18, which the server's existing shed-on-v18-handshake path turns into a full v18
  restore. Bounded (the reply is a v18 config, which never re-enters the branch — no ping-pong).
- **Byte-0 debug demotion is UNCONDITIONAL** (`LSSClientNetworking.dispatchBatchResponses`), not
  gated on `isV16Server` as §3c suggested. A v18 server never emits byte 0, so gating would be
  unobservable; unconditional keeps `dispatchBatchResponses` a static with no gate dependency.
- **The 6-field caps are decoded but UNUSED.** §2/§5 say "read the caps as pacing hints" — that
  describes the *legacy* client. On this v18 client no budget derives from server caps (the
  scanner uses the `WANT_SET_BUDGET` constant); the two cap VarInts are consumed purely for wire
  alignment (`legacySyncCap`/`legacyGenCap`, pinned by the decode tests) and then dropped by
  `clampToProtocolBounds`.
- **The §2 "give-up timer" was unnecessary.** A single `v16FallbackSent` one-shot suffices — after
  the one fallback there is nothing to give up on.
- **Tier B (drive generation via `ts -1→0`) is now IMPLEMENTED** (opt-in, default off) after Tier
  A was live-confirmed. The egress rewrite lives at the single C2S choke point
  (`LodRequestManager.sendRequests`, wire copy only — the send buffer keeps the honest `-1`, so
  `classify`/tracker/cache stay pure v18); the decision (v16 session AND `enableV16Generation`) is
  wired once in `LSSClientNetworking.createRequestManager`. **The §4 "needs a live check" caveat is
  RESOLVED from the v0.6.2 source:** `IncomingRequestRouter` maps `ts==0`→`GENERATION` (line 84) and
  `submitToDiskOrGeneration` routes disk-FIRST for both SYNC and GENERATION whenever a disk reader
  exists — which it always does in production. So `ts=0` reads disk first and only generates a true
  miss: no wasteful regeneration of disk-resident chunks against v0.6.2. Other pre-v0.7.0 versions
  are unverified, which is why the flag defaults off.
- **Tier B needs a client-side NOT_GENERATED heal (review-driven, subtle).** The disk-first check
  above only proves the *no-regeneration* half. The other half: v0.6.2 answers a **transient**
  gen-slot-full miss with `NOT_GENERATED` (`handleDiskNotFound`'s `else` when the GENERATION slot
  cap is hit), where a v18 server drops it silently and reserves `NOT_GENERATED` for permanent
  unservability. The v18 client parks `NOT_GENERATED` **session-permanently**, so a naïve Tier B
  parks the bulk of every cold wave after one gen-slot bounce — filling only a trickle. Fix
  (`LodRequestManager.onColumnNotGenerated`, gated `v16GenerationDrive && generationEnabled`): on a
  gen-ENABLED Tier B session, treat `NOT_GENERATED` as a re-declarable transient drop (leave the
  position first-serve so the next scan re-declares it) — the client-side mirror of v18's
  server-side silent-drop-and-heal. Cold terrain then fills at the old server's generation
  throughput (**the real "same net effect as v18"**, achieved via re-declaration rather than a
  server-side silent drop). A gen-DISABLED v16 server keeps the permanent park (its `NOT_GENERATED`
  is genuinely permanent; re-declaring would starve the want-set); a rare permanent gen failure on
  a gen-enabled server persists as one re-declared position, never a backfill stall. Tier A and
  every v18 session are unaffected (the heal is behind the Tier B gate).
- **Live v0.6.2 smoke (the §7 "real validation") is still PENDING** — and now has TWO distinct
  gates. (1) *Tier A:* the end-to-end handshake→6-field-config→source-less-column→decode→consumer
  chain (already run by the maintainer 2026-07-21). (2) *Tier B:* that cold terrain actually FILLS
  (the heal works) AND disk-resident terrain is served, not regenerated, against a real v0.6.2 —
  the riskier claim and the reason the flag defaults off. CI has no old-protocol server jar, so
  both are manual; Tier 1 pins every decode/ladder/heal unit but cannot substitute for the join.

---

## 1. What's easy, what's hard (grounded in the wire audit)

The two research passes established the wire facts against the `v0.6.2` tag. Summary:

| Direction | Payload | v16 vs v18 wire | Client work |
|---|---|---|---|
| C2S | Handshake | **byte-identical** except the version int | announce 16 (see §2) |
| C2S | BatchChunkRequest (want-set) | **byte-identical** (no type byte ever) | none on the wire; optional ts semantics (§4) |
| S2C | SessionConfig | 6 fields (2 extra cap VarInts) vs 4 | decode branch — **self-describing** (§3a) |
| S2C | VoxelColumn | **no `source` byte** (added in proto 18) | decode branch — **NOT self-describing** (§3b, the hard one) |
| S2C | BatchResponse | **byte-identical**; byte 0 = RATE_LIMITED | handler tweak only (§3c) |
| S2C | DirtyColumns | **byte-identical** | none — pass-through |

So the client→server direction needs **no wire translation at all** — just announcing version
16. The server→client direction needs exactly **two decode shims** (SessionConfig, VoxelColumn)
plus a one-line BatchResponse tweak. The bulk of the difficulty is not the codecs — it is the
three problems below.

### The three real problems

1. **Discovery (the client-speaks-first asymmetry).** The server shim is trivial because the
   server *inspects the client's declared version and branches*. A client speaks first, and a
   v0.4.x server **silently drops a version-18 handshake — no reply, no negative ack**
   (verified `@v0.4.0 LSSServerNetworking.handleHandshake`: `VERSION_MISMATCH → return;`). So
   the client cannot tell "old LSS server" from "no LSS at all." Discovery must be a
   **timeout-driven downgrade** (§2).
2. **The stateless VoxelColumn codec.** The v16 column frame has no version marker, and decode
   runs on the netty thread *before* any handler with connection context. A stateless codec
   can't know whether to expect the `source` byte. Needs session-scoped decode state (§3b).
3. **The generation gap (the real fidelity limit).** On a v16 server, generation happened
   **only** when the client asked with `clientTimestamp == 0`; `ts == -1` was disk-only. The
   v18 client relies on *server-owned* generation and never emits `ts == 0`. A naïve adapter
   therefore loads already-generated terrain but **never triggers generation of cold chunks**
   on an old server (§4).

---

## 2. Discovery: two-phase handshake with timeout downgrade

**Today** (`ClientSessionGate.onJoin`, `LSSClientNetworking.java:29-30`): the client sends
exactly one handshake announcing `PROTOCOL_VERSION (18)` and never retries; if no SessionConfig
arrives it sits dormant forever. Against a v16 server that is the permanent silent rung.

**Design.** Keep the v18-first handshake unchanged, then add a single fallback timer:

```
onJoin:
  send Handshake(18)                     // unchanged v18 path
  if enableV16ServerCompat:
    arm downgrade timer (N ticks, ~60 = 3s)

each client tick while no SessionConfig yet AND compat enabled:
  if downgrade timer expired AND not yet retried:
    send Handshake(V16_COMPAT_PROTOCOL_VERSION = 16)   // the ONE new C2S behavior
    mark retried; arm a give-up timer
```

- Against a **v18 server** the SessionConfig arrives well within the timer → the timer is
  disarmed → the v16 handshake **never sends**. The v18 path is untouched and unslowed.
- Against a **v16 server** the first (v18) handshake is dropped; after ~3 s the client
  re-announces 16; the old server's `evaluate` now returns REGISTER and replies with the
  6-field config.
- Against a **no-LSS server** the client makes one extra (harmless, ignored) handshake attempt
  and then goes dormant as it does today.

This is the **only always-on added code path** (it runs before v16 is detected, so it can't be
gated by the post-detection `isV16Server` flag). It must be written to be provably inert on
v18: the timer is disarmed the instant *any* SessionConfig decodes, and the whole block is
gated by `enableV16ServerCompat`. A dedicated test drives a v18 SessionConfig arriving before
the timer and asserts the v16 handshake is never sent.

**Accept at the gate.** `ClientSessionGate.java:108-113` currently rejects any
`protocolVersion() != 18`. Add a `== 16 && enableV16ServerCompat` branch that establishes a
v16 session (sets `isV16Server`, reads the 6-field config's caps as pacing hints — see §5).

---

## 3. The ingress decode shims

### 3a. SessionConfig — self-describing, clean

`SessionConfigS2CPayload.read` (`:65-79`) already reads the leading version VarInt first and,
on `!= 18`, drains + disables. Replace that with: `== 16` → parse the **6-field** shape
(`version, enabled, lodDistanceChunks, syncCap, genCap, generationEnabled`), returning a
config carrying the two extra caps. This is stateless and safe — the version field
disambiguates every frame. Mirror of the server's existing `v16Legacy(...)` *encoder*
(`:41-63`); the decoder is new.

### 3b. VoxelColumn — the hard one (session-scoped decode state)

The v16 column omits `byte source` (between `columnTimestamp` and `sectionBytes`). Feeding a
source-less frame to the v18 `read` (`VoxelColumnS2CPayload.java:111` does `buf.readByte()`)
misreads the section-array length → `DecoderException` → kick. The frame has no version marker,
so the codec must consult **session state**:

- A `static volatile boolean v16ColumnWire` on the payload class (the codec is a shared static
  with no per-connection instance — this is unavoidable and is the **least-isolated piece**).
- **Set** it when a v16 SessionConfig decodes (SessionConfig always precedes any column;
  decode is single-threaded on the netty thread, so the happens-before holds).
- **Reset** it to `false` on every JOIN and DISCONNECT (`ClientSessionGate` has both hooks) so
  it can never leak from a v16 server into a subsequent v18 connection. Default `false`.
- The v18 `read` path is unchanged when the flag is false → **v18 decode is byte-identical**.

This static flag is the design's primary stability risk and gets the most test attention
(§7): a JOIN/DISCONNECT reset test, and a "v18 decode with flag false is unchanged" pin.
(The encode-side mirror already exists — `VoxelColumnS2CPayload.asV16()`/`v16Wire` at
`:38-42` — the server strips the byte; the client adds the source-less *read*.)

### 3c. BatchResponse byte 0 + DirtyColumns

BatchResponse is byte-identical; today `dispatchBatchResponses` (`LSSClientNetworking.java:225`)
logs "Unknown batch response type: 0" and skips it — so a v16 server's RATE_LIMITED bounce is
**already handled gracefully**: the position stays unsatisfied and is re-declared next scan
(the v18 self-heal, which approximates v16's ~1 s soft bounce). The only real cost is log spam
under a rate-limit storm. Tweak: demote byte 0 to `debug` when `isV16Server`. No codec change.
DirtyColumns is pure pass-through — nothing to do.

---

## 4. The generation gap — pick a fidelity tier

The single semantic decision, isolated entirely to the egress `batchSender` wrapper
(`LodRequestManager.java:277`, the one C2S choke point):

- **Tier A — load-only (most conservative, recommended MVP).** Pure pass-through egress. The
  client renders whatever the v16 server already has on disk; cold/never-generated terrain
  reads not-found and does not fill. Zero semantic risk, zero core change. Honest limitation
  to document: "against a pre-v0.7.0 server, LSS shows terrain the server has already
  generated; it does not drive new generation."
- **Tier B — drive generation (IMPLEMENTED, opt-in `enableV16Generation`, default off).** In v16
  mode the egress rewrites first-serve `ts <= 0 → 0` (leaving resync `ts > 0` untouched), so the
  old server generates on demand. Fills cold terrain. Two v0.6.2 properties had to be verified from
  its source, not assumed: (1) **no regeneration** — the generate path (`ts==0`→`GENERATION`) is
  disk-FIRST when a disk reader exists (always, in production), so already-generated terrain is
  served and only true misses generate; (2) **transient-miss handling** — v0.6.2 answers a
  gen-slot-full miss with `NOT_GENERATED`, which the v18 client would park session-permanently, so
  Tier B pairs the egress rewrite with a client-side heal (`onColumnNotGenerated`, gated
  `v16GenerationDrive && generationEnabled`) that re-declares the transient miss instead of parking
  it. With both, cold terrain fills at the old server's generation throughput — **the same net
  effect as v18 server-owned generation, reached by client-side re-declaration rather than a
  server-side silent drop.** Isolated to the `sendRequests` wire copy + the gated
  `onColumnNotGenerated` branch — the core loop still emits `-1`, `classify`/tracker/cache are
  untouched, and Tier A / every v18 session keep the permanent park. Default off because (a) it
  drives generation load on a remote old server that never opted in, and (b) both v0.6.2 properties
  are verified only for v0.6.2, not the whole v0.4.x–v0.6.2 range.

Recommendation: **ship Tier A first** (it's the low-risk, fully-isolated adapter), and gate
Tier B behind the same config flag as a follow-up once validated live. Faithfully replaying
v16's two-step (`-1` → on NOT_GENERATED re-ask `0`) would require re-introducing generation
classification into `ColumnStateMap`, which violates "keep the core pure v18" — explicitly
out of scope.

---

## 5. Isolation architecture

```
        ┌─────────────── CORE (UNCHANGED, pure v18) ───────────────┐
        │  SpiralScanner → LodRequestManager → ColumnStateMap      │
        │  InFlightTracker · ClientColumnProcessor.decodeSections  │
        └─────────▲───────────────────────────────┬────────────────┘
   want-set (v18) │                                │ v18-shaped records
                  │  ┌──────── ClientV16CompatManager ────────┐
   batchSender ───┼─▶│ egress: pass-through (A) / ts -1→0 (B)  │──▶ wire (announce 16)
                  │  │ isV16Server flag · discovery timer      │
   handlers   ◀───┼──│ ingress: decode 6-field cfg,            │◀── wire (v16 frames)
                  │  │   source-less column (static flag),      │
                  │  │   byte-0 log demote                      │
                  │  └──────────────────────────────────────────┘
```

**New:** one `ClientV16CompatManager` (holds `isV16Server`, the discovery-timer state, and the
static-flag lifecycle) + decode branches in `SessionConfigS2CPayload.read` /
`VoxelColumnS2CPayload.read` + the `batchSender` wrapper + one config field. **Unchanged:**
every core class above, and the section byte-array decoder (the sections themselves are
identical across versions — only the envelope differs).

**The `isV16Server` gate (default false)** makes every post-detection compat path inert on
v18: the SessionConfig branch only fires on version 16, the column flag is false, the egress
wrapper and byte-0 demotion no-op. The v18 steady state is byte-for-byte identical. The two
honest exceptions, both called out above: the **discovery timer** (always-on, but provably
disarmed before the v16 handshake on any v18 server) and the **static column flag**
(process-scoped, reset on JOIN/DISCONNECT).

**Config:** add `enableV16ServerCompat = true` to `LSSClientConfig` (mirrors the server's
`enableV16Compat`). Setting it false removes the discovery fallback entirely → pure v18.

---

## 6. What is NOT reusable (and why the effort is real)

The existing v16 wire branches in the codebase are **encode-only** (the server *writes* v16
frames); the client needs the mirror **decode** side, which does not exist. `V16CompatManager`
/ `V16CompatSession` are the server's drip→want-set *accumulator* — the client is the inverse
(it is the want-set *declarer*), so they are conceptually instructive but not code-reusable.
Genuinely reusable from `common/`: `LSSConstants` (the version + response-byte + channel
constants), `PositionUtil`, and the golden v16-byte test corpus (which can drive the new
decoders in reverse).

---

## 7. Test plan

- **Golden-byte decode tests** (Tier 1, no server): feed recorded v16 SessionConfig +
  source-less VoxelColumn frames (the server shim already has a golden corpus — reuse it) to
  the new decoders; assert the v18-shaped records match. Assert a **v18 frame with the flag
  false decodes byte-identically** (the stability pin).
- **Discovery-timer test:** v18 SessionConfig arriving before the timer → v16 handshake never
  sent; no SessionConfig by the deadline → exactly one v16 handshake sent; compat disabled →
  no fallback at all.
- **Static-flag lifecycle test:** set by a v16 config decode; reset on JOIN and on DISCONNECT;
  a v16 session followed by a v18 session does not leak the flag.
- **Byte-0 handling:** a v16 RATE_LIMITED response leaves the position unsatisfied → re-declared
  next scan (no crash, no permanent hole).
- **Live gate (the real validation):** `test-server.sh` stages an actual **v0.6.2 server** (an
  old jar) and a current client joins it — the only end-to-end proof, exactly as the server
  shim was validated live. CI has no v16 server, so this is a manual/soak gate, not automated.

---

## 8. Effort & recommendation

**Effort: Medium.** The codecs are small; the care goes into the discovery-timer inertness,
the static-flag lifecycle, and the live v0.6.2 validation. Rough sizing: ~1 new class + ~4
small edits + config + ~5 test classes + one live smoke session. Call it 2–4 focused days.

**Recommendation.** Feasible and cleanly isolatable — the core loop needs zero changes, which
is the property the maintainer asked for. Ship **Tier A (load-only)** first: it is the fully
isolated wire adapter with no semantic risk, delivering LODs from any v0.4.x–v0.6.2 server for
already-generated terrain. Add **Tier B (drive generation via `ts -1→0`)** as a flag-gated
follow-up after a live check confirms the old server's `ts=0` disk-first behavior. Keep the
whole thing behind `enableV16ServerCompat` so it can be switched off to a pure-v18 client
instantly. The one irreducible stability surface — the static VoxelColumn decode flag — is
small, reset on both lifecycle edges, and the most-tested piece.
```
