// OVERLAY OF fabric/src/test/java/dev/vox/lss/networking/client/FarPlayerMountLadderTest.java @ 7893f5ce2a71a5cc351e3de9a2dbd04852f4e6b8211364409cf9767f0ac33a26
// 26.1 line overlay (single-branch-consolidation-plan.md §3.2). Refresh the stamp when the shared file changes.
package dev.vox.lss.networking.client;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The R-10 graceful-degrade ladder pins (mega plan E3 row): the injected-resolver
 * rung behaviors + per-type once-latch, PLUS the one NON-injected pin against the
 * REAL registry — {@code BuiltInRegistries.ENTITY_TYPE} is a DefaultedRegistry whose
 * plain {@code getValue} returns PIG for unknown ids (SeeU's exact bug; their
 * null-check is dead code), so rung 1 MUST resolve via {@code getOptional}. Injected
 * tests alone could mask a regression back to {@code getValue}.
 */
class FarPlayerMountLadderTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rung1RealRegistryPinGetOptionalNotTheDefaultedGetValue() {
        var unknown = Identifier.parse("modx:definitely_not_an_entity");
        assertEquals(EntityType.PIG, BuiltInRegistries.ENTITY_TYPE.getValue(unknown),
                "the trap this pin exists for: the DefaultedRegistry's plain lookup"
                        + " returns PIG for unknown ids — if this ever fails, MC"
                        + " changed the default and the pin needs re-derivation");
        assertTrue(FarPlayerMountLadder.resolveTypeStrict("modx:definitely_not_an_entity")
                        .isEmpty(),
                "rung 1 resolves unknown identities EMPTY (getOptional), never PIG");
        assertEquals(Optional.of(EntityType.HORSE),
                FarPlayerMountLadder.resolveTypeStrict("minecraft:horse"),
                "known identities resolve to the real type");
        assertTrue(FarPlayerMountLadder.resolveTypeStrict("not a valid ##identifier")
                .isEmpty(), "malformed identities resolve empty, never throw");
    }

    @Test
    void rung1UnknownTypeLatchesOnceAndRendersUnmounted() {
        var calls = new AtomicInteger();
        var ladder = new FarPlayerMountLadder(
                id -> { calls.incrementAndGet(); return Optional.empty(); },
                (type, level) -> fail("factory must not run for an unresolved type"));
        assertNull(ladder.createMount("modx:ghost", null));
        assertNull(ladder.createMount("modx:ghost", null));
        assertNull(ladder.createMount("modx:ghost", null));
        assertEquals(1, calls.get(),
                "the per-type latch stops re-resolving (and re-warning) a failed type");
        assertTrue(ladder.isLatched("modx:ghost"));
    }

    @Test
    void rung2NullCreationAndThrowingCreationBothLatchPerType() {
        var ladder = new FarPlayerMountLadder(
                id -> Optional.of(EntityType.HORSE),
                (type, level) -> null);
        assertNull(ladder.createMount("minecraft:horse", null),
                "a non-creatable type degrades to unmounted");
        assertTrue(ladder.isLatched("minecraft:horse"));

        var throwing = new FarPlayerMountLadder(
                id -> Optional.of(EntityType.HORSE),
                (type, level) -> { throw new IllegalStateException("modded ctor"); });
        assertNull(throwing.createMount("modmod:cursed_mount", null),
                "a THROWING creation is contained to unmounted, never a crash");
        assertTrue(throwing.isLatched("modmod:cursed_mount"));
        // A different type is unaffected by another type's latch.
        assertFalse(throwing.isLatched("minecraft:horse"));
    }

    @Test
    void theFactoryRunsForResolvableTypesAndOnlyFailureLatches() {
        var calls = new AtomicInteger();
        var ladder = new FarPlayerMountLadder(
                id -> Optional.of(EntityType.HORSE),
                (type, level) -> { calls.incrementAndGet(); return null; });
        // A real Entity needs a live ClientLevel (Tier 3 territory) — the factory-ran
        // counter plus the null-latch pin the control flow: resolution reached the
        // factory, and it is the FAILURE that latched, not the resolution.
        assertNull(ladder.createMount("minecraft:horse", null));
        assertEquals(1, calls.get(), "the factory runs for resolvable types");
        assertNull(ladder.createMount("minecraft:horse", null));
        assertEquals(1, calls.get(), "the null-create latch stops the retry");
    }
}
