package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the count guards on the v16 batch codecs. Both decoders allocate arrays sized by a
 * remote-controlled VarInt; the range check is the only defense against a hostile frame
 * forcing a multi-GB allocation (hostile client vs the server for requests, hostile server
 * vs the client for responses). Dropping a guard in a future protocol edit must fail here.
 */
class BatchCountGuardTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void batchRequestDecoderRejectsOversizedCount() {
        var b = buf();
        b.writeVarInt(LSSConstants.MAX_BATCH_CHUNK_REQUESTS + 1);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> BatchChunkRequestC2SPayload.read(b));
        } finally {
            b.release();
        }
    }

    @Test
    void batchRequestDecoderRejectsNegativeCount() {
        var b = buf();
        b.writeVarInt(-1);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> BatchChunkRequestC2SPayload.read(b));
        } finally {
            b.release();
        }
    }

    @Test
    void batchRequestDecoderRejectsIntegerMaxCountBeforeAllocation() {
        // Integer.MAX_VALUE would be a ~16 GB allocation if the guard ran after
        // `new long[count]`; the MAX+1 case alone cannot prove guard-before-allocation
        // because its ~8 KB array would succeed. Mirrors the Paper twin
        // (PaperPayloadEdgeTest#batchRequestCountGuardRejectsBeforeAllocation).
        var b = buf();
        b.writeVarInt(Integer.MAX_VALUE);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> BatchChunkRequestC2SPayload.read(b));
        } finally {
            b.release();
        }
    }

    @Test
    void batchRequestDecoderAcceptsZeroCountAsEmpty() {
        // {0} is a legal idle-client frame: a `count <= 0` guard tightening would kick
        // every idle client (Paper's count==0 acceptance is pinned in its WireParityTest).
        var b = buf();
        b.writeVarInt(0);
        try {
            var decoded = BatchChunkRequestC2SPayload.read(b);
            assertEquals(0, decoded.count());
            assertEquals(0, decoded.packedPositions().length);
            assertEquals(0, decoded.clientTimestamps().length);
            assertEquals(0, b.readableBytes());
        } finally {
            b.release();
        }
    }

    @Test
    void batchRequestTruncatedBodyThrowsInsteadOfZeroFilling() {
        // count=10 with only 3 pairs present: decode must throw, never return a payload
        // padded with phantom (0,0) positions — those would be admitted as real requests
        // for chunk (0,0) under timestamp 0 (a generation ask).
        var b = buf();
        b.writeVarInt(10);
        for (int i = 0; i < 3; i++) {
            b.writeLong(i + 1);
            b.writeLong(-1L);
        }
        try {
            assertThrows(IndexOutOfBoundsException.class,
                    () -> BatchChunkRequestC2SPayload.read(b));
        } finally {
            b.release();
        }
    }

    @Test
    void batchRequestDecoderAcceptsMaxCount() {
        var b = buf();
        b.writeVarInt(LSSConstants.MAX_BATCH_CHUNK_REQUESTS);
        for (int i = 0; i < LSSConstants.MAX_BATCH_CHUNK_REQUESTS; i++) {
            b.writeLong(i); // packed position
            b.writeLong(-1L); // client timestamp
        }
        try {
            var decoded = BatchChunkRequestC2SPayload.read(b);
            assertEquals(LSSConstants.MAX_BATCH_CHUNK_REQUESTS, decoded.count());
        } finally {
            b.release();
        }
    }

    @Test
    void batchResponseDecoderRejectsOversizedCount() {
        var b = buf();
        b.writeVarInt(LSSConstants.MAX_BATCH_RESPONSES + 1);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> BatchResponseS2CPayload.read(b));
        } finally {
            b.release();
        }
    }

    @Test
    void batchResponseDecoderRejectsNegativeCount() {
        var b = buf();
        b.writeVarInt(-1);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> BatchResponseS2CPayload.read(b));
        } finally {
            b.release();
        }
    }
}
