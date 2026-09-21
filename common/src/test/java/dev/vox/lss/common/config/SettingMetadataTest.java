package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class SettingMetadataTest {
    @Test void everySerializedServerFieldIsExplicitlyInventoried() {
        var actual = Arrays.stream(ServerConfigBase.class.getFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers()))
                .map(java.lang.reflect.Field::getName).collect(Collectors.toSet());
        var described = RuntimeSettings.descriptors().stream().map(SettingDescriptor::key).collect(Collectors.toSet());
        assertEquals(actual, described);
        assertEquals(described.size(), RuntimeSettings.descriptors().size());
    }
    @Test void runtimeMetadataKeepsSentinelsAndGlobalScope() {
        assertTrue(RuntimeSettings.byName("maxConcurrentDiskReads").descriptor().domain().contains("AUTO"));
        assertTrue(RuntimeSettings.byName("dirtyBroadcastIntervalSeconds").descriptor().domain().contains("disables"));
        for (var key : RuntimeSettings.keys()) {
            assertFalse(key.descriptor().restartRequired());
            assertEquals(key.name().equals("lodDistanceChunks"), key.descriptor().supports(SettingDescriptor.Scope.WORLD_DISTANCE));
            assertEquals(key.applyNote(), key.descriptor().applyTiming());
        }
    }
}
