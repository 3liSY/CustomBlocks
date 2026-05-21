package com.customblocks.gui;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Phase 6.1 — Shared GUI layout helpers.
 * <p>
 * Provides a standard 54-slot chest layout used across all CustomBlocks GUIs:
 * <pre>
 *  Slot  0  : Back button (always present)
 *  Slots 1-3, 5-8 : Gray glass border (top row decoration)
 *  Slot  4  : Title / info item
 *  Slots 9-44 : Content area (rows 2–5)
 *  Slot 45  : Previous page (paginated)  or  glass
 *  Slot 49  : Page indicator              or  glass
 *  Slot 53  : Next page (paginated)       or  glass
 * </pre>
 */
public final class GuiLayout {

    private GuiLayout() {}

    // ── Standard items ───────────────────────────────────────────────────────

    public static ItemStack backButton() {
        return GuiManager.uiGlint(Items.RED_CONCRETE, "§c◀ Back", "§8Return to previous screen");
    }

    public static ItemStack backButton(String label) {
        return GuiManager.uiGlint(Items.RED_CONCRETE, label, "§8Return to previous screen");
    }

    /** Section divider — a colored glass pane with a section label. */
    public static ItemStack sectionHeader(Item pane, String label) {
        return GuiManager.ui(pane, label);
    }

    // ── Layout operations ────────────────────────────────────────────────────

    /**
     * Fill the entire inventory with gray glass panes.
     * Call this first, then overwrite content slots.
     */
    public static void fillGlass(SimpleInventory inv) {
        for (int i = 0; i < inv.size(); i++) inv.setStack(i, GuiManager.glass());
    }

    /**
     * Add the standard top-row back button + title item.
     * <ul>
     *   <li>Slot 0 = back button ("§c◀ Back")</li>
     *   <li>Slots 1-3, 5-8 = glass (already filled by {@link #fillGlass})</li>
     *   <li>Slot 4 = {@code titleItem} (caller provides)</li>
     * </ul>
     */
    public static void addTopRow(SimpleInventory inv, ItemStack titleItem) {
        inv.setStack(0, backButton());
        inv.setStack(4, titleItem);
    }

    /** Same as {@link #addTopRow} but with a custom back-button label. */
    public static void addTopRow(SimpleInventory inv, ItemStack titleItem, String backLabel) {
        inv.setStack(0, backButton(backLabel));
        inv.setStack(4, titleItem);
    }

    /**
     * Add standard pagination buttons in the bottom row.
     *
     * @param inv       target inventory (must be 54-slot)
     * @param page      current page (0-based)
     * @param maxPage   last valid page index
     * @param total     total items (shown in indicator lore)
     */
    public static void addPagination(SimpleInventory inv, int page, int maxPage, int total) {
        if (page > 0) {
            inv.setStack(45, GuiManager.uiGlint(Items.AMETHYST_CLUSTER, "§d◀ Previous Page",
                "§8Page " + page + " / " + (maxPage + 1)));
        }
        if (total > 0) {
            inv.setStack(49, GuiManager.ui(Items.PAPER, "§7Page §f" + (page + 1) + " §7/ §f" + (maxPage + 1),
                "§8Total: " + total + " item(s)"));
        }
        if (page < maxPage) {
            inv.setStack(53, GuiManager.uiGlint(Items.AMETHYST_CLUSTER, "§dNext Page ▶",
                "§8Page " + (page + 2) + " / " + (maxPage + 1)));
        }
    }

    /**
     * Add a single colored separator row dividing the content area into sections.
     * Fills the given row index (0 = slots 0-8, 1 = slots 9-17, etc.) with
     * a pane item.
     *
     * @param rowIndex 0-based row (0 = top row already handled by addTopRow)
     */
    public static void addSeparatorRow(SimpleInventory inv, int rowIndex, Item pane, String label) {
        int base = rowIndex * 9;
        for (int col = 0; col < 9; col++) {
            inv.setStack(base + col, col == 4 ? sectionHeader(pane, label) : GuiManager.ui(pane, " "));
        }
    }
}
