package dev.vox.lss.config.menu;

import java.util.Objects;

/**
 * One button on a settings page that DOES something instead of holding a value
 * (docs/planning/2026-11-12-redownload-terrain-button-plan.md). It is not a setting: the
 * schema, the YAML file and the settings reference never see it, and
 * {@link ClientOptionCatalog#find} never returns one (Sodium 0.8+ keeps the button in the
 * same map as real options, and the draft refresh looks every key of that map up through
 * {@code find}).
 *
 * <p>MC-free like the rest of the catalog: the name and tooltip are translation keys, and
 * the behaviour is an {@link Action} constant that {@code ClientMenuActions} maps to a
 * screen. The tooltip is one fixed key, not a {@link Tooltip}: Sodium's external button
 * takes a single component.
 */
public record ActionSpec(String id, String nameKey, String tooltipKey, Action action) {

    /** What a button does. Renderers hand it to the dispatcher and know nothing else about it. */
    public enum Action {
        /** Clear the download records so the server sends distant terrain again. */
        REDOWNLOAD_TERRAIN
    }

    public ActionSpec {
        Objects.requireNonNull(id, "id");
        if (!id.startsWith("lss:")) {
            throw new IllegalArgumentException("action id must be lss:-namespaced: " + id);
        }
        Objects.requireNonNull(nameKey, id + ": nameKey");
        Objects.requireNonNull(tooltipKey, id + ": tooltipKey");
        Objects.requireNonNull(action, id + ": action");
    }
}
