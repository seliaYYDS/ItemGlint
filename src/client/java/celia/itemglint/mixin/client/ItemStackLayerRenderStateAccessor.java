package celia.itemglint.mixin.client;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemStackLayerRenderStateAccessor {
    @Accessor("quads")
    List<BakedQuad> itemglint$getQuads();

    @Accessor("tintLayers")
    int[] itemglint$getTintLayers();

    @Accessor("particleIcon")
    TextureAtlasSprite itemglint$getParticleIcon();

    @Accessor("renderType")
    RenderType itemglint$getRenderType();

    @Accessor("foilType")
    ItemStackRenderState.FoilType itemglint$getFoilType();

    @Accessor("specialRenderer")
    SpecialModelRenderer<?> itemglint$getSpecialRenderer();

    @Accessor("argumentForSpecialRendering")
    Object itemglint$getSpecialRenderArgument();
}
