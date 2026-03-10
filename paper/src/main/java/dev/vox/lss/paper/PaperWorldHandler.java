package dev.vox.lss.paper;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers configurable Bukkit event listeners for dirty chunk detection.
 * Follows DH server plugin's WorldHandler pattern — uses reflection to extract
 * block/chunk positions from arbitrary event classes.
 */
public class PaperWorldHandler {
    private final Plugin plugin;
    private final DirtyColumnTracker dirtyTracker;
    private final Map<String, Method> eventMethods = new ConcurrentHashMap<>();
    private final java.util.Set<String> noMethodClasses = ConcurrentHashMap.newKeySet();
    private final Listener dummyListener = new Listener() {};

    public PaperWorldHandler(Plugin plugin, DirtyColumnTracker dirtyTracker) {
        this.plugin = plugin;
        this.dirtyTracker = dirtyTracker;
    }

    /**
     * Register listeners for all configured event classes.
     */
    @SuppressWarnings("unchecked")
    public void registerUpdateListeners(List<String> eventClassNames) {
        for (String className : eventClassNames) {
            try {
                Class<? extends Event> eventClass =
                        Class.forName(className).asSubclass(Event.class);
                plugin.getServer().getPluginManager().registerEvent(
                        eventClass, dummyListener, EventPriority.MONITOR,
                        (listener, event) -> handleUpdateEvent(event),
                        plugin, true /* ignoreCancelled */
                );
                LSSLogger.info("Registered dirty chunk listener for " + className);
            } catch (ClassNotFoundException e) {
                LSSLogger.warn("Update event class not found: " + className);
            } catch (Exception e) {
                LSSLogger.error("Failed to register update event: " + className, e);
            }
        }
    }

    private void handleUpdateEvent(Event event) {
        String className = event.getClass().getName();
        if (noMethodClasses.contains(className)) return;
        Method method = eventMethods.get(className);
        if (method == null) {
            method = discoverMethod(event);
            if (method == null) { noMethodClasses.add(className); return; }
            eventMethods.put(className, method);
        }

        try {
            Object result = method.invoke(event);
            if (result instanceof List<?> list) {
                for (Object item : list) {
                    submitFromObject(item);
                }
            } else {
                submitFromObject(result);
            }
        } catch (Exception e) {
            LSSLogger.debug("Failed to extract position from " + className, e);
        }
    }

    private void submitFromObject(Object obj) {
        if (obj instanceof Block block) {
            dirtyTracker.markDirty(block.getWorld().getKey().toString(), block.getX() >> 4, block.getZ() >> 4);
        } else if (obj instanceof BlockState state) {
            dirtyTracker.markDirty(state.getWorld().getKey().toString(), state.getX() >> 4, state.getZ() >> 4);
        } else if (obj instanceof Location loc) {
            if (loc.getWorld() != null) {
                dirtyTracker.markDirty(loc.getWorld().getKey().toString(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
            }
        } else if (obj instanceof Chunk chunk) {
            dirtyTracker.markDirty(chunk.getWorld().getKey().toString(), chunk.getX(), chunk.getZ());
        }
    }

    /**
     * DH-style method discovery. Try methods in order:
     * blockList(), getBlocks(), getBlock(), getLocation(), getChunk()
     */
    private static Method discoverMethod(Event event) {
        Class<?> clazz = event.getClass();
        for (String name : new String[]{"blockList", "getBlocks", "getBlock", "getLocation", "getChunk"}) {
            try {
                Method m = clazz.getMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        LSSLogger.debug("No position method found on event class " + clazz.getName());
        return null;
    }
}
