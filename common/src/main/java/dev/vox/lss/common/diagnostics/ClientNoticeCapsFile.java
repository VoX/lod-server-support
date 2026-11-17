package dev.vox.lss.common.diagnostics;

import dev.vox.lss.common.diagnostics.ClientNoticePolicy.Caps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The chat notices' counters on disk: five values in the client's state directory, so a notice
 * that was said its few times is not said again after a restart. No server names or addresses.
 *
 * <p>Fails quiet: a file that cannot be read means "say nothing", and the caller speaks a
 * counted notice only after {@link #save} succeeded, so a broken or read-only directory can
 * never turn a capped notice into one repeated on every game start.
 */
public final class ClientNoticeCapsFile {
    private ClientNoticeCapsFile() {}

    /** The stored caps; {@link Caps#NONE} when the file does not exist yet. */
    public static Caps load(Path file) {
        if (!Files.exists(file)) return Caps.NONE;
        try {
            int noSupport = -1, serverOff = -1, versionMismatch = -1;
            Boolean nothingToShow = null;
            // Added in 0.23.0: a file from 0.22 does not have it, and that means "not said yet".
            boolean rendererUnsupported = false;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int at = line.indexOf('=');
                if (at < 0) continue;
                String key = line.substring(0, at).trim();
                String value = line.substring(at + 1).trim();
                switch (key) {
                    case "no_support" -> noSupport = Integer.parseInt(value);
                    case "server_off" -> serverOff = Integer.parseInt(value);
                    case "version_mismatch" -> versionMismatch = Integer.parseInt(value);
                    case "nothing_to_show" -> nothingToShow = Boolean.parseBoolean(value);
                    case "renderer_unsupported" -> rendererUnsupported = Boolean.parseBoolean(value);
                    default -> { } // a newer version's counter: not ours to judge
                }
            }
            if (noSupport < 0 || serverOff < 0 || versionMismatch < 0 || nothingToShow == null) {
                return Caps.EXHAUSTED;
            }
            return new Caps(noSupport, nothingToShow, serverOff, versionMismatch, rendererUnsupported);
        } catch (IOException | RuntimeException unreadable) {
            return Caps.EXHAUSTED;
        }
    }

    private static final java.util.Set<String> OWN_KEYS =
            java.util.Set.of("no_support", "nothing_to_show", "server_off", "version_mismatch", "renderer_unsupported");

    /** Writes the caps (a sibling temp file, then a move), keeping any counter this version does
     *  not know. False when it could not be saved. */
    public static boolean save(Path file, Caps caps) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            var kept = new StringBuilder();
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int at = line.indexOf('=');
                    if (at > 0 && !OWN_KEYS.contains(line.substring(0, at).trim())) kept.append(line).append('\n');
                }
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, "no_support=" + caps.noSupportShown() + "\n"
                    + "nothing_to_show=" + caps.nothingToShowShown() + "\n"
                    + "server_off=" + caps.serverOffShown() + "\n"
                    + "version_mismatch=" + caps.versionMismatchShown() + "\n"
                    + "renderer_unsupported=" + caps.rendererUnsupportedShown() + "\n" + kept, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }
}
