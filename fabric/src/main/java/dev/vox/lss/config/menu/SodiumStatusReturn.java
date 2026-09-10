package dev.vox.lss.config.menu;

import dev.vox.lss.common.config.ExternalOptionRefresh;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.stream.Stream;

/** User-initiated screen transition only; no optional discovery from status collection. */
public final class SodiumStatusReturn {
    private SodiumStatusReturn() {}
    public static Runnable capture(Object parent) {
        var config = LSSClientConfig.CONFIG;
        try {
            Object option;
            boolean modern = false;
            Class<?> screen = parent.getClass();
            while (screen != null && !screen.getName().endsWith(".SodiumOptionsGUI")
                    && !screen.getName().endsWith(".VideoSettingsScreen")) screen = screen.getSuperclass();
            if (screen == null) return () -> {};
            modern = screen.getName().endsWith(".VideoSettingsScreen");
            if (modern) {
                Class<?> manager = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager", false, screen.getClassLoader());
                Object state = field(manager, "CONFIG").get(null);
                Object options = field(state.getClass(), "options").get(state);
                option = ((Map<?, ?>) options).entrySet().stream()
                        .filter(entry -> ClientOptionCatalog.ID_RECEIVE_SERVER_LODS.equals(entry.getKey().toString()))
                        .map(Map.Entry::getValue).findFirst().orElseThrow();
            } else {
                try (Stream<?> options = (Stream<?>) method(screen, "getAllOptions").invoke(parent)) {
                    option = options.filter(candidate -> isReception(candidate, config)).findFirst().orElseThrow();
                }
            }
            Method get = method(option.getClass(), modern ? "getValidatedValue" : "getValue");
            Method changed = method(option.getClass(), "hasChanged");
            Method reset = method(option.getClass(), modern ? "resetFromBinding" : "reset");
            Method set = method(option.getClass(), modern ? "modifyValue" : "setValue", Object.class);
            Runnable refresh = ExternalOptionRefresh.capture(() -> config.receiveServerLods,
                    () -> (Boolean) invoke(get, option), () -> (Boolean) invoke(changed, option),
                    () -> invoke(reset, option), value -> invoke(set, option, value));
            return () -> {
                try { refresh.run(); }
                catch (RuntimeException failure) { LSSLogger.warn("Could not refresh the Sodium reception control after status closed."); }
            };
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LSSLogger.warn("Could not bind the Sodium reception control for status return.");
            return () -> {};
        }
    }
    private static boolean isReception(Object option, Object config) {
        try {
            Object storage = method(option.getClass(), "getStorage").invoke(option);
            if (method(storage.getClass(), "getData").invoke(storage) != config) return false;
            Object name = method(option.getClass(), "getName").invoke(option);
            return name instanceof Component component && component.getContents() instanceof TranslatableContents text
                    && text.getKey().equals("lss.config.receive_server_lods");
        } catch (ReflectiveOperationException failure) { return false; }
    }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Method result = cursor.getDeclaredMethod(name, parameters);
                if (!result.trySetAccessible()) throw new IllegalStateException("Sodium method inaccessible");
                return result;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(name);
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name);
        if (!result.trySetAccessible()) throw new IllegalStateException("Sodium field inaccessible");
        return result;
    }
    private static Object invoke(Method method, Object target, Object... arguments) {
        try { return method.invoke(target, arguments); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
