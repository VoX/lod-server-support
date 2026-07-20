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
        LSSClientNetworking.init();
        LSSClientCommands.init();
        ModCompat.init();
        BenchmarkBridge.initClient();
    }
}
