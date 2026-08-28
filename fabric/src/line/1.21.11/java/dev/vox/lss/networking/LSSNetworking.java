// OVERLAY OF fabric/src/main/java/dev/vox/lss/networking/LSSNetworking.java @ ea1c3dcbd2e11b7e1fb31823682b3719777527002d752ea6f9197b0888f06d5b
// 1.21.11 line overlay (single-branch-consolidation-plan.md §3.2).
package dev.vox.lss.networking;

import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import dev.vox.lss.networking.payloads.ClientInfoC2SPayload;
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
                ClientInfoC2SPayload.TYPE,
                ClientInfoC2SPayload.CODEC
        );
        PayloadTypeRegistry.playC2S().register(
                dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload.TYPE,
                dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload.CODEC
        );
        PayloadTypeRegistry.playC2S().register(
                dev.vox.lss.networking.payloads.RegionSummaryRequestC2SPayload.TYPE,
                dev.vox.lss.networking.payloads.RegionSummaryRequestC2SPayload.CODEC
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
        PayloadTypeRegistry.playS2C().register(
                dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload.TYPE,
                dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload.TYPE,
                dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                dev.vox.lss.networking.payloads.RegionSummaryS2CPayload.TYPE,
                dev.vox.lss.networking.payloads.RegionSummaryS2CPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                dev.vox.lss.networking.payloads.ColumnStampsS2CPayload.TYPE,
                dev.vox.lss.networking.payloads.ColumnStampsS2CPayload.CODEC
        );
    }
}
