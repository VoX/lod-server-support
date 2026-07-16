# Decouple the Sync Slot Cap from the Want-Set + Real-Limiter Generation Admission

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Retire the confusing `syncOnLoadConcurrencyLimitPerPlayer` knob. It does triple duty today — server sync-slot admission cap, `SessionConfig` wire field, and (client-side, ×4) sync want-set-budget driver — yet its admission role is *vestigial* (shadowed by the shared disk-pool `hasHeadroom()` gate, since pool depth ~165 < 200). Replace it with fixed constants: a client-side sync want-set budget and a server-side sync slot cap. Leave `generationConcurrencyLimitPerPlayer` untouched — it is a *real* worldgen-concurrency limiter and stays a knob, on the wire, driving the client's gen frontier. Then improve generation admission itself: a true `Priority.LOW` hand-off on Paper/Moonrise (Fabric has no equivalent — vanilla pins worldgen priority to ticket level, confirmed).

**Scope decision (user, 2026-07-16):** **sync-only decouple.** Generation stays coupled to its (still-meaningful) cap. Full gen decoupling was considered and rejected because it would silently cap the gen knob's effect at 64 and shift the soak gen-frontier 160→64. "For now" — a later change may revisit gen.

**Architecture:** Two independently-shippable parts.
- **Part 1 — Decouple sync.** `SpiralScanner` sizes the *sync* budget from a constant `WANT_SET_SYNC_BUDGET = 800` (was `syncCap × 4`); the gen frontier keeps deriving from the wire (`generationConcurrencyLimitPerPlayer × SCAN_BUDGET_MULTIPLIER`). `syncOnLoadConcurrencyLimitPerPlayer` leaves the `SessionConfig` wire payload (6 → 5 fields) and the config file, becoming the constant `SYNC_ON_LOAD_SLOT_CAP = 200` that still feeds the server sync slot cap with identical admission semantics. The #28 cross-clamp shrinks from two-sided to a **one-sided gen clamp** (`generationConcurrencyLimitPerPlayer ≤ (MAX_BATCH − WANT_SET_SYNC_BUDGET − reserve) / mult = 40`). Pure refactor: zero behavioral delta on every path except the re-baselined `rate-limit-storm` soak scenario.
- **Part 2 — Real-limiter generation admission.** Paper: generate via Moonrise `scheduleChunkLoad(..., Priority.LOW, ...)` so LOD generation defers to player-driven `NORMAL` generation (true priority hand-off, the generation analogue of the Moonrise `Priority.LOW` read path). Fabric: no generation-priority exists (vanilla pins FULL to ticket level 33), AND worldgen latency is a poor congestion signal (high, variable baseline) — so the concurrency cap stays the Fabric limiter; a latency-adaptive throttle is explicitly NOT shipped here (see Task 9 for why and the alternative).

**Tech Stack:** Java 25 (Fabric/Paper), Java 21 (`common/`); Fabric Loom + accessor mixins; paperweight-userdev; MC 26.2. Moonrise `ca.spottedleaf.moonrise.common.PlatformHooks` (inherits `ChunkSystemHooks.scheduleChunkLoad`) + `ca.spottedleaf.concurrentutil.util.Priority` (already compiled against for reads). No new dependencies.

**Branch:** the current `feat/want-set-requests`.

## Global Constraints

