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
        return new FarPlayerBroadcastService.Settings(mode, 2048, 0, false, List.of(), 10);
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
                0f, 0f, 0f, (byte) 0, 0, 0, 0, spectator, invisible, alive, false,
                equipHash, equipIds, equipCounts, vehicle);
    }

    private FarPlayerBroadcastService subscribed() {
        var svc = new FarPlayerBroadcastService(null);
        svc.subscribeViewer(VIEWER);
        svc.onPrefs(VIEWER, prefs(true, true));
        return svc;
    }

    private static FarPlayerBroadcastService.PlayerSnapshot hiddenSnap(UUID uuid, String name,
                                                                       double x, double z) {
        return new FarPlayerBroadcastService.PlayerSnapshot(uuid, name, "minecraft:overworld",
                x, 64, z, 0f, 0f, 0f, (byte) 0, 0, 0, 0, false, false, true, true,
                0L, null, null, null);
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
                List.of("hidden", T2.toString().toUpperCase(java.util.Locale.ROOT)), 10);
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
        var s = new FarPlayerBroadcastService.Settings("on", 16384, 0, false, List.of(), 10);
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
                12.0, 0, 0, false, false, true, false, 0L, null, null, null);
        svc.tick(10_000, List.of(viewer, moving), settings("on"), sender());
        int afterMoving = updates().size();

        var resting = new FarPlayerBroadcastService.PlayerSnapshot(T1, "T",
                "minecraft:overworld", 500, 64, 0, 0f, 0f, 0f, (byte) 0,
                0, 0, 0, false, false, true, false, 0L, null, null, null);
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
    void hiddenPermissionDropsTheTargetFromRosterAndUpdates() {
        var svc = subscribed();
        svc.tick(10_000, List.of(snap(VIEWER, "Viewer", 0, 0),
                hiddenSnap(T1, "Hidden", 500, 0), snap(T2, "Plain", 600, 0)),
                settings("on"), sender());
        var r = rosters();
        assertEquals(1, r.size());
        assertEquals(1, r.get(0).added().size(), "the hidden player never enters the roster");
        assertEquals("Plain", r.get(0).added().get(0).name());
        for (var u : updates()) {
            assertEquals(1, u.entries().size(), "no update entry for a hidden player");
        }
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
        var s = new FarPlayerBroadcastService.Settings("on", 16384, 0, false, List.of(), 10);
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
}
