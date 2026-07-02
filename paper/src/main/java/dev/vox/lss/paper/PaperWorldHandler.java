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
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * Registers configurable Bukkit event listeners for dirty chunk detection.
 * Follows DH server plugin's WorldHandler pattern — uses reflection to extract
 * block/chunk positions from arbitrary event classes.
 */
public class PaperWorldHandler {
    private final Plugin plugin;
    private final DirtyColumnTracker dirtyTracker;
    private final Listener dummyListener = new Listener() {};

    /**
     * Per-class extractor cache. Keyed by the runtime {@link Class} object via ClassValue
     * (which does not pin the classloader): after a plugin hot-reload, the same-named event
     * class is a fresh Class and gets its own discovery pass — a name-keyed cache returned
     * the OLD class's Method, which throws IllegalArgumentException on every new-loader
     * instance and silently killed that event's dirty marks. Empty = no extractor.
     */
    private static final ClassValue<Optional<Method>> EXTRACTORS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            return Optional.ofNullable(discoverMethod(type));
        }
    };

    public PaperWorldHandler(Plugin plugin, DirtyColumnTracker dirtyTracker) {
        this.plugin = plugin;
        this.dirtyTracker = dirtyTracker;
    }

    /**
     * Register listeners for all configured event classes.
     */
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
        if (event instanceof BlockPistonEvent piston) {
            // Special-cased: the generic ladder reads getBlocks() — the moved blocks'
            // SOURCE positions only — missing the destination chunk of a cross-border push
            // and marking nothing at all for a bare piston (empty list).
            submitPistonEvent(piston);
            return;
        }
        Method method = EXTRACTORS.get(event.getClass()).orElse(null);
        if (method == null) return;

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
            LSSLogger.debug("Failed to extract position from " + event.getClass().getName(), e);
        }
    }

    /**
     * Marks the piston's own chunk, both piston neighbors (the head appears on extend /
     * vanishes on retract next to the base, and getDirection() is the blocks' motion
     * direction on the moved-blocks fire but the piston FACING on Paper's empty-retract
     * fires — both sides covers either convention), and each moved block's source and
     * destination chunk. The tracker dedups repeats.
     */
    private void submitPistonEvent(BlockPistonEvent event) {
        Block piston = event.getBlock();
        String pistonDimension = piston.getWorld().getKey().toString();
        int dx = event.getDirection().getModX();
        int dz = event.getDirection().getModZ();
        markBlockDirty(pistonDimension, piston.getX(), piston.getZ());
        markBlockDirty(pistonDimension, piston.getX() + dx, piston.getZ() + dz);
        markBlockDirty(pistonDimension, piston.getX() - dx, piston.getZ() - dz);
        List<Block> moved;
        if (event instanceof BlockPistonExtendEvent extend) {
            moved = extend.getBlocks();
        } else if (event instanceof BlockPistonRetractEvent retract) {
            moved = retract.getBlocks();
        } else {
            moved = List.of();
        }
        for (Block block : moved) {
            String dimension = block.getWorld().getKey().toString();
            markBlockDirty(dimension, block.getX(), block.getZ());
            markBlockDirty(dimension, block.getX() + dx, block.getZ() + dz);
        }
    }

    private void markBlockDirty(String dimension, int blockX, int blockZ) {
        dirtyTracker.markDirty(dimension, blockX >> 4, blockZ >> 4);
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
     * blockList(), getBlocks(), getReplacedBlockStates(), getBlock(), getLocation(), getChunk().
     * getReplacedBlockStates precedes getBlock so BlockMultiPlaceEvent (bed/door halves,
     * possibly spanning a chunk border) marks every placed position, not just the clicked
     * block. Keyed on the event's runtime class. Package-visible so tests can assert every
     * default updateEvents class yields an extractor (not just that it resolves).
     */
    static Method discoverMethod(Class<?> clazz) {
        for (String name : new String[]{
                "blockList", "getBlocks", "getReplacedBlockStates", "getBlock", "getLocation", "getChunk"}) {
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
