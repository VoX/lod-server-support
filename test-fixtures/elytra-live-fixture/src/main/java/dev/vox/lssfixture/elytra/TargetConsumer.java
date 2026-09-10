package dev.vox.lssfixture.elytra;

import net.fabricmc.api.ClientModInitializer;
import dev.vox.lss.api.LSSApi;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit target-only test consumer, enabling the real negotiated transport without a renderer. */
public final class TargetConsumer implements ClientModInitializer {
    private static final AtomicLong RECEIPTS=new AtomicLong();
    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("lss.rig.elytraTarget"))return;
        LSSApi.registerColumnConsumer((level,dimension,x,z,column)->{
            long count=RECEIPTS.incrementAndGet();
            if(count==1 || count%100==0)System.out.println("LSS_ELYTRA_TARGET_RECEIPT count="+count+" x="+x+" z="+z);
        });
        System.out.println("LSS_ELYTRA_TARGET_CONSUMER registered=true");
    }
}
