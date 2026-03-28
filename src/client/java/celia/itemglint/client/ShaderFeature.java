package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public enum ShaderFeature {
    HELD_ITEM_OUTLINE("held_item_outline", "Held Item Outline");

    private static final Map<String, ShaderFeature> BY_COMMAND_NAME = Map.of(normalize(HELD_ITEM_OUTLINE.commandName), HELD_ITEM_OUTLINE);

    private final String commandName;
    private final String displayName;
    private final Identifier shaderId;

    ShaderFeature(String commandName, String displayName) {
        this.commandName = commandName;
        this.displayName = displayName;
        this.shaderId = Identifier.fromNamespaceAndPath(ItemGlint.MOD_ID, commandName);
    }

    public String getCommandName() {
        return this.commandName;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public Identifier getShaderId() {
        return this.shaderId;
    }

    public boolean isLoaded() {
        return this == HELD_ITEM_OUTLINE && HeldItemOutlinePipelines.isLoaded();
    }

    public static ShaderFeature byCommandName(String commandName) {
        return BY_COMMAND_NAME.get(normalize(commandName));
    }

    public static List<String> commandNames() {
        return List.of(HELD_ITEM_OUTLINE.commandName);
    }

    public static String availableShaderNames() {
        return HELD_ITEM_OUTLINE.commandName;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('-', '_').trim();
    }
}
