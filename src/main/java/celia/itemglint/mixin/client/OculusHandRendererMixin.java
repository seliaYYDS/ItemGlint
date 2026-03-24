package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineCompat;
import celia.itemglint.client.HeldItemOutlineRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public abstract class OculusHandRendererMixin {
    @Inject(method = "renderSolid", at = @At("HEAD"), require = 0)
    private void itemglint$beginEmbeddiumSolidCapture(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (HeldItemOutlineCompat.shouldUseEmbeddiumOculusPipeline(minecraft)) {
            HeldItemOutlineRenderer.debugCompat(minecraft, "OculusHandRendererMixin begin solid capture");
            HeldItemOutlineRenderer.beginEmbeddiumCompatCapture(minecraft, minecraft.getMainRenderTarget());
        }
    }

    @Inject(method = "renderSolid", at = @At("RETURN"), require = 0)
    private void itemglint$endEmbeddiumSolidCapture(CallbackInfo ci) {
        HeldItemOutlineRenderer.debugCompat(Minecraft.getInstance(), "OculusHandRendererMixin end solid capture");
        HeldItemOutlineRenderer.endEmbeddiumCompatCapture();
    }

    @Inject(method = "renderTranslucent", at = @At("HEAD"), require = 0)
    private void itemglint$beginEmbeddiumTranslucentCapture(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (HeldItemOutlineCompat.shouldUseEmbeddiumOculusPipeline(minecraft)) {
            HeldItemOutlineRenderer.debugCompat(minecraft, "OculusHandRendererMixin begin translucent capture");
            HeldItemOutlineRenderer.beginEmbeddiumCompatCapture(minecraft, minecraft.getMainRenderTarget());
        }
    }

    @Inject(method = "renderTranslucent", at = @At("RETURN"), require = 0)
    private void itemglint$endEmbeddiumTranslucentCapture(CallbackInfo ci) {
        HeldItemOutlineRenderer.debugCompat(Minecraft.getInstance(), "OculusHandRendererMixin end translucent capture");
        HeldItemOutlineRenderer.endEmbeddiumCompatCapture();
    }
}
