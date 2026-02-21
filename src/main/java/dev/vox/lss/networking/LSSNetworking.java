package dev.vox.lss.networking;

import dev.vox.lss.networking.payloads.CancelRequestC2SPayload;
import dev.vox.lss.networking.payloads.ChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.ChunkSectionS2CPayload;
import dev.vox.lss.networking.payloads.ColumnUpToDateS2CPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.RequestCompleteS2CPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class LSSNetworking {
    public static final int PROTOCOL_VERSION = 3;

    public static void registerPayloads() {
        // Client -> Server
        PayloadTypeRegistry.playC2S().register(
                HandshakeC2SPayload.TYPE,
                HandshakeC2SPayload.CODEC
        );
        PayloadTypeRegistry.playC2S().register(
                ChunkRequestC2SPayload.TYPE,
                ChunkRequestC2SPayload.CODEC
        );
        PayloadTypeRegistry.playC2S().register(
                CancelRequestC2SPayload.TYPE,
                CancelRequestC2SPayload.CODEC
        );

        // Server -> Client
        PayloadTypeRegistry.playS2C().register(
                SessionConfigS2CPayload.TYPE,
                SessionConfigS2CPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                ChunkSectionS2CPayload.TYPE,
                ChunkSectionS2CPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                RequestCompleteS2CPayload.TYPE,
                RequestCompleteS2CPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                ColumnUpToDateS2CPayload.TYPE,
                ColumnUpToDateS2CPayload.CODEC
        );
    }
}
