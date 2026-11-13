package dev.vox.lss.networking.client;

import dev.vox.lss.common.farplayers.FarPlayerWire;
import java.util.function.Function;

/**
 * Client-owner preference delivery survives reception retirement, scoped to one transport.
 *
 * <p>Two levels of readiness (0.23.0 plan §2). A session answer makes every preference
 * deliverable, as before. From the join on, a frame that says "do not share my position" may
 * go without one: an opt-out can only hide the player, so it must not wait for a session this
 * client may never get (receiving off, nothing installed that shows terrain, a server that
 * stays silent). "Share my position" still waits for the session: on a server in
 * {@code opt_in} mode it would show a player who is hidden today.
 */
final class FarPlayerPreferenceDelivery {
    enum Outcome { SENT, NO_CHANNEL, FAILED }
    /** What to tell the player after a settings save. */
    enum Report {
        /** The preference reached a server that answered this client. */
        SENT,
        /** "Off" reached the server although no session exists. */
        OPT_OUT_SENT,
        /** "On" is saved and cannot be sent without a session, and the server still holds
         *  the "off" it was last sent on this connection. */
        OPT_OUT_KEPT,
        /** Nothing has reached a server. */
        NOT_SENT,
        /** A send failed and is being retried. */
        RETRYING
    }
    private Object connection;
    private boolean joined;
    private boolean ready;
    private FarPlayerWire.Prefs sent;
    private FarPlayerWire.Prefs pending;
    /** The last preference this server received on this join said "do not share". A server
     *  keeps it until the connection ends, whatever happens to the session meanwhile, so
     *  only a join or a delivered "share" changes this. */
    private boolean serverHoldsOptOut;
    private int ticks;
    private Outcome outcome = Outcome.NO_CHANNEL;

    /**
     * A join. Also a second one on the same connection object (a proxy moved the player to
     * another server): the server on the other end has been told nothing yet.
     *
     * @param earlyOptOut false in the player's own world, where there is nobody to hide from
     */
    void joined(Object identity, boolean earlyOptOut) {
        clear();
        connection = identity;
        joined = identity != null && earlyOptOut;
    }
    void sessionReady(Object identity) {
        if (connection != identity) clear();
        connection = identity;
        // The frame after a session answer is the one the server answers with the first
        // roster: an opt-out that went out before the answer does not stand in for it.
        if (!ready) sent = null;
        ready = identity != null;
    }
    /** A handshake was sent (also by the discovery ladder, every few seconds, to a server
     *  that never answers): the next session needs its own frame. What the server holds is
     *  untouched by a handshake. */
    void handshake() { ready = false; sent = null; }
    private boolean deliverable(FarPlayerWire.Prefs prefs) {
        return ready || (joined && !prefs.shareSelf());
    }
    Outcome accept(Object identity, FarPlayerWire.Prefs desired,
                   Function<FarPlayerWire.Prefs, Outcome> enqueue) {
        if (identity != connection || !deliverable(desired)) {
            // Sharing was switched back on before a waiting opt-out could go: it must not go.
            if (identity == connection) pending = null;
            outcome = Outcome.NO_CHANNEL;
            return outcome;
        }
        if (desired.equals(sent)) {
            pending = null;
            return outcome = Outcome.SENT;
        }
        pending = desired;
        return attempt(enqueue);
    }
    private Outcome attempt(Function<FarPlayerWire.Prefs, Outcome> enqueue) {
        try { outcome = enqueue.apply(pending); }
        catch (RuntimeException failure) { outcome = Outcome.FAILED; }
        if (outcome == Outcome.SENT) {
            serverHoldsOptOut = !pending.shareSelf();
            sent = pending;
            pending = null;
        }
        return outcome;
    }
    void tick(Object identity, Function<FarPlayerWire.Prefs, Outcome> enqueue) {
        if (connection != identity) { clear(); return; }
        if (pending == null || !deliverable(pending)) return;
        if (++ticks >= 20) { ticks = 0; attempt(enqueue); }
    }
    Outcome outcome() { return outcome; }
    /** The line for the player, given the preference their settings now hold. */
    Report report(FarPlayerWire.Prefs desired) {
        if (outcome == Outcome.FAILED) return Report.RETRYING;
        if (outcome == Outcome.SENT) return ready ? Report.SENT : Report.OPT_OUT_SENT;
        return serverHoldsOptOut && !ready && desired.shareSelf() ? Report.OPT_OUT_KEPT : Report.NOT_SENT;
    }
    boolean pending() { return pending != null; }
    void invalidateSent() { sent = null; }
    void clear() {
        connection = null;
        joined = false;
        ready = false;
        sent = null;
        pending = null;
        serverHoldsOptOut = false;
        ticks = 0;
        outcome = Outcome.NO_CHANNEL;
    }
}
