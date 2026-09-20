package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static dev.vox.lss.compat.XaeroSession.*;
import dev.vox.lss.compat.XaeroSession.Origin;

/** Origin-owned acquisition and debt; every removal releases its captured receipt. */
final class XaeroAcquisitionQueue {
    private final XaeroSession session;
    XaeroAcquisitionQueue(XaeroSession session) { this.session = session; }

    final Object queueLock = new Object();
    /** Packed chunk pos → entry; insertion-ordered, latest tile wins in place. */
    final LinkedHashMap<Long, Entry> queue = new LinkedHashMap<>();
    long queuedBytes; // under queueLock
    /** Queue occupancy in [0,1] — max of the byte and count fractions, mirrored
     *  under {@link #queueLock} at every mutation for the lock-free 20 Hz
     *  backpressure poll (§12.2). */
    volatile double occupancy;
    volatile int queuedGauge;
    /** Pump-side reports collected INSIDE the ladder (which runs under Xaero's
     *  renderThreadPauseSync monitor) and drained by {@link #pump} AFTER the
     *  ladder returns — up to a whole queue's worth on a world-id change, and an
     *  un-stamp burst must not run under a Xaero monitor (review ×2). Main
     *  thread only. Object[]{dimension, chunkX, chunkZ}. */
    final java.util.ArrayList<Object[]> deferredReports = new java.util.ArrayList<>();
    /** Drops reported back to LSS for their bounded re-serve (the kept reporter
     *  path — stale-dimension drops always, governed drops under §12). */
    final AtomicLong dropsReported = new AtomicLong();
    /** Rotating drain start (the IncomingRequestRouter M4 precedent): without it a
     *  permanently-deferring queue prefix starves committable entries forever. */
    int drainRotation; // main thread only
    /** Regions awaiting their Xaero load as PROBED by the last pump — queued buckets
     *  PLUS (since WI-3) owed regions with no queued bytes, i.e. the whole grant input;
     *  a diag gauge, and a lower bound under budget truncation (buckets the commit
     *  loop never reached are unknown). */
    volatile int regionsWaiting;

    /** Guards {@link #owed} and its gauges. Never nested inside {@link #queueLock}
     *  or any Xaero monitor by the DECODE thread; the pump takes it inside Xaero's
     *  renderPause monitor (as it does queueLock) — one order, no inversion. */
    final Object owedLock = new Object();
    /** Insertion-ordered so the OLDEST debt is evicted first past the cap. */
    final LinkedHashMap<OwedKey, OwedRegion> owed = new LinkedHashMap<>();
    /** The decode-thread evictor's classifier: region keys whose bucket the LAST pump
     *  saw awaiting its Xaero load — published by the pump (Xaero state is main-
     *  thread + region-monitor only; the evictor can read neither). A region absent
     *  here is UNKNOWN and takes today's governed report — fail toward reporting. */
    volatile java.util.Set<Long> awaitingRegions = java.util.Set.of();
    final AtomicLong owedReported = new AtomicLong();
    /** Debts dropped UNREPORTED: the 256-region cap's oldest-first eviction and the
     *  world-change overflow past MAX_QUEUE reports — the silent-loss class, metered so
     *  {@code owed=} falling with {@code owed_reported=} flat is readable. */
    final AtomicLong owedEvicted = new AtomicLong();
    volatile int owedGauge;        // positions, all regions (incremental, under owedLock)
    volatile int owedRegionsGauge; // regions
    int owedRotation; // main thread only (the probe pass rotates like the drain)
    int owedIdleSkips;

    // An origin follows one bounded queue/debt item, never a lookup of the current client.
    final AtomicLong acquisitionGeneration = new AtomicLong();
    volatile boolean retiringAcquisition;
    final Object acquisitionLock = new Object();

    void retireAcquisitionWork() {
        synchronized (this.acquisitionLock) {
            this.retiringAcquisition = true;
            this.acquisitionGeneration.incrementAndGet();
            this.clearQueue();
            this.clearOwed();
            this.awaitingRegions = java.util.Set.of();
            this.regionsWaiting = 0;
            this.retiringAcquisition = false;
        }
    }

    /** Enqueue seam (tests build {@link XaeroTileExtractor.PreparedTile}s directly). */
    void offerPrepared(Object dimension, XaeroTileExtractor.PreparedTile tile) {
        this.offerPrepared(dimension, tile, this.session.new Origin());
    }

