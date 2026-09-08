package dev.vox.lss.paper;

import org.junit.jupiter.api.Test;
import org.bukkit.entity.Entity;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Uses the actual bridge bytecode, its UNMODIFIED default classResolver and the
 * existing API stub, isolated in replaceable plugin classloaders. No Bukkit runtime. */
class LibsDisguisesReloadTest {
    private static final String BRIDGE = "dev.vox.lss.paper.LibsDisguisesBridge";
    private static final String API = "me.libraryaddict.disguise.DisguiseAPI";

    private static byte[] bytes(String name) throws ClassNotFoundException {
        try (var in = LibsDisguisesReloadTest.class.getClassLoader()
                .getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (in == null) throw new ClassNotFoundException(name);
            return in.readAllBytes();
        } catch (IOException e) { throw new ClassNotFoundException(name, e); }
    }
    private static class PluginLoader extends ClassLoader {
        PluginLoader() { super(LibsDisguisesReloadTest.class.getClassLoader()); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (!name.equals(API)) return super.loadClass(name, resolve);
            Class<?> c = findLoadedClass(name);
            if (c == null) { byte[] b = bytes(name); c = defineClass(name, b, 0, b.length); }
            if (resolve) resolveClass(c);
            return c;
        }
    }
    private static class BridgeLoader extends ClassLoader {
        PluginLoader plugin;
        BridgeLoader(PluginLoader plugin) {
            super(LibsDisguisesReloadTest.class.getClassLoader());
            this.plugin = plugin;
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (name.startsWith(BRIDGE)) {
                    byte[] b = bytes(name); c = defineClass(name, b, 0, b.length);
                } else if (name.equals(API)) {
                    c = plugin.loadClass(name);
                } else { return super.loadClass(name, resolve); }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    private static Object pluginInstance(ClassLoader loader) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (p, method, args) -> method.getName().equals("isEnabled") ? true : null);
    }

    @SuppressWarnings("unchecked")
    @Test void changingPluginInstanceMustReadTheNewPluginsDisguiseRegistry() throws Exception {
        var oldPlugin = new PluginLoader();
        var bridgeLoader = new BridgeLoader(oldPlugin);
        Class<?> bridge = Class.forName(BRIDGE, true, bridgeLoader);
        var currentPlugin = new AtomicReference<Object>(pluginInstance(oldPlugin));
        Class<?> probeType = Class.forName(BRIDGE + "$PluginProbe", true, bridgeLoader);
        var field = bridge.getDeclaredField("pluginProbe");
        field.setAccessible(true);
        field.set(null, Proxy.newProxyInstance(bridgeLoader, new Class<?>[]{probeType},
                (proxy, method, args) -> currentPlugin.get()));
        var query = bridge.getMethod("isDisguised", Entity.class);
        Entity target = mock(Entity.class);
        assertEquals(false, query.invoke(null, target), "old instance has no disguise");

        var newPlugin = new PluginLoader();
        Class<?> freshApi = Class.forName(API, true, newPlugin);
        ((Set<Entity>) freshApi.getField("DISGUISED").get(null)).add(target);
        assertEquals(true, freshApi.getMethod("isDisguised", Entity.class).invoke(null, target),
                "new instance really reports the active disguise");
        bridgeLoader.plugin = newPlugin;
        currentPlugin.set(pluginInstance(newPlugin)); // production plugin-instance re-resolution trigger
        assertEquals(true, query.invoke(null, target),
                "enabled replacement plugin's disguise must be hidden; old registry is stale");
        currentPlugin.set(null);
        assertEquals(false, query.invoke(null, target), "disabled plugin must not hide targets");
        currentPlugin.set(pluginInstance(newPlugin));
        assertEquals(true, query.invoke(null, target), "re-enabled current plugin rebinds correctly");
    }
}
