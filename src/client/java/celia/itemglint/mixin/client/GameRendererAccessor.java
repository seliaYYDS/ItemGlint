package celia.itemglint.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("renderHand")
    boolean itemglint$getRenderHand();

    @Accessor("panoramicMode")
    boolean itemglint$getPanoramicMode();

    @Invoker("bobView")
    void itemglint$invokeBobView(PoseStack poseStack, float tickDelta);

    @Invoker("bobHurt")
    void itemglint$invokeBobHurt(PoseStack poseStack, float tickDelta);

    @Invoker("getFov")
    double itemglint$invokeGetFov(Camera camera, float tickDelta, boolean useConfiguredFov);
}
