package celia.itemglint.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
        if (minecraft == null || minecraft.getItemRenderer() == null) {
            return SampledColors.EMPTY;
        }
        BakedModel model = minecraft.getItemRenderer().getModel(stack, minecraft.level, minecraft.player, 0);
        if (model == null) {
            return SampledColors.EMPTY;
        }

        String key = buildCacheKey(stack, model, profile);
        synchronized (CACHE) {
            SampledColors cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }

        SampledColors sampled = sampleModel(minecraft, stack, model, profile);
        synchronized (CACHE) {
            CACHE.put(key, sampled);
        }
        return sampled;
    }

    private static String buildCacheKey(ItemStack stack, BakedModel model, HeldItemOutlineEffectProfile profile) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem())
                + "|" + stack.getDamageValue()
                + "|" + String.valueOf(stack.getTag())
                + "|" + System.identityHashCode(model)
                + "|" + profile.autoSampleSize()
                + "|" + profile.autoSampleMaxColors()
                + "|" + profile.autoSampleSortMode().commandName();
    }

    private static SampledColors sampleModel(Minecraft minecraft, ItemStack stack, BakedModel model, HeldItemOutlineEffectProfile profile) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        collectModelSpriteSamples(counts, minecraft, stack, model, profile.autoSampleSize());
        if (counts.isEmpty()) {
            TextureAtlasSprite sprite = resolveParticleSprite(model);
            if (sprite == null) {
                return SampledColors.EMPTY;
            }
            sampleSpriteInto(counts, sprite, profile.autoSampleSize(), -1);
        }
        return buildPalette(counts, profile.autoSampleMaxColors(), profile.autoSampleSortMode());
    }

    private static void collectModelSpriteSamples(Map<Integer, Integer> counts, Minecraft minecraft, ItemStack stack,
                                                  BakedModel model, int sampleSize) {
        ItemColors itemColors = minecraft.getItemColors();
        for (BakedModel renderPass : resolveRenderPasses(model, stack)) {
            List<BakedQuad> quads = new ArrayList<>();
            collectQuads(quads, renderPass, null);
            for (Direction direction : Direction.values()) {
                collectQuads(quads, renderPass, direction);
            }

            for (BakedQuad quad : quads) {
                TextureAtlasSprite sprite = quad.getSprite();
                if (sprite == null) {
                    continue;
                }
                int tintColor = resolveTintColor(itemColors, stack, quad);
                sampleSpriteInto(counts, sprite, sampleSize, tintColor);
            }
        }
    }

    private static List<BakedModel> resolveRenderPasses(BakedModel model, ItemStack stack) {
        try {
            List<BakedModel> renderPasses = model.getRenderPasses(stack, true);
            return renderPasses == null || renderPasses.isEmpty() ? List.of(model) : renderPasses;
        } catch (Throwable ignored) {
            return List.of(model);
        }
    }

    private static void collectQuads(List<BakedQuad> target, BakedModel model, @Nullable Direction direction) {
        try {
            RandomSource random = RandomSource.create(42L);
            random.setSeed(42L);
            target.addAll(model.getQuads(null, direction, random));
        } catch (Throwable ignored) {
        }
    }

    private static int resolveTintColor(ItemColors itemColors, ItemStack stack, BakedQuad quad) {
        if (!quad.isTinted()) {
            return -1;
        }
        int tintColor = itemColors.getColor(stack, quad.getTintIndex());
        return tintColor == -1 ? -1 : 0xFF000000 | tintColor;
    }

    @Nullable
    private static TextureAtlasSprite resolveParticleSprite(BakedModel model) {
        try {
            return model.getParticleIcon();
        } catch (Throwable ignored) {
            return null;
        }
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

    private static void sampleSpriteInto(Map<Integer, Integer> counts, TextureAtlasSprite sprite, int sampleSize, int tintArgb) {
        NativeImage[] byMipLevel = sprite.contents().byMipLevel;
        if (byMipLevel == null || byMipLevel.length == 0 || byMipLevel[0] == null) {
            return;
        }
        NativeImage image = byMipLevel[0];
        int width = image.getWidth();
        int height = image.getHeight();
        int step = Math.max(1, sampleSize);

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int rgba = image.getPixelRGBA(x, y);
                int alpha = rgba >>> 24 & 255;
                if (alpha < 20) {
                    continue;
                }
                int argb = nativeToArgb(rgba);
                if (tintArgb != -1) {
                    argb = applyTint(argb, tintArgb);
                }
                int quantized = quantize(argb);
                counts.merge(quantized, 1, Integer::sum);
            }
        }
    }

    private static int applyTint(int argb, int tintArgb) {
        int alpha = argb >>> 24 & 255;
        int red = ((argb >> 16) & 255) * ((tintArgb >> 16) & 255) / 255;
        int green = ((argb >> 8) & 255) * ((tintArgb >> 8) & 255) / 255;
        int blue = (argb & 255) * (tintArgb & 255) / 255;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int nativeToArgb(int nativeRgba) {
        int alpha = nativeRgba >>> 24 & 255;
        int blue = nativeRgba >>> 16 & 255;
        int green = nativeRgba >>> 8 & 255;
        int red = nativeRgba & 255;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int quantize(int argb) {
        int alpha = argb >>> 24 & 255;
        int red = ((argb >> 16) & 255) / COLOR_QUANTIZE_STEP * COLOR_QUANTIZE_STEP;
        int green = ((argb >> 8) & 255) / COLOR_QUANTIZE_STEP * COLOR_QUANTIZE_STEP;
        int blue = (argb & 255) / COLOR_QUANTIZE_STEP * COLOR_QUANTIZE_STEP;
        red = Mth.clamp(red, 0, 255);
        green = Mth.clamp(green, 0, 255);
        blue = Mth.clamp(blue, 0, 255);
        return alpha << 24 | red << 16 | green << 8 | blue;
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
