package dev.vox.lss.config.menu;

import java.util.List;
import java.util.Objects;

/**
 * One page (tab) of LSS options. {@code titleKey} is the bare title ("General",
 * "Far Players"); renderers decide the framing — the 0.8+ API nests pages under the
 * mod's own entry, the legacy screen lists tabs beside Sodium's so the legacy builder
 * prefixes the brand (plan D7). {@code id} is a stable test/log handle, never displayed.
 *
 * <p>{@code actions} are the page's buttons ({@link ActionSpec}), shown after the option
 * groups. They are not options: {@link #options()} and the groups never contain one.
 */
public record PageSpec(String id, String titleKey, List<GroupSpec> groups, List<ActionSpec> actions) {

    public PageSpec {
        Objects.requireNonNull(id);
        Objects.requireNonNull(titleKey);
        groups = List.copyOf(groups);
        if (groups.isEmpty()) {
            throw new IllegalArgumentException(id + ": empty page");
        }
        actions = List.copyOf(actions);
    }

    /** A page of options only. */
    public static PageSpec of(String id, String titleKey, GroupSpec... groups) {
        return new PageSpec(id, titleKey, List.of(groups), List.of());
    }

    /** Every option on the page in display order (groups flattened). */
    public List<OptionSpec> options() {
        return groups.stream().flatMap(g -> g.options().stream()).toList();
    }
}
