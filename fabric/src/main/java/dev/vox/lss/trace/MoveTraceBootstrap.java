package dev.vox.lss.trace;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.config.LSSServerConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;

/**
 * Tracer lifecycle (move-desync-tracer-plan.md §0/§1.1): hangs off Fabric server
 * lifecycle events registered from {@code LSSMod} — NEVER off
 * {@code RequestProcessingService}, because the tracer must observe LSS-disabled servers
 * and unregistered players (the E2 control flights).
 *
 * <p>Activation: {@code -Dlss.moveTrace=true} OR the marker file
 * {@code config/lss-move-trace.enable} (content ignored; it exists because the only
 * guaranteed channels to the live host are SFTP and RCON — review U-9). Read ONCE at
 * SERVER_STARTING (Fable F2-11: the config dir is absolute by then, and the static gate
 * is set strictly before any {@code handleMovePlayer} can run). Default absent → fully
 * off: no file, no thread, hook bodies no-op after one static boolean check — and no
 * hook body may reference {@code MoveTraceTelemetry} without that check first (a lazy
 * classload from a hook is exposed to the jar-swapped-under-a-running-server race —
 * the 2026-08-20 shutdown ZipException).
 */
public final class MoveTraceBootstrap {

    static final String MARKER_FILE_NAME = "lss-move-trace.enable";
    static final String OUTPUT_FILE_NAME = "lss-move-trace.jsonl";

    private MoveTraceBootstrap() {}

    /** Called once from {@code LSSMod.onInitialize} — registrations are unconditional,
     *  bodies are gated (the tracer can only activate at SERVER_STARTING). */
    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(MoveTraceBootstrap::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // Gated like the sibling hooks — and deliberately so even though clearAll()
            // on an inactive tracer is a no-op: an inactive session never classloaded
            // MoveTraceTelemetry, and an ungated reference here makes SHUTDOWN the
            // first load. Live sighting 2026-08-20: a jar uploaded over the running
            // server left the classloader's zip offsets stale, and this line's lazy
            // load threw ZipException out of SERVER_STOPPING at the head of
            // stopServer, skipping the orderly shutdown. When the tracer WAS active
            // the class is long since loaded and the clear is real work.
            if (MoveDesyncTracer.enabled()) MoveTraceTelemetry.clearAll();
            MoveDesyncTracer.deactivate();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (MoveDesyncTracer.enabled()) MoveTraceTelemetry.tick(server);
        });
        // On the server thread, like the tick above: Fabric can fire DISCONNECT from netty.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (MoveDesyncTracer.enabled()) {
                java.util.UUID uuid = handler.getPlayer().getUUID();
                server.execute(() -> MoveTraceTelemetry.onDisconnect(uuid));
            }
        });
    }

    private static void onServerStarting(MinecraftServer server) {
        try {
            if (!shouldEnable()) return;
            Path file = outputFile(server);
            var tracer = new MoveDesyncTracer(file, MoveDesyncTracer.DEFAULT_ROTATE_BYTES);
            tracer.setRung(ChunkSendState.resolve().rungName());
            MoveDesyncTracer.activate(tracer);
            // The deploy ladder's first verification step (§4.3): a deploy missing the
            // flag is visible in the first screenful of latest.log.
            LSSLogger.info("Move desync tracer ACTIVE -> " + file);
            emitBootRow(tracer);
        } catch (Throwable t) {
            LSSLogger.warn("Move desync tracer failed to activate (" + t + ") — tracing off");
        }
    }

    private static boolean shouldEnable() {
        if (Boolean.getBoolean("lss.moveTrace")) return true;
        try {
            return Files.exists(FabricLoader.getInstance().getConfigDir().resolve(MARKER_FILE_NAME));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Path outputFile(MinecraftServer server) {
        String override = System.getProperty("lss.moveTrace.file");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return server.getServerDirectory().resolve("logs").resolve(OUTPUT_FILE_NAME);
    }

    private static void emitBootRow(MoveDesyncTracer tracer) {
        var config = new LinkedHashMap<String, Object>();
        var cfg = LSSServerConfig.CONFIG;
        config.put("bytesPerSecondLimitPerPlayer", cfg.bytesPerSecondPerPlayer());
        config.put("bytesPerSecondLimitGlobal", cfg.bytesPerSecondGlobal());
        config.put("lodDistanceChunks", cfg.lodDistanceChunks());
        config.put("lodStore", cfg.lodStore());
        // A live ping-backstop cut shifts the LOD send envelope the same way an armed
        // yield does — analysis must never mix backstop-on and backstop-off boots.
        config.put("enablePingBackstop", cfg.enablePingBackstop());
        config.put("enableSendPacing", cfg.enableSendPacing());
        // The §4.5 partition key: an armed-yield collection period shifts the envelope
        // obuf distribution by design — analysis must never mix armed and unarmed boots.
        config.put("lodYieldsToVanillaTransport", cfg.lodYieldsToVanillaTransport());
        var loader = FabricLoader.getInstance();
        tracer.emit(MoveRow.boot(tracer.bootId(), System.currentTimeMillis(),
                ZonedDateTime.now().getOffset().getTotalSeconds() / 60,
                modVersion(loader, "lss"), modVersion(loader, "minecraft"),
                loader.isModLoaded("moonrise"), loader.isModLoaded("c2me"),
                loader.isModLoaded("chunky"), tracer.rung(), config));
    }

    private static String modVersion(FabricLoader loader, String modId) {
        try {
            return loader.getModContainer(modId)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
