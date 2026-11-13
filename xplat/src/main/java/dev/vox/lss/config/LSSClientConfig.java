package dev.vox.lss.config;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.config.ClientSettings;
import dev.vox.lss.common.config.SettingsHandle;
import dev.vox.lss.common.config.SettingsReload;
import dev.vox.lss.common.config.SettingsSchema;
import dev.vox.lss.common.config.SettingsStore;
import dev.vox.lss.config.menu.ClientSettingsEditSession;
import dev.vox.lss.config.menu.ClientMenuReload;
import dev.vox.lss.networking.client.ClientNetGlue;
import dev.vox.lss.networking.client.FarPlayerClientSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Stable settings owner. Commands and Sodium Apply share one validated reload path. */
public final class LSSClientConfig {
    public static final LSSClientConfig CONFIG = new LSSClientConfig(
            dev.vox.lss.platform.LoaderServices.get().configDir());
    private final SettingsStore<ClientSettings> store;
    private volatile SettingsHandle<ClientSettings> handle;
    private volatile SettingsReload<ClientSettings> reload;
    private final ClientSettings inactive;
    private final AtomicBoolean reloadBusy = new AtomicBoolean();
    private final ClientMenuReload menuReload = new ClientMenuReload(reloadBusy::get,
            () -> reload(this::menuReloadFeedback));
    private volatile String error;
    private Object physicalConnection;
    private long lifecycle;
    private ClientSettingsEditSession edits;

    public LSSClientConfig(Path directory) {
        store = new SettingsStore<>(directory, Brand.lowerShortName(), SettingsSchema.client(), LSSLogger::info);
        var values = new java.util.LinkedHashMap<String, Object>(SettingsSchema.client().defaults().values());
        values.put("lod.receive", false);
        values.put("far_players.enabled", false);
        values.put("far_players.sharing.enabled", false);
        // A broken settings file must never register a map consumer (the template default is on).
        values.put("integrations.map_ingestion.enabled", false);
        inactive = ClientSettings.fromValues(values);
        try { handle = new SettingsHandle<>(store); reload = new SettingsReload<>(handle); }
        catch (Exception failure) {
            error = failure.getMessage();
            LSSLogger.error("Client settings inactive: " + error);
        }
    }

    public ClientSettings snapshot() {
        var current = handle;
        return current == null ? inactive : current.state().effective();
    }
    public ClientSettings configured() {
        var current = handle;
        return current == null ? inactive : current.state().configured();
    }
    public dev.vox.lss.common.diagnostics.ClientSettingsStatus diagnosticSettings() {
        var current = handle;
        if (current == null) return dev.vox.lss.common.diagnostics.ClientSettingsStatus.capture(
                false, inactive, inactive, inactive, java.util.Set.of(), 0, 0, java.util.Set.of());
        var state = current.state();
        return dev.vox.lss.common.diagnostics.ClientSettingsStatus.capture(true,
                edits == null ? state.configured() : edits.savedSnapshot(), state.configured(), state.effective(),
                state.pendingReconnect(), state.revision(), current.adoptedRevision(),
                current.pendingAdoption() == null ? java.util.Set.of() : current.pendingAdoption().paths());
    }
    public SettingsStore<ClientSettings> store() { return store; }
    public String error() { return error; }
    public java.util.Set<String> pendingReconnect() {
        var current = handle;
        return current == null ? java.util.Set.of() : current.state().pendingReconnect();
    }
    public synchronized ClientSettingsEditSession edits() {
        if (edits == null) edits = new ClientSettingsEditSession(store, this::snapshot, this::configured,
                menuReload::request);
        return edits;
    }

    private void menuReloadFeedback(Component message) {
        LSSLogger.info(message.getString());
        dev.vox.lss.networking.client.ClientStatusScreen.sendChatFeedback(message);
    }

    /** Use the transport connection, never the replaceable play listener/world. */
    public void beginConnection(Object connection) {
        if (physicalConnection == connection) return;
        physicalConnection = connection;
        lifecycle++;
        if (handle != null && connection != null) handle.beginSession(connection);
    }
    public void endConnection() {
        if (handle != null) handle.endSession(physicalConnection);
        physicalConnection = null;
        lifecycle++;
    }

