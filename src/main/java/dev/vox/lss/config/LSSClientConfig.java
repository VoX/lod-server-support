package dev.vox.lss.config;

public class LSSClientConfig extends JsonConfig {
    private static final String FILE_NAME = "lss-client-config.json";

    public static LSSClientConfig CONFIG = load(LSSClientConfig.class, FILE_NAME);

    public boolean receiveServerLods = true;
    public int lodDistanceChunks = 0;
    public int resyncIntervalSeconds = 600;
    public int resyncBatchSize = 32;

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @Override
    protected void validate() {
        lodDistanceChunks = Math.max(lodDistanceChunks, 0); // 0 = use server default
        resyncIntervalSeconds = Math.max(resyncIntervalSeconds, 10);
        resyncBatchSize = Math.max(resyncBatchSize, 1);
    }
}
