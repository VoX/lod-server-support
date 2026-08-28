package dev.vox.lss.paper;

import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The far-player privacy ladder (Folia review 2026-08-27 R2/R7): the pump reads a
 * cross-region permissible + vanish metadata on Folia — the ladder is CONTAINED per
 * player and fails HIDDEN (a raced/throwing read must never leak a hidden or vanished
 * player's position, and one broken permissible must not abort the snapshot pass for
 * everyone — the pre-fix shape). First paper-module coverage of this class.
 */
class PaperFarPlayerSnapshotsTest {

    @org.junit.jupiter.api.BeforeAll
    static void setup() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void resetLatch() {
        PaperFarPlayerSnapshots.resetHiddenReadWarnedForTest();
    }

    private static org.bukkit.entity.Player bukkit() {
        var p = mock(org.bukkit.entity.Player.class);
        when(p.getMetadata(anyString())).thenReturn(List.of());
        return p;
    }

    @Test
    void aCleanUnprivilegedPlayerIsVisible() {
        assertFalse(PaperFarPlayerSnapshots.hiddenFor(bukkit()),
                "no node, no vanish: visible (the mode default)");
    }

    @Test
    void eitherBrandSpellingHides() {
        for (String node : new String[]{"lss.farplayers.hidden", "vss.farplayers.hidden"}) {
            var p = bukkit();
            when(p.hasPermission(node)).thenReturn(true);
            assertTrue(PaperFarPlayerSnapshots.hiddenFor(p),
                    node + " must hide (grant model: EITHER spelling takes effect)");
        }
    }

    @Test
    void vanishedMetadataHides() {
        var p = bukkit();
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        when(p.getMetadata("vanished"))
                .thenReturn(List.<MetadataValue>of(new FixedMetadataValue(plugin, true)));
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(p),
                "the vanish bridge must hide a vanished target");
    }

    @Test
    void aThrowingPermissibleFailsHIDDENNotOpen() {
        // The R7 direction decision, pinned: on Folia a cross-region PermissibleBase
        // read can throw where Paper's main thread never does. Hiding too much for one
        // interval is recoverable; leaking a hidden player's position is not.
        var p = bukkit();
        when(p.hasPermission(anyString())).thenThrow(new IllegalStateException("raced"));
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(p),
                "a throwing privacy read must HIDE, never leak");
    }

    @Test
    void aThrowingVanishReadFailsHIDDENNotOpen() {
        // Reverses the E2 fail-open (recorded in the plan's R7): a LazyMetadataValue
        // callable that trips Folia's region-ownership checks used to answer
        // "not vanished" forever — a vanished staff member broadcast for the run.
        var p = bukkit();
        when(p.getMetadata("vanished")).thenThrow(new IllegalStateException("region-owned"));
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(p),
                "a throwing vanish read must HIDE the target, never leak the position");
    }

    @Test
    void theThrowIsContainedPerPlayerNotPerPass() {
        // The pre-fix failure shape: one throwing permissible aborted the snapshot
        // loop for ALL players. hiddenFor must contain, so a healthy player's
        // evaluation right after a throwing one still works.
        var broken = bukkit();
        when(broken.hasPermission(anyString())).thenThrow(new IllegalStateException("x"));
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(broken));
        assertFalse(PaperFarPlayerSnapshots.hiddenFor(bukkit()),
                "the next player's read must be unaffected by the previous throw");
    }

    // ---- R10: the pass-level containment + the snapshot builder (rig-level) ----

    private static net.minecraft.server.level.ServerPlayer healthyNms(java.util.UUID uuid,
                                                                      String name) {
        var level = mock(net.minecraft.server.level.ServerLevel.class);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        var p = mock(net.minecraft.server.level.ServerPlayer.class);
        when(p.getUUID()).thenReturn(uuid);
        when(p.getName()).thenReturn(net.minecraft.network.chat.Component.literal(name));
        when(p.level()).thenReturn(level);
        when(p.getItemBySlot(org.mockito.ArgumentMatchers.any()))
                .thenReturn(net.minecraft.world.item.ItemStack.EMPTY);
        when(p.getKnownMovement()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
        when(p.isAlive()).thenReturn(true);
        var bukkit = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
        when(bukkit.getMetadata(anyString())).thenReturn(List.of());
        when(p.getBukkitEntity()).thenReturn(bukkit);
        return p;
    }

    @Test
    void oneBrokenPlayerSkipsOnlyItselfNeverThePass() {
        // The pre-fix shape: one raced cross-region read aborted the snapshot loop for
        // ALL players (far players dark for the interval). buildFarPlayerSnapshots
        // contains per player.
        var config = new PaperConfig();
        config.validate();
        var server = mock(net.minecraft.server.MinecraftServer.class);
        var players = new java.util.concurrent.ConcurrentHashMap<java.util.UUID,
                PaperPlayerRequestState>();
        var diskReader = new PaperChunkDiskReader(1, false);
        var processor = new PaperRequestProcessingServiceTest.RecordingProcessor(players, diskReader);
        var tracker = new dev.vox.lss.common.tracking.DirtyColumnTracker();
        var broadcaster = new PaperRequestProcessingServiceTest.RecordingBroadcaster(
                server, players, tracker, processor);
        var service = new PaperRequestProcessingService(server, config,
                new PaperRequestProcessingService.Wiring(
                        players, diskReader, null, processor, tracker, broadcaster));

        var healthy = healthyNms(java.util.UUID.randomUUID(), "healthy");
        var broken = healthyNms(java.util.UUID.randomUUID(), "broken");
        when(broken.getItemBySlot(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("cross-region equipment read raced"));

        var snapshots = service.buildFarPlayerSnapshots(List.of(broken, healthy));

        org.junit.jupiter.api.Assertions.assertEquals(1, snapshots.size(),
                "the broken player is skipped; the pass survives");
        org.junit.jupiter.api.Assertions.assertEquals("healthy", snapshots.get(0).name(),
                "the healthy player's snapshot is intact");
    }

    @Test
    void theSnapshotCarriesTheLadderAndIdentityFields() {
        var uuid = java.util.UUID.randomUUID();
        var p = healthyNms(uuid, "steve");
        var bukkit = (org.bukkit.entity.Player) p.getBukkitEntity();
        when(bukkit.hasPermission("lss.farplayers.hidden")).thenReturn(true);

        var snap = PaperFarPlayerSnapshots.snapshot(p);

        org.junit.jupiter.api.Assertions.assertEquals(uuid, snap.uuid());
        org.junit.jupiter.api.Assertions.assertEquals("steve", snap.name());
        org.junit.jupiter.api.Assertions.assertEquals("minecraft:overworld", snap.dimension());
        assertTrue(snap.hidden(), "the privacy ladder's verdict rides the snapshot");
        assertTrue(snap.alive());
    }

    @Test
    void aCleanPlayerSnapshotIsNotHidden() {
        // F16 false-direction pin: the only snapshot.hidden() assertion elsewhere is
        // TRUE, so hard-coding hidden=true (or inverting hiddenFor) would silently hide
        // every far player for every viewer with the suite green. A clean player's
        // snapshot must carry hidden=false.
        var p = healthyNms(java.util.UUID.randomUUID(), "steve");
        var snap = PaperFarPlayerSnapshots.snapshot(p);
        org.junit.jupiter.api.Assertions.assertFalse(snap.hidden(),
                "a clean player is NOT hidden — the false direction of the privacy verdict");
        assertTrue(snap.alive());
    }
}
