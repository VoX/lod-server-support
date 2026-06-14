package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * #4 fix: {@link PaperOffThreadProcessor#buildAndEnqueueColumnPayload} guards a >256-char
 * dimension id and drops just that column (returns false → the caller answers up-to-date),
 * instead of letting {@code encodeVoxelColumnPreEncoded}'s writeUtf cap throw an
 * EncoderException that would abort the WHOLE processing cycle. No real dimension id is this
 * long; this pins the containment so the cycle-killing behavior cannot return silently.
 */
class PaperOffThreadProcessorTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void over256CharDimensionIsDroppedBeforeEncodeInsteadOfThrowing() {
        // Thread created but never start()ed (PaperPayloadEdgeTest harness pattern).
        var proc = new PaperOffThreadProcessor(new ConcurrentHashMap<>(), null, false, null, 1);
        var state = mock(PaperPlayerRequestState.class);
        String dim = "lss:" + "a".repeat(LSSConstants.MAX_DIMENSION_STRING_LENGTH - 3); // 257 chars
        byte[] sections = {1, 2, 3};

        assertFalse(proc.buildAndEnqueueColumnPayload(state, 1, 2, dim, 42L, 7L, sections, 9),
                "an oversized dimension id drops the column (false), it must not throw and "
                        + "abort the cycle");
        verify(state, never()).addReadyPayload(any());

        // A normal dimension still enqueues — the guard rejects only the pathological one.
        assertTrue(proc.buildAndEnqueueColumnPayload(
                state, 1, 2, "minecraft:overworld", 42L, 8L, sections, 9));
        verify(state).addReadyPayload(any());
    }
}
