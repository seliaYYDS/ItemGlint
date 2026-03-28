package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import net.fabricmc.api.ClientModInitializer;

public final class ItemGlintClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ItemGlint.LOGGER.info("Initializing client for {}.", ItemGlint.MOD_ID);
        HeldItemOutlinePipelines.initialize();
        ShaderClientConfig.getInstance().load();
        ShaderKeyMappings.initialize();
    }
}
