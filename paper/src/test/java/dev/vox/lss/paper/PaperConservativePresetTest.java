package dev.vox.lss.paper;

import com.google.gson.JsonParser;
import dev.vox.lss.common.config.ServerConfigBase;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Queued-owner and real filesystem regressions; measurement placeholders are intentionally unresolved. */
class PaperConservativePresetTest {
    @TempDir Path directory;
    private static int radius() { return Integer.parseInt("32"); }
    private static int global() { return Integer.parseInt("4"); }
    private static int perPlayer() { return Integer.parseInt("1"); }
    public static class Config extends PaperConfig {
        transient int saves;
        transient boolean requireOwner;
        transient AtomicBoolean pumping;
        public static Config load(Path directory) { return load(Config.class, "server.json", directory); }
        @Override public boolean trySave() {
            if (requireOwner) assertTrue(pumping.get(), "preset persistence must run on the service owner task");
            saves++; return super.trySave();
        }
    }
    private Config baseline(Path path) {
        var config = Config.load(path);
        config.lodDistanceChunks = radius() == 1 ? 2 : radius() - 1;
        config.validate(); assertTrue(config.trySave()); return config;
    }
    private static final class Harness {
        final AtomicBoolean pumping = new AtomicBoolean();
        final AtomicReference<Config> current;
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<String> messages = new ArrayList<>();
        final PaperRequestProcessingService service = mock(PaperRequestProcessingService.class);
        final CommandSender sender;
        final PaperCommands commands;
        Harness(Config config) {
            current = new AtomicReference<>(config); attach(config);
            doAnswer(inv -> { tasks.add(inv.getArgument(0)); return null; }).when(service).enqueueRuntimeTask(any());
            doAnswer(inv -> { assertTrue(pumping.get(), "repush belongs to the owner task"); return new int[]{2, 1}; }).when(service).repushSessionConfig();
            sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                if (method.getName().equals("sendMessage") && args != null && args.length == 1 && args[0] instanceof String text) messages.add(text);
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "preset-test-sender";
                    default -> method.getReturnType() == boolean.class ? false : null;
                };
            });
            commands = new PaperCommands(() -> service, current::get);
        }
        void attach(Config config) { config.pumping = pumping; config.requireOwner = true; }
        void replace(Config config) { attach(config); current.set(config); }
        void command(String... args) { assertTrue(commands.onCommand(sender, null, "lsslod", args)); }
        void preset(String action) { command("preset", action); }
        void drain() {
            assertFalse(pumping.get()); pumping.set(true);
            try { while (!tasks.isEmpty()) tasks.remove().run(); } finally { pumping.set(false); }
        }
    }
    @Test void previewApplyAndUndoUseOwnerAndDistanceRepushOccursExactlyOncePerEffectiveMutation() {
        var config = baseline(directory); int original = config.lodDistanceChunks; int saves = config.saves;
        var h = new Harness(config);
        h.preset("conservative"); assertEquals(1, h.tasks.size()); assertTrue(h.messages.isEmpty());
        assertEquals(original, config.lodDistanceChunks); h.drain();
        assertEquals(saves, config.saves); verify(h.service, never()).repushSessionConfig();
        h.preset("apply"); assertEquals(original, config.lodDistanceChunks); h.drain();
        assertEquals(radius(), config.lodDistanceChunks); verify(h.service, times(1)).repushSessionConfig();
        assertTrue(h.messages.stream().anyMatch(line -> line.contains("2 client(s)") && line.contains("1 legacy update on rejoin")));
        h.preset("undo"); assertEquals(radius(), config.lodDistanceChunks); h.drain();
        assertEquals(original, config.lodDistanceChunks); verify(h.service, times(2)).repushSessionConfig();
        assertEquals(saves + 2, config.saves);
    }
    @Test void failedSaveStillRepushesButFreshNoOpRetryPersistsWithoutAnotherRepush() throws Exception {
        var config = baseline(directory); var h = new Harness(config);
        String original = Files.readString(directory.resolve("server.json"));
        Files.createDirectory(directory.resolve("server.json.tmp"));
        h.preset("conservative"); h.drain(); h.preset("apply"); h.drain();
        assertEquals(radius(), config.lodDistanceChunks); verify(h.service, times(1)).repushSessionConfig();
        assertTrue(h.messages.stream().anyMatch(line -> line.contains("applied, but not saved")));
        assertEquals(original, Files.readString(directory.resolve("server.json")));
        Files.delete(directory.resolve("server.json.tmp")); int saves = config.saves;
        h.messages.clear(); h.preset("conservative"); h.drain(); h.preset("apply"); h.drain();
        assertEquals(saves + 1, config.saves); verify(h.service, times(1)).repushSessionConfig();
        assertFalse(h.messages.stream().anyMatch(line -> line.contains("not saved")));
        assertEquals(radius(), JsonParser.parseString(Files.readString(directory.resolve("server.json"))).getAsJsonObject().get("lodDistanceChunks").getAsInt());
        h.preset("undo"); h.drain(); assertEquals(radius(), config.lodDistanceChunks);
        verify(h.service, times(1)).repushSessionConfig();
    }
    @Test void generationOnlyChangesAndTheirUndoNeverRepushDistance() {
        var config = baseline(directory); config.lodDistanceChunks = radius();
        config.generationConcurrencyLimitGlobal = global() == 1 ? 2 : 1;
        config.generationConcurrencyLimitPerPlayer = 1; config.validate(); config.trySave();
        var oldLimits = config.generationLimits(); var h = new Harness(config);
        h.preset("conservative"); h.drain(); h.preset("apply"); h.drain();
        assertEquals(new ServerConfigBase.GenerationLimits(global(), perPlayer()), config.generationLimits());
        h.preset("undo"); h.drain(); assertEquals(oldLimits, config.generationLimits());
        assertEquals(radius(), config.lodDistanceChunks); verify(h.service, never()).repushSessionConfig();
    }
    @Test void queuedApplyCannotReusePreviewFromReplacedConfigScope() {
        var first = baseline(directory.resolve("first")); var replacement = baseline(directory.resolve("second"));
        var h = new Harness(first); h.preset("conservative"); h.drain(); h.preset("apply");
        int firstSaves = first.saves, replacementSaves = replacement.saves;
        int original = replacement.lodDistanceChunks; h.replace(replacement); h.drain();
        assertEquals(original, replacement.lodDistanceChunks); assertEquals(firstSaves, first.saves);
        assertEquals(replacementSaves, replacement.saves); verify(h.service, never()).repushSessionConfig();
        assertTrue(h.messages.stream().anyMatch(line -> line.contains("Preview") && line.contains("first")));
    }
    @Test void stalePreviewAndConflictingUndoReplyWithoutRepush() {
        var config = baseline(directory); var h = new Harness(config);
        h.preset("conservative"); h.drain(); config.lodDistanceChunks = config.lodDistanceChunks == 2048 ? 1 : config.lodDistanceChunks + 1;
        int saves = config.saves; h.preset("apply"); h.drain();
        assertEquals(saves, config.saves); verify(h.service, never()).repushSessionConfig();
        assertTrue(h.messages.stream().anyMatch(line -> line.contains("refresh preview")));
        // Restore the original captured radius, then perform a real application and conflicting edit.
        config.lodDistanceChunks = radius() == 1 ? 2 : radius() - 1;
        h.preset("conservative"); h.drain(); h.preset("apply"); h.drain();
        config.lodDistanceChunks = radius() == 2048 ? 1 : radius() + 1; saves = config.saves;
        h.preset("undo"); h.drain(); assertEquals(saves, config.saves);
        verify(h.service, times(1)).repushSessionConfig();
        assertTrue(h.messages.stream().anyMatch(line -> line.contains("undo conflicts")));
    }
    @Test void helpAndCompletionExposeTheSameServerGlobalActions() {
        var h = new Harness(baseline(directory)); h.command("help");
        assertTrue(h.messages.stream().anyMatch(line -> line.contains("preset conservative|pregenerated-world|apply|undo")));
        assertEquals(List.of("conservative"), h.commands.onTabComplete(h.sender, null, "lsslod", new String[]{"preset", "con"}));
        h.messages.clear(); h.command("preset");
        assertTrue(h.messages.getFirst().contains("conservative|pregenerated-world|apply|undo"));
        assertTrue(h.messages.getFirst().contains("server-global"));
    }
}
