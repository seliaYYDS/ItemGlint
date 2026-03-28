package celia.itemglint.client;

import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;

public interface ItemGlintConfigAccess {
    Path configPath();

    ItemGlintClientConfigData snapshot();

    void load();

    void save();

    void apply(ItemGlintClientConfigData data, boolean persist);

    Screen createConfigScreen(Screen parent);
}
