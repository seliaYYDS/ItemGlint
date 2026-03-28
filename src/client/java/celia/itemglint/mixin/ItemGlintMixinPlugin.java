package celia.itemglint.mixin;

import celia.itemglint.ItemGlint;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class ItemGlintMixinPlugin implements IMixinConfigPlugin {
    private static final String IRIS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String IRIS_HAND_RENDERER_CLASS = "net.irisshaders.iris.pathways.HandRenderer";
    private static boolean loggedGameRendererIrisDecision;
    private static boolean loggedIrisHandRendererDecision;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("GameRendererMixin")) {
            return !classExists(IRIS_API_CLASS);
        }

        if (mixinClassName.endsWith("GameRendererIrisMixin") || mixinClassName.endsWith("IrisHandRendererMixin")) {
            boolean shouldApply = classExists(IRIS_HAND_RENDERER_CLASS);
            if (mixinClassName.endsWith("GameRendererIrisMixin") && !loggedGameRendererIrisDecision) {
                loggedGameRendererIrisDecision = true;
                ItemGlint.LOGGER.info("[HeldItemOutline][IrisDebug] mixinDecision mixin={} target={} shouldApply={}",
                        mixinClassName, targetClassName, shouldApply);
            }
            if (mixinClassName.endsWith("IrisHandRendererMixin") && !loggedIrisHandRendererDecision) {
                loggedIrisHandRendererDecision = true;
                ItemGlint.LOGGER.info("[HeldItemOutline][IrisDebug] mixinDecision mixin={} target={} shouldApply={}",
                        mixinClassName, targetClassName, shouldApply);
            }
            return shouldApply;
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, ItemGlintMixinPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
