package dev.vox.lss.networking.client;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-axis WIRING pins (plan §4.4 "source-pin the wiring" — panel MAJOR: the one
 * wire between the tested machinery and production is a private, MC-bound factory, and
 * disconnecting it is invisible to every behavioural test because the fallback is the
 * deliberately-safe direction). The {@code XaeroWiringContractTest} shape: pin the
 * source of the single assignment point, and the gate's session-bracket calls.
 */
class TwoAxisWiringContractTest {

    private static String glueSource() throws IOException {
        return Files.readString(SourcePaths.mainSource(
                "dev/vox/lss/networking/client/ClientNetGlue.java"));
    }

    @Test
    void theFactoryRoutesTheKeyThroughTheLatchAndTheManagerWiring() throws IOException {
        String source = glueSource();
        int factory = source.indexOf("private static LodRequestManager createRequestManager");
        assertTrue(factory >= 0, "the single production factory must exist");
        int factoryEnd = source.indexOf("private static AliasLatch.Decision unaliasedDecision");
        String body = source.substring(factory, factoryEnd > factory ? factoryEnd : source.length());
        assertTrue(body.contains("AliasLatch.forConnection("),
                "the alias decision must go through the per-session latch");
        assertTrue(body.contains("manager.onSessionConfig(payload, decision.addressComponent())"),
                "the manager must be keyed by the LATCHED address component");
        assertTrue(body.contains("manager.configureCacheKeying(decision.addressComponent(), "
                        + "decision.sweepComponents(),"),
                "the two-axis wiring (sweep members + axis token) must be configured");
        assertTrue(body.contains("ClientWorldSeed::context"),
                "the world axis must read the LIVE context — a disabled supplier here "
                        + "silently turns the whole feature off with every test green");
    }

    @Test
    void theCorroborationInputsComeFromTheModCompatGates() throws IOException {
        String source = glueSource();
        int decide = source.indexOf("private static AliasLatch.Decision computeAliasDecision");
        assertTrue(decide >= 0);
        String body = source.substring(decide);
        assertTrue(body.contains("ModCompat.isVoxyBridgeActive()"), body.substring(0, 200));
        assertTrue(body.contains("ModCompat.isXaeroBridgeArmed()"));
        assertTrue(body.contains("ModCompat.observeVoxyStorageDirName()"));
        assertTrue(body.contains("CacheKeyAliases.addressComponent(group.canonicalRaw())"),
                "an applied group must key by the CANONICAL's component");
    }

    @Test
    void theGateBracketsBothSessionLatches() throws IOException {
        String source = Files.readString(SourcePaths.mainSource(
                "dev/vox/lss/networking/client/ClientSessionGate.java"));
        int join = source.lastIndexOf("void onJoin(");
        assertTrue(join >= 0, "the five-arg onJoin body must exist");
        int firstReturn = source.indexOf("if (!receiveServerLods) return;", join);
        String preReturns = source.substring(join, firstReturn);
        assertTrue(preReturns.contains("AliasLatch.resetForJoin();"),
                "JOIN must reset the alias latch BEFORE its early returns");
        assertTrue(preReturns.contains("ResetCoordinator.clearForceGrant();"),
                "JOIN must clear the force grant BEFORE its early returns (a "
                        + "reconfiguration fires no disconnect)");
    }

    @Test
    void disconnectThroughTheRealGateClearsTheForceGrant() {
        // The behavioural half (panel fix): the coordinator test clears the grant
        // directly; this drives it through the production caller.
        ResetCoordinator.clearForceGrant();
        var gate = new ClientSessionGate(new ClientColumnProcessor(), v -> {},
                config -> { throw new AssertionError("no manager needed"); });
        // Arm a grant the raw way (stage-1 machinery is exercised elsewhere).
        gate.onJoin(false, false, false, true); // establishes a clean session bracket
        armRawGrant();
        gate.onDisconnect();
        assertNull(ResetCoordinator.peekForceGrantForTest(),
                "a grant must not survive its connection");
    }

    private static void armRawGrant() {
        // Package access: drive stage 1 with a minimal deps shape.
        var deps = new ResetCoordinator.Deps(true, () -> { },
                (force, granted) -> { throw new AssertionError("stage 1 never resets"); },
                () -> new dev.vox.lss.compat.ModCompat.VoxyStorageProbe(true,
                        dev.vox.lss.compat.VoxyStorageOverride.Verdict.OVERRIDDEN,
                        java.nio.file.Path.of("/g/.voxy/saves/x"),
                        java.nio.file.Path.of("/g/.voxy/saves/y"), true),
                () -> { }, () -> { }, () -> { }, line -> { },
                () -> new Object(), System::nanoTime);
        ResetCoordinator.run(deps, false, true);
        assertTrue(ResetCoordinator.peekForceGrantForTest() != null, "arming failed");
    }
}
