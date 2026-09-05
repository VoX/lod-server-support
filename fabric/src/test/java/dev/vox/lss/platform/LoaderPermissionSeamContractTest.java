package dev.vox.lss.platform;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permission seam's per-loader override pins (service-permission-gate-plan.md
 * §4.1 / §8 O1-M3): {@code checkPermission} is a DEFAULT method whose fallback —
 * serve everyone — is indistinguishable from working, so a loader impl that forgets
 * the override ships the gate permanently inert with a green suite. The NeoForge
 * side is covered by its own widened completeness scan; this is the Fabric half,
 * plus the call-site default-value pin.
 */
class LoaderPermissionSeamContractTest {

    private static String source(String repoPath) throws IOException {
        return Files.readString(SourcePaths.repoFile(repoPath));
    }

    @Test
    void theFabricServerImplOverridesBothPermissionMethods() throws IOException {
        String impl = source("fabric/src/main/java/dev/vox/lss/platform/FabricLoaderServices.java");
        assertTrue(impl.contains("public boolean checkPermission("),
                "FabricLoaderServices must override checkPermission — the default serves "
                        + "everyone and no behavioural test can tell the difference");
        assertTrue(impl.contains("FabricPermissionsBridge.check(player, node, defaultValue)"),
                "…and route it through the reflective bridge");
        assertTrue(impl.contains("public String permissionProviderToken()"),
                "the Gate: diag line's provider token must be the loader's, not \"none\"");
    }

    @Test
    void everyServiceGateCallSitePassesDefaultTrue() throws IOException {
        // A flipped default is a silent server-wide black-out on the two platforms with
        // no plugin.yml to catch it (plan §2.1). Scan every production checkPermission
        // call site for the literal `true` default. The ONE deliberate `false` default —
        // the far-player hide nodes' grant-model read (far-player-render-hardening-plan.md
        // WI-7a) — lives in FabricFarPlayerSnapshots, outside the two gate files scanned
        // here, and is pinned by FabricFarPlayerSnapshotsHiddenTest instead.
        for (String repoPath : new String[] {
                "xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java",
                "xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java",
        }) {
            String text = source(repoPath);
            int idx = 0;
            while ((idx = text.indexOf("checkPermission(", idx)) >= 0) {
                int close = text.indexOf(")", idx);
                String call = text.substring(idx, close + 1);
                if (!call.contains("boolean defaultValue")) { // skip declarations
                    assertTrue(call.contains("true"),
                            repoPath + " has a checkPermission call without the literal "
                                    + "default-true: " + call);
                }
                idx = close;
            }
        }
    }

    // ---- the three-receiver census (plan §2.2 / §8 O1-M4) ----
    //
    // The open-gate overloads are a documented landmine: a production call site left on
    // one opens the gate for everyone with requireServicePermission=true still in the
    // file, and no behavioural test can tell (the armed path needs a live permission
    // backend). So the census pins, in source, that every production receiver reaches
    // the core through a REAL gate.

    @Test
    void theSharedGlueBuildsTheRealGateOnItsProductionEntry() throws IOException {
        String glue = source("xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java");
        assertTrue(glue.contains("serviceGateFor(player, service));"),
                "the 4-arg production entry must construct the real gate");
        assertEquals(1, occurrences(glue, "PlayerServiceGate.OPEN"),
                "exactly ONE open-gate use in the glue — the documented legacy/test "
                        + "overload delegation; a second is a production leak");
        assertTrue(glue.contains("config.enabled && !serviceDenied"),
                "the gate rides the SAME evaluate input as the server-wide kill switch");
        assertTrue(glue.contains("boolean deniedByServiceGate = serviceDenied && config.enabled && service != null")
                        && glue.contains("decision.outcome() == HandshakeGate.Outcome.DISABLED"),
                "the decision-anchored log/deposit conjunction (outcome DISABLED, enabled, "
                        + "servicePresent) must not drift from the Paper twin");
    }

    @Test
    void bothLoaderReceiversUseTheProductionEntry() throws IOException {
        // Fabric: the registered receiver lambda calls the 4-arg entry (payload, player,
        // service, responder) — never a wider overload that could pass OPEN.
        String fabric = source("fabric/src/main/java/dev/vox/lss/networking/server/LSSServerNetworking.java");
        assertTrue(fabric.contains("(payload, context) -> ServerReceiverGlue.handleHandshake(")
                        && fabric.contains("payload, context.player(), requestService,"),
                "the Fabric receiver must ride the 4-arg production entry");
        assertFalse(fabric.contains("OPEN"),
                "no open-gate reference may appear in the Fabric receiver");

        String neo = source("neoforge/src/main/java/dev/vox/lss/networking/server/LSSServerNetworking.java");
        assertTrue(neo.contains("ServerReceiverGlue.handleHandshake(payload, player, requestService,"),
                "the NeoForge receiver must ride the 4-arg production entry");
        assertFalse(neo.contains("OPEN"),
                "no open-gate reference may appear in the NeoForge receiver");
    }

