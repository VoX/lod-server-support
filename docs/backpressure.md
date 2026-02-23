# LSS Backpressure & Flow Control

This document describes how the client scanning and server request processing work together to ensure neither side gets overloaded — no queue grows unbounded, no CPU spins wastefully, and memory usage stays proportional to configured limits.

## Overview

The system forms a pipeline:

```
Client Scan → Batched Requests → Server Batch Processing → Disk Read / Generation → Send Queue → Bandwidth Limiter → Network → Client Receive
```

Every stage has bounded capacity. When a downstream stage fills up, upstream stages stall rather than drop work. The key insight is that backpressure propagates in both directions — the server controls the client's request rate via session config limits, and the client controls the server's work rate by only requesting what it can track.

---

## Client Side

### Spiral Scanner (`LodRequestManager.tickScan`)

The client scans outward from the player in an expanding spiral, one batch per tick. Each position is classified into one of four categories:

| Category | Condition | Timestamp sent | Budget |
|----------|-----------|---------------|--------|
| **Fresh** | Never requested (ts == -1) | 0 | Main |
| **Dirty** | Server pushed change notification | Stored ts | Main |
| **Generation** | Empty (ts == 0), within generation distance | 0 | Generation sub-budget |
| **Resync** | Has cached data (ts > 0), initial join only | Stored ts | Resync sub-budget |

Positions that already have data and aren't dirty are "settled" — the scanner skips them and advances to the next radius.

### Client-Side Backpressure Gates

**1. Pending column limit** — The hard cap. Before scanning even starts:

```java
if (this.pendingColumns.size() >= this.sessionConfig.maxPendingRequests()) return;
```

The server sends `maxPendingRequests` (default 512) in the session config. The client tracks every position it has requested but not yet received a response for. Once this limit is hit, the scanner doesn't run at all. This is the primary mechanism that prevents the client from flooding the server with requests.

**2. Batch size budget** — Within a single tick's scan, the client can collect at most `maxRequestsPerBatch` positions (default 256). This caps the size of any single network packet.

**3. Generation sub-budget** — Empty columns eligible for generation retry are limited to `maxGenerationRequestsPerBatch` (server-configured, default equals `maxConcurrentGenerationsPerPlayer`). This prevents the client from requesting thousands of ungenerated chunks in a single sweep.

**4. Advancement blocking** — When the scanner encounters positions it wants to request but can't (budget exhausted, pending limit reached), it sets `canAdvance = false` for that radius. The scan cursor stays at that radius, ensuring those positions get picked up on the next tick rather than being skipped. This prevents the scanner from racing ahead through the spiral while leaving gaps.

**5. Resync sub-budget** — During the initial join sweep, positions with cached timestamps get an independent cap of `min(resyncBatchSize, maxBatch/4)` (default: min(32, 64) = 32). Resync positions consume from both the resync counter and the main budget, so the total batch size stays capped at `maxRequestsPerBatch`. Unused resync slots remain available for fresh/dirty/generation positions. This prevents resync traffic from starving fresh requests without wasting capacity.

### Client-Side Memory Bounds

- **`columnTimestamps`**: One `long→long` entry per position within `lodDistance + 32` of the player. Pruned on every player movement beyond chunk boundaries. At lodDistance=128, worst case ~(257×257) = ~66K entries × 16 bytes = ~1 MB.
- **`pendingColumns`**: Capped at `maxPendingRequests` (default 512) entries.
- **`activeBatches`**: At most `maxPendingRequests / 1` batches (one position minimum per batch), realistically far fewer. Timed out after 60 seconds.
- **`dirtyColumns`**: Bounded by the number of columns with data that the server reports as changed. Drained into batches on each scan tick.

### Cancellation

When the player moves, the client walks all active batches and identifies positions that are now outside LOD distance. Batches with all positions out of range are cancelled entirely via `CancelRequestC2SPayload`. This frees pending column slots immediately, allowing the scanner to request chunks in the player's new vicinity without waiting for stale responses. The server marks cancelled batches and skips remaining work.

### Batch Timeout

Batches older than 60 seconds are purged client-side. Their positions are removed from `pendingColumns`, freeing slots. This handles edge cases where the server drops a `RequestComplete` packet or crashes mid-batch.

