package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.resources.ResourceLocation;

/** COMPILE-ONLY STUB — see {@link net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint}. */
public interface ModOptionsBuilder {
    ModOptionsBuilder setIcon(ResourceLocation icon);

    ModOptionsBuilder addPage(PageBuilder page);
}