    @Test
    void thePaperCallSitePassesTheRealGate() throws IOException {
        String paper = source("paper/src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java");
        assertTrue(paper.contains("serviceGateFor(bukkitPlayer, nmsPlayer.getUUID(),"),
                "Paper's production call site must construct the real Bukkit gate");
        assertEquals(1, occurrences(paper, "SERVICE_GATE_OPEN, configSender, registrar);"),
                "exactly ONE open-gate use on Paper — the documented legacy/test overload");
        assertTrue(paper.contains("service.getServiceGateState()"),
                "the production gate must be wired to the service's gate state (memo + latch)");
        assertTrue(paper.contains("enqueueServiceGateUnregister"),
                "the denial hook must marshal the unregistration composite onto the pump");
    }

    @Test
    void bothDisconnectHooksSweepTheGateState() throws IOException {
        for (String repoPath : new String[] {
                "fabric/src/main/java/dev/vox/lss/networking/server/LSSServerNetworking.java",
                "neoforge/src/main/java/dev/vox/lss/networking/server/LSSServerNetworking.java",
        }) {
            assertTrue(source(repoPath).contains(".getServiceGateState().onDisconnect("),
                    repoPath + " must sweep the gate state beside the client-info fact — "
                            + "without it every denied joiner leaks a memo entry for the "
                            + "server's life (§8 F2-M2)");
        }
    }

    @Test
    void theSharedServiceTickCadenceAndNoProviderWarnArePinned() throws IOException {
        // Implementation review 2026-08-27 (the O1-M3 class): the tick cadence is the
        // ONLY production caller of the sweeps on Fabric/NeoForge — without these pins
        // the whole live-recheck half is deletable on two loaders with a green suite.
        String svc = source("xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java");
        assertTrue(svc.contains("static final int PERMISSION_RECHECK_TICKS = 200;"),
                "the recheck cadence constant (plan §2.3: 200 ticks)");
        assertTrue(svc.contains("if (++this.permissionRecheckCounter >= PERMISSION_RECHECK_TICKS)")
                        && svc.contains("runServiceGateSweeps(config);"),
                "tick() must run the sweeps on the cadence");
        assertTrue(svc.contains("\"none\".equals(")
                        && svc.contains("no permission provider "),
                "the armed-gate-without-provider once-warn (plan §2.1/§8 N-2) lives in "
                        + "the sweep, keyed on the LoaderServices provider token");
    }

    @Test
    void thePaperReplayWiringAndQuitSweepArePinned() throws IOException {
        String svc = source("paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java");
        assertTrue(svc.contains("this.handshakeReplayer = lss::replayServiceGateHandshake;"),
                "the production constructor must wire the grant sweep's replay to the "
                        + "plugin's receiver body — without it a re-grant strands every "
                        + "denied Paper player until rejoin (implementation review MAJOR)");
        assertTrue(svc.contains("if (++this.permissionRecheckCounter >= PERMISSION_RECHECK_TICKS)"),
                "Paper's tick cadence (its behavioral twin lives in PaperServiceGateSweepTest)");
        String plugin = source("paper/src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java");
        // v0.14 M-1: Paper's quit hook routes removal through the mailbox and must NOT
        // sweep the gate state directly — the un-guarded direct wipe could erase a
        // fast-rejoiner's fresh denial memo on Folia (the R4 belt exists to skip the
        // sweep when a newer connection handshaked since the quit). The gate sweep rides
        // the EPOCH-GUARDED Remove drain instead; without it a denied joiner still leaks.
        assertTrue(plugin.contains("service.enqueueRemove(event.getPlayer().getUniqueId());"),
                "Paper's quit hook routes removal through the mailbox (Folia region-thread safety)");
        assertFalse(plugin.contains(".getServiceGateState().onDisconnect(event.getPlayer().getUniqueId());"),
                "Paper's quit hook must NOT sweep the gate state directly (v0.14 M-1 belt bypass)");
        assertTrue(svc.contains("if (noNewerConnection) {")
                        && svc.contains("this.serviceGateState.onDisconnect(r.uuid());"),
                "the quit's gate sweep happens in the EPOCH-GUARDED Remove drain — swept "
                        + "only when no newer connection handshaked since (the R4 belt)");
        assertTrue(svc.contains("this.serviceGateState.onDisconnect(uuid);"),
                "…and the departed-player sweep (the quit event never fired for those) too");
    }

    @Test
    void noOtherXplatFileTouchesTheOpenGate() throws IOException {
        // Widened census (implementation review NIT): the OPEN landmine count inside
        // the glue is pinned above; this guards the rest of the shared source set.
        java.nio.file.Path root = SourcePaths.repoFile("xplat/src/main/java");
        try (var walk = Files.walk(root)) {
            for (var f : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals("ServerReceiverGlue.java")) continue;
                if (f.getFileName().toString().equals("PlayerServiceGate.java")) continue;
                String text = Files.readString(f);
                assertFalse(text.contains("PlayerServiceGate.OPEN"),
                        f + " references the open gate — production call sites must build "
                                + "a real gate");
            }
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
