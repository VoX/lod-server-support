package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.voxel.ColumnTimestampCache;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;

import java.util.Map;
import java.util.UUID;

/**
 * Routes incoming chunk requests through the resolution pipeline for all players:
 * duplicate check → queue-full check → timestamp check → loaded-probe check →
 * slot admission (or rate-limit bounce) → disk/generation submit.
 *
 * <p>Processing-thread-owned; collaborates with its owning {@link OffThreadProcessor}
 * (fixed at construction) for disk submission and loaded-column serialization.
 *
 * @param <PS> the platform-specific player state type
 */
class IncomingRequestRouter<PS extends AbstractPlayerRequestState<?>> {

    private final OffThreadProcessor<PS> processor;
    private final Map<UUID, PS> players;
    private final ColumnTimestampCache timestampCache;
    private final DedupTracker dedupTracker;
    private final boolean diskReadingAvailable;
    private final boolean generationAvailable;
    private final ProcessingContext ctx;

    // Per-cycle state (processing thread only), set by routeAll for the duration of a cycle
    private long cycleNow;

    IncomingRequestRouter(OffThreadProcessor<PS> processor,
                          Map<UUID, PS> players,
                          ColumnTimestampCache timestampCache,
                          DedupTracker dedupTracker,
                          boolean diskReadingAvailable, boolean generationAvailable,
                          ProcessingContext ctx) {
        this.processor = processor;
        this.players = players;
        this.timestampCache = timestampCache;
        this.dedupTracker = dedupTracker;
        this.diskReadingAvailable = diskReadingAvailable;
        this.generationAvailable = generationAvailable;
        this.ctx = ctx;
    }

    void routeAll(TickSnapshot snapshot, long cycleNow) {
        this.cycleNow = cycleNow;
        for (var entry : snapshot.playerDimensions().entrySet()) {
            var state = this.players.get(entry.getKey());
            if (state == null) continue;
            if (!state.supportsVoxelColumns()) continue;

            processIncomingRequests(state, entry.getKey(), entry.getValue(), snapshot);
        }
    }

    private void processIncomingRequests(PS state, UUID playerUuid, String dimension,
                                          TickSnapshot snapshot) {
        var loadedProbes = snapshot.loadedChunkProbes().getOrDefault(playerUuid, Long2ObjectMaps.emptyMap());

        IncomingRequest req;
        while ((req = state.pollIncomingRequest()) != null) {
            this.ctx.diagnostics().incrementRequestRouted();
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (resolvedAsDuplicate(state, playerUuid, req, packed)) continue;
            if (sendQueueFull(state, snapshot)) break;

            if (resolvedFromTimestamp(state, playerUuid, req, packed, dimension)) continue;

            // Check loaded probes before admission — in-memory hits don't need a disk/gen slot
            if (resolvedFromLoadedProbe(state, playerUuid, req, packed, loadedProbes, dimension)) continue;

            RequestType type = req.clientTimestamp() == 0 ? RequestType.GENERATION : RequestType.SYNC;
            submitToDiskOrGeneration(state, playerUuid, req, packed, dimension, type);
        }
    }