    void offerPrepared(Object dimension, XaeroTileExtractor.PreparedTile tile, Origin origin) {
        int chunkX = tile.chunkX();
        int chunkZ = tile.chunkZ();
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        int bytes = this.session.approxBytes(tile);
        java.util.ArrayList<Object[]> evictedOut = null;
        boolean accepted = false;
        synchronized (this.queueLock) {
            // Teardown may have overtaken extraction; never enqueue into a dead session.
            if (!origin.active() || this.session.dead || !this.session.enabled.getAsBoolean() || !this.session.sessionActive.getAsBoolean()) {
                origin.close();
                return;
            }
            var existing = this.queue.get(key);
            if (bytes > this.session.maxQueueBytes || this.session.maxQueue <= 0) {
                // A refused replacement must not leave older bytes queued while owing
                // the same position. Remove only its own old entry, not unrelated work.
                if (existing != null) {
                    this.queue.remove(key);
                    this.queuedBytes -= existing.bytes;
                    if (existing.dimension == dimension) existing.origin.close();
                    if (existing.dimension != dimension) {
                        this.session.droppedStale.incrementAndGet();
                        evictedOut = new java.util.ArrayList<>();
                        evictedOut.add(new Object[]{existing.dimension, key, existing.origin, Boolean.TRUE});
                    }
                }
                this.session.droppedOverflow.incrementAndGet();
                if (evictedOut == null) evictedOut = new java.util.ArrayList<>();
                evictedOut.add(new Object[]{dimension, key, origin});
            } else {
                boolean replaced = existing != null && existing.dimension == dimension;
                if (replaced) {
                    // Keep Entry identity: an in-flight commit's compare-and-remove
                    // must see the replacement tile and retain these fresher bytes.
                    this.queuedBytes += bytes - existing.bytes;
                    existing.origin.close();
                    existing.origin = origin;
                    existing.tile = tile;
                    existing.bytes = bytes;
                    existing.ladderReadyDeferrals = 0;
                } else if (existing != null) {
                    this.queuedBytes -= existing.bytes;
                    this.queue.remove(key);
                    this.session.droppedStale.incrementAndGet();
                    evictedOut = new java.util.ArrayList<>();
                    evictedOut.add(new Object[]{existing.dimension, key, existing.origin, Boolean.TRUE});
                }
                var it = this.queue.entrySet().iterator();
                while (it.hasNext() && (this.queue.size() + (replaced ? 0 : 1) > this.session.maxQueue
                        || this.queuedBytes + (replaced ? 0 : bytes) > this.session.maxQueueBytes)) {
                    var evicted = it.next();
                    if (replaced && evicted.getKey().longValue() == key) continue;
                    this.queuedBytes -= evicted.getValue().bytes;
                    it.remove();
                    this.session.droppedOverflow.incrementAndGet();
                    if (evictedOut == null) evictedOut = new java.util.ArrayList<>();
                    evictedOut.add(new Object[]{evicted.getValue().dimension, evicted.getKey(), evicted.getValue().origin});
                }
                if (!replaced) {
                    this.queuedBytes += bytes;
                    this.queue.put(key, new Entry(dimension, tile, bytes, origin));
                }
                accepted = true;
            }
            this.updateOccupancyLocked();
        }
        // Only retained fresh bytes pay a debt. Refusals above keep it until recovery.
        if (accepted) this.forgetOwed(dimension, key);
        if (evictedOut != null) {
            for (var e : evictedOut) {
                long k = (Long) e[1];
                Origin evictedOrigin = (Origin) e[2];
                if (e.length > 3) {
                    // stale-dimension replacement: unconditional (correctness)
                    this.reportDropped(e[0], (int) (k >> 32), (int) k, evictedOrigin);
                } else if (!this.shedToOwed(e[0], k, evictedOrigin)) {
                    // WI-3: an evicted tile whose region is awaiting its load, or
                    // any eviction under the WEDGED full-rate stream (silent
                    // permanent holes before), is OWED; the rest keep today's
                    // governed report.
                    this.reportDroppedIfGoverned(e[0], (int) (k >> 32), (int) k, evictedOrigin);
                }
            }
        }
    }

