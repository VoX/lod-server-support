package net.caffeinemc.mods.sodium.api.config.structure;

import java.util.function.Consumer;
import java.util.function.Supplier;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.option.Range;

/** COMPILE-ONLY STUB — see {@link net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint}. */
public interface IntegerOptionBuilder extends StatefulOptionBuilder<Integer> {
    IntegerOptionBuilder setDefaultValue(Integer value);

    IntegerOptionBuilder setRange(Range range);

    IntegerOptionBuilder setValueFormatter(ControlValueFormatter formatter);

    IntegerOptionBuilder setBinding(Consumer<Integer> setter, Supplier<Integer> getter);
}
