package celia.itemglint.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("byMipLevel")
    NativeImage[] itemglint$getByMipLevel();

    @Accessor("originalImage")
    NativeImage itemglint$getOriginalImage();
}
