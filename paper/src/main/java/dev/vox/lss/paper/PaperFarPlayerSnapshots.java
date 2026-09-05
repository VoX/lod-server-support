package dev.vox.lss.paper;

import dev.vox.lss.common.farplayers.FarPlayerBroadcastService;
import dev.vox.lss.common.farplayers.FarPlayerWire;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

/**
 * The Paper per-tick snapshot builder (NMS via paperweight — Mojang-mapped twin of the Fabric builder; pump thread, Folia cross-region reads stale-tolerant by design) (E1, FARP §3.2): one snapshot per ONLINE player
 * per broadcast tick — never per viewer×target pair. Server thread only (plain entity
 * field reads). Equipment and vehicle types cross as identity strings (R-7 wire
 * neutrality — never numeric registry ids).
 */
final class PaperFarPlayerSnapshots {

    // Pose flag bits live in FarPlayerWire (shared with the E2 renderer).
    static final byte POSE_SNEAK = FarPlayerWire.POSE_SNEAK;
    static final byte POSE_GLIDE = FarPlayerWire.POSE_GLIDE;
    static final byte POSE_SWIM = FarPlayerWire.POSE_SWIM;

    private static final EquipmentSlot[] WIRE_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    static FarPlayerBroadcastService.PlayerSnapshot snapshot(ServerPlayer p) {
        byte pose = 0;
        if (p.isCrouching()) pose |= POSE_SNEAK;
        if (p.isFallFlying()) pose |= POSE_GLIDE;
        if (p.isSwimming()) pose |= POSE_SWIM;

        String[] equipmentIds = new String[FarPlayerWire.EQUIPMENT_SLOTS];
        int[] equipmentCounts = new int[FarPlayerWire.EQUIPMENT_SLOTS];
        long hash = 1469598103934665603L; // FNV-1a offset basis, the DirtyContentFilter idiom
        for (int i = 0; i < WIRE_SLOTS.length; i++) {
            var stack = p.getItemBySlot(WIRE_SLOTS[i]);
            if (!stack.isEmpty()) {
                equipmentIds[i] = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                equipmentCounts[i] = stack.getCount();
            }
            long slotHash = equipmentIds[i] == null ? 0 : equipmentIds[i].hashCode();
            hash = (hash ^ slotHash) * 1099511628211L;
            hash = (hash ^ equipmentCounts[i]) * 1099511628211L;
        }

        // R-10: the DIRECT mount only (stacks collapse); a player-vehicle drops to
        // unmounted (never recurse a player chain).
        FarPlayerWire.Vehicle vehicle = null;
        var v = p.getVehicle();
        if (v != null && !(v instanceof Player)) {
            vehicle = new FarPlayerWire.Vehicle(
                    BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()).toString(),
                    v.getUUID(),
                    FarPlayerWire.quantizePos(v.getX()),
                    FarPlayerWire.quantizePos(v.getY()),
                    FarPlayerWire.quantizePos(v.getZ()),
                    FarPlayerWire.angleToByte(v.getYRot()),
                    FarPlayerWire.angleToByte(v.getXRot()));
        }

        // getKnownMovement, NOT getDeltaMovement (E2 review M1): player motion is
        // client-authoritative — ServerPlayer.deltaMovement carries knockback and
        // little else, so the hint would read ~0 for an elytra player at 40 b/s and
        // extrapolation would ship inert. ServerPlayer overrides getKnownMovement to
        // return the move-packet-reported motion (and the vehicle's when ridden).
        var delta = p.getKnownMovement(); // blocks/tick -> blocks/second
        return new FarPlayerBroadcastService.PlayerSnapshot(
                p.getUUID(), p.getName().getString(),
                p.level().dimension().identifier().toString(),
                p.getX(), p.getY(), p.getZ(),
                p.getYRot(), p.getYHeadRot(), p.getXRot(),
                pose,
                delta.x * 20.0, delta.y * 20.0, delta.z * 20.0,
                p.isSpectator(), p.isInvisible(), p.isAlive() && !p.isRemoved(),
                // The privacy ladder (permission nodes + vanish bridge) — contained,
                // fail-hidden; the ladder's full rationale lives on hiddenFor. Pair-wise
                // Player#hideEntity remains uncovered (documented: per-viewer filtering
                // would break the once-per-tick snapshot inversion).
                hiddenFor(p.getBukkitEntity()),
                hash, equipmentIds, equipmentCounts, vehicle);
    }

    private static volatile boolean hiddenReadWarned;

    /**
     * The privacy ladder, CONTAINED per player and failing HIDDEN (Folia review
     * 2026-08-27 R2/R7, reversing the E2 fail-open): on Folia the pump reads a
     * cross-region {@code PermissibleBase} (a plain-HashMap check-then-act the
     * target's own region thread can recalculate mid-read) and vanish metadata whose
     * {@code LazyMetadataValue} callables can hit region-ownership checks that never
     * fire on Paper's main thread. A raced/throwing read must never LEAK a hidden or
     * vanished player's position — hiding too much for one interval is recoverable,
     * a leaked vanished admin is not. The throw is contained HERE so one broken
     * permissible cannot abort the snapshot pass for every other player (that was the
     * old failure shape: the only catch was around the whole pass). Once-per-JVM warn.
     */
    static boolean hiddenFor(org.bukkit.entity.Player bukkit) {
        try {
            // BOTH brand spellings are honored (2026-08-13, the VSS-restore round):
            // plugin.yml declares both nodes default-false, so the dual check is safe —
            // Bukkit resolves an UNDECLARED node to the op default, which is why a
            // single-brand declaration plus a cross-brand check would silently hide
            // every op. A jar swap keeps the grant.
            if (bukkit.hasPermission(dev.vox.lss.common.LSSPermissions.FARPLAYERS_HIDDEN_LSS)
                    || bukkit.hasPermission(dev.vox.lss.common.LSSPermissions.FARPLAYERS_HIDDEN_VSS)) {
                return true;
            }
            for (var meta : bukkit.getMetadata("vanished")) {
                if (meta.asBoolean()) return true;
            }
            return false;
        } catch (Exception e) {
            if (!hiddenReadWarned) {
                hiddenReadWarned = true;
                dev.vox.lss.common.LSSLogger.warn(
                        "Far-player privacy read (permission/vanish) threw — treating the"
                                + " affected player as HIDDEN (fail-safe direction; a raced"
                                + " read must never leak a hidden position). One warn per"
                                + " session (" + e + ")");
            }
            return true;
        }
    }

    /** Test seam. */
    static void resetHiddenReadWarnedForTest() {
        hiddenReadWarned = false;
    }

    private PaperFarPlayerSnapshots() {}
}
