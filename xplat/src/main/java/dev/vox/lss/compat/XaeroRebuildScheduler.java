package dev.vox.lss.compat;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static dev.vox.lss.compat.XaeroMapCompat.*;

/** Extracted responsibility; calls retain their originating bridge/session. */
final class XaeroRebuildScheduler {
    private final XaeroMapCompat session;
    XaeroRebuildScheduler(XaeroMapCompat session) { this.session=session; }
    /** Tile chunks committed but not yet texture-rebuilt, keyed by tile-chunk
     *  coords and ordered by LAST TOUCH (a re-touch re-inserts at the tail, so
     *  idle-due entries are always a prefix). Main thread only. */
    final LinkedHashMap<PendingKey, PendingUpdate> pendingUpdates = new LinkedHashMap<>();
    long pumpCount; // main thread only
    final AtomicLong bufferUpdates = new AtomicLong();
    /** Frame flushes that passed the gate ladder — the per-frame scheduler is alive
     *  (its absence in a live diag means the render hook is not firing and the tick
     *  fallback is doing the rebuilds). */
    final AtomicLong frameFlushes = new AtomicLong();
    /** Total nanos inside {@code MapTileChunk.updateBuffers} + the single worst call
     *  — the live stutter instruments (diag {@code rebuild_ms=}/{@code rebuild_max_us=}). */
    final AtomicLong rebuildNanos = new AtomicLong();
    volatile long rebuildNanosMax;
    /** A frame flush ran (or fast-out-armed) since the last pump — consumed into
     *  {@link #frameActiveThisPump} at the TOP of {@code pump()} (§17.1: a pump that
     *  returns at a ladder gate must not leave it armed for a later one). Main
     *  thread only (frames and ticks share the render thread). */
    boolean frameFlushRan;
    /** The marker's per-pump snapshot — the value {@code tickFlush} acts on. */
    boolean frameActiveThisPump;
    /** Nanos of {@code updateBuffers} the frame slice spent since the last pump —
     *  the interval's allowance meter (§17.1): frames stop recoloring once it
     *  reaches the budget-with-borrow, so a high-fps client pays the same wall
     *  rate the tick fallback would. Main thread only. */
    long rebuildSpentSinceLastPumpNanos;
    /** Frames seen since the last pump — the per-frame cap's pressure bumps apply
     *  only while frames are SCARCE (≤1 per tick). Main thread only. */
    int framesSinceLastPump;
    final AtomicLong droppedUpdates = new AtomicLong();
    /** Owed rebuilds whose region/tile chunk Xaero unloaded, parked or replaced
     *  first — its own counter (review A) so the live test can tell a parking race
     *  from the stall/dimension/session drops. */
    final AtomicLong droppedUnloaded = new AtomicLong();
    volatile int pendingUpdatesGauge;

    /**
     * Per-frame flush (plan §17/§17.1): the texture-rebuild phase runs at FRAME
     * cadence — Xaero's own sweep scheduling — instead of bunched on the client
     * tick, at most a pressure-capped few recolors per call (base
     * {@link #frameMaxRebuilds}; scarce-frame bumps; allowance-bounded). Mirrors the
     * pump ladder's gate envelope exactly down to the dimension equality (a rebuild
     * must never run under weaker gates than the pump's flush did); the settings and
     * cave-layer gates sit BELOW the flush in the pump ladder (rebuilds are owed
     * debt to already-committed tile chunks, not new writes) and are skipped here
     * for the same reason. Never drains commits, never grants loads, never visits
     * regions (tick-side — the 1 s park guard needs only pump cadence), and defers
     * the world-change queue drop to the pump (it returns instead). Shares the
     * pump's containment + death latch.
     */
    void frameFlush() {
        if (this.session.dead || this.session.sessionEndPending || this.pendingUpdates.isEmpty()) return;
        this.framesSinceLastPump++;
        // §17.1 fast-outs, both arming the stand-down marker WITHOUT the reflective
        // ladder (safe: a tick flush with nothing due — or after this interval's
        // allowance was spent on real recolors — is a no-op either way):
        // (1) nothing can be due yet;
        if (this.nothingDueAtHead()) {
            this.frameFlushRan = true;
            return;
        }
        // (2) the interval's allowance is spent — but only after a REAL recolor this
        // interval (spent == 0 must fall through, or a degenerate zero budget would
        // stand the tick down forever and void the always-drains exemption).
        if (this.rebuildSpentSinceLastPumpNanos > 0
                && this.rebuildSpentSinceLastPumpNanos >= this.rebuildBudgetWithBorrow()) {
            this.frameFlushRan = true;
            return;
        }
        try {
            this.frameLadder();
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            this.session.noteFailure(t);
        }
    }

