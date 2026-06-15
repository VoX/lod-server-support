package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;

import java.util.function.IntSupplier;
import java.util.function.LongPredicate;

/**
 * Expanding Chebyshev ring scanner that discovers chunk positions to request.
 * Owns the whole scan policy — 20-tick cadence, skip-one-scan rate-limit backoff,
 * and the budget computation with its queue-pressure and vanilla-load scales —
 * and writes accepted positions directly into the {@link RequestQueue} that
 * {@link LodRequestManager} drip-feeds to the server every tick.
 */
class SpiralScanner {
    /** Scan budget multiplier relative to server concurrency limit. */
    private static final int BUDGET_MULTIPLIER = 4;

    private SessionConfigS2CPayload sessionConfig;

    private int confirmedRing = 0;
    private int scanRing = 0;
    private int scanTickCounter = LSSConstants.TICKS_PER_SECOND - 1; // starts at max so first scan fires immediately on join
    private int missingVanillaChunks = Integer.MAX_VALUE;
    private boolean skipNextScan;

    // Last scan budget tracking
    private int lastBudget;
    private int lastSyncQueued;
    private int lastGenQueued;

    // Cached Voxy view distance — rechecked once per second (20 ticks)
    private int cachedVoxyDistance = -1; // -1 = not present
    private int voxyDistanceStaleness = 0;

    /** Set once per session, alongside {@link #reset()}. */
    void setConfig(SessionConfigS2CPayload sessionConfig) {
        this.sessionConfig = sessionConfig;
    }

    /** A rate-limited response arrived — back off by skipping the next scan. */
    void noteRateLimited() {
        this.skipNextScan = true;
    }

    /**
     * Advance the scan cadence and, when it fires, run a budgeted ring scan that writes
     * accepted positions into the queue (committing only when something was accepted, so
     * an undrained remainder keeps draining after an empty scan).
     *
     * @param missingVanilla evaluated only when the cadence fires and the skip flag is clear
     * @return -1 when the cadence did not fire this tick; otherwise the number of queued
     *         positions (0 for a skipped or empty scan)
     */
    int maybeScan(int playerCx, int playerCz, int viewDistance,
                  int columnQueueSize, int columnQueueHaltThreshold,
                  IntSupplier missingVanilla,
                  ColumnStateMap columns, LongPredicate isInFlight,
                  RequestQueue queue) {
        if (++this.scanTickCounter < LSSConstants.TICKS_PER_SECOND) return -1;
        this.scanTickCounter = 0;

        if (this.skipNextScan) {
            this.skipNextScan = false;
            return 0;
        }

        this.missingVanillaChunks = missingVanilla.getAsInt();

        // Compute scan budget: base × queue-pressure-scale × vanilla-load-scale
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

        if (budget <= 0) return 0;

        int count = scan(playerCx, playerCz, viewDistance, columns, isInFlight, queue, budget);
        if (count > 0) {
            queue.commit(count);
        }
        return count;
    }

