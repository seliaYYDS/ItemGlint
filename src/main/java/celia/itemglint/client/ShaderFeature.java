package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

public enum ShaderFeature {
    HELD_ITEM_OUTLINE("held_item_outline", "Held Item Outline", shaderId("held_item_outline"), HeldItemOutlineShaderRegistry::getLoadedShader);

    private static final Map<String, ShaderFeature> BY_COMMAND_NAME = Map.of(normalize(HELD_ITEM_OUTLINE.commandName), HELD_ITEM_OUTLINE);

    private final String commandName;
    private final String displayName;
    private final ResourceLocation shaderId;
    private final Supplier<ShaderInstance> loadedShaderSupplier;

    ShaderFeature(String commandName, String displayName, ResourceLocation shaderId, Supplier<ShaderInstance> loadedShaderSupplier) {
        this.commandName = commandName;
        this.displayName = displayName;
        this.shaderId = shaderId;
        this.loadedShaderSupplier = loadedShaderSupplier;
    }

    public String getCommandName() {
        return this.commandName;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public ResourceLocation getShaderId() {
        return this.shaderId;
    }

    @Nullable
    public ShaderInstance getLoadedShader() {
        return this.loadedShaderSupplier.get();
    }

    public boolean isLoaded() {
        return getLoadedShader() != null;
    }

    @Nullable
    public static ShaderFeature byCommandName(String commandName) {
        return BY_COMMAND_NAME.get(normalize(commandName));
    }

    public static List<String> commandNames() {
        return List.of(HELD_ITEM_OUTLINE.commandName);
    }

    public static String availableShaderNames() {
        return HELD_ITEM_OUTLINE.commandName;
    }

    private static ResourceLocation shaderId(String path) {
        return ResourceLocation.fromNamespaceAndPath(ItemGlint.MODID, path);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('-', '_').trim();
    }
}
