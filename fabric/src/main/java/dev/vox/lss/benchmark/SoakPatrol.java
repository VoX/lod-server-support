package dev.vox.lss.benchmark;

import dev.vox.lss.common.LSSLogger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Soak-client PATROL (dev-only package, never in a release jar): {@code -Dlss.soak.patrol=
 * x1,z1;x2,z2[;...]} walks the dummy between the waypoints forever — a real MOVING far-player
 * target (walk animation, dead-reckoning, the tag and proxy handoff bands) for the far-player
 * live rigs, without Baritone or a second GUI client. Mechanism: {@code LocalPlayer.input} is
 * swapped for an {@link Input} whose {@code tick()} holds the forward impulse, and the player's
 * yaw is pointed at the current waypoint at the START of every client tick, so vanilla's own
 * aiStep/travel/sendPosition path does the walking and the packets. Re-applied whenever the
 * client player object changes (join, respawn, dimension change — vanilla installs a fresh
 * {@code KeyboardInput} there). A waypoint counts as reached within 1.5 blocks horizontally;
 * the walk then continues to the next one, wrapping around.
 */
final class SoakPatrol {
    private static final double ARRIVE_BLOCKS = 1.5;

    private SoakPatrol() {}

    static void install() {
        String spec = System.getProperty("lss.soak.patrol", "");
        if (spec.isBlank()) return;
        try {
            arm(spec);
        } catch (RuntimeException e) {
            // Review fold C6: a malformed spec must not kill the soak client's snapshot/disconnect
            // hooks (registered after this call) — log loudly and run without a patrol.
            LSSLogger.error("[Soak] Patrol NOT armed — " + e.getMessage(), e);
        }
    }

    private static void arm(String spec) {
        List<double[]> waypoints = new ArrayList<>();
        try {
            for (String point : spec.split(";")) {
                String[] xz = point.trim().split(",");
                waypoints.add(new double[] {
                        Double.parseDouble(xz[0].trim()) + 0.5, Double.parseDouble(xz[1].trim()) + 0.5});
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("[Soak] Malformed lss.soak.patrol '" + spec
                    + "' (want x,z;x,z[;...])", e);
        }
        if (waypoints.size() < 2) {
            throw new IllegalStateException("[Soak] lss.soak.patrol wants at least two waypoints, got '" + spec + "'");
        }
        LSSLogger.info("[Soak] Patrol armed over " + waypoints.size() + " waypoints: " + spec);

        int[] target = {0};
        Input[] driver = {null};
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            LocalPlayer player = client.player;
            if (player == null || client.level == null) return;
            if (player.input != driver[0]) {
                driver[0] = new PatrolInput();
                player.input = driver[0];
            }
            double[] waypoint = waypoints.get(target[0]);
            double dx = waypoint[0] - player.getX();
            double dz = waypoint[1] - player.getZ();
            if (dx * dx + dz * dz < ARRIVE_BLOCKS * ARRIVE_BLOCKS) {
                target[0] = (target[0] + 1) % waypoints.size();
                LSSLogger.info("[Soak] Patrol: reached waypoint, heading for #" + target[0]);
                return;
            }
            // MC yaw: 0 = +z (south), 90 = -x (west).
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            player.setYRot(yaw);
            player.yRotO = yaw;
            player.setYHeadRot(yaw);
            player.yHeadRotO = yaw;
            player.setXRot(0.0f);
        });
    }

    /** Vanilla's {@code KeyboardInput} reads the keys every tick; this one holds "forward". */
    private static final class PatrolInput extends Input {
        @Override
        public void tick(boolean isSneaking, float sneakingSpeedMultiplier) {
            this.up = true;
            this.down = false;
            this.left = false;
            this.right = false;
            this.forwardImpulse = 1.0f;
            this.leftImpulse = 0.0f;
            this.jumping = false;
            this.shiftKeyDown = false;
        }
    }
}
