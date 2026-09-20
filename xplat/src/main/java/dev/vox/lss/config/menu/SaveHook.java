package dev.vox.lss.config.menu;

import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.client.FarPlayerClientSupport;

/**
 * What happens when the user APPLIES an option (sodium-options-page-generations-plan.md
 * D1/D8): every option persists the config; the far-player page additionally pushes the
 * prefs to the server NOW (the E2 review's M2 — a mid-session "Share My Position" flip
 * must not wait for a rejoin; {@code maybeSendPrefs}'s changed-guard makes redundant
 * pushes free). The catalog test pins that every far-player option carries the push
 * variant, so the guarantee is data, not a per-renderer habit.
 *
 * <p>Renderers map these 1:1 onto their storage abstraction: the 0.8+ walker's
 * per-option {@code StorageEventHandler}, the legacy builder's two
 * {@code OptionStorage} proxies (the legacy screen saves once per DISTINCT storage per
 * Apply, so two hooks = two proxies, never one per option).
 */
public enum SaveHook {
    SAVE,
    SAVE_AND_PUSH_FAR_PLAYER_PREFS;

    public void run(LSSClientConfig cfg) {
        cfg.save();
        if (cfg == LSSClientConfig.CONFIG) {
            dev.vox.lss.networking.client.ClientNetGlue.reconcileClientConfig();
        }
        if (this == SAVE_AND_PUSH_FAR_PLAYER_PREFS) {
            FarPlayerClientSupport.onClientConfigChanged();
        }
    }
}
