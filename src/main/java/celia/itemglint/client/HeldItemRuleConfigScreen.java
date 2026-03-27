package celia.itemglint.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public class HeldItemRuleConfigScreen extends Screen {
    private static final int LIST_PAGE_SIZE = 7;
    private static final int RULE_PAGE_SIZE = 5;
    private static final int PANEL_MAX_WIDTH = 420;
    private static final int PANEL_MARGIN = 12;

    private final Screen parent;
    private final Consumer<HeldItemRuleManager.StateSnapshot> onSave;

    private HeldItemRuleManager.RuleMode ruleMode;
    private final List<String> whitelistEntries;
    private final List<String> blacklistEntries;
    private final List<HeldItemRuleManager.CustomRule> customRules;

    private EditBox listEntryField;
    private int listPage;
    private int rulePage;

    public HeldItemRuleConfigScreen(Screen parent, HeldItemRuleManager.StateSnapshot initialState,
                                    Consumer<HeldItemRuleManager.StateSnapshot> onSave) {
        super(Component.translatable("screen.itemglint.rule_editor.title"));
        this.parent = parent;
        this.onSave = onSave;
        this.ruleMode = initialState.ruleMode();
        this.whitelistEntries = new ArrayList<>(initialState.whitelistEntries());
        this.blacklistEntries = new ArrayList<>(initialState.blacklistEntries());
        this.customRules = new ArrayList<>(initialState.customRules());
        sortCustomRules();
    }

    @Override
    protected void init() {
        clearWidgets();

        int left = getPanelLeft();
        int right = getPanelRight();
        int panelWidth = getPanelWidth();
        int footerY = getFooterY();
        int y = 36;

        this.addRenderableWidget(CycleButton.<HeldItemRuleManager.RuleMode>builder(HeldItemRuleManager.RuleMode::label)
                .withValues(HeldItemRuleManager.RuleMode.values())
                .withInitialValue(this.ruleMode)
                .displayOnlyValue()
                .create(left, y, panelWidth, 20, Component.translatable("screen.itemglint.shader_config.outline.rule_mode"),
                        (button, value) -> {
                            this.ruleMode = value;
                            this.listPage = 0;
                            this.rulePage = 0;
                            rebuild();
                        }));
        y += 28;

        switch (this.ruleMode) {
            case ALL -> initAllMode(left, y);
            case WHITELIST, BLACKLIST -> initListMode(left, right, panelWidth, y, footerY);
            case CUSTOM -> initCustomMode(left, right, panelWidth, y, footerY);
        }

        int footerGap = 8;
        int footerWidth = 96 * 2 + footerGap;
        int footerLeft = left + Math.max(0, (panelWidth - footerWidth) / 2);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> closeToParent())
                .bounds(footerLeft, footerY, 96, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> saveAndClose())
                .bounds(footerLeft + 96 + footerGap, footerY, 96, 20)
                .build());
    }

    private void initAllMode(int left, int y) {
        addInfoLine(left, y, "screen.itemglint.rule_editor.all_mode_hint");
        addInfoLine(left, y + 14, "screen.itemglint.rule_editor.all_mode_hint_2");
    }

    private void initListMode(int left, int right, int panelWidth, int y, int footerY) {
        addInfoLine(left, y, "screen.itemglint.rule_editor.selector_hint_compact");
        y += 16;

        int smallGap = 6;
        int addWidth = 28;
        int browseWidth = Math.min(88, Math.max(70, panelWidth / 5));
        int fieldWidth = panelWidth - addWidth - browseWidth - smallGap * 2;
        this.listEntryField = new EditBox(this.font, left, y, fieldWidth, 20, Component.translatable("screen.itemglint.rule_editor.selector"));
        this.listEntryField.setMaxLength(120);
        this.addRenderableWidget(this.listEntryField);
        this.addRenderableWidget(Button.builder(Component.literal("+"), button -> addListEntry(this.listEntryField.getValue()))
                .bounds(left + fieldWidth + smallGap, y, addWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.browse"), button ->
                        openSelectorBrowser(ItemTagSelectorBrowserScreen.BrowserMode.ITEM, this::addListEntry))
                .bounds(right - browseWidth, y, browseWidth, 20).build());
        y += 26;

        int compactGap = 6;
        int compactWidth = (panelWidth - compactGap * 2) / 3;
        this.addRenderableWidget(compactAddButton(left, y, compactWidth, "screen.itemglint.rule_editor.hand.main_id", InteractionHand.MAIN_HAND, HeldItemRuleSelectorHelper.SelectorSource.ITEM));
        this.addRenderableWidget(compactAddButton(left + compactWidth + compactGap, y, compactWidth, "screen.itemglint.rule_editor.hand.main_tag", InteractionHand.MAIN_HAND, HeldItemRuleSelectorHelper.SelectorSource.TAG));
        this.addRenderableWidget(compactAddButton(left + (compactWidth + compactGap) * 2, y, compactWidth, "screen.itemglint.rule_editor.hand.main_mod", InteractionHand.MAIN_HAND, HeldItemRuleSelectorHelper.SelectorSource.MOD));
        y += 28;

        addInfoLine(left, y, "screen.itemglint.rule_editor.selector_examples");
        y += 16;

        int navY = footerY - 30;
        int listPageSize = getListPageSize(y, navY);
        List<String> entries = currentList();
        clampListPage();
        int start = this.listPage * listPageSize;
        int end = Math.min(entries.size(), start + listPageSize);
        if (entries.isEmpty()) {
            addInfoLine(left, y + 10, "screen.itemglint.rule_editor.no_entries");
        }
        int previewWidth = Math.max(140, panelWidth - 68);
        for (int index = start; index < end; index++) {
            final int entryIndex = index;
            int rowY = y + (index - start) * 24;
            this.addRenderableOnly(new SelectorEntryPreview(entries.get(index), left, rowY, previewWidth));
            this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.remove_short"), button -> {
                        entries.remove(entryIndex);
                        clampListPage();
                        rebuild();
                    }).bounds(right - 60, rowY, 60, 20).build());
        }

        if (this.listPage > 0) {
            this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.prev_page"), button -> {
                        this.listPage--;
                        rebuild();
                    }).bounds(left, navY, 80, 20).build());
        }
        if ((this.listPage + 1) * listPageSize < entries.size()) {
            this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.next_page"), button -> {
                        this.listPage++;
                        rebuild();
                    }).bounds(left + 88, navY, 80, 20).build());
        }
    }

    private Button compactAddButton(int x, int y, int width, String key, InteractionHand hand, HeldItemRuleSelectorHelper.SelectorSource source) {
        return Button.builder(Component.translatable(key), button -> addSelectorFromHand(hand, source))
                .bounds(x, y, width, 20)
                .build();
    }

    private void initCustomMode(int left, int right, int panelWidth, int y, int footerY) {
        this.addRenderableWidget(Button.builder(Component.literal("+"), button -> openRuleEditor(-1, null))
                .bounds(left, y, 24, 20)
                .build());
        addInfoLine(left + 32, y + 6, "screen.itemglint.rule_editor.custom_list_hint");
        y += 28;

        int navY = footerY - 30;
        int pageSize = getRulePageSize(y, navY);
        sortCustomRules();
        clampRulePage();
        int start = this.rulePage * pageSize;
        int end = Math.min(this.customRules.size(), start + pageSize);
        if (this.customRules.isEmpty()) {
            addInfoLine(left, y + 8, "screen.itemglint.rule_editor.no_custom_rules");
            addInfoLine(left, y + 22, "screen.itemglint.rule_editor.no_custom_rules_2");
            return;
        }

        int previewWidth = Math.max(140, panelWidth - 126);
        for (int index = start; index < end; index++) {
            final int ruleIndex = index;
            int rowY = y + (index - start) * 28;
            this.addRenderableOnly(new CustomRulePreview(this.customRules.get(index), left, rowY, previewWidth));
            this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.edit_rule"), button ->
                            openRuleEditor(ruleIndex, this.customRules.get(ruleIndex)))
                    .bounds(right - 116, rowY, 54, 20).build());
            this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.remove_short"), button -> {
                        this.customRules.remove(ruleIndex);
                        clampRulePage();
                        rebuild();
                    }).bounds(right - 56, rowY, 56, 20).build());
        }

        if (this.rulePage > 0) {
            this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.prev_page"), button -> {
                        this.rulePage--;
                        rebuild();
                    }).bounds(left, navY, 80, 20).build());
        }
        if ((this.rulePage + 1) * pageSize < this.customRules.size()) {
            this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.rule_editor.next_page"), button -> {
                        this.rulePage++;
                        rebuild();
                    }).bounds(left + 88, navY, 80, 20).build());
        }
    }

    private void openRuleEditor(int editIndex, @Nullable HeldItemRuleManager.CustomRule existingRule) {
        if (this.minecraft == null) {
            return;
        }
        HeldItemRuleManager.CustomRule baseRule = existingRule == null ? HeldItemRuleManager.CustomRule.EMPTY : existingRule;
        this.minecraft.setScreen(new HeldItemCustomRuleEditScreen(this, baseRule, updatedRule -> {
            if (editIndex >= 0 && editIndex < this.customRules.size()) {
                this.customRules.set(editIndex, updatedRule);
            } else {
                this.customRules.add(updatedRule);
            }
            sortCustomRules();
            clampRulePage();
        }));
    }

    private void addListEntry(String rawSelector) {
        String selector = HeldItemRuleSelectorHelper.normalizeSelectorInput(rawSelector);
        if (selector.isEmpty()) {
            return;
        }
        List<String> entries = currentList();
        if (!entries.contains(selector)) {
            entries.add(selector);
        }
        if (this.listEntryField != null) {
            this.listEntryField.setValue("");
        }
        clampListPage();
        rebuild();
    }

    private void addSelectorFromHand(InteractionHand hand, HeldItemRuleSelectorHelper.SelectorSource source) {
        addListEntry(HeldItemRuleSelectorHelper.selectorFromHand(this.minecraft, hand, source));
    }

    private void openSelectorBrowser(ItemTagSelectorBrowserScreen.BrowserMode mode, Consumer<String> onSelect) {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.setScreen(new ItemTagSelectorBrowserScreen(this, mode, onSelect));
    }

    private List<String> currentList() {
        return this.ruleMode == HeldItemRuleManager.RuleMode.BLACKLIST ? this.blacklistEntries : this.whitelistEntries;
    }

    private void clampListPage() {
        int pageSize = Math.max(1, getListPageSize(128, getFooterY() - 30));
        int maxPage = Math.max(0, (currentList().size() - 1) / pageSize);
        if (this.listPage > maxPage) {
            this.listPage = maxPage;
        }
    }

    private void clampRulePage() {
        int pageSize = Math.max(1, getRulePageSize(96, getFooterY() - 30));
        int maxPage = Math.max(0, (this.customRules.size() - 1) / pageSize);
        if (this.rulePage > maxPage) {
            this.rulePage = maxPage;
        }
    }

    private void sortCustomRules() {
        this.customRules.sort(Comparator.comparingInt(HeldItemRuleManager.CustomRule::priority).reversed());
    }

    private void rebuild() {
        this.listEntryField = null;
        if (this.minecraft != null) {
            init(this.minecraft, this.width, this.height);
        }
    }

    private void saveAndClose() {
        sortCustomRules();
        this.onSave.accept(new HeldItemRuleManager.StateSnapshot(this.ruleMode, this.whitelistEntries, this.blacklistEntries, this.customRules));
        closeToParent();
    }

    private void closeToParent() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private void addInfoLine(int x, int y, String key) {
        addInfoLine(x, y, Component.translatable(key));
    }

    private void addInfoLine(int x, int y, Component component) {
        this.addRenderableOnly(new TextLine(component, x, y));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
    }

    private int getPanelWidth() {
        return Math.min(PANEL_MAX_WIDTH, Math.max(240, this.width - PANEL_MARGIN * 2));
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

    private int getListPageSize(int listStartY, int navY) {
        return Math.max(1, Math.min(LIST_PAGE_SIZE, (navY - listStartY) / 24));
    }

    private int getRulePageSize(int listStartY, int navY) {
        return Math.max(1, Math.min(RULE_PAGE_SIZE, (navY - listStartY) / 28));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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

    private final class SelectorEntryPreview extends AbstractWidget {
        private final String selector;

        private SelectorEntryPreview(String selector, int x, int y, int textWidth) {
            super(x, y, textWidth, 20, Component.empty());
            this.selector = selector;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!HeldItemRuleSelectorHelper.previewStackForSelector(this.selector).isEmpty()) {
                graphics.renderItem(HeldItemRuleSelectorHelper.previewStackForSelector(this.selector), getX(), getY() + 2);
            }
            graphics.drawString(font, this.selector, getX() + 20, getY() + 6, 0xE0E6EE, false);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        }
    }

    private final class CustomRulePreview extends AbstractWidget {
        private final HeldItemRuleManager.CustomRule rule;

        private CustomRulePreview(HeldItemRuleManager.CustomRule rule, int x, int y, int width) {
            super(x, y, width, 20, Component.empty());
            this.rule = rule;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.drawString(font, Component.literal(this.rule.displayName()), getX(), getY() + 2, 0xFFFFFF, false);
            graphics.drawString(font,
                    Component.translatable("screen.itemglint.rule_editor.rule_row_meta", this.rule.priority(), this.rule.filters().size()),
                    getX(), getY() + 12, 0xAAB3C3, false);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        }
    }
}
