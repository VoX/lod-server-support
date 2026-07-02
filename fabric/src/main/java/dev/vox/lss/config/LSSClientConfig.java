package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.config.JsonConfig;
import net.fabricmc.loader.api.FabricLoader;

public class LSSClientConfig extends JsonConfig {
    private static final String FILE_NAME = "lss-client-config.json";

    public static LSSClientConfig CONFIG =
            load(LSSClientConfig.class, FILE_NAME, FabricLoader.getInstance().getConfigDir());

    public boolean receiveServerLods = true;
    public int lodDistanceChunks = 0;

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @Override
    public void validate() {
        lodDistanceChunks = net.minecraft.util.Mth.clamp(lodDistanceChunks, 0, LSSConstants.MAX_LOD_DISTANCE); // 0 = use server default
    }
}
