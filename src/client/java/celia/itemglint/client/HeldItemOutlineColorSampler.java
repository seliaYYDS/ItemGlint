package celia.itemglint.client;

import celia.itemglint.mixin.client.ItemStackLayerRenderStateAccessor;
import celia.itemglint.mixin.client.ItemStackRenderStateAccessor;
import celia.itemglint.mixin.client.SpriteContentsAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HeldItemOutlineColorSampler {
    private static final int CACHE_LIMIT = 128;
    private static final int COLOR_QUANTIZE_STEP = 24;
    private static final Map<String, SampledColors> CACHE = new LinkedHashMap<>(CACHE_LIMIT, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SampledColors> eldest) {
            return size() > CACHE_LIMIT;
        }
    };


    private HeldItemOutlineColorSampler() {
    }

    public static SampledColors sample(ItemStack stack, HeldItemOutlineEffectProfile profile) {
        if (stack == null || stack.isEmpty()) {
            return SampledColors.EMPTY;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getItemModelResolver() == null) {
            return SampledColors.EMPTY;
        }

        ItemStackRenderState renderState = new ItemStackRenderState();
        if (minecraft.player != null) {
            minecraft.getItemModelResolver().updateForLiving(renderState, stack, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, minecraft.player);
        } else {
            minecraft.getItemModelResolver().updateForNonLiving(renderState, stack, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null);
        }

        String key = buildCacheKey(stack, renderState, profile);
        synchronized (CACHE) {
            SampledColors cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }

        SampledColors sampled = sampleRenderState(renderState, profile);
        synchronized (CACHE) {
            CACHE.put(key, sampled);
        }
        return sampled;
    }

    private static String buildCacheKey(ItemStack stack, ItemStackRenderState renderState, HeldItemOutlineEffectProfile profile) {
        ItemStackRenderStateAccessor accessor = (ItemStackRenderStateAccessor) renderState;
        StringBuilder key = new StringBuilder(256);
        key.append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                .append('|').append(stack.getDamageValue())
                .append('|').append(String.valueOf(stack.getComponentsPatch()))
                .append('|').append(accessor.itemglint$getActiveLayerCount())
                .append('|').append(profile.autoSampleSize())
                .append('|').append(profile.autoSampleMaxColors())
                .append('|').append(profile.autoSampleSortMode().commandName());

        ItemStackRenderState.LayerRenderState[] layers = accessor.itemglint$getLayers();
        if (layers != null) {
            int activeLayerCount = Math.min(accessor.itemglint$getActiveLayerCount(), layers.length);
            for (int i = 0; i < activeLayerCount; i++) {
                key.append("|L").append(i).append(':').append(layerSignature(layers[i]));
            }
        }
        return key.toString();
    }

    private static String layerSignature(ItemStackRenderState.LayerRenderState layer) {
        if (layer == null) {
            return "null";
        }

        ItemStackLayerRenderStateAccessor accessor = (ItemStackLayerRenderStateAccessor) layer;
        StringBuilder signature = new StringBuilder(128);
        List<BakedQuad> quads = accessor.itemglint$getQuads();
        signature.append(quads == null ? 0 : quads.size());
        if (quads != null) {
            for (BakedQuad quad : quads) {
                if (quad == null) {
                    continue;
                }
                signature.append('@');
                appendSpriteSignature(signature, quad.sprite());
                signature.append('#').append(quad.tintIndex());
            }
        }

        int[] tintLayers = accessor.itemglint$getTintLayers();
        if (tintLayers != null && tintLayers.length > 0) {
            signature.append("|t=").append(Arrays.hashCode(tintLayers));
        }

        TextureAtlasSprite particleIcon = accessor.itemglint$getParticleIcon();
        if (particleIcon != null) {
            signature.append("|p=");
            appendSpriteSignature(signature, particleIcon);
        }

        if (accessor.itemglint$getRenderType() != null) {
            signature.append("|r=").append(System.identityHashCode(accessor.itemglint$getRenderType()));
        }
        if (accessor.itemglint$getFoilType() != null) {
            signature.append("|f=").append(accessor.itemglint$getFoilType().name());
        }
        if (accessor.itemglint$getSpecialRenderer() != null) {
            signature.append("|sr=").append(accessor.itemglint$getSpecialRenderer().getClass().getName());
        }
        if (accessor.itemglint$getSpecialRenderArgument() != null) {
            signature.append("|sa=").append(accessor.itemglint$getSpecialRenderArgument());
        }
        return signature.toString();
    }

    private static void appendSpriteSignature(StringBuilder builder, TextureAtlasSprite sprite) {
        if (sprite == null || sprite.contents() == null) {
            builder.append("null");
            return;
        }

        builder.append(sprite.contents().name())
                .append('[')
                .append(sprite.contents().width())
                .append('x')
                .append(sprite.contents().height())
                .append(']');
    }

    private static SampledColors sampleRenderState(ItemStackRenderState renderState, HeldItemOutlineEffectProfile profile) {
        ItemStackRenderStateAccessor accessor = (ItemStackRenderStateAccessor) renderState;
        ItemStackRenderState.LayerRenderState[] layers = accessor.itemglint$getLayers();
        int activeLayerCount = accessor.itemglint$getActiveLayerCount();
        if (layers == null || activeLayerCount <= 0) {
            return SampledColors.EMPTY;
        }

        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(activeLayerCount, layers.length); i++) {
            ItemStackRenderState.LayerRenderState layer = layers[i];
            if (layer == null) {
                continue;
            }
            sampleLayerInto(counts, layer, profile.autoSampleSize());
        }

        if (counts.isEmpty()) {
            TextureAtlasSprite fallbackSprite = renderState.pickParticleIcon(RandomSource.create(42L));
            if (fallbackSprite != null) {
                sampleSpriteInto(counts, fallbackSprite, profile.autoSampleSize(), -1);
            }
        }

        return buildPalette(counts, profile.autoSampleMaxColors(), profile.autoSampleSortMode());
    }

    private static void sampleLayerInto(Map<Integer, Integer> counts, ItemStackRenderState.LayerRenderState layer, int sampleSize) {
        ItemStackLayerRenderStateAccessor accessor = (ItemStackLayerRenderStateAccessor) layer;
        List<BakedQuad> quads = accessor.itemglint$getQuads();
        boolean sampledAnyQuad = false;
        if (quads != null) {
            int[] tintLayers = accessor.itemglint$getTintLayers();
            for (BakedQuad quad : quads) {
                if (quad == null || quad.sprite() == null) {
                    continue;
                }

                int tintArgb = resolveTintColor(quad, tintLayers);
                sampleSpriteInto(counts, quad.sprite(), sampleSize, tintArgb);
                sampledAnyQuad = true;
            }
        }

        if (!sampledAnyQuad) {
            TextureAtlasSprite particleIcon = accessor.itemglint$getParticleIcon();
            if (particleIcon != null) {
                sampleSpriteInto(counts, particleIcon, sampleSize, -1);
            }
        }
    }

    private static int resolveTintColor(BakedQuad quad, int[] tintLayers) {
        if (!quad.isTinted() || tintLayers == null) {
            return -1;
        }

        int tintIndex = quad.tintIndex();
        if (tintIndex < 0 || tintIndex >= tintLayers.length) {
            return -1;
        }

        int tintColor = tintLayers[tintIndex];
        return tintColor == -1 ? -1 : 0xFF000000 | tintColor;
    }

    private static void sampleSpriteInto(Map<Integer, Integer> counts, TextureAtlasSprite sprite, int sampleSize, int tintArgb) {
        NativeImage image = getBaseImage(sprite);
        if (image == null) {
            return;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int step = Math.max(1, sampleSize);
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int argb = image.getPixel(x, y);
                int alpha = argb >>> 24 & 255;
                if (alpha < 20) {
                    continue;
                }

                if (tintArgb != -1) {
                    argb = applyTint(argb, tintArgb);
                }
                counts.merge(quantize(argb), 1, Integer::sum);
            }
        }
    }

    private static NativeImage getBaseImage(TextureAtlasSprite sprite) {
        if (sprite == null || sprite.contents() == null) {
            return null;
        }

        SpriteContentsAccessor accessor = (SpriteContentsAccessor) (Object) sprite.contents();
        NativeImage[] mipLevels = accessor.itemglint$getByMipLevel();
        if (mipLevels != null && mipLevels.length > 0 && mipLevels[0] != null) {
            return mipLevels[0];
        }

        return accessor.itemglint$getOriginalImage();
    }

    private static SampledColors buildPalette(Map<Integer, Integer> counts, int maxColors,
                                              HeldItemOutlineSettings.SampleColorSortMode sortMode) {
        if (counts.isEmpty()) {
            return SampledColors.EMPTY;
        }

        List<ColorWeight> selected = counts.entrySet().stream()
                .map(entry -> new ColorWeight(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(ColorWeight::weight).reversed())
                .limit(Math.max(1, maxColors))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        if (sortMode == HeldItemOutlineSettings.SampleColorSortMode.HUE_SATURATION) {
            selected.sort(Comparator
                    .comparingDouble((ColorWeight weight) -> hsv(weight.argb())[0])
                    .thenComparingDouble(weight -> hsv(weight.argb())[1])
                    .thenComparing(Comparator.comparingInt(ColorWeight::weight).reversed()));
        }

        float[][] palette = new float[Math.min(8, selected.size())][3];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = rgb(selected.get(i).argb());
        }
        return new SampledColors(palette);
    }

    private static int applyTint(int argb, int tintArgb) {
        int alpha = argb >>> 24 & 255;
        int red = ((argb >> 16) & 255) * ((tintArgb >> 16) & 255) / 255;
        int green = ((argb >> 8) & 255) * ((tintArgb >> 8) & 255) / 255;
        int blue = (argb & 255) * (tintArgb & 255) / 255;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int quantize(int argb) {
        int alpha = argb >>> 24 & 255;
        int red = ((argb >> 16) & 255) / COLOR_QUANTIZE_STEP * COLOR_QUANTIZE_STEP;
        int green = ((argb >> 8) & 255) / COLOR_QUANTIZE_STEP * COLOR_QUANTIZE_STEP;
        int blue = (argb & 255) / COLOR_QUANTIZE_STEP * COLOR_QUANTIZE_STEP;
        return alpha << 24
                | Mth.clamp(red, 0, 255) << 16
                | Mth.clamp(green, 0, 255) << 8
                | Mth.clamp(blue, 0, 255);
    }

    private static float[] rgb(int argb) {
        return new float[]{
                ((argb >> 16) & 255) / 255.0F,
                ((argb >> 8) & 255) / 255.0F,
                (argb & 255) / 255.0F
        };
    }

    private static float[] hsv(int argb) {
        float red = ((argb >> 16) & 255) / 255.0F;
        float green = ((argb >> 8) & 255) / 255.0F;
        float blue = (argb & 255) / 255.0F;
        float max = Math.max(red, Math.max(green, blue));
        float min = Math.min(red, Math.min(green, blue));
        float delta = max - min;
        float hue;
        if (delta <= 0.00001F) {
            hue = 0.0F;
        } else if (max == red) {
            hue = ((green - blue) / delta) % 6.0F;
        } else if (max == green) {
            hue = (blue - red) / delta + 2.0F;
        } else {
            hue = (red - green) / delta + 4.0F;
        }
        hue /= 6.0F;
        if (hue < 0.0F) {
            hue += 1.0F;
        }
        float saturation = max <= 0.00001F ? 0.0F : delta / max;
        return new float[]{hue, saturation, max};
    }

    private record ColorWeight(int argb, int weight) {
    }

    public record SampledColors(float[][] palette) {
        public static final SampledColors EMPTY = new SampledColors(new float[0][3]);

        public int size() {
            return this.palette.length;
        }

        public float[] color(int index) {
            return index >= 0 && index < this.palette.length ? this.palette[index] : new float[]{1.0F, 1.0F, 1.0F};
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "SampledColors[%d]", this.palette.length);
        }
    }
}
