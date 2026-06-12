package dev.vox.lss.networking.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class ClientColumnProcessor {
    static final int MAX_QUEUED_COLUMNS = 8000;
    private static final long DROP_WARN_INTERVAL_MS = 5000;

    private final ConcurrentLinkedQueue<VoxelColumnS2CPayload> columnQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final AtomicLong columnsDropped = new AtomicLong();
    private volatile long lastDropWarnMs = 0;

    // Off-thread column processing: one executor for the singleton's lifetime. A session
    // epoch (bumped on disconnect) makes any in-flight drain self-terminate instead of
    // dispatching a stale session's payloads — no executor teardown/recreation needed.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "LSS-ColumnProcessor");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean processing = new AtomicBoolean();
    private volatile int sessionEpoch;

    void offer(VoxelColumnS2CPayload payload) {
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
            // The receive handler already stamped this position received — without the
            // report the drop would never be re-requested (permanent hole).
            LSSClientNetworking.reportIngestFailure(payload.dimension(),
                    payload.chunkX(), payload.chunkZ());
        }
    }

    void scheduleProcessing(boolean serverEnabled) {
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

        int epoch = this.sessionEpoch;
        if (this.processing.compareAndSet(false, true)) {
            try {
                this.executor.execute(() -> {
                    try {
                        drainColumnQueue(level, epoch);
                    } finally {
                        this.processing.set(false);
                    }
                });
            } catch (Exception e) {
                this.processing.set(false);
            }
        }
    }

    private void drainColumnQueue(ClientLevel level, int epoch) {
        var factory = PalettedContainerFactory.create(level.registryAccess());

        VoxelColumnS2CPayload payload;
        while (epoch == this.sessionEpoch && (payload = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            if (!level.dimension().equals(payload.dimension())) continue;

            byte[] decompressed = payload.decompressedSections();
            if (decompressed == null || decompressed.length == 0) {
                // Defensive (a compliant server always writes at least the section-count
                // varint) — but the position was stamped received at arrival, so a silent
                // drop here would be a permanent false stamp.
                LSSClientNetworking.reportIngestFailure(payload.dimension(),
                        payload.chunkX(), payload.chunkZ());
                continue;
            }

            try {
                var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(decompressed));
                try {
                    // Bound by the client world's actual section count: supports tall/modded
                    // worlds (no silent truncation) while still capping allocation from a peer.
                    int sectionCount = Math.max(0, Math.min(buf.readVarInt(), level.getSectionsCount()));
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
                LSSClientNetworking.reportIngestFailure(payload.dimension(),
                        payload.chunkX(), payload.chunkZ());
            }
        }
    }

    /**
     * Drain columns still queued at disconnect and report each as an ingest failure so the
     * manager forgets their received-stamps BEFORE the cache flush — otherwise the cache
     * persists stamps for columns no consumer ever saw and the next session resyncs as
     * up-to-date over the holes. The epoch bump stops any in-progress drain at its next
     * check; a column it already polled still dispatches normally (and its stamp is then
     * truthful). Main client thread, from the DISCONNECT handler.
     */
    void reportUndispatched(LodRequestManager manager) {
        this.sessionEpoch++;
        VoxelColumnS2CPayload payload;
        while ((payload = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            manager.onIngestFailure(payload.dimension(),
                    PositionUtil.packPosition(payload.chunkX(), payload.chunkZ()));
        }
    }

    /** End the current session: any in-flight drain self-terminates at its next epoch check. */
    void shutdown() {
        this.sessionEpoch++;
        this.columnQueue.clear();
        this.queueSize.set(0);
    }

    int getQueuedCount() { return this.queueSize.get(); }
    long getColumnsDropped() { return this.columnsDropped.get(); }

    void resetStats() {
        this.columnsDropped.set(0);
        this.lastDropWarnMs = 0;
    }
}
