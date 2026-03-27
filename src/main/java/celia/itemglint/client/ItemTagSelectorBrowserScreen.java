package celia.itemglint.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.function.Consumer;

public class ItemTagSelectorBrowserScreen extends Screen {
    private static final int PAGE_SIZE = 8;
    private static final int PANEL_MAX_WIDTH = 460;
    private static final int PANEL_MARGIN = 12;

    private final Screen parent;
    private final Consumer<String> onSelect;
    private final List<Entry> allEntries = new ArrayList<>();
    private final List<Entry> filteredEntries = new ArrayList<>();

    private BrowserMode currentMode;
    private EditBox searchBox;
    private int page;
    private boolean suppressSearchResponder;

    public ItemTagSelectorBrowserScreen(Screen parent, BrowserMode initialMode, Consumer<String> onSelect) {
        super(Component.translatable("screen.itemglint.selector_browser.title"));
        this.parent = parent;
        this.currentMode = initialMode == null ? BrowserMode.ITEM : initialMode;
        this.onSelect = onSelect;
        refreshEntries();
    }

    @Override
    protected void init() {
        clearWidgets();

        int left = getPanelLeft();
        int panelWidth = getPanelWidth();
        int right = left + panelWidth;
        int footerY = getFooterY();
        int y = 34;
        int modeGap = 6;
        int buttonWidth = (panelWidth - modeGap * 2) / 3;
        for (int i = 0; i < BrowserMode.values().length; i++) {
            BrowserMode mode = BrowserMode.values()[i];
            Button button = this.addRenderableWidget(Button.builder(mode.label(), ignored -> switchMode(mode))
                    .bounds(left + i * (buttonWidth + modeGap), y, buttonWidth, 20)
                    .build());
            button.active = mode != this.currentMode;
        }
        y += 28;

        this.searchBox = new EditBox(this.font, left, y, panelWidth, 20, Component.translatable("screen.itemglint.selector_browser.search"));
        this.searchBox.setMaxLength(120);
        this.searchBox.setResponder(value -> {
            if (this.suppressSearchResponder) {
                return;
            }
            this.page = 0;
            refreshFilter();
            rebuild();
        });
        this.addRenderableWidget(this.searchBox);
        y += 28;

        int pageSize = getPageSize(y, footerY - 8);
        int start = this.page * pageSize;
        int end = Math.min(this.filteredEntries.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            Entry entry = this.filteredEntries.get(index);
            this.addRenderableWidget(new EntryWidget(left, y + (index - start) * 24, panelWidth, entry, this::selectEntry));
        }

        if (this.page > 0) {
            this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.selector_browser.prev"), button -> {
                        this.page--;
                        rebuild();
                    }).bounds(left, footerY, 80, 20).build());
        }
        if ((this.page + 1) * pageSize < this.filteredEntries.size()) {
            this.addRenderableWidget(Button.builder(Component.translatable("screen.itemglint.selector_browser.next"), button -> {
                        this.page++;
                        rebuild();
                    }).bounds(left + 88, footerY, 80, 20).build());
        }
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> closeToParent())
                .bounds(right - 96, footerY, 96, 20).build());
    }

    private void switchMode(BrowserMode mode) {
        if (mode == this.currentMode) {
            return;
        }
        this.currentMode = mode;
        this.page = 0;
        refreshEntries();
        rebuild();
    }

    private void rebuild() {
        if (this.minecraft != null) {
            String search = this.searchBox == null ? "" : this.searchBox.getValue();
            int cursorPosition = this.searchBox == null ? search.length() : this.searchBox.getCursorPosition();
            boolean refocusSearch = this.searchBox != null && (this.searchBox.isFocused() || this.getFocused() == this.searchBox);
            this.suppressSearchResponder = true;
            init(this.minecraft, this.width, this.height);
            if (this.searchBox != null) {
                this.searchBox.setValue(search);
                this.searchBox.setCursorPosition(Math.min(cursorPosition, this.searchBox.getValue().length()));
                if (refocusSearch) {
                    this.setFocused(this.searchBox);
                    this.searchBox.setFocused(true);
                }
            }
            this.suppressSearchResponder = false;
        }
    }

    private void refreshEntries() {
        this.allEntries.clear();
        this.allEntries.addAll(buildEntries(this.currentMode));
        refreshFilter();
    }

    private void refreshFilter() {
        String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        this.filteredEntries.clear();
        for (Entry entry : this.allEntries) {
            if (matchesQuery(entry, query)) {
                this.filteredEntries.add(entry);
            }
        }
    }

    private boolean matchesQuery(Entry entry, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (this.currentMode == BrowserMode.ITEM) {
            if (query.startsWith("@")) {
                String modQuery = query.substring(1).trim();
                return modQuery.isEmpty() || entry.modId.contains(modQuery);
            }
            if (query.startsWith("#")) {
                String tagQuery = query.substring(1).trim();
                return tagQuery.isEmpty() || entry.tagSearchText.contains(tagQuery);
            }
        }
        return entry.searchText.contains(query);
    }

    private void selectEntry(Entry entry) {
        this.onSelect.accept(entry.selector);
        closeToParent();
    }

    private void closeToParent() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, Component.translatable("screen.itemglint.selector_browser.title", this.currentMode.label()), this.width / 2, 12, 0xFFFFFF);
        graphics.drawString(this.font,
                Component.translatable("screen.itemglint.selector_browser.count", this.filteredEntries.size(), this.currentMode.label()),
                getPanelLeft(), this.height - 42, 0xAAB3C3, false);
    }

    @Override
    public void onClose() {
        closeToParent();
    }

    private int getPanelWidth() {
        return Math.min(PANEL_MAX_WIDTH, Math.max(240, this.width - PANEL_MARGIN * 2));
    }

    private int getPanelLeft() {
        return this.width / 2 - getPanelWidth() / 2;
    }

    private int getFooterY() {
        return this.height - 28;
    }

    private int getPageSize(int listStartY, int footerY) {
        return Math.max(1, Math.min(PAGE_SIZE, (footerY - listStartY) / 24));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<? extends GuiEventListener> children = this.children();
        for (int index = children.size() - 1; index >= 0; index--) {
            GuiEventListener listener = children.get(index);
            if (listener.mouseClicked(mouseX, mouseY, button)) {
                this.setFocused(listener);
                if (listener == this.searchBox && this.searchBox != null) {
                    this.searchBox.setFocused(true);
                }
                if (button == 0) {
                    this.setDragging(true);
                }
                return true;
            }
        }
        if (this.searchBox != null) {
            this.searchBox.setFocused(false);
        }
        return false;
    }

    private static List<Entry> buildEntries(BrowserMode mode) {
        List<Entry> entries = new ArrayList<>();
        switch (mode) {
            case ITEM -> BuiltInRegistries.ITEM.keySet().stream()
                    .sorted(Comparator.comparing(ResourceLocation::toString))
                    .forEach(id -> entries.add(buildItemEntry(id)));
            case TAG -> BuiltInRegistries.ITEM.getTagNames()
                    .map(TagKey::location)
                    .map(ResourceLocation::toString)
                    .sorted(Comparator.naturalOrder())
                    .forEach(id -> entries.add(new Entry("#" + id, "#" + id, "",
                            ("#" + id + " " + id).toLowerCase(Locale.ROOT), id.toLowerCase(Locale.ROOT), "",
                            HeldItemRuleSelectorHelper.previewForTag(id))));
            case MOD -> HeldItemRuleSelectorHelper.collectModIds().forEach(modId ->
                    entries.add(new Entry("@" + modId, "@" + modId, "",
                            ("@" + modId + " " + modId).toLowerCase(Locale.ROOT), "", modId.toLowerCase(Locale.ROOT),
                            HeldItemRuleSelectorHelper.previewForMod(modId))));
        }
        return entries;
    }

    private static Entry buildItemEntry(ResourceLocation id) {
        String itemId = id.toString();
        ItemStack preview = HeldItemRuleSelectorHelper.previewForItem(itemId);
        String translatedName = preview.getHoverName().getString();
        String tagText = preview.getTags()
                .map(TagKey::location)
                .map(ResourceLocation::toString)
                .map(value -> "#" + value)
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        String modId = id.getNamespace().toLowerCase(Locale.ROOT);
        String searchText = (translatedName + " " + itemId + " @" + modId + " " + tagText).toLowerCase(Locale.ROOT);
        return new Entry(itemId, translatedName, itemId, searchText, tagText, modId, preview);
    }

    public enum BrowserMode {
        ITEM("screen.itemglint.selector_browser.mode.item"),
        TAG("screen.itemglint.selector_browser.mode.tag"),
        MOD("screen.itemglint.selector_browser.mode.mod");

        private final String labelKey;

        BrowserMode(String labelKey) {
            this.labelKey = labelKey;
        }

        private Component label() {
            return Component.translatable(this.labelKey);
        }
    }

    private record Entry(String selector, String title, String subtitle, String searchText,
                         String tagSearchText, String modId, ItemStack preview) {
    }

    private static final class EntryWidget extends AbstractWidget {
        private final Entry entry;
        private final Consumer<Entry> onSelect;

        private EntryWidget(int x, int y, int width, Entry entry, Consumer<Entry> onSelect) {
            super(x, y, width, 20, Component.empty());
            this.entry = entry;
            this.onSelect = onSelect;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getX() + this.width, getY() + this.height, isMouseOver(mouseX, mouseY) ? 0x90303A48 : 0x70202834);
            graphics.renderOutline(getX(), getY(), this.width, this.height, 0xFF4C5668);
            if (!this.entry.preview.isEmpty()) {
                graphics.renderItem(this.entry.preview, getX() + 3, getY() + 2);
            }
            int textLeft = getX() + 24;
            int textRight = getX() + this.width - 38;
            var font = net.minecraft.client.Minecraft.getInstance().font;
            graphics.drawString(font, font.plainSubstrByWidth(this.entry.title, Math.max(10, textRight - textLeft)), textLeft, getY() + 2, 0xE0E6EE, false);
            if (!this.entry.subtitle.isEmpty()) {
                graphics.drawString(font, font.plainSubstrByWidth(this.entry.subtitle, Math.max(10, textRight - textLeft)), textLeft, getY() + 11, 0x8FA1BA, false);
            }
            graphics.drawString(font, Component.translatable("screen.itemglint.selector_browser.add"), getX() + this.width - 34, getY() + 6, 0xA7E7FF, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && this.isMouseOver(mouseX, mouseY)) {
                this.onSelect.accept(this.entry);
                return true;
            }
            return false;
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
        }
    }
}
