package dev.vox.lssfixture.export.mixin;
import dev.vox.lssfixture.export.ExportProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(net.minecraft.client.gui.components.ChatComponent.class) public abstract class ChatMixin {
@Inject(method="addMessage(Lnet/minecraft/network/chat/Component;)V",at=@At("HEAD"),require=1)
private void message(net.minecraft.network.chat.Component message,CallbackInfo ci){ExportProbe.chatFeedback(message.getString());}
}