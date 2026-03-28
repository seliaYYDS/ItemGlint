package celia.itemglint;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;

public final class ItemGlint implements ModInitializer {
    public static final String MOD_ID = "itemglint";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing {}.", MOD_ID);
    }
}
