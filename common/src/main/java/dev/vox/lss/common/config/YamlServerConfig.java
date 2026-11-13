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
                           String startupError, Path path, java.util.List<SettingsProblems.Problem> startupProblems) {}
    /** The last block this JVM logged. Fabric, NeoForge and Sponge read the file when the class
     *  loads and again when the server starts: the same refusal is one block, not two. */
    private static volatile String lastLoggedBlock;
    private final Path directory;
    private final Consumer<String> report;
    private final Consumer<String> problemLog;
    private volatile Context context;

    protected YamlServerConfig(Path directory, boolean paper) {
        this(directory, paper, LSSLogger::info);
    }
    /** Diagnostic sink seam also verifies platform facades retain startup notices. */
    protected YamlServerConfig(Path directory, boolean paper, Consumer<String> report) {
        this(directory, paper, report, LSSLogger::error);
    }
    /** {@code problemLog} receives the block about a file that did not load (tests read it). */
    protected YamlServerConfig(Path directory, boolean paper, Consumer<String> report, Consumer<String> problemLog) {
        super(disabledDefaults(paper), paper);
        this.directory = directory;
        this.report = java.util.Objects.requireNonNull(report);
        this.problemLog = java.util.Objects.requireNonNull(problemLog);
        restartSettingsLifecycle();
    }
    /** Detached immutable view for callers constructing platform services in tests. */
    protected YamlServerConfig(ServerSettings settings, boolean paper) {
        super(settings, paper);
        this.directory = null;
        this.report = LSSLogger::info;
        this.problemLog = LSSLogger::error;
        this.context = new Context(null, null, null, null, java.util.List.of());
    }
    /** Called by platform server-start lifecycle, never by an interactive settings control. */
    protected final synchronized void restartSettingsLifecycle() {
        var old = context;
        if (old != null && old.reload() != null) old.reload().close();
        if (directory == null) return;
        var store = new SettingsStore<>(directory, Brand.lowerShortName(), SettingsSchema.server(paperPlatform), report);
        try {
            var handle = new SettingsHandle<>(store);
            context = new Context(handle, new SettingsReload<>(handle), null, store.path(), java.util.List.of());
            lastLoggedBlock = null;
        } catch (Exception failure) {
            String error = SettingsReload.message(failure);
            var problems = SettingsReload.problems(failure);
            if (problems.isEmpty()) problems = java.util.List.of(new SettingsProblems.Problem(0, error));
            context = new Context(null, null, error, store.path(), problems);
            // Everything wrong with the file, at once (0.23.0 plan §1). The service stays
            // disabled; SettingsProblemAnnouncer says it again once the server has started.
            var block = SettingsProblemText.serverBlock(store.path().getFileName().toString(), problems);
            String text = store.path() + "\n" + String.join("\n", block);
            if (!text.equals(lastLoggedBlock)) {
                lastLoggedBlock = text;
                block.forEach(problemLog);
            }
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
    /** Everything wrong with a settings file that did not load at startup; empty when it loaded. */
    public final java.util.List<SettingsProblems.Problem> startupProblems() { return context.startupProblems(); }
    /** The settings file's own name: the Voxy Server Side build's differs. */
    public final String settingsFileName() {
        Path path = context.path();
        return path == null ? "the settings file" : path.getFileName().toString();
    }
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
