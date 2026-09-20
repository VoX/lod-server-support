package dev.vox.lss.test;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * The gametests' line-neutral chunk-position surface (V-3/T2,
 * version-port-isolation-plan.md — mechanical-rename concentration): every per-MC-line
 * {@code ChunkPos} spelling the gametests touch lives HERE, so a support port flavors
 * this one file instead of sweeping ~100 call sites. This file IS the 1.21.1 flavor
 * ({@code BlockPos} ctor, field coords, {@code asLong}, {@code addRegionTicket}).
 */
final class TestPositions {
    private TestPositions() {}

    /**
     * Line-neutral chunk coordinates: RECORD accessors spell {@code x()}/{@code z()} on
     * every MC line (this line's {@code ChunkPos} exposes fields), so accessor sites
     * carry no port churn; {@link #pos()} hands the real {@code ChunkPos} to any site
     * that still needs one.
     */
    record ChunkAt(int x, int z, ChunkPos pos) {}

    /** The chunk containing a world position (per-line factory: 26.x
     *  {@code ChunkPos.containing}; 1.21.1 the {@code BlockPos} ctor). */
    static ChunkAt chunkAt(BlockPos worldPos) {
        return of(new ChunkPos(worldPos));
    }

    /** Chunk coords direct (the two-int ctor is line-invariant; routed here so accessor
     *  sites read the record, never the per-line {@code ChunkPos} surface). */
    static ChunkAt chunkAt(int chunkX, int chunkZ) {
        return of(new ChunkPos(chunkX, chunkZ));
    }

    /** MC's own packed chunk key (per-line: 26.x {@code pack}; 1.21.1 {@code asLong}).
     *  Distinct from {@code PositionUtil.packPosition} — the LSS wire packing, which is
     *  line-invariant and never routes through here. */
    static long mcChunkKey(int chunkX, int chunkZ) {
        return ChunkPos.asLong(chunkX, chunkZ);
    }

    /** This line keeps the holder lookup protected (public on newer lines). The
     * gametest JVM uses named mappings, so keep this test-only reflection here instead
     * of widening production access merely to inspect the real save predicates. */
    static net.minecraft.server.level.ChunkHolder saveHolder(ServerChunkCache chunkSource, ChunkAt at) {
        try {
            var lookup = net.minecraft.server.level.ChunkMap.class
                    .getDeclaredMethod("getUpdatingChunkIfPresent", long.class);
            lookup.setAccessible(true);
            var chunkMap = ((dev.vox.lss.mixin.AccessorServerChunkCache) chunkSource).getChunkMap();
            return (net.minecraft.server.level.ChunkHolder) lookup.invoke(chunkMap, mcChunkKey(at.x(), at.z()));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to inspect the control chunk's save eligibility", e);
        }
    }

    /** Force-load hold at radius 0 (per-line ticket API: 26.x
     *  {@code addTicketWithRadius(PLAYER_LOADING, pos, 0)}; 1.21.1
     *  {@code addRegionTicket(PLAYER, pos, 0, pos)}). */
    static void holdChunk(ServerChunkCache chunkSource, ChunkAt at) {
        chunkSource.addRegionTicket(TicketType.PLAYER, at.pos(), 0, at.pos());
    }

    /** Release the {@link #holdChunk} ticket. */
    static void releaseChunk(ServerChunkCache chunkSource, ChunkAt at) {
        chunkSource.removeRegionTicket(TicketType.PLAYER, at.pos(), 0, at.pos());
    }

    private static ChunkAt of(ChunkPos p) {
        return new ChunkAt(p.x, p.z, p);
    }
}