    /** True when no owed rebuild can be due this pump, judged from the HEAD entry
     *  (touch order makes it the oldest last-touch, so its idle window binds first)
     *  — the frame slice's cheap pre-ladder skip for the ~2 s coalescing window
     *  (§17.1). The age/stall legs also read only the head: a non-head entry due by
     *  age or stall waits at most one idle window extra — accepted slack. */
    boolean nothingDueAtHead() {
        if (this.pendingUpdates.size() > this.session.pendingUpdatesSoftCap) return false;
        var head = this.pendingUpdates.values().iterator().next();
        return this.pumpCount - head.lastTouchPump < this.session.updateIdlePumps
                && this.pumpCount - head.firstTouchPump < this.session.updateMaxDeferPumps
                && head.stalledSincePump < 0;
    }

    void frameLadder() throws Throwable {
        Object session = this.session.h.getCurrentSession.invoke();
        if (session == null || !(boolean) this.session.h.sessionIsUsable.invoke(session)) return;
        Object mp = this.session.h.getMapProcessor.invoke(session);
        if (mp == null) return;
        if (this.session.h.crashGate != null) {
            Object handler = this.session.h.crashGate.crashHandler().invoke();
            if (handler != null && this.session.h.crashGate.getCrashedBy().invoke(handler) != null) {
                return; // never touch a crashed Xaero; the pump owns the diag flag
            }
        }
        Object renderPause = this.session.h.renderThreadPauseSync.invoke(mp);
        synchronized (renderPause) {
            if ((boolean) this.session.h.isWritingPaused.invoke(mp)) return;
            if ((boolean) this.session.h.isWaitingForWorldUpdate.invoke(mp)) return;
            if (!(boolean) this.session.h.isRegionDetectionComplete.invoke(this.session.h.getMapSaveLoad.invoke(mp))) return;
            if (!(boolean) this.session.h.isCurrentMultiworldWritable.invoke(mp)) return;
            Object world = this.session.h.getWorld.invoke(mp);
            Object mapWorld = this.session.h.getMapWorld.invoke(mp);
            if (world == null || (boolean) this.session.h.isCurrentMapLocked.invoke(mp)
                    || (boolean) this.session.h.isCacheOnlyMode.invoke(mapWorld)) {
                return;
            }
            String worldId = (String) this.session.h.getCurrentWorldId.invoke(mp);
            if (worldId == null || (boolean) this.session.h.ignoreWorld.invoke(mp, world)) return;
            if (this.session.lastWorldId != null && !this.session.lastWorldId.equals(worldId)) return;
            Object dimensionId;
            Object mainSync = this.session.h.mainStuffSync.invoke(mp);
            synchronized (mainSync) {
                if (this.session.h.mainWorld.invoke(mp) != world) return;
                dimensionId = this.session.h.getCurrentDimensionId.invoke(mapWorld);
                if (this.session.levelOps.dimension(world) != dimensionId) return;
            }
            // Past every gate the pump's flush would have run under: the tick's
            // rebuild fallback stands down until the next pump. §17.1: the per-frame
            // cap grows under backlog pressure ONLY while frames are scarce (a long
            // frame absorbs a few recolors; at high fps one per frame already outruns
            // the serve rate), and the flush budget is the interval allowance's
            // remainder, so a multi-rebuild frame stays inside the wall rate the
            // tick fallback would have paid.
            this.frameFlushRan = true;
            this.frameFlushes.incrementAndGet();
            int pending = this.pendingUpdates.size();
            boolean scarce = this.framesSinceLastPump <= 1;
            int cap = this.session.frameMaxRebuilds
                    + (scarce && pending > this.session.pendingUpdatesSoftCap ? 1 : 0)
                    + (scarce && pending > this.session.pendingUpdatesHardCap / 2 ? 1 : 0);
            long remaining = Math.max(1L,
                    this.rebuildBudgetWithBorrow() - this.rebuildSpentSinceLastPumpNanos);
            long rebuildNanosBefore = this.rebuildNanos.get();
            this.flushPendingUpdates(mp, dimensionId, remaining, cap, false);
            this.rebuildSpentSinceLastPumpNanos += this.rebuildNanos.get() - rebuildNanosBefore;
        }
    }

