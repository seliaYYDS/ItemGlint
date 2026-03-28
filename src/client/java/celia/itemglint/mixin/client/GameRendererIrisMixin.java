package celia.itemglint.mixin.client;

import celia.itemglint.client.HeldItemOutlineCompat;
import celia.itemglint.client.HeldItemOutlineRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, priority = 900)
public abstract class GameRendererIrisMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER))
    private void itemglint$compositeHeldOutlineAfterIrisLevel(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        if (!HeldItemOutlineCompat.shouldUseSodiumIrisPipeline(this.minecraft) || !HeldItemOutlineRenderer.hasPendingComposites()) {
            return;
        }

        HeldItemOutlineRenderer.renderPendingComposites(this.minecraft, this.minecraft.getMainRenderTarget());
    }
}
