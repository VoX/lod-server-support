package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;

public class LSSClientConfig extends JsonConfig {
    private static final String FILE_NAME = "lss-client-config.json";

    public static LSSClientConfig CONFIG = load(LSSClientConfig.class, FILE_NAME);

    public boolean receiveServerLods = true;
    public int lodDistanceChunks = 0;
    public boolean offThreadSectionProcessing = true;

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @Override
    protected void validate() {
        lodDistanceChunks = Math.clamp(lodDistanceChunks, 0, LSSConstants.MAX_LOD_DISTANCE); // 0 = use server default
    }
}