    /** Reserve all distinct groups before a multi-group commit; capacity refusal
     *  must retain the column, never leave changed pixels with no owed redraw. */
    boolean hasRebuildCapacity(Object dimensionId, XaeroTileExtractor.PreparedTile tile,
                                       List<SlopeNeighbor> neighbors) {
        var keys = new java.util.HashSet<PendingKey>();
        keys.add(this.session.pendingKey(dimensionId, tile.chunkX() >> 2, tile.chunkZ() >> 2));
        for (var neighbor : neighbors) {
            keys.add(this.session.pendingKey(dimensionId, neighbor.chunkX() >> 2, neighbor.chunkZ() >> 2));
        }
        keys.removeAll(this.pendingUpdates.keySet());
        return this.pendingUpdates.size() + keys.size() <= this.session.pendingUpdatesHardCap;
    }

    // ---- the rebuild phase (plan §15) ----

    void notePendingUpdate(Object mp, Object dimensionId, Object region, Object tileChunk,
                                   int localTcX, int localTcZ, int tileChunkX, int tileChunkZ)
            throws Throwable {
        var key = this.session.pendingKey(dimensionId, tileChunkX, tileChunkZ);
        var existing = this.pendingUpdates.remove(key); // re-insert at the tail = last touch
        if (existing != null && existing.tileChunk == tileChunk) {
            existing.lastTouchPump = this.pumpCount;
            existing.stalledSincePump = -1; // the commit gate just passed: the stall ended
            this.pendingUpdates.put(key, existing);
        } else {
            // A replaced tile chunk (Xaero reloaded the region) gets a FRESH entry —
            // the old object's rebuild would fail its identity check and drop; count
            // the old one now (a reload rebuilds its own textures).
            if (existing != null) this.droppedUnloaded.incrementAndGet();
            this.pendingUpdates.put(key, new PendingUpdate(mp,
                    (String) this.session.h.getCurrentWorldId.invoke(mp), dimensionId, region, tileChunk,
                    localTcX, localTcZ, this.pumpCount));
        }
        this.pendingUpdatesGauge = this.pendingUpdates.size();
    }

    /**
     * Run the owed rebuilds that are DUE — idle for {@link #updateIdlePumps}, or
     * older than {@link #updateMaxDeferPumps} (the trickle ceiling), or the oldest
     * beyond the soft cap, or previously stalled — oldest-touch first, within the
     * caller's {@code budget} and {@code maxRebuilds} (plan §17: the FRAME slice
     * passes {@link #frameMaxRebuilds} with no region visits; the tick fallback
     * passes the borrow-topped budget with no rebuild cap; a frames-active tick
     * passes ZERO rebuilds — cheap drops/bookkeeping only — and the first removing
     * outcome is budget-exempt, so the set always drains). Each rebuild re-runs the writer's region gates
     * ({@code writerThreadPauseSync} + {@code !isWritingPaused()}, the region
     * monitor, {@code isResting()}) — ONCE per region per flush for a not-ready
     * verdict (memoized, no budget): a not-resting region (being saved / cache
     * pending — exactly the state the flag-consuming sweep ignored) keeps its
     * entries for a later pump, as does another dimension's region (the pixel
     * recipe reads the CURRENT dimension's shading; the entries wait for the
     * player's return); either drops after {@link #updateMaxStallPumps}. An
     * unloaded or replaced tile chunk, or an entry from a previous Xaero session
     * (processor identity / world id), drops at once — a reload rebuilds its own
     * textures. A tile chunk the native writer already consumed
     * ({@code !wasChanged()}) needs nothing.
     */
    /** The interval's rebuild allowance: the §15.2 budget-with-borrow math, shared
     *  by the tick fallback and the frame slice's allowance ceiling (§17.1). */
    long rebuildBudgetWithBorrow() {
        boolean queueEmpty;
        synchronized (this.session.queueLock) {
            queueEmpty = this.session.queue.isEmpty();
        }
        long borrow = queueEmpty ? this.session.updateBorrowNanos
                : this.pendingUpdates.size() > this.session.pendingUpdatesSoftCap ? this.session.updateBorrowNanos / 2 : 0;
        return borrow > Long.MAX_VALUE - this.session.updateNanosBudget
                ? Long.MAX_VALUE : this.session.updateNanosBudget + borrow; // saturating (the seams take MAX)
    }

