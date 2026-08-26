package net.caffeinemc.mods.sodium.api.config.structure;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** COMPILE-ONLY STUB — see {@link net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint}. */
public interface StatefulOptionBuilder<V> extends OptionBuilder {
    StatefulOptionBuilder<V> setName(Component name);

    OptionBuilder setEnabledProvider(Function<ConfigState, Boolean> provider, Identifier... dependencies);

    StatefulOptionBuilder<V> setStorageHandler(StorageEventHandler handler);

    StatefulOptionBuilder<V> setTooltip(Component tooltip);

    StatefulOptionBuilder<V> setImpact(OptionImpact impact);

    StatefulOptionBuilder<V> setDefaultValue(V value);

    StatefulOptionBuilder<V> setBinding(Consumer<V> setter, Supplier<V> getter);
}
