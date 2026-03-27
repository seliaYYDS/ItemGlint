package celia.itemglint.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public class HeldItemCustomRuleEditScreen extends Screen {
    private static final int FILTER_PAGE_SIZE = 2;
    private static final int FILTER_BOX_HEIGHT = 102;
    private static final int PANEL_MAX_WIDTH = 420;
    private static final int PANEL_MARGIN = 12;
    private static final int DROPDOWN_OPTION_HEIGHT = 20;
    private static final int DROPDOWN_MAX_VISIBLE_OPTIONS = 8;
    private static final int SCROLL_HORIZONTAL_PADDING = 6;

    private final Screen parent;
    private final Consumer<HeldItemRuleManager.CustomRule> onSave;

    private String ruleName;
    private int priority;
    private String effectParams;
    private final List<HeldItemRuleManager.RuleFilter> filters;
    private final List<TooltipArea> tooltipAreas = new ArrayList<>();

    private EditBox nameField;
    private EditBox priorityField;
    private EditBox effectParamsField;
    private EditBox effectParamValueField;
    private DropdownSelector<NbtPreset> nbtPresetDropdown;
    private DropdownSelector<EffectPreset> effectPresetDropdown;
    private DropdownSelector<EffectParameter> effectParameterDropdown;
    private DropdownSelector<EffectValuePreset> effectValuePresetDropdown;
    private int selectedFilterIndex;
    private int filterPage;
    private EffectParameter selectedEffectParameter;
    private EffectValuePreset selectedEffectValuePreset;
    private String effectParamPickerValue;
    private int visibleFilterPageSize = FILTER_PAGE_SIZE;
    private int contentScrollOffset;
    private int maxContentScrollOffset;

    public HeldItemCustomRuleEditScreen(Screen parent, HeldItemRuleManager.CustomRule initialRule,
                                        Consumer<HeldItemRuleManager.CustomRule> onSave) {
        super(Component.translatable("screen.itemglint.rule_editor.rule_entry_title"));
        this.parent = parent;
        this.onSave = onSave;
        HeldItemRuleManager.CustomRule sanitized = (initialRule == null ? HeldItemRuleManager.CustomRule.EMPTY : initialRule).sanitized();
        this.ruleName = sanitized.name();
        this.priority = sanitized.priority();
        this.effectParams = sanitized.effectParams();
        this.filters = new ArrayList<>(sanitized.filters());
        if (this.filters.isEmpty()) {
            this.filters.add(HeldItemRuleManager.RuleFilter.EMPTY);
        }
        this.selectedFilterIndex = 0;
        this.selectedEffectParameter = EffectParameter.WIDTH;
        this.selectedEffectValuePreset = EffectValuePreset.customPreset();
        this.effectParamPickerValue = this.selectedEffectParameter.defaultValue;
        syncEffectParamPickerState();
    }

    @Override
    protected void init() {
        clearWidgets();
        this.tooltipAreas.clear();

        int left = getPanelLeft();
        int right = getPanelRight();
        int panelWidth = getPanelWidth();
        int footerY = getFooterY();
        int gap = 8;
        this.visibleFilterPageSize = computeFilterPageSize(getBaseFilterStartY(), footerY);
        this.maxContentScrollOffset = getMaxContentScrollOffset(footerY);
        this.contentScrollOffset = Mth.clamp(this.contentScrollOffset, 0, this.maxContentScrollOffset);
        int y = 30 - this.contentScrollOffset;

        int priorityWidth = Math.min(96, Math.max(84, panelWidth / 4));
        int nameWidth = panelWidth - priorityWidth - gap;
        this.nameField = new EditBox(this.font, left, y, nameWidth, 20, Component.translatable("screen.itemglint.rule_editor.rule_name"));
        this.nameField.setMaxLength(60);
        this.nameField.setValue(this.ruleName);
        this.nameField.setResponder(value -> this.ruleName = value.trim());
        this.addRenderableWidget(this.nameField);
        addTooltip(this.nameField, Component.translatable("screen.itemglint.rule_editor.tooltip.rule_name"));

        this.priorityField = new EditBox(this.font, right - priorityWidth, y, priorityWidth, 20, Component.translatable("screen.itemglint.rule_editor.rule_priority"));
        this.priorityField.setMaxLength(8);
        this.priorityField.setValue(Integer.toString(this.priority));
        this.priorityField.setResponder(this::onPriorityChanged);
        this.addRenderableWidget(this.priorityField);
        addTooltip(this.priorityField, Component.translatable("screen.itemglint.rule_editor.tooltip.rule_priority"));
        y += 28;

        int addFilterWidth = 82;
        int pageButtonWidth = 74;
        this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.add_filter"), button -> {
                    this.filters.add(HeldItemRuleManager.RuleFilter.EMPTY);
                    this.selectedFilterIndex = this.filters.size() - 1;
                    this.filterPage = this.selectedFilterIndex / Math.max(1, this.visibleFilterPageSize);
                    rebuild();
                }).bounds(left, y, addFilterWidth, 20).build());
        addRenderableOnly(new TextLine(Component.translatable("screen.itemglint.rule_editor.selected_filter", this.selectedFilterIndex + 1, this.filters.size()), left + addFilterWidth + 8, y + 6));
        if (this.filterPage > 0) {
            this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.prev_page"), button -> {
                        this.filterPage--;
                        rebuild();
                    }).bounds(right - pageButtonWidth * 2 - gap, y, pageButtonWidth, 20).build());
        }
        if ((this.filterPage + 1) * Math.max(1, this.visibleFilterPageSize) < this.filters.size()) {
            this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.next_page"), button -> {
                        this.filterPage++;
                        rebuild();
                    }).bounds(right - pageButtonWidth, y, pageButtonWidth, 20).build());
        }
        y += 28;

        this.nbtPresetDropdown = this.addRenderableWidget(new DropdownSelector<>(left, y, panelWidth,
                Component.translatable("screen.itemglint.rule_editor.nbt_preset"),
                List.of(NbtPreset.values()),
                NbtPreset.CUSTOM,
                NbtPreset::label,
                this::applyNbtPreset));
        addTooltip(this.nbtPresetDropdown, Component.translatable("screen.itemglint.rule_editor.tooltip.nbt_preset"));
        y += 28;

        clampFilterPage();
        int start = this.filterPage * this.visibleFilterPageSize;
        int end = Math.min(this.filters.size(), start + this.visibleFilterPageSize);
        int filterRowHeight = getFilterRowHeight();
        for (int index = start; index < end; index++) {
            int rowY = y + (index - start) * filterRowHeight;
            initFilterRow(left, right, panelWidth, rowY, index);
        }
        y += this.visibleFilterPageSize * filterRowHeight + 4;

        this.effectPresetDropdown = this.addRenderableWidget(new DropdownSelector<>(left, y, panelWidth,
                Component.translatable("screen.itemglint.rule_editor.effect_preset"),
                List.of(EffectPreset.values()),
                EffectPreset.CUSTOM,
                EffectPreset::label,
                this::applyEffectPreset));
        addTooltip(this.effectPresetDropdown, Component.translatable("screen.itemglint.rule_editor.tooltip.effect_preset"));
        y += 28;

        syncEffectParamPickerState();
        int splitWidth = (panelWidth - gap) / 2;
        this.effectParameterDropdown = this.addRenderableWidget(new DropdownSelector<>(left, y, splitWidth,
                Component.translatable("screen.itemglint.rule_editor.effect_param_key"),
                List.of(EffectParameter.values()),
                this.selectedEffectParameter,
                EffectParameter::label,
                this::onEffectParameterSelected));
        addTooltip(this.effectParameterDropdown, Component.translatable("screen.itemglint.rule_editor.tooltip.effect_param_key"));

        this.effectValuePresetDropdown = this.addRenderableWidget(new DropdownSelector<>(left + splitWidth + gap, y, splitWidth,
                Component.translatable("screen.itemglint.rule_editor.effect_param_value_preset"),
                this.selectedEffectParameter.dropdownValues(),
                this.selectedEffectValuePreset,
                EffectValuePreset::label,
                this::onEffectValuePresetSelected));
        addTooltip(this.effectValuePresetDropdown, Component.translatable("screen.itemglint.rule_editor.tooltip.effect_param_value_preset"));
        y += 28;

        int actionButtonWidth = Math.min(84, Math.max(62, panelWidth / 5));
        int valueFieldWidth = panelWidth - actionButtonWidth * 2 - gap * 2;
        this.effectParamValueField = new EditBox(this.font, left, y, valueFieldWidth, 20, Component.translatable("screen.itemglint.rule_editor.effect_param_value"));
        this.effectParamValueField.setMaxLength(120);
        this.effectParamValueField.setValue(this.effectParamPickerValue);
        this.effectParamValueField.setResponder(value -> {
            this.effectParamPickerValue = value.trim();
            this.selectedEffectValuePreset = this.selectedEffectParameter.matchPreset(this.effectParamPickerValue);
        });
        this.addRenderableWidget(this.effectParamValueField);
        addTooltip(this.effectParamValueField, Component.translatable("screen.itemglint.rule_editor.tooltip.effect_param_value"));

        this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.effect_param_apply"), button -> applySelectedEffectParameter())
                .bounds(left + valueFieldWidth + gap, y, actionButtonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.effect_param_remove"), button -> removeSelectedEffectParameter())
                .bounds(right - actionButtonWidth, y, actionButtonWidth, 20).build());
        y += 28;

        this.effectParamsField = new EditBox(this.font, left, y, panelWidth, 20, Component.translatable("screen.itemglint.rule_editor.effect_params"));
        this.effectParamsField.setMaxLength(512);
        this.effectParamsField.setValue(this.effectParams);
        this.effectParamsField.setResponder(value -> this.effectParams = value.trim());
        this.addRenderableWidget(this.effectParamsField);
        addTooltip(this.effectParamsField, Component.translatable("screen.itemglint.rule_editor.tooltip.effect_params"));

        int footerGap = 8;
        int footerLeft = left + Math.max(0, (panelWidth - (96 * 2 + footerGap)) / 2);
        this.addRenderableOnly(new FooterMaskWidget(left - SCROLL_HORIZONTAL_PADDING, getFooterMaskTop(), panelWidth + SCROLL_HORIZONTAL_PADDING * 2, this.height - getFooterMaskTop()));
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> closeToParent())
                .bounds(footerLeft, footerY, 96, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> saveAndClose())
                .bounds(footerLeft + 96 + footerGap, footerY, 96, 20)
                .build());
    }

    private void initFilterRow(int left, int right, int panelWidth, int y, int index) {
        HeldItemRuleManager.RuleFilter filter = this.filters.get(index);
        int localIndex = index;
        int boxHeight = getFilterBoxHeight();
        int innerLeft = left + 8;
        int innerWidth = panelWidth - 16;
        int columnGap = 12;
        int columnWidth = (innerWidth - columnGap) / 2;
        int matchLabelY = y + 24;
        int matchButtonY = y + 36;
        int fieldY = y + boxHeight - 24;
        int fieldLabelY = fieldY - 12;

        addRenderableOnly(new FilterBox(left, y, panelWidth, boxHeight));
        addRenderableOnly(new TextLine(Component.translatable("screen.itemglint.rule_editor.filter_index", index + 1), innerLeft, y + 6));
        Button removeButton = this.addRenderableWidget(Button.builder(Component.literal("-"), button -> removeFilter(localIndex))
                .bounds(right - 30, y + 2, 22, 20).build());
        removeButton.active = this.filters.size() > 1;

        addRenderableOnly(new TextLine(Component.translatable("screen.itemglint.rule_editor.filter_target_match_mode"), innerLeft, matchLabelY));
        AbstractWidget matchModeButton = this.addRenderableWidget(CycleButton.<HeldItemRuleManager.NbtMatchMode>builder(HeldItemRuleManager.NbtMatchMode::label)
                .withValues(HeldItemRuleManager.NbtMatchMode.values())
                .withInitialValue(filter.matchMode())
                .displayOnlyValue()
                .create(innerLeft, matchButtonY, innerWidth, 20, Component.translatable("screen.itemglint.rule_editor.filter_target_match_mode"),
                        (button, value) -> updateFilter(localIndex, null, null, value)));
        addTooltip(matchModeButton, Component.translatable("screen.itemglint.rule_editor.tooltip.match_mode"));

        addRenderableOnly(new TextLine(Component.translatable("screen.itemglint.rule_editor.filter_target_nbt_path"), innerLeft, fieldLabelY));
        EditBox nbtPathField = new EditBox(this.font, innerLeft, fieldY, columnWidth, 18, Component.translatable("screen.itemglint.rule_editor.nbt_path"));
        nbtPathField.setMaxLength(120);
        nbtPathField.setValue(filter.nbtPath());
        nbtPathField.setResponder(value -> updateFilter(localIndex, value, null, null));
        this.addRenderableWidget(nbtPathField);
        addTooltip(nbtPathField, Component.translatable("screen.itemglint.rule_editor.tooltip.nbt_path"));

        int valueX = innerLeft + columnWidth + columnGap;
        addRenderableOnly(new TextLine(Component.translatable("screen.itemglint.rule_editor.filter_target_nbt_value"), valueX, fieldLabelY));
        EditBox nbtValueField = new EditBox(this.font, valueX, fieldY, columnWidth, 18, Component.translatable("screen.itemglint.rule_editor.nbt_value"));
        nbtValueField.setMaxLength(120);
        nbtValueField.setValue(filter.nbtValue());
        nbtValueField.setResponder(value -> updateFilter(localIndex, null, value, null));
        this.addRenderableWidget(nbtValueField);
        addTooltip(nbtValueField, Component.translatable("screen.itemglint.rule_editor.tooltip.nbt_value"));
    }

    private void onPriorityChanged(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed)) {
            this.priority = 0;
            this.priorityField.setTextColor(0xE0E0E0);
            return;
        }
        try {
            this.priority = Mth.clamp(Integer.parseInt(trimmed), -9999, 9999);
            this.priorityField.setTextColor(0xE0E0E0);
        } catch (NumberFormatException exception) {
            this.priorityField.setTextColor(0xFF8080);
        }
    }

    private void updateFilter(int index, String nbtPath, String nbtValue, HeldItemRuleManager.NbtMatchMode matchMode) {
        if (index < 0 || index >= this.filters.size()) {
            return;
        }
        this.selectedFilterIndex = index;
        HeldItemRuleManager.RuleFilter current = this.filters.get(index);
        this.filters.set(index, new HeldItemRuleManager.RuleFilter(
                "",
                nbtPath != null ? nbtPath.trim() : current.nbtPath(),
                nbtValue != null ? nbtValue.trim() : current.nbtValue(),
                matchMode != null ? matchMode : current.matchMode()
        ).sanitized());
    }

    private void removeFilter(int index) {
        if (this.filters.size() <= 1) {
            this.filters.set(0, HeldItemRuleManager.RuleFilter.EMPTY);
        } else if (index >= 0 && index < this.filters.size()) {
            this.filters.remove(index);
        }
        this.selectedFilterIndex = Mth.clamp(this.selectedFilterIndex, 0, this.filters.size() - 1);
        clampFilterPage();
        rebuild();
    }

    private void applyNbtPreset(NbtPreset preset) {
        if (preset == NbtPreset.CUSTOM || this.selectedFilterIndex < 0 || this.selectedFilterIndex >= this.filters.size()) {
            return;
        }
        updateFilter(this.selectedFilterIndex, preset.path, preset.value, null);
        rebuild();
    }

    private void applyEffectPreset(EffectPreset preset) {
        if (preset == EffectPreset.CUSTOM || this.effectParamsField == null) {
            return;
        }
        this.effectParamsField.setValue(preset.params);
        syncEffectParamPickerState();
        rebuild();
    }

    private void onEffectParameterSelected(EffectParameter parameter) {
        if (parameter == null) {
            return;
        }
        this.selectedEffectParameter = parameter;
        syncEffectParamPickerState();
        rebuild();
    }

    private void onEffectValuePresetSelected(EffectValuePreset preset) {
        if (preset == null) {
            return;
        }
        this.selectedEffectValuePreset = preset;
        if (!preset.isCustom()) {
            this.effectParamPickerValue = preset.value();
            if (this.effectParamValueField != null) {
                this.effectParamValueField.setValue(this.effectParamPickerValue);
            }
        }
    }

    private void syncEffectParamPickerState() {
        String currentValue = findEffectParamValue(this.effectParams, this.selectedEffectParameter.paramKey);
        this.effectParamPickerValue = currentValue != null ? currentValue : this.selectedEffectParameter.defaultValue;
        this.selectedEffectValuePreset = this.selectedEffectParameter.matchPreset(this.effectParamPickerValue);
    }

    private void applySelectedEffectParameter() {
        String value = this.effectParamValueField != null ? this.effectParamValueField.getValue().trim() : this.effectParamPickerValue;
        if (value.isEmpty()) {
            return;
        }
        LinkedHashMap<String, String> params = parseEffectParams(this.effectParams);
        params.put(this.selectedEffectParameter.paramKey, value);
        applyEffectParamMap(params);
    }

    private void removeSelectedEffectParameter() {
        LinkedHashMap<String, String> params = parseEffectParams(this.effectParams);
        params.remove(this.selectedEffectParameter.paramKey);
        applyEffectParamMap(params);
    }

    private void applyEffectParamMap(LinkedHashMap<String, String> params) {
        this.effectParams = joinEffectParams(params);
        syncEffectParamPickerState();
        rebuild();
    }

    private static LinkedHashMap<String, String> parseEffectParams(String effectParams) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (effectParams == null || effectParams.isBlank()) {
            return values;
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
            if (!key.isEmpty() && !value.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static String joinEffectParams(LinkedHashMap<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private static String findEffectParamValue(String effectParams, String key) {
        return parseEffectParams(effectParams).get(key);
    }

    private void clampFilterPage() {
        int pageSize = Math.max(1, this.visibleFilterPageSize);
        int maxPage = Math.max(0, (this.filters.size() - 1) / pageSize);
        if (this.filterPage > maxPage) {
            this.filterPage = maxPage;
        }
    }

    private void rebuild() {
        this.nameField = null;
        this.priorityField = null;
        this.effectParamsField = null;
        this.effectParamValueField = null;
        this.nbtPresetDropdown = null;
        this.effectPresetDropdown = null;
        this.effectParameterDropdown = null;
        this.effectValuePresetDropdown = null;
        if (this.minecraft != null) {
            init(this.minecraft, this.width, this.height);
        }
    }

    private void saveAndClose() {
        List<HeldItemRuleManager.RuleFilter> sanitizedFilters = this.filters.stream()
                .map(HeldItemRuleManager.RuleFilter::sanitized)
                .toList();
        this.onSave.accept(new HeldItemRuleManager.CustomRule(this.ruleName, this.priority, sanitizedFilters, this.effectParams).sanitized());
        closeToParent();
    }

    private void closeToParent() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private void addTooltip(AbstractWidget widget, Component tooltip) {
        addTooltipRect(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(), tooltip);
    }

    private void addTooltipRect(int x, int y, int width, int height, Component tooltip) {
        this.tooltipAreas.add(new TooltipArea(x, y, width, height, tooltip));
    }

    private void renderHoveredTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if ((this.nbtPresetDropdown != null && this.nbtPresetDropdown.expanded)
                || (this.effectPresetDropdown != null && this.effectPresetDropdown.expanded)
                || (this.effectParameterDropdown != null && this.effectParameterDropdown.expanded)
                || (this.effectValuePresetDropdown != null && this.effectValuePresetDropdown.expanded)) {
            return;
        }
        for (int i = this.tooltipAreas.size() - 1; i >= 0; i--) {
            TooltipArea area = this.tooltipAreas.get(i);
            if (area.contains(mouseX, mouseY)) {
                graphics.renderTooltip(this.font, this.font.split(area.tooltip(), 220), mouseX, mouseY);
                return;
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.enableScissor(getPanelLeft() - SCROLL_HORIZONTAL_PADDING, 24, getPanelRight() + SCROLL_HORIZONTAL_PADDING, this.height);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderExpandedDropdowns(graphics, mouseX, mouseY, partialTick);
        graphics.disableScissor();
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        renderHoveredTooltip(graphics, mouseX, mouseY);
    }

    private int getPanelWidth() {
        return Math.min(PANEL_MAX_WIDTH, Math.max(280, this.width - PANEL_MARGIN * 2));
    }

    private int getPanelLeft() {
        return this.width / 2 - getPanelWidth() / 2;
    }

    private int getPanelRight() {
        return getPanelLeft() + getPanelWidth();
    }

    private int getFooterY() {
        return this.height - 28;
    }

    private int getFooterButtonY() {
        return getFooterY();
    }

    private int getFooterButtonsLeft() {
        int footerGap = 8;
        return getPanelLeft() + Math.max(0, (getPanelWidth() - (96 * 2 + footerGap)) / 2);
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
        int left = getFooterButtonsLeft();
        return (mouseX >= left && mouseX < left + 96) || (mouseX >= left + 104 && mouseX < left + 200);
    }

    private boolean isInFooterMaskArea(double mouseX, double mouseY) {
        return mouseX >= getPanelLeft() - SCROLL_HORIZONTAL_PADDING
                && mouseX < getPanelRight() + SCROLL_HORIZONTAL_PADDING
                && mouseY >= getFooterMaskTop();
    }

    private boolean isInHiddenPanelArea(double mouseX, double mouseY) {
        return mouseX >= getPanelLeft() - SCROLL_HORIZONTAL_PADDING
                && mouseX < getPanelRight() + SCROLL_HORIZONTAL_PADDING
                && (mouseY < 24 || mouseY >= getFooterMaskTop());
    }

    private int getFooterMaskTop() {
        return this.height - 38;
    }

    private int getScrollViewportBottom() {
        return getFooterMaskTop() - 6;
    }

    private int getScrollStopY() {
        return Math.max(36, this.height / 2);
    }

    private int getBaseFilterStartY() {
        return 86;
    }

    private int getFilterBoxHeight() {
        return this.height < 420 ? 88 : FILTER_BOX_HEIGHT;
    }

    private int getFilterRowHeight() {
        return getFilterBoxHeight() + 10;
    }

    private int computeFilterPageSize(int filterStartY, int footerY) {
        int reservedBottomHeight = 112;
        int availableHeight = footerY - reservedBottomHeight - filterStartY;
        return Mth.clamp(Math.max(1, availableHeight / getFilterRowHeight()), 1, FILTER_PAGE_SIZE);
    }

    private int getMaxContentScrollOffset(int footerY) {
        int logicalBottom = 30 + 28 + 28 + this.visibleFilterPageSize * getFilterRowHeight() + 4 + 28 + 28 + 28 + 20;
        return Math.max(0, logicalBottom - getScrollStopY());
    }

    private boolean isMouseOverScrollableArea(double mouseX, double mouseY) {
        return mouseX >= getPanelLeft() && mouseX < getPanelRight() && mouseY >= 24 && mouseY < getScrollViewportBottom();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isInHiddenPanelArea(mouseX, mouseY) && !isMouseOverFooterButtons(mouseX, mouseY)) {
            closeOtherDropdowns(null);
            return true;
        }
        boolean handled = mouseClickedDropdowns(mouseX, mouseY, button);
        if (!handled) {
            handled = dispatchMouseClickTopmost(mouseX, mouseY, button);
        }
        if (!handled) {
            if (isInFooterMaskArea(mouseX, mouseY) && !isMouseOverFooterButtons(mouseX, mouseY)) {
                closeOtherDropdowns(null);
                return true;
            }
            closeOtherDropdowns(null);
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (mouseScrolledDropdowns(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (scrollY != 0.0D && isMouseOverScrollableArea(mouseX, mouseY) && this.maxContentScrollOffset > 0) {
            int direction = scrollY > 0.0D ? -1 : 1;
            int nextOffset = Mth.clamp(this.contentScrollOffset + direction * 20, 0, this.maxContentScrollOffset);
            if (nextOffset != this.contentScrollOffset) {
                this.contentScrollOffset = nextOffset;
                rebuild();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    private boolean mouseClickedDropdowns(double mouseX, double mouseY, int button) {
        boolean hadExpanded = (this.nbtPresetDropdown != null && this.nbtPresetDropdown.expanded)
                || (this.effectPresetDropdown != null && this.effectPresetDropdown.expanded)
                || (this.effectParameterDropdown != null && this.effectParameterDropdown.expanded)
                || (this.effectValuePresetDropdown != null && this.effectValuePresetDropdown.expanded);
        if (this.effectValuePresetDropdown != null && this.effectValuePresetDropdown.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (this.effectParameterDropdown != null && this.effectParameterDropdown.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (this.effectPresetDropdown != null && this.effectPresetDropdown.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (this.nbtPresetDropdown != null && this.nbtPresetDropdown.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (hadExpanded) {
            closeOtherDropdowns(null);
            return true;
        }
        return false;
    }

    private boolean mouseScrolledDropdowns(double mouseX, double mouseY, double scrollY) {
        if (this.effectValuePresetDropdown != null && this.effectValuePresetDropdown.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (this.effectParameterDropdown != null && this.effectParameterDropdown.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (this.effectPresetDropdown != null && this.effectPresetDropdown.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return this.nbtPresetDropdown != null && this.nbtPresetDropdown.mouseScrolled(mouseX, mouseY, scrollY);
    }

    private void renderExpandedDropdowns(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.nbtPresetDropdown != null) {
            this.nbtPresetDropdown.renderOverlay(graphics, mouseX, mouseY, partialTick);
        }
        if (this.effectPresetDropdown != null) {
            this.effectPresetDropdown.renderOverlay(graphics, mouseX, mouseY, partialTick);
        }
        if (this.effectParameterDropdown != null) {
            this.effectParameterDropdown.renderOverlay(graphics, mouseX, mouseY, partialTick);
        }
        if (this.effectValuePresetDropdown != null) {
            this.effectValuePresetDropdown.renderOverlay(graphics, mouseX, mouseY, partialTick);
        }
    }

    private void closeOtherDropdowns(DropdownSelector<?> active) {
        if (this.nbtPresetDropdown != null && this.nbtPresetDropdown != active) {
            this.nbtPresetDropdown.expanded = false;
        }
        if (this.effectPresetDropdown != null && this.effectPresetDropdown != active) {
            this.effectPresetDropdown.expanded = false;
        }
        if (this.effectParameterDropdown != null && this.effectParameterDropdown != active) {
            this.effectParameterDropdown.expanded = false;
        }
        if (this.effectValuePresetDropdown != null && this.effectValuePresetDropdown != active) {
            this.effectValuePresetDropdown.expanded = false;
        }
    }

    @Override
    public void onClose() {
        closeToParent();
    }

    private final class TextLine extends AbstractWidget {
        private final Component text;

        private TextLine(Component text, int x, int y) {
            super(x, y, 0, 0, Component.empty());
            this.text = text;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.drawString(font, this.text, getX(), getY(), 0xE0E6EE, false);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        }
    }

    private final class DropdownSelector<T> extends AbstractWidget {
        private final Component title;
        private final List<T> options;
        private final java.util.function.Function<T, Component> labeler;
        private final Consumer<T> onSelect;
        private T selected;
        private boolean expanded;
        private int scrollOffset;

        private DropdownSelector(int x, int y, int width, Component title, List<T> options, T selected,
                                 java.util.function.Function<T, Component> labeler, Consumer<T> onSelect) {
            super(x, y, width, 20, Component.empty());
            this.title = title;
            this.options = options;
            this.selected = selected;
            this.labeler = labeler;
            this.onSelect = onSelect;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getX() + this.width, getY() + this.height, 0xFF202834);
            graphics.renderOutline(getX(), getY(), this.width, this.height, 0xFF4C5668);
            graphics.drawString(font, this.title, getX() + 6, getY() + 6, 0xAAB3C3, false);
            graphics.drawString(font, this.labeler.apply(this.selected), getX() + 118, getY() + 6, 0xFFFFFF, false);
            graphics.drawString(font, Component.literal(this.expanded ? "^" : "v"), getX() + this.width - 12, getY() + 6, 0xA7E7FF, false);
        }

        private void renderOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!this.expanded) {
                return;
            }
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 400.0F);
            int visibleCount = visibleOptionCount();
            for (int visibleIndex = 0; visibleIndex < visibleCount; visibleIndex++) {
                int optionIndex = this.scrollOffset + visibleIndex;
                int optionY = getY() + this.height + visibleIndex * DROPDOWN_OPTION_HEIGHT;
                boolean hovered = mouseX >= getX() && mouseX < getX() + this.width && mouseY >= optionY && mouseY < optionY + 20;
                graphics.fill(getX(), optionY, getX() + this.width, optionY + 20, hovered ? 0xFF303A48 : 0xFF202834);
                graphics.renderOutline(getX(), optionY, this.width, 20, 0xFF4C5668);
                if (optionIndex >= 0 && optionIndex < this.options.size()) {
                    graphics.drawString(font, this.labeler.apply(this.options.get(optionIndex)), getX() + 8, optionY + 6, 0xE0E6EE, false);
                }
            }
            if (this.options.size() > visibleCount) {
                int overlayTop = getY() + this.height;
                int overlayHeight = visibleCount * DROPDOWN_OPTION_HEIGHT;
                int barLeft = getX() + this.width - 4;
                graphics.fill(barLeft, overlayTop, barLeft + 2, overlayTop + overlayHeight, 0xFF161C24);
                int maxOffset = Math.max(1, maxScrollOffset());
                int totalScrollableRows = this.options.size() + visibleCount;
                int thumbHeight = Math.max(12, overlayHeight * visibleCount / Math.max(1, totalScrollableRows));
                int thumbTravel = Math.max(0, overlayHeight - thumbHeight);
                int thumbTop = overlayTop + (int) (thumbTravel * (this.scrollOffset / (float) maxOffset));
                graphics.fill(barLeft, thumbTop, barLeft + 2, thumbTop + thumbHeight, 0xFFA7E7FF);
            }
            graphics.pose().popPose();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            if (isMouseOver(mouseX, mouseY)) {
                closeOtherDropdowns(this);
                this.expanded = !this.expanded;
                return true;
            }
            if (this.expanded) {
                if (isMouseOverExpanded(mouseX, mouseY)) {
                    int visibleCount = visibleOptionCount();
                    for (int visibleIndex = 0; visibleIndex < visibleCount; visibleIndex++) {
                        int optionIndex = this.scrollOffset + visibleIndex;
                        int optionY = getY() + this.height + visibleIndex * DROPDOWN_OPTION_HEIGHT;
                        if (mouseX >= getX() && mouseX < getX() + this.width && mouseY >= optionY && mouseY < optionY + 20) {
                            if (optionIndex >= 0 && optionIndex < this.options.size()) {
                                this.selected = this.options.get(optionIndex);
                                this.expanded = false;
                                this.onSelect.accept(this.selected);
                            }
                            return true;
                        }
                    }
                    return true;
                }
                this.expanded = false;
            }
            return false;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            if (!this.expanded || delta == 0.0D || !isMouseOverExpanded(mouseX, mouseY)) {
                return false;
            }
            int maxOffset = maxScrollOffset();
            if (maxOffset <= 0) {
                return true;
            }
            int direction = delta > 0.0D ? -1 : 1;
            this.scrollOffset = Mth.clamp(this.scrollOffset + direction, 0, maxOffset);
            return true;
        }

        private int visibleOptionCount() {
            return Math.min(this.options.size(), DROPDOWN_MAX_VISIBLE_OPTIONS);
        }

        private int maxScrollOffset() {
            int visibleCount = visibleOptionCount();
            if (this.options.size() <= visibleCount) {
                return 0;
            }
            return this.options.size();
        }

        private boolean isMouseOverExpanded(double mouseX, double mouseY) {
            if (isMouseOver(mouseX, mouseY)) {
                return true;
            }
            int overlayTop = getY() + this.height;
            int overlayBottom = overlayTop + visibleOptionCount() * DROPDOWN_OPTION_HEIGHT;
            return mouseX >= getX() && mouseX < getX() + this.width && mouseY >= overlayTop && mouseY < overlayBottom;
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        }
    }

    private enum NbtPreset {
        CUSTOM("screen.itemglint.rule_editor.nbt_preset.custom", "", ""),
        POTION("screen.itemglint.rule_editor.nbt_preset.potion", "Potion", "minecraft:long_night_vision"),
        ENCHANT_FIRST("screen.itemglint.rule_editor.nbt_preset.enchant", "Enchantments[0].id", "minecraft:sharpness"),
        ENCHANT_LEVEL("screen.itemglint.rule_editor.nbt_preset.enchant_level", "Enchantments[0].lvl", "5"),
        STORED_ENCHANT("screen.itemglint.rule_editor.nbt_preset.stored_enchant", "StoredEnchantments[0].id", "minecraft:mending"),
        DISPLAY_NAME("screen.itemglint.rule_editor.nbt_preset.display_name", "display.Name", ""),
        DISPLAY_LORE("screen.itemglint.rule_editor.nbt_preset.display_lore", "display.Lore[0]", ""),
        CUSTOM_MODEL("screen.itemglint.rule_editor.nbt_preset.custom_model", "CustomModelData", "1"),
        UNBREAKABLE("screen.itemglint.rule_editor.nbt_preset.unbreakable", "Unbreakable", "1"),
        DAMAGE("screen.itemglint.rule_editor.nbt_preset.damage", "Damage", "0"),
        REPAIR_COST("screen.itemglint.rule_editor.nbt_preset.repair_cost", "RepairCost", "1"),
        BLOCK_ENTITY_ITEM("screen.itemglint.rule_editor.nbt_preset.block_entity", "BlockEntityTag.Items[0]", ""),
        BLOCK_LOCK("screen.itemglint.rule_editor.nbt_preset.block_lock", "BlockEntityTag.Lock", ""),
        SKULL_OWNER("screen.itemglint.rule_editor.nbt_preset.skull_owner", "SkullOwner.Name", ""),
        TRIM_MATERIAL("screen.itemglint.rule_editor.nbt_preset.trim_material", "Trim.material", "minecraft:gold"),
        TRIM_PATTERN("screen.itemglint.rule_editor.nbt_preset.trim_pattern", "Trim.pattern", "minecraft:sentry");

        private final String key;
        private final String path;
        private final String value;

        NbtPreset(String key, String path, String value) {
            this.key = key;
            this.path = path;
            this.value = value;
        }

        private Component label() {
            return Component.translatable(this.key);
        }
    }

    private final class FilterBox extends AbstractWidget {
        private FilterBox(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getX() + this.width, getY() + this.height, 0xD01B2330);
            graphics.fill(getX(), getY(), getX() + this.width, getY() + 18, 0xFF273142);
            graphics.renderOutline(getX(), getY(), this.width, this.height, 0xFF4C5668);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
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
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        }
    }

    private record TooltipArea(int x, int y, int width, int height, Component tooltip) {
        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
        }
    }

    private enum EffectPreset {
        CUSTOM("screen.itemglint.rule_editor.effect_preset.custom", ""),
        CLEAN("screen.itemglint.rule_editor.effect_preset.clean", "width=2.5,bloom=false,glow_strength=0.25"),
        GOLD("screen.itemglint.rule_editor.effect_preset.gold", "primary=#FFAA00,width=3.5,bloom=false"),
        RAINBOW("screen.itemglint.rule_editor.effect_preset.rainbow", "color_mode=rainbow_flow,glow_strength=1.2"),
        SOFT_BLOOM("screen.itemglint.rule_editor.effect_preset.soft_bloom", "primary=#66CCFF,bloom=true,bloom_strength=1.8,bloom_radius=2.2"),
        SHARP_RED("screen.itemglint.rule_editor.effect_preset.sharp_red", "primary=#FF4040,width=2.0,softness=0.8,bloom=false"),
        VOID_PURPLE("screen.itemglint.rule_editor.effect_preset.void_purple", "primary=#8B5CF6,secondary=#1E1B4B,color_mode=dual_scroll,bloom=true,glow_strength=1.1");

        private final String key;
        private final String params;

        EffectPreset(String key, String params) {
            this.key = key;
            this.params = params;
        }

        private Component label() {
            return Component.translatable(this.key);
        }
    }

    private enum EffectParameter {
        WIDTH("width", "screen.itemglint.shader_config.outline.width", "2.5",
                EffectValuePreset.literal("1.5"), EffectValuePreset.literal("2.5"), EffectValuePreset.literal("3.5")),
        SOFTNESS("softness", "screen.itemglint.shader_config.outline.softness", "1.35",
                EffectValuePreset.literal("0.8"), EffectValuePreset.literal("1.35"), EffectValuePreset.literal("2.0")),
        ALPHA_THRESHOLD("alpha_threshold", "screen.itemglint.shader_config.outline.alpha_threshold", "0.1",
                EffectValuePreset.literal("0.05"), EffectValuePreset.literal("0.1"), EffectValuePreset.literal("0.25")),
        OPACITY("opacity", "screen.itemglint.shader_config.outline.opacity", "0.95",
                EffectValuePreset.literal("0.6"), EffectValuePreset.literal("0.8"), EffectValuePreset.literal("1.0")),
        BLOOM("bloom", "screen.itemglint.shader_config.outline.bloom", "true",
                EffectValuePreset.translatable("true", "options.on"), EffectValuePreset.translatable("false", "options.off")),
        BLOOM_RESOLUTION("bloom_resolution", "screen.itemglint.shader_config.outline.bloom_resolution", "half",
                EffectValuePreset.translatable("full", "screen.itemglint.shader_config.bloom_resolution.full"),
                EffectValuePreset.translatable("half", "screen.itemglint.shader_config.bloom_resolution.half"),
                EffectValuePreset.translatable("quarter", "screen.itemglint.shader_config.bloom_resolution.quarter")),
        BLOOM_STRENGTH("bloom_strength", "screen.itemglint.shader_config.outline.bloom_strength", "1.8",
                EffectValuePreset.literal("1.0"), EffectValuePreset.literal("1.8"), EffectValuePreset.literal("2.5")),
        BLOOM_RADIUS("bloom_radius", "screen.itemglint.shader_config.outline.bloom_radius", "2.2",
                EffectValuePreset.literal("1.2"), EffectValuePreset.literal("2.2"), EffectValuePreset.literal("3.5")),
        BLOOM_MAX_PASSES("bloom_max_passes", "screen.itemglint.shader_config.outline.bloom_max_passes", "4",
                EffectValuePreset.literal("2"), EffectValuePreset.literal("4"), EffectValuePreset.literal("6")),
        COLOR_MODE("color_mode", "screen.itemglint.shader_config.outline.color_mode", "single",
                EffectValuePreset.translatable("single", "screen.itemglint.shader_config.color_mode.single"),
                EffectValuePreset.translatable("dual_scroll", "screen.itemglint.shader_config.color_mode.dual_scroll"),
                EffectValuePreset.translatable("rainbow_flow", "screen.itemglint.shader_config.color_mode.rainbow_flow"),
                EffectValuePreset.translatable("auto_sample_scroll", "screen.itemglint.shader_config.color_mode.auto_sample_scroll")),
        PRIMARY("primary", "screen.itemglint.quick_config.target.primary", "#66CCFF",
                EffectValuePreset.literal("#66CCFF"), EffectValuePreset.literal("#FFAA00"), EffectValuePreset.literal("#FF4040")),
        SECONDARY("secondary", "screen.itemglint.quick_config.target.secondary", "#1E1B4B",
                EffectValuePreset.literal("#1E1B4B"), EffectValuePreset.literal("#FF66CC"), EffectValuePreset.literal("#FFFFFF")),
        RED("red", "screen.itemglint.shader_config.outline.red", "1.0",
                EffectValuePreset.literal("0.25"), EffectValuePreset.literal("0.5"), EffectValuePreset.literal("1.0")),
        GREEN("green", "screen.itemglint.shader_config.outline.green", "1.0",
                EffectValuePreset.literal("0.25"), EffectValuePreset.literal("0.5"), EffectValuePreset.literal("1.0")),
        BLUE("blue", "screen.itemglint.shader_config.outline.blue", "1.0",
                EffectValuePreset.literal("0.25"), EffectValuePreset.literal("0.5"), EffectValuePreset.literal("1.0")),
        SECONDARY_RED("secondary_red", "screen.itemglint.shader_config.outline.secondary_red", "1.0",
                EffectValuePreset.literal("0.25"), EffectValuePreset.literal("0.5"), EffectValuePreset.literal("1.0")),
        SECONDARY_GREEN("secondary_green", "screen.itemglint.shader_config.outline.secondary_green", "1.0",
                EffectValuePreset.literal("0.25"), EffectValuePreset.literal("0.5"), EffectValuePreset.literal("1.0")),
        SECONDARY_BLUE("secondary_blue", "screen.itemglint.shader_config.outline.secondary_blue", "1.0",
                EffectValuePreset.literal("0.25"), EffectValuePreset.literal("0.5"), EffectValuePreset.literal("1.0")),
        COLOR_SCROLL_SPEED("color_scroll_speed", "screen.itemglint.shader_config.outline.color_scroll_speed", "1.0",
                EffectValuePreset.literal("0.5"), EffectValuePreset.literal("1.0"), EffectValuePreset.literal("2.0")),
        DEPTH_WEIGHT("depth_weight", "screen.itemglint.shader_config.outline.depth_weight", "1.15",
                EffectValuePreset.literal("0.5"), EffectValuePreset.literal("1.15"), EffectValuePreset.literal("2.0")),
        GLOW_STRENGTH("glow_strength", "screen.itemglint.shader_config.outline.glow_strength", "0.55",
                EffectValuePreset.literal("0.25"), EffectValuePreset.literal("0.55"), EffectValuePreset.literal("1.2"));

        private final String paramKey;
        private final String labelKey;
        private final String defaultValue;
        private final List<EffectValuePreset> presets;

        EffectParameter(String paramKey, String labelKey, String defaultValue, EffectValuePreset... presets) {
            this.paramKey = paramKey;
            this.labelKey = labelKey;
            this.defaultValue = defaultValue;
            this.presets = List.of(presets);
        }

        private Component label() {
            return Component.translatable(this.labelKey);
        }

        private List<EffectValuePreset> dropdownValues() {
            List<EffectValuePreset> values = new ArrayList<>();
            values.add(EffectValuePreset.customPreset());
            values.addAll(this.presets);
            return values;
        }

        private EffectValuePreset matchPreset(String value) {
            for (EffectValuePreset preset : this.presets) {
                if (preset.matches(value)) {
                    return preset;
                }
            }
            return EffectValuePreset.customPreset();
        }
    }

    private record EffectValuePreset(String value, String labelKey, String literalLabel, boolean isCustom) {
        private static EffectValuePreset customPreset() {
            return new EffectValuePreset("", "screen.itemglint.rule_editor.effect_value_preset.custom", "", true);
        }

        private static EffectValuePreset literal(String value) {
            return new EffectValuePreset(value, "", value, false);
        }

        private static EffectValuePreset translatable(String value, String labelKey) {
            return new EffectValuePreset(value, labelKey, "", false);
        }

        private Component label() {
            return !this.labelKey.isEmpty() ? Component.translatable(this.labelKey) : Component.literal(this.literalLabel);
        }

        private boolean matches(String other) {
            return !this.isCustom && this.value.equalsIgnoreCase(other == null ? "" : other.trim());
        }
    }
}