    /** The tick pump's flush call: with frames flushing (the marker consumed at the
     *  top of {@link #pump}) the tick runs ZERO rebuilds — drops/bookkeeping only,
     *  never a recolor bunched onto the tick; with no frame since the last pump
     *  (loading screens, hidden window, headless test JVMs) it falls back to the
     *  full §15 budget-with-borrow rebuild behavior. */
    void tickFlush(Object mp, Object dimensionId) {
        boolean frameActive = this.frameActiveThisPump;
        long budget = frameActive ? this.session.updateNanosBudget // bounds the cheap-class scan only
                : this.rebuildBudgetWithBorrow();
        this.flushPendingUpdates(mp, dimensionId, budget, frameActive ? 0 : Integer.MAX_VALUE, true);
    }

    void flushPendingUpdates(Object mp, Object dimensionId, long budget,
                                     int maxRebuilds, boolean keepVisited) {
        if (this.pendingUpdates.isEmpty()) {
            this.pendingUpdatesGauge = 0;
            return;
        }
        long start = System.nanoTime();
        var args = new RebuildArgs();
        String worldId;
        try {
            worldId = (String) this.session.h.getCurrentWorldId.invoke(mp);
            if (keepVisited) this.keepOwedRegionsVisited(mp, worldId, dimensionId);
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            this.session.noteFailure(t);
            return;
        }
        int removed = 0;
        int overflow = this.pendingUpdates.size() - this.session.pendingUpdatesSoftCap;
        var it = this.pendingUpdates.values().iterator();
        while (it.hasNext()) {
            // The §15 exemption: removing outcomes arm the budget check, and not-ready
            // probes stay FREE up to a small floor (memoized per region, so the floor
            // is distinct regions — the pin: ready work behind a not-ready region must
            // not starve). Past the floor the budget applies even with zero removals
            // (§17.1, review B m4: an all-not-ready set must not walk hundreds of
            // region monitors unbounded at frame cadence on the render thread).
            if ((removed > 0 || args.probes > FLUSH_PROBE_EXEMPT_FLOOR)
                    && System.nanoTime() - start > budget) break;
            var pu = it.next();
            boolean due = overflow-- > 0
                    || this.pumpCount - pu.lastTouchPump >= this.session.updateIdlePumps
                    || this.pumpCount - pu.firstTouchPump >= this.session.updateMaxDeferPumps
                    || pu.stalledSincePump >= 0;
            if (!due) continue; // touch order makes idle-due a prefix, but the age ceiling is not
            UpdateResult result;
            if (pu.processor != mp || !java.util.Objects.equals(pu.worldId, worldId)) {
                result = UpdateResult.DROPPED; // a previous Xaero session's objects
            } else if (pu.dimension != dimensionId) {
                result = UpdateResult.NOT_READY;
            } else if (maxRebuilds == 0) {
                continue; // frames own the rebuilds while they flush — cheap classes only
            } else {
                result = this.rebuildTileChunk(mp, pu, args);
            }
            switch (result) {
                case DONE -> {
                    it.remove();
                    removed++;
                }
                case DROPPED -> {
                    it.remove();
                    removed++;
                    if (pu.processor != mp || !java.util.Objects.equals(pu.worldId, worldId)) {
                        this.droppedUpdates.incrementAndGet(); // the session-identity drop
                    } // else: the rebuild counted dropped_unloaded itself
                }
                case NOT_READY -> {
                    if (pu.stalledSincePump < 0) {
                        pu.stalledSincePump = this.pumpCount;
                    } else if (this.pumpCount - pu.stalledSincePump >= this.session.updateMaxStallPumps) {
                        it.remove();
                        removed++;
                        this.droppedUpdates.incrementAndGet();
                    }
                }
                case FAILED -> {
                    it.remove();
                    removed++;
                    this.droppedUpdates.incrementAndGet(); // owed, never rebuilt
                }
            }
            if (this.session.dead) break;
            if (maxRebuilds > 0 && args.rebuilt >= maxRebuilds) break;
        }
        this.pendingUpdatesGauge = this.pendingUpdates.size();
    }

