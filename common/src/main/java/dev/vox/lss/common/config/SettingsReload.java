package dev.vox.lss.common.config;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Bounded disk preparation followed by one publication on the supplied lifecycle owner. */
public final class SettingsReload<T> implements AutoCloseable {
    private static final ExecutorService IO = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> daemon(runnable, "LSS Settings IO"),
            new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(
            runnable -> daemon(runnable, "LSS Settings Deadlines"));
    private static Thread daemon(Runnable runnable, String name) {
        var thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @FunctionalInterface
    public interface Reconciler<T> {
        CompletableFuture<Void> apply(T previous, T effective, long revision);
    }
    @FunctionalInterface
    public interface Owner {
        CompletableFuture<Void> execute(Runnable action);
    }
    public enum Status { APPLIED, UNCHANGED, ADOPTION_PENDING, RECONCILIATION_FAILED }
    public record Outcome<T>(SettingsHandle.Commit<T> commit, long revision, Status status, String detail) {}

    private final SettingsHandle<T> handle;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean closed;
    private volatile Operation operation;
    private final long deadlineMillis;

    public SettingsReload(SettingsHandle<T> handle) { this(handle, 30_000); }
    SettingsReload(SettingsHandle<T> handle, long deadlineMillis) {
        this.handle = Objects.requireNonNull(handle);
        this.deadlineMillis = deadlineMillis;
    }

    public CompletableFuture<Outcome<T>> reload(Executor owner, Reconciler<T> reconciler) {
        return reload(owner, candidate -> {}, reconciler);
    }

    public CompletableFuture<Outcome<T>> reload(Executor owner, Consumer<T> validator,
                                                Reconciler<T> reconciler) {
        Objects.requireNonNull(owner);
        return reloadOwned(action -> {
            var receipt = new CompletableFuture<Void>();
            try {
                owner.execute(() -> {
                    try { action.run(); receipt.complete(null); }
                    catch (Throwable error) { receipt.completeExceptionally(error); }
                });
            } catch (Throwable error) { receipt.completeExceptionally(error); }
            return receipt;
        }, validator, reconciler);
    }
    public CompletableFuture<Outcome<T>> reloadOwned(Owner owner, Reconciler<T> reconciler) {
        return reloadOwned(owner, candidate -> {}, reconciler);
    }
    public CompletableFuture<Outcome<T>> reloadOwned(Owner owner, Consumer<T> validator,
                                                     Reconciler<T> reconciler) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(validator);
        Objects.requireNonNull(reconciler);
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Settings lifecycle stopped"));
        if (!busy.compareAndSet(false, true))
            return CompletableFuture.failedFuture(new IllegalStateException("Settings reload busy; wait for the current reload"));
        var op = new Operation();
        operation = op;
        if (closed) { op.fail(new CancellationException("Settings lifecycle stopped")); return op.result; }
        op.timer = DEADLINES.schedule(op::timeout, deadlineMillis, TimeUnit.MILLISECONDS);
        try {
            IO.execute(() -> {
                try {
                    var prepared = handle.prepareReload();
                    synchronized (op) {
                        op.prepared = prepared;
                        if (op.cancelled || closed) {
                            handle.cancelPrepared(prepared);
                            op.finish();
                            return;
                        }
                    }
                    owner.execute(() -> {
                        synchronized (op) {
                            if (op.cancelled || closed) {
                                handle.cancelPrepared(prepared);
                                op.finish();
                                return;
                            }
                            try {
                                validator.accept(prepared.document().normalized());
                                op.commit = handle.commit(prepared);
                                op.revision = handle.state().revision();
                                op.adoption = handle.pendingAdoption();
                                if (op.adoption == null) {
                                    op.finish();
                                    op.result.complete(new Outcome<>(op.commit, op.revision, Status.UNCHANGED, "No settings changed"));
                                    return;
                                }
                                reconciler.apply(op.commit.previous(), op.commit.effective(), op.revision)
                                        .whenComplete((ignored, error) -> {
                                            synchronized (op) {
                                                if (error == null && !op.cancelled) handle.acknowledge(op.adoption);
                                                op.finish();
                                                if (!op.cancelled) {
                                                    op.result.complete(new Outcome<>(op.commit, op.revision,
                                                            error == null ? Status.APPLIED : Status.RECONCILIATION_FAILED,
                                                            error == null ? "Reload applied" : "Settings accepted; reconciliation failed: " + message(error)));
                                                }
                                            }
                                        });
                            } catch (Throwable error) {
                                op.fail(error);
                            }
                        }
                    }).whenComplete((ignored, failure) -> {
                        if (failure != null) op.fail(failure);
                    });
                } catch (Throwable error) {
                    op.fail(error);
                }
            });
        } catch (RejectedExecutionException rejected) {
            op.fail(new IllegalStateException("Settings IO busy; retry later", rejected));
        }
        return op.result;
    }

    public boolean busy() { return busy.get(); }
    /** Everything wrong with the file behind a failed read or reload; empty when the failure
     *  is not about a file's contents. */
    public static java.util.List<SettingsProblems.Problem> problems(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error instanceof SettingsException refused ? refused.problems() : java.util.List.of();
    }
    /** The lines to print under "Reload failed: …": the whole list when it says more than the
     *  one message above it (a second problem, or where the first one is). */
    public static java.util.List<String> problemLines(Throwable error) {
        var problems = problems(error);
        if (problems.isEmpty() || problems.size() == 1 && problems.get(0).line() == 0) return java.util.List.of();
        return SettingsProblemText.lines(problems);
    }
    /** What follows "Reload failed: ". When the list of problems is printed below it, this is
     *  the count alone: the strict reader's own message can repeat a name the author typed
     *  (a mis-indented address or player name becomes a setting name), which the list takes
     *  care never to print (0.23.0 review). */
    public static String headline(Throwable error) {
        var lines = problemLines(error);
        return lines.isEmpty() ? message(error) : "the file has " + SettingsProblemText.count(problems(error).size()) + ":";
    }
    public static String message(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private final class Operation {
        final CompletableFuture<Outcome<T>> result = new CompletableFuture<>();
        SettingsHandle.Prepared<T> prepared;
        SettingsHandle.Commit<T> commit;
        SettingsHandle.Adoption<T> adoption;
        long revision;
        boolean cancelled;
        ScheduledFuture<?> timer;

        synchronized void timeout() {
            if (commit == null) {
                cancelled = true;
                if (prepared != null) handle.cancelPrepared(prepared);
                result.completeExceptionally(new TimeoutException("Settings reload timed out before publication; no settings changed"));
                // Preparation may still be reading; it releases busy on observing cancellation.
                if (prepared != null) finish();
            } else {
                result.complete(new Outcome<>(commit, revision, Status.ADOPTION_PENDING,
                        "Settings accepted; subsystem adoption pending"));
            }
        }
        synchronized void fail(Throwable error) {
            cancelled = true;
            finish();
            if (commit == null) {
                if (prepared != null) handle.cancelPrepared(prepared);
                result.completeExceptionally(error);
            } else {
                result.complete(new Outcome<>(commit, revision, Status.RECONCILIATION_FAILED,
                        "Settings accepted; reconciliation failed: " + message(error)));
            }
        }
        void finish() {
            if (timer != null) timer.cancel(false);
            if (operation == this) {
                operation = null;
                busy.set(false);
            }
        }
    }

    @Override public void close() {
        closed = true;
        handle.close();
        var op = operation;
        if (op != null) synchronized (op) {
            op.cancelled = true;
            op.finish();
            op.result.completeExceptionally(new CancellationException("Settings lifecycle stopped"));
        }
    }
}