    /**
     * Owe a shed position instead of reporting it, when the writer provably cannot
     * take its bytes yet: its region was AWAITING its Xaero load at the last pump, or
     * the stream is wedge-released (the writer is stuck; a report would re-serve into
     * the same shed — silent holes until now). Governed only: with backpressure off
     * the doctrine is "drops stay silent" and owing would hold debt nobody releases.
     * Never under {@link #queueLock}. @return true if owed (caller must not report).
     */
    boolean shedToOwed(Object dimension, long packedChunk, Origin origin) {
        if (!this.session.backpressureEnabled.getAsBoolean()) return false;
        long regionKey = this.session.regionKeyOfPacked(packedChunk);
        if (!this.session.haltWedged && !this.awaitingRegions.contains(regionKey)) return false;
        var key = new OwedKey(dimension, regionKey);
        synchronized (this.owedLock) {
            if (!origin.active()) { origin.close(); return true; }
            var region = this.owedRegionLocked(key);
            if (region.positions.add(packedChunk)) this.owedGauge++;
            this.replaceOwedOrigin(region, packedChunk, origin);
        }
        return true;
    }

    void replaceOwedOrigin(OwedRegion region, long packed, Origin origin) {
        Origin previous = region.origins.put(packed, origin);
        if (previous != null && previous != origin) previous.close();
    }

    void discardDeferredReports() {
        for (var report : this.deferredReports) ((Origin) report[3]).close();
        this.deferredReports.clear();
    }

    /** The region's debt record, created (evicting the oldest past the cap) if absent.
     *  Caller holds {@link #owedLock}. */
    OwedRegion owedRegionLocked(OwedKey key) {
        var region = this.owed.get(key);
        if (region == null) {
            while (!this.owed.isEmpty() && this.owed.size() >= this.session.maxOwedRegions) {
                // Oldest debt first — closest to its TTL, and its positions were
                // already counted dropped at eviction; unreported, but METERED.
                var it = this.owed.entrySet().iterator();
                var oldest = it.next().getValue();
                it.remove();
                this.owedGauge -= oldest.size();
                this.owedEvicted.addAndGet(oldest.size());
                oldest.origins.values().forEach(Origin::close);
            }
            region = new OwedRegion(this.session.nowMillis(), this.acquisitionGeneration.get());
            this.owed.put(key, region);
            this.owedRegionsGauge = this.owed.size();
        }
        return region;
    }

    /** Pay one position's debt (a fresh offer). Never under {@link #queueLock}. */
    void forgetOwed(Object dimension, long packedChunk) {
        if (this.owedRegionsGauge == 0) return; // the common case: no debt at all
        var key = new OwedKey(dimension, this.session.regionKeyOfPacked(packedChunk));
        synchronized (this.owedLock) {
            var region = this.owed.get(key);
            if (region == null) return;
            boolean a = region.positions.remove(packedChunk);
            boolean b = region.busyTiles.remove(packedChunk); // both: never leave a tile-scoped twin
            if (!a && !b) return;
            this.owedGauge -= (a ? 1 : 0) + (b ? 1 : 0);
            Origin origin = region.origins.remove(packedChunk);
            if (origin != null) origin.close();
            if (region.isEmpty()) {
                this.owed.remove(key);
                this.owedRegionsGauge = this.owed.size();
            }
        }
    }

    /** Drop every debt, unreported (session end, world-id change — the map those
     *  tiles belonged to is gone). Any thread. */
    void clearOwed() {
        synchronized (this.owedLock) {
            for (var region : this.owed.values()) region.origins.values().forEach(Origin::close);
            this.owed.clear();
            this.owedGauge = 0;
            this.owedRegionsGauge = 0;
        }
    }

    /**
     * The WORLD-ID-change clear (§12.1(c) for the owed set): those positions' stamps are
     * set and their bytes are gone, so — like the queue's tiles — they are REPORTED
     * (collected into {@link #deferredReports}, drained outside the monitors) or a
     * return to that world would never re-declare them; bounded to {@code maxQueue}
     * reports, the rest counted {@code owed_evicted}. Main thread (the ladder).
     */
    int clearOwedCollectingReports() {
        synchronized (this.owedLock) {
            int reported = 0;
            for (var e : this.owed.entrySet()) {
                Object dimension = e.getKey().dimension();
                var region = e.getValue();
                for (var set : new it.unimi.dsi.fastutil.longs.LongOpenHashSet[]{region.positions, region.busyTiles}) {
                    var it = set.iterator();
                    while (it.hasNext()) {
                        long packed = it.nextLong();
                        if (reported < this.session.maxQueue) {
                            this.deferredReports.add(new Object[]{dimension, (int) (packed >> 32), (int) packed, region.origins.get(packed)});
                            reported++;
                        } else {
                            this.owedEvicted.incrementAndGet();
                            region.origins.get(packed).close();
                        }
                    }
                }
            }
            this.owed.clear();
            this.owedGauge = 0;
            this.owedRegionsGauge = 0;
            this.owedReported.addAndGet(reported);
            return reported;
        }
    }

