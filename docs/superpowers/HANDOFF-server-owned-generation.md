# Handoff Briefing — Server-Owned Generation (v17)

**For the implementing session.** Read this, then the spec. Short by design; the spec has the detail.

## The task

Implement the **server-owned-generation** redesign: the client becomes fully declarative (declares
every position it wants, no sync/gen classification, one budget), and the server owns all
disk/generation limiting. One protocol-breaking change folded into the still-unreleased **v17**.

**Source of truth:** `docs/superpowers/specs/2026-07-16-server-owned-generation-design.md`. Read it
first — it is complete and cross-checked against the code (every mechanism it names was verified to
exist).

## State

- **Branch:** `feat/want-set-requests`.
- **Committed + fully green** (up to `a459be5`): the v17 want-set protocol, the A+B read-protection
  (adaptive-throttle fallback), and a global per-tick probe cap. Tier 1/2/3, `:paper:test`,
  `:paper:shadowJar`, and all soaks (Fabric `fresh-backfill` + Paper `all`) pass. **Nothing for the
  server-owned-generation model is implemented yet** — this handoff is the design only.
- **Uncommitted in the working tree** (commit these before/at handoff so they survive a fresh checkout):
  - `docs/superpowers/specs/2026-07-16-server-owned-generation-design.md` — the spec (implement this).
  - `docs/superpowers/plans/2026-07-16-decouple-wantset-and-generation-priority.md` — **SUPERSEDED**
    reference. It plans only the narrower "sync-only decouple." Do **not** execute it; the spec's goal
    is a superset. **Keep it for its verified reference material** (exact file-touch lists, the
    confirmed Moonrise API signature, and verification findings G1–G7), then delete it once the new
    plan lands.
  - `.superpowers/sdd/progress.md` — the prior work's ledger (A+B). Unrelated to this task.

## Do this, in order

1. **Get spec sign-off + answer the 3 open questions** in the spec's "Open questions for review"
   (WANT_SET_BUDGET default 800 vs ~960; keep/inline the two multiplier constants; any *other*
   breaking wire change to ride this release). The user (VoX) decides.
2. **Write a plan** from the spec (superpowers:writing-plans) → `docs/superpowers/plans/`.
3. **Implement** (superpowers:subagent-driven-development, or inline) — Tier 1 + `:paper:test` green
   at every commit; Tier 2/3 + soak at part boundaries.

## Load-bearing details (already verified — trust these)

- **v17 is unreleased** (latest tag ships v16), so wire-format changes are free — no bump, no
  back-compat. `PROTOCOL_VERSION` stays 17. Fabric and Paper encoders must stay byte-identical
  (`WireParityTest`).
- **The `NOT_GENERATED` inversion is a 2-line change** in `ColumnStateMap.onNotGenerated` (currently
  `sessionSatisfied.remove` + `put(0L)` for the old gen-retry → becomes `sessionSatisfied.add`, drop
  the `put(0L)`), plus deleting the `classify` rung `stored==0 && generationEnabled → 0`. Recovery is
  **only** via the dirty broadcast (which already clears `sessionSatisfied`) — no movement/timeout
  recovery. Mirror `onUpToDate`'s existing `sessionSatisfied.add` for a no-data position.
- **The server gen trigger moves** from the client's `ts==0` to a disk-miss decision:
  `IncomingRequestRouter` line ~121 (`type = clientTimestamp()==0 ? GENERATION : SYNC`) goes away —
  every request is SYNC; on the disk-read not-found, the server generates if gen-enabled + a gen slot
  is free, else emits `NOT_GENERATED`. **Concurrency-only** (no distance/count frontier — a decided
  tradeoff: a stationary player gradually generates their whole LOD range).
- **Paper generation → Moonrise `Priority.LOW`:** `ca.spottedleaf.moonrise.common.PlatformHooks.get()
  .scheduleChunkLoad(level, x, z, true, net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
  true, ca.spottedleaf.concurrentutil.util.Priority.LOW, Consumer<ChunkAccess>)` — confirmed present
  and compile-reachable. `onComplete` gives an NMS `ChunkAccess` (no throwable channel) on the owning
  thread; a null chunk is the failure outcome. Reuse the existing `launchAsyncLoad`/`completeAsyncLoad`
  seams (no new seam). **Fabric gets no gen priority** (ticket-level-pinned) and **no** latency throttle
  (worldgen baseline is too variable) — concurrency cap only; document the asymmetry. Paper change is
  Folia-affecting (experimental status in release notes); `scheduleChunkLoad` self-reschedules onto
  the owning thread (pump-safe).
- **The two caps leave the wire** (`SessionConfig` 6→4 fields). `syncOnLoadConcurrencyLimitPerPlayer`
  config knob is deleted (→ constant `SYNC_ON_LOAD_SLOT_CAP=200`, same admission semantics — the
  router reads the value, not the config). `generationConcurrencyLimit*` stay as server config but
  leave the wire. The `ServerConfigBase.validate()` #28 cross-clamp is deleted (the single want-set
  budget constant satisfies the batch invariant by construction; pin it with a unit test).
- **Soak surface:** the superseded plan's Task 6 has the details — dead `syncOnLoad` keys in the
  scenario JSONs, `check_soak.py`'s `SERVER_CONFIG_INT_KEYS` whitelist, and **`rate-limit-storm` must
  be re-baselined** (its `syncCap:4` premise is gone — under this model no client budget derives from
  any cap). `generation-disabled` becomes the primary `NOT_GENERATED`-terminator exerciser. Follow the
  CLAUDE.md soak rule: under v17, supersession comes from slow *service*, never a small cap.

## Still outstanding from the prior (A+B) work — not this task, but on the branch

- **Manual C2ME smoke test** (the only end-to-end for the A+B fallback): deploy the Fabric jar to a
  C2ME server, confirm exactly ONE background-unavailable warning, no NPE, LOD streams, `/lsslod diag`
  shows the read throttle ENGAGED. Gametests/soak can't reach it (plain vanilla IO).
