package celia.itemglint.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;

import java.util.Locale;

public final class HeldItemOutlineSettings {
    static final ColorMode DEFAULT_COLOR_MODE = ColorMode.SINGLE;
    static final int DEFAULT_AUTO_SAMPLE_SIZE = 2;
    static final int DEFAULT_AUTO_SAMPLE_MAX_COLORS = 4;
    static final SampleColorSortMode DEFAULT_AUTO_SAMPLE_SORT_MODE = SampleColorSortMode.WEIGHT;
    static final BloomResolution DEFAULT_BLOOM_RESOLUTION = BloomResolution.HALF;
    static final boolean DEFAULT_MAIN_HAND = true;
    static final boolean DEFAULT_OFF_HAND = true;
    static final boolean DEFAULT_BLOOM = true;
    static final boolean DEFAULT_RULE_SWITCH_DELAY_ENABLED = true;
    static final int DEFAULT_BLOOM_MAX_PASSES = 4;
    static final float DEFAULT_BLOOM_STRENGTH = 1.00F;
    static final float DEFAULT_BLOOM_RADIUS = 1.00F;
    static final float DEFAULT_WIDTH = 2.25F;
    static final float DEFAULT_SOFTNESS = 1.35F;
    static final float DEFAULT_ALPHA_THRESHOLD = 0.10F;
    static final float DEFAULT_OPACITY = 0.95F;
    static final float DEFAULT_RED = 0.23F;
    static final float DEFAULT_GREEN = 0.95F;
    static final float DEFAULT_BLUE = 1.00F;
    static final float DEFAULT_SECONDARY_RED = 1.00F;
    static final float DEFAULT_SECONDARY_GREEN = 0.25F;
    static final float DEFAULT_SECONDARY_BLUE = 0.78F;
    static final float DEFAULT_COLOR_SCROLL_SPEED = 1.00F;
    static final float DEFAULT_DEPTH_WEIGHT = 1.15F;
    static final float DEFAULT_GLOW_STRENGTH = 0.55F;

    static final float MIN_WIDTH = 0.25F;
    static final float MAX_WIDTH = 8.00F;
    static final float MIN_SOFTNESS = 0.10F;
    static final float MAX_SOFTNESS = 4.00F;
    static final float MIN_ALPHA_THRESHOLD = 0.00F;
    static final float MAX_ALPHA_THRESHOLD = 0.95F;
    static final float MIN_COLOR = 0.00F;
    static final float MAX_COLOR = 1.00F;
    static final float MIN_OPACITY = 0.00F;
    static final float MAX_OPACITY = 1.00F;
    static final float MIN_COLOR_SCROLL_SPEED = 0.00F;
    static final float MAX_COLOR_SCROLL_SPEED = 10.00F;
    static final float MIN_DEPTH_WEIGHT = 0.00F;
    static final float MAX_DEPTH_WEIGHT = 4.00F;
    static final float MIN_GLOW_STRENGTH = 0.00F;
    static final float MAX_GLOW_STRENGTH = 2.00F;
    static final float MIN_BLOOM_STRENGTH = 0.00F;
    static final float MAX_BLOOM_STRENGTH = 8.00F;
    static final float MIN_BLOOM_RADIUS = 0.25F;
    static final float MAX_BLOOM_RADIUS = 12.00F;
    static final int MIN_BLOOM_MAX_PASSES = 1;
    static final int MAX_BLOOM_MAX_PASSES = 6;
    static final int MIN_AUTO_SAMPLE_SIZE = 1;
    static final int MAX_AUTO_SAMPLE_SIZE = 8;
    static final int MIN_AUTO_SAMPLE_MAX_COLORS = 1;
    static final int MAX_AUTO_SAMPLE_MAX_COLORS = 8;

