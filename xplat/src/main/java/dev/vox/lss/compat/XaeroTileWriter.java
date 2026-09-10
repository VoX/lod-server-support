package dev.vox.lss.compat;


import java.util.ArrayList;
import java.util.List;

import static dev.vox.lss.compat.XaeroSession.*;

/** Main-thread native writes under the existing Xaero monitor ladder. */
final class XaeroTileWriter {
    private final XaeroSession session;
    XaeroTileWriter(XaeroSession session) { this.session = session; }

    /**
     * Will the NATIVE writer actually write this chunk? Its edge rule (decompiled
     * {@code writeChunk}) requires the chunk AND all 8 neighbors loaded — so the
     * outermost ring of loaded vanilla chunks is never natively written. Skipping
     * on "loaded" alone left that ring written by NOBODY: a 1-chunk black circle
     * at the vanilla/LOD boundary around every join point (field-tested 2026-08-23
     * — the columns had been served during the join window, and the broad skip
     * threw the tiles away). A loaded-but-edge chunk is bridge-written instead;
     * the native writer reclaims it on its clean-flag once fully surrounded.
     */
    boolean nativelyWritable(Object world, int chunkX, int chunkZ) {
        if (!this.session.levelOps.isChunkLoaded(world, chunkX, chunkZ)) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0)
                        && !this.session.levelOps.isChunkLoaded(world, chunkX + dx, chunkZ + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * One entry against its region — the decompiled {@code MapWriter.writeChunk}
     * region discipline: {@code writerThreadPauseSync} + {@code !isWritingPaused()}
     * (the save-race exclusion), the region monitor for load-state/visit/resting,
     * {@code setBeingWritten(true)} set and NEVER cleared by us (save-eligibility —
     * the save path owns the reset), tile-chunk creation with its cache flags, then
     * the pixel commit. Load REQUESTS live in the grant phase
     * ({@link #requestRegionLoad}) — an unloaded region answers an AWAITING flavor
     * here, classified from {@code canRequestReload_unsynced()} + loadState in the
     * same monitor read.
     */
    Outcome commitEntry(Object mp, Object dimensionId,
                                XaeroTileExtractor.PreparedTile tile,
                                boolean loadNew, boolean update) {
        try {
            int chunkX = tile.chunkX();
            int chunkZ = tile.chunkZ();
            int tileChunkX = chunkX >> 2;
            int tileChunkZ = chunkZ >> 2;
            int localTcX = tileChunkX & 7;
            int localTcZ = tileChunkZ & 7;
            Object region = this.session.h.getLeafMapRegion.invoke(mp, SURFACE_LAYER,
                    tileChunkX >> 3, tileChunkZ >> 3, true);
            if (region == null) return Outcome.DEFERRED; // detection-completeness race
            Object writerPause = this.session.h.writerThreadPauseSync.invoke(region);
            synchronized (writerPause) {
                if ((boolean) this.session.h.regionIsWritingPaused.invoke(region)) return Outcome.DEFERRED;
                boolean resting;
                boolean createdTileChunk = false;
                Object tileChunk = null;
                synchronized (region) {
                    byte loadState = (byte) this.session.h.getLoadState.invoke(region);
                    boolean proper = loadState == 2;
                    if (proper) this.session.h.registerVisit.invoke(region);
                    resting = (boolean) this.session.h.isResting.invoke(region);
                    if (resting) {
                        this.session.h.setBeingWritten.invoke(region, true);
                        if (proper) {
                            tileChunk = this.session.h.regionGetChunk.invoke(region, localTcX, localTcZ);
                            if (tileChunk == null) {
                                tileChunk = this.session.h.newMapTileChunk.invoke(region, tileChunkX, tileChunkZ);
                                this.session.h.regionSetChunk.invoke(region, localTcX, localTcZ, tileChunk);
                                this.session.h.tileChunkSetLoadState.invoke(tileChunk, (byte) 2);
                                this.session.h.setAllCachePrepared.invoke(region, false);
                                createdTileChunk = true;
                            }
                        }
                    }
                    if (!proper) {
                        // Fresh regions NEVER self-promote to loadState 2 — the grant
                        // phase requests the load; this entry (and its whole bucket)
                        // just waits, classified from Xaero's own state right here in
                        // the region monitor (the memoryless window's input):
                        // requestable now, cache-parked (needs the 3→4 revival), or
                        // genuinely in flight (queued/loading/refreshing — occupies a
                        // window slot).
                        if ((boolean) this.session.h.canRequestReload.invoke(region)) {
                            return Outcome.AWAITING_REQUESTABLE;
                        }
                        return loadState == 3 ? Outcome.AWAITING_PARKED
                                : Outcome.AWAITING_IN_FLIGHT;
                    }
                }
                if (!resting || tileChunk == null) return Outcome.DEFERRED;
                if ((int) this.session.h.tileChunkGetLoadState.invoke(tileChunk) != 2) {
                    return Outcome.DEFERRED_TILE;
                }
                Object leafTexture = this.session.h.getLeafTexture.invoke(tileChunk);
                if ((boolean) this.session.h.shouldDownloadFromPBO.invoke(leafTexture)) {
                    return Outcome.DEFERRED_TILE;
                }

                Object existingTile = this.session.h.getTile.invoke(tileChunk, chunkX & 3, chunkZ & 3);
                if (existingTile == null ? !loadNew : !update) {
                    if (createdTileChunk) {
                        synchronized (region) {
                            this.session.h.regionSetChunk.invoke(region, localTcX, localTcZ, null);
                        }
                    }
                    return Outcome.SKIPPED_SETTINGS;
                }
                // Capture dependencies before any pixel mutation. A loaded neighbor
                // temporarily busy with a PBO/load must not lose its one invalidation.
                var neighbors = this.slopeNeighbors(region, tile);
                if (neighbors == null || !this.session.rebuilds.hasRebuildCapacity(dimensionId, tile, neighbors)) {
                    if (createdTileChunk) {
                        synchronized (region) {
                            this.session.h.regionSetChunk.invoke(region, localTcX, localTcZ, null);
                        }
                    }
                    return Outcome.DEFERRED; // cap-exempt, queued bytes retained
                }
                this.commitPixels(mp, dimensionId, region, tileChunk, createdTileChunk,
                        localTcX, localTcZ, tile, neighbors);
                return Outcome.COMMITTED;
            }
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            this.session.noteFailure(t);
            return Outcome.FAILED;
        }
    }

    /** Per-tile commit sequence after the settings and dependency admission gates. */
    void commitPixels(Object mp, Object dimensionId, Object region, Object tileChunk,
                                 boolean createdTileChunk, int localTcX, int localTcZ,
                                 XaeroTileExtractor.PreparedTile tile,
                                 List<SlopeNeighbor> neighbors) throws Throwable {
        int insideX = tile.chunkX() & 3;
        int insideZ = tile.chunkZ() & 3;
        Object mapTile = this.session.h.getTile.invoke(tileChunk, insideX, insideZ);
        if (mapTile == null) {
            Object pool = this.session.h.getTilePool.invoke(mp);
            String dimensionToken = (String) this.session.h.getCurrentDimension.invoke(mp);
            mapTile = this.session.h.poolGet.invoke(pool, dimensionToken, tile.chunkX(), tile.chunkZ());
            this.session.h.tileChunkSetChanged.invoke(tileChunk, true);
        }
        Object overlayManager = this.session.h.getOverlayManager.invoke(mp);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int i = x * 16 + z;
                Object block = this.session.h.newMapBlock.invoke();
                this.session.h.prepareForWriting.invoke(block, tile.worldBottomY());
                var runs = tile.overlays()[i];
                if (runs != null) {
                    for (var run : runs) {
                        // Overlay.getParametres packs light << 4 unmasked too (sweep B m7).
                        byte runLight = (byte) Math.max(0, Math.min(15, run.light()));
                        Object overlay = this.session.h.newOverlay.invoke(run.state(), runLight, run.glowing());
                        this.session.h.increaseOpacity.invoke(overlay, run.opacity());
                        Object original = this.session.h.getOriginal.invoke(overlayManager, overlay);
                        this.session.h.addOverlay.invoke(block, original);
                    }
                }
                // Light must stay 0..15: MapBlock.getParametres packs it UNMASKED next to
                // the height bits and the loader masks on read — an out-of-range value
                // would be a one-way file corruption (sweep B m7). The extractor already
                // delivers a nibble; the clamp is the belt.
                byte light = (byte) Math.max(0, Math.min(15, tile.light()[i]));
                this.session.h.blockWrite.invoke(block, tile.floorState()[i],
                        (int) tile.floorY()[i], (int) tile.topY()[i],
                        tile.biome()[i], light, tile.glowing()[i], false);
                this.session.h.setBlock.invoke(mapTile, x, z, block);
            }
        }
        this.session.h.setWorldInterpretationVersion.invoke(mapTile, this.session.h.interpretationVersion);
        this.session.h.setWrittenCave.invoke(mapTile, SURFACE_LAYER,
                (int) this.session.h.getCaveModeDepthConfig.invoke(mp));
        this.session.h.tileChunkSetChanged.invoke(tileChunk, true);
        this.session.h.setTile.invoke(tileChunk, insideX, insideZ, mapTile,
                this.session.h.getBlockStateShortShapeCache.invoke(mp), mp);
        this.session.h.setWrittenOnce.invoke(mapTile, true);
        this.session.h.setLoaded.invoke(mapTile, true);
        if (createdTileChunk) {
            if ((boolean) this.session.h.includeInSave.invoke(tileChunk)) {
                this.session.h.setHasHadTerrain.invoke(tileChunk);
            }
            Object highlights = this.session.h.getMapRegionHighlightsPreparer.invoke(mp);
            this.session.h.highlightsPrepare.invoke(highlights, region, localTcX, localTcZ, false);
        }
        // The native writer ends a write by rebuilding the tile chunk's texture
        // INSIDE this gate (updateBuffers, then setChanged(false)); ours is
        // coalesced per tile chunk (plan §15) — the change stays marked and the
        // rebuild runs from flushPendingUpdates under these same gates. NEVER the
        // setToUpdateBuffers flag: Xaero's preUpload sweep consumes it with no
        // isResting() check, i.e. possibly after the region was queued for
        // cache-saving on prepared textures — the saver then throws ("Trying to
        // save cache for a region with cache not prepared", 3 crashes/hour live).
        this.session.rebuilds.notePendingUpdate(mp, dimensionId, region, tileChunk, localTcX, localTcZ,
                tile.chunkX() >> 2, tile.chunkZ() >> 2);
        for (var neighbor : neighbors) {
            // Native slope dependencies: south row, east column excluding its
            // first pixel, and the southeast corner (MapWriter.writeChunk).
            if (neighbor.dx() == 0) {
                for (int x = 0; x < 16; x++) this.invalidateSlope(neighbor.mapTile(), x, 0);
            } else if (neighbor.dz() == 0) {
                for (int z = 1; z < 16; z++) this.invalidateSlope(neighbor.mapTile(), 0, z);
            } else {
                this.invalidateSlope(neighbor.mapTile(), 0, 0);
            }
            this.session.h.tileChunkSetChanged.invoke(neighbor.tileChunk(), true);
            this.session.rebuilds.notePendingUpdate(mp, dimensionId, region, neighbor.tileChunk(),
                    (neighbor.chunkX() >> 2) & 7, (neighbor.chunkZ() >> 2) & 7,
                    neighbor.chunkX() >> 2, neighbor.chunkZ() >> 2);
        }
    }

    /** Native dependencies are region-local: no foreign-region locks or loads.
     *  Called under this region's writer-pause gate, like the native writer. */
    List<SlopeNeighbor> slopeNeighbors(Object region, XaeroTileExtractor.PreparedTile tile)
            throws Throwable {
        var neighbors = new ArrayList<SlopeNeighbor>(3);
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int cx = tile.chunkX() + dx;
                int cz = tile.chunkZ() + dz;
                if ((cx >> 5) != (tile.chunkX() >> 5) || (cz >> 5) != (tile.chunkZ() >> 5)) continue;
                Object tc = this.session.h.regionGetChunk.invoke(region, (cx >> 2) & 7, (cz >> 2) & 7);
                if (tc == null) continue;
                Object mapTile = this.session.h.getTile.invoke(tc, cx & 3, cz & 3);
                if (mapTile == null || !(boolean) this.session.h.tileIsLoaded.invoke(mapTile)) continue;
                if ((int) this.session.h.tileChunkGetLoadState.invoke(tc) != 2
                        || (boolean) this.session.h.shouldDownloadFromPBO.invoke(this.session.h.getLeafTexture.invoke(tc))) {
                    return null;
                }
                neighbors.add(new SlopeNeighbor(tc, mapTile, cx, cz, dx, dz));
            }
        }
        return neighbors;
    }

    void invalidateSlope(Object tile, int x, int z) throws Throwable {
        Object block = this.session.h.getBlock.invoke(tile, x, z);
        if (block != null) this.session.h.setSlopeUnknown.invoke(block, true);
    }


    record SlopeNeighbor(Object tileChunk, Object mapTile, int chunkX, int chunkZ,
                                 int dx, int dz) {}
}
