package dev.vox.lss.neoforge;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LSSPermissions;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * The NeoForge permission rung of the service gate
 * (service-permission-gate-plan.md §2.1): NATIVE, no reflection — the module
 * compiles against NeoForge. Both brand spellings are registered as
 * {@link PermissionNode}s with DEFAULT-TRUE resolvers at
 * {@link PermissionGatherEvent.Nodes}, UNCONDITIONALLY: an unregistered node THROWS
 * at query time, a runtime {@code set requireServicePermission true} must work, and
 * the gather event fires during server start before any LSS query can run
 * (verified at the plan review — the service starts at ServerStartedEvent).
 *
 * <p>{@code PermissionAPI.getPermission} is keyed on the registered node INSTANCES
 * (never equal-named copies — pinned); the string-keyed {@code check} maps the
 * shared {@link LSSPermissions} constants to them, and an unknown string answers
 * {@code defaultValue} without ever querying (it would throw). Every failure shape
 * answers the default — fail-open, the gate's doctrine.
 */
public final class LSSNeoPermissions {

    /** The registered node instances — one per brand spelling, default-true. */
    public static final PermissionNode<Boolean> SERVICE_LSS = node(LSSPermissions.SERVICE_LSS);
    public static final PermissionNode<Boolean> SERVICE_VSS = node(LSSPermissions.SERVICE_VSS);

    private static volatile boolean queryWarned;

    private LSSNeoPermissions() {
    }

    private static PermissionNode<Boolean> node(String name) {
        int dot = name.indexOf('.');
        // PermissionNode(namespace, path, ...) composes namespace + "." + path — so
        // "lss" + "use" IS the "lss.use" spelling plugin.yml declares on Paper.
        return new PermissionNode<>(name.substring(0, dot), name.substring(dot + 1),
                PermissionTypes.BOOLEAN, (player, uuid, contexts) -> true);
    }

    /** The gather-event listener — wired unconditionally from {@code LSSNeoMod}. */
    public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(SERVICE_LSS, SERVICE_VSS);
    }

    /** The string-keyed read behind {@code LoaderServices.checkPermission}. */
    public static boolean check(ServerPlayer player, String nodeName, boolean defaultValue) {
        PermissionNode<Boolean> node =
                LSSPermissions.SERVICE_LSS.equals(nodeName) ? SERVICE_LSS
                        : LSSPermissions.SERVICE_VSS.equals(nodeName) ? SERVICE_VSS : null;
        if (node == null || player == null) return defaultValue;
        try {
            return PermissionAPI.getPermission(player, node);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            if (!queryWarned) {
                queryWarned = true;
                LSSLogger.warn("NeoForge PermissionAPI query threw — answering the default ("
                        + defaultValue + ") for " + nodeName + " (" + t + ")");
            }
            return defaultValue;
        }
    }
}
