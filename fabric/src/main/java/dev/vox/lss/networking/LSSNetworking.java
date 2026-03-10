package dev.vox.lss.networking;

import dev.vox.lss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.vox.lss.networking.payloads.CancelRequestC2SPayload;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class LSSNetworking {

    public static void registerPayloads() {
        // Client -> Server
        PayloadTypeRegistry.playC2S().register(
                HandshakeC2SPayload.TYPE,
                HandshakeC2SPayload.CODEC
        );
        PayloadTypeRegistry.playC2S().register(
                BatchChunkRequestC2SPayload.TYPE,
                BatchChunkRequestC2SPayload.CODEC
        );
        PayloadTypeRegistry.playC2S().register(
                CancelRequestC2SPayload.TYPE,
                CancelRequestC2SPayload.CODEC
        );
        PayloadTypeRegistry.playC2S().register(
                BandwidthUpdateC2SPayload.TYPE,
                BandwidthUpdateC2SPayload.CODEC
        );

        // Server -> Client
        PayloadTypeRegistry.playS2C().register(
                SessionConfigS2CPayload.TYPE,
                SessionConfigS2CPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                BatchResponseS2CPayload.TYPE,
                BatchResponseS2CPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                DirtyColumnsS2CPayload.TYPE,
                DirtyColumnsS2CPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                VoxelColumnS2CPayload.TYPE,
                VoxelColumnS2CPayload.CODEC
        );
    }
}
