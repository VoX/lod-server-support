package dev.vox.lss.common.compat;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.AbstractPlayerRequestState;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The v16 compat shim's per-service registry: one {@link V16CompatSession} per connected
 * legacy protocol-16 client, keyed by UUID (docs/planning/v16-compat-design.md).
 *
 * <p>Lifecycle contract (§7 of the design): identity is created at the HANDSHAKE (the
 * network level that owns protocol negotiation) and dropped at the network DISCONNECT
 * hooks; {@code service.removePlayer} — which also fires on the dimension-change
 * remove+register cycle — only resets the session's want-set via {@link #onServiceRemove}
 * and arms the ingress grace, mirroring how capabilities survive the rebuild. A duplicate
 * handshake resets the want-set but keeps the identity.
 *
 * <p>The pipeline never sees this class: a v16 player is an ordinary registered player
 * whose "client" (this shim) declares a v17-style want-set at 1 Hz. Counters live here as
 * {@code AtomicLong}s — NEVER route them through {@code ProcessingDiagnostics}, which is
 * single-writer on the processing thread while these events fire on MAIN/PUMP and Folia
 * region threads.
 */
public final class V16CompatManager {

    /** Declares happen every this many service ticks (1 Hz — the cadence every downstream
     *  mechanism is tuned for: Folia hold-release CAS, superseded accounting, probe
     *  alignment). Ingress NEVER declares — see the design's review finding S1. */
    static final int DECLARE_INTERVAL_TICKS = LSSConstants.TICKS_PER_SECOND;

    /** Evict a want-set entry when no client re-ask refreshed it for this long. 75 s, NOT
     *  the naive 30 s: the old client's 10 s timeout sweep rides its scan phase, and
     *  sustained movement starves both, so a >30 s flight would evict positions the client
     *  still counts in flight (design review finding C4). The declare-time range filter
     *  carries the geometric pruning; the TTL only covers in-range abandonment. */
    static final long ENTRY_TTL_NANOS = 75_000_000_000L;

    /** Ingress merges are discarded for this long after a want-set reset: old-dimension
     *  straggler batches in netty flight would otherwise re-merge (finding C3). ~20 ticks. */
    static final long RESET_GRACE_NANOS = 1_000_000_000L;

    /** Synthetic want-set capacity — the same budget a v17 client declares. Overflow is
     *  bounced {@link LSSConstants#RESPONSE_RATE_LIMITED_V16} (the old client backs off
     *  ~1 s and retries); with advertised caps 200/16 the steady state is ≈216 entries, so
     *  this fires mainly during warm-reconnect resync floods. */
    static final int WANT_SET_CAP = LSSConstants.WANT_SET_BUDGET;

    static final long[] NO_POSITIONS = new long[0];

    /** Outcome of one ingress merge: how many positions the range filter dropped (feeds the
     *  state's range_filtered accounting), how many the post-reset grace discarded, and the
     *  packed positions to bounce {@code RESPONSE_RATE_LIMITED_V16} (set at capacity). */
    public record MergeResult(int rangeFiltered, int graceDiscarded, long[] overflowBounced) {}

    private final ConcurrentHashMap<UUID, V16CompatSession> sessions = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;

    private final AtomicLong redeclares = new AtomicLong();
    private final AtomicLong overflowBounced = new AtomicLong();
    private final AtomicLong graceDiscarded = new AtomicLong();

    public V16CompatManager() {
        this(System::nanoTime);
    }

