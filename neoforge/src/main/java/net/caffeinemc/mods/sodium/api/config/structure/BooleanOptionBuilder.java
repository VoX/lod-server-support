package net.caffeinemc.mods.sodium.api.config.structure;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** COMPILE-ONLY STUB — see {@link net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint}. */
public interface BooleanOptionBuilder extends StatefulOptionBuilder<Boolean> {
    BooleanOptionBuilder setDefaultValue(Boolean value);

    BooleanOptionBuilder setBinding(Consumer<Boolean> setter, Supplier<Boolean> getter);
}
