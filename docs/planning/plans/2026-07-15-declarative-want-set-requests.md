# Declarative Want-Set Requests (protocol v17) — Both Platforms — Implementation Plan


**Goal:** Replace the request-slice + bounce/backoff protocol with a declarative want-set: once per second the client sends its COMPLETE current want-set (every unsatisfied position, closest-first, capped at 1024), and the server REPLACES its per-player backlog with each arriving batch. A full concurrency slot leaves a request queued instead of bouncing it; stale queued work is dropped by the next replace and self-heals through re-declaration. `RESPONSE_RATE_LIMITED` leaves the wire. Movement/teleport staleness disappears, proximity ordering falls out of arrival order, and issue #32's root cause (per-player allowance 200 > shared disk pool 165) stops being client-visible.

**Architecture:**

- **Client (Fabric):** the 20-tick spiral scan already produces the want-set in closest-first order — it becomes the batch, sent whole in the same tick. The drip-feed (`maxSendPerTick`, `RequestQueue`, `drainQueue`), the 10 s timeout sweep, the rate-limit backoff (`skipNextScan`), and the client-side mirroring of server concurrency limits are deleted. **Re-declaration is load-bearing, not an optimisation**: the scanner stops suppressing in-flight positions, so every unsatisfied position is re-declared each scan — this is what makes server-side silent drops safe. `InFlightTracker` slims to an *awaiting-answer set* (positions of the last sent batch minus answers), kept only for status-response gating (BatchResponse carries no dimension) and dirty-crossing detection. Dirty/retry marks move from send-time consumption (`markSent`) to answer-time consumption — under re-declaration a mark consumed at send would stop the re-declares before the answer lands.
- **Server (common, both platforms):** the network→processing `ConcurrentLinkedQueue` mailbox + `MAX_INCOMING_QUEUE` become an `AtomicReference<IncomingBatch>` (latest-wins — an overwritten batch is superseded wholesale, which IS the want-set semantics), plus a processing-thread-owned `ArrayDeque<IncomingRequest>` backlog (≤1024) that each taken batch replaces. The router runs the existing resolution ladder over the backlog each cycle; a full slot or an exhausted disk pool *retains* the entry (order preserved) instead of bouncing. Every silent drop is counted in a new monotonic `service.superseded` counter; entries filtered by the Chebyshev ingress guard are counted in a new `service.range_filtered` counter — together they close the request-conservation law without a wire response.
- **Backpressure is relocated, not removed:** the bounded want-set (≤1024) bounds the backlog; per-player slot caps become dequeue gates; a new `AbstractChunkDiskReader.hasHeadroom()` check stops the router from submitting into a full pool (saturation stops being client-visible); `SharedBandwidthLimiter` + `sendQueueLimitPerPlayer` + the client decode-queue scale are untouched. The client's queue-pressure budget scale becomes an *active* brake: a shrunken want-set replaces (shrinks) the server backlog. Entering the decode-queue backpressure halt sends one explicit **empty batch** (count 0) — the only time an empty batch is sent — which clears the server backlog.
- **Soak harness is part of the change:** law A1 is re-derived (gains `superseded` + `range_filtered`, loses `rate_limited` + `queue_full`), law B1 is deleted, the quiescence predicate gains the server `backlog` drain and keeps `tracker_in_flight` (its meaning — declared-and-unanswered — is preserved by the awaiting set), and `rate-limit-storm` / `disk-saturation` are re-premised rather than deleted.

**Tech Stack:** Java 25 (Fabric/Paper), Java 21 (common); Fabric Loom, paperweight-userdev; MC 26.2; stdlib Python 3 for the soak checker. No new dependencies.

**Branch:** `feat/want-set-requests` off `main` **after PR #36 (`feat/read-io-priority`) merges** — the design assumes reads already yield to gameplay (that is what retires the sync-rate-limit rationale).

## Global Constraints

