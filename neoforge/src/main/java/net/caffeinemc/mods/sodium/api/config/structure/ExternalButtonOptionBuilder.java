package net.caffeinemc.mods.sodium.api.config.structure;

import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** COMPILE-ONLY STUB — see {@link net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint}. */
public interface ExternalButtonOptionBuilder extends OptionBuilder {
    ExternalButtonOptionBuilder setName(Component name);

    ExternalButtonOptionBuilder setTooltip(Component tooltip);

    ExternalButtonOptionBuilder setScreenConsumer(Consumer<Screen> consumer);
}
