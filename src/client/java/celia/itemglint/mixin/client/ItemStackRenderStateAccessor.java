package celia.itemglint.mixin.client;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
    @Accessor("activeLayerCount")
    int itemglint$getActiveLayerCount();

    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] itemglint$getLayers();
}
