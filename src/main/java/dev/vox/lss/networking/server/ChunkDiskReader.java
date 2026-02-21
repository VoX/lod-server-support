package dev.vox.lss.networking.server;

import com.mojang.serialization.Codec;
import dev.vox.lss.LSSLogger;
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

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkDiskReader {
    public record ReadResult(UUID playerUuid, int batchId, int chunkX, int chunkZ,
                                ChunkSectionS2CPayload[] payloads, long columnTimestamp,
                                boolean upToDate, boolean notFound) {}

    private static ReadResult emptyResult(UUID playerUuid, int batchId, int chunkX, int chunkZ) {
        return new ReadResult(playerUuid, batchId, chunkX, chunkZ, new ChunkSectionS2CPayload[0], 0L, false, true);
    }

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<ReadResult> results = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    private final AtomicLong submittedCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong emptyCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong notFullStatusCount = new AtomicLong();
    private final AtomicLong noSectionsCount = new AtomicLong();
    private final AtomicLong totalPayloadsProduced = new AtomicLong();
    private final AtomicLong skippedUndergroundCount = new AtomicLong();

    public ChunkDiskReader(int threadCount) {
        this.executor = Executors.newFixedThreadPool(threadCount, r -> {
            var thread = new Thread(r, "LSS Disk Reader #" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    public void submitRead(UUID playerUuid, int batchId, ServerLevel level, int chunkX, int chunkZ, long clientTimestamp) {
        if (this.isShutdown.get()) return;

        this.submittedCount.incrementAndGet();
        var dimension = level.dimension();
        var registryAccess = level.registryAccess();
        int minBuildHeight = level.getMinY();
        int worldHeight = level.getHeight();
        boolean hasCeiling = level.dimensionType().hasCeiling();
        var chunkMap = ((dev.vox.lss.mixin.AccessorServerChunkCache) level.getChunkSource()).getChunkMap();
        this.executor.submit(() -> {
            if (this.isShutdown.get()) return;
            try {
                this.readChunkFromDisk(playerUuid, batchId, chunkMap, chunkX, chunkZ, dimension, registryAccess, clientTimestamp, minBuildHeight, worldHeight, hasCeiling);
            } catch (Exception e) {
                LSSLogger.error("Failed to read chunk from disk at " + chunkX + ", " + chunkZ, e);
                this.errorCount.incrementAndGet();
                this.completedCount.incrementAndGet();
                this.results.add(emptyResult(playerUuid, batchId, chunkX, chunkZ));
            }
        });
    }

    private void readChunkFromDisk(UUID playerUuid, int batchId, net.minecraft.server.level.ChunkMap chunkMap,
                                    int chunkX, int chunkZ,
                                    ResourceKey<Level> dimension, RegistryAccess registryAccess,
                                    long clientTimestamp, int minBuildHeight, int worldHeight, boolean hasCeiling) {
        if (this.isShutdown.get()) return;

        CompoundTag chunkNbt;
        try {
            var future = chunkMap.read(new ChunkPos(chunkX, chunkZ));
            var optionalTag = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (optionalTag.isEmpty()) {
                this.emptyCount.incrementAndGet();
                this.completedCount.incrementAndGet();
                this.results.add(emptyResult(playerUuid, batchId, chunkX, chunkZ));
                return;
            }
            chunkNbt = optionalTag.get();
        } catch (Exception e) {
            LSSLogger.error("Failed to read chunk NBT from disk at " + chunkX + ", " + chunkZ, e);
            this.errorCount.incrementAndGet();
            this.completedCount.incrementAndGet();
            this.results.add(emptyResult(playerUuid, batchId, chunkX, chunkZ));
            return;
        }

        if (this.isShutdown.get()) return;

        if (!chunkNbt.contains("Status")) {
            this.notFullStatusCount.incrementAndGet();
            this.completedCount.incrementAndGet();
            this.results.add(emptyResult(playerUuid, batchId, chunkX, chunkZ));
            return;
        }
        var status = ChunkStatus.byName(chunkNbt.getStringOr("Status", null));
        if (status != ChunkStatus.FULL) {
            this.notFullStatusCount.incrementAndGet();
            this.completedCount.incrementAndGet();
            this.results.add(emptyResult(playerUuid, batchId, chunkX, chunkZ));
            return;
        }

        long lastUpdate = chunkNbt.getLongOr("LastUpdate", 0L);

        if (clientTimestamp > 0 && lastUpdate <= clientTimestamp) {
            this.completedCount.incrementAndGet();
            this.results.add(new ReadResult(playerUuid, batchId, chunkX, chunkZ,
                    new ChunkSectionS2CPayload[0], lastUpdate, true, false));
            return;
        }

        int minSurfaceSectionY = Integer.MIN_VALUE;
        if (LSSServerConfig.CONFIG.skipUndergroundSections && !hasCeiling) {
            minSurfaceSectionY = extractMinSurfaceSectionY(chunkNbt, LSSServerConfig.CONFIG.undergroundSkipMargin, minBuildHeight, worldHeight);
        }

        var factory = PalettedContainerFactory.create(registryAccess);
        var blockStateCodec = factory.blockStatesContainerCodec();
        var biomeCodec = factory.biomeContainerCodec();

        var biomeRegistry = registryAccess.lookupOrThrow(Registries.BIOME);
        var defaultBiome = biomeRegistry.getOrThrow(Biomes.PLAINS);

        var sectionsTag = chunkNbt.getList("sections");
        if (sectionsTag.isEmpty()) {
            this.noSectionsCount.incrementAndGet();
            this.completedCount.incrementAndGet();
            this.results.add(emptyResult(playerUuid, batchId, chunkX, chunkZ));
            return;
        }

        var sectionsList = sectionsTag.orElseThrow();
        var payloads = new java.util.ArrayList<ChunkSectionS2CPayload>();

        for (var sectionElement : sectionsList) {
            if (this.isShutdown.get()) break;

            var sectionTag = (CompoundTag) sectionElement;
            int sectionY = sectionTag.getIntOr("Y", Integer.MIN_VALUE);
            if (sectionY == Integer.MIN_VALUE) continue;
            if (sectionY < minSurfaceSectionY) {
                this.skippedUndergroundCount.incrementAndGet();
                continue;
            }

            var payload = parseSectionFromNbt(sectionTag, sectionY, chunkX, chunkZ, dimension,
                    registryAccess, blockStateCodec, biomeCodec, defaultBiome, lastUpdate);
            if (payload != null) {
                payloads.add(payload);
            }
        }

        this.totalPayloadsProduced.addAndGet(payloads.size());
        this.completedCount.incrementAndGet();
        this.results.add(new ReadResult(playerUuid, batchId, chunkX, chunkZ,
                payloads.toArray(new ChunkSectionS2CPayload[0]), lastUpdate, false, false));
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

            if (LSSServerConfig.CONFIG.sendLightData) {
                if (blockLightData.length == 2048) {
                    lightFlags |= 1;
                    blockLight = blockLightData;
                }
                if (skyLightData.length == 2048) {
                    lightFlags |= 2;
                    skyLight = skyLightData;
                }
            }

            return new ChunkSectionS2CPayload(
                    chunkX, sectionY, chunkZ, dimension,
                    sectionData, lightFlags, blockLight, skyLight, columnTimestamp
            );
        } catch (Exception e) {
            LSSLogger.error("Failed to serialize disk-read section at " + chunkX + "," + sectionY + "," + chunkZ, e);
            return null;
        } finally {
            buf.release();
        }
    }

    private static int extractMinSurfaceSectionY(CompoundTag chunkNbt, int margin, int minBuildHeight, int worldHeight) {
        var heightmaps = chunkNbt.getCompound("Heightmaps");
        if (heightmaps.isEmpty()) return Integer.MIN_VALUE;

        var oceanFloor = heightmaps.get().getLongArray("OCEAN_FLOOR");
        if (oceanFloor.isEmpty()) return Integer.MIN_VALUE;
        long[] packed = oceanFloor.get();
        if (packed.length == 0) return Integer.MIN_VALUE;

        int bits = ceilLog2(worldHeight + 1);
        if (bits <= 0) return Integer.MIN_VALUE;
        int valuesPerLong = 64 / bits;
        int expectedLength = (256 + valuesPerLong - 1) / valuesPerLong;
        if (packed.length != expectedLength) return Integer.MIN_VALUE;
        long mask = (1L << bits) - 1;
        int minHeight = Integer.MAX_VALUE;
        int index = 0;

        for (long word : packed) {
            for (int v = 0; v < valuesPerLong && index < 256; v++) {
                int height = (int) (word & mask);
                if (height > 0) {
                    minHeight = Math.min(minHeight, height);
                }
                word >>>= bits;
                index++;
            }
        }

        if (minHeight == Integer.MAX_VALUE) return Integer.MIN_VALUE;

        int blockY = minHeight + minBuildHeight - 1;
        return (blockY >> 4) - margin;
    }

    private static int ceilLog2(int value) {
        if (value <= 1) return 0;
        return Integer.SIZE - Integer.numberOfLeadingZeros(value - 1);
    }

    public ReadResult pollResult() {
        return this.results.poll();
    }

    public String getDiagnostics() {
        return String.format("submitted=%d, completed=%d, empty=%d, notFull=%d, noSections=%d, errors=%d, payloads=%d, pending=%d, underground_skipped=%d",
                submittedCount.get(), completedCount.get(), emptyCount.get(),
                notFullStatusCount.get(), noSectionsCount.get(), errorCount.get(),
                totalPayloadsProduced.get(), results.size(), skippedUndergroundCount.get());
    }

    public void shutdown() {
        this.isShutdown.set(true);
        this.executor.shutdownNow();
        this.results.clear();
    }
}