    /**
     * The owed-region PROBE pass (main thread, inside the ladder's renderPause
     * monitor, right before the grant phase): owed regions have no queued bytes, so
     * neither the bucket drain nor the grant phase would ever see them — this pass
     * rotates through up to {@link #owedProbesPerPump} same-dimension owed regions
     * and (a) RELEASES the debt of a region that is loaded AND resting — its
     * positions are reported (≤ {@link #owedReportsPerPump} per pump, collected into
     * {@link #deferredReports} so the un-stamps run outside every Xaero monitor)
     * unless the stream is WEDGED (a burst of re-declarations must not join the
     * full-rate release) — the client re-declares, the server re-serves, and the
     * bytes land in a queue whose region can take them; (b) feeds an UNLOADED owed
     * region into the grant phase's window (classified exactly like the commit
     * probe) so a sparse scatter actually gets its regions loaded; (c) reports a
     * region's debt ONCE at the TTL (governed) or discards it (ungoverned/foreign
     * dimension). A position that is back in the queue by release time is simply
     * forgotten — the invariant's belt for a multi-decode-thread race.
     */
    void probeOwed(Object mp, Object dimensionId, List<WaitingRegion> waiting) {
        List<OwedKey> keys;
        synchronized (this.owedLock) {
            if (this.owed.isEmpty()) return;
            keys = new ArrayList<>(this.owed.keySet());
        }
        var waitingKeys = new java.util.HashSet<Long>();
        for (var w : waiting) waitingKeys.add(w.regionKey());
        long now = this.session.nowMillis();
        boolean governed = this.session.backpressureEnabled.getAsBoolean();
        int reportBudget = this.session.owedReportsPerPump;
        int probes = 0;
        int size = keys.size();
        int start = Math.floorMod(this.owedRotation++, size);
        for (int n = 0; n < size && probes < this.session.owedProbesPerPump && reportBudget > 0; n++) {
            OwedKey key = keys.get((start + n) % size);
            OwedRegion region;
            int debt;
            boolean expired;
            synchronized (this.owedLock) {
                region = this.owed.get(key);
                if (region == null) continue;
                debt = region.size();
                expired = now - region.firstOwedMillis > this.session.owedTtlMillis;
            }
            if (key.dimension() != dimensionId) {
                // Foreign dimension: never probed (the map's region state is the
                // CURRENT dimension's), never reported (a foreign report is a cache
                // deletion per position in the manager); the debt waits for a return
                // within the TTL and is discarded silently at it.
                if (expired) this.discardOwed(key, region);
                continue;
            }
            probes++;
            boolean ready = false;
            Outcome awaitingVerdict = null;
            Object xaeroRegion = null;
            try {
                int regionX = (int) (key.regionKey() >> 32);
                int regionZ = (int) key.regionKey();
                xaeroRegion = this.session.h.getLeafMapRegion.invoke(mp, SURFACE_LAYER,
                        regionX, regionZ, true);
                if (xaeroRegion != null) {
                    synchronized (xaeroRegion) {
                        byte loadState = (byte) this.session.h.getLoadState.invoke(xaeroRegion);
                        if (loadState == 2) {
                            ready = (boolean) this.session.h.isResting.invoke(xaeroRegion);
                        } else if ((boolean) this.session.h.canRequestReload.invoke(xaeroRegion)) {
                            awaitingVerdict = Outcome.AWAITING_REQUESTABLE;
                        } else {
                            awaitingVerdict = loadState == 3 ? Outcome.AWAITING_PARKED
                                    : Outcome.AWAITING_IN_FLIGHT;
                        }
                    }
                }
            } catch (Throwable t) {
                if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                this.session.noteFailure(t);
                return;
            }
            if (ready || expired) {
                if (!governed) {
                    this.discardOwed(key, region); // ungoverned: drops stay silent (§12.8 doctrine)
                    continue;
                }
                // Hold while the stream is wedge-released (a re-declaration burst must
                // not join it) and — for READY releases — while the queue is at its
                // HALT occupancy (release means "the writer can take it"; the queue
                // must have room too). The TTL release bypasses the occupancy hold: a
                // permanently full queue must not turn the debt back into a silent hole.
                if (this.session.haltWedged || (!expired && this.occupancy >= BP_HALT_OCCUPANCY)) continue;
                if (expired) {
                    synchronized (this.owedLock) {
                        region.firstOwedMillis = now; // one report per TTL, never a pass-through
                    }
                }
                reportBudget -= this.releaseOwed(key, region, reportBudget, xaeroRegion, expired);
            } else if (awaitingVerdict != null && !waitingKeys.contains(key.regionKey())) {
                waiting.add(new WaitingRegion(key.regionKey(), debt, awaitingVerdict));
            }
        }
    }

