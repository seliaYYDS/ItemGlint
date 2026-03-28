package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererFrameMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void itemglint$beginHeldOutlineFrame(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        HeldItemOutlineRenderer.beginFrame();
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void itemglint$beginItemInHandRender(float partialTick, boolean renderLevel, Matrix4f projectionMatrix, CallbackInfo ci) {
        HeldItemOutlineRenderer.beginItemInHandRender(projectionMatrix);
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void itemglint$endItemInHandRender(float partialTick, boolean renderLevel, Matrix4f projectionMatrix, CallbackInfo ci) {
        HeldItemOutlineRenderer.endItemInHandRender();
    }
}
