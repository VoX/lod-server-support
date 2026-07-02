package dev.vox.lss.paper;

import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

/**
 * 1.20.1 backport shim for the not-yet-existing {@code PalettedContainerFactory}
 * (a 1.21.5+/26.x class). Mirrors the four factory methods the test corpus infra uses,
 * built from the 1.20.1 vanilla equivalents (ChunkSerializer.BLOCK_STATE_CODEC and
 * PalettedContainer.codecRO — the same codecs vanilla's ChunkSerializer.read uses).
 */
final class TestContainerFactory {
    private final Registry<Biome> biomeRegistry;

    private TestContainerFactory(Registry<Biome> biomeRegistry) {
        this.biomeRegistry = biomeRegistry;
    }

    static TestContainerFactory create(RegistryAccess registryAccess) {
        return new TestContainerFactory(registryAccess.registryOrThrow(Registries.BIOME));
    }

    /** 1.20.1's empty-section ctor is {@code LevelChunkSection(Registry<Biome>)}. */
    Registry<Biome> biomeRegistry() {
        return biomeRegistry;
    }

    Codec<PalettedContainer<BlockState>> blockStatesContainerCodec() {
        return ChunkSerializer.BLOCK_STATE_CODEC;
    }

    Codec<PalettedContainerRO<Holder<Biome>>> biomeContainerCodec() {
        return PalettedContainer.codecRO(
                biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES,
                biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
    }

    PalettedContainer<BlockState> createForBlockStates() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
    }

    PalettedContainer<Holder<Biome>> createForBiomes() {
        return new PalettedContainer<>(biomeRegistry.asHolderIdMap(),
                biomeRegistry.getHolderOrThrow(Biomes.PLAINS),
                PalettedContainer.Strategy.SECTION_BIOMES);
    }
}
