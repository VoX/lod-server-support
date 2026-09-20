package dev.vox.lss.api;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.client.LSSClientNetworking;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public API for Voxel Server Support.
 * <p>
 * LOD rendering mods register a {@link VoxelColumnConsumer}
 * to receive pre-voxelized column data from the server.
 */
public final class LSSApi {

    // Log-sweep hygiene (2026-08-13): per-column/per-frame failure sites aggregate to
    // one line/min — a persistent condition must not flood the client log.
    private static final dev.vox.lss.common.LogThrottle CONSUMER_THROW_WARN =
            new dev.vox.lss.common.LogThrottle(60_000);
    private static final List<VoxelColumnConsumer> columnConsumers = new CopyOnWriteArrayList<>();

    /** A report captured during a consumer callback, safe to retain for deferred work. */
    public interface IngestFailureHandle {
        void report();
        boolean isActive();
        /** Retain only for bounded deferred work, during the callback. Run the returned
         * release exactly when that work commits, is rejected, or is intentionally dropped.
         * Retirement cancels unresolved acceptance without charging an ingest failure. */
        Runnable deferAcceptance();
    }

    private record DeliveryContext(ResourceKey<Level> dimension, int x, int z,
                                   IngestFailureHandle handle) {}
    private static final ThreadLocal<DeliveryContext> deliveryContext = new ThreadLocal<>();

    /** Returns the current delivery's report handle, or null outside an owned callback. */
    public static IngestFailureHandle captureIngestFailureHandle() {
        var context = deliveryContext.get();
        return context == null ? null : context.handle();
    }

    /** Internal dispatch scope. Detached consumers must explicitly retain the handle. */
    public static void withIngestFailureHandle(ResourceKey<Level> dimension, int x, int z,
                                               IngestFailureHandle handle, Runnable dispatch) {
        var previous = deliveryContext.get();
        if (handle != null) deliveryContext.set(new DeliveryContext(dimension, x, z, handle));
        else deliveryContext.remove();
        try {
            dispatch.run();
        } finally {
            if (previous == null) deliveryContext.remove();
            else deliveryContext.set(previous);
        }
    }

    private static IngestFailureHandle currentHandle(ResourceKey<Level> dimension, int x, int z) {
        var context = deliveryContext.get();
        return context != null && context.x() == x && context.z() == z
                && context.dimension().equals(dimension) ? context.handle() : null;
    }

    /**
     * Receives {@link #dispatchColumn}'s implicit ingest-failure reports (a throwing
     * consumer). Test seam — production default restored by {@link #resetReportSink()};
     * package-private so only same-package tests can swap it.
     */
    @FunctionalInterface
    interface ReportSink {
        void report(ResourceKey<Level> dimension, int chunkX, int chunkZ);
    }

    // Test seam, default-wired to production (zero behavior change when default-wired).
    static ReportSink reportSink;

    static {
        resetReportSink();
    }

    /** Restores production wiring for the test seam. */
    static void resetReportSink() {
        reportSink = LSSClientNetworking::reportIngestFailure;
    }

    private LSSApi() {}

    /**
     * Register a consumer to receive pre-voxelized column data from the server.
     * Call this during mod initialization.
     */
    public static void registerColumnConsumer(VoxelColumnConsumer consumer) {
        columnConsumers.add(consumer);
        LSSLogger.info("Registered voxel column consumer: " + consumer.getClass().getName());
    }

    /**
     * Remove a previously registered column consumer.
     */
    public static void removeColumnConsumer(VoxelColumnConsumer consumer) {
        columnConsumers.remove(consumer);
    }

    /**
     * Check whether any voxel consumers are registered.
     */
    public static boolean hasVoxelConsumers() {
        return !columnConsumers.isEmpty();
    }

    /**
     * Check whether the connected server has LOD distribution enabled.
     */
    public static boolean isServerEnabled() {
        return LSSClientNetworking.isServerEnabled();
    }

    /**
     * Get the server's configured LOD distance in chunks, or 0 if not connected.
     */
    public static int getServerLodDistance() {
        return LSSClientNetworking.getServerLodDistance();
    }

