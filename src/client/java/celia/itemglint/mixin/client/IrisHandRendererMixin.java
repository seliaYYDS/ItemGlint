package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineCompat;
import celia.itemglint.client.HeldItemOutlineRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer")
public abstract class IrisHandRendererMixin {
    @ModifyArg(method = "renderSolid",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;iris$renderHandsWithCustomRenderer(Lnet/irisshaders/iris/pathways/HandRenderer;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeStorage;Lnet/minecraft/client/player/LocalPlayer;I)V",
                    remap = false),
            index = 3,
            remap = false)
    private SubmitNodeStorage itemglint$wrapSolidSubmitStorage(SubmitNodeStorage submitNodeStorage) {
        return itemglint$wrapSubmitStorage(submitNodeStorage);
    }

    @ModifyArg(method = "renderTranslucent",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;iris$renderHandsWithCustomRenderer(Lnet/irisshaders/iris/pathways/HandRenderer;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeStorage;Lnet/minecraft/client/player/LocalPlayer;I)V",
                    remap = false),
            index = 3,
            remap = false)
    private SubmitNodeStorage itemglint$wrapTranslucentSubmitStorage(SubmitNodeStorage submitNodeStorage) {
        return itemglint$wrapSubmitStorage(submitNodeStorage);
    }

    private static SubmitNodeStorage itemglint$wrapSubmitStorage(SubmitNodeStorage submitNodeStorage) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !HeldItemOutlineCompat.shouldUseSodiumIrisPipeline(minecraft)) {
            return submitNodeStorage;
        }

        return HeldItemOutlineRenderer.wrapSubmitStorage(minecraft, submitNodeStorage, player);
    }
}
