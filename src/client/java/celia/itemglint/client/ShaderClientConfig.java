package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ShaderClientConfig implements ItemGlintConfigAccess {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final ShaderClientConfig INSTANCE = new ShaderClientConfig();
    private static final Object LOCK = new Object();

    private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve(ItemGlint.MOD_ID + "-client.json");
    private ItemGlintClientConfigData lastLoadedData = ItemGlintClientConfigData.defaults();

    private ShaderClientConfig() {
    }

    public static ShaderClientConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public Path configPath() {
        return this.configPath;
    }

    @Override
    public ItemGlintClientConfigData snapshot() {
        synchronized (LOCK) {
            return captureRuntime().copy();
        }
    }

    @Override
    public void load() {
        synchronized (LOCK) {
            ItemGlintClientConfigData loaded = readOrDefault();
            applyToRuntime(loaded);
            this.lastLoadedData = captureRuntime();
            writeConfig(this.lastLoadedData);
        }
    }

    @Override
    public void save() {
        synchronized (LOCK) {
            this.lastLoadedData = captureRuntime();
            writeConfig(this.lastLoadedData);
        }
    }

    @Override
    public void apply(ItemGlintClientConfigData data, boolean persist) {
        synchronized (LOCK) {
            applyToRuntime(data == null ? ItemGlintClientConfigData.defaults() : data);
            this.lastLoadedData = captureRuntime();
            if (persist) {
                writeConfig(this.lastLoadedData);
            }
        }
    }

    @Override
    public Screen createConfigScreen(Screen parent) {
        return new ShaderConfigScreen(parent);
    }

    public void syncShaderState(ShaderFeature shader) {
        if (shader == ShaderFeature.HELD_ITEM_OUTLINE) {
            save();
        }
    }

    public void syncHeldItemOutlineSettings() {
        save();
    }

    public void syncAllSettings() {
        save();
    }

    public ItemGlintClientConfigData lastLoadedData() {
        synchronized (LOCK) {
            return this.lastLoadedData.copy();
        }
    }

    private ItemGlintClientConfigData readOrDefault() {
        if (!Files.exists(this.configPath)) {
            return ItemGlintClientConfigData.defaults();
        }

        try (Reader reader = Files.newBufferedReader(this.configPath)) {
            ItemGlintClientConfigData data = GSON.fromJson(reader, ItemGlintClientConfigData.class);
            return data == null ? ItemGlintClientConfigData.defaults() : data;
        } catch (Exception exception) {
            ItemGlint.LOGGER.warn("Failed to read client config from {}. Falling back to defaults.", this.configPath, exception);
            return ItemGlintClientConfigData.defaults();
        }
    }

    private void writeConfig(ItemGlintClientConfigData data) {
        try {
            Files.createDirectories(this.configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(this.configPath)) {
                GSON.toJson(data, writer);
            }
        } catch (Exception exception) {
            ItemGlint.LOGGER.error("Failed to write client config to {}.", this.configPath, exception);
        }
    }

    private static ItemGlintClientConfigData captureRuntime() {
        ItemGlintClientConfigData data = new ItemGlintClientConfigData();
        data.heldItemOutlineEnabled = ShaderFeatureManager.isEnabled(ShaderFeature.HELD_ITEM_OUTLINE);
        data.mainHand = HeldItemOutlineSettings.isMainHandEnabled();
        data.offHand = HeldItemOutlineSettings.isOffHandEnabled();
        data.bloom = HeldItemOutlineSettings.isBloomEnabled();
        data.ruleSwitchDelayEnabled = HeldItemOutlineSettings.isRuleSwitchDelayEnabled();
        data.colorMode = HeldItemOutlineSettings.getColorMode().commandName();
        data.autoSampleSize = HeldItemOutlineSettings.getAutoSampleSize();
        data.autoSampleMaxColors = HeldItemOutlineSettings.getAutoSampleMaxColors();
        data.autoSampleSortMode = HeldItemOutlineSettings.getAutoSampleSortMode().commandName();
        data.bloomResolution = HeldItemOutlineSettings.getBloomResolution().commandName();
        data.bloomMaxPasses = HeldItemOutlineSettings.getBloomMaxPasses();
        data.bloomStrength = HeldItemOutlineSettings.getBloomStrength();
        data.bloomRadius = HeldItemOutlineSettings.getBloomRadius();
        data.width = HeldItemOutlineSettings.getWidth();
        data.softness = HeldItemOutlineSettings.getSoftness();
        data.alphaThreshold = HeldItemOutlineSettings.getAlphaThreshold();
        data.opacity = HeldItemOutlineSettings.getOpacity();
        data.red = HeldItemOutlineSettings.getRed();
        data.green = HeldItemOutlineSettings.getGreen();
        data.blue = HeldItemOutlineSettings.getBlue();
        data.secondaryRed = HeldItemOutlineSettings.getSecondaryRed();
        data.secondaryGreen = HeldItemOutlineSettings.getSecondaryGreen();
        data.secondaryBlue = HeldItemOutlineSettings.getSecondaryBlue();
        data.colorScrollSpeed = HeldItemOutlineSettings.getColorScrollSpeed();
        data.depthWeight = HeldItemOutlineSettings.getDepthWeight();
        data.glowStrength = HeldItemOutlineSettings.getGlowStrength();
        data.ruleMode = HeldItemRuleManager.getRuleMode().serializedName();
        data.whitelist = new ArrayList<>(HeldItemRuleManager.getWhitelistEntries());
        data.blacklist = new ArrayList<>(HeldItemRuleManager.getBlacklistEntries());
        data.customRules = new ArrayList<>();
        for (HeldItemRuleManager.CustomRule rule : HeldItemRuleManager.getCustomRules()) {
            data.customRules.add(HeldItemRuleManager.serializeCustomRule(rule));
        }
        return data;
    }

    private static void applyToRuntime(ItemGlintClientConfigData data) {
        ItemGlintClientConfigData safe = data == null ? ItemGlintClientConfigData.defaults() : data;
        ShaderFeatureManager.setEnabled(ShaderFeature.HELD_ITEM_OUTLINE, safe.heldItemOutlineEnabled);
        HeldItemOutlineSettings.setMainHandEnabled(safe.mainHand);
        HeldItemOutlineSettings.setOffHandEnabled(safe.offHand);
        HeldItemOutlineSettings.setBloomEnabled(safe.bloom);
        HeldItemOutlineSettings.setRuleSwitchDelayEnabled(safe.ruleSwitchDelayEnabled);
        HeldItemOutlineSettings.setColorMode(HeldItemOutlineSettings.ColorMode.byName(safe.colorMode));
        HeldItemOutlineSettings.setAutoSampleSize(safe.autoSampleSize);
        HeldItemOutlineSettings.setAutoSampleMaxColors(safe.autoSampleMaxColors);
        HeldItemOutlineSettings.setAutoSampleSortMode(HeldItemOutlineSettings.SampleColorSortMode.byName(safe.autoSampleSortMode));
        HeldItemOutlineSettings.setBloomResolution(HeldItemOutlineSettings.BloomResolution.byName(safe.bloomResolution));
        HeldItemOutlineSettings.setBloomMaxPasses(safe.bloomMaxPasses);
        HeldItemOutlineSettings.setBloomStrength(safe.bloomStrength);
        HeldItemOutlineSettings.setBloomRadius(safe.bloomRadius);
        HeldItemOutlineSettings.setWidth(safe.width);
        HeldItemOutlineSettings.setSoftness(safe.softness);
        HeldItemOutlineSettings.setAlphaThreshold(safe.alphaThreshold);
        HeldItemOutlineSettings.setOpacity(safe.opacity);
        HeldItemOutlineSettings.setRed(safe.red);
        HeldItemOutlineSettings.setGreen(safe.green);
        HeldItemOutlineSettings.setBlue(safe.blue);
        HeldItemOutlineSettings.setSecondaryRed(safe.secondaryRed);
        HeldItemOutlineSettings.setSecondaryGreen(safe.secondaryGreen);
        HeldItemOutlineSettings.setSecondaryBlue(safe.secondaryBlue);
        HeldItemOutlineSettings.setColorScrollSpeed(safe.colorScrollSpeed);
        HeldItemOutlineSettings.setDepthWeight(safe.depthWeight);
        HeldItemOutlineSettings.setGlowStrength(safe.glowStrength);
        HeldItemRuleManager.setRuleMode(HeldItemRuleManager.RuleMode.byName(safe.ruleMode));
        HeldItemRuleManager.setWhitelistEntries(safe.whitelist == null ? List.of() : safe.whitelist);
        HeldItemRuleManager.setBlacklistEntries(safe.blacklist == null ? List.of() : safe.blacklist);
        List<HeldItemRuleManager.CustomRule> customRules = new ArrayList<>();
        if (safe.customRules != null) {
            for (String serialized : safe.customRules) {
                if (serialized != null && !serialized.isBlank()) {
                    customRules.add(HeldItemRuleManager.deserializeCustomRule(serialized));
                }
            }
        }
        HeldItemRuleManager.setCustomRules(customRules);
    }
}
