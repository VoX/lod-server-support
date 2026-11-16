package dev.vox.lss.common.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Says, on a dedicated server, that the settings file did not load (0.23.0 plan §1): the console
 * block once more after the server has started, so it is the last thing in the log and not line
 * 80 of 400, and one chat line to each player who may run the server command, the first time
 * that player is seen online in this server run.
 *
 * <p>A poll, not a join listener: the four platforms need no new event wiring, and a permission
 * that loads after the join is still seen. Ticked by the service before its "disabled" return,
 * because a file that did not load leaves the service disabled.
 *
 * <p>Not thread-safe: one server tick thread drives it.
 */
public final class SettingsProblemAnnouncer {
    /** A player who may run the server command, and how to put a line in their chat. */
    public record Recipient(UUID id, Consumer<String> chat) {}

    /** After this many ticks the startup log is over: the block is logged once more. */
    static final int REPEAT_AFTER_TICKS = 200;
    /** How often the online players are looked at. */
    static final int POLL_TICKS = 100;

    private final Set<UUID> told = new HashSet<>();
    private int ticks;
    private boolean repeated;

    /**
     * One server tick.
     *
     * @param problems empty when the file loaded: nothing is said or computed
     * @param dedicated false in singleplayer and on a LAN host: there is no console to point at
     * @param admins evaluated only on a poll tick
     */
    public void tick(List<SettingsProblems.Problem> problems, String fileName, String brand, String command,
                     boolean dedicated, Supplier<List<Recipient>> admins, Consumer<String> log) {
        if (problems.isEmpty() || !dedicated) return;
        ticks++;
        if (!repeated && ticks >= REPEAT_AFTER_TICKS) {
            repeated = true;
            SettingsProblemText.serverBlock(fileName, problems).forEach(log);
        }
        if (ticks % POLL_TICKS != 0) return;
        for (Recipient admin : admins.get())
            if (told.add(admin.id())) admin.chat().accept(SettingsProblemText.adminLine(brand, problems.size(), command));
    }
}
