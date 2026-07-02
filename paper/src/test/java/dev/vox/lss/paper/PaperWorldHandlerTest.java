package dev.vox.lss.paper;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link PaperWorldHandler}'s reflection-based extraction: every supported method
 * shape (blockList/getBlocks/getBlock/getLocation/getChunk) marks the right chunk in the
 * right dimension. These paths fail silently in production (a wrong world-key string kills
 * ALL dirty broadcasts; a broken {@code >>4} marks the wrong chunk for negative coords),
 * and no Paper soak harness exists yet to backstop them. JDK proxies stand in for the
 * Bukkit interfaces; the captured EventExecutor is the same lambda Bukkit would invoke.
 */
class PaperWorldHandlerTest {

    private static final String OVERWORLD = "minecraft:overworld";

    private record Registration(Class<?> eventClass, Listener listener, EventExecutor executor,
                                boolean ignoreCancelled) {}

    private DirtyColumnTracker tracker;
    private PaperWorldHandler handler;
    private final List<Registration> registrations = new ArrayList<>();
    private World overworld;

    @BeforeEach
    void setup() {
        tracker = new DirtyColumnTracker();
        registrations.clear();
        handler = new PaperWorldHandler(pluginProxy(), tracker);
        overworld = world(OVERWORLD);
    }

    // ---- proxy plumbing ----

