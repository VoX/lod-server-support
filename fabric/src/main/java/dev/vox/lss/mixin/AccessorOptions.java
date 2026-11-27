package dev.vox.lss.mixin;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The server's view distance from the login packet, for the Vertigo repair (issue #437): it
 *  decides when the server has let a chunk go, so a re-ask is a store serve and not a
 *  main-thread serialize. Fabric client only (Vertigo is Fabric-only). */
@Mixin(Options.class)
public interface AccessorOptions {
    @Accessor("serverRenderDistance")
    int lss$getServerRenderDistance();
}
