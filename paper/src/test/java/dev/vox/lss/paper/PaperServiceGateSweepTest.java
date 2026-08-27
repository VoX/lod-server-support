package dev.vox.lss.paper;

import dev.vox.lss.common.HandshakeGate;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSPermissions;
import dev.vox.lss.common.ServiceGateState;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The service-gate recheck sweeps (service-permission-gate-plan.md §2.3 / §4.3) on the
 * Paper service — the §8 O1-m11 differential list: cadence, two-sweep hysteresis,
 * still-denied idempotence, the CURRENT-only revocation (legacy sessions heal at
 * rejoin — the recorded repushSessionConfig doctrine), the composite's viewer shed,
 * the memo deposit carrying the LIVE session's version+capabilities, grant-replay
 * mechanics (fresh resolve, entry-drop, inertness for offline/registered), the disarm
 * drain, gate-off inertness, and per-player throw containment. Built on the same
 * Wiring rig as {@link PaperRequestProcessingServiceTest}.
 */
class PaperServiceGateSweepTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private Map<UUID, PaperPlayerRequestState> players;
    private PaperChunkDiskReader diskReader;
    private MinecraftServer server;
    private PlayerList playerList;
    private PaperConfig config;
    private PaperRequestProcessingService service;

    private final List<String> probeReads = new ArrayList<>();
    private final Set<UUID> denied = ConcurrentHashMap.newKeySet();
    private final Set<UUID> throwing = ConcurrentHashMap.newKeySet();
    private final List<Object[]> sentConfigs = new ArrayList<>(); // {uuid, enabled}
    private final List<ServiceGateState.DeniedHandshake> replays = new ArrayList<>();

    @BeforeEach
    void buildRig() {
        config = new PaperConfig();
        config.validate();
        players = new ConcurrentHashMap<>();
        diskReader = new PaperChunkDiskReader(1, false);
        var processor = new PaperRequestProcessingServiceTest.RecordingProcessor(players, diskReader);
        var genService = new PaperRequestProcessingServiceTest.RecordingGenService(config);
        server = mock(MinecraftServer.class);
        playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        var tracker = new DirtyColumnTracker();
        var broadcaster = new PaperRequestProcessingServiceTest.RecordingBroadcaster(
                server, players, tracker, processor);
        service = new PaperRequestProcessingService(server, config,
                new PaperRequestProcessingService.Wiring(
                        players, diskReader, genService, processor, tracker, broadcaster));
        service.setLoadedColumnProbe((level, cx, cz) -> null);
        service.setPermissionProbeForTest((p, node) -> {
            probeReads.add(node);
            if (throwing.contains(p.getUUID())) throw new IllegalStateException("backend exploded");
            return !denied.contains(p.getUUID());
        });
        service.setSessionConfigSender((p, cfg, enabled) ->
                sentConfigs.add(new Object[]{p.getUUID(), enabled}));
        service.setHandshakeReplayer((p, remembered) -> replays.add(remembered));
    }

    @AfterEach
    void teardownReader() {
        diskReader.shutdown();
    }

    private static ServerPlayer playerIn(UUID uuid) {
        var level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, Level.OVERWORLD.location()));
        var p = mock(ServerPlayer.class);
        when(p.getUUID()).thenReturn(uuid);
        when(p.level()).thenReturn(level);
        when(p.chunkPosition()).thenReturn(new ChunkPos(0, 0));
        when(p.getName()).thenReturn(Component.literal("p-" + uuid.toString().substring(0, 8)));
        return p;
    }

    private ServerPlayer registerCurrent(UUID uuid, int caps) {
        var p = playerIn(uuid);
        service.getDialectTracker().onHandshake(uuid, HandshakeGate.WireDialect.CURRENT);
        service.registerPlayer(p, caps);
        return p;
    }

    // ---- revocation ----

    @Test
    void revocationNeedsTwoConsecutiveFailingSweepsThenPushesDisarmAndUnregisters() {
        config.requireServicePermission = true;
        var uuid = UUID.randomUUID();
        var p = registerCurrent(uuid, 5);
        // The revoked player stays ONLINE: the same sweep pass's grant leg re-resolves
        // it and must find the probe still denying (entry retained), not a null player
        // (entry dropped as offline).
        when(playerList.getPlayer(uuid)).thenReturn(p);
        service.getFarPlayerService().subscribeViewer(uuid);
        denied.add(uuid);

        service.runServiceGateSweeps();
        assertNotNull(players.get(uuid), "ONE failing sweep never revokes (flap hysteresis)");
        assertEquals(List.of(), sentConfigs);

        service.runServiceGateSweeps();
        assertNull(players.get(uuid), "the second consecutive failure revokes");
        assertEquals(1, sentConfigs.size(), "exactly one enabled=false push");
        assertEquals(uuid, sentConfigs.get(0)[0]);
        assertEquals(false, sentConfigs.get(0)[1], "the push advertises DISABLED at the player");
        assertEquals(0, service.getFarPlayerService().subscriberCount(),
                "the composite sheds the far-player viewer — a revoked player must not "
                        + "keep receiving proxy frames");
        assertTrue(service.getServiceGateState().isDenied(uuid),
                "the revocation deposits the memo — revoke-then-regrant must heal");
        var remembered = service.getServiceGateState().peekDenied(uuid);
        assertEquals(LSSConstants.PROTOCOL_VERSION, remembered.protocolVersion(),
                "the deposit carries the LIVE session's version (a registered session is "
                        + "CURRENT-dialect by the sweep's own filter)");
        assertEquals(5, remembered.capabilities(), "…and its live capabilities (§8 F2-M1)");
        assertEquals(1, service.getServiceGateState().permissionDeniedTotal(),
                "one revocation = one counted transition");
        assertEquals(HandshakeGate.WireDialect.CURRENT, service.getDialectTracker().dialectOf(uuid),
                "the dialect mark is a connection-lifecycle fact and survives the composite");
    }

    @Test
    void aFlappingGrantNeverRevokes() {
        config.requireServicePermission = true;
        var uuid = UUID.randomUUID();
        registerCurrent(uuid, 1);

        denied.add(uuid);
        service.runServiceGateSweeps();
        denied.remove(uuid);
        service.runServiceGateSweeps(); // passing sweep resets the streak
        denied.add(uuid);
        service.runServiceGateSweeps();
        assertNotNull(players.get(uuid),
                "hysteresis is CONSECUTIVE failures — an oscillating context-scoped grant "
                        + "must never revoke");
        assertEquals(0, service.getServiceGateState().permissionDeniedTotal());
    }

    @Test
    void legacySessionsAreNeverLiveRevoked() {
        config.requireServicePermission = true;
        var uuid = UUID.randomUUID();
        var p = playerIn(uuid);
        service.getDialectTracker().onHandshake(uuid, HandshakeGate.WireDialect.V16);
        service.registerPlayer(p, 1);
        denied.add(uuid);

        service.runServiceGateSweeps();
        service.runServiceGateSweeps();
        assertNotNull(players.get(uuid),
                "legacy (v16/v18/v19) sessions keep serving until rejoin — their "
                        + "mid-session-config behavior is release-frozen (the recorded "
                        + "repushSessionConfig doctrine); the handshake gate denies them "
                        + "in their own dialect at rejoin");
        assertEquals(List.of(), sentConfigs, "no CURRENT-shape push at a legacy client, ever");
    }

    @Test
    void stillDeniedIsIdempotentZeroWorkZeroCounts() {
        config.requireServicePermission = true;
        var uuid = UUID.randomUUID();
        registerCurrent(uuid, 1);
        denied.add(uuid);
        var online = playerIn(uuid);
        when(playerList.getPlayer(uuid)).thenReturn(online); // online, unregistered after revoke

        service.runServiceGateSweeps();
        service.runServiceGateSweeps(); // revokes
        assertEquals(1, sentConfigs.size());

        service.runServiceGateSweeps();
        service.runServiceGateSweeps();
        assertEquals(1, sentConfigs.size(), "a still-denied player costs no further pushes");
        assertEquals(1, service.getServiceGateState().permissionDeniedTotal(), "…and no re-counts");
        assertEquals(List.of(), replays, "…and no replays while the gate still denies");
        assertTrue(service.getServiceGateState().isDenied(uuid), "the memo entry stays");
    }

    @Test
    void perPlayerThrowIsContainedAndCountsAsHolding() {
        config.requireServicePermission = true;
        var throwingUuid = UUID.randomUUID();
        var deniedUuid = UUID.randomUUID();
        registerCurrent(throwingUuid, 1);
        registerCurrent(deniedUuid, 1);
        throwing.add(throwingUuid);
        denied.add(deniedUuid);

        service.runServiceGateSweeps();
        service.runServiceGateSweeps();
        assertNotNull(players.get(throwingUuid),
                "a throwing backend counts as HOLDING (fail-open) — never a revocation");
        assertNull(players.get(deniedUuid),
                "…and the throw must not stop the sweep: the denied player still revokes");
    }

    // ---- grant / re-offer ----

    @Test
    void aGrantReplaysTheRememberedHandshakeThroughTheProductionBody() {
        config.requireServicePermission = true;
        var uuid = UUID.randomUUID();
        // A v16 client denied at handshake: the memo carries ITS dialect verbatim.
        service.getServiceGateState().rememberDenied(uuid, "steve", 16, 1);
        var online = playerIn(uuid);
        when(playerList.getPlayer(uuid)).thenReturn(online);
        denied.add(uuid);

        service.runServiceGateSweeps();
        assertEquals(List.of(), replays, "still denied: the entry waits");

        denied.remove(uuid);
        service.runServiceGateSweeps();
        assertEquals(1, replays.size(), "the grant replays the stored handshake");
        assertEquals(new ServiceGateState.DeniedHandshake(16, 1, "steve"), replays.get(0),
                "the replay is the ORIGINAL dialect + capabilities — the production ladder "
                        + "re-runs and replies in the client's own dialect (legacy heals too)");
        assertFalse(service.getServiceGateState().isDenied(uuid),
                "the entry is dropped at replay — a non-register terminal must not loop "
                        + "0.1 Hz duplicate configs");

        service.runServiceGateSweeps();
        assertEquals(1, replays.size(), "no re-replay after the drop");
    }

    @Test
    void grantSweepWaitsWhileStillDeniedThenHealsOnGrant() {
        config.requireServicePermission = true;
        var uuid = UUID.randomUUID();
        service.getServiceGateState().rememberDenied(uuid, "steve", 20, 5);
        var online = playerIn(uuid);
        when(playerList.getPlayer(uuid)).thenReturn(online);
        denied.add(uuid);

        service.runServiceGateSweeps();
        assertEquals(List.of(), replays);
        assertTrue(service.getServiceGateState().isDenied(uuid), "still denied: entry retained");

        denied.remove(uuid);
        service.runServiceGateSweeps();
        assertEquals(1, replays.size(), "the regrant heals within one sweep");
    }

    @Test
    void grantReplayIsInertForOfflineAndRegisteredPlayers() {
        config.requireServicePermission = true;
        var offline = UUID.randomUUID();
        service.getServiceGateState().rememberDenied(offline, "gone", 20, 1);
        when(playerList.getPlayer(offline)).thenReturn(null);

        var registered = UUID.randomUUID();
        registerCurrent(registered, 1);
        // A denied re-handshake racing its own queued unregister composite (Folia):
        // the memo deposit landed while the player is still registered.
        service.getServiceGateState().rememberDenied(registered, "back", 20, 1);

        service.runServiceGateSweeps();
        assertEquals(List.of(), replays, "neither an offline nor a registered uuid replays");
        assertFalse(service.getServiceGateState().isDenied(offline),
                "the offline entry drops — the memo is session-scoped");
        assertTrue(service.getServiceGateState().isDenied(registered),
                "a registered uuid's entry is SKIPPED, never cleared (implementation "
                        + "review 2026-08-27): on Folia the deposit can precede the queued "
                        + "unregister composite, and clearing here would strand the player "
                        + "past its own revocation");
        assertNotNull(players.get(registered), "…and the registration is untouched");
    }

    @Test
    void disarmingTheGateDrainsTheMemo() {
        // The staged rollout's final step: `set requireServicePermission false` must
        // re-offer every denied player, not strand them (§8 unanimous MAJOR).
        config.requireServicePermission = false;
        var uuid = UUID.randomUUID();
        service.getServiceGateState().rememberDenied(uuid, "steve", 20, 5);
        var online = playerIn(uuid);
        when(playerList.getPlayer(uuid)).thenReturn(online);
        denied.add(uuid); // even an (unread) negative grant cannot hold the drain back

        service.runServiceGateSweeps();
        assertEquals(1, replays.size(), "a disarmed gate trivially clears everyone");
        assertEquals(List.of(), probeReads, "…without consulting the permission backend");
    }

    @Test
    void gateOffAndEmptyMemoIsCompletelyInert() {
        var uuid = UUID.randomUUID();
        registerCurrent(uuid, 1);
        denied.add(uuid);

        service.runServiceGateSweeps();
        assertEquals(List.of(), probeReads, "gate off: no permission read may happen");
        assertEquals(List.of(), sentConfigs);
        assertEquals(List.of(), replays);
        assertNotNull(players.get(uuid));
    }

    @Test
    void aNullReplayerRetainsTheMemoInsteadOfDrainingIt() {
        // Implementation review MAJOR: takeDenied ran BEFORE the null-replayer check,
        // so a broken replay wiring silently drained the memo — a re-granted player
        // would be stranded with nothing left to re-offer.
        config.requireServicePermission = false;
        service.setHandshakeReplayer(null);
        var uuid = UUID.randomUUID();
        service.getServiceGateState().rememberDenied(uuid, "steve", 20, 5);
        var online = playerIn(uuid);
        when(playerList.getPlayer(uuid)).thenReturn(online);

        service.runServiceGateSweeps();

        assertTrue(service.getServiceGateState().isDenied(uuid),
                "no replayer: the entry must be RETAINED, never drained");
    }

    @Test
    void aDimensionChangeDoesNotResetTheRevocationStreak() {
        // Implementation review: registerPlayer is also the dimension-change reuse
        // path — a streak reset there would let a frequently-portalling player outrun
        // the two-sweep hysteresis forever.
        config.requireServicePermission = true;
        var uuid = UUID.randomUUID();
        var p = registerCurrent(uuid, 1);
        when(playerList.getPlayer(uuid)).thenReturn(p);
        denied.add(uuid);

        service.runServiceGateSweeps();          // streak = 1
        service.removePlayer(uuid);              // the dimension-change cycle…
        service.registerPlayer(p, 1);            // …re-registers the same player
        service.runServiceGateSweeps();          // second consecutive failing sweep

        assertNull(players.get(uuid),
                "the hysteresis counts CONSECUTIVE failing sweeps across dimension "
                        + "changes — portal-hopping must not dodge revocation");
    }

    @Test
    void disarmingClearsStreaksSoReArmRestartsTheHysteresis() {
        config.requireServicePermission = true;
        var uuid = UUID.randomUUID();
        var p = registerCurrent(uuid, 1);
        when(playerList.getPlayer(uuid)).thenReturn(p);
        denied.add(uuid);

        service.runServiceGateSweeps();          // streak = 1
        config.requireServicePermission = false;
        service.runServiceGateSweeps();          // disarmed sweep clears streaks
        config.requireServicePermission = true;
        service.runServiceGateSweeps();          // re-armed: this is failure ONE again

        assertNotNull(players.get(uuid),
                "a disarm/re-arm cycle restarts the two-sweep hysteresis — a stale "
                        + "streak must not turn the first post-re-arm sweep into a revocation");
    }

    // ---- cadence + ordering ----

    @Test
    void theTickCadenceReadsOnlyEveryNthTick() {
        config.requireServicePermission = true;
        var uuid = UUID.randomUUID();
        registerCurrent(uuid, 1);

        for (int i = 0; i < PaperRequestProcessingService.PERMISSION_RECHECK_TICKS - 1; i++) {
            service.tick();
        }
        assertEquals(List.of(), probeReads,
                "199 ticks: not one permission read — the sweep is cadenced, not per-tick");
        service.tick();
        assertFalse(probeReads.isEmpty(), "the 200th tick runs the sweep");
    }

    @Test
    void theSweepIsOrderedAfterTheLifecycleDrain() throws IOException {
        // §8 O2-m2: a registered-but-flip-pending player must have its dialect applied
        // before the CURRENT-only revocation enumerates — same ordering MAJOR as the
        // set re-push. Source pin, same style as the wiring contract tests.
        Path src = Path.of("src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java");
        if (!Files.exists(src)) src = Path.of("paper").resolve(src);
        String tick = Files.readString(src);
        int drain = tick.indexOf("drainLifecycleMailbox();");
        int sweep = tick.indexOf("runServiceGateSweeps();");
        assertTrue(drain > 0 && sweep > drain,
                "tick() must drain the lifecycle mailbox BEFORE the permission sweeps");
    }
}
