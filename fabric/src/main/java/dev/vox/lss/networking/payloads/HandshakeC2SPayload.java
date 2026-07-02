package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record HandshakeC2SPayload(int protocolVersion, int capabilities) implements FabricPacket {
    public static final PacketType<HandshakeC2SPayload> TYPE =
            PacketType.create(new ResourceLocation(LSSConstants.CHANNEL_HANDSHAKE), HandshakeC2SPayload::read);

    public static HandshakeC2SPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        int caps = buf.readVarInt();
        return new HandshakeC2SPayload(version, caps);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.protocolVersion);
        buf.writeVarInt(this.capabilities);
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}
