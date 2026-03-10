package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
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
import net.minecraft.world.level.storage.LevelResource;

import java.util.concurrent.atomic.AtomicLong;

public class LSSClientNetworking {
    private static volatile boolean serverEnabled = false;
    private static volatile int serverLodDistance = 0;
    private static final AtomicLong columnsReceived = new AtomicLong();
    private static final AtomicLong bytesReceived = new AtomicLong();
    private static volatile long connectionStartMs = 0;
    private static volatile LodRequestManager requestManager;

    private static final ClientColumnProcessor columnProcessor = new ClientColumnProcessor();

    public static boolean isServerEnabled() {
        return serverEnabled;
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

    public static void triggerHostHandshake() {
        Minecraft.getInstance().execute(() -> {
            if (!LSSClientConfig.CONFIG.receiveServerLods) return;
            if (requestManager != null) return;
            try {
                int clientCaps = LSSApi.hasVoxelConsumers()
                        ? LSSConstants.CAPABILITY_VOXEL_COLUMNS : 0;
                ClientPlayNetworking.send(new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, clientCaps));
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
                            + ", enabled: " + payload.enabled()
                            + ", syncRate: " + payload.syncOnLoadRateLimitPerPlayer() + ")");

                    if (payload.protocolVersion() != LSSConstants.PROTOCOL_VERSION) {
                        LSSLogger.warn("Server has incompatible LSS protocol version " + payload.protocolVersion()
                                + " (client: " + LSSConstants.PROTOCOL_VERSION + "), LOD distribution disabled");
                        serverEnabled = false;
                        return;
                    }

                    serverEnabled = payload.enabled();
                    serverLodDistance = payload.lodDistanceChunks();

                    if (payload.enabled()) {
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
                        for (int i = 0; i < payload.count(); i++) {
                            int requestId = payload.requestIds()[i];
                            byte type = payload.responseTypes()[i];
                            switch (type) {
                                case LSSConstants.RESPONSE_RATE_LIMITED -> manager.onRateLimited(requestId);
                                case LSSConstants.RESPONSE_UP_TO_DATE -> manager.onColumnUpToDate(requestId);
                                case LSSConstants.RESPONSE_NOT_GENERATED -> manager.onColumnNotGenerated(requestId);
                                default -> LSSLogger.warn("Unknown batch response type: " + type);
                            }
                        }
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
                            manager.onColumnReceived(payload.requestId(),
                                    payload.columnTimestamp());
                        }
                        columnProcessor.offer(payload);
                    });
                }
        );
    }

    private static void registerConnectionLifecycle() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            serverEnabled = false;
            serverLodDistance = 0;
            requestManager = null;

            if (!LSSClientConfig.CONFIG.receiveServerLods) return;

            // Don't activate on singleplayer/integrated servers (unless testing)
            if (Minecraft.getInstance().hasSingleplayerServer()
                    && !Boolean.getBoolean("lss.test.integratedServer")) {
                return;
            }

            try {
                int clientCaps = LSSApi.hasVoxelConsumers()
                        ? LSSConstants.CAPABILITY_VOXEL_COLUMNS : 0;
                ClientPlayNetworking.send(new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, clientCaps));
            } catch (Exception e) {
                LSSLogger.debug("Handshake send failed (server likely doesn't have LSS): " + e.getMessage());
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            var manager = requestManager;
            if (manager != null) {
                manager.disconnect();
                manager.saveCache();
            }
            columnProcessor.shutdown();
            columnProcessor.resetStats();
            serverEnabled = false;
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
