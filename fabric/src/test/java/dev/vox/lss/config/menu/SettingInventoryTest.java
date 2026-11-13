package dev.vox.lss.config.menu;

import dev.vox.lss.common.config.SettingsSchema;
import dev.vox.lss.config.LSSClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class SettingInventoryTest {
    @Test void everyStoredClientPathHasMetadataAndTypedBinding(@TempDir Path directory) {
        var paths = SettingsSchema.client().defaultValues().keySet();
        var bindings = ClientOptionCatalog.serializedBindings();
        assertEquals(paths, bindings.stream().map(b -> b.descriptor().key()).collect(Collectors.toSet()));
        assertEquals(paths.size(), bindings.size());
        var config = new LSSClientConfig(directory);
        assertTrue(config.snapshot().lod().dh().syncOnLoad());
        assertTrue(config.snapshot().lod().dh().realTimeUpdates());
        assertTrue(config.voxyBorderFill());
        for (String key : java.util.List.of("lod.dh.sync_on_load", "lod.dh.real_time_updates", "lod.voxy_border_fill")) {
            var descriptor = bindings.stream().map(b -> b.descriptor()).filter(d -> d.key().equals(key)).findFirst().orElseThrow();
            assertEquals(dev.vox.lss.common.config.SettingDescriptor.Exposure.ADVANCED, descriptor.exposure());
        }
        assertTrue(ClientOptionCatalog.find("lss:dh_sync_on_load").isEmpty());
        assertTrue(ClientOptionCatalog.find("lss:dh_real_time_updates").isEmpty());
        for (var binding : bindings) assertEquals(config.snapshot().values().get(binding.descriptor().key()),binding.storedValue().apply(config));
        assertEquals(12, bindings.stream().filter(b -> b.descriptor().exposure()
                == dev.vox.lss.common.config.SettingDescriptor.Exposure.UI).count());
        // A page's buttons are not settings: no serialized descriptor carries one.
        for (var page : ClientOptionCatalog.pages()) for (var action : page.actions()) for (var binding : bindings) {
            String descriptor = binding.descriptor().toString();
            assertFalse(descriptor.contains(action.id()) || descriptor.contains(action.nameKey()),
                    action.id() + " leaked into the settings inventory: " + descriptor);
        }
    }
}
