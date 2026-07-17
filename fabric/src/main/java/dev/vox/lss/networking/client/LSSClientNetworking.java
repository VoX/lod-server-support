package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.api.LSSApi;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

public class LSSClientNetworking {
    private static final ClientColumnProcessor columnProcessor = new ClientColumnProcessor();

    // Session state and the JOIN / SessionConfig / DISCONNECT ladders live in the gate
    // (unit-testable); this class wires the production seams — the real handshake send
    // and manager construction with live server-address resolution.
    private static final ClientSessionGate sessionGate = new ClientSessionGate(
            columnProcessor,
            () -> ClientPlayNetworking.send(new HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, LSSConstants.CAPABILITY_VOXEL_COLUMNS)),
            LSSClientNetworking::createRequestManager);

    public static boolean isServerEnabled() {
        return sessionGate.isServerEnabled();
    }

    public static boolean hasReceivedSessionConfig() {
        return sessionGate.hasReceivedSessionConfig();
    }

    public static int getServerLodDistance() {
        return sessionGate.getServerLodDistance();
    }

    public static long getColumnsReceived() {
        return sessionGate.getColumnsReceived();
    }

    public static long getBytesReceived() {
        return sessionGate.getBytesReceived();
    }

    public static long getColumnsDropped() {
        return columnProcessor.getColumnsDropped();
    }

    public static long getConnectionStartMs() {
        return sessionGate.getConnectionStartMs();
    }

    public static LodRequestManager getRequestManager() {
        return sessionGate.getRequestManager();
    }

    public static int getQueuedColumnCount() {
        return columnProcessor.getQueuedCount();
    }

    /**
     * Report a delivered-but-not-ingested column (decode failure or consumer rejection
     * via {@link LSSApi#reportIngestFailure}). Hops to the main thread, where the manager
     * forgets the received-stamp and schedules a re-request. Safe from any thread.
     */
    public static void reportIngestFailure(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        var mc = Minecraft.getInstance();
        if (mc == null) return; // unit tests / very early startup — no session to repair anyway
        mc.execute(() -> {
            var manager = sessionGate.getRequestManager();
            if (manager != null) {
                manager.onIngestFailure(dimension, PositionUtil.packPosition(chunkX, chunkZ));
            }
        });
    }

    /**
     * Drain the decode queue and unstamp every undispatched column via the manager —
     * called before any cache persistence (disconnect, dimension change) so stamps for
     * never-ingested data cannot outlive the session state that recorded them.
     */
    static void reportUndispatchedColumns(LodRequestManager manager) {
        columnProcessor.reportUndispatched(manager);
    }

    public static void triggerHostHandshake() {
        Minecraft.getInstance().execute(() -> {
            if (!LSSClientConfig.CONFIG.receiveServerLods) return;
            if (sessionGate.getRequestManager() != null) return;
            if (!LSSApi.hasVoxelConsumers()) return; // no LOD consumer -> stay silent
            try {
                ClientPlayNetworking.send(new HandshakeC2SPayload(
                        LSSConstants.PROTOCOL_VERSION, LSSConstants.CAPABILITY_VOXEL_COLUMNS));
            } catch (Exception e) {
                LSSLogger.debug("LAN host handshake send failed: " + e.getMessage());
            }
        });
    }

    public static void init() {
        registerPacketHandlers();
        registerConnectionLifecycle();
        registerTickHandler();
    }

    /**
     * Production {@link ClientSessionGate.ManagerFactory}: builds the per-session manager
     * and resolves the cache-keying server address from the live client (multiplayer ip →
     * LAN/local world dir → unknown).
     */
    private static LodRequestManager createRequestManager(SessionConfigS2CPayload payload) {
        var manager = new LodRequestManager();
        var mc = Minecraft.getInstance();
        String serverAddr;
        var serverData = mc.getCurrentServer();
        var spServer = mc.getSingleplayerServer();
        if (serverData != null && serverData.ip != null) {
            serverAddr = serverData.ip;
        } else if (spServer != null) {
            var worldDir = spServer.getWorldPath(LevelResource.ROOT).getFileName();
            serverAddr = "local:" + (worldDir != null ? worldDir : "world");
        } else {
            serverAddr = "unknown";
        }
        manager.onSessionConfig(payload, serverAddr);
        return manager;
    }

    private static void registerPacketHandlers() {
        ClientPlayNetworking.registerGlobalReceiver(
                SessionConfigS2CPayload.TYPE,
                (payload, context) -> context.client().execute(
                        () -> sessionGate.onSessionConfig(payload, LSSApi.hasVoxelConsumers()))
        );

        ClientPlayNetworking.registerGlobalReceiver(
                BatchResponseS2CPayload.TYPE,
                (payload, context) -> {
                    context.client().execute(() -> {
                        var manager = sessionGate.getRequestManager();
                        if (manager == null) return;
                        dispatchBatchResponses(manager, payload);
                    });
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                DirtyColumnsS2CPayload.TYPE,
                (payload, context) -> {
                    context.client().execute(() -> {
                        var manager = sessionGate.getRequestManager();
                        if (manager != null) {
                            manager.onDirtyColumns(payload.dirtyPositions());
                        }
                    });
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                VoxelColumnS2CPayload.TYPE,
                (payload, context) -> {
                    sessionGate.recordColumnFrame(payload.estimatedBytes());

                    context.client().execute(() ->
                            handleVoxelColumn(sessionGate.getRequestManager(), columnProcessor, payload));
                }
        );
    }

    /**
     * VoxelColumn receive glue (main client thread). Ordering is load-bearing: "did the
     * client already hold data here" must be captured BEFORE {@code onColumnReceived}
     * stamps the position — a resync must air-fill absent sections to clear ghost terrain.
     * A 0-section column is an authoritative content→air clear REGARDLESS of the held
     * check: the server only sends it to data-claiming clients, so heldContentBefore==false
     * here means the stamp was dropped moments earlier (an ingest-failure report racing the
     * delivery). Treating that as a plain first serve would dispatch zero sections with no
     * air-fill yet stamp ts&gt;0 — a validated hole that up_to_date pins for the session.
     * Package-private so tests can pin the ladder without a network receiver.
     */
    static void handleVoxelColumn(LodRequestManager manager, ClientColumnProcessor processor,
                                  VoxelColumnS2CPayload payload) {
        long packed = PositionUtil.packPosition(payload.chunkX(), payload.chunkZ());
        boolean resync = manager != null && manager.heldContentBefore(packed);
        boolean clear = ClientColumnProcessor.isClearColumn(payload.decompressedSections());
        if (manager != null) {
            manager.onColumnReceived(packed, payload.columnTimestamp(),
                    payload.dimension(), clear, payload.source());
        }
        // A clear air-fills even when the held check missed (see above) — the consumer must
        // overwrite whatever it renders there with air.
        processor.offer(payload, resync || clear);
    }

    /**
     * Routes each batch entry to its per-type manager callback. An unknown responseType
     * skips that entry only, never the rest of the batch (forward compat with newer
     * servers). That same skip covers the RETIRED byte 0 (v16's rate-limited bounce): a
     * pre-v17 server never gets this far (the handshake gate rejects the version mismatch),
     * but the inert skip is what makes byte 0 safe to leave reserved forever.
     * Package-private so tests can exercise it without a network receiver.
     */
    static void dispatchBatchResponses(LodRequestManager manager, BatchResponseS2CPayload payload) {
        for (int i = 0; i < payload.count(); i++) {
            long packed = payload.packedPositions()[i];
            byte type = payload.responseTypes()[i];
            switch (type) {
                case LSSConstants.RESPONSE_UP_TO_DATE -> manager.onColumnUpToDate(packed);
                case LSSConstants.RESPONSE_NOT_GENERATED -> manager.onColumnNotGenerated(packed);
                default -> LSSLogger.warn("Unknown batch response type: " + type);
            }
        }
    }

    private static void registerConnectionLifecycle() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Don't activate on singleplayer/integrated servers (unless testing)
            boolean localIntegratedServer = Minecraft.getInstance().hasSingleplayerServer()
                    && !Boolean.getBoolean("lss.test.integratedServer");
            sessionGate.onJoin(LSSClientConfig.CONFIG.receiveServerLods, localIntegratedServer,
                    LSSApi.hasVoxelConsumers());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> sessionGate.onDisconnect());
    }

    private static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var manager = sessionGate.getRequestManager();
            if (manager != null && sessionGate.isServerEnabled()) {
                manager.tick();
            }
            columnProcessor.scheduleProcessing(sessionGate.isServerEnabled());
        });
    }
}
