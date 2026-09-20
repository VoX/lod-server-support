package dev.vox.lss.networking.server;

import dev.vox.lss.common.LogThrottle;
import dev.vox.lss.common.processing.LoadedColumnData;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the probe-path containment (docs/planning/antixray-compat-design.md §2 A2): the
 * in-memory probe is the one serve path with no catch between {@code section.write} and the
 * server tick loop, so a serializer throwing — the AntiXray class of foreign-mixin
 * conflict — must resolve as "no probe" (the column falls through to the disk/generation
 * ladder) and never propagate into END_SERVER_TICK. VirtualMachineErrors are deliberately
 * NOT contained.
 */
class ProbeContainmentTest {

    private final LogThrottle throttle = new LogThrottle(60_000);

    @Test
    void throwingSerializerIsContainedAsNoProbe() {
        // The exact production shape: unbound ScopedValue.get() in an AntiXray mixin.
        assertNull(RequestProcessingService.serializeProbeContained(
                (level, chunk, cx, cz) -> { throw new NoSuchElementException("ScopedValue not bound"); },
                null, null, 3, -7, throttle));
    }

    @Test
    void linkageErrorIsContainedAsNoProbe() {
        assertNull(RequestProcessingService.serializeProbeContained(
                (level, chunk, cx, cz) -> { throw new NoSuchMethodError("broken mixin target"); },
                null, null, 0, 0, throttle));
    }

    @Test
    void repeatedFailuresStayContained() {
        // A broken mixin fails EVERY probe; the throttle must not change the containment
        // outcome after the first release window is consumed.
        for (int i = 0; i < 100; i++) {
            assertNull(RequestProcessingService.serializeProbeContained(
                    (level, chunk, cx, cz) -> { throw new IllegalStateException("always"); },
                    null, null, i, i, throttle));
        }
    }

    @Test
    void successfulSerializePassesThroughUntouched() {
        var data = new LoadedColumnData(3, -7, new byte[]{1}, 1);
        assertSame(data, RequestProcessingService.serializeProbeContained(
                (level, chunk, cx, cz) -> data, null, null, 3, -7, throttle));
    }

    @Test
    void assertionErrorIsContainedAsNoProbe() {
        // VoxyCompat's ingest policy: a foreign `assert` under -ea is a mixin-failure
        // shape, not a VM failure.
        assertNull(RequestProcessingService.serializeProbeContained(
                (level, chunk, cx, cz) -> { throw new AssertionError("foreign -ea assert"); },
                null, null, 0, 0, throttle));
    }

    @Test
    void virtualMachineErrorsStillPropagate() {
        assertThrows(OutOfMemoryError.class, () -> RequestProcessingService.serializeProbeContained(
                (level, chunk, cx, cz) -> { throw new OutOfMemoryError("synthetic"); },
                null, null, 0, 0, throttle));
    }

    @Test
    void capturePrecedesSerializerAndReentrantInvalidationCannotRefreshIt() throws Exception {
        var guard=new dev.vox.lss.common.processing.LoadedProbeGuard();
        var registration=new dev.vox.lss.common.processing.RequestRegistration();
        long packed=dev.vox.lss.common.PositionUtil.packPosition(3,-7);
        var captured=new java.util.concurrent.atomic.AtomicBoolean();
        var invalidate=guard.getClass().getDeclaredMethod("invalidate",String.class,long[].class);invalidate.setAccessible(true);
        var current=guard.getClass().getDeclaredMethod("current",LoadedColumnData.class,String.class,long.class,dev.vox.lss.common.processing.RequestRegistration.class);current.setAccessible(true);
        var result=RequestProcessingService.serializeCapturedProbe(()->{
            captured.set(true);return guard.capture("minecraft:overworld",packed,registration);
        },(level,chunk,cx,cz)->{
            assertTrue(captured.get());
            try {invalidate.invoke(guard,"minecraft:overworld",new long[]{packed});}
            catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            return new LoadedColumnData(cx,cz,new byte[]{1},1);
        },null,null,3,-7,throttle);
        assertNotNull(result);assertNotNull(result.probeCapture());
        assertFalse((boolean)current.invoke(guard,result,"minecraft:overworld",packed,registration));
        var fresh=RequestProcessingService.serializeCapturedProbe(()->guard.capture("minecraft:overworld",packed,registration),
                (level,chunk,cx,cz)->new LoadedColumnData(cx,cz,new byte[]{2},1),null,null,3,-7,throttle);
        assertTrue((boolean)current.invoke(guard,fresh,"minecraft:overworld",packed,registration));
        assertNull(RequestProcessingService.serializeCapturedProbe(()->guard.capture("minecraft:overworld",packed,registration),
                (level,chunk,cx,cz)->new LoadedColumnData(cx+1,cz,new byte[]{2},1),null,null,3,-7,throttle));
    }
}
