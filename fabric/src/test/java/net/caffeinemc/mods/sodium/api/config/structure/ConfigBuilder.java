package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.resources.Identifier;

public interface ConfigBuilder {
    ModOptionsBuilder registerModOptions(String id, String name, String version);

    OptionPageBuilder createOptionPage();

    OptionGroupBuilder createOptionGroup();

    BooleanOptionBuilder createBooleanOption(Identifier id);

    IntegerOptionBuilder createIntegerOption(Identifier id);

    ExternalButtonOptionBuilder createExternalButtonOption(Identifier id);
}
