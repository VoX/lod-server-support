package dev.vox.lss.paper;

import dev.vox.lss.common.HandshakeGate;
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

/**
 * Paper plugin entry point for LOD Server Support.
 * Registers Plugin Messaging channels, handles handshake/request lifecycle,
 * and ticks the request processing service on the server main thread.
 *
 * <p>The environment-free glue — enable-plan ordering ({@link #runEnablePlan}),
 * plugin-message dispatch containment ({@link #dispatchPluginMessage}), and the
 * handshake reply/registration wiring ({@link #handleHandshake(byte[], String,
 * PaperConfig, boolean, SessionConfigSender, HandshakeRegistrar)}) — is static and
 * package-private so it is testable without a Bukkit server; the instance methods
 * only bind the production environment.
 */
public class LSSPaperPlugin extends JavaPlugin implements PluginMessageListener, Listener {
    private PaperConfig lssConfig;
    private volatile PaperRequestProcessingService requestService;

    /**
     * The onEnable step set, in the order {@link #runEnablePlan} drives them. The
     * production implementation lives in {@link #onEnable}; the interface is a test
     * seam (the plan's step order and enabled gate are pinned without a Bukkit server,
     * and /reload re-runs the identical sequence).
     */
    interface EnableSteps {
        PaperConfig loadConfig();

        void registerChannels();

        void registerQuitListener();

        PaperRequestProcessingService startService(PaperConfig config);

        void registerWorldHandler(PaperRequestProcessingService service, PaperConfig config);

        void registerCommands();

        void scheduleServiceTick();

        void initSoakBridge();
    }

    /**
     * Executes the enable plan. Step order is load-bearing: config before channels
     * (handlers read it), service before the world handler (it feeds the service's
     * dirty tracker), and the soak bridge last so the driver sees a fully wired plugin.
     * /reload runs onDisable then onEnable, so this sequence is also the re-enable
     * contract.
     */
    static void runEnablePlan(EnableSteps steps) {
        var config = steps.loadConfig();

        // Register incoming channels (C2S)
        // Note: S2C packets are sent directly via NMS (bypassing Bukkit's
        // sendPluginMessage channel check), so no outgoing registration needed.
        steps.registerChannels();
        // Register event listener for player quit
        steps.registerQuitListener();

        // Start processing service
        var service = steps.startService(config);
        LSSLogger.info("Starting LSS LOD request processing service");

        // Register dirty chunk event listeners. enabled=false gates here (mirrors Fabric's
        // ChunkMapSaveHook gate): the service tick — and so the dirty-broadcast drain — is
        // disabled, so marking would grow the DirtyColumnTracker without bound for the whole
        // server run. enabled is immutable per run, so skipping registration is safe.
        if (config.enabled) {
            steps.registerWorldHandler(service, config);
        }

        // Register command
        steps.registerCommands();

        // Tick the processing service every server tick (50ms)
        steps.scheduleServiceTick();

        // Dev-only soak harness (no-op unless -Dlss.soak.scenario is set)
        steps.initSoakBridge();

        LSSLogger.info("LOD Server Support (Paper) enabled");
    }

    @Override
    public void onEnable() {
        runEnablePlan(new EnableSteps() {
            @Override
            public PaperConfig loadConfig() {
                lssConfig = PaperConfig.load(getDataFolder().toPath());
                return lssConfig;
            }

            @Override
            public void registerChannels() {
                getServer().getMessenger().registerIncomingPluginChannel(
                        LSSPaperPlugin.this, LSSConstants.CHANNEL_HANDSHAKE, LSSPaperPlugin.this);
                getServer().getMessenger().registerIncomingPluginChannel(
                        LSSPaperPlugin.this, LSSConstants.CHANNEL_CHUNK_REQUEST, LSSPaperPlugin.this);
            }

            @Override
            public void registerQuitListener() {
                getServer().getPluginManager().registerEvents(LSSPaperPlugin.this, LSSPaperPlugin.this);
            }

            @Override
            public PaperRequestProcessingService startService(PaperConfig config) {
                var nmsServer = ((CraftServer) getServer()).getServer();
                requestService = new PaperRequestProcessingService(nmsServer, LSSPaperPlugin.this, config);
                return requestService;
            }

            @Override
            public void registerWorldHandler(PaperRequestProcessingService service, PaperConfig config) {
                var worldHandler = new PaperWorldHandler(LSSPaperPlugin.this, service.getDirtyTracker());
                worldHandler.registerUpdateListeners(config.updateEvents);
            }

            @Override
            public void registerCommands() {
                var cmd = getCommand("lsslod");
                if (cmd != null) {
                    var executor = new PaperCommands(LSSPaperPlugin.this);
                    cmd.setExecutor(executor);
                    cmd.setTabCompleter(executor);
                }
            }

            @Override
            public void scheduleServiceTick() {
                // GlobalRegionScheduler, not BukkitScheduler: on Folia the legacy scheduler
                // throws UnsupportedOperationException; on plain Paper this runs on the main
                // thread every tick, exactly like the BukkitRunnable it replaces. The
                // global-region thread is the plugin's single pump — every single-owner
                // structure in the pipeline hangs off this cadence (Folia design spec §3).
                getServer().getGlobalRegionScheduler().runAtFixedRate(LSSPaperPlugin.this,
                        scheduledTask -> {
                            var service = requestService;
                            if (service != null) {
                                service.tick();
                            }
                        }, 1L, 1L);
            }

            @Override
            public void initSoakBridge() {
                PaperSoakBridge.init(LSSPaperPlugin.this);
            }
        });
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
        dispatchPluginMessage(channel, player.getName(), message,
                data -> handleHandshake(player, nmsPlayer, data),
                data -> handleBatchChunkRequest(nmsPlayer, data));
    }