    private static ColorMode colorMode = DEFAULT_COLOR_MODE;
    private static int autoSampleSize = DEFAULT_AUTO_SAMPLE_SIZE;
    private static int autoSampleMaxColors = DEFAULT_AUTO_SAMPLE_MAX_COLORS;
    private static SampleColorSortMode autoSampleSortMode = DEFAULT_AUTO_SAMPLE_SORT_MODE;
    private static BloomResolution bloomResolution = DEFAULT_BLOOM_RESOLUTION;
    private static boolean mainHand = DEFAULT_MAIN_HAND;
    private static boolean offHand = DEFAULT_OFF_HAND;
    private static boolean bloom = DEFAULT_BLOOM;
    private static boolean ruleSwitchDelayEnabled = DEFAULT_RULE_SWITCH_DELAY_ENABLED;
    private static int bloomMaxPasses = DEFAULT_BLOOM_MAX_PASSES;
    private static float bloomStrength = DEFAULT_BLOOM_STRENGTH;
    private static float bloomRadius = DEFAULT_BLOOM_RADIUS;
    private static float width = DEFAULT_WIDTH;
    private static float softness = DEFAULT_SOFTNESS;
    private static float alphaThreshold = DEFAULT_ALPHA_THRESHOLD;
    private static float opacity = DEFAULT_OPACITY;
    private static float red = DEFAULT_RED;
    private static float green = DEFAULT_GREEN;
    private static float blue = DEFAULT_BLUE;
    private static float secondaryRed = DEFAULT_SECONDARY_RED;
    private static float secondaryGreen = DEFAULT_SECONDARY_GREEN;
    private static float secondaryBlue = DEFAULT_SECONDARY_BLUE;
    private static float colorScrollSpeed = DEFAULT_COLOR_SCROLL_SPEED;
    private static float depthWeight = DEFAULT_DEPTH_WEIGHT;
    private static float glowStrength = DEFAULT_GLOW_STRENGTH;

    private HeldItemOutlineSettings() {
    }

    public static ColorMode getColorMode() {
        return colorMode;
    }

    public static ColorMode setColorMode(ColorMode mode) {
        colorMode = mode == null ? DEFAULT_COLOR_MODE : mode;
        return colorMode;
    }

    public static BloomResolution getBloomResolution() {
        return bloomResolution;
    }

    public static int getAutoSampleSize() {
        return autoSampleSize;
    }

    public static int setAutoSampleSize(int value) {
        autoSampleSize = Mth.clamp(value, MIN_AUTO_SAMPLE_SIZE, MAX_AUTO_SAMPLE_SIZE);
        return autoSampleSize;
    }

    public static int getAutoSampleMaxColors() {
        return autoSampleMaxColors;
    }

    public static int setAutoSampleMaxColors(int value) {
        autoSampleMaxColors = Mth.clamp(value, MIN_AUTO_SAMPLE_MAX_COLORS, MAX_AUTO_SAMPLE_MAX_COLORS);
        return autoSampleMaxColors;
    }

    public static SampleColorSortMode getAutoSampleSortMode() {
        return autoSampleSortMode;
    }

    public static SampleColorSortMode setAutoSampleSortMode(SampleColorSortMode value) {
        autoSampleSortMode = value == null ? DEFAULT_AUTO_SAMPLE_SORT_MODE : value;
        return autoSampleSortMode;
    }

    public static BloomResolution setBloomResolution(BloomResolution resolution) {
        bloomResolution = resolution == null ? DEFAULT_BLOOM_RESOLUTION : resolution;
        return bloomResolution;
    }

    public static boolean isMainHandEnabled() {
        return mainHand;
    }

    public static boolean setMainHandEnabled(boolean enabled) {
        mainHand = enabled;
        return mainHand;
    }

    public static boolean isOffHandEnabled() {
        return offHand;
    }

    public static boolean setOffHandEnabled(boolean enabled) {
        offHand = enabled;
        return offHand;
    }

    public static boolean shouldRenderHand(InteractionHand hand) {
        return switch (hand) {
            case MAIN_HAND -> mainHand;
            case OFF_HAND -> offHand;
        };
    }

    public static boolean rendersAnyHand() {
        return mainHand || offHand;
    }

    public static boolean isBloomEnabled() {
        return bloom;
    }

    public static boolean setBloomEnabled(boolean enabled) {
        bloom = enabled;
        return bloom;
    }

    public static boolean isRuleSwitchDelayEnabled() {
        return ruleSwitchDelayEnabled;
    }

    public static boolean setRuleSwitchDelayEnabled(boolean enabled) {
        ruleSwitchDelayEnabled = enabled;
        return ruleSwitchDelayEnabled;
    }

    public static int getBloomMaxPasses() {
        return bloomMaxPasses;
    }

    public static int setBloomMaxPasses(int value) {
        bloomMaxPasses = Mth.clamp(value, MIN_BLOOM_MAX_PASSES, MAX_BLOOM_MAX_PASSES);
        return bloomMaxPasses;
    }

