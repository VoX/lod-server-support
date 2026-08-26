package net.caffeinemc.mods.sodium.api.config;

import net.minecraft.resources.Identifier;

/** COMPILE-ONLY STUB — see {@link ConfigEntryPoint}. */
public interface ConfigState {
    boolean readBooleanOption(Identifier id);

    int readIntOption(Identifier id);

    <E extends Enum<E>> E readEnumOption(Identifier id, Class<E> enumClass);
}
