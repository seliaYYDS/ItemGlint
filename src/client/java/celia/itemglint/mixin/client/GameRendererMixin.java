package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private Minecraft minecraft;

    @Redirect(method = "renderItemInHand",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    private void itemglint$renderHeldOutline(ItemInHandRenderer itemInHandRenderer, float tickDelta,
                                             PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                             LocalPlayer player, int packedLight) {
        SubmitNodeCollector collector = HeldItemOutlineRenderer.wrapSubmitCollector(this.minecraft, submitNodeCollector, player);
        itemInHandRenderer.renderHandsWithItems(tickDelta, poseStack, collector, player, packedLight);
    }

}