    /**
     * Report up to {@code budget} of one region's owed positions (collected for the
     * outside-the-monitor drain); returns how many were taken. Region-scoped debts go
     * first; tile-scoped ones only when their tile chunk is ready (loadState 2 and no
     * PBO download pending — the commit's own DEFERRED_TILE predicate; a missing tile
     * chunk is ready, the commit creates it) or at the TTL.
     */
    int releaseOwed(OwedKey key, OwedRegion region, int budget, Object xaeroRegion,
                            boolean expired) {
        var taken = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<Origin>();
        long[] busy;
        synchronized (this.owedLock) {
            if (this.owed.get(key) != region || region.generation != this.acquisitionGeneration.get()) return 0;
            var it = region.positions.iterator();
            while (it.hasNext() && taken.size() < budget) {
                long packed = it.nextLong();
                taken.put(packed, region.origins.get(packed));
                it.remove();
                if (!region.busyTiles.contains(packed)) region.origins.remove(packed);
                this.owedGauge--; // removal and accounting share the same ownership check
            }
            busy = taken.size() < budget && !region.busyTiles.isEmpty()
                    ? region.busyTiles.toLongArray() : null;
        }
        if (busy != null) {
            // Xaero probes stay outside owedLock: teardown can detach this record here.
            var ready = new it.unimi.dsi.fastutil.longs.LongArrayList();
            for (long packed : busy) {
                if (ready.size() + taken.size() >= budget) break;
                if (expired || this.tileChunkReady(xaeroRegion, packed)) ready.add(packed);
            }
            synchronized (this.owedLock) {
                if (this.owed.get(key) == region && region.generation == this.acquisitionGeneration.get()) {
                    for (int i = 0; i < ready.size(); i++) {
                        long packed = ready.getLong(i);
                        if (region.busyTiles.remove(packed)) {
                            taken.put(packed, region.origins.remove(packed));
                            this.owedGauge--;
                        }
                    }
                }
            }
        }
        synchronized (this.owedLock) {
            if (region.isEmpty() && this.owed.remove(key, region)) this.owedRegionsGauge = this.owed.size();
        }
        int released = 0;
        for (var entry : taken.long2ObjectEntrySet()) {
            long packed = entry.getLongKey();
            Origin origin = entry.getValue();
            if (!origin.active()) { origin.close(); continue; }
            synchronized (this.queueLock) {
                var queued = this.queue.get(packed);
                if (queued != null && queued.dimension == key.dimension()) {
                    origin.close();
                    continue;
                }
            }
            this.deferredReports.add(new Object[]{key.dimension(), (int) (packed >> 32), (int) packed, origin});
            released++;
        }
        this.owedReported.addAndGet(released);
        return taken.size();
    }

    /** The commit's DEFERRED_TILE predicate, read for one owed position (region
     *  monitor; a throw reads as not-ready — the debt simply waits). */
    boolean tileChunkReady(Object xaeroRegion, long packedChunk) {
        if (xaeroRegion == null) return false;
        int tileChunkX = ((int) (packedChunk >> 32)) >> 2;
        int tileChunkZ = ((int) packedChunk) >> 2;
        try {
            synchronized (xaeroRegion) {
                Object tileChunk = this.session.h.regionGetChunk.invoke(xaeroRegion, tileChunkX & 7, tileChunkZ & 7);
                if (tileChunk == null) return true;
                if ((int) this.session.h.tileChunkGetLoadState.invoke(tileChunk) != 2) return false;
                return !(boolean) this.session.h.shouldDownloadFromPBO.invoke(
                        this.session.h.getLeafTexture.invoke(tileChunk));
            }
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            return false;
        }
    }

