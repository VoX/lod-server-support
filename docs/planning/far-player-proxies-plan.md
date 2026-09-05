# Far-player proxies in LSS ("SeeU-native") — plan

**Status: IMPLEMENTED — shipped in v0.11.0** (E1-E3, 2026-08-13; kept as the design record — the mega plan's R-3/R-5/R-7/R-9/R-10 amendments apply on top). Design for rendering distant players
beyond vanilla entity-tracking range as a native LSS feature — the player-entity
complement to LOD terrain. Informed by a full source read of SeeU 0.7.3 (vendored at
`research/seeu/`, commit `8d79f9a` = the 0.7.3 version bump, upstream
https://github.com/cat4blep/SeeU — the `research/voxy` gitignored-checkout
precedent, re-cloned at E1 per the mega plan's prerequisite) and a live compat test
on the LSS rig (2026-08-12: both mods coexist cleanly — so this feature competes on
merit, not necessity; shipping it must also play nice with SeeU installed, see §6).

**Reviewed 2026-08-12** (1 Fable subagent, verified against BOTH source trees):
verdict IMPLEMENT WITH FIXES, 4 MAJOR / 9 MINOR — all folded into this revision.
Headlines: the send-queue-reuse idea was structurally impossible on Paper (replaced
with a dedicated send lane); delta suppression and the adaptive lerp were mutually
hostile (replaced with server-declared cadence); the roster needs epoch armor (this
codebase's own race history demands it); and SeeU's renderer ships TWO visibility
crutches the plan had missed — a global fog-disable mixin and `setGlowingTag(true)`
on every proxy (through-wall outlines) — forcing an explicit visibility decision.
The review also confirmed the wire premises are direct in-repo precedent
(`CHANNEL_CLIENT_INFO` sidecar doctrine, masked capability gate).

---

## 1. How SeeU does it (verified from source, 0.7.3)

~1,800 lines total across common/fabric/paper. The architecture:

**Wire** (`common/protocol/`, hand-rolled varint codec, own channels `seeu:hello` /
`seeu:players`, protocol version 3):
- C2S `ClientHelloPacket`: version + client prefs (enabled, max render distance, min
  proxy distance, name tags, `shareSelf`, share max distance). Version mismatch =
  silent unsubscribe.
- S2C `FarPlayersPacket`: dimension key string + the viewer's COMPLETE visible-player
  list, every update. Each `FarPlayerSnapshot`: UUID + **name as a string**, position
  as **3 doubles**, 3 floats rotation, 3 booleans (sneak/glide/swim), **six equipment
  slots as registry-id strings + count**, optional vehicle (UUID + entity-type string
  + pos/rot). No deltas, no quantization, no dictionary — every string resent every
  update.

**Server** (`FabricFarPlayerService`, 216 lines): on `END_SERVER_TICK`, every
`updateIntervalTicks` (default 10 = 2 Hz), for **each subscribed viewer iterate all
online players** and filter: same dimension, alive, spectator (config), invisible,
a reflective vanish bridge (`melius-vanish`'s `VanishAPI.canSeePlayer`), min/max
distance ring (client pref ∩ server cap, default max 8192 blocks), and the target's
own `shareSelf`/share-distance prefs when the target runs the mod. Then it builds a
fresh snapshot list per viewer and sends. Complexity is **O(viewers × players) with
full snapshot construction per pair** — fine at 5 players, quadratic pain at 100.

**Client** (`FarPlayerTracker`/`TrackedFarPlayer`/`FarPlayerRenderer`):
- Tracker: generational latest-wins map (a new packet bumps a generation; entries not
  refreshed are swept) — structurally the same idea as LSS's want-set replace.
- Interpolation: lerp position/yaws/pitch over a **fixed 550 ms window**
  (`INTERPOLATION_WINDOW_NANOS`) from `System.nanoTime`. Matched to the default 500 ms
  cadence; raise the server interval and proxies stutter — the window does not adapt,
  and there is no velocity extrapolation. (Review correction: `apply()` re-lerps from
  the current RENDERED position, so an elytra player is a continuous sawtooth lagging
  ~one cadence behind — not per-update teleporting, but still ~16 m adrift at 30 m/s.)
- Renderer: client-side `RemotePlayer`-style proxy entities (IDs allocated from
  1_000_000_000), submitted through Fabric's `LevelRenderContext` + the
  `EntityRenderDispatcher` each frame; skins come free via TAB-list `PlayerInfo`;
  walk animation under a config distance; vehicles are client-spawned entities with
  passengers ejected/re-seated **every frame**. Handoff: skip the proxy when the real
  entity is present AND its chunk is loaded AND distance ≤ vanilla render distance
  + 16 — a hysteresis-free boundary (flicker risk at the tracking edge).
- **Two visibility crutches the first draft of this plan missed (review MAJOR):**
  (a) `FogRendererMixin` is a config-gated (default OFF) **global vanilla-fog
  disable** — all fog distances to `Float.MAX_VALUE` outside fluid/blindness — not
  per-proxy fog handling; without it a proxy beyond fog-end renders fog-colored into
  invisibility. (b) **Every proxy gets `setGlowingTag(true)`**
  (`FarPlayerRenderer.java:260`) — the outline shader, i.e. visible THROUGH terrain.
  Both are load-bearing for SeeU's "you can actually see it" experience, and both
  are decisions LSS must make deliberately (§3.3).

**Paper** (`VoxySeeUPaperPlugin`): `Bukkit.getScheduler().runTaskTimer(...)` —
BukkitScheduler; plugin.yml lacks `folia-supported`, so on Folia SeeU **never loads
at all** (review correction: not a runtime crash — the flag absence keeps it off).
Also: the Paper version-mismatch unsubscribe logs a warning (not silent as on
Fabric). Total ~2,150 lines across common/fabric/paper, plus a NeoForge module LSS
does not need to consider.

**What SeeU gets right** (adopt, don't reinvent): the proxy-entity rendering approach
(proven against 26.2's rendering API, skins-via-TAB free), latest-wins client state,
the same-dimension-only scope, the min/max ring with client∩server caps, the target-
controlled `shareSelf` privacy pref, spectators-hidden default, and the reflective
vanish-mod bridge.

## 2. Scope decision

This is in-product: LSS's pitch (just re-worded in the description change plan) is
"see the world beyond vanilla range" — terrain today, players are the natural
complement, and the live test showed exactly that composition (a distant player
standing on Voxy LOD terrain). It becomes an LSS feature behind a capability bit +
config toggles, in the existing jars — no new mod, no new channels beyond the `lss:*`
namespace, NeoForge stays out of scope (LSS ships Fabric + Paper only).

The genuinely NEW surface for LSS is client-side **rendering** — everything else
(handshake, per-player state, batching, bandwidth, config discipline, Folia patterns,
diag, release) reuses infrastructure LSS already has and SeeU lacks.

## 3. Design

### 3.1 Wire (additive, no protocol bump)

- New capability bit `CAPABILITY_FAR_PLAYERS` in the existing handshake bitmask
  (`CAPABILITY_VOXEL_COLUMNS` precedent). The server sends far-player payloads only to
  sessions that declared it; legacy clients never see them — **no compat rung
  needed**, no version bump. Review-verified as direct in-repo precedent: the
  handshake gate MASKS capability bits (`HandshakeGate.java:151`), so an OLDER v20
  server ignores the unknown bit and registers normally, and the
  `CHANNEL_CLIENT_INFO` sidecar carries the explicit doctrine ("legacy servers
  silently discard unregistered channels", `LSSConstants.java:45-51`); Paper S2C
  bypasses the Bukkit messenger via NMS `DiscardedPayload` (no outgoing registration
  needed), a new C2S channel needs only `registerIncomingPluginChannel` + a dispatch
  case. Caveat (review): the 4-field SessionConfig carries no server-capability echo,
  so the client cannot distinguish "older server ignored my bit" from "nobody in
  range" — if UX wants an ack, the v20-only append arm of `encodeSessionConfig` is
  the available slot.
- C2S `FarPlayerPrefsC2SPayload` (sent after session config, re-sendable at runtime):
  enabled, max distance, min distance, shareSelf, share distance — the SeeU hello
  fields minus the version (LSS's handshake owns versioning). Server-side prefs and
  roster state follow the **v18-rung lifecycle checklist** (review MAJOR — this
  codebase's own race history: the v0.8.0 deferred-reply fix, the dialect tracker's
  quit-race drain): Paper marks pump-only, dropped at disconnect AND the
  quit-originated mailbox Remove, survives the dimension-change remove+register.
- S2C **two-payload split** (the main wire improvement over SeeU):
  - `FarPlayerRosterS2CPayload` — carries a **roster epoch** (review MAJOR) and, per
    player, a compact index ↔ UUID + name binding (+ leave events). A FULL roster is
    sent on subscribe, on (re)handshake, and on the viewer's dimension change; index
    reuse is only valid within an epoch. Names/UUIDs cross the wire once per
    join/leave, not 2× per second.
  - `FarPlayerUpdatesS2CPayload` — the periodic batch, stamped with the roster epoch
    (the client DROPS updates whose epoch it hasn't seen — the misbinding armor) and
    the viewer's dimension (SeeU ships dimensionKey per packet; ours rides the
    epoch'd roster + a dimension check, and the client clears on mismatch). Per
    visible player: roster index (varint), **quantized position** (fixed-point 1/16
    block, int32 — covers ±134M blocks vs the ±30M border; sub-block precision is
    invisible at proxy distances), yaw/head-yaw/pitch as bytes (1.4° steps), a pose
    flags byte, **equipment only when its hash changed** (dictionary indices via the
    v20 identity-dictionary pattern, not registry-id strings per update), optional
    vehicle (type via the same dictionary), and a **velocity hint** (3 shorts,
    blocks/s × 256, clamped ±64 m/s — elytra ≈ 40 m/s) for client extrapolation.
  - The updates payload also carries the **server-declared nominal cadence for the
    player's tier** (one varint) — the client's interpolation window derives from
    the DECLARED interval, not a measured one (review MAJOR — see §3.3).
- Both platforms encode via the shared `common/` codec twins with the established
  Fabric/Paper wire-parity test discipline (`WireParityTest` pattern), and the
  protocol-constant envelope pin gains the new channel ids.

### 3.2 Server service (`common/` core + platform adapters)

- One `FarPlayerBroadcastService` in `common/`, ticked from the existing service tick
  (Fabric server thread / Paper GlobalRegionScheduler pump — Folia-safe by
  construction, and `FoliaWiringContractTest` covers the new classes for free).
- **Invert SeeU's loop**: per broadcast tick, build each ONLINE PLAYER's snapshot
  ONCE (position/rotation/pose/equipment-hash — O(P)), then run the per-viewer
  filter over the shared snapshots (O(V×P) *filter*, but comparisons only — no
  per-pair snapshot construction or string encoding).
- **Distance-tiered cadence**: full-rate updates (default 2 Hz) inside a near band,
  half/quarter rate beyond (e.g. >2048 blocks). A stationary far player costs a
  position-unchanged skip (delta suppression: unchanged players are omitted from the
  update payload entirely — the roster keeps them alive client-side).
- Filters, in SeeU's proven order plus LSS additions: same dimension → alive/removed →
  spectator (config) → invisible → vanish bridge (reuse the `AntiXrayCompat`-style
  reflective ladder; melius-vanish on Fabric, a Paper vanish-meta check on Paper) →
  ring (client ∩ server) → target privacy (below).
- **Privacy, server-authoritative** (the ESP-oracle fix — SeeU's biggest gap): SeeU
  only honors `shareSelf` for targets *running the mod*; vanilla players are broadcast
  with no say and no knowledge. LSS adds: `farPlayersMaxDistanceBlocks` server cap
  (default **2048**, not 8192 — admins raise it consciously), a server-side
  `farPlayersExclude` list + Paper permission node (`lss.farplayers.hidden`), and a
  `farPlayers` mode config: `off` / `opt-in` / `on` (default `on`, documented in the
  README's privacy note). Target-client `shareSelf` still honored on top.
- **Bandwidth — a DEDICATED far-player send lane, NOT the column send queue**
  (review MAJOR — the first draft's queue reuse was structurally impossible: the
  queue is column-position-keyed with load-bearing `packedPos` semantics — the
  relevance prune, send-failure done-bit clears — and Paper's flush sender is
  hard-bound to the `ID_VOXEL_COLUMN` channel; FIFO behind megabytes of backfill
  would also delay 2 Hz pose data by seconds). The lane: send immediately at
  broadcast time, but CONSULT the same channel-writability/yield gate before
  sending, and charge `SharedBandwidthLimiter.recordSend` so the governor sees the
  bytes. Far-player bytes get their OWN diag counters — never folded into
  `service.bytes_sent`/`wire_bytes`, which feed soak_report's cross-identity audits
  against client received counters.
- Honesty note on cost (review): the per-viewer delta/equipment-hash bookkeeping is
  still O(V×P) MEMORY with per-pair rows — the win over SeeU is eliminating per-pair
  snapshot/string construction and unchanged-player bytes, not the asymptotic shape.
- **Folia cross-region reads** (review): positions are plain-field stale-tolerant
  (matches how the pump already reads `player.chunkPosition()` — precedent in
  `PaperRequestProcessingService`), but equipment/pose/vehicle reads are a NEW
  cross-region read class (potentially torn ItemStack reads). Decision:
  accept-and-document for display-only data rather than EntityScheduler hops;
  contained per player (a torn read renders one wrong frame of gear); the Folia
  experimental label covers it in release notes.
- Diag: a `FarPlayers:` line in `/lsslod diag` (subscribers, snapshots/s, bytes/s,
  suppressed-unchanged count) + exporter fields on both platform exporters, added to
  `check_soak.py`'s `KNOWN_SERVER_KEYS` with `--selftest` cases and the
  `DiagnosticsFormatter` golden updates (review: additive fields WARN as unknown
  until registered — register them, don't ship warnings).

### 3.3 Client (tracker + renderer)

- Tracker mirrors SeeU's generational latest-wins map, plus the roster layer
  (index→identity, epoch-guarded) and per-player equipment cache.
- **Interpolation from the DECLARED cadence** (review MAJOR — the first draft's
  measured EWMA was mutually hostile with delta suppression: a suppressed-stationary
  player's measured gap grows unbounded, so their first movement would slow-motion
  glide over an inflated window, and tier migration shifts the gap under the
  filter). The lerp window = the server-declared tier interval + 20% margin;
  measurement survives only as a correction clamped to ≤2× declared. **Velocity
  extrapolation** for moving players (dead-reckon from the velocity hint, clamped to
  ~1.5 windows) — the elytra lag case.
- **Handoff with hysteresis**: use the real `RemotePlayer`'s client-side
  `ClientEntityEvents.ENTITY_LOAD/UNLOAD` (fabric-lifecycle-events-v1 — review
  confirmed these fire for player adds/removes) as EDGE TRIGGERS, but keep SeeU's
  conjuncts in the steady-state formula — the review's caveat: entity-add can
  precede the client having a renderable chunk, which is exactly why SeeU's
  `chunkLoaded` term exists. ±16-block hysteresis band + a 1-frame crossfade guard.
  **SUPERSEDED AS BUILT (E2 review M3, decisions log 2026-08-13 entry 16 — §6.1
  pair): the shipped handoff is vanilla's own cull predicate (`real present ∧
  chunk loaded ∧ real.shouldRenderAtSqrDistance(camDistSq)`) keyed the same both
  directions, NOT a Euclidean distance band. Review proved the band shape
  double-renders at the render square's diagonal (Euclidean vs Chebyshev chunk
  geometry) and leaves an invisibility annulus at high render distance (entity
  cull ~256 blocks sits far inside a 32-chunk circle); the same-predicate swap
  frame-synchronizes with vanilla's entity pop, so no band is needed. The
  ENTITY_LOAD edge trigger survives as the same-frame kill.**
- Renderer: adopt SeeU's `RemotePlayer`-proxy + `LevelRenderContext` submission
  approach (proven on 26.2), with: entity-ID allocation guarded against collision
  with real entity IDs, poses (sneak/glide/swim) mapped as SeeU does,
  TAB-`PlayerInfo` skins, name-tag toggle, animation-distance cap. Vehicles:
  phase C — render the vehicle model at the snapshot pose directly rather than
  spawning rideable client entities and re-seating every frame (SeeU's eject/re-seat
  per frame is the hackiest part of their renderer).
- **Visibility decision (review MAJOR — do NOT copy SeeU blind here):**
  - **No glow, ever, by default.** SeeU sets `setGlowingTag(true)` on every proxy —
    a through-wall outline that contradicts this plan's own privacy stance. LSS
    proxies render as normal entities; if an outline option is ever wanted it is a
    separate opt-in with its own privacy note.
  - **Fog stance:** LSS ships NO fog mixin in phase B. Voxy users overwhelmingly run
    with extended/disabled fog already (the LOD mod owns the horizon); a proxy
    beyond vanilla fog-end on a fog-default client simply fades like terrain would —
    documented, with a client-config `farPlayersMaxRenderDistanceBlocks` the user
    can align with their fog. If live testing shows it matters, a fog *option*
    follows the tracer's non-required-mixin discipline in a later phase.
- Client config (`lss-client-config.json`): `farPlayersEnabled` (default true),
  distance overrides, name tags, shareSelf + share distance — plus Sodium option
  screen rows next to the existing LSS entries.
- **Concrete capability gate** (review — "renderer viable" was not a real
  predicate): the bit is sent when `farPlayersEnabled` AND `-Dlss.soak` /
  `-Dlss.benchmark` are absent. The soak/benchmark clients are full Loom clients
  distinguished only by those properties, and they DO register LSSApi consumers +
  send `CAPABILITY_VOXEL_COLUMNS|zstd` — without the explicit property check they
  would subscribe and shift soak baselines. In Phase A (no renderer yet) this same
  gate applies — the bit does not wait for the renderer (review: the draft's
  renderer-viability wording made Phase A unverifiable by its own gate).

### 3.4 Config (server, shared `ServerConfigBase` — both platforms)

`farPlayers` (`on`/`opt-in`/`off`, default `on`), `farPlayersUpdateIntervalTicks`
(default 10, clamp 2..100), `farPlayersMaxDistanceBlocks` (default 2048, clamp
128..16384), `farPlayersMinDistanceBlocks` (default 0), `farPlayersSendSpectators`
(default false), `farPlayersExclude` (name/UUID list, default empty —
`xrayHiddenBlocks` is the shared-List precedent). All clamped in `validate()` with
the standard test-table entries (Fabric switch + Paper `SHARED_BOUNDS`), plus an
erratum in the clamp-audit doc.

**VSS branding interaction (review)**: the new Paper permission node
(`lss.farplayers.hidden`) lands in plugin.yml, whose LSS↔VSS pair diff is pinned
line-by-line by `release_check.py` and whose rebrand set today is exactly the
command key + `lss.admin`→`vss.admin`. The vssJar rewrite (paper/build.gradle) AND
the release_check token lists must be extended together for the new node
(`vss.farplayers.hidden`), or the release gate reds / the VSS jar ships an `lss.*`
node. Wire channels stay `lss:*` verbatim (the wire-compat contract); config keys
need nothing.

## 4. Concretely better than SeeU (the checklist)

1. **Server cost**: per-target snapshot built once per tick, not per viewer×target;
   unchanged players omitted; distance-tiered cadence. SeeU: full rebuild per pair
   at fixed cadence.
2. **Wire cost**: roster split (names once), quantized positions/rotations,
   equipment-on-change with dictionary ids, delta suppression. SeeU: full doubles +
   full strings for every player every 500 ms.
3. **Folia**: runs on the existing pump; contract-test enforced. SeeU: BukkitScheduler
   — crashes on Folia.
4. **Privacy**: server-authoritative modes + exclude list + permission node +
   conservative 2048 default. SeeU: 8192 default, opt-out only for modded targets.
5. **Motion quality**: adaptive interpolation window + velocity extrapolation.
   SeeU: fixed 550 ms lerp, elytra players teleport-lag.
6. **Handoff**: event-driven swap with hysteresis. SeeU: distance formula, flicker
   possible at the boundary.
7. **Governance**: bandwidth-limited, diag-instrumented, config-clamped, wire-parity
   + Tier-2 tested, release-gated — the LSS operational envelope SeeU has none of.
8. **Versioning**: capability-gated additive payloads — old LSS clients need no
   compat rung; SeeU drops the subscription on any protocol-version mismatch (all
   we can verify from the one cloned version — protocol 3 — is that mismatch means
   no service, with a warn on Paper and silence on Fabric).

## 5. What we deliberately copy (credit where due)

Proxy-entity rendering via `LevelRenderContext` + `EntityRenderDispatcher`;
latest-wins generational tracker; same-dimension scope; min/max ring semantics with
client∩server caps; spectator/invisible/vanish filter ladder (incl. the reflective
vanish bridge pattern); TAB-based skin resolution; walk-animation distance cap;
name-tag toggle. SeeU was MIT-licensed through 0.7.x and relicensed under a restricted
all-rights-reserved license at 0.8 (2026-08; no derivative works, no source reuse) — LSS
reimplements independently from vanilla APIs and observable behaviour, copies nothing,
and credits SeeU in the release notes as prior art (far-player-render-hardening-plan.md F7).

## 6. Coexistence with SeeU

Both installed = double proxies (two renderers drawing the same distant player) —
but ONLY when the **server** also runs SeeU. Review caveat on the blunt gate: a
client-static `isModLoaded("seeu")` default-off means a SeeU-installed client on an
LSS-only server gets ZERO far players — the worst outcome. Actually observing "SeeU
traffic present" client-side is not cleanly feasible (its channels are another mod's
registration). Decision: keep the `isModLoaded` default-off gate for safety, but (a)
log the INFO line naming the override every session ("SeeU detected — LSS far
players disabled; set farPlayersEnabled=true to prefer LSS"), (b) document the
LSS-only-server case explicitly in the README row, and (c) surface the state in the
Sodium screen (a "disabled: SeeU present" tooltip) so the fix is discoverable where
the user looks. Server side needs nothing (a client subscribes to at most one
system). **AS BUILT (E3, §6.1 pair — decisions log 2026-08-13 entry 21): the
override lever is the NEW client key `farPlayersWithSeeU` (default false), not
"set farPlayersEnabled=true" — that key defaults true, so setting it cannot
express an explicit preference. The gate suppresses only the EFFECTIVE enabled
term (renderer + the prefs `enabled` field): the capability bit stays composed
and prefs still deliver, so the shareSelf opt-out survives SeeU's presence (the
E2 prefs-carrier rule). The INFO logs once per game launch (a static latch), not every session.**

## 7. Phasing

- **Phase A — wire + server service + client tracker** (no rendering): payloads,
  capability bit (the §3.3 property-gated form — active from Phase A), broadcast
  service with filters/tiers/privacy, client-side tracked state exposed via a debug
  HUD line + `/lss diag` counters. Tier 1 twins (codec parity, filter ladder via
  seams, prefs/roster lifecycle incl. the epoch resync paths). Tier 2 asserts the
  SERVER EGRESS surface (review correction — client tracker state is not reachable
  from a server gametest): a crafted-handshake mock player with the capability bit
  receives roster + updates for a far player and nothing for a near one — the
  `ServiceLifecycleGameTests` crafted-frame pattern. Tracker state itself is Tier 3
  / live territory.
- **Phase B — renderer**: proxy entities, interpolation/extrapolation, handoff,
  name tags, poses, skins. Live-verified on the test rig (the SeeU session's exact
  setup, minus SeeU).
- **Phase C — polish**: vehicles, Sodium config rows, opt-in mode UX, SeeU-coexist
  default, exporter/soak-report fields, README + release notes.

## 8. Risks / open questions

- **Rendering is new territory for LSS** — the one area with no existing test
  discipline; Phase B leans on live verification and keeps the renderer strictly
  client-side/optional (a renderer crash must degrade to "no proxies", contained
  like every LSS compat surface).
- 26.2's `LevelRenderContext`/submit API is what SeeU targets today; MC rendering
  APIs churn per version — the renderer needs the same per-MC-line porting budget as
  the rest of the client. **SUPERSEDED (mega plan R-7 v1.4, §6.1 pair — this pointer
  edit rides E1's PR): far players ship on ALL THREE lines**, backed by the measured
  SeeU per-line diffs (26.2→26.1.2 = 6 lines; 26.2→1.21.11 = 60/43 lines of symbol
  renames, same render architecture); "backports likely skip Phase B" no longer holds.
- Folia cross-region position reads from the pump are stale-tolerant by design
  (positions are plain fields; a 1-tick-stale snapshot is invisible at 500 ms
  cadence) — document rather than synchronize, matching the Folia experimental
  labeling rules.
- ESP concern is real and worth the README privacy note regardless of design — even
  the 2048 default reveals positions far beyond vanilla; `opt-in` mode exists for
  servers that care.
- Open: should Phase A data also be exposed through `LSSApi` (a
  `FarPlayerConsumer`) so other mods can consume the feed? Cheap to add, matches the
  LSSApi philosophy — leaning yes, decide at implementation.

## 9. Verification

1. Tier 1: codec/parity/filter/prefs/clamp tests both platforms, PLUS (review):
   `DiagnosticsFormatter` golden updates for the `FarPlayers:` line, exporter twins
   with `KNOWN_SERVER_KEYS` + `check_soak.py --selftest` case registration, and the
   protocol-constant envelope pin for the new channel ids.
2. Tier 2: server-egress gametests per §7 Phase A (crafted-handshake mock player;
   NOT client-tracker assertions); handoff behavior is Tier 3/live.
3. `SOAK_PLATFORM=paper` + Fabric `soak.sh all` — baselines must be untouched (soak
   clients never set the capability bit; assert `FarPlayers:` counters stay 0).
4. Live: the SeeU test-rig session repeated with LSS-native proxies — two real
   clients + the SoakPlayer dummy, elytra flight for the extrapolation case, walk
   across the tracking boundary for handoff, vanish/spectator checks via RCON.
5. Folia: one manual `run-folia` session with two clients (experimental label rules
   apply to release notes).
