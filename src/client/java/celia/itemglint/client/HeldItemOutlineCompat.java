package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;

public final class HeldItemOutlineCompat {
    private static final String IRIS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    private static final boolean SODIUM_LOADED = FabricLoader.getInstance().isModLoaded("sodium");
    private static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");
    private static final Method IRIS_GET_INSTANCE_METHOD;
    private static final Method IRIS_SHADER_PACK_IN_USE_METHOD;
    private static final Method IRIS_SHADOW_PASS_METHOD;

    static {
        Method getInstance = null;
        Method shaderPackInUse = null;
        Method shadowPass = null;

        if (IRIS_LOADED) {
            try {
                Class<?> irisApiClass = Class.forName(IRIS_API_CLASS);
                getInstance = irisApiClass.getMethod("getInstance");
                shaderPackInUse = irisApiClass.getMethod("isShaderPackInUse");
                shadowPass = irisApiClass.getMethod("isRenderingShadowPass");
            } catch (ReflectiveOperationException exception) {
                ItemGlint.LOGGER.warn("[HeldItemOutline] Failed to resolve Iris API hooks, falling back to vanilla hand pipeline.", exception);
            }
        }

        IRIS_GET_INSTANCE_METHOD = getInstance;
        IRIS_SHADER_PACK_IN_USE_METHOD = shaderPackInUse;
        IRIS_SHADOW_PASS_METHOD = shadowPass;
    }

    private HeldItemOutlineCompat() {
    }

    public static boolean isSodiumLoaded() {
        return SODIUM_LOADED;
    }

    public static boolean isIrisLoaded() {
        return IRIS_LOADED;
    }

    public static boolean isEmbeddiumLoaded() {
        return isSodiumLoaded();
    }

    public static boolean isOculusLoaded() {
        return isIrisLoaded();
    }

    public static boolean isIrisShaderPackActive() {
        return invokeIrisBoolean(IRIS_SHADER_PACK_IN_USE_METHOD);
    }

    public static boolean isIrisShadowPass() {
        return invokeIrisBoolean(IRIS_SHADOW_PASS_METHOD);
    }

    public static boolean shouldUseSodiumIrisPipeline(Minecraft minecraft) {
        return minecraft.level != null
                && IRIS_LOADED
                && isIrisShaderPackActive()
                && !isIrisShadowPass();
    }

    public static boolean isOculusShaderPackActive() {
        return isIrisShaderPackActive();
    }

    public static boolean isOculusShadowPass() {
        return isIrisShadowPass();
    }

    public static boolean shouldUseEmbeddiumOculusPipeline(Minecraft minecraft) {
        return shouldUseSodiumIrisPipeline(minecraft);
    }

    private static boolean invokeIrisBoolean(Method method) {
        if (!IRIS_LOADED || IRIS_GET_INSTANCE_METHOD == null || method == null) {
            return false;
        }

        try {
            Object irisApi = IRIS_GET_INSTANCE_METHOD.invoke(null);
            return Boolean.TRUE.equals(method.invoke(irisApi));
        } catch (ReflectiveOperationException exception) {
            ItemGlint.LOGGER.debug("[HeldItemOutline] Failed to invoke Iris compatibility hook {}", method.getName(), exception);
            return false;
        }
    }
}
