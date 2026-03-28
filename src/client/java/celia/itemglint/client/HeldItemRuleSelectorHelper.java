package celia.itemglint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

public final class HeldItemRuleSelectorHelper {
    private HeldItemRuleSelectorHelper() {
    }

    public static String normalizeSelectorInput(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static String selectorFromHand(@Nullable Minecraft minecraft, InteractionHand hand, SelectorSource source) {
        if (minecraft == null || minecraft.player == null) {
            return "";
        }
        ItemStack stack = hand == InteractionHand.MAIN_HAND ? minecraft.player.getMainHandItem() : minecraft.player.getOffhandItem();
        if (stack.isEmpty()) {
            return "";
        }
        return switch (source) {
            case ITEM -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            case TAG -> firstTagSelector(stack);
            case MOD -> "@" + BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
        };
    }

    public static String firstTagSelector(ItemStack stack) {
        return stack.getTags()
                .map(TagKey::location)
                .map(ResourceLocation::toString)
                .map(value -> "#" + value)
                .findFirst()
                .orElse("");
    }

    public static ItemStack previewStackForSelector(String selector) {
        if (selector == null || selector.isBlank()) {
            return ItemStack.EMPTY;
        }

        String normalized = normalizeSelectorInput(selector);
        if (normalized.startsWith("#")) {
            return previewForTag(normalized.substring(1));
        }
        if (normalized.startsWith("@")) {
            return previewForMod(normalized.substring(1));
        }
        return previewForItem(normalized);
    }

    public static ItemStack previewForItem(String id) {
        ResourceLocation itemId = ResourceLocation.tryParse(id);
        if (itemId == null) {
            return new ItemStack(Items.BARRIER);
        }
        return BuiltInRegistries.ITEM.getOptional(itemId)
                .map(ItemStack::new)
                .orElse(new ItemStack(Items.BARRIER));
    }

    public static ItemStack previewForTag(String id) {
        ResourceLocation tagId = ResourceLocation.tryParse(id);
        if (tagId == null) {
            return new ItemStack(Items.NAME_TAG);
        }
        TagKey<Item> tagKey = TagKey.create(Registries.ITEM, tagId);
        Optional<HolderSet.Named<Item>> tag = BuiltInRegistries.ITEM.getTag(tagKey);
        if (tag.isPresent()) {
            return tag.get().stream()
                    .findFirst()
                    .map(holder -> new ItemStack(holder.value()))
                    .orElse(new ItemStack(Items.NAME_TAG));
        }
        return new ItemStack(Items.NAME_TAG);
    }

    public static ItemStack previewForMod(String modId) {
        if (modId == null || modId.isBlank()) {
            return new ItemStack(Items.CHEST);
        }
        return BuiltInRegistries.ITEM.keySet().stream()
                .filter(id -> id.getNamespace().equals(modId))
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .findFirst()
                .flatMap(BuiltInRegistries.ITEM::getOptional)
                .map(ItemStack::new)
                .orElse(new ItemStack(Items.CHEST));
    }

    public static TreeSet<String> collectModIds() {
        TreeSet<String> mods = new TreeSet<>();
        BuiltInRegistries.ITEM.keySet().forEach(id -> mods.add(id.getNamespace()));
        return mods;
    }

    public enum SelectorSource {
        ITEM,
        TAG,
        MOD
    }
}
