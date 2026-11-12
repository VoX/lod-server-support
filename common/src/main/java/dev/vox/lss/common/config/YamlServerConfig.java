package dev.vox.lss.common.config;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Stable platform facade: files publish only at startup or through explicit reload. */
public class YamlServerConfig extends ServerConfigBase implements AutoCloseable {
    private record Context(SettingsHandle<ServerSettings> handle, SettingsReload<ServerSettings> reload,
                           String startupError, Path path) {}
    private final Path directory;
    private final Consumer<String> report;
    private volatile Context context;

    protected YamlServerConfig(Path directory, boolean paper) {
        this(directory, paper, LSSLogger::info);
    }
    /** Diagnostic sink seam also verifies platform facades retain startup notices. */
    protected YamlServerConfig(Path directory, boolean paper, Consumer<String> report) {
        super(disabledDefaults(paper), paper);
        this.directory = directory;
        this.report = java.util.Objects.requireNonNull(report);
        restartSettingsLifecycle();
    }
    /** Detached immutable view for callers constructing platform services in tests. */
    protected YamlServerConfig(ServerSettings settings, boolean paper) {
        super(settings, paper);
        this.directory = null;
        this.report = LSSLogger::info;
        this.context = new Context(null, null, null, null);
    }
    /** Called by platform server-start lifecycle, never by an interactive settings control. */
    protected final synchronized void restartSettingsLifecycle() {
        var old = context;
        if (old != null && old.reload() != null) old.reload().close();
        if (directory == null) return;
        var store = new SettingsStore<>(directory, Brand.lowerShortName(), SettingsSchema.server(paperPlatform), report);
        try {
            var handle = new SettingsHandle<>(store);
            context = new Context(handle, new SettingsReload<>(handle), null, store.path());
        } catch (Exception failure) {
            String error = SettingsReload.message(failure);
            context = new Context(null, null, error, store.path());
            LSSLogger.error("Server settings unavailable; LOD service remains disabled. Repair YAML and restart: " + error);
        }
    }
    private static ServerSettings disabledDefaults(boolean paper) {
        var values = new LinkedHashMap<>(SettingsSchema.server(paper).defaults().values());
        values.put("service.enabled", false);
        values.put("generation.enabled", false);
        values.put("storage.lod_store.enabled", false);
        values.put("storage.lod_store.backfill.enabled", false);
        values.put("far_players.mode", "off");
        return ServerSettings.fromValues(values);
    }
    @Override public ServerSettings snapshot() {
        var current = context;
        return current == null || current.handle() == null ? super.snapshot() : current.handle().state().effective();
    }
    @Override public ServerSettings configuredSnapshot() {
        var current = context;
        return current == null || current.handle() == null ? snapshot() : current.handle().state().configured();
    }
    @Override public boolean settingDeclared(String path) {
        var current = context;
        return current == null || current.handle() == null || current.handle().declares(path);
    }
    /** Retains the schema-1 diagnostic accessor name; generation policy itself reloads live. */
    @Override public boolean generationConfiguredForRestart() { return configuredSnapshot().generation().enabled(); }
    public final Path settingsPath() { return context.path(); }
    public final String startupError() { return context.startupError(); }
    public final SettingsHandle<ServerSettings> settingsHandle() { return context.handle(); }
    private static IllegalStateException unavailable(Context current) {
        return new IllegalStateException(current.startupError() == null ? "This settings view has no file"
                : "Repair settings and restart: " + current.startupError());
    }
    public final CompletableFuture<SettingsReload.Outcome<ServerSettings>> reload(
            Executor owner, SettingsReload.Reconciler<ServerSettings> reconciler) {
        var current = context;
        if (current.reload() == null) return CompletableFuture.failedFuture(unavailable(current));
        return current.reload().reload(owner, reconciler);
    }
    public final CompletableFuture<SettingsReload.Outcome<ServerSettings>> reloadOwned(
            SettingsReload.Owner owner, SettingsReload.Reconciler<ServerSettings> reconciler) {
        var current = context;
        if (current.reload() == null) return CompletableFuture.failedFuture(unavailable(current));
        return current.reload().reloadOwned(owner, reconciler);
    }
    @Override public void close() {
        var current = context;
        if (current.reload() != null) current.reload().close();
    }
}
