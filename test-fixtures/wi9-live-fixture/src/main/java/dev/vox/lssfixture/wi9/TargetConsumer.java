package dev.vox.lssfixture.wi9;

import dev.vox.lss.api.LSSApi;
import net.fabricmc.api.ClientModInitializer;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit real consumer for the two owned native subject clients; no proxy injection. */
public final class TargetConsumer implements ClientModInitializer {
    private static final AtomicLong COUNT = new AtomicLong();
    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("lss.rig.seatedTarget")) return;
        if (!System.getProperty("lss.rig.runId", "").matches("[A-Za-z0-9_-]+"))
            throw new IllegalStateException("owned seated-target run required");
        LSSApi.registerColumnConsumer((level, dimension, x, z, column) -> {
            if (COUNT.incrementAndGet() == 1)
                System.out.println("LSS_SEATED_TARGET_RECEIPT actualConsumer=true");
        });
        System.out.println("LSS_SEATED_TARGET_CONSUMER registered=true");
    }
}
