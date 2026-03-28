package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineCompat;
import celia.itemglint.client.HeldItemOutlineRenderer;
import celia.itemglint.client.HeldItemOutlineRenderer.HandEffectTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemInHandRenderer.class, priority = 900)
public abstract class ItemInHandRendererIrisFallbackMixin {
    @Unique
    private Matrix4f itemglint$capturedPose;
    @Unique
    private Matrix3f itemglint$capturedNormal;
    @Unique
    private boolean itemglint$preparedFallbackCapture;

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), require = 0)
    private void itemglint$prepareIrisFallbackCapture(float tickDelta, PoseStack poseStack,
                                                      MultiBufferSource.BufferSource bufferSource, LocalPlayer player,
                                                      int packedLight, CallbackInfo ci) {
        if (!itemglint$shouldUseIrisFallback(player)) {
            itemglint$preparedFallbackCapture = false;
            itemglint$capturedPose = null;
            itemglint$capturedNormal = null;
            return;
        }

        itemglint$capturedPose = new Matrix4f(poseStack.last().pose());
        itemglint$capturedNormal = new Matrix3f(poseStack.last().normal());
        itemglint$preparedFallbackCapture = true;
    }

    @Inject(method = "renderHandsWithItems", at = @At("RETURN"), require = 0)
    private void itemglint$runIrisFallbackCapture(float tickDelta, PoseStack poseStack,
                                                  MultiBufferSource.BufferSource bufferSource, LocalPlayer player,
                                                  int packedLight, CallbackInfo ci) {
        if (!itemglint$preparedFallbackCapture || itemglint$capturedPose == null || itemglint$capturedNormal == null) {
            return;
        }

        itemglint$preparedFallbackCapture = false;
        Minecraft minecraft = Minecraft.getInstance();
        Matrix4f originalPose = itemglint$capturedPose;
        Matrix3f originalNormal = itemglint$capturedNormal;
        itemglint$capturedPose = null;
        itemglint$capturedNormal = null;

        if (!HeldItemOutlineRenderer.shouldRenderOutlinePass(minecraft)) {
            return;
        }

        Matrix4f handProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        HeldItemOutlineRenderer.beginItemInHandRender(handProjection);
        try {
            java.util.List<HandEffectTarget> targets = HeldItemOutlineRenderer.getRenderableHands(player);
            MultiBufferSource.BufferSource captureBufferSource = bufferSource;
            for (int index = 0; index < targets.size(); index++) {
                HandEffectTarget target = targets.get(index);
                HandEffectTarget nextTarget = index + 1 < targets.size() ? targets.get(index + 1) : null;
                boolean batchHands = HeldItemOutlineRenderer.shouldBatchHands(target, nextTarget);

                if (!HeldItemOutlineRenderer.beginCapture(minecraft, minecraft.getMainRenderTarget(), target.hand(),
                        batchHands ? null : target.hand(), originalPose, target.profile(), target.sampledColors())) {
                    continue;
                }

                try {
                    poseStack.last().pose().set(originalPose);
                    poseStack.last().normal().set(originalNormal);
                    ((ItemInHandRenderer) (Object) this).renderHandsWithItems(tickDelta, poseStack, captureBufferSource, player, packedLight);
                    captureBufferSource.endBatch();
                } finally {
                    HeldItemOutlineRenderer.endCapture();
                }

                HeldItemOutlineRenderer.composite(minecraft, minecraft.getMainRenderTarget(), target.hand());
                if (batchHands) {
                    index++;
                }
            }
        } finally {
            HeldItemOutlineRenderer.endItemInHandRender();
        }
    }

    @Unique
    private static boolean itemglint$shouldUseIrisFallback(LocalPlayer player) {
        Minecraft minecraft = Minecraft.getInstance();
        return player != null
                && HeldItemOutlineCompat.isIrisLoaded()
                && !HeldItemOutlineCompat.isIrisShaderPackActive()
                && !HeldItemOutlineRenderer.isCaptureActive()
                && !HeldItemOutlineCompat.isIrisShadowPass()
                && minecraft.level != null;
    }
}