    /**
     * The park guard the flag used to be (review A MAJOR): {@code LeafRegionTexture.
     * postUpload} parks a region — loadState 3, tile chunks {@code clean()}ed, their
     * tiles released — once it is not being written, ONE second has passed since its
     * last visit, and no tile chunk is flagged {@code toUpdateBuffers}. The native
     * writer's flag held that off until the texture was built; ours is never set, so
     * the bridge keeps every region with an owed rebuild VISITED each pump (the
     * writer's own "someone is working here" signal, {@code registerVisit} — what the
     * commit does too), once per region, under the region monitor. Same-session
     * entries only; foreign ones are skipped.
     */
    void keepOwedRegionsVisited(Object mp, String worldId, Object dimensionId) throws Throwable {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (var pu : this.pendingUpdates.values()) {
            if (pu.processor != mp || !java.util.Objects.equals(pu.worldId, worldId)
                    || pu.dimension != dimensionId || !seen.add(pu.region)) {
                continue;
            }
            synchronized (pu.region) {
                if ((byte) this.session.h.getLoadState.invoke(pu.region) == 2) {
                    this.session.h.registerVisit.invoke(pu.region);
                }
            }
        }
    }

    UpdateResult rebuildTileChunk(Object mp, PendingUpdate pu, RebuildArgs args) {
        if (args.notReadyRegions.contains(pu.region)) return UpdateResult.NOT_READY;
        args.probes++; // §17.1: probes past FLUSH_PROBE_EXEMPT_FLOOR arm the budget check
        try {
            Object writerPause = this.session.h.writerThreadPauseSync.invoke(pu.region);
            synchronized (writerPause) {
                if ((boolean) this.session.h.regionIsWritingPaused.invoke(pu.region)) {
                    args.notReadyRegions.add(pu.region);
                    return UpdateResult.NOT_READY;
                }
                synchronized (pu.region) {
                    // Region unloaded/parked, tile chunk replaced, or a tile-chunk-only
                    // teardown (deleteTexturesAndBuffers sets ITS loadState 0 without
                    // touching the region's — review A N3): a reload rebuilds its own.
                    if ((byte) this.session.h.getLoadState.invoke(pu.region) != 2
                            || this.session.h.regionGetChunk.invoke(pu.region, pu.localTcX, pu.localTcZ)
                            != pu.tileChunk
                            || (int) this.session.h.tileChunkGetLoadState.invoke(pu.tileChunk) != 2) {
                        this.droppedUnloaded.incrementAndGet();
                        return UpdateResult.DROPPED;
                    }
                    if (!(boolean) this.session.h.isResting.invoke(pu.region)) {
                        args.notReadyRegions.add(pu.region);
                        return UpdateResult.NOT_READY;
                    }
                    if ((boolean) this.session.h.tileChunkWasChanged.invoke(pu.tileChunk)) {
                        // A save may have reset beingWritten since the commit; the
                        // rebuilt texture must still reach the region's cache, and
                        // the save path is what requests it (set-never-clear, as
                        // in the commit).
                        this.session.h.setBeingWritten.invoke(pu.region, true);
                        if (args.fastConfig == null) {
                            args.tint = this.session.h.getWorldBlockTintProvider.invoke(mp);
                            args.overlayManager = this.session.h.getOverlayManager.invoke(mp);
                            args.shapeCache = this.session.h.getBlockStateShortShapeCache.invoke(mp);
                            args.fastConfig = this.session.h.newMapUpdateFastConfig.invoke(mp);
                        }
                        // The boolean is the writer's detailed-debug flag (log-only).
                        long rebuildStart = System.nanoTime();
                        this.session.h.tileChunkUpdateBuffers.invoke(pu.tileChunk, mp, args.tint,
                                args.overlayManager, false, args.shapeCache, args.fastConfig);
                        long rebuildTook = System.nanoTime() - rebuildStart;
                        this.rebuildNanos.addAndGet(rebuildTook);
                        if (rebuildTook > this.rebuildNanosMax) this.rebuildNanosMax = rebuildTook;
                        args.rebuilt++;
                        this.session.h.tileChunkSetChanged.invoke(pu.tileChunk, false);
                        this.bufferUpdates.incrementAndGet();
                    }
                    return UpdateResult.DONE;
                }
            }
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            this.session.noteFailure(t);
            return UpdateResult.FAILED;
        }
    }
}
