package dev.vox.lss.config;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import dev.vox.lss.common.LSSConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class LSSConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var cfg = LSSClientConfig.CONFIG;
        StorageEventHandler save = cfg::save;

        var version = FabricLoader.getInstance().getModContainer(LSSConstants.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        var mod = builder.registerModOptions(LSSConstants.MOD_ID, "LOD Server Support", version)
                .setIcon(Identifier.parse("lss:icon.png"));

        var page = builder.createOptionPage();
        page.setName(Component.translatable("lss.config.page"));

        // Receive Server LODs
        var receiveGroup = builder.createOptionGroup();
        var receiveOption = builder.createBooleanOption(Identifier.parse("lss:receive_server_lods"));
        receiveOption.setName(Component.translatable("lss.config.receive_server_lods"));
        receiveOption.setTooltip(Component.translatable("lss.config.receive_server_lods.tooltip"));
        receiveOption.setImpact(OptionImpact.HIGH);
        receiveOption.setDefaultValue(true);
        receiveOption.setBinding(v -> cfg.receiveServerLods = v, () -> cfg.receiveServerLods);
        receiveOption.setStorageHandler(save);
        receiveGroup.addOption(receiveOption);
        page.addOptionGroup(receiveGroup);

        var enabledDep = new Identifier[]{Identifier.parse("lss:receive_server_lods")};

        // LOD Distance
        var distanceGroup = builder.createOptionGroup();
        var distanceOption = builder.createIntegerOption(Identifier.parse("lss:lod_distance"));
        distanceOption.setName(Component.translatable("lss.config.lod_distance"));
        distanceOption.setTooltip(Component.translatable("lss.config.lod_distance.tooltip"));
        distanceOption.setDefaultValue(0);
        distanceOption.setRange(new Range(0, 512, 1));
        distanceOption.setValueFormatter(v -> v == 0
                ? Component.translatable("lss.config.lod_distance.server_default")
                : Component.literal(Integer.toString(v)));
        distanceOption.setBinding(v -> cfg.lodDistanceChunks = v, () -> cfg.lodDistanceChunks);
        distanceOption.setStorageHandler(save);
        distanceOption.setEnabledProvider(s -> s.readBooleanOption(enabledDep[0]), enabledDep);
        distanceGroup.addOption(distanceOption);
        page.addOptionGroup(distanceGroup);

        // Off-Thread Processing
        var offThreadGroup = builder.createOptionGroup();
        var offThreadOption = builder.createBooleanOption(Identifier.parse("lss:off_thread_processing"));
        offThreadOption.setName(Component.translatable("lss.config.off_thread_processing"));
        offThreadOption.setTooltip(Component.translatable("lss.config.off_thread_processing.tooltip"));
        offThreadOption.setDefaultValue(true);
        offThreadOption.setBinding(v -> cfg.offThreadSectionProcessing = v, () -> cfg.offThreadSectionProcessing);
        offThreadOption.setStorageHandler(save);
        offThreadOption.setEnabledProvider(s -> s.readBooleanOption(enabledDep[0]), enabledDep);
        offThreadGroup.addOption(offThreadOption);
        page.addOptionGroup(offThreadGroup);

        mod.addPage(page);
    }
}