    /**
     * Report that a delivered column could not be ingested (e.g. the consumer's storage
     * was not ready or rejected the data). LSS forgets the column's received-stamp and
     * re-requests it on a later scan, so the data is re-served instead of being treated
     * as delivered. Without this report a rejected column becomes a permanent hole — LSS
     * has no other way to distinguish "delivered and consumed" from "delivered and lost".
     * Safe to call from any thread.
     *
     * <p>Throwing from {@link VoxelColumnConsumer#onVoxelColumnReceived} is treated as an
     * implicit report. Either way the re-served column is re-dispatched to <em>every</em>
     * registered consumer, so consumers must tolerate duplicate deliveries (the protocol
     * is position-keyed and idempotent). Repeated failures for the same position are
     * capped per session, after which the position is parked until its content changes.
     * Detached work should retain {@link #captureIngestFailureHandle()} during the
     * callback instead: this tokenless overload cannot identify an old delivery after
     * its session has been replaced.
     */
    public static void reportIngestFailure(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        var handle = currentHandle(dimension, chunkX, chunkZ);
        if (handle != null) handle.report();
        else LSSClientNetworking.reportIngestFailure(dimension, chunkX, chunkZ);
    }

    private static void reportDispatchFailure(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        var handle = currentHandle(dimension, chunkX, chunkZ);
        if (handle != null) handle.report();
        else reportSink.report(dimension, chunkX, chunkZ);
    }

    // Warn-once latch for a throwing pendingIngestBacklog() — the poll runs at up to
    // 20 Hz on the main client thread, so per-call logging would flood. Per-JVM (LSSApi
    // has no session lifecycle to key a per-session latch on); package-private reset
    // seam so the warn-once contract is testable.
    private static final java.util.concurrent.atomic.AtomicBoolean backlogWarnLatch =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Test seam: re-arm the {@link #maxReportedIngestBacklog} warn-once latch. */
    static void resetBacklogWarnLatchForTest() { backlogWarnLatch.set(false); }

    /** Test seam: whether the warn-once latch has fired (pins at-most-one-warn). */
    static boolean backlogWarnLatchedForTest() { return backlogWarnLatch.get(); }

    /**
     * Internal — not part of the public API. The largest
     * {@link VoxelColumnConsumer#pendingIngestBacklog()} across registered consumers, or
     * -1 when none report. Max, not sum: every consumer ingests the SAME columns in
     * parallel fan-out, so the slowest one must pace the stream. A throwing reporter is
     * contained to -1 (warn once per JVM) so a broken gauge degrades to "no signal", never
     * to a dead request loop. Main client thread, up to 20 Hz.
     * @hidden
     */
    public static int maxReportedIngestBacklog() {
        int max = -1;
        for (var consumer : columnConsumers) {
            try {
                max = Math.max(max, consumer.pendingIngestBacklog());
            } catch (Throwable e) {
                if (e instanceof Error err && !(e instanceof AssertionError)) throw err;
                if (backlogWarnLatch.compareAndSet(false, true)) {
                    LSSLogger.warn("A voxel column consumer's pendingIngestBacklog() threw — "
                            + "treated as unreported (further warnings suppressed): "
                            + consumer.getClass().getName(), e);
                }
            }
        }
        return max;
    }

    /**
     * Internal dispatch method — not part of the public API.
     * Called by the client networking layer to fan out column data to consumers.
     * @hidden
     */
    public static void dispatchColumn(ClientLevel level, ResourceKey<Level> dimension,
                                       int chunkX, int chunkZ, VoxelColumnData columnData) {
        if (columnConsumers.isEmpty()) {
            // Every consumer deregistered between scheduleProcessing's hasVoxelConsumers()
            // gate and this dispatch: the column was stamped received at arrival, so a silent
            // drop here is a permanent hole. Report it so the manager forgets the stamp and
            // re-requests (bounded by the per-position ingest-failure cap if no consumer
            // returns).
            reportDispatchFailure(dimension, chunkX, chunkZ);
            return;
        }
        for (var consumer : columnConsumers) {
            try {
                consumer.onVoxelColumnReceived(level, dimension, chunkX, chunkZ, columnData);
            } catch (Throwable e) {
                // Isolate per consumer: one consumer's failure (incl. Errors such as a
                // LinkageError from an incompatible LOD mod) must not skip the others.
                long n = CONSUMER_THROW_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (n > 0) {
                    LSSLogger.error("Voxel column consumer threw exception (" + n
                            + " consumer throw(s) since the last report; each column is"
                            + " re-served)", e);
                }
                // A throw means the column was not ingested — treat it like an explicit
                // reportIngestFailure so the position is re-served instead of becoming a
                // permanent hole: exactly one report per failing consumer per delivery.
                // Chronic throwers are bounded by the per-position failure cap before
                // the position parks.
                reportDispatchFailure(dimension, chunkX, chunkZ);
            }
        }
    }
}
