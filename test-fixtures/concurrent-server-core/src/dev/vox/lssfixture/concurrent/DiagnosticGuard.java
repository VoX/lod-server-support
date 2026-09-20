package dev.vox.lssfixture.concurrent;
import java.util.function.Consumer;
/** Diagnostic observation cannot replace the already-computed workload decision. */
public final class DiagnosticGuard {
    private DiagnosticGuard() {}
    public static void observe(Runnable observation,Consumer<Throwable> failure){
        try { observation.run(); }
        catch(Throwable error) {
            try { failure.accept(error); }
            catch(Throwable reportingFailure) {
                System.err.println("LSS_RIG_SOURCE_DIAGNOSTIC_FAILURE: reporting "+reportingFailure.getClass().getName());
            }
        }
    }
}
