package dev.vox.lss.common.processing;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.voxel.ColumnTimestampCache;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * Routes each player's want-set backlog through the resolution pipeline:
 * duplicate check → queue-full check → timestamp check → loaded-probe check →
 * slot admission → disk/generation submit.
 *
 * <p>Want-set replace semantics (protocol v17): each arriving batch REPLACES the backlog
 * wholesale (dropped entries counted as {@code superseded}); an entry that cannot be
 * admitted this cycle — full slot, full disk pool, full send queue — is RETAINED in order
 * rather than bounced, and either routes on a later cycle or is superseded by the next
 * declaration. Nothing on this path answers "try again": the client re-declares every
 * unsatisfied position once per second, which is what makes a silent drop recoverable.
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
            // Stale-snapshot session guard: a dimension change replaced the state after this
            // snapshot was built. Routing the NEW session's requests under the OLD dimension
            // would submit disk reads whose results get dimension-skipped, leaking the pending
            // slots for the whole session. The pending BATCH is left un-taken and the backlog
            // untouched; the next cycle's fresh snapshot routes them under the right
            // dimension. (Null = bare test rig.)
            String registered = state.registeredDimension();
            if (registered != null && !registered.equals(entry.getValue())) continue;

            processIncomingRequests(state, entry.getKey(), entry.getValue(), snapshot);
        }
    }

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
        ArrayList<IncomingRequest> retained = null;
        boolean stopPass = false;

        IncomingRequest req;
        boolean frontierStamped = false;
        while (!stopPass && (req = state.pollBacklog()) != null) {
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            var duplicate = resolvedAsDuplicate(state, playerUuid, req, packed);
            if (duplicate != Duplicate.NO) {
                // In-flight duplicates (pending read/generation, enqueued payload) are
                // UNSATISFIED: the first such entry pins the live frontier so the
                // generation band can never walk away from a starving head. SATISFIED
                // resolutions (the done-bit up_to_date answer here, and the timestamp/
                // probe ladder below) deliberately do not stamp — the frontier advances
                // through them at drain speed (20 Hz), and stamping a satisfied ring
                // would over-gate the true frontier and tick gen_order_gated on
                // FIFO-clean servers, diluting that counter's runaway meaning.
                if (duplicate == Duplicate.IN_FLIGHT && !frontierStamped) {
                    state.stampLiveFrontier(req.cx(), req.cz());
                    frontierStamped = true;
                }
                this.ctx.diagnostics().incrementRequestRouted();
                continue;
            }
            if (sendQueueFull(state, snapshot)) {
                // Stamp the retained head: it passed the duplicate ladder, so it is the
                // nearest possibly-unsatisfied entry this pass. It has NOT passed the
                // timestamp ladder yet, so this can under-estimate the frontier — the
                // safe direction (transient over-gating while the send queue is already
                // saturating delivery), unlike leaving a stale higher stamp in place.
                if (!frontierStamped) {
                    state.stampLiveFrontier(req.cx(), req.cz());
                    frontierStamped = true;
                }
                // Retain (no disposition): the entry stays queued for the next cycle or is
                // superseded by the next replace. queue_full stays a pure event counter,
                // no longer a law A1 term. Stopping the pass keeps order: this entry is
                // re-prepended by restoreBacklog ahead of the un-polled remainder.
                if (retained == null) retained = new ArrayList<>();
                retained.add(req);
                break;
            }
            if (resolvedFromTimestamp(state, playerUuid, req, packed, dimension)) {
                this.ctx.diagnostics().incrementRequestRouted();
                continue;
            }
            // Check loaded probes before admission — in-memory hits don't need a disk/gen slot
            if (resolvedFromLoadedProbe(state, playerUuid, req, packed, loadedProbes, dimension)) {
                this.ctx.diagnostics().incrementRequestRouted();
                continue;
            }

            // First entry needing real work this pass: the live frontier (see the
            // duplicate branch above — in-flight entries stamp it too).
            if (!frontierStamped) {
                state.stampLiveFrontier(req.cx(), req.cz());
                frontierStamped = true;
            }
            // Every request routes the same way — the client no longer classifies sync vs
            // generation (server-owned generation: the disk miss is the generation trigger,
            // and a ts of 0 is just another "no data" shape, as inert as the retired byte 0).
            switch (tryAdmitAndSubmit(state, playerUuid, req, packed, dimension)) {
                case SUBMITTED -> this.ctx.diagnostics().incrementRequestRouted();
                case SLOT_FULL -> {
                    // Dequeue gate: the per-player cap now means "dequeue at most N
                    // concurrently", not "reject above N". Retain in order and KEEP
                    // scanning — entries behind the full slot can still resolve without
                    // one (timestamp ladder, done-bit duplicate, loaded-chunk probe).
                    if (retained == null) retained = new ArrayList<>();
                    retained.add(req);
                }
                case NO_DISK_HEADROOM -> {
                    // The shared reader pool is full — nothing disk-bound can be admitted
                    // this cycle. Retain this entry and STOP the pass: saturation never
                    // reaches the wire (issue #32's root cause, fixed at the source).
                    if (retained == null) retained = new ArrayList<>();
                    retained.add(req);
                    stopPass = true;
                }
            }
        }

        if (retained != null) state.restoreBacklog(retained);
    }

    /**
     * Returns true if the request is a known duplicate (already served or in-flight).
     *
     * <p>The diskReadDone set records <em>resolution</em>, not <em>delivery</em> — a
     * payload can still be lost between the two (send-failure queue drop, client decode
     * error, consumer rejection). A ts&gt;0 re-request comes from a client that HAS data
     * for the position, so answering up-to-date from the set is honest. A ts&le;0
     * re-request is the client declaring it has nothing: answering up-to-date would seal
     * a permanent invisible hole (the open-to-LAN transition bug). Instead, skip silently
     * while the payload is still in the send pipeline (it IS coming — the client's next
     * declaration asks again), or clear the done-bit and re-resolve once it isn't.
     *
     * <p>Accepted race: a re-declaration that crosses its own payload's delivery (sent and
     * decremented between the client's send and this check) re-resolves once redundantly —
     * one extra serve per crossed re-ask, after which the client holds ts&gt;0 and converges.
     */
    /** Duplicate-resolution flavor: SATISFIED answered terminally (must not pin the live
     *  frontier), IN_FLIGHT has work outstanding (pins it — the anti-starvation stamp). */
    enum Duplicate { NO, SATISFIED, IN_FLIGHT }

    private Duplicate resolvedAsDuplicate(PS state, UUID playerUuid, IncomingRequest req, long packed) {
        if (state.hasDiskReadDone(req.cx(), req.cz())) {
            if (req.clientTimestamp() > 0) {
                this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed, state));
                return Duplicate.SATISFIED;
            }
            if (state.hasEnqueuedColumn(packed)) {
                // Delivery honesty, silent form: the column IS already in the send pipeline.
                // Do NOT clear the done-bit while enqueued (a ts<=0 re-ask must not force a
                // redundant re-read of data that is about to arrive) and send nothing — the
                // client re-declares at 1 Hz until the payload lands or is dropped, and a
                // post-drop re-ask falls through to the clearDiskReadDone re-resolution below.
                this.ctx.diagnostics().incrementSkippedDuplicate();
                return Duplicate.IN_FLIGHT;
            }
            state.clearDiskReadDone(packed);
            this.ctx.diagnostics().incrementReResolved();
        }
        if (state.hasPendingRequest(req.cx(), req.cz())) {
            this.ctx.diagnostics().incrementSkippedDuplicate();
            return Duplicate.IN_FLIGHT;
        }
        return Duplicate.NO;
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

        long order = this.ctx.sequence().next();
        boolean allAir = probe.serializedSections() == null || probe.serializedSections().length == 0;
        boolean sent = !allAir
                && this.processor.enqueueLoadedColumn(state, probe, this.cycleNow, order, dimension,
                        dev.vox.lss.common.LSSConstants.COLUMN_SOURCE_IN_MEMORY);
        if (!sent) {
            if (allAir) {
                // Stamp the all-air resolution (the data path stamps inside enqueueLoadedColumn)
                // so future resyncs converge to a warm up_to_date instead of re-resolving —
                // and re-clearing — the same void column every session.
                this.processor.recordAllAirResolution(dimension, packed, this.cycleNow);
            }
            // All-air: a resync client (ts>0) may hold stale content there, so send a clearing
            // 0-section column; a client with nothing (ts<=0) gets up_to_date. An enqueue
            // REJECTION (oversized column) is NOT all-air — clearing would erase the client's
            // stale-but-real terrain; the terminal answer is up_to_date.
            if (allAir && req.clientTimestamp() > 0
                    && this.processor.sendEmptiedColumn(state, req.cx(), req.cz(), dimension, this.cycleNow, order,
                            dev.vox.lss.common.LSSConstants.COLUMN_SOURCE_IN_MEMORY)) {
                // clearing column sent
            } else {
                this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed, state));
            }
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

    private enum AdmitResult { SUBMITTED, SLOT_FULL, NO_DISK_HEADROOM }

    /** Admit into the slot for the route and submit — disk-first always: the disk-read
     *  result is where the server decides generation (a miss escalates in
     *  handleDiskNotFound). A full slot or a full disk pool is NOT an answer — the entry
     *  stays in the backlog and the caller retains it. */
    private AdmitResult tryAdmitAndSubmit(PS state, UUID playerUuid, IncomingRequest req,
                                           long packed, String dimension) {
        if (this.diskReadingAvailable) {
            boolean claimsData = req.clientTimestamp() > 0;
            // Miss-memo rung (docs/planning/miss-memo-design.md): a fresh authoritative
            // miss skips the redundant re-read and falls through to the generation ladder
            // directly — taking a GENERATION slot only, no SYNC slot, no reader-pool cost.
            // Gated on generationAvailable: with generation off, the memo must never speak
            // (NOT_GENERATED from cached info would be a wire answer off stale knowledge;
            // there is no read churn to save there anyway — the first miss parks the
            // client). Placed AFTER the duplicate/timestamp/probe rungs by drain order, so
            // a stamped or loaded chunk always beats a stale memo entry. A memoized drop is
            // the standard transient drop, healed by the next 1 Hz declaration.
            if (this.generationAvailable
                    && this.timestampCache.isFreshMiss(dimension, packed, System.nanoTime())) {
                this.ctx.diagnostics().addMemoHit(1);
                this.processor.escalateMissToGeneration(playerUuid, state, packed,
                        req.cx(), req.cz(), claimsData, dimension);
                return AdmitResult.SUBMITTED; // dispositioned (submitted or silent drop)
            }
            // Route through disk reader (with cross-player dedup)
            if (!state.tryAdmit(new PendingRequest(req.cx(), req.cz(), SlotType.SYNC_ON_LOAD, claimsData))) {
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
                // Submit was a no-op (e.g. the dimension's level isn't registered yet) — a
                // TRANSIENT condition. Unwind the pending entry (which frees the slot) and the
                // dedup group so they aren't leaked, and drop silently (counted superseded):
                // NOT_GENERATED is session-permanent on the client, so a transient no-op must
                // never answer it — the next want-set declaration retries.
                this.dedupTracker.removeGroup(packed, dimension);
                state.removePendingByPosition(req.cx(), req.cz());
                this.ctx.diagnostics().addSuperseded(1);
                return AdmitResult.SUBMITTED; // dispositioned (silent transient drop)
            }
            this.ctx.diagnostics().incrementDiskQueued();
            return AdmitResult.SUBMITTED;
        } else if (this.generationAvailable) {
            // No disk reader — direct generation for ANY request (unreachable in production:
            // both platforms always construct a reader; the disk-first miss path is the live
            // generation trigger)
            if (!state.tryAdmit(new PendingRequest(req.cx(), req.cz(), SlotType.GENERATION, req.clientTimestamp() > 0))) {
                return AdmitResult.SLOT_FULL;
            }
            // Register in-flight so an overtaking edit taints the outcome, same as the
            // disk-not-found escalation path (handleDiskNotFound). Unreachable in production
            // today — both platforms always construct a disk reader — but the guard must cover
            // every gen-ticket producer or this path re-stamps pre-edit terrain as up_to_date.
            this.processor.addGenerationInFlight(playerUuid, dimension, packed);
            this.ctx.generationTicketRequests().add(
                    new OffThreadProcessor.GenerationTicketRequest(playerUuid, req.cx(), req.cz(),
                            dimension, this.ctx.sequence().next()));
            return AdmitResult.SUBMITTED;
        } else {
            // No disk reader AND no generation — can't serve
            this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, packed, state));
            return AdmitResult.SUBMITTED;
        }
    }
}
