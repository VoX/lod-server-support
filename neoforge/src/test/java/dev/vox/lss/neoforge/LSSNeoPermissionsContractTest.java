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
        assertTrue(src.contains("(player, uuid, contexts) -> true"),
                "the default resolver must answer TRUE — arming the gate alone denies nobody");
        assertTrue(src.contains("PermissionTypes.BOOLEAN"), "boolean-typed nodes");
    }

    @Test
    void theFarPlayerHideNodesAreMintedFromTheSharedConstantsWithDefaultFalseResolvers() throws IOException {
        // far-player-render-hardening-plan.md WI-7a: the OPPOSITE shape of the service nodes —
        // a grant-model "deny me" lever. A default-TRUE resolver here would hide every player
        // on a provider-less NeoForge server; an unregistered node would THROW at the first
        // snapshot read (and the read's containment would then hide far players for good).
        String src = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoPermissions.java");
        assertTrue(src.contains("denyNode(LSSPermissions.FARPLAYERS_HIDDEN_LSS)")
                        && src.contains("denyNode(LSSPermissions.FARPLAYERS_HIDDEN_VSS)"),
                "both hide spellings must be minted from the shared constants as deny nodes");
        assertTrue(src.contains("(player, uuid, contexts) -> false"),
                "the hide nodes' default resolver must answer FALSE (nobody is hidden by default)");
    }

    @Test
    void theGatherListenerIsWiredUnconditionally() throws IOException {
        String mod = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoMod.java");
        assertTrue(mod.contains("PermissionGatherEvent.Nodes.class"),
                "LSSNeoMod must subscribe the gather event");
        assertTrue(mod.contains("LSSNeoPermissions::onGatherNodes"),
                "…routing to the node registration");
        String perms = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoPermissions.java");
        assertTrue(perms.contains("event.addNodes(SERVICE_LSS, SERVICE_VSS, FARPLAYERS_HIDDEN_LSS, FARPLAYERS_HIDDEN_VSS)"),
                "all four nodes register — a missing one throws at its first query");
    }

    @Test
    void theQueryUsesTheRegisteredInstancesAndTheSeamRoutesToIt() throws IOException {
        String perms = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoPermissions.java");
        assertTrue(perms.contains("PermissionAPI.getPermission(player, node)"),
                "the query is instance-keyed — an equal-named copy would throw unregistered");
        assertTrue(perms.contains("? SERVICE_LSS")
                        && perms.contains("? SERVICE_VSS")
                        && perms.contains("? FARPLAYERS_HIDDEN_LSS")
                        && perms.contains("? FARPLAYERS_HIDDEN_VSS : null"),
                "the string→node map covers exactly the four shared spellings; unknown "
                        + "strings answer the default without querying");
        String services = read("neoforge/src/main/java/dev/vox/lss/platform/NeoForgeLoaderServices.java");
        assertTrue(services.contains("LSSNeoPermissions.check(player, node, defaultValue)"),
                "the LoaderServices override must route to the native rung");
        assertTrue(services.contains("return \"neoforge\";"),
                "the provider token names the native backend");
    }
}
