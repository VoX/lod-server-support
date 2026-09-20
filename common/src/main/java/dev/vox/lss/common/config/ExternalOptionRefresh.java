package dev.vox.lss.common.config;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Rebase one cached UI binding after a child action, preserving an explicit draft. */
public final class ExternalOptionRefresh {
    private ExternalOptionRefresh() {}
    public static <T> Runnable capture(Supplier<T> live, Supplier<T> staged, BooleanSupplier changed,
                                      Runnable reload, Consumer<T> restoreDraft) {
        T original = live.get();
        T draft = staged.get();
        boolean pending = changed.getAsBoolean();
        return new Runnable() {
            private boolean returned;
            @Override public void run() {
                if (returned) return;
                returned = true;
                if (Objects.equals(original, live.get())) return;
                reload.run();
                if (pending) restoreDraft.accept(draft);
            }
        };
    }
}
