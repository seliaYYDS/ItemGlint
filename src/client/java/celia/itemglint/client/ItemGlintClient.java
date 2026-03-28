package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import java.io.IOException;

public final class ItemGlintClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ItemGlint.LOGGER.info("Initializing client for {}.", ItemGlint.MOD_ID);
        CoreShaderRegistrationCallback.EVENT.register(this::registerShaders);
        ShaderClientConfig.getInstance().load();
        ShaderKeyMappings.initialize();
    }

    private void registerShaders(CoreShaderRegistrationCallback.RegistrationContext context) throws IOException {
        context.register(
                ResourceLocation.fromNamespaceAndPath(ItemGlint.MOD_ID, "held_item_outline"),
                DefaultVertexFormat.POSITION_TEX,
                HeldItemOutlineShaderRegistry::setShader
        );
        context.register(
                ResourceLocation.fromNamespaceAndPath(ItemGlint.MOD_ID, "held_item_bloom_blur"),
                DefaultVertexFormat.POSITION_TEX,
                HeldItemBloomBlurShaderRegistry::setShader
        );
        context.register(
                ResourceLocation.fromNamespaceAndPath(ItemGlint.MOD_ID, "held_item_bloom"),
                DefaultVertexFormat.POSITION_TEX,
                HeldItemBloomShaderRegistry::setShader
        );
    }
}