---

## Server Side

### Request Entry

When the server receives a `ChunkRequestC2SPayload`:

1. **Pending batch limit**: If the player already has `maxPendingRequestsPerPlayer` (default 512) batches queued, the entire batch is rejected with `STATUS_REJECTED`. The client receives this and doesn't mark those positions as empty.

2. **Batch truncation**: Positions beyond `maxRequestsPerBatch` (default 256) are silently dropped.

3. **Distance filter**: Every position is validated against `lodDistanceChunks` from the player's current position. Out-of-range positions are rejected.

The surviving positions are stored in a `RequestBatch` in a `LinkedHashMap` (FIFO order).

### Tick Loop

The server tick runs five phases per tick:

```
1. Drain disk read results   → send queue
2. Tick generation service    → drain generation results → send queue
3. Calculate bandwidth allocation
4. Per player:
   a. Flush send queue        (bandwidth-gated)
   b. Process request batches (submits to disk/generation)
   c. Drain completed batches (sends RequestComplete)
5. Periodic dirty column broadcast
```

The ordering matters: flushing the send queue (4a) runs before processing new batch positions (4b). This means the queue drains before new work is submitted, keeping queue sizes stable.

### Three Processing Pathways

When `processRequestBatches` walks a batch's positions, each position takes one of three paths:

**Path 1 — In-Memory Chunk**: If `getChunkNow()` returns a loaded chunk, it's serialized immediately on the server thread and sections are added directly to the send queue. Gated by `MAX_SERIALIZATIONS_PER_TICK` (1000) to prevent serialization from monopolizing the tick.

**Path 2 — Disk Read**: If the chunk isn't loaded and disk reading is enabled, the position is submitted to `ChunkDiskReader`'s thread pool. Gated by:
- `maxConcurrentDiskReads` per player (default 64) — caps in-flight async reads
- `maxSendQueueSize` per player (default 4800) — prevents submitting reads when the queue is already full

