package dev.vox.lss.networking.server;

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

/**
 * 1.20.1 backport shim for the not-yet-existing {@code PalettedContainerFactory}
 * (a 1.21.5+/26.x class). Mirrors the factory methods the test corpus infra uses,
 * built from the 1.20.1 vanilla equivalents (the codecRW construction below is the
 * same codec vanilla's private {@code ChunkSerializer.BLOCK_STATE_CODEC} wraps —
 * Paper's twin references that field directly because Paper patches it public).
 * Public: the fabric tests using it span networking.server and networking.client.
 */
public final class TestContainerFactory {
    private final Registry<Biome> biomeRegistry;

    private TestContainerFactory(Registry<Biome> biomeRegistry) {
        this.biomeRegistry = biomeRegistry;
    }

    public static TestContainerFactory create(RegistryAccess registryAccess) {
        return new TestContainerFactory(registryAccess.registryOrThrow(Registries.BIOME));
    }

    /** 1.20.1's empty-section ctor is {@code LevelChunkSection(Registry<Biome>)}. */
    public Registry<Biome> biomeRegistry() {
        return biomeRegistry;
    }

    public Codec<PalettedContainer<BlockState>> blockStatesContainerCodec() {
        return PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    }

    public Codec<PalettedContainerRO<Holder<Biome>>> biomeContainerCodec() {
        return PalettedContainer.codecRO(
                biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES,
                biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
    }

    public PalettedContainer<BlockState> createForBlockStates() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
    }

    public PalettedContainer<Holder<Biome>> createForBiomes() {
        return new PalettedContainer<>(biomeRegistry.asHolderIdMap(),
                biomeRegistry.getHolderOrThrow(Biomes.PLAINS),
                PalettedContainer.Strategy.SECTION_BIOMES);
    }
}
