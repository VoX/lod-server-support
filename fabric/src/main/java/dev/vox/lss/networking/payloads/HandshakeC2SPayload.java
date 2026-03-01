package dev.vox.lss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record HandshakeC2SPayload(int protocolVersion) {
    public static final ResourceLocation ID = new ResourceLocation("lss", "handshake_c2s");

    public static HandshakeC2SPayload read(FriendlyByteBuf buf) {
        return new HandshakeC2SPayload(buf.readVarInt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.protocolVersion);
    }
}