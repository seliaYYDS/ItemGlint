package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = ItemGlint.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ShaderClientConfig {
    private static final Object CONFIG_LOCK = new Object();

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue HELD_ITEM_OUTLINE_ENABLED;
    private static final ForgeConfigSpec.BooleanValue HELD_ITEM_MAIN_HAND;
    private static final ForgeConfigSpec.BooleanValue HELD_ITEM_OFF_HAND;
    private static final ForgeConfigSpec.BooleanValue HELD_ITEM_BLOOM;
    private static final ForgeConfigSpec.EnumValue<HeldItemOutlineSettings.ColorMode> HELD_ITEM_COLOR_MODE;
    private static final ForgeConfigSpec.EnumValue<HeldItemOutlineSettings.BloomResolution> HELD_ITEM_BLOOM_RESOLUTION;
    private static final ForgeConfigSpec.IntValue HELD_ITEM_BLOOM_MAX_PASSES;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_BLOOM_STRENGTH;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_BLOOM_RADIUS;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_WIDTH;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_SOFTNESS;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_ALPHA_THRESHOLD;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_OPACITY;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_RED;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_GREEN;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_BLUE;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_SECONDARY_RED;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_SECONDARY_GREEN;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_SECONDARY_BLUE;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_COLOR_SCROLL_SPEED;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_DEPTH_WEIGHT;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_GLOW_STRENGTH;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_PULSE_SPEED;
    private static final ForgeConfigSpec.DoubleValue HELD_ITEM_PULSE_AMOUNT;

    private static boolean suppressOwnedConfigReload;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Held item outline feature toggle.").push("shader_features");
        HELD_ITEM_OUTLINE_ENABLED = builder
                .comment("Enable the held item outline renderer.")
                .define(ShaderFeature.HELD_ITEM_OUTLINE.getCommandName(), true);
        builder.pop();

        builder.comment("Held item outline settings.").push("held_item_outline");
        HELD_ITEM_MAIN_HAND = builder.comment("Render the outline for the main-hand item.").define("main_hand", HeldItemOutlineSettings.DEFAULT_MAIN_HAND);
        HELD_ITEM_OFF_HAND = builder.comment("Render the outline for the off-hand item.").define("off_hand", HeldItemOutlineSettings.DEFAULT_OFF_HAND);
        HELD_ITEM_BLOOM = builder.comment("Render bloom around the outline.").define("bloom", HeldItemOutlineSettings.DEFAULT_BLOOM);
        HELD_ITEM_COLOR_MODE = builder.comment("Color mode for the outline and bloom.").defineEnum("color_mode", HeldItemOutlineSettings.DEFAULT_COLOR_MODE);
        HELD_ITEM_BLOOM_RESOLUTION = builder.comment("Internal bloom render resolution.").defineEnum("bloom_resolution", HeldItemOutlineSettings.DEFAULT_BLOOM_RESOLUTION);
        HELD_ITEM_BLOOM_MAX_PASSES = builder.comment("Maximum number of blur passes.").defineInRange("bloom_max_passes", HeldItemOutlineSettings.DEFAULT_BLOOM_MAX_PASSES, HeldItemOutlineSettings.MIN_BLOOM_MAX_PASSES, HeldItemOutlineSettings.MAX_BLOOM_MAX_PASSES);
        HELD_ITEM_BLOOM_STRENGTH = builder.comment("Bloom strength multiplier.").defineInRange("bloom_strength", (double) HeldItemOutlineSettings.DEFAULT_BLOOM_STRENGTH, HeldItemOutlineSettings.MIN_BLOOM_STRENGTH, HeldItemOutlineSettings.MAX_BLOOM_STRENGTH);
        HELD_ITEM_BLOOM_RADIUS = builder.comment("Bloom blur radius multiplier.").defineInRange("bloom_radius", (double) HeldItemOutlineSettings.DEFAULT_BLOOM_RADIUS, HeldItemOutlineSettings.MIN_BLOOM_RADIUS, HeldItemOutlineSettings.MAX_BLOOM_RADIUS);
        HELD_ITEM_WIDTH = builder.comment("Outline width in pixels.").defineInRange("width", (double) HeldItemOutlineSettings.DEFAULT_WIDTH, HeldItemOutlineSettings.MIN_WIDTH, HeldItemOutlineSettings.MAX_WIDTH);
        HELD_ITEM_SOFTNESS = builder.comment("Outline softness.").defineInRange("softness", (double) HeldItemOutlineSettings.DEFAULT_SOFTNESS, HeldItemOutlineSettings.MIN_SOFTNESS, HeldItemOutlineSettings.MAX_SOFTNESS);
        HELD_ITEM_ALPHA_THRESHOLD = builder.comment("Alpha threshold for the held-item mask.").defineInRange("alpha_threshold", (double) HeldItemOutlineSettings.DEFAULT_ALPHA_THRESHOLD, HeldItemOutlineSettings.MIN_ALPHA_THRESHOLD, HeldItemOutlineSettings.MAX_ALPHA_THRESHOLD);
        HELD_ITEM_OPACITY = builder.comment("Outline opacity.").defineInRange("opacity", (double) HeldItemOutlineSettings.DEFAULT_OPACITY, HeldItemOutlineSettings.MIN_OPACITY, HeldItemOutlineSettings.MAX_OPACITY);
        HELD_ITEM_RED = builder.comment("Primary red channel.").defineInRange("red", (double) HeldItemOutlineSettings.DEFAULT_RED, HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR);
        HELD_ITEM_GREEN = builder.comment("Primary green channel.").defineInRange("green", (double) HeldItemOutlineSettings.DEFAULT_GREEN, HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR);
        HELD_ITEM_BLUE = builder.comment("Primary blue channel.").defineInRange("blue", (double) HeldItemOutlineSettings.DEFAULT_BLUE, HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR);
        HELD_ITEM_SECONDARY_RED = builder.comment("Secondary red channel.").defineInRange("secondary_red", (double) HeldItemOutlineSettings.DEFAULT_SECONDARY_RED, HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR);
        HELD_ITEM_SECONDARY_GREEN = builder.comment("Secondary green channel.").defineInRange("secondary_green", (double) HeldItemOutlineSettings.DEFAULT_SECONDARY_GREEN, HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR);
        HELD_ITEM_SECONDARY_BLUE = builder.comment("Secondary blue channel.").defineInRange("secondary_blue", (double) HeldItemOutlineSettings.DEFAULT_SECONDARY_BLUE, HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR);
        HELD_ITEM_COLOR_SCROLL_SPEED = builder.comment("Color scroll speed.").defineInRange("color_scroll_speed", (double) HeldItemOutlineSettings.DEFAULT_COLOR_SCROLL_SPEED, HeldItemOutlineSettings.MIN_COLOR_SCROLL_SPEED, HeldItemOutlineSettings.MAX_COLOR_SCROLL_SPEED);
        HELD_ITEM_DEPTH_WEIGHT = builder.comment("Depth weighting applied to the outline.").defineInRange("depth_weight", (double) HeldItemOutlineSettings.DEFAULT_DEPTH_WEIGHT, HeldItemOutlineSettings.MIN_DEPTH_WEIGHT, HeldItemOutlineSettings.MAX_DEPTH_WEIGHT);
        HELD_ITEM_GLOW_STRENGTH = builder.comment("Extra glow strength.").defineInRange("glow_strength", (double) HeldItemOutlineSettings.DEFAULT_GLOW_STRENGTH, HeldItemOutlineSettings.MIN_GLOW_STRENGTH, HeldItemOutlineSettings.MAX_GLOW_STRENGTH);
        HELD_ITEM_PULSE_SPEED = builder.comment("Pulse animation speed.").defineInRange("pulse_speed", (double) HeldItemOutlineSettings.DEFAULT_PULSE_SPEED, HeldItemOutlineSettings.MIN_PULSE_SPEED, HeldItemOutlineSettings.MAX_PULSE_SPEED);
        HELD_ITEM_PULSE_AMOUNT = builder.comment("Pulse animation amount.").defineInRange("pulse_amount", (double) HeldItemOutlineSettings.DEFAULT_PULSE_AMOUNT, HeldItemOutlineSettings.MIN_PULSE_AMOUNT, HeldItemOutlineSettings.MAX_PULSE_AMOUNT);
        builder.pop();

        SPEC = builder.build();
    }

    private ShaderClientConfig() {
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SPEC, ItemGlint.MODID + "-client.toml");
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(ShaderConfigScreen::new));
    }

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        applyIfOwnedConfig(event.getConfig());
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        applyIfOwnedConfig(event.getConfig());
    }

    public static void syncShaderState(ShaderFeature shader) {
        if (shader != ShaderFeature.HELD_ITEM_OUTLINE || !SPEC.isLoaded()) {
            return;
        }
        synchronized (CONFIG_LOCK) {
            HELD_ITEM_OUTLINE_ENABLED.set(ShaderFeatureManager.isEnabled(ShaderFeature.HELD_ITEM_OUTLINE));
            saveSpecLocked();
        }
    }

    public static void syncHeldItemOutlineSettings() {
        if (!SPEC.isLoaded()) {
            return;
        }
        synchronized (CONFIG_LOCK) {
            writeHeldItemOutlineSettings();
            saveSpecLocked();
        }
    }

    public static void syncAllSettings() {
        if (!SPEC.isLoaded()) {
            return;
        }
        synchronized (CONFIG_LOCK) {
            HELD_ITEM_OUTLINE_ENABLED.set(ShaderFeatureManager.isEnabled(ShaderFeature.HELD_ITEM_OUTLINE));
            writeHeldItemOutlineSettings();
            saveSpecLocked();
        }
    }

    private static void applyIfOwnedConfig(ModConfig config) {
        if (config.getSpec() != SPEC) {
            return;
        }
        synchronized (CONFIG_LOCK) {
            if (!suppressOwnedConfigReload) {
                applyLoadedValues();
            }
        }
    }

    private static void applyLoadedValues() {
        ShaderFeatureManager.setEnabled(ShaderFeature.HELD_ITEM_OUTLINE, HELD_ITEM_OUTLINE_ENABLED.get());
        HeldItemOutlineSettings.setMainHandEnabled(HELD_ITEM_MAIN_HAND.get());
        HeldItemOutlineSettings.setOffHandEnabled(HELD_ITEM_OFF_HAND.get());
        HeldItemOutlineSettings.setBloomEnabled(HELD_ITEM_BLOOM.get());
        HeldItemOutlineSettings.setColorMode(HELD_ITEM_COLOR_MODE.get());
        HeldItemOutlineSettings.setBloomResolution(HELD_ITEM_BLOOM_RESOLUTION.get());
        HeldItemOutlineSettings.setBloomMaxPasses(HELD_ITEM_BLOOM_MAX_PASSES.get());
        HeldItemOutlineSettings.setBloomStrength(HELD_ITEM_BLOOM_STRENGTH.get().floatValue());
        HeldItemOutlineSettings.setBloomRadius(HELD_ITEM_BLOOM_RADIUS.get().floatValue());
        HeldItemOutlineSettings.setWidth(HELD_ITEM_WIDTH.get().floatValue());
        HeldItemOutlineSettings.setSoftness(HELD_ITEM_SOFTNESS.get().floatValue());
        HeldItemOutlineSettings.setAlphaThreshold(HELD_ITEM_ALPHA_THRESHOLD.get().floatValue());
        HeldItemOutlineSettings.setOpacity(HELD_ITEM_OPACITY.get().floatValue());
        HeldItemOutlineSettings.setRed(HELD_ITEM_RED.get().floatValue());
        HeldItemOutlineSettings.setGreen(HELD_ITEM_GREEN.get().floatValue());
        HeldItemOutlineSettings.setBlue(HELD_ITEM_BLUE.get().floatValue());
        HeldItemOutlineSettings.setSecondaryRed(HELD_ITEM_SECONDARY_RED.get().floatValue());
        HeldItemOutlineSettings.setSecondaryGreen(HELD_ITEM_SECONDARY_GREEN.get().floatValue());
        HeldItemOutlineSettings.setSecondaryBlue(HELD_ITEM_SECONDARY_BLUE.get().floatValue());
        HeldItemOutlineSettings.setColorScrollSpeed(HELD_ITEM_COLOR_SCROLL_SPEED.get().floatValue());
        HeldItemOutlineSettings.setDepthWeight(HELD_ITEM_DEPTH_WEIGHT.get().floatValue());
        HeldItemOutlineSettings.setGlowStrength(HELD_ITEM_GLOW_STRENGTH.get().floatValue());
        HeldItemOutlineSettings.setPulseSpeed(HELD_ITEM_PULSE_SPEED.get().floatValue());
        HeldItemOutlineSettings.setPulseAmount(HELD_ITEM_PULSE_AMOUNT.get().floatValue());
    }

    private static void writeHeldItemOutlineSettings() {
        HELD_ITEM_MAIN_HAND.set(HeldItemOutlineSettings.isMainHandEnabled());
        HELD_ITEM_OFF_HAND.set(HeldItemOutlineSettings.isOffHandEnabled());
        HELD_ITEM_BLOOM.set(HeldItemOutlineSettings.isBloomEnabled());
        HELD_ITEM_COLOR_MODE.set(HeldItemOutlineSettings.getColorMode());
        HELD_ITEM_BLOOM_RESOLUTION.set(HeldItemOutlineSettings.getBloomResolution());
        HELD_ITEM_BLOOM_MAX_PASSES.set(HeldItemOutlineSettings.getBloomMaxPasses());
        HELD_ITEM_BLOOM_STRENGTH.set((double) HeldItemOutlineSettings.getBloomStrength());
        HELD_ITEM_BLOOM_RADIUS.set((double) HeldItemOutlineSettings.getBloomRadius());
        HELD_ITEM_WIDTH.set((double) HeldItemOutlineSettings.getWidth());
        HELD_ITEM_SOFTNESS.set((double) HeldItemOutlineSettings.getSoftness());
        HELD_ITEM_ALPHA_THRESHOLD.set((double) HeldItemOutlineSettings.getAlphaThreshold());
        HELD_ITEM_OPACITY.set((double) HeldItemOutlineSettings.getOpacity());
        HELD_ITEM_RED.set((double) HeldItemOutlineSettings.getRed());
        HELD_ITEM_GREEN.set((double) HeldItemOutlineSettings.getGreen());
        HELD_ITEM_BLUE.set((double) HeldItemOutlineSettings.getBlue());
        HELD_ITEM_SECONDARY_RED.set((double) HeldItemOutlineSettings.getSecondaryRed());
        HELD_ITEM_SECONDARY_GREEN.set((double) HeldItemOutlineSettings.getSecondaryGreen());
        HELD_ITEM_SECONDARY_BLUE.set((double) HeldItemOutlineSettings.getSecondaryBlue());
        HELD_ITEM_COLOR_SCROLL_SPEED.set((double) HeldItemOutlineSettings.getColorScrollSpeed());
        HELD_ITEM_DEPTH_WEIGHT.set((double) HeldItemOutlineSettings.getDepthWeight());
        HELD_ITEM_GLOW_STRENGTH.set((double) HeldItemOutlineSettings.getGlowStrength());
        HELD_ITEM_PULSE_SPEED.set((double) HeldItemOutlineSettings.getPulseSpeed());
        HELD_ITEM_PULSE_AMOUNT.set((double) HeldItemOutlineSettings.getPulseAmount());
    }

    private static void saveSpecLocked() {
        suppressOwnedConfigReload = true;
        try {
            SPEC.save();
        } finally {
            suppressOwnedConfigReload = false;
        }
    }
}
