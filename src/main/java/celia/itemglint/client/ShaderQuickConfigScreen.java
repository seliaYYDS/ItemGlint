package celia.itemglint.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public class ShaderQuickConfigScreen extends Screen {
    private static final int MAIN_WIDTH = 500;
    private static final int SIDEBAR_WIDTH = 168;
    private static final int MIN_SIDEBAR_WIDTH = 92;
    private static final int PANEL_GAP = 12;
    private static final int PANEL_PADDING = 8;
    private static final int ROW_HEIGHT = 24;
    private static final int LABEL_WIDTH = 172;
    private static final int COLOR_PICKER_SIZE = 176;
    private static final int MIN_COLOR_PICKER_SIZE = 96;
    private static final int COLOR_BAR_WIDTH = 18;
    private static final int COLOR_CONTROL_LABEL_WIDTH = 70;
    private static final int COLOR_CONTROL_WIDTH = 180;
    private static final int FRAME_MARGIN = 12;
    private static final int SCROLL_HORIZONTAL_PADDING = 6;

    private final Screen parent;
    private final SettingsSnapshot snapshot;
    private final List<LabelLine> labels = new ArrayList<>();
    private final List<AbstractWidget> bloomDependentWidgets = new ArrayList<>();
    private final List<AbstractWidget> autoSampleWidgets = new ArrayList<>();
    private final List<RowTooltipArea> rowTooltips = new ArrayList<>();

    private Page currentPage = Page.OVERVIEW;
    private ColorTarget activeColorTarget = ColorTarget.PRIMARY;

    private boolean featureEnabled;
    private boolean mainHand;
    private boolean offHand;
    private boolean bloom;
    private boolean ruleSwitchDelayEnabled;
    private HeldItemOutlineSettings.ColorMode colorMode;
    private int autoSampleSize;
    private int autoSampleMaxColors;
    private HeldItemOutlineSettings.SampleColorSortMode autoSampleSortMode;
    private HeldItemOutlineSettings.BloomResolution bloomResolution;
    private int bloomMaxPasses;
    private float bloomStrength;
    private float bloomRadius;
    private float widthValue;
    private float softness;
    private float alphaThreshold;
    private float opacity;
    private float colorScrollSpeed;
    private float depthWeight;
    private float glowStrength;
    private float red;
    private float green;
    private float blue;
    private float secondaryRed;
    private float secondaryGreen;
    private float secondaryBlue;
    private HeldItemRuleManager.RuleMode ruleMode;
    private List<String> whitelistEntries = new ArrayList<>();
    private List<String> blacklistEntries = new ArrayList<>();
    private List<HeldItemRuleManager.CustomRule> customRules = new ArrayList<>();

    private FloatSlider redSlider;
    private FloatSlider greenSlider;
    private FloatSlider blueSlider;
    private ColorPickerBoxWidget colorPicker;
    private BrightnessBarWidget brightnessBar;
    private EditBox hexField;
    private boolean syncingColorControls;
    private int pageScrollOffset;
    private int maxPageScrollOffset;

    public ShaderQuickConfigScreen(Screen parent) {
        super(Component.translatable("screen.itemglint.quick_config.title"));
        this.parent = parent;
        this.snapshot = SettingsSnapshot.capture();
        loadCurrentValues();
    }

    @Override
    protected void init() {
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        this.labels.clear();
        this.bloomDependentWidgets.clear();
        this.autoSampleWidgets.clear();
        this.rowTooltips.clear();
        this.redSlider = null;
        this.greenSlider = null;
        this.blueSlider = null;
        this.colorPicker = null;
        this.brightnessBar = null;
        this.hexField = null;

        addNavigation();

        this.pageScrollOffset = Mth.clamp(this.pageScrollOffset, 0, getMaxPageScrollOffset());
        this.maxPageScrollOffset = getMaxPageScrollOffset();

        int contentLeft = getMainContentLeft();
        int contentTop = getMainContentTop() - this.pageScrollOffset;
        switch (this.currentPage) {
            case OVERVIEW -> buildOverviewPage(contentLeft, contentTop);
            case SHAPE -> buildShapePage(contentLeft, contentTop);
            case EFFECTS -> buildEffectsPage(contentLeft, contentTop);
            case COLORS -> buildColorsPage(contentLeft, contentTop);
        }

        int buttonY = this.height - 29;
        int buttonGap = 8;
        int buttonWidth = Math.max(72, Math.min(108, (getMainContentWidth() - buttonGap * 2) / 3));
        int buttonsTotalWidth = buttonWidth * 3 + buttonGap * 2;
        int buttonsLeft = contentLeft + Math.max(0, (getMainContentWidth() - buttonsTotalWidth) / 2);
        this.addRenderableOnly(new FooterMaskWidget(getFrameLeft() - PANEL_PADDING - SCROLL_HORIZONTAL_PADDING, getFooterMaskTop(), getSidebarRight() - getFrameLeft() + PANEL_PADDING * 2 + SCROLL_HORIZONTAL_PADDING * 2, this.height - getFooterMaskTop()));
        this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.shader_config.reset"), button -> {
                    resetToDefaults();
                    rebuildWidgets();
                }).bounds(buttonsLeft, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> cancelAndClose())
                .bounds(buttonsLeft + buttonWidth + buttonGap, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> saveAndClose())
                .bounds(buttonsLeft + (buttonWidth + buttonGap) * 2, buttonY, buttonWidth, 20).build());

        updateBloomDependentState();
        updateAutoSampleState();
        syncActiveColorControls();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        renderFrame(graphics);
        graphics.enableScissor(getFrameLeft() - PANEL_PADDING - SCROLL_HORIZONTAL_PADDING, 44, getSidebarRight() + PANEL_PADDING + SCROLL_HORIZONTAL_PADDING, this.height);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderStoredText(graphics);
        renderSidebarSummary(graphics);
        graphics.disableScissor();

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        graphics.drawString(this.font, Component.translatable(this.currentPage.titleKey), getMainContentLeft(), 36, 0xA7E7FF, false);
        graphics.drawString(this.font, Component.translatable("screen.itemglint.quick_config.pages"), getSidebarLeft() + getSidebarInset(), 52, 0xAAB3C3, false);
        renderHoveredTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        cancelAndClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isInHiddenFrameArea(mouseX, mouseY) && !isMouseOverFooterButtons(mouseX, mouseY)) {
            return true;
        }
        if (dispatchMouseClickTopmost(mouseX, mouseY, button)) {
            return true;
        }
        return isInFooterMaskArea(mouseX, mouseY) && !isMouseOverFooterButtons(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (scrollY != 0.0D && isMouseOverScrollableArea(mouseX, mouseY) && this.maxPageScrollOffset > 0) {
            int direction = scrollY > 0.0D ? -1 : 1;
            int nextOffset = Mth.clamp(this.pageScrollOffset + direction * ROW_HEIGHT, 0, this.maxPageScrollOffset);
            if (nextOffset != this.pageScrollOffset) {
                this.pageScrollOffset = nextOffset;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    private void renderFrame(GuiGraphics graphics) {
        int frameLeft = getFrameLeft();
        int frameTop = 44;
        int frameRight = getSidebarRight();
        int frameBottom = this.height - 38;

        graphics.fill(frameLeft - PANEL_PADDING, frameTop, frameRight + PANEL_PADDING, frameBottom, 0xC010131A);
        graphics.fill(getMainLeft(), frameTop + 6, getMainRight(), frameBottom - 6, 0xB0161C25);
        graphics.fill(getSidebarLeft(), frameTop + 6, getSidebarRight(), frameBottom - 6, 0xB0121820);
        graphics.renderOutline(frameLeft - PANEL_PADDING, frameTop, frameRight - frameLeft + PANEL_PADDING * 2, frameBottom - frameTop, 0xFF3A4558);
        graphics.fill(getSidebarLeft() - PANEL_GAP / 2, frameTop + 14, getSidebarLeft() - PANEL_GAP / 2 + 1, frameBottom - 14, 0xFF2A3342);
    }

    private void renderStoredText(GuiGraphics graphics) {
        for (LabelLine label : this.labels) {
            String plain = this.font.plainSubstrByWidth(label.text.getString(), label.maxWidth);
            int drawX = label.rightAligned ? label.x + label.maxWidth - this.font.width(plain) : label.x;
            graphics.drawString(this.font, plain, drawX, label.y, label.color, false);
        }

    }

    private void renderHoveredTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int i = this.rowTooltips.size() - 1; i >= 0; i--) {
            RowTooltipArea area = this.rowTooltips.get(i);
            if (area.contains(mouseX, mouseY)) {
                graphics.renderTooltip(this.font, this.font.split(area.tooltip(), 220), mouseX, mouseY);
                return;
            }
        }
    }

    private void renderSidebarSummary(GuiGraphics graphics) {
        int x = getSidebarLeft() + getSidebarInset();
        int swatchX = getSidebarRight() - getSidebarInset() - 16;
        int y = 178;

        graphics.drawString(this.font, Component.translatable("screen.itemglint.quick_config.summary.title"), x, y, 0xA7E7FF, false);
        y += 16;
        graphics.drawString(this.font, Component.translatable("screen.itemglint.quick_config.summary.status", onOffLabel(this.featureEnabled)), x, y, 0xE0E6EE, false);
        y += 12;
        graphics.drawString(this.font, Component.translatable("screen.itemglint.quick_config.summary.hands", describeHands()), x, y, 0xE0E6EE, false);
        y += 12;
        graphics.drawString(this.font, Component.translatable("screen.itemglint.quick_config.summary.mode", colorModeLabel(this.colorMode)), x, y, 0xE0E6EE, false);
        y += 12;
        graphics.drawString(this.font, Component.translatable("screen.itemglint.quick_config.summary.bloom", onOffLabel(this.bloom)), x, y, 0xE0E6EE, false);
        y += 12;
        graphics.drawString(this.font, Component.translatable("screen.itemglint.quick_config.summary.rules", this.ruleMode.label()), x, y, 0xE0E6EE, false);
        y += 18;
        graphics.drawString(this.font, Component.translatable("screen.itemglint.quick_config.summary.hotkey"), x, y, 0xAAB3C3, false);
        y += 12;
        graphics.drawString(this.font, ShaderKeyMappings.OPEN_QUICK_CONFIG.getTranslatedKeyMessage(), x, y, 0xFFFFFF, false);
        y += 22;

        graphics.drawString(this.font, Component.translatable("screen.itemglint.quick_config.primary_color"), x, y, 0xA7E7FF, false);
        drawSwatch(graphics, swatchX, y - 1, getColor(ColorTarget.PRIMARY), 16, 10);
        y += 16;
        graphics.drawString(this.font, Component.translatable("screen.itemglint.quick_config.secondary_color"), x, y, 0xFFADD9, false);
        drawSwatch(graphics, swatchX, y - 1, getColor(ColorTarget.SECONDARY), 16, 10);
        y += 18;

        Component activeTarget = this.activeColorTarget.label();
        graphics.drawString(this.font, Component.translatable("screen.itemglint.quick_config.summary.active_target", activeTarget), x, y, 0xAAB3C3, false);
        y += 12;
        graphics.drawString(this.font, Component.literal(toHex(getColor(this.activeColorTarget))), x, y, 0xFFFFFF, false);
    }

    private void addNavigation() {
        int x = getSidebarLeft() + getSidebarInset();
        int y = 74;
        int buttonWidth = getNavButtonWidth();
        for (Page page : Page.values()) {
            Button button = Button.builder(Component.translatable(page.titleKey), ignored -> {
                        this.currentPage = page;
                        this.pageScrollOffset = 0;
                        this.maxPageScrollOffset = getMaxPageScrollOffset();
                        rebuildWidgets();
                    }).bounds(x, y, buttonWidth, 20).build();
            button.active = page != this.currentPage;
            this.addRenderableWidget(button);
            y += 24;
        }
    }

    private void buildOverviewPage(int left, int top) {
        addSection(Component.translatable("screen.itemglint.quick_config.section.general"), left, top - 18);
        int row = 0;

        addBooleanRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.quick_config.option.enabled"), this.featureEnabled, value -> this.featureEnabled = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.enabled"));
        addBooleanRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.main_hand"), this.mainHand, value -> this.mainHand = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.main_hand"));
        addBooleanRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.off_hand"), this.offHand, value -> this.offHand = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.off_hand"));
        addBooleanRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.bloom"), this.bloom, value -> {
            this.bloom = value;
            updateBloomDependentState();
        }, Component.translatable("screen.itemglint.quick_config.tooltip.bloom"));
        addBooleanRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.rule_switch_delay"), this.ruleSwitchDelayEnabled,
                value -> this.ruleSwitchDelayEnabled = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.rule_switch_delay"));
        addEnumRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.color_mode"),
                HeldItemOutlineSettings.ColorMode.values(), this.colorMode, value -> {
                    this.colorMode = value;
                    updateAutoSampleState();
                }, this::colorModeLabel,
                Component.translatable("screen.itemglint.quick_config.tooltip.color_mode"));
        addEnumRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.rule_mode"),
                HeldItemRuleManager.RuleMode.values(), this.ruleMode, value -> this.ruleMode = value, HeldItemRuleManager.RuleMode::label,
                Component.translatable("screen.itemglint.quick_config.tooltip.rule_mode"));
        Button editRulesButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.quick_config.edit_rules"), button -> openRuleEditor())
                .bounds(left + getRowLabelWidth(), top + row * ROW_HEIGHT, getRowFieldWidth(), 20)
                .build());
        applyTooltip(editRulesButton, left, top + row * ROW_HEIGHT, getRowLabelWidth() + getRowFieldWidth(), 20,
                Component.translatable("screen.itemglint.quick_config.tooltip.edit_rules"));
    }

    private void buildShapePage(int left, int top) {
        addSection(Component.translatable("screen.itemglint.quick_config.section.shape"), left, top - 18);
        int row = 0;
        addFloatSliderRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.width"),
                this.widthValue, HeldItemOutlineSettings.MIN_WIDTH, HeldItemOutlineSettings.MAX_WIDTH, value -> this.widthValue = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.width"));
        addFloatSliderRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.softness"),
                this.softness, HeldItemOutlineSettings.MIN_SOFTNESS, HeldItemOutlineSettings.MAX_SOFTNESS, value -> this.softness = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.softness"));
        addFloatSliderRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.alpha_threshold"),
                this.alphaThreshold, HeldItemOutlineSettings.MIN_ALPHA_THRESHOLD, HeldItemOutlineSettings.MAX_ALPHA_THRESHOLD, value -> this.alphaThreshold = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.alpha_threshold"));
        addFloatSliderRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.opacity"),
                this.opacity, HeldItemOutlineSettings.MIN_OPACITY, HeldItemOutlineSettings.MAX_OPACITY, value -> this.opacity = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.opacity"));
    }

    private void buildEffectsPage(int left, int top) {
        addSection(Component.translatable("screen.itemglint.quick_config.section.effects"), left, top - 18);
        int row = 0;
        trackBloomWidget(addEnumRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.bloom_resolution"),
                HeldItemOutlineSettings.BloomResolution.values(), this.bloomResolution, value -> this.bloomResolution = value, this::bloomResolutionLabel,
                Component.translatable("screen.itemglint.quick_config.tooltip.bloom_resolution")));
        trackBloomWidget(addFloatSliderRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.bloom_strength"),
                this.bloomStrength, HeldItemOutlineSettings.MIN_BLOOM_STRENGTH, HeldItemOutlineSettings.MAX_BLOOM_STRENGTH, value -> this.bloomStrength = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.bloom_strength")));
        trackBloomWidget(addFloatSliderRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.bloom_radius"),
                this.bloomRadius, HeldItemOutlineSettings.MIN_BLOOM_RADIUS, HeldItemOutlineSettings.MAX_BLOOM_RADIUS, value -> this.bloomRadius = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.bloom_radius")));
        trackBloomWidget(addIntSliderRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.bloom_max_passes"),
                this.bloomMaxPasses, HeldItemOutlineSettings.MIN_BLOOM_MAX_PASSES, HeldItemOutlineSettings.MAX_BLOOM_MAX_PASSES, value -> this.bloomMaxPasses = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.bloom_max_passes")));
        addFloatSliderRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.color_scroll_speed"),
                this.colorScrollSpeed, HeldItemOutlineSettings.MIN_COLOR_SCROLL_SPEED, HeldItemOutlineSettings.MAX_COLOR_SCROLL_SPEED, value -> this.colorScrollSpeed = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.color_scroll_speed"));
        trackAutoSampleWidget(addIntSliderRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.auto_sample_size"),
                this.autoSampleSize, HeldItemOutlineSettings.MIN_AUTO_SAMPLE_SIZE, HeldItemOutlineSettings.MAX_AUTO_SAMPLE_SIZE, value -> this.autoSampleSize = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.auto_sample_size")));
        trackAutoSampleWidget(addIntSliderRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.auto_sample_max_colors"),
                this.autoSampleMaxColors, HeldItemOutlineSettings.MIN_AUTO_SAMPLE_MAX_COLORS, HeldItemOutlineSettings.MAX_AUTO_SAMPLE_MAX_COLORS, value -> this.autoSampleMaxColors = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.auto_sample_max_colors")));
        trackAutoSampleWidget(addEnumRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.auto_sample_sort_mode"),
                HeldItemOutlineSettings.SampleColorSortMode.values(), this.autoSampleSortMode, value -> this.autoSampleSortMode = value,
                this::sampleSortModeLabel, Component.translatable("screen.itemglint.quick_config.tooltip.auto_sample_sort_mode")));
        addFloatSliderRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.depth_weight"),
                this.depthWeight, HeldItemOutlineSettings.MIN_DEPTH_WEIGHT, HeldItemOutlineSettings.MAX_DEPTH_WEIGHT, value -> this.depthWeight = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.depth_weight"));
        addFloatSliderRow(left, top + row++ * ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.glow_strength"),
                this.glowStrength, HeldItemOutlineSettings.MIN_GLOW_STRENGTH, HeldItemOutlineSettings.MAX_GLOW_STRENGTH, value -> this.glowStrength = value,
                Component.translatable("screen.itemglint.quick_config.tooltip.glow_strength"));
    }

    private void buildColorsPage(int left, int top) {
        addSection(Component.translatable("screen.itemglint.quick_config.section.colors"), left, top - 18);

        addEnumRow(left, top, Component.translatable("screen.itemglint.quick_config.active_target"),
                ColorTarget.values(), this.activeColorTarget, value -> {
                    this.activeColorTarget = value;
                    syncActiveColorControls();
                }, ColorTarget::label, Component.translatable("screen.itemglint.quick_config.tooltip.active_target"));
        addEnumRow(left, top + ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.color_mode"),
                HeldItemOutlineSettings.ColorMode.values(), this.colorMode, value -> {
                    this.colorMode = value;
                    updateAutoSampleState();
                }, this::colorModeLabel,
                Component.translatable("screen.itemglint.quick_config.tooltip.color_mode"));

        int pickerSize = getColorPickerSize();
        int controlLabelWidth = getColorControlLabelWidth();
        int controlWidth = getColorControlWidth();
        int pickerX = left + 6;
        int pickerY = top + 72;
        int barX = pickerX + pickerSize + 10;
        int controlsX = barX + COLOR_BAR_WIDTH + 18;

        this.colorPicker = this.addRenderableWidget(new ColorPickerBoxWidget(pickerX, pickerY, pickerSize, pickerSize,
                getActiveHue(), getActiveSaturation(), getActiveValue(), (hue, saturation) -> {
            setActiveColorFromHsv(hue, saturation, getActiveValue());
            syncActiveColorControls();
        }));
        addRowTooltip(pickerX, pickerY, pickerSize, pickerSize, Component.translatable("screen.itemglint.quick_config.tooltip.color_picker"));
        this.brightnessBar = this.addRenderableWidget(new BrightnessBarWidget(barX, pickerY, COLOR_BAR_WIDTH, pickerSize,
                getActiveHue(), getActiveSaturation(), getActiveValue(), value -> {
            setActiveColorFromHsv(getActiveHue(), getActiveSaturation(), value);
            syncActiveColorControls();
        }));
        addRowTooltip(barX, pickerY, COLOR_BAR_WIDTH, pickerSize, Component.translatable("screen.itemglint.quick_config.tooltip.brightness"));

        addLabel(Component.translatable("screen.itemglint.quick_config.color_hex_label"), controlsX, pickerY + 6, controlLabelWidth, true, 0xFFFFFF);
        this.hexField = new EditBox(this.font, controlsX + controlLabelWidth, pickerY, controlWidth, 20,
                Component.translatable("screen.itemglint.quick_config.color_hex_label"));
        this.hexField.setMaxLength(7);
        this.hexField.setResponder(this::onHexChanged);
        this.addRenderableWidget(this.hexField);
        addRowTooltip(controlsX, pickerY, controlLabelWidth + controlWidth, 20, Component.translatable("screen.itemglint.quick_config.tooltip.hex"));

        this.redSlider = addColorSliderRow(controlsX, pickerY + 34, Component.translatable("screen.itemglint.shader_config.outline.red"), getActiveRed(), value -> {
            setActiveRed(value);
            syncActiveColorControls();
        }, Component.translatable("screen.itemglint.quick_config.tooltip.red"));
        this.greenSlider = addColorSliderRow(controlsX, pickerY + 34 + ROW_HEIGHT, Component.translatable("screen.itemglint.shader_config.outline.green"), getActiveGreen(), value -> {
            setActiveGreen(value);
            syncActiveColorControls();
        }, Component.translatable("screen.itemglint.quick_config.tooltip.green"));
        this.blueSlider = addColorSliderRow(controlsX, pickerY + 34 + ROW_HEIGHT * 2, Component.translatable("screen.itemglint.shader_config.outline.blue"), getActiveBlue(), value -> {
            setActiveBlue(value);
            syncActiveColorControls();
        }, Component.translatable("screen.itemglint.quick_config.tooltip.blue"));
    }

    private CycleButton<Boolean> addBooleanRow(int left, int y, Component label, boolean value, Consumer<Boolean> consumer, Component tooltip) {
        int labelWidth = getRowLabelWidth();
        int fieldWidth = getRowFieldWidth();
        addLabel(label, left, y + 6);
        CycleButton<Boolean> widget = this.addRenderableWidget(CycleButton.onOffBuilder(value)
                .displayOnlyValue()
                .create(left + labelWidth, y, fieldWidth, 20, Component.empty(), (button, newValue) -> consumer.accept(newValue)));
        applyTooltip(widget, left, y, labelWidth + fieldWidth, 20, tooltip);
        return widget;
    }

    private <T> CycleButton<T> addEnumRow(int left, int y, Component label, T[] values, T currentValue, Consumer<T> consumer, Function<T, Component> labeler, Component tooltip) {
        int labelWidth = getRowLabelWidth();
        int fieldWidth = getRowFieldWidth();
        addLabel(label, left, y + 6);
        CycleButton<T> widget = this.addRenderableWidget(CycleButton.<T>builder(labeler)
                .withValues(values)
                .withInitialValue(currentValue)
                .displayOnlyValue()
                .create(left + labelWidth, y, fieldWidth, 20, Component.empty(), (button, newValue) -> consumer.accept(newValue)));
        applyTooltip(widget, left, y, labelWidth + fieldWidth, 20, tooltip);
        return widget;
    }

    private FloatSlider addFloatSliderRow(int left, int y, Component label, float value, float min, float max, Consumer<Float> consumer, Component tooltip) {
        int labelWidth = getRowLabelWidth();
        int fieldWidth = getRowFieldWidth();
        addLabel(label, left, y + 6);
        FloatSlider widget = this.addRenderableWidget(new FloatSlider(left + labelWidth, y, fieldWidth, 20, value, min, max, consumer));
        applyTooltip(widget, left, y, labelWidth + fieldWidth, 20, tooltip);
        return widget;
    }

    private IntSlider addIntSliderRow(int left, int y, Component label, int value, int min, int max, Consumer<Integer> consumer, Component tooltip) {
        int labelWidth = getRowLabelWidth();
        int fieldWidth = getRowFieldWidth();
        addLabel(label, left, y + 6);
        IntSlider widget = this.addRenderableWidget(new IntSlider(left + labelWidth, y, fieldWidth, 20, value, min, max, consumer));
        applyTooltip(widget, left, y, labelWidth + fieldWidth, 20, tooltip);
        return widget;
    }

    private FloatSlider addColorSliderRow(int left, int y, Component label, float value, Consumer<Float> consumer, Component tooltip) {
        int labelWidth = getColorControlLabelWidth();
        int fieldWidth = getColorControlWidth();
        addLabel(label, left, y + 6, labelWidth, true, 0xFFFFFF);
        FloatSlider widget = this.addRenderableWidget(new FloatSlider(left + labelWidth, y, fieldWidth, 20,
                value, HeldItemOutlineSettings.MIN_COLOR, HeldItemOutlineSettings.MAX_COLOR, consumer));
        applyTooltip(widget, left, y, labelWidth + fieldWidth, 20, tooltip);
        return widget;
    }

    private void addSection(Component text, int x, int y) {
        addLabel(text, x, y, Math.max(140, getMainContentWidth()), false, 0xA7E7FF);
    }

    private void addLabel(Component text, int x, int y) {
        addLabel(text, x, y, Math.max(84, getRowLabelWidth() - 8), true, 0xFFFFFF);
    }

    private void addLabel(Component text, int x, int y, int maxWidth, boolean rightAligned, int color) {
        this.labels.add(new LabelLine(text, x, y, maxWidth, rightAligned, color));
    }

    private void applyTooltip(AbstractWidget widget, int x, int y, int width, int height, Component tooltip) {
        addRowTooltip(x, y, width, height, tooltip);
    }

    private void addRowTooltip(int x, int y, int width, int height, Component tooltip) {
        this.rowTooltips.add(new RowTooltipArea(x, y, width, height, tooltip));
    }

    private <T extends AbstractWidget> T trackBloomWidget(T widget) {
        this.bloomDependentWidgets.add(widget);
        return widget;
    }

    private <T extends AbstractWidget> T trackAutoSampleWidget(T widget) {
        this.autoSampleWidgets.add(widget);
        return widget;
    }

    private void updateBloomDependentState() {
        for (AbstractWidget widget : this.bloomDependentWidgets) {
            widget.active = this.bloom;
        }
    }

    private void updateAutoSampleState() {
        boolean active = this.colorMode == HeldItemOutlineSettings.ColorMode.AUTO_SAMPLE_SCROLL;
        for (AbstractWidget widget : this.autoSampleWidgets) {
            widget.active = active;
        }
    }

    private void loadCurrentValues() {
        this.featureEnabled = ShaderFeatureManager.isEnabled(ShaderFeature.HELD_ITEM_OUTLINE);
        this.mainHand = HeldItemOutlineSettings.isMainHandEnabled();
        this.offHand = HeldItemOutlineSettings.isOffHandEnabled();
        this.bloom = HeldItemOutlineSettings.isBloomEnabled();
        this.ruleSwitchDelayEnabled = HeldItemOutlineSettings.isRuleSwitchDelayEnabled();
        this.colorMode = HeldItemOutlineSettings.getColorMode();
        this.autoSampleSize = HeldItemOutlineSettings.getAutoSampleSize();
        this.autoSampleMaxColors = HeldItemOutlineSettings.getAutoSampleMaxColors();
        this.autoSampleSortMode = HeldItemOutlineSettings.getAutoSampleSortMode();
        this.bloomResolution = HeldItemOutlineSettings.getBloomResolution();
        this.bloomMaxPasses = HeldItemOutlineSettings.getBloomMaxPasses();
        this.bloomStrength = HeldItemOutlineSettings.getBloomStrength();
        this.bloomRadius = HeldItemOutlineSettings.getBloomRadius();
        this.widthValue = HeldItemOutlineSettings.getWidth();
        this.softness = HeldItemOutlineSettings.getSoftness();
        this.alphaThreshold = HeldItemOutlineSettings.getAlphaThreshold();
        this.opacity = HeldItemOutlineSettings.getOpacity();
        this.colorScrollSpeed = HeldItemOutlineSettings.getColorScrollSpeed();
        this.depthWeight = HeldItemOutlineSettings.getDepthWeight();
        this.glowStrength = HeldItemOutlineSettings.getGlowStrength();
        this.red = HeldItemOutlineSettings.getRed();
        this.green = HeldItemOutlineSettings.getGreen();
        this.blue = HeldItemOutlineSettings.getBlue();
        this.secondaryRed = HeldItemOutlineSettings.getSecondaryRed();
        this.secondaryGreen = HeldItemOutlineSettings.getSecondaryGreen();
        this.secondaryBlue = HeldItemOutlineSettings.getSecondaryBlue();
        HeldItemRuleManager.StateSnapshot ruleSnapshot = HeldItemRuleManager.captureState();
        this.ruleMode = ruleSnapshot.ruleMode();
        this.whitelistEntries = new ArrayList<>(ruleSnapshot.whitelistEntries());
        this.blacklistEntries = new ArrayList<>(ruleSnapshot.blacklistEntries());
        this.customRules = new ArrayList<>(ruleSnapshot.customRules());
    }

    private void resetToDefaults() {
        this.featureEnabled = true;
        this.mainHand = HeldItemOutlineSettings.DEFAULT_MAIN_HAND;
        this.offHand = HeldItemOutlineSettings.DEFAULT_OFF_HAND;
        this.bloom = HeldItemOutlineSettings.DEFAULT_BLOOM;
        this.ruleSwitchDelayEnabled = HeldItemOutlineSettings.DEFAULT_RULE_SWITCH_DELAY_ENABLED;
        this.colorMode = HeldItemOutlineSettings.DEFAULT_COLOR_MODE;
        this.autoSampleSize = HeldItemOutlineSettings.DEFAULT_AUTO_SAMPLE_SIZE;
        this.autoSampleMaxColors = HeldItemOutlineSettings.DEFAULT_AUTO_SAMPLE_MAX_COLORS;
        this.autoSampleSortMode = HeldItemOutlineSettings.DEFAULT_AUTO_SAMPLE_SORT_MODE;
        this.bloomResolution = HeldItemOutlineSettings.DEFAULT_BLOOM_RESOLUTION;
        this.bloomMaxPasses = HeldItemOutlineSettings.DEFAULT_BLOOM_MAX_PASSES;
        this.bloomStrength = HeldItemOutlineSettings.DEFAULT_BLOOM_STRENGTH;
        this.bloomRadius = HeldItemOutlineSettings.DEFAULT_BLOOM_RADIUS;
        this.widthValue = HeldItemOutlineSettings.DEFAULT_WIDTH;
        this.softness = HeldItemOutlineSettings.DEFAULT_SOFTNESS;
        this.alphaThreshold = HeldItemOutlineSettings.DEFAULT_ALPHA_THRESHOLD;
        this.opacity = HeldItemOutlineSettings.DEFAULT_OPACITY;
        this.colorScrollSpeed = HeldItemOutlineSettings.DEFAULT_COLOR_SCROLL_SPEED;
        this.depthWeight = HeldItemOutlineSettings.DEFAULT_DEPTH_WEIGHT;
        this.glowStrength = HeldItemOutlineSettings.DEFAULT_GLOW_STRENGTH;
        this.red = HeldItemOutlineSettings.DEFAULT_RED;
        this.green = HeldItemOutlineSettings.DEFAULT_GREEN;
        this.blue = HeldItemOutlineSettings.DEFAULT_BLUE;
        this.secondaryRed = HeldItemOutlineSettings.DEFAULT_SECONDARY_RED;
        this.secondaryGreen = HeldItemOutlineSettings.DEFAULT_SECONDARY_GREEN;
        this.secondaryBlue = HeldItemOutlineSettings.DEFAULT_SECONDARY_BLUE;
        this.activeColorTarget = ColorTarget.PRIMARY;
        this.ruleMode = HeldItemRuleManager.RuleMode.ALL;
        this.whitelistEntries = new ArrayList<>();
        this.blacklistEntries = new ArrayList<>();
        this.customRules = new ArrayList<>();
    }

    private void applyValuesToSettings() {
        ShaderFeatureManager.setEnabled(ShaderFeature.HELD_ITEM_OUTLINE, this.featureEnabled);
        HeldItemOutlineSettings.setMainHandEnabled(this.mainHand);
        HeldItemOutlineSettings.setOffHandEnabled(this.offHand);
        HeldItemOutlineSettings.setBloomEnabled(this.bloom);
        HeldItemOutlineSettings.setRuleSwitchDelayEnabled(this.ruleSwitchDelayEnabled);
        HeldItemOutlineSettings.setColorMode(this.colorMode);
        HeldItemOutlineSettings.setAutoSampleSize(this.autoSampleSize);
        HeldItemOutlineSettings.setAutoSampleMaxColors(this.autoSampleMaxColors);
        HeldItemOutlineSettings.setAutoSampleSortMode(this.autoSampleSortMode);
        HeldItemOutlineSettings.setBloomResolution(this.bloomResolution);
        HeldItemOutlineSettings.setBloomMaxPasses(this.bloomMaxPasses);
        HeldItemOutlineSettings.setBloomStrength(this.bloomStrength);
        HeldItemOutlineSettings.setBloomRadius(this.bloomRadius);
        HeldItemOutlineSettings.setWidth(this.widthValue);
        HeldItemOutlineSettings.setSoftness(this.softness);
        HeldItemOutlineSettings.setAlphaThreshold(this.alphaThreshold);
        HeldItemOutlineSettings.setOpacity(this.opacity);
        HeldItemOutlineSettings.setColorScrollSpeed(this.colorScrollSpeed);
        HeldItemOutlineSettings.setDepthWeight(this.depthWeight);
        HeldItemOutlineSettings.setGlowStrength(this.glowStrength);
        HeldItemOutlineSettings.setRed(this.red);
        HeldItemOutlineSettings.setGreen(this.green);
        HeldItemOutlineSettings.setBlue(this.blue);
        HeldItemOutlineSettings.setSecondaryRed(this.secondaryRed);
        HeldItemOutlineSettings.setSecondaryGreen(this.secondaryGreen);
        HeldItemOutlineSettings.setSecondaryBlue(this.secondaryBlue);
        HeldItemRuleManager.applyState(new HeldItemRuleManager.StateSnapshot(this.ruleMode, this.whitelistEntries, this.blacklistEntries, this.customRules));
    }

    private void openRuleEditor() {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.setScreen(new HeldItemRuleConfigScreen(this,
                new HeldItemRuleManager.StateSnapshot(this.ruleMode, this.whitelistEntries, this.blacklistEntries, this.customRules),
                snapshot -> {
                    this.ruleMode = snapshot.ruleMode();
                    this.whitelistEntries = new ArrayList<>(snapshot.whitelistEntries());
                    this.blacklistEntries = new ArrayList<>(snapshot.blacklistEntries());
                    this.customRules = new ArrayList<>(snapshot.customRules());
                }));
    }

    private void saveAndClose() {
        applyValuesToSettings();
        ShaderClientConfig.syncAllSettings();
        closeToParent();
    }

    private void cancelAndClose() {
        this.snapshot.restore();
        ShaderClientConfig.syncAllSettings();
        closeToParent();
    }

    private void closeToParent() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private Component onOffLabel(boolean value) {
        return Component.translatable(value ? "options.on" : "options.off");
    }

    private Component describeHands() {
        if (this.mainHand && this.offHand) {
            return Component.translatable("screen.itemglint.quick_config.hands.both");
        }
        if (this.mainHand) {
            return Component.translatable("screen.itemglint.quick_config.hands.main");
        }
        if (this.offHand) {
            return Component.translatable("screen.itemglint.quick_config.hands.off");
        }
        return Component.translatable("screen.itemglint.quick_config.hands.none");
    }

    private Component colorModeLabel(HeldItemOutlineSettings.ColorMode mode) {
        return Component.translatable("screen.itemglint.shader_config.color_mode." + mode.commandName());
    }

    private Component sampleSortModeLabel(HeldItemOutlineSettings.SampleColorSortMode mode) {
        return Component.translatable("screen.itemglint.shader_config.sample_sort_mode." + mode.commandName());
    }

    private Component bloomResolutionLabel(HeldItemOutlineSettings.BloomResolution resolution) {
        return Component.translatable("screen.itemglint.shader_config.bloom_resolution." + resolution.commandName());
    }

    private int getFrameLeft() {
        return this.width / 2 - getFrameWidth() / 2;
    }

    private int getFrameWidth() {
        return Math.min(MAIN_WIDTH + PANEL_GAP + SIDEBAR_WIDTH, Math.max(340, this.width - FRAME_MARGIN * 2));
    }

    private int getMainLeft() {
        return getFrameLeft();
    }

    private int getMainRight() {
        return getMainLeft() + getMainPanelWidth();
    }

    private int getMainPanelWidth() {
        return getFrameWidth() - PANEL_GAP - getSidebarWidth();
    }

    private int getSidebarLeft() {
        return getMainRight() + PANEL_GAP;
    }

    private int getSidebarRight() {
        return getSidebarLeft() + getSidebarWidth();
    }

    private int getSidebarWidth() {
        int totalWidth = getFrameWidth();
        int sidebarWidth = Math.min(SIDEBAR_WIDTH, Math.max(MIN_SIDEBAR_WIDTH, totalWidth / 4));
        int mainWidth = totalWidth - PANEL_GAP - sidebarWidth;
        int minimumMainWidth = 280;
        if (mainWidth < minimumMainWidth) {
            sidebarWidth = Math.max(MIN_SIDEBAR_WIDTH, totalWidth - PANEL_GAP - minimumMainWidth);
        }
        return Mth.clamp(sidebarWidth, MIN_SIDEBAR_WIDTH, SIDEBAR_WIDTH);
    }

    private int getHorizontalInset() {
        return Mth.clamp(getMainPanelWidth() / 24, 10, 18);
    }

    private int getSidebarInset() {
        return Mth.clamp(getSidebarWidth() / 14, 8, 12);
    }

    private int getMainContentLeft() {
        return getMainLeft() + getHorizontalInset();
    }

    private int getMainContentRight() {
        return getMainRight() - getHorizontalInset();
    }

    private int getMainContentWidth() {
        return getMainContentRight() - getMainContentLeft();
    }

    private int getRowLabelWidth() {
        return Mth.clamp(getMainContentWidth() * 2 / 5, 104, LABEL_WIDTH);
    }

    private int getRowFieldWidth() {
        return Math.max(120, getMainContentWidth() - getRowLabelWidth());
    }

    private int getNavButtonWidth() {
        return Math.max(72, getSidebarWidth() - getSidebarInset() * 2);
    }

    private int getColorPickerSize() {
        int minimumControlArea = 132;
        int available = getMainContentWidth() - COLOR_BAR_WIDTH - 28 - minimumControlArea;
        return Mth.clamp(Math.min(COLOR_PICKER_SIZE, available), MIN_COLOR_PICKER_SIZE, COLOR_PICKER_SIZE);
    }

    private int getColorControlAreaWidth() {
        return Math.max(120, getMainContentWidth() - getColorPickerSize() - COLOR_BAR_WIDTH - 28);
    }

    private int getColorControlLabelWidth() {
        return Mth.clamp(getColorControlAreaWidth() / 3, 44, COLOR_CONTROL_LABEL_WIDTH);
    }

    private int getColorControlWidth() {
        return Math.max(76, getColorControlAreaWidth() - getColorControlLabelWidth());
    }

    private int getMainContentTop() {
        return 74;
    }

    private int getFooterButtonY() {
        return this.height - 29;
    }

    private int getFooterButtonWidth() {
        int buttonGap = 8;
        return Math.max(72, Math.min(108, (getMainContentWidth() - buttonGap * 2) / 3));
    }

    private int getFooterButtonsLeft() {
        int buttonGap = 8;
        int buttonWidth = getFooterButtonWidth();
        int buttonsTotalWidth = buttonWidth * 3 + buttonGap * 2;
        return getMainContentLeft() + Math.max(0, (getMainContentWidth() - buttonsTotalWidth) / 2);
    }

    private boolean dispatchMouseClickTopmost(double mouseX, double mouseY, int button) {
        List<? extends GuiEventListener> children = this.children();
        for (int index = children.size() - 1; index >= 0; index--) {
            GuiEventListener listener = children.get(index);
            if (listener.mouseClicked(mouseX, mouseY, button)) {
                this.setFocused(listener);
                if (button == 0) {
                    this.setDragging(true);
                }
                return true;
            }
        }
        return false;
    }

    private boolean isMouseOverFooterButtons(double mouseX, double mouseY) {
        int buttonY = getFooterButtonY();
        if (mouseY < buttonY || mouseY >= buttonY + 20) {
            return false;
        }
        int buttonGap = 8;
        int buttonWidth = getFooterButtonWidth();
        int left = getFooterButtonsLeft();
        for (int index = 0; index < 3; index++) {
            int buttonX = left + index * (buttonWidth + buttonGap);
            if (mouseX >= buttonX && mouseX < buttonX + buttonWidth) {
                return true;
            }
        }
        return false;
    }

    private boolean isInFooterMaskArea(double mouseX, double mouseY) {
        return mouseX >= getFrameLeft() - PANEL_PADDING - SCROLL_HORIZONTAL_PADDING
                && mouseX < getSidebarRight() + PANEL_PADDING + SCROLL_HORIZONTAL_PADDING
                && mouseY >= getFooterMaskTop();
    }

    private boolean isInHiddenFrameArea(double mouseX, double mouseY) {
        return mouseX >= getFrameLeft() - PANEL_PADDING - SCROLL_HORIZONTAL_PADDING
                && mouseX < getSidebarRight() + PANEL_PADDING + SCROLL_HORIZONTAL_PADDING
                && (mouseY < 44 || mouseY >= getFooterMaskTop());
    }

    private int getFooterMaskTop() {
        return this.height - 38;
    }

    private int getPageViewportBottom() {
        return getFooterMaskTop() - 6;
    }

    private int getScrollStopY() {
        return Math.max(44, this.height / 2);
    }

    private int getMaxPageScrollOffset() {
        return Math.max(0, getPageContentBottom() - getScrollStopY());
    }

    private int getPageContentBottom() {
        int top = getMainContentTop();
        return switch (this.currentPage) {
            case OVERVIEW -> top + 7 * ROW_HEIGHT + 20;
            case SHAPE -> top + 3 * ROW_HEIGHT + 20;
            case EFFECTS -> top + 9 * ROW_HEIGHT + 20;
            case COLORS -> top + 72 + Math.max(getColorPickerSize(), 34 + ROW_HEIGHT * 2 + 20);
        };
    }

    private boolean isMouseOverScrollableArea(double mouseX, double mouseY) {
        return mouseX >= getMainLeft() && mouseX < getSidebarRight() && mouseY >= 44 && mouseY < getPageViewportBottom();
    }

    private float getActiveRed() {
        return this.activeColorTarget == ColorTarget.PRIMARY ? this.red : this.secondaryRed;
    }

    private float getActiveGreen() {
        return this.activeColorTarget == ColorTarget.PRIMARY ? this.green : this.secondaryGreen;
    }

    private float getActiveBlue() {
        return this.activeColorTarget == ColorTarget.PRIMARY ? this.blue : this.secondaryBlue;
    }

    private void setActiveRed(float value) {
        if (this.activeColorTarget == ColorTarget.PRIMARY) {
            this.red = value;
        } else {
            this.secondaryRed = value;
        }
    }

    private void setActiveGreen(float value) {
        if (this.activeColorTarget == ColorTarget.PRIMARY) {
            this.green = value;
        } else {
            this.secondaryGreen = value;
        }
    }

    private void setActiveBlue(float value) {
        if (this.activeColorTarget == ColorTarget.PRIMARY) {
            this.blue = value;
        } else {
            this.secondaryBlue = value;
        }
    }

    private ColorVector getColor(ColorTarget target) {
        return target == ColorTarget.PRIMARY
                ? new ColorVector(this.red, this.green, this.blue)
                : new ColorVector(this.secondaryRed, this.secondaryGreen, this.secondaryBlue);
    }

    private void setActiveColor(float redValue, float greenValue, float blueValue) {
        if (this.activeColorTarget == ColorTarget.PRIMARY) {
            this.red = redValue;
            this.green = greenValue;
            this.blue = blueValue;
        } else {
            this.secondaryRed = redValue;
            this.secondaryGreen = greenValue;
            this.secondaryBlue = blueValue;
        }
    }

    private float getActiveHue() {
        return rgbToHsv(getColor(this.activeColorTarget))[0];
    }

    private float getActiveSaturation() {
        return rgbToHsv(getColor(this.activeColorTarget))[1];
    }

    private float getActiveValue() {
        return rgbToHsv(getColor(this.activeColorTarget))[2];
    }

    private void setActiveColorFromHsv(float hue, float saturation, float value) {
        int rgb = Mth.hsvToRgb(Mth.positiveModulo(hue, 1.0F), Mth.clamp(saturation, 0.0F, 1.0F), Mth.clamp(value, 0.0F, 1.0F));
        setActiveColor(((rgb >> 16) & 255) / 255.0F, ((rgb >> 8) & 255) / 255.0F, (rgb & 255) / 255.0F);
    }

    private void syncActiveColorControls() {
        this.syncingColorControls = true;
        if (this.redSlider != null) {
            this.redSlider.setExternalValue(getActiveRed());
        }
        if (this.greenSlider != null) {
            this.greenSlider.setExternalValue(getActiveGreen());
        }
        if (this.blueSlider != null) {
            this.blueSlider.setExternalValue(getActiveBlue());
        }
        float[] hsv = rgbToHsv(getColor(this.activeColorTarget));
        if (this.colorPicker != null) {
            this.colorPicker.setColor(hsv[0], hsv[1], hsv[2]);
        }
        if (this.brightnessBar != null) {
            this.brightnessBar.setColor(hsv[0], hsv[1], hsv[2]);
        }
        if (this.hexField != null) {
            this.hexField.setValue(toHex(getColor(this.activeColorTarget)));
            this.hexField.setTextColor(0xE0E0E0);
        }
        this.syncingColorControls = false;
    }

    private void onHexChanged(String value) {
        if (this.syncingColorControls || this.hexField == null) {
            return;
        }

        String normalized = value.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.length() != 6) {
            this.hexField.setTextColor(0xE0E0E0);
            return;
        }

        try {
            int rgb = Integer.parseInt(normalized, 16);
            setActiveColor(((rgb >> 16) & 255) / 255.0F, ((rgb >> 8) & 255) / 255.0F, (rgb & 255) / 255.0F);
            this.hexField.setTextColor(0xE0E0E0);
            syncActiveColorControls();
        } catch (NumberFormatException exception) {
            this.hexField.setTextColor(0xFF8080);
        }
    }

    private void drawSwatch(GuiGraphics graphics, int x, int y, ColorVector color, int width, int height) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF1D222B);
        graphics.fill(x, y, x + width, y + height, toArgb(color));
    }

    private static int toArgb(ColorVector color) {
        return 0xFF000000 | (toByte(color.red) << 16) | (toByte(color.green) << 8) | toByte(color.blue);
    }

    private static int toByte(float value) {
        return Mth.clamp(Math.round(value * 255.0F), 0, 255);
    }

    private static String toHex(ColorVector color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", toByte(color.red), toByte(color.green), toByte(color.blue));
    }

    private static float[] rgbToHsv(ColorVector color) {
        float max = Math.max(color.red, Math.max(color.green, color.blue));
        float min = Math.min(color.red, Math.min(color.green, color.blue));
        float delta = max - min;
        float hue;

        if (delta <= 0.00001F) {
            hue = 0.0F;
        } else if (max == color.red) {
            hue = ((color.green - color.blue) / delta) % 6.0F;
        } else if (max == color.green) {
            hue = (color.blue - color.red) / delta + 2.0F;
        } else {
            hue = (color.red - color.green) / delta + 4.0F;
        }

        hue /= 6.0F;
        if (hue < 0.0F) {
            hue += 1.0F;
        }

        float saturation = max <= 0.00001F ? 0.0F : delta / max;
        return new float[]{hue, saturation, max};
    }

    private enum Page {
        OVERVIEW("screen.itemglint.quick_config.page.overview"),
        SHAPE("screen.itemglint.quick_config.page.shape"),
        EFFECTS("screen.itemglint.quick_config.page.effects"),
        COLORS("screen.itemglint.quick_config.page.colors");

        private final String titleKey;

        Page(String titleKey) {
            this.titleKey = titleKey;
        }
    }

    private enum ColorTarget {
        PRIMARY("screen.itemglint.quick_config.target.primary"),
        SECONDARY("screen.itemglint.quick_config.target.secondary");

        private final String labelKey;

        ColorTarget(String labelKey) {
            this.labelKey = labelKey;
        }

        private Component label() {
            return Component.translatable(this.labelKey);
        }
    }

    private record LabelLine(Component text, int x, int y, int maxWidth, boolean rightAligned, int color) {
    }

    private record ColorVector(float red, float green, float blue) {
    }

    private record RowTooltipArea(int x, int y, int width, int height, Component tooltip) {
        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
        }
    }

    private static final class FooterMaskWidget extends AbstractWidget {
        private FooterMaskWidget(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getX() + this.width, getY() + this.height, 0xD010131A);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }

    private record SettingsSnapshot(boolean featureEnabled, boolean mainHand, boolean offHand, boolean bloom, boolean ruleSwitchDelayEnabled,
                                    HeldItemOutlineSettings.ColorMode colorMode, int autoSampleSize, int autoSampleMaxColors,
                                    HeldItemOutlineSettings.SampleColorSortMode autoSampleSortMode, HeldItemOutlineSettings.BloomResolution bloomResolution,
                                    int bloomMaxPasses, float bloomStrength, float bloomRadius, float widthValue, float softness,
                                    float alphaThreshold, float opacity, float colorScrollSpeed, float depthWeight,
                                    float glowStrength,
                                    float red, float green, float blue, float secondaryRed, float secondaryGreen, float secondaryBlue,
                                    HeldItemRuleManager.RuleMode ruleMode, List<String> whitelistEntries,
                                    List<String> blacklistEntries, List<HeldItemRuleManager.CustomRule> customRules) {
        private static SettingsSnapshot capture() {
            return new SettingsSnapshot(
                    ShaderFeatureManager.isEnabled(ShaderFeature.HELD_ITEM_OUTLINE),
                    HeldItemOutlineSettings.isMainHandEnabled(),
                    HeldItemOutlineSettings.isOffHandEnabled(),
                    HeldItemOutlineSettings.isBloomEnabled(),
                    HeldItemOutlineSettings.isRuleSwitchDelayEnabled(),
                    HeldItemOutlineSettings.getColorMode(),
                    HeldItemOutlineSettings.getAutoSampleSize(),
                    HeldItemOutlineSettings.getAutoSampleMaxColors(),
                    HeldItemOutlineSettings.getAutoSampleSortMode(),
                    HeldItemOutlineSettings.getBloomResolution(),
                    HeldItemOutlineSettings.getBloomMaxPasses(),
                    HeldItemOutlineSettings.getBloomStrength(),
                    HeldItemOutlineSettings.getBloomRadius(),
                    HeldItemOutlineSettings.getWidth(),
                    HeldItemOutlineSettings.getSoftness(),
                    HeldItemOutlineSettings.getAlphaThreshold(),
                    HeldItemOutlineSettings.getOpacity(),
                    HeldItemOutlineSettings.getColorScrollSpeed(),
                    HeldItemOutlineSettings.getDepthWeight(),
                    HeldItemOutlineSettings.getGlowStrength(),
                    HeldItemOutlineSettings.getRed(),
                    HeldItemOutlineSettings.getGreen(),
                    HeldItemOutlineSettings.getBlue(),
                    HeldItemOutlineSettings.getSecondaryRed(),
                    HeldItemOutlineSettings.getSecondaryGreen(),
                    HeldItemOutlineSettings.getSecondaryBlue(),
                    HeldItemRuleManager.getRuleMode(),
                    HeldItemRuleManager.getWhitelistEntries(),
                    HeldItemRuleManager.getBlacklistEntries(),
                    HeldItemRuleManager.getCustomRules()
            );
        }

        private void restore() {
            ShaderFeatureManager.setEnabled(ShaderFeature.HELD_ITEM_OUTLINE, this.featureEnabled);
            HeldItemOutlineSettings.setMainHandEnabled(this.mainHand);
            HeldItemOutlineSettings.setOffHandEnabled(this.offHand);
            HeldItemOutlineSettings.setBloomEnabled(this.bloom);
            HeldItemOutlineSettings.setRuleSwitchDelayEnabled(this.ruleSwitchDelayEnabled);
            HeldItemOutlineSettings.setColorMode(this.colorMode);
            HeldItemOutlineSettings.setAutoSampleSize(this.autoSampleSize);
            HeldItemOutlineSettings.setAutoSampleMaxColors(this.autoSampleMaxColors);
            HeldItemOutlineSettings.setAutoSampleSortMode(this.autoSampleSortMode);
            HeldItemOutlineSettings.setBloomResolution(this.bloomResolution);
            HeldItemOutlineSettings.setBloomMaxPasses(this.bloomMaxPasses);
            HeldItemOutlineSettings.setBloomStrength(this.bloomStrength);
            HeldItemOutlineSettings.setBloomRadius(this.bloomRadius);
            HeldItemOutlineSettings.setWidth(this.widthValue);
            HeldItemOutlineSettings.setSoftness(this.softness);
            HeldItemOutlineSettings.setAlphaThreshold(this.alphaThreshold);
            HeldItemOutlineSettings.setOpacity(this.opacity);
            HeldItemOutlineSettings.setColorScrollSpeed(this.colorScrollSpeed);
            HeldItemOutlineSettings.setDepthWeight(this.depthWeight);
            HeldItemOutlineSettings.setGlowStrength(this.glowStrength);
            HeldItemOutlineSettings.setRed(this.red);
            HeldItemOutlineSettings.setGreen(this.green);
            HeldItemOutlineSettings.setBlue(this.blue);
            HeldItemOutlineSettings.setSecondaryRed(this.secondaryRed);
            HeldItemOutlineSettings.setSecondaryGreen(this.secondaryGreen);
            HeldItemOutlineSettings.setSecondaryBlue(this.secondaryBlue);
            HeldItemRuleManager.applyState(new HeldItemRuleManager.StateSnapshot(this.ruleMode, this.whitelistEntries, this.blacklistEntries, this.customRules));
        }
    }

    private static final class FloatSlider extends AbstractSliderButton {
        private final float minValue;
        private final float maxValue;
        private final Consumer<Float> callback;

        private FloatSlider(int x, int y, int width, int height, float currentValue, float minValue, float maxValue, Consumer<Float> callback) {
            super(x, y, width, height, Component.empty(), normalize(currentValue, minValue, maxValue));
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.callback = callback;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(String.format(Locale.ROOT, "%.2f", getFloatValue())));
        }

        @Override
        protected void applyValue() {
            this.callback.accept(getFloatValue());
            updateMessage();
        }

        private float getFloatValue() {
            return (float) (this.minValue + this.value * (this.maxValue - this.minValue));
        }

        private void setExternalValue(float value) {
            this.value = normalize(value, this.minValue, this.maxValue);
            updateMessage();
        }

        private static double normalize(float value, float minValue, float maxValue) {
            return maxValue <= minValue ? 0.0D : Mth.clamp((value - minValue) / (maxValue - minValue), 0.0F, 1.0F);
        }
    }

    private static final class IntSlider extends AbstractSliderButton {
        private final int minValue;
        private final int maxValue;
        private final Consumer<Integer> callback;

        private IntSlider(int x, int y, int width, int height, int currentValue, int minValue, int maxValue, Consumer<Integer> callback) {
            super(x, y, width, height, Component.empty(), normalize(currentValue, minValue, maxValue));
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.callback = callback;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(Integer.toString(getIntValue())));
        }

        @Override
        protected void applyValue() {
            this.callback.accept(getIntValue());
            updateMessage();
        }

        private int getIntValue() {
            return Mth.clamp(Math.round((float) (this.minValue + this.value * (this.maxValue - this.minValue))), this.minValue, this.maxValue);
        }

        private static double normalize(int value, int minValue, int maxValue) {
            return maxValue <= minValue ? 0.0D : Mth.clamp((value - minValue) / (float) (maxValue - minValue), 0.0F, 1.0F);
        }
    }

    private static final class ColorPickerBoxWidget extends AbstractWidget {
        private static final int SAMPLE_STEP = 2;

        private final int pickerWidth;
        private final int pickerHeight;
        private final BiConsumer<Float, Float> callback;
        private float hue;
        private float saturation;
        private float value;
        private boolean dragging;

        private ColorPickerBoxWidget(int x, int y, int width, int height, float hue, float saturation, float value, BiConsumer<Float, Float> callback) {
            super(x, y, width, height, Component.translatable("screen.itemglint.quick_config.color_picker"));
            this.pickerWidth = width;
            this.pickerHeight = height;
            this.callback = callback;
            setColor(hue, saturation, value);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            for (int py = this.getY(); py < this.getY() + this.pickerHeight; py += SAMPLE_STEP) {
                for (int px = this.getX(); px < this.getX() + this.pickerWidth; px += SAMPLE_STEP) {
                    float sampleHue = Mth.clamp((px - this.getX()) / (float) Math.max(1, this.pickerWidth - 1), 0.0F, 1.0F);
                    float sampleSaturation = 1.0F - Mth.clamp((py - this.getY()) / (float) Math.max(1, this.pickerHeight - 1), 0.0F, 1.0F);
                    graphics.fill(px, py, px + SAMPLE_STEP, py + SAMPLE_STEP, 0xFF000000 | Mth.hsvToRgb(sampleHue, sampleSaturation, this.value));
                }
            }

            graphics.renderOutline(this.getX(), this.getY(), this.width, this.height, 0xFF404040);
            int markerX = Math.round(this.getX() + this.hue * (this.pickerWidth - 1));
            int markerY = Math.round(this.getY() + (1.0F - this.saturation) * (this.pickerHeight - 1));
            graphics.fill(markerX - 3, markerY - 3, markerX + 4, markerY + 4, 0xFF000000);
            graphics.fill(markerX - 2, markerY - 2, markerX + 3, markerY + 3, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.isMouseOver(mouseX, mouseY)) {
                return false;
            }
            this.dragging = true;
            updateFromMouse(mouseX, mouseY);
            return true;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (button != 0 || !this.dragging) {
                return false;
            }
            updateFromMouse(mouseX, mouseY);
            return true;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (button == 0) {
                this.dragging = false;
            }
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        private void setColor(float hue, float saturation, float value) {
            this.hue = Mth.positiveModulo(hue, 1.0F);
            this.saturation = Mth.clamp(saturation, 0.0F, 1.0F);
            this.value = Mth.clamp(value, 0.0F, 1.0F);
        }

        private void updateFromMouse(double mouseX, double mouseY) {
            this.hue = Mth.clamp(((float) mouseX - this.getX()) / Math.max(1.0F, this.pickerWidth - 1.0F), 0.0F, 1.0F);
            this.saturation = 1.0F - Mth.clamp(((float) mouseY - this.getY()) / Math.max(1.0F, this.pickerHeight - 1.0F), 0.0F, 1.0F);
            this.callback.accept(this.hue, this.saturation);
        }
    }

    private static final class BrightnessBarWidget extends AbstractWidget {
        private static final int SAMPLE_STEP = 2;

        private final Consumer<Float> callback;
        private float hue;
        private float saturation;
        private float value;
        private boolean dragging;

        private BrightnessBarWidget(int x, int y, int width, int height, float hue, float saturation, float value, Consumer<Float> callback) {
            super(x, y, width, height, Component.translatable("screen.itemglint.quick_config.brightness"));
            this.callback = callback;
            setColor(hue, saturation, value);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            for (int py = this.getY(); py < this.getY() + this.height; py += SAMPLE_STEP) {
                float sampleValue = 1.0F - Mth.clamp((py - this.getY()) / (float) Math.max(1, this.height - 1), 0.0F, 1.0F);
                graphics.fill(this.getX(), py, this.getX() + this.width, py + SAMPLE_STEP, 0xFF000000 | Mth.hsvToRgb(this.hue, this.saturation, sampleValue));
            }

            graphics.renderOutline(this.getX(), this.getY(), this.width, this.height, 0xFF404040);
            int markerY = Math.round(this.getY() + (1.0F - this.value) * (this.height - 1));
            graphics.fill(this.getX() - 2, markerY - 1, this.getX() + this.width + 2, markerY + 2, 0xFF000000);
            graphics.fill(this.getX() - 1, markerY, this.getX() + this.width + 1, markerY + 1, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.isMouseOver(mouseX, mouseY)) {
                return false;
            }
            this.dragging = true;
            updateFromMouse(mouseY);
            return true;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (button != 0 || !this.dragging) {
                return false;
            }
            updateFromMouse(mouseY);
            return true;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (button == 0) {
                this.dragging = false;
            }
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        private void setColor(float hue, float saturation, float value) {
            this.hue = Mth.positiveModulo(hue, 1.0F);
            this.saturation = Mth.clamp(saturation, 0.0F, 1.0F);
            this.value = Mth.clamp(value, 0.0F, 1.0F);
        }

        private void updateFromMouse(double mouseY) {
            this.value = 1.0F - Mth.clamp(((float) mouseY - this.getY()) / Math.max(1.0F, this.height - 1.0F), 0.0F, 1.0F);
            this.callback.accept(this.value);
        }
    }
}
