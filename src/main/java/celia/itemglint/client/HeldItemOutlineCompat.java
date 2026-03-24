package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

public final class HeldItemOutlineCompat {
    private static final String IRIS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    private static final boolean EMBEDDIUM_LOADED = ModList.get().isLoaded("embeddium");
    private static final boolean OCULUS_LOADED = ModList.get().isLoaded("oculus");
    private static final Method IRIS_GET_INSTANCE_METHOD;
    private static final Method IRIS_SHADER_PACK_IN_USE_METHOD;
    private static final Method IRIS_SHADOW_PASS_METHOD;

    static {
        Method getInstance = null;
        Method shaderPackInUse = null;
        Method shadowPass = null;

        if (OCULUS_LOADED) {
            try {
                Class<?> irisApiClass = Class.forName(IRIS_API_CLASS);
                getInstance = irisApiClass.getMethod("getInstance");
                shaderPackInUse = irisApiClass.getMethod("isShaderPackInUse");
                shadowPass = irisApiClass.getMethod("isRenderingShadowPass");
            } catch (ReflectiveOperationException exception) {
                ItemGlint.LOGGER.warn("[HeldItemOutline] Failed to resolve Oculus/Iris API hooks, falling back to vanilla hand capture.", exception);
            }
        }

        IRIS_GET_INSTANCE_METHOD = getInstance;
        IRIS_SHADER_PACK_IN_USE_METHOD = shaderPackInUse;
        IRIS_SHADOW_PASS_METHOD = shadowPass;
    }

    private HeldItemOutlineCompat() {
    }

    public static boolean isEmbeddiumLoaded() {
        return EMBEDDIUM_LOADED;
    }

    public static boolean isOculusLoaded() {
        return OCULUS_LOADED;
    }

    public static boolean shouldUseEmbeddiumOculusPipeline(Minecraft minecraft) {
        return EMBEDDIUM_LOADED
                && minecraft.level != null
                && isOculusShaderPackActive()
                && !isOculusShadowPass();
    }

    public static boolean isOculusShaderPackActive() {
        return invokeIrisBoolean(IRIS_SHADER_PACK_IN_USE_METHOD);
    }

    public static boolean isOculusShadowPass() {
        return invokeIrisBoolean(IRIS_SHADOW_PASS_METHOD);
    }

    private static boolean invokeIrisBoolean(Method method) {
        if (!OCULUS_LOADED || IRIS_GET_INSTANCE_METHOD == null || method == null) {
            return false;
        }

        try {
            Object irisApi = IRIS_GET_INSTANCE_METHOD.invoke(null);
            return Boolean.TRUE.equals(method.invoke(irisApi));
        } catch (ReflectiveOperationException exception) {
            ItemGlint.LOGGER.debug("[HeldItemOutline] Failed to invoke Oculus/Iris compatibility hook {}", method.getName(), exception);
            return false;
        }
    }
}
