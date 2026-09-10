package dev.vox.lssfixture.wi6.mixin;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ListenerAccessor {
    @Accessor("connection") Connection lssFixtureConnection();
}
