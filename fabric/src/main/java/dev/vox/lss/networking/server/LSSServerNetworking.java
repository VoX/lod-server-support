package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.vox.lss.networking.payloads.CancelRequestC2SPayload;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.client.LSSClientNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;

public class LSSServerNetworking {
    private static volatile RequestProcessingService requestService;

    public static RequestProcessingService getRequestService() {
        return requestService;
    }

    public static synchronized void startServiceForLan(MinecraftServer server) {
        if (requestService != null) return;
        LSSLogger.info("Starting LSS LOD request processing service (LAN server)");
        requestService = new RequestProcessingService(server);
        LSSClientNetworking.triggerHostHandshake();
    }

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(
                HandshakeC2SPayload.TYPE,
                (payload, context) -> {
                    var player = context.player();
                    LSSLogger.info("LSS handshake received from " + player.getName().getString()
                            + " (protocol v" + payload.protocolVersion()
                            + ", capabilities=" + payload.capabilities() + ")");

                    var config = LSSServerConfig.CONFIG;
                    var service = requestService;
                    boolean effectiveEnabled = config.enabled && service != null;

                    int serverCaps = LSSConstants.CAPABILITY_VOXEL_COLUMNS;

                    ServerPlayNetworking.send(player, new SessionConfigS2CPayload(
                            LSSConstants.PROTOCOL_VERSION,
                            effectiveEnabled,
                            config.lodDistanceChunks,
                            serverCaps,
                            config.syncOnLoadRateLimitPerPlayer,
                            config.syncOnLoadConcurrencyLimitPerPlayer,
                            config.generationRateLimitPerPlayer,
                            config.generationConcurrencyLimitPerPlayer,
                            config.enableChunkGeneration,
                            config.bytesPerSecondLimitPerPlayer
                    ));

                    if (payload.protocolVersion() != LSSConstants.PROTOCOL_VERSION) {
                        LSSLogger.warn("Player " + player.getName().getString()
                                + " has incompatible LSS protocol version " + payload.protocolVersion()
                                + " (server: " + LSSConstants.PROTOCOL_VERSION + "), skipping LOD distribution");
                        return;
                    }

                    if (effectiveEnabled) {
                        service.registerPlayer(player, payload.capabilities());
                        LSSLogger.info("Player " + player.getName().getString()
                                + " registered for LSS LOD request processing"
                                + (payload.capabilities() != 0
                                        ? " (caps=" + payload.capabilities() + ")" : ""));
                    }
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                BatchChunkRequestC2SPayload.TYPE,
                (payload, context) -> {
                    var service = requestService;
                    if (service != null) {
                        service.handleBatchRequest(context.player(), payload);
                    }
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                CancelRequestC2SPayload.TYPE,
                (payload, context) -> {
                    var service = requestService;
                    if (service != null) {
                        service.handleCancel(context.player(), payload);
                    }
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                BandwidthUpdateC2SPayload.TYPE,
                (payload, context) -> {
                    var service = requestService;
                    if (service != null) {
                        service.handleBandwidthUpdate(context.player(), payload);
                    }
                }
        );

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!server.isDedicatedServer() && !Boolean.getBoolean("lss.test.integratedServer")) {
                LSSLogger.info("LSS LOD request processing deferred until LAN");
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
