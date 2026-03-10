package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Paper plugin entry point for LOD Server Support.
 * Registers Plugin Messaging channels, handles handshake/request lifecycle,
 * and ticks the request processing service on the server main thread.
 */
public class LSSPaperPlugin extends JavaPlugin implements PluginMessageListener, Listener {
    private PaperConfig lssConfig;
    private volatile PaperRequestProcessingService requestService;

    @Override
    public void onEnable() {
        this.lssConfig = PaperConfig.load(getDataFolder().toPath());

        // Register incoming channels (C2S)
        // Note: S2C packets are sent directly via NMS (bypassing Bukkit's
        // sendPluginMessage channel check), so no outgoing registration needed.
        getServer().getMessenger().registerIncomingPluginChannel(this, LSSConstants.CHANNEL_HANDSHAKE, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, LSSConstants.CHANNEL_CHUNK_REQUEST, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, LSSConstants.CHANNEL_CANCEL_REQUEST, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, LSSConstants.CHANNEL_BANDWIDTH_UPDATE, this);
        // Register event listener for player quit
        getServer().getPluginManager().registerEvents(this, this);

        // Start processing service
        var nmsServer = ((CraftServer) getServer()).getServer();
        this.requestService = new PaperRequestProcessingService(nmsServer, this, this.lssConfig);
        LSSLogger.info("Starting LSS LOD request processing service");

        // Register dirty chunk event listeners
        var worldHandler = new PaperWorldHandler(this, this.requestService.getDirtyTracker());
        worldHandler.registerUpdateListeners(this.lssConfig.updateEvents);

        // Register command
        var cmd = getCommand("lsslod");
        if (cmd != null) {
            var executor = new PaperCommands(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // Tick the processing service every server tick (50ms)
        new BukkitRunnable() {
            @Override
            public void run() {
                var service = requestService;
                if (service != null) {
                    service.tick();
                }
            }
        }.runTaskTimer(this, 1L, 1L);

        LSSLogger.info("LOD Server Support (Paper) enabled");
    }

    @Override
    public void onDisable() {
        var service = this.requestService;
        if (service != null) {
            LSSLogger.info("Stopping LSS LOD request processing service");
            service.shutdown();
            this.requestService = null;
        }

        getServer().getMessenger().unregisterIncomingPluginChannel(this);

        LSSLogger.info("LOD Server Support (Paper) disabled");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        var service = this.requestService;
        if (service == null) return;
        if (message == null || message.length == 0) return;

        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();

        try {
            switch (channel) {
                case LSSConstants.CHANNEL_HANDSHAKE -> handleHandshake(player, nmsPlayer, message);
                case LSSConstants.CHANNEL_CHUNK_REQUEST -> handleBatchChunkRequest(nmsPlayer, message);
                case LSSConstants.CHANNEL_CANCEL_REQUEST -> handleCancelRequest(nmsPlayer, message);
                case LSSConstants.CHANNEL_BANDWIDTH_UPDATE -> handleBandwidthUpdate(nmsPlayer, message);
            }
        } catch (Exception e) {
            LSSLogger.error("Error handling plugin message on channel " + channel + " from " + player.getName(), e);
        }
    }

    private void handleHandshake(Player bukkitPlayer, ServerPlayer nmsPlayer, byte[] data) {
        var handshake = PaperPayloadHandler.decodeHandshake(data);
        if (handshake == null) return;

        LSSLogger.info("LSS handshake received from " + nmsPlayer.getName().getString()
                + " (protocol v" + handshake.protocolVersion()
                + ", capabilities=" + handshake.capabilities() + ")");

        boolean effectiveEnabled = this.lssConfig.enabled && this.requestService != null;

        int serverCaps = LSSConstants.CAPABILITY_VOXEL_COLUMNS;

        PaperPayloadHandler.sendSessionConfig(bukkitPlayer,
                LSSConstants.PROTOCOL_VERSION,
                effectiveEnabled,
                this.lssConfig.lodDistanceChunks,
                serverCaps,
                this.lssConfig.syncOnLoadRateLimitPerPlayer,
                this.lssConfig.syncOnLoadConcurrencyLimitPerPlayer,
                this.lssConfig.generationRateLimitPerPlayer,
                this.lssConfig.generationConcurrencyLimitPerPlayer,
                this.lssConfig.enableChunkGeneration,
                this.lssConfig.bytesPerSecondLimitPerPlayer);

        if (handshake.protocolVersion() != LSSConstants.PROTOCOL_VERSION) {
            LSSLogger.warn("Player " + nmsPlayer.getName().getString()
                    + " has incompatible LSS protocol version " + handshake.protocolVersion()
                    + " (server: " + LSSConstants.PROTOCOL_VERSION + "), skipping LOD distribution");
            return;
        }

        if (effectiveEnabled) {
            this.requestService.registerPlayer(nmsPlayer, handshake.capabilities());
            LSSLogger.info("Player " + nmsPlayer.getName().getString()
                    + " registered for LSS LOD request processing"
                    + (handshake.capabilities() != 0
                            ? " (caps=" + handshake.capabilities() + ")" : ""));
        }
    }

    private void handleBatchChunkRequest(ServerPlayer nmsPlayer, byte[] data) {
        var decoded = PaperPayloadHandler.decodeBatchChunkRequest(data);
        if (decoded == null) return;
        var service = this.requestService;
        if (service != null) {
            service.handleBatchRequest(nmsPlayer, decoded);
        }
    }

    private void handleCancelRequest(ServerPlayer nmsPlayer, byte[] data) {
        var decoded = PaperPayloadHandler.decodeCancelRequest(data);
        if (decoded == null) return;
        var service = this.requestService;
        if (service != null) {
            service.handleCancel(nmsPlayer, decoded.requestId());
        }
    }

    private void handleBandwidthUpdate(ServerPlayer nmsPlayer, byte[] data) {
        var decoded = PaperPayloadHandler.decodeBandwidthUpdate(data);
        if (decoded == null) return;
        var service = this.requestService;
        if (service != null) {
            service.handleBandwidthUpdate(nmsPlayer, decoded.desiredRate());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        var service = this.requestService;
        if (service != null) {
            service.removePlayer(event.getPlayer().getUniqueId());
        }
    }

    public PaperRequestProcessingService getRequestService() {
        return this.requestService;
    }

    public PaperConfig getLssConfig() {
        return this.lssConfig;
    }
}
