package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineCompat;
import celia.itemglint.client.HeldItemOutlineRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemInHandRenderer.class, priority = 900)
public abstract class ItemInHandRendererMixin {
    @Unique
    private boolean itemglint$activeIrisWrap;

    @ModifyVariable(method = "renderHandsWithItems", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private MultiBufferSource.BufferSource itemglint$wrapIrisBufferSource(MultiBufferSource.BufferSource bufferSource) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null
                || !HeldItemOutlineCompat.shouldUseSodiumIrisPipeline(minecraft)
                || HeldItemOutlineRenderer.isCaptureActive()) {
            itemglint$activeIrisWrap = false;
            return bufferSource;
        }

        itemglint$activeIrisWrap = true;
        HeldItemOutlineRenderer.noteIrisRedirect(minecraft, player, "renderHandsWithItems-head");
        HeldItemOutlineRenderer.beginItemInHandRender(new Matrix4f(RenderSystem.getProjectionMatrix()));
        return HeldItemOutlineRenderer.wrapIrisHandBufferSource(minecraft, minecraft.getMainRenderTarget(), bufferSource, player);
    }

    @Inject(method = "renderHandsWithItems", at = @At("RETURN"), require = 0)
    private void itemglint$flushIrisWrappedBuffers(float tickDelta, PoseStack poseStack,
                                                   MultiBufferSource.BufferSource bufferSource, LocalPlayer player,
                                                   int packedLight, CallbackInfo ci) {
        if (!itemglint$activeIrisWrap) {
            return;
        }

        itemglint$activeIrisWrap = false;
        Minecraft minecraft = Minecraft.getInstance();
        HeldItemOutlineRenderer.noteIrisRedirect(minecraft, player, "renderHandsWithItems-return");
        HeldItemOutlineRenderer.endItemInHandRender();
    }

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

        HeldItemOutlineRenderer.beginHandSubmit(hand, stack);
    }

    @Inject(method = "renderArmWithItem", at = @At("RETURN"), require = 0)
    private void itemglint$beginHandSubmitTracking(AbstractClientPlayer player, float partialTick, float pitch,
                                                   InteractionHand hand, float swingProgress, ItemStack stack,
                                                   float equipProgress, PoseStack poseStack, MultiBufferSource bufferSource,
                                                   int packedLight, CallbackInfo ci) {
        if (HeldItemOutlineRenderer.isCaptureActive() && !stack.isEmpty() && !HeldItemOutlineRenderer.shouldSkipHand(hand)) {
            HeldItemOutlineRenderer.markHandCaptured();
        }
        HeldItemOutlineRenderer.endHandSubmit();
    }

    @Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true, require = 0)
    private void itemglint$suppressArmDuplicationStart(PoseStack poseStack, MultiBufferSource bufferSource,
                                                       int packedLight, float equipProgress, float swingProgress,
                                                       HumanoidArm humanoidArm, CallbackInfo ci) {
        if (HeldItemOutlineRenderer.isCaptureActive()) {
            ci.cancel();
            return;
        }

        HeldItemOutlineRenderer.beginDuplicationSuppress();
    }

    @Inject(method = "renderPlayerArm", at = @At("RETURN"), require = 0)
    private void itemglint$suppressArmDuplicationEnd(PoseStack poseStack, MultiBufferSource bufferSource,
                                                     int packedLight, float equipProgress, float swingProgress,
                                                     HumanoidArm humanoidArm, CallbackInfo ci) {
        HeldItemOutlineRenderer.endDuplicationSuppress();
    }

    @Inject(method = "renderMapHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void itemglint$suppressMapHandDuplicationStart(PoseStack poseStack, MultiBufferSource bufferSource,
                                                           int packedLight, HumanoidArm humanoidArm, CallbackInfo ci) {
        if (HeldItemOutlineRenderer.isCaptureActive()) {
            ci.cancel();
            return;
        }

        HeldItemOutlineRenderer.beginDuplicationSuppress();
    }

    @Inject(method = "renderMapHand", at = @At("RETURN"), require = 0)
    private void itemglint$suppressMapHandDuplicationEnd(PoseStack poseStack, MultiBufferSource bufferSource,
                                                         int packedLight, HumanoidArm humanoidArm, CallbackInfo ci) {
        HeldItemOutlineRenderer.endDuplicationSuppress();
    }
}
