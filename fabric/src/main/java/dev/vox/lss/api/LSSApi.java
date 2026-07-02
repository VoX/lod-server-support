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
    private static final List<VoxelColumnConsumer> columnConsumers = new CopyOnWriteArrayList<>();

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
     */
    public static void reportIngestFailure(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        LSSClientNetworking.reportIngestFailure(dimension, chunkX, chunkZ);
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
            reportSink.report(dimension, chunkX, chunkZ);
            return;
        }
        for (var consumer : columnConsumers) {
            try {
                consumer.onVoxelColumnReceived(level, dimension, chunkX, chunkZ, columnData);
            } catch (Throwable e) {
                // Isolate per consumer: one consumer's failure (incl. Errors such as a
                // LinkageError from an incompatible LOD mod) must not skip the others.
                LSSLogger.error("Voxel column consumer threw exception", e);
                // A throw means the column was not ingested — treat it like an explicit
                // reportIngestFailure so the position is re-served instead of becoming a
                // permanent hole: exactly one report per failing consumer per delivery.
                // Chronic throwers are bounded by the per-position failure cap before
                // the position parks.
                reportSink.report(dimension, chunkX, chunkZ);
            }
        }
    }
}