    public static float getBloomStrength() {
        return bloomStrength;
    }

    public static float setBloomStrength(float value) {
        bloomStrength = clamp(value, MIN_BLOOM_STRENGTH, MAX_BLOOM_STRENGTH);
        return bloomStrength;
    }

    public static float getBloomRadius() {
        return bloomRadius;
    }

    public static float setBloomRadius(float value) {
        bloomRadius = clamp(value, MIN_BLOOM_RADIUS, MAX_BLOOM_RADIUS);
        return bloomRadius;
    }

    public static float getWidth() {
        return width;
    }

    public static float setWidth(float value) {
        width = clamp(value, MIN_WIDTH, MAX_WIDTH);
        return width;
    }

    public static float getSoftness() {
        return softness;
    }

    public static float setSoftness(float value) {
        softness = clamp(value, MIN_SOFTNESS, MAX_SOFTNESS);
        return softness;
    }

    public static float getAlphaThreshold() {
        return alphaThreshold;
    }

    public static float setAlphaThreshold(float value) {
        alphaThreshold = clamp(value, MIN_ALPHA_THRESHOLD, MAX_ALPHA_THRESHOLD);
        return alphaThreshold;
    }

    public static float getOpacity() {
        return opacity;
    }

    public static float setOpacity(float value) {
        opacity = clamp(value, MIN_OPACITY, MAX_OPACITY);
        return opacity;
    }

    public static float getRed() {
        return red;
    }

    public static float setRed(float value) {
        red = clamp(value, MIN_COLOR, MAX_COLOR);
        return red;
    }

    public static float getGreen() {
        return green;
    }

    public static float setGreen(float value) {
        green = clamp(value, MIN_COLOR, MAX_COLOR);
        return green;
    }

    public static float getBlue() {
        return blue;
    }

    public static float setBlue(float value) {
        blue = clamp(value, MIN_COLOR, MAX_COLOR);
        return blue;
    }

    public static float getSecondaryRed() {
        return secondaryRed;
    }

    public static float setSecondaryRed(float value) {
        secondaryRed = clamp(value, MIN_COLOR, MAX_COLOR);
        return secondaryRed;
    }

    public static float getSecondaryGreen() {
        return secondaryGreen;
    }

    public static float setSecondaryGreen(float value) {
        secondaryGreen = clamp(value, MIN_COLOR, MAX_COLOR);
        return secondaryGreen;
    }

    public static float getSecondaryBlue() {
        return secondaryBlue;
    }

    public static float setSecondaryBlue(float value) {
        secondaryBlue = clamp(value, MIN_COLOR, MAX_COLOR);
        return secondaryBlue;
    }

    public static float getColorScrollSpeed() {
        return colorScrollSpeed;
    }

    public static float setColorScrollSpeed(float value) {
        colorScrollSpeed = clamp(value, MIN_COLOR_SCROLL_SPEED, MAX_COLOR_SCROLL_SPEED);
        return colorScrollSpeed;
    }

    public static float getDepthWeight() {
        return depthWeight;
    }

    public static float setDepthWeight(float value) {
        depthWeight = clamp(value, MIN_DEPTH_WEIGHT, MAX_DEPTH_WEIGHT);
        return depthWeight;
    }

    public static float getGlowStrength() {
        return glowStrength;
    }

    public static float setGlowStrength(float value) {
        glowStrength = clamp(value, MIN_GLOW_STRENGTH, MAX_GLOW_STRENGTH);
        return glowStrength;
    }

    public static void reset() {
        colorMode = DEFAULT_COLOR_MODE;
        autoSampleSize = DEFAULT_AUTO_SAMPLE_SIZE;
        autoSampleMaxColors = DEFAULT_AUTO_SAMPLE_MAX_COLORS;
        autoSampleSortMode = DEFAULT_AUTO_SAMPLE_SORT_MODE;
        bloomResolution = DEFAULT_BLOOM_RESOLUTION;
        mainHand = DEFAULT_MAIN_HAND;
        offHand = DEFAULT_OFF_HAND;
        bloom = DEFAULT_BLOOM;
        ruleSwitchDelayEnabled = DEFAULT_RULE_SWITCH_DELAY_ENABLED;
        bloomMaxPasses = DEFAULT_BLOOM_MAX_PASSES;
        bloomStrength = DEFAULT_BLOOM_STRENGTH;
        bloomRadius = DEFAULT_BLOOM_RADIUS;
        width = DEFAULT_WIDTH;
        softness = DEFAULT_SOFTNESS;
        alphaThreshold = DEFAULT_ALPHA_THRESHOLD;
        opacity = DEFAULT_OPACITY;
        red = DEFAULT_RED;
        green = DEFAULT_GREEN;
        blue = DEFAULT_BLUE;
        secondaryRed = DEFAULT_SECONDARY_RED;
        secondaryGreen = DEFAULT_SECONDARY_GREEN;
        secondaryBlue = DEFAULT_SECONDARY_BLUE;
        colorScrollSpeed = DEFAULT_COLOR_SCROLL_SPEED;
        depthWeight = DEFAULT_DEPTH_WEIGHT;
        glowStrength = DEFAULT_GLOW_STRENGTH;
    }

