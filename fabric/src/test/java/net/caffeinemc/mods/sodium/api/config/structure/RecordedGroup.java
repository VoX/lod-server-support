package net.caffeinemc.mods.sodium.api.config.structure;

import java.util.ArrayList;
import java.util.List;

public class RecordedGroup implements OptionGroupBuilder {
    /** Everything added, in order: {@link RecordedOption}s and {@link RecordedExternalButton}s. */
    public final List<OptionBuilder> options = new ArrayList<>();

    @Override
    public OptionGroupBuilder addOption(OptionBuilder option) {
        options.add(option);
        return this;
    }
}
