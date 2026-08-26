package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.network.chat.Component;

/** COMPILE-ONLY STUB — see {@link net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint}. */
public interface OptionPageBuilder extends PageBuilder {
    OptionPageBuilder setName(Component name);

    OptionPageBuilder addOptionGroup(OptionGroupBuilder group);
}