- **PROTOCOL_VERSION 16 → 17.** Justified and required: a v16 client's batch is a ~40-position slice sent 20×/s; under replace semantics 19/20 of its requests would be destroyed and its in-flight suppression would delay re-asks by the 10 s timeout — ~5 % throughput with 10 s stalls. `HandshakeGate`'s first rung (version mismatch → **no reply**) cleanly leaves old clients with no LOD session, in both directions (client+server ship in one jar). Both literal version pins must move: `ProtocolConstantsTest.java:184` and paper `WireParityTest.java:264`.
- **Wire SHAPE is unchanged; wire SEMANTICS change.** `BatchChunkRequestC2SPayload` keeps `(long[] packedPositions, long[] clientTimestamps, int count)`, `count ≤ MAX_BATCH_CHUNK_REQUESTS = 1024` (unchanged — it now also bounds the server backlog). `RESPONSE_UP_TO_DATE = 1` and `RESPONSE_NOT_GENERATED = 2` keep their byte values; **byte 0 is retired and reserved, never reused** (an unknown response type is already skipped inert by the client dispatch — pinned by `dispatchRoutesEachTypeAndUnknownTypeSkipsOnlyThatEntry`).
- **HARD SEQUENCING CONSTRAINT — no intermediate state may ship server-side silent drops while the client still suppresses in-flight positions at send.** That combination costs a 10 s stall per dropped entry. This plan therefore lands the **client first** (re-declaration against the unchanged server is safe: re-declared pending positions resolve as `duplicate_skips`, slot bounces are absorbed as metrics-only and healed by the next scan) and the server replace semantics **after**.
- **Empty batch = explicit "want nothing" (clears the backlog).** Sent exactly once, edge-triggered, when the client *enters* the decode-queue backpressure halt. At convergence the client sends **nothing at all** — a hard protocol invariant (a 1 Hz heartbeat would keep `service.requests_received` moving forever and silently disable every soak law via the quiescence predicate). Both pinned by Tier-1 tests.
- **Three `RESPONSE_RATE_LIMITED` producers, three dispositions:** (1) router slot-full bounce (`IncomingRequestRouter.java:235`) → entry stays in the backlog, no response; (2) disk-pool saturation (`OffThreadProcessor.java:659`) → prevented up front by `hasHeadroom()`, and the residual triage path (races, shutdown) drops silently and counts `superseded`; (3) the delivery-honesty bounce for a ts≤0 re-ask while the column is still in the send pipeline (`IncomingRequestRouter.java:112`) → silent `duplicate_skip` (the column IS coming; the client re-declares at 1 Hz until it lands). **The done-bit is still NOT cleared while `hasEnqueuedColumn` — the honest re-resolution ladder (`IncomingRequestRouter.java:89-104`) is untouched.**
- **`superseded` semantics (one counter, one law term):** "a received request dropped without a wire response, recoverable because the client re-declares every unsatisfied position each scan." Producers: mailbox overwrite (network thread), backlog replace (processing thread), Folia held-batch CAS loss (pump thread), residual saturation drop (processing thread, per recipient), dedup-primary-departure attached drops (processing thread, per removed pending — today's pre-existing uncounted silent drop, addendum §D). Cross-thread producers accumulate in per-player `AtomicLong`s drained into `ProcessingDiagnostics` by the processing thread each cycle, preserving that class's single-writer design.
- **Dedup + invalidation-overtake guards are structurally unaffected:** `DedupTracker` groups, `invalidatedInFlight`, and `generationInFlight` are all created only AFTER `tryAdmit` succeeds (verified: `IncomingRequestRouter.java:197-202`, `:223`, `OffThreadProcessor.handleDiskNotFound`). Backlog entries have no dedup group, no pending entry, no stale-guard entry — dropping them needs zero teardown and is immune to the invalidation-overtake race by construction.
- **Deliberately KEPT (do not "simplify" these away):** the scanner's vanilla-load budget scale (client decode/bandwidth still compete with vanilla chunk ingestion — read-priority only fixed the server disk side); the scanner's generation hold-back (`genCap = genLimit×4 = 64` per batch — without it a fresh world's 800-position want-set floods 200 disk reads/s that all miss and bounce as `not_generated`; this is a conscious exception to "the client stops mirroring server limits", documented in code); `BUDGET_MULTIPLIER = 4` (in-flight re-declarations now occupy ≤ syncCap+genCap ≈ 216 of the 800 budget, leaving ~584/s of frontier discovery — far above sustained serve rates; the window self-throttles to delivery rate, so raising the multiplier only inflates duplicate-skip traffic); `sendQueueFull` as a router-pass breaker (send-queue memory is real backpressure — retained entries keep their order and `queue_full` stays a pure diagnostic counter, dropped from law A1); the `ColumnStateMap` classify ladder, `sessionSatisfied`, `staleInFlight`, ingest-failure parking — all untouched.
- **The want-set cap MUST exceed the slot caps by a real margin — clamp it (new, from adversarial review).** Once in-flight positions are re-declared they consume budget, which creates a collision today's code cannot have: `MAX_CONCURRENCY_LIMIT = 1000` (LSSConstants.java:69) vs `MAX_BATCH_CHUNK_REQUESTS = 1024` (:72). At `syncOnLoadConcurrencyLimitPerPlayer = 1000`, `budget = 4 × 1000 = 4000` clamps to 1024 while up to `1000 + genCap` entries are in-flight re-declarations — headroom collapses to ~24 (or below zero), the server drains the batch instantly and then **starves for the rest of the second**, on a config that is legal today. The `≈216 of 800` arithmetic in the `BUDGET_MULTIPLIER` note above holds only at DEFAULT caps. Fix in `ServerConfigBase.validate()`: after the existing clamps, clamp `syncOnLoadConcurrencyLimitPerPlayer` (and `generationConcurrencyLimitPerPlayer`) so that `syncCap + genCap*BUDGET_MULTIPLIER_GEN_SHARE + 64 <= MAX_BATCH_CHUNK_REQUESTS`, i.e. the declared want-set can always carry every in-flight position plus ≥64 frontier slots. State the invariant in a comment (`want-set cap must dominate the slot caps, or frontier discovery starves`) and pin it with a Tier-1 boundary test at the MAX config values — precisely the values nobody exercises today. Add the clamp in Task 2 (it is client-visible via SessionConfig) and the test alongside `ConfigValidationTest`'s existing sweeps (note the Paper twin's reflective `everyNumericFieldClampsToExactSharedBoundsAtBothEnds` sweep keys on `SHARED_BOUNDS` — a changed clamp must be mirrored there or that test fails).
- **No server-side player-position plumbing, no progress pointer, no priority field.** The want-set is a *sliding window of unsatisfied positions*: as the head resolves, those positions classify SATISFIED, drop out of the next batch, and the window advances at exactly the serve rate. Arrival order (closest-first by construction) is the whole ordering mechanism. Tail starvation in the earlier design brief was analysed and is a non-problem; see Rough Edges §1.
- **Folia stays experimental**; release notes for this change must say so. Folia 26.2 is unpublished upstream — `SOAK_PLATFORM=folia` cannot run on this MC line; `SOAK_PLATFORM=paper` carries the Bukkit-side validation and `RegionProbeSchedulingTest` carries the Folia seams.
- **This is one atomic protocol bump, one PR.** Tasks are sequenced so `:fabric:test` (Tier 1) and `:paper:test` are green at every commit; Tier 2/3 gametests are brought green in their own task (6/7) and the full gauntlet gates the PR. Do not ship any subset.

## Known rough edges (read before implementing — these are accepted, not hidden)

1. **Proximity priority defers the tail under sustained inner churn — by design.** If dirty broadcasts keep re-dirtying inner columns every second, those outrank frontier expansion in every batch. This terminates whenever inner churn pauses, inner re-serves are cheap (loaded chunks serve from the in-memory probe), and the tail is never *lost* — it is re-declared the moment budget allows. v16 gave FIFO bounded staleness instead; we are trading that for proximity. No mechanism is added for this.
2. **Loaded-probe alignment is best-effort on Fabric/Paper (deterministic on Folia).** The main thread probes the *published* want-set (`peekWantSet()`), not the mailbox. **This matters: an earlier draft had the probe peek the mailbox, which `takeIncomingBatch()` nulls within ~50 ms of arrival while batches arrive at only 1 Hz — the probe would have seen null on ~19 of every 20 ticks and lost a coin-flip race on the 20th, roughly halving in-memory probe coverage on BOTH Fabric and Paper. Do not "simplify" `peekWantSet()` back into `peekIncomingBatch()`.** The residual window is small: a position admitted before its first probe pass serves from disk once (healed by the dirty broadcast on the next save — the same self-heal class as PR #36's Fabric read-your-writes note). Folia's one-tick hold-release keeps its deterministic alignment.
3. **`requested_total` and RTT change meaning.** `requested_total` becomes "want-set entries declared" (re-declares counted — it inflates during activity and that is what the new A1 balances against). RTT stamps are overwritten per re-declare, so `rtt.p50/p95` measure *last-declare→answer* (current service latency), not first-ask→answer. Documented in the exporter and soak docs; the fields keep their names.
4. **A phantom awaiting entry can linger** when a declared position is superseded server-side AND simultaneously leaves the client's scan window (e.g. moves inside vanilla's render view), so it is never re-declared and never answered. It is bounded (≤1024), pruned by movement/dimension change, and cleared by the awaiting-set *replace* on every fired scan — but in a pathological stationary case it could hold `tracker_in_flight > 0` and block soak quiescence. The replace-on-every-fired-scan rule (including empty scans) is the mitigation; watch for it in validation.
5. **`MIN_CLIENT_WINDOWS` floors were calibrated against drip-feed traffic.** The traffic floor keys on `d(requested_total) > 0`, which moves *more* under re-declaration, so floors should still pass — but Task 10 must recalibrate any that fail, with a comment.
6. **Mid-sequence Tier 2/3 are red between Tasks 4 and 7.** Tier 1 + `:paper:test` gate every commit; the gametest tiers are updated in Task 7. Do not push the branch to CI between those tasks expecting green gametest jobs.
7. **The `enqueuedColumns`/done-bit honesty machinery is test-pinned and deliberately untouched** (project memory: review findings vs pinned decisions). The only change in that neighbourhood is the *response* to an in-pipeline ts≤0 re-ask (silent skip instead of a bounce). If a rewrite there looks tempting, stop.

---

## File Structure

**Create:**
- `common/src/main/java/dev/vox/lss/common/processing/IncomingBatch.java` — immutable batch record for the mailbox.
- `fabric/src/test/java/dev/vox/lss/common/processing/BacklogReplaceTest.java` — mailbox/backlog/superseded unit tests.

**Modify (main source):**
- `common/…/LSSConstants.java` — `PROTOCOL_VERSION = 17`; delete `RESPONSE_RATE_LIMITED` (reserve byte 0 in a comment).
- `common/…/processing/AbstractPlayerRequestState.java` — mailbox + backlog + gauges + pending superseded/range-filtered counters; delete CLQ, `MAX_INCOMING_QUEUE`, `enqueueIncomingRequest`, `pollIncomingRequest`, `reinjectIncomingRequest`, `getIncomingRequests`, `getIncomingRequestCount`, `totalIncomingDropped`.
- `common/…/processing/IncomingRequestRouter.java` — backlog pass with replace, retain-instead-of-bounce, headroom gate, silent enqueued-dup; delete `rateLimit`.
- `common/…/processing/OffThreadProcessor.java` — silent saturated drop (counted), dedup-cleanup superseded counting, `hasDiskHeadroom()`.
- `common/…/processing/ProcessingDiagnostics.java` — add `superseded`/`range_filtered`; delete the rate-limited counters.
- `common/…/processing/SendAction.java` — delete `RateLimited`.
- `common/…/processing/AbstractChunkDiskReader.java` — `hasHeadroom()`.
- `fabric/…/server/RequestProcessingService.java` — batch ingress, probe-from-peek.
- `fabric/…/server/PlayerRequestState.java` — delete `addRequest`.
- `paper/…/PaperRequestProcessingService.java` — batch ingress, probe-from-peek, Folia hold-release on batches.
- `paper/…/PaperPlayerRequestState.java` — delete `addRequest`.
- `fabric/…/client/LodRequestManager.java` — want-set tick shape; delete drip-feed/sweep/backoff/`onRateLimited`.
- `fabric/…/client/SpiralScanner.java` — array sink, no in-flight skip, no backoff, budget clamp.
- `fabric/…/client/ColumnStateMap.java` — answer-time mark consumption; delete `markSent`.
- `fabric/…/client/InFlightTracker.java` — rewrite as awaiting-answer set.
- `fabric/…/client/LSSClientNetworking.java` — delete the byte-0 dispatch arm.
- `fabric/…/client/LSSClientCommands.java` — drop the rate-limited stat line.
- `fabric/…/client/RequestMetrics.java` — delete rate-limited counter (Task 6).
- **Delete:** `fabric/…/client/RequestQueue.java`.
- `fabric/…/benchmark/BenchmarkMetricsExporter.java`, `paper/…/soak/PaperSoakMetricsExporter.java` — field changes.
- `scripts/check_soak.py`, `scripts/soak_report.py`, `docs/planning/soak-test-design.md` — laws, quiescence, scenarios, selftests.
- `README.md`, `CLAUDE.md`.

**Test files (touched):** enumerated per task; headline rewrites are `LodRequestManagerTest`, `LodRequestManagerTickTest`, `SpiralScannerTest`, `InFlightTrackerTest` (rewrite), `RequestQueueTest` (delete), `IncomingRequestRouterTest`, `SlotAdmissionTest`, `DedupFanoutTest:503`, `OffThreadProcessorDiskResultTest:181`, `PaperPlayerRequestStateTest`, `PaperRequestProcessingServiceTest`, `RegionProbeSchedulingTest`, `ExporterContractTest` + both `.contract` fixtures, `PaperExporterContractTest`, `ProtocolConstantsTest`, both `WireParityTest`s, `SendActionBatcherTest`, and the Tier-2 gametest seeding call sites.

---

## Task 1: Client — marks are consumed by ANSWERS, not by sends

Under re-declaration, a dirty/retry mark consumed at send time (`markSent`) makes the position classify SATISFIED on the next scan while its answer is still in flight — if the server superseded that ask, the edit is lost until the next session. Marks must persist across declares and be consumed by the terminal answer. `onReceived` already clears both; `onUpToDate`/`onNotGenerated` must too. This task is green standalone (send-time AND answer-time consumption coexist harmlessly until Task 2 deletes `markSent`).

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java`
- Test: `fabric/src/test/java/dev/vox/lss/networking/client/ColumnStateMapTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `ColumnStateMapTest.java`:
```java
@Test
void upToDateAnswerConsumesDirtyAndRetryMarks() {
    var map = new ColumnStateMap();
    long pos = PositionUtil.packPosition(3, 4);
    map.onReceived(pos, 100L);
    map.markDirtyIfKnown(pos);
    map.markRetry(pos);
    map.onUpToDate(pos);
    assertEquals(ColumnStateMap.SATISFIED, map.classify(pos, true),
            "an up-to-date answer must consume the dirty and retry marks — under "
            + "re-declaration nothing else ever consumes them");
}

@Test
void notGeneratedAnswerConsumesDirtyAndRetryMarksButStaysGenRetryable() {
    var map = new ColumnStateMap();
    long pos = PositionUtil.packPosition(5, 6);
    map.onReceived(pos, 100L);
    map.markDirtyIfKnown(pos);
    map.markRetry(pos);
    map.onNotGenerated(pos);
    assertEquals(0L, map.classify(pos, true),
            "ts=0 + generation enabled must still classify as a gen retry");
    assertEquals(ColumnStateMap.SATISFIED, map.classify(pos, false),
            "with generation disabled the position must park — a surviving dirty/retry "
            + "mark would re-declare it forever");
}
```

- [ ] **Step 2: Run — verify the second assertions fail**

Run: `./gradlew :fabric:test --tests '*ColumnStateMapTest' -x runGameTest -x runClientGameTest`
Expected: FAIL — `classify` returns the dirty timestamp (mark not consumed).

- [ ] **Step 3: Consume marks in the terminal answers**

In `ColumnStateMap.onUpToDate`, after the existing `this.retry.remove(packed);` add:
```java
        // Answer-time mark consumption: under want-set re-declaration, marks are no longer
        // consumed at send (markSent is gone) — the terminal answer is the only consumer.
        // The dirty-crossed-an-in-flight-serve race is covered by staleInFlight, which
        // re-marks dirty at the terminal outcome (LodRequestManager.consumeStaleCrossing).
        this.dirty.remove(packed);
```
In `ColumnStateMap.onNotGenerated`, before `put(packed, 0L);` add:
```java
        this.dirty.remove(packed);  // answer-time consumption — see onUpToDate
        this.retry.remove(packed);
```

- [ ] **Step 4: Run to green (full Tier 1 — the crossing tests must survive)**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest`
Expected: PASS (all ~592). If any `staleInFlight`/crossing test fails, the fix is wrong — stop and re-read `consumeStaleCrossing`.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java \
        fabric/src/test/java/dev/vox/lss/networking/client/ColumnStateMapTest.java
git commit -m "client: consume dirty/retry marks at terminal answers (want-set groundwork)"
```

---

## Task 2: Client — the want-set request loop

The core client rework: the scan emits the complete want-set and sends it in the same tick. Deletes the drip-feed, the timeout sweep, the backoff, the in-flight send suppression, `RequestQueue`, and `markSent`. `InFlightTracker` becomes the awaiting-answer set. `onRateLimited` becomes a metrics-only stub (the server still sends bounces until Task 4-5; re-declaration absorbs them). **This new client is fully correct against the unchanged server** — re-declared pending positions resolve as duplicate skips.

**Files:**
- Modify: `SpiralScanner.java`, `LodRequestManager.java`, `InFlightTracker.java` (rewrite)
- Delete: `RequestQueue.java`
- Tests: `SpiralScannerTest.java`, `LodRequestManagerTest.java`, `LodRequestManagerTickTest.java`, `InFlightTrackerTest.java` (rewrite), `RequestQueueTest.java` (delete)

- [ ] **Step 1: Rewrite `InFlightTracker` as the awaiting-answer set**

Replace the file body (keep the class/file name — the soak field `tracker_in_flight` and ~20 call sites keep working):
```java
package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * The awaiting-answer set: positions of the most recent want-set batch, minus answers
 * received since. This is NOT a send-suppression structure — the scanner re-declares
 * every unsatisfied position each scan regardless. It exists for exactly two consumers:
 * status-response gating (BatchResponse carries no dimension, so up_to_date /
 * not_generated gate on membership here — cleared on dimension change, matching the
 * pre-v16 requestId gate) and dirty-crossing detection (a dirty broadcast landing while
 * a position awaits an answer must survive that answer's mark-clear; see
 * ColumnStateMap.noteStaleIfInFlight).
 *
 * <p>Replaced wholesale on every fired scan ({@link #replaceWith}), so a position that
 * left the scan window (superseded + excluded) cannot linger past the next scan.
 *
 * <p><b>Thread safety:</b> Not thread-safe. Main client thread only.
 */
class InFlightTracker {

    private final LongOpenHashSet awaiting = new LongOpenHashSet();

    /** Replace the set with the positions of the batch just sent (count may be 0). */
    void replaceWith(long[] positions, int count) {
        this.awaiting.clear();
        for (int i = 0; i < count; i++) {
            this.awaiting.add(positions[i]);
        }
    }

    /** An answer arrived for this position. No-op if not tracked. */
    void removeByPosition(long position) {
        this.awaiting.remove(position);
    }

    boolean isInFlight(long position) {
        return this.awaiting.contains(position);
    }

    int size() {
        return this.awaiting.size();
    }

    /** Movement prune: answers for pruned positions fall to the untracked (metrics-only) path. */
    void pruneOutOfRange(int playerCx, int playerCz, int pruneDistance) {
        var iter = this.awaiting.iterator();
        while (iter.hasNext()) {
            if (PositionUtil.isOutOfRange(iter.nextLong(), playerCx, playerCz, pruneDistance)) {
                iter.remove();
            }
        }
    }

    void clear() {
        this.awaiting.clear();
    }
}
```

- [ ] **Step 2: Rewrite `InFlightTrackerTest`**

Delete the file's 8 tests; replace with:
```java
package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The awaiting-answer set: replaced per scan, drained by answers, pruned by movement. */
class InFlightTrackerTest {

    @Test
    void replaceWithInstallsExactlyTheBatchPositions() {
        var t = new InFlightTracker();
        t.replaceWith(new long[]{1L, 2L, 3L, 99L}, 3); // count gates, not array length
        assertTrue(t.isInFlight(1L));
        assertTrue(t.isInFlight(3L));
        assertFalse(t.isInFlight(99L), "entries past count must not be installed");
        assertEquals(3, t.size());
    }

    @Test
    void replaceWithDropsPositionsNotReDeclared() {
        var t = new InFlightTracker();
        t.replaceWith(new long[]{1L, 2L}, 2);
        t.replaceWith(new long[]{2L}, 1);
        assertFalse(t.isInFlight(1L),
                "a position not re-declared left the want-set; its late status answers "
                + "must fall to the untracked metrics-only path");
        assertTrue(t.isInFlight(2L));
    }

    @Test
    void replaceWithEmptyClearsTheSet() {
        var t = new InFlightTracker();
        t.replaceWith(new long[]{1L}, 1);
        t.replaceWith(new long[0], 0);
        assertEquals(0, t.size(), "an empty fired scan must clear phantoms (rough edge #4)");
    }

    @Test
    void answersDrainWithoutAffectingOthers() {
        var t = new InFlightTracker();
        t.replaceWith(new long[]{1L, 2L}, 2);
        t.removeByPosition(1L);
        assertFalse(t.isInFlight(1L));
        assertTrue(t.isInFlight(2L));
        t.removeByPosition(42L); // untracked: no-op
        assertEquals(1, t.size());
    }

    @Test
    void pruneOutOfRangeKeepsBoundaryDropsOutside() {
        var t = new InFlightTracker();
        long inside = PositionUtil.packPosition(10, 0);
        long outside = PositionUtil.packPosition(11, 0);
        t.replaceWith(new long[]{inside, outside}, 2);
        t.pruneOutOfRange(0, 0, 10);
        assertTrue(t.isInFlight(inside), "exactly-at-boundary is kept");
        assertFalse(t.isInFlight(outside));
    }

    @Test
    void clearEmptiesTheSet() {
        var t = new InFlightTracker();
        t.replaceWith(new long[]{1L}, 1);
        t.clear();
        assertEquals(0, t.size());
    }
}
```

- [ ] **Step 3: Rework `SpiralScanner` — array sink, no in-flight predicate, no backoff, budget clamp**

Changes to `SpiralScanner.java`:
1. Delete field `skipNextScan` (:29), methods `noteRateLimited()` (:45-48) and `clearSkipNextScan()` (:226-232), and the skip block in `maybeScan` (:67-70), plus the `skipNextScan = false` line in `reset()` (:204).
2. Change the signatures — the scanner writes straight into the caller's send buffers and no longer sees in-flight state:
```java
    /**
     * Advance the scan cadence and, when it fires with a nonzero budget, walk the rings
     * and write the complete want-set (closest-first) into {@code posOut}/{@code tsOut}.
     *
     * @return -1 when no walk happened this tick (cadence not fired, or budget scaled to
     *         zero by vanilla load); otherwise the number of want-set entries written
     *         (0 = walked and found nothing — the converged case; the caller must then
     *         send NOTHING but still replace its awaiting set)
     */
    int maybeScan(int playerCx, int playerCz, int viewDistance,
                  int columnQueueSize, int columnQueueHaltThreshold,
                  IntSupplier missingVanilla,
                  ColumnStateMap columns,
                  long[] posOut, long[] tsOut) {
        if (++this.scanTickCounter < LSSConstants.TICKS_PER_SECOND) return -1;
        this.scanTickCounter = 0;

        this.missingVanillaChunks = missingVanilla.getAsInt();

        int budget = this.sessionConfig.syncOnLoadConcurrencyLimitPerPlayer() * BUDGET_MULTIPLIER;
        if (columnQueueSize > 0) {
            budget = Math.max(1, Math.round(budget * Math.max(0f, 1f - (float) columnQueueSize / columnQueueHaltThreshold)));
        }
        if (this.missingVanillaChunks > 0) {
            int exclusionArea = (2 * viewDistance + 1) * (2 * viewDistance + 1);
            float missingFraction = (float) this.missingVanillaChunks / exclusionArea;
            float vanillaScale = Math.max(0f, 1f - missingFraction * missingFraction);
            if (vanillaScale <= 0f) budget = 0;
            else budget = Math.max(1, Math.round(budget * vanillaScale));
        }
        // The want-set must fit one wire batch: replace semantics tear across frames.
        budget = Math.min(budget, Math.min(LSSConstants.MAX_BATCH_CHUNK_REQUESTS, posOut.length));

        if (budget <= 0) return -1;

        return scan(playerCx, playerCz, viewDistance, columns, posOut, tsOut, budget);
    }
```
3. In `scan(…)`: drop the `LongPredicate isInFlight` parameter and the `queue` parameter (take `long[] posOut, long[] tsOut`); delete the in-flight skip (`if (isInFlight.test(packed)) continue;` at :150-151 **and its comment** — in-flight positions are now ordinary unsatisfied want-set members and correctly hold `ringFullySatisfied = false` until data arrives, which retires the timeout-orphan-inside-a-confirmed-ring hazard class outright); replace `queue.put(count, packed, ts)` with:
```java
                ringFullySatisfied = false;
                posOut[count] = packed;
                tsOut[count] = ts;
                count++;
```
Delete `queue.ensureCapacity(budget)` and the `queue.commit(count)` call in `maybeScan`. Everything else (ring geometry, contiguous-prefix confirmation, gen hold-back with its `genCap`, retry-driven ring reset, budget scales) stays byte-for-byte. Add one comment where the in-flight skip was:
```java
                // No in-flight suppression: re-declaration is load-bearing. The server may
                // silently supersede any not-yet-admitted ask; only the 1 Hz re-declare heals
                // that. An awaited position is unsatisfied, so it blocks ring confirmation
                // until its data actually arrives — confirmedRing lags the frontier by the
                // in-flight window, and satisfied positions skip free, so the walk stays cheap.
```

- [ ] **Step 4: Rework `LodRequestManager` — the new tick shape**

In `LodRequestManager.java`:
1. Delete: `MIN_SEND_PER_TICK` (:22), `TIMEOUT_NANOS` (:23), the `queue` field (:38-39), `maxSendPerTick` (:54-55), `updateSendPerTick` (:224-229), `tickDrainPhase` (:214-222), `drainQueue` (:231-265), `sweepTimeouts` (:267-276), `onRateLimited`'s body (see step 5), `queueForTest()` (:510), `getQueueRemaining()` (:580), and every `this.queue.` call (`onDimensionChange` :465, `flushCache` :500).
2. Replace `tickWithContext`'s tail phases:
```java
    void tickWithContext(int playerCx, int playerCz, ResourceKey<Level> currentDim,
                         int viewDistance, int columnQueueSize, IntSupplier missingVanilla) {
        tickDimensionAndCachePhase(currentDim);
        tickMovementPhase(playerCx, playerCz);
        this.metrics.updateRollingRates();
        if (haltedByBackpressure(columnQueueSize)) {
            // Entering the halt: silence would leave the server pumping the last want-set
            // (up to 1024 backlogged asks). An EMPTY batch is the explicit "want nothing"
            // declaration — it replaces the backlog with nothing; already-admitted work
            // still completes (bounded by the server's slot caps). Edge-triggered: exactly
            // one clear per halt episode. This is the ONLY producer of empty batches.
            if (!this.backpressureClearSent) {
                this.backpressureClearSent = true;
                sendClearBatch();
            }
            return;
        }
        this.backpressureClearSent = false;
        if (!tickCacheGatePhase()) return;
        tickScanPhase(playerCx, playerCz, viewDistance, columnQueueSize, missingVanilla);
    }
```
Add the field `private boolean backpressureClearSent = false;` and:
```java
    private void sendClearBatch() {
        try {
            this.batchSender.send(new BatchChunkRequestC2SPayload(new long[0], new long[0], 0));
        } catch (Exception e) {
            LSSLogger.error("Failed to send backpressure clear batch", e);
            this.backpressureClearSent = false; // retry on the next halted tick
        }
    }
```
3. Replace `tickScanPhase`:
```java
    /**
     * Periodic scan (every 20 ticks): build the complete want-set and send it as ONE
     * batch in the same tick. A walked-but-empty scan (0) sends NOTHING — the convergence
     * invariant that keeps the soak quiescence predicate alive — but still replaces the
     * awaiting set so phantom entries cannot linger.
     * @return the scanner result (-1 when no walk happened)
     */
    int tickScanPhase(int playerCx, int playerCz, int viewDistance, int columnQueueSize,
                      IntSupplier missingVanilla) {
        int scanned = this.scanner.maybeScan(playerCx, playerCz, viewDistance,
                columnQueueSize, haltThreshold(), missingVanilla,
                this.columns, this.sendPositionBuffer, this.sendTimestampBuffer);
        if (scanned >= 0) {
            this.tracker.replaceWith(this.sendPositionBuffer, scanned);
            if (scanned > 0) {
                sendRequests(this.sendPositionBuffer, this.sendTimestampBuffer, scanned);
            }
        }
        return scanned;
    }
```
4. Simplify `sendRequests` — the failure path loses the mark restoration (nothing is consumed at send any more; the next scan re-declares identically) but keeps the awaiting-set rollback:
```java
    private void sendRequests(long[] positionBuffer, long[] timestampBuffer, int count) {
        // Snapshot to exact-length arrays: the StreamCodec encodes lazily on the netty
        // event loop, racing the next scan's buffer reuse. The payload must own its data.
        long[] positions = java.util.Arrays.copyOf(positionBuffer, count);
        long[] timestamps = java.util.Arrays.copyOf(timestampBuffer, count);
        try {
            this.batchSender.send(new BatchChunkRequestC2SPayload(positions, timestamps, count));
            long nowMs = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                this.metrics.recordRequestSent(positions[i], nowMs); // RTT: last-declare stamp
            }
        } catch (Exception e) {
            LSSLogger.error("Failed to send want-set batch", e);
            // Nothing was consumed at send (marks are answer-consumed now) and the next
            // scan re-declares the same set; just drop the awaiting entries so late-status
            // gating doesn't credit a batch that never reached the wire.
            this.tracker.replaceWith(positions, 0);
        }
        this.metrics.recordSendCycle(count); // counts attempts — a failed batch still counts
    }
```
5. Stub `onRateLimited` (deleted entirely in Task 5 — the server still sends bounces until Task 4):
```java
    /** Transitional v16 stub: a slot bounce needs no reaction — the next scan re-declares
     *  the position. Deleted with the byte-0 dispatch arm in the protocol-bump task. */
    public void onRateLimited(long packed) {
        this.metrics.discardRttStamp(packed);
        this.metrics.recordRateLimited();
    }
```
Also delete the `this.scanner.clearSkipNextScan();` call in `onDimensionChange`; delete `ColumnStateMap.markSent` itself (now caller-less in main source).
**Compile-order shim:** `getQueueRemaining()` (:580) is still called by `BenchmarkMetricsExporter:424` and `LSSClientCommands:115`, which change in Tasks 6 and 5 respectively. In this task, keep it as a stub so those compile unchanged:
```java
    /** Transitional: the drip-feed queue is gone; always 0. LSSClientCommands drops its
     *  caller in the protocol-bump task; the exporter drops request_queue with the
     *  contract change; then this stub is deleted. */
    public int getQueueRemaining() { return 0; }
```

- [ ] **Step 5: Update the client test files**

This is the largest test step; the blast radius is precisely enumerated. Work file by file:

`RequestQueueTest.java` — **delete the file** (4 tests). `RequestQueue.java` — **delete**.

`ColumnStateMapTest.java` — the `markSent` deletion touches it (contrary to an early survey that assumed `ColumnStateMap` unchanged): DELETE `markSentConsumesDirtyAndRetry` (:155 — its exact premise is what Task 1's answer-time tests replace); at the other call sites (:147, :353, :460, :506, :532, :575, :585) `markSent(POS)` simulated "the request went out" inside classify-flow tests — replace each with the terminal answer that now consumes the marks (`onReceived`/`onUpToDate`/`onNotGenerated`, matching each test's scenario) or delete the line where the test's assertion is about a different rung; every test's INVARIANT must survive, only the consumption trigger moves. Also update the `SpiralScannerTest` helper at :85 (`columns.markSent(packed)`) the same way.

`LodRequestManagerTest.java` (40 tests):
- DELETE: `untrackedRateLimitedNeverPoisonsSatisfiedColumnIntoRetry` (:154), `untrackedRateLimitedStillLatchesScanBackoff` (:166), `trackedRateLimitedMarksRetryAndReleasesSlot` (:273), `fullGenLimiterDoesNotStarveQueuedSyncRequests` (:339), `fullSyncLimiterDoesNotStarveQueuedGenerationRequests` (:366), `generationSlotsDoNotCountAgainstSyncCapacity` (:389), `drainStopsAtMaxToSendAndKeepsRemainder` (:403), `sweepTimeoutsEvictsStaleRequestsAndMarksThemForRetry` (:454).
- REWRITE `drainSkipsInFlightAndSatisfiedEntriesWithoutResending` (:419) → new test `scanReDeclaresAwaitedPositionsAndSkipsSatisfiedOnes`: seed one satisfied and one awaited-unanswered position; fire a scan via `tickScanPhase`; assert the batch contains the awaited position (re-declared) and not the satisfied one.
- REWRITE `drainConsumesRetryAndDirtyMarksOnSend` (:435) → `marksSurviveSendAndAreConsumedByAnswers`: mark dirty, fire two scans, assert the position appears in BOTH batches (mark survived the send), then deliver `onColumnReceived` and assert the third scan omits it.
- REWRITE `sendFailureRestoresRequestabilityForEveryPositionInTheFailedBatch` (:592) → `sendFailureLeavesEveryPositionReDeclarableNextScan`: throwing `BatchSender`, fire scan, then a healthy sender and a second scan — assert every position reappears.
- REWRITE `unknownAskAnsweredUpToDateSatisfiesWithoutStampAndStopsReAsking` (:623) — keep the assertion (the convergence invariant), drive via `tickScanPhase` instead of `drainQueue`.
- REWRITE `duplicatePositionInOneBatchFrameResolvesConsistently` (:642) and `dispatchRoutesEachTypeAndUnknownTypeSkipsOnlyThatEntry` (:474): the type-0 entries now exercise the *stub* (assert only metrics move); in Task 5 these become the unknown-type-inert pins.
- REWRITE the helpers: `seedQueue` (:71-79), `fireScan` (:82-90), `maybeScanOnce` (:109-112) — replace `RequestQueue` seeding with `manager.tickScanPhase(…)` against seeded `ColumnStateMap` state, using the existing `columnsForTest()`/`trackerForTest()`/`setBatchSenderForTest()` seams. Capture batches with a recording `BatchSender`.
- UPDATE the untracked-status tests (:133, :144, :179, :191, :204, :226, :244, :259, :290, :303, :315, :328, :525, :540, :552, :576, :672, :687, :706, :724, :755, :768): replace `trackerForTest().markPending(pos, now, isGen)` with `trackerForTest().replaceWith(new long[]{pos}, 1)`. Their invariants (wrong SATISFIED stamp = permanent invisible hole) are load-bearing — do not weaken any assertion.
- ADD two new pins:
```java
@Test
void convergedScanSendsNothingAtAll() {
    // HARD protocol invariant: at convergence the client is silent — a heartbeat batch
    // would keep service.requests_received moving and blind the soak quiescence predicate.
    var sent = new java.util.ArrayList<BatchChunkRequestC2SPayload>();
    manager.setBatchSenderForTest(sent::add);
    // all-satisfied map: no positions classify as needed
    fireScanAtOrigin(); // helper: cadence-advanced tickScanPhase with empty state
    assertTrue(sent.isEmpty(), "a walked-but-empty want-set must send NO batch");
}

@Test
void enteringBackpressureHaltSendsExactlyOneEmptyClearBatch() {
    var sent = new java.util.ArrayList<BatchChunkRequestC2SPayload>();
    manager.setBatchSenderForTest(sent::add);
    int halt = haltThresholdForTest();
    manager.tickWithContext(0, 0, DIM, 12, halt, () -> 0);  // enters halt
    manager.tickWithContext(0, 0, DIM, 12, halt, () -> 0);  // still halted
    assertEquals(1, sent.size(), "edge-triggered: one clear per halt episode");
    assertEquals(0, sent.get(0).count(), "the clear is the empty want-set");
    manager.tickWithContext(0, 0, DIM, 12, 0, () -> 0);     // recovered
    manager.tickWithContext(0, 0, DIM, 12, halt, () -> 0);  // re-enters halt
    assertEquals(2, sent.size(), "a new halt episode re-arms the clear");
}
```
(Expose `haltThreshold()` to the test via a package-private static accessor if needed.)

`LodRequestManagerTickTest.java` (14 tests):
- DELETE `backoffSkippedScanStillSweepsTimeoutsAndBackoffsDoNotCompound` (:160), `sendPerTickDerivesFromScannedCountWithFloorSixteen` (:183).
- REWRITE `oneTickNeverSendsMoreThanTheProtocolBatchCap` (:203) → the want-set cap pin: seed >1024 needed positions, fire one scan, assert exactly one batch with `count <= MAX_BATCH_CHUNK_REQUESTS` and no buffer overflow (the L210-211 invariant survives as the budget clamp).
- REWRITE `stalePreTeleportQueueEntriesStillSendOnceAndAreAbsorbedByTheServerGuard` (:334) → `teleportDropsStaleWantsFromTheNextBatch`: seed wants, move the player far, assert the next batch contains only new-window positions (the server replace handles the rest — note in a comment).
- REWRITE `totalPositionsRequestedCountsAtSendNotAtQueueAccept` (:411) → `totalPositionsRequestedCountsEveryDeclaredEntry`: two scans over the same unanswered position count it twice (the new A1 semantics — say so in the comment).
- REWRITE the tracker-coupled lifecycle tests (:219, :238, :265, :287, :318, :132, :362) mechanically for the new tracker/scan APIs; their orphan/reset invariants must keep their assertions.
- KEEP :101, :110 unchanged.

`SpiralScannerTest.java` (29 tests):
- DELETE `rateLimitBackoffSkipsExactlyOneScan` (:314), `multipleRateLimitNoticesBeforeOneScanConsumeExactlyOneSkip` (:525), `backoffSurvivesMovementResetButNotDimensionChangeClear` (:393).
- REWRITE `movementResetZeroesConfirmedRingRestartsCadenceAndKeepsMarks` (:539), `dimensionChangeClearsMapMarksBackoffQueueAndRecomputesScanStats` (:563), `disconnectRejoinFullResetPrimesImmediateScanAndDropsBackoff` (:598) — drop the backoff legs, keep the ring/cadence/marks legs.
- REWRITE `retryMarkInsideConfirmedDiscForcesRescanFromRingZero` (:331) — the trigger becomes an ingest-failure retry mark instead of a bounce; the rescan invariant is unchanged.
- REWRITE `anyChaosInterleavingLeavesNoPositionPermanentlyOrphaned` (:780) — **the highest-value convergence test in the repo.** Remove the `noteRateLimited` and in-flight-suppression moves from the chaos alphabet; add "batch superseded server-side" as a move (the answer simply never arrives; the position must reappear in a later batch). The terminal orphan assertion (L829) is unchanged.
- UPDATE all 29 for the new signature: replace every `RequestQueue` construction with `long[] pos = new long[1024]; long[] ts = new long[1024];`, pass them to `maybeScan`, and read results from the arrays + return count. Update the shared helpers (:40, :47, :56, :68) once — most tests then need only the call-shape change. **Budget-0 vanilla-load tests: the skip now returns -1, not 0 — update those assertions.**
- ADD:
```java
@Test
void awaitedPositionsAreReDeclaredAndBlockRingConfirmation() {
    // Re-declaration is load-bearing: an unanswered position must appear in EVERY scan's
    // want-set, and its ring must not confirm past it until data actually arrives.
    // (Server-side silent supersession + client-side suppression = a 10s-class stall.)
    ...seed one position, scan twice with no answer, assert present in both outputs
       and getConfirmedRing() == 0; answer it, scan again, assert absent and ring == 1...
}
```

- [ ] **Step 6: Run full Tier 1 to green**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest`
Expected: PASS. (Gametests are NOT run here; they still compile against the unchanged server API.)

- [ ] **Step 7: Commit**

```bash
git add -A fabric/src/main/java/dev/vox/lss/networking/client \
        fabric/src/test/java/dev/vox/lss/networking/client
git commit -m "client: declarative want-set loop — whole-set batches, re-declaration, no drip-feed/sweep/backoff"
```

---

## Task 3: Server — batch mailbox, backlog, and counters (additive)

Adds the new structures alongside the old CLQ path (production still uses the old path — this task is green trivially and keeps Task 4's cutover reviewable).

**Files:**
- Create: `common/src/main/java/dev/vox/lss/common/processing/IncomingBatch.java`
- Modify: `AbstractPlayerRequestState.java`, `ProcessingDiagnostics.java`, `AbstractChunkDiskReader.java`
- Test: create `fabric/src/test/java/dev/vox/lss/common/processing/BacklogReplaceTest.java`

- [ ] **Step 1: `IncomingBatch`**

```java
package dev.vox.lss.common.processing;

/**
 * One decoded want-set batch from a client, already range-filtered at ingress.
 * Immutable after construction (the array is never mutated), so it is safe to
 * peek from the main thread while the network thread may replace the mailbox
 * reference. An EMPTY batch is a meaningful message: "I want nothing" — it
 * replaces the backlog with nothing (the client's backpressure clear).
 */
public record IncomingBatch(IncomingRequest[] requests) {
    public int size() { return this.requests.length; }
}
```

- [ ] **Step 2: State additions in `AbstractPlayerRequestState`**

Add below the existing queue fields (the old CLQ machinery is deleted in Task 4):
```java
    // ---- Want-set mailbox + backlog (protocol v17) ----

    // Network/region thread → processing thread, latest-wins: a batch overwritten before
    // consumption is superseded wholesale — exactly the want-set replace semantics. This
    // bounds ingress at ONE batch (≤ MAX_BATCH_CHUNK_REQUESTS entries) regardless of client
    // behavior, replacing the old MAX_INCOMING_QUEUE flood bound.
    private final java.util.concurrent.atomic.AtomicReference<IncomingBatch> pendingBatch =
            new java.util.concurrent.atomic.AtomicReference<>();
    // Cross-thread superseded/range-filtered events accumulate here and are drained into
    // ProcessingDiagnostics by the processing thread each cycle, preserving that class's
    // single-writer design. Counts pending at player removal die with the state (same as
    // the old incoming queue's contents — accepted, see the soak run-boundary handling).
    private final AtomicLong pendingSuperseded = new AtomicLong();
    private final AtomicLong pendingRangeFiltered = new AtomicLong();

    // Processing-thread-owned want backlog: replaced wholesale by each taken batch,
    // consumed in order (closest-first by construction — the client emits ring order).
    private final java.util.ArrayDeque<IncomingRequest> backlog = new java.util.ArrayDeque<>();
    // Single-writer (processing thread) — volatile for exporter/diagnostic reads.
    private volatile int backlogSizeSnapshot = 0;

    /** Offer a decoded batch (any thread). Latest-wins; an overwritten batch is superseded. */
    public void offerIncomingBatch(IncomingBatch batch) {
        this.totalRequestsReceived.addAndGet(batch.size());
        var previous = this.pendingBatch.getAndSet(batch);
        if (previous != null) {
            this.pendingSuperseded.addAndGet(previous.size());
        }
    }

    /** Non-destructive read of the MAILBOX (tests + Folia hold-release only — NOT the probe;
     *  see peekWantSet). */
    public IncomingBatch peekIncomingBatch() {
        return this.pendingBatch.get();
    }

    // The want-set the processing thread most recently applied to the backlog. The main-thread
    // probe reads THIS, never the mailbox: takeIncomingBatch() nulls the mailbox within ~50ms of
    // arrival (the processing loop polls at 20Hz) while batches arrive at only 1Hz, so a probe
    // peeking the mailbox would see null on ~19 of every 20 ticks AND lose a coin-flip race on the
    // 20th — collapsing in-memory probe coverage to roughly half. Published on apply, cleared when
    // the backlog drains so a converged player is not probed forever. Single-writer (processing
    // thread), volatile for the main thread's read; IncomingBatch is immutable.
    private volatile IncomingBatch publishedWantSet;

    /** Main-thread loaded-chunk probe source: the applied want-set, or null when nothing pends. */
    public IncomingBatch peekWantSet() {
        return this.publishedWantSet;
    }

    /** Processing thread: publish on apply, publish(null) when the backlog drains to empty. */
    public void publishWantSet(IncomingBatch batch) {
        this.publishedWantSet = batch;
    }

    /** Consume the pending batch (processing thread; Folia pump during hold-release). */
    public IncomingBatch takeIncomingBatch() {
        return this.pendingBatch.getAndSet(null);
    }

    /**
     * Folia hold-release republish: put a held batch back ONLY if no newer batch arrived
     * during the hold — a newer batch supersedes the held one (never resurrect a
     * superseded want). Returns true if republished.
     */
    public boolean republishHeldBatch(IncomingBatch held) {
        if (this.pendingBatch.compareAndSet(null, held)) return true;
        this.pendingSuperseded.addAndGet(held.size());
        return false;
    }

    /** Record ingress entries dropped by the Chebyshev range guard (any thread). */
    public void recordRangeFiltered(int n) {
        if (n > 0) this.pendingRangeFiltered.addAndGet(n);
    }

    public long drainPendingSuperseded() { return this.pendingSuperseded.getAndSet(0); }
    public long drainPendingRangeFiltered() { return this.pendingRangeFiltered.getAndSet(0); }

    // ---- Backlog (processing thread only) ----

    /** Replace the backlog with a taken batch. Returns the number of dropped (superseded)
     *  entries. An empty batch is the explicit clear. Also publishes the want-set for the
     *  main-thread probe (an empty batch publishes null — nothing left to probe). */
    public int replaceBacklogWith(IncomingBatch batch) {
        int dropped = this.backlog.size();
        this.backlog.clear();
        java.util.Collections.addAll(this.backlog, batch.requests());
        this.backlogSizeSnapshot = this.backlog.size();
        publishWantSet(batch.size() == 0 ? null : batch);
        return dropped;
    }

    public IncomingRequest pollBacklog() {
        var r = this.backlog.pollFirst();
        this.backlogSizeSnapshot = this.backlog.size();
        // Backlog fully consumed: stop the main thread probing a want-set with nothing left
        // to route (a converged player must cost zero probes — today's empty CLQ does the same).
        if (this.backlog.isEmpty()) publishWantSet(null);
        return r;
    }

    /** Restore entries the router pass retained (slot full / pool full / queue full),
     *  in their original order, ahead of whatever remains. */
    public void restoreBacklog(java.util.List<IncomingRequest> retained) {
        for (int i = retained.size() - 1; i >= 0; i--) {
            this.backlog.addFirst(retained.get(i));
        }
        this.backlogSizeSnapshot = this.backlog.size();
    }

    /** Volatile snapshot for exporters and the soak quiescence gauge. */
    public int getBacklogSize() { return this.backlogSizeSnapshot; }
```

- [ ] **Step 3: `ProcessingDiagnostics` additions**

```java
    // Cumulative (single-writer: processing thread — cross-thread producers accumulate in
    // per-player AtomicLongs and are drained here each cycle)
    private volatile long totalSuperseded;
    private volatile long totalRangeFiltered;

    /** Requests dropped without a wire response (mailbox overwrite, backlog replace, Folia
     *  held-batch loss, residual disk-saturation drop, dedup-primary-departure attached
     *  drop). Recoverable by re-declaration; balanced in soak law A1. */
    public void addSuperseded(long n) { if (n > 0) totalSuperseded += n; }
    /** Ingress entries dropped by the Chebyshev range guard (counted client-side in
     *  requested_total but never entering the backlog — the A1 term for the motion race). */
    public void addRangeFiltered(long n) { if (n > 0) totalRangeFiltered += n; }
    public long getTotalSuperseded() { return totalSuperseded; }
    public long getTotalRangeFiltered() { return totalRangeFiltered; }
```
(Leave `incrementRateLimited`/`getTotalSyncRateLimited`/`getTotalGenRateLimited` in place — deleted in Task 4 with their callers.)

- [ ] **Step 4: `AbstractChunkDiskReader.hasHeadroom()`**

Store the queue in a field: in the constructor, `var workQueue = new ArrayBlockingQueue<Runnable>(queueCapacity);` then pass it to the `ThreadPoolExecutor` and assign `this.workQueue = workQueue;` (field `private final ArrayBlockingQueue<Runnable> workQueue;`). Add:
```java
    /**
     * True when a submit will not be rejected. Single-submitter contract: only the
     * processing thread submits, and pool workers only DRAIN the queue, so a true result
     * cannot turn into a rejection before the same thread's next submit (the race is
     * pessimistic-only). This is what keeps disk saturation out of the client-visible
     * protocol: the router leaves the entry in the backlog instead of submitting into a
     * full pool.
     */
    public boolean hasHeadroom() {
        return this.workQueue.remainingCapacity() > 0;
    }
```

- [ ] **Step 5: `BacklogReplaceTest` (new)**

```java
package dev.vox.lss.common.processing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Mailbox latest-wins + backlog replace + superseded accounting (protocol v17 core). */
class BacklogReplaceTest {

    private static final class TestState extends AbstractPlayerRequestState<Object> {
        TestState() { super(UUID.randomUUID(), 4, 2); }
        @Override public String getPlayerName() { return "test"; }
    }

    private static IncomingBatch batch(int... cxs) {
        var reqs = new IncomingRequest[cxs.length];
        for (int i = 0; i < cxs.length; i++) reqs[i] = new IncomingRequest(cxs[i], 0, -1L);
        return new IncomingBatch(reqs);
    }

    @Test
    void offerCountsReceivedAndOverwriteCountsSuperseded() {
        var s = new TestState();
        s.offerIncomingBatch(batch(1, 2, 3));
        s.offerIncomingBatch(batch(4));
        assertEquals(4, s.getTotalRequestsReceived(), "every entry of every batch is received");
        assertEquals(3, s.drainPendingSuperseded(), "the overwritten batch is superseded wholesale");
        assertEquals(0, s.drainPendingSuperseded(), "drain is destructive");
        assertEquals(1, s.takeIncomingBatch().size(), "latest batch wins");
        assertNull(s.takeIncomingBatch());
    }

    @Test
    void replaceBacklogDropsOldEntriesAndReportsCount() {
        var s = new TestState();
        assertEquals(0, s.replaceBacklogWith(batch(1, 2)));
        assertEquals(2, s.replaceBacklogWith(batch(7)), "old backlog superseded by the replace");
        assertEquals(7, s.pollBacklog().cx());
        assertNull(s.pollBacklog());
        assertEquals(0, s.getBacklogSize());
    }

    @Test
    void emptyBatchIsTheExplicitClear() {
        var s = new TestState();
        s.replaceBacklogWith(batch(1, 2, 3));
        assertEquals(3, s.replaceBacklogWith(batch()), "empty batch clears the backlog");
        assertEquals(0, s.getBacklogSize());
    }

    @Test
    void restoreBacklogPreservesOriginalOrderAheadOfRemainder() {
        var s = new TestState();
        s.replaceBacklogWith(batch(1, 2, 3, 4));
        var a = s.pollBacklog(); // 1
        var b = s.pollBacklog(); // 2
        s.restoreBacklog(List.of(a, b));
        assertEquals(1, s.pollBacklog().cx());
        assertEquals(2, s.pollBacklog().cx());
        assertEquals(3, s.pollBacklog().cx());
    }

    @Test
    void republishHeldBatchLosesToANewerArrival() {
        var s = new TestState();
        var held = batch(1, 2);
        s.offerIncomingBatch(held);
        assertSame(held, s.takeIncomingBatch());
        s.offerIncomingBatch(batch(9)); // newer batch arrives during the Folia hold
        assertFalse(s.republishHeldBatch(held), "a newer batch supersedes the held one");
        assertEquals(2, s.drainPendingSuperseded());
        assertEquals(9, s.takeIncomingBatch().requests()[0].cx());
        // and the CAS succeeds when the mailbox is empty
        assertTrue(s.republishHeldBatch(held));
        assertSame(held, s.peekIncomingBatch());
    }

    @Test
    void rangeFilteredAccumulatesAndDrains() {
        var s = new TestState();
        s.recordRangeFiltered(3);
        s.recordRangeFiltered(0);
        assertEquals(3, s.drainPendingRangeFiltered());
        assertEquals(0, s.drainPendingRangeFiltered());
    }
}
```

- [ ] **Step 6: Run + commit**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest && ./gradlew :paper:test`
Expected: PASS (purely additive).
```bash
git add common/src/main/java/dev/vox/lss/common/processing \
        fabric/src/test/java/dev/vox/lss/common/processing/BacklogReplaceTest.java
git commit -m "server: want-set batch mailbox + backlog + superseded/range_filtered counters (additive)"
```

---

## Task 4: Server — the cutover (router replace semantics, both platforms, Folia hold)

Switches production to the mailbox/backlog, converts every bounce into retain-or-supersede, deletes the CLQ path, and reworks Folia's hold-release to batch granularity. Gates: full Tier 1 + `:paper:test`.

**Files:**
- Modify: `IncomingRequestRouter.java`, `OffThreadProcessor.java`, `AbstractPlayerRequestState.java` (deletions), `ProcessingDiagnostics.java` (deletions), `RequestProcessingService.java`, `PlayerRequestState.java`, `PaperRequestProcessingService.java`, `PaperPlayerRequestState.java`
- Tests: `IncomingRequestRouterTest.java`, `SlotAdmissionTest.java`, `DedupFanoutTest.java`, `OffThreadProcessorDiskResultTest.java`, `PaperPlayerRequestStateTest.java`, `PaperRequestProcessingServiceTest.java`, `RegionProbeSchedulingTest.java`, **`DirtyEventMailboxTest.java`, `OffThreadProcessorLifecycleTest.java`, `OffThreadProcessorMailboxTest.java`** — the last three each declare a local `void enqueue(IncomingRequest r) { enqueueIncomingRequest(r); }` shim on their `TestState` (`DirtyEventMailboxTest:37`, `OffThreadProcessorMailboxTest:28`, `OffThreadProcessorLifecycleTest:36`) and break the moment `enqueueIncomingRequest` is deleted in Step 5. Mechanical fix: point the shim at `offerIncomingBatch` (single-entry batch), same helper pattern as Task 7. Call sites: `DirtyEventMailboxTest:124,140,151`; `OffThreadProcessorLifecycleTest:176,199,228,248,271`; `OffThreadProcessorMailboxTest:117,122,152,165`.

- [ ] **Step 1: Router — the backlog pass**

Replace `IncomingRequestRouter.processIncomingRequests` (:68-87) with:
```java
    private void processIncomingRequests(PS state, UUID playerUuid, String dimension,
                                          TickSnapshot snapshot) {
        // Fold cross-thread supersession/ingress-filter events into the single-writer
        // diagnostics (drained every cycle, whether or not a batch arrived).
        this.ctx.diagnostics().addSuperseded(state.drainPendingSuperseded());
        this.ctx.diagnostics().addRangeFiltered(state.drainPendingRangeFiltered());

        var batch = state.takeIncomingBatch();
        if (batch != null) {
            // Replace semantics: everything not yet admitted is dropped — un-admitted
            // entries have no pending slot, no dedup group, no stale-guard entry, so the
            // drop needs zero teardown. The client re-declares anything it still wants.
            this.ctx.diagnostics().addSuperseded(state.replaceBacklogWith(batch));
        }

        var loadedProbes = snapshot.loadedChunkProbes().getOrDefault(playerUuid, Long2ObjectMaps.emptyMap());
        java.util.ArrayList<IncomingRequest> retained = null;
        boolean stopPass = false;

        IncomingRequest req;
        while (!stopPass && (req = state.pollBacklog()) != null) {
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (resolvedAsDuplicate(state, playerUuid, req, packed)) {
                this.ctx.diagnostics().incrementRequestRouted();
                continue;
            }
            if (sendQueueFull(state, snapshot)) {
                // Retain (no disposition): the entry stays queued for the next cycle or is
                // superseded by the next replace. queue_full stays a pure event counter,
                // no longer a law A1 term. Stopping the pass keeps order: this entry is
                // re-prepended by restoreBacklog ahead of the un-polled remainder.
                if (retained == null) retained = new java.util.ArrayList<>();
                retained.add(req);
                break;
            }
            if (resolvedFromTimestamp(state, playerUuid, req, packed, dimension)) {
                this.ctx.diagnostics().incrementRequestRouted();
                continue;
            }
            if (resolvedFromLoadedProbe(state, playerUuid, req, packed, loadedProbes, dimension)) {
                this.ctx.diagnostics().incrementRequestRouted();
                continue;
            }

            RequestType type = req.clientTimestamp() == 0 ? RequestType.GENERATION : RequestType.SYNC;
            switch (tryAdmitAndSubmit(state, playerUuid, req, packed, dimension, type)) {
                case SUBMITTED -> this.ctx.diagnostics().incrementRequestRouted();
                case SLOT_FULL -> {
                    // Dequeue gate: the per-player cap now means "dequeue at most N
                    // concurrently", not "reject above N". Retain in order and KEEP
                    // scanning — a full SYNC slot must not starve admissible GENERATION
                    // entries behind it (and vice versa; the client-side per-type drain
                    // gates this replaces made the same guarantee).
                    if (retained == null) retained = new java.util.ArrayList<>();
                    retained.add(req);
                }
                case NO_DISK_HEADROOM -> {
                    // The shared reader pool is full — nothing disk-bound can be admitted
                    // this cycle. Retain this entry and STOP the pass: saturation never
                    // reaches the wire (issue #32's root cause, fixed at the source).
                    if (retained == null) retained = new java.util.ArrayList<>();
                    retained.add(req);
                    stopPass = true;
                }
            }
        }

        if (retained != null) state.restoreBacklog(retained);
    }
```
(Retention semantics: `SLOT_FULL` retains-and-continues; `sendQueueFull` and `NO_DISK_HEADROOM` retain-and-stop. Un-polled entries never leave the deque; `restoreBacklog` prepends the polled-but-deferred ones ahead of them, preserving the batch's closest-first order exactly.)

Replace `submitToDiskOrGeneration` + `rateLimit` with:
```java
    private enum AdmitResult { SUBMITTED, SLOT_FULL, NO_DISK_HEADROOM }

    /** Admit into the slot for the route and submit (disk-first for both SYNC and
     *  GENERATION when disk is available). A full slot or a full disk pool is NOT an
     *  answer — the entry stays in the backlog and the caller retains it. */
    private AdmitResult tryAdmitAndSubmit(PS state, UUID playerUuid, IncomingRequest req,
                                           long packed, String dimension, RequestType type) {
        if (type == RequestType.SYNC || this.diskReadingAvailable) {
            boolean claimsData = req.clientTimestamp() > 0;
            if (!state.tryAdmit(new PendingRequest(req.cx(), req.cz(), type, SlotType.SYNC_ON_LOAD, claimsData))) {
                return AdmitResult.SLOT_FULL;
            }
            long order = this.ctx.sequence().next();
            boolean attached = this.dedupTracker.tryAttachOrCreate(packed, dimension, playerUuid, order);
            // Headroom gates FRESH SUBMISSIONS ONLY, and so must be checked AFTER the dedup
            // decision: an attached request rides another player's already-submitted read and
            // costs the pool nothing, so a full pool must not defer it — that would throttle
            // exactly the cross-player convergence dedup exists to accelerate. Unwind the slot
            // (and the group we just created) and retain the entry for the next cycle.
            if (!attached && !this.processor.hasDiskHeadroom()) {
                this.dedupTracker.removeGroup(packed, dimension);
                state.removePendingByPosition(req.cx(), req.cz());
                return AdmitResult.NO_DISK_HEADROOM;
            }
            if (!attached && !this.processor.submitDiskRead(playerUuid, dimension, req.cx(), req.cz(), order)) {
                this.dedupTracker.removeGroup(packed, dimension);
                state.removePendingByPosition(req.cx(), req.cz());
                this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, packed, state));
                return AdmitResult.SUBMITTED; // dispositioned (answered not-generated)
            }
            this.ctx.diagnostics().incrementDiskQueued();
            return AdmitResult.SUBMITTED;
        } else if (this.generationAvailable) {
            if (!state.tryAdmit(new PendingRequest(req.cx(), req.cz(), type, SlotType.GENERATION, req.clientTimestamp() > 0))) {
                return AdmitResult.SLOT_FULL;
            }
            this.processor.addGenerationInFlight(playerUuid, dimension, packed);
            this.ctx.generationTicketRequests().add(
                    new OffThreadProcessor.GenerationTicketRequest(playerUuid, req.cx(), req.cz(),
                            dimension, this.ctx.sequence().next()));
            return AdmitResult.SUBMITTED;
        } else {
            this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, packed, state));
            return AdmitResult.SUBMITTED;
        }
    }
```
And in `resolvedAsDuplicate` (:105-125), replace the enqueued-column bounce (:111-115) with:
```java
            if (state.hasEnqueuedColumn(packed)) {
                // Delivery honesty, silent form: the column IS already in the send pipeline.
                // Do NOT clear the done-bit while enqueued (a ts<=0 re-ask must not force a
                // redundant re-read of data that is about to arrive) and send nothing — the
                // client re-declares at 1 Hz until the payload lands or is dropped, and a
                // post-drop re-ask falls through to the clearDiskReadDone re-resolution below.
                this.ctx.diagnostics().incrementSkippedDuplicate();
                return true;
            }
```
Also update `incrementRequestRouted`'s javadoc in `ProcessingDiagnostics` to: "One increment per backlog entry PERMANENTLY leaving the backlog with a disposition (answered, admitted, or duplicate-skipped). Retained entries are not counted; superseded entries are counted in `superseded` instead."
**Diagnostics deletion split across tasks (deliberate):** in THIS task delete `incrementRateLimited`, `incrementSyncRateLimited`, and `incrementGenRateLimited` (their last callers die with `rateLimit()`), but KEEP the two frozen fields and their getters — `BenchmarkMetricsExporter:238-239` **and `:527-528`** and `PaperSoakMetricsExporter:183-184` still read them, and the exporter + contract files change together in Task 6. The getters simply return 0-forever until then, which the contract tests (type-checking, not value-checking) accept.

> **`BenchmarkMetricsExporter` has TWO unrelated method families — patch both.** The soak-snapshot builders (`buildServerMetrics`/`buildClientSnapshot`, ~:200-450) are the ones cited throughout this plan. There is a SECOND, independent real-benchmark exporter in the same class — `exportServer()` (~:460-557) and `buildClientMetrics()` (~:559-576) — which reads the same doomed getters at **`:527-528`** (`rateLimiting` map) and **`:570`** (`total_rate_limited`). It has **zero Tier-1 coverage** (only reachable via `BenchmarkHook:48,72` under `-Dlss.benchmark=true`), so nothing catches it except a hard compile failure of `:fabric` main source — which blocks every gate in this plan. Task 6 Step 1 must patch both families.

- [ ] **Step 2: `OffThreadProcessor` — headroom hook, silent saturation, dedup-cleanup counting**

Add:
```java
    /** True when the reader pool can accept a submit (see AbstractChunkDiskReader#hasHeadroom).
     *  False when no reader is configured. */
    boolean hasDiskHeadroom() {
        return this.diskReader != null && this.diskReader.hasHeadroom();
    }
```
In `deliverDiskResult` (:658-662), replace the saturated branch:
```java
        if (result.saturated()) {
            // Residual only: the router's headroom gate prevents submits into a full pool,
            // so this fires only on races/shutdown. Silent drop — the pending slot was
            // already freed above, no dedup/stale-guard teardown is needed for a result
            // that already drained, and the client's next want-set re-declares the position.
            this.ctx.diagnostics().addSuperseded(1);
            if (LSSLogger.isDebugEnabled()) {
                LSSLogger.debug("Disk-saturated result dropped silently (superseded) for "
                        + playerUuid + ": chunk [" + cx + ", " + cz + "]");
            }
        } else if (result.notFound()) {
```
In `cleanupDedupGroups` (:472-485), count the pre-existing silent drop:
```java
            for (var attachment : rg.group().attached()) {
                var attachedState = this.players.get(attachment.playerUuid());
                if (attachedState != null) {
                    if (attachedState.removePendingByPosition(cx, cz) != null) {
                        // The read backing this pending will never deliver and no message is
                        // sent (pre-existing silent-drop path) — book it as superseded so the
                        // request-conservation ledger closes.
                        this.ctx.diagnostics().addSuperseded(1);
                    }
                }
            }
```

- [ ] **Step 3: Ingress + probe on Fabric**

`RequestProcessingService.handleBatchRequest` (:123-138) becomes:
```java
    public void handleBatchRequest(ServerPlayer player, BatchChunkRequestC2SPayload payload) {
        var state = this.players.get(player.getUUID());
        if (state == null || !state.hasCompletedHandshake()) return;

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDist = LSSServerConfig.CONFIG.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;

        var accepted = new java.util.ArrayList<IncomingRequest>(payload.count());
        for (int i = 0; i < payload.count(); i++) {
            long packedPosition = payload.packedPositions()[i];
            int cx = PositionUtil.unpackX(packedPosition);
            int cz = PositionUtil.unpackZ(packedPosition);
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > maxDist) continue;
            accepted.add(new IncomingRequest(cx, cz, payload.clientTimestamps()[i]));
        }
        state.recordRangeFiltered(payload.count() - accepted.size());
        // Offer even when empty: an empty batch is the client's explicit backpressure
        // clear and must replace the backlog with nothing.
        state.offerIncomingBatch(new IncomingBatch(accepted.toArray(new IncomingRequest[0])));
    }
```
`probeLoadedChunks` (:365-…): replace the `state.getIncomingRequests()` iteration with a read of the
PUBLISHED want-set (NOT the mailbox — see the `publishedWantSet` comment in Step 1; peeking the
mailbox here would halve probe coverage):
```java
        var batch = state.peekWantSet();
        if (batch == null) return probes;   // nothing pending — converged player, no probe cost
        for (var req : batch.requests()) {
            if (probed >= MAX_PROBES_PER_TICK_PER_PLAYER) break;
            ... // body unchanged
        }
```
Add a comment: probe alignment is best-effort (rough edge #2) — a position admitted before its first
probe pass serves from disk once; the 1 Hz re-declare re-probes, and the dirty broadcast heals any
stale read. The published want-set still lists already-routed positions until the next batch replaces
it; a probe for an already-routed position is simply unused by the router (bounded by
`MAX_PROBES_PER_TICK_PER_PLAYER`).
Delete `PlayerRequestState.addRequest` (:29-33).

- [ ] **Step 4: Ingress + probe on Paper, Folia hold-release on batches**

`PaperRequestProcessingService.handleBatchRequest` (:289-304): same rework as Fabric (uses `this.config.lodDistanceChunks`). Delete `PaperPlayerRequestState.addRequest`.
`probeLoadedChunks` (:468-491): same peek rework.
Replace the Folia hold pipeline — field `private final Map<UUID, List<IncomingRequest>> heldForProbe` (:172) becomes `private final Map<UUID, IncomingBatch> heldForProbe = new HashMap<>();` and `holdAndScheduleRegionProbe` (:518-541) becomes:
```java
    /** Pump only. One-tick hold-release at BATCH granularity: take this tick's pending
     *  batch (if any), release last tick's held batch back into the mailbox — but only if
     *  no newer batch arrived during the hold (republishHeldBatch CAS; a lost CAS means
     *  the held batch was superseded and is dropped, never resurrected) — then park the
     *  fresh batch and hand its positions to the player's owning region. The processing
     *  thread takes batches only from the mailbox, so a held batch is invisible to routing
     *  until released with its probe results already published. */
    private void holdAndScheduleRegionProbe(PaperPlayerRequestState state, ServerPlayer player,
                                            ServerLevel level, LongOpenHashSet skipPositions) {
        var fresh = state.takeIncomingBatch();

        var released = this.heldForProbe.remove(player.getUUID());
        if (released != null) {
            state.republishHeldBatch(released);
        }

        if (fresh == null) return;
        this.heldForProbe.put(player.getUUID(), fresh);

        long[] positions = snapshotProbePositions(fresh, skipPositions);
        if (positions.length == 0) return;
        UUID uuid = player.getUUID();
        this.regionTaskScheduler.schedule(player, () -> runRegionProbe(uuid, level, positions));
    }
```
`snapshotProbePositions` takes `IncomingBatch held` and iterates `held.requests()` (body otherwise unchanged). Note the release-before-park order flips relative to today (fresh is taken first either way; the released batch can no longer be "stolen" mid-hold by the processing thread — batch granularity removes that shared-consumer subtlety entirely).
**Ordering caveat to encode in a test:** if the client sent a new batch during the hold tick, the CAS fails and the HELD batch dies while the FRESH one (just taken) is parked for probing — the newer declaration always wins, and no batch is ever both held and pending.

- [ ] **Step 5: Delete the CLQ path**

In `AbstractPlayerRequestState`: delete `MAX_INCOMING_QUEUE` (:43-46), `incomingRequests` + `incomingRequestCount` (:49-50), `enqueueIncomingRequest` (:116-124), `pollIncomingRequest` (:189-193), `reinjectIncomingRequest` (:195-205), `getIncomingRequests` (:273-275), `getIncomingRequestCount` (:277-280), `totalIncomingDropped` (:67) and `getTotalIncomingDropped` (:299). **Temporary compile shim:** the two exporters still call `getTotalIncomingDropped()` — replace those two exporter lines NOW with `p.put("incoming_dropped", 0L);` and a `// removed in Task 6 with the contract` comment (the contract tests still pass: same key, and `PaperExporterContractTest:226` is updated in this task to expect `0L`). Alternatively update the contracts fully here; this plan does it in Task 6 to keep this commit reviewable — the `0L` shim keeps both contract tests green because the *key set* is unchanged. Update `PaperExporterContractTest.java:81` (`doReturn(7L)…getTotalIncomingDropped()` — delete the stub) and `:226` (`assertEquals(0L, row.get("incoming_dropped"))`).

- [ ] **Step 6: Rewrite the server-side Tier-1 tests**

`IncomingRequestRouterTest.java` (20 tests) — seeding changes from `state.addRequest(...)` loops to `state.offerIncomingBatch(...)`; a helper:
```java
    private static void offer(TestState state, IncomingRequest... reqs) {
        state.offerIncomingBatch(new IncomingBatch(reqs));
    }
```
- `mixedBatchEveryRequestGetsExactlyOneDisposition` (:177): the drain barrier can no longer be a RateLimited frame. New shape: seed a mixed batch where the last entry resolves terminally (e.g. an up-to-date ask), drain until that answer, then assert the ledger: every entry either answered, admitted (pending), or retained (`getBacklogSize()`), with `getTotalSuperseded()==0` for a no-replace run.
- `quiescentFullLedgerBalancesEveryDispositionClass` (:834): new closing equation —
```java
    long received = state.getTotalRequestsReceived();
    long answered = upToDateSent + notGeneratedSent + columnsEnqueued;
    assertEquals(received, answered + duplicateSkips + diag.getTotalSuperseded()
            + diag.getTotalRangeFiltered() + heldSync + heldGen + state.getBacklogSize(),
            "every received entry is answered, skipped, superseded, range-filtered, "
            + "admitted, or still queued — no silent leak class exists");
```
- `servedPositionReRequestsFollowTheHonestReResolutionLadder` (:429): the in-pipeline ts≤0 re-ask now asserts `getTotalDuplicateSkips()` increments and NO send action; the post-pipeline re-ask still asserts `re_resolved` + a re-serve. The permanent-invisible-hole invariant comment moves over verbatim.
- `sendQueueFullBreaksRoutingButAnswersDuplicatesAndKeepsBacklog` (:489): "keeps backlog" now means `getBacklogSize()` — assert the retained entries survive INTO the next cycle AND that a replace arriving first supersedes them (add that second leg).
- `slot-full tests` (:355 etc.): assert retention (backlog nonzero, no send action) instead of a bounce, and that freeing a slot admits the retained entry on the next pass.
- `staleSnapshotDimensionDoesNotRouteAReRegisteredSession` (:288): the guard now leaves the pending BATCH un-taken (assert `peekIncomingBatch() != null` after the stale-dimension cycle, routed after a fresh snapshot).
- `playerWithoutVoxelCapabilityIsSkippedWithBacklogIntact` (:250): assert the pending batch is un-taken (capability skip happens in `routeAll` before the take).
- `emptyBatchAdmitsNothing` (:995) → `emptyBatchClearsTheBacklog`: seed a backlog via one batch + a full-slot retain, offer an empty batch, run a cycle, assert `getBacklogSize()==0` and `getTotalSuperseded()` counted the cleared entries.
- ADD `replaceSupersedesUnadmittedButNeverInFlight`:
```java
@Test
void replaceSupersedesUnadmittedButNeverInFlight() {
    // The owner's core requirement: "we never cancel in-progress requests". Admit one
    // request (slot held, dedup group live), retain one (slot cap 1), then replace with a
    // disjoint batch: the retained entry is superseded, the admitted one survives to
    // deliver, and its dedup/stale-guard bookkeeping is untouched.
    ...
}
```
`SlotAdmissionTest.java`: `admitsUpToCapThenBounces` (:52) → `admitsUpToCapThenRejectsAdmission` (same arithmetic, name/comment change — `tryAdmit` returning false now means "leave queued"). DELETE `incomingFloodDropsAtTheBoundAndRecoversAfterDraining` (:149) and `incomingQueueReopensExactlyByDrainedCapacity` (:170) — the flood bound is now structural (one batch); their replacement coverage is `BacklogReplaceTest.offerCountsReceivedAndOverwriteCountsSuperseded`. Keep the other 7.
`DedupFanoutTest.saturatedResultFansOutRateLimitedToEveryRecipientUncounted` (:503) → `saturatedResultDropsSilentlyAndCountsSupersededPerRecipient`: assert NO send actions and `getTotalSuperseded()` == recipient count. Also extend `primaryDisconnectFreesAttachmentsSilently…` (:441) to assert the new superseded increment.
`OffThreadProcessorDiskResultTest.saturatedResultBouncesRetryableWithRateLimited` (:181) → same silent-drop rework (the "misclassifying saturated as notFound makes the client give up" comment survives — the client now retries via re-declaration, not via a bounce).
`PaperPlayerRequestStateTest.floodThroughTheRealSubclassDropsAtTheSharedBoundAndReopensOnDrain` (:38) → `secondBatchThroughTheRealSubclassSupersedesTheFirst`: offer two batches through the Paper subclass, assert received/superseded/take.
`PaperRequestProcessingServiceTest`: `batchRequestGateDropsBeyondLodPlusBufferAndKeepsClientTimestamps` (:250) — same assertions off `peekIncomingBatch()`, plus `recordRangeFiltered` drains; `reHandshakeReusesStateUpdatesCapabilitiesAndKeepsPendingWork` (:295) and `dimensionChangeTickReRegistersAFreshState…` (:335) — "pending work" now = pending batch + backlog + admitted pendings; assert the batch/backlog survive re-handshake and die with dimension change.
`RegionProbeSchedulingTest` (7 rewrites: :180, :201, :228, :252, :266, :319, :348): mechanical move to batch granularity — `addRequest` seeding becomes `offerIncomingBatch`; `freshRequestsAreHeldOneTickAndReleasedWithTheirProbeResults` asserts the released batch is back in the mailbox (`peekIncomingBatch()`); `heldBatchDiesWithTheRemovedPlayer` unchanged in spirit; ADD `heldBatchLosesToANewerArrivalAndIsCountedSuperseded` (the CAS pin — a batch offered during the hold wins; the held batch's count lands in `drainPendingSuperseded`).

- [ ] **Step 7: Run both gates to green + commit**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest && ./gradlew :paper:test`
Expected: PASS. (Tier 2 gametests are known-red until Task 7 — do not run them here.)
```bash
git add -A common fabric/src/main fabric/src/test paper/src
git commit -m "server: replace-semantics want backlog — retain instead of bounce, headroom gate, Folia batch hold (v17 core)"
```

---

## Task 5: The protocol bump — delete `RESPONSE_RATE_LIMITED`, PROTOCOL_VERSION 17

Nothing produces the bounce any more; delete it from the wire and the client, and bump the version.

**Files:**
- Modify: `LSSConstants.java`, `SendAction.java`, `LSSClientNetworking.java`, `LodRequestManager.java`, `LSSClientCommands.java`
- Tests: `ProtocolConstantsTest.java`, fabric `WireParityTest.java`, paper `WireParityTest.java`, `PayloadCodecTest.java`, `WireEdgeCaseTest.java`, `SendActionBatcherTest.java`, `LodRequestManagerTest.java`

- [ ] **Step 1: Constants**

In `LSSConstants.java`:
```java
    public static final int PROTOCOL_VERSION = 17;
```
```java
    // Batch response type tags. Byte 0 was RESPONSE_RATE_LIMITED through v16 — retired by
    // the v17 want-set model (a full slot queues instead of bouncing) and RESERVED: never
    // reuse 0, and keep 1/2 stable (wire-parity fixtures pin the bytes). The client skips
    // unknown types inert, so a reserved byte is forward-safe.
    public static final byte RESPONSE_UP_TO_DATE = 1;
    public static final byte RESPONSE_NOT_GENERATED = 2;
```

- [ ] **Step 2: Delete `SendAction.RateLimited`** (records at :24-27) and the client arm

`LSSClientNetworking.java:213`: delete the `case LSSConstants.RESPONSE_RATE_LIMITED -> …` arm (byte 0 now falls to the existing unknown-type skip).
`LodRequestManager`: delete the `onRateLimited` stub and `getTotalRateLimited()` (:567). **Keep** `RequestMetrics.recordRateLimited/getTotalRateLimited` until Task 6 (the exporter still reads it — replace the exporter's call with the manager? No: `BenchmarkMetricsExporter.java:397` calls `manager.getTotalRateLimited()`; change that line NOW to `0L` with a `// field removed in Task 6` comment so this task compiles, then Task 6 deletes the key + the metrics counter together.)
`LSSClientCommands.java:88-90`: drop the `rate_limited=%d` stat segment; also drop the queue-remaining stat at :115 (its `getQueueRemaining()` source is a 0-stub since Task 2).

- [ ] **Step 3: Test updates**

- `ProtocolConstantsTest.java:184`: `assertEquals(17, LSSConstants.PROTOCOL_VERSION, …)` — update the tripwire's comment to name the want-set break.
- paper `WireParityTest.java:264`: same 16→17.
- Both `WireParityTest` `batchResponse` reference frames (fabric :193-194, paper :148-149): replace the type-0 entry with type 1/2 bytes; regenerate the expected hex literals by running the test once and copying the actual (the fixtures are self-describing byte dumps).
- `PayloadCodecTest.java:74`, `WireEdgeCaseTest.java:165`: replace type-0 literals with `RESPONSE_UP_TO_DATE`.
- `SendActionBatcherTest.java:44`: `typeFor` helper drops `case 0`; map test indices onto types 1/2.
- `LodRequestManagerTest` dispatch tests (:474, :642): byte 0 is now the unknown-type case — assert it is skipped inert and the other entries in the same frame still dispatch (this is the reserved-byte forward-safety pin).

- [ ] **Step 4: Run + commit**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest && ./gradlew :paper:test`
Expected: PASS.
```bash
git add -A common fabric paper
git commit -m "protocol: v17 — retire RESPONSE_RATE_LIMITED (byte 0 reserved), bump version"
```

---

## Task 6: Exporters, contracts, and diagnostics surfaces

**Files:**
- Modify: `BenchmarkMetricsExporter.java`, `PaperSoakMetricsExporter.java`, `RequestMetrics.java`, `fabric/src/test/resources/exporter-contract/server-snapshot.contract`, `…/client-snapshot.contract`
- Tests: `ExporterContractTest.java`, `PaperExporterContractTest.java`, `RequestMetricsTest.java`

- [ ] **Step 1: Server snapshot changes (both exporters, in lockstep)**

In `BenchmarkMetricsExporter.buildServerMetrics` and `PaperSoakMetricsExporter` (mirror lines):
- DELETE `service.sync_rate_limited` (fabric :238, paper :183) and `service.gen_rate_limited` (:239 / :184); now delete the frozen `ProcessingDiagnostics` getters too.
- ADD after `service.re_resolved`:
```java
            service.put("superseded", diag.getTotalSuperseded());
            service.put("range_filtered", diag.getTotalRangeFiltered());
```
- players[]: DELETE `incoming_dropped` (fabric :337, paper :283 — remove the Task-4 `0L` shim), ADD `p.put("backlog", state.getBacklogSize());`.

- [ ] **Step 2: Client snapshot changes (fabric exporter only)**

- DELETE `responses.rate_limited` (:397 — and now delete `RequestMetrics.recordRateLimited`/`getTotalRateLimited` + their `RequestMetricsTest` coverage at :16-48, folding the reset-semantics assertions onto a surviving counter). **Also `RequestMetricsTest:55`** — `totalPositionsRequestedIncrementsOnlyAtRecordSendCycle` (:49-68) calls `m.recordRateLimited()` at :55, OUTSIDE the :16-48 range; drop that one line or the file won't compile.
- DELETE the second exporter family's rate-limited reads: `exportServer()`'s `rateLimiting` map at **:525-530** (drop the whole map or just the two `sync_rate_limited`/`gen_rate_limited` entries at :527-528) and `buildClientMetrics()`'s `total_rate_limited` at **:570**. These are NOT the same lines as :238-239/:397 above — see the two-families note in Task 4. No test covers them; a missed one is a `:fabric` main-source compile failure.
- DELETE `request_queue` (:424) and now delete the `getQueueRemaining()` 0-stub in `LodRequestManager` (its last caller).
- KEEP `tracker_in_flight` (:422) — now the awaiting-set size; update the comment: "declared-and-unanswered (awaiting-answer set), replaced per scan".
- KEEP `queued` (:388 — the decode/ingest queue, untouched) and `requested_total` (:401) with a comment noting the re-declaration semantics.
- Update the disabled-session zero-fill branch (`ExporterContractTest:276-299` pins identical key sets) to the same key set.

- [ ] **Step 3: Contract fixtures**

`server-snapshot.contract`: remove `service.gen_rate_limited=long` (L51) and `service.sync_rate_limited=long` (L56); insert `service.range_filtered=long` and `service.superseded=long` at their sorted positions; replace `players[].incoming_dropped` line with `players[].backlog=int` (keep the file's sorted order — run the test once and diff the actual against the expected to place lines exactly).
`client-snapshot.contract`: remove `responses.rate_limited=long` (L23) and `request_queue=int` (L19). Keep `tracker_in_flight=int` (L35) and `queued=int` (L16).
Update `PaperExporterContractTest.distinctValuesLandOnTheirContractKeys` (:179): replace the `incoming_dropped` stub/assert with a `getBacklogSize()`-backed `backlog` assert and add distinct-value stubs for `superseded`/`range_filtered`.

- [ ] **Step 4: Run + commit**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest && ./gradlew :paper:test`
Expected: PASS.
```bash
git add -A fabric paper
git commit -m "exporters: superseded/range_filtered/backlog in, rate_limited/incoming_dropped/request_queue out (contracts moved in lockstep)"
```

---

## Task 7: Tier 2 gametests + Tier 3

Every gametest that seeded via `state.addRequest(...)` now seeds one `IncomingBatch`. Add a shared helper to each touched class (or a small package-private `GameTestSeeding` utility):
```java
    private static void seedRequests(PlayerRequestState state, long[] packed, long[] timestamps) {
        var reqs = new IncomingRequest[packed.length];
        for (int i = 0; i < packed.length; i++) {
            reqs[i] = new IncomingRequest(PositionUtil.unpackX(packed[i]),
                    PositionUtil.unpackZ(packed[i]), timestamps[i]);
        }
        state.offerIncomingBatch(new IncomingBatch(reqs));
    }
```
**Multiple positions MUST be seeded as ONE batch** — two sequential offers supersede the first (this is the semantics, not a test bug; put that sentence in the helper's javadoc).

- [ ] **Step 1: Mechanical seeding updates (UPDATE class)**

`ServiceLifecycleGameTests` (:83, :150, :368, :446 — distance-guard tests additionally assert `drainPendingRangeFiltered()` for the filtered count), `RegionFaultGameTests` (:99-100), `SerializerParityGameTests` (:489, :572, :601), `TwoPlayerGameTests` (:233, :242), `CommandGameTests` (:134-135), `GenerationLifecycleGameTests` (:508, :587).

- [ ] **Step 2: Semantic rewrites (REWRITE class)**

- `ServiceLifecycleGameTests.reHandshakeReusesStateResendsConfigAndCapsZeroSkipsRouting` (:549): backlog-survives-re-handshake now asserted via `getBacklogSize()`/`peekIncomingBatch()`.
- `disabledTickFreezesPipelineAndFrozenEventsApplyOnFirstResumedCycle` (:779): assert the pending batch survives disabled ticks un-taken and routes on resume.
- `probeBudgetPushesTrailingLoadedRequestsToDiskWithoutStarvation` (:901) and `duplicateQueuedPositionsSerializeOncePerProbePass` (:960): seed the 1000+/duplicate positions as ONE batch; the probe iterates `peekIncomingBatch().requests()` — assertions otherwise unchanged (the once-per-pass dedup pin survives).
- `GenerationLifecycleGameTests.dimensionChangeReplacesStatePreservesCapabilitiesAndDropsStaleWork` (:388): L448's `!getIncomingRequests().iterator().hasNext()` becomes `getBacklogSize() == 0 && peekIncomingBatch() == null`.
- `sameTickGenerationCompletionAndQueuedReRequestServeExactlyOnePayload` (:641): re-request seeding via a fresh single-entry batch; the exactly-one-payload pin is unchanged.
- `TwoPlayerGameTests.overlappingRequestsFromTwoPlayersDedupeDiskReadsAndBothConverge` (:58) and `editedColumnPropagatesToBothHoldersThroughBroadcastFanout` (:274): batch seeding per player; the guarded re-ask retries (the PR #19/#30 hardening) stay exactly as they are — a fresh ts=1 re-ask batch per retry mirrors the real client's re-declare.
- `LSSGameTests.diagnosticsContainAllFields` (:89) + `CommandGameTests.serverMetricsExporterAgreesWithDiagFormatterCounters` (:208): reconcile with the Task-6 field set (add `superseded`, drop the rate-limited pair).

- [ ] **Step 3: Tier 3 (`LSSClientGameTests`)**

No counter it reads was deleted (it sums columns/up_to_date/not_generated). Re-derive the pacing: `getTotalSendCycles()` now advances once per scan (~20 ticks), so the L131 `> 0` check within 400 ticks holds with margin; the ingest-rejection re-serve budgets (L168-184) now ride the 1 Hz re-declare instead of retry marks + drip — same order of magnitude. Leave budgets unchanged unless a run proves otherwise; if a budget must move, bump it and note why in the assertion message.

- [ ] **Step 4: Run Tier 2 + Tier 3 to green + commit**

Run: `./gradlew :fabric:runGameTest && ./gradlew :fabric:runClientGameTest`
Expected: PASS (55 gametests + client flow; `RegionFaultGameTests` may be env-red on WSL2 per the flake catalog — trust clean CI).
```bash
git add -A fabric/src/gametest
git commit -m "gametests: batch seeding + want-set semantics across Tier 2/3"
```

---

## Task 8: The soak harness (part of the change, not a gate on it)

**Files:** `scripts/check_soak.py`, `scripts/soak_report.py`, `docs/planning/soak-test-design.md`. Scenario configs are UNCHANGED (the contention configs still create the phenomena; only the checks re-aim).

- [ ] **Step 1: Quiescence predicate**

- `PLAYER_DRAINS` (line 145): add `"backlog"` → `("held_sync", "held_gen", "send_queue", "backlog")`.
- Client mirror (lines 409, 420): **unchanged fields** — `tracker_in_flight` (awaiting set — meaning preserved) and `queued` (ingest queue — untouched). Update the doc-comment at lines 27-32 to define the new meanings.
- `SERVER_MONOTONIC` (156-172): remove `service.sync_rate_limited`/`service.gen_rate_limited`; add `service.superseded`, `service.range_filtered`.
- `KNOWN_SERVER_KEYS`/`KNOWN_CLIENT_KEYS` (188-210): mirror the Task-6 contract changes (`request_queue` and `responses.rate_limited` out; `superseded`, `range_filtered`, `players[].backlog` in; `incoming_dropped` out).

- [ ] **Step 2: Law A1 re-derived; law B1 deleted; A7 opt-in pair split**

Replace `law_A1` (441-453):
```python
def law_A1(ps, cs, pc, cc, window):
    # v17 request conservation: requested_total counts every DECLARED entry (re-declares
    # included). Each server-received entry ends exactly one way by the time both endpoints
    # are quiescent (backlog == 0): answered, duplicate-skipped, superseded (silent drop,
    # healed by re-declaration), or range-filtered at ingress (the motion race).
    d_req = delta(pc, cc, "requested_total")
    d_resp = sum(delta(pc, cc, f"responses.{k}")
                 for k in ("columns", "up_to_date", "not_generated"))
    d_dup = delta(ps, cs, "service.duplicate_skips")
    d_sup = delta(ps, cs, "service.superseded")
    d_rf = delta(ps, cs, "service.range_filtered")
    if d_req != d_resp + d_dup + d_sup + d_rf:
        ...violation with all five terms in the detail dict...
```
(`queue_full` leaves the law: a queue-full break retains entries with no disposition, and `backlog == 0` at both quiescent endpoints guarantees they resolved or superseded within the window. Remove `"rate_limited": 0` from the run-start virtual-window seed at 724-727.)
Delete `law_B1` (586-603) and its invocation (701).
A7: split the fused opt-in pair (55-79) — delete the `"rate_limited"` member from all 16 scenario entries and from `law_A7_client` (581-582), KEEP the `"saturated"` member and `law_A7_server`'s gate (571) exactly as they are (`disk.saturated` survives as a counter; the headroom gate should hold it at 0, which makes the anomaly stronger, not vacuous — and `soak_report.py`'s split-keyed `OPT_IN_NAMES` at 75-77 is the reference for why the pair must be split, not deleted).

- [ ] **Step 3: Named scenario checks**

- **rate-limit-storm** (1007-1039) — re-premise, keep the file name (renaming touches 6 keyed tables; note the historical name in the check's docstring): the `syncOnLoadConcurrencyLimitPerPlayer: 4` config now produces a deep retained backlog + heavy supersession instead of bounces. New checks: (1) `service.superseded` grew (`>= 100` over the run — with a 4-slot gate and ~800-entry want-sets, replaces drop hundreds per second); (2) keep the generation floor (1032-1035); (3) keep the quiescent tail (1036-1039) — **convergence under a 4-slot gate is the strongest assertion the scenario has ever made**; (4) delete the two `responses.rate_limited` checks (1014-1027).
- **disk-saturation** (1042-1062): flip the premise — the headroom gate means saturation must NOT occur: assert `server.disk.saturated == 0` over the run (was `>= 1`), delete the client rate-limited leg (1055-1058), keep the quiescent tail. Update the docstring: threads:1 vs a 200-slot flood now proves the backlog absorbs what used to bounce.
- **bandwidth-throttle** (1127-1152): unaffected (B2 is bandwidth-only; `queue_full` still fires). Verify the docstring's mention of the 10 s sweep (check_soak.py:1130) and re-word: late columns are absorbed by re-declaration now.
- **teleport-prune** (1259-1317): unchanged; expect it to pass with margin (replace semantics drop pre-teleport wants server-side). If the annulus bound (1299-1307) tightens in practice, leave the threshold alone — do not chase an improvement in this PR.
- **dimension-trip** `required_fields` (:893): unchanged (`tracker_in_flight` and `queued` both survive).
- **generation-capacity-stress** (`check_soak.py:1100-1124`): **verified unaffected — do not touch, and do not re-investigate.** Its config (`generationConcurrencyLimitGlobal: 1`, `generationConcurrencyLimitPerPlayer: 8`) mimics the force-a-bounce shape of the two scenarios above closely enough that reviewers keep flagging it as a fourth `RESPONSE_RATE_LIMITED` producer; it is not. Its `syncOnLoadConcurrencyLimitPerPlayer: 200` means the router's SYNC slot never fills, so the generation ceiling is enforced downstream in `OffThreadProcessor.handleDiskNotFound` (`SlotType.GENERATION` admit-or-`ColumnNotGenerated`) and `drainGenerationTicketRequests` → `feedGenerationFailure` → `ColumnNotGenerated` — never via `SendAction.RateLimited`. Its named check only asserts `generation.completed` / `responses.not_generated` / quiescence. This plan never touches `handleDiskNotFound`. (Recorded here because two independent reviewers derived this same false alarm from scratch.)

- [ ] **Step 4: Selftests (check_soak) — delete, retarget, and close the coverage gap**

- Delete: `"B1 balanced"`/`"B1 lost bounce"` (2296-2304), `"A7 rate_limited w/o opt-in"`/`"A7 rate_limited with opt-in"` (2289-2294), `"A6 sync_rate_limited decrement"`/`"A6 gen_rate_limited decrement"` (2244-2249 — the counters are gone).
- Retarget the three A6 client vehicles (2260-2270) from `responses.rate_limited` to `responses.up_to_date`.
- Rewrite the A1 pair (2183-2193) to the new five-term law (balanced + lost-request); add `"A1 superseded balances a silent drop"` (server drops N, superseded += N, law holds) and `"A1 range_filtered balances the motion race"`.
- Update `_srv`/`_cli` fixture zero-fills (2137-2165): remove `sync_rate_limited`/`gen_rate_limited`/`responses.rate_limited`/`request_queue`; add `superseded`, `range_filtered`, player `backlog`.
- Update the stress fixtures (2646-2694): `"rate-limit-storm clean"`/`"no bounces"` become the superseded-floor pair; `"disk-saturation clean"`/`"never saturated"` flip to the saturated==0 premise.
- **Close the pre-existing gap:** the client mirror of quiescence (lines 409/420) has zero selftest coverage. Add: `"quiescence client tracker_in_flight disqualifies"`, `"quiescence client queued disqualifies"`, `"quiescence player backlog disqualifies"` (server pair stable but `backlog: 1` → no window).
- Update the final selftest count print (2829-2832) to the new total.

- [ ] **Step 5: soak_report.py + design doc**

- `SERVER_MECHANISM` (55-61): remove `sync_rate_limited`/`gen_rate_limited`; add `"want-set superseded": "service.superseded"` (a mechanism — work re-planned, not lost).
- `CLIENT_MECHANISM` (66-68): remove its single `rate_limited` entry (dict may be empty — keep the dict and the plumbing).
- Keep `OPT_IN_NAMES` (75-77) untouched; preserve the `ingest_failures`-stays-concerning selftest (543-553) intent by retargeting its scenario fixture off the removed field, and update the affected `_selftest` fixtures/count (20 → new total).
- `docs/planning/soak-test-design.md`: rewrite the A1 statement (L234), delete B1 (L250-251), update A7 (L247), the field tables (L85, L101, L113-121, L209), and add a paragraph defining `superseded`/`range_filtered`/`backlog` and the convergence-silence invariant.
- `docs/planning/soak-test-design.md:142-144` — **`duplicate_skips`' documented rationale becomes actively false, not merely stale.** It reads: "the server SILENTLY drops duplicate-while-pending requests **and the client legitimately produces them: 10 s client retry vs up-to-60 s server gen pending**" — i.e. it describes the counter as measuring a rare timing-mismatch artifact of the 10 s retry. Under 1 Hz re-declaration, `duplicate_skips` becomes the DOMINANT disposition for any request outstanding longer than a second, and the 10 s retry it names no longer exists. Rewrite it to define the counter as "re-declarations of a still-pending position — expected, one per position per scan while in flight, and a load-bearing term in law A1."

- [ ] **Step 6: Validate the harness offline + commit**

Run: `python3 scripts/check_soak.py --selftest && python3 scripts/soak_report.py --selftest`
Expected: both print all-green with the new counts.
```bash
git add scripts/check_soak.py scripts/soak_report.py docs/planning/soak-test-design.md
git commit -m "soak: v17 laws — A1 gains superseded/range_filtered, B1 deleted, quiescence gains backlog, storm/saturation re-premised"
```

---

## Task 9: Docs — CLAUDE.md + README

- [ ] **Step 1: CLAUDE.md**

Rewrite the "Networking Protocol" section for v17: the want-set model (complete set, 1 Hz, replace semantics, empty batch = backpressure clear, silence at convergence), the response-type set (up-to-date / not-generated; byte 0 reserved), backpressure-relocation summary, and the re-declaration-is-load-bearing invariant. Update the component lists (`RequestQueue` gone; `InFlightTracker` re-described as the awaiting set; `AbstractPlayerRequestState` mailbox/backlog; `superseded`), the Tier-1/Tier-2 test-architecture descriptions that name deleted machinery (drip-feed, rate-limit backoff, timeout sweep), and the soak law list. Mention that `rate-limit-storm`/`disk-saturation` keep their names but test the queue-absorption premises.

Four specific CLAUDE.md lines that go stale and are easy to miss:
- **`:141`** — the Folia key-pattern bullet names ``heldForProbe` + `reinjectIncomingRequest``; Task 4 Step 4 deletes `reinjectIncomingRequest` and re-types `heldForProbe` to `Map<UUID, IncomingBatch>`. Rewrite to the batch hold-release + `republishHeldBatch` CAS (a newer batch supersedes the held one).
- **`:203`, `:235`, `:241`** — the **Benchmarking** section documents `client.json`'s `total_rate_limited` and `server.json`'s `rate_limiting.sync_rate_limited`/`gen_rate_limited`. Those are the SECOND exporter family's fields (`BenchmarkMetricsExporter:527-528,:570`), deleted in Task 6. Drop them from the interpretation tables.
- **`:49`** — the "~592 tests" Tier-1 count drifts (net: `RequestQueueTest` deleted, `InFlightTrackerTest` 8→6, several `LodRequestManagerTest`/`Tick` deletions, `SlotAdmissionTest` −2, `IncomingRequestRouterTest` −2/+1, `ColumnStateMapTest` +2, `LodRequestManagerTest` +2, `SpiralScannerTest` +1, new `BacklogReplaceTest` +6). Recount from the Task 10 run rather than guessing.

- [ ] **Step 1b: `docs/planning/read-scheduler-design.md` — supersession note**

This is NOT a dead historical artifact: it is the design doc for PR #36, this plan's own stated prerequisite. Two parts of it become wrong:
- §1 "The current pipeline, piece by piece (baseline)" (~:43-49) documents `enqueueIncomingRequest` / `MAX_INCOMING_QUEUE` / the FIFO CLQ / the rate-limited bounce as the baseline — all deleted here.
- §3 Piece D and §7 (~:98-114, :151-152) propose a future "hold-don't-bounce backlog" roadmap that this plan actually realizes, but via a different mechanism (client-declared replace, not a server-side region-bucketed scheduler).

Add a dated status note at the top of §1 and §3/§7 pointing at this plan as superseding them, so a reader isn't misled about either current or intended state. Do not rewrite the doc — a pointer is enough.

- [ ] **Step 2: README**

Update any protocol/behavior prose (request batching, rate limiting descriptions) and the config-key table if it references bounce behavior (`syncOnLoadConcurrencyLimitPerPlayer` description becomes "maximum concurrently serviced requests per player — above this, requests queue on the server"; same for the generation limit).

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md docs/planning/read-scheduler-design.md
git commit -m "docs: protocol v17 want-set model"
```

---

## Task 10: Validation gauntlet + live soak (the true gate)

- [ ] **Step 1: Full local gauntlet**

```bash
./gradlew :fabric:test -x runClientGameTest      # Tier 1 + 2
./gradlew :fabric:runClientGameTest              # Tier 3
./gradlew :paper:test :paper:shadowJar
python3 scripts/check_soak.py --selftest
python3 scripts/soak_report.py --selftest
python3 scripts/release_check.py
```
Expected: all PASS (`RegionFaultGameTests` WSL2 env-flake exempt per the catalog).

- [ ] **Step 2: Fabric soak — all 17 scenarios**

Run: `./scripts/soak.sh all`
Expected: every verdict PASS. Watch specifically: **fresh-backfill** (the gen hold-back keeps the want-set from flooding not-generated), **rate-limit-storm** (converges under the 4-slot gate with superseded churn), **disk-saturation** (`disk.saturated == 0`), **bandwidth-throttle** (late columns absorbed by re-declaration; no orphan class), **teleport-prune** (replace drops pre-teleport work), **dirty-during-backfill** (proximity priority vs frontier — rough edge #1 in the wild), **dimension-trip** (quiescence with the surviving client fields). If a `MIN_CLIENT_WINDOWS` floor fails on a converged-early run, recalibrate that scenario's floor with a comment citing this change.

- [ ] **Step 3: Paper soak**

Run: `SOAK_PLATFORM=paper ./scripts/soak.sh all`
Expected: fresh-backfill, warm-rejoin, dimension-trip, paper-dirty-falling-block PASS. **Folia cannot run** (26.2 unpublished upstream) — `RegionProbeSchedulingTest` + this Paper run carry the Bukkit validation; note the Folia gap in the PR description and keep the experimental label.

- [ ] **Step 4: Flake catalog + memory**

Any new flake or pacing quirk surfaced by the gauntlet goes into CLAUDE.md's **Known Test Flakes & Environmental Failures** (and the deleted orphan-class/bandwidth-throttle history there is updated). Commit.

- [ ] **Step 5: PR**

```bash
git push -u origin feat/want-set-requests
gh pr create --base main --title "Protocol v17: declarative want-set requests (replace semantics, no rate-limit bounces)" \
  --body "$(cat <<'EOF'
The client now declares its complete want-set once per second; the server replaces its
per-player backlog with each batch. Full concurrency slots queue instead of bouncing;
RESPONSE_RATE_LIMITED leaves the wire; disk-pool saturation is absorbed by a headroom
gate (issue #32's root cause). Deletes the client drip-feed, the 10s timeout sweep, and
the rate-limit backoff — re-declaration is the single self-healing mechanism.

- PROTOCOL_VERSION 16 → 17 (breaking: old client + new server, or vice versa, get no
  LOD session — the handshake gate's silent version rung).
- Soak harness updated in lockstep: A1 re-derived (superseded/range_filtered), B1
  deleted, quiescence gains the backlog drain; rate-limit-storm and disk-saturation
  re-premised as queue-absorption scenarios.
- Folia support remains experimental; Folia 26.2 is unpublished upstream, so Paper soak
  + RegionProbeSchedulingTest carry the Bukkit-side validation.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
Confirm CI green (gametest tiers auto-retry once; diagnose from `gametest-*-evidence-*` artifacts before re-running). Release notes (when tagged) must state the protocol break in player terms: **server admins and players must update both sides together; v17 clients and v16 servers (or vice versa) simply see no LOD terrain.**

---

## Self-Review (completed)

- **Owner requirements coverage:** complete want-set each second (Task 2); server replace of the per-player queue (Tasks 3-4); never cancel in-progress work — pinned by `replaceSupersedesUnadmittedButNeverInFlight` and the untouched dedup/stale-guard machinery; late/unwanted columns still ingested (already true — `columnDataAppliesEvenWhenNoLongerTracked` is untouched); backpressure relocated into the bounded want-set + dequeue gates + headroom check, with the empty-batch clear covering the decode-queue halt (the one place "we don't need backpressure" was false client-side).
- **The v16-history objection is answered:** the deleted `waitingQueue` (F4) was FIFO-append — it hoarded stale asks forever; this backlog is replace + proximity-ordered and drops stale asks by construction. The deleted `CancelRequestC2SPayload` could not cancel anything; replace semantics is the position-keyed early-abort the v16 review said would need a protocol bump — this is that bump.
- **Conservation:** every silent-drop path is enumerated and counted (mailbox overwrite, replace, Folia CAS, residual saturation, dedup-primary departure — the last a pre-existing uncounted drop now booked); A1's new five-term form balances them; the Tier-1 ledger test closes the same equation server-side.
- **Threading:** mailbox is an immutable-batch `AtomicReference` (network/pump/processing safe); backlog is processing-thread-only with a volatile size mirror; `ProcessingDiagnostics` stays single-writer via the drain pattern; `hasHeadroom` is single-submitter-safe (pessimistic-only race).
- **Placeholders:** none. The two spots where exact literals cannot be pre-computed (wire-parity hex fixtures, contract-file sorted positions) carry the concrete regenerate-and-diff instruction instead.
- **Open risks carried, not hidden:** the seven Rough Edges up top, each with its mitigation or its "accepted, watch in validation" note.
