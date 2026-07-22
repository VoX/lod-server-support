package dev.vox.lss;

import dev.vox.lss.common.Brand;
import dev.vox.lss.networking.LSSNetworking;
import dev.vox.lss.networking.server.LSSServerNetworking;
import net.fabricmc.api.ModInitializer;

public class LSSMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // FIRST: resolve display branding before any service/thread/log line is created.
        // Load-bearing beyond logging: the ModInitializer runs before the ClientModInitializer,
        // so on a client this is what guarantees Brand is resolved before LSSClientConfig's static
        // init reads Brand.shortName() to pick its config filename (brandedConfigCandidates). Do
        // not remove as "redundant with LSSClient" — keep branding resolved before any config touch.
        Brand.load(LSSMod.class.getClassLoader());
        LSSNetworking.registerPayloads();
        LSSServerNetworking.init();
        BenchmarkBridge.initServer();
    }
}