When a read completes, the result enters a `ConcurrentLinkedQueue` and is drained on the next server tick. Results that find data go to the send queue. Results that find an empty or partial chunk are routed to generation (if within generation distance and the player's generation backpressure allows) or marked done.

**Path 3 — Generation**: If the chunk doesn't exist on disk and is within `generationDistanceChunks`, it's submitted to `ChunkGenerationService`. The generation service has a two-tier queue:
- **Active** (max `maxConcurrentGenerations`, default 16): Has a Minecraft loading ticket. The server's chunk system will load/generate the chunk.
- **Waiting** (unbounded metadata queue): No ticket yet. Promoted to active FIFO as slots free up.

Deduplication: if multiple players (or multiple batch positions) request the same chunk, they piggyback on a single generation entry via a callback list.

When a generated chunk becomes available (`getChunkNow()` returns non-null), it's serialized and results are posted to all callbacks. The ticket is removed.

### Server-Side Backpressure Gates

The system has backpressure at every pipeline boundary:

**Batch processing → Disk reads:**
```java
// Won't submit more disk reads if queue is full or too many in-flight
if (state.getPendingDiskReadCount() < config.maxConcurrentDiskReads
        && state.getSendQueueSize() < config.maxSendQueueSize) {
    // submit
} else {
    batch.nextPositionIndex--;  // back off, retry next tick
    return;
}
```

**Batch processing → Generation:**
```java
if (dist <= config.generationDistanceChunks) {
    if (state.getPendingGenerationCount() >= config.maxConcurrentGenerationsPerPlayer * 2) {
        batch.nextPositionIndex--;  // back off, retry next tick
        return;
    }
    generationService.submitGeneration(...);
    state.markPendingGeneration(cx, cz);
}
```
Per-player pending generation count is gated at `maxConcurrentGenerationsPerPlayer * 2`. The active slot limit (`maxConcurrentGenerations`) and per-player active limit (`maxConcurrentGenerationsPerPlayer`) are the real rate limiters inside the generation service; the pending count gate in the batch processor prevents a single player from flooding the waiting queue.

**Disk results → Generation (drainDiskResultsToQueues):**
```java
boolean routeToGen = false;
if (result.notFound() && generationService != null) {
    int dist = Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz));
    if (dist <= config.generationDistanceChunks) {
        if (state.getPendingGenerationCount() >= config.maxConcurrentGenerationsPerPlayer * 2) {
            break; // generation full, retry next tick
        }
        routeToGen = true;
    }
}
```
When disk reads return "not found" and the chunk is within generation distance, it routes to generation — but only if the player's pending generation count is below the backpressure threshold. If generation is full, the drain loop `break`s without polling the result, leaving it and all subsequent results in the queue for the next tick. This is critical on new servers where hundreds of "not found" results arrive at once — without the break, only the first batch (up to the generation limit) would be routed to generation while the rest would be silently dropped, creating visible gaps in the spiral. The trade-off is head-of-line blocking: results behind a generation-gated one must wait even if they'd take a different path. In practice this is fine because disk results drain fully each tick and the stall is at most one tick.

**Generation results → Send queue:**
```java
// Peek-before-poll: don't drain results if send queue is full
while ((result = queue.peek()) != null) {
    if (result.payloads().length > 0 && state.getSendQueueSize() >= config.maxSendQueueSize) break;
    queue.poll();
    // ... add payloads to send queue
}
```
This prevents the send queue from growing unbounded when generation completes faster than the network can drain. Results accumulate in the generation service's lightweight results deque instead.

**Send queue → Network:**
```java
// Two gates: per-tick section cap and per-second bandwidth budget
if (state.getSectionsSentThisTick() >= config.maxSectionsPerTickPerPlayer) return;
if (!state.canSend(allocationBytes)) return;
```

**Global bandwidth fairness:**
```java
// SharedBandwidthLimiter — token bucket that refills proportionally to elapsed real time,
// then divides available tokens equally among active players
long perPlayerAllocation = bandwidthLimiter.getPerPlayerAllocation(activeCount);
long perPlayerCap = Math.min(perPlayerAllocation, config.maxBytesPerSecondPerPlayer);
```

### Server-Side Memory Bounds

| Structure | Bound | Size estimate |
|-----------|-------|---------------|
| `pendingBatches` per player | `maxPendingRequestsPerPlayer` batches × `maxRequestsPerBatch` positions | 512 × 256 × 16 bytes = ~2 MB worst case |
| `sendQueue` per player | Held to ~`maxSendQueueSize` (4800) by upstream gating | ~4800 × ~3 KB avg = ~14 MB worst case |
| `pendingDiskReads` per player | `maxConcurrentDiskReads` (64) entries | Negligible |
| `pendingGeneration` per player | One entry per submitted-but-not-completed generation | Proportional to waiting queue |
| `diskReadDone` per player | One entry per position with a completed disk read or generation in an active batch | Bounded by total batch positions |
| Generation active queue | `maxConcurrentGenerations` (16) entries with tickets | 16 chunk loads |
| Generation waiting queue | Unbounded entries, but each is just a key + callback list | ~100 bytes per entry |
| Generation results deque | Accumulates when send queue is full; drains when it isn't | Temporary buffer, bounded by generation rate (16 completions/tick max) |
| Disk reader results | `ConcurrentLinkedQueue`, fully drained each tick | Bounded by thread pool throughput between ticks |

### Batch Lifecycle & Completion

A batch is "fully processed" when `nextPositionIndex >= positions.length` — the server has visited every position and either serialized it, submitted it to disk read, submitted it to generation, or skipped it. But this doesn't mean all results have arrived — disk reads and generation are async.

A batch is "complete" (eligible for `RequestComplete` response) when it's fully processed or cancelled. The `drainCompletedBatches` iterator walks batches FIFO and removes completed ones. Note that `RequestComplete` is sent as soon as all positions have been *visited* (submitted to disk/generation/serialized), even if async results haven't arrived yet — section payloads may still be in flight or queued.

When the client receives `RequestComplete`, it removes the batch from `activeBatches` and frees pending column slots. For any positions in the batch that the client never received section data for, it marks them with timestamp 0 (empty). On the next scan sweep, those positions may be re-requested if they're within generation distance and budget allows.

---

## End-to-End Flow Example

1. **Client joins**, receives `SessionConfig` with limits (lodDistance=128, maxBatch=256, maxPending=512, genBudget=8, genDistance=64).

2. **Client scans** outward in a spiral. First tick: collects 256 fresh positions at radii 0–~9, sends batch #0. `pendingColumns` = 256.

3. **Next tick**: collects another 256 fresh positions (radii ~9–~13), sends batch #1. `pendingColumns` = 512. Now at the pending limit — scanner stops.

4. **Server processes batch #0**: walks 256 positions. ~50 are in-memory → serialized immediately. ~200 go to disk reader. ~6 go to generation. Batch cursor reaches the end.

5. **Disk reads complete** over the next few ticks. Most find data → sections added to send queue. ~30 are empty → routed to generation waiting queue.

6. **Send queue drains** at ~200 sections/tick (bandwidth permitting). After ~5 ticks, most of batch #0's results are sent.

7. **Server sends `RequestComplete`** for batch #0. Client receives it, frees 256 pending slots. Scanner immediately picks up the next 256 positions.

8. **Generation** of the 36 submitted chunks proceeds in the background. As each completes, its sections enter the generation results deque and drain into the send queue (respecting the `maxSendQueueSize` gate). Results arrive over the next 30–60 seconds.

9. **Client receives sections** from generation: `onSectionReceived` removes the position from pending and records the timestamp. On the next sweep, those positions are settled and skipped.

10. **Client moves** 32 chunks north. `pruneOutOfRangePositions` cancels batches whose positions are now out of range. `pruneOutOfRangeTimestamps` removes timestamps beyond `lodDistance + 32`. `scanRadius` resets to 0. The scanner begins a new spiral from the new position.

11. **Server receives cancellation**, marks batches as cancelled. `drainCompletedBatches` sends `STATUS_CANCELLED`. Generation service doesn't explicitly cancel in-flight generations (they're cheap to complete), but orphaned waiting entries are evicted when the player is removed or changes dimension.

