package dev.vox.lss.networking.client;

import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload;
import dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * NeoForge client-side networking — the fabric {@code LSSClientNetworking}
 * twin (same FQN, per-loader tree). Since N-3 this is REAL: every member
 * delegates to the loader-neutral {@link ClientNetGlue} (xplat — the session
 * state + receiver bodies both loaders share); this class carries only the
 * payload-handler adapters the registrar binds and the static surface xplat
 * compiles against.
 *
 * <p>DELIBERATELY STATELESS (no static fields): the registrar's method
 * references load this class on the DEDICATED server too — class init must
 * never touch {@code ClientNetGlue}/{@code Minecraft} (client-only classes);
 * handler bodies resolve lazily and only ever run on physical clients.
 */
public class LSSClientNetworking {

    public static boolean isServerEnabled() {
        return ClientNetGlue.isServerEnabled();
    }

    public static int getSessionVersion() {
        return ClientNetGlue.getSessionVersion();
    }

    public static boolean hasReceivedSessionConfig() {
        return ClientNetGlue.hasReceivedSessionConfig();
    }

    public static int getServerLodDistance() {
        return ClientNetGlue.getServerLodDistance();
    }

    public static long getColumnsReceived() {
        return ClientNetGlue.getColumnsReceived();
    }

    public static long getBytesReceived() {
        return ClientNetGlue.getBytesReceived();
    }

    public static long getWireBytesReceived() {
        return ClientNetGlue.getWireBytesReceived();
    }

    public static long getColumnsDropped() {
        return ClientNetGlue.getColumnsDropped();
    }

    public static long getConnectionStartMs() {
        return ClientNetGlue.getConnectionStartMs();
    }

    public static LodRequestManager getRequestManager() {
        return ClientNetGlue.getRequestManager();
    }

    public static int getQueuedColumnCount() {
        return ClientNetGlue.getQueuedColumnCount();
    }

    public static long getQueuedColumnBytes() {
        return ClientNetGlue.getQueuedColumnBytes();
    }

    /** LSSApi's ingest-failure sink — see {@link ClientNetGlue#reportIngestFailure}. */
    public static void reportIngestFailure(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        ClientNetGlue.reportIngestFailure(dimension, chunkX, chunkZ);
    }

    /** ClientColumnProcessor's silent-clear report path. */
    static void reportUndispatchedColumns(LodRequestManager manager) {
        ClientNetGlue.reportUndispatchedColumns(manager);
    }

    /** LAN host re-handshake (issue #257): the {@code IntegratedServerLanHook} mixin's
     *  activation path calls this so the host — already "joined" its own world before the
     *  service existed — re-sends its handshake and gets registered (shared body). */
    public static void triggerHostHandshake() {
        ClientNetGlue.triggerHostHandshake();
    }

    // ---- Payload handlers (bound by the registrar; executesOn(MAIN)) ----

    public static void handleSessionConfigPayload(SessionConfigS2CPayload payload,
                                                  IPayloadContext context) {
        ClientNetGlue.onSessionConfigFrame(payload);
    }

    public static void handleBatchResponsePayload(BatchResponseS2CPayload payload,
                                                  IPayloadContext context) {
        ClientNetGlue.onBatchResponseFrame(payload);
    }

    public static void handleDirtyColumnsPayload(DirtyColumnsS2CPayload payload,
                                                 IPayloadContext context) {
        ClientNetGlue.onDirtyColumnsFrame(payload);
    }

    public static void handleVoxelColumnPayload(VoxelColumnS2CPayload payload,
                                                IPayloadContext context) {
        ClientNetGlue.onVoxelColumnFrame(payload);
    }

    public static void handleFarPlayerRosterPayload(FarPlayerRosterS2CPayload payload,
                                                    IPayloadContext context) {
        ClientNetGlue.onFarPlayerRosterFrame(payload.body());
    }

    public static void handleFarPlayerUpdatesPayload(FarPlayerUpdatesS2CPayload payload,
                                                     IPayloadContext context) {
        ClientNetGlue.onFarPlayerUpdatesFrame(payload.body());
    }

    public static void handleRegionSummaryPayload(
            dev.vox.lss.networking.payloads.RegionSummaryS2CPayload payload,
            IPayloadContext context) {
        ClientNetGlue.onRegionSummaryFrame(payload.body());
    }

    public static void handleColumnStampsPayload(
            dev.vox.lss.networking.payloads.ColumnStampsS2CPayload payload,
            IPayloadContext context) {
        ClientNetGlue.onColumnStampsFrame(payload.body());
    }
}
