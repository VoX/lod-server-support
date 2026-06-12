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

import java.util.concurrent.atomic.AtomicLong;

public class LSSClientNetworking {
    private static volatile boolean serverEnabled = false;
    // True once a SessionConfig reply (compatible version) arrived for the current
    // connection — distinguishes "server said disabled" from "no LSS server / not yet
    // answered". Read by the dev-only soak hook to snapshot disabled sessions.
    private static volatile boolean sessionConfigReceived = false;
    private static volatile int serverLodDistance = 0;
    private static final AtomicLong columnsReceived = new AtomicLong();
    private static final AtomicLong bytesReceived = new AtomicLong();
    private static volatile long connectionStartMs = 0;
    private static volatile LodRequestManager requestManager;

    private static final ClientColumnProcessor columnProcessor = new ClientColumnProcessor();

    public static boolean isServerEnabled() {
        return serverEnabled;
    }

    public static boolean hasReceivedSessionConfig() {
        return sessionConfigReceived;
    }

    public static int getServerLodDistance() {
        return serverLodDistance;
    }

    public static long getColumnsReceived() {
        return columnsReceived.get();
    }

    public static long getBytesReceived() {
        return bytesReceived.get();
    }

    public static long getColumnsDropped() {
        return columnProcessor.getColumnsDropped();
    }

    public static long getConnectionStartMs() {
        return connectionStartMs;
    }

    public static LodRequestManager getRequestManager() {
        return requestManager;
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
            var manager = requestManager;
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
            if (requestManager != null) return;
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

    private static void registerPacketHandlers() {
        ClientPlayNetworking.registerGlobalReceiver(
                SessionConfigS2CPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    LSSLogger.info("Server session config received (protocol v" + payload.protocolVersion()
                            + ", LOD distance: " + payload.lodDistanceChunks() + " chunks"
                            + ", enabled: " + payload.enabled() + ")");

                    if (payload.protocolVersion() != LSSConstants.PROTOCOL_VERSION) {
                        LSSLogger.warn("Server has incompatible LSS protocol version " + payload.protocolVersion()
                                + " (client: " + LSSConstants.PROTOCOL_VERSION + "), LOD distribution disabled");
                        serverEnabled = false;
                        return;
                    }

                    serverEnabled = payload.enabled();
                    sessionConfigReceived = true;
                    serverLodDistance = payload.lodDistanceChunks();

                    // Without a registered LSSApi consumer there is nothing to deliver columns
                    // to — skip session setup entirely (capability is sampled at JOIN).
                    if (payload.enabled() && LSSApi.hasVoxelConsumers()) {
                        connectionStartMs = System.currentTimeMillis();
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
                        requestManager = manager;
                    }
                })
        );

        ClientPlayNetworking.registerGlobalReceiver(
                BatchResponseS2CPayload.TYPE,
                (payload, context) -> {
                    context.client().execute(() -> {
                        var manager = requestManager;
                        if (manager == null) return;
                        dispatchBatchResponses(manager, payload);
                    });
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                DirtyColumnsS2CPayload.TYPE,
                (payload, context) -> {
                    context.client().execute(() -> {
                        var manager = requestManager;
                        if (manager != null) {
                            manager.onDirtyColumns(payload.dirtyPositions());
                        }
                    });
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                VoxelColumnS2CPayload.TYPE,
                (payload, context) -> {
                    columnsReceived.incrementAndGet();
                    bytesReceived.addAndGet(payload.estimatedBytes());

                    context.client().execute(() -> {
                        var manager = requestManager;
                        if (manager != null) {
                            manager.onColumnReceived(
                                    PositionUtil.packPosition(payload.chunkX(), payload.chunkZ()),
                                    payload.columnTimestamp(),
                                    payload.dimension());
                        }
                        columnProcessor.offer(payload);
                    });
                }
        );
    }

    /**
     * Routes each batch entry to its per-type manager callback. An unknown responseType
     * skips that entry only, never the rest of the batch (forward compat with newer
     * servers). Package-private so tests can exercise it without a network receiver.
     */
    static void dispatchBatchResponses(LodRequestManager manager, BatchResponseS2CPayload payload) {
        for (int i = 0; i < payload.count(); i++) {
            long packed = payload.packedPositions()[i];
            byte type = payload.responseTypes()[i];
            switch (type) {
                case LSSConstants.RESPONSE_RATE_LIMITED -> manager.onRateLimited(packed);
                case LSSConstants.RESPONSE_UP_TO_DATE -> manager.onColumnUpToDate(packed);
                case LSSConstants.RESPONSE_NOT_GENERATED -> manager.onColumnNotGenerated(packed);
                default -> LSSLogger.warn("Unknown batch response type: " + type);
            }
        }
    }

    private static void registerConnectionLifecycle() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            serverEnabled = false;
            sessionConfigReceived = false;
            serverLodDistance = 0;
            requestManager = null;

            if (!LSSClientConfig.CONFIG.receiveServerLods) return;

            // Don't activate on singleplayer/integrated servers (unless testing)
            if (Minecraft.getInstance().hasSingleplayerServer()
                    && !Boolean.getBoolean("lss.test.integratedServer")) {
                return;
            }

            // Without a consumer the server would ignore our requests anyway — stay silent.
            if (!LSSApi.hasVoxelConsumers()) return;

            try {
                ClientPlayNetworking.send(new HandshakeC2SPayload(
                        LSSConstants.PROTOCOL_VERSION, LSSConstants.CAPABILITY_VOXEL_COLUMNS));
            } catch (Exception e) {
                LSSLogger.debug("Handshake send failed (server likely doesn't have LSS): " + e.getMessage());
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            var manager = requestManager;
            if (manager != null) {
                // Unstamp columns still queued for decode BEFORE the cache flush — their
                // received-stamps would otherwise persist for data no consumer ever saw.
                // (A column the drain thread polled concurrently still dispatches; if its
                // consumer then rejects, that single report lands after requestManager is
                // nulled and is dropped — at most one stale stamp per disconnect, healed
                // by the next session unless the server kept its timestamp cache. Accepted
                // residual.)
                reportUndispatchedColumns(manager);
                manager.disconnect();
                manager.saveCache();
            }
            columnProcessor.shutdown();
            columnProcessor.resetStats();
            serverEnabled = false;
            sessionConfigReceived = false;
            serverLodDistance = 0;
            columnsReceived.set(0);
            bytesReceived.set(0);
            connectionStartMs = 0;
            requestManager = null;
        });
    }

    private static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var manager = requestManager;
            if (manager != null && serverEnabled) {
                manager.tick();
            }
            columnProcessor.scheduleProcessing(serverEnabled);
        });
    }
}
