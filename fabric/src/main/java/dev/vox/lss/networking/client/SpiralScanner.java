package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.function.LongPredicate;

/**
 * Expanding Chebyshev ring scanner that discovers chunk positions to request.
 * Populates a queue that {@link LodRequestManager} drip-feeds to the server every tick.
 */
class SpiralScanner {
    /** Scan budget multiplier relative to server concurrency limit. */
    private static final int BUDGET_MULTIPLIER = 4;

    private int confirmedRing = 0;
    private int scanRing = 0;
    private int scanTickCounter = LSSConstants.TICKS_PER_SECOND - 1; // starts at max so first scan fires immediately on join
    private int missingVanillaChunks = Integer.MAX_VALUE;

    // Last scan budget tracking
    private int lastBudget;
    private int lastSyncQueued;
    private int lastGenQueued;

    // Cached Voxy view distance — rechecked once per second (20 ticks)
    private int cachedVoxyDistance = -1; // -1 = not present
    private int voxyDistanceStaleness = 0;

    // Reusable scan buffers (grown as needed)
    private long[] posBuf = new long[0];
    private long[] tsBuf = new long[0];

    /**
     * Result of a scan tick: parallel arrays of positions and timestamps, plus count.
     */
    record ScanResult(long[] positions, long[] timestamps, int count) {}

    /**
     * Increments the scan tick counter. Returns true when 20 ticks have elapsed
     * and a scan should fire.
     */
    boolean advanceScanTick() {
        if (++this.scanTickCounter < LSSConstants.TICKS_PER_SECOND) return false;
        this.scanTickCounter = 0;
        return true;
    }

    /**
     * Returns the base scan budget for the given server concurrency limit.
     */
    static int baseBudget(SessionConfigS2CPayload config) {
        int concurrencyBased = config.syncOnLoadConcurrencyLimitPerPlayer() * BUDGET_MULTIPLIER;
        return Math.min(concurrencyBased, config.syncOnLoadRateLimitPerPlayer());
    }

    /**
     * Scans expanding Chebyshev rings for positions that need requesting.
     * Skips fully-confirmed rings (all positions satisfied) without spending budget,
     * and continues across multiple rings until budget is exhausted.
     */
    ScanResult scan(int playerCx, int playerCz, int viewDistance,
                    Long2LongOpenHashMap columnTimestamps,
                    LongOpenHashSet dirtyColumns,
                    LongOpenHashSet rateLimitRetryPositions,
                    LongOpenHashSet validatedThisSession,
                    LongPredicate isInFlight,
                    SessionConfigS2CPayload sessionConfig,
                    int budget) {
        int exclusionRadius = viewDistance;
        int lodDistance = getEffectiveLodDistance(sessionConfig);

        // Reuse buffers, grow if needed
        if (this.posBuf.length < budget) {
            this.posBuf = new long[budget];
            this.tsBuf = new long[budget];
        }
        long[] posBuf = this.posBuf;
        long[] tsBuf = this.tsBuf;
        int count = 0;

        int genCap = sessionConfig.generationConcurrencyLimitPerPlayer() * BUDGET_MULTIPLIER;

        int exclusionDistSq = exclusionRadius * exclusionRadius;
        int[] chunkCoords = new int[2];
        int localScanRing = -1;
        int syncQueued = 0;
        int genQueued = 0;

        if (!rateLimitRetryPositions.isEmpty()) {
            this.confirmedRing = 0;
        }

        int localConfirmedRing = this.confirmedRing;

        outer:
        for (int r = localConfirmedRing; r <= lodDistance; r++) {
            if (2 * r * r <= exclusionDistSq) { localConfirmedRing = r + 1; continue; }
            if (r == 0) { localConfirmedRing = 1; continue; }

            boolean ringFullySatisfied = true;
            int ringSize = 8 * r;
            for (int i = 0; i < ringSize; i++) {
                if (count >= budget) { ringFullySatisfied = false; break outer; }

                ringIndexToCoord(r, i, playerCx, playerCz, chunkCoords);
                int cx = chunkCoords[0];
                int cz = chunkCoords[1];

                int pdx = cx - playerCx;
                int pdz = cz - playerCz;
                if (pdx * pdx + pdz * pdz <= exclusionDistSq) continue;

                long packed = PositionUtil.packPosition(cx, cz);

                // In-flight positions are satisfied — skip without breaking ring confirmation
                if (isInFlight.test(packed)) continue;

                long stored = columnTimestamps.get(packed);

                // Determine timestamp to send, or skip this position entirely.
                // Priority: unknown > generation > dirty > rate-limit retry > revalidation
                long ts;
                if (stored == -1L) {
                    ts = -1L; // Unknown — sync-on-load first; server generates only on explicit retry
                } else if (stored == 0L && sessionConfig.generationEnabled()) {
                    if (genQueued >= genCap) { ringFullySatisfied = false; continue; }
                    ts = 0L; // Not generated — generation retry
                } else if (dirtyColumns.contains(packed)) {
                    ts = stored; // Server-pushed dirty
                } else if (rateLimitRetryPositions.contains(packed)) {
                    ts = stored; // Rate-limit retry
                } else if (stored > 0 && !validatedThisSession.contains(packed)) {
                    ts = stored; // Cached but not validated this session
                } else {
                    continue; // Position is satisfied
                }

                ringFullySatisfied = false;
                posBuf[count] = packed;
                tsBuf[count] = ts;
                count++;
                if (ts == 0) genQueued++; else syncQueued++;
                if (localScanRing < r) localScanRing = r;
            }

            if (ringFullySatisfied) {
                localConfirmedRing = r + 1;
            }
        }

        this.confirmedRing = localConfirmedRing;
        this.scanRing = localScanRing >= 0 ? localScanRing : localConfirmedRing;
        this.lastBudget = budget;
        this.lastSyncQueued = syncQueued;
        this.lastGenQueued = genQueued;

        return new ScanResult(posBuf, tsBuf, count);
    }

