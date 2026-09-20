package dev.vox.lssfixture.wi6;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.farplayers.FarPlayerWire;
import dev.vox.lss.config.LSSServerConfig;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/** Disposable actual-adapter fixture. Never changes broadcaster state or writes a network frame. */
public final class Probe {
    private static final Logger LOG = LoggerFactory.getLogger("LSS-WI6-Fixture");
    private static final boolean ENABLED = Boolean.getBoolean("lss.wi6.enabled") && !System.getProperty("lss.rig.runId", "").isBlank();
    private static final String VIEWER_NAME = System.getProperty("lss.wi6.observer", "AstraValidator");
    private static final String SUBJECT_NAME = System.getProperty("lss.wi6.subject", "SoakPlayer");
    private static final String OTHER_NAME = System.getProperty("lss.wi6.unaffected", "SeatedSubjectB");
    private static final UUID OTHER = offline(OTHER_NAME);
    private static boolean otherBaseline, otherCleared;
    private static final UUID VIEWER = offline(VIEWER_NAME), SUBJECT = offline(SUBJECT_NAME);
    private static final Path ROOT = Path.of(System.getProperty("lss.rig.serverRoot", "."));
    private static final Path HOLD = ROOT.resolve("wi6-hold-clear");
    private static boolean checked, safe, failed, armed, offSeen, onSeen, pass;
    private static boolean baselineUpdate, restoredRoster, restoredUpdate;
    private static int tick, offTick, onTick, updateTick, baselineEpoch, baselineIndex = -1;
    private static int heldEpoch, heldCount, restoredIndex = -1;
    private static byte[] heldBody;
    private Probe() {}
    private static UUID offline(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
    private static void log(String event, String detail) {
        LOG.info("[WI6-FIXTURE] {} tick={} {}", event, tick, detail);
    }
    private static void fail(String detail) {
        if (!failed) log("FAIL_OR_INCONCLUSIVE", detail);
        failed = true;
    }
    private static boolean enabled() { return ENABLED && safe; }
    private static boolean off() { return "off".equals(LSSServerConfig.CONFIG.farPlayers); }
    private static boolean holdExists() {
        return Files.isRegularFile(HOLD, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(HOLD);
    }
    public static void tickStart(MinecraftServer server) {
        if (!ENABLED || pass) return;
        tick++;
        if (!checked) {
            checked = true;
            try {
                safe = Path.of("").toRealPath().equals(ROOT.toRealPath())
                        && !Files.isSymbolicLink(ROOT) && !VIEWER.equals(SUBJECT) && !VIEWER.equals(OTHER) && !SUBJECT.equals(OTHER)
                        && !server.usesAuthentication();
            } catch (Exception e) { safe = false; }
            if (!safe) { fail("wrong fixture root, online-mode server, or identical participants; injector disabled"); return; }
            log("READY", "observer=" + VIEWER_NAME + " observer_uuid=" + VIEWER
                    + " subject=" + SUBJECT_NAME + " subject_uuid=" + SUBJECT
                    + " unaffected=" + OTHER_NAME + " unaffected_uuid=" + OTHER + " hold=" + HOLD);
        }
        if (!safe || failed) return;
        if (!armed && baselineUpdate && holdExists()) {
            if (off()) { fail("hold armed after OFF; missing controlled ON baseline"); return; }
            armed = true;
            log("ARMED", "baseline_epoch=" + baselineEpoch + " subject_index=" + baselineIndex
                    + " actual_roster_and_update_accepted=true");
        }
        if (!armed) return;
        var viewer = server.getPlayerList().getPlayer(VIEWER);
        var subject = server.getPlayerList().getPlayer(SUBJECT);
        if (viewer == null || subject == null
                || !VIEWER_NAME.equals(viewer.getGameProfile().getName())
                || !SUBJECT_NAME.equals(subject.getGameProfile().getName())) {
            fail("allowlisted offline participant missing or changed"); return;
        }
        if (!holdExists()) { fail("hold marker removed before completed observation"); return; }
        if (off()) {
            if (onSeen) { fail("unexpected second OFF during observation"); return; }
            if (!offSeen) { offSeen = true; offTick = tick; log("OFF_ENTERED", "baseline_epoch=" + baselineEpoch); }
        } else if (offSeen && !onSeen) {
            if (heldCount < 3) { fail("ON before at least three identical declined clear retries"); return; }
            onSeen = true; onTick = tick;
            log("ON_ENTERED", "held_retries=" + heldCount + " held_epoch=" + heldEpoch);
        }
        if (offSeen && !onSeen && tick - offTick > 1200) fail("OFF window exceeded 1200 server ticks");
        if (onSeen && !restoredUpdate && tick - onTick > 200) fail("no accepted replacement roster/update within 200 server ticks");
    }
    public static boolean decline(UUID viewer, String channel, byte[] body) {
        if (!enabled() || pass || !VIEWER.equals(viewer)) return false;
        try {
            if (LSSConstants.CHANNEL_FAR_PLAYER_ROSTER.equals(channel)) {
                var roster = FarPlayerWire.decodeRoster(body);
                boolean empty = roster.full() && roster.added().isEmpty();
                if (onSeen && empty) fail("obsolete empty full roster attempted after ON; epoch=" + roster.epoch());
                if (!failed && armed && offSeen && !onSeen && off() && empty) {
                    if (roster.removedIndices().length != 0) { fail("unexpected removal indices in OFF clear"); return false; }
                    if (heldBody == null) {
                        heldBody = body.clone(); heldEpoch = roster.epoch();
                        if (heldEpoch != baselineEpoch + 1) { fail("clear epoch not baseline+1"); return false; }
                    } else if (!Arrays.equals(heldBody, body)) {
                        fail("declined clear changed epoch/body before acceptance"); return false;
                    }
                    heldCount++;
                    if (heldCount <= 3 || heldCount % 20 == 0)
                        log("CLEAR_DECLINED", "count=" + heldCount + " epoch=" + heldEpoch + " actual_adapter_return=false");
                    return true;
                }
                if (!failed && offSeen && !onSeen && off()) fail("unexpected nonempty/incremental roster while OFF");
            } else if (LSSConstants.CHANNEL_FAR_PLAYER_UPDATES.equals(channel) && armed && offSeen && !onSeen && off()) {
                fail("target update attempted while OFF");
            }
        } catch (RuntimeException e) { fail("frame decode failed: " + e); }
        return false;
    }
    public static void returned(UUID viewer, String channel, byte[] body, boolean accepted) {
        if (!enabled() || pass || failed || !accepted) return;
        if (OTHER.equals(viewer) && LSSConstants.CHANNEL_FAR_PLAYER_ROSTER.equals(channel)) {
            try {
                var other = FarPlayerWire.decodeRoster(body);
                if (!offSeen && other.full() && other.added().stream().anyMatch(e -> e.uuid().equals(SUBJECT))) {
                    otherBaseline = true; log("UNAFFECTED_BASELINE", "viewer=" + OTHER + " actual_adapter_accepted=true");
                }
                if (armed && offSeen && !onSeen && off() && otherBaseline && other.full() && other.added().isEmpty()) {
                    otherCleared = true; log("UNAFFECTED_CLEAR_ACCEPTED", "viewer=" + OTHER + " actual_adapter_accepted=true");
                }
            } catch (RuntimeException e) { fail("unaffected frame decode failed: " + e); }
        }
        if (!VIEWER.equals(viewer)) return;
        try {
            if (LSSConstants.CHANNEL_FAR_PLAYER_ROSTER.equals(channel)) {
                var roster = FarPlayerWire.decodeRoster(body);
                var target = roster.added().stream().filter(e -> e.uuid().equals(SUBJECT)).findFirst();
                if (!offSeen && roster.full() && target.isPresent()) {
                    baselineEpoch = roster.epoch(); baselineIndex = target.get().index(); baselineUpdate = false;
                    log("BASELINE_ROSTER", "epoch=" + baselineEpoch + " subject_index=" + baselineIndex);
                }
                if (onSeen && roster.full()) {
                    if (target.isEmpty() || roster.epoch() != heldEpoch) {
                        fail("replacement full roster missing subject or not superseding held epoch"); return;
                    }
                    restoredRoster = true; restoredIndex = target.get().index();
                    log("REPLACEMENT_ROSTER_ACCEPTED", "epoch=" + roster.epoch() + " subject_index=" + restoredIndex);
                }
            } else if (LSSConstants.CHANNEL_FAR_PLAYER_UPDATES.equals(channel)) {
                var updates = FarPlayerWire.decodeUpdates(body);
                if (!offSeen && updates.epoch() == baselineEpoch
                        && updates.entries().stream().anyMatch(e -> e.rosterIndex() == baselineIndex)) {
                    baselineUpdate = true;
                    log("BASELINE_UPDATE", "epoch=" + updates.epoch());
                }
                if (onSeen && restoredRoster && updates.epoch() == heldEpoch
                        && updates.entries().stream().anyMatch(e -> e.rosterIndex() == restoredIndex)) {
                    if (!restoredUpdate) {
                        restoredUpdate = true; updateTick = tick;
                        log("REPLACEMENT_UPDATE_ACCEPTED", "epoch=" + updates.epoch());
                    }
                }
            }
        } catch (RuntimeException e) { fail("accepted frame decode failed: " + e); }
    }
    public static void tickEnd(MinecraftServer server, dev.vox.lss.common.farplayers.FarPlayerBroadcastService service) {
        if (!enabled() || failed || pass || !restoredUpdate || tick - updateTick < 100) return;
        if (off() || server.getPlayerList().getPlayer(VIEWER) == null
                || server.getPlayerList().getPlayer(SUBJECT) == null) {
            fail("mode or participants changed before final observation"); return;
        }
        if (!otherCleared || server.getPlayerList().getPlayer(OTHER) == null) {
            fail("unaffected viewer lacked native clear progress during denial"); return;
        }
        try {
            // Read-only test observation, on the same native owner thread as tickFarPlayers.
            var viewersField = service.getClass().getDeclaredField("viewers"); viewersField.setAccessible(true);
            var viewers = (java.util.Map<?, ?>) viewersField.get(service);
            var state = viewers.get(VIEWER);
            if (state == null) { fail("target viewer state missing"); return; }
            for (String name : java.util.List.of("clearPending", "fullRosterPending", "controlFullPending")) {
                var field = state.getClass().getDeclaredField(name); field.setAccessible(true);
                if (field.getBoolean(state)) { fail("native broadcaster debt remains: " + name); return; }
            }
            log("DEBT_RELEASED", "clearPending=false fullRosterPending=false controlFullPending=false owner_thread=true");
        } catch (ReflectiveOperationException | RuntimeException e) { fail("native debt observation failed: " + e); return; }
        pass = true;
        log("PASS_SEND_ADMISSION", "held_retries=" + heldCount + " held_epoch=" + heldEpoch
                + " replacement_roster=true replacement_subject_update=true observation_ticks=" + (tick - updateTick)
                + " obsolete_clear_attempts=0 fixture_scope=adapter_denial_not_physical_netty");
    }
}
