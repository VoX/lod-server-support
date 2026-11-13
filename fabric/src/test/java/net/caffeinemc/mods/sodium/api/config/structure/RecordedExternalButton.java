package net.caffeinemc.mods.sodium.api.config.structure;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;
import java.util.function.Function;

/** RECORDING external button: a row that opens a screen instead of holding a value. */
public class RecordedExternalButton implements ExternalButtonOptionBuilder {
    public final ResourceLocation id;
    public Component name;
    public Component tooltip;
    public Consumer<Screen> screenConsumer;
    public Function<ConfigState, Boolean> enabledProvider;

    RecordedExternalButton(ResourceLocation id) {
        this.id = id;
    }

    @Override
    public ExternalButtonOptionBuilder setName(Component name) {
        this.name = name;
        return this;
    }

    @Override
    public ExternalButtonOptionBuilder setTooltip(Component tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    @Override
    public ExternalButtonOptionBuilder setEnabledProvider(Function<ConfigState, Boolean> provider, ResourceLocation... dependencies) {
        this.enabledProvider = provider;
        return this;
    }

    @Override
    public ExternalButtonOptionBuilder setScreenConsumer(Consumer<Screen> consumer) {
        this.screenConsumer = consumer;
        return this;
    }
}
