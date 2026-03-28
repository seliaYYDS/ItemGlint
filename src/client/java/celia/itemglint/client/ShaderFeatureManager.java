package celia.itemglint.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class ShaderFeatureManager {
    private static boolean heldItemOutlineEnabled = true;

    private ShaderFeatureManager() {
    }

    public static boolean isEnabled(ShaderFeature shader) {
        return shader == ShaderFeature.HELD_ITEM_OUTLINE && heldItemOutlineEnabled;
    }

    public static boolean setEnabled(ShaderFeature shader, boolean enabled) {
        if (shader != ShaderFeature.HELD_ITEM_OUTLINE) {
            return false;
        }
        boolean changed = heldItemOutlineEnabled != enabled;
        heldItemOutlineEnabled = enabled;
        return changed;
    }

    public static Component describe(ShaderFeature shader) {
        MutableComponent statusComponent = Component.literal(isEnabled(shader) ? "enabled" : "disabled")
                .withStyle(isEnabled(shader) ? ChatFormatting.GREEN : ChatFormatting.RED);
        MutableComponent loadedComponent = Component.literal(shader.isLoaded() ? "loaded" : "not loaded")
                .withStyle(shader.isLoaded() ? ChatFormatting.AQUA : ChatFormatting.YELLOW);
        return Component.literal(shader.getCommandName())
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" -> "))
                .append(statusComponent)
                .append(Component.literal(", "))
                .append(loadedComponent)
                .append(Component.literal(", id=" + shader.getShaderId()).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                .append(HeldItemOutlineSettings.describe());
    }
}