    /** Test seam: a per-channel message handler; hostile-frame decodes may throw. */
    @FunctionalInterface
    interface PluginMessageHandler {
        void handle(byte[] message) throws Exception;
    }

    /**
     * Channel switch + exception containment for {@link #onPluginMessageReceived},
     * extracted so hostile-frame containment is testable without a CraftPlayer: one
     * malformed frame must be caught and logged — never propagate into Bukkit's
     * messenger — and later messages must still dispatch. Unknown channels are ignored.
     * Errors deliberately propagate (only Exception is contained).
     */
    static void dispatchPluginMessage(String channel, String playerName, byte[] message,
                                      PluginMessageHandler handshakeHandler,
                                      PluginMessageHandler chunkRequestHandler) {
        try {
            switch (channel) {
                case LSSConstants.CHANNEL_HANDSHAKE -> handshakeHandler.handle(message);
                case LSSConstants.CHANNEL_CHUNK_REQUEST -> chunkRequestHandler.handle(message);
            }
        } catch (Exception e) {
            LSSLogger.error("Error handling plugin message on channel " + channel + " from " + playerName, e);
        }
    }

    /**
     * Test seam: the session-config reply send, production-wired to
     * {@link PaperPayloadHandler#sendSessionConfig} for the handshaking player.
     */
    @FunctionalInterface
    interface SessionConfigSender {
        void send(int protocolVersion, boolean enabled, int lodDistanceChunks,
                  int syncOnLoadConcurrencyLimitPerPlayer,
                  int generationConcurrencyLimitPerPlayer, boolean generationEnabled);
    }

    /**
     * Test seam: player registration, production-wired to
     * {@link PaperRequestProcessingService#enqueueRegister} — on Folia the handshake message
     * arrives on the player's region thread, so registration is mailboxed and the pump
     * applies it next tick. Only invoked when the {@link HandshakeGate} decision says to
     * register, so the production lambda may capture a service reference that is non-null
     * whenever servicePresent was true.
     */
    @FunctionalInterface
    interface HandshakeRegistrar {
        void register(int capabilities);
    }

    private void handleHandshake(Player bukkitPlayer, ServerPlayer nmsPlayer, byte[] data) {
        var service = this.requestService;
        handleHandshake(data, nmsPlayer.getName().getString(), this.lssConfig, service != null,
                (protocolVersion, enabled, lodDistanceChunks, syncLimit, genLimit, generationEnabled) ->
                        PaperPayloadHandler.sendSessionConfig(bukkitPlayer, protocolVersion, enabled,
                                lodDistanceChunks, syncLimit, genLimit, generationEnabled),
                capabilities -> service.enqueueRegister(nmsPlayer, capabilities));
    }

    /**
     * Handshake decode → {@link HandshakeGate} → reply/registration glue, extracted
     * behind the sender/registrar seams so call-site obedience is testable. Contract:
     * a VERSION_MISMATCH decision sends NOTHING (any reply would kick the skewed
     * client — see {@link HandshakeGate.Outcome#VERSION_MISMATCH}); NO_CONSUMER
     * replies but never registers; the reply advertises the gate's effectiveEnabled
     * and wires each config field to its session-config slot.
     */
    static void handleHandshake(byte[] data, String playerName, PaperConfig config,
                                boolean servicePresent, SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        var handshake = PaperPayloadHandler.decodeHandshake(data);
        if (handshake == null) return;

        LSSLogger.info("LSS handshake received from " + playerName
                + " (protocol v" + handshake.protocolVersion()
                + ", capabilities=" + handshake.capabilities() + ")");

        var decision = HandshakeGate.evaluate(handshake.protocolVersion(),
                handshake.capabilities(), config.enabled, servicePresent);

        if (!decision.sendSessionConfig()) {
            // See HandshakeGate.Outcome.VERSION_MISMATCH: replying would kick the player.
            LSSLogger.warn("Player " + playerName
                    + " has incompatible LSS protocol version " + handshake.protocolVersion()
                    + " (server: " + LSSConstants.PROTOCOL_VERSION + "), skipping LOD distribution");
            return;
        }

        configSender.send(LSSConstants.PROTOCOL_VERSION,
                decision.effectiveEnabled(),
                config.lodDistanceChunks,
                config.syncOnLoadConcurrencyLimitPerPlayer,
                config.generationConcurrencyLimitPerPlayer,
                config.enableChunkGeneration);

        if (decision.outcome() == HandshakeGate.Outcome.NO_CONSUMER) {
            // Visible to admins via this log.
            LSSLogger.info("Player " + playerName
                    + " has no LOD consumer (caps=" + handshake.capabilities()
                    + "), skipping LOD registration");
            return;
        }

        if (decision.registerPlayer()) {
            registrar.register(handshake.capabilities());
            LSSLogger.info("Player " + playerName
                    + " registered for LSS LOD request processing (caps="
                    + handshake.capabilities() + ")");
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

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        var service = this.requestService;
        if (service != null) {
            // Mailboxed: on Folia this event fires on the quitting player's region thread,
            // and removal mutates pump-owned state (generation service maps among others).
            service.enqueueRemove(event.getPlayer().getUniqueId());
        }
    }

    public PaperRequestProcessingService getRequestService() {
        return this.requestService;
    }

    public PaperConfig getLssConfig() {
        return this.lssConfig;
    }
}
