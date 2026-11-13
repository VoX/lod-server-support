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
        // Since 0.22.0 the count is followed by the map-only fact the verdict reads, and since
        // 0.23.0 by the server's reason for an answer that says "not here" and by what the LOD
        // renderer says about itself.
        assertTrue(code.contains("journeyMapAvailability, journeyMap.pendingColumns(),"
                        + " FarPlayerClientSupport.tracker().trackedCount(),"
                        + " dev.vox.lss.compat.ModCompat.isMapOnlyIngestion(),"
                        + " ClientNetGlue.serverOffReason(), renderer);"),
                "the native snapshot must carry the tracker's count, the map-only fact, the reason, "
                        + "then the renderer's answer");
        assertEquals(1, code.split("FarPlayerClientSupport\\.tracker\\(\\)\\.trackedCount\\(\\)", -1).length - 1,
                "exactly one snapshot carries it (the DH branch has no far-player service)");
        // The Distant Horizons branch is where a refused native session ends up (the fallback
        // was tried and found no service), so it carries the reason too; still no far players.
        assertTrue(code.contains("DhClientSession.diagnostics(), journeyMapAvailability, 0, 0, false,"
                        + " ClientNetGlue.serverOffReason(), renderer));"),
                "the DH snapshot has no far-player observation (0) and carries the server's reason "
                        + "and the renderer's answer");
        assertEquals(2, code.split("ClientNetGlue\\.serverOffReason\\(\\)", -1).length - 1,
                "both snapshots carry the reason");
        // The renderer is asked once, in a world only: before the game's renderer exists it
        // has not decided, and would read as "cannot run on this computer".
        assertTrue(code.contains("var renderer = mc.level != null ? dev.vox.lss.compat.ModCompat.voxyDrawState()"
                        + " : ClientStatusSnapshot.Renderer.UNKNOWN;"),
                "the renderer's answer is read only in a world");
        assertEquals(1, code.split("voxyDrawState\\(\\)", -1).length - 1, "asked once per capture");
    }
}
