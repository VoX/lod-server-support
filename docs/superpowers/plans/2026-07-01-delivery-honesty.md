# Delivery-Honesty Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the delivery-honesty finding cluster (#2, #4, #5, #11, #13, #21, #36, #43) so a client never renders permanently stale or ghost LOD terrain.

**Architecture:** Split the client's conflated column state into "held server data" (`timestamps`, persisted, only ever server-issued values) vs "resolved this session" (a new unpersisted `sessionSatisfied` set), removing the client-clock stamps that leak into the server's freshness comparison. Convey emptied/cleared terrain with an authoritative-column model over the existing wire format (no protocol bump). Make server timestamp-cache invalidations durable via debounced save-on-invalidate, and fix cross-dimension ingest reports with a targeted persistent unstamp.

**Tech Stack:** Java 25 (fabric/paper), Java 21 (common), fastutil collections, JUnit 5 via fabric-loader-junit, Fabric gametests, Mockito (paper only), Python soak checker.

## Global Constraints

- **No wire-protocol change.** `LSSConstants.PROTOCOL_VERSION` stays `16`. No new payload types, no new `RESPONSE_*` constants. A `VoxelColumnS2CPayload` with 0 sections is valid v16.
- **Client `ColumnStateMap` is main-client-thread only.** `sessionSatisfied` and `staleInFlight` are plain `LongOpenHashSet`s touched only from the main thread, like the existing `dirty`/`retry`/`validated`.
- **`timestamps`/`ColumnCacheStore` hold ONLY server-issued values** (`-1`, `0`, or a server `columnTimestamp`). No client-clock (`LSSConstants.epochSeconds()`) value may ever be written to `timestamps` or persisted.
- **Fabric and Paper wire bytes stay byte-identical.** Any change to `VoxelColumnS2CPayload` serialization must be mirrored in `PaperPayloadHandler` and covered by the wire-parity tests.
- **Server serve paths run on the processing thread; disk/generation callbacks marshal through the mailbox.** New server state (WS3 claims-data bit, WS4 dirty flag) follows the existing thread ownership — never touch the timestamp cache from the main thread.
- **Test tiers:** Tier 1 `./gradlew :fabric:test -x runGameTest -x runClientGameTest`; Paper `./gradlew :paper:test`; Tier 2 `./gradlew :fabric:runGameTest`. Every behavioral change gets a failing test first.
- **Commit after each task.** Branch is `fix/lod-lighting-and-review-findings` (already checked out; main is protected).

---

## File Structure

**Client (`fabric/src/main/java/dev/vox/lss/networking/client/`)**
- `ColumnStateMap.java` — add `sessionSatisfied`, `staleInFlight`; rewrite `classify`, `onUpToDate`, `onIngestFailed`, `markDirtyIfKnown`, `pruneOutOfRange`; add `noteStaleIfInFlight`/`resolveStale` (WS1, WS2).
- `LodRequestManager.java` — re-derive ts in `drainQueue` (WS1); resolve `staleInFlight` at `tracker.removeByPosition` sites (WS2); targeted cross-dim unstamp in `onIngestFailure` (WS5).
- `ColumnCacheStore.java` — bump to v4, discard `<4`, delete v1/v2 migration; add `removeIfStamped` for WS5.
- `ClientColumnProcessor.java` — authoritative air-fill on resync columns (WS3).
- `SpiralScanner.java` — expose the ring re-walk hook already present (`resetConfirmedRing`, WS2).