    /**
     * Returns true if the request is a known duplicate (already served or in-flight).
     *
     * <p>The diskReadDone set records <em>resolution</em>, not <em>delivery</em> — a
     * payload can still be lost between the two (send-failure queue drop, client decode
     * error, consumer rejection). A ts&gt;0 re-request comes from a client that HAS data
     * for the position, so answering up-to-date from the set is honest. A ts&le;0
     * re-request is the client declaring it has nothing: answering up-to-date would seal
     * a permanent invisible hole (the open-to-LAN transition bug). Instead, bounce with
     * rate-limited while the payload is still in the send pipeline, or clear the done-bit
     * and re-resolve once it isn't.
     *
     * <p>Accepted race: a timeout re-ask that crosses its own payload's delivery (sent and
     * decremented between the client's send and this check) re-resolves once redundantly —
     * one extra serve per crossed retry, after which the client holds ts&gt;0 and converges.
     */
    private boolean resolvedAsDuplicate(PS state, UUID playerUuid, IncomingRequest req, long packed) {
        if (state.hasDiskReadDone(req.cx(), req.cz())) {
            if (req.clientTimestamp() > 0) {
                this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed, state));
                return true;
            }
            if (state.hasEnqueuedColumn(packed)) {
                this.ctx.sendActions().add(new SendAction.RateLimited(playerUuid, packed, state));
                this.ctx.diagnostics().incrementRateLimited(
                        req.clientTimestamp() == 0 ? RequestType.GENERATION : RequestType.SYNC);
                return true;
            }
            state.clearDiskReadDone(packed);
            this.ctx.diagnostics().incrementReResolved();
        }
        if (state.hasPendingRequest(req.cx(), req.cz())) {
            this.ctx.diagnostics().incrementSkippedDuplicate();
            return true;
        }
        return false;
    }

    /** Returns true if the send queue is full — caller should break the loop. */
    private boolean sendQueueFull(PS state, TickSnapshot snapshot) {
        if (snapshot.maxSendQueueSize() > 0
                && state.getSendQueueSize() >= snapshot.maxSendQueueSize()) {
            this.ctx.diagnostics().incrementQueueFull();
            return true;
        }
        return false;
    }

    /** Returns true if resolved from an in-memory loaded chunk probe. */
    private boolean resolvedFromLoadedProbe(PS state, UUID playerUuid, IncomingRequest req, long packed,
                                             Long2ObjectMap<LoadedColumnData> probes, String dimension) {
        var probe = probes.get(packed);
        if (probe == null) return false;

        boolean sent = this.processor.enqueueLoadedColumn(state, probe, this.cycleNow,
                this.ctx.sequence().next(), dimension);
        if (!sent) {
            this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed, state));
        }
        state.markDiskReadDone(req.cx(), req.cz());
        this.ctx.diagnostics().incrementInMemory();
        return true;
    }

    /**
     * Returns true if the column is up-to-date based on timestamp cache.
     * Only sends ColumnUpToDate — no data is served from this cache.
     */
    private boolean resolvedFromTimestamp(PS state, UUID playerUuid, IncomingRequest req,
                                           long packed, String dimension) {
        if (req.clientTimestamp() <= 0) return false;

        long cachedTs = this.timestampCache.get(dimension, packed);
        if (cachedTs > 0 && cachedTs <= req.clientTimestamp()) {
            state.markDiskReadDone(req.cx(), req.cz());
            this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed, state));
            this.ctx.diagnostics().incrementUpToDate();
            return true;
        }

        return false;
    }

    /** Admit into the slot for the route and submit to disk reader (disk-first for both SYNC and
     *  GENERATION when disk is available) or to the generation service. A full slot bounces the
     *  request with RateLimited; the client retries on a later scan. */
    private void submitToDiskOrGeneration(PS state, UUID playerUuid, IncomingRequest req,
                                           long packed, String dimension, RequestType type) {
        if (type == RequestType.SYNC || this.diskReadingAvailable) {
            // Route through disk reader (with cross-player dedup)
            if (!state.tryAdmit(new PendingRequest(req.cx(), req.cz(), type, SlotType.SYNC_ON_LOAD))) {
                rateLimit(state, playerUuid, req, packed, type, dimension);
                return;
            }
            long order = this.ctx.sequence().next();
            boolean attached = this.dedupTracker.tryAttachOrCreate(packed, dimension, playerUuid, order);
            if (!attached && !this.processor.submitDiskRead(playerUuid, dimension, req.cx(), req.cz(), order)) {
                // Submit was a no-op (e.g. the dimension's level isn't registered yet). Unwind the
                // pending entry (which frees the slot) and the dedup group so they aren't leaked,
                // and tell the client we couldn't serve this position so it re-requests later.
                this.dedupTracker.removeGroup(packed, dimension);
                state.removePendingByPosition(req.cx(), req.cz());
                this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, packed, state));
                return;
            }
            this.ctx.diagnostics().incrementDiskQueued();
        } else if (this.generationAvailable) {
            // No disk reader — direct generation (type is GENERATION here by the branch above)
            if (!state.tryAdmit(new PendingRequest(req.cx(), req.cz(), type, SlotType.GENERATION))) {
                rateLimit(state, playerUuid, req, packed, type, dimension);
                return;
            }
            this.ctx.generationTicketRequests().add(
                    new OffThreadProcessor.GenerationTicketRequest(playerUuid, req.cx(), req.cz(),
                            dimension, this.ctx.sequence().next()));
        } else {
            // No disk reader AND no generation — can't serve
            this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, packed, state));
        }
    }

    private void rateLimit(PS state, UUID playerUuid, IncomingRequest req, long packed,
                            RequestType type, String dimension) {
        this.ctx.sendActions().add(new SendAction.RateLimited(playerUuid, packed, state));
        this.ctx.diagnostics().incrementRateLimited(type);
        if (LSSLogger.isDebugEnabled()) {
            LSSLogger.debug("Rate-limited " + playerUuid + " (" + type + "): slot full"
                    + " for chunk [" + req.cx() + ", " + req.cz() + "] in " + dimension);
        }
    }
}
