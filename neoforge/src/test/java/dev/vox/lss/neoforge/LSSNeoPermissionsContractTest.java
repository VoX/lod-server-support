package dev.vox.lss.neoforge;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static dev.vox.lss.neoforge.NeoForgeModuleContractTest.read;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The NeoForge permission rung's contract (service-permission-gate-plan.md §4.1) —
 * source-flavored like the module's other pins (the classes are un-instantiable in a
 * bare JUnit JVM). Four facts, each with a silent failure mode:
 *
 * <ol>
 *   <li>the registered node names come from the SHARED constants (a literal drift
 *       here splits the enforcement across modules with nothing red);</li>
 *   <li>the default resolvers answer TRUE (a false default is a server-wide
 *       black-out the moment an operator arms the key);</li>
 *   <li>the gather-event listener is wired UNCONDITIONALLY in LSSNeoMod (an
 *       unregistered node THROWS at query time, and a runtime `set` arm must find
 *       the nodes live);</li>
 *   <li>the query goes through the registered node INSTANCES via
 *       PermissionAPI.getPermission, and the loader seam routes to it.</li>
 * </ol>
 */
class LSSNeoPermissionsContractTest {

    @Test
    void nodesAreBuiltFromTheSharedConstantsWithDefaultTrueResolvers() throws IOException {
        String src = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoPermissions.java");
        assertTrue(src.contains("node(LSSPermissions.SERVICE_LSS)"),
                "the LSS node must be minted from the shared constant");
        assertTrue(src.contains("node(LSSPermissions.SERVICE_VSS)"),
                "the VSS node must be minted from the shared constant");
        assertTrue(java.util.regex.Pattern.compile("node\\(String name\\)[\\s\\S]{0,400}?\\(player, uuid, contexts\\) -> true")
                        .matcher(src).find(),
                "node()'s resolver must answer TRUE — arming the gate alone denies nobody (scoped to the"
                        + " helper — review fold B2: a swap with denyNode() must red)");
        assertTrue(src.contains("PermissionTypes.BOOLEAN"), "boolean-typed nodes");
    }

    @Test
    void theFarPlayerHideNodesAreMintedFromTheSharedConstantsWithDefaultFalseResolvers() throws IOException {
        // far-player-render-hardening-plan.md WI-7a: the OPPOSITE shape of the service nodes —
        // a grant model. Since 0.21.0 (hide-permission plan §2) a holder is hidden only while
        // far_players.hide_permission_holders is on, so a default-TRUE resolver here would
        // make every player a holder on a provider-less NeoForge server (all hidden the moment
        // an operator turns the setting on, and a false upgrade notice before that); an
        // unregistered node would THROW at the first snapshot read (the seam's containment
        // then answers "not a holder" for good, so the setting could never hide anyone).
        String src = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoPermissions.java");
        assertTrue(src.contains("far_players.hide_permission_holders"),
                "the class doc must name the setting that makes the hide nodes hide");
        assertTrue(src.contains("denyNode(LSSPermissions.FARPLAYERS_HIDDEN_LSS)")
                        && src.contains("denyNode(LSSPermissions.FARPLAYERS_HIDDEN_VSS)"),
                "both hide spellings must be minted from the shared constants as deny nodes");
        assertTrue(java.util.regex.Pattern.compile("denyNode\\(String name\\)[\\s\\S]{0,400}?\\(player, uuid, contexts\\) -> false")
                        .matcher(src).find(),
                "denyNode()'s resolver must answer FALSE (nobody is hidden by default; scoped to the"
                        + " helper — review fold B2)");
    }

    @Test
    void theMapIngestionNodesAreMintedFromTheSharedConstantsAsDenyNodes() throws IOException {
        // Map-ingestion permission plan §2/§3.1: a grant-model exemption, default FALSE — a
        // default-TRUE resolver would exempt everyone from privacy.request_no_map_ingestion on
        // a provider-less NeoForge server; an unregistered node would answer the caller's
        // default without ever querying LuckPerms (nobody could be granted it).
        String src = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoPermissions.java");
        assertTrue(src.contains("MAP_INGESTION_LSS = denyNode(LSSPermissions.MAP_INGESTION_LSS)")
                        && src.contains("MAP_INGESTION_VSS = denyNode(LSSPermissions.MAP_INGESTION_VSS)"),
                "both exemption spellings are minted from the shared constants as deny (default-false) nodes");
    }

    @Test
    void theGatherListenerIsWiredUnconditionally() throws IOException {
        String mod = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoMod.java");
        assertTrue(mod.contains("PermissionGatherEvent.Nodes.class"),
                "LSSNeoMod must subscribe the gather event");
        assertTrue(mod.contains("LSSNeoPermissions::onGatherNodes"),
                "…routing to the node registration");
        String perms = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoPermissions.java");
        assertTrue(perms.replaceAll("\\s+", " ").contains("event.addNodes(SERVICE_LSS, SERVICE_VSS, FARPLAYERS_HIDDEN_LSS, "
                        + "FARPLAYERS_HIDDEN_VSS, MAP_INGESTION_LSS, MAP_INGESTION_VSS);"),
                "all six nodes register — exactly this list; a missing one is never queried");
    }

    @Test
    void theQueryUsesTheRegisteredInstancesAndTheSeamRoutesToIt() throws IOException {
        String perms = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoPermissions.java");
        assertTrue(perms.contains("PermissionAPI.getPermission(player, node)"),
                "the query passes the REGISTERED node instances (PermissionNode equality is name+type,"
                        + " so this pins the routing, not identity)");
        assertTrue(perms.contains("LSSPermissions.SERVICE_LSS.equals(nodeName) ? SERVICE_LSS")
                        && perms.contains("LSSPermissions.SERVICE_VSS.equals(nodeName) ? SERVICE_VSS")
                        && perms.contains("LSSPermissions.FARPLAYERS_HIDDEN_LSS.equals(nodeName) ? FARPLAYERS_HIDDEN_LSS")
                        && perms.contains("LSSPermissions.FARPLAYERS_HIDDEN_VSS.equals(nodeName) ? FARPLAYERS_HIDDEN_VSS")
                        && perms.contains("LSSPermissions.MAP_INGESTION_LSS.equals(nodeName) ? MAP_INGESTION_LSS")
                        && perms.contains("LSSPermissions.MAP_INGESTION_VSS.equals(nodeName) ? MAP_INGESTION_VSS : null"),
                "the string→node map covers exactly the six shared spellings, each to its own "
                        + "node; unknown strings answer the default without querying");
        String services = read("neoforge/src/main/java/dev/vox/lss/platform/NeoForgeLoaderServices.java");
        assertTrue(services.contains("LSSNeoPermissions.check(player, node, defaultValue)"),
                "the LoaderServices override must route to the native rung");
        assertTrue(services.contains("return \"neoforge\";"),
                "the provider token names the native backend");
    }
}
