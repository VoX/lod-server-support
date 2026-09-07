package xaero.map.region;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/** Tier-1 stub — captures write() inputs for the extractor→commit assertions.
 *  prepareForWriting is FAITHFUL to the real jar (clears overlays + resets the
 *  write fields): the production order prepare → addOverlay* → write is
 *  load-bearing — with the real semantics, calling prepare after the overlay
 *  loop wipes every overlay, so the overlay assertions pin the order for real
 *  (review MAJOR: an unfaithful no-op stub made the ordering pin vacuous). */
public class MapBlock {
    public int preparedBottomY = Integer.MIN_VALUE;
    public BlockState state;
    public int height;
    public int topHeight;
    public ResourceKey<Biome> biome;
    public byte light;
    public boolean glowing;
    public boolean cave;
    public final java.util.List<Overlay> overlays = new java.util.ArrayList<>();

    public boolean slopeUnknown = true;

    public void setSlopeUnknown(boolean unknown) { this.slopeUnknown = unknown; }

    public MapBlock() {}

    public void prepareForWriting(int defaultHeight) {
        this.slopeUnknown = true;
        this.preparedBottomY = defaultHeight;
        this.overlays.clear();
        this.biome = null;
        this.state = null;
        this.light = 0;
        this.glowing = false;
        this.height = defaultHeight;
        this.topHeight = defaultHeight;
    }

    public void write(BlockState state, int height, int topHeight, ResourceKey<Biome> biome,
                      byte light, boolean glowing, boolean cave) {
        this.state = state;
        this.height = height;
        this.topHeight = topHeight;
        if (biome != null) {
            this.biome = biome; // real write() keeps the previous biome on null
        }
        this.light = light;
        this.glowing = glowing;
        this.cave = cave;
    }

    public void addOverlay(Overlay overlay) { this.overlays.add(overlay); }
}
