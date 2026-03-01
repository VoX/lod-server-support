package dev.vox.lss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record SessionConfigS2CPayload(
        int protocolVersion,
        boolean enabled,
        int lodDistanceChunks,
        int maxRequestsPerBatch,
        int maxPendingRequests,
        int maxGenerationRequestsPerBatch,
        int generationDistanceChunks
) {
    public static final ResourceLocation ID = new ResourceLocation("lss", "session_config");

    public static SessionConfigS2CPayload read(FriendlyByteBuf buf) {
        return new SessionConfigS2CPayload(
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt()
        );
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.protocolVersion);
        buf.writeBoolean(this.enabled);
        buf.writeVarInt(this.lodDistanceChunks);
        buf.writeVarInt(this.maxRequestsPerBatch);
        buf.writeVarInt(this.maxPendingRequests);
        buf.writeVarInt(this.maxGenerationRequestsPerBatch);
        buf.writeVarInt(this.generationDistanceChunks);
    }
}