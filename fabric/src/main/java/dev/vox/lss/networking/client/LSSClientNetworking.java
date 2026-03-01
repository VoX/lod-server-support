package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.api.LSSApi;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.ChunkSectionS2CPayload;
import dev.vox.lss.networking.payloads.ColumnUpToDateS2CPayload;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.RequestCompleteS2CPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

public class LSSClientNetworking {
    private static volatile boolean serverEnabled = false;
    private static volatile int serverLodDistance = 0;
    private static final AtomicLong sectionsReceived = new AtomicLong();
    private static final AtomicLong bytesReceived = new AtomicLong();
    private static volatile LodRequestManager requestManager;

    public static boolean isServerEnabled() {
        return serverEnabled;
    }

    public static int getServerLodDistance() {
        return serverLodDistance;
    }

    public static long getSectionsReceived() {
        return sectionsReceived.get();
    }

    public static long getBytesReceived() {
        return bytesReceived.get();
    }

    public static LodRequestManager getRequestManager() {
        return requestManager;
    }

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(
                SessionConfigS2CPayload.ID,
                (client, handler, buf, responseSender) -> {
                    SessionConfigS2CPayload payload = SessionConfigS2CPayload.read(buf);
                    client.execute(() -> {
                        LSSLogger.info("Server session config received (protocol v" + payload.protocolVersion()
                                + ", LOD distance: " + payload.lodDistanceChunks() + " chunks"
                                + ", enabled: " + payload.enabled()
                                + ", maxBatch: " + payload.maxRequestsPerBatch()
                                + ", maxPending: " + payload.maxPendingRequests()
                                + ", genBudget: " + payload.maxGenerationRequestsPerBatch()
                                + ", genDistance: " + payload.generationDistanceChunks() + ")");

                        if (payload.protocolVersion() != LSSConstants.PROTOCOL_VERSION) {
                            LSSLogger.warn("Server has incompatible LSS protocol version " + payload.protocolVersion()
                                    + " (client: " + LSSConstants.PROTOCOL_VERSION + "), LOD distribution disabled");
                            serverEnabled = false;
                            return;
                        }

                        serverEnabled = payload.enabled();
                        serverLodDistance = payload.lodDistanceChunks();

                        if (payload.enabled()) {
                            var manager = new LodRequestManager();
                            var serverData = Minecraft.getInstance().getCurrentServer();
                            String serverAddr = serverData != null ? serverData.ip : "unknown";
                            manager.onSessionConfig(payload, serverAddr);
                            requestManager = manager;
                        }
                    });
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                RequestCompleteS2CPayload.ID,
                (client, handler, buf, responseSender) -> {
                    RequestCompleteS2CPayload payload = RequestCompleteS2CPayload.read(buf);
                    client.execute(() -> {
                        var manager = requestManager;
                        if (manager != null) {
                            manager.onBatchComplete(payload.batchId(), payload.status());
                        }
                    });
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                ChunkSectionS2CPayload.ID,
                (client, handler, buf, responseSender) -> {
                    ChunkSectionS2CPayload payload = ChunkSectionS2CPayload.read(buf);
                    sectionsReceived.incrementAndGet();
                    bytesReceived.addAndGet(payload.sectionData().length + 20);

                    client.execute(() -> {
                        var manager = requestManager;
                        if (manager != null) {
                            manager.onSectionReceived(payload.chunkX(), payload.chunkZ(), payload.columnTimestamp());
                        }
                        handleChunkSection(payload);
                    });
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                ColumnUpToDateS2CPayload.ID,
                (client, handler, buf, responseSender) -> {
                    ColumnUpToDateS2CPayload payload = ColumnUpToDateS2CPayload.read(buf);
                    client.execute(() -> {
                        var manager = requestManager;
                        if (manager != null) {
                            manager.onColumnUpToDate(payload.chunkX(), payload.chunkZ());
                        }
                    });
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                DirtyColumnsS2CPayload.ID,
                (client, handler, buf, responseSender) -> {
                    DirtyColumnsS2CPayload payload = DirtyColumnsS2CPayload.read(buf);
                    client.execute(() -> {
                        var manager = requestManager;
                        if (manager != null) {
                            manager.onDirtyColumns(payload.dirtyPositions());
                        }
                    });
                }
        );

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            serverEnabled = false;
            serverLodDistance = 0;
            requestManager = null;

            if (!LSSClientConfig.CONFIG.receiveServerLods) return;

            if (Minecraft.getInstance().hasSingleplayerServer()
                    && !Boolean.getBoolean("lss.test.integratedServer")) {
                return;
            }

            try {
                FriendlyByteBuf buf = PacketByteBufs.create();
                new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION).write(buf);
                ClientPlayNetworking.send(HandshakeC2SPayload.ID, buf);
            } catch (Exception e) {
                LSSLogger.debug("Handshake send failed (server likely doesn't have LSS): " + e.getMessage());
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            var manager = requestManager;
            if (manager != null) {
                manager.saveCache();
            }
            serverEnabled = false;
            serverLodDistance = 0;
            sectionsReceived.set(0);
            bytesReceived.set(0);
            requestManager = null;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var manager = requestManager;
            if (manager != null && serverEnabled) {
                manager.tick();
            }
        });
    }

    private static void handleChunkSection(ChunkSectionS2CPayload payload) {
        if (!serverEnabled) return;
        if (!LSSClientConfig.CONFIG.receiveServerLods) return;
        if (!LSSApi.hasConsumers()) return;

        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) return;

        if (!level.dimension().equals(payload.dimension())) {
            return;
        }

        try {
            // RegistryFriendlyByteBuf does not exist in 1.20.1, using standard FriendlyByteBuf
            var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload.sectionData()));

