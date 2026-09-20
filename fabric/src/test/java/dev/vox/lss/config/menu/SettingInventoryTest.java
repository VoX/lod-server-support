package dev.vox.lss.config.menu;

import dev.vox.lss.config.LSSClientConfig;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.lang.reflect.Modifier;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class SettingInventoryTest {
    @Test void everyStoredClientFieldHasMetadataAndTypedBinding() throws Exception {
        var fields = Arrays.stream(LSSClientConfig.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !Modifier.isTransient(f.getModifiers()))
                .map(java.lang.reflect.Field::getName).collect(Collectors.toSet());
        var bindings = ClientOptionCatalog.serializedBindings();
        assertEquals(fields, bindings.stream().map(b -> b.descriptor().key()).collect(Collectors.toSet()));
        assertEquals(fields.size(), bindings.size());
        var config = new LSSClientConfig();
        for (var binding : bindings) {
            assertEquals(LSSClientConfig.class.getField(binding.descriptor().key()).get(config),
                    binding.storedValue().apply(config));
        }
        assertEquals(11, bindings.stream().filter(b -> b.descriptor().exposure()
                == dev.vox.lss.common.config.SettingDescriptor.Exposure.UI).count());
    }
}