    private static <T> T proxy(Class<T> iface, InvocationHandler h) {
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h));
    }

    /** Exact boxed default per return type (a proxy returning Integer for byte would throw). */
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return 0;
    }

    private static InvocationHandler defaults() {
        return (p, m, args) -> switch (m.getName()) {
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == args[0];
            case "toString" -> "proxy";
            default -> defaultValue(m.getReturnType());
        };
    }

    /** Plugin whose PluginManager records every registerEvent call. */
    private Plugin pluginProxy() {
        return pluginProxyRejecting(Set.of());
    }

    /**
     * Like {@link #pluginProxy()}, but registerEvent throws Bukkit's real registration
     * error for the given classes — the abstract / handler-list-less Event shape that
     * {@code SimplePluginManager.getEventListeners} rejects at runtime.
     */
    private Plugin pluginProxyRejecting(Set<Class<?>> rejected) {
        PluginManager pm = proxy(PluginManager.class, (p, m, args) -> {
            if ("registerEvent".equals(m.getName()) && args != null && args.length == 6) {
                Class<?> eventClass = (Class<?>) args[0];
                if (rejected.contains(eventClass)) {
                    throw new IllegalPluginAccessException(
                            "Unable to find handler list for event " + eventClass.getName());
                }
                registrations.add(new Registration(
                        eventClass, (Listener) args[1], (EventExecutor) args[3], (boolean) args[5]));
            }
            return defaults().invoke(p, m, args);
        });
        Server server = proxy(Server.class, (p, m, args) ->
                "getPluginManager".equals(m.getName()) ? pm : defaults().invoke(p, m, args));
        return proxy(Plugin.class, (p, m, args) ->
                "getServer".equals(m.getName()) ? server : defaults().invoke(p, m, args));
    }

    private static World world(String key) {
        NamespacedKey nsKey = Objects.requireNonNull(NamespacedKey.fromString(key));
        return proxy(World.class, (p, m, args) ->
                "getKey".equals(m.getName()) ? nsKey : defaults().invoke(p, m, args));
    }

    private static Block block(World w, int x, int z) {
        return proxy(Block.class, (p, m, args) -> switch (m.getName()) {
            case "getWorld" -> w;
            case "getX" -> x;
            case "getZ" -> z;
            default -> defaults().invoke(p, m, args);
        });
    }

    private static BlockState blockState(World w, int x, int z) {
        return proxy(BlockState.class, (p, m, args) -> switch (m.getName()) {
            case "getWorld" -> w;
            case "getX" -> x;
            case "getZ" -> z;
            default -> defaults().invoke(p, m, args);
        });
    }

    private static Chunk chunk(World w, int cx, int cz) {
        return proxy(Chunk.class, (p, m, args) -> switch (m.getName()) {
            case "getWorld" -> w;
            case "getX" -> cx;
            case "getZ" -> cz;
            default -> defaults().invoke(p, m, args);
        });
    }

    /** Register the event's own class and deliver it through the captured executor. */
    private void registerAndFire(Event event) throws Exception {
        handler.registerUpdateListeners(List.of(event.getClass().getName()));
        var reg = registrations.get(registrations.size() - 1);
        fire(reg, event);
    }

    private static void fire(Registration reg, Event event) throws Exception {
        reg.executor().execute(reg.listener(), event);
    }

    private void assertDirty(String dimension, long... expected) {
        long[] drained = tracker.drainDirty(dimension);
        assertNotNull(drained, "expected dirty positions in " + dimension);
        Arrays.sort(drained);
        Arrays.sort(expected);
        assertArrayEquals(expected, drained);
    }

    // ---- synthetic events covering the discovery-method matrix ----

    public static class BlockListEvent extends Event {
        private final List<Block> blocks;
        public BlockListEvent(List<Block> blocks) { this.blocks = blocks; }
        public List<Block> blockList() { return blocks; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class BlockStatesEvent extends Event {
        private final List<BlockState> states;
        public BlockStatesEvent(List<BlockState> states) { this.states = states; }
        public List<BlockState> getBlocks() { return states; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class SingleBlockEvent extends Event {
        private final Block block;
        public SingleBlockEvent(Block block) { this.block = block; }
        public Block getBlock() { return block; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class LocationEvent extends Event {
        private final Location location;
        public LocationEvent(Location location) { this.location = location; }
        public Location getLocation() { return location; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class ChunkEvent extends Event {
        private final Chunk chunk;
        public ChunkEvent(Chunk chunk) { this.chunk = chunk; }
        public Chunk getChunk() { return chunk; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    /** Mirrors BlockExplodeEvent: has BOTH blockList() and getBlock(). */
    public static class ExplosionLikeEvent extends Event {
        private final List<Block> destroyed;
        private final Block source;
        public ExplosionLikeEvent(List<Block> destroyed, Block source) {
            this.destroyed = destroyed;
            this.source = source;
        }
        public List<Block> blockList() { return destroyed; }
        public Block getBlock() { return source; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class OpaqueEvent extends Event {
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    // ---- extraction matrix ----

    @Test
    void blockListEventMarksEachBlocksChunk() throws Exception {
        registerAndFire(new BlockListEvent(List.of(
                block(overworld, 0, 0), block(overworld, 17, 33))));
        assertDirty(OVERWORLD, PositionUtil.packPosition(0, 0), PositionUtil.packPosition(1, 2));
    }

    @Test
    void getBlocksEventUsesBlockStateBranch() throws Exception {
        registerAndFire(new BlockStatesEvent(List.of(blockState(overworld, 16, -16))));
        assertDirty(OVERWORLD, PositionUtil.packPosition(1, -1));
    }

    @Test
    void getBlockEventMarksSingleChunk() throws Exception {
        registerAndFire(new SingleBlockEvent(block(overworld, 31, 47)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(1, 2));
    }

    @Test
    void getLocationEventFloorsAndShifts() throws Exception {
        // blockX = floor(-1.5) = -2 -> chunk -1; blockZ = -17 -> chunk -2
        registerAndFire(new LocationEvent(new Location(overworld, -1.5, 64.0, -17.0)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(-1, -2));
    }

    @Test
    void getChunkEventUsesChunkCoordsWithoutShift() throws Exception {
        registerAndFire(new ChunkEvent(chunk(overworld, 5, -7)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(5, -7));
    }

    @Test
    void negativeBlockCoordsUseArithmeticShift() throws Exception {
        // -1>>4 = -1 and -16>>4 = -1; integer division (-1/16 = 0) would mark chunk (0, -1)
        registerAndFire(new BlockListEvent(List.of(
                block(overworld, -1, -16), block(overworld, -17, 15))));
        assertDirty(OVERWORLD,
                PositionUtil.packPosition(-1, -1), PositionUtil.packPosition(-2, 0));
    }

    @Test
    void customWorldKeyBecomesTheDimensionString() throws Exception {
        registerAndFire(new SingleBlockEvent(block(world("lsstest:custom_world"), 8, 8)));
        assertDirty("lsstest:custom_world", PositionUtil.packPosition(0, 0));
        assertNull(tracker.drainDirty(OVERWORLD), "nothing leaks into the overworld bucket");
    }

    @Test
    void blockListWinsOverGetBlockInDiscoveryOrder() throws Exception {
        // For explosion-shaped events the list IS the destruction; falling back to getBlock()
        // would mark only the source chunk and miss every destroyed chunk.
        registerAndFire(new ExplosionLikeEvent(
                List.of(block(overworld, 0, 0), block(overworld, 160, 160)),
                block(overworld, 320, 320)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(0, 0), PositionUtil.packPosition(10, 10));
    }

    @Test
    void locationWithNullWorldIsIgnored() throws Exception {
        registerAndFire(new LocationEvent(new Location(null, 5.0, 64.0, 5.0)));
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void eventWithoutExtractorMarksNothingEvenWhenRefired() throws Exception {
        var event = new OpaqueEvent();
        registerAndFire(event);
        fire(registrations.get(registrations.size() - 1), event); // second fire exercises the negative-cache path
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void cachedMethodPathStillExtractsOnSecondFire() throws Exception {
        registerAndFire(new SingleBlockEvent(block(overworld, 0, 0)));
        fire(registrations.get(registrations.size() - 1), new SingleBlockEvent(block(overworld, 16, 0)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(0, 0), PositionUtil.packPosition(1, 0));
    }

    // ---- registration resilience and defaults ----

    @Test
    void badClassNamesAreSkippedAndRegistrationContinues() {
        handler.registerUpdateListeners(List.of(
                "com.example.DoesNotExist",          // ClassNotFoundException branch
                "java.lang.String",                  // not an Event -> generic catch branch
                BlockListEvent.class.getName()));
        assertEquals(1, registrations.size(), "only the valid class registers; bad names do not abort the loop");
        assertEquals(BlockListEvent.class, registrations.get(0).eventClass());
    }

    @Test
    void defaultUpdateEventsAllResolveAndExcludeChunkPopulate() {
        List<String> defaults = new PaperConfig().updateEvents;
        assertFalse(defaults.contains("org.bukkit.event.world.ChunkPopulateEvent"),
                "ChunkPopulateEvent must not be a default: it re-marks every LSS-generated chunk");
        assertTrue(defaults.contains("org.bukkit.event.block.BlockBreakEvent"));
        assertTrue(defaults.contains("org.bukkit.event.entity.EntityExplodeEvent"),
                "entity explosions (creeper/TNT/wither) must mark chunks dirty — BlockExplodeEvent"
                + " only covers block-caused explosions");
        handler.registerUpdateListeners(defaults);
        assertEquals(defaults.size(), registrations.size(),
                "every default event class name resolves on the Paper API (catches typos in defaults)");
    }

    // ---- discovery-ladder order (full walk; each rung returns a DISTINCT chunk) ----

    public static class AllFiveEvent extends Event {
        private final World w;
        public AllFiveEvent(World w) { this.w = w; }
        public List<Block> blockList() { return List.of(block(w, 0, 0)); }      // chunk (0,0)
        public List<BlockState> getBlocks() { return List.of(blockState(w, 16, 0)); } // chunk (1,0)
        public Block getBlock() { return block(w, 32, 0); }                      // chunk (2,0)
        public Location getLocation() { return new Location(w, 48.0, 64.0, 0.0); } // chunk (3,0)
        public Chunk getChunk() { return chunk(w, 4, 0); }                       // chunk (4,0)
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class FourRungEvent extends Event {
        private final World w;
        public FourRungEvent(World w) { this.w = w; }
        public List<BlockState> getBlocks() { return List.of(blockState(w, 16, 0)); }
        public Block getBlock() { return block(w, 32, 0); }
        public Location getLocation() { return new Location(w, 48.0, 64.0, 0.0); }
        public Chunk getChunk() { return chunk(w, 4, 0); }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class ThreeRungEvent extends Event {
        private final World w;
        public ThreeRungEvent(World w) { this.w = w; }
        public Block getBlock() { return block(w, 32, 0); }
        public Location getLocation() { return new Location(w, 48.0, 64.0, 0.0); }
        public Chunk getChunk() { return chunk(w, 4, 0); }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class TwoRungEvent extends Event {
        private final World w;
        public TwoRungEvent(World w) { this.w = w; }
        public Location getLocation() { return new Location(w, 48.0, 64.0, 0.0); }
        public Chunk getChunk() { return chunk(w, 4, 0); }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    @Test
    void discoveryLadderWalksBlockListThenGetBlocksThenGetBlockThenGetLocationThenGetChunk() throws Exception {
        // PP-011: each removal of the top rung must promote exactly the next one — a reorder
        // among getBlocks/getBlock/getLocation/getChunk changes which chunk gets marked.
        registerAndFire(new AllFiveEvent(overworld));
        assertDirty(OVERWORLD, PositionUtil.packPosition(0, 0));

        registerAndFire(new FourRungEvent(overworld));
        assertDirty(OVERWORLD, PositionUtil.packPosition(1, 0));

        registerAndFire(new ThreeRungEvent(overworld));
        assertDirty(OVERWORLD, PositionUtil.packPosition(2, 0));

        registerAndFire(new TwoRungEvent(overworld));
        assertDirty(OVERWORLD, PositionUtil.packPosition(3, 0));
        // getChunk-only bottom rung is pinned by getChunkEventUsesChunkCoordsWithoutShift
    }

    // ---- registration failure shapes ----

    /** The handler-list-less shape real Bukkit rejects from inside registerEvent. */
    public abstract static class AbstractHandlerlessEvent extends Event {
    }

    @Test
    void abstractEventRegistrationThrowIsContainedAndLaterNamesStillRegister() {
        // PP-012: Bukkit throws IllegalPluginAccessException from registerEvent for events
        // without a resolvable handler list; the generic catch must contain it per-entry.
        var rejecting = new PaperWorldHandler(
                pluginProxyRejecting(Set.of(AbstractHandlerlessEvent.class)), tracker);
        rejecting.registerUpdateListeners(List.of(
                AbstractHandlerlessEvent.class.getName(),
                BlockListEvent.class.getName()));
        assertEquals(1, registrations.size(), "the registration throw must not abort the loop");
        assertEquals(BlockListEvent.class, registrations.get(0).eventClass());
    }

    @Test
    void nullUpdateEventsEntryIsSkippedAndLoopContinues() {
        // PP-013: GSON can deserialize [null, ...]; Class.forName(null) NPEs into the
        // generic catch and the remaining names must still register.
        handler.registerUpdateListeners(Arrays.asList(null, SingleBlockEvent.class.getName()));
        assertEquals(1, registrations.size());
        assertEquals(SingleBlockEvent.class, registrations.get(0).eventClass());
    }

    // ---- runtime-subclass caching ----

    public static class BaseBlockEvent extends Event {
        private final Block block;
        public BaseBlockEvent(Block block) { this.block = block; }
        public Block getBlock() { return block; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class SubWithBlockListEvent extends BaseBlockEvent {
        private final List<Block> blocks;
        public SubWithBlockListEvent(Block base, List<Block> blocks) {
            super(base);
            this.blocks = blocks;
        }
        public List<Block> blockList() { return blocks; }
    }

    public static class PlainSubEvent extends BaseBlockEvent {
        public PlainSubEvent(Block block) { super(block); }
    }

    @Test
    void subclassEventsDiscoverAndCacheExtractorsByRuntimeClass() throws Exception {
        // PP-014: Bukkit delivers subclasses of the registered class; discovery keyed on the
        // REGISTERED class would miss SubWithBlockListEvent's richer blockList() extractor.
        handler.registerUpdateListeners(List.of(BaseBlockEvent.class.getName()));
        var reg = registrations.get(registrations.size() - 1);

        fire(reg, new SubWithBlockListEvent(
                block(overworld, 0, 0), List.of(block(overworld, 160, 0))));
        assertDirty(OVERWORLD, PositionUtil.packPosition(10, 0));

        fire(reg, new PlainSubEvent(block(overworld, 32, 0)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(2, 0));

        // Second fire of each subclass rides its own cached extractor (independent entries)
        fire(reg, new SubWithBlockListEvent(
                block(overworld, 320, 320), List.of(block(overworld, 480, 0))));
        assertDirty(OVERWORLD, PositionUtil.packPosition(30, 0));
    }

    // ---- piston events (special-cased: source + destination + head chunks) ----

    @Test
    void pistonExtendAcrossAChunkBorderMarksTheDestinationChunk() throws Exception {
        // A piston pushes a block from chunk (0,0) into chunk (1,0). The generic ladder
        // reads getBlocks() — SOURCE positions only — so the chunk where the block lands
        // was never marked and clients kept stale LOD there indefinitely (Paper has no
        // save-hook fallback).
        registerAndFire(new BlockPistonExtendEvent(
                block(overworld, 14, 0), List.of(block(overworld, 15, 0)), BlockFace.EAST));
        assertDirty(OVERWORLD, PositionUtil.packPosition(0, 0), PositionUtil.packPosition(1, 0));
    }

    @Test
    void barePistonExtendStillMarksTheHeadChunk() throws Exception {
        // Nothing in front: getBlocks() is empty, but the head itself appears at
        // piston+direction — possibly across the chunk border. The list-only walk marked
        // nothing at all.
        registerAndFire(new BlockPistonExtendEvent(
                block(overworld, 15, 0), List.of(), BlockFace.EAST));
        assertDirty(OVERWORLD, PositionUtil.packPosition(0, 0), PositionUtil.packPosition(1, 0));
    }

    @Test
    void pistonRetractMarksThePistonChunkNotJustTheMovedBlocks() throws Exception {
        // Sticky piston in chunk (1,0) facing WEST pulls the block at (14,0) [chunk (0,0)]
        // to (15,0). getDirection() on the moved-blocks fire is the motion direction (EAST
        // here). The piston base's own extended->retracted state change and the vanishing
        // head are never in getBlocks(), so chunk (1,0) went unmarked.
        registerAndFire(new BlockPistonRetractEvent(
                block(overworld, 16, 0), List.of(block(overworld, 14, 0)), BlockFace.EAST));
        assertDirty(OVERWORLD, PositionUtil.packPosition(0, 0), PositionUtil.packPosition(1, 0));
    }

    // ---- multi-place events (getReplacedBlockStates rung) ----

    /** Mirrors BlockMultiPlaceEvent: getReplacedBlockStates() (all placed halves) + inherited getBlock(). */
    public static class MultiPlaceLikeEvent extends Event {
        private final List<BlockState> replaced;
        private final Block clicked;
        public MultiPlaceLikeEvent(List<BlockState> replaced, Block clicked) {
            this.replaced = replaced;
            this.clicked = clicked;
        }
        public List<BlockState> getReplacedBlockStates() { return replaced; }
        public Block getBlock() { return clicked; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    @Test
    void multiPlaceShapedEventMarksEveryPlacedHalfNotJustTheClickedBlock() throws Exception {
        // A bed/door spanning a chunk border places two halves; getBlock() is only the
        // clicked half, so the second half's chunk was never marked.
        registerAndFire(new MultiPlaceLikeEvent(
                List.of(blockState(overworld, 15, 0), blockState(overworld, 16, 0)),
                block(overworld, 15, 0)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(0, 0), PositionUtil.packPosition(1, 0));
        // and the real Bukkit class discovers this rung (it shares BlockPlaceEvent's
        // HandlerList, so it arrives at listeners registered for the plain place event):
        Method discovered = PaperWorldHandler.discoverMethod(
                org.bukkit.event.block.BlockMultiPlaceEvent.class);
        assertNotNull(discovered);
        assertEquals("getReplacedBlockStates", discovered.getName());
    }

    // ---- hot-reloaded event classes (extractor cache keyed by Class, not name) ----

    @Test
    void sameNamedEventClassFromAFreshClassloaderStillExtracts() throws Exception {
        // PlugMan-style hot reload: the registration (and our extractor cache) outlives the
        // plugin's classloader. A name-keyed cache returns the OLD class's Method, which
        // throws IllegalArgumentException on every new-loader instance — swallowed at
        // debug level, all dirty marks from that event silently lost until restart.
        registerAndFire(new SingleBlockEvent(block(overworld, 5, 5)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(0, 0));

        Class<?> reloaded = reloadClass(SingleBlockEvent.class);
        assertNotSame(SingleBlockEvent.class, reloaded, "premise: a genuinely fresh Class object");
        Event fresh = (Event) reloaded.getConstructor(Block.class)
                .newInstance(block(overworld, 33, 0));
        fire(registrations.get(registrations.size() - 1), fresh);
        assertDirty(OVERWORLD, PositionUtil.packPosition(2, 0));
    }

    /** Define the class's bytes again in a child-first loader — same FQCN, different Class. */
    private static Class<?> reloadClass(Class<?> original) throws Exception {
        String resource = original.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (var in = original.getClassLoader().getResourceAsStream(resource)) {
            bytes = Objects.requireNonNull(in, resource).readAllBytes();
        }
        var loader = new ClassLoader(original.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(original.getName())) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) found = defineClass(name, bytes, 0, bytes.length);
                    if (resolve) resolveClass(found);
                    return found;
                }
                return super.loadClass(name, resolve);
            }
        };
        return Class.forName(original.getName(), false, loader);
    }

    // ---- registration arguments ----

    @Test
    void registrationPassesIgnoreCancelledTrueScopedToTheRegisterEventArg() {
        // PP-015 (scoped pin): cancelled events must not mark dirty. Actual bus-level
        // suppression is Bukkit's contract for this flag — untestable in a proxy harness,
        // so the pin is the literal argument the handler registers with.
        handler.registerUpdateListeners(List.of(SingleBlockEvent.class.getName()));
        assertTrue(registrations.get(registrations.size() - 1).ignoreCancelled());
    }

    // ---- extractor invocation failure ----

    public static class ThrowingBlockEvent extends Event {
        private final Block block;
        public ThrowingBlockEvent(Block block) { this.block = block; }
        public Block getBlock() {
            if (block == null) throw new IllegalStateException("extractor boom");
            return block;
        }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    @Test
    void extractorExceptionIsContainedAndSubsequentEventsStillProcess() throws Exception {
        // PP-016: a throwing extractor must be debug-logged, never rethrown into the event
        // bus, and must not poison the cached method for later events.
        handler.registerUpdateListeners(List.of(ThrowingBlockEvent.class.getName()));
        var reg = registrations.get(registrations.size() - 1);

        assertDoesNotThrow(() -> fire(reg, new ThrowingBlockEvent(null)));
        assertEquals(0, tracker.pendingCount());

        fire(reg, new ThrowingBlockEvent(block(overworld, 16, 16)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(1, 1));
    }

    // ---- defaults yield extractors ----

    @Test
    void everyDefaultUpdateEventYieldsAPositionExtractorAndFluidFlowStaysExcluded() throws Exception {
        // PP-017: resolvable-but-extractor-less defaults register fine and then silently
        // extract nothing forever; pin that discoverMethod finds a rung for each default.
        List<String> defaults = new PaperConfig().updateEvents;
        assertFalse(defaults.contains("org.bukkit.event.block.BlockFromToEvent"),
                "high-frequency fluid flow is deliberately opt-in, never a default");
        for (String name : defaults) {
            assertNotNull(PaperWorldHandler.discoverMethod(Class.forName(name)),
                    name + " resolves but yields no position extractor — it would register and "
                            + "silently mark nothing forever");
        }
    }
}
