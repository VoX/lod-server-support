package xaero.map.region;

/** Tier-1 stub. */
public class MapTile {
    /** The real field is static FINAL; non-final here so a test can vary the value the
     *  bridge reads reflectively at resolve time. */
    public static int CURRENT_WORLD_INTERPRETATION_VERSION = 1;
    public final int chunkX;
    public final int chunkZ;
    public final MapBlock[][] blocks = new MapBlock[16][16];
    public int worldInterpretationVersion;
    public int writtenCaveStart;
    public int writtenCaveDepth;
    public boolean writtenOnce;
    public boolean loaded;

    public MapTile(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public MapBlock getBlock(int x, int z) { return this.blocks[x][z]; }

    public boolean isLoaded() { return this.loaded; }

    public void setBlock(int x, int z, MapBlock block) { this.blocks[x][z] = block; }

    public void setWorldInterpretationVersion(int version) {
        this.worldInterpretationVersion = version;
        dev.vox.lss.compat.XaeroStubEvents.record("tile.setWorldInterpretationVersion " + version);
    }

    public void setWrittenCave(int caveStart, int caveDepth) {
        this.writtenCaveStart = caveStart;
        this.writtenCaveDepth = caveDepth;
        dev.vox.lss.compat.XaeroStubEvents.record("tile.setWrittenCave");
    }

    public void setWrittenOnce(boolean written) {
        this.writtenOnce = written;
        dev.vox.lss.compat.XaeroStubEvents.record("tile.setWrittenOnce " + written);
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
        dev.vox.lss.compat.XaeroStubEvents.record("tile.setLoaded " + loaded);
    }
}
