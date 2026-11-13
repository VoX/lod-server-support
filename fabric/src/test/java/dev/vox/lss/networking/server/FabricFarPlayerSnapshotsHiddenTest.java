package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSPermissions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Fabric/NeoForge/Sponge hide-permission read (far-player-render-hardening-plan.md
 * WI-7a): either brand spelling makes the player a HOLDER, exactly the two shared constants
 * are consulted, and the predicate is the caller's default-FALSE seam read (the deliberate
 * {@code false} default {@code LoaderPermissionSeamContractTest} exempts). Since 0.21.0
 * (hide-permission plan §3) the read is a fact carried in
 * {@code PlayerSnapshot.holdsHidePermission}, never the unconditional {@code hidden}
 * verdict: the service hides a holder only while
 * {@code far_players.hide_permission_holders} is on.
 */
class FabricFarPlayerSnapshotsHiddenTest {

    @Test
    void eitherSpellingMakesAHolderAndNothingElseIsConsulted() {
        List<String> asked = new ArrayList<>();
        assertFalse(FabricFarPlayerSnapshots.holdsHidePermission(node -> {
            asked.add(node);
            return false;
        }), "no grant = not a holder (the node is default false)");
        assertEquals(List.of(LSSPermissions.FARPLAYERS_HIDDEN_LSS, LSSPermissions.FARPLAYERS_HIDDEN_VSS), asked);
        assertTrue(FabricFarPlayerSnapshots.holdsHidePermission(LSSPermissions.FARPLAYERS_HIDDEN_LSS::equals));
        assertTrue(FabricFarPlayerSnapshots.holdsHidePermission(LSSPermissions.FARPLAYERS_HIDDEN_VSS::equals),
                "the VSS spelling counts in the LSS jar too — a jar swap keeps the grant");
    }

    @Test
    void thePermissionRidesTheSnapshotAsAFactNeverAsTheUnconditionalVerdict() throws java.io.IOException {
        // Hide-permission plan §3: in the PlayerSnapshot constructor call the unconditional
        // `hidden` argument is the literal false (nothing on this path can set it: vanish is
        // per viewer and rides the VanishBridge seam) and the NEXT argument is the permission
        // read. Folding the read back into `hidden` would hide every wildcard admin again
        // whatever the server setting says; swapping the two would do the same.
        String src = java.nio.file.Files.readString(dev.vox.lss.testutil.RepoPaths
                .locate("xplat/src/main/java/dev/vox/lss/networking/server/FabricFarPlayerSnapshots.java"));
        String code = src.replaceAll("(?m)^\\s*//.*$", "").replaceAll("\\s+", " ");
        assertTrue(code.contains("p.isSpectator(), p.isInvisible(), p.isAlive() && !p.isRemoved(), false,"
                        + " holdsHidePermission(node -> LoaderServices.get().checkPermission(p, node, false)),"
                        + " hash, equipmentIds, equipmentCounts, vehicle);"),
                "hidden must be the literal false, immediately followed by the permission fact");
        assertFalse(code.contains("hiddenFor("), "the old verdict-shaped read must not come back");
        // The record declares the two components in that order (the positional call depends on it).
        var components = dev.vox.lss.common.farplayers.FarPlayerBroadcastService.PlayerSnapshot.class
                .getRecordComponents();
        var names = java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(names.indexOf("hidden") + 1, names.indexOf("holdsHidePermission"),
                "holdsHidePermission must directly follow hidden: " + names);
    }

    @Test
    void theVanishAdapterKeepsViewerAndTargetStraightAndMemoizesPerTick() throws java.io.IOException {
        // Review fold B1: the seam is canSee(UUID viewer, UUID target) — both UUIDs, so a swap
        // type-checks and would ask "is the VIEWER vanished". Pin the adapter's mapping (the
        // observer entity is resolved from the VIEWER, the target travels as the UUID) and the
        // per-tick memo (fold B3).
        String src = java.nio.file.Files.readString(dev.vox.lss.testutil.RepoPaths
                .locate("xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java"));
        // Since sponge-support-plan.md §1.5 the calls go through the loader's VanishVisibility
        // seam (the Melius bridge by default) — the same three calls, the same argument order.
        assertTrue(src.contains("var vanish = dev.vox.lss.platform.LoaderServices.get().vanishVisibility();")
                        && src.contains("if (!vanish.present()) return true;")
                        && src.contains("var observer = server.getPlayerList().getPlayer(viewer);")
                        && src.contains("vanish.canSee(server, observer, target);")
                        && src.contains("vanishMemo.computeIfAbsent(target,")
                        && src.contains("vanish.isVanished(server, t)"),
                "the vanish adapter must resolve the observer from the VIEWER, pass the TARGET uuid,"
                        + " and memoize isVanished per target per tick");
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