    /** Parse off-thread, then commit and reconcile exactly once on the client owner. */
    public void reload(Consumer<Component> feedback) {
        if (!reloadBusy.compareAndSet(false, true)) {
            feedback.accept(Component.translatable("lss.settings.reload_busy"));
            return;
        }
        long expectedLifecycle = lifecycle;
        var owner = Minecraft.getInstance();
        var current = handle;
        if (current == null) {
            reloadBusy.set(false);
            feedback.accept(Component.translatable("lss.settings.inactive"));
            return;
        }
        reload.reload(owner::execute, candidate -> {
            if (expectedLifecycle != lifecycle) throw new IllegalStateException("connection changed; retry reload");
            ClientNetGlue.validateClientSettings(candidate);
        }, (previous, effective, revision) -> {
            reconcile();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).whenComplete((result, failure) -> owner.execute(() -> {
            try {
                if (failure != null) {
                    LSSLogger.warn("Client settings reload failed: " + SettingsReload.message(failure));
                    feedback.accept(Component.translatable("lss.settings.reload_failed"));
                    return;
                }
                error = null;
                for (var normalization : result.commit().normalizations()) {
                    var notice = dev.vox.lss.common.diagnostics.ClientReloadText.normalization(normalization);
                    feedback.accept(Component.translatable(notice.key(), notice.arguments().toArray()));
                }
                afterReload(feedback, result.status());
            } finally {
                reloadBusy.set(false);
                menuReload.drain();
            }
        }));
    }

    private void reconcile() {
        // Privacy enqueue precedes receive-off acquisition retirement.
        FarPlayerClientSupport.onClientConfigChanged();
        ClientNetGlue.reconcileClientConfig();
    }

    private void afterReload(Consumer<Component> feedback, SettingsReload.Status status) {
        if (edits != null) {
            var screen = dev.vox.lss.networking.client.ClientStatusScreen.currentScreen();
            if (screen != null) dev.vox.lss.config.menu.SodiumDraftRefresh.retainDirtyBeforeReloadRefresh(screen);
            edits.onReload();
            if (screen != null) dev.vox.lss.config.menu.SodiumDraftRefresh.open(screen);
        }
        feedback.accept(Component.translatable(dev.vox.lss.common.diagnostics.ClientReloadText.outcomeKey(status)));
        if (!pendingReconnect().isEmpty())
            feedback.accept(Component.translatable("lss.settings.pending_reconnect", String.join(", ", pendingReconnect())));
        feedback.accept(Component.translatable(FarPlayerClientSupport.preferenceMessageKey()));
    }

    // Read-only compatibility methods keep cross-line consumers small. Capture snapshot()
    // once where correlated values are needed; there are no mutable runtime fields.
    public boolean enableRegionScan() { return snapshot().scan().regionOrder(); }
    // Experimental DH adapter settings. Callers use these accessors only, so the
    // backing file (downgrade-safe sidecar) can change without touching consumers.
    public boolean dhSourceAllowed() { return "distant_horizons".equals(snapshot().lod().source()); }
    public boolean dhSyncOnLoad() { return snapshot().lod().dh().syncOnLoad(); }
    public boolean dhRealTimeUpdates() { return snapshot().lod().dh().realTimeUpdates(); }
    /** Vanilla-border fill into Voxy; runs only while a native or DH terrain session is active. */
    public boolean voxyBorderFill() { return snapshot().lod().voxyBorderFill(); }
    public boolean receiveServerLods() { return snapshot().lod().receive(); }
    public boolean useWorldSubBuckets() { return snapshot().cache().splitByWorld(); }
    public java.util.List<java.util.List<String>> cacheAddressAliases() { return snapshot().cache().addressAliases(); }
    public int lodDistanceChunks() { return snapshot().lod().distanceChunks(); }
    public String unknownBlockFallback() { return snapshot().compatibility().blockFallbacks().defaultBlock(); }
    public java.util.Map<String, String> crossVersionBlockFallbacks() { return snapshot().compatibility().blockFallbacks().overrides(); }
    public boolean enableV16ServerCompat() { return snapshot().compatibility().protocols().v16(); }
    public boolean enableV19ServerCompat() { return snapshot().compatibility().protocols().v19(); }
    public boolean enableV16Generation() { return snapshot().compatibility().v16Generation(); }
    public boolean enableAdaptiveScanCadence() { return snapshot().scan().adaptiveCadence(); }
    public boolean enableScanPrefixRetention() { return snapshot().scan().retainCompletedPrefix(); }
    public boolean enableQuadtreeScan() { return snapshot().scan().quadtree(); }
    public boolean enableRegionSummarySync() { return snapshot().scan().regionSummaries(); }
    /** Map ingestion (Xaero's World Map, JourneyMap): the neutral switch, timing reconnect. */
    public boolean mapIngestionEnabled() { return snapshot().integrations().mapIngestion().enabled(); }
    public boolean mapIngestionBackpressure() { return snapshot().integrations().mapIngestion().backpressure(); }
    /** Xaero cave-layer fill store cap in MiB (sidecar key); 0 = no store and no fill on switch. */
    public int mapIngestionNetherStoreMb() { return snapshot().integrations().mapIngestion().netherStoreMb(); }
    public boolean enableIngestBackpressure() { return snapshot().lod().download().ingestBackpressure(); }
    public int lodColumnsPerSecondLimit() { return snapshot().lod().download().maxColumnsPerSecond(); }
    public boolean enableAdaptiveTransferRate() { return snapshot().lod().download().adaptiveRate(); }
    public boolean enableJoinSlowStart() { return snapshot().lod().download().slowStartOnJoin(); }
    public boolean farPlayersEnabled() { return snapshot().farPlayers().enabled(); }
    public int farPlayersMaxDistanceBlocks() { return snapshot().farPlayers().distance().maxBlocks(); }
    public int farPlayersMinDistanceBlocks() { return snapshot().farPlayers().distance().minBlocks(); }
    public boolean farPlayersNameTags() { return snapshot().farPlayers().nameTags(); }
    public boolean farPlayersFullBright() { return snapshot().farPlayers().fullBright(); }
    public boolean farPlayersExtendFarPlane() { return snapshot().farPlayers().extendFarPlane(); }
    public boolean farPlayersShareSelf() { return snapshot().farPlayers().sharing().enabled(); }
    public int farPlayersShareDistanceBlocks() { return snapshot().farPlayers().sharing().maxDistanceBlocks(); }
    public int farPlayersMaxRenderDistanceBlocks() { return snapshot().farPlayers().renderDistanceBlocks(); }
    public int farPlayersMaxAnimationDistanceBlocks() { return snapshot().farPlayers().animationDistanceBlocks(); }
}
