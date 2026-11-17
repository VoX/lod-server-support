package dev.vox.lss.common;

/**
 * Lets a client's extra command name step aside for a server command of the same name
 * (0.23.0 plan §4). A client command wins over a server command, so the Voxy Server Side
 * client's extra {@code /lss} must not take another plugin's {@code /lss} away from the player.
 *
 * <p>The question "does the server have this command?" can only be asked of the server's command
 * list before the loader has merged the client's own commands into it, and Fabric merges into
 * the same list object. So the answer is taken the first time a given list is seen, which is
 * the merge itself, and kept for as long as that list is the current one.
 *
 * <p>Client thread.
 */
public final class ExtraCommandRootGuard {
    private Object decidedFor;
    private boolean allowed = true;

    /**
     * @param serverCommandList the server's command list as the client holds it now (identity only)
     * @param presentNow whether that list has a root of this name right now
     * @return whether the extra name may be used with this list
     */
    public boolean allows(Object serverCommandList, boolean presentNow) {
        if (serverCommandList != this.decidedFor) {
            this.decidedFor = serverCommandList;
            this.allowed = !presentNow;
        }
        return this.allowed;
    }
}