    /**
     * Scans expanding Chebyshev rings for positions that need requesting.
     * Skips fully-confirmed rings (all positions satisfied) without spending budget,
     * and continues across multiple rings until budget is exhausted.
     */
    private int scan(int playerCx, int playerCz, int viewDistance,
                     ColumnStateMap columns, LongPredicate isInFlight,
                     RequestQueue queue, int budget) {
        int exclusionRadius = viewDistance;
        int lodDistance = getEffectiveLodDistance();
        boolean generationEnabled = this.sessionConfig.generationEnabled();

        queue.ensureCapacity(budget);
        int count = 0;

        int genCap = this.sessionConfig.generationConcurrencyLimitPerPlayer() * BUDGET_MULTIPLIER;

        int[] chunkCoords = new int[2];
        int localScanRing = -1;
        int syncQueued = 0;
        int genQueued = 0;

        if (columns.hasRetries()) {
            this.confirmedRing = 0;
        }

        int localConfirmedRing = this.confirmedRing;

        outer:
        for (int r = localConfirmedRing; r <= lodDistance; r++) {
            boolean ringFullySatisfied = true;
            int ringSize = 8 * r;
            for (int i = 0; i < ringSize; i++) {
                if (count >= budget) { ringFullySatisfied = false; break outer; }

                ringIndexToCoord(r, i, playerCx, playerCz, chunkCoords);
                int cx = chunkCoords[0];
                int cz = chunkCoords[1];

                long packed = PositionUtil.packPosition(cx, cz);

                // Exclude chunks vanilla RENDERS, replicating its own view-distance test
                // (ChunkTrackingView.isInViewDistance: a 1-chunk-buffered Euclidean radius). The
                // render SQUARE's corners fall OUTSIDE this rounded view — e.g. at viewDistance 12
                // the corner (12,12) is buffered-distance 11^2+11^2=242 vs 12^2=144 — so vanilla
                // never renders them, and the old Chebyshev exclusion (max(|dx|,|dz|) <= vd) left
                // them blank until the player moved. They now fall through to LOD. Like an in-flight
                // skip, an excluded (in-view) chunk does NOT break ring confirmation. Loop-safe:
                // DirtyContentFilter suppresses metadata-only (inhabitedTime) re-saves of a served
                // corner, so re-serving one cannot revive the old re-request loop.
                int adx = Math.max(0, Math.abs(cx - playerCx) - 1);
                int adz = Math.max(0, Math.abs(cz - playerCz) - 1);
                if ((long) adx * adx + (long) adz * adz < (long) exclusionRadius * exclusionRadius) continue;

                // In-flight positions are satisfied — skip without breaking ring confirmation
                if (isInFlight.test(packed)) continue;

                long ts = columns.classify(packed, generationEnabled);
                if (ts == ColumnStateMap.SATISFIED) continue;
                if (ts == 0L && genQueued >= genCap) { ringFullySatisfied = false; continue; }

                ringFullySatisfied = false;
                queue.put(count, packed, ts);
                count++;
                if (ts == 0) genQueued++; else syncQueued++;
                if (localScanRing < r) localScanRing = r;
            }

            // Contiguous prefix only: confirming a satisfied OUTER ring while an inner ring still
            // has unsatisfied positions (gen-cap skips, or an uncovered corner hole) would start
            // every later scan past the inner ring — a permanent LOD hole for a stationary player.
            if (ringFullySatisfied && localConfirmedRing == r) {
                localConfirmedRing = r + 1;
            }
        }

        this.confirmedRing = localConfirmedRing;
        this.scanRing = localScanRing >= 0 ? localScanRing : localConfirmedRing;
        this.lastBudget = budget;
        this.lastSyncQueued = syncQueued;
        this.lastGenQueued = genQueued;

        return count;
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

    void reset() {
        this.confirmedRing = 0;
        this.scanRing = 0;
        this.scanTickCounter = LSSConstants.TICKS_PER_SECOND - 1;
        this.missingVanillaChunks = Integer.MAX_VALUE;
        this.cachedVoxyDistance = -1;
        this.voxyDistanceStaleness = 0;
        this.skipNextScan = false;
    }

    void resetScanCounter() {
        this.confirmedRing = 0;
        this.scanTickCounter = 0;
    }

    /**
     * Force the next scan to re-walk from the innermost ring (cheaply skipping already-satisfied
     * positions) WITHOUT resetting the scan-tick cadence. Used when a position BELOW the confirmed
     * ring became requestable again — a late not-generated stamp on a position the ring confirmed
     * past while it was in-flight: only a re-walk re-reaches it. Unlike {@link #resetScanCounter}
     * this leaves the cadence alone (a steady not-generated trickle would otherwise debounce scans
     * back indefinitely), and unlike a retry mark it leaves {@link ColumnStateMap#classify} free to
     * correctly PARK the position when generation is disabled instead of looping re-requests.
     */
    void resetConfirmedRing() {
        this.confirmedRing = 0;
    }

    /**
     * A dimension change discards any pending rate-limit backoff — it belonged to the old
     * dimension's load. Deliberately NOT part of resetScanCounter(): the movement and
     * dirty-broadcast paths call that too and must preserve the backoff.
     */
    void clearSkipNextScan() {
        this.skipNextScan = false;
    }

    int getEffectiveLodDistance() {
        int serverDistance = this.sessionConfig.lodDistanceChunks();
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

    int getPruneDistance() {
        return getEffectiveLodDistance() + LSSConstants.LOD_DISTANCE_BUFFER;
    }

    // --- Getters ---

    int getConfirmedRing() { return this.confirmedRing; }
    int getScanRing() { return this.scanRing; }
    int getMissingVanillaChunks() { return this.missingVanillaChunks; }
    int getLastBudget() { return this.lastBudget; }
    int getLastSyncQueued() { return this.lastSyncQueued; }
    int getLastGenQueued() { return this.lastGenQueued; }
}
