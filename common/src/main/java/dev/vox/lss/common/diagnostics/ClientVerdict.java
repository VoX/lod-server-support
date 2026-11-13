package dev.vox.lss.common.diagnostics;

import java.util.Locale;

/**
 * The one-line answer to "is distant terrain working here, and if not, why?". Computed in one
 * place from the status snapshot and used by the status command, the status screen and the
 * chat notice, so the three can never disagree.
 *
 * <p>Invariant: a verdict never claims more than the client knows. A silent server is
 * {@link #NO_ANSWER}, not "this server lacks the mod": a proxy, a version skew or a saturated
 * link are silent too. {@link #NO_SUPPORT} needs the stronger fact that the server never
 * registered the handshake channel.
 */
public enum ClientVerdict {
    /** Negotiated, the server serves this player here, and something can show the terrain. */
    WORKING,
    /** Terrain comes from this server's Distant Horizons. */
    WORKING_DH,
    /** Still negotiating, or the Distant Horizons source has not settled. */
    WAITING,
    NOT_CONNECTED,
    /** Singleplayer or a LAN host: the world is local, nothing is downloaded. */
    LOCAL_WORLD,
    /** The server never registered the handshake channel and offers no Distant Horizons terrain. */
    NO_SUPPORT,
    /** The channel exists but every handshake went unanswered. */
    NO_ANSWER,
    /** The server answered with a protocol this client does not speak, direction unknown. */
    VERSION_MISMATCH,
    /** The server speaks a newer protocol: the player can fix it by updating. */
    CLIENT_OUTDATED,
    /** The server speaks an older protocol: only the server can fix it. */
    SERVER_OUTDATED,
    /** The server answered "not for you here": off for everyone, for this world, or for this player. */
    SERVER_OFF,
    /** No LOD renderer and no map mod: nothing can show what would be downloaded. */
    NOTHING_TO_SHOW,
    /** The map mods are the only thing that could show the terrain, and the server asks
     *  clients not to write it into maps. */
    MAPS_BLOCKED,
    /** The player turned receiving off. */
    RECEIVING_OFF,
    /** The Distant Horizons source is selected but paused, blocked or stopped; its own lines say why. */
    SOURCE_NOT_RUNNING;

    /** The translation key suffix: {@code lss.status.verdict.<key>} and {@code lss.notice.<key>}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The server's map request stops every map mod this client could write to. */
    private static boolean mapsBlocked(ClientStatusSnapshot s) {
        var blocked = ClientStatusSnapshot.Availability.SERVER_BLOCKED;
        var available = ClientStatusSnapshot.Availability.AVAILABLE;
        return (s.xaeroAvailability() == blocked || s.journeyMapAvailability() == blocked)
                && s.xaeroAvailability() != available && s.journeyMapAvailability() != available;
    }

    /** Whether this is one of the three "the two sides speak different versions" answers. */
    public boolean versionMismatch() {
        return this == VERSION_MISMATCH || this == CLIENT_OUTDATED || this == SERVER_OUTDATED;
    }

    /** Whether distant terrain is arriving. */
    public boolean working() {
        return this == WORKING || this == WORKING_DH;
    }

    public static ClientVerdict of(ClientStatusSnapshot s) {
        if (s == null) return WAITING;
        if (!s.connected()) return NOT_CONNECTED;
        if (s.discovery() == ClientStatusSnapshot.Discovery.LOCAL) return LOCAL_WORLD;
        if (!s.receptionEnabled()) return RECEIVING_OFF;
        var dh = s.dh();
        boolean dhSource = dh != null && dh.reason() != DhStatusSnapshot.Reason.NATIVE_OFFER
                && dh.reason() != DhStatusSnapshot.Reason.NATIVE_PREFERRED;
        if (dhSource) {
            switch (dh.state()) {
                case READY: return WORKING_DH;
                case CONNECTING: case BETWEEN_SESSIONS: case PAUSED: return WAITING;
                case BLOCKED: case STOPPED: return SOURCE_NOT_RUNNING;
                case NEED_VOXY:
                    // A Distant Horizons server answered and the LOD renderer is still starting.
                    if (dh.activity() == DhStatusSnapshot.Activity.WAITING_VOXY) return WAITING;
                    break;
                // No Distant Horizons terrain here, or this source cannot run on this client
                // (it feeds an LOD renderer only): the native answer decides.
                case NO_SERVICE: case NEED_TRANSPORT: break;
            }
        }
        if (!s.consumerAvailable()) return NOTHING_TO_SHOW;
        return switch (s.discovery()) {
            case NOT_CONNECTED -> NOT_CONNECTED;
            case LOCAL -> LOCAL_WORLD;
            // Not asking, and not because of the player's switch or a missing consumer (both
            // answered above): the first seconds of a connection, before the channel settles.
            case DORMANT -> WAITING;
            case AWAITING_NEGOTIATION -> WAITING;
            case SEND_FAILED, NO_CHANNEL -> NO_SUPPORT;
            case NO_ANSWER -> NO_ANSWER;
            // The snapshot carries the version the server answered with.
            case PROTOCOL_REJECTED -> s.protocol() > dev.vox.lss.common.LSSConstants.PROTOCOL_VERSION
                    ? CLIENT_OUTDATED
                    : s.protocol() > 0 && s.protocol() < dev.vox.lss.common.LSSConstants.PROTOCOL_VERSION
                            ? SERVER_OUTDATED : VERSION_MISMATCH;
            case NEGOTIATED -> !s.serverEnabled() ? SERVER_OFF : s.mapOnly() && mapsBlocked(s) ? MAPS_BLOCKED : WORKING;
        };
    }
}
