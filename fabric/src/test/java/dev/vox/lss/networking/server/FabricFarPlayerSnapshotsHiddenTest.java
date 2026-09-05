package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSPermissions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Fabric/NeoForge hide-permission read (far-player-render-hardening-plan.md WI-7a):
 * either brand spelling hides, exactly the two shared constants are consulted, and the
 * predicate is the caller's default-FALSE seam read (the deliberate {@code false} default
 * {@code LoaderPermissionSeamContractTest} exempts).
 */
class FabricFarPlayerSnapshotsHiddenTest {

    @Test
    void eitherSpellingHidesAndNothingElseIsConsulted() {
        List<String> asked = new ArrayList<>();
        assertFalse(FabricFarPlayerSnapshots.hiddenFor(node -> {
            asked.add(node);
            return false;
        }), "no grant = visible (default false is the grant-model deny-me lever)");
        assertEquals(List.of(LSSPermissions.FARPLAYERS_HIDDEN_LSS, LSSPermissions.FARPLAYERS_HIDDEN_VSS), asked);
        assertTrue(FabricFarPlayerSnapshots.hiddenFor(LSSPermissions.FARPLAYERS_HIDDEN_LSS::equals));
        assertTrue(FabricFarPlayerSnapshots.hiddenFor(LSSPermissions.FARPLAYERS_HIDDEN_VSS::equals),
                "the VSS spelling hides in the LSS jar too — a jar swap keeps the grant");
    }

    @Test
    void theProductionReadPassesDefaultFalse() throws java.io.IOException {
        String src = java.nio.file.Files.readString(dev.vox.lss.testutil.RepoPaths
                .locate("xplat/src/main/java/dev/vox/lss/networking/server/FabricFarPlayerSnapshots.java"));
        assertTrue(src.contains("checkPermission(p, node, false)"),
                "the hide read must pass default FALSE — true would hide every player on a"
                        + " provider-less server, and the seam cannot express 'threw'");
    }
}
