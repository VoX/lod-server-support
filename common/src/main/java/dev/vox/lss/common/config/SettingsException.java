package dev.vox.lss.common.config;

import java.util.List;

/** An actionable settings failure. Messages never include private collection values. */
public final class SettingsException extends RuntimeException {
    private final List<SettingsProblems.Problem> problems;
    public SettingsException(String message) { this(message, null, List.of()); }
    public SettingsException(String message, Throwable cause) { this(message, cause, List.of()); }
    /** A file the strict reader refused, with everything that is wrong in it. The message
     *  stays the strict reader's (its first problem); {@link #problems()} is for people. */
    SettingsException(String message, Throwable cause, List<SettingsProblems.Problem> problems) {
        super(message, cause);
        this.problems = List.copyOf(problems);
    }
    /** Every problem in the refused file, in file order; empty when this failure is not about
     *  a file's contents (a missing file, a conflicting save, a programmatic change). */
    public List<SettingsProblems.Problem> problems() { return problems; }
}
