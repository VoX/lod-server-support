package dev.vox.lss.paper;

/**
 * Platform probe. Folia's presence is detected by its regionizer class — the canonical check
 * per PaperMC docs. Behavioral differences stay tiny by design (the plugin runs the same
 * GlobalRegionScheduler pump on both platforms); this flag exists for the few spots where
 * Folia removes functionality outright (e.g. the save-all command the soak driver maps).
 */
public final class FoliaSupport {

    /** Test seam for {@link #detect}: Class.forName minus the classloading side effects. */
    @FunctionalInterface
    interface ClassLookup {
        Class<?> lookup(String name) throws ClassNotFoundException;
    }

    public static final boolean IS_FOLIA = detect(Class::forName);

    static boolean detect(ClassLookup lookup) {
        try {
            lookup.lookup("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private FoliaSupport() {}
}