**Server (`common/` + `fabric/` + `paper/`)**
- `PendingRequest.java`, `ChunkReadResult.java`, `TickSnapshot.GenerationReadyData`, `DedupTracker.Attachment` — thread a `claimsData` boolean (WS3).
- `IncomingRequestRouter.java` — capture `claimsData`; all-air resolution (WS3).
- `OffThreadProcessor.java` — all-air serve emits a 0-section column when `claimsData` (WS3); WS4 dirty flag + debounced save.
- `ColumnTimestampCache.java` — `invalidate` returns removed count; WS4 dirty tracking (WS4).
- `AbstractChunkDiskReader.java` + both platform disk readers/generation services — pass `claimsData` through (WS3).
- `DirtyColumnBroadcaster.java` / `PaperDirtyColumnBroadcaster.java` — comment fix + ordering test (#43).

**Instrumentation:** `BenchmarkMetricsExporter.java` (+ Paper twin) export a `sessionSatisfied` count; `scripts/check_soak.py` folds it into `make_disc_completeness` (#25).

---

## Task 0: Verify Voxy clears a stored column when handed all-air

**Files:**
- Test: `fabric/src/test/java/dev/vox/lss/compat/VoxyCompatTest.java`

**Interfaces:**
- Produces: a documented answer (in the task's commit message and a code comment) to "does `VoxelIngestService.rawIngest` overwrite/clear a stored section when given an all-air `LevelChunkSection`?" This gates WS3's user-visible payoff but not its code — WS3 ships regardless per the design.

This is an investigation task, not a behavior change. The stub `me.cortex.voxy.*` classes on the test classpath record calls but do not model Voxy's real storage semantics, so the definitive answer requires reading Voxy's published `rawIngest` contract.

- [ ] **Step 1: Inspect Voxy's rawIngest contract**

Run:
```bash
cd /home/vox/projects/voxel-server-support
find ~ /root -path '*voxy*' -name '*.jar' 2>/dev/null | head
./gradlew :fabric:dependencies --configuration runtimeClasspath 2>/dev/null | grep -i voxy || echo "voxy not a gradle dep (reflection bridge)"
```
Expected: Voxy is a runtime reflection target, not a compile dep. Read the `VoxelIngestService.rawIngest` signature already resolved in `VoxyCompat.init` (`boolean rawIngest(worldId, LevelChunkSection, cx, sy, cz, DataLayer blockLight, DataLayer skyLight)`), and confirm from Voxy's source/docs whether ingesting an all-air section overwrites a previously-ingested non-air section at the same coordinates (the expected behavior — Voxy stores per-section, and rawIngest is an upsert) or is a no-op/skip for empty sections.

- [ ] **Step 2: Record the finding**

Add a comment to `VoxyCompat.java` above the `rawIngest.invoke` loop documenting the verified behavior, e.g.:
```java
// Voxy's rawIngest is an upsert keyed by (worldId, cx, sy, cz): ingesting an all-air
// section overwrites a previously-stored non-air section, so WS3's authoritative clear
// (air sections for cleared terrain) reaches Voxy. [If verification shows otherwise:
// "Voxy skips all-air sections; ghost terrain persists until Voxy adds column-clear
// support — WS3 still ships so any consumer that clears benefits."]
```

- [ ] **Step 3: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/compat/VoxyCompat.java
git commit -m "WS3 Task 0: document Voxy clear-on-air-ingest behavior"
```

---

## Task 1: Add `sessionSatisfied` set with `classify` precedence (dirty > sessionSatisfied)

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java`
- Test: `fabric/src/test/java/dev/vox/lss/networking/client/ColumnStateMapTest.java`

**Interfaces:**
- Produces: `void markSessionSatisfied(long packed)`, `boolean isSessionSatisfied(long packed)` on `ColumnStateMap`; `classify` now returns `SATISFIED` for a session-satisfied position UNLESS it is also `dirty`.

- [ ] **Step 1: Write the failing test**

Add to `ColumnStateMapTest`:
```java
@Test
void sessionSatisfiedClassifiesSatisfiedButDirtyStillWins() {
    var m = new ColumnStateMap();
    long p = PositionUtil.packPosition(3, 4);
    // A never-seen position marked session-satisfied (the all-air/park case) classifies
    // SATISFIED without any timestamp being stored.
    m.markSessionSatisfied(p);
    assertEquals(ColumnStateMap.SATISFIED, m.classify(p, true),
            "a session-satisfied position needs no request");
    assertEquals(-1L, m.timestampFor(p), "no timestamp is fabricated for a satisfied position");

    // A dirty broadcast for the same position must outrank sessionSatisfied so it re-requests.
    assertTrue(m.markDirtyIfKnown(p), "dirty must fire for a session-satisfied position");
    assertNotEquals(ColumnStateMap.SATISFIED, m.classify(p, true),
            "dirty outranks sessionSatisfied — the position re-requests");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ColumnStateMapTest'`
Expected: FAIL — `markSessionSatisfied` undefined (compile error), or after adding the field, the dirty-outranks assertion fails.

- [ ] **Step 3: Add the field and accessors**

In `ColumnStateMap`, after the `validated` set declaration (~line 36):
```java
    // Positions resolved for THIS session (all-air up-to-date, ingest-parked) that hold no
    // server data — stops within-session re-request loops WITHOUT fabricating a timestamp.
    // Never persisted; cleared on reconnect/dimension change and pruned by distance.
    private final LongOpenHashSet sessionSatisfied = new LongOpenHashSet();
```
Add methods near `markRetry`:
```java
    void markSessionSatisfied(long packed) { this.sessionSatisfied.add(packed); }
    boolean isSessionSatisfied(long packed) { return this.sessionSatisfied.contains(packed); }
```

- [ ] **Step 4: Rewrite `classify` with dirty-before-sessionSatisfied ordering**

Replace the `classify` body:
```java
    long classify(long packed, boolean generationEnabled) {
        // Dirty outranks everything: a server-pushed change must re-request even a
        // session-satisfied or validated position.
        if (this.dirty.contains(packed)) {
            long stored = this.timestamps.get(packed);
            return stored == -1L ? -1L : stored; // unknown-but-dirty re-asks as first serve
        }
        if (this.sessionSatisfied.contains(packed)) return SATISFIED;
        long stored = this.timestamps.get(packed);
        if (stored == -1L) return -1L; // Unknown — sync-on-load first
        if (stored == 0L && generationEnabled) return 0L; // Not generated — generation retry
        if (this.retry.contains(packed)) return stored; // Rate-limit retry
        if (stored > 0 && !this.validated.contains(packed)) return stored; // Cached, unvalidated
        return SATISFIED;
    }
```

- [ ] **Step 5: Make `markDirtyIfKnown` fire for session-satisfied positions**

Replace `markDirtyIfKnown`:
```java
    boolean markDirtyIfKnown(long packed) {
        // Fire if the client has ANY disposition for the column: a stored timestamp (data or
        // not-generated) OR a session-satisfied mark (an all-air/parked position with no
        // stored value). The latter is the fix for the air->content permanent hole: without
        // it a settled all-air column that gains content is never re-requested. Removing it
        // from sessionSatisfied lets classify's timestamp/dirty ladder request it again.
        if (this.timestamps.get(packed) != -1L || this.sessionSatisfied.contains(packed)) {
            this.sessionSatisfied.remove(packed);
            this.dirty.add(packed);
            return true;
        }
        return false;
    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ColumnStateMapTest'`
Expected: the new test PASSES; some existing `onUpToDate`/ingest-park tests FAIL (they assert the old fabricated-stamp behavior) — those are rewritten in Task 2. If only the Task 2-owned tests fail, proceed.

- [ ] **Step 7: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java fabric/src/test/java/dev/vox/lss/networking/client/ColumnStateMapTest.java
git commit -m "WS1 Task 1: sessionSatisfied set with dirty-first classify precedence"
```

---

## Task 2: Route all-air up-to-date and ingest-park through `sessionSatisfied` (no clock stamp)

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java`
- Test: `fabric/src/test/java/dev/vox/lss/networking/client/ColumnStateMapTest.java`

**Interfaces:**
- Consumes: `markSessionSatisfied` (Task 1).
- Produces: `onUpToDate` and `onIngestFailed` no longer write `LSSConstants.epochSeconds()` into `timestamps`.

- [ ] **Step 1: Rewrite the pinned tests to the honest contract**

In `ColumnStateMapTest`, replace the bodies of `onUpToDateStampsAbsentPositions` (~line 165), `dirtyHealsIngestParkedPositionForExactlyOneAttempt` (~line 403), `ingestParkStampPersistsToCacheAndRevalidatesNextSession`, and `ingestFailureCapParksThePosition` with assertions that (a) classify returns `SATISFIED`, (b) `timestampFor == -1` (or `0` for the not-generated case), and (c) `mapForSave()` contains NO fabricated `>0` value for these positions. Example for the up-to-date case:
```java
@Test
void onUpToDateSatisfiesAbsentPositionsWithoutFabricatingAStamp() {
    var m = new ColumnStateMap();
    long p = PositionUtil.packPosition(1, 1);
    m.markPending(p); // in-flight first serve of an all-air column (stored == -1)
    m.onUpToDate(p);
    assertEquals(ColumnStateMap.SATISFIED, m.classify(p, true), "up-to-date resolves the position");
    assertEquals(-1L, m.timestampFor(p), "no client-clock stamp is fabricated");
    assertFalse(m.mapForSave().containsKey(p), "an all-air satisfied position is not persisted");
}

@Test
void ingestParkDropsToUnknownAndReAsksNextSession() {
    var m = new ColumnStateMap();
    long p = PositionUtil.packPosition(2, 2);
    m.onReceived(p, 5000L);           // real server stamp
    for (int i = 0; i < ColumnStateMap.MAX_INGEST_FAILURES; i++) m.onIngestFailed(p);
    assertEquals(ColumnStateMap.SATISFIED, m.classify(p, true), "parked this session");
    assertEquals(-1L, m.timestampFor(p), "park drops to unknown — no fabricated or retained >0 stamp");
    assertFalse(m.mapForSave().containsKey(p), "a parked position is not persisted with a false stamp");
}

@Test
void dirtyUnparksAnIngestParkedPosition() {
    var m = new ColumnStateMap();
    long p = PositionUtil.packPosition(3, 3);
    m.onReceived(p, 5000L);
    for (int i = 0; i < ColumnStateMap.MAX_INGEST_FAILURES; i++) m.onIngestFailed(p);
    assertTrue(m.markDirtyIfKnown(p), "a parked position is re-requestable on dirty");
    assertNotEquals(ColumnStateMap.SATISFIED, m.classify(p, true));
}
```
(If `markPending` is not a test seam on `ColumnStateMap`, drive the in-flight-first-serve state the way the existing test file already does — reuse its helper.)

- [ ] **Step 2: Run to verify the rewritten tests fail against current code**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ColumnStateMapTest'`
Expected: FAIL — current `onUpToDate`/`onIngestFailed` still fabricate `epochSeconds()`.

- [ ] **Step 3: Rewrite `onUpToDate`**

```java
    void onUpToDate(long packed) {
        this.retry.remove(packed);
        long stored = this.timestamps.get(packed);
        if (stored == -1L || stored == 0L) {
            // All-air (-1, never carries a VoxelColumn) or not-generated-then-resolved (0):
            // the client holds no server data, so mark session-satisfied instead of
            // fabricating a client-clock stamp (which would persist and lie next session).
            this.sessionSatisfied.add(packed);
        } else {
            this.validated.add(packed); // real >0 data confirmed current
        }
    }
```

- [ ] **Step 4: Rewrite the `onIngestFailed` park branch**

In `onIngestFailed`, replace the park branch (the `priorFailures + 1 > MAX_INGEST_FAILURES` block) so it drops to unknown and marks session-satisfied:
```java
        if (priorFailures + 1 > MAX_INGEST_FAILURES) {
            // Park WITHOUT a fabricated/retained stamp: the consumer never stored the data,
            // so timestamps drops to -1 and the position is session-satisfied (no re-download
            // loop this session). Next session it re-asks -1; a transient failure heals, a
            // permanent one costs one attempt/session (bounded by the cap).
            long old = this.timestamps.remove(packed);
            if (old > 0) this.receivedCount--;
            else if (old == 0) this.emptyCount--;
            this.sessionSatisfied.add(packed);
            this.validated.remove(packed);
            this.dirty.remove(packed);
            this.retry.remove(packed);
            return;
        }
```
Keep the existing "un-park to retry" branch below it (the `< MAX` path) unchanged except it no longer needs to interact with `sessionSatisfied`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ColumnStateMapTest'`
Expected: PASS.

- [ ] **Step 6: Run the scanner/manager suites (End-loop regression guard)**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.SpiralScannerTest' --tests 'dev.vox.lss.networking.client.LodRequestManager*'`
Expected: The End ~50 req/s loop tests still terminate (they assert bounded requests, now satisfied via `sessionSatisfied` instead of a stamp). If a test asserts a specific fabricated stamp value, migrate it to assert `SATISFIED`/`timestampFor == -1`.

- [ ] **Step 7: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java fabric/src/test/java/dev/vox/lss/networking/client/ColumnStateMapTest.java
git commit -m "WS1 Task 2: route all-air/ingest-park through sessionSatisfied, no client-clock stamp"
```

---

## Task 3: Clear and prune `sessionSatisfied` alongside the other session sets

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java`
- Test: `fabric/src/test/java/dev/vox/lss/networking/client/ColumnStateMapTest.java`

**Interfaces:**
- Consumes: `sessionSatisfied` (Task 1).
- Produces: `clear()` empties `sessionSatisfied`; `pruneOutOfRange` prunes it by distance.

- [ ] **Step 1: Write the failing test**

```java
@Test
void sessionSatisfiedIsClearedAndDistancePruned() {
    var m = new ColumnStateMap();
    long near = PositionUtil.packPosition(0, 0);
    long far = PositionUtil.packPosition(1000, 1000);
    m.markSessionSatisfied(near);
    m.markSessionSatisfied(far);

    m.pruneOutOfRange(0, 0, 32);
    assertTrue(m.isSessionSatisfied(near), "in-range stays");
    assertFalse(m.isSessionSatisfied(far), "out-of-range pruned so the set cannot grow unbounded");

    m.clear();
    assertFalse(m.isSessionSatisfied(near), "clear (reconnect/dimension change) empties it");
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ColumnStateMapTest.sessionSatisfiedIsClearedAndDistancePruned'`
Expected: FAIL — `far` still session-satisfied after prune.

- [ ] **Step 3: Wire clear + prune**

In `clear()`, add `this.sessionSatisfied.clear();`. In `pruneOutOfRange`, add alongside the existing `pruneSet` calls:
```java
        pruneSet(this.sessionSatisfied, playerCx, playerCz, pruneDistance);
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ColumnStateMapTest.sessionSatisfiedIsClearedAndDistancePruned'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java fabric/src/test/java/dev/vox/lss/networking/client/ColumnStateMapTest.java
git commit -m "WS1 Task 3: clear + distance-prune sessionSatisfied"
```

---

## Task 4: Re-derive the send timestamp at drain time

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java:216-243`
- Test: `fabric/src/test/java/dev/vox/lss/networking/client/LodRequestManagerTickTest.java`, `LodRequestManagerTest.java`

**Interfaces:**
- Consumes: `ColumnStateMap.classify` (Task 1).
- Produces: `drainQueue` sends `classify(pos, genEnabled)` (skipping `SATISFIED`), not `queue.peekTimestamp()`.

- [ ] **Step 1: Migrate the pinned CL-071 and scan-time-timestamp tests**

In `LodRequestManagerTickTest.stalePreTeleportQueueEntriesStillSendOnceAndAreAbsorbedByTheServerGuard` (~line 347), change the timestamp assertion from `5000L` to `-1L` and update its comment: a pruned position re-derives to `-1` (unknown); the server distance guard still absorbs it, so CL-071's send-once + in-flight-churn-bound essence holds. In `LodRequestManagerTest.drainSendsTheScanTimeTimestampWhenClassificationChangedSinceScan`, rename/rewrite it to `drainReDerivesTheTimestampFromClassifyAtSendTime` asserting the re-derived value.

- [ ] **Step 2: Run to verify the migrated tests fail against current code**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.LodRequestManagerTickTest' --tests 'dev.vox.lss.networking.client.LodRequestManagerTest'`
Expected: FAIL — current `drainQueue` still sends the scan-time `ts` (`5000L`).

- [ ] **Step 3: Re-derive in `drainQueue`**

In `drainQueue`, replace the timestamp source. Current:
```java
            long pos = this.queue.peekPosition();
            long ts = this.queue.peekTimestamp();
            if (this.tracker.isInFlight(pos)) { this.queue.skip(); continue; }
            if (this.columns.classify(pos, generationEnabled) == ColumnStateMap.SATISFIED) { this.queue.skip(); continue; }
```
Change to derive `ts` from the same classify call:
```java
            long pos = this.queue.peekPosition();
            if (this.tracker.isInFlight(pos)) { this.queue.skip(); continue; }
            long ts = this.columns.classify(pos, generationEnabled);
            if (ts == ColumnStateMap.SATISFIED) { this.queue.skip(); continue; }
```
Leave the rest (`isGen = ts == 0`, slot checks, `sendTimestampBuffer[count] = ts`) unchanged — it now uses the honest re-derived `ts`. The `peekTimestamp` call and `RequestQueue.peekTimestamp` may stay (still used by tests) but is no longer read here.

- [ ] **Step 4: Run to verify tests pass**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.LodRequestManager*'`
Expected: PASS. If a concurrency/slot-type test seeded a queue entry whose classify no longer matches its scan-time ts (gen vs sync), seed the matching column state (e.g. `onNotGenerated` for a gen entry) so re-derivation preserves the slot type.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java fabric/src/test/java/dev/vox/lss/networking/client/LodRequestManagerTickTest.java fabric/src/test/java/dev/vox/lss/networking/client/LodRequestManagerTest.java
git commit -m "WS1 Task 4: re-derive send timestamp from classify at drain time (#5)"
```

---

## Task 5: Bump `ColumnCacheStore` to v4 and discard older caches

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/ColumnCacheStore.java`
- Test: `fabric/src/test/java/dev/vox/lss/networking/client/ColumnCacheStoreTest.java`

**Interfaces:**
- Produces: `FORMAT_VERSION = 4`; `load` returns an empty map for any file with `version != 4`.

- [ ] **Step 1: Rewrite the migration tests to the discard contract**

In `ColumnCacheStoreTest`, replace the v1/v2 migration tests with:
```java
@Test
void loadDiscardsPreV4Caches() {
    // A pre-fix cache may hold fabricated client-clock stamps indistinguishable from real
    // ones, so any version < 4 is discarded (rebuildable; one cold resync after upgrade).
    for (int oldVersion : new int[]{1, 2, 3}) {
        var dir = /* @TempDir per the file's existing pattern */ makeTempDir();
        writeCacheFile(dir, "srv", DIM, oldVersion, /* one entry */ 1L, 5000L);
        var loaded = ColumnCacheStore.load("srv", DIM);
        assertTrue(loaded.isEmpty(), "v" + oldVersion + " cache is discarded, not migrated");
    }
}

@Test
void v4RoundTrips() {
    var dir = makeTempDir();
    var map = new Long2LongOpenHashMap();
    map.put(1L, 5000L);
    ColumnCacheStore.save("srv", DIM, map); // writes v4
    assertEquals(5000L, ColumnCacheStore.load("srv", DIM).get(1L));
}
```
(Match the file's existing temp-dir/`writeCacheFile` helpers; `save`/`load` in this class resolve `CACHE_DIR` from `FabricLoader`, so the existing tests already have a pattern for redirecting or asserting — follow it.)

- [ ] **Step 2: Run to verify the discard test fails**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ColumnCacheStoreTest'`
Expected: FAIL — current `load` accepts v1/v2/v3.

- [ ] **Step 3: Bump the version and simplify `load`**

In `ColumnCacheStore`, set `FORMAT_VERSION = 4`. In `load`, replace the version gate + migration branch:
```java
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                LSSLogger.info("Column cache " + file + " is v" + version + " (< v" + FORMAT_VERSION
                        + "); discarding — timestamps are rebuilt on resync");
                return map;
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_CACHE_ENTRIES) {
                LSSLogger.warn("Column cache " + file + " has invalid entry count " + count + ", discarding");
                return map;
            }
            map.ensureCapacity(count);
            for (int i = 0; i < count; i++) {
                long pos = in.readLong();
                map.put(pos, in.readLong()); // raw server timestamp
            }
            LSSLogger.info("Loaded " + count + " cached column entries for " + dimensionKey(dimension));
```
Delete the `version == 2` migration branch. `save` already writes `FORMAT_VERSION` — no change.

- [ ] **Step 4: Run to verify tests pass**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ColumnCacheStoreTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/client/ColumnCacheStore.java fabric/src/test/java/dev/vox/lss/networking/client/ColumnCacheStoreTest.java
git commit -m "WS1 Task 5: ColumnCacheStore v4 discards pre-fix caches"
```

---

## Task 6: WS1 end-to-end regression — full Tier 1 + Tier 2 green

**Files:** (no product change; verification + any fallout test migration)

- [ ] **Step 1: Run full Fabric Tier 1**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest`
Expected: PASS. Migrate any remaining test that asserts a fabricated stamp value to the `SATISFIED`/`-1` contract.

- [ ] **Step 2: Run Fabric Tier 2 gametests**

Run: `./gradlew :fabric:runGameTest`
Expected: PASS.

- [ ] **Step 3: Commit any test migrations**

```bash
git add -A
git commit -m "WS1 Task 6: migrate residual pinned tests to the honest-timestamp contract"
```

---

## Task 7: `staleInFlight` — record a dirty that crosses an in-flight first serve

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java`
- Test: `fabric/src/test/java/dev/vox/lss/networking/client/ColumnStateMapTest.java`

**Interfaces:**
- Produces: `void noteStaleIfInFlight(long packed, boolean inFlight)`, `boolean resolveStale(long packed)` (returns true and clears if the position was marked stale). `markDirtyIfKnown` is unchanged; the manager calls `noteStaleIfInFlight` when a dirty arrives for an in-flight `-1` position (Task 8).

- [ ] **Step 1: Write the failing test**

```java
@Test
void staleInFlightRecordsAndResolvesOnce() {
    var m = new ColumnStateMap();
    long p = PositionUtil.packPosition(5, 5);
    m.noteStaleIfInFlight(p, true);     // dirty crossed the in-flight first serve
    assertTrue(m.resolveStale(p), "a crossed-dirty position resolves stale exactly once");
    assertFalse(m.resolveStale(p), "the mark is consumed");

    m.noteStaleIfInFlight(p, false);    // not in flight -> no mark (dirty handled normally)
    assertFalse(m.resolveStale(p));
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ColumnStateMapTest.staleInFlightRecordsAndResolvesOnce'`
Expected: FAIL — methods undefined.

- [ ] **Step 3: Add the set and methods**

```java
    // Positions dirtied WHILE their first serve was in flight (stored == -1, so
    // markDirtyIfKnown could not record them). Resolved when the in-flight request reaches
    // any terminal outcome, forcing a re-request because the arriving column predates the edit.
    private final LongOpenHashSet staleInFlight = new LongOpenHashSet();

    void noteStaleIfInFlight(long packed, boolean inFlight) {
        if (inFlight) this.staleInFlight.add(packed);
    }

    /** True (and clears) if this position was dirtied mid-first-serve. */
    boolean resolveStale(long packed) {
        return this.staleInFlight.remove(packed);
    }
```
Add `this.staleInFlight.clear();` to `clear()` and `pruneSet(this.staleInFlight, ...)` to `pruneOutOfRange` (so a never-consumed mark cannot pin the ring or grow unbounded).

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ColumnStateMapTest.staleInFlightRecordsAndResolvesOnce'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java fabric/src/test/java/dev/vox/lss/networking/client/ColumnStateMapTest.java
git commit -m "WS2 Task 7: staleInFlight set for dirty crossing an in-flight first serve"
```

---

## Task 8: Wire `staleInFlight` through the manager's terminal outcomes

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java` (`onDirtyColumns`, `onColumnReceived`, `onColumnUpToDate`, `onColumnNotGenerated`)
- Test: `fabric/src/test/java/dev/vox/lss/networking/client/LodRequestManagerTest.java`

**Interfaces:**
- Consumes: `noteStaleIfInFlight`, `resolveStale` (Task 7); `InFlightTracker.isInFlight`; `SpiralScanner.resetConfirmedRing`.
- Produces: a dirty crossing an in-flight first serve re-requests (does not settle) at whichever terminal outcome arrives.

- [ ] **Step 1: Write the failing test**

```java
@Test
void dirtyCrossingAnInFlightFirstServeReRequests() {
    // Position requested for the first time (in flight, stored == -1). A dirty broadcast
    // arrives while it is in flight. When the (pre-edit) answer lands, it must NOT settle —
    // the position must re-request.
    var mgr = /* the test's manager-with-seams builder */ newManager();
    long p = PositionUtil.packPosition(6, 6);
    mgr.trackerForTest().markPending(p, System.nanoTime(), false); // in flight
    mgr.onDirtyColumns(new long[]{p});                             // dirty crosses it
    mgr.onColumnUpToDate(p);                                       // pre-edit answer (all-air)

    assertNotEquals(ColumnStateMap.SATISFIED,
            mgr.columnsForTest().classify(p, true),
            "a dirty that crossed the in-flight serve forces a re-request");
}
```
Add a matching case driving `onColumnReceived(p, 5000L, dim)` as the terminal outcome.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.LodRequestManagerTest.dirtyCrossingAnInFlightFirstServeReRequests'`
Expected: FAIL — the position settles `SATISFIED`.

- [ ] **Step 3: Record the crossing in `onDirtyColumns`**

```java
    public void onDirtyColumns(long[] dirtyPositions) {
        boolean added = false;
        for (long packed : dirtyPositions) {
            if (this.columns.markDirtyIfKnown(packed)) {
                added = true;
            } else {
                // Unknown position: if it is a first serve currently in flight, record the
                // crossing so its (pre-edit) answer forces a re-request instead of settling.
                this.columns.noteStaleIfInFlight(packed, this.tracker.isInFlight(packed));
            }
        }
        if (added) {
            this.scanner.resetScanCounter();
        }
    }
```

- [ ] **Step 4: Resolve the crossing at each terminal outcome**

Add a private helper and call it from `onColumnReceived`, `onColumnUpToDate`, and `onColumnNotGenerated` (each already calls `tracker.removeByPosition`), immediately after the position resolves:
```java
    /** If a dirty crossed this in-flight first serve, un-settle and force a ring re-walk. */
    private boolean consumeStaleCrossing(long packed) {
        if (this.columns.resolveStale(packed)) {
            this.columns.markDirtyIfKnown(packed); // re-mark for re-request (now that it has a disposition)
            this.scanner.resetConfirmedRing();     // reach it below the confirmed ring
            return true;
        }
        return false;
    }
```
In `onColumnReceived` (after `this.columns.onReceived(...)`), `onColumnUpToDate` (after `this.columns.onUpToDate(...)`), and `onColumnNotGenerated` (after `this.columns.onNotGenerated(...)`), add:
```java
        consumeStaleCrossing(packed);
```
For `onColumnReceived`/`onColumnUpToDate` the position now has a disposition (a stamp or `sessionSatisfied`), so `markDirtyIfKnown` fires; for the all-air `onColumnUpToDate` case it un-parks from `sessionSatisfied` (Task 1's fix), which is exactly the intent.

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.LodRequestManagerTest' --tests 'dev.vox.lss.networking.client.LodRequestManagerTickTest'`
Expected: PASS. Adjust any dirty-count assertion that now tolerates one extra request+response per crossing.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java fabric/src/test/java/dev/vox/lss/networking/client/LodRequestManagerTest.java
git commit -m "WS2 Task 8: re-request a dirty that crossed an in-flight first serve (#11)"
```

---

## Task 9: #43 done-bit ordering — comment fix + ordering-regression test

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/server/DirtyColumnBroadcaster.java:132-133`; `paper/src/main/java/dev/vox/lss/paper/PaperDirtyColumnBroadcaster.java` (analogous comment)
- Test: `fabric/src/test/java/dev/vox/lss/common/processing/OffThreadProcessorMailboxTest.java` (or `DirtyEventMailboxTest`)

**Interfaces:** none (documentation + test only).

- [ ] **Step 1: Write the ordering-regression test**

Pin that `applyEvents` (which applies `clearDiskReadDone`) runs before `routeIncomingRequests` within a processing cycle, so a re-request routed in the same-or-later cycle sees the cleared done-bit. Drive a processor with a done-bit set, post a dirty-clear mailbox event AND a ts>0 re-request for the same position in the next snapshot, and assert the re-request re-resolves (not answered `up_to_date` from the stale done-bit):
```java
@Test
void dirtyClearIsAppliedBeforeReRequestsAreRoutedInACycle() {
    // done-bit set for P; a dirty-clear for P is posted; then a ts>0 re-request for P.
    // clearDiskReadDone (applyEvents) must precede routing so P re-resolves, not up_to_date.
    // ... build TestProcessor (see OffThreadProcessorMailboxTest), markDiskReadDone(P),
    // clearDiskReadDone(uuid, {P}), enqueue IncomingRequest(P, ts>0), postSnapshot, then
    // assert the send action for P is NOT ColumnUpToDate-from-done-bit.
}
```

- [ ] **Step 2: Run to verify it passes against current ordering (regression guard, not a bug)**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.common.processing.OffThreadProcessorMailboxTest'`
Expected: PASS — the ordering is already correct; this test pins it so a future reorder cannot regress it.

- [ ] **Step 3: Fix the misleading comments**

In `DirtyColumnBroadcaster` replace the "Done-bits clear before the send" comment (~line 132) with:
```java
                    // clearDiskReadDone only ENQUEUES a mailbox event; correctness comes from
                    // applyEvents (which applies it) running before routeIncomingRequests in
                    // every processing cycle, so a re-request routed after this notification
                    // sees the cleared done-bit and re-resolves — never a stale up_to_date.
```
Apply the analogous comment fix in `PaperDirtyColumnBroadcaster`.

- [ ] **Step 4: Run to verify still green**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.common.processing.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/server/DirtyColumnBroadcaster.java paper/src/main/java/dev/vox/lss/paper/PaperDirtyColumnBroadcaster.java fabric/src/test/java/dev/vox/lss/common/processing/OffThreadProcessorMailboxTest.java
git commit -m "WS2 Task 9: pin dirty-clear-before-route ordering; fix misleading comment (#43)"
```

---

## Task 10: `ColumnTimestampCache.invalidate` returns a removed-count

**Files:**
- Modify: `common/src/main/java/dev/vox/lss/common/voxel/ColumnTimestampCache.java:71-79`
- Test: `fabric/src/test/java/dev/vox/lss/voxel/ColumnTimestampCacheTest.java`

**Interfaces:**
- Produces: `int invalidate(String dimension, long[] positions)` returning the number actually removed.

- [ ] **Step 1: Write the failing test**

```java
@Test
void invalidateReturnsRemovedCount() {
    var c = new ColumnTimestampCache(DEFAULT_MAX);
    c.put(DIM, 1L, 100L, 0L);
    c.put(DIM, 2L, 100L, 0L);
    assertEquals(2, c.invalidate(DIM, new long[]{1L, 2L, 3L}),
            "returns the count actually removed (3L was never cached)");
    assertEquals(0, c.invalidate(DIM, new long[]{1L}), "already-removed positions count zero");
}
```
(Use the file's existing `DIM`/`DEFAULT_MAX` constants.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.voxel.ColumnTimestampCacheTest.invalidateReturnsRemovedCount'`
Expected: FAIL — `invalidate` returns void.

- [ ] **Step 3: Change the signature**

```java
    public int invalidate(String dimension, long[] positions) {
        var cache = caches.get(dimension);
        if (cache == null) return 0;
        int removed = 0;
        for (long pos : positions) {
            if (cache.timestamps.remove(pos) != cache.timestamps.defaultReturnValue()) {
                cache.insertionTimes.remove(pos);
                removed++;
            }
        }
        if (removed > 0) liveSizes.put(dimension, cache.timestamps.size());
        return removed;
    }
```
(Confirm `Long2LongOpenHashMap.remove` returns the prior value and `defaultReturnValue()` is the "absent" sentinel; if the cache uses `0L`-default, compare against `0L` and note a stored `0L` timestamp is never used on the server — timestamps are epoch seconds > 0.)

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.voxel.ColumnTimestampCacheTest'`
Expected: PASS. Fix the one caller (`OffThreadProcessor.applyEvents`) to accept the return in Task 11.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/dev/vox/lss/common/voxel/ColumnTimestampCache.java fabric/src/test/java/dev/vox/lss/voxel/ColumnTimestampCacheTest.java
git commit -m "WS4 Task 10: invalidate returns removed-count"
```

---

## Task 11: Debounced save-on-invalidate (processing thread)

**Files:**
- Modify: `common/src/main/java/dev/vox/lss/common/processing/OffThreadProcessor.java` (`applyEvents`, `processCycle`, new fields)
- Test: `fabric/src/test/java/dev/vox/lss/common/processing/OffThreadProcessorDiskResultTest.java` or a new `OffThreadProcessorSaveTest`

**Interfaces:**
- Consumes: `ColumnTimestampCache.invalidate` returning a count (Task 10); the existing per-instance `saveExecutor`, `snapshotForSave`, `SAVE_INTERVAL_CYCLES`.
- Produces: after an invalidation removes ≥1 entry, a save is scheduled within `INVALIDATE_SAVE_MAX_CYCLES` cycles (coalesced, min-interval floored), all on the processing thread.

- [ ] **Step 1: Write the failing test**

Drive a processor (see `OffThreadProcessorMailboxTest.TestProcessor`, which passes a non-null `dataDir`), post an invalidation for a cached position, cycle it, and assert a save file appears within the cap window (well before `SAVE_INTERVAL_CYCLES`). Use a `@TempDir` dataDir and poll for the `lss-timestamps.bin` file:
```java
@Test
void invalidationSchedulesADebouncedSaveWellBeforeThePeriodicInterval(@TempDir Path dir) throws Exception {
    var players = new ConcurrentHashMap<UUID, TestState>();
    var proc = new TestProcessorWithDataDir(players, dir); // subclass passing dataDir to super
    try {
        proc.start();
        // Seed a cached timestamp, then invalidate it.
        proc.getTimestampCacheForTest().put(DIM, PositionUtil.packPosition(1, 1), 100L, 0L);
        proc.invalidateTimestamps(DIM, new long[]{PositionUtil.packPosition(1, 1)});
        // Cycle the loop a handful of times (< SAVE_INTERVAL_CYCLES).
        for (int i = 0; i < 10; i++) { proc.postSnapshot(snapshot(), List.of()); Thread.sleep(20); }
        assertTrue(Files.exists(dir.resolve("lss-timestamps.bin")),
                "a debounced save fires within the cap, not only every 5 min");
    } finally { proc.shutdown(); }
}
```
(Add a `getTimestampCacheForTest()` seam if absent, mirroring existing harness accessors.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests '*OffThreadProcessorSaveTest*'`
Expected: FAIL — no save until `SAVE_INTERVAL_CYCLES`.

- [ ] **Step 3: Add the dirty-flag + debounce fields and apply on invalidation**

Add fields near `saveCounter`:
```java
    // WS4: durable invalidation. A dirty-broadcast invalidation removes cache entries in
    // memory; without a prompt save a crash within the ~5-min periodic window resurrects them
    // (false up_to_date). Debounce: on the FIRST removal since the last save, arm a countdown;
    // save when it elapses (coalesce, do not reset) so continuous churn cannot starve durability.
    private static final int INVALIDATE_SAVE_MAX_CYCLES = 40; // ~2s at 20 cycles/s
    private boolean invalidationDirty = false;
    private int invalidationCountdown = 0;
```
In `applyEvents`, capture the removed count and arm the countdown:
```java
        for (var inv : take.invalidations()) {
            int removed = this.timestampCache.invalidate(inv.dimension(), inv.positions());
            if (removed > 0 && !this.invalidationDirty) {
                this.invalidationDirty = true;
                this.invalidationCountdown = INVALIDATE_SAVE_MAX_CYCLES;
            }
        }
```

- [ ] **Step 4: Fire the debounced save in `processCycle` (same thread as the periodic save)**

In `processCycle`, alongside the existing periodic-save block:
```java
        if (this.dataDir != null && this.invalidationDirty && --this.invalidationCountdown <= 0) {
            this.invalidationDirty = false;
            this.saveCounter = 0; // a fresh save resets the periodic timer too
            var snap = this.timestampCache.snapshotForSave();
            this.saveExecutor.execute(() -> snap.save(this.dataDir));
        }
```
`snapshotForSave` and the countdown both run on the processing thread; only the `save` runs on `saveExecutor`. The min-interval floor is implicit: `INVALIDATE_SAVE_MAX_CYCLES` (~2s) bounds how often the deep-copy runs even under continuous edits, because the countdown only re-arms after a save clears `invalidationDirty`.

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests '*OffThreadProcessorSaveTest*' --tests 'dev.vox.lss.common.processing.*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/dev/vox/lss/common/processing/OffThreadProcessor.java fabric/src/test/java/dev/vox/lss/common/processing/OffThreadProcessorSaveTest.java
git commit -m "WS4 Task 11: debounced save-on-invalidate (#2)"
```

---

## Task 12: Document WS4 durability scope + Paper twin check

**Files:**
- Modify: `common/src/main/java/dev/vox/lss/common/processing/OffThreadProcessor.java` (comment); no Paper code change (Paper extends the same base — the debounce is inherited).
- Test: `paper/src/test/java/dev/vox/lss/paper/PaperRequestProcessingServiceTest.java` (confirm the inherited path)

- [ ] **Step 1: Add the durability-scope comment**

Above the debounced-save block, note: atomic-move gives atomicity, not power-loss durability (no fsync); the ~5-min→~2s window applies to graceful/process crashes; power-loss durability is deferred to the documented tombstone fallback.

- [ ] **Step 2: Confirm Paper inherits the debounce (no override)**

Run: `./gradlew :paper:test`
Expected: PASS — `PaperOffThreadProcessor` extends `OffThreadProcessor` and does not override `applyEvents`/`processCycle`, so the debounce is inherited. If a Paper test seams the save, extend it to cover the debounced path.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/dev/vox/lss/common/processing/OffThreadProcessor.java
git commit -m "WS4 Task 12: document durability scope; confirm Paper inherits debounce"
```

---

## Task 13: Thread a `claimsData` bit through the request/serve records

**Files:**
- Modify: `common/src/main/java/dev/vox/lss/common/processing/PendingRequest.java`, `ChunkReadResult.java`, `DedupTracker.java` (`Attachment`), `TickSnapshot.java` (`GenerationReadyData`), `AbstractChunkDiskReader.java`, `IncomingRequestRouter.java`, `OffThreadProcessor.java`, both platform disk readers + generation services.
- Test: `fabric/src/test/java/dev/vox/lss/common/processing/IncomingRequestRouterTest.java`

**Interfaces:**
- Produces: every serve path knows whether the requesting client `claimsData` (`clientTimestamp > 0`). `PendingRequest` gains `boolean claimsData`; `ChunkReadResult` gains `boolean claimsData`; `DedupTracker.Attachment` gains `boolean claimsData`; `GenerationReadyData` gains `boolean claimsData`.

This task is mechanical plumbing (add a field + pass it through) with no behavior change yet — the value is consumed in Task 14. Keep it a pure passthrough so the diff is reviewable in isolation.

- [ ] **Step 1: Write the failing test**

In `IncomingRequestRouterTest`, assert that a `ts>0` request produces a `PendingRequest` (or disk submission) carrying `claimsData == true` and a `ts<=0` request carries `false`. Use the test's existing router harness (it captures submissions):
```java
@Test
void claimsDataReflectsClientTimestampSign() {
    // ts>0 -> claimsData true; ts<=0 -> false. Assert via the captured disk submission /
    // pending entry that the router threads the bit. (Follow the file's existing capture seam.)
}
```

- [ ] **Step 2: Run to verify it fails (compile error is fine)**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.common.processing.IncomingRequestRouterTest.claimsDataReflectsClientTimestampSign'`
Expected: FAIL — no `claimsData` accessor.

- [ ] **Step 3: Add the field to each record (default false where synthesized)**

- `PendingRequest`: `public record PendingRequest(int cx, int cz, RequestType type, SlotType heldSlot, boolean claimsData) {}` — update all constructors (grep `new PendingRequest(`); a generation-escalation `PendingRequest` in `OffThreadProcessor.handleDiskNotFound` inherits the original's `claimsData`.
- `ChunkReadResult`: add `boolean claimsData` as a component; update `empty`/`saturated` factories and `readAndDeliver` to carry it (thread from `submitRead`).
- `DedupTracker.Attachment`: `record Attachment(UUID playerUuid, long submissionOrder, boolean claimsData) {}`; update `tryAttachOrCreate` to accept and store it per attachment.
- `GenerationReadyData`: add `boolean claimsData`; update its constructors and `feedGenerationFailure`.
- `AbstractChunkDiskReader.submitRead` / `submitReadDirect` (both platforms): add a `boolean claimsData` param passed into the `ChunkReadResult`.

- [ ] **Step 4: Pass it through the router and processor**

- `IncomingRequestRouter.submitToDiskOrGeneration`: compute `boolean claimsData = req.clientTimestamp() > 0;` and pass into `new PendingRequest(...)`, `submitDiskRead(...)`, and `dedupTracker.tryAttachOrCreate(...)`.
- `OffThreadProcessor.submitDiskRead` abstract signature + both overrides: add `boolean claimsData` and forward to the disk reader.
- Generation path: carry `claimsData` from the `PendingRequest` into the `GenerationTicketRequest`/`GenerationReadyData`.

- [ ] **Step 5: Run to verify it passes (Fabric + Paper compile + tests)**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest :paper:test`
Expected: PASS — pure passthrough; existing behavior unchanged. Fix any missed `new PendingRequest(...)`/`new ChunkReadResult(...)` call site the compiler flags.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "WS3 Task 13: thread claimsData bit through request/serve records"
```

---

## Task 14: Serve a 0-section column for a fully-all-air resync (`claimsData`)

**Files:**
- Modify: `common/src/main/java/dev/vox/lss/common/processing/OffThreadProcessor.java` (`deliverDiskResult`, `processGenerationReady`, `resolvedFromLoadedProbe` in the router), `IncomingRequestRouter.java`.
- Test: `fabric/src/test/java/dev/vox/lss/common/processing/OffThreadProcessorDiskResultTest.java`, `IncomingRequestRouterTest.java`

**Interfaces:**
- Consumes: `claimsData` (Task 13).
- Produces: an all-air resolution (empty section bytes) sends a **0-section `VoxelColumn`** (via `buildAndEnqueueColumnPayload` with a 1-byte `[0x00]` body) when `claimsData`, else `ColumnUpToDate` (today's behavior).

- [ ] **Step 1: Write the failing test**

```java
@Test
void allAirResolutionSendsZeroSectionColumnWhenClientClaimsData() {
    // A disk read resolves all-air (sectionBytes == null, notFound == false) for a request
    // with claimsData == true -> the client is served a 0-section VoxelColumn (authoritative
    // clear), NOT ColumnUpToDate. With claimsData == false -> ColumnUpToDate (void dims cheap).
}
```
Drive both cases through `deliverDiskResult` (via the disk-result harness in `OffThreadProcessorDiskResultTest`) and assert the emitted `SendAction` type / that `buildAndEnqueueColumnPayload` was called with a 0-section body.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests '*OffThreadProcessorDiskResultTest*'`
Expected: FAIL — all-air currently always sends `ColumnUpToDate`.

- [ ] **Step 3: Add a 0-section-column serve path**

In `OffThreadProcessor`, add a helper that builds the canonical empty-column body (`[0x00]` = one varint `0` for section count) and enqueues it as a normal column carrying the server `columnTimestamp`:
```java
    private static final byte[] ZERO_SECTION_COLUMN = new byte[]{0x00}; // varint 0: zero sections

    /** Authoritative all-air serve: a 0-section column tells the client to clear the column. */
    private boolean sendEmptiedColumn(PlayerState state, int cx, int cz, String dimension,
                                       long columnTimestamp, long submissionOrder) {
        int est = ZERO_SECTION_COLUMN.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
        return buildAndEnqueueColumnPayload(state, cx, cz, dimension, columnTimestamp,
                submissionOrder, ZERO_SECTION_COLUMN, est);
    }
```
In `deliverDiskResult`, the all-air branch (`result.sectionBytes() == null && !result.notFound()` → currently `ColumnUpToDate`) becomes:
```java
        } else if (isAllAir) {
            state.markDiskReadDone(cx, cz);
            if (result.claimsData()
                    && sendEmptiedColumn(state, cx, cz, result.dimension(),
                            result.columnTimestamp(), submissionOrder)) {
                // clearing column sent
            } else {
                this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed, state));
            }
        }
```
Apply the same choice in `processGenerationReady`'s all-air branch and in `IncomingRequestRouter.resolvedFromLoadedProbe` (a probe of a loaded all-air chunk: if `req.clientTimestamp() > 0`, send the 0-section column instead of `ColumnUpToDate`). For dedup fan-out, each attachment's `claimsData` decides its own recipient's answer.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests '*OffThreadProcessorDiskResultTest*' --tests 'dev.vox.lss.common.processing.IncomingRequestRouterTest'`
Expected: PASS.

- [ ] **Step 5: Migrate the pinned Tier-2 all-air test**

`SerializerParityGameTests.becomesAllAirReServeResolvesUpToDateWithNoColumnPayload` now expects a 0-section column for a `ts>0` re-serve. Rename to `becomesAllAirReServeSendsClearingColumnWhenClientClaimsData` and assert the emitted response is a 0-section `VoxelColumn` for `ts>0`, and still `RESPONSE_UP_TO_DATE` for a `ts<=0` re-serve. (This runs in Task 18's Tier-2 pass; update the assertion here.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "WS3 Task 14: serve a 0-section clearing column for all-air resync (#21)"
```

---

## Task 15: Paper parity for the 0-section serve + wire parity

**Files:**
- Modify: `paper/src/main/java/dev/vox/lss/paper/PaperOffThreadProcessor.java` (inherits `sendEmptiedColumn`; ensure `buildAndEnqueueColumnPayload` handles a 1-byte body), `PaperPayloadHandler.java`.
- Test: `paper/src/test/java/dev/vox/lss/paper/WireParityTest.java`, `PaperPayloadEdgeTest.java`

**Interfaces:**
- Consumes: the 0-section serve path (Task 14, inherited from the common base).
- Produces: a Paper-encoded 0-section `VoxelColumn` is byte-identical to Fabric's.

- [ ] **Step 1: Write the failing wire-parity test**

In the Paper `WireParityTest`, assert `encodeVoxelColumnPreEncoded(cx, cz, dim, ts, new byte[]{0x00})` equals the Fabric `VoxelColumnS2CPayload` encoding for the same inputs (0 sections). And in `PaperPayloadEdgeTest`, assert a 0-section column decodes cleanly (does not trip the empty-bytes guard — the body is length 1, not 0).

- [ ] **Step 2: Run to verify it fails or passes**

Run: `./gradlew :paper:test --tests 'dev.vox.lss.paper.WireParityTest' --tests 'dev.vox.lss.paper.PaperPayloadEdgeTest'`
Expected: If Paper's `buildAndEnqueueColumnPayload` already handles arbitrary bodies, this may PASS immediately (the base serve path is shared). If the Paper drop-guard rejects a short body, FAIL — fix the guard.

- [ ] **Step 3: Ensure the Paper serve path accepts the 1-byte body**

Confirm `PaperOffThreadProcessor.buildAndEnqueueColumnPayload` (the oversized/dimension guards) does not reject a 1-byte `[0x00]` body; it should pass straight through to `encodeVoxelColumnPreEncoded`. No functional change expected — this is a verification + parity-test task.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :paper:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "WS3 Task 15: Paper wire parity for the 0-section clearing column"
```

---

## Task 16: Client — authoritative air-fill on a resync column

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/ClientColumnProcessor.java` (`decodeSections`/`drainColumnQueue`), `LodRequestManager.java` (mark a position as "resync" so the processor knows to air-fill).
- Test: `fabric/src/test/java/dev/vox/lss/networking/client/ClientColumnProcessorTest.java`

**Interfaces:**
- Consumes: a decoded `VoxelColumnData` (possibly 0 sections).
- Produces: on a **resync** column (client re-requested with `ts>0`), the processor fills every absent section-Y in the level range with an all-air `LevelChunkSection` and includes it in the dispatched `VoxelColumnData`, so a consumer overwrites ghost content with air. A first-serve column (`ts<=0`) is dispatched as-is (absent = air = no-op).

The processor already runs off the render thread and has `levelSectionCount` + a `PalettedContainerFactory`. The "is this a resync" signal must reach the processor: the receive handler stamps the payload's position; the manager knows the sent `clientTimestamp` for the position. Simplest: the server only ever sends a 0-section (or content-omitting) column authoritatively when `claimsData`, so the client can treat **any received column** as authoritative for absent sections **only when the client already held data for that position** (`ColumnStateMap.timestampFor(packed) > 0` at receive time). Pass that boolean into the processor when offering the payload.

- [ ] **Step 1: Write the failing test**

```java
@Test
void resyncColumnAirFillsAbsentSectionsSoGhostTerrainClears() {
    // A column with sections {Y=0} arrives for a position the client already held (resync).
    // The dispatched VoxelColumnData must include all-air sections for every other Y in the
    // level range, so the consumer overwrites previously-ingested terrain at those Ys.
    // A first-serve column (client held nothing) dispatches only {Y=0} (no air-fill).
}
```
Drive `drainColumnQueue` with the seam constructor (it already takes `levelSectionCount` + factory), offering a payload flagged resync vs first-serve, and assert the dispatched section-Y set.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ClientColumnProcessorTest'`
Expected: FAIL — no air-fill today.

- [ ] **Step 3: Add the resync flag and air-fill**

Add a parallel queue flag (or wrap the payload) recording, at `offer` time, whether the client held data for the position. In `drainColumnQueue`, after decoding the present sections, if `resync`, build an all-air `LevelChunkSection` (via the existing `factory`) for every section-Y in `[minSectionY, minSectionY + levelSectionCount)` not present in the decoded set, and append them to the `SectionData[]` before dispatch. Use a shared cached all-air section per factory to avoid per-column allocation where safe (a `LevelChunkSection` is not shared across ingests if the consumer mutates it — build fresh per dispatch to be safe; document the cost). Bound: air-fill only runs on resyncs (rare), so the extra sections are not on the hot first-serve path.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ClientColumnProcessorTest'`
Expected: PASS. Confirm a 0-section resync column (fully emptied) does NOT trip the empty-bytes ingest-failure branch (its body is `[0x00]`, length 1) and DOES air-fill every Y.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/client/ClientColumnProcessor.java fabric/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java fabric/src/test/java/dev/vox/lss/networking/client/ClientColumnProcessorTest.java
git commit -m "WS3 Task 16: client authoritative air-fill on resync columns (#21)"
```

---

## Task 17: Client — stamp the emptied position honestly + VoxyCompat air ingest

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java` (`onColumnReceived` already stamps `columnTimestamp`), `fabric/src/main/java/dev/vox/lss/compat/VoxyCompat.java` (already ingests all sections incl. air after Task 16).
- Test: `fabric/src/test/java/dev/vox/lss/compat/VoxyCompatTest.java`

**Interfaces:**
- Consumes: the air-filled `VoxelColumnData` (Task 16).
- Produces: VoxyCompat ingests the air sections (overwriting ghost terrain, per Task 0); the position is stamped with the server `columnTimestamp` so future resyncs are correct.

- [ ] **Step 1: Write the failing/guard test**

```java
@Test
void bridgeIngestsAirSectionsOfAClearedColumn() {
    var consumer = initBridge();
    // A column whose sections are all-air (a cleared column) must still be ingested per
    // section (so Voxy overwrites stored terrain) — not skipped.
    var air = new VoxelColumnData.SectionData(0, /* all-air LevelChunkSection */ airSection(), null, null);
    consumer.onVoxelColumnReceived(null, DIM, 0, 0, column(air));
    assertEquals(1, VoxelIngestService.calls.size(), "air sections are ingested, not skipped");
}
```
(Build `airSection()` from the test factory the way `SectionLightDefaultsTest` builds sections.)

- [ ] **Step 2: Run to verify it passes (guard)**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.compat.VoxyCompatTest'`
Expected: PASS — `VoxyCompat` already loops over `columnData.sections()` and ingests each; this pins that air sections are not filtered. If a filter exists, remove it.

- [ ] **Step 3: Confirm the honest stamp**

Verify `onColumnReceived` stamps `payload.columnTimestamp()` (the server value) — it already does (`ColumnStateMap.onReceived(packed, columnTimestamp)`), so a cleared column that arrives is stamped `>0` honestly and future resyncs compare correctly. No change expected; add an assertion if a test seam allows.

- [ ] **Step 4: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/compat/VoxyCompat.java fabric/src/test/java/dev/vox/lss/compat/VoxyCompatTest.java
git commit -m "WS3 Task 17: pin air-section ingest + honest stamp on cleared columns"
```

---

## Task 18: WS3 end-to-end — Tier 2 + Tier 3 gametests

**Files:**
- Modify: `fabric/src/gametest/java/dev/vox/lss/test/SerializerParityGameTests.java` (migrate the pinned all-air test, Task 14 Step 5), add a content→air gametest; `fabric/src/gametest/java/dev/vox/lss/test/LSSClientGameTests.java` (client applies a clearing column).

- [ ] **Step 1: Add/adjust the Tier-2 content→air gametest**

Add a gametest: place content in a chunk, serve it (client holds it), clear it to air, trigger a dirty broadcast, and assert the client receives a clearing (0-section or content-omitting) column for the `ts>0` re-request. Keep the `ts<=0` first-serve all-air case asserting `RESPONSE_UP_TO_DATE`.

- [ ] **Step 2: Run Tier 2**

Run: `./gradlew :fabric:runGameTest`
Expected: PASS.

- [ ] **Step 3: Add the Tier-3 client-apply assertion**

In `LSSClientGameTests`, extend the flow so a cleared column is dispatched to the registered `LSSApi` consumer with air sections across the level range, off the render thread, with no `reportIngestFailure`.

- [ ] **Step 4: Run Tier 3**

Run: `./gradlew :fabric:runClientGameTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "WS3 Task 18: content->air Tier-2 + client-apply Tier-3 gametests (#21)"
```

---

## Task 19: WS5 — `ColumnCacheStore.removeIfStamped` (targeted persistent unstamp)

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/ColumnCacheStore.java`
- Test: `fabric/src/test/java/dev/vox/lss/networking/client/ColumnCacheStoreTest.java`

**Interfaces:**
- Produces: `static void removeIfStampedAsync(String serverAddress, ResourceKey<Level> dimension, long packed, long expectedStamp)` — on the IO thread, load the file, remove `packed` ONLY if its stored value equals `expectedStamp`, and re-save. One-shot; a legitimate re-serve (different stamp) is never clobbered.

- [ ] **Step 1: Write the failing test**

```java
@Test
void removeIfStampedRemovesOnlyOnStampMatch() {
    var map = new Long2LongOpenHashMap();
    map.put(1L, 5000L);
    ColumnCacheStore.save("srv", DIM, map);

    ColumnCacheStore.removeIfStampedAsync("srv", DIM, 1L, 4999L); // wrong stamp -> no-op
    ColumnCacheStore.flushPendingIo();
    assertEquals(5000L, ColumnCacheStore.load("srv", DIM).get(1L), "mismatched stamp is not removed");

    ColumnCacheStore.removeIfStampedAsync("srv", DIM, 1L, 5000L); // match -> removed
    ColumnCacheStore.flushPendingIo();
    assertFalse(ColumnCacheStore.load("srv", DIM).containsKey(1L), "matching stamp is unstamped");
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ColumnCacheStoreTest.removeIfStampedRemovesOnlyOnStampMatch'`
Expected: FAIL — method undefined.

- [ ] **Step 3: Implement it on the IO thread**

```java
    public static void removeIfStampedAsync(String serverAddress, ResourceKey<Level> dimension,
                                            long packed, long expectedStamp) {
        IO_EXECUTOR.execute(() -> {
            var map = load(serverAddress, dimension); // load reuses the FIFO IO thread's file
            if (map.get(packed) == expectedStamp) {
                map.remove(packed);
                save(serverAddress, dimension, map);
            }
        });
    }
```
(`load`/`save` are already static and file-based; running inside the single IO thread serializes with other cache IO.)

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.ColumnCacheStoreTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/client/ColumnCacheStore.java fabric/src/test/java/dev/vox/lss/networking/client/ColumnCacheStoreTest.java
git commit -m "WS5 Task 19: ColumnCacheStore.removeIfStamped for targeted persistent unstamp"
```

---

## Task 20: WS5 — cross-dimension ingest report triggers the targeted unstamp

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java` (`onIngestFailure`)
- Test: `fabric/src/test/java/dev/vox/lss/networking/client/LodRequestManagerTest.java`

**Interfaces:**
- Consumes: `ColumnCacheStore.removeIfStampedAsync` (Task 19); the manager knows `serverAddress` and the last-known stamp for a position in the reported dimension.

The manager's in-memory `ColumnStateMap` is the CURRENT dimension's, so it cannot hold the other dimension's stamp. The report carries only `(dimension, packed)`. To unstamp by exact stamp we need the value that was persisted for that position in that dimension. Since the false stamp is whatever the current session last saved, the manager records, per cross-dimension position, the stamp it last held before the dimension change — OR simpler and sufficient: the cross-dimension report removes the position **unconditionally if present** is unsafe (could clobber a legitimate re-serve), so instead the manager passes the stamp the CLIENT last received for that position, which the report path does have: the ingest-failure originates from a column the consumer just rejected, and that column's `columnTimestamp` is the exact stamp persisted. Thread that `columnTimestamp` into the report.

- [ ] **Step 1: Extend the report signature to carry the rejected column's stamp**

Change `LSSApi.reportIngestFailure` / `LSSClientNetworking.reportIngestFailure` / `LodRequestManager.onIngestFailure` to carry the `long columnTimestamp` of the rejected column (the value the client stamped and persisted). Update the `VoxyCompat`/`ClientColumnProcessor` call sites to pass `columnData.columnTimestamp()`.

- [ ] **Step 2: Write the failing test**

```java
@Test
void crossDimensionIngestReportUnstampsTheOtherDimensionsCache() {
    // Manager is in dimension B. A report arrives for dimension A, packed P, stamp S.
    // The manager must schedule ColumnCacheStore.removeIfStamped(server, A, P, S) rather
    // than dropping the report (which today leaves a false stamp in A's saved cache).
}
```
Drive with the manager's test seams; assert the targeted unstamp was scheduled (inject a `ColumnCacheStore` seam or assert file state via `flushPendingIo`).

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.LodRequestManagerTest.crossDimensionIngestReportUnstampsTheOtherDimensionsCache'`
Expected: FAIL — cross-dim report is currently dropped.

- [ ] **Step 4: Handle the cross-dimension case in `onIngestFailure`**

```java
    public void onIngestFailure(ResourceKey<Level> dimension, long packed, long columnTimestamp) {
        if (this.lastDimension != null && this.lastDimension.equals(dimension)) {
            // Current dimension: unstamp in memory (heals via the normal re-request path).
            this.tracker.removeByPosition(packed);
            this.columns.onIngestFailed(packed);
            this.metrics.recordIngestFailure();
            return;
        }
        // Other dimension: its state map isn't loaded and its cache is already saved. Remove
        // the false stamp directly from that dimension's persisted cache, only if it still
        // matches the rejected column's stamp (a legitimate re-serve since then is untouched).
        if (this.serverAddress != null && dimension != null && columnTimestamp > 0) {
            ColumnCacheStore.removeIfStampedAsync(this.serverAddress, dimension, packed, columnTimestamp);
        }
    }
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest --tests 'dev.vox.lss.networking.client.LodRequestManager*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "WS5 Task 20: cross-dimension ingest report unstamps the other dimension's cache (#36)"
```

---

## Task 21: Instrumentation — export a `sessionSatisfied` count; fix the soak disc-completeness floor

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java` (a `sessionSatisfiedCount()` getter), `LodRequestManager.java` (expose it), `fabric/src/main/java/dev/vox/lss/benchmark/BenchmarkMetricsExporter.java` (+ Paper twin `PaperSoakMetricsExporter` if it exports client columns — verify), `scripts/check_soak.py` (`make_disc_completeness`).
- Test: `scripts/check_soak.py --selftest`

**Interfaces:**
- Consumes: `sessionSatisfied` (Task 1).
- Produces: the soak client snapshot's `columns` object gains a `satisfied` field; `make_disc_completeness` floors on `known OR empty OR satisfied`.

Under WS1, all-air positions move out of `columns.known` (they no longer get a fabricated `>0` stamp), so the existing disc-completeness floor (`known OR empty`) would under-count and the checker would false-fail void-dimension scenarios.

- [ ] **Step 1: Expose the count**

Add `int sessionSatisfiedCount() { return this.sessionSatisfied.size(); }` to `ColumnStateMap`, and a passthrough `getSatisfiedColumnCount()` on `LodRequestManager`. In `BenchmarkMetricsExporter` (client `columns` block, ~line 405), add `columns.put("satisfied", manager != null ? manager.getSatisfiedColumnCount() : 0);`.

- [ ] **Step 2: Update the checker floor + its self-test**

In `scripts/check_soak.py` `make_disc_completeness` (~line 966), read `satisfied = fc["columns"].get("satisfied", 0)` and change the completeness floor to count a position as covered if it is in `known`, `empty`, OR `satisfied`. Add `"client.columns.satisfied"` to the `@named_check` required keys and to `KNOWN_CLIENT_KEYS` for the `columns` event. Add a `--selftest` case: an all-air disc that is fully `satisfied` (0 known, 0 empty) passes disc-completeness.

- [ ] **Step 3: Run the checker self-test**

Run: `python3 scripts/check_soak.py --selftest`
Expected: PASS (all ~115+ cases, incl. the new one).

- [ ] **Step 4: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java fabric/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java fabric/src/main/java/dev/vox/lss/benchmark/BenchmarkMetricsExporter.java scripts/check_soak.py
git commit -m "WS1 Task 21: export sessionSatisfied count; fold into soak disc-completeness (#25)"
```

---

## Task 22: Full regression — all tiers + soak smoke + release gate

**Files:** (verification only)

- [ ] **Step 1: Full Fabric build (Tier 1 + Tier 2) + Paper**

Run: `./gradlew :fabric:build -x runClientGameTest :paper:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Client gametests (Tier 3)**

Run: `./gradlew :fabric:runClientGameTest`
Expected: PASS.

- [ ] **Step 3: Soak smoke (fresh-backfill + a dirty scenario + End void)**

Run:
```bash
./scripts/soak.sh fresh-backfill
./scripts/soak.sh dirty-broadcast
./scripts/soak.sh dimension-trip
```
Expected: each exits 0 (checker green). Investigate any disc-completeness or conservation failure — it likely means a WS1/WS3 count or a serve path is off.

- [ ] **Step 4: Release-jar safety gate**

Run: `python3 scripts/release_check.py`
Expected: `OK`.

- [ ] **Step 5: Commit any final fixups**

```bash
git add -A
git commit -m "Delivery-honesty: full-tier regression green" --allow-empty
```

---

## Self-Review Notes (author)

- **Spec coverage:** WS1 → Tasks 1–6, 21; WS2 → Tasks 7–9; WS4 → Tasks 10–12; WS3 → Tasks 0, 13–18; WS5 → Tasks 19–20; #25 → Task 21; full regression → Task 22. Every workstream and the review's confirmed concerns map to a task.
- **Pinned-test migrations enumerated:** `ColumnStateMapTest` (Task 2), CL-071 + scan-time-ts (Task 4), `becomesAllAir…` (Tasks 14/18), cache migration (Task 5). No "still pass" claims.
- **Type consistency:** `claimsData` (boolean) added uniformly to `PendingRequest`/`ChunkReadResult`/`Attachment`/`GenerationReadyData` in Task 13 before use in Task 14; `sessionSatisfied`/`staleInFlight` methods defined in Tasks 1/7 before use in Tasks 2/8; `removeIfStampedAsync` defined in Task 19 before use in Task 20; `onIngestFailure` gains `columnTimestamp` in Task 20 (all call sites updated there).
