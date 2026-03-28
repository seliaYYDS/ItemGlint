package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineCompat;
import celia.itemglint.client.HeldItemOutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = GameRenderer.class, priority = 900)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Redirect(method = "renderItemInHand",
            require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    private void itemglint$skipVanillaHandRender(ItemInHandRenderer itemInHandRenderer, float partialTick,
                                                  PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                                  LocalPlayer player, int packedLight) {
        if (HeldItemOutlineCompat.shouldUseEmbeddiumOculusPipeline(minecraft)) {
            HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererMixin skipped vanilla redirect because Embeddium+Oculus pipeline is active");
            return;
        }

        boolean embeddiumLoaded = HeldItemOutlineCompat.isEmbeddiumLoaded();
        MultiBufferSource.BufferSource captureBufferSource = embeddiumLoaded
                ? HeldItemOutlineRenderer.getEmbeddiumCaptureBufferSource()
                : bufferSource;
        HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererMixin renderItemInHand redirect: embeddiumLoaded="
                + embeddiumLoaded + ", captureBufferSourceSame=" + (captureBufferSource == bufferSource));
        Matrix4f originalPose = new Matrix4f(poseStack.last().pose());
        Matrix3f originalNormal = new Matrix3f(poseStack.last().normal());
        itemInHandRenderer.renderHandsWithItems(partialTick, poseStack, bufferSource, player, packedLight);
        if (embeddiumLoaded) {
            bufferSource.endBatch();
            HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererMixin ended primary buffer batch for Embeddium");
        }

        if (!HeldItemOutlineRenderer.shouldRenderOutlinePass(minecraft)) {
            HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererMixin outline pass skipped by shouldRenderOutlinePass");
            return;
        }

        GameRendererAccessor accessor = (GameRendererAccessor) (Object) this;
        Matrix4f handProjection = ((GameRenderer) (Object) this)
                .getProjectionMatrix(accessor.invokeGetFov(minecraft.gameRenderer.getMainCamera(), partialTick, false));
        HeldItemOutlineRenderer.beginItemInHandRender(handProjection);
        try {
            java.util.List<HeldItemOutlineRenderer.HandEffectTarget> targets = HeldItemOutlineRenderer.getRenderableHands(player);
            for (int index = 0; index < targets.size(); index++) {
                HeldItemOutlineRenderer.HandEffectTarget target = targets.get(index);
                HeldItemOutlineRenderer.HandEffectTarget nextTarget = index + 1 < targets.size() ? targets.get(index + 1) : null;
                boolean batchHands = HeldItemOutlineRenderer.shouldBatchHands(target, nextTarget);
                InteractionHand hand = target.hand();
                if (!HeldItemOutlineRenderer.beginCapture(minecraft, minecraft.getMainRenderTarget(), hand,
                        batchHands ? null : hand, originalPose, target.profile(), target.sampledColors())) {
                    HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererMixin beginCapture returned false for " + hand);
                    continue;
                }

                try {
                    poseStack.last().pose().set(originalPose);
                    poseStack.last().normal().set(originalNormal);
                    itemInHandRenderer.renderHandsWithItems(partialTick, poseStack, captureBufferSource, player, packedLight);
                    captureBufferSource.endBatch();
                    HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererMixin finished capture buffer batch for " + hand);
                } finally {
                    HeldItemOutlineRenderer.endCapture();
                }
                HeldItemOutlineRenderer.composite(minecraft, minecraft.getMainRenderTarget(), hand);
                if (batchHands) {
                    index++;
                }
            }
        } finally {
            HeldItemOutlineRenderer.endItemInHandRender();
        }
    }
}