    void discardOwed(OwedKey key, OwedRegion region) {
        synchronized (this.owedLock) {
            if (this.owed.remove(key, region)) { // identity-checked (see releaseOwed)
                region.origins.values().forEach(Origin::close);
                this.owedGauge -= region.size();
                this.owedRegionsGauge = this.owed.size();
            }
        }
    }

    /** Recompute the occupancy mirror. Caller holds {@link #queueLock}. */
    void updateOccupancyLocked() {
        this.queuedGauge = this.queue.size();
        double byBytes = this.session.maxQueueBytes <= 0 ? 1.0
                : (double) this.queuedBytes / this.session.maxQueueBytes;
        double byCount = this.session.maxQueue <= 0 ? 1.0
                : (double) this.queue.size() / this.session.maxQueue;
        this.occupancy = Math.min(1.0, Math.max(byBytes, byCount));
    }

    /** A same-dimension drop's report, gated on §12 governance: with backpressure
     *  OFF (the COMPOSED switch — global #71 and the bridge key) or WEDGE-degraded
     *  (stream flowing, writer stuck — a report would churn re-serves into the
     *  same drop), drops stay silent. §12.8 dropped the old {@code pumpDrainable}
     *  conjunct: a blocked-not-wedged overflow IS reported — the halt the blocked
     *  pump is now reporting defers the re-declaration until after the burst, so
     *  the re-serve lands in a draining queue instead of a churn loop. */
    void reportDroppedIfGoverned(Object dimension, int chunkX, int chunkZ, Origin origin) {
        if (!this.session.backpressureEnabled.getAsBoolean() || this.session.haltWedged) {
            origin.close();
            return;
        }
        this.reportDropped(dimension, chunkX, chunkZ, origin);
    }

    /** Drop the whole queue, unreported (teardowns, toggles, settings-off clears —
     *  reporting those would either race a teardown or re-serve into a state the
     *  user turned off; §12.8 deleted the refusal these clears used to hand over
     *  to). @return how many entries were dropped. */
    int clearQueue() {
        synchronized (this.queueLock) {
            int n = this.queue.size();
            for (var entry : this.queue.values()) entry.origin.close();
            this.queue.clear();
            this.queuedBytes = 0;
            this.updateOccupancyLocked();
            return n;
        }
    }

    /** The WORLD-ID-change clear (§12.1(c)): the queued tiles belong to a previous
     *  world, and the player may return — each position's report is COLLECTED into
     *  {@link #deferredReports} (the caller sits inside Xaero's renderPause
     *  monitor; the pump drains outside it) so its stamp is forgotten and a
     *  return re-declares it. Accepted churn: a same-dimension world change
     *  un-stamps up to a queue's worth (~3k) and re-downloads it — the new map
     *  needs those tiles; ~4 s of re-serves, rare event (recorded §12.6). */
    int clearQueueCollectingReports() {
        synchronized (this.queueLock) {
            int n = this.queue.size();
            for (var e : this.queue.entrySet()) {
                long k = e.getKey();
                this.deferredReports.add(new Object[]{e.getValue().dimension,
                        (int) (k >> 32), (int) k, e.getValue().origin});
            }
            this.queue.clear();
            this.queuedBytes = 0;
            this.updateOccupancyLocked();
            return n;
        }
    }

    /**
     * Remove only if the entry AND its tile are still the ones this pump pass
     * examined — a plain remove would silently delete a fresher tile (or a
     * replacement Entry) the decode thread installed mid-commit (review MINOR:
     * the latest-wins guarantee must survive the commit window). Returns whether
     * the removal actually happened, so drop counters count DROPS, not attempts
     * (3-Opus fold: a survived entry must not re-count every pump).
     */
    boolean removeIfCurrent(Long key, Entry entry, XaeroTileExtractor.PreparedTile tile) {
        synchronized (this.queueLock) {
            var current = this.queue.get(key);
            if (current == entry && entry.tile == tile) {
                this.queuedBytes -= entry.bytes;
                this.queue.remove(key);
                this.updateOccupancyLocked();
                return true;
            }
            return false;
        }
    }

