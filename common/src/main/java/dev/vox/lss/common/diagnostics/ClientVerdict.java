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
    /** An LOD renderer is installed and the bridge to it did not start (its internals changed):
     *  the player has the mod the "nothing to show" advice names, so that advice would be wrong. */
    RENDERER_NOT_CONNECTED,
    /** A map mod is installed and the bridge to it did not start, and nothing else here can
     *  show the terrain. */
    MAP_NOT_CONNECTED,
    /** A map mod is installed, the player switched map writing off, and nothing else here can
     *  show the terrain. The player's own choice: never announced. */
    MAP_WRITING_OFF,
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

    /** Voxy is installed: its version is a real one, not "absent" or unreadable. A consumer is
     *  registered for the whole launch once the bridge starts, so installed without a consumer
     *  means the bridge failed. */
    private static boolean rendererInstalled(ClientStatusSnapshot s) {
        // "redacted" is an installed mod whose version string the status will not print.
        String voxy = s.versions().components().get(DiagnosticVersions.Component.VOXY);
        return voxy != null && !voxy.equals("absent") && !voxy.equals("unknown");
    }

    /** Why nothing on this client can show the terrain: the nearest fix first. */
    private static ClientVerdict nothingShows(ClientStatusSnapshot s) {
        if (rendererInstalled(s)) return RENDERER_NOT_CONNECTED;
        var xaero = s.xaeroAvailability();
        var journeyMap = s.journeyMapAvailability();
        var off = ClientStatusSnapshot.Availability.DISABLED;
        if (xaero == off || journeyMap == off) return MAP_WRITING_OFF;
        if (unconnected(xaero) || unconnected(journeyMap)) return MAP_NOT_CONNECTED;
        return NOTHING_TO_SHOW;
    }

    private static boolean unconnected(ClientStatusSnapshot.Availability map) {
        return map == ClientStatusSnapshot.Availability.UNAVAILABLE || map == ClientStatusSnapshot.Availability.FAILED;
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
                // (it feeds an LOD renderer only; the player runs the Distant Horizons client
                // mod): the native answer decides. That answer speaks about this mod only, so
                // it stays true for a player whose own Distant Horizons mod is showing terrain.
                case NO_SERVICE: case NEED_TRANSPORT: break;
            }
        }
        if (!s.consumerAvailable()) return nothingShows(s);
        return switch (s.discovery()) {
            case NOT_CONNECTED -> NOT_CONNECTED;
            case LOCAL -> LOCAL_WORLD;
            // Not asking, and not because of the player's switch or a missing consumer (both
            // answered above): the first seconds of a connection, before the channel settles.
            case DORMANT -> WAITING;
            case AWAITING_NEGOTIATION -> WAITING;
            // The handshake could not be sent from this client (not in the world yet): that
            // says nothing about the server.
            case SEND_FAILED -> WAITING;
            case NO_CHANNEL -> NO_SUPPORT;
            case NO_ANSWER -> NO_ANSWER;
            // The snapshot carries the version the server answered with.
            case PROTOCOL_REJECTED -> s.protocol() > dev.vox.lss.common.LSSConstants.PROTOCOL_VERSION
                    ? CLIENT_OUTDATED
                    : s.protocol() > 0 && s.protocol() < dev.vox.lss.common.LSSConstants.PROTOCOL_VERSION
                            ? SERVER_OUTDATED : VERSION_MISMATCH;
            case NEGOTIATED -> !s.serverEnabled() ? SERVER_OFF
                    // Terrain reaches the maps, but the LOD renderer the player installed gets none.
                    : s.mapOnly() && rendererInstalled(s) ? RENDERER_NOT_CONNECTED
                    : s.mapOnly() && mapsBlocked(s) ? MAPS_BLOCKED : WORKING;
        };
    }
}
