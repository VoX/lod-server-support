package dev.vox.lssfixture.wi9;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

/** External, one-shot visual fixture. No product hook or synthetic network state. */
public final class Probe {
    private static final Logger LOGGER = LoggerFactory.getLogger("LSS-WI9-Fixture");
    private static final boolean ENABLED = Boolean.getBoolean("lss.wi9.enabled") && !System.getProperty("lss.rig.runId", "").isBlank();
    private static final UUID A = offline(System.getProperty("lss.wi9.subjectA", "SoakPlayer"));
    private static final UUID B = offline(System.getProperty("lss.wi9.subjectB", "LssSubjectB"));
    private static final String PROXY = "dev.vox.lss.networking.client.FarPlayerRenderer$Proxy";
    private static final List<UUID> order = new ArrayList<>();
    private static final List<UUID> seated = new ArrayList<>();
    private static final List<UUID> healthyReturns = new ArrayList<>();
    private static boolean healthyReady;
    private static UUID armed, fault;
    private static boolean inPass, fired, faultThisPass, failed;
    private static int pass, nextStarts, nextReturns, tagStarts, tagReturns;
    private static PoseStack stack;
    private static PoseStack.Pose parent, mark;
    private static Matrix4f parentMatrix, markMatrix;
    private static Matrix3f parentNormal, markNormal;

    private Probe() {}
    private static UUID offline(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
    private static void log(String event, String detail) {
        LOGGER.info("[WI9-FIXTURE] {} pass={} {}", event, pass, detail);
    }
    private static boolean subject(Entity entity) {
        return ENABLED && inPass && entity.getClass().getName().equals(PROXY)
                && (entity.getUUID().equals(A) || entity.getUUID().equals(B));
    }
    public static void begin() {
        if (!ENABLED) return;
        inPass = true; pass++; faultThisPass = false;
        order.clear(); seated.clear(); healthyReturns.clear(); stack = null; mark = null; parent = null;
        nextStarts = nextReturns = tagStarts = tagReturns = 0;
    }
    public static void beforeMark(PoseStack poses) {
        if (!ENABLED || !inPass) return;
        stack = poses; parent = poses.last();
        parentMatrix = new Matrix4f(parent.pose()); parentNormal = new Matrix3f(parent.normal());
    }
    public static void afterMark(PoseStack.Pose value) {
        if (!ENABLED || !inPass) return;
        mark = value; markMatrix = new Matrix4f(value.pose()); markNormal = new Matrix3f(value.normal());
    }
    private static boolean restored() {
        return stack != null && mark != null && stack.last() == mark
                && stack.last().pose().equals(markMatrix) && stack.last().normal().equals(markNormal);
    }
    public static void dispatcherHead(Entity entity, PoseStack poses) {
        if (!subject(entity)) return;
        UUID id = entity.getUUID();
        var world = net.minecraft.client.Minecraft.getInstance().level;
        var observer = net.minecraft.client.Minecraft.getInstance().player;
        if (world == null || world.getPlayerByUUID(id) != null || observer == null
                || observer.distanceTo(entity) <= 128.0F) {
            failed = true;
            log("PREMISE_FAILED", "native_player_still_tracked_or_subject_not_far=true");
            return;
        }
        if (!order.contains(id)) order.add(id);
        if (entity.isPassenger() && !seated.contains(id)) seated.add(id);
        if (faultThisPass && !id.equals(fault)) {
            nextStarts++;
            boolean ok = poses == stack && restored();
            if (!ok) failed = true;
            log("NEXT_PROXY_HEAD", "uuid=" + id + " sentinel_and_matrices_restored=" + ok);
        }
    }
    public static void afterFirstTranslate(Entity entity, PoseStack poses) {
        if (!subject(entity) || fired || armed == null || !entity.getUUID().equals(armed)
                || !entity.isPassenger() || order.size() != 1 || !order.getFirst().equals(armed)) return;
        // Require the REAL dispatcher push and nontrivial translation, not a simulated stack leak.
        if (poses != stack || mark == null || poses.last() == mark || poses.last().pose().equals(markMatrix)) {
            log("PREMISE_FAILED", "dispatcher push/translate did not change the recorded sentinel");
            fired = true; failed = true; return;
        }
        fired = true; faultThisPass = true; fault = entity.getUUID();
        log("INJECTED", "uuid=" + fault + " passenger=true real_dispatcher_push_translate=true");
        throw new IllegalStateException("WI9 isolated one-shot seated dispatcher fixture");
    }
    public static void dispatcherReturn(Entity entity) {
        if (subject(entity) && entity.isPassenger() && !faultThisPass
                && !healthyReturns.contains(entity.getUUID())) healthyReturns.add(entity.getUUID());
        if (subject(entity) && faultThisPass && !entity.getUUID().equals(fault)) {
            nextReturns++; log("NEXT_PROXY_RETURN", "uuid=" + entity.getUUID());
        }
    }
    public static void tagHead() {
        if (!ENABLED || !inPass || !faultThisPass) return;
        tagStarts++;
        boolean ok = restored(); if (!ok) failed = true;
        log("TAG_HEAD", "sentinel_and_matrices_restored=" + ok);
    }
    public static void tagReturn() {
        if (!ENABLED || !inPass || !faultThisPass) return;
        tagReturns++;
    }
    private static boolean scoping() {
        var client = net.minecraft.client.Minecraft.getInstance();
        return client.player != null && client.player.isScoping()
                && client.options.getCameraType().isFirstPerson();
    }
    private static boolean captureGateOpen() {
        String value = System.getProperty("lss.wi9.captureGate", "");
        if (value.isBlank()) return false;
        Path path = Path.of(value);
        try {
            return !Files.isSymbolicLink(path) && Files.isRegularFile(path) && Files.size(path) <= 128
                    && Files.readString(path).equals(System.getProperty("lss.rig.runId") + "\n");
        } catch (java.io.IOException ignored) { return false; }
    }
    public static void end(boolean crashLatched) {
        if (!ENABLED || !inPass) return;
        if (faultThisPass) {
            boolean unwound = stack != null && parent != null && stack.last() == parent
                    && parent.pose().equals(parentMatrix) && parent.normal().equals(parentNormal);
            boolean complete = nextStarts > 0 && nextReturns > 0 && tagStarts > 0 && tagReturns > 0;
            boolean ok = !failed && complete && unwound && !crashLatched;
            log(ok ? "PASS_SAME_FRAME" : "FAIL_OR_INCONCLUSIVE", "next_starts=" + nextStarts
                    + " next_returns=" + nextReturns + " tag_starts=" + tagStarts + " tag_returns=" + tagReturns
                    + " outer_unwind=" + unwound + " crash_latched=" + crashLatched + " assertion_failed=" + failed);
        } else if (!fired && armed == null && !failed && order.size() == 2
                && seated.containsAll(order) && healthyReturns.containsAll(order) && scoping()) {
            if (!healthyReady) {
                healthyReady = true;
                log("HEALTHY_READY", "both_seated_returns=true native_players_absent=true beyond_128=true scoping=true first_seated="
                        + order.getFirst() + " second=" + order.get(1));
            }
            // The owned controller writes this gate ONLY after the healthy native PNG.
            // Recheck both seated returns and real spyglass use in this current frame.
            if (captureGateOpen()) {
                armed = order.getFirst();
                log("ARMED", "observed_two_real_proxy_draws=true native_players_absent=true first_seated=" + armed + " second=" + order.get(1));
            }
        }
        inPass = false;
    }
}
