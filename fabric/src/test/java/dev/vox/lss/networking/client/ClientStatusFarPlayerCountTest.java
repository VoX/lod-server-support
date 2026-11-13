package dev.vox.lss.networking.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status screen's far-player count (hide-permission plan §3): the native snapshot's
 * last constructor argument is the client tracker's count (what the server is currently
 * sending), and the DH snapshot, which has no far-player service, leaves it unobserved.
 * Source-flavored because {@code ClientStatus.tick} needs a live client; the rendered
 * line, its position and its translations are pinned by {@code ClientStatusTextTest}.
 */
class ClientStatusFarPlayerCountTest {

    @Test
    void theNativeSnapshotCarriesTheTrackersCount() throws java.io.IOException {
        String src = java.nio.file.Files.readString(dev.vox.lss.testutil.RepoPaths
                .locate("xplat/src/main/java/dev/vox/lss/networking/client/ClientStatus.java"));
        String code = src.replaceAll("(?m)^\\s*//.*$", "").replaceAll("\\s+", " ");
        assertTrue(code.contains("journeyMapAvailability, journeyMap.pendingColumns(),"
                        + " FarPlayerClientSupport.tracker().trackedCount());"),
                "the native snapshot must end with the tracker's count");
        assertEquals(1, code.split("FarPlayerClientSupport\\.tracker\\(\\)\\.trackedCount\\(\\)\\);", -1).length - 1,
                "exactly one snapshot carries it (the DH branch has no far-player service)");
        assertTrue(code.contains("DhClientSession.diagnostics(), journeyMapAvailability, 0));"),
                "the DH snapshot keeps the constructor without a far-player observation");
    }
}
