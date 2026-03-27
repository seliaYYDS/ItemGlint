package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineCompat;
import celia.itemglint.client.HeldItemOutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.InteractionHand;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, priority = 900)
public abstract class GameRendererOculusMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private ItemInHandRenderer itemInHandRenderer;

    @Shadow
    @Final
    private RenderBuffers renderBuffers;

    @Shadow
    @Final
    private LightTexture lightTexture;

    @Unique
    private Matrix4f itemglint$oculusPose;

    @Unique
    private Matrix3f itemglint$oculusNormal;

    @Unique
    private boolean itemglint$oculusPrepared;

    @Inject(method = "renderItemInHand",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LightTexture;turnOnLightLayer()V",
                    shift = At.Shift.BEFORE),
            require = 0)
    private void itemglint$prepareOculusHandCapture(PoseStack poseStack, net.minecraft.client.Camera camera, float partialTick,
                                                    CallbackInfo ci) {
        itemglint$oculusPrepared = false;
        itemglint$oculusPose = null;
        itemglint$oculusNormal = null;

        if (!HeldItemOutlineCompat.isOculusLoaded()) {
            return;
        }

        if (HeldItemOutlineCompat.isOculusShaderPackActive()) {
            HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererOculusMixin prepare skipped because Oculus shader pack is active");
            return;
        }

        itemglint$oculusPose = new Matrix4f(poseStack.last().pose());
        itemglint$oculusNormal = new Matrix3f(poseStack.last().normal());
        itemglint$oculusPrepared = true;
        HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererOculusMixin prepared alternate Oculus branch");
    }

    @Inject(method = "renderItemInHand",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LightTexture;turnOffLightLayer()V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void itemglint$runOculusHandCapture(PoseStack poseStack, net.minecraft.client.Camera camera, float partialTick,
                                                CallbackInfo ci) {
        if (!itemglint$oculusPrepared) {
            return;
        }

        itemglint$oculusPrepared = false;

        if (!HeldItemOutlineCompat.isOculusLoaded()) {
            return;
        }

        if (HeldItemOutlineCompat.isOculusShaderPackActive()) {
            HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererOculusMixin alternate branch skipped because Oculus shader pack became active");
            return;
        }

        LocalPlayer player = minecraft.player;
        if (player == null) {
            HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererOculusMixin alternate branch skipped: player=null");
            return;
        }

        MultiBufferSource.BufferSource primaryBufferSource = renderBuffers.bufferSource();
        primaryBufferSource.endBatch();
        HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererOculusMixin ended primary buffer batch");

        if (!HeldItemOutlineRenderer.shouldRenderOutlinePass(minecraft)) {
            HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererOculusMixin outline pass skipped by shouldRenderOutlinePass");
            return;
        }

        MultiBufferSource.BufferSource captureBufferSource = HeldItemOutlineCompat.isEmbeddiumLoaded()
                ? HeldItemOutlineRenderer.getEmbeddiumCaptureBufferSource()
                : primaryBufferSource;
        EntityRenderDispatcher entityRenderDispatcher = minecraft.getEntityRenderDispatcher();
        int packedLight = entityRenderDispatcher.getPackedLightCoords(player, partialTick);

        for (HeldItemOutlineRenderer.HandEffectTarget target : HeldItemOutlineRenderer.getRenderableHands(player)) {
            InteractionHand hand = target.hand();
            if (!HeldItemOutlineRenderer.beginCapture(minecraft, minecraft.getMainRenderTarget(), hand)) {
                HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererOculusMixin beginCapture returned false for " + hand);
                continue;
            }

            lightTexture.turnOnLightLayer();
            try {
                if (itemglint$oculusPose != null) {
                    poseStack.last().pose().set(itemglint$oculusPose);
                }
                if (itemglint$oculusNormal != null) {
                    poseStack.last().normal().set(itemglint$oculusNormal);
                }
                itemInHandRenderer.renderHandsWithItems(partialTick, poseStack, captureBufferSource, player, packedLight);
                captureBufferSource.endBatch();
                HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererOculusMixin finished capture buffer batch for " + hand);
            } finally {
                HeldItemOutlineRenderer.endCapture();
                lightTexture.turnOffLightLayer();
            }

            HeldItemOutlineRenderer.composite(minecraft, minecraft.getMainRenderTarget(), hand, target.profile(), target.sampledColors());
        }
        itemglint$oculusPose = null;
        itemglint$oculusNormal = null;
    }

    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void itemglint$finishEmbeddiumOculusShaderpackOutline(float partialTick, long nanoTime, PoseStack poseStack,
                                                                  CallbackInfo ci) {
        if (!HeldItemOutlineCompat.shouldUseEmbeddiumOculusPipeline(minecraft)) {
            return;
        }

        HeldItemOutlineRenderer.debugCompat(minecraft, "GameRendererOculusMixin finishing Embeddium+Oculus compat frame"
                + " queued=" + HeldItemOutlineRenderer.isEmbeddiumCompatFrameQueued()
                + " captured=" + HeldItemOutlineRenderer.hasCapturedThisFrame()
                + " count=" + HeldItemOutlineRenderer.getCapturedHandCount());
        HeldItemOutlineRenderer.finishEmbeddiumCompatFrame(minecraft, minecraft.getMainRenderTarget());
    }
}
