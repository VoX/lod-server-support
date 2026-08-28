package dev.vox.lss.config.menu;

import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.client.FarPlayerClientSupport;
import dev.vox.lss.networking.client.FarPlayerRenderer;
import dev.vox.lss.platform.LoaderServices;

/**
 * The environment facts the options catalog's CONDITIONAL surfaces read at page-build
 * time (sodium-options-page-generations-plan.md D1): the booleans behind the
 * conditional tooltips ({@link Tooltip.Condition}) and the hidden options
 * ({@link Visibility}). Computed ONCE per page build by {@link #current()} — every
 * renderer resolves the same facts the same way, so a legacy-Sodium page and a modern
 * one never disagree about which tooltip the user sees.
 *
 * <p>WHEN a page is built differs per renderer and is the shipped shape: the 0.8+ walker
 * builds once at Sodium's config registration (game start), the legacy builder on every
 * screen construction — so a config-file edit to {@code enableAdaptiveTransferRate}
 * mid-session is reflected by the legacy tooltip only. Observable for that one fact.
 *
 * <p>A plain record so tests enumerate every combination (the lang-key completeness
 * pin walks {@link Tooltip#keys()} instead, but the resolve tests flip these).
 *
 * @param governorOn               {@code enableAdaptiveTransferRate} — the join-slow-start
 *                                 toggle is inert without it (its tooltip says so)
 * @param xaeroPresent             Xaero's World Map is installed — the map-bridge toggle is
 *                                 inert without it (its tooltip says so)
 * @param seeuPresent              SeeU is installed — the coexist gate overrides "Show Far
 *                                 Players" (its tooltip says so) and reveals the override
 * @param farPlayerRenderAvailable this loader's tree renders far players
 *                                 ({@link FarPlayerRenderer#RENDER_AVAILABLE} — true on both
 *                                 loaders on this line since v0.14.0; where a loader/line's
 *                                 render path is a stub it is false and the renderer-only
 *                                 options hide; "Share My Position" is the prefs carrier and
 *                                 always shows)
 */
public record MenuContext(boolean governorOn, boolean xaeroPresent, boolean seeuPresent,
                          boolean farPlayerRenderAvailable) {

    /** Every fact ON: the default for tests and the tooltip-key enumeration. */
    public static MenuContext all() {
        return new MenuContext(true, true, true, true);
    }

    /** The live facts: the loaded client config + the loader's mod list + the SeeU probe +
     *  the loader tree's render fact. Every lookup is contained — a loader-less unit
     *  context reads as "absent"/"off", the direction whose tooltip claims the LEAST. */
    public static MenuContext current() {
        boolean governor;
        try {
            governor = LSSClientConfig.CONFIG.enableAdaptiveTransferRate;
        } catch (Throwable t) {
            governor = false;
        }
        boolean xaero;
        try {
            xaero = LoaderServices.get().isModLoaded("xaeroworldmap");
        } catch (Throwable t) {
            xaero = false;
        }
        boolean seeu;
        try {
            seeu = FarPlayerClientSupport.isSeeuPresent();
        } catch (Throwable t) {
            seeu = false;
        }
        return new MenuContext(governor, xaero, seeu, FarPlayerRenderer.RENDER_AVAILABLE);
    }
}
