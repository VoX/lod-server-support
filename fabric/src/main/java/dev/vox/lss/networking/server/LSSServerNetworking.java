package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.config.LSSServerConfig;
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

                    if (payload.protocolVersion() != LSSConstants.PROTOCOL_VERSION) {
                        // Must not reply: a mismatched client's SessionConfig codec has a
                        // different field layout on the same channel id, so any frame we
                        // send decodes as a DecoderException and kicks the player. Sending
                        // nothing leaves its LSS disabled (no LodRequestManager is created).
                        LSSLogger.warn("Player " + player.getName().getString()
                                + " has incompatible LSS protocol version " + payload.protocolVersion()
                                + " (server: " + LSSConstants.PROTOCOL_VERSION + "), skipping LOD distribution");
                        return;
                    }

                    var config = LSSServerConfig.CONFIG;
                    var service = requestService;
                    boolean effectiveEnabled = config.enabled && service != null;

                    ServerPlayNetworking.send(player, new SessionConfigS2CPayload(
                            LSSConstants.PROTOCOL_VERSION,
                            effectiveEnabled,
                            config.lodDistanceChunks,
                            config.syncOnLoadConcurrencyLimitPerPlayer,
                            config.generationConcurrencyLimitPerPlayer,
                            config.enableChunkGeneration
                    ));

                    if ((payload.capabilities() & LSSConstants.CAPABILITY_VOXEL_COLUMNS) == 0) {
                        // No consumer on the client — registering would only create a zombie
                        // state that ignores every request. Visible to admins via this log.
                        LSSLogger.info("Player " + player.getName().getString()
                                + " has no LOD consumer (caps=" + payload.capabilities()
                                + "), skipping LOD registration");
                        return;
                    }

                    if (effectiveEnabled) {
                        service.registerPlayer(player, payload.capabilities());
                        LSSLogger.info("Player " + player.getName().getString()
                                + " registered for LSS LOD request processing (caps="
                                + payload.capabilities() + ")");
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
