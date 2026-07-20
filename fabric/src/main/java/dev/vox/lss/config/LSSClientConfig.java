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
    // Backward compat with pre-v0.7.0 (protocol 16, v0.4.x–v0.6.2) SERVERS: if a server does
    // not answer the v18 handshake, re-handshake announcing version 16 and decode the legacy
    // wire. Default true (mirrors the server's enableV16Compat). Set false for a strict-v18
    // client (no discovery fallback). See docs/planning/v16-client-compat-design.md.
    public boolean enableV16ServerCompat = true;

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @Override
    public void validate() {
        lodDistanceChunks = Math.clamp(lodDistanceChunks, 0, LSSConstants.MAX_LOD_DISTANCE); // 0 = use server default
    }
}
