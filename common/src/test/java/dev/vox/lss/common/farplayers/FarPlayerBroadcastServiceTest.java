package dev.vox.lss.common.farplayers;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The broadcast core (E1): the filter ladder in order, the epoch'd roster with the R-3
 * any-prefs full-roster rule + rate bound, whole-snapshot delta suppression (R-10: a
 * position-unchanged dismount still forces), tier cadence, withheld-send rollbacks, and
 * the v18-rung lifecycle surface.
 */
class FarPlayerBroadcastServiceTest {

    private static final UUID VIEWER = new UUID(0, 1);
    private static final UUID T1 = new UUID(0, 2);
    private static final UUID T2 = new UUID(0, 3);

    private record Sent(UUID viewer, String channel, byte[] body) {}

    private final List<Sent> sent = new ArrayList<>();
    private boolean sendResult = true;

    private FarPlayerBroadcastService.FrameSender sender() {
        return (viewer, channel, body) -> {
            if (!sendResult) return false;
            sent.add(new Sent(viewer, channel, body));
            return true;
        };
    }

    private static FarPlayerBroadcastService.Settings settings(String mode) {
        return settings(mode, false);
    }

    /** {@code hideHolders} = {@code far_players.hide_permission_holders}. */
    private static FarPlayerBroadcastService.Settings settings(String mode, boolean hideHolders) {
        return new FarPlayerBroadcastService.Settings(mode, 2048, 0, false, List.of(), 10, hideHolders);
    }

    private static FarPlayerWire.Prefs prefs(boolean enabled, boolean shareSelf) {
        return new FarPlayerWire.Prefs(enabled, 0, 0, shareSelf, 0);
    }

    private static FarPlayerBroadcastService.PlayerSnapshot snap(UUID uuid, String name,
                                                                 double x, double z) {
        return snapFull(uuid, name, "minecraft:overworld", x, 64, z, false, false, true,
                0L, null, null, null);
    }

    private static FarPlayerBroadcastService.PlayerSnapshot snapFull(
            UUID uuid, String name, String dim, double x, double y, double z,
            boolean spectator, boolean invisible, boolean alive,
            long equipHash, String[] equipIds, int[] equipCounts,
            FarPlayerWire.Vehicle vehicle) {
        return new FarPlayerBroadcastService.PlayerSnapshot(uuid, name, dim, x, y, z,
                0f, 0f, 0f, (byte) 0, 0, 0, 0, spectator, invisible, alive, false, false,
                equipHash, equipIds, equipCounts, vehicle);
    }

    private FarPlayerBroadcastService subscribed() {
        var svc = new FarPlayerBroadcastService(null);
        svc.subscribeViewer(VIEWER);
        svc.onPrefs(VIEWER, prefs(true, true));
        return svc;
    }

    /** The unconditional ladder verdict (vanish, disguise, a failed read). */
    private static FarPlayerBroadcastService.PlayerSnapshot hiddenSnap(UUID uuid, String name,
                                                                       double x, double z) {
        return privacySnap(uuid, name, x, z, true, false);
    }

    /** A hide-permission holder the ladder did not hide. */
    private static FarPlayerBroadcastService.PlayerSnapshot holderSnap(UUID uuid, String name,
                                                                       double x, double z) {
        return privacySnap(uuid, name, x, z, false, true);
    }

    private static FarPlayerBroadcastService.PlayerSnapshot privacySnap(
            UUID uuid, String name, double x, double z, boolean hidden, boolean holdsHidePermission) {
        return new FarPlayerBroadcastService.PlayerSnapshot(uuid, name, "minecraft:overworld",
                x, 64, z, 0f, 0f, 0f, (byte) 0, 0, 0, 0, false, false, true,
                hidden, holdsHidePermission, 0L, null, null, null);
    }

    private List<UUID> rosterUuids() {
        return rosters().stream().flatMap(r -> r.added().stream())
                .map(FarPlayerWire.RosterEntry::uuid).toList();
    }

    private List<FarPlayerWire.Roster> rosters() {
        return sent.stream().filter(s -> s.channel().equals(LSSConstants.CHANNEL_FAR_PLAYER_ROSTER))
                .map(s -> FarPlayerWire.decodeRoster(s.body())).toList();
    }

    private List<FarPlayerWire.Updates> updates() {
        return sent.stream().filter(s -> s.channel().equals(LSSConstants.CHANNEL_FAR_PLAYER_UPDATES))
                .map(s -> FarPlayerWire.decodeUpdates(s.body())).toList();
    }

