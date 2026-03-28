package celia.itemglint.client;

public record HeldItemOutlineEffectProfile(
        HeldItemOutlineSettings.ColorMode colorMode,
        int autoSampleSize,
        int autoSampleMaxColors,
        HeldItemOutlineSettings.SampleColorSortMode autoSampleSortMode,
        HeldItemOutlineSettings.BloomResolution bloomResolution,
        boolean bloom,
        int bloomMaxPasses,
        float bloomStrength,
        float bloomRadius,
        float width,
        float softness,
        float alphaThreshold,
        float opacity,
        float red,
        float green,
        float blue,
        float secondaryRed,
        float secondaryGreen,
        float secondaryBlue,
        float colorScrollSpeed,
        float depthWeight,
        float glowStrength) {

    public static HeldItemOutlineEffectProfile captureCurrent() {
        return new HeldItemOutlineEffectProfile(
                HeldItemOutlineSettings.getColorMode(),
                HeldItemOutlineSettings.getAutoSampleSize(),
                HeldItemOutlineSettings.getAutoSampleMaxColors(),
                HeldItemOutlineSettings.getAutoSampleSortMode(),
                HeldItemOutlineSettings.getBloomResolution(),
                HeldItemOutlineSettings.isBloomEnabled(),
                HeldItemOutlineSettings.getBloomMaxPasses(),
                HeldItemOutlineSettings.getBloomStrength(),
                HeldItemOutlineSettings.getBloomRadius(),
                HeldItemOutlineSettings.getWidth(),
                HeldItemOutlineSettings.getSoftness(),
                HeldItemOutlineSettings.getAlphaThreshold(),
                HeldItemOutlineSettings.getOpacity(),
                HeldItemOutlineSettings.getRed(),
                HeldItemOutlineSettings.getGreen(),
                HeldItemOutlineSettings.getBlue(),
                HeldItemOutlineSettings.getSecondaryRed(),
                HeldItemOutlineSettings.getSecondaryGreen(),
                HeldItemOutlineSettings.getSecondaryBlue(),
                HeldItemOutlineSettings.getColorScrollSpeed(),
                HeldItemOutlineSettings.getDepthWeight(),
                HeldItemOutlineSettings.getGlowStrength()
        );
    }

    public static HeldItemOutlineEffectProfile defaults() {
        return new HeldItemOutlineEffectProfile(
                HeldItemOutlineSettings.DEFAULT_COLOR_MODE,
                HeldItemOutlineSettings.DEFAULT_AUTO_SAMPLE_SIZE,
                HeldItemOutlineSettings.DEFAULT_AUTO_SAMPLE_MAX_COLORS,
                HeldItemOutlineSettings.DEFAULT_AUTO_SAMPLE_SORT_MODE,
                HeldItemOutlineSettings.DEFAULT_BLOOM_RESOLUTION,
                HeldItemOutlineSettings.DEFAULT_BLOOM,
                HeldItemOutlineSettings.DEFAULT_BLOOM_MAX_PASSES,
                HeldItemOutlineSettings.DEFAULT_BLOOM_STRENGTH,
                HeldItemOutlineSettings.DEFAULT_BLOOM_RADIUS,
                HeldItemOutlineSettings.DEFAULT_WIDTH,
                HeldItemOutlineSettings.DEFAULT_SOFTNESS,
                HeldItemOutlineSettings.DEFAULT_ALPHA_THRESHOLD,
                HeldItemOutlineSettings.DEFAULT_OPACITY,
                HeldItemOutlineSettings.DEFAULT_RED,
                HeldItemOutlineSettings.DEFAULT_GREEN,
                HeldItemOutlineSettings.DEFAULT_BLUE,
                HeldItemOutlineSettings.DEFAULT_SECONDARY_RED,
                HeldItemOutlineSettings.DEFAULT_SECONDARY_GREEN,
                HeldItemOutlineSettings.DEFAULT_SECONDARY_BLUE,
                HeldItemOutlineSettings.DEFAULT_COLOR_SCROLL_SPEED,
                HeldItemOutlineSettings.DEFAULT_DEPTH_WEIGHT,
                HeldItemOutlineSettings.DEFAULT_GLOW_STRENGTH
        );
    }
}
