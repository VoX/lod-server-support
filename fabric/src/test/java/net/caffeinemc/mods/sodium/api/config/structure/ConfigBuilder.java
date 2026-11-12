package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.resources.ResourceLocation;

public interface ConfigBuilder {
    ModOptionsBuilder registerModOptions(String id, String name, String version);

    OptionPageBuilder createOptionPage();

    OptionGroupBuilder createOptionGroup();

    BooleanOptionBuilder createBooleanOption(ResourceLocation id);

    IntegerOptionBuilder createIntegerOption(ResourceLocation id);

    ExternalButtonOptionBuilder createExternalButtonOption(ResourceLocation id);
}
