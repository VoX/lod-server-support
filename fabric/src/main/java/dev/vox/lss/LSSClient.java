package dev.vox.lss;

import dev.vox.lss.common.Brand;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.networking.client.LSSClientCommands;
import dev.vox.lss.networking.client.LSSClientNetworking;
import net.fabricmc.api.ClientModInitializer;

public class LSSClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // FIRST: resolve display branding (a client can start without the server init).
        Brand.load(LSSClient.class.getClassLoader());
        // Upgrade LSSMod's common LoaderServices install to the client-capable impl
        // (adds sendToServer) before any client networking runs.
        dev.vox.lss.platform.FabricClientLoaderServices.installProductionClient();
        dev.vox.lss.networking.client.DhClientTransport.init();
        LSSClientNetworking.init();
        LSSClientCommands.init();
        dev.vox.lss.networking.client.FarPlayerRenderer.initRenderer();
        ModCompat.init();
        dev.vox.lss.compat.VertigoFabricHook.init();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_LOAD.register(ModCompat::vanillaChunkLoaded);
        BenchmarkBridge.initClient();
    }
}
