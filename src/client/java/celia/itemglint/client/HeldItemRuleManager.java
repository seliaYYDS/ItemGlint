package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class HeldItemRuleManager {
    private static final Gson GSON = new Gson();
    private static final Comparator<CustomRule> RULE_ORDER = Comparator.comparingInt(CustomRule::priority).reversed();

    private static RuleMode ruleMode = RuleMode.ALL;
    private static List<String> whitelistEntries = new ArrayList<>();
    private static List<String> blacklistEntries = new ArrayList<>();
    private static List<String> normalizedWhitelistEntries = new ArrayList<>();
    private static List<String> normalizedBlacklistEntries = new ArrayList<>();
    private static List<RuleSelector> compiledWhitelistSelectors = List.of();
    private static List<RuleSelector> compiledBlacklistSelectors = List.of();
    private static List<CustomRule> customRules = new ArrayList<>();
    private static List<CompiledCustomRule> compiledCustomRules = List.of();
    private static long revision;

    private HeldItemRuleManager() {
    }

    public static RuleMode getRuleMode() {
        return ruleMode;
    }

    public static long getRevision() {
        return revision;
    }

    public static void setRuleMode(RuleMode mode) {
        RuleMode nextMode = mode == null ? RuleMode.ALL : mode;
        if (ruleMode != nextMode) {
            ruleMode = nextMode;
            bumpRevision();
        }
    }

    public static List<String> getWhitelistEntries() {
        return List.copyOf(whitelistEntries);
    }

    public static void setWhitelistEntries(List<String> entries) {
        List<String> sanitizedEntries = sanitizeEntries(entries);
        if (whitelistEntries.equals(sanitizedEntries)) {
            return;
        }

        whitelistEntries = sanitizedEntries;
        normalizedWhitelistEntries = whitelistEntries.stream().map(HeldItemRuleManager::normalizeSelectorText).toList();
        compiledWhitelistSelectors = compileSelectorList(normalizedWhitelistEntries);
        bumpRevision();
    }

    public static List<String> getBlacklistEntries() {
        return List.copyOf(blacklistEntries);
    }

    public static void setBlacklistEntries(List<String> entries) {
        List<String> sanitizedEntries = sanitizeEntries(entries);
        if (blacklistEntries.equals(sanitizedEntries)) {
            return;
        }

        blacklistEntries = sanitizedEntries;
        normalizedBlacklistEntries = blacklistEntries.stream().map(HeldItemRuleManager::normalizeSelectorText).toList();
        compiledBlacklistSelectors = compileSelectorList(normalizedBlacklistEntries);
        bumpRevision();
    }

    public static List<CustomRule> getCustomRules() {
        return List.copyOf(customRules);
    }

    public static void setCustomRules(List<CustomRule> rules) {
        List<CustomRule> sanitized = new ArrayList<>();
        if (rules != null) {
            for (CustomRule rule : rules) {
                if (rule != null) {
                    sanitized.add(rule.sanitized());
                }
            }
        }
        sanitized.sort(RULE_ORDER);

        List<CompiledCustomRule> compiled = new ArrayList<>();
        for (CustomRule rule : sanitized) {
            compiled.add(new CompiledCustomRule(rule));
        }

        List<CustomRule> sanitizedCopy = List.copyOf(sanitized);
        List<CompiledCustomRule> compiledCopy = List.copyOf(compiled);
        if (customRules.equals(sanitizedCopy)) {
            return;
        }

        customRules = sanitizedCopy;
        compiledCustomRules = compiledCopy;
        bumpRevision();
    }

    public static StateSnapshot captureState() {
        return new StateSnapshot(ruleMode, whitelistEntries, blacklistEntries, customRules);
    }

    public static void applyState(StateSnapshot snapshot) {
        if (snapshot == null) {
            applyState(StateSnapshot.defaults());
            return;
        }
        setRuleMode(snapshot.ruleMode());
        setWhitelistEntries(snapshot.whitelistEntries());
        setBlacklistEntries(snapshot.blacklistEntries());
        setCustomRules(snapshot.customRules());
    }

    @Nullable
    public static HeldItemOutlineEffectProfile resolveProfile(ItemStack stack) {
        return resolveMatch(stack).profile();
    }

    public static ResolvedMatch resolveMatch(ItemStack stack) {
        return resolveMatch(stack, HeldItemOutlineEffectProfile.captureCurrent());
    }

    public static ResolvedMatch resolveMatch(ItemStack stack, HeldItemOutlineEffectProfile baseProfile) {
        if (stack == null || stack.isEmpty()) {
            return ResolvedMatch.NO_MATCH;
        }

        return switch (ruleMode) {
            case ALL -> new ResolvedMatch(baseProfile, "all");
            case WHITELIST -> matchesSelectorList(stack, compiledWhitelistSelectors)
                    ? new ResolvedMatch(baseProfile, "whitelist")
                    : new ResolvedMatch(null, "whitelist:none");
            case BLACKLIST -> matchesSelectorList(stack, compiledBlacklistSelectors)
                    ? new ResolvedMatch(null, "blacklist:blocked")
                    : new ResolvedMatch(baseProfile, "blacklist");
            case CUSTOM -> resolveCustomProfile(stack, baseProfile);
        };
    }

    public static String serializeCustomRule(CustomRule rule) {
        CustomRule sanitizedRule = rule == null ? CustomRule.EMPTY : rule.sanitized();
        JsonObject object = new JsonObject();
        object.addProperty("name", sanitizedRule.name());
        object.addProperty("priority", sanitizedRule.priority());
        object.addProperty("effectParams", sanitizedRule.effectParams());

        JsonArray filters = new JsonArray();
        for (RuleFilter filter : sanitizedRule.filters()) {
            JsonObject filterObject = new JsonObject();
            filterObject.addProperty("selector", filter.selector());
            filterObject.addProperty("nbtPath", filter.nbtPath());
            filterObject.addProperty("nbtValue", filter.nbtValue());
            filterObject.addProperty("matchMode", filter.matchMode().serializedName());
            filters.add(filterObject);
        }
        object.add("filters", filters);
        return GSON.toJson(object);
    }

    public static CustomRule deserializeCustomRule(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return CustomRule.EMPTY;
        }
        try {
            JsonObject object = JsonParser.parseString(serialized).getAsJsonObject();
            if (object.has("filters") && object.get("filters").isJsonArray()) {
                List<RuleFilter> filters = new ArrayList<>();
                for (JsonElement element : object.getAsJsonArray("filters")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject filterObject = element.getAsJsonObject();
                    filters.add(new RuleFilter(
                            readString(filterObject, "selector"),
                            readString(filterObject, "nbtPath"),
                            readString(filterObject, "nbtValue"),
                            NbtMatchMode.byName(readString(filterObject, "matchMode"))
                    ));
                }
                return new CustomRule(
                        readString(object, "name"),
                        readInt(object, "priority", 0),
                        filters,
                        readString(object, "effectParams")
                ).sanitized();
            }

            return new CustomRule(
                    "",
                    0,
                    List.of(new RuleFilter(
                            readString(object, "selector"),
                            readString(object, "nbtPath"),
                            readString(object, "nbtValue"),
                            NbtMatchMode.EQUALS
                    )),
                    readString(object, "effectParams")
            ).sanitized();
        } catch (Exception exception) {
            ItemGlint.LOGGER.warn("[HeldItemOutline] Failed to parse custom rule config entry {}", serialized, exception);
            return CustomRule.EMPTY;
        }
    }

    private static String readString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static int readInt(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Nullable
    private static ResolvedMatch resolveCustomProfile(ItemStack stack, HeldItemOutlineEffectProfile baseProfile) {
        for (CompiledCustomRule customRule : compiledCustomRules) {
            if (customRule.matches(stack)) {
                return new ResolvedMatch(customRule.apply(baseProfile), customRule.ruleKey());
            }
        }
        return new ResolvedMatch(null, "custom:none");
    }

    private static boolean matchesSelectorList(ItemStack stack, List<RuleSelector> selectors) {
        for (RuleSelector selector : selectors) {
            if (selector.matches(stack)) {
                return true;
            }
        }
        return false;
    }

    private static List<RuleSelector> compileSelectorList(List<String> normalizedEntries) {
        if (normalizedEntries.isEmpty()) {
            return List.of();
        }

        List<RuleSelector> selectors = new ArrayList<>(normalizedEntries.size());
        for (String entry : normalizedEntries) {
            RuleSelector selector = RuleSelector.parse(entry, false);
            if (selector.selectorType() != SelectorType.INVALID) {
                selectors.add(selector);
            }
        }
        return List.copyOf(selectors);
    }

    private static void bumpRevision() {
        revision++;
    }

    private static List<String> sanitizeEntries(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> sanitized = new ArrayList<>();
        for (String entry : entries) {
            String normalized = normalizeSelectorText(entry);
            if (!normalized.isEmpty()) {
                sanitized.add(normalized);
            }
        }
        return sanitized;
    }

    private static String normalizeSelectorText(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static Tag resolveNbtPath(@Nullable CompoundTag root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }

        Tag current = root;
        for (String rawSegment : path.split("\\.")) {
            String segment = rawSegment.trim();
            if (segment.isEmpty()) {
                return null;
            }

            int bracketIndex = segment.indexOf('[');
            String key = bracketIndex >= 0 ? segment.substring(0, bracketIndex) : segment;
            if (!key.isEmpty()) {
                if (!(current instanceof CompoundTag compoundTag) || !compoundTag.contains(key)) {
                    return null;
                }
                current = compoundTag.get(key);
            }

            while (bracketIndex >= 0) {
                int endIndex = segment.indexOf(']', bracketIndex);
                if (endIndex <= bracketIndex + 1) {
                    return null;
                }
                if (!(current instanceof ListTag listTag)) {
                    return null;
                }
                int listIndex;
                try {
                    listIndex = Integer.parseInt(segment.substring(bracketIndex + 1, endIndex));
                } catch (NumberFormatException exception) {
                    return null;
                }
                if (listIndex < 0 || listIndex >= listTag.size()) {
                    return null;
                }
                current = listTag.get(listIndex);
                bracketIndex = segment.indexOf('[', endIndex);
            }
        }
        return current;
    }

    @Nullable
    private static Tag resolveNbtPathWithRedirects(ItemStack stack, String nbtPath) {
        Tag encodedTag = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack).result().orElseGet(CompoundTag::new);
        CompoundTag root = encodedTag instanceof CompoundTag compoundTag ? compoundTag : new CompoundTag();
        Tag direct = resolveNbtPath(root, nbtPath);
        if (direct != null) {
            return direct;
        }
        if (!nbtPath.startsWith("tag.")) {
            Tag redirected = resolveNbtPath(root, "tag." + nbtPath);
            if (redirected != null) {
                return redirected;
            }
        }
        return null;
    }

    private static boolean matchesNbt(ItemStack stack, String nbtPath, String nbtValue, NbtMatchMode matchMode) {
        if (nbtPath.isBlank()) {
            return true;
        }

        Tag tag = resolveNbtPathWithRedirects(stack, nbtPath);
        if (tag == null) {
            return false;
        }

        if (nbtValue.isBlank()) {
            return true;
        }

        String expected = nbtValue.trim();
        return matchMode.matches(tag, expected);
    }

    public enum RuleMode {
        ALL("all"),
        WHITELIST("whitelist"),
        BLACKLIST("blacklist"),
        CUSTOM("custom");

        private final String serializedName;

        RuleMode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public Component label() {
            return Component.translatable("screen.itemglint.rule_mode." + this.serializedName);
        }

        public static RuleMode byName(String name) {
            if (name == null) {
                return ALL;
            }
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            for (RuleMode mode : values()) {
                if (mode.serializedName.equals(normalized)) {
                    return mode;
                }
            }
            return ALL;
        }
    }

    public record StateSnapshot(RuleMode ruleMode, List<String> whitelistEntries, List<String> blacklistEntries, List<CustomRule> customRules) {
        public StateSnapshot {
            ruleMode = ruleMode == null ? RuleMode.ALL : ruleMode;
            whitelistEntries = whitelistEntries == null ? List.of() : List.copyOf(whitelistEntries);
            blacklistEntries = blacklistEntries == null ? List.of() : List.copyOf(blacklistEntries);
            customRules = customRules == null ? List.of() : List.copyOf(customRules);
        }

        public static StateSnapshot defaults() {
            return new StateSnapshot(RuleMode.ALL, List.of(), List.of(), List.of());
        }
    }

    public enum NbtMatchMode {
        EQUALS("equals"),
        NOT_EQUALS("not_equals"),
        GREATER_THAN("greater_than"),
        LESS_THAN("less_than"),
        GREATER_OR_EQUAL("greater_or_equal"),
        LESS_OR_EQUAL("less_or_equal"),
        CONTAINS("contains");

        private final String serializedName;

        NbtMatchMode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return this.serializedName;
        }

        public Component label() {
            return Component.translatable("screen.itemglint.rule_editor.match_mode." + this.serializedName);
        }

        public boolean matches(Tag tag, String expected) {
            return switch (this) {
                case EQUALS -> matchesEquals(tag, expected);
                case NOT_EQUALS -> !matchesEquals(tag, expected);
                case GREATER_THAN -> compareNumeric(tag, expected, result -> result > 0);
                case LESS_THAN -> compareNumeric(tag, expected, result -> result < 0);
                case GREATER_OR_EQUAL -> compareNumeric(tag, expected, result -> result >= 0);
                case LESS_OR_EQUAL -> compareNumeric(tag, expected, result -> result <= 0);
                case CONTAINS -> tag.asString().orElse("").contains(expected) || Objects.toString(tag, "").contains(expected);
            };
        }

        public static NbtMatchMode byName(String name) {
            if (name != null) {
                String normalized = name.trim().toLowerCase(Locale.ROOT);
                for (NbtMatchMode value : values()) {
                    if (value.serializedName.equals(normalized)) {
                        return value;
                    }
                }
            }
            return EQUALS;
        }

        private static boolean matchesEquals(Tag tag, String expected) {
            if (tag instanceof NumericTag numericTag) {
                return numericTag.box().toString().equals(expected)
                        || tag.asString().orElse("").equalsIgnoreCase(expected);
            }
            return tag.asString().orElse("").equals(expected) || Objects.equals(tag.toString(), expected);
        }

        private static boolean compareNumeric(Tag tag, String expected, java.util.function.IntPredicate predicate) {
            if (!(tag instanceof NumericTag numericTag)) {
                return false;
            }
            try {
                double actualValue = numericTag.doubleValue();
                double expectedValue = Double.parseDouble(expected);
                return predicate.test(Double.compare(actualValue, expectedValue));
            } catch (NumberFormatException exception) {
                return false;
            }
        }
    }

    public record ResolvedMatch(@Nullable HeldItemOutlineEffectProfile profile, String ruleKey) {
        public static final ResolvedMatch NO_MATCH = new ResolvedMatch(null, "none");
    }

    public record RuleFilter(String selector, String nbtPath, String nbtValue, NbtMatchMode matchMode) {
        public static final RuleFilter EMPTY = new RuleFilter("", "", "", NbtMatchMode.EQUALS);

        public RuleFilter sanitized() {
            return new RuleFilter(
                    normalizeSelectorText(selector),
                    nbtPath == null ? "" : nbtPath.trim(),
                    nbtValue == null ? "" : nbtValue.trim(),
                    matchMode == null ? NbtMatchMode.EQUALS : matchMode
            );
        }

        public String summary() {
            String selectorText = selector == null || selector.isBlank() ? "*" : selector;
            String nbtText = nbtPath == null || nbtPath.isBlank() ? "any" : nbtPath + " " + matchMode.serializedName() + " " + (nbtValue == null ? "" : nbtValue);
            return selectorText + " | nbt=" + nbtText;
        }
    }

    public record CustomRule(String name, int priority, List<RuleFilter> filters, String effectParams) {
        public static final CustomRule EMPTY = new CustomRule("", 0, List.of(RuleFilter.EMPTY), "");

        public CustomRule sanitized() {
            List<RuleFilter> sanitizedFilters = new ArrayList<>();
            if (filters != null) {
                for (RuleFilter filter : filters) {
                    if (filter != null) {
                        sanitizedFilters.add(filter.sanitized());
                    }
                }
            }
            if (sanitizedFilters.isEmpty()) {
                sanitizedFilters.add(RuleFilter.EMPTY);
            }
            return new CustomRule(
                    name == null ? "" : name.trim(),
                    priority,
                    List.copyOf(sanitizedFilters),
                    effectParams == null ? "" : effectParams.trim()
            );
        }

        public String displayName() {
            return name == null || name.isBlank() ? "Unnamed Rule" : name;
        }

        public String summary() {
            StringBuilder builder = new StringBuilder();
            builder.append("P").append(priority).append(" | ");
            if (filters == null || filters.isEmpty()) {
                builder.append("*");
            } else if (filters.size() == 1) {
                builder.append(filters.get(0).summary());
            } else {
                builder.append(filters.size()).append(" filters");
            }
            if (effectParams != null && !effectParams.isBlank()) {
                builder.append(" | ").append(effectParams);
            }
            return builder.toString();
        }
    }

    private record RuleSelector(SelectorType selectorType, @Nullable Identifier itemId, @Nullable TagKey<Item> tagKey,
                                @Nullable String modId) {
        private static final RuleSelector ANY = new RuleSelector(SelectorType.ANY, null, null, null);

        private static RuleSelector parse(String selectorText, boolean allowAny) {
            String normalized = normalizeSelectorText(selectorText);
            if (normalized.isEmpty()) {
                return allowAny ? ANY : new RuleSelector(SelectorType.INVALID, null, null, null);
            }

            if (normalized.startsWith("#")) {
                Identifier tagId = Identifier.tryParse(normalized.substring(1));
                return tagId == null
                        ? new RuleSelector(SelectorType.INVALID, null, null, null)
                        : new RuleSelector(SelectorType.ITEM_TAG, null, TagKey.create(Registries.ITEM, tagId), null);
            }

            if (normalized.startsWith("@")) {
                String namespace = normalized.substring(1).trim();
                return namespace.isEmpty()
                        ? new RuleSelector(SelectorType.INVALID, null, null, null)
                        : new RuleSelector(SelectorType.MOD_ID, null, null, namespace);
            }

            Identifier itemId = Identifier.tryParse(normalized);
            return itemId == null
                    ? new RuleSelector(SelectorType.INVALID, null, null, null)
                    : new RuleSelector(SelectorType.ITEM_ID, itemId, null, null);
        }

        private boolean matches(ItemStack stack) {
            return switch (selectorType) {
                case ANY -> true;
                case ITEM_ID -> Objects.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()), itemId);
                case ITEM_TAG -> tagKey != null && stack.is(tagKey);
                case MOD_ID -> modId != null && modId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
                case INVALID -> false;
            };
        }
    }

    private enum SelectorType {
        ANY,
        ITEM_ID,
        ITEM_TAG,
        MOD_ID,
        INVALID
    }

    private record CompiledRuleFilter(RuleSelector selector, String nbtPath, String nbtValue, NbtMatchMode matchMode) {
        private CompiledRuleFilter(RuleFilter filter) {
            this(RuleSelector.parse(filter.selector(), true), filter.nbtPath(), filter.nbtValue(), filter.matchMode());
        }

        private boolean matches(ItemStack stack) {
            return this.selector.matches(stack) && matchesNbt(stack, this.nbtPath, this.nbtValue, this.matchMode);
        }
    }

    private record CompiledCustomRule(CustomRule source, List<CompiledRuleFilter> filters, EffectOverrides overrides, String ruleKey) {
        private CompiledCustomRule(CustomRule source) {
            this(
                    source,
                    source.filters().stream().map(CompiledRuleFilter::new).toList(),
                    EffectOverrides.parse(source.effectParams()),
                    buildRuleKey(source)
            );
        }

        private boolean matches(ItemStack stack) {
            for (CompiledRuleFilter filter : this.filters) {
                if (!filter.matches(stack)) {
                    return false;
                }
            }
            return true;
        }

        private HeldItemOutlineEffectProfile apply(HeldItemOutlineEffectProfile baseProfile) {
            return overrides.apply(baseProfile);
        }

        private static String buildRuleKey(CustomRule source) {
            return "custom:" + Integer.toHexString(source.hashCode());
        }
    }

    private static final class EffectOverrides {
        @Nullable
        private HeldItemOutlineSettings.ColorMode colorMode;
        @Nullable
        private HeldItemOutlineSettings.BloomResolution bloomResolution;
        @Nullable
        private Boolean bloom;
        @Nullable
        private Integer bloomMaxPasses;
        @Nullable
        private Float bloomStrength;
        @Nullable
        private Float bloomRadius;
        @Nullable
        private Float width;
        @Nullable
        private Float softness;
        @Nullable
        private Float alphaThreshold;
        @Nullable
        private Float opacity;
        @Nullable
        private Float red;
        @Nullable
        private Float green;
        @Nullable
        private Float blue;
        @Nullable
        private Float secondaryRed;
        @Nullable
        private Float secondaryGreen;
        @Nullable
        private Float secondaryBlue;
        @Nullable
        private Float colorScrollSpeed;
        @Nullable
        private Float depthWeight;
        @Nullable
        private Float glowStrength;

        private HeldItemOutlineEffectProfile apply(HeldItemOutlineEffectProfile baseProfile) {
            return new HeldItemOutlineEffectProfile(
                    colorMode != null ? colorMode : baseProfile.colorMode(),
                    baseProfile.autoSampleSize(),
                    baseProfile.autoSampleMaxColors(),
                    baseProfile.autoSampleSortMode(),
                    bloomResolution != null ? bloomResolution : baseProfile.bloomResolution(),
                    bloom != null ? bloom : baseProfile.bloom(),
                    bloomMaxPasses != null ? bloomMaxPasses : baseProfile.bloomMaxPasses(),
                    bloomStrength != null ? bloomStrength : baseProfile.bloomStrength(),
                    bloomRadius != null ? bloomRadius : baseProfile.bloomRadius(),
                    width != null ? width : baseProfile.width(),
                    softness != null ? softness : baseProfile.softness(),
                    alphaThreshold != null ? alphaThreshold : baseProfile.alphaThreshold(),
                    opacity != null ? opacity : baseProfile.opacity(),
                    red != null ? red : baseProfile.red(),
                    green != null ? green : baseProfile.green(),
                    blue != null ? blue : baseProfile.blue(),
                    secondaryRed != null ? secondaryRed : baseProfile.secondaryRed(),
                    secondaryGreen != null ? secondaryGreen : baseProfile.secondaryGreen(),
                    secondaryBlue != null ? secondaryBlue : baseProfile.secondaryBlue(),
                    colorScrollSpeed != null ? colorScrollSpeed : baseProfile.colorScrollSpeed(),
                    depthWeight != null ? depthWeight : baseProfile.depthWeight(),
                    glowStrength != null ? glowStrength : baseProfile.glowStrength()
            );
        }

        private static EffectOverrides parse(String effectParams) {
            EffectOverrides overrides = new EffectOverrides();
            if (effectParams == null || effectParams.isBlank()) {
                return overrides;
            }

            String[] tokens = effectParams.split("[;,]");
            for (String token : tokens) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int delimiter = trimmed.indexOf('=');
                if (delimiter <= 0 || delimiter >= trimmed.length() - 1) {
                    continue;
                }
                String key = trimmed.substring(0, delimiter).trim().toLowerCase(Locale.ROOT);
                String value = trimmed.substring(delimiter + 1).trim();
                overrides.applyToken(key, value);
            }
            return overrides;
        }

        private void applyToken(String key, String value) {
            try {
                switch (key) {
                    case "color_mode", "mode" -> this.colorMode = HeldItemOutlineSettings.ColorMode.byName(value);
                    case "bloom_resolution" -> this.bloomResolution = HeldItemOutlineSettings.BloomResolution.byName(value);
                    case "bloom" -> this.bloom = Boolean.parseBoolean(value);
                    case "bloom_max_passes" -> this.bloomMaxPasses = Mth.clamp(Integer.parseInt(value), HeldItemOutlineSettings.MIN_BLOOM_MAX_PASSES, HeldItemOutlineSettings.MAX_BLOOM_MAX_PASSES);
                    case "bloom_strength" -> this.bloomStrength = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_BLOOM_STRENGTH, HeldItemOutlineSettings.MAX_BLOOM_STRENGTH);
                    case "bloom_radius" -> this.bloomRadius = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_BLOOM_RADIUS, HeldItemOutlineSettings.MAX_BLOOM_RADIUS);
                    case "width" -> this.width = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_WIDTH, HeldItemOutlineSettings.MAX_WIDTH);
                    case "softness" -> this.softness = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_SOFTNESS, HeldItemOutlineSettings.MAX_SOFTNESS);
                    case "alpha_threshold" -> this.alphaThreshold = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_ALPHA_THRESHOLD, HeldItemOutlineSettings.MAX_ALPHA_THRESHOLD);
                    case "opacity" -> this.opacity = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_OPACITY, HeldItemOutlineSettings.MAX_OPACITY);
                    case "primary", "primary_color" -> applyPrimaryColor(value);
                    case "secondary", "secondary_color" -> applySecondaryColor(value);
                    case "red" -> this.red = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR);
                    case "green" -> this.green = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR);
                    case "blue" -> this.blue = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR);
                    case "secondary_red" -> this.secondaryRed = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR);
                    case "secondary_green" -> this.secondaryGreen = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR);
                    case "secondary_blue" -> this.secondaryBlue = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR);
                    case "color_scroll_speed" -> this.colorScrollSpeed = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_COLOR_SCROLL_SPEED, HeldItemOutlineSettings.MAX_COLOR_SCROLL_SPEED);
                    case "depth_weight" -> this.depthWeight = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_DEPTH_WEIGHT, HeldItemOutlineSettings.MAX_DEPTH_WEIGHT);
                    case "glow_strength" -> this.glowStrength = Mth.clamp(Float.parseFloat(value), HeldItemOutlineSettings.MIN_GLOW_STRENGTH, HeldItemOutlineSettings.MAX_GLOW_STRENGTH);
                    default -> {
                    }
                }
            } catch (Exception exception) {
                ItemGlint.LOGGER.warn("[HeldItemOutline] Ignoring invalid rule override {}={}", key, value);
            }
        }

        private void applyPrimaryColor(String value) {
            float[] color = parseHexColor(value);
            if (color == null) {
                return;
            }
            this.red = color[0];
            this.green = color[1];
            this.blue = color[2];
        }

        private void applySecondaryColor(String value) {
            float[] color = parseHexColor(value);
            if (color == null) {
                return;
            }
            this.secondaryRed = color[0];
            this.secondaryGreen = color[1];
            this.secondaryBlue = color[2];
        }

        @Nullable
        private static float[] parseHexColor(String value) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.startsWith("#")) {
                normalized = normalized.substring(1);
            }
            if (normalized.length() != 6) {
                return null;
            }
            try {
                int rgb = Integer.parseInt(normalized, 16);
                return new float[]{
                        ((rgb >> 16) & 255) / 255.0F,
                        ((rgb >> 8) & 255) / 255.0F,
                        (rgb & 255) / 255.0F
                };
            } catch (NumberFormatException exception) {
                return null;
            }
        }
    }
}
