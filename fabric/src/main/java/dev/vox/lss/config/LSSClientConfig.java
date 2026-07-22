package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.config.JsonConfig;
import net.fabricmc.loader.api.FabricLoader;

public class LSSClientConfig extends JsonConfig {
    // Brand-primary first, other brand as fallback: the VSS jar (Brand.shortName()=="VSS") prefers
    // vss-client-config.json but adopts an existing lss-client-config.json; the LSS jar is the
    // mirror. So a fresh install gets the brand's own file, and an LSS<->VSS jar swap keeps its
    // config. Brand is loaded by the entrypoint before this class is first touched, so it reads
    // the correct brand for the running jar. See JsonConfig.brandedConfigCandidates.
    private static final String[] CANDIDATES = brandedConfigCandidates("client");

    public static LSSClientConfig CONFIG =
            load(LSSClientConfig.class, CANDIDATES, FabricLoader.getInstance().getConfigDir());

    public boolean receiveServerLods = true;
    public int lodDistanceChunks = 0;
    // Backward compat with pre-v0.7.0 (protocol 16, v0.4.x–v0.6.2) SERVERS: if a server does
    // not answer the v18 handshake, re-handshake announcing version 16 and decode the legacy
    // wire. Default true (mirrors the server's enableV16Compat). Set false for a strict-v18
    // client (no discovery fallback). See docs/planning/v16-client-compat-design.md.
    public boolean enableV16ServerCompat = true;
    // Tier B of the same compat: on a v16 SERVER, drive on-demand GENERATION of cold columns
    // instead of load-only (Tier A). The egress rewrites a first-serve request to v16's generate
    // trigger, so the old server generates terrain the player has not visited. Default TRUE — this
    // is how a native protocol-16 client behaved (the old servers were built to have LOD clients
    // drive generation), so it is the faithful default and gives the full LOD experience. Verified
    // safe across the compat range: both v0.4.0 and v0.6.2 read disk-FIRST for the generate path
    // (already-generated terrain is served, not regenerated), and the transient-NOT_GENERATED heal
    // that keeps cold backfill filling is client-side (version-independent). Set false for
    // strict load-only (Tier A). No effect unless enableV16ServerCompat is also on, and never on a
    // v18 session. See docs/planning/v16-client-compat-design.md §4 (Tier B).
    public boolean enableV16Generation = true;

    @Override
    protected String getFileName() {
        return CANDIDATES[0]; // brand-primary (creation target / default when not loaded via load())
    }

    @Override
    public void validate() {
        lodDistanceChunks = Math.clamp(lodDistanceChunks, 0, LSSConstants.MAX_LOD_DISTANCE); // 0 = use server default
    }
}
