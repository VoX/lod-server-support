package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** RECORDING implementation of the stub interfaces: everything built is retained. */
public class RecordingConfigBuilder implements ConfigBuilder {
    public final List<RecordedMod> mods = new ArrayList<>();

    @Override
    public ModOptionsBuilder registerModOptions(String id, String name, String version) {
        var m = new RecordedMod(id, name, version);
        mods.add(m);
        return m;
    }

    @Override
    public OptionPageBuilder createOptionPage() {
        return new RecordedPage();
    }

    @Override
    public OptionGroupBuilder createOptionGroup() {
        return new RecordedGroup();
    }

    @Override
    public BooleanOptionBuilder createBooleanOption(ResourceLocation id) {
        return new RecordedBoolean(id);
    }

    @Override
    public IntegerOptionBuilder createIntegerOption(ResourceLocation id) {
        return new RecordedInteger(id);
    }

    @Override
    public ExternalButtonOptionBuilder createExternalButtonOption(ResourceLocation id) {
        return new RecordedExternalButton(id);
    }
}
