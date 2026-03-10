package dev.vox.lss.networking.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class ClientColumnProcessor {
    static final int MAX_QUEUED_COLUMNS = 8000;
    private static final long DROP_WARN_INTERVAL_MS = 5000;
    private static final int MAX_SECTIONS_PER_COLUMN = 64;

    private final ConcurrentLinkedQueue<VoxelColumnS2CPayload> columnQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final AtomicLong columnsDropped = new AtomicLong();
    private volatile long lastDropWarnMs = 0;

    // Off-thread column processing
    private volatile ExecutorService executor = createExecutor();
    private final AtomicBoolean processing = new AtomicBoolean();
    private volatile boolean shuttingDown;

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "LSS-ColumnProcessor");
            t.setDaemon(true);
            return t;
        });
    }

    void offer(VoxelColumnS2CPayload payload) {
        if (this.shuttingDown) return;
        if (this.queueSize.get() < MAX_QUEUED_COLUMNS) {
            this.columnQueue.add(payload);
            this.queueSize.incrementAndGet();
        } else {
            long dropped = this.columnsDropped.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - this.lastDropWarnMs > DROP_WARN_INTERVAL_MS) {
                this.lastDropWarnMs = now;
                LSSLogger.warn("Column processing queue full (" + MAX_QUEUED_COLUMNS
                        + "), " + dropped + " columns dropped total");
            }
        }
    }

    void scheduleProcessing(boolean serverEnabled) {
        if (this.shuttingDown) return;
        if (!serverEnabled || !LSSClientConfig.CONFIG.receiveServerLods || !LSSApi.hasVoxelConsumers()) {
            this.columnQueue.clear();
            this.queueSize.set(0);
            return;
        }

        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) {
            this.columnQueue.clear();
            this.queueSize.set(0);
            return;
        }

        if (this.columnQueue.isEmpty()) return;

        if (LSSClientConfig.CONFIG.offThreadSectionProcessing) {
            if (this.processing.compareAndSet(false, true)) {
                var capturedLevel = level;
                try {
                    this.executor.execute(() -> {
                        try {
                            drainColumnQueue(capturedLevel);
                        } finally {
                            this.processing.set(false);
                        }
                    });
                } catch (Exception e) {
                    this.processing.set(false);
                }
            }
        } else {
            drainColumnQueue(level);
        }
    }

    private void drainColumnQueue(ClientLevel level) {
        var factory = PalettedContainerFactory.create(level.registryAccess());

        VoxelColumnS2CPayload payload;
        while (!Thread.currentThread().isInterrupted() && (payload = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            if (!level.dimension().equals(payload.dimension())) continue;

            byte[] decompressed = payload.decompressedSections();
            if (decompressed == null || decompressed.length == 0) continue;

            try {
                var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(decompressed));
                try {
                    int sectionCount = Math.max(0, Math.min(buf.readVarInt(), MAX_SECTIONS_PER_COLUMN));
                    var sectionDatas = new VoxelColumnData.SectionData[sectionCount];

                    for (int i = 0; i < sectionCount; i++) {
                        int sectionY = buf.readByte();

                        var section = new LevelChunkSection(factory);
                        section.read(buf);

                        DataLayer blockLight = null;
                        if (buf.readBoolean()) {
                            byte[] lightBytes = new byte[2048];
                            buf.readBytes(lightBytes);
                            blockLight = new DataLayer(lightBytes);
                        }

                        DataLayer skyLight = null;
                        if (buf.readBoolean()) {
                            byte[] lightBytes = new byte[2048];
                            buf.readBytes(lightBytes);
                            skyLight = new DataLayer(lightBytes);
                        }

                        sectionDatas[i] = new VoxelColumnData.SectionData(
                                sectionY, section, blockLight, skyLight);
                    }

                    var columnData = new VoxelColumnData(sectionDatas, payload.columnTimestamp());
                    LSSApi.dispatchColumn(level, payload.dimension(),
                            payload.chunkX(), payload.chunkZ(), columnData);
                } finally {
                    buf.release();
                }
            } catch (Exception e) {
                LSSLogger.error("Failed to process voxel column at "
                        + payload.chunkX() + "," + payload.chunkZ(), e);
            }
        }
    }

    void shutdown() {
        this.shuttingDown = true;
        var old = this.executor;
        old.shutdownNow();
        this.columnQueue.clear();
        this.queueSize.set(0);
        try {
            old.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        this.processing.set(false);
        this.executor = createExecutor();
        this.shuttingDown = false;
    }

    int getQueuedCount() { return this.queueSize.get(); }
    long getColumnsDropped() { return this.columnsDropped.get(); }

    void resetStats() {
        this.columnsDropped.set(0);
        this.lastDropWarnMs = 0;
    }
}
