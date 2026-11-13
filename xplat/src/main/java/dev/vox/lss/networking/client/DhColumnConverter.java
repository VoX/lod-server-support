package dev.vox.lss.networking.client;

import dev.vox.lss.common.dh.DhTerrainTile;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.platform.SectionConstruction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import java.util.*;

/** Worker-only expansion; containers and light arrays are never reused after handoff. */
final class DhColumnConverter {
    /** {@code carryLoss}: 0, or the sky light one voxel of this block costs when light is
     *  carried down through it (see {@link #carryLoss(BlockState)}). */
    private record Material(BlockState state, Holder<Biome> biome, int waterSkyLoss, int carryLoss) {}
    private final net.minecraft.core.Registry<Biome> biomes;
    private final ClientIdentityResolver resolver;
    DhColumnConverter(ClientLevel level) {
        this(level.registryAccess().registryOrThrow(Registries.BIOME));
    }
    DhColumnConverter(net.minecraft.core.Registry<Biome> biomes) {
        this.biomes=biomes;
        var fallback=dev.vox.lss.config.LSSClientConfig.CONFIG.snapshot().compatibility().blockFallbacks();
        resolver=new ClientIdentityResolver(biomes, biomes.asHolderIdMap(),fallback);
    }
    /** Whole-tile conversion on the worker: materials resolve once, then each
     *  selected complete column converts. Unselected/incomplete columns stay null. */
    VoxelColumnData[] convertTile(DhTerrainTile tile, int columnMask) { return convertTile(tile,columnMask,false); }
    /** {@code bakeSkyLight}: a DH 3.3 real-time push is sent before DH bakes sky light
     *  (it arrives at zero); rebuild it first, as a DH client does when storing it.
     *  Nothing is baked when no column is selected (e.g. a push vanilla already holds). */
    VoxelColumnData[] convertTile(DhTerrainTile tile, int columnMask, boolean bakeSkyLight) {
        var materials=materials(tile);
        if(bakeSkyLight && columnMask!=0) {
            int[] opacity=new int[materials.size()];
            for(int i=0;i<opacity.length;i++) opacity[i]=dhOpacity(materials.get(i).state);
            tile.bakeSkyLight(opacity);
        }
        var out=new VoxelColumnData[16];
        for(int c=0;c<16;c++) if((columnMask&(1<<c))!=0) out[c]=convert(tile,c,materials);
        return out;
    }
    VoxelColumnData convert(DhTerrainTile tile, int column) { return convert(tile,column,materials(tile)); }
    private VoxelColumnData convert(DhTerrainTile tile, int column, List<Material> materials) {
        int height=tile.world().height();
        if ((height&15)!=0 || (tile.world().minY()&15)!=0) throw new IllegalArgumentException("Unaligned world height");
        int tx=column/4*16, tz=column%4*16;
        // Validate all 256 vertical columns BEFORE constructing a replacement.
        for(int x=0;x<16;x++) for(int z=0;z<16;z++)
            if(tile.coverage((tx+x)*64+tz+z)!=DhTerrainTile.Coverage.COMPLETE) return null;
        var sections=new VoxelColumnData.SectionData[height/16];
        for(int sy=0;sy<sections.length;sy++) sections[sy]=new VoxelColumnData.SectionData(
                tile.world().minY()/16+sy, SectionConstruction.empty(biomes), new DataLayer(), new DataLayer());
        for(int x=0;x<16;x++) for(int z=0;z<16;z++) {
            int index=(tx+x)*64+tz+z;
            int[] carried=carriedLight(tile,index,materials);
            for(int i=0;i<tile.spanCount(index);i++) {
                var span=tile.span(index,i); var material=materials.get(span.material());
                int carry=carried==null?0:carried[i];
                // DH renders a side face exposed through an air run with the sky light of
                // the floor below it; Voxy lights faces from the neighbouring air voxel.
                // Give the whole run the floor's sky light (identical for official DH,
                // which records the floor's value; brightens DH Support overhangs to match).
                int floor=floorLight(tile,index,span,materials);
                for(int y=span.bottom();y<span.bottom()+span.height();y++) {
                    var section=sections[y/16];
                    section.section().setBlockState(x,y&15,z,material.state,false);
                    section.blockLight().set(x,y&15,z,span.blockLight());
                    section.skyLight().set(x,y&15,z,floor>=0?floor&15
                            :carry>0?Math.max(0,carry-material.carryLoss*(span.bottom()+span.height()-y))
                            :sky(tile,span,material,y));
                    // Sample the (2,2,2) voxel at each 4x4x4 biome-cell center.
                    if((x&3)==2 && (z&3)==2 && (y&3)==2)
                        ((PalettedContainer<Holder<Biome>>)section.section().getBiomes())
                                .set(x/4,(y&15)/4,z/4,material.biome);
                }
            }
            for(int i=0;i<tile.spanCount(index);i++) {
                var span=tile.span(index,i);int light=floorLight(tile,index,span,materials);
                if(light>=0) {
                    int y=span.bottom();var section=sections[y/16];
                    section.blockLight().set(x,y&15,z,light>>>4);
                    section.skyLight().set(x,y&15,z,light&15);
                }
            }
        }
        return new VoxelColumnData(sections,0);
    }
    private List<Material> materials(DhTerrainTile tile) {
        var materials=new ArrayList<Material>();
        for(var m:tile.materials()) {
            var state=Block.BLOCK_STATE_REGISTRY.byId(resolver.blockIdForDh(m));
            int waterSkyLoss=!state.getFluidState().isEmpty()
                    ?Math.max(1,state.getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO)):0;
            materials.add(new Material(state,
                    biomes.asHolderIdMap().byId(resolver.biomeIdFor(m.biome())),waterSkyLoss,carryLoss(state)));
        }
        return materials;
    }
    /** DH's {@code BlockStateWrapper} opacity: 0 transparent, 1 translucent fluid, 16 opaque. */
    static int dhOpacity(BlockState state) {
        if(state.isAir()) return 0;
        boolean occludes=state.canOcclude();
        if(!state.getFluidState().isEmpty() && !occludes) return 1;
        if(state.propagatesSkylightDown(EmptyBlockGetter.INSTANCE,BlockPos.ZERO) && !occludes) return 0;
        return 16;
    }
    /**
     * The sky light one voxel of {@code state} costs when light is carried down through it,
     * or 0 when the block is outside the carrying set. The set is what DH's lighting treats
     * as opaque although vanilla lets sky light through it: opaque to DH, not occluding, no
     * fluid, light dampening below 15. That is leaves, and a few others (ice, slime and
     * honey blocks, mangrove roots, cobweb, spawners, powder snow, copper grates).
     */
    static int carryLoss(BlockState state) {
        if(dhOpacity(state)!=16 || state.canOcclude() || !state.getFluidState().isEmpty())return 0;
        int dampening=state.getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
        return dampening>=15?0:Math.max(1,dampening);
    }
    /**
     * Dark tree crowns (issue #383). DH lights a non-transparent span only when the span
     * above it is transparent, and leaves are opaque to it: under a crown's top leaf span
     * every further leaf span is stored with sky 0, and a face lit from such a voxel renders
     * black (inner leaf faces with a better-leaves resource pack, the side faces of logs).
     *
     * <p>Walking the column top-down: a span DH left at sky 0 that is in the carrying set
     * and sits directly under another carrying-set span takes that span's light, minus the
     * block's dampening for each voxel going down. Every span DH lit keeps its value; a span
     * outside the carrying set (air, fluids, stone, logs) keeps DH's value and nothing is
     * carried through it. {@link dev.vox.lss.common.dh.DhSkyLightBake} stays DH's own rule;
     * this runs on its result, for stored replies and baked pushes alike.
     *
     * <p>A span DH lit holds ONE value for all its voxels, however tall it is; in vanilla
     * the light falls by the dampening with every voxel of it. The carry therefore starts
     * from what the lit span's BOTTOM voxel would hold by that rule (its value minus the
     * dampening for each voxel above the bottom one), not from the span's value: under a
     * lit leaf span four blocks tall the next span starts at 15 - 3 - 1, not at 14. The lit
     * span's own bytes stay as DH has them.
     *
     * <p>DH's hidden-block culling merges ground it cannot see into the datapoint above it,
     * so a dark leaf span under a crown can reach down to bedrock. Such a span fades out
     * after at most 14 voxels and stays dark below, which suits what it really is.
     *
     * <p>Only called for a column the decoder marked COMPLETE: its spans tile the world
     * height without gaps or overlaps, so the next span down always touches the one above.
     * In a world without sky light the decoder zeroes every span's sky light, so nothing
     * is ever carried there.
     *
     * @return per span index, the light of the voxel just above a span that takes carried
     *         light (0 for every other span); null when no span in the column does
     */
    private static int[] carriedLight(DhTerrainTile tile,int column,List<Material> materials) {
        int count=tile.spanCount(column);
        boolean candidate=false,ordered=true;
        int previousBottom=Integer.MAX_VALUE;
        for(int i=0;i<count;i++) {
            var span=tile.span(column,i);
            candidate|=span.skyLight()==0 && materials.get(span.material()).carryLoss>0;
            ordered&=span.bottom()<previousBottom;
            previousBottom=span.bottom();
        }
        if(!candidate)return null;
        // DH sends spans top-down; any other order is put in that order by index.
        int[] order=null;
        if(!ordered) {
            long[] keys=new long[count];
            for(int i=0;i<count;i++)keys[i]=(long)tile.span(column,i).bottom()<<32|i;
            Arrays.sort(keys);
            order=new int[count];
            for(int i=0;i<count;i++)order[i]=(int)keys[count-1-i];
        }
        int[] carried=null;
        int carry=0;
        for(int k=0;k<count;k++) {
            int i=order==null?k:order[k];
            var span=tile.span(column,i);
            int loss=materials.get(span.material()).carryLoss;
            if(loss==0){carry=0;continue;}
            if(span.skyLight()==0 && carry>0) {
                if(carried==null)carried=new int[count];
                carried[i]=carry;
                carry=Math.max(0,carry-loss*span.height());
            } else carry=Math.max(0,span.skyLight()-loss*(span.height()-1));
        }
        return carried;
    }
    private static int sky(DhTerrainTile tile,DhTerrainTile.Span span,Material material,int y) {
        // DH merges water despite changing light; Voxy lights faces from the neighboring voxel.
        return tile.world().sky()?Math.max(0,span.skyLight()-material.waterSkyLoss*(span.bottom()+span.height()-y)):0;
    }
    /** Voxy shades the upward floor face from the first AIR voxel above it.
     * DH shades that face from the floor datapoint, not the top of the AIR run. */
    private static int floorLight(DhTerrainTile tile,int column,DhTerrainTile.Span air,List<Material> materials) {
        if(!materials.get(air.material()).state.isAir())return -1;
        for(int i=0;i<tile.spanCount(column);i++) {
            var lower=tile.span(column,i);var material=materials.get(lower.material());
            if(lower.bottom()+lower.height()==air.bottom() && !material.state.isAir())
                return (tile.world().sky()?(material.waterSkyLoss==0?lower.skyLight():air.skyLight()):0)|(lower.blockLight()<<4);
        }
        return -1;
    }
}
