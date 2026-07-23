package dev.vox.lss.networking.server;

import dev.vox.lss.common.XrayMaskPolicy.FallbackKind;
import dev.vox.lss.compat.AntiXrayCompat.EngineView;
import dev.vox.lss.config.LSSServerConfig;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@link XrayMaskManager} decision/caching core through the level-free seam
 * (dimension + injected {@link EngineView}): the per-dimension single evaluation, the
 * policy-to-mask wiring (engine adoption vs LSS-keys fallback incl. the Unreadable
 * fallback), the once-per-manager fallback resolution (log-spam bound), the dimension
 * fallback kind, and the diag label aggregation.
 */
class XrayMaskManagerTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static LSSServerConfig config(String mode) {
        var c = new LSSServerConfig();
        c.xrayObfuscation = mode;
        return c;
    }

    private static final EngineView ABSENT = new EngineView.Absent();
    private static final EngineView DISABLED = new EngineView.Disabled();
    private static final EngineView UNREADABLE = new EngineView.Unreadable();

    private static EngineView active(int maxY) {
        return new EngineView.Active(List.of(
                Blocks.DIAMOND_ORE.defaultBlockState(),
                Blocks.GOLD_ORE.defaultBlockState()), maxY);
    }

    @Test
    void autoWithoutEngineIsInactiveAndCachedWithOneEvaluation() {
        var manager = new XrayMaskManager(config("auto"));
        var evaluations = new AtomicInteger();
        assertNull(manager.entryFor("minecraft:overworld", () -> {
            evaluations.incrementAndGet();
            return ABSENT;
        }));
        assertNull(manager.entryFor("minecraft:overworld", () -> {
            evaluations.incrementAndGet();
            return ABSENT;
        }));
        assertEquals(1, evaluations.get(), "a dimension evaluates exactly once per manager");
    }

    @Test
    void autoWithActiveEngineAdoptsItsStatesAndHeight() {
        var manager = new XrayMaskManager(config("auto"));
        var entry = manager.entryFor("minecraft:overworld", () -> active(48));
        assertNotNull(entry);
        assertEquals("antixray-mod", entry.sourceLabel());
        assertEquals(48, entry.mask().maxBlockHeight());
        assertTrue(entry.mask().contains(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertFalse(entry.mask().contains(Blocks.IRON_ORE.defaultBlockState()),
                "engine adoption must carry exactly the engine's states, not the LSS defaults");
    }

    @Test
    void autoWithDisabledWorldIsInactive() {
        var manager = new XrayMaskManager(config("auto"));
        assertNull(manager.entryFor("minecraft:overworld", () -> DISABLED));
    }

    @Test
    void autoWithUnreadableEngineFallsBackToConfigKeys() {
        var manager = new XrayMaskManager(config("auto"));
        var entry = manager.entryFor("minecraft:overworld", () -> UNREADABLE);
        assertNotNull(entry, "mod present but unreadable must mask (with LSS keys), not leak");
        assertEquals("config", entry.sourceLabel());
        assertEquals(64, entry.mask().maxBlockHeight(), "the LSS xrayMaxBlockHeight default");
        assertTrue(entry.mask().contains(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertTrue(entry.mask().contains(Blocks.IRON_ORE.defaultBlockState()));
    }

    @Test
    void offIsInactiveEvenWithAnActiveEngine() {
        var manager = new XrayMaskManager(config("off"));
        assertNull(manager.entryFor("minecraft:overworld", () -> active(64)));
    }

    @Test
    void onMasksWithoutAnyEngineUsingConfigKeys() {
        var manager = new XrayMaskManager(config("on"));
        var entry = manager.entryFor("minecraft:overworld", () -> ABSENT);
        assertNotNull(entry);
        assertEquals("config", entry.sourceLabel());
    }

    @Test
    void fallbackMaskResolvesOncePerManagerAcrossDimensions() {
        // The log-spam bound: the LSS-keys list (and its unknown-id warnings) must resolve
        // once per manager, not once per world.
        var manager = new XrayMaskManager(config("on"));
        var a = manager.entryFor("minecraft:overworld", () -> ABSENT);
        var b = manager.entryFor("minecraft:the_nether", () -> ABSENT);
        assertSame(a.mask(), b.mask());
    }

    @Test
    void fallbackKindFollowsTheDimensionString() {
        var manager = new XrayMaskManager(config("on"));
        assertEquals(FallbackKind.OVERWORLD,
                manager.entryFor("minecraft:overworld", () -> ABSENT).kind());
        assertEquals(FallbackKind.NETHER,
                manager.entryFor("minecraft:the_nether", () -> ABSENT).kind());
        assertEquals(FallbackKind.END,
                manager.entryFor("minecraft:the_end", () -> ABSENT).kind());
    }

    @Test
    void diagLineAggregatesLabelAndCounter() {
        var manager = new XrayMaskManager(config("auto"));
        assertEquals("Xray: active=off, masked_sections=0", manager.diagLine());

        manager.entryFor("minecraft:the_nether", () -> UNREADABLE);
        assertEquals("Xray: active=config, masked_sections=0", manager.diagLine(),
                "LSS-keys masking reads as config");

        manager.entryFor("minecraft:overworld", () -> active(64));
        manager.countMaskedSection();
        manager.countMaskedSection();
        assertEquals("Xray: active=antixray-mod, masked_sections=2", manager.diagLine(),
                "an engine-sourced world is the strongest label");
    }

    @Test
    void staticHolderLifecycleIsOwnerGuarded() {
        var first = XrayMaskManager.activate(config("on"));
        assertSame(first, XrayMaskManager.current());

        // A successor replaces the holder; the PREDECESSOR's late retract must be a no-op
        // (a stale shutdown must not null out the live service's masking) — as must a
        // null owner (the test-wired-service shape on the Paper twin).
        var second = XrayMaskManager.activate(config("on"));
        XrayMaskManager.deactivate(first);
        assertSame(second, XrayMaskManager.current());
        XrayMaskManager.deactivate(null);
        assertSame(second, XrayMaskManager.current());

        XrayMaskManager.deactivate(second);
        assertNull(XrayMaskManager.current());
    }

    @Test
    void emptyEngineAdoptionStaysInertNotFallback() {
        // The deliberate twin ASYMMETRY vs Paper's empty-list rung: obfuscateGlobal is the
        // engine's post-resolution ground truth, so an empty adoption means the packets
        // hide nothing and there is nothing to mirror — the entry keeps the engine label
        // with an inert mask (diag tell: masked_sections stays 0), and must NOT fall back
        // to the LSS keys (that would over-mask relative to the engine).
        var manager = new XrayMaskManager(config("auto"));
        var entry = manager.entryFor("minecraft:overworld",
                () -> new EngineView.Active(List.of(), 64));
        assertNotNull(entry);
        assertEquals("antixray-mod", entry.sourceLabel());
        assertTrue(entry.mask().isEmpty(), "an empty engine adoption must mask nothing");
        assertFalse(entry.mask().contains(Blocks.DIAMOND_ORE.defaultBlockState()));
    }
}
