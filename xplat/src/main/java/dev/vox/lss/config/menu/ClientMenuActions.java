package dev.vox.lss.config.menu;

import net.minecraft.client.gui.screens.Screen;

/**
 * The one place an {@link ActionSpec.Action} becomes behaviour. Renderers call
 * {@link #open} with the screen the button sits on and know nothing about individual
 * actions; the catalog stays MC-free because the screen types live here.
 */
public final class ClientMenuActions {

    private ClientMenuActions() {
    }

    public static void open(ActionSpec.Action action, Screen parent) {
        switch (action) {
            case REDOWNLOAD_TERRAIN -> RedownloadTerrainConfirmScreen.open(parent);
        }
    }
}
