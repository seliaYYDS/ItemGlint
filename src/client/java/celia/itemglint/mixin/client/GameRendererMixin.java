package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineRenderer;
import celia.itemglint.client.HeldItemOutlineRenderer.HandEffectTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = GameRenderer.class, priority = 900)
public abstract class GameRendererMixin {
    @Shadow @Final private Minecraft minecraft;

    @Redirect(method = "renderItemInHand",
            require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    private void itemglint$renderHeldOutline(ItemInHandRenderer itemInHandRenderer, float tickDelta,
                                             PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                             LocalPlayer player, int packedLight) {
        Matrix4f originalPose = new Matrix4f(poseStack.last().pose());
        Matrix3f originalNormal = new Matrix3f(poseStack.last().normal());
        itemInHandRenderer.renderHandsWithItems(tickDelta, poseStack, bufferSource, player, packedLight);

        if (!HeldItemOutlineRenderer.shouldRenderOutlinePass(this.minecraft)) {
            return;
        }

        GameRendererAccessor accessor = (GameRendererAccessor) (Object) this;
        Matrix4f handProjection = ((GameRenderer) (Object) this)
                .getProjectionMatrix(accessor.itemglint$invokeGetFov(this.minecraft.gameRenderer.getMainCamera(), tickDelta, false));
        HeldItemOutlineRenderer.beginItemInHandRender(handProjection);
        try {
            java.util.List<HandEffectTarget> targets = HeldItemOutlineRenderer.getRenderableHands(player);
            for (int index = 0; index < targets.size(); index++) {
                HandEffectTarget target = targets.get(index);
                HandEffectTarget nextTarget = index + 1 < targets.size() ? targets.get(index + 1) : null;
                boolean batchHands = HeldItemOutlineRenderer.shouldBatchHands(target, nextTarget);

                if (!HeldItemOutlineRenderer.beginCapture(this.minecraft, this.minecraft.getMainRenderTarget(), target.hand(),
                        batchHands ? null : target.hand(), originalPose, target.profile(), target.sampledColors())) {
                    continue;
                }

                try {
                    poseStack.last().pose().set(originalPose);
                    poseStack.last().normal().set(originalNormal);
                    itemInHandRenderer.renderHandsWithItems(tickDelta, poseStack, bufferSource, player, packedLight);
                    bufferSource.endBatch();
                } finally {
                    HeldItemOutlineRenderer.endCapture();
                }

                HeldItemOutlineRenderer.composite(this.minecraft, this.minecraft.getMainRenderTarget(), target.hand());
                if (batchHands) {
                    index++;
                }
            }
        } finally {
            HeldItemOutlineRenderer.endItemInHandRender();
        }
    }
}
