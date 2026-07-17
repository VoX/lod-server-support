package dev.vox.lss.networking.server;

import dev.vox.lss.common.HandshakeGate;
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
import net.minecraft.server.level.ServerPlayer;

public class LSSServerNetworking {
    private static volatile RequestProcessingService requestService;

    public static RequestProcessingService getRequestService() {
        return requestService;
    }

    /**
     * Test seam (D9): atomically swaps the live service reference and returns the previous
     * one, so a gametest can point the static call sites that hard-read
     * {@link #getRequestService()} (the /lsslod commands, the soak metrics exporter) at a
     * service with known state for one synchronous assertion window, then restore it.
     * Refused outside gametest/soak JVMs; production code never calls this.
     */
    public static RequestProcessingService swapServiceForTesting(RequestProcessingService replacement) {
        if (!Boolean.getBoolean("lss.test.integratedServer") && !isSoakJvm()) {
            throw new IllegalStateException(
                    "swapServiceForTesting is only available in gametest/soak JVMs");
        }
        var previous = requestService;
        requestService = replacement;
        return previous;
    }

    private static boolean isSoakJvm() {
        // Blank counts as unset: the soakServer run config always defines the property,
        // as the empty string when no scenario is staged (BenchmarkBridge convention).
        String scenario = System.getProperty("lss.soak.scenario");
        return scenario != null && !scenario.isBlank();
    }

    public static synchronized void startServiceForLan(MinecraftServer server) {
        if (requestService != null) return;
        LSSLogger.info("Starting LSS LOD request processing service (LAN server)");
        requestService = new RequestProcessingService(server);
        LSSClientNetworking.triggerHostHandshake();
    }

    /** Reply hook for {@link #handleHandshake}; production wires {@code ServerPlayNetworking.send}. */
    @FunctionalInterface
    public interface SessionConfigResponder {
        void send(SessionConfigS2CPayload reply);
    }

    /**
     * The handshake receiver body, extracted so gametests can drive crafted frames through
     * the real call-site policy — gate evaluation, reply field wiring, registration — against
     * an explicit service and a recording responder (a caps=0 frame must reply without
     * registering, a foreign-version frame must produce zero reply frames). Production
     * behavior is unchanged: the registered receiver calls this with the live service and a
     * real network sender.
     */
    public static void handleHandshake(HandshakeC2SPayload payload, ServerPlayer player,
                                       RequestProcessingService service,
                                       SessionConfigResponder responder) {
        LSSLogger.info("LSS handshake received from " + player.getName().getString()
                + " (protocol v" + payload.protocolVersion()
                + ", capabilities=" + payload.capabilities() + ")");

        var config = LSSServerConfig.CONFIG;
        var decision = HandshakeGate.evaluate(payload.protocolVersion(),
                payload.capabilities(), config.enabled, service != null,
                config.enableV16Compat);

        if (!decision.sendSessionConfig()) {
            // See HandshakeGate.Outcome.VERSION_MISMATCH: replying would kick the player.
            LSSLogger.warn("Player " + player.getName().getString()
                    + " has incompatible LSS protocol version " + payload.protocolVersion()
                    + " (server: " + LSSConstants.PROTOCOL_VERSION + "), skipping LOD distribution");
            return;
        }

        boolean v16 = decision.dialect() == HandshakeGate.WireDialect.V16;
        responder.send(v16
                ? SessionConfigS2CPayload.v16Legacy(
                        decision.effectiveEnabled(),
                        config.lodDistanceChunks,
                        // The caps ARE the old client's pacing — advertise the server's real
                        // admission values (see the v16 compat design §4.1).
                        LSSConstants.SYNC_ON_LOAD_SLOT_CAP,
                        config.generationConcurrencyLimitPerPlayer,
                        config.enableChunkGeneration)
                : new SessionConfigS2CPayload(
                        LSSConstants.PROTOCOL_VERSION,
                        decision.effectiveEnabled(),
                        config.lodDistanceChunks,
                        config.enableChunkGeneration));

        if (decision.outcome() == HandshakeGate.Outcome.NO_CONSUMER) {
            // Visible to admins via this log.
            LSSLogger.info("Player " + player.getName().getString()
                    + " has no LOD consumer (caps=" + payload.capabilities()
                    + "), skipping LOD registration");
            return;
        }

        if (decision.registerPlayer()) {
            if (v16) {
                // Session identity first, so drip batches merge from the first frame.
                service.getV16CompatManager().onHandshake(player.getUUID());
            }
            service.registerPlayer(player, payload.capabilities());
            LSSLogger.info("Player " + player.getName().getString()
                    + " registered for LSS LOD request processing (caps="
                    + payload.capabilities() + (v16 ? ", v16-compat" : "") + ")");
        }
    }

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(
                HandshakeC2SPayload.TYPE,
                (payload, context) -> handleHandshake(payload, context.player(), requestService,
                        reply -> ServerPlayNetworking.send(context.player(), reply))
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
                // Network-level: the disconnect drops the v16 session identity (removePlayer
                // above only reset its want-set — dim changes reuse that path).
                service.getV16CompatManager().onDisconnect(handler.getPlayer().getUUID());
            }
        });
    }
}
