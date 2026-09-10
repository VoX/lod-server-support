package dev.vox.lss.common.diagnostics;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Explicit local export, one worker and one pending request, at most 10 bounded reports.
 * Accepts immutable typed DTOs only; arbitrary logs/errors are deliberately not an input. */
public final class DiagnosticExport {
    private static final int MAX_BYTES = 64 * 1024;
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                var thread = new Thread(runnable, "LSS-DiagnosticExport");
                thread.setDaemon(true);
                return thread;
            });
    static { IO.allowCoreThreadTimeOut(true); }
    public static CompletableFuture<Path> write(Path directory, ClientStatusSnapshot snapshot) {
        // Capture/serialize typed immutable fields before enqueue; the task retains no live session.
        byte[] bytes = clientJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String summary = String.join(System.lineSeparator(), snapshot.lines());
        return CompletableFuture.supplyAsync(() -> {
            try { return writeCaptured(directory, bytes, summary); }
            catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
        }, IO);
    }
    static String clientJson(ClientStatusSnapshot snapshot) {
        var gson = new GsonBuilder().setPrettyPrinting().addSerializationExclusionStrategy(new com.google.gson.ExclusionStrategy() {
            public boolean shouldSkipField(com.google.gson.FieldAttributes field) { return field.getName().equals("details"); }
            public boolean shouldSkipClass(Class<?> type) { return false; }
        }).create();
        var safe = gson.toJsonTree(snapshot).getAsJsonObject();
        if (snapshot.details() != null) {
            var numeric = new GsonBuilder().serializeSpecialFloatingPointValues().create().toJsonTree(snapshot.details()).getAsJsonObject();
            numeric.entrySet().removeIf(entry -> !entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isNumber()
                    || !Double.isFinite(entry.getValue().getAsDouble()));
            safe.add("counters", numeric);
        }
        return gson.toJson(safe);
    }

    public static CompletableFuture<Path> write(Path directory, ServerStatusSnapshot snapshot) {
        byte[] bytes = new GsonBuilder().setPrettyPrinting().create().toJson(snapshot)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String summary = String.join(System.lineSeparator(), snapshot.lines());
        return CompletableFuture.supplyAsync(() -> {
            try { return writeCaptured(directory, bytes, summary); }
            catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
        }, IO);
    }
    static Path writeCaptured(Path directory, byte[] bytes, String summary) throws IOException {
        byte[] summaryBytes = summary.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES || summaryBytes.length > MAX_BYTES) throw new IOException("report too large");
        // Refuse symlink components, including a user-replaced diagnostics directory.
        Path absolute = directory.toAbsolutePath().normalize();
        for (Path part = absolute; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("symlink destination");
        Files.createDirectories(absolute);
        try (var stream = Files.list(absolute)) {
            var files = stream.filter(p -> p.getFileName().toString().matches("diagnostics-[0-9a-f-]+\\.(json|txt)"))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingLong(DiagnosticExport::modified)).toList();
            for (int i = 0; i < Math.max(0, files.size() - 18); i++) Files.delete(files.get(i));
        }
        String name = "diagnostics-" + java.util.UUID.randomUUID();
        Path json = absolute.resolve(name + ".json");
        Path txt = absolute.resolve(name + ".txt");
        Files.write(json, bytes, StandardOpenOption.CREATE_NEW);
        try { Files.write(txt, summaryBytes, StandardOpenOption.CREATE_NEW); }
        catch (IOException error) { Files.deleteIfExists(json); throw error; }
        return json;
    }
    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException ignored) { return Long.MIN_VALUE; }
    }
    private DiagnosticExport() {}
}
