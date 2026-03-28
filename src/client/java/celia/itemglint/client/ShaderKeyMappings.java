package celia.itemglint.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class ShaderKeyMappings {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("itemglint", "itemglint"));
    public static final KeyMapping OPEN_QUICK_CONFIG = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.itemglint.open_quick_config",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            CATEGORY
    ));

    private static boolean initialized;

    private ShaderKeyMappings() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) {
                return;
            }
            while (OPEN_QUICK_CONFIG.consumeClick()) {
                if (!(client.screen instanceof ShaderQuickConfigScreen)) {
                    client.setScreen(new ShaderQuickConfigScreen(client.screen));
                }
            }
        });
    }
}
