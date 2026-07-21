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
    // Tier B of the same compat: on a v16 SERVER, drive on-demand GENERATION of cold columns
    // instead of load-only (Tier A). The egress rewrites a first-serve request to v16's
    // generate trigger, so the old server generates terrain the player has not visited. Default
    // FALSE — opt-in, because it drives generation load on a remote (old) server that never
    // agreed to it. Confirmed safe against v0.6.2 (its generate path reads disk-FIRST, so
    // already-generated terrain is served, not regenerated); other pre-v0.7.0 versions are
    // unverified. No effect unless enableV16ServerCompat is also on (a v18 session ignores it).
    // See docs/planning/v16-client-compat-design.md §4 (Tier B).
    public boolean enableV16Generation = false;

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @Override
    public void validate() {
        lodDistanceChunks = Math.clamp(lodDistanceChunks, 0, LSSConstants.MAX_LOD_DISTANCE); // 0 = use server default
    }
}
