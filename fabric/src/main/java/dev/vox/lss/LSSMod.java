package dev.vox.lss;

import dev.vox.lss.networking.LSSNetworking;
import dev.vox.lss.networking.server.LSSServerNetworking;
import net.fabricmc.api.ModInitializer;

public class LSSMod implements ModInitializer {
    @Override
    public void onInitialize() {
        LSSNetworking.registerPayloads();
        LSSServerNetworking.init();
    }
}
