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
 *
 * <p>Since 0.21.0 (hide-permission plan §3) the hide permission is reported as its own
 * FACT ({@code holdsHidePermission}) beside the unconditional verdict ({@code hidden}:
 * vanish, a disguise, any failed read). Holding the node no longer sets {@code hidden};
 * a THROWING permission read still does.
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
        LibsDisguisesBridge.resetForTest();
        me.libraryaddict.disguise.DisguiseAPI.reset();
    }

    @org.junit.jupiter.api.AfterEach
    void restoreBridge() {
        LibsDisguisesBridge.resetForTest();
        me.libraryaddict.disguise.DisguiseAPI.reset();
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
        assertFalse(PaperFarPlayerSnapshots.privacyFor(bukkit()).holdsHidePermission(),
                "and not a holder: the node is declared default false");
    }

    @Test
    void eitherBrandSpellingIsReportedAsTheHolderFactAndNoLongerHidesByItself() {
        // Hide-permission plan §2: a wildcard grant resolves the node true, so the node
        // must not be a verdict. The service hides a holder only while
        // far_players.hide_permission_holders is on.
        for (String node : new String[]{"lss.farplayers.hidden", "vss.farplayers.hidden"}) {
            var p = bukkit();
            when(p.hasPermission(node)).thenReturn(true);
            var privacy = PaperFarPlayerSnapshots.privacyFor(p);
            assertTrue(privacy.holdsHidePermission(),
                    node + " must be reported (grant model: EITHER spelling counts)");
            assertFalse(privacy.hidden(),
                    node + " alone must not set the unconditional verdict");
            assertFalse(PaperFarPlayerSnapshots.hiddenFor(p));
        }
    }

    @Test
    void aVanishedHolderIsBothHiddenAndAHolder() {
        var p = bukkit();
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        when(p.hasPermission("lss.farplayers.hidden")).thenReturn(true);
        when(p.getMetadata("vanished"))
                .thenReturn(List.<MetadataValue>of(new FixedMetadataValue(plugin, true)));
        var privacy = PaperFarPlayerSnapshots.privacyFor(p);
        assertTrue(privacy.hidden(), "vanish hides a holder like anyone else");
        assertTrue(privacy.holdsHidePermission());
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
        // 0.21.0: the permission is only a fact now, but a THROWING permission read is
        // still the unconditional verdict — whatever far_players.hide_permission_holders
        // says (the snapshot builder never sees the setting), and no fact is guessed.
        var privacy = PaperFarPlayerSnapshots.privacyFor(p);
        assertTrue(privacy.hidden(), "a throwing permission read sets the unconditional verdict");
        assertFalse(privacy.holdsHidePermission(), "and claims no permission fact");
    }

    @Test
    void aThrowOnTheSecondSpellingStillHides() {
        // The LSS spelling answers false, so the OR reaches the VSS read — which races.
        var p = bukkit();
        when(p.hasPermission("vss.farplayers.hidden")).thenThrow(new IllegalStateException("raced"));
        assertTrue(PaperFarPlayerSnapshots.privacyFor(p).hidden(),
                "a throw on either spelling's read must HIDE");
    }

    @Test
    void aHolderWhoseVanishReadThrowsIsHidden() {
        // The permission read succeeded (holder); the ladder read after it raced.
        var p = bukkit();
        when(p.hasPermission("lss.farplayers.hidden")).thenReturn(true);
        when(p.getMetadata("vanished")).thenThrow(new IllegalStateException("region-owned"));
        assertTrue(PaperFarPlayerSnapshots.privacyFor(p).hidden(),
                "a holder is not exempt from the fail-hidden direction");
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

    // ---- issue #282: the LibsDisguises rung (after permission and vanish) ----

    /** The "running plugin" identity token the default gate would hand over. */
    private static final Object DISGUISE_PLUGIN = new DisguisePlugin();
    private static final class DisguisePlugin {}

    @Test
    void aDisguisedPlayerIsHidden() {
        LibsDisguisesBridge.pluginProbe = () -> DISGUISE_PLUGIN;
        var p = bukkit();
        me.libraryaddict.disguise.DisguiseAPI.DISGUISED.add(p);
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(p),
                "a LibsDisguises-disguised player must never ride a far-player roster");
        assertFalse(PaperFarPlayerSnapshots.hiddenFor(bukkit()),
                "an undisguised player beside it stays visible");
    }

    @Test
    void aThrowingDisguiseReadFailsHIDDENNotOpen() {
        LibsDisguisesBridge.pluginProbe = () -> DISGUISE_PLUGIN;
        var p = bukkit();
        me.libraryaddict.disguise.DisguiseAPI.THROW = new IllegalStateException("raced");
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(p),
                "a throwing disguise read must HIDE (the ladder's direction), never leak");
        // The broken-install shape: an Error from inside LibsDisguises. The bridge wraps
        // it, so the ladder's catch (Exception) contains it instead of aborting the pass.
        me.libraryaddict.disguise.DisguiseAPI.THROW =
                new NoClassDefFoundError("me/libraryaddict/disguise/DisguiseUtilities");
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(p),
                "an Error out of the disguise read is contained as HIDDEN too");
        // No latch-to-absent: once the plugin answers again, the next read consults it
        // (the stub's call count climbs) and a clean player is visible again.
        me.libraryaddict.disguise.DisguiseAPI.THROW = null;
        int before = me.libraryaddict.disguise.DisguiseAPI.CALLS;
        assertFalse(PaperFarPlayerSnapshots.hiddenFor(bukkit()),
                "the next player's read is unaffected — no latch, no pass abort");
        org.junit.jupiter.api.Assertions.assertEquals(before + 1,
                me.libraryaddict.disguise.DisguiseAPI.CALLS,
                "the plugin is still consulted after a throw — never latched absent");
    }

    @Test
    void aDisabledLibsDisguisesIsIgnored() {
        LibsDisguisesBridge.pluginProbe = () -> null;
        var p = bukkit();
        me.libraryaddict.disguise.DisguiseAPI.DISGUISED.add(p);
        assertFalse(PaperFarPlayerSnapshots.hiddenFor(p),
                "a loaded-but-disabled LibsDisguises rewrites no packets: not consulted");
        org.junit.jupiter.api.Assertions.assertEquals(0, me.libraryaddict.disguise.DisguiseAPI.CALLS);
    }

    @Test
    void absentLibsDisguisesLeavesTheLadderUnchanged() {
        LibsDisguisesBridge.pluginProbe = () -> DISGUISE_PLUGIN;
        LibsDisguisesBridge.classResolver = (name, loader) -> {
            throw new ClassNotFoundException(name);
        };
        var p = bukkit();
        me.libraryaddict.disguise.DisguiseAPI.DISGUISED.add(p);
        assertFalse(PaperFarPlayerSnapshots.hiddenFor(p),
                "no API: the pre-fix ladder verdict, never a throw");
        var vanished = bukkit();
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        when(vanished.getMetadata("vanished"))
                .thenReturn(List.<MetadataValue>of(new FixedMetadataValue(plugin, true)));
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(vanished),
                "the earlier rungs still decide on their own");
    }

    @Test
    void theLadderOrderIsVanishThenDisguiseAndAHolderStillReachesIt() {
        // A throwing disguise read is armed; the vanish rung must answer HIDDEN without
        // ever consulting it (its verdict is final, and the stub's call count proves it).
        LibsDisguisesBridge.pluginProbe = () -> DISGUISE_PLUGIN;
        me.libraryaddict.disguise.DisguiseAPI.THROW = new IllegalStateException("must not be reached");
        var byVanish = bukkit();
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        when(byVanish.getMetadata("vanished"))
                .thenReturn(List.<MetadataValue>of(new FixedMetadataValue(plugin, true)));
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(byVanish));
        org.junit.jupiter.api.Assertions.assertEquals(0, me.libraryaddict.disguise.DisguiseAPI.CALLS,
                "vanish short-circuits BEFORE the disguise read");
        // 0.21.0: the permission is a fact, not a rung — a holder no longer short-circuits,
        // so a DISGUISED (or here: unreadable) holder is still caught by the ladder.
        var byNode = bukkit();
        when(byNode.hasPermission("lss.farplayers.hidden")).thenReturn(true);
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(byNode),
                "a holder reaches the (throwing) disguise rung: HIDDEN");
        org.junit.jupiter.api.Assertions.assertEquals(1, me.libraryaddict.disguise.DisguiseAPI.CALLS,
                "the holder's ladder was read");
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(bukkit()),
                "a clean player reaches the (throwing) disguise rung: HIDDEN");
        org.junit.jupiter.api.Assertions.assertEquals(2, me.libraryaddict.disguise.DisguiseAPI.CALLS);
        me.libraryaddict.disguise.DisguiseAPI.THROW = null;
    }

    @Test
    void aDisguisedHolderIsHidden() {
        LibsDisguisesBridge.pluginProbe = () -> DISGUISE_PLUGIN;
        var p = bukkit();
        when(p.hasPermission("vss.farplayers.hidden")).thenReturn(true);
        me.libraryaddict.disguise.DisguiseAPI.DISGUISED.add(p);
        var privacy = PaperFarPlayerSnapshots.privacyFor(p);
        assertTrue(privacy.hidden(), "holding the node must not skip the disguise rung");
        assertTrue(privacy.holdsHidePermission());
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
        var config = new MutablePaperSettings();
        MutablePaperSettings.normalize(config);
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
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        when(bukkit.getMetadata("vanished"))
                .thenReturn(List.<MetadataValue>of(new FixedMetadataValue(plugin, true)));

        var snap = PaperFarPlayerSnapshots.snapshot(p);

        org.junit.jupiter.api.Assertions.assertEquals(uuid, snap.uuid());
        org.junit.jupiter.api.Assertions.assertEquals("steve", snap.name());
        org.junit.jupiter.api.Assertions.assertEquals("minecraft:overworld", snap.dimension());
        assertTrue(snap.hidden(), "the privacy ladder's verdict rides the snapshot");
        assertFalse(snap.holdsHidePermission(), "vanish is not the permission fact");
        assertTrue(snap.alive());
    }

    @Test
    void aHoldersSnapshotCarriesThePermissionFactAndIsNotHidden() {
        // The two record components must not be swapped or folded back together: a
        // holder's snapshot is (hidden=false, holdsHidePermission=true).
        var p = healthyNms(java.util.UUID.randomUUID(), "admin");
        var bukkit = (org.bukkit.entity.Player) p.getBukkitEntity();
        when(bukkit.hasPermission("lss.farplayers.hidden")).thenReturn(true);

        var snap = PaperFarPlayerSnapshots.snapshot(p);

        assertTrue(snap.holdsHidePermission(), "the permission fact rides the snapshot");
        assertFalse(snap.hidden(), "and the permission alone is not the unconditional verdict");
    }

    @Test
    void aThrowingPermissionReadReachesTheSnapshotAsHidden() {
        var p = healthyNms(java.util.UUID.randomUUID(), "raced");
        var bukkit = (org.bukkit.entity.Player) p.getBukkitEntity();
        when(bukkit.hasPermission(anyString())).thenThrow(new IllegalStateException("raced"));

        var snap = PaperFarPlayerSnapshots.snapshot(p);

        assertTrue(snap.hidden(), "a raced permission read must hide the player in the snapshot");
        assertFalse(snap.holdsHidePermission());
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
        assertFalse(snap.holdsHidePermission(), "and NOT a holder — the false direction of the fact");
        assertTrue(snap.alive());
    }
}