- **v17 is UNRELEASED on this branch → the wire change is free.** Verified: latest tag `v0.6.2` ships `PROTOCOL_VERSION=16`; v17 exists only on this branch. Removing the one `SessionConfig` field needs no protocol bump and no back-compat path. `PROTOCOL_VERSION` stays `17`. Fabric and Paper encoders MUST stay byte-identical (`WireParityTest` pins it).
- **Only `syncOnLoadConcurrencyLimitPerPlayer` is removed. `generationConcurrencyLimitPerPlayer` STAYS** — config field, wire field, and client gen-frontier driver, all unchanged. The SessionConfig wire payload goes from 6 components to **5** (`protocolVersion, enabled, lodDistanceChunks, generationConcurrencyLimitPerPlayer, generationEnabled`).
- **Part 1 preserves server admission SEMANTICS exactly.** The per-player sync slot cap keeps value 200 and its router interaction (`SLOT_FULL` retain-keep-scanning vs `NO_DISK_HEADROOM` retain-stop). Only its *source* changes (config field → constant `SYNC_ON_LOAD_SLOT_CAP`) and its wire transmission is removed. Verified: `IncomingRequestRouter`/`AbstractPlayerRequestState` read the derived cap value, not the config — value-identical means behavior-identical.
- **The #28 invariant survives as a one-sided clamp, not deleted.** With the sync budget now a constant, `ServerConfigBase.validate()` clamps only gen: `generationConcurrencyLimitPerPlayer = min(genCap, (MAX_BATCH_CHUNK_REQUESTS − WANT_SET_SYNC_BUDGET − WANT_SET_FRONTIER_RESERVE) / SCAN_BUDGET_MULTIPLIER)` = `min(genCap, 40)`. `SCAN_BUDGET_MULTIPLIER` (4) and `WANT_SET_FRONTIER_RESERVE` (64) are STILL used (gen frontier + this clamp) — do NOT delete them.
- **Effective default values unchanged.** Sync budget 800, gen frontier `genCap×4` (=64 at default gen 16, =160 at the soak configs' gen 40 — unchanged), sync slot cap 200, gen caps 16/32. The one visible clamp change: `generationConcurrencyLimitPerPlayer`'s post-clamp maximum drops from the old two-sided 190 to 40 (the soak configs sit at exactly 40 → still valid; the default 16 is unaffected).
- **Static want-set invariant, pinned by test:** `WANT_SET_SYNC_BUDGET + maxGenClamp × SCAN_BUDGET_MULTIPLIER + WANT_SET_FRONTIER_RESERVE ≤ MAX_BATCH_CHUNK_REQUESTS` (800 + 40×4 + 64 = 1024 ≤ 1024, exact fit).
- **Part 2 Paper change is Folia-affecting and experimental.** `scheduleChunkLoad` self-reschedules onto the owning tick thread (safe from the pump). Release note must mention Folia's experimental status.
- **Tier 1 (`:fabric:test -x runGameTest -x runClientGameTest`) and `:paper:test` green at every commit.** Tier 2/3 + soak at part boundaries.

## Verification status (adversarial pass, 2026-07-16)

Load-bearing claims CONFIRMED: (1) `PlatformHooks.get().scheduleChunkLoad(ServerLevel, int, int, boolean, ChunkStatus, boolean, Priority, Consumer<ChunkAccess>)` exists (inherited from `ChunkSystemHooks`; the exact call Paper's `CraftWorld` uses), `ChunkStatus` = `net.minecraft.world.level.chunk.status.ChunkStatus`, `Priority.LOW` real; (2) `forPool(n,0,t)` ceiling = `n`; (3) constant-sourcing the sync cap is behavior-identical. Diagnostics/exporters/benchmark reference neither removed-nor-kept field improperly. Gaps found are folded into the tasks below (G1→Task 9, G3→Task 8, G5→Task 6, G6→Task 5).

## Known rough edges (accepted, stated)

1. **The Paper `Priority.LOW` generation path has no cheap unit test** — like the Moonrise LOW *read* path, its gate is the soak harness on a real Paper/Folia server. The priority argument + completion wiring are pinned via the existing `launchAsyncLoad`/`completeAsyncLoad` seams.
2. **Fabric generation gets no priority AND no reliable adaptive throttle.** Vanilla pins worldgen priority to ticket level (33 for FULL, the low floor). And worldgen latency has a high, variable natural baseline (fresh terrain is genuinely slow), so an absolute-latency AIMD can't separate "contended" from "naturally slow" — it would collapse LSS generation on a fresh world with no contention. The concurrency cap remains the honest Fabric limiter. Task 9 records this and the tick-health-based alternative for later.

---

## File Structure

**Part 1 — Decouple sync**

Modify:
- `common/…/LSSConstants.java` — add `WANT_SET_SYNC_BUDGET = 800`, `SYNC_ON_LOAD_SLOT_CAP = 200`. Keep `SCAN_BUDGET_MULTIPLIER`, `WANT_SET_FRONTIER_RESERVE`, `MAX_CONCURRENCY_LIMIT`.
- `common/…/config/ServerConfigBase.java` — delete field `syncOnLoadConcurrencyLimitPerPlayer` + its clamp; rewrite the #28 block into the one-sided gen clamp.
- `fabric/…/networking/client/SpiralScanner.java` — sync budget from `WANT_SET_SYNC_BUDGET`; the gen-frontier line (`generationConcurrencyLimitPerPlayer() * BUDGET_MULTIPLIER`) is UNCHANGED; keep the `BUDGET_MULTIPLIER` field.
- `fabric/…/networking/payloads/SessionConfigS2CPayload.java` — remove the one sync component + its codec read/write (6 → 5 components). Keep the gen component. Also fix the version-mismatch fallback `new SessionConfigS2CPayload(version,false,0,0,0,false)` → 5 args.
- `fabric/…/networking/client/ClientSessionGate.java` — drop the sync clamp; keep the gen clamp.
- `fabric/…/networking/server/LSSServerNetworking.java` — drop the sync arg from the SessionConfig send (line 93 area); keep the gen arg.
- `fabric/…/networking/server/RequestProcessingService.java` — build `PlayerRequestState` sync cap from `SYNC_ON_LOAD_SLOT_CAP` (line 108); gen cap still from config.
- `paper/…/PaperPayloadHandler.java` — drop the sync param + `writeVarInt` from `encodeSessionConfig`/`sendSessionConfig`.
- `paper/…/LSSPaperPlugin.java` — the session-config bridge (lines ~227-228 signature, ~285-286 call) drops the sync param.
- `paper/…/PaperRequestProcessingService.java` — stop sending sync; build the sync cap from the constant.
- `scripts/soak-scenarios/*.json` — remove the dead `syncOnLoadConcurrencyLimitPerPlayer` key; **re-baseline `rate-limit-storm`** (Task 6).
- `scripts/check_soak.py` — drop `syncOnLoadConcurrencyLimitPerPlayer` from `SERVER_CONFIG_INT_KEYS`; update the `rate-limit-storm` named assertion.

Test files touched (Part 1): `WireParityTest`, `PayloadCodecTest`, `VoxelSectionPayloadTest`, `ClientSessionGateTest`, `ConfigValidationTest` (Fabric), `PaperConfigValidationTest` + `PaperConfigLoadTest`, `LSSPaperPluginGlueTest`, `JsonConfigLoadTest`, `LSSGameTests`, `ServiceLifecycleGameTests` (the `252` end-to-end sync-cap wiring test — premise deleted), plus a new `WantSetBudgetInvariantTest`. `GenerationLifecycleGameTests` uses only the surviving gen field — no change.

**Part 2 — Generation admission**

Modify: `paper/…/PaperChunkGenerationService.java` (Moonrise `Priority.LOW`); `fabric/…/networking/server/ChunkGenerationService.java` (doc only, Task 9); `README.md`, `CLAUDE.md`, `docs/planning/resource-management.md`.

---

## Part 1 — Decouple the sync slot cap

### Task 1: Sync budget + sync-slot-cap constants + invariant

**Files:** Modify `common/…/LSSConstants.java`; Test `fabric/src/test/java/dev/vox/lss/common/WantSetBudgetInvariantTest.java` (new).

**Interfaces — Produces:** `LSSConstants.WANT_SET_SYNC_BUDGET` (800), `LSSConstants.SYNC_ON_LOAD_SLOT_CAP` (200).

- [ ] **Step 1: Write the failing invariant test.**
```java
package dev.vox.lss.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client SYNC budget is a fixed constant now, not syncCap*4 from the (deleted) wire field.
 *  The gen frontier still rides generationConcurrencyLimitPerPlayer (server-clamped to a max that
 *  keeps the whole want-set inside one wire batch — the invariant #28 enforced, now one-sided). */
class WantSetBudgetInvariantTest {
    @Test
    void wantSetFitsOneBatchAtMaxGen() {
        int reserve = LSSConstants.WANT_SET_FRONTIER_RESERVE;
        int mult = LSSConstants.SCAN_BUDGET_MULTIPLIER;
        int maxGen = (LSSConstants.MAX_BATCH_CHUNK_REQUESTS - LSSConstants.WANT_SET_SYNC_BUDGET - reserve) / mult;
        assertTrue(maxGen >= LSSConstants.MIN_CONCURRENCY_LIMIT, "gen clamp must leave at least the minimum");
        assertTrue(LSSConstants.WANT_SET_SYNC_BUDGET + maxGen * mult + reserve
                        <= LSSConstants.MAX_BATCH_CHUNK_REQUESTS,
                "sync budget + max gen frontier + reserve must fit one batch");
        assertTrue(LSSConstants.SYNC_ON_LOAD_SLOT_CAP <= LSSConstants.MAX_CONCURRENCY_LIMIT);
    }
}
```
- [ ] **Step 2: Run — fails to compile.** `./gradlew :fabric:test --tests '*WantSetBudgetInvariantTest' -x runGameTest -x runClientGameTest`
- [ ] **Step 3: Add the constants** in `LSSConstants.java` near the existing want-set block (leave `SCAN_BUDGET_MULTIPLIER`/`WANT_SET_FRONTIER_RESERVE` in place):
```java
    // Client SYNC want-set budget: max sync (ts>0/-1) positions the scanner declares per scan.
    // A fixed constant, decoupled from any server slot cap — the sync cap's admission role is
    // shadowed by the shared disk-pool hasHeadroom() gate (pool depth ~165 < 200), so it was never
    // an operator-meaningful knob. 800 preserves the historic default (old syncCap 200 * 4). The
    // gen frontier still derives from generationConcurrencyLimitPerPlayer (a real limiter) * the
    // multiplier; ServerConfigBase.validate() clamps that so sync budget + gen frontier + reserve
    // fits MAX_BATCH_CHUNK_REQUESTS (pinned by WantSetBudgetInvariantTest).
    public static final int WANT_SET_SYNC_BUDGET = 800;

    // Server per-player SYNC (disk-read) slot cap. Formerly the config field
    // syncOnLoadConcurrencyLimitPerPlayer; now a constant. Its value and the router's SLOT_FULL vs
    // NO_DISK_HEADROOM retain semantics are unchanged — only the source (config -> constant).
    public static final int SYNC_ON_LOAD_SLOT_CAP = 200;
```
- [ ] **Step 4: Run — passes.** Same command → green.
- [ ] **Step 5: Commit.** `wantset: add constant sync budget + sync-slot-cap + one-batch invariant`

### Task 2: SpiralScanner sizes the SYNC budget from the constant (gen frontier unchanged)

**Files:** Modify `fabric/…/networking/client/SpiralScanner.java`; Test `SpiralScannerTest`.

- [ ] **Step 1: Update the SpiralScanner budget test** — assert the base sync budget is `WANT_SET_SYNC_BUDGET` (800) independent of session config; leave the gen-frontier assertions (`genCap × BUDGET_MULTIPLIER`) unchanged. Keep queue-pressure and vanilla-load scaling assertions.
- [ ] **Step 2: Run — fails.** `./gradlew :fabric:test --tests '*SpiralScannerTest' -x runGameTest -x runClientGameTest`
- [ ] **Step 3: Change only the sync line.** `SpiralScanner.java:78`:
```java
        int budget = LSSConstants.WANT_SET_SYNC_BUDGET;   // was sessionConfig.syncOnLoad...() * BUDGET_MULTIPLIER
```
Leave line 111 (`int genCap = sessionConfig.generationConcurrencyLimitPerPlayer() * BUDGET_MULTIPLIER;`) as-is. Keep the `BUDGET_MULTIPLIER` field (still used at 111).
- [ ] **Step 4: Run — passes.**
- [ ] **Step 5: Commit.** `scanner: size the SYNC budget from a constant (gen frontier unchanged)`

### Task 3: Remove ONLY the sync field from the Fabric SessionConfig payload + client gate

**Files:** Modify `SessionConfigS2CPayload.java`, `ClientSessionGate.java`, `LSSServerNetworking.java`; Tests `WireParityTest`, `PayloadCodecTest`, `VoxelSectionPayloadTest`, `ClientSessionGateTest`.

**Interfaces — Produces:** `SessionConfigS2CPayload(protocolVersion, enabled, lodDistanceChunks, generationConcurrencyLimitPerPlayer, generationEnabled)` — 5 components. Codec writes/reads 5.

- [ ] **Step 1: Update the wire tests FIRST** to the 5-field shape. In `WireParityTest` delete only the `syncOnLoadConcurrencyLimitPerPlayer` `writeVarInt` + decode assertion (KEEP the gen one); in `PayloadCodecTest`/`VoxelSectionPayloadTest` drop only the sync round-trip assertion; in `ClientSessionGateTest` drop only the `syncOnLoadConcurrencyLimitPerPlayer()` MIN/MAX clamp assertions (KEEP the gen clamp ones). Update each SessionConfig constructor call to 5-arg.
- [ ] **Step 2: Run — fails to compile.** `./gradlew :fabric:test -x runGameTest -x runClientGameTest`
- [ ] **Step 3: Edit `SessionConfigS2CPayload.java`** — delete the `syncOnLoadConcurrencyLimitPerPlayer` record component and its `writeVarInt`/read line; keep `generationConcurrencyLimitPerPlayer`. Fix the version-mismatch fallback constructor (`SessionConfigS2CPayload.java:38` area) to 5 args.
- [ ] **Step 4: Edit `ClientSessionGate.java`** — drop the sync `Math.clamp(...)` (line ~156); keep the gen clamp (line ~157).
- [ ] **Step 5: Edit `LSSServerNetworking.java:93`** — drop `config.syncOnLoadConcurrencyLimitPerPlayer` from the payload constructor; keep `config.generationConcurrencyLimitPerPlayer`.
- [ ] **Step 6: Run — passes.** `./gradlew :fabric:test -x runGameTest -x runClientGameTest` → green.
- [ ] **Step 7: Commit.** `wire: drop syncOnLoad from the Fabric SessionConfig payload (gen field stays)`

### Task 4: Remove the sync field from the Paper encoder + plugin bridge

**Files:** Modify `PaperPayloadHandler.java`, `LSSPaperPlugin.java`, `PaperRequestProcessingService.java`; Tests the Paper wire-parity twin + `LSSPaperPluginGlueTest`.

**Interfaces — Consumes:** the 5-component wire shape from Task 3. **Produces:** byte-parity preserved; the `LSSPaperPlugin` bridge drops one int param.

- [ ] **Step 1: Update the Paper wire-parity test + `LSSPaperPluginGlueTest`** to the 5-field shape (delete only the sync write/assert; update the bridge-signature expectation). `./gradlew :paper:test` → fails to compile.
- [ ] **Step 2: Edit `PaperPayloadHandler.encodeSessionConfig`/`sendSessionConfig`** — drop the one `syncOnLoad` int param + its `writeVarInt`; wire is now `protocolVersion, enabled, lodDistanceChunks, generationConcurrencyLimitPerPlayer, generationEnabled`.
- [ ] **Step 3: Edit `LSSPaperPlugin.java`** — the bridge interface (line ~227) drops `int syncOnLoadConcurrencyLimitPerPlayer`; the call (line ~285) stops passing `config.syncOnLoadConcurrencyLimitPerPlayer`.
- [ ] **Step 4: Edit `PaperRequestProcessingService`** — stop passing sync to `sendSessionConfig`.
- [ ] **Step 5: Run — passes.** `./gradlew :paper:test` → green.
- [ ] **Step 6: Commit.** `wire: drop syncOnLoad from the Paper SessionConfig encoder + bridge`

### Task 5: Delete the `syncOnLoad` config knob; #28 → one-sided gen clamp

**Files:** Modify `ServerConfigBase.java`, `RequestProcessingService.java`, `PaperRequestProcessingService.java`; Tests `ConfigValidationTest` (Fabric), `PaperConfigValidationTest`, `PaperConfigLoadTest`, `JsonConfigLoadTest`, `LSSGameTests`, `ServiceLifecycleGameTests`.

**Interfaces — Consumes:** `SYNC_ON_LOAD_SLOT_CAP`, `WANT_SET_SYNC_BUDGET`. **Produces:** `ServerConfigBase` has no `syncOnLoadConcurrencyLimitPerPlayer`; `validate()` gen-clamps to `min(genCap, 40)`.

- [ ] **Step 1: Update config tests FIRST.**
  - Fabric `ConfigValidationTest`: delete `syncOnLoadConcurrencyLimitPerPlayerClamped`; change `generationConcurrencyLimitPerPlayerClamped` to expect **40** (was 190 — the one-sided clamp against the constant 800 budget: `(1024−800−64)/4`); delete `maxConfigLeavesFrontierHeadroomInTheWantSetBudget` (invariant now in `WantSetBudgetInvariantTest`).
  - Paper `PaperConfigValidationTest`: remove `syncOnLoadConcurrencyLimitPerPlayer` from `SHARED_BOUNDS`; recompute the helper constants (lines ~51-57) — `DEFAULT_SYNC_CAP` is gone; `GEN_BUDGET_MAX` becomes a fixed **40** computed from `WANT_SET_SYNC_BUDGET`/reserve/mult (not from the deleted field); the anti-vacuity check (line ~97, SHARED_BOUNDS keys == PaperConfig numeric fields) forces the sync entry's removal in lockstep. Delete the Paper `maxConfigLeavesFrontierHeadroom…` twin.
  - `PaperConfigLoadTest` + `JsonConfigLoadTest`: delete the `syncOnLoadConcurrencyLimitPerPlayer` default assertions.
  - `LSSGameTests`: delete the sync-cap range assertion (line ~136).
- [ ] **Step 2: Delete the `ServiceLifecycleGameTests` sync-cap end-to-end test** (lines ~738-769): it sets `config.syncOnLoadConcurrencyLimitPerPlayer = 252` and asserts it wires to the client SessionConfig — both the field and that wire component are gone. Note the deletion in the commit body.
- [ ] **Step 3: Run — fails to compile.** `./gradlew :fabric:test :paper:test -x runGameTest -x runClientGameTest`
- [ ] **Step 4: Edit `ServerConfigBase.java`** — delete `public int syncOnLoadConcurrencyLimitPerPlayer = 200;` and its clamp; rewrite the #28 block to a one-sided gen clamp:
```java
        // Global Constraint #28 (one-sided, post sync-decouple): the client's SYNC want-set budget
        // is now the constant WANT_SET_SYNC_BUDGET, so only the gen frontier is variable. Keep it
        // from stealing the frontier reserve: sync budget + genCap*mult + reserve <= MAX_BATCH.
        int mult = LSSConstants.SCAN_BUDGET_MULTIPLIER;
        int reserve = LSSConstants.WANT_SET_FRONTIER_RESERVE;
        int maxGen = Math.max(LSSConstants.MIN_CONCURRENCY_LIMIT,
                (LSSConstants.MAX_BATCH_CHUNK_REQUESTS - LSSConstants.WANT_SET_SYNC_BUDGET - reserve) / mult);
        generationConcurrencyLimitPerPlayer = Math.min(generationConcurrencyLimitPerPlayer, maxGen);
```
(This runs after the plain `[MIN, MAX_CONCURRENCY_LIMIT]` clamp on gen, exactly as the old code layered.)
- [ ] **Step 5: Edit both services** — `RequestProcessingService.java:108` and the Paper `PlayerRequestState` construction: pass `LSSConstants.SYNC_ON_LOAD_SLOT_CAP` where they passed `config.syncOnLoadConcurrencyLimitPerPlayer`. Gen slot cap still from `config.generationConcurrencyLimitPerPlayer`.
- [ ] **Step 6: Run — passes.** `./gradlew :fabric:test :paper:test -x runGameTest -x runClientGameTest` → green.
- [ ] **Step 7: Commit.** `config: delete syncOnLoad knob; #28 becomes a one-sided gen clamp`

### Task 6: Soak scenario configs — remove the dead key + re-baseline `rate-limit-storm`

**Files:** Modify `scripts/soak-scenarios/*.json`, `scripts/check_soak.py`.

- [ ] **Step 1: Drop the dead key** `syncOnLoadConcurrencyLimitPerPlayer` from the configs that set it to the default 200 — `bandwidth-throttle`, `disk-saturation`, `generation-capacity-stress`, `generation-disabled` — pure cleanup (they set the old default; behavior identical). Leave every `generationConcurrencyLimitPerPlayer` value as-is (it still works, and gen=40 is exactly the new clamp max).
- [ ] **Step 2: Re-baseline `rate-limit-storm`.** Its `syncOnLoadConcurrencyLimitPerPlayer: 4` was the entire premise (syncCap 4 → want-set 16 → no contention → `service.superseded <= 50` ceiling). The constant 800 want-set removes that lever; leaving the config as-is would silently declare 800/scan and flip the ceiling to failing. Repurpose to slow-*service* contention (the only v17-honest way to create want-set contention, per the CLAUDE.md soak rule): drop the dead key, set `diskReaderThreads: 1`, and flip the `check_soak.py` assertion to a supersession *floor* (`service.superseded >= N`, N measured live in Task 7). Comment records why the syncCap premise died (decoupling → constant want-set) and cites this plan. If this makes it redundant with `disk-saturation` (both threads:1), note that and consider retiring it — decide from the measured run.
- [ ] **Step 3: `check_soak.py`** — remove `syncOnLoadConcurrencyLimitPerPlayer` from `SERVER_CONFIG_INT_KEYS` (the `--validate` unknown-key guard should now reject it if re-added). Update the `rate-limit-storm` named assertion. `python3 scripts/check_soak.py --selftest` → passes (the named-check registry changed). `python3 scripts/check_soak.py --validate` on the edited scenarios → PASS.
- [ ] **Step 4: Commit.** `soak: drop dead syncOnLoad key; re-baseline rate-limit-storm for the constant want-set`

### Task 7: Part 1 validation (behavioral no-op proof)

- [ ] **Step 1:** `./gradlew :fabric:test :fabric:runGameTest :paper:test :paper:shadowJar` → all green.
- [ ] **Step 2:** `./gradlew :fabric:runClientGameTest` → green.
- [ ] **Step 3: Soak — the no-op proof.** `./scripts/soak.sh fresh-backfill` and `SOAK_PLATFORM=paper ./scripts/soak.sh all`. Under sync-only, every effective value is preserved on all scenarios except `rate-limit-storm`: sync budget 800 (they never overrode syncCap except the storm), gen frontier `genCap×4` unchanged (gen values untouched), sync slot cap 200. So conservation counters must match pre-change — any delta on a non-storm scenario is a decoupling bug. `rate-limit-storm` is expected to change (Task 6); record its NEW measured floor, not a guess.
- [ ] **Step 4: Commit** any doc note. Part 1 is independently shippable here.

---

## Part 2 — Real-limiter generation admission

### Task 8: Paper — generate at Moonrise `Priority.LOW`

Replace the `NORMAL`-priority Bukkit `getChunkAtAsync` with the Moonrise priority-aware `scheduleChunkLoad` so LOD generation defers to player-driven generation. **Reuse the existing seams** (`launchAsyncLoad` is package-visible + override-based for tests; `completeAsyncLoad` is the completion path) — do NOT add a parallel `GenerationLauncher`.

**Files:** Modify `PaperChunkGenerationService.java`; Test: extend `PaperChunkGenerationServiceTest` via the existing `launchAsyncLoad` override to capture the `Priority`.

- [ ] **Step 1: Write the seam test** — override `launchAsyncLoad` (existing test seam, line ~133) to record the `Priority` handed to it and complete synchronously with a stub NMS chunk; assert `submitGeneration` drives `Priority.LOW` (not `NORMAL`/`HIGHER`) and that completion still routes through the serialization path + the monotonic-`token` stale guard. `./gradlew :paper:test` → fails (still `getChunkAtAsync`).
- [ ] **Step 2: Adapt the completion contract.** `getChunkAtAsync(...).whenComplete((Bukkit Chunk, Throwable ex) -> completeAsyncLoad(...))` becomes a Moonrise `Consumer<ChunkAccess>` — **NMS `ChunkAccess`, no throwable channel.** Refactor `completeAsyncLoad` (line ~151) to take a `net.minecraft.world.level.chunk.ChunkAccess` (cast to `LevelChunk` for serialization, as the code already re-fetches NMS) and drop the `Throwable ex` parameter + its error branch (lines ~153-155, ~177-183). Moonrise surfaces failure as a null/absent chunk, not an exception — handle a null chunk as the generation-failure outcome (feed failures for the callbacks, same as the timeout path). Keep the `token` stale-guard and the per-player/global admission caps as the safety ceiling.
- [ ] **Step 3: Implement the production `launchAsyncLoad`:**
```java
    ca.spottedleaf.moonrise.common.PlatformHooks.get().scheduleChunkLoad(
        level, cx, cz, /*gen=*/true,
        net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
        /*addTicket=*/true, ca.spottedleaf.concurrentutil.util.Priority.LOW,
        (net.minecraft.world.level.chunk.ChunkAccess chunk) -> completeAsyncLoad(/* token, uuid, cx, cz, */ chunk));
```
`addTicket=true` lets Moonrise own the load ticket (Paper had no manual ticket bookkeeping — that is the Fabric service; nothing to remove here). Serialize in the completion on the owning thread via `PaperSectionSerializer.serializeColumn`, exactly as today.
- [ ] **Step 4: Run — passes.** `./gradlew :paper:test` → green; `./gradlew :paper:shadowJar`.
- [ ] **Step 5: Commit.** `paper: generate LOD chunks at Moonrise Priority.LOW (defers to player generation)`

### Task 9: Fabric — record the generation-priority limitation (no throttle shipped)

Vanilla exposes no generation priority (FULL pins ticket level 33), and worldgen latency is a poor congestion signal — a high, variable natural baseline means an absolute-latency AIMD would collapse LSS generation on a fresh world with **no** contention (verified: the flat-world soak generates in ~1 tick, so the harness cannot even validate such a throttle). So **no adaptive generation throttle is shipped for Fabric.** The concurrency cap (`generationConcurrencyLimitGlobal`/`PerPlayer`) stays the Fabric limiter.

- [ ] **Step 1: Document the asymmetry** in `docs/planning/resource-management.md` (generation section) and the `CLAUDE.md` generation bullet: Paper defers LOD generation at Moonrise `Priority.LOW`; Fabric cannot (ticket-level pinning) and keeps the concurrency cap. Record the rejected option and the correct future direction: if Fabric needs adaptive gen backoff, key it on **server tick health (MSPT)**, not worldgen latency — a real congestion signal that doesn't conflate with natural terrain cost. (No code change.)
- [ ] **Step 2:** `./gradlew :fabric:test -x runGameTest -x runClientGameTest` → green (no code touched).
- [ ] **Step 3: Commit.** `docs: record why Fabric generation keeps the concurrency cap (no priority, latency unreliable)`

### Task 10: Docs + validation

- [ ] **Step 1: Docs.** `README.md`, `CLAUDE.md` (both generation bullets), `docs/planning/resource-management.md`: A-only generation protection — Paper Moonrise `Priority.LOW`; Fabric concurrency cap (asymmetry per Task 9). Note Folia experimental status for the Paper change.
- [ ] **Step 2: Gauntlet.** `./gradlew :fabric:test -x runClientGameTest`, `./gradlew :fabric:runClientGameTest`, `./gradlew :paper:test :paper:shadowJar` → green.
- [ ] **Step 3: Soak.** `./scripts/soak.sh fresh-backfill` (Fabric gen path unchanged) and `SOAK_PLATFORM=paper ./scripts/soak.sh all` (the live Paper `Priority.LOW` generation path) → verdicts PASS. Folia unrunnable (26.2 unpublished); note it.
- [ ] **Step 4: Commit** doc/flake updates.

---

## Self-Review

- **Sync-only, per the user's decision:** only `syncOnLoadConcurrencyLimitPerPlayer` is removed; `generationConcurrencyLimitPerPlayer` stays a knob, on the wire (6→5 fields), driving the gen frontier. The two verification gaps of full-decouple (gen knob capped at 64; soak gen-frontier 160→64) are avoided. ✅
- **No behavioral delta in Part 1** except the re-baselined `rate-limit-storm`: every other effective value preserved; soak is the no-op proof. ✅
- **Admission semantics preserved:** the sync slot cap is kept at constant 200 with the same router paths (verified value-identical). ✅
- **#28 survives correctly** as a one-sided gen clamp (`≤40`); `SCAN_BUDGET_MULTIPLIER`/`WANT_SET_FRONTIER_RESERVE` retained (still used). The gen post-clamp max moves 190→40; soak's gen=40 stays valid; default 16 unaffected. ✅
- **Verification fixes folded:** G1 (no Fabric gen throttle — worldgen latency unreliable + harness can't validate; documented), G3 (Paper callback refactored to `Consumer<ChunkAccess>`, no throwable, reuse existing seams, no false "manual ticket" claim), G5 (`check_soak.py` whitelist), G6 (Paper test helper constants + fallback constructor named). ✅
- **Generation A/B matches the research:** Paper gets the real `Priority.LOW` hand-off; Fabric honestly keeps the cap (no priority, latency unreliable). ✅
- **Type consistency:** `SessionConfigS2CPayload` 6→5-arg consistently across record, Fabric codec + fallback constructor, Paper encoder, plugin bridge, client gate, server send, every test constructor. ✅
