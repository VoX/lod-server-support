package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.resources.Identifier;

/** COMPILE-ONLY STUB — see {@link net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint}. */
public interface ConfigBuilder {
    ModOptionsBuilder registerModOptions(String modId, String name, String version);

    OptionPageBuilder createOptionPage();

    OptionGroupBuilder createOptionGroup();

    BooleanOptionBuilder createBooleanOption(Identifier id);

    IntegerOptionBuilder createIntegerOption(Identifier id);
}
