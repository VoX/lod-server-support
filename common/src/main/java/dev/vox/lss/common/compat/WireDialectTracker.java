package dev.vox.lss.common.compat;

import dev.vox.lss.common.HandshakeGate.WireDialect;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One per-service registry of every session's wire dialect
 * (cross-version-identity-encoding-plan §4.3): UUID → {@link WireDialect}, replacing the
 * {@code V18CompatTracker} bare set and the {@code V16CompatManager.isV16} egress checks
 * with a single source of truth. Because it is ONE map, a cross-dialect re-handshake is a
 * plain overwrite — the old mark-plus-shed call pairs collapse into {@link #onHandshake}.
 *
 * <p>Lifecycle contract — the {@code V18CompatTracker} javadoc's, preserved verbatim:
 * membership is created at the HANDSHAKE (Fabric: inline before {@code registerPlayer} on
 * the main thread; Paper: ONLY via the {@code dialectFlip} runnable the lifecycle mailbox
 * runs on the PUMP — a region-thread mark can flip the dialect mid-flush and ship
 * wrong-shaped frames to a decoder still armed for the old dialect) and dropped at the
 * network DISCONNECT hooks. {@code service.removePlayer} — the dimension-change
 * remove+register cycle — must NOT drop it: the re-registration re-derives
 * {@code wantsCompressedColumns} through this membership, and losing it would re-grow
 * stripped bytes mid-session (hard-kick class). Paper's quit path ADDITIONALLY drops
 * membership when the lifecycle mailbox drains the quit-originated Remove (a quit's
 * direct {@code onDisconnect} can run before a deferred Register's mark has applied —
 * the leak guard; quit-originated only, so dim-change survival is untouched).
 *
 * <p><b>The v16 single-lifetime rule</b> (the §4.3 review finding, RESOLVED here by
 * inspection): the manager's 75 s TTL is per WANT-SET ENTRY, not per session — v16
 * sessions themselves die only at disconnect/shed, exactly this tracker's lifetimes.
 * Moving the egress checks onto this tracker therefore leaves ONE lifetime with no
 * extra hook; the plan's "session prune is the tracker-removal hook" premise had no
 * corresponding code (recorded in the C1 decisions log).
 */
public final class WireDialectTracker {

    private final Map<UUID, WireDialect> sessions = new ConcurrentHashMap<>();
    /** Cumulative sessions started per dialect this service lifetime — keeps the diag
     *  line's history visible after the last legacy client leaves. */
    private final AtomicLong startedV16 = new AtomicLong();
    private final AtomicLong startedV18 = new AtomicLong();
    private final AtomicLong startedV19 = new AtomicLong();
    private final AtomicLong startedCurrent = new AtomicLong();

    /** The session's dialect, or {@code CURRENT} when untracked (an unknown session can
     *  only be served canonical shapes — never a legacy strip/translation). */
    public WireDialect dialectOf(UUID uuid) {
        // Null-tolerant: no identity = no session = CURRENT. Production states always
        // carry a UUID; bare-mock test rigs may not, and a CHM get(null) throws.
        if (uuid == null) {
            return WireDialect.CURRENT;
        }
        return this.sessions.getOrDefault(uuid, WireDialect.CURRENT);
    }

    public boolean isV16(UUID uuid) {
        return this.sessions.get(uuid) == WireDialect.V16;
    }

    /**
     * True only when this UUID was marked {@link WireDialect#CURRENT} at handshake.
     * Distinct from {@link #dialectOf}, which answers CURRENT for untracked ids
     * (egress must never strip an unknown session). A mid-session SessionConfig push
     * (the dimension-change distance re-push) must gate on THIS, not on that default:
     * the payload is v20-shaped, so a legacy (v16/v18/v19) session must not receive it
     * — a legacy client picks a world's distance up on rejoin instead. (It also keeps
     * unit rigs that {@code registerPlayer} without a handshake from being sent at.)
     */
    public boolean isCurrent(UUID uuid) {
        return this.sessions.get(uuid) == WireDialect.CURRENT;
    }

    public boolean isV18(UUID uuid) {
        return this.sessions.get(uuid) == WireDialect.V18;
    }

    public boolean isV19(UUID uuid) {
        return this.sessions.get(uuid) == WireDialect.V19;
    }

    /**
     * Marks this player's dialect (any re-handshake overwrites — the cross-dialect shed).
     * Counts a started session only when the dialect actually changes (or is new), so a
     * same-dialect re-handshake stays idempotent like the old trackers.
     */
    public void onHandshake(UUID uuid, WireDialect dialect) {
        WireDialect previous = this.sessions.put(uuid, dialect);
        if (previous != dialect) {
            counterFor(dialect).incrementAndGet();
            if (dialect != WireDialect.CURRENT) {
                // Session-start log parity with the deleted per-dialect trackers
                // (review C1-11): without it a Paper operator has no log evidence a
                // legacy session ever existed, only the live Dialects: gauge.
                dev.vox.lss.common.LSSLogger.info(dialect + "-compat session started for "
                        + uuid + " (dialect tracker)");
            }
        }
    }

    /** Network disconnect — the identity dies with the connection. */
    public void onDisconnect(UUID uuid) {
        this.sessions.remove(uuid);
    }

    /**
     * A handshake that did NOT register (NO_CONSUMER / DISABLED): a CROSS-dialect
     * re-handshake must still shed a stale membership (the old per-dialect
     * {@code onNonV18Handshake} behavior — without this, a v18 member re-handshaking
     * as a consumer-less current client would keep receiving v18-stripped frames from
     * its surviving registration), while a SAME-dialect non-register handshake keeps
     * the membership exactly as the old trackers did.
     */
    public void onNonRegisterHandshake(UUID uuid, WireDialect handshakenDialect) {
        this.sessions.computeIfPresent(uuid,
                (k, existing) -> existing == handshakenDialect ? existing : null);
    }

    public int sessionCount(WireDialect dialect) {
        int n = 0;
        for (WireDialect d : this.sessions.values()) {
            if (d == dialect) {
                n++;
            }
        }
        return n;
    }

    private AtomicLong counterFor(WireDialect dialect) {
        return switch (dialect) {
            case V16 -> this.startedV16;
            case V18 -> this.startedV18;
            case V19 -> this.startedV19;
            case CURRENT -> this.startedCurrent;
        };
    }

    /**
     * The {@code Dialects:} diag line (replaces the {@code V18Compat:} slot): live
     * session counts per dialect, plus cumulative started totals when any legacy
     * session has ever existed. Always non-null — the current-dialect count alone is
     * operationally useful (it is the "how many LOD sessions" number).
     */
    public String diagLine() {
        return "Dialects: v20=" + sessionCount(WireDialect.CURRENT)
                + ", v19=" + sessionCount(WireDialect.V19)
                + ", v18=" + sessionCount(WireDialect.V18)
                + ", v16=" + sessionCount(WireDialect.V16)
                + ", started=" + this.startedCurrent.get()
                + "/" + this.startedV19.get()
                + "/" + this.startedV18.get()
                + "/" + this.startedV16.get();
    }
}
