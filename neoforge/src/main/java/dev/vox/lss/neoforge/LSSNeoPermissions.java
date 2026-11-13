package dev.vox.lss.neoforge;

import dev.vox.lss.common.FaultCounters;
import dev.vox.lss.common.LSSPermissions;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * The NeoForge permission rung of the service gate
 * (service-permission-gate-plan.md §2.1): NATIVE, no reflection — the module
 * compiles against NeoForge. Both brand spellings of the SERVICE node are registered
 * as {@link PermissionNode}s with DEFAULT-TRUE resolvers at
 * {@link PermissionGatherEvent.Nodes}, UNCONDITIONALLY: an unregistered node THROWS
 * at query time, a runtime {@code set requireServicePermission true} must work, and
 * the gather event fires during server start before any LSS query can run
 * (verified at the plan review — the service starts at ServerStartedEvent). The two
 * far-player HIDE nodes (far-player-render-hardening-plan.md WI-7a) register beside
 * them with DEFAULT-FALSE resolvers — a grant model, the opposite shape (see
 * {@link LSSPermissions}); since 0.21.0 a holder is hidden from far-player rendering
 * only while the server sets {@code far_players.hide_permission_holders: true}
 * (hide-permission plan §2) — and so do the two MAP-INGESTION exemption nodes
 * (map-ingestion permission plan §2: default false, grant = exempt from
 * {@code privacy.request_no_map_ingestion}; their read passes default FALSE).
 *
 * <p>{@code PermissionAPI.getPermission} is keyed on the registered node INSTANCES
 * (never equal-named copies — pinned); the string-keyed {@code check} maps the
 * shared {@link LSSPermissions} constants to them, and an unknown string answers
 * {@code defaultValue} without ever querying (it would throw). Every failure shape
 * answers the caller's default — fail-open for the gate (true), "not a holder" for the
 * hide nodes (false): this seam cannot express "the backend threw", and a true
 * default would make every player a holder on a provider-less server.
 */
public final class LSSNeoPermissions {

    /** The registered node instances — one per brand spelling, default-true. */
    public static final PermissionNode<Boolean> SERVICE_LSS = node(LSSPermissions.SERVICE_LSS);
    public static final PermissionNode<Boolean> SERVICE_VSS = node(LSSPermissions.SERVICE_VSS);
    /** The far-player hide nodes — default-FALSE resolvers (grant = a holder; hidden only
     *  while {@code far_players.hide_permission_holders} is on). */
    public static final PermissionNode<Boolean> FARPLAYERS_HIDDEN_LSS = denyNode(LSSPermissions.FARPLAYERS_HIDDEN_LSS);
    public static final PermissionNode<Boolean> FARPLAYERS_HIDDEN_VSS = denyNode(LSSPermissions.FARPLAYERS_HIDDEN_VSS);
    /** The map-ingestion exemption nodes — default-FALSE resolvers (grant = exempt). */
    public static final PermissionNode<Boolean> MAP_INGESTION_LSS = denyNode(LSSPermissions.MAP_INGESTION_LSS);
    public static final PermissionNode<Boolean> MAP_INGESTION_VSS = denyNode(LSSPermissions.MAP_INGESTION_VSS);


    private LSSNeoPermissions() {
    }

    private static PermissionNode<Boolean> node(String name) {
        int dot = name.indexOf('.');
        // PermissionNode(namespace, path, ...) composes namespace + "." + path — so
        // "lss" + "use" IS the "lss.use" spelling plugin.yml declares on Paper.
        return new PermissionNode<>(name.substring(0, dot), name.substring(dot + 1),
                PermissionTypes.BOOLEAN, (player, uuid, contexts) -> true);
    }

    /** A grant-model node: nobody holds it until an admin grants it. */
    private static PermissionNode<Boolean> denyNode(String name) {
        int dot = name.indexOf('.');
        return new PermissionNode<>(name.substring(0, dot), name.substring(dot + 1),
                PermissionTypes.BOOLEAN, (player, uuid, contexts) -> false);
    }

    /** The gather-event listener — wired unconditionally from {@code LSSNeoMod}. */
    public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(SERVICE_LSS, SERVICE_VSS, FARPLAYERS_HIDDEN_LSS, FARPLAYERS_HIDDEN_VSS,
                MAP_INGESTION_LSS, MAP_INGESTION_VSS);
    }

    /** The string→node map: exactly the shared spellings; anything else answers the
     *  caller's default without querying (an unregistered node would throw). */
    private static PermissionNode<Boolean> nodeFor(String nodeName) {
        return LSSPermissions.SERVICE_LSS.equals(nodeName) ? SERVICE_LSS
                : LSSPermissions.SERVICE_VSS.equals(nodeName) ? SERVICE_VSS
                : LSSPermissions.FARPLAYERS_HIDDEN_LSS.equals(nodeName) ? FARPLAYERS_HIDDEN_LSS
                : LSSPermissions.FARPLAYERS_HIDDEN_VSS.equals(nodeName) ? FARPLAYERS_HIDDEN_VSS
                : LSSPermissions.MAP_INGESTION_LSS.equals(nodeName) ? MAP_INGESTION_LSS
                : LSSPermissions.MAP_INGESTION_VSS.equals(nodeName) ? MAP_INGESTION_VSS : null;
    }

    /** The string-keyed read behind {@code LoaderServices.checkPermission}. */
    public static boolean check(ServerPlayer player, String nodeName, boolean defaultValue) {
        PermissionNode<Boolean> node = nodeFor(nodeName);
        if (node == null || player == null) return defaultValue;
        try {
            return PermissionAPI.getPermission(player, node);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            FaultCounters.global().record(FaultCounters.Site.PERMISSION_CHECK,
                    "NeoForge PermissionAPI query threw; answering the default (" + defaultValue + ") for "
                            + nodeName + " (" + t + ")");
            return defaultValue;
        }
    }
}
