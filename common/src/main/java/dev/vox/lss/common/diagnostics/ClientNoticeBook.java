package dev.vox.lss.common.diagnostics;

import dev.vox.lss.common.diagnostics.ClientNoticePolicy.Caps;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * What the client may say in chat, across connections and game starts: the per-connection
 * {@link ClientNoticePolicy}, the lifetime counters on disk and one rule about proxy networks.
 * The client glue feeds it the verdict each tick and prints what it returns.
 *
 * <p>Rules kept here: the counters file is read only when a notice is about to be considered
 * (most sessions never read it); a count is saved before its line is spoken, so a directory that
 * cannot be written never turns a capped notice into one repeated on every game start; and on a
 * connection where terrain already worked, "this server has no mod" is not said (a network's lobby
 * after its game server); and a settings file that did not load is said once per game start,
 * with no counter. Not thread-safe: the client tick drives it.
 */
public final class ClientNoticeBook {
    private final ClientNoticePolicy policy = new ClientNoticePolicy();
    private final Supplier<Path> file;
    private Caps caps;
    private boolean workedThisConnection;
    private boolean settingsBrokenSaid;

    public ClientNoticeBook(Supplier<Path> file) {
        this.file = file;
    }

    /** A join, also the one a proxy's server switch fires: the next settled verdict may speak. */
    public void onJoin() {
        this.policy.onJoin();
    }

    /** The connection ended (a server switch through a proxy does not end it). */
    public void onDisconnect() {
        this.workedThisConnection = false;
    }

    /** One client tick. Returns the verdict to say in chat now, or null. */
    public ClientVerdict tick(ClientVerdict verdict) { return tick(verdict, false); }

    /**
     * One client tick. Returns the verdict to say in chat now, or null.
     *
     * @param served a server on this connection answered and serves this player here. The
     *               verdict can still be "the LOD renderer is not drawing"; the network has the
     *               mod all the same, which is all the lobby rule needs to know.
     */
    public ClientVerdict tick(ClientVerdict verdict, boolean served) {
        if (served || verdict != null && verdict.working()) this.workedThisConnection = true;
        ClientVerdict settled = this.policy.settle(verdict);
        if (settled == null) return null;
        if (settled == ClientVerdict.NO_SUPPORT && this.workedThisConnection) return null;
        if (settled == ClientVerdict.SETTINGS_BROKEN) {
            // Once per game start, and no counter on disk (0.23.0 plan §1): the mod is off on
            // every server, the player can fix it, and a second breakage next year must not be
            // silent because three earlier ones used up a lifetime cap.
            if (this.settingsBrokenSaid) return null;
            this.settingsBrokenSaid = true;
            return settled;
        }
        try {
            Path path = this.file.get();
            if (this.caps == null) this.caps = ClientNoticeCapsFile.load(path);
            if (!this.caps.allows(settled)) return null;
            Caps after = this.caps.after(settled);
            if (!ClientNoticeCapsFile.save(path, after)) return null;
            this.caps = after;
            return settled;
        } catch (RuntimeException unknownPlace) {
            return null;
        }
    }
}
