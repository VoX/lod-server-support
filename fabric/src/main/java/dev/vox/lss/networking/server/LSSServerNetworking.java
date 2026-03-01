package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.CancelRequestC2SPayload;
import dev.vox.lss.networking.payloads.ChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;

public class LSSServerNetworking {
    private static volatile RequestProcessingService requestService;

    public static RequestProcessingService getRequestService() {
        return requestService;
    }

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(
                HandshakeC2SPayload.ID,
                (server, player, handler, buf, responseSender) -> {
                    HandshakeC2SPayload payload = HandshakeC2SPayload.read(buf);
                    
                    server.execute(() -> {
                        LSSLogger.info("LSS handshake received from " + player.getName().getString()
                                + " (protocol v" + payload.protocolVersion() + ")");

                        var config = LSSServerConfig.CONFIG;
                        var service = requestService;
                        boolean effectiveEnabled = config.enabled && service != null;

                        SessionConfigS2CPayload configPayload = new SessionConfigS2CPayload(
                                LSSConstants.PROTOCOL_VERSION,
                                effectiveEnabled,
                                config.lodDistanceChunks,
                                config.maxRequestsPerBatch,
                                config.maxPendingRequestsPerPlayer,
                                config.enableChunkGeneration ? config.maxConcurrentGenerationsPerPlayer : 0,
                                config.generationDistanceChunks
                        );

                        FriendlyByteBuf outBuf = PacketByteBufs.create();
                        configPayload.write(outBuf);
                        ServerPlayNetworking.send(player, SessionConfigS2CPayload.ID, outBuf);

                        if (payload.protocolVersion() != LSSConstants.PROTOCOL_VERSION) {
                            LSSLogger.warn("Player " + player.getName().getString()
                                    + " has incompatible LSS protocol version " + payload.protocolVersion()
                                    + " (server: " + LSSConstants.PROTOCOL_VERSION + "), skipping LOD distribution");
                            return;
                        }

                        if (effectiveEnabled) {
                            service.registerPlayer(player);
                            LSSLogger.info("Player " + player.getName().getString()
                                    + " registered for LSS LOD request processing");
                        }
                    });
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                ChunkRequestC2SPayload.ID,
                (server, player, handler, buf, responseSender) -> {
                    ChunkRequestC2SPayload payload = ChunkRequestC2SPayload.read(buf);
                    server.execute(() -> {
                        var service = requestService;
                        if (service != null) {
                            service.handleRequestBatch(player, payload);
                        }
                    });
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                CancelRequestC2SPayload.ID,
                (server, player, handler, buf, responseSender) -> {
                    CancelRequestC2SPayload payload = CancelRequestC2SPayload.read(buf);
                    server.execute(() -> {
                        var service = requestService;
                        if (service != null) {
                            service.handleCancelRequest(player, payload);
                        }
                    });
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