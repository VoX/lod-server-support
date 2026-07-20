package dev.vox.lss;

import dev.vox.lss.common.Brand;
import dev.vox.lss.networking.LSSNetworking;
import dev.vox.lss.networking.server.LSSServerNetworking;
import net.fabricmc.api.ModInitializer;

public class LSSMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // FIRST: resolve display branding before any service/thread/log line is created.
        Brand.load(LSSMod.class.getClassLoader());
        LSSNetworking.registerPayloads();
        LSSServerNetworking.init();
        BenchmarkBridge.initServer();
    }
}
