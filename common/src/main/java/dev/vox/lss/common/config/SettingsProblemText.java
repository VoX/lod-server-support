package dev.vox.lss.common.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The words for a settings file that did not load (0.23.0 plan §1): the console block, the line
 * for admins in chat, and the problem lines a reload or a status screen prints. Every caller
 * passes the real file name and the build's own command, so the Voxy Server Side build never
 * names a file or a command that does not exist there.
 */
public final class SettingsProblemText {
    /** A list is for a console and a chat screen: past this many, the rest is counted. */
    static final int MAX_LINES = 20;

    private SettingsProblemText() {}

    /** "1 problem" / "3 problems". */
    public static String count(int problems) {
        return problems == 1 ? "1 problem" : problems + " problems";
    }

    /** The problems, one line each, cut after {@link #MAX_LINES}. */
    public static List<String> lines(List<SettingsProblems.Problem> problems) {
        var lines = new ArrayList<String>();
        for (int i = 0; i < problems.size() && i < MAX_LINES; i++) lines.add(problems.get(i).render());
        if (problems.size() > MAX_LINES) lines.add("and " + (problems.size() - MAX_LINES) + " more");
        return List.copyOf(lines);
    }

    /** The server console block. */
    public static List<String> serverBlock(String fileName, List<SettingsProblems.Problem> problems) {
        var block = new ArrayList<String>();
        block.add(fileName + " has " + count(problems.size()) + ", so the mod is switched off:");
        for (String line : lines(problems)) block.add("  " + line);
        block.add("Fix the file and restart the server. The file was not changed.");
        return List.copyOf(block);
    }

    /** The chat line for a player who may run the server command. */
    public static String adminLine(String brand, int problems, String command) {
        return "[" + brand + "] Switched off: the settings file has " + count(problems)
                + ". Run /" + command + " to see them.";
    }
}
