package dev.vox.lss.compat;

import dev.vox.lss.testutil.VanishStubState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Melius Vanish bridge (far-player-render-hardening-plan.md WI-7b) against the
 * real-package-name stub: the fast path, the ARGUMENT-ORDER pin (the target must travel as
 * Melius's {@code actor} — a swap would ask whether the vanished admin can see the viewer,
 * true, and broadcast them), and the fail directions — absent = visible, drifted or
 * throwing = HIDDEN.
 */
class MeliusVanishBridgeTest {

    private static final UUID VANISHED = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    private static final UUID PLAIN = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");

    @BeforeEach
    @AfterEach
    void reset() {
        MeliusVanishBridge.resetForTest();
        VanishStubState.reset();
    }

    @Test
    void aNonVanishedTargetIsVisibleWithoutThePerViewerQuery() {
        assertTrue(MeliusVanishBridge.present());
        assertTrue(MeliusVanishBridge.canSee(null, null, PLAIN));
        assertNull(VanishStubState.LAST_ACTOR[0], "isVanished is the fast path — canSeePlayer never ran");
    }

    @Test
    void aVanishedTargetTravelsAsMeliusActorAndIsHidden() {
        VanishStubState.VANISHED.add(VANISHED);
        assertFalse(MeliusVanishBridge.canSee(null, null, VANISHED));
        assertEquals(VANISHED, VanishStubState.LAST_ACTOR[0],
                "the TARGET must be passed as Melius's actor (the vanished one) — the order pin");
        assertTrue(MeliusVanishBridge.canSee(null, null, PLAIN), "the other target stays visible");
    }

    @Test
    void anAbsentApiIsVisibleQuietly() {
        MeliusVanishBridge.classResolver = name -> {
            throw new ClassNotFoundException(name);
        };
        assertFalse(MeliusVanishBridge.present());
        VanishStubState.VANISHED.add(VANISHED);
        assertTrue(MeliusVanishBridge.canSee(null, null, VANISHED), "no vanish mod = nobody is vanished");
    }

    @Test
    void aDriftedApiSurfaceHidesEveryone() {
        MeliusVanishBridge.classResolver = name -> String.class; // present, no VanishAPI surface
        assertTrue(MeliusVanishBridge.present(), "present-but-unusable still counts as present");
        assertFalse(MeliusVanishBridge.canSee(null, null, PLAIN),
                "a vanish mod we cannot consult must fail HIDDEN, never leak");
    }

    @Test
    void aThrowingQueryIsHidden() {
        VanishStubState.VANISHED.add(VANISHED);
        VanishStubState.THROW[0] = true;
        assertFalse(MeliusVanishBridge.canSee(null, null, VANISHED));
        assertFalse(MeliusVanishBridge.canSee(null, null, PLAIN),
                "the throw lands on the fast path too — hidden, not visible");
    }
}
