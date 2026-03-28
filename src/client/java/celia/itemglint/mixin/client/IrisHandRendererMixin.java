package celia.itemglint.mixin.client;

import celia.itemglint.ItemGlint;
import celia.itemglint.client.HeldItemOutlineCompat;
import celia.itemglint.client.HeldItemOutlineRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public abstract class IrisHandRendererMixin {
    private static long itemglint$nextHandRendererLogMillis;

    @Inject(method = "renderSolid", at = @At("HEAD"), require = 0)
    private void itemglint$debugRenderSolidHead(CallbackInfo ci) {
        itemglint$logHandRendererStage("renderSolid-head");
    }

    @Inject(method = "renderTranslucent", at = @At("HEAD"), require = 0)
    private void itemglint$debugRenderTranslucentHead(CallbackInfo ci) {
        itemglint$logHandRendererStage("renderTranslucent-head");
    }

    @Redirect(method = "renderSolid",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
                    remap = false),
            require = 0)
    private void itemglint$renderHeldOutlineSolid(ItemInHandRenderer itemInHandRenderer, float tickDelta,
                                                  PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                                  LocalPlayer player, int packedLight) {
        itemglint$renderHeldOutline(itemInHandRenderer, tickDelta, poseStack, bufferSource, player, packedLight);
    }

    @Redirect(method = "renderTranslucent",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
                    remap = false),
            require = 0)
    private void itemglint$renderHeldOutlineTranslucent(ItemInHandRenderer itemInHandRenderer, float tickDelta,
                                                        PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                                        LocalPlayer player, int packedLight) {
        itemglint$renderHeldOutline(itemInHandRenderer, tickDelta, poseStack, bufferSource, player, packedLight);
    }

    private static void itemglint$renderHeldOutline(ItemInHandRenderer itemInHandRenderer, float tickDelta,
                                                    PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                                    LocalPlayer player, int packedLight) {
        Minecraft minecraft = Minecraft.getInstance();
        HeldItemOutlineRenderer.noteIrisRedirect(minecraft, player, "redirect-enter");
        if (!HeldItemOutlineCompat.shouldUseSodiumIrisPipeline(minecraft)) {
            HeldItemOutlineRenderer.noteIrisRedirect(minecraft, player, "redirect-fallback");
            itemInHandRenderer.renderHandsWithItems(tickDelta, poseStack, bufferSource, player, packedLight);
            return;
        }

        MultiBufferSource.BufferSource wrappedBufferSource = HeldItemOutlineRenderer.wrapIrisHandBufferSource(
                minecraft, minecraft.getMainRenderTarget(), bufferSource, player
        );
        HeldItemOutlineRenderer.beginItemInHandRender(new Matrix4f(RenderSystem.getProjectionMatrix()));
        try {
            itemInHandRenderer.renderHandsWithItems(tickDelta, poseStack, wrappedBufferSource, player, packedLight);
            HeldItemOutlineRenderer.flushIrisCompatCaptureBuffers(minecraft.getMainRenderTarget());
            HeldItemOutlineRenderer.noteIrisRedirect(minecraft, player, "redirect-post-render");
        } finally {
            HeldItemOutlineRenderer.endItemInHandRender();
        }
    }

    private static void itemglint$logHandRendererStage(String stage) {
        long now = System.currentTimeMillis();
        if (now < itemglint$nextHandRendererLogMillis) {
            return;
        }

        itemglint$nextHandRendererLogMillis = now + 2000L;
        Minecraft minecraft = Minecraft.getInstance();
        @Nullable LocalPlayer player = minecraft.player;
        ItemGlint.LOGGER.info(
                "[HeldItemOutline][IrisDebug] handRendererStage={} shaderPack={} shadowPass={} player={} level={} shouldUsePipeline={}",
                stage,
                HeldItemOutlineCompat.isIrisShaderPackActive(),
                HeldItemOutlineCompat.isIrisShadowPass(),
                player != null,
                minecraft.level != null,
                HeldItemOutlineCompat.shouldUseSodiumIrisPipeline(minecraft)
        );
    }
}
