package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;

import java.util.function.IntSupplier;

/**
 * Expanding Chebyshev ring scanner that produces the client's want-set: every position it
 * still wants, closest-first, written straight into {@link LodRequestManager}'s send buffers
 * and shipped whole in the same tick. Owns the scan policy — the 20-tick cadence and the
 * budget with its queue-pressure and vanilla-load scales.
 *
 * <p>The scan does NOT suppress in-flight positions: under want-set semantics the server may
 * silently supersede any not-yet-admitted ask, and the 1 Hz re-declare is the only thing that
 * heals it. An awaited position is therefore an ordinary unsatisfied want-set member, which
 * also means it blocks ring confirmation until its data actually lands.
 */
class SpiralScanner {
    /**
     * Scan budget relative to the server's per-player sync slot cap. 4× leaves the want-set
     * comfortably larger than the in-flight set it now re-declares, so there is always frontier
     * headroom. Raising it buys nothing: the window self-throttles to the serve rate (as heads
     * resolve they classify SATISFIED and drop out), so a bigger window only inflates
     * duplicate-skip traffic. Shared with {@code ServerConfigBase.validate()} via
     * {@link LSSConstants#SCAN_BUDGET_MULTIPLIER}: that clamp reserves
     * {@link LSSConstants#WANT_SET_FRONTIER_RESERVE} frontier positions using this exact multiplier,
     * so the two must never drift — the want-set cap dominating the slot caps is what keeps frontier
     * discovery from starving (Global Constraint #28).
     */
    private static final int BUDGET_MULTIPLIER = LSSConstants.SCAN_BUDGET_MULTIPLIER;

    private SessionConfigS2CPayload sessionConfig;

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

    /** Set once per session, alongside {@link #reset()}. */
    void setConfig(SessionConfigS2CPayload sessionConfig) {
        this.sessionConfig = sessionConfig;
    }

    /**
     * Advance the scan cadence and, when it fires with a nonzero budget, walk the rings
     * and write the complete want-set (closest-first) into {@code posOut}/{@code tsOut}.
     *
     * @param missingVanilla evaluated only when the cadence fires
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
        // The want-set must fit one wire batch: replace semantics tear across frames.
        budget = Math.min(budget, Math.min(LSSConstants.MAX_BATCH_CHUNK_REQUESTS, posOut.length));

        if (budget <= 0) return -1;

        return scan(playerCx, playerCz, viewDistance, columns, posOut, tsOut, budget);
    }

    /**
     * Scans expanding Chebyshev rings for positions that need requesting.
     * Skips fully-confirmed rings (all positions satisfied) without spending budget,
     * and continues across multiple rings until budget is exhausted.
     */
    private int scan(int playerCx, int playerCz, int viewDistance,
                     ColumnStateMap columns,
                     long[] posOut, long[] tsOut, int budget) {
        int exclusionRadius = viewDistance;
        int lodDistance = getEffectiveLodDistance();
        boolean generationEnabled = this.sessionConfig.generationEnabled();

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

                // No in-flight suppression: re-declaration is load-bearing. The server may
                // silently supersede any not-yet-admitted ask; only the 1 Hz re-declare heals
                // that. An awaited position is unsatisfied, so it blocks ring confirmation
                // until its data actually arrives — confirmedRing lags the frontier by the
                // in-flight window, and satisfied positions skip free, so the walk stays cheap.
                long ts = columns.classify(packed, generationEnabled);
                if (ts == ColumnStateMap.SATISFIED) continue;
                if (ts == 0L && genQueued >= genCap) { ringFullySatisfied = false; continue; }

                ringFullySatisfied = false;
                posOut[count] = packed;
                tsOut[count] = ts;
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
