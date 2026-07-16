package dev.vox.lss.paper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins PaperConfig.load() through the real production entry point (the path the plugin uses
 * for plugins/LodServerSupport/lss-server-config.json):
 * <ul>
 *   <li>broken files never abort plugin startup and are never overwritten;</li>
 *   <li>fields absent from the file keep compiled defaults — critically updateEvents: if GSON
 *       ever stops using the no-arg constructor (Unsafe.allocateInstance after someone adds an
 *       explicit constructor), updateEvents deserializes to null, validate() turns it into an
 *       empty list, and dirty chunk detection dies silently;</li>
 *   <li>re-save migrates newly added fields (and updateEvents) into partial files.</li>
 * </ul>
 */
class PaperConfigLoadTest {

    // The on-disk name admins know; renaming it would orphan every existing install's config.
    private static final String FILE = "lss-server-config.json";

    private static final List<String> DEFAULT_EVENTS = new PaperConfig().updateEvents;

    private static List<String> serializedFieldNames() {
        List<String> names = Arrays.stream(PaperConfig.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .toList();
        assertTrue(names.size() >= 14, "field reflection broke, found only: " + names);
        return names;
    }

    private static JsonObject savedJson(Path dataFolder) throws Exception {
        return JsonParser.parseString(Files.readString(dataFolder.resolve(FILE))).getAsJsonObject();
    }

    @Test
    void missingFileLoadsDefaultsAndCreatesFileWithAllFields(@TempDir Path tempDir) throws Exception {
        Path dataFolder = tempDir.resolve("LodServerSupport"); // first run: folder doesn't exist yet
        PaperConfig c = PaperConfig.load(dataFolder);

        assertEquals(256, c.lodDistanceChunks);
        assertFalse(DEFAULT_EVENTS.isEmpty(), "compiled updateEvents defaults must not be empty");
        assertEquals(DEFAULT_EVENTS, c.updateEvents);
        assertTrue(Files.isRegularFile(dataFolder.resolve(FILE)));

        JsonObject saved = savedJson(dataFolder);
        for (String key : serializedFieldNames()) {
            assertTrue(saved.has(key), "defaults file missing field " + key);
        }
        assertEquals(DEFAULT_EVENTS.size(), saved.getAsJsonArray("updateEvents").size());
    }

    @Test
    void truncatedFileLoadsDefaultsAndPreservesFileExactly(@TempDir Path dataFolder) throws Exception {
        String broken = "{\"lodDistanceChunks\": 64"; // interrupted write: no closing brace
        Files.writeString(dataFolder.resolve(FILE), broken);

        PaperConfig c = assertDoesNotThrow(() -> PaperConfig.load(dataFolder));

        assertEquals(256, c.lodDistanceChunks); // defaults, not the half-written value
        assertEquals(DEFAULT_EVENTS, c.updateEvents);
        assertEquals(broken, Files.readString(dataFolder.resolve(FILE)));
    }

    @Test
    void emptyFileLoadsDefaultsAndIsLeftUntouched(@TempDir Path dataFolder) throws Exception {
        Files.writeString(dataFolder.resolve(FILE), "");

        PaperConfig c = assertDoesNotThrow(() -> PaperConfig.load(dataFolder));

        assertEquals(256, c.lodDistanceChunks);
        assertEquals(DEFAULT_EVENTS, c.updateEvents);
        assertEquals("", Files.readString(dataFolder.resolve(FILE)));
    }

    @Test
    void partialFileKeepsCompiledDefaultsIncludingUpdateEvents(@TempDir Path dataFolder) throws Exception {
        Files.writeString(dataFolder.resolve(FILE), "{\"lodDistanceChunks\": 64}");

        PaperConfig c = PaperConfig.load(dataFolder);

        assertEquals(64, c.lodDistanceChunks);
        // The GSON Unsafe landmine would null updateEvents -> validate() -> List.of(),
        // and zero every numeric default before the clamps pull them to the minimums.
        // Exact values double as Fabric/Paper default-parity pins (see ServerConfigBase).
        assertEquals(DEFAULT_EVENTS, c.updateEvents);
        assertTrue(c.enabled);
        assertEquals(20_971_520, c.bytesPerSecondLimitPerPlayer);
        assertEquals(5, c.diskReaderThreads);
        assertEquals(4000, c.sendQueueLimitPerPlayer);
        assertEquals(104_857_600, c.bytesPerSecondLimitGlobal);
        assertTrue(c.enableChunkGeneration);
        assertEquals(32, c.generationConcurrencyLimitGlobal);
        assertEquals(60, c.generationTimeoutSeconds);
        assertEquals(10, c.dirtyBroadcastIntervalSeconds);
        assertEquals(16, c.generationConcurrencyLimitPerPlayer);
        assertEquals(32, c.perDimensionTimestampCacheSizeMB);
    }

    @Test
    void partialFileResaveMigratesUpdateEventsAndNewFieldsIn(@TempDir Path dataFolder) throws Exception {
        Files.writeString(dataFolder.resolve(FILE), "{\"lodDistanceChunks\": 64}");

        PaperConfig.load(dataFolder);

        JsonObject saved = savedJson(dataFolder);
        assertEquals(64, saved.get("lodDistanceChunks").getAsInt()); // admin's value kept
        for (String key : serializedFieldNames()) {
            assertTrue(saved.has(key), "re-saved file missing migrated field " + key);
        }
        var savedEvents = saved.getAsJsonArray("updateEvents");
        assertEquals(DEFAULT_EVENTS.size(), savedEvents.size());
        assertTrue(savedEvents.toString().contains("org.bukkit.event.block.BlockPlaceEvent"));
    }

    @Test
    void updateEventsFromFileReplaceDefaultsEntirely(@TempDir Path dataFolder) throws Exception {
        Files.writeString(dataFolder.resolve(FILE),
                "{\"updateEvents\": [\"org.bukkit.event.block.BlockPlaceEvent\"]}");

        PaperConfig c = PaperConfig.load(dataFolder);

        assertEquals(List.of("org.bukkit.event.block.BlockPlaceEvent"), c.updateEvents);
        assertEquals(1, savedJson(dataFolder).getAsJsonArray("updateEvents").size());
    }

    @Test
    void nullUpdateEventsInFileBecomesEmptyListAndResavesAsEmptyArray(@TempDir Path dataFolder) throws Exception {
        Files.writeString(dataFolder.resolve(FILE), "{\"updateEvents\": null}");

        PaperConfig c = assertDoesNotThrow(() -> PaperConfig.load(dataFolder));

        assertEquals(List.of(), c.updateEvents); // null guard in validate(), no NPE downstream
        assertEquals(0, savedJson(dataFolder).getAsJsonArray("updateEvents").size());
    }

    // ---- registration glue: a Plugin proxy recording PaperWorldHandler's registerEvent calls ----

    private static <T> T proxy(Class<T> iface, InvocationHandler h) {
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h));
    }

    /** Records the event class of every registerEvent call (same proxy shape as PaperWorldHandlerTest). */
    private static Plugin recordingPlugin(List<Class<?>> registered) {
        PluginManager pm = proxy(PluginManager.class, (p, m, args) -> {
            if ("registerEvent".equals(m.getName())) registered.add((Class<?>) args[0]);
            return null;
        });
        Server server = proxy(Server.class, (p, m, args) ->
                "getPluginManager".equals(m.getName()) ? pm : null);
        return proxy(Plugin.class, (p, m, args) ->
                "getServer".equals(m.getName()) ? server : null);
    }

    @Test
    void updateEventsStringInsteadOfArrayRevertsWholeFileAndDefaultsRegister(@TempDir Path dataFolder) throws Exception {
        // A string where the array is expected fails GSON's collection adapter, which reverts
        // the WHOLE file to defaults — including the valid lodDistanceChunks customization.
        String broken = "{\"updateEvents\": \"org.bukkit.event.block.BlockPlaceEvent\", \"lodDistanceChunks\": 64}";
        Files.writeString(dataFolder.resolve(FILE), broken);

        PaperConfig c = assertDoesNotThrow(() -> PaperConfig.load(dataFolder));

        assertEquals(DEFAULT_EVENTS, c.updateEvents);
        assertEquals(256, c.lodDistanceChunks); // the valid customization is reverted with the rest
        assertEquals(broken, Files.readString(dataFolder.resolve(FILE)));

        List<Class<?>> registered = new ArrayList<>();
        new PaperWorldHandler(recordingPlugin(registered), new DirtyColumnTracker())
                .registerUpdateListeners(c.updateEvents);
        assertEquals(DEFAULT_EVENTS.size(), registered.size(),
                "the fallback default events must all register so dirty detection still works");
    }

    @Test
    void updateEventsNullEntrySurvivesLoadAndRegistrationSkipsOnlyTheNull(@TempDir Path dataFolder) throws Exception {
        // GSON loads a null ELEMENT into the list — a shape List.of-based tests can never build.
        // Registration must skip it via the per-entry catch and keep both valid neighbours.
        Files.writeString(dataFolder.resolve(FILE), "{\"updateEvents\": ["
                + "\"org.bukkit.event.block.BlockPlaceEvent\", null, \"org.bukkit.event.block.BlockBreakEvent\"]}");

        PaperConfig c = assertDoesNotThrow(() -> PaperConfig.load(dataFolder));

        assertEquals(Arrays.asList("org.bukkit.event.block.BlockPlaceEvent", null,
                "org.bukkit.event.block.BlockBreakEvent"), c.updateEvents);
        assertEquals(3, savedJson(dataFolder).getAsJsonArray("updateEvents").size()); // write-back keeps the null

        List<Class<?>> registered = new ArrayList<>();
        assertDoesNotThrow(() -> new PaperWorldHandler(recordingPlugin(registered), new DirtyColumnTracker())
                .registerUpdateListeners(c.updateEvents));
        assertEquals(List.of("org.bukkit.event.block.BlockPlaceEvent", "org.bukkit.event.block.BlockBreakEvent"),
                registered.stream().map(Class::getName).toList(),
                "entries before AND after the null must register; the null lands in the per-entry catch");
    }
}
