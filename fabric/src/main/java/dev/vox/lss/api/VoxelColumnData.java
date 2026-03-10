package dev.vox.lss.api;

import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * Column data dispatched to consumers, containing MC-native chunk section objects.
 * Sections include block states, biomes, and optional light data.
 */
public record VoxelColumnData(SectionData[] sections, long columnTimestamp) {
    public record SectionData(
            int sectionY,
            LevelChunkSection section,
            DataLayer blockLight,
            DataLayer skyLight
    ) {}
}
