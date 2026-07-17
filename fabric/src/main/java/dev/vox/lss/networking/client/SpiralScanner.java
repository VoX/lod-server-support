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
 * budget with its queue-pressure scale (the vanilla-load scale is retired: server-side
 * priority/throttling owns that protection under v17).
 *
 * <p>The scan does NOT suppress in-flight positions: under want-set semantics the server may
 * silently supersede any not-yet-admitted ask, and the 1 Hz re-declare is the only thing that
 * heals it. An awaited position is therefore an ordinary unsatisfied want-set member, which
 * also means it blocks ring confirmation until its data actually lands.
 */
class SpiralScanner {

    private SessionConfigS2CPayload sessionConfig;

    private int confirmedRing = 0;
    private int scanRing = 0;
    private int scanTickCounter = LSSConstants.TICKS_PER_SECOND - 1; // starts at max so first scan fires immediately on join
    private int missingVanillaChunks = Integer.MAX_VALUE;

    // Last scan budget tracking
    private int lastBudget;
    private int lastQueued;

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
     * @param missingVanilla evaluated only when the cadence fires (diagnostics only)
     * @return -1 when no walk happened this tick (cadence not fired); otherwise the
     *         number of want-set entries written
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

        // Compute scan budget: base × queue-pressure-scale. The base is
        // the ONE want-set budget — a constant; no client budget derives from any server cap
        // (server-owned generation). Raising it buys nothing: the window self-throttles to
        // the serve rate (as heads resolve they classify SATISFIED and drop out), so a bigger
        // window only inflates duplicate-skip traffic. WantSetBudgetInvariantTest pins it
        // above the worst-case in-flight set with frontier headroom and inside one wire batch.
        int budget = LSSConstants.WANT_SET_BUDGET;
        if (columnQueueSize > 0) {
            budget = Math.max(1, Math.round(budget * Math.max(0f, 1f - (float) columnQueueSize / columnQueueHaltThreshold)));
        }
        // The vanilla-load budget scale is GONE (2026-07-17, user call): it was client-side
        // triage of SERVER resources — v17 moved all of that server-side (BACKGROUND/LOW
        // read+gen priority, the adaptive throttle, headroom gates), and its only observable
        // effect was silently stopping LOD during fast travel, the same class of starvation
        // as the removed movement cadence debounce. missingVanillaChunks is still counted
        // for /lss diag and the trace — as a diagnostic, not a lever.
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

        int count = 0;

        int[] chunkCoords = new int[2];
        int localScanRing = -1;
        int queued = 0;

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
                long ts = columns.classify(packed);
                if (ts == ColumnStateMap.SATISFIED) continue;

                ringFullySatisfied = false;
                posOut[count] = packed;
                tsOut[count] = ts;
                count++;
                queued++;
                if (localScanRing < r) localScanRing = r;
            }

            // Contiguous prefix only: confirming a satisfied OUTER ring while an inner ring still
            // has unsatisfied positions (an uncovered corner hole) would start every later scan
            // past the inner ring — a permanent LOD hole for a stationary player.
            if (ringFullySatisfied && localConfirmedRing == r) {
                localConfirmedRing = r + 1;
            }
        }

        this.confirmedRing = localConfirmedRing;
        this.scanRing = localScanRing >= 0 ? localScanRing : localConfirmedRing;
        this.lastBudget = budget;
        this.lastQueued = queued;

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

    /** Movement re-center: re-walk from ring 0 at the new center WITHOUT touching the
     *  cadence. The pre-v17 movement path used {@link #resetScanCounter} (a debounce),
     *  which starved scanning — and with it re-declaration, the want-set's only healer —
     *  for as long as the player crossed a chunk boundary more often than every 20 ticks:
     *  sustained creative flight stopped LOD generation entirely. Under latest-wins
     *  replace semantics a moving client declaring on schedule is the DESIGNED behavior
     *  (stale asks are superseded and re-declared); yielding to vanilla's own chunk
     *  loading during fast travel is SERVER-SIDE read/generation priority's job (the
     *  client-side vanilla-load budget scale is retired), not the cadence's. The confirmed-ring reset stays: the confirmed prefix was computed for
     *  the OLD center, and keeping it would skip never-scanned rings at the new one. */
    void recenter() {
        this.confirmedRing = 0;
    }

    /**
     * Force the next scan to re-walk from the innermost ring (cheaply skipping already-satisfied
     * positions) WITHOUT resetting the scan-tick cadence. Used when a position BELOW the confirmed
     * ring became requestable again while the ring confirmed past it — a dirty mark landing at a
     * terminal outcome (the stale-crossing path): only a re-walk re-reaches it. Unlike
     * {@link #resetScanCounter} this leaves the cadence alone (a steady trickle of terminal
     * answers would otherwise debounce scans back indefinitely).
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
    int getLastQueued() { return this.lastQueued; }
}
