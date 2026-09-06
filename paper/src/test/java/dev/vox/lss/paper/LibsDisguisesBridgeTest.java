package dev.vox.lss.paper;

import me.libraryaddict.disguise.DisguiseAPI;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The LibsDisguises bridge (issue #282): binds the verified upstream signature against the
 * real-package-name stub, gates on the plugin being enabled BEFORE resolving, reads absent
 * and drifted surfaces as visible with one warn, and surfaces a throwing read as an
 * {@link IllegalStateException} WITHOUT latching — the privacy ladder's contained catch
 * turns that into HIDDEN. {@code :paper:test} is one JVM, so every test resets the bridge
 * and the stub's switches.
 */
class LibsDisguisesBridgeTest {

    @BeforeEach
    void reset() {
        LibsDisguisesBridge.resetForTest();
        DisguiseAPI.reset();
        LibsDisguisesBridge.enabledProbe = () -> true;
    }

    @AfterEach
    void restore() {
        LibsDisguisesBridge.resetForTest();
        DisguiseAPI.reset();
    }

    private static Entity entity() {
        return mock(org.bukkit.entity.Player.class);
    }

    @Test
    void bindsTheVerifiedSignatureAgainstTheStub() {
        var e = entity();
        assertFalse(LibsDisguisesBridge.isDisguised(e), "not in the disguised set: visible");
        assertTrue(LibsDisguisesBridge.present(), "resolved through the real-package-name stub");
        assertEquals(MethodType.methodType(boolean.class, Entity.class),
                LibsDisguisesBridge.boundTypeForTest(),
                "the bound shape is upstream's isDisguised(Entity) — DisguiseAPI.java:410");
        DisguiseAPI.DISGUISED.add(e);
        assertTrue(LibsDisguisesBridge.isDisguised(e), "the stub's answer flows through");
        assertEquals(0, LibsDisguisesBridge.resolveWarnsForTest(), "a clean bind never warns");
    }

    @Test
    void aDisabledPluginSkipsTheReadEntirely() {
        LibsDisguisesBridge.enabledProbe = () -> false;
        var e = entity();
        DisguiseAPI.DISGUISED.add(e);
        assertFalse(LibsDisguisesBridge.isDisguised(e),
                "a loaded-but-disabled LibsDisguises rewrites no packets: not consulted");
        assertFalse(LibsDisguisesBridge.present(), "the gate runs BEFORE resolution");
        assertNull(LibsDisguisesBridge.boundTypeForTest());
        assertEquals(0, DisguiseAPI.CALLS, "the stub was never called");
    }

    @Test
    void anInvisibleClassReadsAbsentWithOneWarnAndNoThrow() {
        LibsDisguisesBridge.classResolver = name -> {
            throw new ClassNotFoundException(name);
        };
        var e = entity();
        DisguiseAPI.DISGUISED.add(e);
        assertFalse(LibsDisguisesBridge.isDisguised(e), "absent API: the pre-fix behavior");
        assertFalse(LibsDisguisesBridge.present());
        assertEquals(1, LibsDisguisesBridge.resolveWarnsForTest(),
                "the plugin is ENABLED yet invisible — worth one warn");
        assertFalse(LibsDisguisesBridge.isDisguised(e));
        assertEquals(1, LibsDisguisesBridge.resolveWarnsForTest(), "once per JVM");
    }

    @Test
    void aDriftedSurfaceReadsAbsentWithOneWarn() {
        LibsDisguisesBridge.classResolver = name -> String.class; // present, no isDisguised(Entity)
        var e = entity();
        DisguiseAPI.DISGUISED.add(e);
        assertFalse(LibsDisguisesBridge.isDisguised(e), "drift: visible, never a throw");
        assertFalse(LibsDisguisesBridge.present());
        assertEquals(1, LibsDisguisesBridge.resolveWarnsForTest());
        LibsDisguisesBridge.isDisguised(e);
        assertEquals(1, LibsDisguisesBridge.resolveWarnsForTest(), "once per JVM");
    }

    @Test
    void aFailingClassLoadReadsAbsentWithOneWarn() {
        LibsDisguisesBridge.classResolver = name -> {
            throw new NoClassDefFoundError("me/libraryaddict/disguise/DisguiseUtilities");
        };
        assertFalse(LibsDisguisesBridge.isDisguised(entity()),
                "a broken LibsDisguises install must not make the ladder throw at resolve time");
        assertEquals(1, LibsDisguisesBridge.resolveWarnsForTest());
    }

    @Test
    void aThrowingReadSurfacesAsIllegalStateExceptionWithoutLatching() {
        var e = entity();
        DisguiseAPI.THROW = new IllegalStateException("raced");
        assertThrows(IllegalStateException.class, () -> LibsDisguisesBridge.isDisguised(e),
                "a throwing read is surfaced, never answered false");
        // An Error (a broken install's shape) is WRAPPED so the ladder's catch (Exception)
        // contains it — unwrapped, it would escape the ladder AND the pump.
        DisguiseAPI.THROW = new NoClassDefFoundError("me/libraryaddict/disguise/DisguiseUtilities");
        var wrapped = assertThrows(IllegalStateException.class,
                () -> LibsDisguisesBridge.isDisguised(e));
        assertInstanceOf(NoClassDefFoundError.class, wrapped.getCause());
        // No latch-to-absent: the next read works and still consults the plugin.
        DisguiseAPI.THROW = null;
        DisguiseAPI.DISGUISED.add(e);
        assertTrue(LibsDisguisesBridge.isDisguised(e),
                "an enabled-but-throwing LibsDisguises may still rewrite spawns — never latch absent");
        assertTrue(LibsDisguisesBridge.present());
        assertEquals(0, LibsDisguisesBridge.resolveWarnsForTest(),
                "invoke throws are the ladder's once-warn, not a resolve warn");
    }

    @Test
    void virtualMachineErrorsPropagateUnwrapped() {
        DisguiseAPI.THROW = new OutOfMemoryError("x");
        assertThrows(OutOfMemoryError.class, () -> LibsDisguisesBridge.isDisguised(entity()));
    }
}
