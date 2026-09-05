package dev.vox.lss.config.menu;

/**
 * Whether an option is built into the page at all (as opposed to greyed — that is
 * {@link OptionSpec#enabledBy()}). Enumerable for the same reason as
 * {@link Tooltip.Condition}: tests can assert exactly which options a context hides.
 */
public enum Visibility {
    ALWAYS,
    /** Only with SeeU installed — the "Prefer LSS Far Players" coexist override (E3). */
    SEEU_ONLY,
    /** Only where this loader's tree actually renders far players — the renderer-only
     *  options (show / name tags / full bright / render limit) are hidden wherever a loader/line's render
     *  path is a stub; a shown-but-inert toggle would be a lie where the user is looking. (On
     *  this 1.21.11 line the NeoForge twin is a stub, so they show on Fabric only.) */
    RENDER_AVAILABLE;

    public boolean test(MenuContext ctx) {
        return switch (this) {
            case ALWAYS -> true;
            case SEEU_ONLY -> ctx.seeuPresent();
            case RENDER_AVAILABLE -> ctx.farPlayerRenderAvailable();
        };
    }
}
