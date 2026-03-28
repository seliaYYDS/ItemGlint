package celia.itemglint.client;

import java.util.ArrayList;
import java.util.List;

public final class ItemGlintClientConfigData {
    public boolean heldItemOutlineEnabled = true;
    public boolean mainHand = HeldItemOutlineSettings.DEFAULT_MAIN_HAND;
    public boolean offHand = HeldItemOutlineSettings.DEFAULT_OFF_HAND;
    public boolean bloom = HeldItemOutlineSettings.DEFAULT_BLOOM;
    public boolean ruleSwitchDelayEnabled = HeldItemOutlineSettings.DEFAULT_RULE_SWITCH_DELAY_ENABLED;
    public String colorMode = HeldItemOutlineSettings.DEFAULT_COLOR_MODE.commandName();
    public int autoSampleSize = HeldItemOutlineSettings.DEFAULT_AUTO_SAMPLE_SIZE;
    public int autoSampleMaxColors = HeldItemOutlineSettings.DEFAULT_AUTO_SAMPLE_MAX_COLORS;
    public String autoSampleSortMode = HeldItemOutlineSettings.DEFAULT_AUTO_SAMPLE_SORT_MODE.commandName();
    public String bloomResolution = HeldItemOutlineSettings.DEFAULT_BLOOM_RESOLUTION.commandName();
    public int bloomMaxPasses = HeldItemOutlineSettings.DEFAULT_BLOOM_MAX_PASSES;
    public float bloomStrength = HeldItemOutlineSettings.DEFAULT_BLOOM_STRENGTH;
    public float bloomRadius = HeldItemOutlineSettings.DEFAULT_BLOOM_RADIUS;
    public float width = HeldItemOutlineSettings.DEFAULT_WIDTH;
    public float softness = HeldItemOutlineSettings.DEFAULT_SOFTNESS;
    public float alphaThreshold = HeldItemOutlineSettings.DEFAULT_ALPHA_THRESHOLD;
    public float opacity = HeldItemOutlineSettings.DEFAULT_OPACITY;
    public float red = HeldItemOutlineSettings.DEFAULT_RED;
    public float green = HeldItemOutlineSettings.DEFAULT_GREEN;
    public float blue = HeldItemOutlineSettings.DEFAULT_BLUE;
    public float secondaryRed = HeldItemOutlineSettings.DEFAULT_SECONDARY_RED;
    public float secondaryGreen = HeldItemOutlineSettings.DEFAULT_SECONDARY_GREEN;
    public float secondaryBlue = HeldItemOutlineSettings.DEFAULT_SECONDARY_BLUE;
    public float colorScrollSpeed = HeldItemOutlineSettings.DEFAULT_COLOR_SCROLL_SPEED;
    public float depthWeight = HeldItemOutlineSettings.DEFAULT_DEPTH_WEIGHT;
    public float glowStrength = HeldItemOutlineSettings.DEFAULT_GLOW_STRENGTH;
    public String ruleMode = HeldItemRuleManager.RuleMode.ALL.serializedName();
    public List<String> whitelist = new ArrayList<>();
    public List<String> blacklist = new ArrayList<>();
    public List<String> customRules = new ArrayList<>();

    public static ItemGlintClientConfigData defaults() {
        return new ItemGlintClientConfigData();
    }

    public ItemGlintClientConfigData copy() {
        ItemGlintClientConfigData copy = new ItemGlintClientConfigData();
        copyFrom(this, copy);
        return copy;
    }

    public void copyFrom(ItemGlintClientConfigData source) {
        copyFrom(source, this);
    }