    public static Component describe() {
        MutableComponent settings = Component.literal(String.format(Locale.ROOT,
                        "mode=%s, main=%s, off=%s, bloom=%s, bloom_resolution=%s, bloom_passes=%d, bloom_strength=%.2f, bloom_radius=%.2f, width=%.2f, softness=%.2f, threshold=%.2f, opacity=%.2f",
                        colorMode.commandName(), mainHand, offHand, bloom, bloomResolution.commandName(), bloomMaxPasses,
                        bloomStrength, bloomRadius, width, softness, alphaThreshold, opacity))
                .withStyle(ChatFormatting.AQUA);
        settings.append(Component.literal(String.format(Locale.ROOT,
                                ", switch_delay=%s, sample_size=%d, sample_colors=%d, sample_sort=%s",
                                ruleSwitchDelayEnabled, autoSampleSize, autoSampleMaxColors, autoSampleSortMode.commandName()))
                        .withStyle(ChatFormatting.DARK_AQUA));
        settings.append(Component.literal(String.format(Locale.ROOT,
                                ", color=(%.2f, %.2f, %.2f), secondary=(%.2f, %.2f, %.2f), scroll=%.2f, depth=%.2f, glow=%.2f",
                                red, green, blue, secondaryRed, secondaryGreen, secondaryBlue, colorScrollSpeed, depthWeight, glowStrength))
                        .withStyle(ChatFormatting.DARK_AQUA));
        return settings;
    }

    private static float clamp(float value, float min, float max) {
        return Mth.clamp(value, min, max);
    }

    public enum ColorMode {
        SINGLE("single", 0.0F),
        DUAL_SCROLL("dual_scroll", 1.0F),
        RAINBOW_FLOW("rainbow_flow", 2.0F),
        AUTO_SAMPLE_SCROLL("auto_sample_scroll", 3.0F);

        private final String commandName;
        private final float shaderValue;

        ColorMode(String commandName, float shaderValue) {
            this.commandName = commandName;
            this.shaderValue = shaderValue;
        }

        public String commandName() {
            return commandName;
        }

        public float shaderValue() {
            return shaderValue;
        }

        public static ColorMode byName(String name) {
            if (name == null) {
                return null;
            }

            for (ColorMode mode : values()) {
                if (mode.commandName.equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            return null;
        }
    }

    public enum SampleColorSortMode {
        WEIGHT("weight"),
        HUE_SATURATION("hue_saturation");

        private final String commandName;

        SampleColorSortMode(String commandName) {
            this.commandName = commandName;
        }

        public String commandName() {
            return this.commandName;
        }

        public static SampleColorSortMode byName(String name) {
            if (name == null) {
                return null;
            }

            for (SampleColorSortMode mode : values()) {
                if (mode.commandName.equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            return null;
        }
    }

    public enum BloomResolution {
        FULL("full", 1),
        HALF("half", 2),
        QUARTER("quarter", 4);

        private final String commandName;
        private final int downsampleFactor;

        BloomResolution(String commandName, int downsampleFactor) {
            this.commandName = commandName;
            this.downsampleFactor = downsampleFactor;
        }

        public String commandName() {
            return commandName;
        }

        public int downsampleFactor() {
            return downsampleFactor;
        }

        public static BloomResolution byName(String name) {
            if (name == null) {
                return null;
            }

            for (BloomResolution resolution : values()) {
                if (resolution.commandName.equalsIgnoreCase(name)) {
                    return resolution;
                }
            }
            return null;
        }
    }
}
