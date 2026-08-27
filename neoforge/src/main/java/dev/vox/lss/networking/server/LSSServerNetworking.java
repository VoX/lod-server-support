package dev.vox.lss.networking.server;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.ClientInfoC2SPayload;
import dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.platform.LoaderServices;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * NeoForge server-side networking glue — the fabric {@code LSSServerNetworking}
 * twin (same FQN, per-loader tree; plan §1.1's static-holder contract). The
 * receiver BODIES are the shared {@link ServerReceiverGlue} (xplat); this class
 * owns the static service holder, the NeoForge event handlers ({@code LSSNeoMod}
 * registers them), and the payload-handler adapters the registrar binds.
 *
 * <p>No LAN hook on NeoForge v1 (recorded feature gap, plan §5.4): a
 * LAN-published integrated server does not start the service.
 */
public class LSSServerNetworking {
    private static volatile RequestProcessingService requestService;

    public static RequestProcessingService getRequestService() {
        return requestService;
    }

    /** The client's announced MC data version, or null for a legacy client. */
    public static Integer clientDataVersion(java.util.UUID uuid) {
        return ServerReceiverGlue.clientDataVersion(uuid);
    }

    /** Hook body for the {@code ChunkSaveDataHook} mixin twin. */
    public static void onChunkSaveData(ServerLevel level, ChunkAccess chunk) {
        ServerReceiverGlue.onChunkSaveData(level, chunk, requestService);
    }

    // ---- NeoForge event handlers (registered by LSSNeoMod) ----

    public static void onServerStarted(ServerStartedEvent event) {
        var server = event.getServer();
        if (!server.isDedicatedServer() && !Boolean.getBoolean("lss.test.integratedServer")) {
            // The fabric line defers to the LAN hook here; NeoForge v1 has none.
            LSSLogger.info(Brand.shortName() + " LOD request processing inactive on integrated servers"
                    + " (no LAN hook on NeoForge v1)");
            return;
        }
        LSSLogger.info("Starting " + Brand.shortName() + " LOD request processing service");
        requestService = new RequestProcessingService(server);
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        var service = requestService;
        if (service != null) {
            LSSLogger.info("Stopping " + Brand.shortName() + " LOD request processing service");
            service.shutdown();
            requestService = null;
        }
        // Sidecar facts die with the server (integrated-server world cycles would
        // otherwise accrete entries across sessions — review C1-9).
        ServerReceiverGlue.clearClientInfo();
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        var service = requestService;
        if (service != null) {
            service.tick();
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        var service = requestService;
        if (service != null) {
            service.removePlayer(player.getUUID());
            // Network-level: the disconnect drops the compat session identities
            // (removePlayer above only reset the v16 want-set and touches neither
            // membership — dim changes reuse that path and must keep both).
            service.getV16CompatManager().onDisconnect(player.getUUID());
            service.getDialectTracker().onDisconnect(player.getUUID());
            // Far players: the subscription AND the retained target prefs die with
            // the CONNECTION, never with the dimension-change remove+register cycle
            // (the v18-rung checklist).
            service.getFarPlayerService().onDisconnect(player.getUUID());
            // Region summaries: same connection-scoped cleanup (pending request,
            // queued job, and the re-sweep cooldown mark die here).
            service.getRegionSummaries().removePlayer(player.getUUID());
            // Service gate: the denied-handshake memo, the denial-log latch, and any
            // revocation streak are session-scoped — swept beside the client-info fact.
            service.getServiceGateState().onDisconnect(player.getUUID());
        }
        // Service-independent: the sidecar fact is recorded at the network level
        // (possibly before any service exists) and must die with the connection.
        ServerReceiverGlue.sweepClientInfo(player.getUUID());
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LSSServerCommands.register(event.getDispatcher());
    }

    // ---- Payload-handler adapters (bound by the registrar; executesOn(MAIN)) ----

    public static void handleHandshakePayload(HandshakeC2SPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerReceiverGlue.handleHandshake(payload, player, requestService,
                reply -> LoaderServices.get().sendToPlayer(player, reply));
    }

    public static void handleBatchRequestPayload(BatchChunkRequestC2SPayload payload,
                                                 IPayloadContext context) {
        var service = requestService;
        if (service != null && context.player() instanceof ServerPlayer player) {
            service.handleBatchRequest(player, payload);
        }
    }

    public static void handleClientInfoPayload(ClientInfoC2SPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ServerReceiverGlue.recordClientInfo(player.getUUID(), payload.dataVersion());
        }
    }

    public static void handleFarPlayerPrefsPayload(FarPlayerPrefsC2SPayload payload,
                                                   IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ServerReceiverGlue.onFarPlayerPrefs(requestService, player, payload.body());
        }
    }

    public static void handleRegionSummaryRequestPayload(
            dev.vox.lss.networking.payloads.RegionSummaryRequestC2SPayload payload,
            IPayloadContext context) {
        var service = requestService;
        if (service != null && context.player() instanceof ServerPlayer player) {
            service.handleRegionSummaryRequest(player, payload.body());
        }
    }
}
