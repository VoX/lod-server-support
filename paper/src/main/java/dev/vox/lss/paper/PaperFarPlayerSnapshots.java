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
        // The two privacy facts, read together and contained: the unconditional ladder
        // (vanish, disguise, any failed read) and the hide-permission fact, which the
        // service enforces only while far_players.hide_permission_holders is on. The
        // full rationale lives on privacyFor. Pair-wise Player#hideEntity remains
        // uncovered (documented: per-viewer filtering would break the once-per-tick
        // snapshot inversion).
        var privacy = privacyFor(p.getBukkitEntity());
        return new FarPlayerBroadcastService.PlayerSnapshot(
                p.getUUID(), p.getName().getString(),
                p.level().dimension().location().toString(),
                p.getX(), p.getY(), p.getZ(),
                p.getYRot(), p.getYHeadRot(), p.getXRot(),
                pose,
                delta.x * 20.0, delta.y * 20.0, delta.z * 20.0,
                p.isSpectator(), p.isInvisible(), p.isAlive() && !p.isRemoved(),
                privacy.hidden(), privacy.holdsHidePermission(),
                hash, equipmentIds, equipmentCounts, vehicle);
    }

    private static volatile boolean hiddenReadWarned;

    /** One player's two privacy facts (hide-permission plan §3, 0.21.0). {@code hidden}
     *  is the unconditional ladder: vanish metadata, a disguise, or ANY failed read.
     *  {@code holdsHidePermission} is only the fact that the player holds either
     *  spelling of the hide node; the service withholds a holder only while
     *  {@code far_players.hide_permission_holders} is on. */
    record Privacy(boolean hidden, boolean holdsHidePermission) {
        /** A read threw: HIDDEN, and no permission fact is claimed. */
        static final Privacy FAILED_READ = new Privacy(true, false);
    }

    /**
     * The privacy reads, CONTAINED per player and failing HIDDEN (Folia review
     * 2026-08-27 R2/R7, reversing the E2 fail-open): on Folia the pump reads a
     * cross-region {@code PermissibleBase} (a plain-HashMap check-then-act the
     * target's own region thread can recalculate mid-read) and vanish metadata whose
     * {@code LazyMetadataValue} callables can hit region-ownership checks that never
     * fire on Paper's main thread. A raced/throwing read must never LEAK a hidden or
     * vanished player's position — hiding too much for one interval is recoverable,
     * a leaked vanished admin is not. The throw is contained HERE so one broken
     * permissible cannot abort the snapshot pass for every other player (that was the
     * old failure shape: the only catch was around the whole pass). Once-per-JVM warn.
     *
     * <p>Since 0.21.0 the permission is a FACT, not a verdict: by default a holder is
     * shown like anyone else (a wildcard grant used to hide every admin), and the
     * service hides holders only while {@code far_players.hide_permission_holders} is
     * on. That does not relax the fail direction: a throw from ANY read here, the
     * permission read included, still answers HIDDEN whatever the setting says — the
     * permission read shares the racy permissible with nothing else to tell "raced"
     * from "not held", and with the setting on a wrongly-visible holder is a leak.
     */
    static Privacy privacyFor(org.bukkit.entity.Player bukkit) {
        try {
            // BOTH brand spellings are honored (2026-08-13, the VSS-restore round):
            // plugin.yml declares both nodes default-false, so the dual check is safe —
            // Bukkit resolves an UNDECLARED node to the op default, which is why a
            // single-brand declaration plus a cross-brand check would silently make
            // every op a holder. A jar swap keeps the grant.
            boolean holds = bukkit.hasPermission(dev.vox.lss.common.LSSPermissions.FARPLAYERS_HIDDEN_LSS)
                    || bukkit.hasPermission(dev.vox.lss.common.LSSPermissions.FARPLAYERS_HIDDEN_VSS);
            return new Privacy(ladderHides(bukkit), holds);
        } catch (Exception e) {
            if (!hiddenReadWarned) {
                hiddenReadWarned = true;
                dev.vox.lss.common.LSSLogger.warn(
                        "Far-player privacy read (permission/vanish/disguise) threw — treating the"
                                + " affected player as HIDDEN (fail-safe direction; a raced"
                                + " read must never leak a hidden position). One warn per"
                                + " session (" + e + ")");
            }
            return Privacy.FAILED_READ;
        }
    }

    /** The unconditional rungs, in order: vanish metadata, then a disguise. Throws
     *  propagate to {@link #privacyFor}'s catch (HIDDEN). */
    private static boolean ladderHides(org.bukkit.entity.Player bukkit) {
        for (var meta : bukkit.getMetadata("vanished")) {
            if (meta.asBoolean()) return true;
        }
        // Issue #282: a LibsDisguises disguise replaces the player's spawn with a
        // disguise-specific UUID, so the CLIENT handoff belt (no vanilla entity with
        // the roster UUID → draw the proxy) reveals the disguised player through the
        // LOD terrain. Only the server knows — a throwing read is wrapped by the
        // bridge into an IllegalStateException and lands in privacyFor's catch (HIDDEN).
        return LibsDisguisesBridge.isDisguised(bukkit);
    }

    /** The unconditional verdict alone: vanish, a disguise, or any failed read (the
     *  permission read included). Holding the hide permission is NOT part of it. */
    static boolean hiddenFor(org.bukkit.entity.Player bukkit) {
        return privacyFor(bukkit).hidden();
    }

    /** Test seam. */
    static void resetHiddenReadWarnedForTest() {
        hiddenReadWarned = false;
    }

    private PaperFarPlayerSnapshots() {}
}