            try {
                buf.readShort();

                var blockStates = new PalettedContainer<>(
                        Block.BLOCK_STATE_REGISTRY,
                        Blocks.AIR.defaultBlockState(),
                        PalettedContainer.Strategy.SECTION_STATES
                );
                blockStates.read(buf);

                var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
                var defaultBiome = biomeRegistry.getHolderOrThrow(Biomes.PLAINS); // Using getHolderOrThrow
                var biomes = new PalettedContainer<>(
                        biomeRegistry.asHolderIdMap(),
                        defaultBiome,
                        PalettedContainer.Strategy.SECTION_BIOMES
                );
                biomes.read(buf);

                var section = new LevelChunkSection(blockStates, biomes);

                section.recalcBlockCounts();

                DataLayer blockLight = null;
                DataLayer skyLight = null;
                if ((payload.lightFlags() & 0x01) != 0 && payload.blockLight() != null) {
                    blockLight = new DataLayer(payload.blockLight().clone());
                } else if ((payload.lightFlags() & 0x04) != 0) {
                    byte[] expanded = new byte[2048];
                    byte val = (byte) ((payload.uniformBlockLight() & 0xF) | ((payload.uniformBlockLight() & 0xF) << 4));
                    Arrays.fill(expanded, val);
                    blockLight = new DataLayer(expanded);
                }
                if ((payload.lightFlags() & 0x02) != 0 && payload.skyLight() != null) {
                    skyLight = new DataLayer(payload.skyLight().clone());
                } else if ((payload.lightFlags() & 0x08) != 0) {
                    byte[] expanded = new byte[2048];
                    byte val = (byte) ((payload.uniformSkyLight() & 0xF) | ((payload.uniformSkyLight() & 0xF) << 4));
                    Arrays.fill(expanded, val);
                    skyLight = new DataLayer(expanded);
                }

                LSSApi.dispatch(level, payload.dimension(), section,
                        payload.chunkX(), payload.sectionY(), payload.chunkZ(),
                        blockLight, skyLight);
            } finally {
                buf.release();
            }
        } catch (Exception e) {
            LSSLogger.error("Failed to process server LOD section at "
                    + payload.chunkX() + "," + payload.sectionY() + "," + payload.chunkZ(), e);
        }
    }
}