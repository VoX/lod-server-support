package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.resources.Identifier;

/** COMPILE-ONLY STUB — see {@link net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint}. */
public interface ModOptionsBuilder {
    ModOptionsBuilder setIcon(Identifier icon);

    ModOptionsBuilder addPage(PageBuilder page);
}
