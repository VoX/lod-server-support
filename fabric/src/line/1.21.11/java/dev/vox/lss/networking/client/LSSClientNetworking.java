// OVERLAY OF fabric/src/main/java/dev/vox/lss/networking/client/LSSClientNetworking.java @ 37482c338381b1e81cac9dc2b63fb23342c102fac6d272fd53a952b1de387dbd
// 1.21.11 line overlay (single-branch-consolidation-plan.md §3.2).
package dev.vox.lss.networking.client;

import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Fabric client-side networking glue: event + receiver REGISTRATION. Since N-3
 * the session state and every receiver/lifecycle body live in the loader-neutral
 * {@link ClientNetGlue} (xplat — shared verbatim with the NeoForge module); the
 * delegating statics here keep every pre-extraction caller and test signature
 * intact.
 */
public class LSSClientNetworking {

    public static boolean isServerEnabled() {
        return ClientNetGlue.isServerEnabled();
    }

    /** C6 observability: the established session's protocol version (0 pre-config). */
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

    /** See {@link ClientNetGlue#reportIngestFailure} — safe from any thread. */
    public static void reportIngestFailure(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        ClientNetGlue.reportIngestFailure(dimension, chunkX, chunkZ);
    }

    /** See {@link ClientNetGlue#reportUndispatchedColumns}. */
    static void reportUndispatchedColumns(LodRequestManager manager) {
        ClientNetGlue.reportUndispatchedColumns(manager);
    }

    /** See {@link ClientNetGlue#awaitDecodeIdle}. */
    static boolean awaitDecodeIdle(long timeoutMs) {
        return ClientNetGlue.awaitDecodeIdle(timeoutMs);
    }

    public static void triggerHostHandshake() {
        ClientNetGlue.triggerHostHandshake();
    }

    /** Delegate kept for the pinned truth table — see {@link ClientNetGlue#shouldDriveV16Generation}. */
    static boolean shouldDriveV16Generation(int protocolVersion, boolean generationOptIn) {
        return ClientNetGlue.shouldDriveV16Generation(protocolVersion, generationOptIn);
    }

    /** Delegate kept for the receiver-ladder tests — see {@link ClientNetGlue#handleVoxelColumn}. */
    static void handleVoxelColumn(LodRequestManager manager, ClientColumnProcessor processor,
                                  VoxelColumnS2CPayload payload) {
        ClientNetGlue.handleVoxelColumn(manager, processor, payload);
    }

    /** Delegate kept for the dispatch tests — see {@link ClientNetGlue#dispatchBatchResponses}. */
    static void dispatchBatchResponses(LodRequestManager manager, BatchResponseS2CPayload payload) {
        ClientNetGlue.dispatchBatchResponses(manager, payload);
    }

    public static void init() {
        registerPacketHandlers();
        registerConnectionLifecycle();
        registerTickHandler();
    }

    private static void registerPacketHandlers() {
        ClientPlayNetworking.registerGlobalReceiver(
                SessionConfigS2CPayload.TYPE,
                (payload, context) -> ClientNetGlue.onSessionConfigFrame(payload)
        );

        ClientPlayNetworking.registerGlobalReceiver(
                BatchResponseS2CPayload.TYPE,
                (payload, context) -> ClientNetGlue.onBatchResponseFrame(payload)
        );

        ClientPlayNetworking.registerGlobalReceiver(
                DirtyColumnsS2CPayload.TYPE,
                (payload, context) -> ClientNetGlue.onDirtyColumnsFrame(payload)
        );

        ClientPlayNetworking.registerGlobalReceiver(
                dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload.TYPE,
                (payload, context) -> ClientNetGlue.onFarPlayerRosterFrame(payload.body())
        );

        ClientPlayNetworking.registerGlobalReceiver(
                dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload.TYPE,
                (payload, context) -> ClientNetGlue.onFarPlayerUpdatesFrame(payload.body())
        );

        ClientPlayNetworking.registerGlobalReceiver(
                dev.vox.lss.networking.payloads.RegionSummaryS2CPayload.TYPE,
                (payload, context) -> ClientNetGlue.onRegionSummaryFrame(payload.body())
        );

        ClientPlayNetworking.registerGlobalReceiver(
                dev.vox.lss.networking.payloads.ColumnStampsS2CPayload.TYPE,
                (payload, context) -> ClientNetGlue.onColumnStampsFrame(payload.body())
        );

        ClientPlayNetworking.registerGlobalReceiver(
                VoxelColumnS2CPayload.TYPE,
                (payload, context) -> ClientNetGlue.onVoxelColumnFrame(payload)
        );
    }

    private static void registerConnectionLifecycle() {
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> ClientNetGlue.onJoin());

        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> ClientNetGlue.onDisconnect());
    }

    private static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientNetGlue.onEndClientTick());
        // The Xaero bridge's per-frame rebuild slice (plan §17). A world-render event
        // fires only while a world renders — exactly when map rebuilds matter; anywhere
        // it does not (loading screens, hidden window) the tick pump's fallback
        // rebuilds. PER-LINE: the event CLASS is line flavor (26.2 =
        // level.LevelRenderEvents; this line = world.WorldRenderEvents) — any
        // end-of-frame point works, the slice renders nothing (unlike surfaces row 15
        // there is no ordering invariant to verify on a port).
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
                .END_MAIN.register(context -> ClientNetGlue.onRenderFrame());
    }
}