    private static void copyFrom(ItemGlintClientConfigData source, ItemGlintClientConfigData target) {
        target.heldItemOutlineEnabled = source != null && source.heldItemOutlineEnabled;
        target.mainHand = source == null ? HeldItemOutlineSettings.DEFAULT_MAIN_HAND : source.mainHand;
        target.offHand = source == null ? HeldItemOutlineSettings.DEFAULT_OFF_HAND : source.offHand;
        target.bloom = source == null ? HeldItemOutlineSettings.DEFAULT_BLOOM : source.bloom;
        target.ruleSwitchDelayEnabled = source == null ? HeldItemOutlineSettings.DEFAULT_RULE_SWITCH_DELAY_ENABLED : source.ruleSwitchDelayEnabled;
        target.colorMode = source == null ? HeldItemOutlineSettings.DEFAULT_COLOR_MODE.commandName() : source.colorMode;
        target.autoSampleSize = source == null ? HeldItemOutlineSettings.DEFAULT_AUTO_SAMPLE_SIZE : source.autoSampleSize;
        target.autoSampleMaxColors = source == null ? HeldItemOutlineSettings.DEFAULT_AUTO_SAMPLE_MAX_COLORS : source.autoSampleMaxColors;
        target.autoSampleSortMode = source == null ? HeldItemOutlineSettings.DEFAULT_AUTO_SAMPLE_SORT_MODE.commandName() : source.autoSampleSortMode;
        target.bloomResolution = source == null ? HeldItemOutlineSettings.DEFAULT_BLOOM_RESOLUTION.commandName() : source.bloomResolution;
        target.bloomMaxPasses = source == null ? HeldItemOutlineSettings.DEFAULT_BLOOM_MAX_PASSES : source.bloomMaxPasses;
        target.bloomStrength = source == null ? HeldItemOutlineSettings.DEFAULT_BLOOM_STRENGTH : source.bloomStrength;
        target.bloomRadius = source == null ? HeldItemOutlineSettings.DEFAULT_BLOOM_RADIUS : source.bloomRadius;
        target.width = source == null ? HeldItemOutlineSettings.DEFAULT_WIDTH : source.width;
        target.softness = source == null ? HeldItemOutlineSettings.DEFAULT_SOFTNESS : source.softness;
        target.alphaThreshold = source == null ? HeldItemOutlineSettings.DEFAULT_ALPHA_THRESHOLD : source.alphaThreshold;
        target.opacity = source == null ? HeldItemOutlineSettings.DEFAULT_OPACITY : source.opacity;
        target.red = source == null ? HeldItemOutlineSettings.DEFAULT_RED : source.red;
        target.green = source == null ? HeldItemOutlineSettings.DEFAULT_GREEN : source.green;
        target.blue = source == null ? HeldItemOutlineSettings.DEFAULT_BLUE : source.blue;
        target.secondaryRed = source == null ? HeldItemOutlineSettings.DEFAULT_SECONDARY_RED : source.secondaryRed;
        target.secondaryGreen = source == null ? HeldItemOutlineSettings.DEFAULT_SECONDARY_GREEN : source.secondaryGreen;
        target.secondaryBlue = source == null ? HeldItemOutlineSettings.DEFAULT_SECONDARY_BLUE : source.secondaryBlue;
        target.colorScrollSpeed = source == null ? HeldItemOutlineSettings.DEFAULT_COLOR_SCROLL_SPEED : source.colorScrollSpeed;
        target.depthWeight = source == null ? HeldItemOutlineSettings.DEFAULT_DEPTH_WEIGHT : source.depthWeight;
        target.glowStrength = source == null ? HeldItemOutlineSettings.DEFAULT_GLOW_STRENGTH : source.glowStrength;
        target.ruleMode = source == null ? HeldItemRuleManager.RuleMode.ALL.serializedName() : source.ruleMode;
        target.whitelist = source == null ? new ArrayList<>() : new ArrayList<>(safeList(source.whitelist));
        target.blacklist = source == null ? new ArrayList<>() : new ArrayList<>(safeList(source.blacklist));
        target.customRules = source == null ? new ArrayList<>() : new ArrayList<>(safeList(source.customRules));
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
