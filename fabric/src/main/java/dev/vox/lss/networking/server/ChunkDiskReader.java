package dev.vox.lss.networking.server;

import com.mojang.serialization.Codec;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LSSUtil;
import dev.vox.lss.common.RegionTimestampReader;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.ChunkSectionS2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkDiskReader {
    public record ReadResult(UUID playerUuid, int batchId, int chunkX, int chunkZ,
                                ChunkSectionS2CPayload[] payloads, long columnTimestamp,
                                boolean upToDate, boolean notFound,
                                long submissionOrder) {}

    private static ReadResult emptyResult(UUID playerUuid, int batchId, int chunkX, int chunkZ, long submissionOrder) {
        return new ReadResult(playerUuid, batchId, chunkX, chunkZ, new ChunkSectionS2CPayload[0], 0L, false, true, submissionOrder);
    }

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final ExecutorService executor;
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<ReadResult>> playerResults = new ConcurrentHashMap<>();
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    private final AtomicLong submittedCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong emptyCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong notFullStatusCount = new AtomicLong();
    private final AtomicLong noSectionsCount = new AtomicLong();
    private final AtomicLong totalPayloadsProduced = new AtomicLong();

    public ChunkDiskReader(int threadCount) {
        this.executor = Executors.newFixedThreadPool(threadCount, r -> {
            var thread = new Thread(r, "LSS Disk Reader #" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    public void submitRead(UUID playerUuid, int batchId, ServerLevel level, int chunkX, int chunkZ,
                            long clientTimestamp, Path regionDir, long submissionOrder) {
        if (this.isShutdown.get()) return;

        this.submittedCount.incrementAndGet();
        var dimension = level.dimension();
        var registryAccess = level.registryAccess();
        var chunkMap = ((dev.vox.lss.mixin.AccessorServerChunkCache) level.getChunkSource()).getChunkMap();
        try {
            this.executor.submit(() -> {
                if (this.isShutdown.get()) return;
                try {
                    this.readChunkFromDisk(playerUuid, batchId, chunkMap, chunkX, chunkZ, dimension,
                            registryAccess, clientTimestamp, regionDir, submissionOrder);
                } catch (Exception e) {
                    LSSLogger.error("Failed to read chunk from disk at " + chunkX + ", " + chunkZ, e);
                    this.errorCount.incrementAndGet();
                    this.completedCount.incrementAndGet();
                    this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>()).add(emptyResult(playerUuid, batchId, chunkX, chunkZ, submissionOrder));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            this.completedCount.incrementAndGet();
            this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>()).add(emptyResult(playerUuid, batchId, chunkX, chunkZ, submissionOrder));
        }
    }

    private void readChunkFromDisk(UUID playerUuid, int batchId, net.minecraft.server.level.ChunkMap chunkMap,
                                    int chunkX, int chunkZ,
                                    ResourceKey<Level> dimension, RegistryAccess registryAccess,
                                    long clientTimestamp, Path regionDir, long submissionOrder) {
        if (this.isShutdown.get()) return;

        // Fast-path: read the 4-byte region header timestamp before full NBT parse
        long headerTimestamp = RegionTimestampReader.readTimestamp(regionDir, chunkX, chunkZ);
        if (clientTimestamp > 0 && headerTimestamp > 0 && headerTimestamp <= clientTimestamp) {
            this.completedCount.incrementAndGet();
            this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>()).add(new ReadResult(playerUuid, batchId, chunkX, chunkZ,
                    new ChunkSectionS2CPayload[0], headerTimestamp, true, false, submissionOrder));
            return;
        }

        CompoundTag chunkNbt;
        try {
            var future = chunkMap.read(new ChunkPos(chunkX, chunkZ));
            var optionalTag = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (optionalTag.isEmpty()) {
                this.emptyCount.incrementAndGet();
                this.completedCount.incrementAndGet();
                this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>()).add(emptyResult(playerUuid, batchId, chunkX, chunkZ, submissionOrder));
                return;
            }
            chunkNbt = optionalTag.get();
        } catch (Exception e) {
            LSSLogger.error("Failed to read chunk NBT from disk at " + chunkX + ", " + chunkZ, e);
            this.errorCount.incrementAndGet();
            this.completedCount.incrementAndGet();
            this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>()).add(emptyResult(playerUuid, batchId, chunkX, chunkZ, submissionOrder));
            return;
        }

        if (this.isShutdown.get()) return;

        var statusStr = chunkNbt.getString("Status");
        boolean isFull = !statusStr.isEmpty() && ChunkStatus.byName(statusStr) == ChunkStatus.FULL;
        if (!isFull) {
            this.notFullStatusCount.incrementAndGet();
            this.completedCount.incrementAndGet();
            this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>()).add(new ReadResult(playerUuid, batchId, chunkX, chunkZ, new ChunkSectionS2CPayload[0], 0L, false, true, submissionOrder));
            return;
        }

        long columnTimestamp = headerTimestamp > 0 ? headerTimestamp : chunkNbt.getLong("LastUpdate");

        var blockStateCodec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
        var biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);
        var defaultBiome = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
        var biomeCodec = PalettedContainer.codecRO(biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, defaultBiome);

        var sectionsTag = chunkNbt.getList("sections", 10);
        if (sectionsTag.isEmpty()) {
            this.noSectionsCount.incrementAndGet();
            this.completedCount.incrementAndGet();
            this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>()).add(emptyResult(playerUuid, batchId, chunkX, chunkZ, submissionOrder));
            return;
        }

        var payloads = new java.util.ArrayList<ChunkSectionS2CPayload>();
        for (int i = 0; i < sectionsTag.size(); i++) {
            if (this.isShutdown.get()) break;
            var sectionTag = sectionsTag.getCompound(i);
            int sectionY = sectionTag.contains("Y") ? sectionTag.getInt("Y") : Integer.MIN_VALUE;
            if (sectionY == Integer.MIN_VALUE) continue;

            var payload = parseSectionFromNbt(sectionTag, sectionY, chunkX, chunkZ, dimension, registryAccess, blockStateCodec, biomeCodec, defaultBiome, columnTimestamp);
            if (payload != null) payloads.add(payload);
        }

        this.totalPayloadsProduced.addAndGet(payloads.size());
        this.completedCount.incrementAndGet();
        this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>()).add(new ReadResult(playerUuid, batchId, chunkX, chunkZ,
                payloads.toArray(new ChunkSectionS2CPayload[0]), columnTimestamp, false, false, submissionOrder));
    }

    private static final byte[] EMPTY = new byte[0];

    private ChunkSectionS2CPayload parseSectionFromNbt(
            CompoundTag sectionTag, int sectionY, int chunkX, int chunkZ,
            ResourceKey<Level> dimension, RegistryAccess registryAccess,
            Codec<PalettedContainer<BlockState>> blockStateCodec,
            Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
            Holder<Biome> defaultBiome, long columnTimestamp) {

        if (sectionTag.getCompound("block_states").isEmpty()) {
            return null;
        }

        var blockStatesResult = blockStateCodec.parse(NbtOps.INSTANCE, sectionTag.get("block_states"));
        
        var blockStatesOpt = blockStatesResult.resultOrPartial(err -> LSSLogger.error(err));
        if (blockStatesOpt.isEmpty()) return null;
        var blockStates = blockStatesOpt.get();

        PalettedContainerRO<Holder<Biome>> biomes = null;
        var optBiomes = sectionTag.get("biomes");
        if (optBiomes != null) {
            var biomesResult = biomeCodec.parse(NbtOps.INSTANCE, optBiomes);
            biomes = biomesResult.result().orElse(null);
        }
        if (biomes == null) {
            var biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);
            biomes = new PalettedContainer<>(biomeRegistry.asHolderIdMap(), defaultBiome, PalettedContainer.Strategy.SECTION_BIOMES);
        }

        LevelChunkSection section = new LevelChunkSection(blockStates, biomes);

        section.recalcBlockCounts();

        var buf = new FriendlyByteBuf(Unpooled.buffer(8192));
        try {
            section.write(buf);
            byte[] sectionData = new byte[buf.readableBytes()];
            buf.readBytes(sectionData);

            byte[] blockLightData = sectionTag.getByteArray("BlockLight");
            byte[] skyLightData = sectionTag.getByteArray("SkyLight");

            byte lightFlags = 0;
            byte[] blockLight = null;
            byte[] skyLight = null;
            byte uniformBlockLight = 0;
            byte uniformSkyLight = 0;

            if (LSSServerConfig.CONFIG.sendLightData) {
                if (blockLightData.length == 2048) {
                    if (LSSUtil.isUniformArray(blockLightData)) {
                        lightFlags |= 0x04;
                        uniformBlockLight = (byte) (blockLightData[0] & 0x0F);
                    } else {
                        lightFlags |= 0x01;
                        blockLight = blockLightData;
                    }
                } else {
                    // If block light data is missing - send 0
                    lightFlags |= 0x04;
                    uniformBlockLight = 0;
                }
                
                if (skyLightData.length == 2048) {
                    if (LSSUtil.isUniformArray(skyLightData)) {
                        lightFlags |= 0x08;
                        uniformSkyLight = (byte) (skyLightData[0] & 0x0F);
                    } else {
                        lightFlags |= 0x02;
                        skyLight = skyLightData;
                    }
                } else {
                    // If sky light data is missing - send 15 (maximum brightness for LOD)
                    lightFlags |= 0x08;
                    uniformSkyLight = 15;
                }
            }

            return new ChunkSectionS2CPayload(
                    chunkX, sectionY, chunkZ, dimension,
                    sectionData, lightFlags, blockLight, skyLight,
                    uniformBlockLight, uniformSkyLight, columnTimestamp
            );
        } catch (Exception e) {
            LSSLogger.error("Failed to serialize disk-read section at " + chunkX + "," + sectionY + "," + chunkZ, e);
            return null;
        } finally {
            buf.release();
        }
    }

    public ConcurrentLinkedQueue<ReadResult> getPlayerQueue(UUID playerUuid) {
        return this.playerResults.get(playerUuid);
    }

    public void removePlayerResults(UUID playerUuid) {
        this.playerResults.remove(playerUuid);
    }

    public String getDiagnostics() {
        int pending = 0;
        for (var queue : this.playerResults.values()) {
            pending += queue.size();
        }
        return String.format("submitted=%d, completed=%d, empty=%d, notFull=%d, noSections=%d, errors=%d, payloads=%d, pending=%d",
                submittedCount.get(), completedCount.get(), emptyCount.get(),
                notFullStatusCount.get(), noSectionsCount.get(), errorCount.get(),
                totalPayloadsProduced.get(), pending);
    }

    public void shutdown() {
        this.isShutdown.set(true);
        this.executor.shutdownNow();
        this.playerResults.clear();
    }
}