    /** Test seam: injectable clock for TTL/grace tests. */
    V16CompatManager(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** Whether this player is a legacy v16 session (any thread). */
    public boolean isV16(UUID uuid) {
        return this.sessions.containsKey(uuid);
    }

    /** Handshake (network thread): create the session, or reset the want-set of an existing
     *  one (duplicate handshake keeps identity — registerPlayer is computeIfAbsent-idempotent
     *  on the service side). Called only on a REGISTER outcome with the V16 dialect. */
    public void onHandshake(UUID uuid) {
        var existing = this.sessions.putIfAbsent(uuid, new V16CompatSession());
        if (existing != null) {
            existing.resetWantSet(this.nanoClock.getAsLong(), false);
            LSSLogger.info("v16-compat: duplicate handshake for " + uuid + ", want-set reset");
        } else {
            LSSLogger.info("v16-compat: legacy protocol-16 session started for " + uuid);
        }
    }

    /** Network disconnect (Fabric DISCONNECT event / Paper PlayerQuit): drop identity. */
    public void onDisconnect(UUID uuid) {
        this.sessions.remove(uuid);
    }

    /** Service-level removal — fired by BOTH disconnect teardown and the dimension-change
     *  remove+register cycle. Resets the want-set and arms the ingress grace; identity
     *  survives (the disconnect hook is what drops it). No-op for v18 players. */
    public void onServiceRemove(UUID uuid) {
        var session = this.sessions.get(uuid);
        if (session != null) session.resetWantSet(this.nanoClock.getAsLong(), true);
    }

    /**
     * Ingress merge of one decoded client batch (network/region thread). NEVER declares.
     * Returns null for non-v16 players (caller proceeds with the normal v18 ingress path).
     */
    public MergeResult onClientBatch(UUID uuid, long[] packedPositions, long[] clientTimestamps,
                                     int count, int playerCx, int playerCz, int maxDist) {
        var session = this.sessions.get(uuid);
        if (session == null) return null;
        var result = session.merge(packedPositions, clientTimestamps, count,
                playerCx, playerCz, maxDist, this.nanoClock.getAsLong(), WANT_SET_CAP);
        if (result.graceDiscarded() > 0) this.graceDiscarded.addAndGet(result.graceDiscarded());
        if (result.overflowBounced().length > 0) {
            this.overflowBounced.addAndGet(result.overflowBounced().length);
        }
        return result;
    }

    /**
     * Per-player service tick (MAIN/PUMP), called for every registered player each tick —
     * cheap no-op for v18 players. On this session's declare tick, sweeps TTL, re-applies
     * the range filter against the player's current chunk, sorts closest-first, and offers
     * the full synthetic want-set into the player's normal mailbox — the SOLE declarer.
     */
    public void tickPlayer(UUID uuid, AbstractPlayerRequestState<?> state,
                           int playerCx, int playerCz, int maxDist) {
        var session = this.sessions.get(uuid);
        if (session == null) return;
        var declared = session.tickAndBuildDeclaration(playerCx, playerCz, maxDist,
                this.nanoClock.getAsLong());
        if (declared == null) return;
        if (declared.rangeEvicted() > 0) state.recordRangeFiltered(declared.rangeEvicted());
        if (declared.batch() != null) {
            state.offerIncomingBatch(declared.batch());
            this.redeclares.incrementAndGet();
        }
    }

    /** Column send seam (MAIN/PUMP, after a successful wire send): the position is
     *  satisfied-by-data — prune it. Load-bearing (design §4.4). No-op for v18 players. */
    public void onColumnSent(UUID uuid, long packed) {
        var session = this.sessions.get(uuid);
        if (session != null) session.prune(packed);
    }

    /** BatchResponse send seam (MAIN/PUMP): UP_TO_DATE / NOT_GENERATED terminally answer
     *  their positions — prune them. The frame itself passes through wire-identical. */
    public void observeBatchResponse(UUID uuid, byte[] responseTypes, long[] packedPositions,
                                     int count) {
        var session = this.sessions.get(uuid);
        if (session == null) return;
        for (int i = 0; i < count; i++) {
            byte type = responseTypes[i];
            if (type == LSSConstants.RESPONSE_UP_TO_DATE
                    || type == LSSConstants.RESPONSE_NOT_GENERATED) {
                session.prune(packedPositions[i]);
            }
        }
    }

    // ---- Diagnostics (read by formatters/exporters; counters are shim-owned AtomicLongs) ----

    public int sessionCount() {
        return this.sessions.size();
    }

    public long totalRedeclares() {
        return this.redeclares.get();
    }

    public long totalOverflowBounced() {
        return this.overflowBounced.get();
    }

    public long totalGraceDiscarded() {
        return this.graceDiscarded.get();
    }

    /** One /lsslod diag line, or null when the shim has never been touched this run. */
    public String diagLineOrNull() {
        int clients = sessionCount();
        long re = totalRedeclares();
        long ov = totalOverflowBounced();
        long gr = totalGraceDiscarded();
        if (clients == 0 && re == 0 && ov == 0 && gr == 0) return null;
        return String.format("V16Compat: clients=%d, redeclares=%d, overflow_bounced=%d, grace_discarded=%d",
                clients, re, ov, gr);
    }
}