12. **Dirty columns**: periodically, the server's `ChunkChangeTracker` collects chunk positions that were saved to disk since the last broadcast. The server filters these to within each player's LOD distance and sends `DirtyColumnsS2CPayload`. The client adds them to `dirtyColumns` and on the next scan, they're collected like fresh requests (with the stored timestamp, so the server can detect if the client is already up-to-date via the fast-path region header comparison).

---

## Summary of All Backpressure Points

```
CLIENT                              SERVER
------                              ------

pendingColumns >= maxPending ----> Scanner stops entirely
batch budget exhausted ----------> Scanner stops for this tick
generation budget exhausted -----> Scanner blocks at this radius

              +========================+
              |  Network (batches)     |
              +========================+

                       pendingBatches >= maxPendingPerPlayer --> Batch rejected
                       positions > maxRequestsPerBatch --> Truncated
                       positions outside lodDistance --> Filtered

                       sendQueue >= maxSendQueueSize --> Batch processing pauses
                       serializations >= 1000/tick --> Batch processing pauses
                       pendingDiskReads >= maxConcurrentDiskReads --> Back off
                       pendingGeneration >= maxPerPlayer * 2 --> Batch processing pauses

                       active generations >= maxConcurrent --> Wait in queue
                       generation results + sendQueue full --> Peek-pause drain
                       drainDiskResults: generation full --> Pause drain (retry next tick)

                       sectionsSentThisTick >= maxSectionsPerTick --> Stop sending
                       bytesSentThisSecond >= perPlayerBandwidth --> Stop sending
                       globalBytesSent >= globalBandwidth --> Allocation shrinks

              +========================+
              |  Network (sections)    |
              +========================+

onSectionReceived ----> Remove from pending (frees slots)
onBatchComplete ------> Remove batch + remaining pending (frees slots)
batch timeout (60s) --> Purge stale batch (frees slots)
```

Every queue is either explicitly capped by configuration or implicitly bounded by an upstream cap. The generation waiting queue is the only "unbounded" structure, but it contains only lightweight metadata (~100 bytes per entry) and is naturally bounded by the number of positions the client can request (which is itself bounded by `maxPendingRequests × maxRequestsPerBatch` and the LOD area).
