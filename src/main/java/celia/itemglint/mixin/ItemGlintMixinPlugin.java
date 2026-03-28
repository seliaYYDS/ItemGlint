package celia.itemglint.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class ItemGlintMixinPlugin implements IMixinConfigPlugin {
    private static final String EMBEDDIUM_CLASS = "org.embeddedt.embeddium.api.EmbeddiumConstants";
    private static final String OCULUS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String OCULUS_HAND_RENDERER_CLASS = "net.irisshaders.iris.pathways.HandRenderer";
    private static final boolean DEBUG = Boolean.getBoolean("itemglint.debugCompat");

    @Override
    public void onLoad(String mixinPackage) {
        if (DEBUG) {
            System.err.println("[HeldItemOutlineCompat] Mixin plugin loaded: package=" + mixinPackage);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("GameRendererMixin")) {
            boolean apply = !classExists(OCULUS_API_CLASS);
            debugMixinDecision(mixinClassName, targetClassName, apply);
            return apply;
        }

        if (mixinClassName.endsWith("GameRendererOculusMixin")) {
            boolean apply = classExists(OCULUS_API_CLASS);
            debugMixinDecision(mixinClassName, targetClassName, apply);
            return apply;
        }

        if (mixinClassName.endsWith("LevelRendererEmbeddiumMixin")) {
            boolean apply = classExists(EMBEDDIUM_CLASS);
            debugMixinDecision(mixinClassName, targetClassName, apply);
            return apply;
        }

        if (mixinClassName.endsWith("OculusHandRendererMixin")) {
            debugMixinDecision(mixinClassName, targetClassName, false);
            return false;
        }

        debugMixinDecision(mixinClassName, targetClassName, true);
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

    private static void debugMixinDecision(String mixinClassName, String targetClassName, boolean apply) {
        if (DEBUG) {
            System.err.println("[HeldItemOutlineCompat] Mixin decision: " + mixinClassName
                    + " -> " + targetClassName + " apply=" + apply);
        }
    }
}