    /**
     * Maps linear index {@code i} (0 to 8r-1) to the chunk coordinates of the
     * i-th border chunk in Chebyshev ring {@code r}. Four edges, 2r points each,
     * clockwise from top-left.
     */
    static void ringIndexToCoord(int r, int i, int centerX, int centerZ, int[] out) {
        int edge = i / (2 * r);
        int pos = i % (2 * r);
        switch (edge) {
            case 0 -> { out[0] = centerX - r + pos; out[1] = centerZ - r; }
            case 1 -> { out[0] = centerX + r;       out[1] = centerZ - r + pos; }
            case 2 -> { out[0] = centerX + r - pos; out[1] = centerZ + r; }
            case 3 -> { out[0] = centerX - r;       out[1] = centerZ + r - pos; }
        }
    }

    void updateMissingVanillaChunks(int count) {
        this.missingVanillaChunks = count;
    }

    void reset() {
        this.confirmedRing = 0;
        this.scanRing = 0;
        this.scanTickCounter = LSSConstants.TICKS_PER_SECOND - 1;
        this.missingVanillaChunks = Integer.MAX_VALUE;
        this.cachedVoxyDistance = -1;
        this.voxyDistanceStaleness = 0;
    }

    void resetScanCounter() {
        this.confirmedRing = 0;
        this.scanTickCounter = 0;
    }

    int getEffectiveLodDistance(SessionConfigS2CPayload sessionConfig) {
        int serverDistance = sessionConfig.lodDistanceChunks();
        int clientDistance = LSSClientConfig.CONFIG.lodDistanceChunks;
        int effective;
        if (clientDistance > 0) {
            effective = Math.min(clientDistance, serverDistance);
        } else {
            effective = serverDistance;
        }
        int voxyDist = getCachedVoxyDistance();
        if (voxyDist > 0 && voxyDist < effective) {
            effective = voxyDist;
        }
        return effective;
    }

    private int getCachedVoxyDistance() {
        if (++this.voxyDistanceStaleness >= LSSConstants.TICKS_PER_SECOND) {
            this.voxyDistanceStaleness = 0;
            var voxyDistance = ModCompat.getVoxyViewDistanceChunks();
            this.cachedVoxyDistance = voxyDistance.isPresent() ? voxyDistance.getAsInt() : -1;
        }
        return this.cachedVoxyDistance;
    }

    int getPruneDistance(SessionConfigS2CPayload sessionConfig) {
        return getEffectiveLodDistance(sessionConfig) + LSSConstants.LOD_DISTANCE_BUFFER;
    }

    // --- Pruning ---

    void pruneOutOfRangeTimestamps(Long2LongOpenHashMap columnTimestamps, RequestMetrics metrics,
                                    int playerCx, int playerCz, int pruneDistance) {
        var iter = columnTimestamps.long2LongEntrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            if (PositionUtil.isOutOfRange(entry.getLongKey(), playerCx, playerCz, pruneDistance)) {
                metrics.onTimestampRemoved(entry.getLongValue());
                iter.remove();
            }
        }
    }

    void pruneOutOfRangePositions(LongOpenHashSet set, int playerCx, int playerCz, int pruneDistance) {
        var iter = set.iterator();
        while (iter.hasNext()) {
            long packed = iter.nextLong();
            if (PositionUtil.isOutOfRange(packed, playerCx, playerCz, pruneDistance)) {
                iter.remove();
            }
        }
    }

    // --- Getters ---

    int getConfirmedRing() { return this.confirmedRing; }
    int getScanRing() { return this.scanRing; }
    int getMissingVanillaChunks() { return this.missingVanillaChunks; }
    int getLastBudget() { return this.lastBudget; }
    int getLastSyncQueued() { return this.lastSyncQueued; }
    int getLastGenQueued() { return this.lastGenQueued; }
}
