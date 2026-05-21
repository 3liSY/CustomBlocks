package com.customblocks.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.customblocks.CustomBlocksConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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

    /**
     * Phase 9.1 — optional callback invoked after every successful push.
     * Arguments: playerUuid (may be null for console), description string.
     * Registered externally (e.g., in CustomBlocksMod) to send action-bar flashes.
     */
    public static volatile java.util.function.BiConsumer<java.util.UUID, String> onUndoPushed = null;

    // ── Constants ────────────────────────────────────────────────────────────

    /** 1.28 — directory where disk-backed undo snapshots are stored. */
    static final String SNAPSHOT_DIR = "config/customblocks/undo_snapshots";
    /** 1.28 — maximum total snapshot directory size before forced eviction (200 MB). */
    private static final long MAX_SNAPSHOT_SIZE_BYTES = 200L * 1024 * 1024;

    // ── 1.28 — UndoDelta sealed interface ────────────────────────────────────

    /**
     * Sealed delta hierarchy for the 1.28 delta-differential undo system.
     * Stored internally in the undo/redo stacks; converted to {@link UndoEntry} on pop.
     */
    public sealed interface UndoDelta permits
            UndoManager.MetaDelta, UndoManager.TextureDelta, UndoManager.FaceDelta,
            UndoManager.AnimDelta, UndoManager.ShapeDelta,
            UndoManager.SlotCreated, UndoManager.SlotDeleted {
        String customId();
        String description();
        UUID   playerUuid();
    }

    /** Metadata-only mutation (rename, hardness, light, sound, collision, hologram). RAM-only. */
    @SuppressFBWarnings({"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public record MetaDelta(
            String customId, UUID playerUuid,
            String prevName, float prevHardness, int prevLight, String prevSound,
            boolean prevNoCollision, String prevHologramText, String prevAnimMeta,
            List<SlotData.ShapeBox> prevShapeBoxes) implements UndoDelta {
        public String description() { return "meta"; }
    }

    /** Texture retexture / resize — old texture bytes on disk (~60 bytes RAM). */
    public record TextureDelta(String customId, UUID playerUuid,
                               String snapshotPath, String descLabel) implements UndoDelta {
        public String description() { return descLabel; }
    }

    /** Face texture change — old face bytes on disk (null snapshotPath = face was absent). */
    public record FaceDelta(String customId, UUID playerUuid,
                            String face, String snapshotPath, String descLabel) implements UndoDelta {
        public String description() { return descLabel; }
    }

    /** Animation-meta-only change. RAM-only. */
    public record AnimDelta(String customId, UUID playerUuid, String prevMcmeta) implements UndoDelta {
        public String description() { return "anim"; }
    }

    /** Shape-boxes-only change. RAM-only. */
    @SuppressFBWarnings({"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public record ShapeDelta(String customId, UUID playerUuid,
                             List<SlotData.ShapeBox> prevBoxes) implements UndoDelta {
        public String description() { return "shape"; }
    }

    /** Block was created — undo = delete. No snapshot needed. */
    public record SlotCreated(String customId, UUID playerUuid) implements UndoDelta {
        public String description() { return "create"; }
    }

    /** Block was deleted — full slot data on disk (~60+ bytes RAM). */
    public record SlotDeleted(String customId, UUID playerUuid,
                              String snapshotPath) implements UndoDelta {
        public String description() { return "delete"; }
    }

    // ── Undo entry record (public API — unchanged externally) ─────────────────

    /**
     * @param customId      block ID affected
     * @param previousState snapshot before the change (null = was a creation → undo = delete)
     * @param description   human-readable label ("retexture", "setglow", "delete", …)
     * @param wasDeleted    true if the block was deleted (undo = re-create)
     * @param playerUuid    UUID of the player who performed the action (null for system actions)
     * @param snapshotPath  1.28 — path to disk snapshot (null if RAM-only entry)
     */
    public record UndoEntry(
            String customId,
            SlotData previousState,
            String description,
            boolean wasDeleted,
            UUID playerUuid,
            String snapshotPath
    ) {
        /** Convenience: entry without player UUID or snapshot path. */
        public UndoEntry(String customId, SlotData previousState, String description, boolean wasDeleted) {
            this(customId, previousState, description, wasDeleted, null, null);
        }
        /** Convenience: entry with player UUID, no snapshot path. */
        public UndoEntry(String customId, SlotData previousState, String description, boolean wasDeleted, UUID playerUuid) {
            this(customId, previousState, description, wasDeleted, playerUuid, null);
        }
    }

    // ── Stacks (1.28: now store UndoDelta internally) ────────────────────────

    private static final Deque<UndoDelta> GLOBAL_UNDO = new ArrayDeque<>();
    private static final Deque<UndoDelta> GLOBAL_REDO = new ArrayDeque<>();

    private static final Map<UUID, Deque<UndoDelta>> PLAYER_UNDO = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<UndoDelta>> PLAYER_REDO = new ConcurrentHashMap<>();

    // ── Push ─────────────────────────────────────────────────────────────────

    /**
     * Push an undo entry. Depending on config mode, pushes to global and/or player stack.
     * 1.28: internally converts to {@link UndoDelta} and writes texture snapshots to disk.
     */
    public static synchronized void pushUndo(UndoEntry entry) {
        String mode = CustomBlocksConfig.undoMode;
        int maxDepth = CustomBlocksConfig.maxUndoDepth;
        UndoDelta delta = toDelta(entry);

        if ("global".equals(mode) || "both".equals(mode)) {
            pushTo(GLOBAL_UNDO, delta, maxDepth);
            clearDeltaStack(GLOBAL_REDO);
        }
        if (("per_player".equals(mode) || "both".equals(mode)) && entry.playerUuid() != null) {
            Deque<UndoDelta> stack = PLAYER_UNDO.computeIfAbsent(entry.playerUuid(), k -> new ArrayDeque<>());
            pushTo(stack, delta, maxDepth);
            clearDeltaStack(PLAYER_REDO.computeIfAbsent(entry.playerUuid(), k -> new ArrayDeque<>()));
        }
        // Phase 9.1 — notify callback (action-bar flash + 80% warning)
        java.util.function.BiConsumer<java.util.UUID, String> cb = onUndoPushed;
        if (cb != null && entry.playerUuid() != null) {
            cb.accept(entry.playerUuid(), entry.description());
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
        UndoDelta d = GLOBAL_UNDO.pollFirst();
        return d != null ? toEntry(d) : null;
    }

    /** Pop from the global redo stack. Returns null if empty. */
    public static synchronized UndoEntry popGlobalRedo() {
        UndoDelta d = GLOBAL_REDO.pollFirst();
        return d != null ? toEntry(d) : null;
    }

    /** Pop from a player's undo stack. Returns null if empty. */
    public static synchronized UndoEntry popPlayerUndo(UUID playerUuid) {
        Deque<UndoDelta> stack = PLAYER_UNDO.get(playerUuid);
        if (stack == null) return null;
        UndoDelta d = stack.pollFirst();
        return d != null ? toEntry(d) : null;
    }

    /** Pop from a player's redo stack. Returns null if empty. */
    public static synchronized UndoEntry popPlayerRedo(UUID playerUuid) {
        Deque<UndoDelta> stack = PLAYER_REDO.get(playerUuid);
        if (stack == null) return null;
        UndoDelta d = stack.pollFirst();
        return d != null ? toEntry(d) : null;
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
            Deque<UndoDelta> pstack = PLAYER_UNDO.get(playerUuid);
            UndoDelta pd = pstack != null ? pstack.pollFirst() : null;
            if (pd != null) {
                GLOBAL_UNDO.removeIf(d -> d == pd);
                return toEntry(pd);
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
            Deque<UndoDelta> pstack = PLAYER_REDO.get(playerUuid);
            UndoDelta pd = pstack != null ? pstack.pollFirst() : null;
            if (pd != null) {
                GLOBAL_REDO.removeIf(d -> d == pd);
                return toEntry(pd);
            }
            return popGlobalRedo();
        }
    }

    // ── Push to redo (for undo operations) ───────────────────────────────────

    public static synchronized void pushRedo(UndoEntry entry) {
        String mode = CustomBlocksConfig.undoMode;
        int maxDepth = CustomBlocksConfig.maxUndoDepth;
        UndoDelta delta = toDelta(entry);

        if ("global".equals(mode) || "both".equals(mode)) {
            pushTo(GLOBAL_REDO, delta, maxDepth);
        }
        if (("per_player".equals(mode) || "both".equals(mode)) && entry.playerUuid() != null) {
            Deque<UndoDelta> stack = PLAYER_REDO.computeIfAbsent(entry.playerUuid(), k -> new ArrayDeque<>());
            pushTo(stack, delta, maxDepth);
        }
    }

    /**
     * Push to undo without clearing redo (used during redo operations).
     */
    public static synchronized void pushUndoForRedo(UndoEntry entry) {
        String mode = CustomBlocksConfig.undoMode;
        int maxDepth = CustomBlocksConfig.maxUndoDepth;
        UndoDelta delta = toDelta(entry);

        if ("global".equals(mode) || "both".equals(mode)) {
            pushTo(GLOBAL_UNDO, delta, maxDepth);
        }
        if (("per_player".equals(mode) || "both".equals(mode)) && entry.playerUuid() != null) {
            Deque<UndoDelta> stack = PLAYER_UNDO.computeIfAbsent(entry.playerUuid(), k -> new ArrayDeque<>());
            pushTo(stack, delta, maxDepth);
        }
    }

    // ── Query ────────────────────────────────────────────────────────────────

    public static synchronized int globalUndoSize() { return GLOBAL_UNDO.size(); }
    public static synchronized int globalRedoSize() { return GLOBAL_REDO.size(); }

    public static synchronized int playerUndoSize(UUID playerUuid) {
        Deque<UndoDelta> stack = PLAYER_UNDO.get(playerUuid);
        return stack != null ? stack.size() : 0;
    }

    public static synchronized int playerRedoSize(UUID playerUuid) {
        Deque<UndoDelta> stack = PLAYER_REDO.get(playerUuid);
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
            Deque<UndoDelta> stack = PLAYER_UNDO.get(playerUuid);
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
            Deque<UndoDelta> stack = PLAYER_REDO.get(playerUuid);
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
    @SuppressFBWarnings({"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
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

    /** Clear all stacks and snapshot files (used on reload). */
    public static synchronized void clearAll() {
        clearDeltaStack(GLOBAL_UNDO);
        clearDeltaStack(GLOBAL_REDO);
        for (Deque<UndoDelta> s : PLAYER_UNDO.values()) clearDeltaStack(s);
        for (Deque<UndoDelta> s : PLAYER_REDO.values()) clearDeltaStack(s);
        PLAYER_UNDO.clear();
        PLAYER_REDO.clear();
        CATEGORY_UNDO.clear();
        CATEGORY_REDO.clear();
        CATEGORY_PLAYER_UNDO.clear();
        CATEGORY_PLAYER_REDO.clear();
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    /** Remove a player's stacks (on disconnect). Deletes that player's snapshot files. */
    public static synchronized void clearPlayer(UUID playerUuid) {
        Deque<UndoDelta> pu = PLAYER_UNDO.remove(playerUuid);
        Deque<UndoDelta> pr = PLAYER_REDO.remove(playerUuid);
        if (pu != null) clearDeltaStack(pu);
        if (pr != null) clearDeltaStack(pr);
        CATEGORY_PLAYER_UNDO.remove(playerUuid);
        CATEGORY_PLAYER_REDO.remove(playerUuid);
    }

    /** Lightweight display-only UndoEntry — no disk I/O, no previousState loaded. */
    private static UndoEntry deltaToDisplay(UndoDelta d) {
        return new UndoEntry(d.customId(), null, d.description(), d instanceof SlotDeleted, d.playerUuid());
    }

    public static synchronized List<UndoEntry> getUndoEntries(UUID playerUuid, int max) {
        return getUndoEntries(playerUuid, 0, max);
    }

    public static synchronized List<UndoEntry> getRedoEntries(UUID playerUuid, int max) {
        return getRedoEntries(playerUuid, 0, max);
    }

    public static synchronized List<UndoEntry> getUndoEntries(UUID playerUuid, int offset, int max) {
        String mode = CustomBlocksConfig.undoMode;
        Deque<UndoDelta> stack;
        if ("per_player".equals(mode)) stack = PLAYER_UNDO.get(playerUuid);
        else if ("global".equals(mode)) stack = GLOBAL_UNDO;
        else { stack = PLAYER_UNDO.get(playerUuid); if (stack == null || stack.isEmpty()) stack = GLOBAL_UNDO; }
        if (stack == null) return List.of();
        List<UndoEntry> result = new ArrayList<>();
        int skipped = 0;
        for (UndoDelta d : stack) {
            if (skipped++ < offset) continue;
            result.add(deltaToDisplay(d));
            if (result.size() >= max) break;
        }
        return result;
    }

    public static synchronized List<UndoEntry> getRedoEntries(UUID playerUuid, int offset, int max) {
        String mode = CustomBlocksConfig.undoMode;
        Deque<UndoDelta> stack;
        if ("per_player".equals(mode)) stack = PLAYER_REDO.get(playerUuid);
        else if ("global".equals(mode)) stack = GLOBAL_REDO;
        else { stack = PLAYER_REDO.get(playerUuid); if (stack == null || stack.isEmpty()) stack = GLOBAL_REDO; }
        if (stack == null) return List.of();
        List<UndoEntry> result = new ArrayList<>();
        int skipped = 0;
        for (UndoDelta d : stack) {
            if (skipped++ < offset) continue;
            result.add(deltaToDisplay(d));
            if (result.size() >= max) break;
        }
        return result;
    }

    // ── Internal push / clear helpers ────────────────────────────────────────

    /** Push a delta; evict (and delete snapshot of) oldest entry when over maxDepth. */
    private static void pushTo(Deque<UndoDelta> stack, UndoDelta delta, int maxDepth) {
        stack.addFirst(delta);
        while (stack.size() > maxDepth) {
            UndoDelta evicted = stack.removeLast();
            deleteSnapshotOfDelta(evicted);
        }
    }

    /** Clear a delta stack, deleting all referenced snapshot files first. */
    private static void clearDeltaStack(Deque<UndoDelta> stack) {
        for (UndoDelta d : stack) deleteSnapshotOfDelta(d);
        stack.clear();
    }

    /** Delete the snapshot file(s) for a delta (no-op for RAM-only delta types). */
    private static void deleteSnapshotOfDelta(UndoDelta d) {
        switch (d) {
            case TextureDelta td -> deleteSnapshot(td.snapshotPath());
            case FaceDelta    fd -> deleteSnapshot(fd.snapshotPath());
            case SlotDeleted  sd -> deleteSnapshot(sd.snapshotPath());
            default -> { /* MetaDelta / AnimDelta / ShapeDelta / SlotCreated — no disk file */ }
        }
    }

    // ── toDelta / toEntry ────────────────────────────────────────────────────

    /**
     * Convert a public {@link UndoEntry} to an internal {@link UndoDelta}.
     * Texture-bearing deltas write bytes to disk and store only the path.
     */
    private static UndoDelta toDelta(UndoEntry entry) {
        String   id   = entry.customId();
        UUID     who  = entry.playerUuid();
        SlotData prev = entry.previousState();
        String   desc = entry.description() != null ? entry.description() : "";

        // Re-push with an existing snapshot path — honour the stored path
        if (entry.snapshotPath() != null) {
            if (entry.wasDeleted() || "delete".equals(desc))
                return new SlotDeleted(id, who, entry.snapshotPath());
            if (isFaceDesc(desc))
                return new FaceDelta(id, who, extractFace(desc, prev), entry.snapshotPath(), desc);
            return new TextureDelta(id, who, entry.snapshotPath(), desc);
        }

        if ("create".equals(desc))
            return new SlotCreated(id, who);

        if (entry.wasDeleted() || "delete".equals(desc)) {
            String path = prev != null ? writeSlotSnapshot(prev, id, who) : null;
            return new SlotDeleted(id, who, path);
        }
        if (isTextureDesc(desc)) {
            byte[] bytes = prev != null ? prev.texture : null;
            String path  = bytes != null ? writeSnapshot(bytes, id, who, desc) : null;
            return new TextureDelta(id, who, path, desc);
        }
        if (isFaceDesc(desc)) {
            String face  = extractFace(desc, prev);
            byte[] bytes = (prev != null && face != null) ? prev.faceTextures.get(face) : null;
            String path  = bytes != null ? writeSnapshot(bytes, id, who, "face_" + face) : null;
            return new FaceDelta(id, who, face, path, desc);
        }
        if ("anim".equals(desc))
            return new AnimDelta(id, who, prev != null ? prev.animMeta : null);
        if ("shape".equals(desc))
            return new ShapeDelta(id, who, prev != null ? prev.shapeBoxes : null);

        // Default → MetaDelta (all metadata fields; RAM-only)
        return new MetaDelta(id, who,
                prev != null ? prev.displayName  : null,
                prev != null ? prev.hardness     : 0f,
                prev != null ? prev.lightLevel   : 0,
                prev != null ? prev.soundType    : null,
                prev != null && prev.noCollision,
                prev != null ? prev.hologramText : null,
                prev != null ? prev.animMeta     : null,
                prev != null ? prev.shapeBoxes   : null);
    }

    private static boolean isTextureDesc(String d) {
        return "retexture".equals(d) || "resize".equals(d) || d.startsWith("texture");
    }

    private static boolean isFaceDesc(String d) {
        return d.startsWith("setface") || d.startsWith("clearface") || "clearallfaces".equals(d);
    }

    private static String extractFace(String desc, SlotData prev) {
        int colon = desc.indexOf(':');
        if (colon >= 0) return desc.substring(colon + 1);
        if (prev != null && prev.hasFaces()) return prev.faceTextures.keySet().iterator().next();
        return "top";
    }

    /**
     * Convert an internal {@link UndoDelta} back to a full {@link UndoEntry}.
     * Disk-backed deltas load snapshot bytes and reconstruct the full {@link SlotData}.
     */
    private static UndoEntry toEntry(UndoDelta delta) {
        return switch (delta) {
            case SlotCreated sc ->
                new UndoEntry(sc.customId(), null, "create", false, sc.playerUuid());
            case SlotDeleted sd -> {
                SlotData prev = sd.snapshotPath() != null ? loadSlotSnapshot(sd.snapshotPath()) : null;
                yield new UndoEntry(sd.customId(), prev, "delete", true, sd.playerUuid(), sd.snapshotPath());
            }
            case TextureDelta td -> {
                SlotData current = SlotManager.getById(td.customId());
                SlotData prev = null;
                if (current != null && td.snapshotPath() != null) {
                    byte[] bytes = readSnapshot(td.snapshotPath());
                    prev = current.withTexture(bytes);
                }
                yield new UndoEntry(td.customId(), prev, td.descLabel(), false, td.playerUuid(), td.snapshotPath());
            }
            case FaceDelta fd -> {
                SlotData current = SlotManager.getById(fd.customId());
                SlotData prev = null;
                if (current != null) {
                    if (fd.snapshotPath() != null) {
                        byte[] bytes = readSnapshot(fd.snapshotPath());
                        prev = bytes != null
                                ? current.withFaceTexture(fd.face(), bytes)
                                : current.withoutFaceTexture(fd.face());
                    } else {
                        prev = current.withoutFaceTexture(fd.face());
                    }
                }
                yield new UndoEntry(fd.customId(), prev, fd.descLabel(), false, fd.playerUuid(), fd.snapshotPath());
            }
            case AnimDelta ad -> {
                SlotData current = SlotManager.getById(ad.customId());
                SlotData prev = current != null ? current.withAnimMeta(ad.prevMcmeta()) : null;
                yield new UndoEntry(ad.customId(), prev, "anim", false, ad.playerUuid());
            }
            case ShapeDelta sd -> {
                SlotData current = SlotManager.getById(sd.customId());
                SlotData prev = current != null ? current.withShapeBoxes(sd.prevBoxes()) : null;
                yield new UndoEntry(sd.customId(), prev, "shape", false, sd.playerUuid());
            }
            case MetaDelta md -> {
                SlotData current = SlotManager.getById(md.customId());
                SlotData prev = null;
                if (current != null) {
                    prev = current
                            .withDisplayName(md.prevName())
                            .withHardness(md.prevHardness())
                            .withLightLevel(md.prevLight())
                            .withSoundType(md.prevSound())
                            .withNoCollision(md.prevNoCollision())
                            .withHologramText(md.prevHologramText())
                            .withAnimMeta(md.prevAnimMeta())
                            .withShapeBoxes(md.prevShapeBoxes());
                }
                yield new UndoEntry(md.customId(), prev, "meta", false, md.playerUuid());
            }
        };
    }

    // ── Snapshot I/O ─────────────────────────────────────────────────────────

    /** Write raw bytes (texture/face) to SNAPSHOT_DIR. Returns path or null on failure. */
    private static String writeSnapshot(byte[] bytes, String customId, UUID who, String label) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            Path dir = Path.of(SNAPSHOT_DIR);
            Files.createDirectories(dir);
            String name = sanitize(customId) + "_" + sanitize(label) + "_"
                    + (who != null ? who.toString().replace("-", "") : "sys")
                    + "_" + System.nanoTime() + ".dat";
            Path out = dir.resolve(name);
            Files.write(out, bytes);
            return out.toString();
        } catch (IOException e) {
            LOGGER.warn("[UndoManager] Failed to write snapshot for {}: {}", customId, e.getMessage());
            return null;
        }
    }

    /** Write full slot data (including texture bytes inlined as Base64) to SNAPSHOT_DIR. */
    private static String writeSlotSnapshot(SlotData d, String customId, UUID who) {
        try {
            Path dir = Path.of(SNAPSHOT_DIR);
            Files.createDirectories(dir);
            String name = "slot_" + sanitize(customId) + "_"
                    + (who != null ? who.toString().replace("-", "") : "sys")
                    + "_" + System.nanoTime() + ".json";
            Path out = dir.resolve(name);
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("index",       d.index);
            obj.addProperty("customId",    d.customId);
            obj.addProperty("displayName", d.displayName);
            obj.addProperty("lightLevel",  d.lightLevel);
            obj.addProperty("hardness",    d.hardness);
            obj.addProperty("soundType",   d.soundType);
            if (d.animMeta   != null) obj.addProperty("animMeta",    d.animMeta);
            if (d.noCollision)        obj.addProperty("noCollision",  true);
            if (d.hologramText != null) obj.addProperty("hologramText", d.hologramText);
            if (d.lastEditedAt > 0)   obj.addProperty("lastEditedAt", d.lastEditedAt);
            if (d.texture != null && d.texture.length > 0)
                obj.addProperty("texture", java.util.Base64.getEncoder().encodeToString(d.texture));
            if (d.hasFaces()) {
                com.google.gson.JsonObject faces = new com.google.gson.JsonObject();
                d.faceTextures.forEach((k, v) ->
                        faces.addProperty(k, java.util.Base64.getEncoder().encodeToString(v)));
                obj.add("faceTextures", faces);
            }
            if (d.isShaped()) {
                com.google.gson.JsonArray boxes = new com.google.gson.JsonArray();
                for (SlotData.ShapeBox b : d.shapeBoxes) boxes.add(b.toSerialString());
                obj.add("shapeBoxes", boxes);
            }
            Files.writeString(out, obj.toString(), StandardCharsets.UTF_8);
            return out.toString();
        } catch (IOException e) {
            LOGGER.warn("[UndoManager] Failed to write slot snapshot for {}: {}", customId, e.getMessage());
            return null;
        }
    }

    /** Read raw bytes from a snapshot file. Returns null on missing / error. */
    private static byte[] readSnapshot(String path) {
        if (path == null) return null;
        try { return Files.readAllBytes(Path.of(path)); }
        catch (IOException e) {
            LOGGER.warn("[UndoManager] Failed to read snapshot {}: {}", path, e.getMessage());
            return null;
        }
    }

    /** Load a full SlotData from a JSON slot-snapshot file. Returns null on missing / error. */
    private static SlotData loadSlotSnapshot(String path) {
        if (path == null) return null;
        try {
            String json = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            int    index       = obj.get("index").getAsInt();
            String customId    = obj.get("customId").getAsString();
            String displayName = obj.has("displayName") ? obj.get("displayName").getAsString() : customId;
            int    lightLevel  = obj.has("lightLevel")  ? obj.get("lightLevel").getAsInt()     : 0;
            float  hardness    = obj.has("hardness")    ? obj.get("hardness").getAsFloat()      : 1.5f;
            String soundType   = obj.has("soundType")   ? obj.get("soundType").getAsString()   : "stone";
            String animMeta    = obj.has("animMeta")    ? obj.get("animMeta").getAsString()    : null;
            boolean noCollision= obj.has("noCollision") && obj.get("noCollision").getAsBoolean();
            String hologramText= obj.has("hologramText")? obj.get("hologramText").getAsString(): null;
            long lastEditedAt  = obj.has("lastEditedAt")? obj.get("lastEditedAt").getAsLong()  : 0L;
            byte[] texture = null;
            if (obj.has("texture")) {
                String b64 = obj.get("texture").getAsString();
                if (!b64.isEmpty()) texture = java.util.Base64.getDecoder().decode(b64);
            }
            Map<String, byte[]> faceTextures = null;
            if (obj.has("faceTextures")) {
                faceTextures = new java.util.HashMap<>();
                com.google.gson.JsonObject ft = obj.getAsJsonObject("faceTextures");
                for (Map.Entry<String, com.google.gson.JsonElement> e : ft.entrySet()) {
                    String b64 = e.getValue().getAsString();
                    if (!b64.isEmpty()) faceTextures.put(e.getKey(), java.util.Base64.getDecoder().decode(b64));
                }
            }
            List<SlotData.ShapeBox> shapeBoxes = null;
            if (obj.has("shapeBoxes")) {
                shapeBoxes = new ArrayList<>();
                for (com.google.gson.JsonElement el : obj.getAsJsonArray("shapeBoxes"))
                    shapeBoxes.add(SlotData.ShapeBox.parse(el.getAsString()));
            }
            return SlotData.createTrustedFull(index, customId, displayName, texture,
                    lightLevel, hardness, soundType, faceTextures, animMeta,
                    shapeBoxes, noCollision, null, hologramText, lastEditedAt);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[UndoManager] Failed to load slot snapshot {}: {}", path, e.getMessage());
            return null;
        }
    }

    /** Delete a snapshot file; no-op if path is null or file missing. */
    private static void deleteSnapshot(String path) {
        if (path == null) return;
        try { Files.deleteIfExists(Path.of(path)); }
        catch (IOException e) {
            LOGGER.warn("[UndoManager] Failed to delete snapshot {}: {}", path, e.getMessage());
        }
    }

    /**
     * 1.28 — Delete all files in SNAPSHOT_DIR (orphan cleanup at startup).
     * Called from {@link SlotManager#loadAll()} before any stacks are populated,
     * so every file in the directory is a leftover from a previous (possibly crashed) run.
     */
    public static void cleanupOrphans() {
        Path dir = Path.of(SNAPSHOT_DIR);
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            files.forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e) { LOGGER.debug("[UndoManager] cleanupOrphans skip {}: {}", p, e.getMessage()); }
            });
        } catch (IOException e) {
            LOGGER.warn("[UndoManager] cleanupOrphans failed: {}", e.getMessage());
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "null";
        String safe = s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return safe.substring(0, Math.min(safe.length(), 32));
    }
}
