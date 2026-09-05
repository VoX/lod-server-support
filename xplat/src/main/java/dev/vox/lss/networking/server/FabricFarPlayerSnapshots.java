package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSPermissions;
import dev.vox.lss.common.farplayers.FarPlayerBroadcastService;
import dev.vox.lss.common.farplayers.FarPlayerWire;
import dev.vox.lss.platform.LoaderServices;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

/**
 * The Fabric per-tick snapshot builder (E1, FARP §3.2): one snapshot per ONLINE player
 * per broadcast tick — never per viewer×target pair. Server thread only (plain entity
 * field reads). Equipment and vehicle types cross as identity strings (R-7 wire
 * neutrality — never numeric registry ids).
 */
final class FabricFarPlayerSnapshots {

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
                p.level().dimension().location().toString(),
                p.getX(), p.getY(), p.getZ(),
                p.getYRot(), p.getYHeadRot(), p.getXRot(),
                pose,
                delta.x * 20.0, delta.y * 20.0, delta.z * 20.0,
                p.isSpectator(), p.isInvisible(), p.isAlive() && !p.isRemoved(),
                // The hide permission, read through the loader seam (fabric-permissions-api
                // by shape on Fabric, native PermissionNodes on NeoForge) — WI-7a.
                hiddenFor(node -> LoaderServices.get().checkPermission(p, node, false)),
                hash, equipmentIds, equipmentCounts, vehicle);
    }

    /**
     * The hide-permission read (far-player-render-hardening-plan.md WI-7a): BOTH brand
     * spellings, default FALSE — a grant-model "deny me" lever (undefined = visible, matching
     * plugin.yml's {@code default: false} on Paper; a LuckPerms wildcard grant resolves it
     * TRUE, so wildcard admins disappear from far-player rendering, as on Paper). Fail
     * direction is VISIBLE on this seam BY CONTRACT: {@code LoaderServices.checkPermission}
     * never throws and answers the caller's default on any failure, so {@code false} cannot
     * express "the backend threw" — and a {@code true} default would hide every player on a
     * provider-less server. Paper's twin fails HIDDEN only because Folia's cross-region
     * {@code PermissibleBase} read can race; Fabric and NeoForge read on the single server
     * tick thread. Pure over a node predicate so JUnit can drive it without a ServerPlayer.
     */
    static boolean hiddenFor(java.util.function.Predicate<String> check) {
        return check.test(LSSPermissions.FARPLAYERS_HIDDEN_LSS)
                || check.test(LSSPermissions.FARPLAYERS_HIDDEN_VSS);
    }

    private FabricFarPlayerSnapshots() {}
}
