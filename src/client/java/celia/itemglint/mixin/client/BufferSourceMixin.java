package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineCompat;
import celia.itemglint.client.HeldItemOutlineRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiBufferSource.BufferSource.class)
public abstract class BufferSourceMixin {
    @Inject(method = "endBatch", at = @At("TAIL"))
    private void itemglint$compositeHeldOutlinesAfterMainBatch(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null || !HeldItemOutlineRenderer.hasPendingComposites()) {
            return;
        }

        if (HeldItemOutlineCompat.shouldUseSodiumIrisPipeline(minecraft)) {
            return;
        }

        RenderBuffers renderBuffers = ((GameRendererAccessor) minecraft.gameRenderer).itemglint$getRenderBuffers();
        if (renderBuffers == null || (Object) this != renderBuffers.bufferSource()) {
            return;
        }

        HeldItemOutlineRenderer.renderPendingComposites(minecraft, minecraft.getMainRenderTarget());
    }
}
