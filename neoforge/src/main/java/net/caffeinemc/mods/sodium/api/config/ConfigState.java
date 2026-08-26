package net.caffeinemc.mods.sodium.api.config;

import net.minecraft.resources.ResourceLocation;

/** COMPILE-ONLY STUB — see {@link ConfigEntryPoint}. */
public interface ConfigState {
    boolean readBooleanOption(ResourceLocation id);

    int readIntOption(ResourceLocation id);

    <E extends Enum<E>> E readEnumOption(ResourceLocation id, Class<E> enumClass);
}
