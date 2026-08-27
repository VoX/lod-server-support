package dev.vox.lss;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source wiring pin for the C5 Via mismatch guard (XVER §7), the
 * {@code ChannelAccessorContractTest} pattern: the gate's 8-arg overload delegates
 * {@code false} for legacy call paths, so a platform that drops the
 * {@code enableViaMismatchGuard} gate around the probe (an UNDISABLABLE guard — the
 * exact §2.2 operator-override obligation), or feeds the wrong native protocol, ships
 * green through every unit suite: the ladder itself is pinned pure, and no test JVM
 * has real Via. Regexes are whitespace-normalized and anchor the RELATIONSHIP
 * (config flag {@code ?} probe {@code :} no-signal) in one expression — a
 * {@code contains} pair passes when the flag only appears in a comment (review
 * MAJOR-3's exact complaint).
 */
class ViaGuardWiringContractTest {

    private static String normalized(String path) throws Exception {
        return Files.readString(Path.of(path)).replaceAll("\\s+", " ");
    }

    /** The shared receiver glue (xplat since N-2) — where the Via consult lives now. */
    private static String normalizedGlue() throws Exception {
        return Files.readString(dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/server/ServerReceiverGlue.java"))
                .replaceAll("\\s+", " ");
    }

    /** The composition: probe consulted ONLY behind the config flag, ternary to
     *  NO_SIGNAL. Matches both platforms' shapes (`config.` / `this.lssConfig.`). */
    private static final Pattern GATED_PROBE = Pattern.compile(
            "enableViaMismatchGuard \\? dev\\.vox\\.lss\\.common\\.compat\\.ViaProbe"
                    + "\\.playerProtocol\\([A-Za-z.]*[Pp]layer\\.getUUID\\(\\)\\)"
                    + " : dev\\.vox\\.lss\\.common\\.compat\\.ViaProbe\\.NO_SIGNAL");

    @Test
    void fabricGatesTheProbeAndFeedsTheRealNativeProtocol() throws Exception {
        String src = normalizedGlue();
        assertTrue(GATED_PROBE.matcher(src).find(),
                "Fabric must consult the Via probe ONLY behind enableViaMismatchGuard"
                        + " (ternary to NO_SIGNAL) — an unconditional consult makes the"
                        + " guard undisablable");
        assertTrue(src.contains("viaProtocol, SharedConstants.getProtocolVersion(),"),
                // (trailing comma: the service-gate seam widened the call — the gate
                // param rides after the native protocol)
                "Fabric's production caller must pass the REAL MC native protocol into"
                        + " the seam — LSSConstants.PROTOCOL_VERSION there denies every"
                        + " legacy client (20 != any real Via protocol)");
        assertTrue(src.contains("ViaProbe.isMismatch(viaProtocol, nativeProtocol))"),
                "Fabric must feed the shared mismatch RULE into the gate's 8-arg form");
        assertTrue(src.contains("Outcome.VIA_MISMATCH"),
                "Fabric must classify the denial (the INFO line) off the dedicated outcome");
    }

    @Test
    void paperGatesTheProbeAndFeedsTheRealNativeProtocol() throws Exception {
        String src = normalized("../paper/src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java");
        assertTrue(GATED_PROBE.matcher(src).find(),
                "Paper must consult the Via probe ONLY behind enableViaMismatchGuard"
                        + " (ternary to NO_SIGNAL)");
        assertTrue(src.contains("viaProtocol, net.minecraft.SharedConstants.getProtocolVersion(),"),
                "Paper's production caller must pass the REAL MC native protocol into"
                        + " the seam, never the LSS constant the no-signal overload"
                        + " defaults to");
        assertTrue(src.contains("ViaProbe.isMismatch(viaProtocol, nativeProtocol))"),
                "Paper's seam must feed the shared mismatch RULE into the gate's 8-arg form");
        assertTrue(src.contains("Outcome.VIA_MISMATCH"),
                "Paper must classify the denial off the dedicated outcome");
    }

    @Test
    void theDenialPrecedesTheReplyOnBothPlatforms() throws Exception {
        // Order pin: the VIA_MISMATCH early return must come BEFORE any reply/register
        // dispatch — a denial placed after the reply leaks the SessionConfig frame.
        for (String src : new String[]{
                normalizedGlue(),
                normalized("../paper/src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java")}) {
            int denial = src.indexOf("Outcome.VIA_MISMATCH");
            int reply = src.indexOf("decision.sendSessionConfig()");
            assertTrue(denial >= 0 && reply >= 0 && denial < reply,
                    "the VIA_MISMATCH check must precede the reply dispatch (glue or paper)");
        }
    }
}
