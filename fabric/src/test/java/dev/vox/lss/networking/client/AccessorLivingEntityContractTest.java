package dev.vox.lss.networking.client;

import dev.vox.lss.testutil.RepoPaths;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two client accessors behind far-player-render-hardening-plan.md: the swim-amount
 * accessor (WI-6 fold (d)) and — this line's fold (e2) port — the dispatcher's equipment-asset
 * accessor. Their private target fields must exist with the expected type (a mixin config
 * with {@code required: true} hard-crashes the client at boot on a rename — this reds Tier 1
 * instead), and each accessor must be listed under {@code client} in BOTH loaders' mixin
 * configs (an unlisted accessor compiles but ClassCastExceptions at the first swimming /
 * armored proxy). Review folds C2/C3.
 */
class AccessorLivingEntityContractTest {

    private static final String[] CONFIGS = {"fabric/src/main/resources/lss.mixins.json",
            "neoforge/src/main/resources/lss.neoforge.mixins.json"};

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
        var field = EntityRenderDispatcher.class.getDeclaredField("equipmentAssets");
        assertEquals(EquipmentAssetManager.class, field.getType(),
                "vanilla's EntityRenderDispatcher.equipmentAssets changed type — AccessorEntityRenderDispatcher must move with it");
    }

    @Test
    void bothMixinConfigsListTheAccessorsUnderClient() throws IOException {
        for (String cfg : CONFIGS) {
            String json = Files.readString(RepoPaths.locate(cfg));
            int client = json.indexOf("\"client\"");
            for (String accessor : new String[] {"AccessorLivingEntity", "AccessorEntityRenderDispatcher"}) {
                assertTrue(client >= 0 && json.indexOf("\"" + accessor + "\"", client) > client,
                        cfg + ": " + accessor + " must be listed under \"client\"");
            }
        }
    }
}
