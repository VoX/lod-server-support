package dev.vox.lss.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.vox.lss.common.LSSConstants;

public class LSSModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            try {
                var page = net.caffeinemc.mods.sodium.client.config.ConfigManager.CONFIG
                        .getModOptions().stream()
                        .filter(a -> a.configId().equals(LSSConstants.MOD_ID))
                        .findFirst().orElse(null);
                if (page == null) return null;
                return net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen
                        .createScreen(parent, (net.caffeinemc.mods.sodium.client.config.structure.OptionPage) page.pages().get(0));
            } catch (Throwable e) {
                return null;
            }
        };
    }
}
