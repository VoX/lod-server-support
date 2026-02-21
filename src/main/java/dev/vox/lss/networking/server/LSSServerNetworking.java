package dev.vox.lss.networking.server;

import dev.vox.lss.LSSLogger;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.LSSNetworking;
import dev.vox.lss.networking.payloads.CancelRequestC2SPayload;
import dev.vox.lss.networking.payloads.ChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class LSSServerNetworking {
    private static volatile RequestProcessingService requestService;

    public static RequestProcessingService getRequestService() {
        return requestService;
    }

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(
                HandshakeC2SPayload.TYPE,
                (payload, context) -> {
                    var player = context.player();
                    LSSLogger.info("LSS handshake received from " + player.getName().getString()
                            + " (protocol v" + payload.protocolVersion() + ")");

                    var config = LSSServerConfig.CONFIG;
                    var service = requestService;
                    boolean effectiveEnabled = config.enabled && service != null;

                    ServerPlayNetworking.send(player, new SessionConfigS2CPayload(
                            LSSNetworking.PROTOCOL_VERSION,
                            effectiveEnabled,
                            config.lodDistanceChunks,
                            config.maxRequestsPerBatch,
                            config.maxPendingRequestsPerPlayer,
                            config.enableChunkGeneration ? config.maxConcurrentGenerationsPerPlayer : 0,
                            config.generationDistanceChunks
                    ));

                    if (payload.protocolVersion() != LSSNetworking.PROTOCOL_VERSION) {
                        LSSLogger.warn("Player " + player.getName().getString()
                                + " has incompatible LSS protocol version " + payload.protocolVersion()
                                + " (server: " + LSSNetworking.PROTOCOL_VERSION + "), skipping LOD distribution");
                        return;
                    }

                    if (effectiveEnabled) {
                        service.registerPlayer(player, payload.protocolVersion());
                        LSSLogger.info("Player " + player.getName().getString()
                                + " registered for LSS LOD request processing");
                    }
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                ChunkRequestC2SPayload.TYPE,
                (payload, context) -> {
                    var service = requestService;
                    if (service != null) {
                        service.handleRequestBatch(context.player(), payload);
                    }
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                CancelRequestC2SPayload.TYPE,
                (payload, context) -> {
                    var service = requestService;
                    if (service != null) {
                        service.handleCancelRequest(context.player(), payload);
                    }
                }
        );

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!server.isDedicatedServer() && !Boolean.getBoolean("lss.test.integratedServer")) {
                LSSLogger.info("LSS LOD request processing skipped for integrated server");
                return;
            }
            LSSLogger.info("Starting LSS LOD request processing service");
            requestService = new RequestProcessingService(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var service = requestService;
            if (service != null) {
                LSSLogger.info("Stopping LSS LOD request processing service");
                service.shutdown();
                requestService = null;
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var service = requestService;
            if (service != null) {
                service.tick();
            }
        });

        LSSServerCommands.init();

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var service = requestService;
            if (service != null) {
                service.removePlayer(handler.getPlayer().getUUID());
            }
        });
    }
}
