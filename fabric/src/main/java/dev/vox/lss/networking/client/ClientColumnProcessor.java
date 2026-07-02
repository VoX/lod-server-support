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
    /** Byte budget for queued (still-compressed-in-memory) section payloads: the count cap
     *  alone admits up to 8000 x 2 MiB = 16 GiB from a hostile or misbehaving server before
     *  any drop fires. 256 MiB is ~13 s of backlog at the default 20 MB/s bandwidth cap —
     *  unreachable in normal play, fatal-allocation-proof under attack. */
    static final long MAX_QUEUED_BYTES = 256L * 1024 * 1024;
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

    /**
     * A queued column plus whether it is a RESYNC (the client already held data for this
     * position). On a resync the decode fills every absent section-Y with an all-air section
     * so a consumer overwrites ghost terrain the server cleared (WorldEdit-style clears and
     * fully-emptied columns). Captured on the main thread at offer time — the decode thread
     * must not touch ColumnStateMap.
     */
    private record QueuedColumn(VoxelColumnS2CPayload payload, boolean resync) {}

    private final ConcurrentLinkedQueue<QueuedColumn> columnQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final AtomicLong queuedBytes = new AtomicLong();
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

    private static int sectionBytesOf(VoxelColumnS2CPayload payload) {
        return payload.decompressedSections() == null ? 0 : payload.decompressedSections().length;
    }

    /** Queue admission: bounded by count AND bytes (either alone admits multi-GiB retention). */
    static boolean admits(int queuedCount, long queuedBytes, int payloadBytes) {
        return queuedCount < MAX_QUEUED_COLUMNS
                && queuedBytes + payloadBytes <= MAX_QUEUED_BYTES;
    }

    void offer(VoxelColumnS2CPayload payload, boolean resync) {
        // Null/corrupt section bytes still traverse the queue (the drain reports them):
        // count them as zero rather than NPE here.
        int payloadBytes = sectionBytesOf(payload);
        if (admits(this.queueSize.get(), this.queuedBytes.get(), payloadBytes)) {
            this.columnQueue.add(new QueuedColumn(payload, resync));
            this.queueSize.incrementAndGet();
            this.queuedBytes.addAndGet(payloadBytes);
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
        QueuedColumn queued;
        while ((queued = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            this.queuedBytes.addAndGet(-sectionBytesOf(queued.payload()));
            var payload = queued.payload();
            this.failureReporter.report(payload.dimension(),
                    payload.chunkX(), payload.chunkZ());
        }
    }

    private void drainColumnQueue(ClientLevel level, int epoch) {
        drainColumnQueue(level.dimension(), level.getSectionsCount(), level.getMinSectionY(),
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
    void drainColumnQueue(ResourceKey<Level> levelDimension, int levelSectionCount, int minSectionY,
                          PalettedContainerFactory factory, ColumnDispatcher dispatcher, int epoch) {
        QueuedColumn queued;
        while (epoch == this.sessionEpoch && (queued = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            this.queuedBytes.addAndGet(-sectionBytesOf(queued.payload()));
            var payload = queued.payload();
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
                var sections = decodeSections(decompressed, levelSectionCount, factory);
                if (queued.resync()) {
                    // The client already held data here; the server sends the current column
                    // (which OMITS air sections) authoritatively. Fill every absent section-Y
                    // with an all-air section so the consumer overwrites terrain the server
                    // cleared (WorldEdit clears, fully-emptied 0-section columns). Only on
                    // resync — a first serve had nothing, so absent == air == no-op.
                    sections = withAirFilledAbsentSections(sections, levelSectionCount, minSectionY, factory);
                }
                var columnData = new VoxelColumnData(sections, payload.columnTimestamp());
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
     * Append an all-air {@link VoxelColumnData.SectionData} for every section-Y in
     * {@code [minSectionY, minSectionY + levelSectionCount)} that the decoded column does not
     * already carry, so a resync overwrites ghost terrain the server cleared. A fresh all-air
     * {@link LevelChunkSection} per absent Y — {@code new LevelChunkSection(factory)} is all-air
     * until written — with null light layers (dark), consistent with the absent-means-all-zero
     * light default.
     */
    static VoxelColumnData.SectionData[] withAirFilledAbsentSections(
            VoxelColumnData.SectionData[] present, int levelSectionCount, int minSectionY,
            PalettedContainerFactory factory) {
        var seen = new java.util.HashSet<Integer>(present.length * 2);
        for (var s : present) seen.add(s.sectionY());

        var out = new java.util.ArrayList<VoxelColumnData.SectionData>(levelSectionCount);
        java.util.Collections.addAll(out, present);
        for (int i = 0; i < levelSectionCount; i++) {
            int y = minSectionY + i;
            if (!seen.contains(y)) {
                out.add(new VoxelColumnData.SectionData(y, new LevelChunkSection(factory), null, null));
            }
        }
        return out.toArray(new VoxelColumnData.SectionData[0]);
    }

    /**
     * True if these column bytes carry ZERO sections — the wire form of an authoritative
     * content-&gt;air CLEAR (the server sends it only to a data-claiming client). Reads just the
     * leading section-count varint. A malformed header reads as not-a-clear; the decode path
     * reports that column's failure separately.
     */
    static boolean isClearColumn(byte[] decompressed) {
        if (decompressed == null || decompressed.length == 0) return false;
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(decompressed));
        try {
            return buf.readVarInt() == 0;
        } catch (RuntimeException e) {
            return false;
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
        QueuedColumn queued;
        while ((queued = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            this.queuedBytes.addAndGet(-sectionBytesOf(queued.payload()));
            var payload = queued.payload();
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
        QueuedColumn drained;
        while ((drained = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            this.queuedBytes.addAndGet(-sectionBytesOf(drained.payload()));
        }
    }

    int getQueuedCount() { return this.queueSize.get(); }
    long getQueuedBytes() { return this.queuedBytes.get(); }
    long getColumnsDropped() { return this.columnsDropped.get(); }

    /** Current session epoch, for tests driving the drain loop directly. */
    int sessionEpochForTest() { return this.sessionEpoch; }

    void resetStats() {
        this.columnsDropped.set(0);
        this.lastDropWarnMs = 0;
    }
}
