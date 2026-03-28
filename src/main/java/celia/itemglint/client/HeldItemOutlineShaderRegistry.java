package celia.itemglint.client;

import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;

public final class HeldItemOutlineShaderRegistry {
    @Nullable
    private static ShaderInstance shader;

    private HeldItemOutlineShaderRegistry() {
    }

    public static void setShader(@Nullable ShaderInstance shaderInstance) {
        shader = shaderInstance;
    }

    @Nullable
    public static ShaderInstance getShader() {
        return ShaderFeatureManager.isEnabled(ShaderFeature.HELD_ITEM_OUTLINE) ? shader : null;
    }

    @Nullable
    public static ShaderInstance getLoadedShader() {
        return shader;
    }
}
