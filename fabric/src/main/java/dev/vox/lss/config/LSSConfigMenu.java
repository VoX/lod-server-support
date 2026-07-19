package dev.vox.lss.config;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import dev.vox.lss.common.LSSConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.Optional;

public class LSSConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var cfg = LSSClientConfig.CONFIG;
        StorageEventHandler save = cfg::save;

        var container = FabricLoader.getInstance().getModContainer(LSSConstants.MOD_ID);
        var version = container
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        // Display-only branding flows from the jar's OWN descriptor: the Voxy Server Side
        // repackage rewrites fabric.mod.json name/icon (identity fields untouched), so the
        // VSS jar brands this screen without a code fork. MOD_ID stays the registration key.
        var displayName = container
                .map(c -> c.getMetadata().getName())
                .orElse("LOD Server Support");
        var mod = builder.registerModOptions(LSSConstants.MOD_ID, displayName, version)
                .setIcon(iconFromMetadata(container));

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
        distanceOption.setRange(new Range(0, LSSConstants.MAX_LOD_DISTANCE, 1));
        distanceOption.setValueFormatter(v -> v == 0
                ? Component.translatable("lss.config.lod_distance.server_default")
                : Component.literal(Integer.toString(v)));
        distanceOption.setBinding(v -> cfg.lodDistanceChunks = v, () -> cfg.lodDistanceChunks);
        distanceOption.setStorageHandler(save);
        distanceOption.setEnabledProvider(s -> s.readBooleanOption(enabledDep[0]), enabledDep);
        distanceGroup.addOption(distanceOption);
        page.addOptionGroup(distanceGroup);

        mod.addPage(page);
    }

    /** The descriptor's `icon` path as a resource Identifier ("assets/&lt;ns&gt;/&lt;path&gt;" →
     *  "&lt;ns&gt;:&lt;path&gt;"), falling back to the LSS icon. Display-only — a malformed or
     *  missing path must degrade, never throw. */
    private static Identifier iconFromMetadata(Optional<ModContainer> container) {
        return container.flatMap(c -> c.getMetadata().getIconPath(32))
                .filter(p -> p.startsWith("assets/"))
                .map(p -> p.substring("assets/".length()))
                .map(p -> {
                    int slash = p.indexOf('/');
                    return slash > 0
                            ? Identifier.tryParse(p.substring(0, slash) + ":" + p.substring(slash + 1))
                            : null;
                })
                .filter(Objects::nonNull)
                .orElse(Identifier.parse("lss:icon.png"));
    }
}
