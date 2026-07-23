package dev.vox.lss.paper;

import dev.vox.lss.common.XrayMaskPolicy.FallbackKind;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Twin of the Fabric {@code XrayMaskManagerTest}: pins the {@link PaperXrayMaskManager}
 * decision/caching core through the level-free seam (dimension + injected
 * {@link PaperXrayMaskManager.EngineConfig} — the extracted shape of
 * {@code paperConfig().anticheat.antiXray}), incl. the empty-hidden-list fallback that is
 * Paper-specific in the design (§3 Detection).
 */
class PaperXrayMaskManagerTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static PaperConfig config(String mode) {
        var c = new PaperConfig();
        c.xrayObfuscation = mode;
        return c;
    }

    private static PaperXrayMaskManager.EngineConfig enabled(int maxY) {
        return new PaperXrayMaskManager.EngineConfig(true,
                List.of(Blocks.DIAMOND_ORE, Blocks.GOLD_ORE), maxY);
    }

    private static final PaperXrayMaskManager.EngineConfig DISABLED =
            new PaperXrayMaskManager.EngineConfig(false, List.of(), 64);

    @Test
    void autoWithDisabledWorldIsInactiveAndCachedWithOneEvaluation() {
        var manager = new PaperXrayMaskManager(config("auto"));
        var evaluations = new AtomicInteger();
        assertNull(manager.entryFor("minecraft:overworld", () -> {
            evaluations.incrementAndGet();
            return DISABLED;
        }));
        assertNull(manager.entryFor("minecraft:overworld", () -> {
            evaluations.incrementAndGet();
            return DISABLED;
        }));
        assertEquals(1, evaluations.get(), "a dimension evaluates exactly once per manager");
    }

    @Test
    void autoWithEnabledWorldAdoptsItsBlocksAndHeight() {
        var manager = new PaperXrayMaskManager(config("auto"));
        var entry = manager.entryFor("minecraft:overworld", () -> enabled(48));
        assertNotNull(entry);
        assertEquals("paper-config", entry.sourceLabel());
        assertEquals(48, entry.mask().maxBlockHeight());
        assertTrue(entry.mask().contains(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertTrue(entry.mask().contains(Blocks.GOLD_ORE.defaultBlockState()));
        assertFalse(entry.mask().contains(Blocks.REDSTONE_ORE.defaultBlockState()),
                "engine adoption must carry exactly the config's blocks, not the LSS defaults");
    }

    @Test
    void autoWithEnabledWorldButEmptyHiddenListFallsBackToConfigKeys() {
        // The Paper-specific fallback rung: enabled + hidden-blocks resolving empty.
        var manager = new PaperXrayMaskManager(config("auto"));
        var entry = manager.entryFor("minecraft:overworld",
                () -> new PaperXrayMaskManager.EngineConfig(true, List.of(), 32));
        assertNotNull(entry, "an enabled world must mask even when its list resolves empty");
        assertEquals("config", entry.sourceLabel());
        assertEquals(64, entry.mask().maxBlockHeight(),
                "the fallback carries the LSS height, not the engine's");
    }

    @Test
    void offIsInactiveEvenWithAnEnabledWorld() {
        var manager = new PaperXrayMaskManager(config("off"));
        assertNull(manager.entryFor("minecraft:overworld", () -> enabled(64)));
    }

    @Test
    void onMasksDisabledWorldsUsingConfigKeys() {
        var manager = new PaperXrayMaskManager(config("on"));
        var entry = manager.entryFor("minecraft:overworld", () -> DISABLED);
        assertNotNull(entry);
        assertEquals("config", entry.sourceLabel());
    }

    @Test
    void fallbackMaskResolvesOncePerManagerAcrossDimensions() {
        var manager = new PaperXrayMaskManager(config("on"));
        var a = manager.entryFor("minecraft:overworld", () -> DISABLED);
        var b = manager.entryFor("minecraft:the_nether", () -> DISABLED);
        assertSame(a.mask(), b.mask());
    }

    @Test
    void fallbackKindFollowsTheDimensionString() {
        var manager = new PaperXrayMaskManager(config("on"));
        assertEquals(FallbackKind.NETHER,
                manager.entryFor("minecraft:the_nether", () -> DISABLED).kind());
    }

    @Test
    void diagLineAggregatesLabelAndCounter() {
        var manager = new PaperXrayMaskManager(config("auto"));
        assertEquals("Xray: active=off, masked_sections=0", manager.diagLine());
        manager.entryFor("minecraft:overworld", () -> enabled(64));
        manager.countMaskedSection();
        assertEquals("Xray: active=paper-config, masked_sections=1", manager.diagLine());
    }

    @Test
    void staticHolderLifecycleIsOwnerGuarded() {
        var first = PaperXrayMaskManager.activate(config("on"));
        assertSame(first, PaperXrayMaskManager.current());

        // A successor replaces the holder; the PREDECESSOR's late retract must be a no-op,
        // as must a null owner — the test-wired-service shape: its shutdown() runs with a
        // null xrayMasks field and must not null out a live production manager.
        var second = PaperXrayMaskManager.activate(config("on"));
        PaperXrayMaskManager.deactivate(first);
        assertSame(second, PaperXrayMaskManager.current());
        PaperXrayMaskManager.deactivate(null);
        assertSame(second, PaperXrayMaskManager.current());

        PaperXrayMaskManager.deactivate(second);
        assertNull(PaperXrayMaskManager.current());
    }
}
