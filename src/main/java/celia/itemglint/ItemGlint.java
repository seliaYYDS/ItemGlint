package celia.itemglint;

import celia.itemglint.client.ShaderClientConfig;
import celia.itemglint.client.HeldItemOutlineCompat;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(ItemGlint.MODID)
public class ItemGlint {
    public static final String MODID = "itemglint";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean COMPAT_DEBUG = Boolean.getBoolean("itemglint.debugCompat");

    public ItemGlint() {
        if (COMPAT_DEBUG) {
            LOGGER.warn("[HeldItemOutlineCompat] Debug logging enabled. embeddiumLoaded={}, oculusLoaded={}",
                    HeldItemOutlineCompat.isEmbeddiumLoaded(),
                    HeldItemOutlineCompat.isOculusLoaded());
            System.err.println("[HeldItemOutlineCompat] Debug logging enabled. embeddiumLoaded="
                    + HeldItemOutlineCompat.isEmbeddiumLoaded()
                    + ", oculusLoaded=" + HeldItemOutlineCompat.isOculusLoaded());
        }

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ShaderClientConfig.register();
        }
    }
}
