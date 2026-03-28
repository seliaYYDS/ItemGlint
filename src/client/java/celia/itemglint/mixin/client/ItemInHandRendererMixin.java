package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @ModifyVariable(method = "renderHandsWithItems", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private SubmitNodeCollector itemglint$wrapHandSubmitCollector(SubmitNodeCollector submitNodeCollector) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return submitNodeCollector;
        }

        if (submitNodeCollector instanceof SubmitNodeStorage submitNodeStorage) {
            return HeldItemOutlineRenderer.wrapSubmitStorage(minecraft, submitNodeStorage, minecraft.player);
        }

        return HeldItemOutlineRenderer.wrapSubmitCollector(minecraft, submitNodeCollector, minecraft.player);
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void itemglint$beginHandSubmitTracking(AbstractClientPlayer player, float partialTick, float pitch,
                                                   InteractionHand hand, float swingProgress, ItemStack stack,
                                                   float equipProgress, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                                   int packedLight, CallbackInfo ci) {
        HeldItemOutlineRenderer.beginHandSubmit(hand, stack);
    }

    @Inject(method = "renderArmWithItem", at = @At("RETURN"))
    private void itemglint$endHandSubmitTracking(AbstractClientPlayer player, float partialTick, float pitch,
                                                 InteractionHand hand, float swingProgress, ItemStack stack,
                                                 float equipProgress, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                                 int packedLight, CallbackInfo ci) {
        HeldItemOutlineRenderer.endHandSubmit();
    }

    @Inject(method = "renderPlayerArm", at = @At("HEAD"))
    private void itemglint$suppressArmDuplicationStart(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                                       int packedLight, float equipProgress, float swingProgress,
                                                       HumanoidArm humanoidArm, CallbackInfo ci) {
        HeldItemOutlineRenderer.beginDuplicationSuppress();
    }

    @Inject(method = "renderPlayerArm", at = @At("RETURN"))
    private void itemglint$suppressArmDuplicationEnd(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                                     int packedLight, float equipProgress, float swingProgress,
                                                     HumanoidArm humanoidArm, CallbackInfo ci) {
        HeldItemOutlineRenderer.endDuplicationSuppress();
    }

    @Inject(method = "renderMapHand", at = @At("HEAD"))
    private void itemglint$suppressMapHandDuplicationStart(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                                           int packedLight, HumanoidArm humanoidArm, CallbackInfo ci) {
        HeldItemOutlineRenderer.beginDuplicationSuppress();
    }

    @Inject(method = "renderMapHand", at = @At("RETURN"))
    private void itemglint$suppressMapHandDuplicationEnd(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                                         int packedLight, HumanoidArm humanoidArm, CallbackInfo ci) {
        HeldItemOutlineRenderer.endDuplicationSuppress();
    }
}
