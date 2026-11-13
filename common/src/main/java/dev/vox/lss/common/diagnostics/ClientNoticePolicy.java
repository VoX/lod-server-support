package dev.vox.lss.common.diagnostics;

/**
 * Decides when the client tells the player, in chat, why no distant terrain is arriving.
 *
 * <p>Rules (0.22.0 plan §1): at most one notice per connection; only after the verdict has held
 * still for {@link #SETTLE_TICKS}, so a slow server or the Distant Horizons timers are never
 * mistaken for an answer; and under lifetime caps, so a player whose usual server lacks the mod,
 * has it switched off or runs another version is told a few times and then left alone. The
 * status command and screen always carry the verdict. The caps are four counters, never a list
 * of servers.
 *
 * <p>Not thread-safe: the client tick drives it.
 */
public final class ClientNoticePolicy {

    /** 20 s of an unchanged verdict: longer than the Distant Horizons source's 15 s timers. */
    public static final int SETTLE_TICKS = 400;
    /** Each kind of notice is said this many times ever ("nothing can show it": once). */
    public static final int LIFETIME_CAP = 3;

    /** What has already been said, across game starts. */
    public record Caps(int noSupportShown, boolean nothingToShowShown, int serverOffShown,
                       int versionMismatchShown) {
        public static final Caps NONE = new Caps(0, false, 0, 0);
        /** Nothing may be said: the state file could not be read. */
        public static final Caps EXHAUSTED = new Caps(LIFETIME_CAP, true, LIFETIME_CAP, LIFETIME_CAP);

        /** The caps after {@code verdict} was announced. */
        public Caps after(ClientVerdict verdict) {
            if (verdict.versionMismatch()) {
                return new Caps(this.noSupportShown, this.nothingToShowShown, this.serverOffShown,
                        this.versionMismatchShown + 1);
            }
            return switch (verdict) {
                case NO_SUPPORT -> new Caps(this.noSupportShown + 1, this.nothingToShowShown,
                        this.serverOffShown, this.versionMismatchShown);
                case NOTHING_TO_SHOW -> new Caps(this.noSupportShown, true, this.serverOffShown,
                        this.versionMismatchShown);
                case SERVER_OFF -> new Caps(this.noSupportShown, this.nothingToShowShown,
                        this.serverOffShown + 1, this.versionMismatchShown);
                default -> this;
            };
        }

        /** Whether {@code verdict} may still be announced. */
        public boolean allows(ClientVerdict verdict) {
            if (verdict.versionMismatch()) return this.versionMismatchShown < LIFETIME_CAP;
            return switch (verdict) {
                case NO_SUPPORT -> this.noSupportShown < LIFETIME_CAP;
                case NOTHING_TO_SHOW -> !this.nothingToShowShown;
                case SERVER_OFF -> this.serverOffShown < LIFETIME_CAP;
                // Working, waiting, local, the player's own choice, and a silent server (which
                // may be a proxy or a busy link) are never announced.
                default -> false;
            };
        }
    }

    private ClientVerdict last;
    private int stableTicks;
    private boolean decidedThisConnection;

    /** A new connection: the next settled verdict may be announced. */
    public void onJoin() {
        this.last = null;
        this.stableTicks = 0;
        this.decidedThisConnection = false;
    }

    /**
     * One client tick with the current verdict. Returns the verdict to announce now, or null.
     * After a connection's first settled announceable verdict the policy stays silent until the
     * next join, whether or not a cap let it speak.
     */
    public ClientVerdict tick(ClientVerdict verdict, Caps caps) {
        if (this.decidedThisConnection || verdict == null) return null;
        if (verdict != this.last) {
            this.last = verdict;
            this.stableTicks = 0;
            return null;
        }
        if (++this.stableTicks < SETTLE_TICKS) return null;
        if (!announceable(verdict)) return null; // keep watching: the verdict may still change
        this.decidedThisConnection = true;
        return caps.allows(verdict) ? verdict : null;
    }

    /** The verdicts that end a connection's watch, spoken or capped. */
    private static boolean announceable(ClientVerdict verdict) {
        return verdict == ClientVerdict.NO_SUPPORT || verdict == ClientVerdict.NOTHING_TO_SHOW
                || verdict == ClientVerdict.SERVER_OFF || verdict.versionMismatch();
    }
}
