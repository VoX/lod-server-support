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
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.status.ChunkStatus;

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

        var statusStr = chunkNbt.getStringOr("Status", null);
        boolean isFull = statusStr != null && ChunkStatus.byName(statusStr) == ChunkStatus.FULL;
        if (!isFull) {
            this.notFullStatusCount.incrementAndGet();
            this.completedCount.incrementAndGet();
            // Route to generation service so MC upgrades the partial chunk to FULL status.
            this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>()).add(new ReadResult(playerUuid, batchId, chunkX, chunkZ,
                    new ChunkSectionS2CPayload[0], 0L, false, true, submissionOrder));
            return;
        }

        // Use region header timestamp (epoch seconds) as columnTimestamp.
        // Fall back to NBT LastUpdate if header timestamp is unavailable.
        long columnTimestamp = headerTimestamp > 0 ? headerTimestamp : chunkNbt.getLongOr("LastUpdate", 0L);

        var factory = PalettedContainerFactory.create(registryAccess);
        var blockStateCodec = factory.blockStatesContainerCodec();
        var biomeCodec = factory.biomeContainerCodec();

        var biomeRegistry = registryAccess.lookupOrThrow(Registries.BIOME);
        var defaultBiome = biomeRegistry.getOrThrow(Biomes.PLAINS);

        var sectionsTag = chunkNbt.getList("sections");
        if (sectionsTag.isEmpty()) {
            this.noSectionsCount.incrementAndGet();
            this.completedCount.incrementAndGet();
            this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>()).add(emptyResult(playerUuid, batchId, chunkX, chunkZ, submissionOrder));
            return;
        }

        var sectionsList = sectionsTag.orElseThrow();
        var payloads = new java.util.ArrayList<ChunkSectionS2CPayload>();

        for (var sectionElement : sectionsList) {
            if (this.isShutdown.get()) break;

            var sectionTag = (CompoundTag) sectionElement;
            int sectionY = sectionTag.getIntOr("Y", Integer.MIN_VALUE);
            if (sectionY == Integer.MIN_VALUE) continue;

            var payload = parseSectionFromNbt(sectionTag, sectionY, chunkX, chunkZ, dimension,
                    registryAccess, blockStateCodec, biomeCodec, defaultBiome, columnTimestamp);
            if (payload != null) {
                payloads.add(payload);
            }
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

        var blockStatesResult = blockStateCodec.parse(NbtOps.INSTANCE, sectionTag.getCompound("block_states").get());
        if (!blockStatesResult.hasResultOrPartial()) {
            return null;
        }
        var blockStates = blockStatesResult.getPartialOrThrow();

        PalettedContainerRO<Holder<Biome>> biomes;
        var optBiomes = sectionTag.getCompound("biomes");
        if (optBiomes.isPresent()) {
            var biomesResult = biomeCodec.parse(NbtOps.INSTANCE, optBiomes.get());
            biomes = biomesResult.result().orElse(null);
        } else {
            biomes = null;
        }

        LevelChunkSection section;
        if (biomes instanceof PalettedContainer<Holder<Biome>> biomeContainer) {
            section = new LevelChunkSection(blockStates, biomeContainer);
        } else {
            var biomeRegistry = registryAccess.lookupOrThrow(Registries.BIOME);
            var defaultBiomeContainer = new PalettedContainer<>(
                    defaultBiome,
                    Strategy.createForBiomes(biomeRegistry.asHolderIdMap())
            );
            section = new LevelChunkSection(blockStates, defaultBiomeContainer);
        }

        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(8192), registryAccess);
        try {
            section.write(buf);
            byte[] sectionData = new byte[buf.readableBytes()];
            buf.readBytes(sectionData);

            byte[] blockLightData = sectionTag.getByteArray("BlockLight").orElse(EMPTY);
            byte[] skyLightData = sectionTag.getByteArray("SkyLight").orElse(EMPTY);

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
                }
                if (skyLightData.length == 2048) {
                    if (LSSUtil.isUniformArray(skyLightData)) {
                        lightFlags |= 0x08;
                        uniformSkyLight = (byte) (skyLightData[0] & 0x0F);
                    } else {
                        lightFlags |= 0x02;
                        skyLight = skyLightData;
                    }
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
