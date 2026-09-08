package dev.vox.lss.compat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxyHolderCompatibilityTest {
    /** Verified in the actual 1.21.1 Voxy 0.2.15-beta jar: old interface name,
     * namespaced shutdown method. Static getNullable returns a render system,
     * NOT a holder, so the existing LevelRenderer carrier remains appropriate. */
    public interface Ported1211Holder {
        void voxy$shutdownRenderer();
    }

    @Test void resetMustResolveTheNamespacedMethodOnTheOldHolderInterface() throws Throwable {
        VoxyCompat.resetSeams();
        VoxyCompat.resetResetDomainForTest();
        VoxyCompat.classResolver = name -> {
            if (name.equals("me.cortex.voxy.client.core.IVoxyRenderSystemHolder")) {
                throw new ClassNotFoundException(name);
            }
            if (name.equals("me.cortex.voxy.client.core.IGetVoxyRenderSystem")) {
                return Ported1211Holder.class;
            }
            return Class.forName(name);
        };
        try {
            assertTrue(VoxyCompat.initResetDomain(),
                    "1.21.1 Voxy 0.2.15-beta exposes IGetVoxyRenderSystem.voxy$shutdownRenderer");
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            Ported1211Holder carrier = calls::incrementAndGet;
            var field = VoxyCompat.class.getDeclaredField("rendererHolderShutdown");
            field.setAccessible(true);
            ((java.lang.invoke.MethodHandle) field.get(null)).invokeExact((Object) carrier);
            assertEquals(1, calls.get(), "resolved method must invoke the actual holder once");
        } finally {
            VoxyCompat.resetSeams();
            VoxyCompat.resetResetDomainForTest();
        }
    }
}
