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
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

class ClientColumnProcessor {
    static final int MAX_QUEUED_COLUMNS = 8000;
    private static final long DROP_WARN_INTERVAL_MS = 5000;

    /**
     * Receives every "delivered but never ingested" report this processor emits (queue
     * overflow, backlog cleared without dispatch, empty/corrupt section bytes). Test seam
     * with a production default of {@link LSSClientNetworking#reportIngestFailure}, which
     * makes the manager forget the position's received-stamp and re-request it.
     */
    @FunctionalInterface
    interface FailureReporter {
        void report(ResourceKey<Level> dimension, int chunkX, int chunkZ);
    }

    /**
     * Fan-out target for a successfully decoded column. Test seam: production wiring
     * dispatches via {@link LSSApi#dispatchColumn} with the live client level.
     */
    @FunctionalInterface
    interface ColumnDispatcher {
        void dispatch(ResourceKey<Level> dimension, int chunkX, int chunkZ, VoxelColumnData columnData);
    }

    private final FailureReporter failureReporter;
    // Supplies the level scheduleProcessing drains against (null clears the backlog with
    // reports). Test seam; the production default reads the live Minecraft client.
    private final Supplier<ClientLevel> levelSupplier;

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

    ClientColumnProcessor() {
        this(LSSClientNetworking::reportIngestFailure, ClientColumnProcessor::liveClientLevel);
    }

    /** Seam constructor for tests; the no-arg constructor is the production wiring. */
    ClientColumnProcessor(FailureReporter failureReporter, Supplier<ClientLevel> levelSupplier) {
        this.failureReporter = failureReporter;
        this.levelSupplier = levelSupplier;
    }

    private static ClientLevel liveClientLevel() {
        var mc = Minecraft.getInstance();
        return mc != null ? mc.level : null;
    }

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
            this.failureReporter.report(payload.dimension(),
                    payload.chunkX(), payload.chunkZ());
        }
    }

    void scheduleProcessing(boolean serverEnabled) {
        if (!serverEnabled || !LSSClientConfig.CONFIG.receiveServerLods || !LSSApi.hasVoxelConsumers()) {
            reportAndClearBacklog();
            return;
        }

        var level = this.levelSupplier.get();
        if (level == null) {
            reportAndClearBacklog();
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

    /**
     * Clear the decode backlog, reporting every queued column as an ingest failure. Each
     * was stamped received at arrival, so the previously-silent clears (receiveServerLods
     * disable flip, last consumer deregistering, level-null race) left permanent false
     * stamps for data no consumer ever saw. A clear-while-still-serving loop is bounded
     * by the per-position failure cap (ColumnStateMap.MAX_INGEST_FAILURES parks the
     * position as satisfied).
     */
    private void reportAndClearBacklog() {
        VoxelColumnS2CPayload payload;
        while ((payload = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            this.failureReporter.report(payload.dimension(),
                    payload.chunkX(), payload.chunkZ());
        }
    }

    private void drainColumnQueue(ClientLevel level, int epoch) {
        drainColumnQueue(level.dimension(), level.getSectionsCount(),
                PalettedContainerFactory.create(level.registryAccess()),
                (dimension, chunkX, chunkZ, columnData) ->
                        LSSApi.dispatchColumn(level, dimension, chunkX, chunkZ, columnData),
                epoch);
    }

    /**
     * The drain loop, parameterized on the level-derived inputs so tests can drive it
     * without a {@link ClientLevel}. Contract per polled column: a stale-dimension column
     * is consumed silently (its stamp lives in the other dimension's map — a report here
     * would unstamp the wrong position); empty/absent bytes and any decode throw report
     * exactly once and the drain continues; the loop re-checks the session epoch at every
     * poll, so a teardown mid-drain lets at most the already-polled column dispatch.
     */
    void drainColumnQueue(ResourceKey<Level> levelDimension, int levelSectionCount,
                          PalettedContainerFactory factory, ColumnDispatcher dispatcher, int epoch) {
        VoxelColumnS2CPayload payload;
        while (epoch == this.sessionEpoch && (payload = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            if (!levelDimension.equals(payload.dimension())) continue;

            byte[] decompressed = payload.decompressedSections();
            if (decompressed == null || decompressed.length == 0) {
                // Defensive (a compliant server always writes at least the section-count
                // varint) — but the position was stamped received at arrival, so a silent
                // drop here would be a permanent false stamp.
                this.failureReporter.report(payload.dimension(),
                        payload.chunkX(), payload.chunkZ());
                continue;
            }

            try {
                var columnData = new VoxelColumnData(
                        decodeSections(decompressed, levelSectionCount, factory),
                        payload.columnTimestamp());
                dispatcher.dispatch(payload.dimension(),
                        payload.chunkX(), payload.chunkZ(), columnData);
            } catch (Exception e) {
                LSSLogger.error("Failed to process voxel column at "
                        + payload.chunkX() + "," + payload.chunkZ(), e);
                this.failureReporter.report(payload.dimension(),
                        payload.chunkX(), payload.chunkZ());
            }
        }
    }

    /**
     * Decode one column's wire bytes into section data. The claimed section count never
     * sizes the allocation: it is clamped to {@code [0, levelSectionCount]} — supporting
     * tall/modded worlds up to the client level's height while capping what a hostile
     * peer can make us allocate. Sections beyond the clamp are silently truncated (their
     * bytes are left unread). Bytes that lie about their own content throw, which the
     * drain converts into an ingest-failure report for the column.
     */
    static VoxelColumnData.SectionData[] decodeSections(byte[] decompressed, int levelSectionCount,
                                                        PalettedContainerFactory factory) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(decompressed));
        try {
            int sectionCount = Math.max(0, Math.min(buf.readVarInt(), levelSectionCount));
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
            return sectionDatas;
        } finally {
            buf.release();
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
        // Drain with the same poll+decrement discipline as the decode loop rather than
        // clear()+set(0): a set(0) races a decode iteration that already polled an item but
        // has not yet decremented, permanently driving the lifetime-singleton's queueSize to
        // -1 (drift accumulates across sessions). Pairing every poll with one decrement keeps
        // the counter self-consistent no matter which thread removes each item.
        while (this.columnQueue.poll() != null) {
            this.queueSize.decrementAndGet();
        }
    }

    int getQueuedCount() { return this.queueSize.get(); }
    long getColumnsDropped() { return this.columnsDropped.get(); }

    /** Current session epoch, for tests driving the drain loop directly. */
    int sessionEpochForTest() { return this.sessionEpoch; }

    void resetStats() {
        this.columnsDropped.set(0);
        this.lastDropWarnMs = 0;
    }
}
