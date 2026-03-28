package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = ItemGlint.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        ShaderInstance heldItemOutlineShader = new ShaderInstance(
                event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(ItemGlint.MODID, "held_item_outline"),
                DefaultVertexFormat.POSITION_TEX
        );
        event.registerShader(heldItemOutlineShader, HeldItemOutlineShaderRegistry::setShader);

        ShaderInstance heldItemBloomBlurShader = new ShaderInstance(
                event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(ItemGlint.MODID, "held_item_bloom_blur"),
                DefaultVertexFormat.POSITION_TEX
        );
        event.registerShader(heldItemBloomBlurShader, HeldItemBloomBlurShaderRegistry::setShader);

        ShaderInstance heldItemBloomShader = new ShaderInstance(
                event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(ItemGlint.MODID, "held_item_bloom"),
                DefaultVertexFormat.POSITION_TEX
        );
        event.registerShader(heldItemBloomShader, HeldItemBloomShaderRegistry::setShader);
    }
}
