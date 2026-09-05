package dev.vox.lss.networking.client;

import dev.vox.lss.testutil.RepoPaths;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The swim-amount accessor behind far-player-render-hardening-plan.md WI-6 fold (d): the two
 * private {@code LivingEntity} fields it targets must exist with the expected type (a mixin
 * config with {@code required: true} hard-crashes the client at boot on a rename — this reds
 * Tier 1 instead), and the accessor must be listed under {@code client} in BOTH loaders' mixin
 * configs (an unlisted accessor compiles but ClassCastExceptions at the first swimming proxy).
 * Review folds C2/C3. This line adds {@code AccessorEntityRenderDispatcher} (the armor lift's
 * equipment-asset lookup) under the same two pins.
 */
class AccessorLivingEntityContractTest {

    @Test
    void theAccessorTargetsFieldsThatActuallyExist() throws Exception {
        for (String name : new String[] {"swimAmount", "swimAmountO"}) {
            var field = LivingEntity.class.getDeclaredField(name);
            assertEquals(float.class, field.getType(),
                    "vanilla's LivingEntity." + name + " changed type — AccessorLivingEntity must move with it");
        }
    }

    @Test
    void theDispatcherAccessorTargetsTheEquipmentAssetsField() throws Exception {
        // The armor depth-lift's tiers (fold (e2)) read the dispatcher's private
        // EquipmentAssetManager through AccessorEntityRenderDispatcher on this line — the
        // renderer degrades to one armor tier if the accessor is missing, so this is the only
        // thing that reds on a vanilla rename.
        var field = net.minecraft.client.renderer.entity.EntityRenderDispatcher.class
                .getDeclaredField("equipmentAssets");
        assertEquals(net.minecraft.client.resources.model.EquipmentAssetManager.class, field.getType(),
                "vanilla's EntityRenderDispatcher.equipmentAssets changed — AccessorEntityRenderDispatcher must move with it");
    }

    @Test
    void bothMixinConfigsListTheAccessorsUnderClient() throws IOException {
        for (String cfg : new String[] {"fabric/src/main/resources/lss.mixins.json",
                "neoforge/src/main/resources/lss.neoforge.mixins.json"}) {
            String json = Files.readString(RepoPaths.locate(cfg));
            int client = json.indexOf("\"client\"");
            for (String accessor : new String[] {"AccessorLivingEntity", "AccessorEntityRenderDispatcher"}) {
                assertTrue(client >= 0 && json.indexOf("\"" + accessor + "\"", client) > client,
                        cfg + ": " + accessor + " must be listed under \"client\"");
            }
        }
    }
}
