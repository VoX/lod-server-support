package dev.vox.lss.neoforge;

import dev.vox.lss.common.Brand;
import dev.vox.lss.networking.LSSNetworking;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.platform.NeoForgeLoaderServices;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * NeoForge entrypoint (stage N-2, neoforge-support-plan.md): the fabric
 * {@code LSSMod}/{@code LSSClient} pair's twin. Brand first, the LoaderServices
 * seam second (before any config/compat touch), then payload + event wiring.
 * The client half boots reflectively behind a dist gate (the fabric
 * BenchmarkBridge / vss-fork pattern) so a dedicated server never links a
 * client class.
 */
@Mod("lss")
public final class LSSNeoMod {

    private static final String CLIENT_BOOTSTRAP_CLASS = "dev.vox.lss.neoforge.LSSNeoClientBootstrap";

    public LSSNeoMod(IEventBus modBus) {
        // FIRST: display branding (the fabric ordering contract — before any config
        // touch, LSSClientConfig's static init reads Brand.shortName()).
        Brand.load(LSSNeoMod.class.getClassLoader());
        // SECOND: the loader seam — everything xplat reads flows through it.
        NeoForgeLoaderServices.installProduction();

        modBus.addListener(RegisterPayloadHandlersEvent.class, LSSNetworking::register);

        // Service-gate permission nodes (service-permission-gate-plan.md §2.1):
        // registered UNCONDITIONALLY — an unregistered node throws at query time,
        // and a runtime `set requireServicePermission true` must find them live.
        NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.server.permission.events.PermissionGatherEvent.Nodes.class,
                LSSNeoPermissions::onGatherNodes);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,
                LSSServerNetworking::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class,
                LSSServerNetworking::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,
                LSSServerNetworking::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.level.ChunkEvent.Load.class,
                LSSServerNetworking::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class,
                LSSServerNetworking::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                LSSServerNetworking::onRegisterCommands);

        if (FMLEnvironment.dist.isClient()) { // 1.21.1 line: dist is a field on 21.1
            initClientReflectively();
        }
    }

    /** N-3 ships the bootstrap class; until then the miss is an inert INFO (the client
     *  half is compiled-and-inert on 26.2 anyway — plan §0.1). Contained: a client-boot
     *  failure must never take the whole mod down with it. */
    private static void initClientReflectively() {
        try {
            Class.forName(CLIENT_BOOTSTRAP_CLASS).getMethod("init").invoke(null);
        } catch (ClassNotFoundException e) {
            dev.vox.lss.common.LSSLogger.info(
                    "No NeoForge client bootstrap present — client half inert (N-2 server-only build)");
        } catch (ReflectiveOperationException e) {
            dev.vox.lss.common.LSSLogger.error("Failed to initialize the "
                    + Brand.shortName() + " client half — client features disabled", e);
        }
    }
}
