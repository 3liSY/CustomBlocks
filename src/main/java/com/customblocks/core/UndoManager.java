package com.customblocks.core;

import com.customblocks.CustomBlocksConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages undo / redo stacks.
 * <p>
 * Supports three modes controlled by {@link CustomBlocksConfig#undoMode}:
 * <ul>
 *     <li><b>global</b> — single shared stack for all players</li>
 *     <li><b>per_player</b> — each player has their own stack</li>
 *     <li><b>both</b> — every action is pushed to BOTH the global stack and the acting player's stack.
 *         Undo from command/GUI uses the player stack; {@code /cb undo global} uses the global stack.</li>
 * </ul>
 */
public final class UndoManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");

    // ── Undo entry record ────────────────────────────────────────────────────

    /**
     * @param customId     block ID affected
     * @param previousState snapshot before the change (null = was a creation → undo = delete)
     * @param description  human-readable label ("retexture", "setglow", "delete", …)
     * @param wasDeleted   true if the block was deleted (undo = re-create)
     * @param playerUuid   UUID of the player who performed the action (null for system actions)
     */
    public record UndoEntry(
            String customId,
            SlotData previousState,
            String description,
            boolean wasDeleted,
            UUID playerUuid
    ) {
        /** Convenience: entry without player UUID. */
        public UndoEntry(String customId, SlotData previousState, String description, boolean wasDeleted) {
            this(customId, previousState, description, wasDeleted, null);
        }
    }

    // ── Stacks ───────────────────────────────────────────────────────────────

    private static final Deque<UndoEntry> GLOBAL_UNDO = new ArrayDeque<>();
    private static final Deque<UndoEntry> GLOBAL_REDO = new ArrayDeque<>();

    private static final Map<UUID, Deque<UndoEntry>> PLAYER_UNDO = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<UndoEntry>> PLAYER_REDO = new ConcurrentHashMap<>();

    // ── Push ─────────────────────────────────────────────────────────────────

    /**
     * Push an undo entry. Depending on config mode, pushes to global and/or player stack.
     *
     * @param entry the undo entry to push
     */
    public static synchronized void pushUndo(UndoEntry entry) {
        String mode = CustomBlocksConfig.undoMode;
        int maxDepth = CustomBlocksConfig.maxUndoDepth;

        if ("global".equals(mode) || "both".equals(mode)) {
            pushTo(GLOBAL_UNDO, entry, maxDepth);
            GLOBAL_REDO.clear();
        }
        if (("per_player".equals(mode) || "both".equals(mode)) && entry.playerUuid() != null) {
            Deque<UndoEntry> stack = PLAYER_UNDO.computeIfAbsent(entry.playerUuid(), k -> new ArrayDeque<>());
            pushTo(stack, entry, maxDepth);
            PLAYER_REDO.computeIfAbsent(entry.playerUuid(), k -> new ArrayDeque<>()).clear();
        }
    }

    /**
     * Push a "create" undo entry (undo = delete the block).
     */
    public static void pushUndoCreate(String customId, UUID playerUuid) {
        pushUndo(new UndoEntry(customId, null, "create", false, playerUuid));
    }

    /**
     * Push a mutation undo entry with a snapshot of the current state.
     */
    public static void pushUndoMutation(String customId, SlotData snapshot, String description, UUID playerUuid) {
        pushUndo(new UndoEntry(customId, snapshot, description, false, playerUuid));
    }

    /**
     * Push a deletion undo entry.
     */
    public static void pushUndoDeletion(String customId, SlotData snapshot, UUID playerUuid) {
        pushUndo(new UndoEntry(customId, snapshot, "delete", true, playerUuid));
    }

    // ── Pop ──────────────────────────────────────────────────────────────────

    /** Pop from the global undo stack. Returns null if empty. */
    public static synchronized UndoEntry popGlobalUndo() {
        return GLOBAL_UNDO.pollFirst();
    }

    /** Pop from the global redo stack. Returns null if empty. */
    public static synchronized UndoEntry popGlobalRedo() {
        return GLOBAL_REDO.pollFirst();
    }

    /** Pop from a player's undo stack. Returns null if empty. */
    public static synchronized UndoEntry popPlayerUndo(UUID playerUuid) {
        Deque<UndoEntry> stack = PLAYER_UNDO.get(playerUuid);
        return stack != null ? stack.pollFirst() : null;
    }

    /** Pop from a player's redo stack. Returns null if empty. */
    public static synchronized UndoEntry popPlayerRedo(UUID playerUuid) {
        Deque<UndoEntry> stack = PLAYER_REDO.get(playerUuid);
        return stack != null ? stack.pollFirst() : null;
    }

    /**
     * Pop the undo entry — uses per-player stack if available, falls back to global.
     */
    public static synchronized UndoEntry popUndo(UUID playerUuid) {
        String mode = CustomBlocksConfig.undoMode;
        if ("per_player".equals(mode)) {
            return popPlayerUndo(playerUuid);
        } else if ("global".equals(mode)) {
            return popGlobalUndo();
        } else { // "both"
            // Prefer player stack, fall back to global
            UndoEntry entry = popPlayerUndo(playerUuid);
            if (entry != null) {
                // Also remove matching entry from global stack
                GLOBAL_UNDO.removeFirstOccurrence(entry);
                return entry;
            }
            return popGlobalUndo();
        }
    }

    /**
     * Pop the redo entry — uses per-player stack if available, falls back to global.
     */
    public static synchronized UndoEntry popRedo(UUID playerUuid) {
        String mode = CustomBlocksConfig.undoMode;
        if ("per_player".equals(mode)) {
            return popPlayerRedo(playerUuid);
        } else if ("global".equals(mode)) {
            return popGlobalRedo();
        } else { // "both"
            UndoEntry entry = popPlayerRedo(playerUuid);
            if (entry != null) {
                GLOBAL_REDO.removeFirstOccurrence(entry);
                return entry;
            }
            return popGlobalRedo();
        }
    }

    // ── Push to redo (for undo operations) ───────────────────────────────────

    public static synchronized void pushRedo(UndoEntry entry) {
        String mode = CustomBlocksConfig.undoMode;
        int maxDepth = CustomBlocksConfig.maxUndoDepth;

        if ("global".equals(mode) || "both".equals(mode)) {
            pushTo(GLOBAL_REDO, entry, maxDepth);
        }
        if (("per_player".equals(mode) || "both".equals(mode)) && entry.playerUuid() != null) {
            Deque<UndoEntry> stack = PLAYER_REDO.computeIfAbsent(entry.playerUuid(), k -> new ArrayDeque<>());
            pushTo(stack, entry, maxDepth);
        }
    }

    /**
     * Push to redo without clearing undo (used during redo operations).
     */
    public static synchronized void pushUndoForRedo(UndoEntry entry) {
        String mode = CustomBlocksConfig.undoMode;
        int maxDepth = CustomBlocksConfig.maxUndoDepth;

        if ("global".equals(mode) || "both".equals(mode)) {
            pushTo(GLOBAL_UNDO, entry, maxDepth);
        }
        if (("per_player".equals(mode) || "both".equals(mode)) && entry.playerUuid() != null) {
            Deque<UndoEntry> stack = PLAYER_UNDO.computeIfAbsent(entry.playerUuid(), k -> new ArrayDeque<>());
            pushTo(stack, entry, maxDepth);
        }
    }

    // ── Query ────────────────────────────────────────────────────────────────

    public static synchronized int globalUndoSize() { return GLOBAL_UNDO.size(); }
    public static synchronized int globalRedoSize() { return GLOBAL_REDO.size(); }

    public static synchronized int playerUndoSize(UUID playerUuid) {
        Deque<UndoEntry> stack = PLAYER_UNDO.get(playerUuid);
        return stack != null ? stack.size() : 0;
    }

    public static synchronized int playerRedoSize(UUID playerUuid) {
        Deque<UndoEntry> stack = PLAYER_REDO.get(playerUuid);
        return stack != null ? stack.size() : 0;
    }

    /**
     * Effective undo size for a player (respects config mode).
     */
    public static synchronized int undoSize(UUID playerUuid) {
        String mode = CustomBlocksConfig.undoMode;
        if ("per_player".equals(mode)) return playerUndoSize(playerUuid);
        if ("global".equals(mode)) return globalUndoSize();
        // "both" — report player stack size (primary), or global if empty
        int ps = playerUndoSize(playerUuid);
        return ps > 0 ? ps : globalUndoSize();
    }

    /**
     * Effective redo size for a player (respects config mode).
     */
    public static synchronized int redoSize(UUID playerUuid) {
        String mode = CustomBlocksConfig.undoMode;
        if ("per_player".equals(mode)) return playerRedoSize(playerUuid);
        if ("global".equals(mode)) return globalRedoSize();
        int ps = playerRedoSize(playerUuid);
        return ps > 0 ? ps : globalRedoSize();
    }

    /** Peek description of next undo for the player. */
    public static synchronized String peekUndoDescription(UUID playerUuid) {
        String mode = CustomBlocksConfig.undoMode;
        if ("per_player".equals(mode) || "both".equals(mode)) {
            Deque<UndoEntry> stack = PLAYER_UNDO.get(playerUuid);
            if (stack != null && !stack.isEmpty()) return stack.peekFirst().description();
        }
        if ("global".equals(mode) || "both".equals(mode)) {
            if (!GLOBAL_UNDO.isEmpty()) return GLOBAL_UNDO.peekFirst().description();
        }
        return "";
    }

    /** Peek description of next redo for the player. */
    public static synchronized String peekRedoDescription(UUID playerUuid) {
        String mode = CustomBlocksConfig.undoMode;
        if ("per_player".equals(mode) || "both".equals(mode)) {
            Deque<UndoEntry> stack = PLAYER_REDO.get(playerUuid);
            if (stack != null && !stack.isEmpty()) return stack.peekFirst().description();
        }
        if ("global".equals(mode) || "both".equals(mode)) {
            if (!GLOBAL_REDO.isEmpty()) return GLOBAL_REDO.peekFirst().description();
        }
        return "";
    }

    // ── Category Undo (Phase 10 — bulk assignments as one atomic entry) ──────

    /**
     * @param description           e.g. "bulk-assign 12 → food"
     * @param beforeAssignments     snapshot of CategoryManager.assignments BEFORE the change
     * @param beforeCategories      snapshot of all categories BEFORE the change (or empty if no cat-create)
     * @param playerUuid            actor
     */
    public record CategoryUndoEntry(
            String description,
            java.util.Map<String, java.util.Set<String>> beforeAssignments,
            java.util.Map<String, com.customblocks.core.Category> beforeCategories,
            UUID playerUuid
    ) {}

    private static final Deque<CategoryUndoEntry> CATEGORY_UNDO = new ArrayDeque<>();
    private static final Deque<CategoryUndoEntry> CATEGORY_REDO = new ArrayDeque<>();
    private static final Map<UUID, Deque<CategoryUndoEntry>> CATEGORY_PLAYER_UNDO = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<CategoryUndoEntry>> CATEGORY_PLAYER_REDO = new ConcurrentHashMap<>();

    /** Capture the current CategoryManager state into a snapshot suitable for undo. */
    public static CategoryUndoEntry captureCategorySnapshot(String description, UUID playerUuid) {
        java.util.Map<String, java.util.Set<String>> assignSnap = new HashMap<>();
        for (com.customblocks.core.SlotData d : com.customblocks.core.SlotManager.allSlots()) {
            java.util.Set<String> cats = com.customblocks.core.CategoryManager.getCategoriesForBlock(d.customId);
            if (!cats.isEmpty()) assignSnap.put(d.customId, new HashSet<>(cats));
        }
        java.util.Map<String, com.customblocks.core.Category> catSnap = new HashMap<>();
        for (com.customblocks.core.Category c : com.customblocks.core.CategoryManager.getAllCategories()) {
            catSnap.put(c.key(), c);
        }
        return new CategoryUndoEntry(description, assignSnap, catSnap, playerUuid);
    }

    public static synchronized void pushCategoryUndo(CategoryUndoEntry entry) {
        int maxDepth = CustomBlocksConfig.maxUndoDepth;
        String mode = CustomBlocksConfig.undoMode;
        if ("global".equals(mode) || "both".equals(mode)) {
            CATEGORY_UNDO.addFirst(entry);
            while (CATEGORY_UNDO.size() > maxDepth) CATEGORY_UNDO.removeLast();
            CATEGORY_REDO.clear();
        }
        if (("per_player".equals(mode) || "both".equals(mode)) && entry.playerUuid() != null) {
            Deque<CategoryUndoEntry> stack = CATEGORY_PLAYER_UNDO.computeIfAbsent(entry.playerUuid(), k -> new ArrayDeque<>());
            stack.addFirst(entry);
            while (stack.size() > maxDepth) stack.removeLast();
            CATEGORY_PLAYER_REDO.computeIfAbsent(entry.playerUuid(), k -> new ArrayDeque<>()).clear();
        }
    }

    public static synchronized CategoryUndoEntry popCategoryUndo(UUID playerUuid) {
        String mode = CustomBlocksConfig.undoMode;
        if ("per_player".equals(mode)) {
            Deque<CategoryUndoEntry> s = CATEGORY_PLAYER_UNDO.get(playerUuid);
            return s != null ? s.pollFirst() : null;
        } else if ("global".equals(mode)) {
            return CATEGORY_UNDO.pollFirst();
        } else {
            Deque<CategoryUndoEntry> s = CATEGORY_PLAYER_UNDO.get(playerUuid);
            CategoryUndoEntry e = s != null ? s.pollFirst() : null;
            if (e != null) {
                CATEGORY_UNDO.removeFirstOccurrence(e);
                return e;
            }
            return CATEGORY_UNDO.pollFirst();
        }
    }

    public static synchronized void pushCategoryRedo(CategoryUndoEntry entry) {
        int maxDepth = CustomBlocksConfig.maxUndoDepth;
        String mode = CustomBlocksConfig.undoMode;
        if ("global".equals(mode) || "both".equals(mode)) {
            CATEGORY_REDO.addFirst(entry);
            while (CATEGORY_REDO.size() > maxDepth) CATEGORY_REDO.removeLast();
        }
        if (("per_player".equals(mode) || "both".equals(mode)) && entry.playerUuid() != null) {
            Deque<CategoryUndoEntry> stack = CATEGORY_PLAYER_REDO.computeIfAbsent(entry.playerUuid(), k -> new ArrayDeque<>());
            stack.addFirst(entry);
            while (stack.size() > maxDepth) stack.removeLast();
        }
    }

    public static synchronized CategoryUndoEntry popCategoryRedo(UUID playerUuid) {
        String mode = CustomBlocksConfig.undoMode;
        if ("per_player".equals(mode)) {
            Deque<CategoryUndoEntry> s = CATEGORY_PLAYER_REDO.get(playerUuid);
            return s != null ? s.pollFirst() : null;
        } else if ("global".equals(mode)) {
            return CATEGORY_REDO.pollFirst();
        } else {
            Deque<CategoryUndoEntry> s = CATEGORY_PLAYER_REDO.get(playerUuid);
            CategoryUndoEntry e = s != null ? s.pollFirst() : null;
            if (e != null) {
                CATEGORY_REDO.removeFirstOccurrence(e);
                return e;
            }
            return CATEGORY_REDO.pollFirst();
        }
    }

    /** Clear all stacks (used on reload). */
    public static synchronized void clearAll() {
        GLOBAL_UNDO.clear();
        GLOBAL_REDO.clear();
        PLAYER_UNDO.clear();
        PLAYER_REDO.clear();
        CATEGORY_UNDO.clear();
        CATEGORY_REDO.clear();
        CATEGORY_PLAYER_UNDO.clear();
        CATEGORY_PLAYER_REDO.clear();
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    /** Remove a player's stacks (on disconnect). */
    public static synchronized void clearPlayer(UUID playerUuid) {
        PLAYER_UNDO.remove(playerUuid);
        PLAYER_REDO.remove(playerUuid);
        CATEGORY_PLAYER_UNDO.remove(playerUuid);
        CATEGORY_PLAYER_REDO.remove(playerUuid);
    }

    public static synchronized List<UndoEntry> getUndoEntries(UUID playerUuid, int max) {
        String mode = CustomBlocksConfig.undoMode;
        Deque<UndoEntry> stack;
        if ("per_player".equals(mode)) stack = PLAYER_UNDO.get(playerUuid);
        else if ("global".equals(mode)) stack = GLOBAL_UNDO;
        else { stack = PLAYER_UNDO.get(playerUuid); if (stack == null || stack.isEmpty()) stack = GLOBAL_UNDO; }
        if (stack == null) return List.of();
        List<UndoEntry> result = new ArrayList<>();
        for (UndoEntry e : stack) { result.add(e); if (result.size() >= max) break; }
        return result;
    }

    public static synchronized List<UndoEntry> getRedoEntries(UUID playerUuid, int max) {
        String mode = CustomBlocksConfig.undoMode;
        Deque<UndoEntry> stack;
        if ("per_player".equals(mode)) stack = PLAYER_REDO.get(playerUuid);
        else if ("global".equals(mode)) stack = GLOBAL_REDO;
        else { stack = PLAYER_REDO.get(playerUuid); if (stack == null || stack.isEmpty()) stack = GLOBAL_REDO; }
        if (stack == null) return List.of();
        List<UndoEntry> result = new ArrayList<>();
        for (UndoEntry e : stack) { result.add(e); if (result.size() >= max) break; }
        return result;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static void pushTo(Deque<UndoEntry> stack, UndoEntry entry, int maxDepth) {
        stack.addFirst(entry);
        while (stack.size() > maxDepth) stack.removeLast();
    }
}
