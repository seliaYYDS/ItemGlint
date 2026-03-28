package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ItemGlint.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientInputEvents {
    private ClientInputEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (ShaderKeyMappings.OPEN_QUICK_CONFIG.consumeClick()
                && minecraft.player != null
                && !(minecraft.screen instanceof ShaderQuickConfigScreen)) {
            minecraft.setScreen(new ShaderQuickConfigScreen(minecraft.screen));
        }
    }
}