    @Test
    void subscribeSendsAFullRosterAndFirstUpdates() {
        var svc = subscribed();
        svc.tick(10_000, List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "Target", 500, 0)),
                settings("on"), sender());
        var r = rosters();
        assertEquals(1, r.size());
        assertTrue(r.get(0).full());
        assertEquals(1, r.get(0).added().size());
        assertEquals("Target", r.get(0).added().get(0).name());
        var u = updates();
        assertEquals(1, u.size());
        assertEquals(r.get(0).epoch(), u.get(0).epoch(), "updates are stamped with the roster epoch");
        assertEquals(10, u.get(0).cadenceTicks(), "the server-declared cadence rides every frame");
        assertEquals(1, u.get(0).entries().size());
    }

    @Test
    void anyPrefsReceiptQueuesAFullRosterButTheRateBoundHoldsIt() {
        var svc = subscribed();
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "Target", 500, 0));
        svc.tick(10_000, world, settings("on"), sender());
        assertEquals(1, rosters().size());

        // Byte-identical prefs re-send (R-3: the reset/re-subscribe mechanism).
        svc.onPrefs(VIEWER, prefs(true, true));
        svc.tick(11_000, world, settings("on"), sender());
        assertEquals(1, rosters().size(),
                "the rate bound holds the client-triggerable full roster (flood lever)");
        svc.tick(10_000 + FarPlayerBroadcastService.FULL_ROSTER_MIN_INTERVAL_MILLIS,
                world, settings("on"), sender());
        var r = rosters();
        assertEquals(2, r.size(), "the pending full roster HELD, never dropped");
        assertTrue(r.get(1).full());
        assertTrue(r.get(1).epoch() > r.get(0).epoch(), "a full roster bumps the epoch");
    }

    @Test
    void membershipChangesRideIncrementalFramesWithinTheEpoch() {
        var svc = subscribed();
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        svc.tick(10_000, List.of(viewer, snap(T1, "A", 500, 0)), settings("on"), sender());
        int epoch = rosters().get(0).epoch();

        // T2 walks into range: an incremental add, same epoch.
        svc.tick(10_500, List.of(viewer, snap(T1, "A", 500, 0), snap(T2, "B", 600, 0)),
                settings("on"), sender());
        var r = rosters();
        assertEquals(2, r.size());
        assertFalse(r.get(1).full());
        assertEquals(epoch, r.get(1).epoch());
        assertEquals("B", r.get(1).added().get(0).name());

        // T1 leaves: an incremental removal carrying its index.
        svc.tick(11_000, List.of(viewer, snap(T2, "B", 600, 0)), settings("on"), sender());
        r = rosters();
        assertEquals(3, r.size());
        assertEquals(1, r.get(2).removedIndices().length);
        assertEquals(0, r.get(2).removedIndices()[0], "A held index 0");
    }

    @Test
    void wholeSnapshotDeltaSuppressionAndTheR10DismountForce() {
        var svc = subscribed();
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        var horse = new FarPlayerWire.Vehicle("minecraft:horse", new UUID(9, 9),
                FarPlayerWire.quantizePos(500), FarPlayerWire.quantizePos(64),
                FarPlayerWire.quantizePos(0), (byte) 0, (byte) 0);
        var mounted = snapFull(T1, "T", "minecraft:overworld", 500, 64, 0,
                false, false, true, 0L, null, null, horse);
        svc.tick(10_000, List.of(viewer, mounted), settings("on"), sender());
        assertEquals(1, updates().size());
        assertNotNull(updates().get(0).entries().get(0).vehicle());

        // Nothing changed: suppressed entirely (no updates frame).
        svc.tick(10_500, List.of(viewer, mounted), settings("on"), sender());
        assertEquals(1, updates().size(), "an unchanged snapshot ships nothing");
        assertTrue(svc.suppressedUnchanged() > 0);

        // Dismount at the IDENTICAL quantized position: must force through (R-10 — a
        // position-only delta rule would render the stale mount indefinitely).
        var dismounted = snapFull(T1, "T", "minecraft:overworld", 500, 64, 0,
                false, false, true, 0L, null, null, null);
        svc.tick(11_000, List.of(viewer, dismounted), settings("on"), sender());
        var u = updates();
        assertEquals(2, u.size(), "the dismount forces an update at an unchanged position");
        assertNull(u.get(1).entries().get(0).vehicle());
    }

    @Test
    void equipmentShipsOnFirstSendAndOnHashChangeOnly() {
        var svc = subscribed();
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        String[] ids = {"minecraft:netherite_helmet", null, null, null, null, null};
        int[] counts = {1, 0, 0, 0, 0, 0};
        var geared = snapFull(T1, "T", "minecraft:overworld", 500, 64, 0,
                false, false, true, 111L, ids, counts, null);
        svc.tick(10_000, List.of(viewer, geared), settings("on"), sender());
        assertNotNull(updates().get(0).entries().get(0).equipmentIdentities(),
                "first send carries equipment");

        // Moves but same gear: the entry ships WITHOUT equipment.
        var moved = snapFull(T1, "T", "minecraft:overworld", 510, 64, 0,
                false, false, true, 111L, ids, counts, null);
        svc.tick(10_500, List.of(viewer, moved), settings("on"), sender());
        assertNull(updates().get(1).entries().get(0).equipmentIdentities(),
                "unchanged equipment hash -> omitted (the wire-cost win)");

        // Gear swap: hash change forces + re-ships.
        String[] ids2 = {"minecraft:iron_helmet", null, null, null, null, null};
        var swapped = snapFull(T1, "T", "minecraft:overworld", 510, 64, 0,
                false, false, true, 222L, ids2, counts, null);
        svc.tick(11_000, List.of(viewer, swapped), settings("on"), sender());
        assertEquals("minecraft:iron_helmet",
                updates().get(2).entries().get(0).equipmentIdentities()[0]);
    }

    @Test
    void filterLadderExcludesEveryIneligibleShape() {
        var vanishHidden = new UUID(0, 7);
        var svc = new FarPlayerBroadcastService((viewer, target) -> !target.equals(vanishHidden));
        svc.subscribeViewer(VIEWER);
        svc.onPrefs(VIEWER, prefs(true, true));
        var world = new ArrayList<FarPlayerBroadcastService.PlayerSnapshot>();
        world.add(snap(VIEWER, "Viewer", 0, 0));                 // self — never served
        world.add(snapFull(new UUID(0, 10), "OtherDim", "minecraft:the_end", 100, 64, 0,
                false, false, true, 0, null, null, null));
        world.add(snapFull(new UUID(0, 11), "Dead", "minecraft:overworld", 100, 64, 0,
                false, false, false, 0, null, null, null));
        world.add(snapFull(new UUID(0, 12), "Spec", "minecraft:overworld", 100, 64, 0,
                true, false, true, 0, null, null, null));
        world.add(snapFull(new UUID(0, 13), "Invis", "minecraft:overworld", 100, 64, 0,
                false, true, true, 0, null, null, null));
        world.add(snapFull(vanishHidden, "Vanished", "minecraft:overworld", 100, 64, 0,
                false, false, true, 0, null, null, null));
        world.add(snap(new UUID(0, 14), 9_000, 0));              // beyond the 2048 server cap
        world.add(snap(new UUID(0, 15), 500, 0));                // the one visible target
        svc.tick(10_000, world, settings("on"), sender());
        var r = rosters();
        assertEquals(1, r.size());
        assertEquals(1, r.get(0).added().size(), "exactly one target survives the ladder");
        assertEquals(new UUID(0, 15), r.get(0).added().get(0).uuid());
    }

    private static FarPlayerBroadcastService.PlayerSnapshot snap(UUID uuid, double x, double z) {
        return snap(uuid, "P" + uuid.getLeastSignificantBits(), x, z);
    }

    @Test
    void optInModeServesOnlySharingTargetsAndModeOnHonorsOptOut() {
        var svc = new FarPlayerBroadcastService(null);
        svc.subscribeViewer(VIEWER);
        svc.onPrefs(VIEWER, prefs(true, true));
        // T1 subscribed + sharing; T2 subscribed + NOT sharing; T3 vanilla (no prefs).
        svc.subscribeViewer(T1);
        svc.onPrefs(T1, prefs(true, true));
        svc.subscribeViewer(T2);
        svc.onPrefs(T2, prefs(true, false));
        var t3 = new UUID(0, 4);
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "Sharer", 500, 0),
                snap(T2, "OptOut", 500, 100), snap(t3, "Vanilla", 500, 200));

        svc.tick(10_000, world, settings("opt-in"), sender());
        var optIn = rosters();
        assertEquals(1, optIn.get(0).added().size(), "opt-in: only the explicit sharer");
        assertEquals(T1, optIn.get(0).added().get(0).uuid());

        sent.clear();
        var svc2 = new FarPlayerBroadcastService(null);
        svc2.subscribeViewer(VIEWER);
        svc2.onPrefs(VIEWER, prefs(true, true));
        svc2.subscribeViewer(T2);
        svc2.onPrefs(T2, prefs(true, false));
        svc2.tick(10_000, world, settings("on"), sender());
        var on = rosters();
        assertEquals(2, on.get(0).added().size(),
                "mode on: the vanilla target and the sharer are served...");
        assertTrue(on.get(0).added().stream().noneMatch(e -> e.uuid().equals(T2)),
                "...but an EXPLICIT opt-out is honored even in mode on");
    }

    @Test
    void excludeListBlocksByNameAndUuidCaseInsensitively() {
        var svc = subscribed();
        var t3 = new UUID(0, 4);
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "Hidden", 500, 0),
                snap(T2, "ByUuid", 500, 100), snap(t3, "Served", 500, 200));
        var s = new FarPlayerBroadcastService.Settings("on", 2048, 0, false,
                List.of("hidden", T2.toString().toUpperCase(java.util.Locale.ROOT)), 10, false);
        svc.tick(10_000, world, s, sender());
        assertEquals(1, rosters().get(0).added().size());
        assertEquals(t3, rosters().get(0).added().get(0).uuid());
    }

    @Test
    void withheldFullRosterNeverBurnsAnEpochTheClientDidNotSee() {
        var svc = subscribed();
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "T", 500, 0));
        sendResult = false;
        svc.tick(10_000, world, settings("on"), sender());
        assertTrue(sent.isEmpty());
        sendResult = true;
        svc.tick(10_100, world, settings("on"), sender());
        var r = rosters();
        assertEquals(1, r.size(), "the pending full roster survived the withheld send");
        assertEquals(1, r.get(0).epoch(),
                "the withheld attempt must not have burned epoch 1 (the client never saw it)");
    }

    @Test
    void lifecycleSurfacesFollowTheV18RungChecklist() {
        var svc = new FarPlayerBroadcastService(null);
        svc.onPrefs(VIEWER, prefs(true, true));
        assertFalse(svc.isSubscribed(VIEWER), "prefs before subscribe are ignored");
        svc.subscribeViewer(VIEWER);
        assertTrue(svc.isSubscribed(VIEWER));
        svc.onPrefs(VIEWER, prefs(true, true));

        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "T", 500, 0));
        svc.tick(10_000, world, settings("on"), sender());
        int epoch1 = rosters().get(0).epoch();

        // Dimension change: identity SURVIVES, the roster re-issues at a bumped epoch.
        svc.onViewerDimensionChange(VIEWER);
        assertTrue(svc.isSubscribed(VIEWER), "the subscription survives a dimension change");
        svc.tick(10_000 + FarPlayerBroadcastService.FULL_ROSTER_MIN_INTERVAL_MILLIS,
                world, settings("on"), sender());
        assertTrue(rosters().get(1).full());
        assertTrue(rosters().get(1).epoch() > epoch1);

        svc.removeViewer(VIEWER);
        assertFalse(svc.isSubscribed(VIEWER));
        sent.clear();
        svc.tick(20_000, world, settings("on"), sender());
        assertTrue(sent.isEmpty(), "a removed viewer receives nothing");
    }

    @Test
    void offModeAndDisabledPrefsTickToNothing() {
        var svc = subscribed();
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "T", 500, 0));
        svc.tick(10_000, world, settings("off"), sender());
        assertTrue(sent.isEmpty(), "mode off ships nothing");

        svc.onPrefs(VIEWER, prefs(false, true));
        svc.tick(11_000, world, settings("on"), sender());
        assertTrue(sent.isEmpty(), "a viewer whose prefs disable the feature gets nothing");
    }

    @Test
    void farTargetsUpdateAtTierCadenceButForcesBreakThrough() {
        var svc = subscribed();
        var s = new FarPlayerBroadcastService.Settings("on", 16384, 0, false, List.of(), 10, false);
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        // Beyond TIER_HALF_RATE_BLOCKS: half-rate tier. Move every tick; only every
        // second moving tick ships.
        double x = 3000;
        svc.tick(10_000, List.of(viewer, snap(T1, "Far", x, 0)), s, sender());
        int framesAfterFirst = updates().size();
        int shipped = 0;
        for (int i = 1; i <= 4; i++) {
            x += 10;
            svc.tick(10_000 + i * 500L, List.of(viewer, snap(T1, "Far", x, 0)), s, sender());
        }
        shipped = updates().size() - framesAfterFirst;
        assertTrue(shipped < 4, "a half-rate tier target must skip some moving ticks, shipped "
                + shipped + "/4");
        assertTrue(shipped >= 1, "the tier holds updates, it does not starve them");

        // A mount change breaks through the tier hold immediately.
        var horse = new FarPlayerWire.Vehicle("minecraft:horse", new UUID(9, 9),
                0, 0, 0, (byte) 0, (byte) 0);
        int before = updates().size();
        svc.tick(20_000, List.of(viewer, snapFull(T1, "Far", "minecraft:overworld", x, 64, 0,
                false, false, true, 0, null, null, horse)), s, sender());
        assertEquals(before + 1, updates().size(), "a mount change forces through the tier");
    }

    // ---- The E1-review pins (re-landed at E2 — the E1 fix batch wrote and RAN these
    // ---- green, but an external file-watcher revert ate the write before the commit;
    // ---- incident recorded in the progress doc) + the E2 m7 rest-frame pin.

    @Test
    void stopAfterMotionShipsExactlyOneRestFrameThenSuppresses() {
        // E2 review m7: the last moving frame carries a nonzero velocity hint; without
        // a forced rest frame the client dead-reckons ~1.5 windows past the true stop
        // and parks there. One zero-velocity frame must ship, then suppression.
        var svc = subscribed();
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        var moving = new FarPlayerBroadcastService.PlayerSnapshot(T1, "T",
                "minecraft:overworld", 500, 64, 0, 0f, 0f, 0f, (byte) 0,
                12.0, 0, 0, false, false, true, false, false, 0L, null, null, null);
        svc.tick(10_000, List.of(viewer, moving), settings("on"), sender());
        int afterMoving = updates().size();

        var resting = new FarPlayerBroadcastService.PlayerSnapshot(T1, "T",
                "minecraft:overworld", 500, 64, 0, 0f, 0f, 0f, (byte) 0,
                0, 0, 0, false, false, true, false, false, 0L, null, null, null);
        svc.tick(10_500, List.of(viewer, resting), settings("on"), sender());
        var u = updates();
        assertEquals(afterMoving + 1, u.size(), "the stop ships one rest frame");
        assertEquals(0, u.get(u.size() - 1).entries().get(0).velX(),
                "the rest frame carries the zero velocity that stops extrapolation");

        svc.tick(11_000, List.of(viewer, resting), settings("on"), sender());
        assertEquals(afterMoving + 1, updates().size(),
                "after the rest frame, an unchanged target suppresses again");
    }

    @Test
    void theLadderVerdictDropsTheTargetFromRosterAndUpdatesWhateverTheSettingSays() {
        // hidden = vanish metadata, a disguise, a failed or raced read: unconditional
        // (hide-permission plan §2.3). The server setting must never widen who is shown.
        for (boolean hideHolders : new boolean[]{false, true}) {
            sent.clear();
            var svc = subscribed();
            svc.tick(10_000, List.of(snap(VIEWER, "Viewer", 0, 0),
                    hiddenSnap(T1, "Hidden", 500, 0), snap(T2, "Plain", 600, 0)),
                    settings("on", hideHolders), sender());
            var r = rosters();
            assertEquals(1, r.size());
            assertEquals(1, r.get(0).added().size(),
                    "the hidden player never enters the roster, hideHolders=" + hideHolders);
            assertEquals("Plain", r.get(0).added().get(0).name());
            for (var u : updates()) {
                assertEquals(1, u.entries().size(), "no update entry for a hidden player");
            }
        }
    }

    // ---- hide-permission plan §2/§5 (0.21.0): the permission no longer hides by itself ----

    @Test
    void aPermissionHolderIsServedWhileTheSettingIsOffAndWithheldWhileItIsOn() {
        var world = List.of(snap(VIEWER, "Viewer", 0, 0),
                holderSnap(T1, "Admin", 500, 0), snap(T2, "Plain", 600, 0));

        var off = subscribed();
        off.tick(10_000, world, settings("on", false), sender());
        assertEquals(List.of(T1, T2), rosterUuids(),
                "setting off (the default): a holder is shown like anyone else");
        assertEquals(2, updates().get(0).entries().size(), "and its position is sent");

        sent.clear();
        var on = subscribed();
        on.tick(10_000, world, settings("on", true), sender());
        assertEquals(List.of(T2), rosterUuids(),
                "setting on: every holder is withheld, as before 0.21.0");
        for (var u : updates()) {
            assertEquals(1, u.entries().size(), "no update entry for a withheld holder");
        }
    }

    @Test
    void turningTheSettingOnWithdrawsAnAlreadyServedHolderOnTheNextPass() {
        // Live effect (no restart, no rejoin): the per-tick settings snapshot decides, and
        // the roster diff removes the holder; turning it back off re-adds it.
        var svc = subscribed();
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), holderSnap(T1, "Admin", 500, 0));
        svc.tick(10_000, world, settings("on", false), sender());
        assertEquals(List.of(T1), rosterUuids());
        int index = rosters().get(0).added().get(0).index();

        svc.tick(10_500, world, settings("on", true), sender());
        var removal = rosters().get(1);
        assertFalse(removal.full());
        assertArrayEquals(new int[]{index}, removal.removedIndices(),
                "the holder leaves the viewer's roster on the first pass after the reload");
        int updatesAfterRemoval = updates().size();
        svc.tick(11_000, world, settings("on", true), sender());
        assertEquals(updatesAfterRemoval, updates().size(), "and no position follows");

        svc.tick(11_500, world, settings("on", false), sender());
        assertEquals(T1, rosters().get(rosters().size() - 1).added().get(0).uuid(),
                "turning it off again shows the holder again");
    }

    @Test
    void aHoldersOwnOptOutAndShareDistanceAreHonoredInBothSettings() {
        // Opt-out is NOT gated on the permission (plan §2.4): every player keeps the
        // choice, holder or not, whatever the server setting says.
        for (boolean hideHolders : new boolean[]{false, true}) {
            sent.clear();
            var svc = subscribed();
            svc.onPrefs(T1, prefs(true, false));                       // holder opted out
            svc.onPrefs(T2, prefs(true, false));                       // plain player opted out
            var near = new UUID(0, 5);
            svc.onPrefs(near, new FarPlayerWire.Prefs(true, 0, 0, true, 300)); // shares within 300
            svc.tick(10_000, List.of(snap(VIEWER, "Viewer", 0, 0),
                    holderSnap(T1, "AdminOptOut", 500, 0), snap(T2, "PlainOptOut", 600, 0),
                    holderSnap(near, "AdminNearOnly", 700, 0)),
                    settings("on", hideHolders), sender());
            assertEquals(List.of(), rosterUuids(),
                    "explicit opt-outs and the share distance bind, hideHolders=" + hideHolders);
        }
    }

    @Test
    void optInModeIsUnchangedForHoldersAndTheSettingStillWithholdsThem() {
        var vanilla = new UUID(0, 4);
        var world = List.of(snap(VIEWER, "Viewer", 0, 0),
                holderSnap(T1, "AdminSharing", 500, 0),               // holder, shareSelf=true
                holderSnap(T2, "AdminSilent", 500, 100),              // holder, no prefs at all
                snap(vanilla, "Vanilla", 500, 200));                  // no prefs at all

        var off = subscribed();
        off.onPrefs(T1, prefs(true, true));
        off.tick(10_000, world, settings("opt-in", false), sender());
        assertEquals(List.of(T1), rosterUuids(),
                "opt-in: only explicit sharers, holder or not (the permission adds nothing)");

        sent.clear();
        var on = subscribed();
        on.onPrefs(T1, prefs(true, true));
        on.tick(10_000, world, settings("opt-in", true), sender());
        assertEquals(List.of(), rosterUuids(),
                "opt-in with the setting on: a sharing holder is still withheld");
    }

    @Test
    void theRestOfTheLadderStillAppliesToAHolderWhileTheSettingIsOff() {
        // The permission fact must not become a way AROUND another rung: an excluded,
        // invisible, spectating or vanish-bridge-hidden holder stays withheld.
        var bridgeHidden = new UUID(0, 7);
        var svc = new FarPlayerBroadcastService((viewer, target) -> !target.equals(bridgeHidden));
        svc.subscribeViewer(VIEWER);
        svc.onPrefs(VIEWER, prefs(true, true));
        var world = new ArrayList<FarPlayerBroadcastService.PlayerSnapshot>();
        world.add(snap(VIEWER, "Viewer", 0, 0));
        world.add(holderSnap(T1, "Excluded", 500, 0));
        world.add(new FarPlayerBroadcastService.PlayerSnapshot(new UUID(0, 12), "Spec",
                "minecraft:overworld", 100, 64, 0, 0f, 0f, 0f, (byte) 0, 0, 0, 0,
                true, false, true, false, true, 0L, null, null, null));
        world.add(new FarPlayerBroadcastService.PlayerSnapshot(new UUID(0, 13), "Invis",
                "minecraft:overworld", 100, 64, 0, 0f, 0f, 0f, (byte) 0, 0, 0, 0,
                false, true, true, false, true, 0L, null, null, null));
        world.add(holderSnap(bridgeHidden, "Vanished", 100, 0));
        world.add(privacySnap(new UUID(0, 14), "VanishedHolder", 100, 0, true, true));
        world.add(holderSnap(T2, "Shown", 600, 0));
        var s = new FarPlayerBroadcastService.Settings("on", 2048, 0, false,
                List.of("excluded"), 10, false);
        svc.tick(10_000, world, s, sender());
        assertEquals(List.of(T2), rosterUuids(), "only the holder no other rung withholds is shown");
    }

    @Test
    void theUpgradeNoticeFiresOnceNamingTheFirstHolderSeenWhileTheSettingIsOff() {
        var notices = new ArrayList<String>();
        var svc = new FarPlayerBroadcastService(null, notices::add);
        svc.subscribeViewer(VIEWER);
        svc.onPrefs(VIEWER, prefs(true, true));
        var plain = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T2, "Plain", 600, 0));
        svc.tick(10_000, plain, settings("on", false), sender());
        assertTrue(notices.isEmpty(), "no holder online: nothing to announce");

        var withHolders = List.of(snap(VIEWER, "Viewer", 0, 0),
                holderSnap(T1, "AdminOne", 500, 0), holderSnap(T2, "AdminTwo", 600, 0));
        svc.tick(10_500, withHolders, settings("on", false), sender());
        assertEquals(1, notices.size(), "one line, however many holders are online");
        String line = notices.get(0);
        assertTrue(line.contains("AdminOne"), line);
        assertFalse(line.contains("AdminTwo"), line);
        assertTrue(line.contains("lss.farplayers.hidden") && line.contains("vss.farplayers.hidden"), line);
        assertTrue(line.contains("no longer hides a player by itself"), line);
        assertTrue(line.contains("0.21.0"), line);
        assertTrue(line.contains("Share My Position"), line);
        assertTrue(line.contains("far_players.hide_permission_holders: true"), line);
        assertTrue(line.contains("far_players.excluded_players"), "the other way to hide named players: " + line);

        for (int i = 0; i < 5; i++) {
            svc.tick(11_000 + i * 500, withHolders, settings("on", false), sender());
        }
        svc.tick(20_000, withHolders, settings("on", true), sender());
        svc.tick(20_500, withHolders, settings("on", false), sender());
        assertEquals(1, notices.size(), "latched for the life of the service (one server start)");

        var restarted = new FarPlayerBroadcastService(null, notices::add);
        restarted.subscribeViewer(VIEWER);
        restarted.onPrefs(VIEWER, prefs(true, true));
        restarted.tick(10_000, withHolders, settings("on", false), sender());
        assertEquals(2, notices.size(), "the latch lives in the service: a new start announces again");
    }

    @Test
    void theUpgradeNoticeStaysSilentWhileTheSettingIsOnOrTheModeIsOff() {
        var notices = new ArrayList<String>();
        var svc = new FarPlayerBroadcastService(null, notices::add);
        svc.subscribeViewer(VIEWER);
        svc.onPrefs(VIEWER, prefs(true, true));
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), holderSnap(T1, "Admin", 500, 0));
        svc.tick(10_000, world, settings("on", true), sender());
        assertTrue(notices.isEmpty(), "the operator already chose the old behaviour");
        svc.tick(10_500, world, settings("off", false), sender());
        assertTrue(notices.isEmpty(), "mode off: nobody is shown, nothing changed for holders");
        // A ladder-hidden player whose permission could not be read is not a holder.
        svc.tick(11_000, List.of(snap(VIEWER, "Viewer", 0, 0), hiddenSnap(T1, "Raced", 500, 0)),
                settings("on", false), sender());
        assertTrue(notices.isEmpty());
        svc.tick(11_500, world, settings("on", false), sender());
        assertEquals(1, notices.size(), "armed until the first holder is seen with the setting off");
    }

    @Test
    void theDiagLineGainsThePrivacyTokensOnlyWhenTheyAreNonZero() {
        var svc = subscribed();
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        assertEquals("FarPlayers: subs=1, rosters=0, updates=0, entries=0, suppressed=0, bytes=0",
                svc.diagLine(), "the pre-0.21.0 line, byte for byte, before any pass");

        svc.tick(10_000, List.of(viewer, snap(T1, "Plain", 500, 0)), settings("on", false), sender());
        assertFalse(svc.diagLine().contains("hidden="), svc.diagLine());
        assertFalse(svc.diagLine().contains("perm_holders="), svc.diagLine());
        assertTrue(svc.diagLine().matches(
                "FarPlayers: subs=1, rosters=1, updates=1, entries=1, suppressed=0, bytes=\\d+"),
                "no holder, nobody hidden: the line keeps its old shape: " + svc.diagLine());

        // Two holders (one of them also ladder-hidden) and one vanished player, setting off.
        var mixed = List.of(viewer, holderSnap(T1, "Admin", 500, 0),
                privacySnap(T2, "VanishedAdmin", 600, 0, true, true),
                hiddenSnap(new UUID(0, 9), "Vanished", 700, 0));
        svc.tick(10_500, mixed, settings("on", false), sender());
        assertTrue(svc.diagLine().endsWith(", hidden=2, perm_holders=2"), svc.diagLine());
        assertEquals(2, svc.lastPassHidden());
        assertEquals(2, svc.lastPassPermissionHolders());

        // Setting on: the holder is withheld too, and no holder is "shown despite the node".
        svc.tick(11_000, mixed, settings("on", true), sender());
        assertTrue(svc.diagLine().endsWith(", hidden=3"), svc.diagLine());
        assertFalse(svc.diagLine().contains("perm_holders="), svc.diagLine());

        // The counts describe the LAST pass: they follow the world, and clear when no pass
        // can run (mode off, or the last subscriber gone).
        svc.tick(11_500, List.of(viewer, holderSnap(T1, "Admin", 500, 0)), settings("on", false), sender());
        assertTrue(svc.diagLine().endsWith(", perm_holders=1"), svc.diagLine());
        assertFalse(svc.diagLine().contains("hidden="), svc.diagLine());
        svc.applyMode("off", sender());
        assertFalse(svc.diagLine().contains("perm_holders="), "mode off: " + svc.diagLine());
        svc.tick(12_000, mixed, settings("on", false), sender());
        assertTrue(svc.diagLine().endsWith(", hidden=2, perm_holders=2"), svc.diagLine());
        svc.removeViewer(VIEWER);
        assertFalse(svc.diagLine().contains("hidden=") || svc.diagLine().contains("perm_holders="),
                "no subscriber, no pass: " + svc.diagLine());
        svc.subscribeViewer(VIEWER);
        svc.tick(12_500, mixed, settings("on", false), sender());
        svc.onDisconnect(VIEWER);
        assertFalse(svc.diagLine().contains("hidden=") || svc.diagLine().contains("perm_holders="),
                "disconnect of the last subscriber: " + svc.diagLine());
    }

    @Test
    void withheldIncrementalRosterCommitsNothingAndRetriesNextTick() {
        var svc = subscribed();
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        svc.tick(10_000, List.of(viewer, snap(T1, "A", 500, 0)), settings("on"), sender());
        assertEquals(1, rosters().size());

        // T2 arrives while the channel is unwritable: the frame is withheld and the
        // membership commit must NOT happen (commit-on-success — a burned index with
        // no delivered add is a permanently silent player).
        sendResult = false;
        svc.tick(10_500, List.of(viewer, snap(T1, "A", 500, 0), snap(T2, "B", 600, 0)),
                settings("on"), sender());
        assertEquals(1, rosters().size());

        sendResult = true;
        svc.tick(11_000, List.of(viewer, snap(T1, "A", 500, 0), snap(T2, "B", 600, 0)),
                settings("on"), sender());
        var r = rosters();
        assertEquals(2, r.size(), "the add re-sends once the channel drains");
        assertEquals("B", r.get(1).added().get(0).name());
    }

    @Test
    void withheldUpdatesLeaveRowsUncommittedSoAStationaryTargetStillHeals() {
        var svc = subscribed();
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        svc.tick(10_000, List.of(viewer, snap(T1, "T", 500, 0)), settings("on"), sender());
        int before = updates().size();

        // The target moves, but the frame is withheld — then STOPS moving. If rows
        // advanced at scan time, delta suppression would eat every later frame and the
        // client would keep the stale position forever.
        sendResult = false;
        svc.tick(10_500, List.of(viewer, snap(T1, "T", 510, 0)), settings("on"), sender());
        assertEquals(before, updates().size());

        sendResult = true;
        svc.tick(11_000, List.of(viewer, snap(T1, "T", 510, 0)), settings("on"), sender());
        var u = updates();
        assertEquals(before + 1, u.size(), "the uncommitted row re-sends the missed movement");
        assertEquals(FarPlayerWire.quantizePos(510),
                u.get(u.size() - 1).entries().get(0).quantX());
    }

    @Test
    void updatesChunkUnderTheDictionaryBoundAndStayDecodable() {
        var svc = subscribed();
        var world = new ArrayList<FarPlayerBroadcastService.PlayerSnapshot>();
        world.add(snap(VIEWER, "Viewer", 0, 0));
        // 150 targets x 6 unique equipment identities = 900 dictionary entries — over
        // MAX_DICT_ENTRIES (512) in one frame. Encode must chunk, decode must accept.
        for (int i = 0; i < 150; i++) {
            var ids = new String[FarPlayerWire.EQUIPMENT_SLOTS];
            var counts = new int[FarPlayerWire.EQUIPMENT_SLOTS];
            for (int slot = 0; slot < ids.length; slot++) {
                ids[slot] = "modx:item_" + i + "_" + slot;
                counts[slot] = 1;
            }
            world.add(snapFull(new UUID(1, i), "P" + i, "minecraft:overworld",
                    200 + i, 64, 0, false, false, true, 1000 + i, ids, counts, null));
        }
        svc.tick(10_000, world, settings("on"), sender());
        var u = updates();
        assertTrue(u.size() >= 2, "the first geared frame must chunk, got " + u.size());
        int total = u.stream().mapToInt(f -> f.entries().size()).sum();
        assertEquals(150, total, "chunking preserves every entry exactly once");
    }

    @Test
    void visibleSetCapsNearestFirstAtTheWireBound() {
        var svc = subscribed();
        var world = new ArrayList<FarPlayerBroadcastService.PlayerSnapshot>();
        world.add(snap(VIEWER, "Viewer", 0, 0));
        var s = new FarPlayerBroadcastService.Settings("on", 16384, 0, false, List.of(), 10, false);
        for (int i = 0; i < FarPlayerWire.MAX_UPDATE_ENTRIES + 50; i++) {
            world.add(snap(new UUID(2, i), "P" + i, 200 + i * 4.0, 0));
        }
        svc.tick(10_000, world, s, sender());
        var r = rosters();
        assertEquals(1, r.size());
        assertEquals(FarPlayerWire.MAX_UPDATE_ENTRIES, r.get(0).added().size(),
                "the served set caps at the wire bound");
        assertTrue(r.get(0).added().stream().anyMatch(e -> e.name().equals("P0")),
                "nearest targets survive the cap");
        assertTrue(r.get(0).added().stream()
                        .noneMatch(e -> e.name().equals("P" + (FarPlayerWire.MAX_UPDATE_ENTRIES + 49))),
                "the farthest target is the one dropped");
    }

    // ---- target-prefs retention (service-permission-gate implementation review) ----

    @Test
    void aShareSelfOptOutSurvivesTheViewerShed() {
        // The E2 prefs-carrier rule, viewer-shed direction: the service gate's
        // revocation composite (and a NO_CONSUMER re-handshake) calls removeViewer on
        // an ONLINE player — their opt-out must keep binding the target filter, or the
        // revocation makes them visible to everyone against their explicit preference.
        var svc = subscribed();
        svc.onPrefs(T1, prefs(true, false)); // T1 opted OUT of being shown
        svc.removeViewer(T1);                // the revocation composite's shed

        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "Hidden", 500, 0));
        svc.tick(10_000, world, settings("on"), sender());
        assertTrue(rosters().stream().flatMap(r -> r.added().stream())
                        .noneMatch(e -> e.uuid().equals(T1)),
                "the retained opt-out must keep the shed player OFF every roster");
    }

    @Test
    void aResubscriptionSeedsTheRetainedPrefsSoServingResumes() {
        // The grant re-offer re-subscribes server-side with NO client re-handshake and
        // no prefs re-send (the client's send-once latch holds): the fresh viewer
        // state must seed from the retained prefs or tick() skips it forever.
        var svc = new FarPlayerBroadcastService(null);
        svc.subscribeViewer(VIEWER);
        svc.onPrefs(VIEWER, prefs(true, true));
        svc.removeViewer(VIEWER);            // revocation composite
        svc.subscribeViewer(VIEWER);         // the grant sweep's replayed registration

        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "T", 500, 0));
        svc.tick(10_000, world, settings("on"), sender());
        assertFalse(sent.isEmpty(),
                "the re-subscribed viewer serves again without a prefs re-send — "
                        + "otherwise far players stay dead until rejoin after every re-offer");
    }

    @Test
    void anUnsubscribedSendersPrefsAreRetainedForTheTargetFilter() {
        // A gate-DENIED client never registers a viewer, but its client still delivers
        // the shareSelf opt-out — the receipt binds the TARGET filter even without a
        // viewer session.
        var svc = subscribed();              // VIEWER subscribed; T1 never is
        svc.onPrefs(T1, prefs(true, false)); // denied player's opt-out
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "Denied", 500, 0));
        svc.tick(10_000, world, settings("on"), sender());
        assertTrue(rosters().stream().flatMap(r -> r.added().stream())
                        .noneMatch(e -> e.uuid().equals(T1)),
                "an unsubscribed sender's opt-out must still bind the target filter");
    }

    @Test
    void disconnectForgetsTheRetainedPrefs() {
        var svc = new FarPlayerBroadcastService(null);
        svc.subscribeViewer(T1);
        svc.onPrefs(T1, prefs(true, false));
        svc.onDisconnect(T1);                // the connection died
        svc.subscribeViewer(VIEWER);
        svc.onPrefs(VIEWER, prefs(true, true));

        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "Back", 500, 0));
        svc.tick(10_000, world, settings("on"), sender());
        assertTrue(rosters().stream().flatMap(r -> r.added().stream())
                        .anyMatch(e -> e.uuid().equals(T1)),
                "retained prefs are CONNECTION-scoped: a rejoin (same UUID, fresh "
                        + "session, no prefs yet) starts from the mode default again");
    }

    // ---- hide-permission plan §2b (0.21.0): mode "required" and the honest notice ----

    private record Told(UUID player, FarPlayerBroadcastService.ChatLine line) {}

    private final List<Told> told = new ArrayList<>();
    private boolean reachable = true;
    private RuntimeException notifierFailure;

    /** A service whose chat lane records what each player was told; nobody is subscribed. */
    private FarPlayerBroadcastService unsubscribedToldService(FarPlayerBroadcastService.VanishBridge bridge) {
        return new FarPlayerBroadcastService(bridge, logged -> { }, (player, line) -> {
            if (notifierFailure != null) throw notifierFailure;
            if (!reachable) return false;
            told.add(new Told(player, line));
            return true;
        });
    }

    /** The same with VIEWER subscribed and receiving far players. */
    private FarPlayerBroadcastService toldService(FarPlayerBroadcastService.VanishBridge bridge) {
        var svc = unsubscribedToldService(bridge);
        svc.subscribeViewer(VIEWER);
        svc.onPrefs(VIEWER, prefs(true, true));
        return svc;
    }

    private static FarPlayerWire.Prefs shareWithin(int blocks) {
        return new FarPlayerWire.Prefs(true, 0, 0, true, blocks);
    }

    private static final UUID T3 = new UUID(0, 4);
    private static final UUID T4 = new UUID(0, 5);

    /** Two consecutive passes: what mode "required" needs before it acts on "not a holder". */
    private void twoPasses(FarPlayerBroadcastService svc, long at,
                           List<FarPlayerBroadcastService.PlayerSnapshot> world,
                           FarPlayerBroadcastService.Settings settings) {
        svc.tick(at, world, settings, sender());
        svc.tick(at + 500, world, settings, sender());
    }

    /** The roster {@code viewer}'s client holds after every frame sent so far, in index order. */
    private List<UUID> currentRoster(UUID viewer) {
        var byIndex = new java.util.TreeMap<Integer, UUID>();
        for (var s : sent) {
            if (!s.viewer().equals(viewer) || !s.channel().equals(LSSConstants.CHANNEL_FAR_PLAYER_ROSTER)) continue;
            var r = FarPlayerWire.decodeRoster(s.body());
            if (r.full()) byIndex.clear();
            for (var e : r.added()) byIndex.put(e.index(), e.uuid());
            for (int idx : r.removedIndices()) byIndex.remove(idx);
        }
        return List.copyOf(byIndex.values());
    }

    private List<UUID> currentRoster() {
        return currentRoster(VIEWER);
    }

    /** The players sent the required-mode line, in order. */
    private List<UUID> toldPlayers() {
        return told.stream().filter(t -> t.line().equals(FarPlayerBroadcastService.requiredSharingNotice()))
                .map(Told::player).toList();
    }

    @Test
    void requiredModeServesANonHolderWhoOptedOutOrLimitedTheShareDistance() {
        var world = List.of(snap(VIEWER, "Viewer", 0, 0),
                snap(T1, "OptedOut", 500, 0),               // shareSelf=false
                snap(T2, "NearOnly", 600, 0),               // shares within 300, is 600 away
                snap(T3, "Vanilla", 700, 0));               // never sent preferences

        // Control: mode "on" honors both choices (unchanged behaviour).
        var on = toldService(null);
        on.onPrefs(T1, prefs(true, false));
        on.onPrefs(T2, shareWithin(300));
        twoPasses(on, 10_000, world, settings("on"));
        assertEquals(List.of(T3), currentRoster(), "mode on: opt-out and share distance bind");

        sent.clear();
        var required = toldService(null);
        required.onPrefs(T1, prefs(true, false));
        required.onPrefs(T2, shareWithin(300));
        required.tick(10_000, world, settings("required"), sender());
        assertEquals(List.of(T3), currentRoster(),
                "the first pass: one 'not a holder' read is not acted on yet, and a player"
                        + " without preferences is shown as in mode on");
        required.tick(10_500, world, settings("required"), sender());
        assertEquals(List.of(T3, T1, T2), currentRoster(),
                "mode required: a non-holder's opt-out and share distance are ignored");
        assertEquals(2, updates().get(updates().size() - 1).entries().size(),
                "and their positions are sent");
    }

    @Test
    void requiredModeHonorsAHoldersOwnChoiceExactlyLikeModeOn() {
        var svc = toldService(null);
        svc.onPrefs(T1, prefs(true, false));                // holder, opted out
        svc.onPrefs(T2, shareWithin(300));                  // holder, 600 away: beyond its limit
        svc.onPrefs(T3, shareWithin(300));                  // holder, 200 away: inside its limit
        svc.onPrefs(T4, prefs(true, true));                 // holder who did not opt out
        var silent = new UUID(0, 6);                        // holder who never sent preferences
        var world = List.of(snap(VIEWER, "Viewer", 0, 0),
                holderSnap(T1, "HolderOptedOut", 500, 0), holderSnap(T2, "HolderFar", 600, 0),
                holderSnap(T3, "HolderNear", 200, 0), holderSnap(T4, "HolderSharing", 700, 0),
                holderSnap(silent, "HolderSilent", 800, 0));
        twoPasses(svc, 10_000, world, settings("required"));
        svc.tick(11_000, world, settings("required"), sender());
        assertEquals(List.of(T3, T4, silent), currentRoster(),
                "the permission means 'may hide': a holder's opt-out and share distance bind,"
                        + " and a holder who shares is shown");
        assertTrue(told.isEmpty(), "nothing of a holder's is overridden: " + told);
    }

    @Test
    void requiredModeWithHidePermissionHoldersOnHidesEveryHolder() {
        var svc = toldService(null);
        svc.onPrefs(T1, prefs(true, true));                 // a holder who shares
        svc.onPrefs(T3, prefs(true, false));                // a non-holder who opted out
        twoPasses(svc, 10_000, List.of(snap(VIEWER, "Viewer", 0, 0),
                holderSnap(T1, "HolderSharing", 500, 0), holderSnap(T2, "HolderSilent", 600, 0),
                snap(T3, "OptedOut", 700, 0)),
                settings("required", true));
        assertEquals(List.of(T3), currentRoster(),
                "hide_permission_holders hides holders automatically in every mode, and"
                        + " required still shows everyone else");
    }

    @Test
    void requiredModeKeepsEveryUnconditionalRungAndTheViewersOwnDistancePrefs() {
        // "required" overrides ONLY the target's own sharing choices. Every target below
        // opted out, so the override is live for each of them; another rung withholds each.
        var bridgeHidden = new UUID(0, 7);
        var svc = toldService((viewer, target) -> !target.equals(bridgeHidden));
        svc.onPrefs(VIEWER, new FarPlayerWire.Prefs(true, 1000, 100, true, 0)); // viewer: 100..1000 blocks
        var world = new ArrayList<FarPlayerBroadcastService.PlayerSnapshot>();
        world.add(snap(VIEWER, "Viewer", 0, 0));
        var withheld = new ArrayList<UUID>();
        java.util.function.Consumer<FarPlayerBroadcastService.PlayerSnapshot> add = s -> {
            svc.onPrefs(s.uuid(), prefs(true, false));
            world.add(s);
            withheld.add(s.uuid());
        };
        add.accept(hiddenSnap(new UUID(0, 10), "Vanished", 500, 0));            // the ladder
        add.accept(snap(new UUID(0, 11), "Excluded", 500, 0));                  // excluded_players
        add.accept(snapFull(new UUID(0, 12), "Spec", "minecraft:overworld", 500, 64, 0,
                true, false, true, 0, null, null, null));                       // send_spectators off
        add.accept(snapFull(new UUID(0, 13), "Invis", "minecraft:overworld", 500, 64, 0,
                false, true, true, 0, null, null, null));                       // invisibility
        add.accept(snap(bridgeHidden, "BridgeVanished", 500, 0));               // the vanish bridge
        add.accept(snapFull(new UUID(0, 14), "OtherDim", "minecraft:the_end", 500, 64, 0,
                false, false, true, 0, null, null, null));
        add.accept(snapFull(new UUID(0, 15), "Dead", "minecraft:overworld", 500, 64, 0,
                false, false, false, 0, null, null, null));
        add.accept(snap(new UUID(0, 16), "BeyondViewerMax", 1500, 0));          // viewer max 1000
        add.accept(snap(new UUID(0, 17), "InsideViewerMin", 50, 0));            // viewer min 100
        add.accept(snap(new UUID(0, 18), "BeyondServerMax", 3000, 0));          // server max 2048
        svc.onPrefs(T1, prefs(true, false));
        world.add(snap(T1, "Shown", 500, 0));                                   // the one overridden
        var s = new FarPlayerBroadcastService.Settings("required", 2048, 0, false,
                List.of("excluded"), 10, false);
        twoPasses(svc, 10_000, world, s);
        svc.tick(11_000, world, s, sender());
        assertEquals(List.of(T1), currentRoster(),
                "only the target no other rung withholds is shown; withheld=" + withheld);
    }

    // ---- review fold §1: one "not a holder" read is never acted on ----

    @Test
    void aSingleNotAHolderReadNeverServesOrTellsAnOptedOutHolder() {
        // A quiet wrong "false" (Folia: the permissible read while its owner's region thread
        // rebuilds it; Fabric/NeoForge/Sponge: the loader seam answering doubt) must not
        // expose an opted-out holder for an interval, nor send them the false notice.
        var svc = toldService(null);
        svc.onPrefs(T1, prefs(true, false));                // a holder who opted out
        svc.onPrefs(T2, shareWithin(300));                  // a holder who shares within 300
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        var asHolders = List.of(viewer, holderSnap(T1, "Holder", 500, 0), holderSnap(T2, "HolderFar", 600, 0));
        var quietFalse = List.of(viewer, snap(T1, "Holder", 500, 0), snap(T2, "HolderFar", 600, 0));
        long now = 10_000;
        for (var world : List.of(asHolders, quietFalse, asHolders, quietFalse, asHolders, asHolders, quietFalse)) {
            svc.tick(now += 500, world, settings("required"), sender());
            assertEquals(List.of(), currentRoster(), "never shown, at " + now);
            assertTrue(told.isEmpty(), "never told their setting does not apply, at " + now + ": " + told);
            assertEquals(0, svc.lastPassOverridden(), "never counted, at " + now);
        }
    }

    @Test
    void twoConsecutiveNotAHolderReadsServeAndTell() {
        var svc = toldService(null);
        svc.onPrefs(T1, prefs(true, false));
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "OptedOut", 500, 0));
        svc.tick(10_000, world, settings("required"), sender());
        assertEquals(List.of(), currentRoster(), "the first read: their choice still binds");
        assertTrue(told.isEmpty());
        assertEquals(0, svc.lastPassOverridden());
        assertFalse(svc.hasRequiredSharingNotice(T1));

        svc.tick(10_500, world, settings("required"), sender());
        assertEquals(List.of(T1), currentRoster(), "the second consecutive read: shown");
        assertEquals(List.of(T1), toldPlayers(), "and told in the same pass");
        assertEquals(1, svc.lastPassOverridden());
    }

    @Test
    void theConfirmationStartsAgainAfterAHolderReadAFailedReadOrAMissedPass() {
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        var notAHolder = List.of(viewer, snap(T1, "P", 500, 0));
        var interruptions = new java.util.LinkedHashMap<String, List<FarPlayerBroadcastService.PlayerSnapshot>>();
        interruptions.put("a holder read", List.of(viewer, holderSnap(T1, "P", 500, 0)));
        interruptions.put("a failed read (hidden, no permission fact)", List.of(viewer, hiddenSnap(T1, "P", 500, 0)));
        interruptions.put("a pass that skipped the player", List.of(viewer));
        for (var interruption : interruptions.entrySet()) {
            sent.clear();
            told.clear();
            var svc = toldService(null);
            svc.onPrefs(T1, prefs(true, false));
            svc.tick(10_000, notAHolder, settings("required"), sender());
            svc.tick(10_500, interruption.getValue(), settings("required"), sender());
            svc.tick(11_000, notAHolder, settings("required"), sender());
            assertEquals(List.of(), currentRoster(),
                    "false, then " + interruption.getKey() + ", then false is not two consecutive reads");
            assertTrue(told.isEmpty(), interruption.getKey() + ": " + told);
            svc.tick(11_500, notAHolder, settings("required"), sender());
            assertEquals(List.of(T1), currentRoster(), "the next consecutive read confirms, after " + interruption.getKey());
            assertEquals(List.of(T1), toldPlayers());
        }
    }

    @Test
    void theConfirmationStartsAgainAfterLeavingRequiredAndAfterADisconnect() {
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "OptedOut", 500, 0));

        // The mode leaves "required" for one pass (on honors the opt-out anyway).
        var left = toldService(null);
        left.onPrefs(T1, prefs(true, false));
        left.tick(10_000, world, settings("required"), sender());
        left.tick(10_500, world, settings("on"), sender());
        left.tick(11_000, world, settings("required"), sender());
        assertEquals(List.of(), currentRoster(), "a read from before the mode change does not count");
        assertTrue(told.isEmpty());
        left.tick(11_500, world, settings("required"), sender());
        assertEquals(List.of(T1), currentRoster());
        assertEquals(1, told.size());

        // Mode off in between.
        sent.clear();
        told.clear();
        var off = toldService(null);
        off.onPrefs(T1, prefs(true, false));
        off.tick(10_000, world, settings("required"), sender());
        off.applyMode("off", sender());
        off.tick(10_500, world, settings("required"), sender());
        assertEquals(List.of(), currentRoster(), "a read from before the mode went off does not count");
        assertTrue(told.isEmpty());
        off.tick(11_000, world, settings("required"), sender());
        assertEquals(List.of(T1), currentRoster());

        // The connection ends and the player is back within the same interval.
        sent.clear();
        told.clear();
        var rejoined = toldService(null);
        rejoined.onPrefs(T1, prefs(true, false));
        rejoined.tick(10_000, world, settings("required"), sender());
        rejoined.onDisconnect(T1);
        rejoined.onPrefs(T1, prefs(true, false));
        rejoined.tick(10_500, world, settings("required"), sender());
        assertEquals(List.of(), currentRoster(), "a read from the previous connection does not count");
        assertTrue(told.isEmpty());
        rejoined.tick(11_000, world, settings("required"), sender());
        assertEquals(List.of(T1), currentRoster());
        assertEquals(1, told.size());
    }

    // ---- the honest notice ----

    @Test
    void anOverriddenNonHolderIsToldOncePerConnectionAndNobodyElseIs() {
        var svc = toldService(null);
        svc.onPrefs(T1, prefs(true, false));                // non-holder, opted out: overridden
        svc.onPrefs(T2, prefs(true, false));                // holder, opted out: honored
        svc.onPrefs(T3, prefs(true, true));                 // shares anyway
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "OptedOut", 500, 0),
                holderSnap(T2, "HolderOptedOut", 600, 0), snap(T3, "Sharer", 700, 0),
                snap(T4, "Vanilla", 800, 0));
        twoPasses(svc, 10_000, world, settings("required"));
        assertEquals(List.of(new Told(T1, FarPlayerBroadcastService.requiredSharingNotice())), told,
                "exactly the overridden player");
        assertEquals("This server requires position sharing: other players can see where you are"
                        + " from far away, and your Share My Position setting does not apply here.",
                told.get(0).line().fallback(), "exactly the approved wording, as the fallback");
        assertEquals("lss.far_players.sharing_required", told.get(0).line().translationKey());
        assertTrue(svc.hasRequiredSharingNotice(T1));
        assertFalse(svc.hasRequiredSharingNotice(T2));

        for (int i = 2; i <= 6; i++) {
            svc.tick(10_000 + i * 500, world, settings("required"), sender());
        }
        svc.onPrefs(T1, prefs(true, false));                // a byte-identical prefs re-send
        svc.onViewerDimensionChange(T1);
        svc.removeViewer(T1);                               // a same-connection shed
        twoPasses(svc, 20_000, world, settings("required"));
        assertEquals(1, told.size(), "one line per connection, whatever happens on it");
    }

    @Test
    void aShareDistanceLimitAloneIsAnOverrideToo() {
        var svc = toldService(null);
        svc.onPrefs(T1, shareWithin(300));
        twoPasses(svc, 10_000, List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "NearOnly", 100, 0)),
                settings("required"));
        assertEquals(List.of(T1), toldPlayers(),
                "the limit is ignored on this server, even while nobody is beyond it yet");
    }

    @Test
    void noNoticeInModeOnOrOptIn() {
        for (String mode : new String[]{"on", "opt-in"}) {
            told.clear();
            var svc = toldService(null);
            svc.onPrefs(T1, prefs(true, false));
            svc.onPrefs(T2, shareWithin(300));
            var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "OptedOut", 500, 0),
                    snap(T2, "NearOnly", 600, 0));
            twoPasses(svc, 10_000, world, settings(mode));
            svc.tick(11_000, world, settings(mode), sender());
            assertTrue(told.isEmpty(), "mode " + mode + " honors the choice: nothing to say");
            assertFalse(svc.diagLine().contains("overridden="), svc.diagLine());
        }
    }

    @Test
    void theNoticeFollowsARuntimeModeSwitchAndSwitchingBackHidesThePlayerAgain() {
        var svc = toldService(null);
        svc.onPrefs(T1, prefs(true, false));
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "OptedOut", 500, 0));
        twoPasses(svc, 10_000, world, settings("on"));
        assertTrue(told.isEmpty());
        assertEquals(List.of(), currentRoster(), "mode on: hidden by their own choice");

        twoPasses(svc, 11_000, world, settings("required"));       // the reload
        assertEquals(1, told.size(), "told once the switch is confirmed");
        assertEquals(List.of(T1), currentRoster(), "and shown from that pass on");
        int index = rosters().get(rosters().size() - 1).added().get(0).index();

        svc.tick(12_000, world, settings("on"), sender());         // the reload back
        var removal = rosters().get(rosters().size() - 1);
        assertFalse(removal.full());
        assertArrayEquals(new int[]{index}, removal.removedIndices(),
                "back in mode on the opt-out binds again: removed on the first pass");
        assertEquals(List.of(), currentRoster());
        int updatesAfterRemoval = updates().size();
        svc.tick(12_500, world, settings("on"), sender());
        assertEquals(updatesAfterRemoval, updates().size(), "and no position follows");

        svc.tick(13_000, world, settings("off"), sender());
        twoPasses(svc, 13_500, world, settings("required"));
        assertEquals(List.of(T1), currentRoster(), "required again: shown again");
        assertEquals(1, told.size(), "the mode coming back does not repeat the line");
    }

    @Test
    void theNoticeFollowsPreferencesArrivingUnderRequiredAndALostPermission() {
        var svc = toldService(null);
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        svc.tick(10_000, List.of(viewer, snap(T1, "A", 500, 0), holderSnap(T2, "B", 600, 0)),
                settings("required"), sender());
        assertTrue(told.isEmpty(), "no preferences yet: nothing is overridden");

        svc.onPrefs(T1, prefs(true, false));                // the opt-out arrives
        svc.onPrefs(T2, prefs(true, false));                // a holder's opt-out arrives
        svc.tick(10_500, List.of(viewer, snap(T1, "A", 500, 0), holderSnap(T2, "B", 600, 0)),
                settings("required"), sender());
        assertEquals(List.of(T1), toldPlayers(),
                "A was read 'not a holder' on both passes, so the late opt-out is overridden at once");

        // B loses the permission while opted out: confirmed on the second such read.
        svc.tick(11_000, List.of(viewer, snap(T1, "A", 500, 0), snap(T2, "B", 600, 0)),
                settings("required"), sender());
        assertEquals(List.of(T1), toldPlayers(), "one read is not enough");
        assertEquals(List.of(T1), currentRoster());
        svc.tick(11_500, List.of(viewer, snap(T1, "A", 500, 0), snap(T2, "B", 600, 0)),
                settings("required"), sender());
        assertEquals(List.of(T1, T2), toldPlayers());
        assertEquals(List.of(T1, T2), currentRoster());
    }

    @Test
    void theNoticeIsReArmedByAReconnectOnly() {
        var svc = toldService(null);
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "OptedOut", 500, 0));
        svc.onPrefs(T1, prefs(true, false));
        twoPasses(svc, 10_000, world, settings("required"));
        assertEquals(1, told.size());

        svc.onDisconnect(T1);                               // the connection ended
        assertFalse(svc.hasRequiredSharingNotice(T1));
        svc.tick(11_000, world, settings("required"), sender());
        assertEquals(1, told.size(), "the new connection has sent no preferences yet");
        svc.onPrefs(T1, prefs(true, false));                // the rejoined client's prefs
        svc.tick(11_500, world, settings("required"), sender());
        assertEquals(2, told.size(), "told again on the new connection");
        svc.tick(12_000, world, settings("required"), sender());
        assertEquals(2, told.size());
    }

    @Test
    void aPlayerWhoCannotBeReachedYetIsToldOnALaterPass() {
        var svc = toldService(null);
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "OptedOut", 500, 0));
        svc.onPrefs(T1, prefs(true, false));
        reachable = false;
        twoPasses(svc, 10_000, world, settings("required"));
        svc.tick(11_000, world, settings("required"), sender());
        assertTrue(told.isEmpty());
        assertEquals(1, svc.lastPassOverridden(), "premise: the override is live");
        assertFalse(svc.hasRequiredSharingNotice(T1), "an undelivered line must not latch");
        reachable = true;
        twoPasses(svc, 11_500, world, settings("required"));
        assertEquals(1, told.size(), "delivered once, as soon as the player is reachable");
    }

    @Test
    void aThrowingChatLaneIsContainedAndNeverRetriedIntoSpam() {
        var svc = toldService(null);
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "OptedOut", 500, 0),
                snap(T2, "Plain", 600, 0));
        svc.onPrefs(T1, prefs(true, false));
        notifierFailure = new IllegalStateException("send failed");
        twoPasses(svc, 10_000, world, settings("required"));
        assertEquals(List.of(T2, T1), currentRoster(), "the pass survives a failed chat send");
        assertTrue(svc.hasRequiredSharingNotice(T1), "premise: the send was attempted");
        notifierFailure = null;
        twoPasses(svc, 11_000, world, settings("required"));
        assertTrue(told.isEmpty(), "a send that threw may have delivered: never sent twice");
    }

    @Test
    void aLadderHiddenOrExcludedPlayerIsToldOnlyOnceTheOverrideActuallyExposesThem() {
        // hidden covers a raced permission read on Paper/Folia (no permission fact): a
        // holder must never be told their setting does not apply. And a vanished or
        // excluded player is not shown, so nothing is overridden yet.
        var svc = toldService(null);
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        svc.onPrefs(T1, prefs(true, false));
        svc.onPrefs(T2, prefs(true, false));
        var excluding = new FarPlayerBroadcastService.Settings("required", 2048, 0, false,
                List.of("listed"), 10, false);
        var first = List.of(viewer, hiddenSnap(T1, "Raced", 500, 0), snap(T2, "Listed", 600, 0));
        twoPasses(svc, 10_000, first, excluding);
        svc.tick(11_000, first, excluding, sender());
        assertTrue(told.isEmpty(), told.toString());
        assertEquals(List.of(), currentRoster());
        assertFalse(svc.diagLine().contains("overridden="), svc.diagLine());

        // The read is clean again (a holder after all) and the exclusion is lifted.
        twoPasses(svc, 11_500, List.of(viewer, holderSnap(T1, "Raced", 500, 0), snap(T2, "Listed", 600, 0)),
                settings("required"));
        assertEquals(List.of(T2), toldPlayers(),
                "the holder is never told; the formerly excluded player is, now that it is shown");
    }

    // ---- review fold §2: the notice means "you are being shown" ----

    @Test
    void aPlayerNobodyCanBeShownIsNotToldOrCountedUntilTheyBecomeVisible() {
        // A spectator the server does not send, an invisible or dead player, and a player the
        // vanish bridge hides from every viewer (the only vanish source on Fabric, NeoForge
        // and Sponge) are shown to nobody: "other players can see where you are" would be
        // false, and they are not overridden yet.
        boolean[] bridgeHides = {false};
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        var visible = List.of(viewer, snap(T1, "P", 500, 0));
        var unseen = new java.util.LinkedHashMap<String, FarPlayerBroadcastService.PlayerSnapshot>();
        unseen.put("spectator", snapFull(T1, "P", "minecraft:overworld", 500, 64, 0,
                true, false, true, 0, null, null, null));
        unseen.put("invisible", snapFull(T1, "P", "minecraft:overworld", 500, 64, 0,
                false, true, true, 0, null, null, null));
        unseen.put("dead", snapFull(T1, "P", "minecraft:overworld", 500, 64, 0,
                false, false, false, 0, null, null, null));
        unseen.put("vanish bridge", snap(T1, "P", 500, 0));
        for (var shape : unseen.entrySet()) {
            String name = shape.getKey();
            sent.clear();
            told.clear();
            bridgeHides[0] = name.equals("vanish bridge");
            var svc = toldService((v, target) -> !(bridgeHides[0] && target.equals(T1)));
            svc.onPrefs(T1, prefs(true, false));
            var hiddenWorld = List.of(viewer, shape.getValue());
            twoPasses(svc, 10_000, hiddenWorld, settings("required"));
            svc.tick(11_000, hiddenWorld, settings("required"), sender());
            assertTrue(told.isEmpty(), name + ": nobody is shown them, so nothing to say: " + told);
            assertEquals(0, svc.lastPassOverridden(), name + ": not counted");
            assertFalse(svc.diagLine().contains("overridden="), name + ": " + svc.diagLine());
            assertEquals(List.of(), currentRoster(), name);

            bridgeHides[0] = false;                         // they become visible
            svc.tick(11_500, visible, settings("required"), sender());
            assertEquals(List.of(T1), toldPlayers(), name + ": told on the pass that shows them");
            assertEquals(1, svc.lastPassOverridden(), name);
            assertEquals(List.of(T1), currentRoster(), name);

            bridgeHides[0] = name.equals("vanish bridge");  // unseen again, then visible again
            svc.tick(12_000, hiddenWorld, settings("required"), sender());
            assertEquals(0, svc.lastPassOverridden(), name + ": the count follows the pass");
            bridgeHides[0] = false;
            svc.tick(12_500, visible, settings("required"), sender());
            assertEquals(1, told.size(), name + ": still one line on this connection");
        }
    }

    @Test
    void aSpectatorIsToldWhenTheServerSendsSpectators() {
        var svc = toldService(null);
        svc.onPrefs(T1, prefs(true, false));
        var sendingSpectators = new FarPlayerBroadcastService.Settings("required", 2048, 0, true,
                List.of(), 10, false);
        twoPasses(svc, 10_000, List.of(snap(VIEWER, "Viewer", 0, 0),
                snapFull(T1, "Spec", "minecraft:overworld", 500, 64, 0, true, false, true, 0, null, null, null)),
                sendingSpectators);
        assertEquals(List.of(T1), currentRoster(), "send_spectators on: a spectator is shown");
        assertEquals(List.of(T1), toldPlayers(), "so the override is real and they are told");
    }

    @Test
    void aPlayerIsNotToldWhileNoOtherViewerReceivesFarPlayers() {
        // The opted-out player is the only far-player viewer: nobody can be shown them.
        var svc = unsubscribedToldService(null);
        svc.subscribeViewer(T1);
        svc.onPrefs(T1, prefs(true, false));
        var world = List.of(snap(T1, "OptedOut", 0, 0), snap(T2, "Other", 500, 0));
        twoPasses(svc, 10_000, world, settings("required"));
        svc.tick(11_000, world, settings("required"), sender());
        assertTrue(told.isEmpty(), told.toString());
        assertEquals(0, svc.lastPassOverridden());

        // A second player subscribes but has Show Far Players off: still nobody is shown them.
        svc.subscribeViewer(T2);
        svc.onPrefs(T2, prefs(false, true));
        svc.tick(11_500, world, settings("required"), sender());
        assertTrue(told.isEmpty(), told.toString());

        svc.onPrefs(T2, prefs(true, true));                 // now someone receives far players
        svc.tick(12_000, world, settings("required"), sender());
        assertEquals(List.of(T1), toldPlayers());
        assertEquals(List.of(T1), currentRoster(T2), "and that viewer is shown the opted-out player");
    }

    @Test
    void theDiagLineCountsOverriddenPlayersOnlyWhenThereAreAny() {
        var svc = toldService(null);
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        svc.onPrefs(T1, prefs(true, false));
        svc.onPrefs(T2, shareWithin(300));
        svc.onPrefs(T3, prefs(true, false));                // a holder: honored, not counted
        var world = List.of(viewer, snap(T1, "OptedOut", 500, 0), snap(T2, "NearOnly", 600, 0),
                holderSnap(T3, "HolderOptedOut", 700, 0), snap(T4, "Vanilla", 800, 0));
        svc.tick(10_000, world, settings("required"), sender());
        assertEquals(0, svc.lastPassOverridden(), "not on the first read");
        assertTrue(svc.diagLine().endsWith(", perm_holders=1"), svc.diagLine());
        svc.tick(10_500, world, settings("required"), sender());
        assertTrue(svc.diagLine().endsWith(", perm_holders=1, overridden=2"), svc.diagLine());
        assertEquals(2, svc.lastPassOverridden());

        svc.tick(11_000, world, settings("on"), sender());
        assertFalse(svc.diagLine().contains("overridden="), "mode on: " + svc.diagLine());
        assertEquals(0, svc.lastPassOverridden());
        twoPasses(svc, 11_500, world, settings("required"));
        assertEquals(2, svc.lastPassOverridden(), "the count describes the pass, not the notices");
        svc.applyMode("off", sender());
        assertFalse(svc.diagLine().contains("overridden="), "mode off: " + svc.diagLine());
    }

    @Test
    void requiredModeEnablesAndWithdrawsLikeModeOn() {
        // applyMode treats every mode but "off" as enabled: required -> off withdraws the
        // published roster, off -> required re-publishes it.
        var svc = toldService(null);
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), snap(T1, "Target", 500, 0));
        assertTrue(svc.applyMode("required", sender()));
        svc.tick(10_000, world, settings("required"), sender());
        assertEquals(List.of(T1), rosterUuids());
        assertFalse(svc.applyMode("off", sender()));
        var cleared = rosters().get(rosters().size() - 1);
        assertTrue(cleared.full() && cleared.added().isEmpty(), "off withdraws the roster");
        svc.tick(10_001, world, settings("required"), sender());
        assertEquals(T1, rosters().get(rosters().size() - 1).added().get(0).uuid());
    }

    // ---- review fold A: the holder notice on a server that has not chosen yet ----

    /** Settings as an UPGRADED server reads them: its file never set
     *  far_players.hide_permission_holders, so the effective value is the default (off). */
    private static FarPlayerBroadcastService.Settings undecided(String mode) {
        return new FarPlayerBroadcastService.Settings(mode, 2048, 0, false, List.of(), 10, false, true);
    }

    private List<UUID> toldHolders() {
        return told.stream().filter(t -> t.line().equals(FarPlayerBroadcastService.holderNotice()))
                .map(Told::player).toList();
    }

    @Test
    void aHolderOnAServerThatHasNotChosenIsToldOncePerConnection() {
        var svc = toldService(null);
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), holderSnap(T1, "Admin", 500, 0),
                snap(T2, "Plain", 600, 0));
        svc.tick(10_000, world, undecided("on"), sender());
        assertTrue(told.isEmpty(), "one 'holder' read is not acted on");
        svc.tick(10_500, world, undecided("on"), sender());
        assertEquals(List.of(new Told(T1, FarPlayerBroadcastService.holderNotice())), told,
                "the holder, and only the holder");
        assertEquals("You have the lss.farplayers.hidden permission. Since 0.21.0 it no longer hides"
                        + " you from far players by itself: turn off Share My Position to hide. Server"
                        + " owners: set far_players.hide_permission_holders in the server config (true"
                        + " hides holders as before, false keeps this) to stop this message.",
                told.get(0).line().fallback(), "the approved wording");
        assertEquals("lss.far_players.permission_no_longer_hides", told.get(0).line().translationKey());
        assertTrue(svc.hasHolderNotice(T1));
        assertFalse(svc.hasHolderNotice(T2));
        assertEquals(List.of(T1, T2), currentRoster(), "the holder is shown: that is what the line explains");

        for (int i = 2; i <= 5; i++) svc.tick(10_000 + i * 500, world, undecided("on"), sender());
        svc.onPrefs(T1, prefs(true, true));
        svc.removeViewer(T1);                               // a same-connection shed
        twoPasses(svc, 15_000, world, undecided("on"));
        assertEquals(1, told.size(), "one line per connection");

        svc.onDisconnect(T1);                               // the connection ended
        assertFalse(svc.hasHolderNotice(T1));
        twoPasses(svc, 16_000, world, undecided("on"));
        assertEquals(List.of(T1, T1), toldHolders(), "told again on the next connection");
    }

    @Test
    void noHolderNoticeOnceTheOperatorHasChosenEitherWay() {
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), holderSnap(T1, "Admin", 500, 0));
        var chosen = new java.util.LinkedHashMap<String, FarPlayerBroadcastService.Settings>();
        chosen.put("the key written false", settings("on", false));
        chosen.put("the key written true", settings("on", true));
        chosen.put("a fresh 0.21.0 file (the template carries the key)",
                new FarPlayerBroadcastService.Settings("on", 2048, 0, false, List.of(), 10, false, false));
        // Cannot come from a file (an unset key reads false), but the rule must not depend on that.
        chosen.put("hiding on, whatever the file says",
                new FarPlayerBroadcastService.Settings("on", 2048, 0, false, List.of(), 10, true, true));
        for (var choice : chosen.entrySet()) {
            told.clear();
            var svc = toldService(null);
            twoPasses(svc, 10_000, world, choice.getValue());
            svc.tick(11_000, world, choice.getValue(), sender());
            assertTrue(told.isEmpty(), choice.getKey() + ": " + told);
            assertFalse(svc.hasHolderNotice(T1), choice.getKey());
        }
    }

    @Test
    void theHolderNoticeStopsForEveryoneOnTheReloadThatWritesTheKey() {
        var svc = toldService(null);
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        twoPasses(svc, 10_000, List.of(viewer, holderSnap(T1, "AdminOne", 500, 0)), undecided("on"));
        assertEquals(List.of(T1), toldHolders());

        // The operator writes the key (false keeps the new behaviour) and reloads; a second
        // holder joins afterwards.
        var world = List.of(viewer, holderSnap(T1, "AdminOne", 500, 0), holderSnap(T2, "AdminTwo", 600, 0));
        twoPasses(svc, 11_000, world, settings("on", false));
        svc.tick(12_000, world, settings("on", false), sender());
        assertEquals(List.of(T1), toldHolders(), "nobody else is told once the server has chosen");
        assertEquals(List.of(T1, T2), currentRoster(), "and the choice 'false' keeps holders shown");
    }

    @Test
    void aSingleHolderReadNeverMessagesANonHolder() {
        // The same two-consecutive-reads rule as mode "required": one wrong read must not
        // tell a player they hold a permission they do not.
        var svc = toldService(null);
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        var notAHolder = List.of(viewer, snap(T1, "P", 500, 0));
        var wrongTrue = List.of(viewer, holderSnap(T1, "P", 500, 0));
        var failedRead = List.of(viewer, privacySnap(T1, "P", 500, 0, true, true));
        long now = 10_000;
        for (var world : List.of(notAHolder, wrongTrue, notAHolder, wrongTrue, failedRead, wrongTrue, notAHolder)) {
            svc.tick(now += 500, world, undecided("on"), sender());
            assertTrue(told.isEmpty(), "never told on a single read, at " + now + ": " + told);
        }
        twoPasses(svc, now + 500, wrongTrue, undecided("on"));
        assertEquals(List.of(T1), toldHolders(), "two consecutive holder reads are a holder");
    }

    @Test
    void aHolderWhoOptedOutIsNotToldUntilTheyShareAgain() {
        // Share My Position already off: hidden by their own choice, nothing to explain.
        var svc = toldService(null);
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), holderSnap(T1, "Admin", 500, 0));
        svc.onPrefs(T1, prefs(true, false));
        twoPasses(svc, 10_000, world, undecided("on"));
        svc.tick(11_000, world, undecided("on"), sender());
        assertTrue(told.isEmpty(), told.toString());
        svc.onPrefs(T1, prefs(true, true));                 // they turn sharing back on
        svc.tick(11_500, world, undecided("on"), sender());
        assertEquals(List.of(T1), toldHolders(), "now the permission is all that stood between them and being shown");
    }

    @Test
    void anExcludedHolderIsNotTold() {
        // Listed in excluded_players: never shown, whatever the permission says.
        var svc = toldService(null);
        var excluding = new FarPlayerBroadcastService.Settings("on", 2048, 0, false,
                List.of("admin"), 10, false, true);
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), holderSnap(T1, "Admin", 500, 0),
                holderSnap(T2, "Other", 600, 0));
        twoPasses(svc, 10_000, world, excluding);
        svc.tick(11_000, world, excluding, sender());
        assertEquals(List.of(T2), toldHolders(), "the holder who is not excluded is told; the excluded one never");
    }

    @Test
    void aLadderHiddenHolderIsNotToldAndAHiddenReadIsNoPermissionRead() {
        // Vanished (the ladder): not shown. On Paper/Folia a raced read looks the same and
        // carries no permission fact, so it never counts toward the two reads.
        var svc = toldService(null);
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        var vanished = List.of(viewer, privacySnap(T1, "Admin", 500, 0, true, true));
        var visible = List.of(viewer, holderSnap(T1, "Admin", 500, 0));
        twoPasses(svc, 10_000, vanished, undecided("on"));
        svc.tick(11_000, vanished, undecided("on"), sender());
        assertTrue(told.isEmpty(), "never while the ladder hides them: " + told);
        svc.tick(11_500, visible, undecided("on"), sender());
        assertTrue(told.isEmpty(), "the first clean read after vanish is one read: " + told);
        svc.tick(12_000, visible, undecided("on"), sender());
        assertEquals(List.of(T1), toldHolders());
    }

    @Test
    void inModeOptInOnlyAHolderWhoSharesIsTold() {
        // Mode opt-in shows only explicit sharers: a holder who never sent preferences, or
        // who does not share, is not shown and is told nothing.
        var svc = toldService(null);
        svc.onPrefs(T2, prefs(true, true));
        svc.onPrefs(T3, prefs(true, false));
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), holderSnap(T1, "Silent", 500, 0),
                holderSnap(T2, "Sharing", 600, 0), holderSnap(T3, "NotSharing", 700, 0));
        twoPasses(svc, 10_000, world, undecided("opt-in"));
        svc.tick(11_000, world, undecided("opt-in"), sender());
        assertEquals(List.of(T2), toldHolders());
        assertEquals(1, told.size(), told.toString());
    }

    @Test
    void aHolderWithoutTheModIsToldInModeOnAndRequired() {
        // No preferences at all (no client mod): modes on and required show them, so the
        // line reaches them too (it is ordinary chat).
        for (String mode : List.of("on", "required")) {
            told.clear();
            var svc = toldService(null);
            twoPasses(svc, 10_000, List.of(snap(VIEWER, "Viewer", 0, 0), holderSnap(T1, "Admin", 500, 0)),
                    undecided(mode));
            assertEquals(List.of(T1), toldHolders(), mode);
            assertEquals(1, told.size(), mode + ": " + told);
        }
    }

    @Test
    void aHolderReadFromBeforeAGapWithNoPassDoesNotCount() {
        // Mode off (or nobody subscribed) runs no pass: a read from before it is not
        // adjacent to the next one, whatever the pass counter says.
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), holderSnap(T1, "Admin", 500, 0));
        var off = toldService(null);
        off.tick(10_000, world, undecided("on"), sender());
        off.applyMode("off", sender());
        off.tick(10_500, world, undecided("on"), sender());
        assertTrue(told.isEmpty(), "one read, a gap, one read: " + told);
        off.tick(11_000, world, undecided("on"), sender());
        assertEquals(List.of(T1), toldHolders());

        told.clear();
        var unsubscribed = toldService(null);
        unsubscribed.tick(10_000, world, undecided("on"), sender());
        unsubscribed.removeViewer(VIEWER);
        unsubscribed.tick(10_500, world, undecided("on"), sender());   // no subscriber: no pass
        unsubscribed.subscribeViewer(VIEWER);
        unsubscribed.onPrefs(VIEWER, prefs(true, true));
        unsubscribed.tick(11_000, world, undecided("on"), sender());
        assertTrue(told.isEmpty(), "one read, a gap, one read: " + told);
        unsubscribed.tick(11_500, world, undecided("on"), sender());
        assertEquals(List.of(T1), toldHolders());
    }

    @Test
    void noHolderNoticeInModeOff() {
        // Mode off: nobody is shown and no pass runs.
        var svc = toldService(null);
        var world = List.of(snap(VIEWER, "Viewer", 0, 0), holderSnap(T1, "Admin", 500, 0));
        twoPasses(svc, 10_000, world, undecided("off"));
        svc.tick(11_000, world, undecided("off"), sender());
        assertTrue(told.isEmpty(), told.toString());
        assertFalse(svc.hasHolderNotice(T1));
    }

    @Test
    void theTwoNoticesHaveSeparateLatches() {
        // Mode required on a server that has not chosen: the holder line goes to the holder,
        // the required line to the opted-out non-holder, each once. A holder who then loses
        // the permission while opted out is told the required line as well.
        var svc = toldService(null);
        var viewer = snap(VIEWER, "Viewer", 0, 0);
        svc.onPrefs(T2, prefs(true, false));
        var world = List.of(viewer, holderSnap(T1, "Admin", 500, 0), snap(T2, "OptedOut", 600, 0));
        twoPasses(svc, 10_000, world, undecided("required"));
        svc.tick(11_000, world, undecided("required"), sender());
        assertEquals(List.of(T1), toldHolders());
        assertEquals(List.of(T2), toldPlayers());
        assertEquals(2, told.size());

        svc.onPrefs(T1, prefs(true, false));                // the former holder opts out...
        var demoted = List.of(viewer, snap(T1, "Admin", 500, 0), snap(T2, "OptedOut", 600, 0));
        twoPasses(svc, 11_500, demoted, undecided("required"));   // ...and has lost the permission
        assertEquals(List.of(T2, T1), toldPlayers(), "a different line, so its own latch");
        assertEquals(List.of(T1), toldHolders());
    }
}
