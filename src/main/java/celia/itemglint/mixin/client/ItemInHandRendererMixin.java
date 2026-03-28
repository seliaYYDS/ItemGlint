package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemInHandRenderer.class, priority = 900)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void itemglint$skipDisabledItemHand(AbstractClientPlayer player, float partialTick, float pitch,
                                                 InteractionHand hand, float swingProgress, ItemStack stack,
                                                 float equipProgress, PoseStack poseStack, MultiBufferSource bufferSource,
                                                 int packedLight, CallbackInfo ci) {
        if (HeldItemOutlineRenderer.isCaptureActive()
                && (stack.isEmpty() || HeldItemOutlineRenderer.shouldSkipHand(hand))) {
            ci.cancel();
            return;
        }
    }

    @Inject(method = "renderArmWithItem", at = @At("RETURN"), require = 0)
    private void itemglint$finishArmCapture(AbstractClientPlayer player, float partialTick, float pitch,
                                             InteractionHand hand, float swingProgress, ItemStack stack,
                                             float equipProgress, PoseStack poseStack, MultiBufferSource bufferSource,
                                             int packedLight, CallbackInfo ci) {
        if (HeldItemOutlineRenderer.isCaptureActive() && !stack.isEmpty() && !HeldItemOutlineRenderer.shouldSkipHand(hand)) {
            HeldItemOutlineRenderer.markHandCaptured();
        }
    }

    @Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true, require = 0)
    private void itemglint$skipPlayerArmForOutlineCapture(PoseStack poseStack, MultiBufferSource bufferSource,
                                                           int packedLight, float equipProgress, float swingProgress,
                                                           HumanoidArm humanoidArm, CallbackInfo ci) {
        if (HeldItemOutlineRenderer.isCaptureActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderMapHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void itemglint$skipMapHandForOutlineCapture(PoseStack poseStack, MultiBufferSource bufferSource,
                                                         int packedLight, HumanoidArm humanoidArm, CallbackInfo ci) {
        if (HeldItemOutlineRenderer.isCaptureActive()) {
            ci.cancel();
        }
    }
}
