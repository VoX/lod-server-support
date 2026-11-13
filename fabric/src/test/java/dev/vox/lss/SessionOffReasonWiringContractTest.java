package dev.vox.lss;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source wiring pins for the reason a session answer says "not here" (0.23.0 plan §3). The rule
 * is tested in common ({@code SessionOffReasonTest}), the bytes in the two wire tests, and the
 * Paper handshake in {@code LSSPaperPluginGlueTest}. What no behavior test sees: that every send
 * on the shared core computes a reason, and that both handshake cores feed the rule the gate's
 * own denial and the operator's switch.
 */
class SessionOffReasonWiringContractTest {
    private static String source(String repoPath) throws IOException {
        return MapPolicyWiringContractTest.withoutComments(Files.readString(SourcePaths.repoFile(repoPath)))
                .replaceAll("\\s+", " ");
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void everySharedCoreSendComputesAReason() throws IOException {
        String handshake = source("xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java");
        String service = source("xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java");
        assertEquals(1, count(handshake, "SessionConfigS2CPayload.forSession("));
        assertEquals(1, count(handshake, ".withOffReason("));
        assertEquals(5, count(service, "SessionConfigS2CPayload.forSession("),
                "repush, gate revocation, map revocation, flags push, dimension change");
        assertEquals(5, count(service, ".withOffReason("), "every one of them");
        assertEquals(3, count(service, ".withOffReason(registeredOffReason(config, "),
                "a registered session: the service switch or this world's distance");
        assertTrue(service.contains(".withOffReason(dev.vox.lss.common.SessionOffReason.of( config.enabled(), true,"
                        + " config.showPermissionRefusal(), false))")
                || service.contains(".withOffReason(dev.vox.lss.common.SessionOffReason.of(config.enabled(), true,"
                        + " config.showPermissionRefusal(), false))"),
                "the gate's revocation: the permission reason, never the world");
    }

    @Test
    void bothHandshakeCoresFeedTheRuleTheGatesDenialAndTheOperatorsSwitch() throws IOException {
        String fabric = source("xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java");
        assertTrue(fabric.contains("SessionOffReason.of(config.enabled(), deniedByServiceGate,"
                + " config.showPermissionRefusal(), decision.effectiveEnabled() && !enabledHere)"), "the shared core");
        String paper = source("paper/src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java");
        assertTrue(paper.contains("SessionOffReason.of(config.enabled(), deniedByServiceGate,"
                + " config.showPermissionRefusal(), decision.effectiveEnabled() && !refusedAtSend && !enabledHere)"),
                "the Paper core");
        assertTrue(paper.contains("SessionConfigSender.reasoned((dialect, enabled, offReason,"),
                "the production handshake sender takes the reason");
    }

    @Test
    void thePaperServiceSendsTheReasonItWasGiven() throws IOException {
        String service = source("paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java");
        assertTrue(service.contains("this.sessionConfigSender.send(player, this.config, enabled, offReason,"
                + " capabilities, denyMapIngestion);"), "the wrapper calls the reason form");
        assertTrue(service.contains("PaperPayloadHandler.sendSessionConfig(player.getBukkitEntity(),"
                + " LSSConstants.PROTOCOL_VERSION, enabled, offReason,"), "the production sender puts it on the wire");
    }
}
