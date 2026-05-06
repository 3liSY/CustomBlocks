package com.customblocks.core;

import java.util.Objects;

/**
 * Immutable record representing a CustomBlocks Category.
 * TRD § 4: Immutable state objects prevent race conditions.
 */
public record Category(
    String key,
    String displayName,
    String iconItem,
    String iconCustomBlockId,
    String color,
    String badge,
    String badgeColor,
    String parentKey,
    boolean hidden,
    boolean locked,
    boolean isDefault,
    String lorePrefix,
    String lorePrefixPosition,
    String subcategoryIndicator,
    boolean displayBlockEnabled,
    String displayBlockType,
    String badgeOverflowMode,
    String colorPlacement,
    int sortOrder,
    String description,
    long createdAt
) {
    public Category {
        Objects.requireNonNull(key, "Category key cannot be null");
        Objects.requireNonNull(displayName, "Category displayName cannot be null");
        key = key.toLowerCase().replace(" ", "_");
    }

    /** Helper to create a new category with defaults. */
    public static Category create(String displayName) {
        String key = displayName.toLowerCase().replaceAll("[^a-z0-9]", "_").replaceAll("_+", "_");
        if (key.endsWith("_")) key = key.substring(0, key.length() - 1);
        if (key.isEmpty()) key = "category_" + System.currentTimeMillis();
        
        return new Category(
            key,
            displayName,
            "minecraft:grass_block", // default icon
            null,
            "#FFFFFF", // default color
            null,
            null,
            null,
            false,
            false,
            false,
            null,
            "above_badge",
            "↳",
            false,
            "vanilla",
            "cap_3_more",
            "borders",
            0,
            "",
            System.currentTimeMillis()
        );
    }

    // Wither pattern methods for immutable mutation
    public Category withDisplayName(String displayName) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withIconItem(String iconItem) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withIconCustomBlockId(String iconCustomBlockId) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withColor(String color) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withBadge(String badge) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withBadgeColor(String badgeColor) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withParentKey(String parentKey) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withHidden(boolean hidden) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withLocked(boolean locked) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withDefault(boolean isDefault) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withLorePrefix(String lorePrefix) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withLorePrefixPosition(String lorePrefixPosition) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withSubcategoryIndicator(String subcategoryIndicator) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withDisplayBlockEnabled(boolean displayBlockEnabled) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withDisplayBlockType(String displayBlockType) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withBadgeOverflowMode(String badgeOverflowMode) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withColorPlacement(String colorPlacement) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withSortOrder(int sortOrder) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
    public Category withDescription(String description) { return new Category(key, displayName, iconItem, iconCustomBlockId, color, badge, badgeColor, parentKey, hidden, locked, isDefault, lorePrefix, lorePrefixPosition, subcategoryIndicator, displayBlockEnabled, displayBlockType, badgeOverflowMode, colorPlacement, sortOrder, description, createdAt); }
}