    /** Drain the ladder-collected reports (main thread, no monitors held). */
    void drainDeferredReports() {
        if (this.deferredReports.isEmpty()) return;
        for (var e : this.deferredReports) {
            this.reportDropped(e[0], (Integer) e[1], (Integer) e[2], (Origin) e[3]);
        }
        this.deferredReports.clear();
    }

    /** Owe a deferral-expired tile (pump side; governed only, like every owe) — a
     *  TILE-scoped debt: released once its own tile chunk is ready. */
    // Direct debt seam retained for the owed-set fixture; production transfers an existing origin.
    void oweExpired(Object dimension, long packedChunk, long regionKey) {
        this.oweExpired(dimension, packedChunk, regionKey, this.session.new Origin());
    }

    void oweExpired(Object dimension, long packedChunk, long regionKey, Origin origin) {
        if (!this.session.backpressureEnabled.getAsBoolean()) { origin.close(); return; }
        var key = new OwedKey(dimension, regionKey);
        synchronized (this.owedLock) {
            if (!origin.active()) { origin.close(); return; }
            var region = this.owedRegionLocked(key);
            if (region.busyTiles.add(packedChunk)) this.owedGauge++;
            this.replaceOwedOrigin(region, packedChunk, origin);
        }
    }

    // ---- the kept drop reporter (§12.1: the §18 ledger heal is deleted; the
    // immediate report path below is what makes dimension-switch and governed
    // drops self-heal — client stamps persist per dimension, and only
    // reportIngestFailure un-stamps) ----

    /** Report one dropped position for its bounded re-serve. Contained per report
     *  (an LSS-side throw must never feed the XAERO bridge's death latch); never
     *  called under {@link #queueLock}. */
    void reportDropped(Object dimension, int chunkX, int chunkZ, Origin origin) {
        try {
            if (!origin.active()) return;
            if (origin.handle != null) origin.handle.report();
            else this.session.dropReporter.report(dimension, chunkX, chunkZ);
            this.dropsReported.incrementAndGet();
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            long n = COMMIT_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) LSSLogger.warn("Xaero map bridge: a drop report threw (contained)", t);
        } finally {
            origin.close();
        }
    }


    /** Owed-set key: dimension + region (the End/Nether reuse Overworld region
     *  coords — the {@link PendingKey} lesson). ResourceKeys are interned. */
    record OwedKey(Object dimension, long regionKey) {}

    /** One region's debt: positions shed without bytes, and the age of the oldest.
     *  {@code positions} are REGION-scoped debts (released once the region is loaded
     *  and resting); {@code busyTiles} are TILE-scoped ones (a deferral-expired tile
     *  chunk — released only once ITS tile chunk is ready too, or a region-ready
     *  release would re-serve straight back into the same busy tile and burn a
     *  strike per DEFER_CAP interval, the §12 review's original objection). */
    static final class OwedRegion {
        /** Re-based on every expired release, so a region that keeps taking new
         *  sheds pays ONE report per TTL, never a pass-through. */
        long firstOwedMillis;
        final long generation;
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Origin> origins =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet positions =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet busyTiles =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        OwedRegion(long firstOwedMillis, long generation) {
            this.firstOwedMillis = firstOwedMillis;
            this.generation = generation;
        }

        int size() {
            return this.positions.size() + this.busyTiles.size();
        }

        boolean isEmpty() {
            return this.positions.isEmpty() && this.busyTiles.isEmpty();
        }
    }

    static final class Entry {
        volatile XaeroTileExtractor.PreparedTile tile; // replaced under queueLock (latest wins)
        final Object dimension;
        Origin origin; // replaced with tile under queueLock
        int bytes; // under queueLock
        /** Pump-side (++) with a decode-side reset on tile replace — the race is
         *  benign (one deferral tick lost or kept; the cap is approximate). */
        int ladderReadyDeferrals;

        Entry(Object dimension, XaeroTileExtractor.PreparedTile tile, int bytes, Origin origin) {
            this.dimension = dimension;
            this.tile = tile;
            this.bytes = bytes;
            this.origin = origin;
        }
    }
}
