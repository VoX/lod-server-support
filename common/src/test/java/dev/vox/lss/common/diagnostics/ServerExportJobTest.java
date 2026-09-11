package dev.vox.lss.common.diagnostics;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServerExportJobTest {
    @TempDir Path directory;
    private static ServerStatusSnapshot snapshot() {
        return new ServerStatusSnapshot(1, 42, true, true, false, false, 16, 1, 2, 3, 4, 5, null);
    }
    @Test void admittedHeldJobHasConcreteUniqueTargetWithoutFilesystemWork() throws Exception {
        var held = new ArrayList<Runnable>();
        Path absent = directory.resolve("absent/../reports");
        var first = DiagnosticExport.submitServer(absent, snapshot(), held::add);
        var second = DiagnosticExport.submitServer(absent, snapshot(), held::add);
        assertNotEquals(first.target(), second.target());
        assertEquals(directory.resolve("reports"), first.target().getParent());
        assertFalse(Files.exists(first.target().getParent()));
        assertFalse(first.completion().isDone());
        held.remove(0).run();
        assertEquals(first.target(), first.completion().get(1, TimeUnit.SECONDS));
        assertTrue(Files.readString(first.target()).contains("schemaVersion"));
        assertTrue(Files.exists(Path.of(first.target().toString().replace(".json", ".txt"))));
        assertFalse(second.completion().isDone());
        held.remove(0).run();
        assertEquals(second.target(), second.completion().get(1, TimeUnit.SECONDS));
    }
    @Test void queueRejectionReturnsNoJobAndCreatesNothing() {
        Executor rejected = task -> { throw new RejectedExecutionException("busy"); };
        assertThrows(RejectedExecutionException.class,
                () -> DiagnosticExport.submitServer(directory.resolve("absent"), snapshot(), rejected));
        assertFalse(Files.exists(directory.resolve("absent")));
    }
    @Test void admittedDeniedWriteIsFailureRatherThanSuccessfulPath() throws Exception {
        Path file = Files.writeString(directory.resolve("occupied"), "unchanged");
        var held = new ArrayList<Runnable>();
        var job = DiagnosticExport.submitServer(file, snapshot(), held::add);
        assertFalse(job.completion().isDone());
        held.remove(0).run();
        assertThrows(ExecutionException.class, () -> job.completion().get(1, TimeUnit.SECONDS));
        assertEquals("unchanged", Files.readString(file));
    }
    @Test void predeterminedTargetStillUsesCreateNew() throws Exception {
        var held = new ArrayList<Runnable>();
        var job = DiagnosticExport.submitServer(directory, snapshot(), held::add);
        Files.writeString(job.target(), "existing", StandardOpenOption.CREATE_NEW);
        held.remove(0).run();
        assertThrows(ExecutionException.class, () -> job.completion().get(1, TimeUnit.SECONDS));
        assertEquals("existing", Files.readString(job.target()));
    }
    @Test void symlinkIsCheckedWhenHeldWriterRuns() throws Exception {
        var held = new ArrayList<Runnable>();
        Path link = directory.resolve("link");
        var job = DiagnosticExport.submitServer(link, snapshot(), held::add);
        Files.createSymbolicLink(link, directory);
        held.remove(0).run();
        assertThrows(ExecutionException.class, () -> job.completion().get(1, TimeUnit.SECONDS));
        assertFalse(Files.exists(job.target()));
    }
    @Test void synchronousCompletionStillReturnsActualPath() throws Exception {
        var job = DiagnosticExport.submitServer(directory, snapshot(), Runnable::run);
        assertEquals(job.target(), job.completion().get(1, TimeUnit.SECONDS));
    }
    @Test void actualProductionQueueRemainsOneWorkerAndOnePending() throws Exception {
        var field = DiagnosticExport.class.getDeclaredField("IO");
        field.setAccessible(true);
        var executor = (ThreadPoolExecutor) field.get(null);
        assertEquals(1, executor.getCorePoolSize());
        assertEquals(1, executor.getMaximumPoolSize());
        assertEquals(1, executor.getQueue().size() + executor.getQueue().remainingCapacity());
    }
    @Test void existingPublicServerWriteApiRemainsUsable() throws Exception {
        Path output = DiagnosticExport.write(directory, snapshot()).get(5, TimeUnit.SECONDS);
        assertTrue(Files.isRegularFile(output));
    }
}
