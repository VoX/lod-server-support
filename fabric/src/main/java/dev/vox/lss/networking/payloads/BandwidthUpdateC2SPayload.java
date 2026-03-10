package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record BandwidthUpdateC2SPayload(long desiredRate) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BandwidthUpdateC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_BANDWIDTH_UPDATE));

    public static final StreamCodec<FriendlyByteBuf, BandwidthUpdateC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarLong(payload.desiredRate),
                    buf -> new BandwidthUpdateC2SPayload(buf.readVarLong())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
