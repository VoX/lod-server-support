package dev.vox.lss.networking.server;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.client.LSSClientNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Fabric server-side networking glue: the static service holder, event/receiver
 * registration, and LAN startup. Since N-2 the receiver BODIES live in the
 * loader-neutral {@link ServerReceiverGlue} (xplat — shared verbatim with the
 * NeoForge module); the delegating statics here keep every pre-extraction
 * caller and test signature intact.
 */
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

    public static void startServiceForLan(MinecraftServer server) {
        // The LAN publish hook fires on the RENDER thread (the share/options screen calls
        // publishServer directly), and construction is heavy: it starts the processing,
        // save, and disk-reader threads and does a blocking ColumnTimestampCache.load().
        // Hop to the server thread — the same context the dedicated-server start uses.
        // Accepted ≤1-tick window between the LAN listener being up and the service being
        // non-null: a joining client cannot complete login inside it, and the host's own
        // handshake already hops through the client executor. From the server thread
        // itself (the /publish command path) execute() runs inline, so nothing changes
        // there.
        server.execute(() -> startServiceForLanOnServerThread(server));
    }

    private static synchronized void startServiceForLanOnServerThread(MinecraftServer server) {
        // A hop task queued in the last tick before Save-and-Quit runs AFTER the
        // SERVER_STOPPING handler nulled requestService (stopServer drains pending tasks
        // after the Fabric event fires) — starting here would leave a zombie service bound
        // to a dead server for the rest of the client JVM.
        if (!server.isRunning()) return;
        if (requestService != null) return;
        LSSLogger.info(Brand.shortName() + " LOD request processing service starting (LAN server)");
        requestService = new RequestProcessingService(server);
        ServerReceiverGlue.flushPendingLoadSeeds(server, requestService); // the pre-service spawn set
        LSSClientNetworking.triggerHostHandshake();
    }

    /** The client's announced MC data version, or null for a legacy client. */
    public static Integer clientDataVersion(java.util.UUID uuid) {
        return ServerReceiverGlue.clientDataVersion(uuid);
    }

    /** Delegating hook body — see {@link ServerReceiverGlue#onChunkSaveData}; the
     *  {@code ChunkSaveDataHook} mixin calls this with no service argument, so the
     *  static read stays here. */
    public static void onChunkSaveData(ServerLevel level, ChunkAccess chunk) {
        ServerReceiverGlue.onChunkSaveData(level, chunk, requestService);
    }

    /** Delegate kept for the pinned truth table — see {@link ServerReceiverGlue#skipDirtyHash}. */
    static boolean skipDirtyHash(boolean everRegistered, boolean storePresent,
                                 boolean timestampCacheBootedEmpty) {
        return ServerReceiverGlue.skipDirtyHash(everRegistered, storePresent,
                timestampCacheBootedEmpty);
    }

    /** Delegate kept for the store-bridge tests — see {@link ServerReceiverGlue#applySaveObservationToStore}. */
    static void applySaveObservationToStore(dev.vox.lss.common.store.LodStoreService store,
                                            String dimension, int cx, int cz,
                                            DirtyContentFilter.SaveObservation obs) {
        ServerReceiverGlue.applySaveObservationToStore(store, dimension, cx, cz, obs);
    }

    /** Reply hook alias — the shared body's interface, re-exported so existing
     *  gametest lambdas keep their type name. */
    public interface SessionConfigResponder extends ServerReceiverGlue.SessionConfigResponder {
    }

    /** Delegating crafted-frame entry — see {@link ServerReceiverGlue#handleHandshake}. */
    public static void handleHandshake(HandshakeC2SPayload payload, ServerPlayer player,
                                       RequestProcessingService service,
                                       ServerReceiverGlue.SessionConfigResponder responder) {
        ServerReceiverGlue.handleHandshake(payload, player, service, responder);
    }

    /** Delegating Via-seam entry — see {@link ServerReceiverGlue#handleHandshake}. */
    public static void handleHandshake(HandshakeC2SPayload payload, ServerPlayer player,
                                       RequestProcessingService service,
                                       ServerReceiverGlue.SessionConfigResponder responder,
                                       int viaProtocol, int nativeProtocol) {
        ServerReceiverGlue.handleHandshake(payload, player, service, responder,
                viaProtocol, nativeProtocol);
    }

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(
                HandshakeC2SPayload.TYPE,
                (payload, context) -> ServerReceiverGlue.handleHandshake(
                        payload, context.player(), requestService,
                        reply -> ServerPlayNetworking.send(context.player(), reply))
        );

        ServerPlayNetworking.registerGlobalReceiver(
                dev.vox.lss.networking.payloads.ClientInfoC2SPayload.TYPE,
                (payload, context) -> ServerReceiverGlue.recordClientInfo(
                        context.player().getUUID(), payload.dataVersion())
        );

        ServerPlayNetworking.registerGlobalReceiver(
                dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload.TYPE,
                (payload, context) -> ServerReceiverGlue.onFarPlayerPrefs(
                        requestService, context.player(), payload.body())
        );

        ServerPlayNetworking.registerGlobalReceiver(
                dev.vox.lss.networking.payloads.RegionSummaryRequestC2SPayload.TYPE,
                (payload, context) -> {
                    var service = requestService;
                    if (service != null) {
                        service.handleRegionSummaryRequest(context.player(), payload.body());
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
            dev.vox.lss.config.LSSServerConfig.beginServerLifecycle();
            if (!server.isDedicatedServer() && !Boolean.getBoolean("lss.test.integratedServer")) {
                LSSLogger.info(Brand.shortName() + " LOD request processing deferred until LAN");
                return;
            }
            LSSLogger.info("Starting " + Brand.shortName() + " LOD request processing service");
            requestService = new RequestProcessingService(server);
            ServerReceiverGlue.flushPendingLoadSeeds(server, requestService); // the pre-service spawn set
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            dev.vox.lss.config.LSSServerConfig.CONFIG.close();
            var service = requestService;
            if (service != null) {
                LSSLogger.info("Stopping " + Brand.shortName() + " LOD request processing service");
                service.shutdown();
                requestService = null;
            }
            // Sidecar facts die with the server (integrated-server world cycles would
            // otherwise accrete entries across sessions — review C1-9).
            ServerReceiverGlue.clearClientInfo();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var service = requestService;
            if (service != null) {
                service.tick();
            }
        });

        // The dirty content filter's chunk-LOAD baseline (xaero-scatter-remediation-plan.md
        // WI-1b): fired from the FULL status task — vanilla, and Moonrise/C2ME through their
        // Fabric platform hooks; a chunk system that skips it leaves the filter as before.
        // LINE FLAVOR: the fabric-api 4.x lifecycle module (26.2, 26.1) passes a third
        // `generated` flag; the 2.x module on the 1.21.x lines takes (level, chunk) —
        // surfaces row 22.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register(
                (level, chunk, generated) ->
                        ServerReceiverGlue.onChunkLoaded(level, chunk, requestService, generated));

        // The shared /lsslod tree (xplat since N-2), registered through Fabric's event.
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        LSSServerCommands.register(dispatcher));

        // Fabric fires DISCONNECT from whichever comes first: netty's channelInactive (the other
        // end closed the socket, or a kick's close went through) or the server thread's own
        // disconnect handling. Everything the cleanup touches belongs to the server thread:
        // the generation service's active map, the far-player and region-summary state, the
        // service gate. Run from a netty thread it raced the tick, and a real-stack run on
        // 2026-11-13 crashed the server there (ConcurrentModificationException in
        // ChunkGenerationService.tick, a player kicked with 40 generations in flight). So the
        // event body reads the id and hands the rest to the server thread. execute() runs it
        // there: at once when called on that thread outside a task, queued otherwise. It has
        // run before the same player can be back, because vanilla holds a second login of a
        // profile until the first is out of the player list. Once the server has stopped,
        // execute() runs a task on the thread that called it: see onDisconnect.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            java.util.UUID uuid = handler.getPlayer().getUUID();
            server.execute(() -> onDisconnect(server, uuid));
        });
    }

    /**
     * The connection of {@code uuid} ended (see the DISCONNECT registration). On the server
     * thread, with one exception: a stopped server runs the task on the calling network
     * thread. The service is then shutting down, or shut down, on the server thread and drops
     * every player's state itself, so only the thread-safe sweep runs here.
     */
    private static void onDisconnect(net.minecraft.server.MinecraftServer server, java.util.UUID uuid) {
        var service = server.isSameThread() ? requestService : null;
        if (service != null) {
            service.removePlayer(uuid);
            // Network-level: the disconnect drops the compat session identities
            // (removePlayer above only reset the v16 want-set and touches neither
            // membership — dim changes reuse that path and must keep both).
            service.getV16CompatManager().onDisconnect(uuid);
            service.getDialectTracker().onDisconnect(uuid);
            // Far players: the subscription AND the retained target prefs die with
            // the CONNECTION, never with the dimension-change remove+register cycle
            // (the v18-rung checklist).
            service.getFarPlayerService().onDisconnect(uuid);
            // Region summaries: same connection-scoped cleanup (pending request,
            // queued job, and the re-sweep cooldown mark die here).
            service.getRegionSummaries().removePlayer(uuid);
            // Service gate: the denied-handshake memo, the denial-log latch, and any
            // revocation streak are session-scoped — swept beside the client-info fact.
            service.getServiceGateState().onDisconnect(uuid);
        }
        // Service-independent: the sidecar fact is recorded at the network level
        // (possibly before any service exists) and must die with the connection.
        ServerReceiverGlue.sweepClientInfo(uuid);
    }
}
