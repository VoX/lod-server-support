package dev.vox.lss.paper;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.HandshakeGate;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
import dev.vox.lss.common.PlayerServiceGate;
import dev.vox.lss.common.ServiceGateState;
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

    // lss:client_info sidecar facts (XVER §2.2): the client's MC data version, keyed by
    // UUID, swept at quit. Absence = legacy client (no sidecar channel). Consumed as
    // diagnostics + the C5 Via-guard input.
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Integer>
            CLIENT_DATA_VERSIONS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The client's announced MC data version, or null for a legacy client. */
    public static Integer clientDataVersion(java.util.UUID uuid) {
        return CLIENT_DATA_VERSIONS.get(uuid);
    }

    /**
     * Service-gate permission nodes, consulted only while {@link PaperConfig#requireServicePermission}
     * is on. BOTH spellings must be held — a negative grant on EITHER denies (see
     * {@link #holdsServicePermission}). The dual declaration in plugin.yml is load-bearing for the
     * same reason as the far-player privacy nodes (see PaperFarPlayerSnapshots): Bukkit resolves
     * an UNDECLARED node to the op default, so a single-brand declaration paired with a
     * cross-brand check would silently deny (or silently admit) every op on the jar that lacks
     * the spelling — and declaring both keeps an operator's grant alive across an LSS&lt;-&gt;VSS
     * jar swap. Both are declared {@code default: true} (user decision, 2026-08-25): arming
     * the gate alone therefore changes nothing for anyone, and a denial is always an explicit
     * negative grant an admin made in their permission plugin.
     */
    static final String PERMISSION_SERVICE_LSS = dev.vox.lss.common.LSSPermissions.SERVICE_LSS;
    /** The VSS spelling of {@link #PERMISSION_SERVICE_LSS} — see there. */
    static final String PERMISSION_SERVICE_VSS = dev.vox.lss.common.LSSPermissions.SERVICE_VSS;


    /** The pre-gate overloads' gate (see {@link PlayerServiceGate#OPEN} — the seam
     *  interface and the open-gate landmine doc live in common now, shared with the
     *  Fabric/NeoForge glue). */
    static final PlayerServiceGate SERVICE_GATE_OPEN = PlayerServiceGate.OPEN;

    /** The production {@link PlayerServiceGate}: a real Bukkit permission read (CONTAINED
     *  — a throwing permissible answers TRUE with a once-warn: fail-open, serve; a throw
     *  must never escape into handshake silence), the log latch + denied-handshake memo on
     *  the service's {@link ServiceGateState}, and the denial hook marshaling the
     *  unregistration composite onto the pump. Extracted static so both halves are
     *  pinnable against a mock Player — a hard-coded {@code true} here would make the
     *  whole feature inert on a live server while every core test stayed green.
     *
     *  @param state                 the owning service's gate state, or null when no
     *                               service exists (the conjunction then never deposits)
     *  @param deniedWhileRegistered the pump-marshaled unregistration composite
     *                               (enqueueServiceGateUnregister), or null */
    static PlayerServiceGate serviceGateFor(Player bukkitPlayer, java.util.UUID uuid,
                                            String playerName, ServiceGateState state,
                                            Runnable deniedWhileRegistered) {
        return serviceGateFor(bukkitPlayer, uuid, playerName, state, deniedWhileRegistered, null);
    }

    /** @param mapPolicyNotice the map policy's chat line, pump-marshaled (the service
     *                        claims its once-per-connection latch there), or null */
    static PlayerServiceGate serviceGateFor(Player bukkitPlayer, java.util.UUID uuid,
                                            String playerName, ServiceGateState state,
                                            Runnable deniedWhileRegistered,
                                            Runnable mapPolicyNotice) {
        return serviceGateFor(bukkitPlayer, uuid, playerName, state, deniedWhileRegistered,
                mapPolicyNotice, false);
    }

    /** @param replay the grant sweep's re-offer: the chat line goes out only when the
     *                refusal is a MAP_POLICY transition (review fold A6 — a player
     *                permission-denied first and granted while the policy is armed has
     *                never seen it; the per-connection latch prevents repeats) */
    static PlayerServiceGate serviceGateFor(Player bukkitPlayer, java.util.UUID uuid,
                                            String playerName, ServiceGateState state,
                                            Runnable deniedWhileRegistered,
                                            Runnable mapPolicyNotice, boolean replay) {
        return new PlayerServiceGate() {
            @Override
            public boolean hasPermission(String node) {
                try {
                    return bukkitPlayer.hasPermission(node);
                } catch (Throwable e) {
                    if (e instanceof VirtualMachineError vme) throw vme;
                    if (PERMISSIBLE_THROW_WARNED.compareAndSet(false, true)) {
                        LSSLogger.warn("Bukkit permission read threw for " + playerName
                                + " on " + node + " — serving (fail-open; the gate is not"
                                + " a security boundary): " + e);
                    }
                    return true;
                }
            }

            @Override
            public boolean claimDenialLog() {
                return state != null && state.claimDenialLog(uuid);
            }

            @Override
            public void onServiceDenied(int protocolVersion, int capabilities) {
                if (state == null) return; // unreachable: the conjunction requires servicePresent
                state.rememberDenied(uuid, playerName, protocolVersion, capabilities);
                if (deniedWhileRegistered != null) deniedWhileRegistered.run();
            }

            @Override
            public boolean onMapPolicyRefused(int protocolVersion, int capabilities) {
                if (state == null) return false; // no service: nothing to remember
                // Map-policy plan A3.2 (the xplat twin's order): memo deposit with reason
                // MAP_POLICY, the chat line (pump), then the composite (pump) for a live
                // session re-handshaking while the policy is armed.
                boolean transition = state.rememberDenied(uuid, playerName, protocolVersion,
                        capabilities, ServiceGateState.REASON_MAP_POLICY);
                if (mapPolicyNotice != null && (!replay || transition)) mapPolicyNotice.run();
                if (deniedWhileRegistered != null) deniedWhileRegistered.run();
                return transition;
            }

            /** Read once per handshake (map-ingestion permission plan §3.1/§3.2): the
             *  decision, the deferred reply and the pump's Register drain all see the same
             *  answer, so a refusal at the drain and the reply's enabled flag can never
             *  disagree. */
            private volatile Boolean exemptOnce;

            @Override
            public boolean mapIngestionExempt() {
                Boolean memo = this.exemptOnce;
                if (memo == null) {
                    // FAIL-CLOSED, unlike hasPermission above: either spelling (default
                    // false in plugin.yml); a throwing permissible (a Folia read race) is
                    // not exempt.
                    memo = dev.vox.lss.common.MapPolicy.holdsExemption(bukkitPlayer::hasPermission);
                    this.exemptOnce = memo;
                }
                return memo;
            }
        };
    }

    /** Once-per-JVM warn latch for a throwing Bukkit permissible (CAS — on Folia the
     *  handshake read and the pump sweep can race; test seam resets). */
    private static final java.util.concurrent.atomic.AtomicBoolean PERMISSIBLE_THROW_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Test seam. */
    static void resetPermissibleThrowWarnedForTest() {
        PERMISSIBLE_THROW_WARNED.set(false);
    }

    /** Whether the player clears the service gate — BOTH brand spellings, AND not OR
     *  (the De Morgan mirror of the far-player privacy nodes' grant-model OR; the full
     *  rationale lives on {@link PlayerServiceGate#holdsService}, shared with the
     *  Fabric/NeoForge glue). */
    static boolean holdsServicePermission(PlayerServiceGate gate) {
        return PlayerServiceGate.holdsService(gate);
    }

    /**
     * The onEnable step set, in the order {@link #runEnablePlan} drives them. The
     * production implementation lives in {@link #onEnable}; the interface is a test
     * seam (the plan's step order and enabled gate are pinned without a Bukkit server,
     * and /reload re-runs the identical sequence).
     */
    interface EnableSteps {
        void loadBranding();

        /** The version guard (paper-version-guard-store-plan §3.1): false = refused, the
         *  plugin has already disabled itself and nothing else may run. */
        boolean checkMinecraftVersion();

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
        // FIRST: resolve display branding before any service/thread/log line is created.
        steps.loadBranding();
        // SECOND: refuse a server this jar was not built for (paper-version-guard-store-plan
        // §3.1) before any channel, config file or store exists. Paper only enforces
        // api-version's floor, and a 26.2 jar on 26.3 read old chunks as air and stored them.
        if (!steps.checkMinecraftVersion()) return;
        var config = steps.loadConfig();

        // Register incoming channels (C2S)
        // Note: S2C packets are sent directly via NMS (bypassing Bukkit's
        // sendPluginMessage channel check), so no outgoing registration needed.
        steps.registerChannels();
        // Register event listener for player quit
        steps.registerQuitListener();

        // Start processing service
        var service = steps.startService(config);
        LSSLogger.info("Starting " + Brand.shortName() + " LOD request processing service");

        // Register dirty chunk event listeners. enabled=false gates here (mirrors Fabric's
        // ChunkSaveDataHook gate): the service tick — and so the dirty-broadcast drain — is
        // disabled, so marking would grow the DirtyColumnTracker without bound for the whole
        // server run. enabled is immutable per run, so skipping registration is safe.
        if (config.enabled()) {
            steps.registerWorldHandler(service, config);
        }

        // Register command
        steps.registerCommands();

        // Tick the processing service every server tick (50ms)
        steps.scheduleServiceTick();

        // Dev-only soak harness (no-op unless -Dlss.soak.scenario is set)
        steps.initSoakBridge();

        LSSLogger.info(Brand.displayName() + " (Paper) enabled");
    }

    @Override
    public void onEnable() {
        runEnablePlan(new EnableSteps() {
            @Override
            public void loadBranding() {
                Brand.load(getClassLoader());
            }

            @Override
            public boolean checkMinecraftVersion() {
                String refusal = minecraftVersionRefusal(readMinecraftDependency(),
                        getServer().getMinecraftVersion(), getDescription().getName(),
                        getDescription().getWebsite());
                if (refusal == null) return true;
                LSSLogger.error(refusal);
                getServer().getPluginManager().disablePlugin(LSSPaperPlugin.this);
                return false;
            }

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
                getServer().getMessenger().registerIncomingPluginChannel(
                        LSSPaperPlugin.this, LSSConstants.CHANNEL_CLIENT_INFO, LSSPaperPlugin.this);
                getServer().getMessenger().registerIncomingPluginChannel(
                        LSSPaperPlugin.this, LSSConstants.CHANNEL_FAR_PLAYER_PREFS, LSSPaperPlugin.this);
                getServer().getMessenger().registerIncomingPluginChannel(
                        LSSPaperPlugin.this, LSSConstants.CHANNEL_REGION_SUMMARY_REQ, LSSPaperPlugin.this);
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
                worldHandler.registerUpdateListeners(config.updateEvents());
            }

            @Override
            public void registerCommands() {
                // Read the declared command name from plugin.yml rather than hardcoding it,
                // so the Voxy Server Side repackage (which rewrites plugin.yml's command key
                // lsslod -> vsslod) registers its executor without a code fork. The plugin
                // declares exactly one command.
                var cmdName = getDescription().getCommands().keySet().stream().findFirst().orElse(null);
                var cmd = cmdName == null ? null : getCommand(cmdName);
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

    /** The plugin.yml key carrying the line's minecraft_dependency (build-time templated). */
    static final String MINECRAFT_DEPENDENCY_KEY = "lss-minecraft-dependency";

    /** The guard's rule from this jar's own plugin.yml ({@code PluginDescriptionFile} drops
     *  unknown keys). Null when absent or unreadable — the guard then refuses. */
    private String readMinecraftDependency() {
        try (var in = getResource("plugin.yml")) {
            if (in == null) return null;
            var yml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            return yml.getString(MINECRAFT_DEPENDENCY_KEY);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The version guard's decision (paper-version-guard-store-plan §3.1): null when
     * {@code running} satisfies the jar's {@code minecraft_dependency} rule, otherwise the one
     * ERROR line to log before disabling. A missing or malformed rule refuses too: that is a
     * broken build, and enabling unguarded is exactly the failure this guard exists for.
     */
    static String minecraftVersionRefusal(String rule, String running, String pluginName,
                                          String website) {
        String where = website == null || website.isBlank() ? "" : " from " + website;
        dev.vox.lss.common.MinecraftVersionRule parsed;
        try {
            parsed = dev.vox.lss.common.MinecraftVersionRule.parse(rule);
        } catch (IllegalArgumentException e) {
            return pluginName + " cannot tell which Minecraft version this jar was built for"
                    + " (plugin.yml " + MINECRAFT_DEPENDENCY_KEY + " is "
                    + (rule == null ? "missing" : "'" + rule + "'") + "), so it is disabling"
                    + " itself. Download the " + pluginName + " build for Minecraft " + running
                    + where + " if one exists.";
        }
        if (parsed.accepts(running)) return null;
        // The supported versions come first; the download advice is conditional, because
        // not every Minecraft version has a build (e.g. no 0.20.0 Paper build for 1.21.4).
        return pluginName + " supports Minecraft " + parsed.describe() + ". This server runs"
                + " Minecraft " + running + ", so " + pluginName + " is disabling itself (a jar"
                + " for another version can misread your world and fill the LOD store with"
                + " bad data). Download the " + pluginName + " build for Minecraft " + running
                + where + " if one exists.";
    }

    @Override
    public void onDisable() {
        if (this.lssConfig != null) this.lssConfig.close();
        // Null the field BEFORE shutting down so the next pump fire no-ops — a runtime
        // plugin-manager disable can arrive from a region thread while the pump is mid-tick
        // (the service's shuttingDown flag covers the one already-in-flight tick).
        var service = this.requestService;
        this.requestService = null;
        // Static sidecar facts must not survive /reload or a plugin-manager disable —
        // players who quit while disabled would leak entries forever (review C1-9).
        CLIENT_DATA_VERSIONS.clear();
        // Unregister the channels BEFORE shutdown (2026-08-05 review H5): a frame already
        // dispatched into onPluginMessageReceived proceeds with its captured service
        // reference (nothing can stop it; everything it touches is individually
        // thread-safe), but unregistering first means no NEW frame can dispatch
        // concurrently with the teardown below.
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        if (service != null) {
            LSSLogger.info("Stopping " + Brand.shortName() + " LOD request processing service");
            service.shutdown();
        }

        LSSLogger.info(Brand.displayName() + " (Paper) disabled");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        var service = this.requestService;
        if (service == null) return;
        if (message == null || message.length == 0) return;

        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        dispatchPluginMessage(channel, player.getName(), message,
                data -> handleHandshake(player, nmsPlayer, data),
                data -> handleBatchChunkRequest(nmsPlayer, data),
                data -> CLIENT_DATA_VERSIONS.put(nmsPlayer.getUUID(),
                        PaperPayloadHandler.decodeClientInfo(data)),
                // Far players (E1): decode on the messenger thread (pure), apply on the
                // PUMP via the runtime-task queue — drained AFTER the lifecycle mailbox,
                // so a prefs frame racing its own Register lands post-registration and
                // the broadcast core's single-threaded contract holds on Folia too.
                data -> {
                    var prefs = dev.vox.lss.common.farplayers.FarPlayerWire.decodePrefs(data);
                    // Dropped on the pump when this connection's player has gone meanwhile
                    // (the quit sets the flag before it enqueues its Remove).
                    service.enqueueFarPlayerPrefs(nmsPlayer.getUUID(), prefs, nmsPlayer::hasDisconnected);
                },
                // Region summaries (P2 §5): decode on the messenger thread (pure — a
                // Folia region thread is fine, no entity access), offer into the
                // latest-wins mailbox; the pump reads player state at admission. The
                // kill switch is HANDLER-checked; boot-set in practice (not in the
                // /lsslod set registry — a flip needs a restart).
                data -> service.handleRegionSummaryRequest(nmsPlayer.getUUID(), data));
    }

    /** Test seam: a per-channel message handler; hostile-frame decodes may throw. */
    @FunctionalInterface
    interface PluginMessageHandler {
        void handle(byte[] message) throws Exception;
    }

    /** Contained hostile-frame ERROR rate limit. Any authenticated client can spam malformed
     *  frames at packet rate on these channels, and an unthrottled stack trace per frame is a
     *  log-flood vector (Fabric self-limits — a bad codec decode kicks the client; Plugin
     *  Messaging has no equivalent). First frame logs immediately with the stack; later ones
     *  aggregate into a suppressed count released at most once per interval. Package-visible
     *  and swappable so the glue tests' one-ERROR-row containment pins stay deterministic. */
    static volatile LogThrottle hostileFrameLog = new LogThrottle(60_000);

    /**
     * Channel switch + exception containment for {@link #onPluginMessageReceived},
     * extracted so hostile-frame containment is testable without a CraftPlayer: one
     * malformed frame must be caught and logged — never propagate into Bukkit's
     * messenger — and later messages must still dispatch. Unknown channels are ignored.
     * Errors deliberately propagate (only Exception is contained).
     */
    static void dispatchPluginMessage(String channel, String playerName, byte[] message,
                                      PluginMessageHandler handshakeHandler,
                                      PluginMessageHandler chunkRequestHandler,
                                      PluginMessageHandler clientInfoHandler,
                                      PluginMessageHandler farPlayerPrefsHandler,
                                      PluginMessageHandler regionSummaryReqHandler) {
        try {
            switch (channel) {
                case LSSConstants.CHANNEL_HANDSHAKE -> handshakeHandler.handle(message);
                case LSSConstants.CHANNEL_CHUNK_REQUEST -> chunkRequestHandler.handle(message);
                case LSSConstants.CHANNEL_CLIENT_INFO -> clientInfoHandler.handle(message);
                case LSSConstants.CHANNEL_FAR_PLAYER_PREFS -> farPlayerPrefsHandler.handle(message);
                case LSSConstants.CHANNEL_REGION_SUMMARY_REQ -> regionSummaryReqHandler.handle(message);
            }
        } catch (Exception e) {
            long released = hostileFrameLog.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (released > 0) {
                String suffix = released > 1 ? " (+" + (released - 1) + " more suppressed)" : "";
                LSSLogger.error("Error handling plugin message on channel " + channel
                        + " from " + playerName + suffix, e);
            }
        }
    }

    /**
     * Test seam: the session-config reply send, production-wired to
     * {@link PaperPayloadHandler#sendSessionConfig} (CURRENT echoes {@code PROTOCOL_VERSION};
     * the V18 dialect echoes 18 on the same 4-field layout — the old client's gate
     * hard-requires its own version) or {@link PaperPayloadHandler#sendSessionConfigV16}
     * (V16 dialect — the legacy 6-field layout echoing protocol 16; the caps are the old
     * client's pacing) for the handshaking player.
     */
    @FunctionalInterface
    interface SessionConfigSender {
        /** @param capabilities    the handshake's capabilities — the production sender reads
         *                        the map policy at SEND time and appends the flags field only
         *                        for a v20 session that declared the bit (map-policy plan A2)
         *  @param keepDialectMark a QUIET map-policy refusal (a legacy rung WITH the bit — a
         *                        0.19 client's raced discovery rung): the reply must not shed
         *                        the live session's dialect mark (review fold A8) */
        void send(HandshakeGate.WireDialect dialect, boolean enabled, int lodDistanceChunks,
                  int syncCap, int genCap, boolean generationEnabled, int capabilities,
                  boolean keepDialectMark);

        /** The same reply with the reason it says "not here" (0.23.0 plan §3): one
         *  {@code SESSION_FLAG_OFF_*} bit or 0. The handshake core calls this form. A sender
         *  that only records the answer keeps implementing the form above; the production
         *  sender is built with {@link #reasoned} and puts the reason on the wire. */
        default void send(HandshakeGate.WireDialect dialect, boolean enabled, int offReason,
                          int lodDistanceChunks, int syncCap, int genCap, boolean generationEnabled,
                          int capabilities, boolean keepDialectMark) {
            send(dialect, enabled, lodDistanceChunks, syncCap, genCap, generationEnabled, capabilities,
                    keepDialectMark);
        }

        /** A sender whose one body takes the reason. */
        static SessionConfigSender reasoned(ReasonedSessionConfigSender body) {
            return new SessionConfigSender() {
                @Override
                public void send(HandshakeGate.WireDialect dialect, boolean enabled, int lodDistanceChunks,
                                 int syncCap, int genCap, boolean generationEnabled, int capabilities,
                                 boolean keepDialectMark) {
                    body.send(dialect, enabled, 0, lodDistanceChunks, syncCap, genCap, generationEnabled,
                            capabilities, keepDialectMark);
                }
                @Override
                public void send(HandshakeGate.WireDialect dialect, boolean enabled, int offReason,
                                 int lodDistanceChunks, int syncCap, int genCap, boolean generationEnabled,
                                 int capabilities, boolean keepDialectMark) {
                    body.send(dialect, enabled, offReason, lodDistanceChunks, syncCap, genCap,
                            generationEnabled, capabilities, keepDialectMark);
                }
            };
        }
    }

    /** The production handshake reply: {@link SessionConfigSender} with the reason. */
    @FunctionalInterface
    interface ReasonedSessionConfigSender {
        void send(HandshakeGate.WireDialect dialect, boolean enabled, int offReason, int lodDistanceChunks,
                  int syncCap, int genCap, boolean generationEnabled, int capabilities,
                  boolean keepDialectMark);
    }

    /**
     * Test seam: player registration, production-wired to
     * {@link PaperRequestProcessingService#enqueueRegister} — on Folia the handshake message
     * arrives on the player's region thread, so registration is mailboxed and the pump
     * applies it next tick. EVERY dialect identity mark (v16 and v18 alike) rides that
     * mailbox too, as the {@link #dialectFlipFor} runnable the pump runs during its
     * lifecycle drain — never directly from the region thread. (This javadoc used to
     * describe a pre-round-3 design where V16 marked its session identity directly; that
     * direct mark IS the hard-kick race the round-3 review fixed — a column egress
     * deciding its wire shape off a half-published identity — so do not reintroduce it.
     * 2026-08-05 review D1.) Only invoked when the {@link HandshakeGate} decision says to
     * register, so the production lambda may capture a service reference that is non-null
     * whenever servicePresent was true.
     */
    @FunctionalInterface
    interface HandshakeRegistrar {
        /** {@code replyAfterRegister} MUST run after the registration is applied (the pump's
         *  lifecycle drain in production) — never inline before it; see enqueueRegister. */
        void register(int capabilities, HandshakeGate.WireDialect dialect, Runnable replyAfterRegister);
    }

    private void handleHandshake(Player bukkitPlayer, ServerPlayer nmsPlayer, byte[] data) {
        handleHandshake(bukkitPlayer, nmsPlayer, data, false);
    }

    /** @param replay the grant sweep's re-offer: a map-policy refusal sends the chat line
     *                only on a MAP_POLICY transition (review fold A6) */
    private void handleHandshake(Player bukkitPlayer, ServerPlayer nmsPlayer, byte[] data,
                                 boolean replay) {
        var service = this.requestService;
        // R4 (Folia review 2026-08-27): every handshake stamps its connection's epoch
        // BEFORE any gate/summary state can be written for it — the late mailboxed
        // Remove of a PREVIOUS connection compares against this and skips its
        // region-thread-state belts for a fast rejoiner.
        if (service != null) service.markConnection(nmsPlayer.getUUID());
        // XVER §7: capture Via's answer once per handshake (the && keeps a disabled
        // guard from ever triggering probe resolution); the pure seam applies the rule.
        int viaProtocol = this.lssConfig.enableViaMismatchGuard()
                ? dev.vox.lss.common.compat.ViaProbe.playerProtocol(nmsPlayer.getUUID())
                : dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL;
        // Ticket #6: the per-player service gate. Read inline on this thread (region
        // thread on Folia) — no scheduling, and an online player's permissible is
        // safe to read there, the same shape PaperFarPlayerSnapshots already uses.
        var serviceGate = serviceGateFor(bukkitPlayer, nmsPlayer.getUUID(),
                        nmsPlayer.getName().getString(),
                        service == null ? null : service.getServiceGateState(),
                        service == null ? null
                                : () -> service.enqueueServiceGateUnregister(nmsPlayer.getUUID()),
                        // Map-policy plan A3.3: the chat line goes out on the pump (where
                        // the revocation push already sends from); from a replay only on a
                        // MAP_POLICY transition. The service skips a player the player list
                        // no longer holds (quit or replaced before the pump drained, A7).
                        service == null ? null
                                : () -> service.enqueueRuntimeTask(
                                        () -> service.noticeMapPolicyRefusal(nmsPlayer)),
                        replay);
        handleHandshake(data, nmsPlayer.getName().getString(), nmsPlayer, this.lssConfig, service != null,
                viaProtocol, net.minecraft.SharedConstants.getProtocolVersion(),
                serviceGate,
                SessionConfigSender.reasoned((dialect, enabled, offReason, lodDistanceChunks, syncCap, genCap,
                 generationEnabledByConfig, capabilities, keepDialectMark) -> {
                    boolean generationEnabled = service != null
                            ? service.generationEnabledForSession() : generationEnabledByConfig;
                    // A cross-dialect re-handshake sheds the stale compat identities it is
                    // NOT — otherwise columns keep shipping the old dialect's shape and
                    // hard-kick the re-armed decoder. Placed on the sender seam because it
                    // fires for every replying outcome (REGISTER and NO_CONSUMER/DISABLED
                    // alike); reply-only sheds run inline on the region thread, an
                    // inherited accepted residual (v18-compat design §2.3).
                    if (service != null) {
                        if (dialect != HandshakeGate.WireDialect.V16) {
                            service.getV16CompatManager().onNonV16Handshake(nmsPlayer.getUUID());
                        }
                        // Reply-only outcomes shed a stale CROSS-dialect membership; a
                        // REGISTER's mark happens on the pump via dialectFlipFor. A QUIET
                        // map-policy refusal keeps the live mark (review fold A8).
                        if (!keepDialectMark) {
                            service.getDialectTracker().onNonRegisterHandshake(
                                    nmsPlayer.getUUID(), dialect);
                        }
                    }
                    if (dialect == HandshakeGate.WireDialect.V16) {
                        PaperPayloadHandler.sendSessionConfigV16(bukkitPlayer, enabled,
                                lodDistanceChunks, syncCap, genCap, generationEnabled);
                    } else {
                        // Map-policy plan A2: decided at SEND time — for a registering
                        // handshake that is the pump's Register drain — per player (the
                        // gate's exemption read, once per handshake).
                        boolean denyMapIngestion = dev.vox.lss.common.MapPolicy.decide(
                                this.lssConfig.requestNoMapIngestion(),
                                serviceGate::mapIngestionExempt).deny();
                        int version = sessionConfigVersionFor(dialect);
                        PaperPayloadHandler.sendSessionConfig(bukkitPlayer,
                                version,
                                enabled, offReason, lodDistanceChunks, generationEnabled,
                                capabilities, denyMapIngestion);
                        if (service != null) {
                            // The last flags this session was sent (map-ingestion permission
                            // plan §3.3; a no-op unless the frame carries the field).
                            service.getServiceGateState().noteMapFlagsSent(nmsPlayer.getUUID(),
                                    version, capabilities, denyMapIngestion);
                        }
                    }
                }),
                (capabilities, dialect, replyAfterRegister) -> {
                    // The dialect flip runs on the PUMP, immediately before registerPlayer,
                    // via the mailbox's pre-register hook. Both halves of that placement are
                    // load-bearing:
                    //   * BEFORE registerPlayer, because that is where
                    //     wantsCompressedColumns is derived from isV16(). Doing it after —
                    //     which is what the sender seam amounts to for a REGISTER outcome,
                    //     since the drain runs replyAfterRegister last — left a v16 ->
                    //     current re-handshake running its whole session uncompressed.
                    //   * ON THE PUMP, because on Folia the handshake arrives on a REGION
                    //     thread. A flip applied there takes effect instantly while the
                    //     SessionConfig that re-arms the client's decoder waits for the next
                    //     drain, so the remainder of that tick's flush could ship
                    //     new-dialect columns to a decoder still armed for the old one — a
                    //     malformed frame, and a disconnect. (Round-3 review; the first
                    //     bullet's fix originally introduced the second bullet's race.)
                    // All directions go through the hook so none can drift off-pump: each
                    // dialect marks its own identity and sheds the other's (the v18
                    // membership especially must NEVER be marked on the region thread —
                    // v18-compat design §2.3, review F1).
                    service.enqueueRegister(nmsPlayer, capabilities,
                            dialectFlipFor(dialect, service.getV16CompatManager(),
                                    service.getDialectTracker(), nmsPlayer.getUUID()),
                            replyAfterRegister,
                            // Review fold A10: the drain re-checks the map policy — with
                            // this handshake's exemption read (the same answer the reply sees).
                            dialect, serviceGate::mapIngestionExempt);
                });
    }

    /** The SessionConfig version echo per dialect (v18-compat design §2.4): the V18
     *  dialect echoes 18 on the CURRENT 4-field layout — the v0.8.x client's gate
     *  hard-requires its own version and self-disables on 19. Extracted static so the
     *  literal is pinnable (execution-review finding 1: the production lambda sat one
     *  seam above every test, and a silent V18->PROTOCOL_VERSION regression compiled
     *  clean). V16 never reaches this — it takes the 6-field legacy sender. */
    static int sessionConfigVersionFor(HandshakeGate.WireDialect dialect) {
        return switch (dialect) {
            case V18 -> LSSConstants.V18_COMPAT_PROTOCOL_VERSION;
            case V19 -> LSSConstants.V19_COMPAT_PROTOCOL_VERSION;
            case V16, CURRENT -> LSSConstants.PROTOCOL_VERSION;
        };
    }

    /** The pump-deferred dialect flip: mark the session's dialect in the single-map
     *  tracker (any cross-dialect shed is the overwrite itself) and create/shed the v16
     *  manager's ingress-shim session. Extracted static so the BODY is pinnable against
     *  real manager/tracker instances (execution-review finding 1: dropping the V18
     *  case's mark — which mis-derives wantsCompressedColumns and leaks the codec byte
     *  to every v0.8.x client — passed the whole suite). */
    static Runnable dialectFlipFor(HandshakeGate.WireDialect dialect,
                                   dev.vox.lss.common.compat.V16CompatManager v16,
                                   dev.vox.lss.common.compat.WireDialectTracker dialects,
                                   java.util.UUID uuid) {
        return () -> {
            dialects.onHandshake(uuid, dialect);
            if (dialect == HandshakeGate.WireDialect.V16) {
                // Session identity first, so drip batches merge from the first frame.
                v16.onHandshake(uuid);
            } else {
                v16.onNonV16Handshake(uuid);
            }
        };
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
        handleHandshake(data, playerName, null, config, servicePresent, configSender, registrar);
    }

    static void handleHandshake(byte[] data, String playerName, ServerPlayer nmsPlayer,
                                PaperConfig config, boolean servicePresent,
                                SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        // No-Via-signal overload: every pre-C5 glue pin rides this unchanged. The
        // native slot gets 0 — a value no real MC protocol can be — never an LSS
        // protocol number (review m6: pairing NO_SIGNAL with PROTOCOL_VERSION was a
        // landmine one edit from denying everyone via 20 != <real via protocol>).
        handleHandshake(data, playerName, nmsPlayer, config, servicePresent,
                dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0,
                configSender, registrar);
    }

    /** Pre-service-gate overload: rides {@link #SERVICE_GATE_OPEN}, i.e. the behavior of
     *  every build before {@code requireServicePermission} existed. Test/legacy only — the
     *  production call site passes a real {@link #serviceGateFor} gate. */
    static void handleHandshake(byte[] data, String playerName, PaperConfig config,
                                boolean servicePresent, int viaProtocol, int nativeProtocol,
                                SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        handleHandshake(data, playerName, null, config, servicePresent, viaProtocol, nativeProtocol,
                configSender, registrar);
    }

    static void handleHandshake(byte[] data, String playerName, ServerPlayer nmsPlayer,
                                PaperConfig config, boolean servicePresent, int viaProtocol,
                                int nativeProtocol, SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        handleHandshake(data, playerName, nmsPlayer, config, servicePresent, viaProtocol,
                nativeProtocol, SERVICE_GATE_OPEN, configSender, registrar);
    }

    static void handleHandshake(byte[] data, String playerName, PaperConfig config,
                                boolean servicePresent, int viaProtocol, int nativeProtocol,
                                PlayerServiceGate serviceGate,
                                SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        handleHandshake(data, playerName, null, config, servicePresent, viaProtocol, nativeProtocol,
                serviceGate, configSender, registrar);
    }

    static void handleHandshake(byte[] data, String playerName, ServerPlayer nmsPlayer,
                                PaperConfig config, boolean servicePresent, int viaProtocol,
                                int nativeProtocol, PlayerServiceGate serviceGate,
                                SessionConfigSender configSender,
                                HandshakeRegistrar registrar) {
        var handshake = PaperPayloadHandler.decodeHandshake(data);
        if (handshake == null) return;

        LSSLogger.info(Brand.shortName() + " handshake received from " + playerName
                + " (protocol v" + handshake.protocolVersion()
                + ", capabilities=" + handshake.capabilities() + ")");

        // Per-player service gate. It rides the SAME input the server-wide kill switch uses,
        // so a denied player takes the already-pinned DISABLED path verbatim: a SessionConfig
        // advertising enabled=false (in the client's OWN dialect) and no registration. That
        // shape — not silence — is deliberate: silence is the version-skew signal and sends
        // the client's discovery ladder into its retry rungs, while enabled=false is the
        // clean disarm ClientSessionGate already implements. Evaluated BEFORE the ladder
        // because the ladder is pure; the log below waits until the reply-bearing rungs.
        boolean serviceDenied = config.requireServicePermission()
                && !holdsServicePermission(serviceGate);

        // Map-policy plan A3.2: the MAP_POLICY_REFUSED rung (DISABLED < it < REGISTER),
        // per player (map-ingestion permission plan §3.2): an exempt player is served.
        boolean denyMapIngestion = dev.vox.lss.common.MapPolicy.decide(
                config.requestNoMapIngestion(), serviceGate::mapIngestionExempt).deny();
        var decision = HandshakeGate.evaluate(handshake.protocolVersion(),
                handshake.capabilities(), config.enabled() && !serviceDenied, servicePresent,
                config.enableV16Compat(), config.enableV18Compat(), config.enableV19Compat(),
                dev.vox.lss.common.compat.ViaProbe.isMismatch(viaProtocol, nativeProtocol),
                denyMapIngestion);

        if (decision.outcome() == HandshakeGate.Outcome.VIA_MISMATCH) {
            // Silent deny — "Minecraft protocol" to keep the number space distinct
            // from the LSS protocol the handshake INFO above prints (review m5). Like
            // VERSION_MISMATCH below, an EXISTING registration deliberately survives
            // (review m1): the reachable window is a no-signal FIRST handshake (Via
            // mid-init) that registered legacy, then a later denial — bounded to that
            // race, healed by rejoin (the re-attach prompt loop this can enter is
            // 1/minute-bounded; see sendReattachPromptPayload).
            LSSLogger.info(dev.vox.lss.common.compat.ViaProbe.legacyAnnounceRefusedLine(
                    Brand.shortName(), playerName,
                    handshake.protocolVersion(), viaProtocol, nativeProtocol));
            return;
        }
        if (!decision.sendSessionConfig()) {
            // See HandshakeGate.Outcome.VERSION_MISMATCH: replying would kick the player.
            // An EXISTING registration deliberately survives this rung (and NO_CONSUMER):
            // only a hostile/buggy client re-handshakes cross-capability on a live
            // connection, and a stray duplicate frame must not kill a working stream.
            // The NO_CONSUMER keeps-registration shape is pinned (ServiceLifecycleGameTests,
            // Fabric); the mismatch-survives shape follows from the same early return but
            // is argued, not pinned. Accepted residual: such a client keeps receiving
            // columns it just disclaimed, bounded to its own connection.
            LSSLogger.warn("Player " + playerName
                    + " has incompatible " + Brand.shortName() + " protocol version " + handshake.protocolVersion()
                    + " (server: " + LSSConstants.PROTOCOL_VERSION + "), skipping LOD distribution");
            return;
        }

        // Anchored on the DECISION, not merely on serviceDenied: the line (and the
        // once-per-session claim it burns) may fire only where the missing grant is what
        // actually took the service away.
        //   * outcome DISABLED, not NO_CONSUMER: a capability-less client is classified by
        //     the rung ABOVE the enabled check, so it is denied for having no LOD consumer
        //     whatever its permissions. Logging there would double up with the NO_CONSUMER
        //     line below AND spend the session's one release on a handshake the gate never
        //     decided — the client's later, consumer-bearing handshake would then be denied
        //     in silence.
        //   * config.enabled() + servicePresent: with LSS off server-wide (or its service
        //     absent) the player is dark regardless, so naming a permission would send the
        //     admin hunting a grant that changes nothing.
        //   * past the two silent rungs (above): a version/Via denial is a protocol skew,
        //     not a missing grant.
        boolean deniedByServiceGate = serviceDenied && config.enabled() && servicePresent
                && decision.outcome() == HandshakeGate.Outcome.DISABLED;
        if (deniedByServiceGate && serviceGate.claimDenialLog()) {
            LSSLogger.info("LOD unavailable for " + playerName
                    + ": requireServicePermission is on and this player does not hold both "
                    + PERMISSION_SERVICE_LSS + " and " + PERMISSION_SERVICE_VSS
                    + " (a negative grant on either spelling denies) — the client was told"
                    + " " + Brand.shortName() + " is disabled and will stop asking. Restore"
                    + " both nodes to serve this player.");
        }

        boolean v16 = decision.dialect() == HandshakeGate.WireDialect.V16;
        boolean v18 = decision.dialect() == HandshakeGate.WireDialect.V18;
        boolean legacyDialect = decision.dialect() != HandshakeGate.WireDialect.CURRENT;
        Runnable reply = () -> {
            // 0 = no LOD service in this world (the Fabric twin): a CURRENT session is told
            // enabled=false (and re-told on dimension change); legacy dialects get no later
            // push, so they keep a serving distance and the request filter enforces the off
            // world. Resolved when the reply SENDS — for a registering handshake that is the
            // pump's Register drain, the same moment the state records its dimension — so a
            // world change between the handshake and registration cannot strand the client.
            int worldLod = PaperWorldLod.distance(config, nmsPlayer);
            int lodDistance = legacyDialect ? config.legacyAdvertisedDistance(worldLod) : worldLod;
            // Review fold A10: a REGISTER decided on the pre-arm config whose Register drains
            // after an arm is refused there — this reply then says so (re-read at SEND time).
            boolean refusedAtSend = decision.registerPlayer()
                    && !HandshakeGate.honorsMapPolicy(decision.dialect(), handshake.capabilities())
                    && dev.vox.lss.common.MapPolicy.decide(config.requestNoMapIngestion(),
                            serviceGate::mapIngestionExempt).deny();
            boolean mapPolicyRefused = refusedAtSend
                    || decision.outcome() == HandshakeGate.Outcome.MAP_POLICY_REFUSED;
            boolean enabledHere = decision.effectiveEnabled() && !refusedAtSend
                    && (legacyDialect || dev.vox.lss.common.config.ServerConfigBase.servesWorld(worldLod));
            configSender.send(decision.dialect(),
                    enabledHere,
                    // 0.23.0 plan §3: which "not here" this is (the Fabric twin). The world
                    // reason only when the world alone took the answer away.
                    dev.vox.lss.common.SessionOffReason.of(config.enabled(), deniedByServiceGate,
                            config.showPermissionRefusal(),
                            decision.effectiveEnabled() && !refusedAtSend && !enabledHere),
                    lodDistance,
                    // The caps ARE the old client's pacing — the server's real admission values
                    // (ignored by the V18 sender branch; see the v16 compat design §4.1).
                    LSSConstants.SYNC_ON_LOAD_SLOT_CAP,
                    config.generationLimits().perPlayer(),
                    config.enableChunkGeneration(),
                    // The session's capabilities: the sender appends the map-policy flags
                    // only for a v20 session that declared the bit (map-policy plan A2).
                    handshake.capabilities(),
                    // A QUIET refusal (the bit present) keeps the live dialect mark (A8).
                    mapPolicyRefused && (handshake.capabilities() & LSSConstants.CAPABILITY_MAP_POLICY) != 0);
        };

        if (decision.outcome() == HandshakeGate.Outcome.NO_CONSUMER) {
            // Reply-only outcome: no state will exist, so the inline reply cannot race it.
            reply.run();
            // Visible to admins via this log.
            LSSLogger.info("Player " + playerName
                    + " has no LOD consumer (caps=" + handshake.capabilities()
                    + "), skipping LOD registration");
            return;
        }

        if (decision.registerPlayer()) {
            // REGISTERING outcome: the reply is DEFERRED into the registration so the
            // client cannot declare before its state exists (the Folia pre-registration
            // drop, soak-diagnosed 2026-07-27 — on Folia this handler runs on the region
            // thread while the pump applies registrations next tick; a SessionConfig sent
            // from here invited a first want-set into the gap, dropped uncounted).
            registrar.register(handshake.capabilities(), decision.dialect(), reply);
            LSSLogger.info("Player " + playerName
                    + " registered for " + Brand.shortName() + " LOD request processing (caps="
                    + handshake.capabilities()
                    + (v16 ? ", v16-compat" : "") + (v18 ? ", v18-compat" : "") + ")");
        } else {
            // Reply-without-register (e.g. DISABLED advertisement): nothing to race.
            reply.run();
            if (deniedByServiceGate) {
                // AFTER the reply: the enabled=false config is the client's disarm; the
                // hook deposits the denied-handshake memo (re-offerable on a later
                // grant) and pump-marshals the unregistration composite for a live
                // session re-handshaking after a revocation (an ADMIN fact, unlike the
                // protocol facts an existing registration deliberately survives).
                serviceGate.onServiceDenied(handshake.protocolVersion(), handshake.capabilities());
            }
            if (decision.outcome() == HandshakeGate.Outcome.MAP_POLICY_REFUSED) {
                // Map-policy plan A3.2 (the xplat twin): the refusal reply went out INLINE
                // above. REAL refusal (bit absent): memo, chat line, live-session
                // composite, one INFO per transition. QUIET refusal (a legacy dialect WITH
                // the bit — a 0.19 client's raced rung): the reply only.
                boolean realRefusal = (handshake.capabilities()
                        & LSSConstants.CAPABILITY_MAP_POLICY) == 0;
                if (realRefusal && serviceGate.onMapPolicyRefused(
                        handshake.protocolVersion(), handshake.capabilities())) {
                    LSSLogger.info(dev.vox.lss.common.MapPolicy.refusalLogLine(playerName,
                            handshake.protocolVersion(), handshake.capabilities()));
                }
            }
        }
    }

    /**
     * The grant sweep's re-offer entry (service-permission-gate-plan.md §2.3): rebuilds
     * the remembered handshake frame and drives it through the PRODUCTION receiver body
     * — the full ladder re-runs (version, Via, consumer, enabled, gate), the reply
     * lands in the client's own dialect, and a REGISTER outcome takes the registrar's
     * DEFERRED reply (the Folia pre-registration gap stays closed). Called on the pump;
     * the player reference was freshly resolved by the sweep this tick.
     */
    void replayServiceGateHandshake(ServerPlayer nmsPlayer,
                                    ServiceGateState.DeniedHandshake remembered) {
        handleHandshake(nmsPlayer.getBukkitEntity(), nmsPlayer,
                PaperPayloadHandler.encodeHandshakeFrame(
                        remembered.protocolVersion(), remembered.capabilities()),
                true);
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
            // Network-level and immediate (both structures are any-thread safe): the quit
            // drops the compat session identities; the mailboxed removePlayer above only
            // resets a want-set that no longer exists — a no-op. The v18 membership is
            // ALSO dropped by the mailbox Remove drain (the quit-race leak guard —
            // v18-compat design §2.3).
            service.getV16CompatManager().onDisconnect(event.getPlayer().getUniqueId());
            service.getDialectTracker().onDisconnect(event.getPlayer().getUniqueId());
        }
        // Service-independent: the sidecar fact is recorded at the network level
        // (possibly before any service exists) and must die with the connection.
        CLIENT_DATA_VERSIONS.remove(event.getPlayer().getUniqueId());
        // Service gate: the denied-handshake memo, the denial-log latch, and the
        // revocation streak are swept by the EPOCH-GUARDED mailbox Remove drain
        // (enqueueRemove above), NOT here. On Folia this event can fire on a stalled
        // region thread AFTER a same-UUID reconnection already deposited a fresh
        // denial memo on its own region thread; an un-guarded sweep here would wipe
        // the successor's memo and strand a disarmed rejoiner with no re-offer (the
        // R4 belt exists precisely to skip the sweep when a newer connection exists).
    }

    public PaperRequestProcessingService getRequestService() {
        return this.requestService;
    }

    public PaperConfig getLssConfig() {
        return this.lssConfig;
    }
}
